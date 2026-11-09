package com.pank.vsevodnom.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.health.HealthRepo
import com.pank.vsevodnom.ui.health.HealthSummary
import com.pank.vsevodnom.ui.health.LifePart
import com.pank.vsevodnom.ui.health.LifeScore
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.weather.WeatherGlyph
import com.pank.vsevodnom.ui.weather.fmtT
import java.time.LocalDate

private val HeaderInk = Color(0xFF1F3D63)

/** Погода: значок, температура сейчас и ↑самая тёплая / ↓самая холодная за день. */
@Composable
internal fun WeatherPill(w: WeatherData?, onClick: () -> Unit) {
    Row(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.65f)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (w == null) { Icon(Icons.Rounded.Cloud, "Погода", tint = HeaderInk, modifier = Modifier.size(20.dp)); return@Row }
        WeatherGlyph(wmo(w.code).second, w.isDay, Modifier.size(26.dp), animated = true)
        Spacer(Modifier.width(4.dp))
        Text(fmtT(w.temp), style = MaterialTheme.typography.titleSmall, color = HeaderInk)
        w.days.firstOrNull { it.date == LocalDate.now() }?.let { d ->
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text("↑" + fmtT(d.tMax), fontSize = 10.sp, lineHeight = 11.sp, color = Color(0xFFC0563C), fontWeight = FontWeight.SemiBold)
                Text("↓" + fmtT(d.tMin), fontSize = 10.sp, lineHeight = 11.sp, color = Color(0xFF3B6EA5), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Цвет оценки 0…10: красный → жёлтый → зелёный, плавно. */
internal fun scoreColor(v: Double): Color {
    val red = Color(0xFFE5484D); val yellow = Color(0xFFF2B33A); val green = Color(0xFF2DB37A)
    val t = (v / 10.0).coerceIn(0.0, 1.0).toFloat()
    return if (t < 0.5f) lerp(red, yellow, t / 0.5f) else lerp(yellow, green, (t - 0.5f) / 0.5f)
}

/** Образ жизни 0.0–10.0 — круг с цифрой; цвет от красного к зелёному. Нажатие — подробности. */
@Composable
internal fun LifeBadge(score: Double?, onClick: () -> Unit, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(score?.let { scoreColor(it) } ?: BadgeGrey).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(score?.let { "%.1f".format(it).replace('.', ',') } ?: "—", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Шаги за сегодня — овал «6,2 тыс шагов». */
@Composable
internal fun StepsPill(steps: Long?, goal: Long, onClick: () -> Unit) {
    if (steps == null) return
    val k = steps / 1000.0
    val reached = steps >= goal
    Row(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (reached) Color(0xFF2DB37A) else Color.White.copy(alpha = 0.65f)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = if (reached) Color.White else HeaderInk, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text((if (k >= 10) "%.0f" else "%.1f").format(k).replace('.', ',') + " тыс", style = MaterialTheme.typography.titleSmall, color = if (reached) Color.White else HeaderInk)
    }
}

/** Подробности оценки образа жизни: по сторонам жизни, что хорошо и что подтянуть. */
@Composable
internal fun LifeDialog(parts: List<LifePart>, hs: HealthSummary?, onOpenHealth: () -> Unit, onDismiss: () -> Unit) {
    val total = LifeScore.total(parts)
    Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pal.Card).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifeBadge(total, {}, 56.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Как я проживаю жизнь", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    total?.let { Text(LifeScore.verdict(it), style = MaterialTheme.typography.bodyMedium, color = Pal.Muted) }
                }
            }
            if (parts.isEmpty()) Text("Пока мало данных: вес, настроение, привычки, задачи, шаги и сон (Здоровье → Health Connect).", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
            parts.forEach { p ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("%.1f".format(p.score).replace('.', ','), style = MaterialTheme.typography.titleSmall, color = scoreColor(p.score), fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Line)) {
                        Box(Modifier.fillMaxWidth((p.score / 10).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(scoreColor(p.score)))
                    }
                    if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
                    p.good?.let { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = Pal.MintInk) }
                    p.bad?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
                }
            }
            hs?.let { Text("Сегодня: " + "%,d".format(it.steps).replace(',', ' ') + " шагов" + (it.sleep?.let { s -> " · сон ${s.toHours()} ч ${s.toMinutes() % 60} мин" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onOpenHealth) { Text("Открыть «Здоровье»") }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("Закрыть") }
            }
        }
    }
}

/** Данные шапки, которые грузятся в фоне: здоровье и оценка жизни. */
internal class HeaderData {
    var hs by mutableStateOf<HealthSummary?>(null)
    var parts by mutableStateOf<List<LifePart>>(emptyList())
    suspend fun load(ctx: android.content.Context) {
        hs = runCatching { if (HealthRepo.status(ctx) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE && HealthRepo.granted(ctx).isNotEmpty()) HealthRepo.summary(ctx) else null }.getOrNull()
        parts = runCatching { LifeScore.compute(ctx, hs) }.getOrDefault(emptyList())
    }
}

@Composable
internal fun rememberHeaderData(tick: Int): HeaderData {
    val ctx = LocalContext.current
    val d = remember { HeaderData() }
    LaunchedEffect(tick) { d.load(ctx) }
    return d
}
