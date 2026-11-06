package com.pank.vsevodnom.ui.todo

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

private val DOW = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val DM = DateTimeFormatter.ofPattern("dd.MM")
private val RU = java.util.Locale("ru")

fun dayLabel(d: LocalDate): String {
    val t = LocalDate.now()
    return when (d) { t -> "сегодня"; t.plusDays(1) -> "завтра"; t.minusDays(1) -> "вчера"; else -> d.format(DateTimeFormatter.ofPattern("EE, d MMM", RU)) }
}

@Composable
fun TodoScreen(onBack: (() -> Unit)? = null) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack); Spacer(Modifier.width(12.dp)) }
            Text("Дела", style = MaterialTheme.typography.headlineMedium)
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = Pal.Bg, divider = {}) {
            listOf("Задачи", "Привычки", "Фокус", "Списки", "Заметки", "Даты").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Tasks()
                1 -> Habits()
                2 -> Focus()
                3 -> Lists()
                4 -> Notes()
                else -> Dates()
            }
        }
    }
}

/** Плавающая кнопка «+» внизу справа. */
@Composable
private fun BoxScope.Fab(onClick: () -> Unit) {
    Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Add, "Добавить", tint = Color.White)
    }
}

// ================= Задачи =================

@Composable
private fun Tasks() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<TaskItem?>(null) }
    var list by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val lists = tasks.map { it.list }.filter { it.isNotBlank() }.distinct()
    val open = tasks.filter { !it.done && (list == null || it.list == list) }
    val groups = listOf(
        "Просрочено" to open.filter { (it.dueDay ?: Long.MAX_VALUE) < today },
        "Сегодня" to open.filter { it.dueDay == today },
        "Завтра" to open.filter { it.dueDay == today + 1 },
        "Позже" to open.filter { (it.dueDay ?: -1) > today + 1 },
        "Без даты" to open.filter { it.dueDay == null },
    ).filter { it.second.isNotEmpty() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lists.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Chip("Все", list == null, { list = null }) }
                    items(lists) { l -> Chip(l, list == l, { list = if (list == l) null else l }) }
                }
            }
            if (groups.isEmpty()) item { EmptyHint("Задач нет. Нажми «+», чтобы добавить.") }
            groups.forEach { (title, l) ->
                item { Text(title, style = MaterialTheme.typography.labelLarge, color = if (title == "Просрочено") Pal.RoseInk else Pal.Muted, modifier = Modifier.padding(top = 8.dp)) }
                items(l, key = { it.id }) { t -> TaskRow(t, { edit = t }) }
            }
            val done = tasks.filter { it.done && (list == null || it.list == list) }
            if (done.isNotEmpty()) item { TextButton({ showDone = !showDone }) { Text(if (showDone) "Скрыть выполненные" else "Выполненные (${done.size})") } }
            if (showDone) items(done.take(100), key = { it.id }) { t -> TaskRow(t, { edit = t }) }
        }
        Fab { edit = TaskItem(title = "", dueDay = today) }
    }
    edit?.let { TaskEditor(it, lists) { edit = null } }
}

@Composable
private fun TaskRow(t: TaskItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(4.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(t.done, { AppScope.io.launch { if (it) Todo.complete(ctx, t) else Todo.undo(ctx, t) } })
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null))
            val meta = listOfNotNull(
                t.dueDay?.let { dayLabel(LocalDate.ofEpochDay(it)) }, t.dueMinute?.let { "%02d:%02d".format(it / 60, it % 60) },
                t.repeat.takeIf { it.isNotBlank() }?.let { "↻" }, t.list.ifBlank { null }, t.note.take(40).ifBlank { null }
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (t.priority > 0) Box(Modifier.padding(end = 8.dp).size(10.dp).clip(CircleShape).background(if (t.priority == 2) Pal.RoseInk else Pal.PeachInk))
    }
}

/** Полноэкранный редактор задачи со всеми настройками. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TaskEditor(t: TaskItem, lists: List<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(t.title) }
    var note by remember { mutableStateOf(t.note) }
    var day by remember { mutableStateOf(t.dueDay) }
    var minute by remember { mutableStateOf(t.dueMinute) }
    var prio by remember { mutableIntStateOf(t.priority) }
    var list by remember { mutableStateOf(t.list) }
    var repeat by remember { mutableStateOf(t.repeat) }
    var remind by remember { mutableStateOf(t.remindBefore) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val tp = rememberTimePickerState((minute ?: 540) / 60, (minute ?: 540) % 60, true)
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (t.id == 0L) "Новая задача" else "Задача", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (t.id != 0L) IconButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteTask(t) }; onDone() }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.RoseInk) }
            }
            Column(Modifier.weight(1f).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(title, { title = it }, "Что сделать")
                AppTextField(note, { note = it }, "Заметка", singleLine = false)
                SectionLabel("Когда")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val td = LocalDate.now().toEpochDay()
                    Chip("Без даты", day == null, { day = null; minute = null })
                    Chip("Сегодня", day == td, { day = td }); Chip("Завтра", day == td + 1, { day = td + 1 })
                    Chip(day?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) } ?: "Дата…", false, { pickDate = true })
                    if (day != null) Chip(minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "Время…", minute != null, { pickTime = true })
                    if (minute != null) Chip("без времени", false, { minute = null })
                }
                SectionLabel("Важность")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Обычная", "Важная", "Срочная").forEachIndexed { i, s -> Chip(s, prio == i, { prio = i }) } }
                SectionLabel("Повтор")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Нет", "daily" to "Каждый день", "weekdays" to "По будням", "weekly" to "Каждую неделю", "monthly" to "Каждый месяц").forEach { (k, s) -> Chip(s, repeat == k, { repeat = k }) }
                }
                if (minute != null) {
                    SectionLabel("Напомнить")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Pair<Int?, String>>(null to "Нет", 0 to "В момент", 10 to "За 10 мин", 30 to "За 30 мин", 60 to "За час", 1440 to "За день").forEach { (k, s) -> Chip(s, remind == k, { remind = k }) }
                    }
                }
                SectionLabel("Список")
                AppTextField(list, { list = it }, "Например: Учёба, Дом, Работа")
                if (lists.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { lists.forEach { l -> Chip(l, list == l, { list = l }) } }
            }
            PillButton("Сохранить", {
                if (title.isBlank()) return@PillButton
                val n = t.copy(title = title.trim(), note = note.trim(), dueDay = day, dueMinute = if (day == null) null else minute, priority = prio, list = list.trim(), repeat = repeat, remindBefore = remind)
                AppScope.io.launch { val id = AppDb.get(ctx).todo().upsertTask(n); Todo.scheduleReminder(ctx, n.copy(id = if (n.id == 0L) id else n.id)) }
                onDone()
            }, Modifier.fillMaxWidth())
        }
    }
    if (pickTime) AlertDialog(onDismissRequest = { pickTime = false }, confirmButton = { TextButton({ minute = tp.hour * 60 + tp.minute; pickTime = false }) { Text("Ок") } }, text = { TimePicker(tp) })
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(day ?: LocalDate.now().toEpochDay()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }; pickDate = false }) { Text("Ок") }
        }) { DatePicker(dp) }
    }
}

@Composable
private fun Modifier.verticalScrollCompat() = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

// ================= Привычки =================

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Habits() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val habits by dao.habits().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val logs by remember { dao.habitLogs(today.minusDays(400).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Habit?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (habits.isEmpty()) item { EmptyHint("Привычек нет. Например: «Вода 8 стаканов», «Зарядка», «Читать 20 минут».") }
            items(habits, key = { it.id }) { h ->
                val t = Tints.all[h.tint % 6]
                val todayCount = logs.firstOrNull { it.habitId == h.id && it.epochDay == today.toEpochDay() }?.count ?: 0
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { edit = h }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.titleSmall)
                            Text("серия ${Todo.streak(h, logs, today)} дн.", style = MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (todayCount >= h.perDay) t.ink.copy(alpha = 0.7f) else t.bg)
                            .clickable { AppScope.io.launch { Todo.tickHabit(ctx, h, today) } }, contentAlignment = Alignment.Center) {
                            Text(if (todayCount >= h.perDay) "✓" else "$todayCount/${h.perDay}", color = if (todayCount >= h.perDay) Color.White else t.ink, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // последние 14 дней
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (13 downTo 0).forEach { k ->
                            val d = today.minusDays(k.toLong())
                            val planned = h.daysMask and (1 shl (d.dayOfWeek.value - 1)) != 0
                            val ok = (logs.firstOrNull { it.habitId == h.id && it.epochDay == d.toEpochDay() }?.count ?: 0) >= h.perDay
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(when { ok -> t.ink.copy(alpha = 0.7f); planned -> Pal.Line; else -> Color.Transparent }))
                        }
                    }
                }
            }
        }
        Fab { edit = Habit(title = "") }
    }
    edit?.let { h ->
        var title by remember { mutableStateOf(h.title) }
        var per by remember { mutableIntStateOf(h.perDay) }
        var mask by remember { mutableIntStateOf(h.daysMask) }
        var tint by remember { mutableIntStateOf(h.tint) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (h.id == 0L) "Новая привычка" else "Привычка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(title, { title = it }, "Название")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Раз в день: $per", modifier = Modifier.weight(1f))
                        TextButton({ if (per > 1) per-- }) { Text("−") }; TextButton({ if (per < 20) per++ }) { Text("+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DOW.forEachIndexed { i, d -> val on = mask and (1 shl i) != 0
                            Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) Pal.Ink else Pal.Line).clickable { mask = mask xor (1 shl i) }, contentAlignment = Alignment.Center) { Text(d, color = if (on) Color.White else Pal.Ink, fontSize = 12.sp) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (i == tint) 30.dp else 24.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.8f else 0.35f)).clickable { tint = i }) } }
                    if (h.id != 0L) TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteHabit(h) }; edit = null }) { Text("Удалить", color = Pal.RoseInk) }
                }
            },
            confirmButton = { TextButton({ if (title.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).todo().upsertHabit(h.copy(title = title.trim(), perDay = per, daysMask = mask, tint = tint)) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}

// ================= Фокус и лес =================

/** Дерево: вид 0…3, живое или засохшее (если сессию бросили). */
fun DrawScope.tree(kind: Int, alive: Boolean) {
    val w = size.width; val h = size.height
    val trunk = if (alive) Color(0xFF9A6B45) else Color(0xFF8C8478)
    val leaf = if (!alive) Color(0xFFC9C1B2) else listOf(Color(0xFF7CC59A), Color(0xFF5FB287), Color(0xFF9AD3A8), Color(0xFFB4D98A))[kind % 4]
    drawRect(trunk, Offset(w * 0.45f, h * 0.55f), Size(w * 0.1f, h * 0.35f))
    when (kind % 4) {
        0 -> drawCircle(leaf, w * 0.3f, Offset(w / 2, h * 0.4f))
        1 -> drawPath(Path().apply { moveTo(w / 2, h * 0.05f); lineTo(w * 0.85f, h * 0.65f); lineTo(w * 0.15f, h * 0.65f); close() }, leaf)
        2 -> { drawCircle(leaf, w * 0.22f, Offset(w * 0.38f, h * 0.45f)); drawCircle(leaf, w * 0.22f, Offset(w * 0.62f, h * 0.45f)); drawCircle(leaf, w * 0.24f, Offset(w / 2, h * 0.28f)) }
        else -> { drawOval(leaf, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.6f)) }
    }
    drawOval(Color(0x22000000), Offset(w * 0.25f, h * 0.88f), Size(w * 0.5f, h * 0.08f))
}

private object FocusPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE)
}

@Composable
private fun Focus() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val sessions by dao.focus().collectAsState(initial = emptyList())
    val p = remember { FocusPrefs.p(ctx) }
    var minutes by remember { mutableIntStateOf(p.getInt("len", 25)) }
    var endAt by remember { mutableLongStateOf(p.getLong("endAt", 0)) }
    var label by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endAt > 0
    LaunchedEffect(running) {
        while (endAt > 0) {
            now = System.currentTimeMillis()
            if (now >= endAt) {
                val start = endAt - p.getInt("runLen", minutes) * 60_000L
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = start, minutes = p.getInt("runLen", minutes), label = p.getString("label", "")!!, completed = true, tree = (start / 1000 % 4).toInt())) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
                EventReminder.notify(ctx, "Фокус завершён", "Новое дерево в твоём лесу", 3200)
            }
            delay(500)
        }
    }
    val done = sessions.filter { it.completed }
    val todayMin = done.filter { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }.sumOf { it.minutes }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Mint) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val left = if (running) ((endAt - now) / 1000).coerceAtLeast(0) else minutes * 60L
                    val total = (if (running) p.getInt("runLen", minutes) else minutes) * 60f
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(Color.White, 0f, 360f, false, style = Stroke(16f))
                            drawArc(Pal.MintInk, -90f, 360f * (1 - left / total), false, style = Stroke(16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        // дерево растёт по мере сессии
                        Canvas(Modifier.size((60 + 80 * (1 - left / total)).dp)) { tree(1, true) }
                    }
                    Text("%02d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium)
                    if (running) Text("Не уходи — иначе дерево засохнет 🙂".replace(" 🙂", ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!running) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(15, 25, 45, 60, 90).forEach { m -> Chip("$m мин", minutes == m, { minutes = m; p.edit().putInt("len", m).apply() }) } } }
            item { AppTextField(label, { label = it }, "Над чем работаешь (необязательно)") }
            item {
                PillButton("Начать", {
                    endAt = System.currentTimeMillis() + minutes * 60_000L
                    p.edit().putLong("endAt", endAt).putInt("runLen", minutes).putString("label", label).apply()
                }, Modifier.fillMaxWidth())
            }
        } else item {
            PillButton("Сдаться (дерево засохнет)", {
                val len = p.getInt("runLen", minutes)
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = endAt - len * 60_000L, minutes = len - ((endAt - System.currentTimeMillis()) / 60_000).toInt(), label = label, completed = false)) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
            }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.RoseInk)
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Мой лес")
                Text("Деревьев: ${done.size} · сегодня $todayMin мин · всего ${done.sumOf { it.minutes } / 60} ч", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val shown = sessions.take(60)
                if (shown.isEmpty()) Text("Здесь будет расти лес из твоих фокус-сессий.", style = MaterialTheme.typography.bodySmall)
                shown.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { s -> Canvas(Modifier.weight(1f).aspectRatio(0.8f)) { tree(s.tree, s.completed) } }; repeat(6 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
            }
        }
    }
}

// ================= Списки =================

private val DEFAULT_LISTS = listOf("Фильмы", "Сериалы", "Книги", "Игры", "Аниме")
private val STATUS = listOf("Хочу", "В процессе", "Готово")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Lists() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val all by dao.media().collectAsState(initial = emptyList())
    val p = remember { ctx.getSharedPreferences("lists", Context.MODE_PRIVATE) }
    var custom by remember { mutableStateOf(p.getString("custom", "")!!.split('|').filter { it.isNotBlank() }) }
    val lists = (DEFAULT_LISTS + custom + all.map { it.list }).distinct()
    var cur by rememberSaveable { mutableStateOf(DEFAULT_LISTS.first()) }
    var edit by remember { mutableStateOf<MediaItem?>(null) }
    var newList by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lists) { l -> Chip("$l ${all.count { it.list == l }.takeIf { it > 0 } ?: ""}".trim(), cur == l, { cur = l }) }
                    item { Chip("+ список", false, { newList = true }) }
                }
            }
            val items = all.filter { it.list == cur }
            if (items.isEmpty()) item { EmptyHint("Пусто. Добавь первое — «+» внизу.") }
            STATUS.indices.forEach { st ->
                val l = items.filter { it.status == st }
                if (l.isNotEmpty()) {
                    item { Text(STATUS[st], style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp)) }
                    items(l, key = { it.id }) { m ->
                        AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { edit = m }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                                    if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                                if (m.rating > 0) Text("★".repeat(m.rating), color = Pal.ButterInk)
                            }
                        }
                    }
                }
            }
        }
        Fab { edit = MediaItem(list = cur, title = "") }
    }
    edit?.let { m ->
        var title by remember { mutableStateOf(m.title) }
        var note by remember { mutableStateOf(m.note) }
        var status by remember { mutableIntStateOf(m.status) }
        var rating by remember { mutableIntStateOf(m.rating) }
        var link by remember { mutableStateOf(m.link) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(m.list) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(title, { title = it }, "Название")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { STATUS.forEachIndexed { i, s -> Chip(s, status == i, { status = i }) } }
                    Row { (1..5).forEach { i -> Text(if (i <= rating) "★" else "☆", fontSize = 30.sp, color = Pal.ButterInk, modifier = Modifier.clickable { rating = if (rating == i) 0 else i }.padding(2.dp)) } }
                    AppTextField(note, { note = it }, "Заметка", singleLine = false)
                    AppTextField(link, { link = it }, "Ссылка")
                    if (m.id != 0L) TextButton({ AppScope.io.launch { dao.deleteMedia(m) }; edit = null }) { Text("Удалить", color = Pal.RoseInk) }
                }
            },
            confirmButton = { TextButton({ if (title.isNotBlank()) AppScope.io.launch { dao.upsertMedia(m.copy(title = title.trim(), note = note.trim(), status = status, rating = rating, link = link.trim())) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
    if (newList) {
        var n by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newList = false }, title = { Text("Новый список") }, text = { AppTextField(n, { n = it }, "Например: Рецепты, Подарки, Места") },
            confirmButton = { TextButton({ if (n.isNotBlank()) { custom = custom + n.trim(); p.edit().putString("custom", custom.joinToString("|")).apply(); cur = n.trim() }; newList = false }) { Text("Создать") } },
            dismissButton = { TextButton({ newList = false }) { Text("Отмена") } })
    }
}

// ================= Заметки =================

@Composable
private fun Notes() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val notes by dao.notes().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Note?>(null) }
    var q by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { AppTextField(q, { q = it }, "Поиск по заметкам") }
            items(notes.filter { q.isBlank() || it.title.contains(q, true) || it.body.contains(q, true) || it.tags.contains(q, true) }, key = { it.id }) { n ->
                val t = Tints.all[n.tint % 6]
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(t.bg).clickable { edit = n }.padding(12.dp).heightIn(min = 90.dp)) {
                    Row { Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2); if (n.pinned) Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp), tint = t.ink) }
                    Text(n.body, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                    if (n.tags.isNotBlank()) Text(n.tags.split(',').joinToString(" ") { "#" + it.trim() }, style = MaterialTheme.typography.labelSmall, color = t.ink)
                }
            }
        }
        Fab { edit = Note(title = "") }
    }
    edit?.let { n ->
        var title by remember { mutableStateOf(n.title) }
        var body by remember { mutableStateOf(n.body) }
        var tint by remember { mutableIntStateOf(n.tint) }
        var pinned by remember { mutableStateOf(n.pinned) }
        var tags by remember { mutableStateOf(n.tags) }
        Dialog(onDismissRequest = { edit = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Tints.all[tint % 6].bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.Close, "Закрыть", { edit = null })
                    Spacer(Modifier.weight(1f))
                    IconButton({ pinned = !pinned }) { Icon(Icons.Rounded.PushPin, "Закрепить", tint = if (pinned) Pal.Ink else Pal.Muted) }
                    if (n.id != 0L) IconButton({ AppScope.io.launch { dao.deleteNote(n) }; edit = null }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.RoseInk) }
                    TextButton({ if (title.isNotBlank() || body.isNotBlank()) AppScope.io.launch { dao.upsertNote(n.copy(title = title.trim().ifBlank { body.lineSequence().first().take(40) }, body = body, tint = tint, pinned = pinned, tags = tags.trim(), updatedAt = System.currentTimeMillis())) }; edit = null }) { Text("Готово") }
                }
                AppTextField(title, { title = it }, "Заголовок")
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().weight(1f), placeholder = { Text("Текст заметки") }, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
                AppTextField(tags, { tags = it }, "Теги через запятую")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (i == tint) 30.dp else 24.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.8f else 0.35f)).clickable { tint = i }) } }
            }
        }
    }
}

// ================= Дни рождения и праздники =================

@Composable
private fun Dates() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val bdays by dao.birthdays().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Birthday?>(null) }
    val today = LocalDate.now()
    fun next(b: Birthday): LocalDate = runCatching { LocalDate.of(today.year, b.month, b.day).let { if (it.isBefore(today)) it.plusYears(1) else it } }.getOrDefault(today.plusYears(1))
    val holidays = (Holidays.all(today.year) + Holidays.all(today.year + 1)).filterKeys { !it.isBefore(today) }.toSortedMap().entries.take(8)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionLabel("Дни рождения") }
            if (bdays.isEmpty()) item { EmptyHint("Добавь дни рождения близких — напомню в сводке.") }
            items(bdays.sortedBy { next(it) }, key = { it.id }) { b ->
                val n = next(b); val days = Duration.between(today.atStartOfDay(), n.atStartOfDay()).toDays()
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { edit = b }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Pal.Rose), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Cake, null, tint = Pal.RoseInk) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text("${b.day}.${"%02d".format(b.month)}" + (b.year?.let { " · будет ${n.year - it}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (days == 0L) "сегодня!" else "через $days дн.", style = MaterialTheme.typography.labelLarge, color = if (days <= 7) Pal.RoseInk else Pal.Muted)
                    }
                }
            }
            item { SectionLabel("Ближайшие праздники") }
            items(holidays) { (d, name) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(d.format(DM), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(56.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Fab { edit = Birthday(name = "", day = today.dayOfMonth, month = today.monthValue) }
    }
    edit?.let { b ->
        var name by remember { mutableStateOf(b.name) }
        var day by remember { mutableStateOf(b.day.toString()) }
        var month by remember { mutableStateOf(b.month.toString()) }
        var year by remember { mutableStateOf(b.year?.toString() ?: "") }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("День рождения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(name, { name = it }, "Имя")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(day, { day = it }, "День", Modifier.weight(1f)); AppTextField(month, { month = it }, "Месяц", Modifier.weight(1f)); AppTextField(year, { year = it }, "Год", Modifier.weight(1f))
                    }
                    if (b.id != 0L) TextButton({ AppScope.io.launch { dao.deleteBirthday(b) }; edit = null }) { Text("Удалить", color = Pal.RoseInk) }
                }
            },
            confirmButton = {
                TextButton({
                    val d = day.toIntOrNull(); val m = month.toIntOrNull()
                    if (name.isNotBlank() && d in 1..31 && m in 1..12) AppScope.io.launch { dao.upsertBirthday(b.copy(name = name.trim(), day = d!!, month = m!!, year = year.toIntOrNull())) }
                    edit = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}
