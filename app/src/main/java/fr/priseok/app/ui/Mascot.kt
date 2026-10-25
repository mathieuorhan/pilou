package fr.priseok.app.ui

import android.provider.Settings as SysSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

enum class Mood { WAITING, HAPPY, WORRIED, SLEEPY }

/**
 * Pilou, the capsule. Its face tells the state of today's dose at a glance.
 * [celebrate] increments each time a dose is confirmed: Pilou bounces and throws sparkles.
 */
@Composable
fun Mascot(mood: Mood, celebrate: Int, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val motion = remember {
        SysSettings.Global.getFloat(ctx.contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    val inf = rememberInfiniteTransition(label = "pilou")
    val bob by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (mood == Mood.SLEEPY) 3200 else 2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val blink by inf.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = 4200
            0f at 3900; 1f at 4000; 0f at 4100
        }),
        label = "blink",
    )
    val zz by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "zz")

    val tilt by animateFloatAsState(
        when (mood) {
            Mood.WAITING -> -16f; Mood.HAPPY -> -8f; Mood.WORRIED -> -26f; Mood.SLEEPY -> -4f
        },
        spring(dampingRatio = 0.5f, stiffness = 120f), label = "tilt",
    )

    val pop = remember { Animatable(1f) }
    val squash = remember { Animatable(1f) }
    LaunchedEffect(celebrate) {
        if (celebrate > 0 && motion) {
            squash.snapTo(0.82f)
            pop.snapTo(0f)
            launch { squash.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 260f)) }
            launch { pop.animateTo(1f, tween(950, easing = FastOutSlowInEasing)) }
        }
    }

    val measurer = rememberTextMeasurer()
    val zStyle = TextStyle(fontFamily = Fredoka, fontSize = 22.sp, color = p.pineSoft)

    val description = when (mood) {
        Mood.WAITING -> "Pilou attend. Touche-le pour valider la prise."
        Mood.HAPPY -> "Pilou est content : c'est pris."
        Mood.WORRIED -> "Pilou est inquiet : la prise n'a pas été validée."
        Mood.SLEEPY -> "Pilou dort : rien à prendre pour l'instant."
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap,
                ) else Modifier
            )
    ) {
        val L = size.width * 0.62f
        val T = L * 0.46f
        val amp = if (!motion) 0f else if (mood == Mood.SLEEPY) 4f else 9f
        val bobY = -bob * amp * density
        val c = Offset(size.width / 2f, size.height * 0.46f)

        // soft floor shadow, shrinks as Pilou floats up
        val sw = L * (0.78f - bob * 0.08f * (amp / 9f))
        drawOval(
            p.pine.copy(alpha = 0.08f),
            topLeft = Offset(c.x - sw / 2, c.y + T * 0.95f),
            size = Size(sw, T * 0.22f),
        )

        translate(c.x, c.y + bobY) {
            scale(scaleX = 2f - squash.value, scaleY = squash.value, pivot = Offset(0f, T / 2)) {
                rotate(tilt, pivot = Offset.Zero) {
                    drawCapsule(L, T, p)
                    drawFace(mood, T, if (motion) blink else 0f, p)
                }
            }
        }

        if (mood == Mood.SLEEPY && motion) drawZs(c + Offset(L * 0.34f, -T * 0.7f + bobY), zz, measurer, zStyle)
        if (pop.value < 1f) drawSparkles(c + Offset(0f, bobY), L, pop.value, p)
    }
}

private fun DrawScope.drawCapsule(L: Float, T: Float, p: Palette) {
    val tl = Offset(-L / 2, -T / 2)
    val sz = Size(L, T)
    val r = CornerRadius(T / 2, T / 2)
    drawRoundRect(p.butter, tl, sz, r)
    clipRect(left = -L / 2, top = -T, right = 0f, bottom = T) { drawRoundRect(p.pink, tl, sz, r) }
    // seam between the two halves
    drawLine(Color(0xFF17332D).copy(alpha = 0.18f), Offset(0f, -T / 2 + 2f), Offset(0f, T / 2 - 2f), strokeWidth = 2.5f * density)
    // glossy highlight
    drawLine(
        Color.White.copy(alpha = 0.6f),
        Offset(-L * 0.34f, -T * 0.29f), Offset(-L * 0.12f, -T * 0.29f),
        strokeWidth = 6f * density, cap = StrokeCap.Round,
    )
    drawLine(Color.White.copy(alpha = 0.6f), Offset(-L * 0.06f, -T * 0.29f), Offset(-L * 0.03f, -T * 0.29f), 6f * density, cap = StrokeCap.Round)
    // sticker outline
    drawRoundRect(Color(0xFF17332D), tl, sz, r, style = Stroke(width = 3.2f * density))
}

private fun DrawScope.drawFace(mood: Mood, T: Float, blink: Float, p: Palette) {
    val ink = Color(0xFF17332D)
    val ex = T * 0.34f
    val ey = -T * 0.02f
    val er = T * 0.072f
    val line = Stroke(width = 3.4f * density, cap = StrokeCap.Round)

    // cheeks
    if (mood == Mood.HAPPY || mood == Mood.WAITING) {
        val a = if (mood == Mood.HAPPY) 0.55f else 0.3f
        drawCircle(p.coral.copy(alpha = a * 0.6f), T * 0.085f, Offset(-T * 0.6f, T * 0.15f))
        drawCircle(p.coral.copy(alpha = a * 0.6f), T * 0.085f, Offset(T * 0.6f, T * 0.15f))
    }

    for (side in listOf(-1f, 1f)) {
        val e = Offset(side * ex, ey)
        when (mood) {
            Mood.WAITING -> {
                val h = (er * 2 * (1f - blink)).coerceAtLeast(2.5f * density)
                drawOval(ink, Offset(e.x - er, e.y - h / 2), Size(er * 2, h))
                if (blink < 0.5f) drawCircle(Color.White, er * 0.32f, Offset(e.x + er * 0.32f, e.y - er * 0.35f))
            }
            Mood.HAPPY -> {
                val path = Path().apply {
                    moveTo(e.x - er * 1.1f, e.y + er * 0.5f)
                    quadraticTo(e.x, e.y - er * 1.5f, e.x + er * 1.1f, e.y + er * 0.5f)
                }
                drawPath(path, ink, style = line)
            }
            Mood.WORRIED -> {
                drawCircle(ink, er * 0.85f, e)
                // brows: inner end raised
                drawLine(
                    ink,
                    Offset(e.x + side * er * 1.3f, e.y - er * 1.7f),
                    Offset(e.x - side * er * 1.0f, e.y - er * 2.5f),
                    strokeWidth = 3f * density, cap = StrokeCap.Round,
                )
            }
            Mood.SLEEPY -> {
                val path = Path().apply {
                    moveTo(e.x - er * 1.1f, e.y)
                    quadraticTo(e.x, e.y + er * 1.3f, e.x + er * 1.1f, e.y)
                }
                drawPath(path, ink, style = line)
            }
        }
    }

    val m = Offset(0f, T * 0.2f)
    when (mood) {
        Mood.WAITING -> drawOval(ink, Offset(m.x - T * 0.045f, m.y - T * 0.04f), Size(T * 0.09f, T * 0.1f))
        Mood.HAPPY -> {
            val w = T * 0.3f
            drawArc(ink, 0f, 180f, true, Offset(m.x - w / 2, m.y - w * 0.42f), Size(w, w * 0.8f))
            drawArc(p.coral, 20f, 140f, true, Offset(m.x - w * 0.28f, m.y + w * 0.02f), Size(w * 0.56f, w * 0.34f))
        }
        Mood.WORRIED -> {
            val w = T * 0.26f
            val path = Path().apply {
                moveTo(m.x - w / 2, m.y + T * 0.03f)
                quadraticTo(m.x - w / 4, m.y - T * 0.05f, m.x, m.y + T * 0.03f)
                quadraticTo(m.x + w / 4, m.y + T * 0.11f, m.x + w / 2, m.y + T * 0.03f)
            }
            drawPath(path, ink, style = line)
            // sweat drop
            val d = Offset(T * 0.78f, -T * 0.3f)
            val drop = Path().apply {
                moveTo(d.x, d.y - T * 0.13f)
                quadraticTo(d.x + T * 0.08f, d.y, d.x, d.y + T * 0.04f)
                quadraticTo(d.x - T * 0.08f, d.y, d.x, d.y - T * 0.13f)
            }
            drawPath(drop, Color(0xFF8FD3F4))
        }
        Mood.SLEEPY -> drawLine(ink, Offset(m.x - T * 0.05f, m.y), Offset(m.x + T * 0.05f, m.y), 3f * density, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawZs(
    origin: Offset, phase: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
) {
    for (i in 0 until 3) {
        val t = (phase + i / 3f) % 1f
        val alpha = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
        val s = 0.6f + t * 0.7f
        val o = origin + Offset(t * 28f * density, -t * 46f * density)
        val layout = measurer.measure("z", style.copy(fontSize = style.fontSize * s, color = style.color.copy(alpha = alpha.coerceIn(0f, 1f))))
        drawText(layout, topLeft = o)
    }
}

private fun DrawScope.drawSparkles(c: Offset, L: Float, t: Float, p: Palette) {
    val colors = listOf(p.butter, p.pink, p.leaf)
    val n = 8
    for (i in 0 until n) {
        val a = (i * (360f / n) + 12f) * (Math.PI / 180f).toFloat()
        val r = L * (0.48f + 0.32f * t)
        val pos = c + Offset(cos(a) * r, sin(a) * r * 0.8f)
        val s = (1f - t) * 11f * density * (if (i % 2 == 0) 1f else 0.7f)
        val col = colors[i % 3].copy(alpha = 1f - t)
        val star = Path().apply {
            moveTo(pos.x, pos.y - s)
            quadraticTo(pos.x, pos.y, pos.x + s, pos.y)
            quadraticTo(pos.x, pos.y, pos.x, pos.y + s)
            quadraticTo(pos.x, pos.y, pos.x - s, pos.y)
            quadraticTo(pos.x, pos.y, pos.x, pos.y - s)
            close()
        }
        drawPath(star, col)
    }
}
