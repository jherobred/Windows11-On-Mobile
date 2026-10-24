package io.github.jherobred.pane

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val MICROSOFT_ISO = "https://www.microsoft.com/software-download/windows11arm64"

@Composable
fun SetupScreen(onBack: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val engine by Engine.state.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(if (Engine.isReady(ctx)) 1 else 0) }
    var iso by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(engine) {
        if (engine is Engine.State.Ready && step == 0) { delay(700); step = 1 }
    }

    Screen {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Glyphs.Back, onClick = { if (step == 2) step = 1 else onBack() })
                Spacer(Modifier.width(16.dp))
                Text("Set up Windows 11", color = Pane.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(28.dp))
            Steps(step)
            Spacer(Modifier.height(28.dp))
            AnimatedContent(
                step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(450)) { dir * it / 4 } + fadeIn(tween(450))) togetherWith
                        (slideOutHorizontally(tween(300)) { -dir * it / 4 } + fadeOut(tween(250)))
                },
                label = "step",
            ) { s ->
                Box(Modifier.widthIn(max = 640.dp)) {
                    when (s) {
                        0 -> EngineStep(engine)
                        1 -> IsoStep { iso = it; step = 2 }
                        else -> {
                            val f = iso
                            if (f != null) AccountStep(f, onStarted) else LaunchedEffect(Unit) { step = 1 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Steps(step: Int) {
    Row(Modifier.widthIn(max = 640.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Engine", "Windows image", "Your account").forEachIndexed { i, label ->
            val fill by animateFloatAsState(if (i <= step) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 120f), label = "seg")
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
                    Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(Brush.horizontalGradient(listOf(Pane.Accent, Pane.Violet))))
                }
                Spacer(Modifier.height(8.dp))
                Text(label, color = if (i <= step) Pane.Ink else Pane.Faint, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Panel(title: String, body: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(26.dp)) {
        Text(title, color = Pane.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Pane.Dim, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(22.dp))
        content()
    }
}

@Composable
private fun EngineStep(engine: Engine.State) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Panel(
        "Get the engine",
        "Pane runs Windows inside QEMU, an open-source emulator, on a small Alpine Linux. It downloads once, about 40 MB.",
    ) {
        when (engine) {
            is Engine.State.Working -> {
                Text(engine.step, color = Pane.Ink, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Progress(engine.progress)
            }
            Engine.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Check, null, tint = Pane.Mint)
                Spacer(Modifier.width(10.dp))
                Text("Engine ready", color = Pane.Ink, fontSize = 16.sp)
            }
            else -> Column {
                if (engine is Engine.State.Failed) {
                    Text(engine.message, color = Pane.Danger, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                }
                PrimaryButton(if (engine is Engine.State.Failed) "Try again" else "Download engine", icon = Glyphs.Download) {
                    scope.launch { Engine.install(ctx.applicationContext) }
                }
            }
        }
    }
}

@Composable
fun Progress(value: Float) {
    val shown by animateFloatAsState(value.coerceIn(0f, 1f), spring(stiffness = 60f), label = "progress")
    val sweep by rememberInfiniteTransition(label = "indeterminate")
        .animateFloat(-0.4f, 1.1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
        if (value < 0) {
            Box(Modifier.fillMaxWidth(0.35f).fillMaxHeight().graphicsLayer { translationX = sweep * size.width * 2.8f }
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Pane.Accent, Color.Transparent))))
        } else {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(Brush.horizontalGradient(listOf(Pane.Accent, Pane.Violet))))
        }
    }
}

@Composable
private fun IsoStep(onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var scan by remember { mutableIntStateOf(0) }
    val isos = remember(granted, scan) {
        if (!granted) emptyList()
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".iso", ignoreCase = true) }?.sortedByDescending { it.lastModified() }.orEmpty()
    }
    val freeGb = remember { StatFs(ctx.filesDir.path).availableBytes / 1e9 }

    Panel(
        "Get Windows 11 from Microsoft",
        "Download the official Windows 11 Arm64 ISO (about 6 GB) in your browser and keep it in Downloads. " +
            "Pick any language; English keeps setup fully automatic.",
    ) {
        GhostButton("Open Microsoft's download page", icon = Glyphs.Open) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MICROSOFT_ISO)))
        }
        Spacer(Modifier.height(20.dp))
        if (!granted) {
            PrimaryButton("Let Pane read Downloads", icon = Glyphs.Disc) { ask.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isos.isEmpty()) "No ISO in Downloads yet." else "Found in Downloads", color = Pane.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconChip(Glyphs.Refresh) { scan++ }
        }
        Spacer(Modifier.height(12.dp))
        isos.forEach { f ->
            val arm = f.name.contains("arm64", true) || f.name.contains("aarch64", true)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).pressable(arm) { onPick(f) }.glass(RoundedCornerShape(20.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Disc, null, tint = if (arm) Pane.Accent else Pane.Faint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = if (arm) Pane.Ink else Pane.Faint, fontSize = 15.sp)
                    Text(
                        "%.1f GB".format(f.length() / 1e9) + if (arm) "" else " · x64 ISOs can't run here, get the Arm64 one",
                        color = Pane.Faint, fontSize = 12.sp,
                    )
                }
                if (arm) Text("Use", color = Pane.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Windows needs about 30 GB on this device. %.0f GB free.".format(freeGb),
            color = if (freeGb < 30) Pane.Amber else Pane.Faint, fontSize = 13.sp,
        )
    }
}

@Composable
private fun AccountStep(iso: File, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var edition by remember { mutableStateOf(WindowsVm.Edition.HOME) }
    var name by remember { mutableStateOf("User") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val s = remember { WindowsVm.settings(ctx) }

    Panel(
        "Your Windows account",
        "Setup runs on its own and restarts a few times. Expect 1 to 3 hours on this device. Keep it plugged in.",
    ) {
        Text("Edition", color = Pane.Dim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.glass(RoundedCornerShape(18.dp)).padding(4.dp)) {
            WindowsVm.Edition.entries.forEach { e ->
                val on = e == edition
                Text(
                    "Windows 11 ${e.label}", color = if (on) Pane.Night else Pane.Ink, fontWeight = FontWeight.Medium,
                    modifier = Modifier.pressable { edition = e }.clip(RoundedCornerShape(14.dp))
                        .background(if (on) Pane.Accent else Color.Transparent).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Field("User name", name) { name = it.filter { c -> c !in "\"/\\[]:;|=,+*?<>@" }.take(20) }
        Spacer(Modifier.height(12.dp))
        Field("Password (optional)", password, secret = true) { password = it }
        Spacer(Modifier.height(18.dp))
        Text(
            "Windows gets ${s.ramMb / 1024} GB of memory and ${s.cpus} cores. Its disk grows as needed. " +
                "Activation uses your own Windows license.",
            color = Pane.Faint, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(22.dp))
        error?.let {
            Text(it, color = Pane.Danger, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }
        if (busy) {
            Text("Preparing the virtual PC", color = Pane.Ink, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Progress(-1f)
        } else {
            PrimaryButton("Install Windows 11", icon = Glyphs.Play, enabled = name.isNotBlank()) {
                busy = true
                scope.launch {
                    runCatching { WindowsVm.create(ctx.applicationContext, iso, edition, name.trim(), password) }
                        .onSuccess { VmService.start(ctx); onStarted() }
                        .onFailure { error = it.message; busy = false }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = Pane.Faint, fontSize = 12.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Pane.Ink, fontSize = 17.sp),
            cursorBrush = SolidColor(Pane.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
