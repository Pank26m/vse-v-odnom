package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")
private val HBG = Color(0xFFF1F3F9)
private val DOW2 = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
private val SECTIONS = listOf("Утро", "После обеда", "Ночь", "Другие")
private val CIRCLE = listOf(Color(0xFF9BE07F), Color(0xFF7FE0CF), Color(0xFFFF9FA8), Color(0xFFB4E37A), Color(0xFFFFD45C), Color(0xFF6F7FE8),
    Color(0xFF7FC4F2), Color(0xFF7FDDE8), Color(0xFFF59BE0), Color(0xFFB59BF2))

/** Готовые привычки для галереи: эмодзи, название, подпись, раздел галереи. */
private data class Preset(val icon: String, val title: String, val sub: String, val group: String, val section: String = "Другие", val perDay: Int = 1)
private val PRESETS = listOf(
    Preset("🙂", "Ежедневная проверка", "Постарайтесь чуть больше, чтобы стать чуть лучше", "Предложенные"),
    Preset("💧", "Выпить воды", "Будьте в тонусе", "Предложенные", perDay = 8),
    Preset("🍳", "Позавтракать", "Жизнь начинается после завтрака", "Предложенные", "Утро"),
    Preset("🍌", "Поесть фруктов", "Будьте здоровы, будьте счастливы", "Предложенные"),
    Preset("☀️", "Рано вставать", "Просыпайтесь и будьте на кураже", "Предложенные", "Утро"),
    Preset("🌙", "Рано ложиться", "Помечтать о возвышенном", "Предложенные", "Ночь"),
    Preset("📘", "Выучить что-то новое", "Небольшое количество — большой результат", "Предложенные"),
    Preset("📖", "Чтение", "Одна глава в день прогонит прочь тень", "Предложенные", "Ночь"),
    Preset("🍦", "Бросить перекусывать", "Перестаньте заедать эмоции", "Предложенные"),
    Preset("🏋️", "Упражнения", "Зарядите энергией тело и отточите ум", "Предложенные", "Утро"),
    Preset("🛏️", "Заправить кровать", "Маленькая победа с утра", "Рутина", "Утро"),
    Preset("🪥", "Почистить зубы", "Дважды в день", "Рутина", perDay = 2),
    Preset("🧹", "Убраться 10 минут", "Порядок вокруг — порядок в голове", "Рутина"),
    Preset("📝", "Планировать день", "Пять минут, чтобы не терять часы", "Рутина", "Утро"),
    Preset("📵", "Без телефона перед сном", "Сон станет глубже", "Рутина", "Ночь"),
    Preset("💊", "Витамины", "Не забыть с утра", "Здоровье", "Утро"),
    Preset("🥗", "Овощи в каждом приёме пищи", "Тарелка наполовину зелёная", "Здоровье"),
    Preset("🚭", "Не курить", "Каждый день без сигареты — победа", "Здоровье"),
    Preset("🍺", "Без алкоголя", "Ясная голова", "Здоровье"),
    Preset("😴", "Спать 8 часов", "Сон — лучшая восстановка", "Здоровье", "Ночь"),
    Preset("🚶", "10 000 шагов", "Ходьба — самый простой спорт", "Спорт"),
    Preset("🚴", "Велосипед", "Крутить педали хотя бы 30 минут", "Спорт"),
    Preset("🧘", "Растяжка", "Гибкость и меньше боли в спине", "Спорт", "Утро"),
    Preset("🏃", "Пробежка", "Лёгкие пять километров", "Спорт", "Утро"),
    Preset("💪", "Отжимания", "По 20 раз", "Спорт", perDay = 3),
    Preset("🙏", "Благодарность", "Запиши три хороших вещи за день", "Настроение", "Ночь"),
    Preset("🧠", "Медитация", "10 минут тишины", "Настроение", "Утро"),
    Preset("📓", "Дневник", "Пара строк о дне", "Настроение", "Ночь"),
    Preset("🌳", "Выйти на улицу", "Свежий воздух и солнце", "Настроение", "После обеда"),
    Preset("☎️", "Позвонить близким", "Тёплые слова не бывают лишними", "Настроение"),
)

@Composable
fun HabitsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val habits by dao.habits().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    var day by remember { mutableStateOf(today) }
    val logs by remember { dao.habitLogs(today.minusDays(400).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    var gallery by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Habit?>(null) }
    var detail by remember { mutableStateOf<Habit?>(null) }
    var journal by remember { mutableStateOf<Habit?>(null) }

    if (gallery) { BackHandler { gallery = false }; HabitGallery(onBack = { gallery = false }, onPick = { edit = it; gallery = false }); edit?.let { HabitEditor(it) { edit = null } }; return }

    val due = habits.filter { Todo.isDue(it, day, logs) }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Привычки", onBack) {
            // неделя: кружки с прогрессом по дням
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                (6 downTo 0).forEach { k ->
                    val d = today.minusDays(k.toLong())
                    val dueD = habits.filter { Todo.isDue(it, d, logs) }
                    val doneD = dueD.count { h -> (logs.firstOrNull { it.habitId == h.id && it.epochDay == d.toEpochDay() }?.count ?: 0) >= h.perDay }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (d == day) BLUE.copy(alpha = 0.1f) else Color.Transparent).clickable { day = d }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(DOW2[d.dayOfWeek.value - 1].lowercase(), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawCircle(Color(0xFFE3E7EF), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                                if (dueD.isNotEmpty()) drawArc(BLUE, -90f, 360f * doneD / dueD.size, false, style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                            }
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = if (d == today) BLUE else Pal.Ink)
                        }
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (habits.isEmpty()) item { EmptyHint("Привычек пока нет. Нажми «+» — там галерея готовых идей.") }
                else if (due.isEmpty()) item { EmptyHint("На этот день привычек нет") }
                (SECTIONS + due.map { it.section.ifBlank { "Другие" } }).distinct().forEach { sec ->
                    val list = due.filter { it.section.ifBlank { "Другие" } == sec }
                    if (list.isNotEmpty()) {
                        item(key = "s$sec") { Text(sec, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp)) }
                        items(list, key = { it.id }) { h ->
                            val cnt = logs.firstOrNull { it.habitId == h.id && it.epochDay == day.toEpochDay() }?.count ?: 0
                            val done = cnt >= h.perDay
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { detail = h }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).clip(CircleShape).background(CIRCLE[h.tint % CIRCLE.size]), contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }, fontSize = 22.sp) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(h.title, style = MaterialTheme.typography.titleSmall)
                                    Text(listOfNotNull("серия ${Todo.streak(h, logs, today)} дн.", h.perDay.takeIf { it > 1 }?.let { "$cnt/$it" },
                                        HabitReminders.times(h).takeIf { it.isNotEmpty() }?.joinToString { "%02d:%02d".format(it / 60, it % 60) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if (done) BLUE else HBG).clickable {
                                    AppScope.io.launch { Todo.tickHabit(ctx, h, day) }
                                    if (!done && cnt + 1 >= h.perDay && h.autoLog) journal = h
                                }, contentAlignment = Alignment.Center) {
                                    if (done) Icon(Icons.Rounded.Check, "Готово", tint = Color.White) else if (h.perDay > 1) Text("+1", color = BLUE, style = MaterialTheme.typography.labelLarge) else Icon(Icons.Rounded.Check, "Отметить", tint = Color(0xFFB8C0D0))
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape).background(BLUE).clickable { gallery = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Новая привычка", tint = Color.White)
        }
    }
    edit?.let { HabitEditor(it) { edit = null } }
    detail?.let { h -> HabitDetail(h, logs, onEdit = { edit = h; detail = null }) { detail = null } }
    journal?.let { h -> HabitJournalDialog(h, day) { journal = null } }
}

// ================= Галерея (скриншот: «Предложенные / Рутина / …») =================

@Composable
private fun HabitGallery(onBack: () -> Unit, onPick: (Habit) -> Unit) {
    val groups = PRESETS.map { it.group }.distinct()
    var g by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(HBG).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Галерея", style = MaterialTheme.typography.headlineMedium)
        }
        ScrollableTabRow(g, edgePadding = 12.dp, containerColor = HBG, divider = {}) {
            groups.forEachIndexed { i, s -> Tab(g == i, { g = i }, text = { Text(s, style = MaterialTheme.typography.titleSmall, color = if (g == i) Pal.Ink else Pal.Muted) }) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PRESETS.filter { it.group == groups[g] }) { p ->
                val idx = PRESETS.indexOf(p)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable {
                    onPick(Habit(title = p.title, icon = p.icon, goal = p.sub, section = p.section, perDay = p.perDay, tint = idx % CIRCLE.size, startDay = LocalDate.now().toEpochDay()))
                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(CIRCLE[idx % CIRCLE.size]), contentAlignment = Alignment.Center) { Text(p.icon, fontSize = 26.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                        Text(p.sub, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, maxLines = 2)
                    }
                    Icon(Icons.Rounded.Add, "Добавить", tint = Color(0xFFC4C8D0))
                }
            }
        }
        Button({ onPick(Habit(title = "", startDay = LocalDate.now().toEpochDay())) }, Modifier.fillMaxWidth().padding(12.dp).height(56.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BLUE)) { Text("Создать новую привычку", style = MaterialTheme.typography.titleMedium) }
    }
}

// ================= Новая привычка (скриншот: Частота / Цель / Раздел / Уведомление) =================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditor(h0: Habit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var h by remember { mutableStateOf(h0) }
    var pick by remember { mutableStateOf<String?>(null) }
    var newSection by remember { mutableStateOf<String?>(null) }
    val reminders = remember { mutableStateListOf<Int>().apply { addAll(HabitReminders.times(h0).ifEmpty { if (h0.id == 0L) listOf(7 * 60) else emptyList() }) } }
    val sections = remember { mutableStateListOf<String>().apply { addAll((SECTIONS + h0.section).filter { it.isNotBlank() }.distinct()) } }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(HBG).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text(if (h0.id == 0L) "Новая привычка" else "Привычка", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (h0.id != 0L) IconButton({ pick = "delete" }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.Muted) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WhiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(CIRCLE[h.tint % CIRCLE.size]).clickable { pick = "icon" }, contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }, fontSize = 26.sp) }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { AppTextField(h.title, { h = h.copy(title = it) }, "Название привычки") }
                    }
                }
                WhiteCard {
                    Text("Частота", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        listOf("ЕЖЕДНЕВНО", "ЕЖЕНЕДЕЛЬНО", "ПОВТОР").forEachIndexed { i, s ->
                            Column(Modifier.padding(end = 18.dp).clickable { h = h.copy(freqType = i, freqN = if (i == 1) h.freqN.coerceIn(1, 7).let { if (h.freqType != 1) 3 else it } else if (i == 2) h.freqN.coerceAtLeast(2) else h.freqN) }) {
                                Text(s, style = MaterialTheme.typography.titleSmall, color = if (h.freqType == i) BLUE else Color(0xFF9AA0AA))
                                Box(Modifier.padding(top = 6.dp).height(4.dp).width(if (h.freqType == i) 70.dp else 0.dp).clip(RoundedCornerShape(2.dp)).background(BLUE))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    when (h.freqType) {
                        0 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DOW2.forEachIndexed { i, d ->
                                val on = h.daysMask and (1 shl i) != 0
                                Box(Modifier.size(42.dp).clip(CircleShape).background(if (on) BLUE else HBG).clickable { h = h.copy(daysMask = (h.daysMask xor (1 shl i)).let { if (it == 0) h.daysMask else it }) },
                                    contentAlignment = Alignment.Center) { Text(d, color = if (on) Color.White else Pal.Muted, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                        1 -> Stepper("Дней в неделю", h.freqN, 1..7) { h = h.copy(freqN = it) }
                        else -> Stepper("Каждые N дней", h.freqN, 2..30) { h = h.copy(freqN = it) }
                    }
                }
                WhiteCard(padding = 0.dp) {
                    ValueRow("Цель", if (h.perDay > 1) "${h.perDay} раз в день" else "Достигните всего этого") { pick = "goal" }
                    ValueRow("Дата начала", (h.startDay ?: LocalDate.now().toEpochDay()).let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM", RU)) }) { pick = "start" }
                    ValueRow("Цель в днях", h.goalDays?.let { "$it дн." } ?: "Вечно", info = true) { pick = "days" }
                }
                WhiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Раздел", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton({ newSection = "" }) { Icon(Icons.Rounded.Add, "Свой раздел", tint = Color(0xFFB8BEC8)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sections.forEach { s ->
                            val sel = h.section.ifBlank { "Другие" } == s
                            Box(Modifier.widthIn(min = 76.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) BLUE else Color(0xFFF0F0F2)).clickable { h = h.copy(section = s) }.padding(14.dp, 12.dp), contentAlignment = Alignment.Center) {
                                Text(s, color = if (sel) Color.White else Pal.Ink, maxLines = 1)
                            }
                        }
                    }
                }
                WhiteCard {
                    Text("Уведомление", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        reminders.sorted().forEach { m ->
                            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF0F0F2)).clickable { reminders.remove(m) }.padding(18.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("%02d:%02d".format(m / 60, m % 60), style = MaterialTheme.typography.titleMedium); Text("  ✕", color = Pal.Muted)
                            }
                        }
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { pick = "remind" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null, tint = BLUE); Text("Добавить", color = BLUE, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                WhiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Автоматическое отображение журнала привычек", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(h.autoLog, { h = h.copy(autoLog = it) })
                    }
                    Text("После отметки спросит, как прошло, и сохранит запись.", style = MaterialTheme.typography.bodySmall)
                }
                WhiteCard { AppTextField(h.goal, { h = h.copy(goal = it) }, "Мотивация (подпись под названием)") }
                Spacer(Modifier.height(8.dp))
            }
            Button({
                if (h.title.isBlank()) return@Button
                val saved = h.copy(title = h.title.trim(), reminders = reminders.sorted().joinToString(","), remindMinute = null, startDay = h.startDay ?: LocalDate.now().toEpochDay())
                AppScope.io.launch {
                    val id = AppDb.get(ctx).todo().upsertHabit(saved)
                    HabitReminders.schedule(ctx, saved.copy(id = if (saved.id == 0L) id else saved.id))
                }
                onDone()
            }, Modifier.fillMaxWidth().padding(12.dp).height(56.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = BLUE)) {
                Text("Сохранить", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    when (pick) {
        "goal" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Цель на день") }, confirmButton = { TextButton({ pick = null }) { Text("Готово") } },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { h = h.copy(perDay = 1) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(h.perDay == 1, null); Text("  Достигните всего этого (один раз)") }
                    Row(Modifier.fillMaxWidth().clickable { if (h.perDay == 1) h = h.copy(perDay = 2) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(h.perDay > 1, null); Text("  Несколько раз в день") }
                    if (h.perDay > 1) Stepper("Раз в день", h.perDay, 2..30) { h = h.copy(perDay = it) }
                }
            })
        "days" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Цель в днях") }, confirmButton = {},
            text = {
                Column {
                    Text("Сколько дней держать привычку. После этого срока она перестанет появляться.", style = MaterialTheme.typography.bodySmall)
                    listOf(null, 7, 21, 30, 66, 100, 365).forEach { d ->
                        Row(Modifier.fillMaxWidth().clickable { h = h.copy(goalDays = d); pick = null }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(h.goalDays == d, null); Text("  " + (d?.let { "$it дней" } ?: "Вечно")) }
                    }
                }
            })
        "start" -> {
            val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(h.startDay ?: LocalDate.now().toEpochDay()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { pick = null }, confirmButton = {
                TextButton({ dp.selectedDateMillis?.let { h = h.copy(startDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }; pick = null }) { Text("Ок") }
            }) { DatePicker(dp) }
        }
        "remind" -> {
            val tp = rememberTimePickerState(7, 0, true)
            AlertDialog(onDismissRequest = { pick = null }, title = { Text("Время напоминания") }, text = { TimePicker(tp) },
                confirmButton = { TextButton({ val m = tp.hour * 60 + tp.minute; if (m !in reminders) reminders.add(m); pick = null }) { Text("Добавить") } },
                dismissButton = { TextButton({ pick = null }) { Text("Отмена") } })
        }
        "icon" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Значок и цвет") }, confirmButton = { TextButton({ pick = null }) { Text("Готово") } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (PRESETS.map { it.icon } + listOf("🎸", "🎨", "💻", "🇬🇧", "🧹", "🌱", "🐶", "💰", "🧴", "🫁", "🍎", "🚰")).distinct().forEach { e ->
                            Box(Modifier.size(40.dp).clip(CircleShape).background(if (h.icon == e) BLUE.copy(alpha = 0.15f) else Color.Transparent).clickable { h = h.copy(icon = e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CIRCLE.forEachIndexed { i, c -> Box(Modifier.size(if (h.tint % CIRCLE.size == i) 34.dp else 28.dp).clip(CircleShape).background(c).clickable { h = h.copy(tint = i) }) }
                    }
                }
            })
        "delete" -> AlertDialog(onDismissRequest = { pick = null }, title = { Text("Удалить привычку «${h0.title}»?") }, text = { Text("Можно не удалять, а скрыть — история сохранится.") },
            confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteHabit(h0); HabitReminders.schedule(ctx, h0.copy(archived = true)) }; pick = null; onDone() }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().upsertHabit(h0.copy(archived = true)); HabitReminders.schedule(ctx, h0.copy(archived = true)) }; pick = null; onDone() }) { Text("Скрыть") } })
    }
    newSection?.let { s ->
        var name by remember { mutableStateOf(s) }
        AlertDialog(onDismissRequest = { newSection = null }, title = { Text("Свой раздел") }, text = { AppTextField(name, { name = it }, "Например: Учёба") },
            confirmButton = { TextButton({ if (name.isNotBlank()) { sections.add(name.trim()); h = h.copy(section = name.trim()) }; newSection = null }) { Text("Добавить") } })
    }
}

@Composable
private fun WhiteCard(padding: androidx.compose.ui.unit.Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(padding), content = content)
}

@Composable
private fun ValueRow(title: String, value: String, info: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp, 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (info) Icon(Icons.Rounded.Info, null, Modifier.padding(start = 8.dp).size(20.dp), tint = Color(0xFFB8BEC8))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFA0A6B0))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFB8BEC8))
    }
}

@Composable
private fun Stepper(title: String, v: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton({ if (v > range.first) onChange(v - 1) }) { Icon(Icons.Rounded.Remove, "Меньше", tint = BLUE) }
        Text("$v", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        IconButton({ if (v < range.last) onChange(v + 1) }) { Icon(Icons.Rounded.Add, "Больше", tint = BLUE) }
    }
}

// ================= Подробности и журнал =================

private object HabitJournal {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("habit_journal", android.content.Context.MODE_PRIVATE)
    fun get(ctx: android.content.Context, h: Long, day: Long) = p(ctx).getString("$h:$day", "") ?: ""
    fun set(ctx: android.content.Context, h: Long, day: Long, text: String) = p(ctx).edit().putString("$h:$day", text).apply()
    fun all(ctx: android.content.Context, h: Long) = p(ctx).all.filterKeys { it.startsWith("$h:") }.mapNotNull { (k, v) -> k.substringAfter(':').toLongOrNull()?.let { it to v.toString() } }.sortedByDescending { it.first }
}

@Composable
private fun HabitJournalDialog(h: Habit, day: LocalDate, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(HabitJournal.get(ctx, h.id, day.toEpochDay())) }
    AlertDialog(onDismissRequest = onDone, title = { Text("${h.icon} ${h.title}".trim()) },
        text = { Column { Text("Как прошло? Пара слов для журнала.", style = MaterialTheme.typography.bodySmall); AppTextField(text, { text = it }, "Запись", singleLine = false) } },
        confirmButton = { TextButton({ if (text.isNotBlank()) HabitJournal.set(ctx, h.id, day.toEpochDay(), text.trim()); onDone() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onDone) { Text("Пропустить") } })
}

@Composable
private fun HabitDetail(h: Habit, logs: List<HabitLog>, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.now()) }
    val mine = logs.filter { it.habitId == h.id && it.count >= h.perDay }.map { it.epochDay }.toSet()
    val journal = remember { HabitJournal.all(ctx, h.id) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(HBG).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                Text("${h.icon} ${h.title}".trim(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onEdit) { Icon(Icons.Rounded.Edit, "Изменить") }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Серия" to "${Todo.streak(h, logs, today)} дн.", "Всего" to "${mine.size} дн.", "Месяц" to "${mine.count { LocalDate.ofEpochDay(it).let { d -> YearMonth.from(d) == YearMonth.now() } }} дн.").forEach { (t, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) { Text(t, style = MaterialTheme.typography.bodySmall); Text(v, style = MaterialTheme.typography.titleLarge, color = BLUE) }
                    }
                }
                WhiteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Rounded.ChevronLeft, "Раньше") }
                        IconButton({ month = month.plusMonths(1) }) { Icon(Icons.Rounded.ChevronRight, "Позже") }
                    }
                    val lead = month.atDay(1).dayOfWeek.value - 1
                    for (w in 0 until (lead + month.lengthOfMonth() + 6) / 7) Row {
                        for (d in 0 until 7) {
                            val n = w * 7 + d - lead + 1
                            Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                if (n in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(n); val ok = date.toEpochDay() in mine
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (ok) BLUE else Color.Transparent)
                                        .clickable(enabled = !date.isAfter(today)) { AppScope.io.launch { Todo.tickHabit(ctx, h, date) } }, contentAlignment = Alignment.Center) {
                                        Text("$n", color = if (ok) Color.White else if (Todo.isDue(h, date, logs)) Pal.Ink else Pal.Muted, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    Text("Нажми на день, чтобы отметить задним числом.", style = MaterialTheme.typography.bodySmall)
                }
                if (journal.isNotEmpty()) WhiteCard {
                    Text("Журнал", style = MaterialTheme.typography.titleMedium)
                    journal.take(30).forEach { (d, t) -> Text("${LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern("d MMM", RU))} — $t", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}
