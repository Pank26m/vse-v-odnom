package com.pank.vsevodnom.ui.home

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.data.schedule.Agenda
import com.pank.vsevodnom.data.schedule.CalendarRepo
import com.pank.vsevodnom.ui.alerts.AlertCache
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import com.pank.vsevodnom.ui.weather.WEATHER_TTL_MS
import com.pank.vsevodnom.ui.weather.WeatherGlyph
import com.pank.vsevodnom.ui.weather.WeatherRepo
import com.pank.vsevodnom.ui.weather.fmtT
import com.pank.vsevodnom.ui.weight.fmtKg
import com.pank.vsevodnom.widget.row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class Tile(val title: String, val icon: ImageVector, val tint: Tint, val route: String?)

val TILES = listOf(
    Tile("ToDo", Icons.Rounded.Checklist, Tints.Lilac, "todo"),
    Tile("Финансы", Icons.Rounded.AccountBalanceWallet, Tints.Mint, "money"),
    Tile("Календарь", Icons.Rounded.CalendarMonth, Tints.Lilac, "calendar"),
    Tile("Погода", Icons.Rounded.Cloud, Tints.Sky, "weather"),
    Tile("Карта", Icons.Rounded.Map, Tints.Sky, "map"),
    Tile("Свет", Icons.Rounded.Bolt, Tints.Butter, "power"),
    Tile("Тревоги", Icons.Rounded.Campaign, Tints.Rose, "alerts"),
    Tile("Часы", Icons.Rounded.Schedule, Tints.Lilac, "clock/0"),
    Tile("Здоровье", Icons.Rounded.Favorite, Tints.Rose, "health"),
    Tile("Пароли", Icons.Rounded.Lock, Tints.Lilac, "passwords"),
    Tile("Телефон", Icons.Rounded.Phone, Tints.Sky, "phone?tab=0"),
    Tile("Калькулятор", Icons.Rounded.Calculate, Tints.Butter, "calc"),
    Tile("Копилки", Icons.Rounded.Savings, Tints.Mint, "piggy"),
    Tile("Курсы", Icons.AutoMirrored.Rounded.TrendingUp, Tints.Mint, "markets"),
    Tile("Игры", Icons.Rounded.SportsEsports, Tints.Peach, "games"),
    Tile("Диктофон", Icons.Rounded.Mic, Tints.Rose, "recorder"),
    Tile("Загрузки", Icons.Rounded.Download, Tints.Peach, "downloads"),
    Tile("Утилиты", Icons.Rounded.Build, Tints.Butter, "utils"),
    Tile("Команды", Icons.Rounded.Bolt, Tints.Lilac, "commands"),
    Tile("Музыка", Icons.Rounded.MusicNote, Tints.Rose, "music"),
    Tile("Коды", Icons.Rounded.QrCode2, Tints.Sky, "codes"),
    Tile("PDF", Icons.Rounded.PictureAsPdf, Tints.Rose, "pdf"),
    Tile("Карточки", Icons.Rounded.Style, Tints.Mint, "cards"),
    Tile("Английский", Icons.Rounded.Translate, Tints.Sky, "english"),
)

/** Какие плитки показывать на главной — выбирает пользователь. */
object HomeTiles {
    private val DEFAULT = listOf("todo", "money", "calendar", "map", "power", "passwords")
    private fun p(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    fun get(ctx: Context): List<String> = (p(ctx).getString("tiles", null)?.split('|')?.filter { it.isNotBlank() } ?: DEFAULT)
        .map { if (it == "sms" || it == "contacts") "phone?tab=0" else it }.distinct()
    fun set(ctx: Context, l: List<String>) = p(ctx).edit().putString("tiles", l.joinToString("|")).apply()
}

private val RU = Locale("ru")
private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val HeaderBg = Color(0xFFCFE2F6)
private val HeaderInk = Color(0xFF1F3D63)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val cities by db.cities().weather().collectAsState(initial = emptyList())
    val alarms by db.alarms().all().collectAsState(initial = emptyList())
    val allTasks by db.todo().tasks().collectAsState(initial = emptyList())
    val birthdays by db.todo().birthdays().collectAsState(initial = emptyList())
    var today by remember { mutableStateOf(LocalDate.now()) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); hour = LocalTime.now().hour; delay(60_000) } }
    var userName by remember { mutableStateOf(UserPrefs.name(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var tilesEdit by remember { mutableStateOf(false) }
    var tiles by remember { mutableStateOf(HomeTiles.get(ctx)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var lifeOpen by remember { mutableStateOf(false) }
    var taskList by remember { mutableStateOf<String?>(null) }
    val head = rememberHeaderData(refreshTick)

    val city = cities.firstOrNull()
    val weather = remember(city?.cachedJson) { city?.let { WeatherRepo.cached(it) } }
    LaunchedEffect(city?.id) {
        WeatherRepo.ensureDefault(ctx)
        val c = city ?: return@LaunchedEffect
        if (System.currentTimeMillis() - c.cachedAt > WEATHER_TTL_MS) runCatching { WeatherRepo.refresh(ctx, c) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { AlertsApi.fetch(ctx) }.onSuccess { AlertCache.state = it }
            runCatching { Power.refresh(ctx) }
            delay(60_000)
        }
    }
    /** Свайп вниз — обновить всё: погоду, тревоги, свет, сводку, здоровье. */
    fun refreshAll() = scope.launch {
        refreshing = true
        withContext(Dispatchers.IO) {
            city?.let { runCatching { WeatherRepo.refresh(ctx, it) } }
            runCatching { AlertsApi.fetch(ctx) }.onSuccess { AlertCache.state = it }
            runCatching { Power.refresh(ctx) }
        }
        refreshTick++
        delay(400); refreshing = false
    }
    // заметки дня под приветствием
    val notes = remember(alarms, allTasks, weather, birthdays, today, hour, AlertCache.state) { dayNotes(alarms, allTasks, weather, birthdays, today, kyivAlertLevel(AlertCache.state)) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(refreshing, { refreshAll() }, Modifier.fillMaxSize().background(Pal.Bg)) {
    LazyColumn(Modifier.fillMaxSize().background(Pal.Bg)) {
        item {
            Column(Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(16.dp, 8.dp, 16.dp, 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeatherPill(weather) { open("weather") }
                        LifeBadge(com.pank.vsevodnom.ui.health.LifeScore.total(head.parts), { lifeOpen = true })
                        PowerBadge(Power.state, { open("power") })
                        AlertBadge(kyivAlertLevel(AlertCache.state), { open("alerts") })
                    }
                    IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Настройки", tint = HeaderInk) }
                }
                Spacer(Modifier.height(18.dp))
                Text("${today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }}, ${today.dayOfMonth} ${MONTHS[today.monthValue - 1]}",
                    style = MaterialTheme.typography.bodySmall, color = HeaderInk.copy(alpha = 0.7f))
                Text(when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" } + ", $userName",
                    style = MaterialTheme.typography.headlineLarge, color = HeaderInk)
                // на что обратить внимание сегодня: будильник, неотложные дела, погода, дни рождения, тревога
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        notes.forEach { (ic, t, route) ->
                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.6f)).clickable(enabled = route != null) { route?.let { r -> if (r.startsWith("tasks:")) taskList = r.removePrefix("tasks:") else open(r) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(ic, null, tint = HeaderInk, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                Text(t, style = MaterialTheme.typography.labelLarge, color = HeaderInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(if (notes.isEmpty()) 68.dp else 36.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tiles.mapNotNull { r -> TILES.firstOrNull { it.route == r } }) { t -> BigTile(t) { open(t.route!!) } }
                    item {
                        Column(Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.5f)).clickable { tilesEdit = true }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(Icons.Rounded.Tune, null, tint = HeaderInk)
                            Text("Настроить", style = MaterialTheme.typography.labelLarge, color = HeaderInk)
                        }
                    }
                }
            }
        }
        item { Summary(open, refreshTick, head.hs?.steps, weather) }
    }
    }

    if (settings) AppSettingsScreen(onDismiss = { settings = false }, onName = { userName = it })
    taskList?.let { kind ->
        val todayE = today.toEpochDay()
        val list = allTasks.filter { !it.done }.filter { t -> when (kind) { "urgent" -> t.dueDay == todayE && t.priority >= 3; "overdue" -> t.dueDay != null && t.dueDay < todayE; else -> t.dueDay == todayE } }
            .sortedWith(compareBy({ it.dueDay }, { -it.priority }))
        AlertDialog(onDismissRequest = { taskList = null }, confirmButton = { TextButton({ taskList = null }) { Text("Закрыть") } },
            title = { Text(when (kind) { "urgent" -> "Неотложное сегодня"; "overdue" -> "Просроченные задачи"; else -> "Дела на сегодня" }) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    if (list.isEmpty()) item { Text("Уже ничего нет 👏", color = Pal.Muted) }
                    items(list, key = { it.id }) { t -> com.pank.vsevodnom.ui.todo.TaskRow(t) { taskList = null; com.pank.vsevodnom.ui.todo.TodoNav.target = "task:${t.id}"; open("todo") } }
                }
            })
    }
    if (lifeOpen) LifeDialog(head.parts, head.hs, onOpenHealth = { lifeOpen = false; open("health") }) { lifeOpen = false }
    if (tilesEdit) {
        val sel = remember { mutableStateListOf(*tiles.toTypedArray()) }
        AlertDialog(onDismissRequest = { tilesEdit = false }, title = { Text("Плитки на главной") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item { Text("Стрелками меняй порядок. Галочка — показывать.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    // сначала выбранные — в их порядке, потом остальные
                    val ordered = sel.mapNotNull { r -> TILES.firstOrNull { it.route == r } } + TILES.filter { it.route !in sel }
                    items(ordered, key = { it.route!! }) { t ->
                        val i = sel.indexOf(t.route)
                        Row(Modifier.fillMaxWidth().animateItem().clickable { if (i >= 0) sel.remove(t.route) else sel.add(t.route!!) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i >= 0, { if (it) sel.add(t.route!!) else sel.remove(t.route) })
                            Icon(t.icon, null, tint = t.tint.ink); Spacer(Modifier.width(8.dp)); Text(t.title, modifier = Modifier.weight(1f))
                            if (i >= 0) {
                                IconButton({ if (i > 0) { sel.removeAt(i); sel.add(i - 1, t.route!!) } }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Выше") }
                                IconButton({ if (i < sel.lastIndex) { sel.removeAt(i); sel.add(i + 1, t.route!!) } }, enabled = i < sel.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Ниже") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ tiles = sel.toList(); HomeTiles.set(ctx, tiles); tilesEdit = false }) { Text("Готово") } },
            dismissButton = { TextButton({ tilesEdit = false }) { Text("Отмена") } })
    }
}

/** Заметки дня: будильник, неотложные дела, погода, дни рождения, тревога. (значок, текст, куда открыть) */
private fun dayNotes(alarms: List<com.pank.vsevodnom.data.AlarmEntity>, tasks: List<TaskItem>, w: WeatherData?, bdays: List<Birthday>, today: LocalDate, alert: Int?): List<Triple<ImageVector, String, String?>> {
    val out = ArrayList<Triple<ImageVector, String, String?>>()
    val now = java.time.LocalDateTime.now()
    alarms.filter { it.enabled }.map { com.pank.vsevodnom.alarm.nextTrigger(it, now) }.minOrNull()?.takeIf { java.time.Duration.between(now, it).toHours() < 24 }?.let { t ->
        out += Triple(Icons.Rounded.Alarm, "Будильник прозвонит " + (if (t.toLocalDate() != today) "завтра " else "") + "в %02d:%02d".format(t.hour, t.minute), "clock/0")
    }
    // неотложные — высокий приоритет на сегодня; просроченные — отдельно; нажатие показывает сами задачи
    val urgent = tasks.count { !it.done && it.dueDay == today.toEpochDay() && it.priority >= 3 }
    val overdue = tasks.count { !it.done && it.dueDay != null && it.dueDay < today.toEpochDay() }
    val todayN = tasks.count { !it.done && it.dueDay == today.toEpochDay() }
    if (urgent > 0) out += Triple(Icons.Rounded.PriorityHigh, "$urgent " + plural(urgent, "неотложное дело", "неотложных дела", "неотложных дел"), "tasks:urgent")
    if (overdue > 0) out += Triple(Icons.Rounded.EventBusy, "У вас $overdue " + plural(overdue, "просроченная задача", "просроченные задачи", "просроченных задач"), "tasks:overdue")
    if (urgent == 0 && todayN > 0) out += Triple(Icons.Rounded.Checklist, "$todayN " + plural(todayN, "дело", "дела", "дел") + " на сегодня", "tasks:today")
    w?.days?.firstOrNull { it.date == today }?.let { d ->
        val txt = when {
            d.code in 95..99 -> "Сегодня гроза"
            d.code in listOf(65, 81, 82) || (d.precipSum ?: 0.0) >= 15 -> "Сегодня сильный ливень"
            d.code in 71..77 || d.code in 85..86 -> "Сегодня снег"
            d.code in listOf(56, 57, 66, 67) -> "Гололёд: ледяной дождь"
            d.rainProb >= 60 -> "Возьми зонт — дождь ${d.rainProb}%"
            d.tMax >= 30 -> "Жара до +${d.tMax.toInt()}°"
            d.tMin <= -10 -> "Мороз до ${d.tMin.toInt()}°"
            (d.windMax ?: 0.0) >= 14 -> "Сильный ветер до ${(d.windMax ?: 0.0).toInt()} м/с"
            d.code in listOf(45, 48) -> "Туман"
            else -> null
        }
        txt?.let { out += Triple(Icons.Rounded.Umbrella, it, "weather") }
    }
    bdays.filter { it.day == today.dayOfMonth && it.month == today.monthValue }.take(2).forEach { out += Triple(Icons.Rounded.Cake, "День рождения: ${it.name}", "todo") }
    return out
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return if (m10 == 1 && m100 != 11) one else if (m10 in 2..4 && m100 !in 12..14) few else many
}

@Composable
private fun StatusPill(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.65f)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun BigTile(t: Tile, onClick: () -> Unit) {
    Column(Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(t.tint.bg), contentAlignment = Alignment.Center) { Icon(t.icon, null, tint = t.tint.ink, modifier = Modifier.size(22.dp)) }
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
    }
}

// ================= Сводка дня =================

@Composable
private fun Summary(open: (String) -> Unit, tick: Int, steps: Long?, weather: WeatherData?) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val today = LocalDate.now()
    val tasks by db.todo().tasks().collectAsState(initial = emptyList())
    val habits by db.todo().habits().collectAsState(initial = emptyList())
    val logs by remember { db.todo().habitLogs(today.toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    var agenda by remember { mutableStateOf<List<Agenda>>(emptyList()) }
    val birthdays by db.todo().birthdays().collectAsState(initial = emptyList())
    LaunchedEffect(tick) { while (true) { agenda = withContext(Dispatchers.IO) { CalendarRepo.agendaSync(ctx, today, today) }; delay(120_000) } }
    val weights by db.weight().all().collectAsState(initial = emptyList())
    var weightAsk by remember { mutableStateOf(false) }
    var eventOpen by remember { mutableStateOf<Agenda?>(null) }
    var review by remember { mutableStateOf(false) }
    var tomorrow by remember { mutableStateOf(false) }
    var agTick by remember { mutableIntStateOf(0) }
    var holTick by remember { mutableIntStateOf(0) }
    val holTickOuter = holTick

    val dow = today.dayOfWeek.value - 1
    val todayTasks = tasks.filter { !it.done && it.dueDay != null && it.dueDay <= today.toEpochDay() }
    val todayHabits = habits.filter { com.pank.vsevodnom.data.Todo.isDue(it, LocalDate.now(), logs) }
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val agendaLeft = agenda.filter { a -> if (a is Agenda.Pair) LocalTime.parse(a.l.end).let { it.hour * 60 + it.minute } > nowMin else true }
    val bdays = birthdays.filter { it.day == today.dayOfMonth && it.month == today.monthValue }
    val hol = remember(today, holTickOuter) { Holidays.on(today, ctx) }

    Column(Modifier.fillMaxWidth().offset(y = (-16).dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Pal.Bg).padding(16.dp, 18.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сводка", style = MaterialTheme.typography.titleLarge)
        MoodCheckIn()
        // вес — закреп на уровне настроения: по понедельникам, пока не взвесился; стрелка — сразу окно ввода
        if (today.dayOfWeek == DayOfWeek.MONDAY && weights.none { it.epochDay == today.toEpochDay() })
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Sky).clickable { weightAsk = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MonitorWeight, null, tint = Pal.SkyInk)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Понедельник — время взвеситься", style = MaterialTheme.typography.titleMedium, color = Pal.SkyInk)
                    weights.lastOrNull()?.let { Text("прошлый раз ${fmtKg(it.kg)} кг", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                }
                Icon(Icons.Rounded.ChevronRight, "Записать вес", tint = Pal.SkyInk)
            }
        // анализ состава тела — раз в 3 месяца: когда пора, напоминание в сводке
        val bodyLeft = remember(today) { com.pank.vsevodnom.data.BodyCheck.daysLeft(ctx) }
        if (bodyLeft != null && bodyLeft <= 0)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Rose).clickable { open("health") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Biotech, null, tint = Pal.RoseInk)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Обновите данные состава тела", style = MaterialTheme.typography.titleSmall, color = Pal.RoseInk)
                    Text("биоимпедансный анализ — раз в 3 месяца", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Pal.RoseInk)
            }
        @Suppress("UNUSED_EXPRESSION") holTick
        hol.filter { !HolidayDone.isDone(ctx, today, it.name) }.forEach { hd ->
            val h = hd.name
            key("hol$h") {
                com.pank.vsevodnom.ui.todo.Leaving(h, onLeft = { HolidayDone.mark(ctx, today, h); holTick++ }) { done, leave ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { com.pank.vsevodnom.ui.todo.TodoNav.target = "page:dates"; open("todo") }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Peach), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Celebration, null, tint = Pal.PeachInk) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(h, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(hd.kind, style = MaterialTheme.typography.bodySmall)
                        }
                        com.pank.vsevodnom.ui.todo.CheckDivider()
                        com.pank.vsevodnom.ui.todo.CheckCircle(done, com.pank.vsevodnom.ui.todo.CHECK_GREY, com.pank.vsevodnom.ui.todo.CHECK_SIZE) { leave() }
                    }
                }
            }
        }
        var bdTick by remember { mutableIntStateOf(0) }
        bdays.filter { bdTick >= 0 && !com.pank.vsevodnom.ui.todo.BdayDone.isDone(ctx, it.id) }.forEach { b ->
            key("b${b.id}") { com.pank.vsevodnom.ui.todo.BirthdayRow(b, today, onOpen = { com.pank.vsevodnom.ui.todo.TodoNav.target = "bday:${b.id}"; open("todo") }) { bdTick++ } }
        }
        // пары и события календаря — с кружком «сделано»: отмеченное прячется (пары — общая отметка с «Задачами»)
        @Suppress("UNUSED_EXPRESSION") agTick
        agendaLeft.filter { !AgendaDone.isDone(ctx, it) }.forEach { a ->
            val r = a.row()
            val work = a is Agenda.Sys && a.isWork
            key("ag" + AgendaDone.key(a)) {
                com.pank.vsevodnom.ui.todo.Leaving(AgendaDone.key(a), onLeft = { AgendaDone.mark(ctx, a); agTick++ }) { done, leave ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { eventOpen = a }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (work) Pal.Peach else Pal.Lilac), contentAlignment = Alignment.Center) {
                            Icon(when { a is Agenda.Pair -> Icons.Rounded.School; work -> Icons.Rounded.Work; else -> Icons.Rounded.Event }, null, tint = if (work) Pal.PeachInk else Color(r.color), modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(listOf(r.time.replace('\n', '–'), if (work) "рабочее задание" else "", r.sub).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        com.pank.vsevodnom.ui.todo.CheckDivider()
                        com.pank.vsevodnom.ui.todo.CheckCircle(done, com.pank.vsevodnom.ui.todo.CHECK_GREY, com.pank.vsevodnom.ui.todo.CHECK_SIZE) { leave() }
                    }
                }
            }
        }
        todayTasks.forEach { t ->
            key("t${t.id}") { com.pank.vsevodnom.ui.todo.TaskRow(t) { com.pank.vsevodnom.ui.todo.TodoNav.target = "task:${t.id}"; open("todo") } }
        }
        // выполненные привычки не показываем — сводка только про то, что ещё нужно сделать
        val leftHabits = todayHabits.filter { h -> (logs.firstOrNull { it.habitId == h.id }?.count ?: 0) < h.perDay }
        leftHabits.forEach { h ->
            key("h${h.id}") {
                com.pank.vsevodnom.ui.todo.Leaving(h.id, onLeft = { AppScope.io.launch { Todo.tickHabit(ctx, h, today) } }) { done, leave ->
                    val t = Tints.all[h.tint % 6]
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (done) t.bg else Pal.Card).clickable { com.pank.vsevodnom.ui.todo.TodoNav.target = "habit:${h.id}"; open("todo") }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.bg), contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(h.title, style = MaterialTheme.typography.titleSmall)
                            Text("привычка" + if (h.perDay > 1) " · ${logs.firstOrNull { it.habitId == h.id }?.count ?: 0}/${h.perDay}" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        com.pank.vsevodnom.ui.todo.CheckDivider()
                        com.pank.vsevodnom.ui.todo.CheckCircle(done, com.pank.vsevodnom.ui.todo.CHECK_GREY, com.pank.vsevodnom.ui.todo.CHECK_SIZE) {
                            val c = logs.firstOrNull { it.habitId == h.id }?.count ?: 0
                            if (c + 1 >= h.perDay) leave() else AppScope.io.launch { Todo.tickHabit(ctx, h, today) }
                        }
                    }
                }
            }
        }
        if (hol.isEmpty() && bdays.isEmpty() && agendaLeft.isEmpty() && todayTasks.isEmpty() && leftHabits.isEmpty())
            Text("На сегодня всё чисто. Добавь задачи в разделе ToDo.", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
        // в конце дня — «Мои успехи»
        Spacer(Modifier.height(4.dp))
        StepsBar(steps) { open("health") }
        SuccessTile(tasks, logs, todayHabits.size) { review = true }
        TomorrowButton { tomorrow = true }
    }
    if (review) DayReviewScreen { review = false }
    if (tomorrow) TomorrowScreen(weather) { tomorrow = false }
    if (weightAsk) WeightDialog { weightAsk = false }
    eventOpen?.let { a ->
        when (a) {
            is Agenda.Own -> com.pank.vsevodnom.ui.calendar.EventEditor(a.e, onDismiss = { eventOpen = null })
            is Agenda.Pair -> com.pank.vsevodnom.ui.calendar.LessonDialog(a) { eventOpen = null }
            is Agenda.Sys -> { LaunchedEffect(a) { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, a.id))) }; eventOpen = null } }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, title: String, sub: String, bg: Color, ink: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
    }
}

/**
 * «Как ваш день?» — три раза в день: с 7:00, с 15:00 и с 22:00 (до 4 утра). Висит первой в сводке,
 * пока не выберешь смайлик, потом плавно уезжает.
 */
private fun moodSlot(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Pair<Long, Int>? {
    val h = now.hour
    return when {
        h < 4 -> now.toLocalDate().minusDays(1).toEpochDay() to 2
        h < 7 -> null
        h < 15 -> now.toLocalDate().toEpochDay() to 0
        h < 22 -> now.toLocalDate().toEpochDay() to 1
        else -> now.toLocalDate().toEpochDay() to 2
    }
}

@Composable
private fun MoodCheckIn() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("home", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val slot = remember(tick) { moodSlot() } ?: return
    val key = "${slot.first}:${slot.second}"
    var answered by remember(key) { mutableStateOf(p.getString("mood_slot", "") == key) }
    if (answered) return
    var level by remember(key) { mutableStateOf<Int?>(null) }
    com.pank.vsevodnom.ui.todo.Leaving(key, onLeft = {
        p.edit().putString("mood_slot", key).apply(); answered = true
        level?.let { lvl -> AppScope.io.launch { AppDb.get(ctx).life().addMood(MoodEntry(level = lvl)) } }
    }) { _, leave ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Butter).padding(14.dp)) {
            Text(when (slot.second) { 0 -> "Как начинается день?"; 1 -> "Как проходит день?"; else -> "Как прошёл день?" }, style = MaterialTheme.typography.titleMedium, color = Pal.ButterInk)
            Spacer(Modifier.height(8.dp))
            com.pank.vsevodnom.ui.life.MoodPicker({ lvl -> if (level == null) { level = lvl; leave() } }, selected = level)
        }
    }
}

/**
 * «Сделано» у пар и событий календаря в сводке: пара — в том же наборе, что и в «Задачах» (prefs tasks/lesson_done),
 * событие (своё или из Google Календаря) — по id и дню (prefs home/agenda_done, хранится 2 недели).
 */
object AgendaDone {
    fun key(a: Agenda): String = when (a) {
        is Agenda.Pair -> "p:${a.l.id}"
        is Agenda.Own -> "o:${a.e.id}:${a.date.toEpochDay()}"
        is Agenda.Sys -> "s:${a.id}:${a.date.toEpochDay()}"
    }
    private fun tp(ctx: Context) = ctx.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    private fun hp(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    fun isDone(ctx: Context, a: Agenda): Boolean =
        if (a is Agenda.Pair) "${a.l.id}" in tp(ctx).getStringSet("lesson_done", emptySet())!! else key(a) in hp(ctx).getStringSet("agenda_done", emptySet())!!
    fun mark(ctx: Context, a: Agenda) {
        if (a is Agenda.Pair) { tp(ctx).edit().putStringSet("lesson_done", tp(ctx).getStringSet("lesson_done", emptySet())!! + "${a.l.id}").apply(); return }
        val today = LocalDate.now().toEpochDay()
        val keep = hp(ctx).getStringSet("agenda_done", emptySet())!!.filter { (it.substringAfterLast(':').toLongOrNull() ?: 0) > today - 14 }.toSet()
        hp(ctx).edit().putStringSet("agenda_done", keep + key(a)).apply()
    }
}

/** Государственный праздник отмечен «сделано» (галочка в сводке). */
object HolidayDone {
    private fun p(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private fun set(ctx: Context) = p(ctx).getStringSet("hol_done_set", emptySet())!!
    fun isDone(ctx: Context, d: LocalDate, name: String) = "${d.toEpochDay()}|$name" in set(ctx)
    fun mark(ctx: Context, d: LocalDate, name: String) {
        // храним только последние две недели
        val keep = set(ctx).filter { (it.substringBefore('|').toLongOrNull() ?: 0) > d.toEpochDay() - 14 }.toMutableSet()
        keep += "${d.toEpochDay()}|$name"; p(ctx).edit().putStringSet("hol_done_set", keep).apply()
    }
}

/** Ввод веса прямо со сводки. */
@Composable
private fun WeightDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Вес сегодня") },
        text = {
            OutlinedTextField(v, { v = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, suffix = { Text("кг") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.focusRequester(focus))
        },
        confirmButton = { TextButton({
            v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }?.let { kg ->
                val day = LocalDate.now().toEpochDay()
                AppScope.io.launch { val dao = AppDb.get(ctx).weight(); dao.upsert((dao.byDay(day) ?: WeightEntry(epochDay = day, kg = kg)).copy(kg = kg)) }
                onDismiss()
            }
        }) { Text("Записать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

/** Разделы, которые уже есть в нижнем баре или внутри ToDo, — в «Всех» не дублируем. */
/** Что не показывать во «Все»: есть в нижнем баре, в ToDo или в «Утилитах». */
private val IN_BAR = setOf("todo", "money", "calendar", "weather", "map", "power", "alerts", "cart", "recorder", "passwords", "piggy", "cards", "pdf", "codes", "downloads", "calc", "markets")

/** Все разделы плитками. */
@Composable
fun AppsScreen(open: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(4) }) { Text("Все приложения", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(4.dp, 8.dp)) }
        // в «Всех» — только то, чего нет в нижнем баре и в ToDo
        val apps = TILES.filter { it.route !in IN_BAR }
        items(apps.size) { i ->
            val t = apps[i]
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { t.route?.let(open) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.tint.bg), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = t.tint.ink, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Резервная копия всего (кроме паролей — у них свой зашифрованный бэкап). */
@Composable
internal fun BackupCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<android.net.Uri?>(null) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) scope.launch { msg = runCatching { com.pank.vsevodnom.data.Backup.export(ctx, u) }.fold({ "$it. Храни файл в Google Диске или Telegram «Избранное»." }, { "Не получилось: ${it.message}" }) }
    }
    val opener = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u -> confirm = u }
    AppCard(Modifier.fillMaxWidth().padding(top = 8.dp), color = Pal.Lilac) {
        Text("Резервная копия", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
        Text("Один файл: вес, дела, деньги, календарь и пары, места и треки, настроение, корзина, будильники, настройки. Пароли — отдельно: «Пароли» → ⋮ → «Сохранить бэкап».", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Сохранить", { saver.launch("vse-v-odnom-" + java.time.LocalDate.now() + ".zip") }, Modifier.weight(1f))
            PillButton("Восстановить", { opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        }
        msg?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    confirm?.let { u ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Восстановить из копии?") },
            text = { Text("Текущие данные (кроме паролей) заменятся данными из файла, приложение перезапустится.") },
            confirmButton = { TextButton({ confirm = null; scope.launch { runCatching { com.pank.vsevodnom.data.Backup.restore(ctx, u) }.onFailure { msg = "Не получилось: ${it.message}" } } }) { Text("Восстановить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Отмена") } })
    }
}
