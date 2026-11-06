package com.pank.vsevodnom.ui.calendar

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.CalEvent
import com.pank.vsevodnom.data.SubjectAlias
import com.pank.vsevodnom.data.schedule.Agenda
import com.pank.vsevodnom.data.schedule.CalendarRepo
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import com.pank.vsevodnom.widget.ScheduleWidget
import com.pank.vsevodnom.widget.dayTitle
import com.pank.vsevodnom.widget.row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")
private val MONTHS_NOM = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
private val DOW = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** Uri файла расписания, пришедшего через «Открыть с помощью». */
object PendingImport { var uri by mutableStateOf<Uri?>(null) }

@Composable
fun CalendarScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).schedule() }
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var menu by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<CalEvent?>(null) }
    var namingSubjects by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var sysVersion by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    // окно данных: видимый месяц целиком (+ хвосты соседних недель)
    val gridStart = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
    val gridEnd = gridStart.plusDays(41)
    val zone = ZoneId.systemDefault()
    val lessons by remember(gridStart) { dao.lessons(gridStart.toEpochDay(), gridEnd.toEpochDay()) }.collectAsState(initial = emptyList())
    val aliases by dao.aliases().collectAsState(initial = emptyList())
    val own by remember(gridStart) {
        dao.events(gridStart.atStartOfDay(zone).toInstant().toEpochMilli(), gridEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }.collectAsState(initial = emptyList())
    var sys by remember { mutableStateOf<List<Agenda.Sys>>(emptyList()) }
    LaunchedEffect(gridStart, sysVersion) {
        sys = withContext(Dispatchers.IO) { CalendarRepo.systemEvents(ctx, gridStart, gridEnd) }
    }

    val aliasMap = aliases.associateBy { it.abbr }
    val all: List<Agenda> = remember(lessons, aliases, own, sys) {
        (lessons.map { Agenda.Pair(it, aliasMap[it.subject]) } +
            own.flatMap { CalendarRepo.expandOwn(it, gridStart, gridEnd) } + sys).sortedBy { it.sortKey }
    }
    val byDay = all.groupBy { it.date }

    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sysVersion++; ScheduleWidget.refresh(ctx) }

    fun doImport(uri: Uri) {
        scope.launch {
            importMsg = runCatching { CalendarRepo.importSchedule(ctx, uri) }.fold(
                onSuccess = { r ->
                    month = YearMonth.from(r.from.coerceAtLeast(LocalDate.now()).coerceAtMost(r.to))
                    if (r.newSubjects.isNotEmpty()) namingSubjects = true
                    "Загружено пар: ${r.count}" + (if (r.group.isNotBlank()) "\nГруппа ${r.group}" else "") +
                        "\nПериод ${r.from.format(DateTimeFormatter.ofPattern("dd.MM"))}–${r.to.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}" +
                        "\nСтарые пары за этот период заменены."
                },
                onFailure = { "Не получилось: ${it.message ?: "неизвестная ошибка"}" }
            )
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::doImport) }
    LaunchedEffect(PendingImport.uri) { PendingImport.uri?.let { PendingImport.uri = null; doImport(it) } }

    ScreenScaffold("Календарь", onBack, actions = {
        RoundIconButton(Icons.Rounded.Add, "Новое событие", {
            val s = selected.atTime(LocalTime.now().plusHours(1).withMinute(0)).atZone(zone).toInstant().toEpochMilli()
            editEvent = CalEvent(title = "", startMillis = s, endMillis = s + 3600_000)
        })
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Загрузить расписание пар (.xls)") }, {
                    menu = false; picker.launch(arrayOf("application/vnd.ms-excel", "application/octet-stream", "*/*"))
                })
                DropdownMenuItem({ Text("Названия предметов") }, { menu = false; namingSubjects = true })
                if (!CalendarRepo.hasSysPermission(ctx)) DropdownMenuItem({ Text("Показывать Google Календарь") }, {
                    menu = false; calPerm.launch(Manifest.permission.READ_CALENDAR)
                })
                DropdownMenuItem({ Text("Удалить все пары", color = Pal.RoseInk) }, { menu = false; confirmClear = true })
            }
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Назад") }
                        Text(
                            "${MONTHS_NOM[month.monthValue - 1]} ${month.year}",
                            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clickable { month = YearMonth.now(); selected = LocalDate.now() }
                        )
                        IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Вперёд") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        DOW.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = Pal.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    for (w in 0 until 6) {
                        Row(Modifier.fillMaxWidth()) {
                            for (d in 0 until 7) {
                                val date = gridStart.plusDays((w * 7 + d).toLong())
                                DayCell(date, date.month == month.month, date == selected, date == LocalDate.now(), byDay[date].orEmpty(), Modifier.weight(1f)) {
                                    selected = date
                                }
                            }
                        }
                    }
                }
            }
            if (CalendarRepo.hasSysPermission(ctx).not() && lessons.isEmpty() && own.isEmpty()) item {
                AppCard(color = Pal.Lilac, modifier = Modifier.fillMaxWidth()) {
                    Text("Как наполнить календарь", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
                    Spacer(Modifier.height(4.dp))
                    Text("• Меню ⋮ → «Загрузить расписание пар» и выбери файл .xls из портала.\n• Или открой этот файл из Telegram/Файлов через «Открыть с помощью → Всё в одном».\n• «Показывать Google Календарь» — подтянет события из других приложений.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Text(dayTitle(selected), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            val dayItems = byDay[selected].orEmpty()
            if (dayItems.isEmpty()) item { EmptyHint("Ничего не запланировано") }
            items(dayItems) { a -> AgendaCard(a, onClick = { if (a is Agenda.Own) editEvent = a.e }) }
        }
    }

    importMsg?.let { m ->
        AlertDialog(onDismissRequest = { importMsg = null }, confirmButton = { TextButton({ importMsg = null }) { Text("Ок") } },
            title = { Text("Расписание") }, text = { Text(m) })
    }
    if (namingSubjects) SubjectNamesDialog(aliases) { namingSubjects = false }
    editEvent?.let { e -> EventEditor(e, onDismiss = { editEvent = null }) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Удалить все пары из календаря?") },
        text = { Text("Свои события и Google Календарь не тронутся.") },
        confirmButton = { TextButton({ scope.launch { dao.deleteAll(); ScheduleWidget.refresh(ctx) }; confirmClear = false }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } }
    )
}

@Composable
private fun DayCell(date: LocalDate, inMonth: Boolean, selected: Boolean, today: Boolean, items: List<Agenda>, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Pal.Ink else if (today) Pal.Lilac else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (today || selected) FontWeight.ExtraBold else FontWeight.SemiBold),
            color = when { selected -> Color.White; !inMonth -> Pal.Muted.copy(alpha = 0.5f); else -> Pal.Ink }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items.take(4).forEach { a ->
                val c = when (a) {
                    is Agenda.Pair -> Tints.all[a.tint % 6].ink
                    is Agenda.Own -> Tints.all[a.e.tint % 6].ink
                    is Agenda.Sys -> Color(a.color or 0xFF000000.toInt())
                }
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (selected) Color.White else c))
            }
        }
    }
}

@Composable
fun AgendaCard(a: Agenda, onClick: () -> Unit = {}) {
    val r = a.row()
    AppCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(r.color).copy(alpha = 0.55f)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (r.sub.isNotBlank()) Text(r.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Text(r.time, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun SubjectNamesDialog(aliases: List<SubjectAlias>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).schedule() }
    val scope = com.pank.vsevodnom.data.AppScope.io
    val subjects by dao.subjects().collectAsState(initial = emptyList())
    val edits = remember { mutableStateMapOf<String, String>() }
    val tints = remember { mutableStateMapOf<String, Int>() }
    val map = aliases.associateBy { it.abbr }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Названия предметов") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Впиши полные названия — в календаре и виджете будут они. Можно оставить пустым.", style = MaterialTheme.typography.bodySmall) }
                items(subjects) { s ->
                    val tint = tints[s] ?: map[s]?.tint ?: com.pank.vsevodnom.data.schedule.tintFor(s)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Tints.all.forEachIndexed { i, t ->
                                Box(
                                    Modifier.padding(start = 4.dp).size(if (i == tint) 22.dp else 16.dp).clip(CircleShape)
                                        .background(t.ink.copy(alpha = if (i == tint) 0.8f else 0.35f)).clickable { tints[s] = i }
                                )
                            }
                        }
                        AppTextField(edits[s] ?: map[s]?.fullName ?: "", { edits[s] = it }, "Полное название")
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    (edits.keys + tints.keys).forEach { s ->
                        dao.upsertAlias(SubjectAlias(s, (edits[s] ?: map[s]?.fullName ?: "").trim(), tints[s] ?: map[s]?.tint ?: com.pank.vsevodnom.data.schedule.tintFor(s)))
                    }
                    ScheduleWidget.refresh(ctx)
                }
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(e: CalEvent, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).schedule() }
    val scope = com.pank.vsevodnom.data.AppScope.io
    val zone = ZoneId.systemDefault()
    val start0 = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDateTime()
    val durMin = ((e.endMillis - e.startMillis) / 60_000).coerceAtLeast(15)
    var title by remember { mutableStateOf(e.title) }
    var note by remember { mutableStateOf(e.note) }
    var allDay by remember { mutableStateOf(e.allDay) }
    var date by remember { mutableStateOf(start0.toLocalDate()) }
    var dur by remember { mutableLongStateOf(durMin) }
    var tint by remember { mutableIntStateOf(e.tint) }
    val tp = rememberTimePickerState(start0.hour, start0.minute, true)
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.id == 0L) "Новое событие" else "Событие") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(title, { title = it }, "Название")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), false, { pickDate = true })
                    if (!allDay) Chip("%02d:%02d".format(tp.hour, tp.minute), false, { pickTime = true })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Весь день", allDay, { allDay = !allDay })
                    if (!allDay) listOf(30L to "30 мин", 60L to "1 ч", 120L to "2 ч").forEach { (m, t) -> Chip(t, dur == m, { dur = m }) }
                }
                AppTextField(note, { note = it }, "Заметка", singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tints.all.forEachIndexed { i, t ->
                        Box(Modifier.size(if (i == tint) 30.dp else 24.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.8f else 0.35f)).clickable { tint = i })
                    }
                }
                if (e.id != 0L) TextButton({ scope.launch { dao.deleteEvent(e); ScheduleWidget.refresh(ctx) }; onDismiss() }) { Text("Удалить", color = Pal.RoseInk) }
            }
        },
        confirmButton = {
            TextButton({
                if (title.isBlank()) return@TextButton
                val s = if (allDay) date.atStartOfDay(zone) else date.atTime(tp.hour, tp.minute).atZone(zone)
                val end = if (allDay) s.plusDays(1) else s.plusMinutes(dur)
                scope.launch {
                    dao.upsertEvent(e.copy(title = title.trim(), note = note.trim(), allDay = allDay, startMillis = s.toInstant().toEpochMilli(), endMillis = end.toInstant().toEpochMilli(), tint = tint))
                    ScheduleWidget.refresh(ctx)
                }
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
    if (pickTime) AlertDialog(onDismissRequest = { pickTime = false }, confirmButton = { TextButton({ pickTime = false }) { Text("Ок") } }, text = { TimePicker(tp) })
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    dp.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("Ок") }
            }
        ) { DatePicker(dp) }
    }
}
