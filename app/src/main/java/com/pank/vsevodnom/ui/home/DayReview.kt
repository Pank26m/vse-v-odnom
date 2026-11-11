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
import androidx.compose.ui.graphics.graphicsLayer
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
    private fun notes(ctx: Context) = runCatching { JSONObject(p(ctx).getString("notes", "{}")!!) }.getOrDefault(JSONObject())
    /** Комментарий к дню. */
    fun note(ctx: Context, day: Long): String = notes(ctx).optString(day.toString(), "")
    fun setNote(ctx: Context, day: Long, v: String) { val o = notes(ctx); if (v.isBlank()) o.remove(day.toString()) else o.put(day.toString(), v); p(ctx).edit().putString("notes", o.toString()).apply() }
    val LABELS = listOf("ужасно", "плохо", "нейтрально", "хорошо", "отлично")
}

/** Звёзды во всю ширину: мягкие (Material, скруглённые), под каждой — подпись. */
@Composable
fun StarRating(stars: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        (1..5).forEach { i ->
            val on = i <= stars
            val scale by androidx.compose.animation.core.animateFloatAsState(if (i == stars) 1.15f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f), label = "st")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(if (stars == i) 0 else i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (on) Icons.Rounded.Star else Icons.Rounded.StarOutline, null, tint = if (on) Color(0xFFF2B33A) else Color(0xFFC9CCD6),
                    modifier = Modifier.size(52.dp).graphicsLayerScale(scale))
                Text(DayRating.LABELS[i - 1], style = MaterialTheme.typography.labelSmall, color = if (i == stars) Pal.Ink else Pal.Muted, fontWeight = if (i == stars) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private fun Modifier.graphicsLayerScale(s: Float) = this.then(Modifier.graphicsLayer { scaleX = s; scaleY = s })

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

/** Серая кнопка под «Мои успехи». */
@Composable
internal fun TomorrowButton(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Line.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WbTwilight, null, tint = Pal.Muted)
        Text("  Посмотреть сводку на завтра", style = MaterialTheme.typography.titleSmall, color = Pal.Muted, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
    }
}

/** Сводка на завтра: погода, пары и события, задачи, привычки, даты, праздники. */
@Composable
internal fun TomorrowScreen(weather: WeatherData?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val d = LocalDate.now().plusDays(1)
    val tasks by db.todo().tasks().collectAsState(initial = emptyList())
    val habits by db.todo().habits().collectAsState(initial = emptyList())
    val birthdays by db.todo().birthdays().collectAsState(initial = emptyList())
    var agenda by remember { mutableStateOf<List<com.pank.vsevodnom.data.schedule.Agenda>>(emptyList()) }
    LaunchedEffect(Unit) {
        agenda = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.pank.vsevodnom.data.schedule.CalendarRepo.agendaSync(ctx, d, d) }
    }
    val dueTasks = tasks.filter { !it.done && it.dueDay == d.toEpochDay() }
    val overdue = tasks.count { !it.done && it.dueDay != null && it.dueDay < d.toEpochDay() }
    val dueHabits = habits.filter { !it.archived && Todo.isDue(it, d) }
    val bd = birthdays.filter { it.day == d.dayOfMonth && it.month == d.monthValue }
    val hol = Holidays.on(d, ctx)
    FullScreen("Завтра, " + d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale("ru"))), onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            weather?.days?.firstOrNull { it.date == d }?.let { w ->
                Card("Погода") { Text("${wmo(w.code).first} · ↑${com.pank.vsevodnom.ui.weather.fmtT(w.tMax)} ↓${com.pank.vsevodnom.ui.weather.fmtT(w.tMin)}" + (if (w.rainProb > 0) " · дождь ${w.rainProb}%" else ""), style = MaterialTheme.typography.bodyLarge) }
            }
            if (hol.isNotEmpty() || bd.isNotEmpty()) Card("Даты") {
                hol.forEach { Text("🎉 " + it.name, style = MaterialTheme.typography.bodyMedium) }
                bd.forEach { Text("🎂 " + it.name, style = MaterialTheme.typography.bodyMedium) }
            }
            Card("Пары и события (${agenda.size})") {
                if (agenda.isEmpty()) Text("Ничего не запланировано", color = Pal.Muted)
                agenda.forEach { a ->
                    val (time, title) = when (a) {
                        is com.pank.vsevodnom.data.schedule.Agenda.Pair -> "${a.l.start}–${a.l.end}" to a.title
                        is com.pank.vsevodnom.data.schedule.Agenda.Own -> (if (a.e.allDay) "весь день" else a.sortKey.toLocalTime().toString()) to a.e.title
                        is com.pank.vsevodnom.data.schedule.Agenda.Sys -> (if (a.allDay) "весь день" else a.sortKey.toLocalTime().toString()) to a.title
                    }
                    Row { Text(time, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.width(96.dp)); Text(title, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Card("Задачи (${dueTasks.size})") {
                if (dueTasks.isEmpty()) Text("Нет задач на завтра", color = Pal.Muted)
                dueTasks.sortedByDescending { it.priority }.forEach { Text((if (it.priority >= 3) "‼ " else "• ") + it.title, style = MaterialTheme.typography.bodyMedium) }
                if (overdue > 0) Text("И ещё просрочено: $overdue", color = Pal.RoseInk, style = MaterialTheme.typography.bodySmall)
            }
            Card("Привычки (${dueHabits.size})") {
                if (dueHabits.isEmpty()) Text("Нет", color = Pal.Muted)
                dueHabits.forEach { Text("${it.icon.ifBlank { "✓" }} ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
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
            // оценка жизни (все стороны: движение, сон, настроение, привычки, дела, деньги, вес) — здесь, в «Моих успехах»
            var life by remember { mutableStateOf<List<com.pank.vsevodnom.ui.health.LifePart>?>(null) }
            LaunchedEffect(Unit) {
                val hs = runCatching { if (com.pank.vsevodnom.ui.health.HealthRepo.status(ctx) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE && com.pank.vsevodnom.ui.health.HealthRepo.granted(ctx).isNotEmpty()) com.pank.vsevodnom.ui.health.HealthRepo.summary(ctx) else null }.getOrNull()
                life = runCatching { com.pank.vsevodnom.ui.health.LifeScore.compute(ctx, hs) }.getOrDefault(emptyList())
            }
            life?.takeIf { it.isNotEmpty() }?.let { parts ->
                val total = com.pank.vsevodnom.ui.health.LifeScore.total(parts)
                Card("Как я проживаю жизнь") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LifeBadge(total, {}, 52.dp)
                        Text("  " + (total?.let { com.pank.vsevodnom.ui.health.LifeScore.verdict(it) } ?: ""), style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    }
                    parts.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Line)) {
                                Box(Modifier.fillMaxWidth((p.score / 10).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(scoreColor(p.score)))
                            }
                            Text("  " + "%.1f".format(p.score).replace('.', ','), style = MaterialTheme.typography.labelLarge, color = scoreColor(p.score))
                        }
                    }
                }
            }
            when (tab) {
                0 -> {
                    val doneList = tasks.filter { it.done && it.doneAt != null && day(it.doneAt) == today }
                    val failed = tasks.filter { !it.done && it.dueDay != null && it.dueDay <= today.toEpochDay() }
                    val dueHabits = habits.filter { Todo.isDue(it, today, logs.filter { l -> l.epochDay == today.toEpochDay() }) }
                    val doneHabits = dueHabits.filter { h -> (logs.firstOrNull { it.habitId == h.id && it.epochDay == today.toEpochDay() }?.count ?: 0) >= h.perDay }
                    Card("Оцени свой день") {
                        StarRating(stars) { stars = it; DayRating.set(ctx, today.toEpochDay(), it) }
                        Text(when (stars) { 0 -> "Доволен ли собой сегодня?"; 1 -> "Тяжёлый день. Завтра — новый."; 2 -> "Так себе. Что мешало?"; 3 -> "Нормально."; 4 -> "Хороший день!"; else -> "Отличный день, гордись собой!" },
                            style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        var note by remember { mutableStateOf(DayRating.note(ctx, today.toEpochDay())) }
                        var noteOpen by remember { mutableStateOf(note.isNotBlank()) }
                        if (!noteOpen) TextButton({ noteOpen = true }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Rounded.EditNote, null); Text(" Добавить комментарий") }
                        else OutlinedTextField(note, { note = it; DayRating.setNote(ctx, today.toEpochDay(), it) }, Modifier.fillMaxWidth(), placeholder = { Text("Что было главным сегодня?") }, minLines = 2)
                        Text("Оценка и комментарий попадают в статистику вместе с настроением (Здоровье → Настроение).", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
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
                    val dayMoods = moods.filter { day(it.at) == today }.sortedBy { it.at }
                    if (dayMoods.isNotEmpty()) Card("Настроение за день") {
                        Text(dayMoods.joinToString("  ") { m -> java.time.Instant.ofEpochMilli(m.at).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) } + " " + com.pank.vsevodnom.ui.life.MOOD_NAMES[(m.level - 1).coerceIn(0, 4)] },
                            style = MaterialTheme.typography.bodyMedium)
                    }
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
                    val journal = (0..((minOf(cur.to, today).toEpochDay() - cur.from.toEpochDay()).toInt())).map { cur.from.plusDays(it.toLong()) }
                        .filter { DayRating.get(ctx, it.toEpochDay()) > 0 || DayRating.note(ctx, it.toEpochDay()).isNotBlank() }.reversed()
                    if (journal.isNotEmpty()) Card("Дневник дней") {
                        journal.take(40).forEach { dd ->
                            val st = DayRating.get(ctx, dd.toEpochDay()); val nt = DayRating.note(ctx, dd.toEpochDay())
                            Column(Modifier.padding(vertical = 3.dp)) {
                                Text(dd.format(DateTimeFormatter.ofPattern("EE, d MMM", java.util.Locale("ru"))) + (if (st > 0) "  " + "★".repeat(st) + " " + DayRating.LABELS[st - 1] else ""), style = MaterialTheme.typography.labelLarge)
                                if (nt.isNotBlank()) Text(nt, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                            }
                        }
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
