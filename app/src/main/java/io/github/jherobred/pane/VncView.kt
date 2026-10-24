package io.github.jherobred.pane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Shows the Windows screen and turns touch, pen, mouse, keyboard and gamepad into PC input. */
class VncView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    /** Trackpad: drag moves the pointer, tap clicks. Direct: tap and drag where you touch. */
    var trackpad = true
    var onFirstFrame: () -> Unit = {}
    var onStickyChanged: (Set<Int>) -> Unit = {}

    @Volatile private var client: RfbClient? = null
    @Volatile private var attached = false
    private val main = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("vnc-render").apply { start() }
    private val render = Handler(renderThread.looper)
    private val dirty = AtomicBoolean(false)
    @Volatile private var surfaceReady = false
    private var bitmap: Bitmap? = null
    private var shown = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    @Volatile private var scale = 1f

    // Pointer state, in Windows screen pixels.
    private var cx = 0f
    private var cy = 0f
    private var buttons = 0
    private val sticky = mutableSetOf<Int>()

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        // Otherwise Android tints the whole screen grey once a keyboard is used.
        defaultFocusHighlightEnabled = false
    }

    /** Connects to QEMU's VNC socket and keeps reconnecting until the view goes away. */
    fun connect(socketPath: String) {
        attached = true
        thread(name = "vnc") {
            while (attached) {
                val c = RfbClient(socketPath, onResize = { w, h -> cx = w / 2f; cy = h / 2f; requestRender() }, onFrame = ::requestRender)
                client = c
                runCatching { c.run() }
                client = null
                if (attached) Thread.sleep(500)
            }
        }
    }

    override fun onDetachedFromWindow() {
        attached = false
        client?.close()
        renderThread.quitSafely()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; requestRender() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = requestRender()
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }

    private fun requestRender() {
        if (dirty.compareAndSet(false, true)) render.post(::draw)
    }

    private fun draw() {
        dirty.set(false)
        val c = client ?: return
        val b = synchronized(c) {
            val w = c.width
            val h = c.height
            if (w == 0 || h == 0) return
            val bmp = bitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            bmp.setPixels(c.pixels, 0, w, 0, 0, w, h)
            if (!shown && !(w == 640 && h == 480)) {
                // Before the firmware sets up the screen, QEMU shows a 640x480 "display not initialized"
                // placeholder, then black frames. Wait for real content.
                val p = c.pixels
                if ((0 until p.size step 97).any { p[it] and 0xFFFFFF != 0 }) {
                    shown = true
                    main.post(onFirstFrame)
                }
            }
            bmp
        }
        if (!surfaceReady) return
        val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            val s = min(canvas.width / b.width.toFloat(), canvas.height / b.height.toFloat())
            val w = b.width * s
            val h = b.height * s
            dst.set((canvas.width - w) / 2, (canvas.height - h) / 2, (canvas.width + w) / 2, (canvas.height + h) / 2)
            scale = s
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(b, null, dst, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // ---- Pointer --------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scrollStep = 40 * resources.displayMetrics.density
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var maxPointers = 0
    private var lastTapUp = 0L
    private var dragArmed = false
    private var scrollAcc = 0f

    private fun fbX(x: Float) = ((x - dst.left) / scale).coerceIn(0f, (client?.width ?: 1) - 1f)
    private fun fbY(y: Float) = ((y - dst.top) / scale).coerceIn(0f, (client?.height ?: 1) - 1f)
    private fun send(c: RfbClient, b: Int = buttons) = c.pointer(cx.toInt(), cy.toInt(), b)
    private fun click(c: RfbClient, b: Int) { send(c, b); send(c, 0) }

    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

    /** Two fingers scroll, in either mode. Content follows the fingers. */
    private fun scroll(c: RfbClient, e: MotionEvent) {
        val y = avgY(e)
        scrollAcc += y - lastY
        lastY = y
        if (abs(scrollAcc) > slop) moved = true
        while (abs(scrollAcc) >= scrollStep) {
            click(c, if (scrollAcc > 0) 8 else 16)
            scrollAcc -= if (scrollAcc > 0) scrollStep else -scrollStep
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return true
        when (e.getToolType(0)) {
            MotionEvent.TOOL_TYPE_MOUSE -> mouse(c, e)
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> direct(c, e)
            else -> if (trackpad) trackpad(c, e) else direct(c, e)
        }
        return true
    }

    private fun trackpad(c: RfbClient, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime; startX = e.x; startY = e.y; lastX = e.x; lastY = e.y
                moved = false; maxPointers = 1
                dragArmed = e.eventTime - lastTapUp < 300 // tap, then touch and move: drag
            }
            MotionEvent.ACTION_POINTER_DOWN -> { maxPointers = maxOf(maxPointers, e.pointerCount); lastY = avgY(e); scrollAcc = 0f }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) scroll(c, e) else if (maxPointers == 1) {
                if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                    moved = true
                    if (dragArmed) { buttons = 1; send(c) }
                }
                if (moved) {
                    val k = 1.5f / scale
                    cx = (cx + (e.x - lastX) * k).coerceIn(0f, c.width - 1f)
                    cy = (cy + (e.y - lastY) * k).coerceIn(0f, c.height - 1f)
                    send(c)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val held = e.eventTime - downTime
                when {
                    buttons != 0 -> { buttons = 0; send(c) }
                    moved -> {}
                    maxPointers >= 2 -> click(c, 4) // two-finger tap: right click
                    held >= 500 -> click(c, 4) // long press: right click
                    else -> { click(c, 1); lastTapUp = e.eventTime }
                }
            }
            MotionEvent.ACTION_CANCEL -> if (buttons != 0) { buttons = 0; send(c) }
        }
    }

    private fun direct(c: RfbClient, e: MotionEvent) {
        val pen = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime; startX = e.x; startY = e.y; lastY = e.y
                moved = false; maxPointers = 1
                cx = fbX(e.x); cy = fbY(e.y); send(c, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount); lastY = avgY(e); scrollAcc = 0f
                if (buttons != 0) { buttons = 0; send(c) }
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) scroll(c, e) else if (maxPointers == 1) {
                if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                    moved = true
                    buttons = if (pen && e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) 4 else 1
                    send(c) // press where the finger went down, then drag
                }
                if (moved) { cx = fbX(e.x); cy = fbY(e.y); send(c) }
            }
            MotionEvent.ACTION_UP -> when {
                buttons != 0 -> { buttons = 0; send(c) }
                moved || maxPointers > 1 -> {}
                e.eventTime - downTime >= 500 || (pen && e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) -> click(c, 4)
                else -> click(c, 1)
            }
            MotionEvent.ACTION_CANCEL -> if (buttons != 0) { buttons = 0; send(c) }
        }
    }

    private fun mouse(c: RfbClient, e: MotionEvent) {
        cx = fbX(e.x); cy = fbY(e.y)
        var b = 0
        if (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) b = b or 1
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) b = b or 2
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) b = b or 4
        buttons = b
        send(c)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val c = client ?: return super.onGenericMotionEvent(e)
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK) && e.actionMasked == MotionEvent.ACTION_MOVE) {
            stickX = e.getAxisValue(MotionEvent.AXIS_X)
            stickY = e.getAxisValue(MotionEvent.AXIS_Y)
            stickScroll = e.getAxisValue(MotionEvent.AXIS_RZ)
            if (!ticking) { ticking = true; Choreographer.getInstance().postFrameCallback(stick) }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> { cx = fbX(e.x); cy = fbY(e.y); send(c) }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouse(c, e)
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v != 0f) click(c, if (v > 0) 8 else 16)
                if (h != 0f) click(c, if (h > 0) 64 else 32)
            }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    // ---- Gamepad: left stick moves the pointer, right stick scrolls ------------------------------

    private var stickX = 0f
    private var stickY = 0f
    private var stickScroll = 0f
    private var stickScrollAcc = 0f
    private var ticking = false
    private val stick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val c = client
            if (c == null || (abs(stickX) < 0.15f && abs(stickY) < 0.15f && abs(stickScroll) < 0.3f)) { ticking = false; return }
            cx = (cx + stickX * 14).coerceIn(0f, c.width - 1f)
            cy = (cy + stickY * 14).coerceIn(0f, c.height - 1f)
            send(c)
            stickScrollAcc += stickScroll
            if (abs(stickScrollAcc) > 6) { click(c, if (stickScrollAcc < 0) 8 else 16); stickScrollAcc = 0f }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val gamepadKeys = mapOf(
        KeyEvent.KEYCODE_BUTTON_X to Keys.ESC, KeyEvent.KEYCODE_BUTTON_Y to Keys.ENTER,
        KeyEvent.KEYCODE_BUTTON_START to Keys.WIN, KeyEvent.KEYCODE_BUTTON_SELECT to Keys.TAB,
        KeyEvent.KEYCODE_BUTTON_L1 to 0xC9, KeyEvent.KEYCODE_BUTTON_R1 to 0xD1, // Page Up / Down
    )

    // ---- Keyboard -------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = key(event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = key(event, false) || super.onKeyUp(keyCode, event)

    private fun key(e: KeyEvent, down: Boolean): Boolean {
        val c = client ?: return false
        if (down && e.repeatCount > 0 && KeyEvent.isGamepadButton(e.keyCode)) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> { buttons = if (down) buttons or 1 else buttons and 1.inv(); send(c); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { buttons = if (down) buttons or 4 else buttons and 4.inv(); send(c); return true }
        }
        val sc = gamepadKeys[e.keyCode] ?: Keys.scancode(e.keyCode) ?: return false
        c.key(sc, down)
        if (!down && sc !in Keys.MODIFIERS) releaseSticky()
        return true
    }

    /** Types text with US scancodes, e.g. a command at a firmware prompt. */
    fun type(text: String) = text.forEach { ch -> Keys.forChar(ch)?.let { (sc, shift) -> tap(sc, shift) } }

    /** Taps a key, holding Shift if asked. Releases sticky modifiers afterwards. */
    fun tap(scancode: Int, shift: Boolean = false) {
        val c = client ?: return
        if (shift) c.key(Keys.SHIFT, true)
        c.key(scancode, true)
        c.key(scancode, false)
        if (shift) c.key(Keys.SHIFT, false)
        releaseSticky()
    }

    /** Ctrl, Alt, Shift and Win from the on-screen bar stay down until the next key. */
    fun toggleSticky(scancode: Int) {
        val c = client ?: return
        if (sticky.remove(scancode)) c.key(scancode, false) else { sticky += scancode; c.key(scancode, true) }
        onStickyChanged(sticky.toSet())
    }

    fun chord(vararg scancodes: Int) {
        val c = client ?: return
        scancodes.forEach { c.key(it, true) }
        scancodes.reversed().forEach { c.key(it, false) }
    }

    private fun releaseSticky() {
        if (sticky.isEmpty()) return
        val c = client ?: return
        sticky.forEach { c.key(it, false) }
        sticky.clear()
        main.post { onStickyChanged(emptySet()) }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A password-style field: keyboards send each character at once, without suggestions.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                type(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { tap(Keys.BACKSPACE) }
                repeat(afterLength) { tap(Keys.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                key(event, event.action == KeyEvent.ACTION_DOWN)
                return true
            }
        }
    }
}
