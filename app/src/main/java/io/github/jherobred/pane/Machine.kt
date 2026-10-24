package io.github.jherobred.pane

import android.app.Activity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MachineScreen(onHome: () -> Unit) {
    val ctx = LocalContext.current
    val vm by VmService.state.collectAsState()
    var view by remember { mutableStateOf<VncView?>(null) }
    var booted by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf(true) }
    var keysRow by remember { mutableStateOf(false) }
    var powerMenu by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var trackpad by remember { mutableStateOf(true) }
    var sticky by remember { mutableStateOf(emptySet<Int>()) }
    var installed by remember { mutableStateOf(WindowsVm.isInstalled(ctx)) }
    val scope = rememberCoroutineScope()

    FullScreen()
    LaunchedEffect(vm) {
        if (vm is VmState.Stopped) { delay(400); onHome() }
    }
    LaunchedEffect(bar, keysRow, powerMenu) {
        if (bar && !keysRow && !powerMenu) { delay(6000); bar = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                VncView(c).apply {
                    onFirstFrame = { booted = true }
                    onStickyChanged = { sticky = it }
                    connect(File(WindowsVm.dir(c), "vnc.sock").path)
                    view = this
                    requestFocus()
                }
            },
            update = { it.trackpad = trackpad },
            modifier = Modifier.fillMaxSize().imePadding(),
        )

        // Boot screen until Windows draws something.
        AnimatedVisibility(!booted || vm is VmState.Failed, enter = fadeIn(), exit = fadeOut(tween(900))) {
            Screen {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (vm is VmState.Failed) {
                        FailurePanel((vm as VmState.Failed).log, onHome)
                    } else {
                        BootLoader()
                        Spacer(Modifier.height(28.dp))
                        AnimatedContent(
                            if (vm is VmState.Running) "Booting Windows 11" else "Starting the engine",
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "status",
                        ) { Text(it, color = Pane.Ink, fontSize = 20.sp, fontWeight = FontWeight.Light) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Windows is emulated on this device, so the first screen can take a minute.",
                            color = Pane.Faint, fontSize = 14.sp,
                        )
                    }
                }
            }
        }

        // Top handle and toolbar.
        Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(bar, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.glass(RoundedCornerShape(24.dp), strong = true).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconChip(Glyphs.Back) { onHome() }
                        IconChip(Glyphs.Keyboard, active = keyboard) {
                            val v = view ?: return@IconChip
                            val imm = ctx.getSystemService(InputMethodManager::class.java)
                            keyboard = !keyboard
                            if (keyboard) { v.requestFocus(); imm.showSoftInput(v, 0) } else imm.hideSoftInputFromWindow(v.windowToken, 0)
                        }
                        IconChip(if (trackpad) Glyphs.Trackpad else Glyphs.Touch, active = !trackpad) { trackpad = !trackpad }
                        IconChip(Glyphs.Keys, active = keysRow) { keysRow = !keysRow; powerMenu = false }
                        IconChip(Glyphs.Power, active = powerMenu) { powerMenu = !powerMenu; keysRow = false }
                    }
                    Text(
                        if (trackpad) "Trackpad: drag to move, tap to click, two fingers to scroll or right-click"
                        else "Touch: tap to click, hold to right-click, drag to drag",
                        color = Pane.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    AnimatedVisibility(keysRow, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(
                            Modifier.padding(top = 8.dp).widthIn(max = 720.dp).horizontalScroll(rememberScrollState())
                                .glass(RoundedCornerShape(20.dp), strong = true).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val v = view
                            KeyChip("Esc") { v?.tap(Keys.ESC) }
                            KeyChip("Tab") { v?.tap(Keys.TAB) }
                            for ((label, sc) in listOf("Win" to Keys.WIN, "Ctrl" to Keys.CTRL, "Alt" to Keys.ALT, "Shift" to Keys.SHIFT)) {
                                KeyChip(label, active = sc in sticky) { v?.toggleSticky(sc) }
                            }
                            KeyChip("Del") { v?.tap(Keys.DELETE) }
                            KeyChip("←") { v?.tap(Keys.LEFT) }
                            KeyChip("↑") { v?.tap(Keys.UP) }
                            KeyChip("↓") { v?.tap(Keys.DOWN) }
                            KeyChip("→") { v?.tap(Keys.RIGHT) }
                            KeyChip("Enter") { v?.tap(Keys.ENTER) }
                            KeyChip("Ctrl+Alt+Del") { v?.chord(Keys.CTRL, Keys.ALT, Keys.DELETE) }
                        }
                    }
                    AnimatedVisibility(powerMenu, enter = scaleIn(initialScale = 0.9f) + fadeIn(), exit = fadeOut()) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Shut down", icon = Glyphs.Power) { powerMenu = false; VmService.shutdown(ctx) }
                            GhostButton("Force stop", icon = Glyphs.Close, color = Pane.Danger) { powerMenu = false; VmService.kill(ctx) }
                        }
                    }
                }
            }
            if (!bar) {
                Box(
                    Modifier.padding(top = 2.dp).pressable { bar = true }.glass(RoundedCornerShape(50)).width(72.dp).height(22.dp),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(32.dp).height(4.dp).background(Pane.Dim, RoundedCornerShape(2.dp))) }
            }
        }

        // During setup: how long it has been, and a way to say it's done.
        if (!installed && vm is VmState.Running) {
            val since = (vm as VmState.Running).since
            val minutes by produceState(0L, since) { while (true) { value = (System.currentTimeMillis() - since) / 60_000; delay(15_000) } }
            Row(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)
                    .glass(RoundedCornerShape(22.dp), strong = true).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Installing Windows · $minutes min", color = Pane.Ink, fontSize = 14.sp)
                Spacer(Modifier.width(14.dp))
                // If the firmware ends up at its Shell> prompt, start the installer from the ISO by hand,
                // then press Enter through its "Press any key to boot from CD or DVD".
                GhostButton("At Shell>? Boot installer", icon = Glyphs.Disc) {
                    scope.launch {
                        view?.type("fs0:\\efi\\boot\\bootaa64.efi\n")
                        repeat(15) { delay(1500); view?.tap(Keys.ENTER) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                GhostButton("I see the desktop", icon = Glyphs.Check) { WindowsVm.markInstalled(ctx); installed = true }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.pressable(onClick = onClick).glass(RoundedCornerShape(14.dp), strong = active)
            .background(if (active) Pane.Accent.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Pane.Accent else Pane.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun FailurePanel(log: String, onHome: () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).glass(RoundedCornerShape(28.dp), strong = true).padding(24.dp)) {
        Text("Windows stopped", color = Pane.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            log, color = Pane.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
            modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Back to Pane", icon = Glyphs.Back) { VmService.state.value = VmState.Stopped; onHome() }
    }
}

/** Edge to edge, bars hidden until swiped in, screen kept on. */
@Composable
private fun FullScreen() {
    val activity = LocalContext.current as Activity
    DisposableEffect(Unit) {
        val w = activity.window
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            c.show(WindowInsetsCompat.Type.systemBars())
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
