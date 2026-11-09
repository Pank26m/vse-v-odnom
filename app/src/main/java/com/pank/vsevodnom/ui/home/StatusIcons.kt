package com.pank.vsevodnom.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AlertState
import com.pank.vsevodnom.data.AlertsApi
import com.pank.vsevodnom.data.PowerState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val BadgeGreen = Color(0xFF4CB86B)
val BadgeYellow = Color(0xFFF2BE45)
val BadgeRed = Color(0xFFE5484D)
val BadgeGrey = Color(0xFFA9AEB8)

/** Уровень тревоги в Киеве: 0 — нет, 1 — жёлтая (угроза), 2 — красная. Берём alert_level, а не просто «есть запись». */
fun kyivAlertLevel(s: AlertState?): Int? {
    s ?: return null
    val items = s.items.filter { it.oblast == AlertsApi.KYIV_CITY }
    if (s.districtLevel) return when { items.any { it.red } -> 2; items.isNotEmpty() -> 1; else -> 0 }
    return if (s.of(AlertsApi.KYIV_CITY) != com.pank.vsevodnom.data.AlertLevel.NONE) 2 else 0
}

@Composable
private fun Badge(bg: Color, onClick: () -> Unit, size: Dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** Тревога: зелёный щит — спокойно, жёлтый дрон — угроза, красная ракета — тревога. */
@Composable
fun AlertBadge(level: Int?, onClick: () -> Unit, size: Dp = 40.dp) {
    when (level) {
        2 -> Badge(BadgeRed, onClick, size) { MissileAnim(Modifier.size(size * 0.78f)) }
        1 -> Badge(BadgeYellow, onClick, size) { DroneAnim(Modifier.size(size * 0.7f)) }
        0 -> Badge(BadgeGreen, onClick, size) { Icon(Icons.Rounded.GppGood, "Тревоги нет", tint = Color.White, modifier = Modifier.size(size * 0.58f)) }
        else -> Badge(BadgeGrey, onClick, size) { Icon(Icons.Rounded.GppGood, "Тревоги", tint = Color.White, modifier = Modifier.size(size * 0.58f)) }
    }
}

/**
 * Свет: зелёный — есть, серый (зачёркнутая молния) — нет, красный — графики не действуют (экстренные отключения).
 * Меньше часа до смены (отключения или включения) — кружок растягивается в овал: «59хв» и точка справа
 * (серая — скоро отключат, зелёная — скоро включат).
 */
@Composable
fun PowerBadge(s: PowerState?, onClick: () -> Unit, size: Dp = 40.dp) {
    val bg = when { s == null -> BadgeGrey; s.emergency -> BadgeRed; s.offNow -> BadgeGrey; else -> BadgeGreen }
    val mins = if (s == null || s.emergency) null else if (s.offNow) s.nextOnInMin?.takeIf { it < 60 } else s.nextOffInMin?.takeIf { it < 60 }
    androidx.compose.foundation.layout.Row(Modifier.height(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size / 2)).background(bg).clickable(onClick = onClick)
        .padding(end = if (mins != null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Bolt, "Свет", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            if (s != null && s.offNow && !s.emergency) Canvas(Modifier.size(size * 0.6f)) {
                val a = Offset(this.size.width * 0.12f, this.size.height * 0.08f); val b = Offset(this.size.width * 0.88f, this.size.height * 0.92f)
                drawLine(bg, a, b, strokeWidth = this.size.width * 0.22f)
                drawLine(Color.White, a, b, strokeWidth = this.size.width * 0.09f, cap = StrokeCap.Round)
            }
        }
        if (mins != null) {
            androidx.compose.material3.Text("${mins}хв", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (s!!.offNow) BadgeGreen else Color(0xFF6E737D)).then(
                if (s.offNow) Modifier else Modifier))
        }
    }
}

/**
 * Ударный дрон (вид сверху): дельта-крыло, нос вперёд, кили на концах крыльев.
 * Анимация — вираж: крен вниз-вправо (крыло «опускается» — проекция сужается), потом выравнивание.
 */
@Composable
fun DroneAnim(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "drone").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width
        // 0…0.5 — заход в вираж, 0.5…1 — выход: плавно по синусу
        val bank = ((1 - cos(t * 2 * PI)) / 2).toFloat()
        val c = Offset(w / 2 + bank * w * 0.06f, w / 2 + bank * w * 0.05f)
        rotate(-10f + bank * 38f, c) {
            scale(1f - bank * 0.22f, 1f, c) { drone(c, w * 0.92f, Color.White) }
        }
    }
}

private fun DrawScope.drone(c: Offset, s: Float, col: Color) {
    val x = c.x; val y = c.y
    val wing = Path().apply {
        moveTo(x, y - s * 0.46f)              // нос
        lineTo(x + s * 0.06f, y - s * 0.3f)
        lineTo(x + s * 0.48f, y + s * 0.2f)   // правое крыло
        lineTo(x + s * 0.44f, y + s * 0.28f)
        lineTo(x + s * 0.1f, y + s * 0.22f)
        lineTo(x + s * 0.06f, y + s * 0.34f)  // хвост
        lineTo(x - s * 0.06f, y + s * 0.34f)
        lineTo(x - s * 0.1f, y + s * 0.22f)
        lineTo(x - s * 0.44f, y + s * 0.28f)
        lineTo(x - s * 0.48f, y + s * 0.2f)   // левое крыло
        lineTo(x - s * 0.06f, y - s * 0.3f)
        close()
    }
    drawPath(wing, col)
    // кили на концах крыльев и винт сзади
    drawLine(col, Offset(x + s * 0.46f, y + s * 0.12f), Offset(x + s * 0.46f, y + s * 0.32f), s * 0.05f, StrokeCap.Round)
    drawLine(col, Offset(x - s * 0.46f, y + s * 0.12f), Offset(x - s * 0.46f, y + s * 0.32f), s * 0.05f, StrokeCap.Round)
    drawLine(col, Offset(x - s * 0.1f, y + s * 0.4f), Offset(x + s * 0.1f, y + s * 0.4f), s * 0.035f, StrokeCap.Round)
}

/**
 * Крылатая ракета (вид сбоку): длинный корпус, оживальный нос, стреловидные крылья посередине и хвостовое оперение.
 * Анимация — летит с лёгким покачиванием и вращается вокруг оси (крылья по очереди «уходят» за корпус), из сопла — пламя.
 */
@Composable
fun MissileAnim(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "missile")
    val roll by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "r")
    val fly by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "f")
    Canvas(modifier) {
        val w = size.width
        val bob = sin(fly * 2 * PI).toFloat() * w * 0.035f
        val drift = sin(fly * 2 * PI + 1.2).toFloat() * w * 0.04f
        translate(drift, bob) { rotate(-28f, Offset(w / 2, w / 2)) { missile(Offset(w / 2, w / 2), w, roll, fly) } }
    }
}

private fun DrawScope.missile(c: Offset, s: Float, roll: Float, flicker: Float) {
    val white = Color.White
    val len = s * 0.86f; val r = s * 0.07f
    val x0 = c.x - len / 2; val x1 = c.x + len / 2
    val k = cos(roll * 2 * PI).toFloat()          // проекция крыла при вращении
    val front = k >= 0
    // пламя из сопла
    val fl = 0.75f + 0.25f * abs(sin(flicker * 12 * PI).toFloat())
    drawPath(Path().apply { moveTo(x0, c.y - r * 0.7f); lineTo(x0 - s * 0.16f * fl, c.y); lineTo(x0, c.y + r * 0.7f); close() }, Color(0xFFFFD58A))
    fun wings(alpha: Float) {
        val h = s * 0.2f * abs(k)
        val col = white.copy(alpha = alpha)
        val wx = c.x - len * 0.02f
        // крыло вверх и вниз (симметрично), стреловидное назад
        drawPath(Path().apply { moveTo(wx + s * 0.06f, c.y); lineTo(wx - s * 0.08f, c.y - h); lineTo(wx - s * 0.14f, c.y - h); lineTo(wx - s * 0.04f, c.y); close() }, col)
        drawPath(Path().apply { moveTo(wx + s * 0.06f, c.y); lineTo(wx - s * 0.08f, c.y + h); lineTo(wx - s * 0.14f, c.y + h); lineTo(wx - s * 0.04f, c.y); close() }, col)
        // хвостовое оперение
        val th = s * 0.12f
        drawPath(Path().apply { moveTo(x0 + s * 0.1f, c.y); lineTo(x0 + s * 0.01f, c.y - th); lineTo(x0 - s * 0.01f, c.y - th); lineTo(x0 + s * 0.02f, c.y); close() }, col)
        drawPath(Path().apply { moveTo(x0 + s * 0.1f, c.y); lineTo(x0 + s * 0.01f, c.y + th * abs(k)); lineTo(x0 - s * 0.01f, c.y + th * abs(k)); lineTo(x0 + s * 0.02f, c.y); close() }, col)
    }
    if (!front) wings(0.7f)
    // корпус с оживальным носом
    drawRoundRect(white, Offset(x0, c.y - r), Size(len - s * 0.12f, 2 * r), androidx.compose.ui.geometry.CornerRadius(r * 0.4f))
    drawPath(Path().apply {
        moveTo(x1 - s * 0.13f, c.y - r)
        quadraticTo(x1 - s * 0.02f, c.y - r * 0.8f, x1, c.y)
        quadraticTo(x1 - s * 0.02f, c.y + r * 0.8f, x1 - s * 0.13f, c.y + r)
        close()
    }, white)
    // полоска на корпусе бежит при вращении
    val sy = c.y + r * 0.6f * sin(roll * 2 * PI).toFloat()
    drawLine(Color(0xFFE5484D).copy(alpha = 0.55f), Offset(x0 + s * 0.2f, sy), Offset(x1 - s * 0.2f, sy), s * 0.02f)
    if (front) wings(1f)
}

