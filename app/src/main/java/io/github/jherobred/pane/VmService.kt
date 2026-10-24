package io.github.jherobred.pane

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.concurrent.thread

sealed interface VmState {
    data object Stopped : VmState
    data object Starting : VmState
    data class Running(val since: Long) : VmState
    data class Failed(val log: String) : VmState
}

/** Owns the QEMU process. A foreground service, so Android leaves Windows running behind other apps. */
class VmService : Service() {
    companion object {
        val state = MutableStateFlow<VmState>(VmState.Stopped)
        private const val SHUTDOWN = "shutdown"
        private const val KILL = "kill"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, VmService::class.java))
        fun shutdown(ctx: Context) = ctx.startService(Intent(ctx, VmService::class.java).setAction(SHUTDOWN))
        fun kill(ctx: Context) = ctx.startService(Intent(ctx, VmService::class.java).setAction(KILL))

        /** One QMP command on a fresh connection. QMP is QEMU's JSON control socket. */
        fun qmp(ctx: Context, command: String, args: String = "{}") {
            LocalSocket().use { s ->
                s.connect(LocalSocketAddress(File(WindowsVm.dir(ctx), "qmp.sock").path, LocalSocketAddress.Namespace.FILESYSTEM))
                val r = s.inputStream.bufferedReader()
                val w = s.outputStream.bufferedWriter()
                r.readLine() // greeting
                for (cmd in listOf("""{"execute":"qmp_capabilities"}""", """{"execute":"$command","arguments":$args}""")) {
                    w.write(cmd + "\n")
                    w.flush()
                    while (true) {
                        val line = r.readLine() ?: return
                        if ("\"return\"" in line || "\"error\"" in line) break
                    }
                }
            }
        }
    }

    @Volatile private var process: Process? = null
    @Volatile private var killed = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SHUTDOWN -> thread { runCatching { qmp(this, "system_powerdown") } }
            KILL -> {
                killed = true
                // proot shrugs off SIGTERM. Ask QEMU to quit; SIGKILL proot (and its children) if that fails.
                thread {
                    runCatching { qmp(this, "quit") }
                    Thread.sleep(3000)
                    process?.destroyForcibly()
                }
            }
            else -> if (process == null) launch()
        }
        return START_NOT_STICKY
    }

    private fun launch() {
        killed = false
        startForeground(1, notification("Starting Windows 11"))
        state.value = VmState.Starting
        val dir = WindowsVm.dir(this)
        File(dir, "vnc.sock").delete()
        File(dir, "qmp.sock").delete()
        val p = try {
            Engine.proot(this, WindowsVm.qemuArgs(this), WindowsVm.binds(this)).start()
        } catch (e: Exception) {
            finish("Couldn't start QEMU: ${e.message}")
            return
        }
        process = p
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pane:windows").apply { acquire() }

        thread(name = "qemu-output") {
            val tail = ArrayDeque<String>()
            File(dir, "qemu.log").bufferedWriter().use { log ->
                // Force stop closes this stream under us; that just ends the log.
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        log.appendLine(it)
                        log.flush()
                        tail.addLast(it)
                        if (tail.size > 15) tail.removeFirst()
                    }
                }
            }
            val code = p.waitFor()
            finish(
                when {
                    code == 0 || killed -> null
                    // SIGKILL from outside: on Android 12+ that is usually the phantom process killer.
                    code == 137 -> "Android stopped Windows in the background. In Developer options, turn on " +
                        "\"Disable child process restrictions\", then start Windows again.\n\n" + tail.joinToString("\n")
                    else -> "QEMU stopped with code $code.\n\n" + tail.joinToString("\n")
                }
            )
        }

        thread(name = "qemu-ready") {
            val vnc = File(dir, "vnc.sock")
            val fresh = WindowsVm.isDiskFresh(this)
            repeat(600) {
                if (process !== p) return@thread
                if (vnc.exists()) {
                    state.value = VmState.Running(System.currentTimeMillis())
                    getSystemService(NotificationManager::class.java).notify(1, notification("Windows 11 is running"))
                    // The installer's "Press any key to boot from CD or DVD" times out in a few seconds.
                    // Keep pressing Enter through the window where it can appear, only while the disk is empty.
                    if (fresh) repeat(80) {
                        if (process !== p) return@thread
                        runCatching { qmp(this, "send-key", """{"keys":[{"type":"qcode","data":"ret"}]}""") }
                        Thread.sleep(1500)
                    }
                    return@thread
                }
                Thread.sleep(100)
            }
        }
    }

    private fun finish(error: String?) {
        process = null
        wakeLock?.takeIf { it.isHeld }?.release()
        state.value = error?.let { VmState.Failed(it) } ?: VmState.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        process?.destroyForcibly()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vm", "Windows", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VmService::class.java).setAction(SHUTDOWN), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "vm")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pane")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Shut down", stop).build())
            .build()
    }
}
