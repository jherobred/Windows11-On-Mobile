package io.github.jherobred.pane

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Pane {
    val Night = Color(0xFF05070D)
    val Ink = Color(0xFFEFF3FF)
    val Dim = Color(0xB3EFF3FF)
    val Faint = Color(0x66EFF3FF)
    val Accent = Color(0xFF60CDFF)
    val Violet = Color(0xFF7C5CFF)
    val Mint = Color(0xFF5CE6B0)
    val Amber = Color(0xFFFFC46B)
    val Danger = Color(0xFFFF7A7A)
}

@Composable
fun PaneTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Pane.Accent, onPrimary = Pane.Night, background = Pane.Night, surface = Color(0xFF111522),
        onSurface = Pane.Ink, onBackground = Pane.Ink, surfaceVariant = Color(0xFF1A2030),
    ),
    content = content,
)

/** Slow drifting light behind every screen. */
@Composable
fun Aurora(modifier: Modifier = Modifier, speed: Float = 1f) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween((40_000 / speed).toInt(), easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF070A14), Color(0xFF0A0F1F), Color(0xFF05070D))))
        fun blob(color: Color, cxF: Float, cyF: Float, r: Float) {
            val c = Offset(size.width * cxF, size.height * cyF)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), c, r), r, c)
        }
        val d = size.minDimension
        blob(Pane.Accent.copy(alpha = 0.30f), 0.25f + 0.10f * cos(t), 0.20f + 0.08f * sin(2 * t), d * 0.75f)
        blob(Pane.Violet.copy(alpha = 0.28f), 0.80f + 0.08f * sin(t), 0.35f + 0.10f * cos(t), d * 0.70f)
        blob(Color(0xFF22D3EE).copy(alpha = 0.14f), 0.55f + 0.12f * sin(t + 1f), 0.95f + 0.05f * cos(2 * t), d * 0.80f)
    }
}

/** Frosted surface: soft fill, light rim along the top edge. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp), strong: Boolean = false) = this
    .clip(shape)
    .background(Brush.verticalGradient(if (strong) listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF)) else listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF))))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x0FFFFFFF))), shape)

/** Shrinks under the finger on press, springs back on release. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    val haptics = LocalHapticFeedback.current
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .pressable(enabled, onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Pane.Accent, Color(0xFF8AB4FF), Pane.Violet)))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Pane.Night, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Pane.Night, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Pane.Ink, onClick: () -> Unit) {
    Row(
        modifier.pressable(onClick = onClick).glass(RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable
fun IconChip(icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.pressable(onClick = onClick).glass(RoundedCornerShape(16.dp), strong = active).size(46.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) Pane.Accent else Pane.Ink, modifier = Modifier.size(22.dp)) }
}

/** The Windows 11 tile: a lit glass square with "11". Deliberately not Microsoft's logo. */
@Composable
fun ElevenTile(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Pane.Accent, Color(0xFF3B82F6), Pane.Violet)))
            .border(1.dp, Color(0x55FFFFFF), shape)
            .drawBehind {
                drawRect(Brush.linearGradient(listOf(Color(0x44FFFFFF), Color.Transparent), Offset.Zero, Offset(this.size.width * 0.7f, this.size.height * 0.7f)))
            },
        contentAlignment = Alignment.Center,
    ) { Text("11", color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Light) }
}

/** Boot animation: a sweeping arc and three orbiting sparks around the tile. */
@Composable
fun BootLoader(modifier: Modifier = Modifier, tile: Dp = 84.dp) {
    val inf = rememberInfiniteTransition(label = "boot")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val orbit by inf.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "orbit")
    val breathe by inf.animateFloat(0.97f, 1.04f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    Box(modifier.size(tile * 2.4f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 8.dp.toPx()
            val stroke = 3.dp.toPx()
            drawCircle(Color(0x14FFFFFF), r, style = Stroke(stroke))
            rotate(spin) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Pane.Accent, Pane.Violet, Color.Transparent)),
                    0f, 220f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            for (i in 0 until 3) {
                val a = orbit * (1f + i * 0.35f) + i * 2.1f
                val p = Offset(center.x + r * cos(a), center.y + r * sin(a))
                drawCircle(Brush.radialGradient(listOf(Color.White, Pane.Accent.copy(alpha = 0f)), p, 9.dp.toPx()), 9.dp.toPx(), p)
            }
        }
        ElevenTile(tile, Modifier.graphicsLayer { scaleX = breathe; scaleY = breathe })
    }
}

@Composable
fun Pill(text: String, color: Color, pulse: Boolean = false) {
    val a by rememberInfiniteTransition(label = "pill").animateFloat(
        1f, if (pulse) 0.25f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
    )
    Row(
        Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Pane.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Screen(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize().background(Pane.Night)) {
    Aurora()
    content()
}

private fun glyph(vararg paths: String) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    paths.forEach {
        addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

object Glyphs {
    val Power = glyph("M12 3v8", "M7.1 6.3a7.5 7.5 0 1 0 9.8 0")
    val Keyboard = glyph("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z", "M7 10h.01M11 10h.01M15 10h.01M8 14h8")
    val Trackpad = glyph("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 15h18M12 15v5")
    val Touch = glyph("M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6z", "M12 4a8 8 0 1 1 0 16 8 8 0 0 1 0-16z")
    val Back = glyph("M15 5l-7 7 7 7")
    val Play = glyph("M8 5l11 7-11 7z")
    val Download = glyph("M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Open = glyph("M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val Disc = glyph("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z", "M12 10a2 2 0 1 1 0 4 2 2 0 0 1 0-4z")
    val Check = glyph("M5 12.5l4.5 4.5L19 7.5")
    val Refresh = glyph("M20 11a8 8 0 1 0-2.3 5.7", "M20 5v6h-6")
    val Trash = glyph("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Keys = glyph("M7 7h10v10H7z", "M4 4h3v3H4zM17 4h3v3h-3zM4 17h3v3H4zM17 17h3v3h-3z")
    val More = glyph("M6 12h.01M12 12h.01M18 12h.01")
    val Close = glyph("M6 6l12 12M18 6L6 18")
}
