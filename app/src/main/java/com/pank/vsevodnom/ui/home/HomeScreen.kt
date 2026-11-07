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
    Tile("СМС", Icons.Rounded.Sms, Tints.Sky, "sms"),
    Tile("Контакты", Icons.Rounded.Person, Tints.Sky, "contacts"),
    Tile("Корзина", Icons.Rounded.ShoppingCart, Tints.Peach, "cart"),
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
    fun get(ctx: Context): List<String> = p(ctx).getString("tiles", null)?.split('|')?.filter { it.isNotBlank() } ?: DEFAULT
    fun set(ctx: Context, l: List<String>) = p(ctx).edit().putString("tiles", l.joinToString("|")).apply()
}

private val RU = Locale("ru")
private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val HeaderBg = Color(0xFFCFE2F6)
private val HeaderInk = Color(0xFF1F3D63)

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val cities by db.cities().weather().collectAsState(initial = emptyList())
    val weights by db.weight().all().collectAsState(initial = emptyList())
    var today by remember { mutableStateOf(LocalDate.now()) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); hour = LocalTime.now().hour; delay(60_000) } }
    var userName by remember { mutableStateOf(UserPrefs.name(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var tilesEdit by remember { mutableStateOf(false) }
    var tiles by remember { mutableStateOf(HomeTiles.get(ctx)) }

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

    LazyColumn(Modifier.fillMaxSize().background(Pal.Bg)) {
        item {
            Column(Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(16.dp, 8.dp, 16.dp, 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(onClick = { open("weather") }) {
                        if (weather != null) { WeatherGlyph(wmo(weather.code).second, weather.isDay, Modifier.size(26.dp), animated = true); Spacer(Modifier.width(4.dp)); Text(fmtT(weather.temp), style = MaterialTheme.typography.titleSmall, color = HeaderInk) }
                        else Icon(Icons.Rounded.Cloud, "Погода", tint = HeaderInk, modifier = Modifier.size(20.dp))
                    }
                    PowerBadge(Power.state, { open("power") })
                    AlertBadge(kyivAlertLevel(AlertCache.state), { open("alerts") })
                    Spacer(Modifier.weight(1f))
                    IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Настройки", tint = HeaderInk) }
                }
                Spacer(Modifier.height(18.dp))
                Text("${today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }}, ${today.dayOfMonth} ${MONTHS[today.monthValue - 1]}",
                    style = MaterialTheme.typography.bodySmall, color = HeaderInk.copy(alpha = 0.7f))
                Text(when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" } + ", $userName",
                    style = MaterialTheme.typography.headlineLarge, color = HeaderInk)
                // вес — только утром в понедельник
                if (today.dayOfWeek == DayOfWeek.MONDAY && hour < 13) {
                    val w = weights.lastOrNull()
                    val prev = weights.getOrNull(weights.size - 2)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (w?.epochDay == today.toEpochDay()) "Вес сегодня ${fmtKg(w.kg)} кг" + (prev?.let { val d = w.kg - it.kg; " (${if (d > 0) "+" else if (d < 0) "−" else ""}${fmtKg(kotlin.math.abs(d))})" } ?: "")
                        else "Понедельник — время взвеситься",
                        style = MaterialTheme.typography.titleSmall, color = HeaderInk,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.6f)).clickable { open("health") }.padding(10.dp, 6.dp)
                    )
                }
                // больше воздуха над плитками — примерно половина плитки
                Spacer(Modifier.height(68.dp))
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
        item { Summary(open) }
    }

    if (settings) {
        var n by remember { mutableStateOf(userName) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Настройки") },
            text = { AppTextField(n, { n = it }, "Как к тебе обращаться") },
            confirmButton = { TextButton({ UserPrefs.setName(ctx, n.trim().ifBlank { "Миша" }); userName = n.trim().ifBlank { "Миша" }; settings = false }) { Text("Сохранить") } },
            dismissButton = { TextButton({ settings = false }) { Text("Отмена") } })
    }
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
private fun Summary(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val today = LocalDate.now()
    val tasks by db.todo().tasks().collectAsState(initial = emptyList())
    val habits by db.todo().habits().collectAsState(initial = emptyList())
    val logs by remember { db.todo().habitLogs(today.toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    var agenda by remember { mutableStateOf<List<Agenda>>(emptyList()) }
    val birthdays by db.todo().birthdays().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { while (true) { agenda = withContext(Dispatchers.IO) { CalendarRepo.agendaSync(ctx, today, today) }; delay(120_000) } }

    val dow = today.dayOfWeek.value - 1
    val todayTasks = tasks.filter { !it.done && it.dueDay != null && it.dueDay <= today.toEpochDay() }
    val todayHabits = habits.filter { com.pank.vsevodnom.data.Todo.isDue(it, LocalDate.now(), logs) }
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val agendaLeft = agenda.filter { a -> if (a is Agenda.Pair) LocalTime.parse(a.l.end).let { it.hour * 60 + it.minute } > nowMin else true }
    val bdays = birthdays.filter { it.day == today.dayOfMonth && it.month == today.monthValue }
    val hol = Holidays.on(today)
    val power = Power.state

    Column(Modifier.fillMaxWidth().offset(y = (-16).dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Pal.Bg).padding(16.dp, 18.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сводка", style = MaterialTheme.typography.titleLarge)
        MoodCheckIn()
        com.pank.vsevodnom.ui.health.LifeScoreMini { open("health") }
        hol?.let { SummaryRow(Icons.Rounded.Celebration, it, "праздник", Pal.Peach, Pal.PeachInk) { com.pank.vsevodnom.ui.todo.TodoNav.target = "page:dates"; open("todo") } }
        var bdTick by remember { mutableIntStateOf(0) }
        bdays.filter { bdTick >= 0 && !com.pank.vsevodnom.ui.todo.BdayDone.isDone(ctx, it.id) }.forEach { b ->
            key("b${b.id}") { com.pank.vsevodnom.ui.todo.BirthdayRow(b, today, onOpen = { com.pank.vsevodnom.ui.todo.TodoNav.target = "bday:${b.id}"; open("todo") }) { bdTick++ } }
        }
        power?.let { SummaryRow(Icons.Rounded.Bolt, it.nearestText(), "очередь ${it.group}", Pal.Butter, Pal.ButterInk) { open("power") } }
        agendaLeft.forEach { a ->
            val r = a.row()
            SummaryRow(if (a is Agenda.Pair) Icons.Rounded.School else Icons.Rounded.Event, r.title, listOf(r.time.replace('\n', '–'), r.sub).filter { it.isNotBlank() }.joinToString(" · "), Pal.Lilac, Color(r.color)) {
                com.pank.vsevodnom.ui.calendar.CalendarNav.target = a; open("todo")
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
                        com.pank.vsevodnom.ui.todo.CheckCircle(done, t.ink, 26.dp) {
                            val c = logs.firstOrNull { it.habitId == h.id }?.count ?: 0
                            if (c + 1 >= h.perDay) leave() else AppScope.io.launch { Todo.tickHabit(ctx, h, today) }
                        }
                    }
                }
            }
        }
        if (hol == null && bdays.isEmpty() && agendaLeft.isEmpty() && todayTasks.isEmpty() && leftHabits.isEmpty())
            Text("На сегодня всё чисто. Добавь задачи в разделе ToDo.", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
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
        items(TILES.size) { i ->
            val t = TILES[i]
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { t.route?.let(open) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.tint.bg), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = t.tint.ink, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
        item(span = { GridItemSpan(4) }) { BackupCard() }
    }
}

/** Резервная копия всего (кроме паролей — у них свой зашифрованный бэкап). */
@Composable
private fun BackupCard() {
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
