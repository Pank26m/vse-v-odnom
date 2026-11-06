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
    Tile("Дела", Icons.Rounded.Checklist, Tints.Lilac, "todo"),
    Tile("Деньги", Icons.Rounded.AccountBalanceWallet, Tints.Mint, "money"),
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
    Tile("Настроение", Icons.Rounded.Mood, Tints.Butter, "mood"),
    Tile("Калькулятор", Icons.Rounded.Calculate, Tints.Butter, "calc"),
    Tile("Рандом", Icons.Rounded.Casino, Tints.Peach, "random"),
    Tile("Копилки", Icons.Rounded.Savings, Tints.Mint, "piggy"),
    Tile("Курсы", Icons.AutoMirrored.Rounded.TrendingUp, Tints.Mint, "markets"),
    Tile("Игры", Icons.Rounded.SportsEsports, Tints.Peach, "games"),
    Tile("Файлы", Icons.Rounded.Description, Tints.Butter, "files"),
    Tile("Диктофон", Icons.Rounded.Mic, Tints.Rose, "recorder"),
    Tile("Загрузки", Icons.Rounded.Download, Tints.Peach, "downloads"),
    Tile("Утилиты", Icons.Rounded.Build, Tints.Butter, "utils"),
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
                        if (weather != null) { WeatherGlyph(wmo(weather.code).second, weather.isDay, Modifier.size(22.dp)); Spacer(Modifier.width(4.dp)); Text(fmtT(weather.temp), style = MaterialTheme.typography.titleSmall, color = HeaderInk) }
                        else Icon(Icons.Rounded.Cloud, "Погода", tint = HeaderInk, modifier = Modifier.size(20.dp))
                    }
                    StatusPill(onClick = { open("power") }) { PowerIcon(Power.state) }
                    StatusPill(onClick = { open("alerts") }) { AlertIcon() }
                    Spacer(Modifier.weight(1f))
                    IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Настройки", tint = HeaderInk) }
                }
                Spacer(Modifier.height(18.dp))
                Text("${today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }}, ${today.dayOfMonth} ${MONTHS[today.monthValue - 1]}",
                    style = MaterialTheme.typography.bodySmall, color = HeaderInk.copy(alpha = 0.7f))
                Text(when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" } + ", $userName",
                    style = MaterialTheme.typography.headlineLarge, color = HeaderInk)
                weather?.let { Text("${city?.name ?: ""}: ${wmo(it.code).first.lowercase()}, ${fmtT(it.temp)}", style = MaterialTheme.typography.bodyMedium, color = HeaderInk) }
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
                Spacer(Modifier.height(16.dp))
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
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(TILES) { t ->
                        Row(Modifier.fillMaxWidth().clickable { if (t.route in sel) sel.remove(t.route) else sel.add(t.route!!) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t.route in sel, { if (it) sel.add(t.route!!) else sel.remove(t.route) })
                            Icon(t.icon, null, tint = t.tint.ink); Spacer(Modifier.width(8.dp)); Text(t.title)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ tiles = sel.toList(); HomeTiles.set(ctx, tiles); tilesEdit = false }) { Text("Готово") } },
            dismissButton = { TextButton({ tilesEdit = false }) { Text("Отмена") } })
    }
    MorningMood()
}

@Composable
private fun StatusPill(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.65f)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Тревога: зелёный щит — спокойно, жёлтый дрон — частично, красная ракета — в Киеве. */
@Composable
private fun AlertIcon() {
    val st = AlertCache.state
    val c = st?.of(AlertsApi.KYIV_CITY); val o = st?.of(AlertsApi.KYIV_OBL)
    val worst = listOfNotNull(c, o).maxByOrNull { it.ordinal }
    when {
        st == null -> Icon(Icons.Rounded.Shield, "Тревоги", tint = Pal.Muted, modifier = Modifier.size(22.dp))
        c == AlertLevel.FULL -> { Icon(Icons.Rounded.RocketLaunch, "Тревога в Киеве", tint = Color(0xFFD94A6A), modifier = Modifier.size(22.dp)); Text(" Киев", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD94A6A)) }
        worst != null && worst != AlertLevel.NONE -> { DroneIcon(Color(0xFFD9A321)); Text(" область", style = MaterialTheme.typography.labelLarge, color = Color(0xFF9C7414)) }
        else -> Icon(Icons.Rounded.Shield, "Тревоги нет", tint = Color(0xFF3FA36B), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DroneIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * 0.5f, h * 0.15f); lineTo(w * 0.95f, h * 0.7f); lineTo(w * 0.55f, h * 0.6f); lineTo(w * 0.5f, h * 0.85f); lineTo(w * 0.45f, h * 0.6f); lineTo(w * 0.05f, h * 0.7f); close() }, color)
    }
}

@Composable
private fun PowerIcon(s: PowerState?) {
    val next = s?.nextOffInMin
    val (color, text) = when {
        s == null -> Pal.Muted to ""
        s.offNow -> Color(0xFFD94A6A) to " нет света"
        s.emergency -> Color(0xFFD94A6A) to " экстренные"
        next != null && next <= 120 -> Color(0xFFD9A321) to " через ${if (next >= 60) "${next / 60} ч" else "$next мин"}"
        else -> Color(0xFF3FA36B) to ""
    }
    Icon(Icons.Rounded.Bolt, "Свет", tint = color, modifier = Modifier.size(22.dp))
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelLarge, color = color)
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
    val todayHabits = habits.filter { it.daysMask and (1 shl dow) != 0 }
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val agendaLeft = agenda.filter { a -> if (a is Agenda.Pair) LocalTime.parse(a.l.end).let { it.hour * 60 + it.minute } > nowMin else true }
    val bdays = birthdays.filter { it.day == today.dayOfMonth && it.month == today.monthValue }
    val hol = Holidays.on(today)
    val power = Power.state

    Column(Modifier.fillMaxWidth().offset(y = (-16).dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Pal.Bg).padding(16.dp, 18.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сводка", style = MaterialTheme.typography.titleLarge)
        hol?.let { SummaryRow(Icons.Rounded.Celebration, it, "праздник", Pal.Peach, Pal.PeachInk) { open("todo") } }
        bdays.forEach { b -> SummaryRow(Icons.Rounded.Cake, "День рождения: ${b.name}", b.year?.let { "исполняется ${today.year - it}" } ?: "", Pal.Rose, Pal.RoseInk) { open("todo") } }
        power?.todaySlots?.takeIf { it.isNotEmpty() }?.let { SummaryRow(Icons.Rounded.Bolt, "Отключения света: ${it.joinToString(", ")}", if (power.emergency) "действуют экстренные отключения" else "очередь ${power.group}", Pal.Butter, Pal.ButterInk) { open("power") } }
        agendaLeft.forEach { a ->
            val r = a.row()
            SummaryRow(if (a is Agenda.Pair) Icons.Rounded.School else Icons.Rounded.Event, r.title, listOf(r.time.replace('\n', '–'), r.sub).filter { it.isNotBlank() }.joinToString(" · "), Pal.Lilac, Color(r.color)) { open("calendar") }
        }
        todayTasks.forEach { t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(false, { AppScope.io.launch { Todo.complete(ctx, t) } })
                Column(Modifier.weight(1f).clickable { open("todo") }) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                    val overdue = t.dueDay!! < today.toEpochDay()
                    Text(listOfNotNull(if (overdue) "просрочено" else null, t.dueMinute?.let { "%02d:%02d".format(it / 60, it % 60) }, t.list.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = if (overdue) Pal.RoseInk else Pal.Muted)
                }
                if (t.priority > 0) Icon(Icons.Rounded.PriorityHigh, null, tint = Pal.RoseInk)
            }
        }
        if (todayHabits.isNotEmpty()) {
            Text("Привычки", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                todayHabits.take(4).forEach { h ->
                    val done = logs.firstOrNull { it.habitId == h.id }?.count ?: 0
                    val t = Tints.all[h.tint % 6]
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (done >= h.perDay) t.bg else Pal.Card)
                        .clickable { AppScope.io.launch { Todo.tickHabit(ctx, h, today) } }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(h.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text(if (done >= h.perDay) "✓" else "$done/${h.perDay}", style = MaterialTheme.typography.titleMedium, color = t.ink)
                    }
                }
            }
        }
        if (hol == null && bdays.isEmpty() && agendaLeft.isEmpty() && todayTasks.isEmpty() && todayHabits.isEmpty())
            Text("На сегодня всё чисто. Добавь дела в разделе «Дела».", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
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

/** Утром, при первом открытии за день — спросить самочувствие. Одно нажатие — и окно исчезает. */
@Composable
private fun MorningMood() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("home", Context.MODE_PRIVATE) }
    val today = LocalDate.now().toEpochDay()
    var show by remember { mutableStateOf(LocalTime.now().hour in 5..13 && p.getLong("mood_asked", 0) != today) }
    if (!show) return
    fun done() { p.edit().putLong("mood_asked", today).apply(); show = false }
    AlertDialog(
        onDismissRequest = { done() },
        title = { Text("Как ты сегодня?") },
        text = { com.pank.vsevodnom.ui.life.MoodPicker({ lvl -> AppScope.io.launch { AppDb.get(ctx).life().addMood(MoodEntry(level = lvl)) }; done() }) },
        confirmButton = {},
        dismissButton = { TextButton({ done() }) { Text("Позже") } }
    )
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
    }
}
