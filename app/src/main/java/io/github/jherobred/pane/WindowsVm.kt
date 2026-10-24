package io.github.jherobred.pane

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One Windows 11 ARM64 machine: its files, its QEMU command line and its unattended setup. */
object WindowsVm {
    enum class Edition(val label: String, val key: String) {
        // Microsoft's generic install keys. They pick the edition; they don't activate Windows.
        HOME("Home", "YTMG3-N6DKC-DKB77-7M9GH-8HVX7"),
        PRO("Pro", "VK7JG-NPHTM-C97JM-9MPGT-3V66T"),
    }

    data class Settings(val ramMb: Int, val cpus: Int)

    fun dir(ctx: Context) = File(ctx.filesDir, "vms/windows11")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("windows11", Context.MODE_PRIVATE)

    fun isCreated(ctx: Context) = File(dir(ctx), "disk.qcow2").exists()
    fun isInstalled(ctx: Context) = prefs(ctx).getBoolean("installed", false)
    fun markInstalled(ctx: Context) = prefs(ctx).edit().putBoolean("installed", true).apply()
    private fun iso(ctx: Context) = prefs(ctx).getString("iso", null)?.let(::File)?.takeIf { it.exists() }

    fun settings(ctx: Context): Settings {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val totalMb = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem shr 20
        // Android needs room too: 8 GB phones give Windows 4 GB, 12 GB and up give it 6 GB.
        val ram = when {
            totalMb >= 11_000 -> 6144
            totalMb >= 7_000 -> 4096
            else -> 3072
        }
        val p = prefs(ctx)
        return Settings(p.getInt("ram", ram), p.getInt("cpus", Runtime.getRuntime().availableProcessors().coerceAtMost(4)))
    }

    fun save(ctx: Context, s: Settings) = prefs(ctx).edit().putInt("ram", s.ramMb).putInt("cpus", s.cpus).apply()

    /** True while the disk is still empty, which is when the installer's "Press any key" prompt shows. */
    fun isDiskFresh(ctx: Context) = File(dir(ctx), "disk.qcow2").length() < 200L shl 20

    /** Fresh UEFI variables, which resets the firmware's boot list. Only safe before Windows is on the disk. */
    fun resetFirmware(ctx: Context) {
        File(Engine.root(ctx), "usr/share/qemu/edk2-arm-vars.fd").copyTo(File(dir(ctx), "vars.fd"), overwrite = true)
    }

    suspend fun create(ctx: Context, iso: File, edition: Edition, user: String, password: String) = withContext(Dispatchers.IO) {
        val dir = dir(ctx)
        Engine.deleteTree(dir)
        val setup = File(dir, "setup/\$WinPEDriver\$/NetKVM").apply { mkdirs() }
        for (name in ctx.assets.list("drivers/NetKVM").orEmpty()) {
            ctx.assets.open("drivers/NetKVM/$name").use { input -> File(setup, name).outputStream().use(input::copyTo) }
        }
        File(dir, "setup/autounattend.xml").writeText(unattend(edition, user, password, language(iso.name)))
        File(Engine.root(ctx), "usr/share/qemu/edk2-arm-vars.fd").copyTo(File(dir, "vars.fd"))
        val d = dir.path
        Engine.run(Engine.proot(ctx, listOf("qemu-img", "create", "-f", "qcow2", "$d/disk.qcow2", "128G"), listOf(d)))
        Engine.run(Engine.proot(ctx, listOf("xorriso", "-as", "mkisofs", "-quiet", "-J", "-R", "-V", "PANE_SETUP", "-o", "$d/setup.iso", "$d/setup"), listOf(d)))
        prefs(ctx).edit().putString("iso", iso.canonicalPath).putBoolean("installed", false).apply()
    }

    fun delete(ctx: Context) {
        Engine.deleteTree(dir(ctx))
        prefs(ctx).edit().clear().apply()
    }

    /** Folders QEMU sees at their real paths: the machine's own files and shared storage, for the ISO. */
    fun binds(ctx: Context) = listOf(dir(ctx).path, "/storage")

    fun qemuArgs(ctx: Context): List<String> {
        val s = settings(ctx)
        val d = dir(ctx).path
        // Translated code cache: bigger is faster, but it comes out of the phone's RAM.
        val tb = if (s.ramMb >= 6144) 512 else 256
        val args = mutableListOf(
            "qemu-system-aarch64", "-name", "Windows 11",
            "-M", "virt,gic-version=3",
            "-accel", "tcg,thread=multi,tb-size=$tb",
            // pauth-impdef swaps the costly standard pointer-auth cipher for a cheap one under emulation.
            "-cpu", "max,pauth-impdef=on",
            "-smp", "${s.cpus}", "-m", "${s.ramMb}M",
            "-drive", "if=pflash,format=raw,readonly=on,file=/usr/share/qemu/edk2-aarch64-code.fd",
            "-drive", "if=pflash,format=raw,file=$d/vars.fd",
            "-rtc", "base=localtime",
            // ponytail: ramfb is stuck at 1024x768 with stock firmware. virtio-gpu + viogpudo or a patched
            // firmware would give native resolution, at a cost in emulation speed.
            "-device", "ramfb",
            "-device", "qemu-xhci", "-device", "usb-kbd", "-device", "usb-tablet",
            "-audiodev", "none,id=snd", "-device", "usb-audio,audiodev=snd",
            // No bootindex: with one, edk2 rebuilds its boot list from QEMU's hints, can't match the USB
            // installer disc on ARM, and drops it. The default order already tries the USB disc first.
            "-device", "nvme,drive=disk,serial=pane0",
            "-drive", "if=none,id=disk,format=qcow2,file=$d/disk.qcow2,cache=writeback,discard=unmap,detect-zeroes=unmap",
            "-nic", "user,model=virtio-net-pci",
            "-vnc", "unix:$d/vnc.sock,audiodev=snd",
            "-qmp", "unix:$d/qmp.sock,server=on,wait=off",
            "-monitor", "none", "-serial", "none",
        )
        iso(ctx)?.let {
            args += listOf("-device", "usb-storage,drive=install", "-drive", "if=none,id=install,format=raw,media=cdrom,readonly=on,file=${it.path}")
        }
        if (File(d, "setup.iso").exists()) {
            args += listOf("-device", "usb-storage,drive=setup", "-drive", "if=none,id=setup,format=raw,media=cdrom,readonly=on,file=$d/setup.iso")
        }
        return args
    }

    /** Windows display language from Microsoft's ISO names, e.g. Win11_25H2_English_Arm64.iso. Unknown: Setup asks. */
    private fun language(isoName: String): String? {
        val n = isoName.lowercase()
        return listOf(
            "englishinternational" to "en-GB", "english" to "en-US", "spanish" to "es-ES", "french" to "fr-FR",
            "german" to "de-DE", "italian" to "it-IT", "portuguese" to "pt-BR", "japanese" to "ja-JP", "korean" to "ko-KR",
        ).firstOrNull { n.contains(it.first) }?.second
    }

    private fun unattend(edition: Edition, user: String, password: String, lang: String?): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val tz = android.icu.util.TimeZone.getWindowsID(java.util.TimeZone.getDefault().id) ?: "UTC"
        val c = """processorArchitecture="arm64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS""""
        // The keyboard is always US: touch typing and the hardware keyboard both send US scancodes.
        val intl = lang?.let {
            """<InputLocale>0409:00000409</InputLocale><SystemLocale>$it</SystemLocale><UILanguage>$it</UILanguage><UserLocale>$it</UserLocale>"""
        }
        val bypass = listOf("BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck", "BypassCPUCheck", "BypassStorageCheck")
            .mapIndexed { i, v ->
                """<RunSynchronousCommand wcm:action="add"><Order>${i + 1}</Order><Path>reg add HKLM\SYSTEM\Setup\LabConfig /v $v /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>"""
            }.joinToString("")
        val firstLogon = listOf(
            // Drivers from the setup disc, in case Setup's own pass missed them.
            """cmd /c for %d in (D E F G H I J K L M N O P Q R S T U V W Y Z) do @if exist "%d:\${'$'}WinPEDriver${'$'}" pnputil /add-driver "%d:\${'$'}WinPEDriver${'$'}\*.inf" /subdirs /install""",
            // Pane's Shut down button presses the virtual power button. Make that shut down, not sleep.
            "powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS PBUTTONACTION 3",
            "powercfg /setactive SCHEME_CURRENT",
        ).mapIndexed { i, cmd ->
            """<SynchronousCommand wcm:action="add"><Order>${i + 1}</Order><CommandLine>${esc(cmd)}</CommandLine></SynchronousCommand>"""
        }.joinToString("")
        val pw = """<Password><Value>${esc(password)}</Value><PlainText>true</PlainText></Password>"""
        return """<?xml version="1.0" encoding="utf-8"?>
<unattend xmlns="urn:schemas-microsoft-com:unattend" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
<settings pass="windowsPE">
${intl?.let { """<component name="Microsoft-Windows-International-Core-WinPE" $c><SetupUILanguage><UILanguage>$lang</UILanguage></SetupUILanguage>$it</component>""" } ?: ""}
<component name="Microsoft-Windows-Setup" $c>
<RunSynchronous>$bypass</RunSynchronous>
<DiskConfiguration><Disk wcm:action="add"><DiskID>0</DiskID><WillWipeDisk>true</WillWipeDisk><CreatePartitions>
<CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>300</Size></CreatePartition>
<CreatePartition wcm:action="add"><Order>2</Order><Type>MSR</Type><Size>16</Size></CreatePartition>
<CreatePartition wcm:action="add"><Order>3</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition>
</CreatePartitions><ModifyPartitions>
<ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Label>System</Label><Format>FAT32</Format></ModifyPartition>
<ModifyPartition wcm:action="add"><Order>2</Order><PartitionID>3</PartitionID><Label>Windows</Label><Letter>C</Letter><Format>NTFS</Format></ModifyPartition>
</ModifyPartitions></Disk></DiskConfiguration>
<ImageInstall><OSImage><InstallTo><DiskID>0</DiskID><PartitionID>3</PartitionID></InstallTo></OSImage></ImageInstall>
<UserData><AcceptEula>true</AcceptEula><ProductKey><Key>${edition.key}</Key><WillShowUI>OnError</WillShowUI></ProductKey></UserData>
</component>
</settings>
<settings pass="oobeSystem">
${intl?.let { """<component name="Microsoft-Windows-International-Core" $c>$it</component>""" } ?: ""}
<component name="Microsoft-Windows-Shell-Setup" $c>
<OOBE><HideEULAPage>true</HideEULAPage><HideOEMRegistrationScreen>true</HideOEMRegistrationScreen><HideOnlineAccountScreens>true</HideOnlineAccountScreens><HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE><ProtectYourPC>1</ProtectYourPC></OOBE>
<UserAccounts><LocalAccounts><LocalAccount wcm:action="add"><Name>${esc(user)}</Name><Group>Administrators</Group>$pw</LocalAccount></LocalAccounts></UserAccounts>
<AutoLogon><Enabled>true</Enabled><LogonCount>1</LogonCount><Username>${esc(user)}</Username>$pw</AutoLogon>
<FirstLogonCommands>$firstLogon</FirstLogonCommands>
<TimeZone>${esc(tz)}</TimeZone>
</component>
</settings>
</unattend>
"""
    }
}
