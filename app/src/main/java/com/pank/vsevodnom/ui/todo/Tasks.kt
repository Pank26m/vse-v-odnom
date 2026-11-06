package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.Send
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

val BLUE = Color(0xFF4772F5)
private val RU = java.util.Locale("ru")

/** Приоритет: 0 нет, 1 низкий, 2 средний, 3 высокий. */
val PRIO = listOf("Нет" to Color(0xFF9E9E9E), "Низкий" to Color(0xFF4772F5), "Средний" to Color(0xFFF2A33A), "Высокий" to Color(0xFFE5485D))
fun prioColor(p: Int) = PRIO[p.coerceIn(0, 3)].second

private val REMIND = listOf(null to "Нет", 0 to "Во время", 5 to "За 5 минут", 30 to "За 30 минут", 60 to "За 1 час", 1440 to "За 1 день")
private val REPEAT = listOf("" to "Не повторять", "daily" to "Каждый день", "weekdays" to "По будням", "weekly" to "Каждую неделю", "monthly" to "Каждый месяц", "yearly" to "Каждый год")

fun TaskItem.tagList() = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }
private fun hm(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60)
private fun durText(m: Int) = when { m % 60 == 0 -> "${m / 60} ч"; m > 60 -> "${m / 60} ч ${m % 60} мин"; else -> "$m мин" }

fun dateLabel(day: Long?): String {
    if (day == null) return "Без даты"
    val d = LocalDate.ofEpochDay(day); val t = LocalDate.now()
    return when (d) { t -> "Сегодня"; t.plusDays(1) -> "Завтра"; t.minusDays(1) -> "Вчера"; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == t.year) "d MMM" else "d MMM yyyy", RU)) }
}

// ================= Фильтры =================

private enum class Sort(val title: String) { DATE("По дате"), PRIORITY("По приоритету"), ALPHA("По алфавиту"), DURATION("По длительности"), CREATED("По времени создания") }
private enum class Show(val title: String) { ALL("Все"), OVERDUE("Просроченные"), TODAY("Сегодня"), WEEK("Ближайшие 7 дней"), NODATE("Без даты"), WITH_IMAGE("С фото") }

private class TaskFilter(p: android.content.SharedPreferences) {
    var sort by mutableStateOf(runCatching { Sort.valueOf(p.getString("sort", "DATE")!!) }.getOrDefault(Sort.DATE))
    var show by mutableStateOf(runCatching { Show.valueOf(p.getString("show", "ALL")!!) }.getOrDefault(Show.ALL))
    var prios by mutableStateOf(p.getString("prios", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet())
    var tag by mutableStateOf<String?>(p.getString("tag", null))
    var done by mutableStateOf(p.getBoolean("done", false))
    fun save(p: android.content.SharedPreferences) = p.edit().putString("sort", sort.name).putString("show", show.name).putString("prios", prios.joinToString(","))
        .putString("tag", tag).putBoolean("done", done).apply()
    val active get() = show != Show.ALL || prios.isNotEmpty() || tag != null || sort != Sort.DATE
}

@Composable
fun TasksPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    val prefs = remember { ctx.getSharedPreferences("tasks", android.content.Context.MODE_PRIVATE) }
    val f = remember { TaskFilter(prefs) }
    var adding by remember { mutableStateOf(false) }
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
            Box {
                RoundIconButton(Icons.Rounded.FilterList, "Фильтр", { filterOpen = true }, bg = if (f.active) Pal.Lilac else Pal.Card)
            }
        }) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AppTextField(q, { q = it }, "Поиск задач") }
                if (f.active) item {
                    Text(listOfNotNull(f.show.title.takeIf { f.show != Show.ALL }, f.sort.title.takeIf { f.sort != Sort.DATE },
                        f.prios.takeIf { it.isNotEmpty() }?.joinToString { PRIO[it].first }, f.tag?.let { "#$it" }).joinToString(" · ") + "  ✕",
                        style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Lilac).clickable { f.sort = Sort.DATE; f.show = Show.ALL; f.prios = emptySet(); f.tag = null; f.save(prefs) }.padding(10.dp, 6.dp))
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
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape).background(BLUE).clickable { adding = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить задачу", tint = Color.White)
        }
    }
    if (adding) TaskSheet(TaskItem(title = "", dueDay = today), allTags) { adding = false }
    edit?.let { TaskSheet(it, allTags) { edit = null } }
    if (filterOpen) FilterDialog(f, allTags, { f.save(prefs) }) { filterOpen = false }
}

@Composable
private fun TaskRow(t: TaskItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now().toEpochDay()
    val overdue = !t.done && (t.dueDay ?: Long.MAX_VALUE) < today
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // квадратная галочка цвета приоритета, как в TickTick
        Box(Modifier.padding(6.dp).size(22.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, prioColor(t.priority), RoundedCornerShape(5.dp))
            .background(if (t.done) prioColor(t.priority).copy(alpha = 0.25f) else Color.Transparent)
            .clickable { AppScope.io.launch { if (!t.done) Todo.complete(ctx, t) else Todo.undo(ctx, t) } }, contentAlignment = Alignment.Center) {
            if (t.done) Icon(Icons.Rounded.Check, null, tint = prioColor(t.priority), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null), color = if (t.done) Pal.Muted else Pal.Ink, maxLines = 2)
            if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (t.tagList().isNotEmpty()) Text(t.tagList().joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = BLUE)
        }
        if (t.image.isNotBlank()) Icon(Icons.Rounded.Image, null, Modifier.size(16.dp), tint = Pal.Muted)
        if (t.repeat.isNotBlank()) Icon(Icons.Rounded.Repeat, null, Modifier.size(16.dp), tint = Pal.Muted)
        if (t.remindBefore != null) Icon(Icons.Rounded.Alarm, null, Modifier.size(16.dp), tint = Pal.Muted)
        if (t.dueDay != null) Text(" " + dateLabel(t.dueDay) + (t.dueMinute?.let { ", " + hm(it) } ?: ""), style = MaterialTheme.typography.labelMedium,
            color = if (overdue) Pal.RoseInk else if (t.dueDay == today) BLUE else Pal.Muted)
    }
}

// ================= Быстрое добавление / изменение =================

/** Окно задачи как в TickTick: текст, описание, ниже — дата, флажок, теги, фото и «отправить». */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(t0: TaskItem, knownTags: List<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(t0) }
    var tagMenu by remember { mutableStateOf(false) }
    var prioMenu by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { copyIntoApp(ctx, it, "tasks")?.let { p -> t = t.copy(image = p) } } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { if (t0.id == 0L) runCatching { fr.requestFocus() } }
    fun save() {
        if (t.title.isBlank()) { onDone(); return }
        val item = t.copy(title = t.title.trim(), note = t.note.trim())
        AppScope.io.launch {
            val id = AppDb.get(ctx).todo().upsertTask(item)
            Todo.scheduleReminder(ctx, item.copy(id = if (item.id == 0L) id else item.id))
        }
        onDone()
    }
    ModalBottomSheet(onDismissRequest = { if (t0.id != 0L) save() else onDone() }, sheetState = sheet, containerColor = Color.White, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp, 16.dp, 16.dp, 8.dp)) {
            if (t0.id != 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, prioColor(t.priority), RoundedCornerShape(5.dp)).background(if (t.done) prioColor(t.priority).copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { t = t.copy(done = !t.done, doneAt = if (!t.done) System.currentTimeMillis() else null) })
                Spacer(Modifier.weight(1f))
                IconButton({ confirmDel = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.Muted) }
            }
            BasicTextField(t.title, { t = t.copy(title = it) }, Modifier.fillMaxWidth().focusRequester(fr), textStyle = MaterialTheme.typography.titleLarge.copy(color = Pal.Ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(BLUE), decorationBox = { inner -> if (t.title.isEmpty()) Text("Что бы вы хотели сделать?", style = MaterialTheme.typography.titleLarge, color = Color(0xFFC4C4C4)); inner() })
            Spacer(Modifier.height(8.dp))
            BasicTextField(t.note, { t = t.copy(note = it) }, Modifier.fillMaxWidth().heightIn(min = 40.dp), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pal.Ink),
                cursorBrush = SolidColor(BLUE), decorationBox = { inner -> if (t.note.isEmpty()) Text("Описание", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC4C4C4)); inner() })
            if (t.image.isNotBlank()) Box(Modifier.padding(top = 8.dp)) {
                AppImage(t.image, Modifier.fillMaxWidth().height(160.dp), maxPx = 900)
                IconButton({ t = t.copy(image = "") }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Rounded.Close, "Убрать фото", tint = Color.White) }
            }
            if (t.tagList().isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                t.tagList().forEach { tg -> Text("#$tg ✕", style = MaterialTheme.typography.labelLarge, color = BLUE, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BLUE.copy(alpha = 0.1f))
                    .clickable { t = t.copy(tags = (t.tagList() - tg).joinToString(",")) }.padding(8.dp, 3.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val today = LocalDate.now().toEpochDay()
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { dateOpen = true }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = if (t.dueDay != null && t.dueDay!! < today && !t.done) Pal.RoseInk else BLUE)
                    Text(" " + dateLabel(t.dueDay) + (t.dueMinute?.let { ", " + hm(it) } ?: ""), color = if (t.dueDay != null && t.dueDay!! < today && !t.done) Pal.RoseInk else BLUE, style = MaterialTheme.typography.bodyLarge)
                }
                Box {
                    IconButton({ prioMenu = true }) { Icon(if (t.priority > 0) Icons.Rounded.Flag else Icons.Rounded.OutlinedFlag, "Приоритет", tint = if (t.priority > 0) prioColor(t.priority) else Pal.Muted) }
                    DropdownMenu(prioMenu, { prioMenu = false }) {
                        (3 downTo 0).forEach { p -> DropdownMenuItem({ Text(PRIO[p].first + " приоритет") }, { t = t.copy(priority = p); prioMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Flag, null, tint = PRIO[p].second) }, trailingIcon = { if (t.priority == p) Icon(Icons.Rounded.Check, null) }) }
                    }
                }
                Box {
                    IconButton({ tagMenu = true }) { Icon(Icons.Rounded.Sell, "Теги", tint = if (t.tagList().isNotEmpty()) BLUE else Pal.Muted) }
                    DropdownMenu(tagMenu, { tagMenu = false }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(170.dp)) { AppTextField(newTag, { newTag = it.replace(",", "") }, "Новый тег") }
                            IconButton({ if (newTag.isNotBlank()) { t = t.copy(tags = (t.tagList() + newTag.trim()).distinct().joinToString(",")); newTag = "" } }) { Icon(Icons.Rounded.Add, "Добавить") }
                        }
                        (knownTags + t.tagList()).distinct().forEach { tg ->
                            val on = tg in t.tagList()
                            DropdownMenuItem({ Text("#$tg") }, { t = t.copy(tags = (if (on) t.tagList() - tg else t.tagList() + tg).joinToString(",")) }, trailingIcon = { if (on) Icon(Icons.Rounded.Check, null) })
                        }
                    }
                }
                IconButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Rounded.AddPhotoAlternate, "Добавить изображение", tint = if (t.image.isNotBlank()) BLUE else Pal.Muted) }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (t.title.isBlank()) BLUE.copy(alpha = 0.35f) else BLUE).clickable { save() }, contentAlignment = Alignment.Center) {
                    Icon(if (t0.id == 0L) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Check, "Сохранить", tint = Color.White)
                }
            }
        }
    }
    if (dateOpen) DateDialog(t, onDismiss = { dateOpen = false }) { t = it; dateOpen = false }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Удалить задачу?") },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteTask(t0) }; confirmDel = false; onDone() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } })
}

// ================= Подробный выбор даты: «Дата» и «Длительность» =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(t0: TaskItem, onDismiss: () -> Unit, onApply: (TaskItem) -> Unit) {
    var tab by remember { mutableIntStateOf(if (t0.durationMin != null) 1 else 0) }
    var day by remember { mutableStateOf(t0.dueDay ?: LocalDate.now().toEpochDay()) }
    var minute by remember { mutableStateOf(t0.dueMinute) }
    var dur by remember { mutableStateOf(t0.durationMin) }
    var remind by remember { mutableStateOf(t0.remindBefore) }
    var repeat by remember { mutableStateOf(t0.repeat) }
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(day))) }
    var pick by remember { mutableStateOf<String?>(null) }   // time | end | remind | repeat | date
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
                listOf("Дата", "Длительность").forEachIndexed { i, s ->
                    Column(Modifier.padding(horizontal = 10.dp).clickable { tab = i; if (i == 1 && minute == null) { minute = 17 * 60; dur = 60 } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s, style = MaterialTheme.typography.titleLarge, color = if (tab == i) BLUE else Color(0xFFA0A0A0))
                        Box(Modifier.padding(top = 4.dp).height(3.dp).width(if (tab == i) 60.dp else 0.dp).clip(RoundedCornerShape(2.dp)).background(BLUE))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton({ onApply(t0.copy(dueDay = day, dueMinute = minute, durationMin = if (minute == null) null else dur, remindBefore = remind, repeat = repeat)) }) { Icon(Icons.Rounded.Check, "Готово") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(month.atDay(1).format(DateTimeFormatter.ofPattern(if (month.year == LocalDate.now().year) "LLLL" else "LLLL yyyy", RU)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton({ month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше", tint = Pal.Muted) }
                        IconButton({ month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже", tint = Pal.Muted) }
                    }
                    Row { listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Pal.Muted, style = MaterialTheme.typography.bodyMedium) } }
                    val first = month.atDay(1); val lead = first.dayOfWeek.value - 1
                    val cells = lead + month.lengthOfMonth()
                    for (w in 0 until (cells + 6) / 7) Row {
                        for (d in 0 until 7) {
                            val n = w * 7 + d - lead + 1
                            Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                                if (n in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(n); val sel = date.toEpochDay() == day
                                    Box(Modifier.size(46.dp).clip(CircleShape).background(if (sel) BLUE else Color.Transparent).clickable { day = date.toEpochDay() }, contentAlignment = Alignment.Center) {
                                        Text("$n", style = MaterialTheme.typography.titleMedium, color = if (sel) Color.White else if (date == LocalDate.now()) BLUE else Pal.Ink)
                                    }
                                }
                            }
                        }
                    }
                    SettingsCard {
                        SettingRow(Icons.Rounded.Schedule, "Срок исполнения", minute?.let { hm(it) + (dur?.let { d -> " – " + hm(it + d) } ?: "") } ?: "Нет", minute != null, { pick = "time" }, if (minute != null) ({ minute = null; dur = null }) else null)
                        SettingRow(Icons.Rounded.Alarm, "Уведомление", REMIND.firstOrNull { it.first == remind }?.second ?: "Нет", remind != null, { pick = "remind" }, if (remind != null) ({ remind = null }) else null)
                        SettingRow(Icons.Rounded.Repeat, REPEAT.firstOrNull { it.first == repeat }?.second ?: "Не повторять", if (repeat.isNotBlank()) "" else "Нет", repeat.isNotBlank(), { pick = "repeat" }, if (repeat.isNotBlank()) ({ repeat = "" }) else null)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { tab = 0 }.padding(16.dp)) {
                            Text("Дата", style = MaterialTheme.typography.bodyLarge)
                            Text(LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EE, d MMM", RU)), style = MaterialTheme.typography.titleLarge, color = BLUE)
                            Text(dateLabel(day).takeIf { it in setOf("Сегодня", "Завтра", "Вчера") } ?: LocalDate.ofEpochDay(day).year.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(16.dp)) {
                            Text("Срок исполнения", style = MaterialTheme.typography.bodyLarge)
                            if (minute == null) Text("Весь день", style = MaterialTheme.typography.titleLarge, color = BLUE)
                            else Row {
                                Text(hm(minute!!), style = MaterialTheme.typography.titleLarge, color = BLUE, modifier = Modifier.clickable { pick = "time" })
                                Text(" – ", style = MaterialTheme.typography.titleLarge, color = BLUE)
                                Text(hm(minute!! + (dur ?: 60)), style = MaterialTheme.typography.titleLarge, color = BLUE, modifier = Modifier.clickable { pick = "end" })
                            }
                            Text(if (minute == null) "без времени" else "Длительность: ${durText(dur ?: 60)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SettingsCard {
                        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Весь день", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(minute == null, { if (it) { minute = null; dur = null } else { minute = 17 * 60; dur = 60 } })
                        }
                    }
                    if (minute != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        listOf(15, 30, 60, 90, 120).forEach { m -> Chip(durText(m), dur == m, { dur = m }, Modifier.padding(horizontal = 3.dp)) }
                    }
                    Text("Киев, GMT" + ZoneId.systemDefault().rules.getOffset(Instant.now()).id.replace("Z", "+00:00"), style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    SettingsCard {
                        SettingRow(Icons.Rounded.Alarm, "Уведомление", REMIND.firstOrNull { it.first == remind }?.second ?: "Нет", remind != null, { pick = "remind" }, if (remind != null) ({ remind = null }) else null)
                        SettingRow(Icons.Rounded.Repeat, REPEAT.firstOrNull { it.first == repeat }?.second ?: "Не повторять", if (repeat.isNotBlank()) "" else "Нет", repeat.isNotBlank(), { pick = "repeat" }, if (repeat.isNotBlank()) ({ repeat = "" }) else null)
                    }
                }
            }
            TextButton({ onApply(t0.copy(dueDay = null, dueMinute = null, durationMin = null, remindBefore = null, repeat = "")) }, Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("ОЧИСТИТЬ", color = Color(0xFFE53935), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    when (pick) {
        "time", "end" -> {
            val base = if (pick == "time") (minute ?: 17 * 60) else ((minute ?: 17 * 60) + (dur ?: 60))
            val tp = rememberTimePickerState(base / 60 % 24, base % 60, true)
            AlertDialog(onDismissRequest = { pick = null }, title = { Text(if (pick == "time") "Начало" else "Конец") }, text = { TimePicker(tp) },
                confirmButton = {
                    TextButton({
                        val m = tp.hour * 60 + tp.minute
                        if (pick == "time") { minute = m; if (dur == null) dur = 60 } else { var d = m - (minute ?: m); if (d <= 0) d += 1440; dur = d }
                        pick = null
                    }) { Text("Ок") }
                }, dismissButton = { TextButton({ pick = null }) { Text("Отмена") } })
        }
        "remind" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Уведомление") }, confirmButton = {},
            text = { Column { REMIND.forEach { (v, s) -> Row(Modifier.fillMaxWidth().clickable { remind = v; pick = null }.padding(12.dp)) { RadioButton(remind == v, null); Text("  $s") } } } })
        "repeat" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Повтор") }, confirmButton = {},
            text = { Column { REPEAT.forEach { (v, s) -> Row(Modifier.fillMaxWidth().clickable { repeat = v; pick = null }.padding(12.dp)) { RadioButton(repeat == v, null); Text("  $s") } } } })
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(vertical = 6.dp), content = content)
}

@Composable
fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, active: Boolean, onClick: () -> Unit, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) BLUE else Pal.Muted)
        Text("  $title", style = MaterialTheme.typography.bodyLarge, color = if (active) BLUE else Pal.Ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (active) BLUE else Color(0xFFA0A0A0))
        if (onClear != null) Icon(Icons.Rounded.Close, "Убрать", Modifier.padding(start = 6.dp).size(20.dp).clickable(onClick = onClear), tint = BLUE)
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFA0A0A0))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(f: TaskFilter, tags: List<String>, onChange: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { onChange(); onDismiss() }, title = { Text("Фильтр и сортировка") },
        confirmButton = { TextButton({ onChange(); onDismiss() }) { Text("Готово") } },
        dismissButton = { TextButton({ f.sort = Sort.DATE; f.show = Show.ALL; f.prios = emptySet(); f.tag = null; onChange(); onDismiss() }) { Text("Сбросить") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Показать")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Show.entries.forEach { s -> Chip(s.title, f.show == s, { f.show = s }) } }
                SectionLabel("Сортировка")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Sort.entries.forEach { s -> Chip(s.title, f.sort == s, { f.sort = s }) } }
                SectionLabel("Приоритет")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (3 downTo 0).forEach { p -> Chip(PRIO[p].first, p in f.prios, { f.prios = if (p in f.prios) f.prios - p else f.prios + p }) }
                }
                if (tags.isNotEmpty()) {
                    SectionLabel("Тег")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { tg -> Chip("#$tg", f.tag == tg, { f.tag = if (f.tag == tg) null else tg }) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Показывать выполненные", Modifier.weight(1f)); Switch(f.done, { f.done = it }) }
            }
        })
}
