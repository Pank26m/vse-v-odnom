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
internal fun LifeBadge(score: Double?, onClick: () -> Unit, size: Dp = 44.dp) {
    // как «Оценка здоровья»: белый круг, дуга-спидометр заполняется с анимацией, цвет дуги и цифры — от красного к зелёному
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(score) { a.animateTo(((score ?: 0.0) / 10).toFloat(), androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Box(Modifier.size(size).clip(CircleShape).background(Color.White).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val sw = this.size.minDimension * 0.12f
            val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
            val sz = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
            val st = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Pal.Line, 135f, 270f, false, topLeft = tl, size = sz, style = st)
            if (score != null) drawArc(scoreColor(a.value * 10.0), 135f, 270f * a.value, false, topLeft = tl, size = sz, style = st)
        }
        Text(score?.let { "%.1f".format(a.value * 10.0).replace('.', ',') } ?: "—", color = score?.let { scoreColor(it) } ?: Pal.Muted,
            fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * Цель шагов: считается по росту, весу, возрасту и полу (профиль + последнее взвешивание) или задаётся вручную.
 * Чем выше индекс массы тела, тем мягче цель (суставы), по мере похудения она растёт к 10 000.
 */
object StepGoal {
    /** Ручная цель или null — тогда автоматическая. */
    fun manual(ctx: android.content.Context): Long? =
        Profile.get(ctx, "goal_steps_manual").toLongOrNull()
            ?: Profile.get(ctx, "goal_steps").toLongOrNull()?.takeIf { it != 8000L }   // старая настройка (0.28)
    fun setManual(ctx: android.content.Context, v: Long?) { Profile.set(ctx, "goal_steps_manual", v?.toString() ?: ""); Profile.set(ctx, "goal_steps", "") }

    fun auto(ctx: android.content.Context, kg: Double?): Long {
        val h = Profile.get(ctx, "height").replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..250.0 }
        var goal = 8000.0
        if (h != null && kg != null) {
            val bmi = kg / ((h / 100) * (h / 100))
            goal = 10000 - ((bmi - 25) / 10).coerceIn(0.0, 1.0) * 4000          // ИМТ 25 → 10 000, 35+ → 6 000
        }
        val age = Profile.get(ctx, "birthday").let { b -> Regex("(\\d{4})").find(b)?.value?.toIntOrNull() }?.let { java.time.LocalDate.now().year - it }
        if (age != null) goal -= when { age >= 70 -> 2500; age >= 60 -> 1500; age >= 50 -> 500; else -> 0 }
        if (Profile.get(ctx, "sex") == "женский") goal -= 500
        return (Math.round(goal / 500) * 500).coerceIn(4000, 12000)
    }
    fun value(ctx: android.content.Context, kg: Double?) = manual(ctx) ?: auto(ctx, kg)
}

/** Последний вес из базы (для цели шагов). */
@Composable
internal fun rememberLastKg(): Double? {
    val ctx = LocalContext.current
    val ws by remember { com.pank.vsevodnom.data.AppDb.get(ctx).weight().all() }.collectAsState(initial = emptyList())
    return ws.lastOrNull()?.kg
}

/** Окно цели шагов: автоматическая с пояснением, ручной ввод, сброс к автоматической. */
@Composable
internal fun StepGoalDialog(kg: Double?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val auto = StepGoal.auto(ctx, kg)
    var v by remember { mutableStateOf(StepGoal.manual(ctx)?.toString() ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Цель шагов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Автоматически: ${fmtSteps(auto)} — по росту, весу, возрасту и полу из профиля. Чем ближе вес к норме, тем выше цель (до 10 000).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                OutlinedTextField(v, { v = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Своя цель (пусто — автоматическая)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        },
        confirmButton = { TextButton({ StepGoal.setManual(ctx, v.toLongOrNull()?.takeIf { it in 1000..50000 }); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton({ StepGoal.setManual(ctx, null); onDismiss() }) { Text("Автоматически") } })
}

internal fun fmtSteps(n: Long) = "%,d".format(n).replace(',', ' ')

/** Итог дня по шагам: человечек · полоса до края · цель справа. Нажатие — цель шагов. */
@Composable
internal fun StepsBar(steps: Long?, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val kg = rememberLastKg()
    var edit by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val goal = remember(kg, tick) { StepGoal.value(ctx, kg) }
    val s = steps ?: 0L
    val done = s >= goal
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = if (done) Color(0xFF2DB37A) else Pal.Muted, modifier = Modifier.size(16.dp))
        Text(" " + (steps?.let { fmtSteps(it) } ?: "—"), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        Box(Modifier.padding(horizontal = 8.dp).size(4.dp).clip(CircleShape).background(Pal.Muted.copy(alpha = 0.5f)))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Line)) {
            Box(Modifier.fillMaxWidth((s.toFloat() / goal).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(0xFF2DB37A)))
        }
        Text("  " + fmtSteps(goal), style = MaterialTheme.typography.labelMedium, color = if (done) Color(0xFF2DB37A) else Pal.Ink, fontWeight = FontWeight.SemiBold)
        IconButton({ edit = true }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Edit, "Цель шагов", tint = Pal.Muted, modifier = Modifier.size(16.dp)) }
    }
    if (edit) StepGoalDialog(kg) { edit = false; tick++ }
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
