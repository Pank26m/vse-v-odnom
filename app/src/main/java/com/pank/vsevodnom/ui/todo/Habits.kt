package com.pank.vsevodnom.ui.todo

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")
private val DOW = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val EMOJI = listOf("💧", "🏃", "📖", "🧘", "🏋️", "🚴", "🥗", "🍎", "😴", "☀️", "🌙", "🪥", "💊", "🧹", "📝", "📵", "🚭", "🙏", "🎸", "🎨",
    "💻", "🇬🇧", "🌱", "🐶", "💰", "🚶", "🧠", "☎️", "🛏️", "🍳", "🥤", "✍️")

fun habitDone(h: Habit, logs: List<HabitLog>, day: LocalDate) = (logs.firstOrNull { it.habitId == h.id && it.epochDay == day.toEpochDay() }?.count ?: 0) >= h.perDay

/** Доля выполненных из нужных дней за период (null — нужных дней не было). */
fun habitRate(h: Habit, logs: List<HabitLog>, from: LocalDate, to: LocalDate): Pair<Int, Int> {
    var due = 0; var done = 0; var d = from
    while (!d.isAfter(to)) { if (Todo.isDue(h, d, logs)) { due++; if (habitDone(h, logs, d)) done++ }; d = d.plusDays(1) }
    return done to due
}

fun bestStreak(h: Habit, logs: List<HabitLog>): Int {
    val start = LocalDate.ofEpochDay(h.startDay ?: (h.createdAt / 86_400_000L)).coerceAtMost(LocalDate.now())
    var best = 0; var cur = 0; var d = start
    while (!d.isAfter(LocalDate.now())) {
        if (Todo.isDue(h, d, logs)) { if (habitDone(h, logs, d)) { cur++; best = maxOf(best, cur) } else if (d != LocalDate.now()) cur = 0 }
        d = d.plusDays(1)
    }
    return best
}

@Composable
fun HabitsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val habits by dao.habits().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    val logs by remember { dao.habitLogs(today.minusDays(400).toEpochDay(), today.plusDays(7).toEpochDay()) }.collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Habit?>(null) }
    var detail by remember { mutableStateOf<Habit?>(null) }

    val due = habits.filter { Todo.isDue(it, day, logs) }
    val (wDone, wDue) = habits.fold(0 to 0) { acc, h -> val r = habitRate(h, logs, today.minusDays(6), today); (acc.first + r.first) to (acc.second + r.second) }
    val (mDone, mDue) = habits.fold(0 to 0) { acc, h -> val r = habitRate(h, logs, today.withDayOfMonth(1), today); (acc.first + r.first) to (acc.second + r.second) }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Привычки", onBack) {
            // неделя: сегодня — посередине
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                (-3..3).forEach { k ->
                    val d = today.plusDays(k.toLong())
                    val dueD = habits.filter { Todo.isDue(it, d, logs) }
                    val doneD = dueD.count { habitDone(it, logs, d) }
                    val sel = d == day
                    Column(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) Pal.Ink else if (d == today) Pal.Lilac else Color.Transparent)
                        .clickable(enabled = !d.isAfter(today)) { day = d }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(DOW[d.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = if (sel) Color.White.copy(alpha = 0.8f) else Pal.Muted)
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(if (sel) Color.White.copy(alpha = 0.25f) else Pal.Ink.copy(alpha = 0.08f), style = Stroke(5f))
                                if (dueD.isNotEmpty() && doneD > 0) drawArc(if (sel) Color.White else Color(0xFF3FB27F), -90f, 360f * doneD / dueD.size, false, style = Stroke(5f, cap = StrokeCap.Round))
                            }
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (sel) Color.White else if (d.isAfter(today)) Pal.Muted else Pal.Ink)
                        }
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (habits.isNotEmpty()) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RateTile("Неделя", wDone, wDue, Modifier.weight(1f))
                        RateTile("Месяц", mDone, mDue, Modifier.weight(1f))
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(12.dp)) {
                            Text("Лучшая серия", style = MaterialTheme.typography.labelSmall)
                            StreakFlame(habits.maxOfOrNull { Todo.streak(it, logs, today) } ?: 0, true, 24.dp)
                        }
                    }
                }
                if (habits.isEmpty()) item { EmptyHint("Привычек пока нет. Нажми «+» и задай свою.") }
                else if (due.isEmpty()) item { EmptyHint("На этот день привычек нет") }
                items(due.sortedBy { habitDone(it, logs, day) }, key = { it.id }) { h -> HabitRow(h, logs, day) { detail = h } }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Habit(title = "", tint = habits.size % 6, startDay = today.toEpochDay(), icon = EMOJI[habits.size % EMOJI.size]) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Новая привычка", tint = Color.White)
        }
    }
    edit?.let { HabitEditor(it) { edit = null } }
    detail?.let { h -> HabitDetail(habits.firstOrNull { it.id == h.id } ?: h, logs, onEdit = { edit = h; detail = null }) { detail = null } }
}

@Composable
private fun RateTile(title: String, done: Int, due: Int, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Text(if (due == 0) "—" else "${done * 100 / due}%", style = MaterialTheme.typography.titleLarge, color = Color(0xFF2E8A5E))
        Text("$done из $due", style = MaterialTheme.typography.labelSmall)
    }
}

/** Карточка привычки: значок, название, огонёк серии, точки недели, анимированная отметка. */
@Composable
fun HabitRow(h: Habit, logs: List<HabitLog>, day: LocalDate, compact: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val t = Tints.all[h.tint % 6]
    val cnt = logs.firstOrNull { it.habitId == h.id && it.epochDay == day.toEpochDay() }?.count ?: 0
    val done = cnt >= h.perDay
    val today = LocalDate.now()
    val bg by animateColorAsState(if (done) t.bg else Pal.Card, label = "bg")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (done) Color.White.copy(alpha = 0.7f) else t.bg), contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }, fontSize = 24.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StreakFlame(Todo.streak(h, logs, today), habitDone(h, logs, today), 16.dp)
                if (!compact) {
                    Spacer(Modifier.width(10.dp))
                    (6 downTo 0).forEach { k ->
                        val d = today.minusDays(k.toLong())
                        Box(Modifier.padding(horizontal = 1.5.dp).size(7.dp).clip(CircleShape).background(when {
                            habitDone(h, logs, d) -> t.ink.copy(alpha = 0.8f); Todo.isDue(h, d, logs) -> Pal.Ink.copy(alpha = 0.1f); else -> Color.Transparent }))
                    }
                }
                if (h.perDay > 1) Text("  $cnt/${h.perDay}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
        }
        CheckCircle(done, t.ink, 30.dp) { AppScope.io.launch { Todo.tickHabit(ctx, h, day) } }
    }
}

// ================= Создание: просто и пусто =================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditor(h0: Habit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var h by remember { mutableStateOf(h0.copy(freqType = 0)) }
    var pick by remember { mutableStateOf<String?>(null) }
    var emojiOpen by remember { mutableStateOf(h0.id == 0L) }
    val reminders = remember { mutableStateListOf<Int>().apply { addAll(HabitReminders.times(h0)) } }
    val t = Tints.all[h.tint % 6]
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (h0.id == 0L) "Новая привычка" else "Привычка", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Check, "Сохранить", {
                    if (h.title.isBlank()) return@RoundIconButton
                    val saved = h.copy(title = h.title.trim(), reminders = reminders.sorted().joinToString(","), remindMinute = null, startDay = h.startDay ?: LocalDate.now().toEpochDay())
                    AppScope.io.launch { val id = AppDb.get(ctx).todo().upsertHabit(saved); HabitReminders.schedule(ctx, saved.copy(id = if (saved.id == 0L) id else saved.id)) }
                    onDone()
                }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(t.bg).clickable { emojiOpen = !emojiOpen }, contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }, fontSize = 30.sp) }
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(h.title, { h = h.copy(title = it) }, Modifier.weight(1f), textStyle = MaterialTheme.typography.titleLarge.copy(color = Pal.Ink), cursorBrush = SolidColor(ACCENT),
                            decorationBox = { inner -> if (h.title.isEmpty()) Text("Название", style = MaterialTheme.typography.titleLarge, color = Color(0xFFB8BCC6)); inner() })
                    }
                    if (emojiOpen) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 8) {
                            EMOJI.forEach { e -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (h.icon == e) t.bg else Color.Transparent).clickable { h = h.copy(icon = e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Tints.all.forEachIndexed { i, c -> Box(Modifier.size(if (h.tint % 6 == i) 34.dp else 28.dp).clip(CircleShape).background(c.bg).then(if (h.tint % 6 == i) Modifier.background(c.ink.copy(alpha = 0.25f)) else Modifier).clickable { h = h.copy(tint = i) }) }
                        }
                    }
                }
                FormCard("Дни") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DOW.forEachIndexed { i, d ->
                            val on = h.daysMask and (1 shl i) != 0
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) t.ink else Pal.Ink.copy(alpha = 0.06f))
                                .clickable { h = h.copy(daysMask = (h.daysMask xor (1 shl i)).let { if (it == 0) h.daysMask else it }) }, contentAlignment = Alignment.Center) {
                                Text(d, color = if (on) Color.White else Pal.Ink, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    ChoiceGrid(listOf(0b1111111 to "Каждый день", 0b0011111 to "Будни", 0b1100000 to "Выходные"), { it == h.daysMask }, 3) { h = h.copy(daysMask = it) }
                }
                FormCard("Дата старта") {
                    val start = LocalDate.ofEpochDay(h.startDay ?: LocalDate.now().toEpochDay())
                    ChoiceGrid(listOf(LocalDate.now().toEpochDay() to "Сегодня", LocalDate.now().plusDays(1).toEpochDay() to "Завтра", -1L to if (start.isAfter(LocalDate.now().plusDays(1)) || start.isBefore(LocalDate.now())) start.format(DateTimeFormatter.ofPattern("d MMM", RU)) else "Другая…"),
                        { it == h.startDay || (it == -1L && h.startDay != LocalDate.now().toEpochDay() && h.startDay != LocalDate.now().plusDays(1).toEpochDay()) }, 3) {
                        if (it == -1L) pick = "start" else h = h.copy(startDay = it)
                    }
                }
                FormCard("Цель в днях") {
                    ChoiceGrid(listOf<Pair<Int?, String>>(null to "Вечно", 7 to "7", 21 to "21", 30 to "30", 66 to "66", 100 to "100"), { it == h.goalDays }, 3) { h = h.copy(goalDays = it) }
                    Text(h.goalDays?.let { "Через $it дней привычка закончится — можно продлить." } ?: "Привычка без срока.", style = MaterialTheme.typography.bodySmall)
                }
                FormCard("Уведомление") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        reminders.sorted().forEach { m ->
                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(t.bg).clickable { reminders.remove(m) }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(18.dp), tint = t.ink)
                                Text(" %02d:%02d".format(m / 60, m % 60), style = MaterialTheme.typography.titleMedium, color = t.ink)
                                Icon(Icons.Rounded.Close, "Убрать", Modifier.padding(start = 6.dp).size(16.dp), tint = t.ink)
                            }
                        }
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.06f)).clickable { pick = "remind" }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text(" Время", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (h0.id != 0L) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Скрыть", { AppScope.io.launch { AppDb.get(ctx).todo().upsertHabit(h0.copy(archived = true)); HabitReminders.schedule(ctx, h0.copy(archived = true)) }; onDone() }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                    PillButton("Удалить", { pick = "delete" }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    when (pick) {
        "start" -> {
            val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(h.startDay ?: LocalDate.now().toEpochDay()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { pick = null }, confirmButton = {
                TextButton({ dp.selectedDateMillis?.let { h = h.copy(startDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }; pick = null }) { Text("Ок") }
            }) { DatePicker(dp) }
        }
        "remind" -> {
            val tp = rememberTimePickerState(8, 0, true)
            AlertDialog(onDismissRequest = { pick = null }, title = { Text("Время напоминания") }, text = { TimePicker(tp) },
                confirmButton = { TextButton({ val m = tp.hour * 60 + tp.minute; if (m !in reminders) reminders.add(m); pick = null }) { Text("Добавить") } },
                dismissButton = { TextButton({ pick = null }) { Text("Отмена") } })
        }
        "delete" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Удалить «${h0.title}»?") }, text = { Text("История отметок тоже пропадёт. Можно не удалять, а скрыть.") },
            confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteHabit(h0); HabitReminders.schedule(ctx, h0.copy(archived = true)) }; pick = null; onDone() }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ pick = null }) { Text("Отмена") } })
    }
}

// ================= Подробности привычки =================

@Composable
private fun HabitDetail(h: Habit, logs: List<HabitLog>, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val t = Tints.all[h.tint % 6]
    var month by remember { mutableStateOf(YearMonth.now()) }
    val streak = Todo.streak(h, logs, today)
    val (wd, wn) = habitRate(h, logs, today.minusDays((today.dayOfWeek.value - 1).toLong()), today)
    val (md, mn) = habitRate(h, logs, month.atDay(1), month.atEndOfMonth().coerceAtMost(today))
    val total = logs.count { it.habitId == h.id && it.count >= h.perDay }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss)
                Spacer(Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Edit, "Изменить", onEdit)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // шапка
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(t.bg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }, fontSize = 40.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(h.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(when (h.daysMask) { 0b1111111 -> "каждый день"; 0b0011111 -> "по будням"; 0b1100000 -> "по выходным"; else -> DOW.filterIndexed { i, _ -> h.daysMask and (1 shl i) != 0 }.joinToString(", ") } +
                        (h.goalDays?.let { " · цель $it дней" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = t.ink)
                    Spacer(Modifier.height(12.dp))
                    StreakFlame(streak, habitDone(h, logs, today), 44.dp)
                    Text(if (streak == 0) "серия не начата" else if (habitDone(h, logs, today)) "дней подряд — огонь горит" else "дней подряд — отметь сегодня, чтобы не погасла", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    CheckCircle(habitDone(h, logs, today), t.ink, 40.dp) { AppScope.io.launch { Todo.tickHabit(ctx, h, today) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RateTile("Эта неделя", wd, wn, Modifier.weight(1f))
                    RateTile("Месяц", md, mn, Modifier.weight(1f))
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(12.dp)) {
                        Text("Рекорд", style = MaterialTheme.typography.labelSmall); Text("${bestStreak(h, logs)}", style = MaterialTheme.typography.titleLarge, color = Color(0xFFE8711A)); Text("всего $total", style = MaterialTheme.typography.labelSmall)
                    }
                }
                FormCard("Неделя") {
                    val mon = today.minusDays((today.dayOfWeek.value - 1).toLong())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (0..6).forEach { k ->
                            val d = mon.plusDays(k.toLong()); val ok = habitDone(h, logs, d); val dueD = Todo.isDue(h, d, logs)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(DOW[k], style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.padding(top = 4.dp).size(36.dp).clip(RoundedCornerShape(10.dp)).background(when { ok -> t.ink; dueD && !d.isAfter(today) -> Pal.Ink.copy(alpha = 0.07f); else -> Color.Transparent })
                                    .clickable(enabled = !d.isAfter(today)) { AppScope.io.launch { Todo.tickHabit(ctx, h, d) } }, contentAlignment = Alignment.Center) {
                                    if (ok) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) else Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = if (dueD) Pal.Ink else Pal.Muted)
                                }
                            }
                        }
                    }
                }
                FormCard("Месяц") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton({ month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
                        IconButton({ month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
                    }
                    val lead = month.atDay(1).dayOfWeek.value - 1
                    for (w in 0 until (lead + month.lengthOfMonth() + 6) / 7) Row {
                        for (d in 0 until 7) {
                            val n = w * 7 + d - lead + 1
                            Box(Modifier.weight(1f).height(40.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (n in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(n); val ok = habitDone(h, logs, date)
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (ok) t.ink else if (date == today) Pal.Lilac else Color.Transparent)
                                        .clickable(enabled = !date.isAfter(today)) { AppScope.io.launch { Todo.tickHabit(ctx, h, date) } }, contentAlignment = Alignment.Center) {
                                        Text("$n", color = if (ok) Color.White else if (Todo.isDue(h, date, logs) && !date.isAfter(today)) Pal.Ink else Pal.Muted, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    Text("Нажми на день, чтобы отметить задним числом.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
