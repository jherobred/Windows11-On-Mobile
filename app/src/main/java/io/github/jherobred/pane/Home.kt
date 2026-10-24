package io.github.jherobred.pane

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Fades and lifts children in one after another. */
@Composable
private fun Stagger(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(700, 90 * index, Ease)) + slideInVertically(tween(700, 90 * index, Ease)) { it / 4 },
    ) { content() }
}

@Composable
fun HomeScreen(onSetup: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val vm by VmService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val created = remember(refresh, vm) { WindowsVm.isCreated(ctx) }
    val installed = remember(refresh, vm) { WindowsVm.isInstalled(ctx) }
    var settingsOpen by remember { mutableStateOf(false) }
    val now by produceState(Date()) { while (true) { value = Date(); delay(10_000) } }

    Screen {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            Stagger(0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ElevenTileMini()
                    Text("  Pane", color = Pane.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), color = Pane.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(now), color = Pane.Faint, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
            Stagger(1) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                Column {
                    Text(
                        when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" },
                        color = Pane.Ink, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 52.sp,
                    )
                    Text("Choose a system to start.", color = Pane.Dim, fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
            Stagger(2) {
                OsCard(
                    vm = vm, created = created, installed = installed,
                    onPrimary = {
                        when {
                            vm is VmState.Running || vm is VmState.Starting -> onOpen()
                            created -> { VmService.start(ctx); onOpen() }
                            else -> onSetup()
                        }
                    },
                    onMore = { settingsOpen = true },
                )
            }
            Spacer(Modifier.height(20.dp))
            Stagger(3) { DeviceStrip() }
        }

        SettingsPanel(settingsOpen, running = vm is VmState.Running || vm is VmState.Starting, onClose = { settingsOpen = false; refresh++ })
    }
}

@Composable
private fun ElevenTileMini() = ElevenTile(30.dp)

@Composable
private fun OsCard(vm: VmState, created: Boolean, installed: Boolean, onPrimary: () -> Unit, onMore: () -> Unit) {
    val running = vm is VmState.Running || vm is VmState.Starting
    val (status, color) = when {
        vm is VmState.Starting -> "Starting" to Pane.Amber
        vm is VmState.Running && !installed -> "Installing" to Pane.Amber
        vm is VmState.Running -> "Running" to Pane.Mint
        installed -> "Ready" to Pane.Accent
        created -> "Setup not finished" to Pane.Amber
        else -> "Not set up" to Pane.Faint
    }
    val action = when {
        running -> "Open"
        installed -> "Start"
        created -> "Continue setup"
        else -> "Set up"
    }
    Column(
        Modifier.widthIn(max = 620.dp).fillMaxWidth().pressable(onClick = onPrimary).glass(RoundedCornerShape(32.dp)).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElevenTile(84.dp)
            Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                Text("Windows 11", color = Pane.Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Genuine Arm64 image from Microsoft", color = Pane.Dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Pill(status, color, pulse = running)
            }
            IconChip(Glyphs.More, onClick = onMore)
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(action, icon = if (running) Glyphs.Open else Glyphs.Play, onClick = onPrimary)
            Spacer(Modifier.size(16.dp))
            Text(
                if (installed || running) "Runs fully on this device. No PC, no cloud."
                else "One-time setup. Installing takes 1 to 3 hours.",
                color = Pane.Faint, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
        }
        if (vm is VmState.Failed) {
            Spacer(Modifier.height(16.dp))
            Text(vm.log.lineSequence().take(3).joinToString("\n"), color = Pane.Danger, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DeviceStrip() {
    val ctx = LocalContext.current
    val info = remember {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val total = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / 1e9
        val soc = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val free = StatFs(ctx.filesDir.path).availableBytes / 1e9
        val s = WindowsVm.settings(ctx)
        listOf(
            soc.uppercase(),
            "%.0f GB RAM".format(total),
            "Windows gets ${s.ramMb / 1024} GB · ${s.cpus} cores",
            "%.0f GB free".format(free),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 620.dp)) {
        info.forEach {
            Text(it, color = Pane.Dim, fontSize = 12.sp, modifier = Modifier.glass(RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun SettingsPanel(open: Boolean, running: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AnimatedVisibility(open, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            var s by remember { mutableStateOf(WindowsVm.settings(ctx)) }
            var confirmDelete by remember { mutableStateOf(false) }
            val maxRam = (ctx.getSystemService(ActivityManager::class.java).let { am ->
                ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem shr 20
            } * 3 / 4).toInt()
            AnimatedVisibility(open, enter = scaleIn(tween(350, easing = Ease), 0.92f) + fadeIn(), exit = scaleOut(targetScale = 0.95f) + fadeOut()) {
                Column(
                    Modifier.padding(24.dp).widthIn(max = 460.dp).glass(RoundedCornerShape(30.dp), strong = true)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}.padding(26.dp),
                ) {
                    Text("Windows 11 settings", color = Pane.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (running) "Shut Windows down to change these." else "Applies the next time Windows starts.", color = Pane.Faint, fontSize = 13.sp)
                    Spacer(Modifier.height(20.dp))
                    Stepper("Memory", "${s.ramMb / 1024.0} GB".replace(".0 ", " "), enabled = !running,
                        onMinus = { s = s.copy(ramMb = (s.ramMb - 512).coerceAtLeast(2048)) },
                        onPlus = { s = s.copy(ramMb = (s.ramMb + 512).coerceAtMost(maxRam)) })
                    Spacer(Modifier.height(12.dp))
                    Stepper("Processor cores", "${s.cpus}", enabled = !running,
                        onMinus = { s = s.copy(cpus = (s.cpus - 1).coerceAtLeast(1)) },
                        onPlus = { s = s.copy(cpus = (s.cpus + 1).coerceAtMost(Runtime.getRuntime().availableProcessors())) })
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (WindowsVm.isCreated(ctx) && !running) {
                            GhostButton(if (confirmDelete) "Tap again to erase" else "Erase Windows", icon = Glyphs.Trash, color = Pane.Danger) {
                                if (confirmDelete) { WindowsVm.delete(ctx); onClose() } else confirmDelete = true
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("Done", enabled = true) {
                            if (!running) WindowsVm.save(ctx, s)
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pane.Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("–", color = if (enabled) Pane.Ink else Pane.Faint, fontSize = 22.sp, modifier = Modifier.pressable(enabled, onMinus).padding(horizontal = 14.dp))
        Text(value, color = Pane.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("+", color = if (enabled) Pane.Ink else Pane.Faint, fontSize = 22.sp, modifier = Modifier.pressable(enabled, onPlus).padding(horizontal = 14.dp))
    }
}
