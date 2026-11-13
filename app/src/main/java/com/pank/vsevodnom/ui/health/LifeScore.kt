package com.pank.vsevodnom.ui.health

import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.SectionLabel
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Одна сторона жизни: оценка 0…10, вес в общей оценке, что хорошо и что плохо. */
data class LifePart(val name: String, val score: Double, val weight: Double, val note: String, val good: String?, val bad: String?)

/**
 * «Как я проживаю жизнь» по 10-балльной шкале. Считается только по тому, что реально есть в приложении:
 * движение и сон (Health Connect), настроение, привычки, дела, фокус, деньги, вес. Нет данных — сторона не учитывается.
 */
object LifeScore {
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun clamp(v: Double) = v.coerceIn(0.0, 10.0)

    suspend fun compute(ctx: Context, hs: HealthSummary?): List<LifePart> = withContext(Dispatchers.IO) {
        val db = AppDb.get(ctx)
        val today = LocalDate.now()
        val weekAgo = today.minusDays(6)
        val out = ArrayList<LifePart>()

        // Движение: шаги к цели (треки на карте сюда НЕ идут — импорт из Guru давал «26 000 км» и 10 баллов)
        val steps = hs?.weekSteps?.map { it.second }?.filter { it > 0 }
        if (!steps.isNullOrEmpty()) {
            val avg = steps.average()
            val goal = com.pank.vsevodnom.ui.home.StepGoal.value(ctx, runCatching { db.weight().all().first().lastOrNull()?.kg }.getOrNull()).toDouble()
            val workouts = hs.workouts.count { !it.startTime.atZone(ZoneId.systemDefault()).toLocalDate().isBefore(weekAgo) }
            val s = clamp(avg / goal * 10 + minOf(workouts, 3) * 0.5)
            out += LifePart("Движение", s, 1.5, "в среднем ${avg.toInt()} шагов в день при цели ${goal.toInt()}" + (if (workouts > 0) ", тренировок: $workouts" else ""),
                if (s >= 7) "Много двигаешься — это лучшее, что можно сделать для здоровья и настроения." else null,
                if (s < 5) "Мало движения: до цели не хватает ${(goal - avg).toInt()} шагов в день — это около ${((goal - avg) / 100).toInt()} минут прогулки." else null)
        }

        // Сон
        hs?.sleep?.let { d ->
            val h = d.toMinutes() / 60.0
            val s = clamp(when { h in 7.0..9.0 -> 10.0; h > 9 -> 10 - (h - 9) * 2; else -> 10 - (7 - h) * 2.5 })
            out += LifePart("Сон", s, 1.5, "прошлой ночью ${d.toHours()} ч ${d.toMinutes() % 60} мин",
                if (s >= 8) "Высыпаешься — так держать." else null,
                if (s < 6) (if (h < 7) "Сна мало. Попробуй ложиться на 30–40 минут раньше и убрать телефон за час до сна." else "Сна многовато — бывает от усталости или недосыпа в будни.") else null)
        }

        // Настроение
        val moods = runCatching { db.life().moods().first() }.getOrDefault(emptyList()).filter { !day(it.at).isBefore(weekAgo) }
        if (moods.isNotEmpty()) {
            val avg = moods.map { it.level }.average()
            val s = clamp((avg - 1) / 4 * 10)
            out += LifePart("Настроение", s, 2.0, "среднее ${"%.1f".format(avg).replace('.', ',')} из 5 по ${moods.size} записям",
                if (s >= 7) "Неделя в целом в плюсе по настроению." else null,
                if (s < 5) "Настроение чаще плохое. Посмотри в дневнике, что повторяется в такие дни — недосып, нагрузка, люди. Если затянулось — поговори с близким или специалистом." else null)
        }

        // Привычки
        val habits = runCatching { db.todo().habitsSync() }.getOrDefault(emptyList())
        if (habits.isNotEmpty()) {
            val logs = runCatching { db.todo().habitLogs(weekAgo.toEpochDay(), today.toEpochDay()).first() }.getOrDefault(emptyList())
            var due = 0; var done = 0
            (0..6).forEach { k ->
                val d = weekAgo.plusDays(k.toLong())
                habits.filter { Todo.isDue(it, d, logs) }.forEach { h -> due++; if ((logs.firstOrNull { it.habitId == h.id && it.epochDay == d.toEpochDay() }?.count ?: 0) >= h.perDay) done++ }
            }
            if (due > 0) {
                val r = done.toDouble() / due
                out += LifePart("Привычки", clamp(r * 10), 1.0, "выполнено $done из $due за неделю",
                    if (r >= 0.75) "Привычки держатся — дисциплина работает." else null,
                    if (r < 0.5) "Привычки проседают. Оставь 2–3 самые важные и поставь напоминания на удобное время." else null)
            }
        }

        // Дела
        val tasks = runCatching { db.todo().tasks().first() }.getOrDefault(emptyList())
        if (tasks.isNotEmpty()) {
            val overdue = tasks.count { !it.done && (it.dueDay ?: Long.MAX_VALUE) < today.toEpochDay() }
            val doneWeek = tasks.count { it.done && it.doneAt != null && !day(it.doneAt!!).isBefore(weekAgo) }
            val s = clamp(7.0 + doneWeek * 0.4 - overdue * 1.2)
            out += LifePart("Дела", s, 1.0, "сделано за неделю: $doneWeek, просрочено: $overdue",
                if (s >= 7) "Дела под контролем." else null,
                if (overdue >= 3) "Висит $overdue просроченных задач. Разбери: что-то сделать сегодня, что-то перенести, лишнее — удалить." else null)
        }

        // Экранное время (0.45): средние полные дни за неделю
        val scr = com.pank.vsevodnom.data.ScreenTime.byDay(ctx, 8).filterKeys { it.isBefore(today) && !it.isBefore(weekAgo) }.values.filter { it > 0 }
        if (scr.isNotEmpty()) {
            val avg = scr.average(); val s = com.pank.vsevodnom.data.ScreenTime.score(avg)
            out += LifePart("Экран", s, 1.0, "в среднем ${"%.1f".format(avg / 60).replace('.', ',')} ч в день",
                if (s >= 7) "Телефон не съедает день — время остаётся на жизнь." else null,
                if (s < 5) "Телефон забирает много времени. Поставь лимиты на 2–3 самых «залипательных» приложения и не бери телефон в кровать." else null)
        }

        // Фокус
        val focus = runCatching { db.todo().focus().first() }.getOrDefault(emptyList()).filter { it.completed && !day(it.startedAt).isBefore(weekAgo) }
        if (focus.isNotEmpty()) {
            val min = focus.sumOf { it.minutes }
            out += LifePart("Фокус", clamp(min / 30.0), 0.7, "$min минут сосредоточенной работы",
                if (min >= 240) "Хорошая концентрация — лес растёт." else null,
                if (min < 90) "Мало времени в фокусе. Пара сессий по 25 минут в день — уже результат." else null)
        }

        // Деньги: темп трат этого месяца против прошлых трёх
        val txns = runCatching { db.money().allTxns().first() }.getOrDefault(emptyList())
        if (txns.size >= 10) {
            val ms = today.withDayOfMonth(1)
            fun spent(from: LocalDate, to: LocalDate) = txns.filter { it.type == MoneyRepo.EXPENSE && day(it.at).let { d -> !d.isBefore(from) && d.isBefore(to) } }.sumOf { it.amount }
            fun income(from: LocalDate, to: LocalDate) = txns.filter { it.type == MoneyRepo.INCOME && day(it.at).let { d -> !d.isBefore(from) && d.isBefore(to) } }.sumOf { it.amount }
            val now = spent(ms, today.plusDays(1))
            val pace = now / today.dayOfMonth * ms.lengthOfMonth()
            val prev = (1..3).map { spent(ms.minusMonths(it.toLong()), ms.minusMonths(it - 1L)) }.filter { it > 0 }
            val inc = income(ms.minusMonths(3), today.plusDays(1))
            val exp3 = spent(ms.minusMonths(3), today.plusDays(1))
            if (prev.isNotEmpty() || inc > 0) {
                val avgPrev = prev.average().takeIf { !it.isNaN() } ?: pace
                val ratio = if (avgPrev > 0) pace / avgPrev else 1.0
                var s = clamp(10 - (ratio - 0.8) * 10)
                if (inc > 0) s = clamp((s + clamp((inc - exp3) / inc * 20 + 5)) / 2)
                out += LifePart("Деньги", s, 1.0, "в этом месяце ${(now).toInt()} грн, к концу месяца ≈ ${pace.toInt()} (обычно ${avgPrev.toInt()})",
                    if (s >= 7) "Траты в норме относительно прошлых месяцев." else null,
                    if (s < 5) "Тратишь быстрее обычного. Загляни в «Финансы → Статистика»: какая категория выросла сильнее всего." else null)
            }
        }

        // Вес — движение к цели
        val goal = ctx.getSharedPreferences("weight", Context.MODE_PRIVATE).getFloat("goal", 0f).toDouble()
        val w = runCatching { db.weight().all().first() }.getOrDefault(emptyList())
        if (goal > 0 && w.size >= 2) {
            val last = w.last()
            val monthAgo = w.lastOrNull { it.epochDay <= last.epochDay - 25 } ?: w.first()
            val towards = abs(monthAgo.kg - goal) - abs(last.kg - goal)
            val s = clamp(5 + towards * 3)
            out += LifePart("Вес", s, 1.0, "${"%.1f".format(last.kg).replace('.', ',')} кг, цель ${"%.1f".format(goal).replace('.', ',')}",
                if (towards > 0.3) "Вес идёт к цели: ${"%.1f".format(towards).replace('.', ',')} кг за месяц." else null,
                if (towards < -0.3) "Вес уходит от цели. Проверь сон и перекусы — обычно дело в них." else null)
        }
        out
    }

    fun total(parts: List<LifePart>): Double? = parts.takeIf { it.isNotEmpty() }?.let { p -> p.sumOf { it.score * it.weight } / p.sumOf { it.weight } }

    fun verdict(v: Double) = when {
        v >= 8.5 -> "Отличная неделя — живёшь в полную силу"
        v >= 7 -> "Хорошо, есть пара мест, где можно лучше"
        v >= 5 -> "Средне: часть сторон проседает"
        v >= 3 -> "Тяжёлый период — начни с одного пункта ниже"
        else -> "Совсем непросто. Сон, движение и разговор с близкими — первые шаги"
    }
}

/** Карточка в «Здоровье»: общая оценка, чем доволен, что подтянуть, раскладка по сторонам. */
@Composable
fun LifeScoreCard(hs: HealthSummary?, version: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var parts by remember { mutableStateOf<List<LifePart>?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(hs, version) { parts = runCatching { LifeScore.compute(ctx, hs) }.getOrDefault(emptyList()) }
    val p = parts ?: return
    val total = LifeScore.total(p)
    AppCard(Modifier.fillMaxWidth(), color = Color(0xFFEDE7FB), onClick = { open = !open }) {
        Text("Как я проживаю жизнь", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
        if (total == null) {
            Text("Пока мало данных. Отмечай настроение, привычки, дела и подключи Health Connect — оценка появится сама.", style = MaterialTheme.typography.bodyMedium)
            return@AppCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                val col = when { total >= 7 -> Color(0xFF2DB37A); total >= 5 -> Color(0xFFF2A33A); else -> Color(0xFFE5485D) }
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Color.White, 135f, 270f, false, style = Stroke(14f, cap = StrokeCap.Round))
                    drawArc(col, 135f, (270 * total / 10).toFloat(), false, style = Stroke(14f, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.1f".format(total).replace('.', ','), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold))
                    Text("из 10", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(LifeScore.verdict(total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        val goods = p.mapNotNull { it.good }; val bads = p.sortedBy { it.score }.mapNotNull { it.bad }
        if (goods.isNotEmpty()) {
            Spacer(Modifier.height(10.dp)); SectionLabel("Чем доволен", Color(0xFF2E8A5E))
            goods.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp)) }
        }
        if (bads.isNotEmpty()) {
            Spacer(Modifier.height(10.dp)); SectionLabel("Что подтянуть", Pal.RoseInk)
            bads.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        if (open) p.sortedByDescending { it.score }.forEach { part ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Row { Text(part.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("%.1f".format(part.score).replace('.', ','), style = MaterialTheme.typography.titleSmall) }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) {
                    Box(Modifier.fillMaxWidth((part.score / 10).toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                        .background(when { part.score >= 7 -> Color(0xFF2DB37A); part.score >= 5 -> Color(0xFFF2A33A); else -> Color(0xFFE5485D) }))
                }
                Text(part.note, style = MaterialTheme.typography.bodySmall)
            }
        } else Text("Нажми — разбор по каждой стороне (${p.size})", style = MaterialTheme.typography.labelMedium, color = Pal.LilacInk)
    }
}

/** Короткая карточка для главной: оценка, одна фраза и тонкая полоска шагов. Нажатие — в «Здоровье». */
@Composable
fun LifeScoreMini(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hs by remember { mutableStateOf<HealthSummary?>(null) }
    var parts by remember { mutableStateOf<List<LifePart>?>(null) }
    LaunchedEffect(Unit) {
        hs = runCatching { if (HealthRepo.status(ctx) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE && HealthRepo.granted(ctx).isNotEmpty()) HealthRepo.summary(ctx) else null }.getOrNull()
        parts = runCatching { LifeScore.compute(ctx, hs) }.getOrDefault(emptyList())
    }
    val total = parts?.let { LifeScore.total(it) } ?: return
    val col = when { total >= 7 -> Color(0xFF2DB37A); total >= 5 -> Color(0xFFF2A33A); else -> Color(0xFFE5485D) }
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Pal.Line, 135f, 270f, false, style = Stroke(8f, cap = StrokeCap.Round))
                drawArc(col, 135f, (270 * total / 10).toFloat(), false, style = Stroke(8f, cap = StrokeCap.Round))
            }
            Text("%.1f".format(total).replace('.', ','), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(LifeScore.verdict(total), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            hs?.let { h ->
                val goal = 8000L
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.weight(1f).height(5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(Pal.Line)) {
                        Box(Modifier.fillMaxWidth((h.steps.toFloat() / goal).coerceIn(0f, 1f)).fillMaxHeight().background(Pal.MintInk))
                    }
                    Text("  " + "%,d".format(h.steps).replace(',', ' ') + " шагов", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            } ?: Text("как я проживаю жизнь", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
        Icon(androidx.compose.material.icons.Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
    }
}
