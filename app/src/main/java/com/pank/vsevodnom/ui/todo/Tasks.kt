package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

/** Старое имя акцента — оставлено для остальных экранов раздела. */
val BLUE = ACCENT
private val RU = java.util.Locale("ru")

private val REMIND = listOf<Pair<Int?, String>>(null to "Нет", 0 to "Во время", 5 to "За 5 мин", 30 to "За 30 мин", 60 to "За 1 час", 1440 to "За день")
private val REPEAT = listOf("" to "Не повторять", "daily" to "Каждый день", "weekdays" to "По будням", "weekly" to "Раз в неделю", "monthly" to "Раз в месяц", "yearly" to "Раз в год")

fun TaskItem.tagList() = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }
private fun hm(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60)

fun dateLabel(day: Long?): String {
    if (day == null) return "Без даты"
    val d = LocalDate.ofEpochDay(day); val t = LocalDate.now()
    return when (d) { t -> "Сегодня"; t.plusDays(1) -> "Завтра"; t.minusDays(1) -> "Вчера"; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == t.year) "d MMM" else "d MMM yyyy", RU)) }
}

// ================= Фильтры =================

private enum class Sort(val title: String) { DATE("По дате"), PRIORITY("Приоритет"), ALPHA("По алфавиту"), DURATION("Длительность"), CREATED("Новые сверху") }
private enum class Show(val title: String) { ALL("Все"), OVERDUE("Просрочено"), TODAY("Сегодня"), WEEK("7 дней"), NODATE("Без даты"), WITH_IMAGE("С фото") }

private class TaskFilter(p: android.content.SharedPreferences) {
    var sort by mutableStateOf(runCatching { Sort.valueOf(p.getString("sort", "DATE")!!) }.getOrDefault(Sort.DATE))
    var show by mutableStateOf(runCatching { Show.valueOf(p.getString("show", "ALL")!!) }.getOrDefault(Show.ALL))
    var prios by mutableStateOf(p.getString("prios", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet())
    var tag by mutableStateOf<String?>(p.getString("tag", null))
    var done by mutableStateOf(p.getBoolean("done", false))
    fun save(p: android.content.SharedPreferences) = p.edit().putString("sort", sort.name).putString("show", show.name).putString("prios", prios.joinToString(","))
        .putString("tag", tag).putBoolean("done", done).apply()
    val active get() = show != Show.ALL || prios.isNotEmpty() || tag != null || sort != Sort.DATE
    fun reset() { sort = Sort.DATE; show = Show.ALL; prios = emptySet(); tag = null }
}

@Composable
fun TasksPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    val prefs = remember { ctx.getSharedPreferences("tasks", android.content.Context.MODE_PRIVATE) }
    val f = remember { TaskFilter(prefs) }
    var edit by remember { mutableStateOf<TaskItem?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    val today = LocalDate.now().toEpochDay()
    val allTags = tasks.flatMap { it.tagList() }.distinct().sorted()

    val filtered = tasks.filter { t ->
        (f.done || !t.done) && (f.prios.isEmpty() || t.priority in f.prios) && (f.tag == null || f.tag in t.tagList()) &&
            (q.isBlank() || t.title.contains(q, true) || t.note.contains(q, true)) &&
            when (f.show) {
                Show.ALL -> true; Show.OVERDUE -> !t.done && (t.dueDay ?: Long.MAX_VALUE) < today; Show.TODAY -> t.dueDay == today
                Show.WEEK -> t.dueDay != null && t.dueDay in today..(today + 6); Show.NODATE -> t.dueDay == null; Show.WITH_IMAGE -> t.image.isNotBlank()
            }
    }
    val sorted = when (f.sort) {
        Sort.DATE -> filtered.sortedWith(compareBy<TaskItem>({ it.done }, { it.dueDay ?: Long.MAX_VALUE }, { it.dueMinute ?: 9999 }, { -it.priority }))
        Sort.PRIORITY -> filtered.sortedWith(compareBy<TaskItem>({ it.done }, { -it.priority }, { it.dueDay ?: Long.MAX_VALUE }))
        Sort.ALPHA -> filtered.sortedWith(compareBy<TaskItem>({ it.done }, { it.title.lowercase() }))
        Sort.DURATION -> filtered.sortedWith(compareBy<TaskItem>({ it.done }, { -(it.durationMin ?: 0) }))
        Sort.CREATED -> filtered.sortedWith(compareBy<TaskItem>({ it.done }, { -it.createdAt }))
    }
    val groups: List<Pair<String, List<TaskItem>>> = if (f.sort == Sort.DATE) listOf(
        "Просрочено" to sorted.filter { !it.done && (it.dueDay ?: Long.MAX_VALUE) < today },
        "Сегодня" to sorted.filter { !it.done && it.dueDay == today },
        "Завтра" to sorted.filter { !it.done && it.dueDay == today + 1 },
        "Позже" to sorted.filter { !it.done && (it.dueDay ?: -1) > today + 1 },
        "Без даты" to sorted.filter { !it.done && it.dueDay == null },
        "Выполнено" to sorted.filter { it.done },
    ).filter { it.second.isNotEmpty() } else listOf(f.sort.title to sorted)

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Задачи", onBack, actions = {
            RoundIconButton(Icons.Rounded.Tune, "Фильтр и сортировка", { filterOpen = true }, bg = if (f.active) Pal.Lilac else Pal.Card, tint = if (f.active) Pal.LilacInk else Pal.Ink)
        }) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AppTextField(q, { q = it }, "Поиск задач") }
                if (f.active) item {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Pal.Lilac).clickable { f.reset(); f.save(prefs) }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(f.show.title.takeIf { f.show != Show.ALL }, f.sort.title.takeIf { f.sort != Sort.DATE },
                            f.prios.takeIf { it.isNotEmpty() }?.sortedDescending()?.joinToString { PRIO[it].first }, f.tag?.let { "#$it" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
                        Icon(Icons.Rounded.Close, "Сбросить", Modifier.padding(start = 6.dp).size(16.dp), tint = Pal.LilacInk)
                    }
                }
                if (groups.isEmpty()) item { EmptyHint(if (tasks.isEmpty()) "Задач нет. Нажми «+», чтобы добавить." else "Под фильтр ничего не попало") }
                groups.forEach { (title, l) ->
                    item(key = "g$title") {
                        Text("$title · ${l.size}", style = MaterialTheme.typography.labelLarge, color = if (title == "Просрочено") Pal.RoseInk else Pal.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    items(l, key = { it.id }) { t -> TaskRow(t) { edit = t } }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = TaskItem(title = "", dueDay = today) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить задачу", tint = Color.White)
        }
    }
    edit?.let { TaskEditor(it, allTags) { edit = null } }
    if (filterOpen) FilterDialog(f, allTags, { f.save(prefs) }) { filterOpen = false }
}

/** Строка задачи. Невыполненная при отметке показывает галочку и плавно уезжает. */
@Composable
fun TaskRow(t: TaskItem, showMeta: Boolean = true, onClick: () -> Unit) {
    val ctx = LocalContext.current
    if (t.done) { TaskRowBody(t, true, showMeta, onClick) { AppScope.io.launch { Todo.undo(ctx, t) } }; return }
    Leaving(t.id, onLeft = { AppScope.io.launch { Todo.complete(ctx, t) } }) { done, leave ->
        TaskRowBody(t, done, showMeta, onClick) { leave() }
    }
}

@Composable
private fun TaskRowBody(t: TaskItem, checked: Boolean, showMeta: Boolean, onClick: () -> Unit, onCheck: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    val overdue = !t.done && (t.dueDay ?: Long.MAX_VALUE) < today
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckCircle(checked, if (t.priority > 0) prioColor(t.priority) else Pal.LilacInk, 24.dp, onCheck)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, textDecoration = if (checked) TextDecoration.LineThrough else null), color = if (checked) Pal.Muted else Pal.Ink, maxLines = 2)
            if (showMeta) {
                val meta = listOfNotNull(
                    t.dueDay?.let { dateLabel(it) + (t.dueMinute?.let { m -> ", " + hm(m) + (t.durationMin?.let { d -> "–" + hm(m + d) } ?: "") } ?: "") },
                    t.tagList().takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (overdue) Pal.RoseInk else Pal.Muted, maxLines = 1)
            }
        }
        if (t.image.isNotBlank()) Icon(Icons.Rounded.Image, null, Modifier.padding(horizontal = 2.dp).size(16.dp), tint = Pal.Muted)
        if (t.repeat.isNotBlank()) Icon(Icons.Rounded.Repeat, null, Modifier.padding(horizontal = 2.dp).size(16.dp), tint = Pal.Muted)
        if (t.remindBefore != null) Icon(Icons.Rounded.NotificationsNone, null, Modifier.padding(horizontal = 2.dp).size(16.dp), tint = Pal.Muted)
        if (t.priority > 0) Box(Modifier.padding(horizontal = 10.dp).size(10.dp).clip(CircleShape).background(prioColor(t.priority)))
        else Spacer(Modifier.width(8.dp))
    }
}

// ================= Окно задачи =================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(t0: TaskItem, knownTags: List<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(t0) }
    var note by remember { mutableStateOf(TextFieldValue(t0.note)) }
    var newTag by remember { mutableStateOf("") }
    var pickTime by remember { mutableStateOf<String?>(null) }   // start | end
    var confirmDel by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(t0.dueDay ?: LocalDate.now().toEpochDay()))) }
    val fr = remember { FocusRequester() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { copyIntoApp(ctx, it, "tasks")?.let { p -> t = t.copy(image = p) } } }
    LaunchedEffect(Unit) { if (t0.id == 0L) runCatching { fr.requestFocus() } }
    fun save() {
        if (t.title.isBlank()) { onDone(); return }
        val item = t.copy(title = t.title.trim(), note = note.text.trimEnd(), tags = (t.tagList() + newTag.trim()).filter { it.isNotBlank() }.distinct().joinToString(","))
        AppScope.io.launch {
            val id = AppDb.get(ctx).todo().upsertTask(item)
            Todo.scheduleReminder(ctx, item.copy(id = if (item.id == 0L) id else item.id))
        }
        onDone()
    }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (t0.id == 0L) "Новая задача" else "Задача", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (t0.id != 0L) RoundIconButton(Icons.Rounded.DeleteOutline, "Удалить", { confirmDel = true })
                RoundIconButton(Icons.Rounded.Check, "Сохранить", { save() }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormCard {
                    BasicTextField(t.title, { t = t.copy(title = it) }, Modifier.fillMaxWidth().focusRequester(fr),
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = Pal.Ink), cursorBrush = SolidColor(ACCENT),
                        decorationBox = { inner -> if (t.title.isEmpty()) Text("Название задачи", style = MaterialTheme.typography.titleLarge, color = Color(0xFFB8BCC6)); inner() })
                    HorizontalDivider(color = Pal.Line)
                    RichField(note, { note = it }, "Описание: можно **жирным**, *курсивом*, списком…")
                }
                FormCard("Дата и время") {
                    ChoiceGrid(listOf<Pair<Long?, String>>(LocalDate.now().toEpochDay() to "Сегодня", LocalDate.now().plusDays(1).toEpochDay() to "Завтра", null to "Без даты"),
                        { it == t.dueDay }, 3) { d -> t = t.copy(dueDay = d, dueMinute = if (d == null) null else t.dueMinute); d?.let { month = YearMonth.from(LocalDate.ofEpochDay(it)) } }
                    MonthPicker(month, { month = it }, t.dueDay) { d -> t = t.copy(dueDay = d) }
                    if (t.dueDay != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TimeTile("Начало", t.dueMinute?.let { hm(it) } ?: "Весь день", Modifier.weight(1f)) { pickTime = "start" }
                        TimeTile("Конец", if (t.dueMinute != null && t.durationMin != null) hm(t.dueMinute!! + t.durationMin!!) else "—", Modifier.weight(1f)) { if (t.dueMinute != null) pickTime = "end" }
                        if (t.dueMinute != null) IconButton({ t = t.copy(dueMinute = null, durationMin = null) }) { Icon(Icons.Rounded.Close, "Без времени", tint = Pal.Muted) }
                    }
                }
                if (t.dueDay != null) FormCard("Напомнить") {
                    ChoiceGrid(REMIND, { it == t.remindBefore }, 3) { t = t.copy(remindBefore = it) }
                }
                if (t.dueDay != null) FormCard("Повтор") {
                    ChoiceGrid(REPEAT, { it == t.repeat }, 3) { t = t.copy(repeat = it) }
                }
                FormCard("Приоритет") { PriorityDots(t.priority) { t = t.copy(priority = it) } }
                FormCard("Теги") {
                    val all = (knownTags + t.tagList()).distinct()
                    if (all.isNotEmpty()) ChoiceGrid(all.map { it to "#$it" }, { it in t.tagList() }, 3) { tg ->
                        t = t.copy(tags = (if (tg in t.tagList()) t.tagList() - tg else t.tagList() + tg).joinToString(","))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AppTextField(newTag, { newTag = it.replace(",", "") }, "Новый тег") }
                        IconButton({ if (newTag.isNotBlank()) { t = t.copy(tags = (t.tagList() + newTag.trim()).distinct().joinToString(",")); newTag = "" } }) { Icon(Icons.Rounded.Add, "Добавить тег") }
                    }
                }
                FormCard("Фото") {
                    if (t.image.isNotBlank()) Box {
                        AppImage(t.image, Modifier.fillMaxWidth().height(180.dp), maxPx = 1000)
                        IconButton({ t = t.copy(image = "") }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Rounded.Close, "Убрать фото", tint = Color.White) }
                    }
                    PillButton(if (t.image.isBlank()) "Добавить фото" else "Другое фото", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), bg = Pal.Lilac, fg = Pal.LilacInk)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    pickTime?.let { which ->
        val base = if (which == "start") (t.dueMinute ?: 9 * 60) else ((t.dueMinute ?: 9 * 60) + (t.durationMin ?: 60))
        val tp = rememberTimePickerState(base / 60 % 24, base % 60, true)
        AlertDialog(onDismissRequest = { pickTime = null }, title = { Text(if (which == "start") "Начало" else "Конец") }, text = { TimePicker(tp) },
            confirmButton = {
                TextButton({
                    val m = tp.hour * 60 + tp.minute
                    t = if (which == "start") t.copy(dueMinute = m) else { var d = m - (t.dueMinute ?: m); if (d <= 0) d += 1440; t.copy(durationMin = d) }
                    pickTime = null
                }) { Text("Ок") }
            }, dismissButton = { TextButton({ pickTime = null }) { Text("Отмена") } })
    }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Удалить задачу?") },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteTask(t0) }; confirmDel = false; onDone() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } })
}

@Composable
private fun TimeTile(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.05f)).clickable(onClick = onClick).padding(12.dp, 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (value == "—" || value == "Весь день") Pal.Muted else ACCENT)
    }
}

/** Календарь месяца в стиле приложения. */
@Composable
fun MonthPicker(month: YearMonth, onMonth: (YearMonth) -> Unit, selected: Long?, onPick: (Long) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
            IconButton({ onMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
        }
        Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Pal.Muted, style = MaterialTheme.typography.labelMedium) } }
        val lead = month.atDay(1).dayOfWeek.value - 1
        for (w in 0 until (lead + month.lengthOfMonth() + 6) / 7) Row {
            for (d in 0 until 7) {
                val n = w * 7 + d - lead + 1
                Box(Modifier.weight(1f).height(44.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (n in 1..month.lengthOfMonth()) {
                        val date = month.atDay(n); val sel = date.toEpochDay() == selected; val today = date == LocalDate.now()
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(if (sel) Pal.Ink else if (today) Pal.Lilac else Color.Transparent).clickable { onPick(date.toEpochDay()) },
                            contentAlignment = Alignment.Center) {
                            Text("$n", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (sel || today) FontWeight.ExtraBold else FontWeight.Medium),
                                color = if (sel) Color.White else if (date.isBefore(LocalDate.now())) Pal.Muted else Pal.Ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterDialog(f: TaskFilter, tags: List<String>, onChange: () -> Unit, onDismiss: () -> Unit) {
    Dialog({ onChange(); onDismiss() }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", { onChange(); onDismiss() })
                Text("Фильтр и сортировка", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormCard("Показать") { ChoiceGrid(Show.entries.map { it to it.title }, { it == f.show }, 3) { f.show = it } }
                FormCard("Сортировка") { ChoiceGrid(Sort.entries.map { it to it.title }, { it == f.sort }, 2) { f.sort = it } }
                FormCard("Приоритет") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(3, 2, 1, 0).forEach { p ->
                            val sel = p in f.prios
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) prioColor(p).copy(alpha = 0.18f) else Pal.Ink.copy(alpha = 0.05f))
                                .clickable { f.prios = if (sel) f.prios - p else f.prios + p }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(18.dp).clip(CircleShape).background(prioColor(p)))
                                Text(listOf("Нет", "Низкий", "Средний", "Высокий")[p], style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                if (tags.isNotEmpty()) FormCard("Тег") { ChoiceGrid(tags.map { it to "#$it" }, { it == f.tag }, 3) { f.tag = if (f.tag == it) null else it } }
                FormCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("Показывать выполненные", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(f.done, { f.done = it }) } }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Сбросить", { f.reset(); onChange() }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                PillButton("Готово", { onChange(); onDismiss() }, Modifier.weight(1f))
            }
        }
    }
}
