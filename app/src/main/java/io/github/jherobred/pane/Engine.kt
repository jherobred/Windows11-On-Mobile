package io.github.jherobred.pane

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** Alpine Linux under proot, with QEMU from Alpine's own packages. Nothing needs root. */
object Engine {
    private const val ALPINE = "v3.24"
    private const val VERSION = "3.24.3-1" // bump to make every install redo itself
    private val ROOTFS = mapOf(
        "aarch64" to ("alpine-minirootfs-3.24.3-aarch64.tar.gz" to "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"),
        "x86_64" to ("alpine-minirootfs-3.24.3-x86_64.tar.gz" to "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407"),
    )
    private val PACKAGES = listOf("qemu-system-aarch64", "qemu-img", "xorriso")

    sealed interface State {
        data object Missing : State
        data class Working(val step: String, val progress: Float) : State // progress < 0: unknown
        data object Ready : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Missing)

    fun root(ctx: Context) = File(ctx.filesDir, "alpine")
    private fun marker(ctx: Context) = File(ctx.filesDir, "engine.version")
    fun isReady(ctx: Context) = marker(ctx).let { it.exists() && it.readText() == VERSION }

    fun refresh(ctx: Context) {
        if (state.value !is State.Working) state.value = if (isReady(ctx)) State.Ready else State.Missing
    }

    suspend fun install(ctx: Context) = withContext(Dispatchers.IO) {
        if (state.value is State.Working) return@withContext
        state.value = State.Working("Downloading Alpine Linux", -1f)
        runCatching {
            val arch = if (Build.SUPPORTED_ABIS[0] == "x86_64") "x86_64" else "aarch64"
            val (file, sha) = ROOTFS.getValue(arch)
            val root = root(ctx)
            deleteTree(root)
            root.mkdirs()
            val tgz = File(ctx.cacheDir, file)
            download("https://dl-cdn.alpinelinux.org/alpine/$ALPINE/releases/$arch/$file", tgz, sha)

            state.value = State.Working("Unpacking", -1f)
            run(ProcessBuilder("tar", "xzf", tgz.path, "-C", root.path))
            tgz.delete()
            File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            File(root, "etc/apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/$ALPINE/main\nhttps://dl-cdn.alpinelinux.org/alpine/$ALPINE/community\n"
            )

            // apk buffers its output when piped, so per-package progress may only arrive at the end.
            state.value = State.Working("Installing QEMU", -1f)
            val step = Regex("""\((\d+)/(\d+)\) Installing (\S+)""")
            run(proot(ctx, listOf("apk", "add", "-U", "--no-progress") + PACKAGES)) { line ->
                step.find(line)?.let {
                    val (i, n, pkg) = it.destructured
                    state.value = State.Working("Installing $pkg", i.toFloat() / n.toFloat())
                }
            }
            check(File(root, "usr/bin/qemu-system-aarch64").exists()) { "QEMU is missing after install" }
            marker(ctx).writeText(VERSION)
        }.onSuccess { state.value = State.Ready }
            .onFailure { state.value = State.Failed(it.message ?: it.toString()) }
    }

    /** A command inside Alpine, with a clean environment. [binds] are host paths mounted at the same path. */
    fun proot(ctx: Context, cmd: List<String>, binds: List<String> = emptyList()): ProcessBuilder {
        val lib = ctx.applicationInfo.nativeLibraryDir
        val tmp = File(ctx.filesDir, "tmp").apply { mkdirs() }
        val args = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0",
            "-r", root(ctx).path, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
        )
        binds.forEach { args += listOf("-b", it) }
        args += listOf("/usr/bin/env", "-i", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8")
        args += cmd
        return ProcessBuilder(args).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = lib
            environment()["PROOT_LOADER"] = "$lib/libproot-loader.so"
            environment()["PROOT_TMP_DIR"] = tmp.path
        }
    }

    /** Runs to completion. Fails with the last lines of output. */
    fun run(pb: ProcessBuilder, onLine: (String) -> Unit = {}) {
        val p = pb.start()
        val tail = ArrayDeque<String>()
        p.inputStream.bufferedReader().forEachLine {
            onLine(it)
            tail.addLast(it)
            if (tail.size > 12) tail.removeFirst()
        }
        check(p.waitFor() == 0) { "${pb.command().last()} failed:\n" + tail.joinToString("\n") }
    }

    /** Deletes without following symlinks: the rootfs links to absolute paths that exist on Android. */
    fun deleteTree(dir: File) {
        if (!dir.exists() && !Files.isSymbolicLink(dir.toPath())) return
        Files.walk(dir.toPath()).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun download(url: String, to: File, sha256: String) {
        val md = MessageDigest.getInstance("SHA-256")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        check(md.digest().joinToString("") { "%02x".format(it) } == sha256) { "Download was corrupted: $url" }
    }
}
