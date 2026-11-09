package com.pank.vsevodnom.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.map.FullScreen
import com.pank.vsevodnom.ui.theme.Pal
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Оценка дня звёздами (1–5) — в настройках «review»: день → звёзды. */
object DayRating {
    private fun p(ctx: Context) = ctx.getSharedPreferences("review", Context.MODE_PRIVATE)
    private fun all(ctx: Context) = runCatching { JSONObject(p(ctx).getString("stars", "{}")!!) }.getOrDefault(JSONObject())
    fun get(ctx: Context, day: Long): Int = all(ctx).optInt(day.toString(), 0)
    fun set(ctx: Context, day: Long, v: Int) { val o = all(ctx); if (v == 0) o.remove(day.toString()) else o.put(day.toString(), v); p(ctx).edit().putString("stars", o.toString()).apply() }
    fun range(ctx: Context, from: Long, to: Long): Map<Long, Int> { val o = all(ctx); return (from..to).mapNotNull { d -> o.optInt(d.toString(), 0).takeIf { it > 0 }?.let { d to it } }.toMap() }
}

/** Плитка «Мои успехи» в конце сводки. */
@Composable
internal fun SuccessTile(tasks: List<TaskItem>, logs: List<HabitLog>, habitsDue: Int, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val doneToday = tasks.count { it.done && it.doneAt != null && java.time.Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate() == today }
    val habitsDone = logs.count { it.epochDay == today.toEpochDay() }
    val stars = DayRating.get(ctx, today.toEpochDay())
    val title = when {
        today.month != today.plusDays(1).month && today.monthValue == 12 -> "Мои успехи · итоги года"
        today.month != today.plusDays(1).month -> "Мои успехи · итоги месяца"
        today.dayOfWeek == java.time.DayOfWeek.SUNDAY -> "Мои успехи · итоги недели"
        else -> "Мои успехи"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFE7F2EA)).clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2DB37A)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.EmojiEvents, null, tint = Color.White) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFF1E6B47))
            Text("Сделано задач: $doneToday · привычек: $habitsDone из $habitsDue" + if (stars > 0) " · " + "★".repeat(stars) else " · оцени день", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
    }
}

private data class Period(val title: String, val from: LocalDate, val to: LocalDate)

/** Окно «Мои успехи»: день (сделано / не сделано, звёзды, сравнение со средним), неделя, месяц, год против прошлых. */
@Composable
internal fun DayReviewScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val tasks by db.todo().tasks().collectAsState(initial = emptyList())
    val habits by db.todo().habits().collectAsState(initial = emptyList())
    val logs by remember { db.todo().habitLogs(today.minusDays(800).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    val moods by db.life().moods().collectAsState(initial = emptyList())
    var stars by remember { mutableIntStateOf(DayRating.get(ctx, today.toEpochDay())) }
    val defTab = when { today.month != today.plusDays(1).month && today.monthValue == 12 -> 3; today.month != today.plusDays(1).month -> 2; today.dayOfWeek == java.time.DayOfWeek.SUNDAY -> 1; else -> 0 }
    var tab by remember { mutableIntStateOf(defTab) }

    fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun doneIn(a: LocalDate, b: LocalDate) = tasks.count { it.done && it.doneAt != null && day(it.doneAt) in a..b }
    fun habitsIn(a: LocalDate, b: LocalDate) = logs.filter { it.epochDay in a.toEpochDay()..b.toEpochDay() }.sumOf { it.count }
    fun moodIn(a: LocalDate, b: LocalDate) = moods.filter { day(it.at) in a..b }.map { it.level }.takeIf { it.isNotEmpty() }?.average()
    fun starsIn(a: LocalDate, b: LocalDate) = DayRating.range(ctx, a.toEpochDay(), b.toEpochDay()).values.takeIf { it.isNotEmpty() }?.average()
    /** Очки продуктивности: задача — 2, отметка привычки — 1. */
    fun score(a: LocalDate, b: LocalDate) = doneIn(a, b) * 2 + habitsIn(a, b)

    FullScreen("Мои успехи", onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TabRow(tab, containerColor = Pal.Bg) {
                listOf("День", "Неделя", "Месяц", "Год").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> {
                    val doneList = tasks.filter { it.done && it.doneAt != null && day(it.doneAt) == today }
                    val failed = tasks.filter { !it.done && it.dueDay != null && it.dueDay <= today.toEpochDay() }
                    val dueHabits = habits.filter { Todo.isDue(it, today, logs.filter { l -> l.epochDay == today.toEpochDay() }) }
                    val doneHabits = dueHabits.filter { h -> (logs.firstOrNull { it.habitId == h.id && it.epochDay == today.toEpochDay() }?.count ?: 0) >= h.perDay }
                    Card("Как я провёл день?") {
                        Row { (1..5).forEach { i -> Text(if (i <= stars) "★" else "☆", fontSize = 36.sp, color = Color(0xFFE9A92B), modifier = Modifier.clickable { stars = if (stars == i) 0 else i; DayRating.set(ctx, today.toEpochDay(), stars) }.padding(4.dp)) } }
                        Text(when (stars) { 0 -> "Оцени день — доволен ли собой"; 1 -> "Тяжёлый день. Завтра — новый."; 2 -> "Так себе. Что мешало?"; 3 -> "Нормально."; 4 -> "Хороший день!"; else -> "Отличный день, гордись собой!" },
                            style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    }
                    val sToday = score(today, today)
                    val week = (1..7).map { score(today.minusDays(it.toLong()), today.minusDays(it.toLong())) }
                    val avg = week.average()
                    Card("Продуктивность") {
                        Text("$sToday очков", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(when { avg == 0.0 && sToday == 0 -> "Пока без дел"; avg == 0.0 -> "Лучше, чем за прошлую неделю"
                            sToday >= avg * 1.2 -> "На ${((sToday / avg - 1) * 100).toInt()}% продуктивнее среднего за неделю"
                            sToday <= avg * 0.8 -> "На ${((1 - sToday / avg) * 100).toInt()}% меньше среднего за неделю"; else -> "Как обычно" },
                            style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                        Bars((6 downTo 0).map { val d = today.minusDays(it.toLong()); d.format(DateTimeFormatter.ofPattern("EE", java.util.Locale("ru"))) to score(d, d).toDouble() })
                        Text("Задача — 2 очка, отметка привычки — 1.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                    Card("Сделано (${doneList.size + doneHabits.size})") {
                        if (doneList.isEmpty() && doneHabits.isEmpty()) Text("Пока ничего", color = Pal.Muted)
                        doneList.forEach { Text("✓ " + it.title, color = Pal.MintInk) }
                        doneHabits.forEach { Text("✓ " + it.title + " (привычка)", color = Pal.MintInk) }
                    }
                    val missed = failed.size + (dueHabits.size - doneHabits.size)
                    Card("Не справился ($missed)") {
                        if (missed == 0) Text("Всё сделано 👏", color = Pal.MintInk)
                        failed.forEach { Text("• " + it.title + if (it.dueDay!! < today.toEpochDay()) " — просрочено" else "", color = Pal.RoseInk) }
                        dueHabits.filter { it !in doneHabits }.forEach { Text("• " + it.title + " (привычка)", color = Pal.RoseInk) }
                    }
                    moodIn(today, today)?.let { m -> Card("Настроение") { Text("Среднее за день: %.1f из 5".format(m), style = MaterialTheme.typography.bodyLarge) } }
                }
                else -> {
                    val periods: List<Period> = when (tab) {
                        1 -> { val mon = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); (0..5).map { val a = mon.minusWeeks(it.toLong()); Period(if (it == 0) "Эта неделя" else a.format(DateTimeFormatter.ofPattern("dd.MM")), a, a.plusDays(6)) } }
                        2 -> { val f = today.withDayOfMonth(1); (0..5).map { val a = f.minusMonths(it.toLong()); Period(a.format(DateTimeFormatter.ofPattern("LLLL", java.util.Locale("ru"))).replaceFirstChar { c -> c.uppercase() }, a, a.plusMonths(1).minusDays(1)) } }
                        else -> { val f = today.withDayOfYear(1); (0..2).map { val a = f.minusYears(it.toLong()); Period(a.year.toString(), a, a.plusYears(1).minusDays(1)) } }
                    }
                    val cur = periods.first(); val prev = periods.getOrNull(1)
                    val sCur = score(cur.from, minOf(cur.to, today)); val sPrev = prev?.let { score(it.from, it.to) } ?: 0
                    Card(cur.title + " против прошлых") {
                        Text("$sCur очков", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (prev != null) Text(when {
                            sPrev == 0 && sCur == 0 -> "Пока без дел"; sPrev == 0 -> "Прошлый период был пустым"
                            sCur >= sPrev -> "+${((sCur.toDouble() / sPrev - 1) * 100).toInt()}% к прошлому периоду (${prev.title})"
                            else -> "−${((1 - sCur.toDouble() / sPrev) * 100).toInt()}% к прошлому периоду (${prev.title})"
                        }, style = MaterialTheme.typography.bodyMedium, color = if (sCur >= sPrev) Pal.MintInk else Pal.RoseInk)
                        Bars(periods.reversed().map { it.title.take(10) to score(it.from, minOf(it.to, today)).toDouble() })
                    }
                    Card("Подробно") {
                        periods.forEach { p ->
                            val st = starsIn(p.from, minOf(p.to, today)); val md = moodIn(p.from, minOf(p.to, today))
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(p.title, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (p == cur) FontWeight.Bold else FontWeight.Normal)
                                Text("задач ${doneIn(p.from, p.to)} · привычек ${habitsIn(p.from, p.to)}" + (st?.let { " · ★%.1f".format(it) } ?: "") + (md?.let { " · настр. %.1f".format(it) } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Bars(items: List<Pair<String, Double>>) {
    val max = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        items.forEachIndexed { i, (label, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(v.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                Box(Modifier.fillMaxWidth().height((70 * (v / max)).coerceAtLeast(2.0).dp).clip(RoundedCornerShape(6.dp)).background(if (i == items.lastIndex) Color(0xFF2DB37A) else Color(0xFFBFD9C8)))
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
