package com.pank.vsevodnom.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    Tile("Будильник", Icons.Rounded.Alarm, Tints.Lilac, "clock/1"),
    Tile("Таймер", Icons.Rounded.HourglassBottom, Tints.Lilac, "clock/3"),
    Tile("Погода", Icons.Rounded.Cloud, Tints.Sky, "weather"),
    Tile("Калькулятор", Icons.Rounded.Calculate, Tints.Butter, "calc"),
    Tile("Рандом", Icons.Rounded.Casino, Tints.Peach, "random"),
    Tile("Копилки", Icons.Rounded.Savings, Tints.Mint, "piggy"),
    Tile("Вес", Icons.Rounded.MonitorWeight, Tints.Mint, "weight"),
    // следующие этапы
    Tile("Календарь", Icons.Rounded.CalendarMonth, Tints.Lilac, null),
    Tile("Курсы", Icons.AutoMirrored.Rounded.TrendingUp, Tints.Mint, null),
    Tile("Тревоги", Icons.Rounded.Campaign, Tints.Rose, null),
    Tile("Утилиты", Icons.Rounded.Build, Tints.Butter, null),
    Tile("Файлы", Icons.Rounded.Description, Tints.Butter, null),
    Tile("Диктофон", Icons.Rounded.Mic, Tints.Rose, null),
    Tile("Игры", Icons.Rounded.SportsEsports, Tints.Peach, null),
    Tile("Пароли", Icons.Rounded.Lock, Tints.Lilac, null),
    Tile("Здоровье", Icons.Rounded.Favorite, Tints.Rose, null),
    Tile("Загрузки", Icons.Rounded.Download, Tints.Peach, null),
    Tile("СМС", Icons.Rounded.Sms, Tints.Sky, null),
    Tile("Контакты", Icons.Rounded.Person, Tints.Sky, null),
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
                Text(
                    when (hour) { in 5..11 -> "Доброе утро"; in 12..17 -> "Добрый день"; in 18..22 -> "Добрый вечер"; else -> "Доброй ночи" },
                    style = MaterialTheme.typography.headlineLarge
                )
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
            Text("Всё", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
        items(TILES) { t ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                    if (t.route != null) open(t.route)
                    else Toast.makeText(ctx, "«${t.title}» — в следующих этапах", Toast.LENGTH_SHORT).show()
                }.alpha(if (t.route == null) 0.45f else 1f),
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
