package com.pank.vsevodnom.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.AppTextField
import com.pank.vsevodnom.widget.row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.alarm.nextTrigger
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.wmo
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import com.pank.vsevodnom.ui.weather.WEATHER_TTL_MS
import com.pank.vsevodnom.ui.weather.WeatherGlyph
import com.pank.vsevodnom.ui.weather.WeatherRepo
import com.pank.vsevodnom.ui.weather.fmtT
import com.pank.vsevodnom.ui.weight.WeightChart
import com.pank.vsevodnom.ui.weight.daysToMonday
import com.pank.vsevodnom.ui.weight.fmtKg
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class Tile(val title: String, val icon: ImageVector, val tint: Tint, val route: String?)

val TILES = listOf(
    Tile("Часы", Icons.Rounded.Schedule, Tints.Lilac, "clock/0"),
    Tile("Погода", Icons.Rounded.Cloud, Tints.Sky, "weather"),
    Tile("Калькулятор", Icons.Rounded.Calculate, Tints.Butter, "calc"),
    Tile("Рандом", Icons.Rounded.Casino, Tints.Peach, "random"),
    Tile("Копилки", Icons.Rounded.Savings, Tints.Mint, "piggy"),
    Tile("Вес", Icons.Rounded.MonitorWeight, Tints.Mint, "weight"),
    Tile("Календарь", Icons.Rounded.CalendarMonth, Tints.Lilac, "calendar"),
    Tile("Курсы", Icons.AutoMirrored.Rounded.TrendingUp, Tints.Mint, "markets"),
    Tile("Утилиты", Icons.Rounded.Build, Tints.Butter, "utils"),
    Tile("Файлы", Icons.Rounded.Description, Tints.Butter, "files"),
    Tile("Диктофон", Icons.Rounded.Mic, Tints.Rose, "recorder"),
    Tile("Игры", Icons.Rounded.SportsEsports, Tints.Peach, "games"),
    Tile("Пароли", Icons.Rounded.Lock, Tints.Lilac, "passwords"),
    Tile("СМС", Icons.Rounded.Sms, Tints.Sky, "sms"),
    Tile("Контакты", Icons.Rounded.Person, Tints.Sky, "contacts"),
    Tile("Здоровье", Icons.Rounded.Favorite, Tints.Rose, "health"),
    Tile("Загрузки", Icons.Rounded.Download, Tints.Peach, "downloads"),
    Tile("Корзина", Icons.Rounded.ShoppingCart, Tints.Peach, "cart"),
    Tile("Настроение", Icons.Rounded.Mood, Tints.Butter, "mood"),
    Tile("Тревоги", Icons.Rounded.Campaign, Tints.Rose, "alerts"),
    Tile("Капри", Icons.Rounded.Face, Tints.Lilac, "capri"),
)

private val RU = Locale("ru")
private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val cities by db.cities().weather().collectAsState(initial = emptyList())
    val weights by db.weight().all().collectAsState(initial = emptyList())
    val alarms by db.alarms().all().collectAsState(initial = emptyList())
    var today by remember { mutableStateOf(LocalDate.now()) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); hour = LocalTime.now().hour; delay(60_000) } }

    var userName by remember { mutableStateOf(com.pank.vsevodnom.data.UserPrefs.name(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var moodLevel by remember { mutableStateOf<Int?>(null) }
    val city = cities.firstOrNull()
    val weather = remember(city?.cachedJson) { city?.let { WeatherRepo.cached(it) } }
    LaunchedEffect(city?.id) {
        WeatherRepo.ensureDefault(ctx)
        val c = city ?: return@LaunchedEffect
        if (System.currentTimeMillis() - c.cachedAt > WEATHER_TTL_MS) runCatching { WeatherRepo.refresh(ctx, c) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(4) }) {
            Column(Modifier.padding(4.dp, 8.dp, 4.dp, 0.dp)) {
                Text(
                    "${today.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }}, ${today.dayOfMonth} ${MONTHS[today.monthValue - 1]}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Pal.Muted)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" } + ", $userName",
                        style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 1
                    )
                    IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Настройки", tint = Pal.Muted) }
                }
                AlertLine(open)
            }
        }
        item(span = { GridItemSpan(4) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppCard(Modifier.weight(1f).heightIn(min = 150.dp), color = Pal.Sky, onClick = { open("weather") }) {
                    Text(city?.name ?: "Погода", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk)
                    Spacer(Modifier.height(6.dp))
                    if (weather != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherGlyph(wmo(weather.code).second, weather.isDay, Modifier.size(40.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(fmtT(weather.temp), style = MaterialTheme.typography.displayMedium)
                        }
                        Text(
                            wmo(weather.code).first + (weather.days.firstOrNull()?.let { " · до ${fmtT(it.tMax)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF44506A))
                        )
                    } else Text("Загрузка…", style = MaterialTheme.typography.bodySmall)
                }
                AppCard(Modifier.weight(1f).heightIn(min = 150.dp), color = Pal.Mint, onClick = { open("weight") }) {
                    Text("Вес · пн", style = MaterialTheme.typography.labelLarge, color = Pal.MintInk)
                    Spacer(Modifier.height(6.dp))
                    Text(weights.lastOrNull()?.let { "${fmtKg(it.kg)} кг" } ?: "— кг", style = MaterialTheme.typography.headlineLarge)
                    WeightChart(weights.takeLast(10), Modifier.fillMaxWidth().height(34.dp), line = Pal.MintInk)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (today.dayOfWeek == DayOfWeek.MONDAY) "Сегодня взвешивание" else "Взвешивание через ${daysToMonday()} дн.",
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFF3F5A4B)
                    )
                }
            }
        }
        item(span = { GridItemSpan(4) }) { Ticker(open) }
        item(span = { GridItemSpan(4) }) { UniCard(open) }
        item(span = { GridItemSpan(4) }) { PlansCard(open) }
        val nextAlarm = alarms.filter { it.enabled }.minByOrNull { nextTrigger(it) }
        if (nextAlarm != null) item(span = { GridItemSpan(4) }) {
            AppCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { open("clock/1") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Alarm, null, tint = Pal.LilacInk)
                    Spacer(Modifier.width(10.dp))
                    val t = nextTrigger(nextAlarm)
                    val day = when (t.toLocalDate()) {
                        today -> "сегодня"; today.plusDays(1) -> "завтра"
                        else -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)
                    }
                    Text("Будильник $day в %02d:%02d".format(nextAlarm.hour, nextAlarm.minute), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        item(span = { GridItemSpan(4) }) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Как самочувствие?", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk, modifier = Modifier.weight(1f))
                    Text("дневник →", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.clickable { open("mood") })
                }
                Spacer(Modifier.height(8.dp))
                com.pank.vsevodnom.ui.life.MoodPicker({ moodLevel = it })
            }
        }
        item(span = { GridItemSpan(4) }) { QuickActions(open) { moodLevel = 4 } }
        item(span = { GridItemSpan(4) }) { NewsCard() }
        item(span = { GridItemSpan(4) }) {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac, onClick = { open("capri") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.pank.vsevodnom.ui.capri.CapriAvatar(44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Капри", style = MaterialTheme.typography.titleSmall)
                        Text(com.pank.vsevodnom.ui.capri.Chat.msgs.lastOrNull { !it.fromUser }?.text?.take(80) ?: "Спроси, что по тревогам, добавь событие…", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
    moodLevel?.let { com.pank.vsevodnom.ui.life.MoodNoteDialog(it) { moodLevel = null } }
    if (settings) {
        var n by remember { mutableStateOf(userName) }
        var news by remember { mutableStateOf(com.pank.vsevodnom.data.UserPrefs.newsUrl(ctx)) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Настройки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(n, { n = it }, "Как к тебе обращаться")
                    Text("Новости", style = MaterialTheme.typography.labelLarge)
                    com.pank.vsevodnom.data.UserPrefs.NEWS_SOURCES.forEach { (title, url) -> Chip(title, news == url, { news = url }) }
                }
            },
            confirmButton = { TextButton({ com.pank.vsevodnom.data.UserPrefs.setName(ctx, n.trim().ifBlank { "Миша" }); com.pank.vsevodnom.data.UserPrefs.setNewsUrl(ctx, news); userName = n.trim().ifBlank { "Миша" }; settings = false }) { Text("Сохранить") } },
            dismissButton = { TextButton({ settings = false }) { Text("Отмена") } })
    }
}

/** Бегущая строка котировок — как на бирже. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Ticker(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val syms = remember { com.pank.vsevodnom.data.MarketsApi.watchlist(ctx) }
    val cache = com.pank.vsevodnom.ui.markets.QuoteCache.map
    LaunchedEffect(Unit) {
        syms.forEach { s -> if (cache[s] == null) runCatching { com.pank.vsevodnom.data.MarketsApi.quote(s) }.onSuccess { cache[s] = it } }
    }
    val parts = syms.mapNotNull { s -> cache[s]?.let { q -> Triple(com.pank.vsevodnom.data.MarketsApi.nameOf(ctx, s), com.pank.vsevodnom.ui.markets.fmtPrice(q.price), q.changePct) } }
    if (parts.isEmpty()) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { open("markets") }.padding(vertical = 8.dp).basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp)) {
        parts.forEach { (n, p, ch) ->
            Text("  $n ", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
            Text(p, style = MaterialTheme.typography.labelLarge)
            ch?.let { Text(" " + (if (it >= 0) "▲" else "▼") + com.pank.vsevodnom.ui.markets.fmtPct(it).trimStart('+', '−'), style = MaterialTheme.typography.labelLarge, color = if (it >= 0) Pal.MintInk else Pal.RoseInk) }
            Text("   ·", color = Pal.Muted)
        }
    }
}

/** Свои события и Google Календарь на сегодня (пары — отдельной карточкой). */
@Composable
private fun PlansCard(open: (String) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<com.pank.vsevodnom.data.schedule.Agenda>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val t = LocalDate.now()
                com.pank.vsevodnom.data.schedule.CalendarRepo.agendaSync(ctx, t, t.plusDays(1)).filter { it !is com.pank.vsevodnom.data.schedule.Agenda.Pair }
            }
            delay(120_000)
        }
    }
    if (items.isEmpty()) return
    AppCard(Modifier.fillMaxWidth(), onClick = { open("calendar") }) {
        Text("Планы", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
        items.take(4).forEach { a ->
            val r = a.row()
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(Color(r.color).copy(alpha = 0.5f)))
                Spacer(Modifier.width(10.dp))
                Text(r.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text((if (a.date != LocalDate.now()) "завтра " else "") + r.time.replace('\n', '–'), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
        }
    }
}

@Composable
private fun QuickActions(open: (String) -> Unit, onMood: () -> Unit) {
    data class Q(val t: String, val r: String)
    val list = listOf(Q("+ Событие", "calendar?new=1"), Q("+ Вес", "weight?new=1"), Q("+ В корзину", "cart?new=1"), Q("+ Копилка", "piggy"), Q("Таймер", "clock/3"), Q("Капри", "capri"))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list.size) { i -> Chip(list[i].t, false, { open(list[i].r) }) }
    }
}

@Composable
private fun NewsCard() {
    val ctx = LocalContext.current
    var news by remember { mutableStateOf<List<com.pank.vsevodnom.data.NewsItem>?>(null) }
    var err by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { com.pank.vsevodnom.data.News.load(ctx) }.onSuccess { news = it }.onFailure { err = true } }
    val n = news ?: return
    AppCard(Modifier.fillMaxWidth()) {
        Text("Главные новости", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk)
        n.take(if (all) 15 else 5).forEach { item ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.link))) }
            }.padding(vertical = 6.dp)) {
                Text(item.time, style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.width(44.dp))
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
        }
        if (!all && n.size > 5) TextButton({ all = true }) { Text("Ещё") }
    }
}

/** Все разделы плитками. */
@Composable
fun AppsScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(4) }) { Text("Все приложения", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(4.dp, 8.dp)) }
        items(TILES) { t ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { t.route?.let(open) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.tint.bg), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = t.tint.ink, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** «Сегодня в универе»: оставшиеся пары сегодня, иначе — ближайший учебный день. */
@Composable
private fun UniCard(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).schedule() }
    val today = LocalDate.now()
    val lessons by remember { dao.lessons(today.toEpochDay(), today.plusDays(14).toEpochDay()) }.collectAsState(initial = null)
    val aliases by dao.aliases().collectAsState(initial = emptyList())
    val all = lessons ?: return
    val nowT = LocalTime.now()
    val rest = all.filter { it.epochDay > today.toEpochDay() || LocalTime.parse(it.end) > nowT }
    val day = rest.firstOrNull()?.epochDay
    val shown = rest.filter { it.epochDay == day }
    val map = aliases.associateBy { it.abbr }
    AppCard(Modifier.fillMaxWidth(), onClick = { open("calendar") }) {
        if (all.isEmpty() || day == null) {
            Text("Пары", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
            Spacer(Modifier.height(4.dp))
            Text(
                if (all.isEmpty()) "Загрузи файл расписания из портала — пары появятся здесь и в виджете"
                else "На ближайшие две недели пар нет",
                style = MaterialTheme.typography.bodyMedium, color = Pal.Muted
            )
            return@AppCard
        }
        val d = LocalDate.ofEpochDay(day)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (d) { today -> "Сегодня в универе"; today.plusDays(1) -> "Завтра в универе"
                    else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + " в универе" },
                style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk, modifier = Modifier.weight(1f)
            )
            Text("${shown.size} ${pairsWord(shown.size)}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        }
        shown.forEach { l ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = Tints.all[(map[l.subject]?.tint ?: com.pank.vsevodnom.data.schedule.tintFor(l.subject)) % 6]
                Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(tint.ink.copy(alpha = 0.5f)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (map[l.subject]?.fullName?.takeIf { it.isNotBlank() } ?: l.subject) + if (l.kind.isNotBlank()) " [${l.kind}]" else "",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1
                    )
                    Text(
                        listOf(if (l.room.firstOrNull()?.isDigit() == true) "ауд. ${l.room}" else l.room, l.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1
                    )
                }
                Text(l.start, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun pairsWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "пара"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "пары"
    else -> "пар"
}

/** Тревоги одной строкой под приветствием: цветной кружок + коротко. */
@Composable
private fun AlertLine(open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { com.pank.vsevodnom.data.AlertsApi.fetch(ctx) }.onSuccess { com.pank.vsevodnom.ui.alerts.AlertCache.state = it }
            delay(60_000)
        }
    }
    val (lvl, text) = com.pank.vsevodnom.ui.alerts.kyivSummary(com.pank.vsevodnom.ui.alerts.AlertCache.state)
    Row(
        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { open("alerts") }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(com.pank.vsevodnom.ui.alerts.levelColor(lvl)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Pal.Muted, maxLines = 1)
    }
}
