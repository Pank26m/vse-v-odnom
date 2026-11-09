package com.pank.vsevodnom.ui.weather

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.pank.vsevodnom.data.Scene
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Цвет неба под погоду — пастельный, без кислоты. */
fun skyColor(scene: Scene, isDay: Boolean): Color = if (!isDay) when (scene) {
    Scene.CLEAR, Scene.PARTLY -> Color(0xFFC9CDE8)
    else -> Color(0xFFC4CAD8)
} else when (scene) {
    Scene.CLEAR -> Color(0xFFCFE6F8)
    Scene.PARTLY -> Color(0xFFD3E4F4)
    Scene.CLOUDY, Scene.FOG -> Color(0xFFDDE3EA)
    Scene.DRIZZLE, Scene.RAIN -> Color(0xFFCFDCEB)
    Scene.SNOW -> Color(0xFFE4ECF4)
    Scene.THUNDER -> Color(0xFFCBCFDF)
}

private data class Particle(val x: Float, val phase: Float, val speed: Float, val len: Float, val drift: Float)

/**
 * Анимированная сцена в стиле погодных приложений середины 2010-х:
 * солнце с вращающимися лучами, плывущие облака, капли, снег, вспышки молний.
 */
@Composable
fun WeatherScene(scene: Scene, isDay: Boolean, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "w")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    val fast by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "f")
    val slow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "s")
    val particles = remember(scene) {
        val r = Random(7)
        val n = when (scene) { Scene.RAIN, Scene.THUNDER -> 70; Scene.DRIZZLE -> 40; Scene.SNOW -> 55; else -> 0 }
        List(n) { Particle(r.nextFloat(), r.nextFloat(), 0.8f + r.nextFloat() * 0.5f, 0.6f + r.nextFloat() * 0.6f, r.nextFloat() * 2 - 1) }
    }
    val stars = remember { val r = Random(3); List(40) { Offset(r.nextFloat(), r.nextFloat() * 0.6f) to r.nextFloat() } }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val showSun = scene == Scene.CLEAR || scene == Scene.PARTLY
        if (!isDay && (scene == Scene.CLEAR || scene == Scene.PARTLY)) {
            stars.forEach { (p, ph) ->
                val a = 0.35f + 0.45f * ((sin((slow + ph) * 2 * PI).toFloat() + 1) / 2)
                drawCircle(Color.White.copy(alpha = a), radius = 2.2f, center = Offset(p.x * w, p.y * h))
            }
        }
        if (showSun) {
            val c = Offset(w * 0.72f, h * 0.40f)
            if (isDay) sun(c, w * 0.11f, t * 360f * 3) else moon(c, w * 0.09f)
        }
        when (scene) {
            Scene.PARTLY -> {
                cloud(Offset(w * (0.50f + 0.05f * sin(slow * 2 * PI).toFloat()), h * 0.50f), w * 0.26f, Color.White.copy(alpha = 0.95f))
            }
            Scene.CLOUDY, Scene.FOG, Scene.DRIZZLE, Scene.RAIN, Scene.SNOW, Scene.THUNDER -> {
                val dark = scene == Scene.THUNDER || scene == Scene.RAIN
                val base = if (dark) Color(0xFFEEF1F6) else Color.White
                cloud(Offset(w * (0.18f + 0.04f * sin(slow * 2 * PI).toFloat()), h * 0.40f), w * 0.20f, base.copy(alpha = 0.75f))
                cloud(Offset(w * (0.70f - 0.05f * sin(slow * 2 * PI).toFloat()), h * 0.36f), w * 0.28f, base.copy(alpha = 0.95f))
                cloud(Offset(w * (0.45f + 0.03f * cos(slow * 2 * PI).toFloat()), h * 0.48f), w * 0.24f, base)
            }
            Scene.CLEAR -> {}
        }
        when (scene) {
            Scene.RAIN, Scene.DRIZZLE, Scene.THUNDER -> {
                val top = h * 0.52f
                val dropLen = if (scene == Scene.DRIZZLE) 10f else 22f
                particles.forEach { p ->
                    val prog = (fast * p.speed + p.phase) % 1f
                    val y = top + prog * (h - top + 40)
                    val x = p.x * w - prog * 18f
                    val a = (1f - prog).coerceIn(0f, 1f) * 0.85f
                    drawLine(
                        Color(0xFF8DB3DF).copy(alpha = a), Offset(x, y), Offset(x - 4f, y + dropLen * p.len),
                        strokeWidth = if (scene == Scene.DRIZZLE) 2.5f else 3.5f, cap = StrokeCap.Round
                    )
                }
                if (scene == Scene.THUNDER) {
                    val flash = ((slow * 9) % 1f)
                    if (flash < 0.06f) drawRect(Color.White.copy(alpha = 0.45f * (1 - flash / 0.06f)))
                    if (flash < 0.04f) bolt(Offset(w * 0.55f, h * 0.42f), h * 0.32f)
                }
            }
            Scene.SNOW -> {
                val top = h * 0.40f
                particles.forEach { p ->
                    val prog = (slow * p.speed * 1.6f + p.phase) % 1f
                    val y = top + prog * (h - top + 20)
                    val x = p.x * w + sin((prog * 4 + p.drift) * PI).toFloat() * 14f
                    drawCircle(Color.White.copy(alpha = 0.9f * (1 - prog * 0.6f)), radius = 3f + p.len * 3f, center = Offset(x, y))
                }
            }
            Scene.FOG -> {
                for (i in 0..3) {
                    val y = h * (0.55f + i * 0.1f)
                    val dx = sin((slow + i * 0.25f) * 2 * PI).toFloat() * 30f
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(w * 0.05f + dx, y), Offset(w * 0.95f + dx, y), strokeWidth = 14f, cap = StrokeCap.Round)
                }
            }
            else -> {}
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, deg: Float) {
    rotate(deg, c) {
        for (i in 0 until 10) {
            val a = i * (2 * PI / 10)
            val s = Offset(c.x + cos(a).toFloat() * r * 1.25f, c.y + sin(a).toFloat() * r * 1.25f)
            val e = Offset(c.x + cos(a).toFloat() * r * 1.65f, c.y + sin(a).toFloat() * r * 1.65f)
            drawLine(Color(0xFFF6D77B), s, e, strokeWidth = r * 0.16f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Color(0xFFFCE7A6), radius = r, center = c)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    drawCircle(Color(0xFFF7F3E3), radius = r, center = c)
    drawCircle(Color(0xFFE9E4CF), radius = r * 0.18f, center = Offset(c.x - r * 0.3f, c.y - r * 0.2f))
    drawCircle(Color(0xFFE9E4CF), radius = r * 0.12f, center = Offset(c.x + r * 0.35f, c.y + r * 0.3f))
}

/** Пышное облако: несколько «шапок» разного размера и мягкая подошва. */
private fun DrawScope.cloud(c: Offset, r: Float, color: Color) {
    drawCircle(color, radius = r * 0.42f, center = Offset(c.x - r * 0.7f, c.y + r * 0.18f))
    drawCircle(color, radius = r * 0.58f, center = Offset(c.x - r * 0.35f, c.y - r * 0.08f))
    drawCircle(color, radius = r * 0.78f, center = Offset(c.x + r * 0.12f, c.y - r * 0.28f))
    drawCircle(color, radius = r * 0.55f, center = Offset(c.x + r * 0.62f, c.y + r * 0.02f))
    drawCircle(color, radius = r * 0.4f, center = Offset(c.x + r * 0.9f, c.y + r * 0.22f))
    drawRoundRect(
        color, topLeft = Offset(c.x - r * 0.95f, c.y + r * 0.05f), size = Size(r * 1.95f, r * 0.58f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.29f)
    )
}

private fun DrawScope.bolt(top: Offset, len: Float) {
    val p = Path().apply {
        moveTo(top.x, top.y)
        lineTo(top.x - len * 0.15f, top.y + len * 0.5f)
        lineTo(top.x + len * 0.02f, top.y + len * 0.5f)
        lineTo(top.x - len * 0.1f, top.y + len)
        lineTo(top.x + len * 0.2f, top.y + len * 0.38f)
        lineTo(top.x + len * 0.03f, top.y + len * 0.38f)
        close()
    }
    drawPath(p, Color(0xFFFCE7A6))
}

/** Иконка погоды. animated = true — для главной: солнце крутится, облака плывут, дождь и снег падают, молния вспыхивает. */
@Composable
fun WeatherGlyph(scene: Scene, isDay: Boolean, modifier: Modifier = Modifier, animated: Boolean = false) {
    val inf = rememberInfiniteTransition(label = "g")
    // маленький значок — анимация быстрее, чтобы движение было заметно
    val ph = if (animated) inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p").value else 0.5f
    val slow = if (animated) inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "s").value else 0f
    val flash = if (animated) inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "f").value else 0.1f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sway = if (animated) sin(slow * 2 * PI).toFloat() * w * 0.05f else 0f
        val sunC = Offset(w * 0.62f, h * 0.36f)
        when (scene) {
            Scene.CLEAR -> if (isDay) sun(Offset(w / 2, h / 2), w * (0.24f + if (animated) sin(ph * 2 * PI).toFloat() * 0.012f else 0f), slow * 360f)
                else { moon(Offset(w / 2, h / 2), w * 0.3f); if (animated) drawCircle(Color(0xFFF7F3E3).copy(alpha = 0.25f * (0.5f + 0.5f * sin(slow * 2 * PI).toFloat())), w * 0.42f, Offset(w / 2, h / 2)) }
            Scene.PARTLY -> {
                if (isDay) sun(sunC, w * 0.18f, slow * 360f) else moon(sunC, w * 0.2f)
                cloud(Offset(w * 0.45f + sway, h * 0.55f), w * 0.3f, Color(0xFFB9C7D8))
            }
            // туман — три плывущие полосы по центру значка
            // туман — три полосы точно по центру значка (средняя — на центре, крайние симметрично), плывут чуть-чуть
            Scene.FOG -> for (i in 0..2) {
                val dx = sway * 0.8f * (if (i % 2 == 0) 1 else -1)
                val y = h / 2 + (i - 1) * h * 0.17f
                val half = w * (if (i == 1) 0.36f else 0.3f)
                drawLine(Color(0xFF9DAFC6).copy(alpha = if (i == 1) 0.95f else 0.75f), Offset(w / 2 - half + dx, y), Offset(w / 2 + half + dx, y), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
            }
            else -> {
                val cc = if (scene == Scene.THUNDER || scene == Scene.RAIN) Color(0xFF9DAFC6) else Color(0xFFB9C7D8)
                if (scene == Scene.CLOUDY && animated) cloud(Offset(w * 0.62f - sway, h * 0.3f), w * 0.22f, Color(0xFFD3DCE7))
                cloud(Offset(w * 0.5f + sway * 0.6f, h * 0.38f), w * 0.34f, cc)
                when (scene) {
                    Scene.RAIN, Scene.DRIZZLE, Scene.THUNDER -> for (i in 0..2) {
                        if (scene == Scene.THUNDER && i == 1) continue
                        val x = w * (0.3f + i * 0.2f)
                        val k = (ph + i * 0.33f) % 1f
                        val dy = k * h * 0.2f
                        drawLine(Color(0xFF6E98C9).copy(alpha = 1f - k * 0.7f), Offset(x, h * 0.66f + dy), Offset(x - w * 0.05f, h * 0.8f + dy), strokeWidth = w * (if (scene == Scene.DRIZZLE) 0.05f else 0.07f), cap = StrokeCap.Round)
                    }
                    Scene.SNOW -> for (i in 0..2) {
                        val k = (ph * 0.6f + i * 0.33f) % 1f
                        drawCircle(Color(0xFF8FA9C7).copy(alpha = 1f - k * 0.6f), radius = w * 0.06f, center = Offset(w * (0.3f + i * 0.2f) + sin((k + i) * 2 * PI).toFloat() * w * 0.04f, h * (0.7f + k * 0.24f)))
                    }
                    Scene.FOG -> for (i in 0..1) { val dx = sway * (if (i == 0) 1 else -1); drawLine(Color(0xFF9DAFC6), Offset(w * 0.15f + dx, h * (0.75f + i * 0.14f)), Offset(w * 0.85f + dx, h * (0.75f + i * 0.14f)), strokeWidth = w * 0.07f, cap = StrokeCap.Round) }
                    else -> {}
                }
                if (scene == Scene.THUNDER) {
                    // вспышка: две короткие молнии за цикл, остальное время — бледная
                    val on = flash in 0.0f..0.08f || flash in 0.14f..0.2f || !animated
                    if (on) { if (animated) drawCircle(Color(0xFFFFF4C2).copy(alpha = 0.35f), w * 0.45f, Offset(w * 0.52f, h * 0.62f)); bolt(Offset(w * 0.52f, h * 0.56f), h * 0.42f) }
                    else drawPath(Path().apply { moveTo(w * 0.52f, h * 0.56f); lineTo(w * 0.46f, h * 0.77f) }, Color(0xFFFCE7A6).copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.03f))
                }
            }
        }
    }
}
