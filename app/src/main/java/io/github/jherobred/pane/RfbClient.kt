package io.github.jherobred.pane

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal VNC (RFB 3.8) client for QEMU's built-in server over a Unix socket.
 * Raw encoding only (it's a local socket), plus QEMU's extensions for scancodes and audio.
 */
class RfbClient(private val socketPath: String, private val onResize: (Int, Int) -> Unit, private val onFrame: () -> Unit) {
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    /** ARGB pixels. Guard reads with `synchronized(this)`. */
    var pixels = IntArray(0)
        private set

    private val socket = LocalSocket()
    private lateinit var out: DataOutputStream
    private var audio: AudioTrack? = null
    @Volatile private var closed = false

    /** Blocks until the connection ends. */
    fun run() {
        socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        val input = DataInputStream(BufferedInputStream(socket.inputStream, 1 shl 18))
        out = DataOutputStream(BufferedOutputStream(socket.outputStream))

        input.readFully(ByteArray(12)) // "RFB 003.008\n"
        out.write("RFB 003.008\n".toByteArray())
        out.flush()
        val types = ByteArray(input.readUnsignedByte()).also(input::readFully)
        check(1.toByte() in types) { "VNC server wants a password" }
        out.writeByte(1) // security: none
        out.flush()
        check(input.readInt() == 0) { "VNC handshake failed" }
        out.writeByte(1) // shared
        out.flush()
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipBytes(16)
        input.skipBytes(input.readInt())

        send {
            // 32-bit little-endian BGRX: reads straight into Android's ARGB ints.
            writeByte(0); write(ByteArray(3))
            writeByte(32); writeByte(24); writeByte(0); writeByte(1)
            writeShort(255); writeShort(255); writeShort(255)
            writeByte(16); writeByte(8); writeByte(0); write(ByteArray(3))
            // Raw, DesktopSize, QEMU extended key event, QEMU audio.
            val encodings = intArrayOf(0, -223, -258, -259)
            writeByte(2); writeByte(0); writeShort(encodings.size)
            encodings.forEach(::writeInt)
            requestUpdate(incremental = false)
        }

        var raw = ByteArray(0)
        try {
            while (!closed) {
                when (val type = input.readUnsignedByte()) {
                    0 -> { // FramebufferUpdate
                        input.readByte()
                        repeat(input.readUnsignedShort()) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort()
                            val h = input.readUnsignedShort()
                            when (val enc = input.readInt()) {
                                0 -> {
                                    val n = w * h * 4
                                    if (raw.size < n) raw = ByteArray(n)
                                    input.readFully(raw, 0, n)
                                    val ints = ByteBuffer.wrap(raw, 0, n).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                                    synchronized(this) {
                                        for (row in 0 until h) {
                                            val start = (y + row) * width + x
                                            ints.get(pixels, start, w)
                                            for (i in start until start + w) pixels[i] = pixels[i] or -0x1000000
                                        }
                                    }
                                }
                                -223 -> resize(w, h)
                                -258 -> {} // server confirms scancode support
                                -259 -> startAudio()
                                else -> error("Unexpected VNC encoding $enc")
                            }
                        }
                        onFrame()
                        send { requestUpdate(incremental = !resized) }
                        resized = false
                    }
                    1 -> { input.readByte(); input.readUnsignedShort(); input.skipBytes(input.readUnsignedShort() * 6) }
                    2 -> {} // bell
                    3 -> { input.skipBytes(3); input.skipBytes(input.readInt()) } // guest clipboard, unused
                    255 -> { // QEMU server message
                        check(input.readUnsignedByte() == 1) { "Unknown QEMU message" }
                        when (input.readUnsignedShort()) {
                            0 -> audio?.pause()
                            1 -> audio?.play()
                            2 -> {
                                val n = input.readInt()
                                if (raw.size < n) raw = ByteArray(n)
                                input.readFully(raw, 0, n)
                                audio?.write(raw, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        }
                    }
                    else -> error("Unknown VNC message $type")
                }
            }
        } finally {
            close()
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        audio?.release()
        audio = null
    }

    /** Absolute pointer. [buttons]: bit 0 left, 1 middle, 2 right, 3/4 wheel up/down, 5/6 wheel left/right. */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5); writeByte(buttons)
        writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
        writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    /** XT scancode, E0-prefixed codes with bit 7 set, as QEMU's extended key event wants. */
    fun key(scancode: Int, down: Boolean) = send {
        writeByte(255); writeByte(0); writeShort(if (down) 1 else 0)
        writeInt(0); writeInt(scancode)
    }

    private fun startAudio() {
        if (audio != null) return
        val rate = 44100
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), rate * 4 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        send {
            writeByte(255); writeByte(1); writeShort(2) // set format: S16, stereo, 44.1 kHz
            writeByte(3); writeByte(2); writeInt(rate)
            writeByte(255); writeByte(1); writeShort(0) // enable
        }
    }

    private var resized = false

    private fun resize(w: Int, h: Int) {
        resized = true
        synchronized(this) {
            width = w
            height = h
            pixels = IntArray(w * h)
        }
        onResize(w, h)
    }

    private fun DataOutputStream.requestUpdate(incremental: Boolean) {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        if (closed || !::out.isInitialized) return
        runCatching { synchronized(out) { out.block(); out.flush() } }
    }
}
