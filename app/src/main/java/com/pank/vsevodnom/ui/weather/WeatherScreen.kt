package com.pank.vsevodnom.ui.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val RU = Locale("ru")
const val WEATHER_TTL_MS = 20 * 60_000L

object WeatherRepo {
    /** Обновить и закэшировать. Возвращает свежие данные или кидает исключение. */
    suspend fun refresh(ctx: Context, c: WeatherCity): WeatherData {
        val raw = WeatherApi.fetchRaw(c.lat, c.lon)
        AppDb.get(ctx).cities().cacheWeather(c.id, raw, System.currentTimeMillis())
        return WeatherApi.parse(raw)
    }

    fun cached(c: WeatherCity): WeatherData? = c.cachedJson?.let { runCatching { WeatherApi.parse(it) }.getOrNull() }

    suspend fun ensureDefault(ctx: Context) {
        val dao = AppDb.get(ctx).cities()
        if (dao.weatherOnce().isEmpty()) dao.upsertWeather(WeatherCity(name = "Киев", region = "Украина", lat = 50.4501, lon = 30.5234))
    }
}

fun fmtT(t: Double): String {
    val r = t.roundToInt()
    return when { r > 0 -> "+$r°"; r < 0 -> "−${-r}°"; else -> "0°" }
}

private fun windDirRu(deg: Int): String {
    val names = listOf("северный", "северо-восточный", "восточный", "юго-восточный", "южный", "юго-западный", "западный", "северо-западный")
    return names[((deg % 360 + 22) / 45) % 8]
}

private fun uvText(uv: Double) = when { uv < 3 -> "низкий"; uv < 6 -> "умеренный"; uv < 8 -> "высокий"; uv < 11 -> "очень высокий"; else -> "экстремальный" }
private fun aqiText(a: Int) = when { a <= 20 -> "отлично"; a <= 40 -> "хорошо"; a <= 60 -> "средне"; a <= 80 -> "плохо"; a <= 100 -> "очень плохо"; else -> "опасно" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeatherScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cities() }
    val scope = rememberCoroutineScope()
    val cities by dao.weather().collectAsState(initial = null)
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WeatherCity?>(null) }
    var dayIdx by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { WeatherRepo.ensureDefault(ctx) }

    val list = cities ?: emptyList()
    val city = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    val data = remember(city?.cachedJson) { city?.let { WeatherRepo.cached(it) } }

    fun reload(c: WeatherCity) {
        scope.launch {
            loading = true; error = null
            runCatching { WeatherRepo.refresh(ctx, c) }.onFailure { error = "Нет связи с сервером погоды" }
            loading = false
        }
    }
    LaunchedEffect(city?.id, com.pank.vsevodnom.data.Net.online) {
        val c = city ?: return@LaunchedEffect
        if (System.currentTimeMillis() - c.cachedAt > WEATHER_TTL_MS) reload(c)
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) scope.launch { addCurrentLocation(ctx)?.let { selectedId = it } ?: run { error = "Не удалось определить место" } }
    }
    fun requestLocation() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) scope.launch { loading = true; addCurrentLocation(ctx)?.let { selectedId = it } ?: run { error = "Не удалось определить место" }; loading = false }
        else locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val selDay = data?.dayAt(dayIdx)
    val scene = data?.let { wmo(if (dayIdx != 0 && selDay != null) selDay.code else it.code).second } ?: Scene.CLOUDY
    val isDay = if (dayIdx != 0) true else data?.isDay ?: true

    LazyColumn(Modifier.fillMaxSize().background(Pal.Bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().height(440.dp)
                    .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                    .background(skyColor(scene, isDay))
            ) {
                WeatherScene(scene, isDay, Modifier.matchParentSize())
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, bg = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Pal.Ink)
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { city?.let { reload(it) } }, bg = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(Icons.Rounded.Add, "Добавить город", { adding = true }, bg = Color.White.copy(alpha = 0.7f))
                    }
                    Spacer(Modifier.weight(1f))
                    if (city != null) {
                        Text(
                            city.name,
                            style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E4F78)
                        )
                    }
                    if (data != null) {
                        val day = data.dayAt(dayIdx)
                        if (dayIdx == 0 || day == null) {
                            Text(fmtT(data.temp), style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, letterSpacing = (-5).sp))
                            Text("Ощущается как ${fmtT(data.feels)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF2E4F78))
                            Text(wmo(data.code).first, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        } else {
                            val feels = data.allHours.filter { it.time.toLocalDate() == day.date && it.time.hour in 9..18 }.mapNotNull { it.feels }.maxOrNull()
                            Text(fmtT(day.tMax), style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, letterSpacing = (-5).sp))
                            feels?.let { Text("Ощущается до ${fmtT(it)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF2E4F78)) }
                            Text(wmo(day.code).first, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                        day?.let { Text("↑ ${fmtT(it.tMax)}  ↓ ${fmtT(it.tMin)}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4A63)) }
                    } else {
                        NetWait(if (loading) null else error)
                    }
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 16.dp, 16.dp, 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                list.forEach { c ->
                    val sel = c.id == city?.id
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Pal.Ink else Pal.Card)
                            .combinedClickable(onClick = { selectedId = c.id }, onLongClick = { deleting = c })
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.isCurrentLocation) {
                                Icon(Icons.Rounded.MyLocation, null, Modifier.size(14.dp), tint = if (sel) Color.White else Pal.Ink)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (c.isCurrentLocation) listOf(c.name, c.region.substringBefore(',').trim()).filter { it.isNotBlank() }.joinToString(", ") else c.name,
                                style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Pal.Ink)
                        }
                    }
                }
            }
            if (error != null && data != null && com.pank.vsevodnom.data.Net.online) Text("$error — показаны сохранённые данные", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
        }
        if (data != null) {
            val date = data.dayAt(dayIdx)?.date ?: LocalDate.now()
            item { DaySwitcher(data, dayIdx) { dayIdx = it } }
            tipsFor(data, date).takeIf { it.isNotEmpty() }?.let { t -> item { TipsCard(t) } }
            item { HourlyCard(data, date) }
            item { StatsGrid(data, dayIdx) }
            item { SunCard(data, dayIdx) }
            item { DailyCard(data, dayIdx) { dayIdx = it } }
            item {
                Text(
                    "Данные: Open-Meteo · обновлено ${java.time.Instant.ofEpochMilli(data.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).withSecond(0)}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }

    if (adding) AddCityDialog(
        onDismiss = { adding = false },
        onLocation = { adding = false; requestLocation() },
        onPick = { p ->
            adding = false
            scope.launch {
                val id = dao.upsertWeather(WeatherCity(name = p.name, region = p.region, lat = p.lat, lon = p.lon, sort = list.size))
                selectedId = id
            }
        }
    )
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Убрать ${c.name}?") },
            confirmButton = { TextButton(onClick = { scope.launch { dao.deleteWeather(c) }; deleting = null; selectedId = null }) { Text("Убрать", color = Pal.RoseInk) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

private suspend fun addCurrentLocation(ctx: Context): Long? {
    val p = WeatherApi.locate(ctx) ?: return null
    val dao = AppDb.get(ctx).cities()
    val existing = dao.currentLocation()
    return dao.upsertWeather(
        (existing ?: WeatherCity(name = p.name, region = p.region, lat = p.lat, lon = p.lon, isCurrentLocation = true))
            .copy(name = p.name, region = p.region, lat = p.lat, lon = p.lon, cachedAt = 0)
    ).let { if (existing != null) existing.id else it }
}

@Composable
private fun HourlyCard(d: WeatherData, date: LocalDate) {
    val hours = if (date == LocalDate.now()) d.hours else d.allHours.filter { it.time.toLocalDate() == date }
    if (hours.isEmpty()) return
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)) {
        SectionLabel("По часам")
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(hours) { h ->
                val first = date == LocalDate.now() && h == hours.first()
                Column(
                    Modifier.width(56.dp).clip(RoundedCornerShape(14.dp)).background(if (first) Color(0xFFE8F1FB) else Color.Transparent).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(if (first) "Сейчас" else "%02d".format(h.time.hour), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                    MiniIcon(wmo(h.code).second, h.isDay)
                    Text(fmtT(h.temp), style = MaterialTheme.typography.titleMedium)
                    Text(if (h.rainProb >= 10) "${h.rainProb}%" else " ", style = MaterialTheme.typography.labelSmall.copy(color = Pal.SkyInk))
                }
            }
        }
    }
}

@Composable
private fun MiniIcon(scene: Scene, isDay: Boolean) = WeatherGlyph(scene, isDay, Modifier.size(28.dp), animated = true)

@Composable
private fun StatsGrid(d: WeatherData, dayIdx: Int) {
    val day = d.dayAt(dayIdx) ?: return
    val hrs = d.allHours.filter { it.time.toLocalDate() == day.date }
    val now = dayIdx == 0
    val wind = if (now) d.windMs else day.windMax ?: hrs.mapNotNull { it.wind }.maxOrNull() ?: 0.0
    val dir = if (now) d.windDir else hrs.mapNotNull { it.windDir }.let { if (it.isEmpty()) 0 else it[it.size / 2] }
    val hum = if (now) d.humidity else hrs.mapNotNull { it.humidity }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
    Column(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Ветер", if (now) "${wind.roundToInt()} м/с" else "до ${wind.roundToInt()} м/с", if (now) "${windDirRu(dir)}, порывы ${d.gustsMs.roundToInt()}" else windDirRu(dir), Modifier.weight(1f)) { WindArrow(dir, Modifier.size(30.dp)) }
            Stat("Влажность", "$hum%", if (now) "облачность ${d.cloud}%" else "в среднем за день", Modifier.weight(1f)) { HumidityDrop(hum, Modifier.size(30.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("УФ-индекс", "${day.uv.roundToInt()}", uvText(day.uv), Modifier.weight(1f)) { UvIcon(day.uv, Modifier.size(30.dp)) }
            if (now) Stat("Давление", "${d.pressureMm.roundToInt()} мм", "${d.pressureHpa.roundToInt()} гПа", Modifier.weight(1f)) { PressureGauge(d.pressureMm, Modifier.size(30.dp)) }
            else Stat("Осадки", "${day.rainProb}%", day.precipSum?.let { "%.1f мм".format(it).replace('.', ',') } ?: "", Modifier.weight(1f)) { HumidityDrop(day.rainProb, Modifier.size(30.dp)) }
        }
        if (now) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.aqi != null) Stat("Воздух", "AQI ${d.aqi}", aqiText(d.aqi) + (d.pm25?.let { " · PM2.5 ${it.roundToInt()}" } ?: ""), Modifier.weight(1f)) { AirDot(d.aqi, Modifier.size(30.dp)) }
            d.visibilityM?.let { v -> Stat("Видимость", "${(v / 1000).roundToInt()} км", if (v < 1000) "туман" else if (v < 5000) "дымка" else "хорошая", Modifier.weight(1f)) { VisibilityIcon(v, Modifier.size(30.dp)) } }
            if (d.aqi == null || d.visibilityM == null) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, note: String, modifier: Modifier, icon: @Composable () -> Unit) {
    AppCard(modifier, radius = 16.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) { icon(); Spacer(Modifier.width(8.dp)); Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) }
        Text(note, style = MaterialTheme.typography.labelMedium, color = Pal.Muted, maxLines = 2)
    }
}

/** Стрелки «‹ день ›» под анимацией: вся лента ниже — про выбранный день. */
@Composable
private fun DaySwitcher(d: WeatherData, idx: Int, onIdx: (Int) -> Unit) {
    val max = minOf(6, d.days.size - 1)
    val day = d.dayAt(idx) ?: return
    val min = if (d.yesterday != null) -1 else 0
    val nudge by rememberInfiniteTransition(label = "arr").animateFloat(0f, 5f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "n")
    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ if (idx > min) onIdx(idx - 1) }, enabled = idx > min) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Предыдущий день", Modifier.offset(x = if (idx > min) (-nudge).dp else 0.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (idx) { -1 -> "Вчера"; 0 -> "Сегодня"; 1 -> "Завтра"; else -> day.date.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.titleMedium)
            Text("${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.FULL, RU).let { m -> listOf("января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря")[day.date.monthValue - 1] }}",
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton({ if (idx < max) onIdx(idx + 1) }, enabled = idx < max) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Следующий день", Modifier.offset(x = if (idx < max) nudge.dp else 0.dp)) }
    }
}

@Composable
private fun DailyCard(d: WeatherData, selected: Int, onDay: (Int) -> Unit) {
    val lo = d.days.minOfOrNull { it.tMin } ?: 0.0
    val hi = d.days.maxOfOrNull { it.tMax } ?: 1.0
    val span = (hi - lo).coerceAtLeast(1.0)
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 12.dp), padding = 16.dp) {
        SectionLabel("На 7 дней")
        Spacer(Modifier.height(4.dp))
        d.days.forEachIndexed { i, day ->
            val name = when (day.date) {
                LocalDate.now() -> "Сегодня"
                LocalDate.now().plusDays(1) -> "Завтра"
                else -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() } + ", ${day.date.dayOfMonth}"
            }
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (i == selected) Color(0xFFE8F1FB) else Color.Transparent).clickable(enabled = i <= 6) { onDay(i) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(86.dp))
                MiniIcon(wmo(day.code).second, true)
                Text(if (day.rainProb >= 20) "${day.rainProb}%" else "", style = MaterialTheme.typography.labelMedium, color = Pal.SkyInk, modifier = Modifier.width(34.dp))
                Text(fmtT(day.tMin), style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, modifier = Modifier.width(38.dp))
                BoxWithConstraints(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFEEF1F5))) {
                    val start = ((day.tMin - lo) / span).toFloat()
                    val width = ((day.tMax - day.tMin) / span).toFloat().coerceAtLeast(0.05f)
                    Box(
                        Modifier.offset(x = maxWidth * start).width(maxWidth * width).fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(Color(0xFFA9C8EC))
                    )
                }
                Text(fmtT(day.tMax), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(38.dp))
            }
        }
    }
}

@Composable
private fun AddCityDialog(onDismiss: () -> Unit, onLocation: () -> Unit, onPick: (WeatherApi.Place) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherApi.Place>>(emptyList()) }
    var err by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        runCatching { WeatherApi.search(q.trim()) }.onSuccess { results = it; err = false }.onFailure { err = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Добавить город") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Sky).clickable(onClick = onLocation).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.MyLocation, null, tint = Pal.SkyInk)
                    Spacer(Modifier.width(10.dp))
                    Text("Моё местоположение", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk)
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(q, { q = it }, "Название города")
                if (err) NetWait(if (com.pank.vsevodnom.data.Net.online) "Нет связи" else null, Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.region, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    )
}

// ---------------- советы, солнце, детали дня ----------------

/** Совместимость со старым вызовом (Капри убрана, но функцию используют другие места). */
fun tips(d: WeatherData): List<String> = tipsFor(d, LocalDate.now())

/**
 * Советы на ВЕСЬ день (а не «на сейчас»): смотрим прогноз с 6 до 23 часов и говорим, когда что ждёт.
 * Например: утром ясно, дождь с 15:00 → «Зонт понадобится после 15:00».
 */
fun tipsFor(d: WeatherData, date: LocalDate): List<String> {
    val day = d.days.firstOrNull { it.date == date } ?: return emptyList()
    val hrs = d.allHours.filter { it.time.toLocalDate() == date && it.time.hour in 6..23 }
    fun h(x: HourW) = "%02d:00".format(x.time.hour)
    val out = ArrayList<String>()
    val hail = hrs.firstOrNull { it.code in setOf(96, 99) }
    val storm = hrs.firstOrNull { it.code == 95 }
    if (hail != null) out += "Осторожно, возможен град около ${h(hail)}"
    else if (storm != null) out += "Гроза около ${h(storm)} — держись подальше от деревьев и воды"
    val wet = hrs.filter { it.rainProb >= 50 || wmo(it.code).second == Scene.RAIN }
    if (wet.isNotEmpty()) {
        val first = wet.first(); val last = wet.last()
        out += when {
            wet.size >= hrs.size * 0.7 -> "Дождь почти весь день — зонт обязательно"
            first.time.hour >= 17 -> "Вечером дождь (с ${h(first)}) — зонт к вечеру"
            first.time.hour >= 12 -> "Зонт понадобится после ${h(first)}"
            last.time.hour <= 12 -> "Утром дождь до ~${h(last)}, потом суше"
            else -> "Дождь с ${h(first)} — не забудь зонт"
        }
    }
    hrs.firstOrNull { wmo(it.code).second == Scene.SNOW }?.let { out += "Снег с ${h(it)} — обувь по погоде" }
    if (day.uv >= 6) out += "Сильное солнце днём (УФ ${day.uv.roundToInt()}) — нужен крем"
    if ((day.windMax ?: 0.0) >= 12) out += "Сильный ветер, до ${day.windMax!!.roundToInt()} м/с"
    if (day.tMax - day.tMin >= 9) out += "Утром ${fmtT(day.tMin)}, днём до ${fmtT(day.tMax)} — одевайся слоями"
    if (day.tMax >= 30) out += "Жарко — пей больше воды"
    if (day.tMin <= 0 && (day.precipSum ?: 0.0) > 0) out += "Возможен гололёд"
    hrs.firstOrNull { it.code == 45 || it.code == 48 }?.let { out += "Туман около ${h(it)} — осторожно на дороге" }
    val morningFeels = hrs.filter { it.time.hour in 7..9 }.mapNotNull { it.feels }.minOrNull()
    if (morningFeels != null && morningFeels <= day.tMin + 0.5 && morningFeels <= 3) out += "Утром ощущается ${fmtT(morningFeels)} — оденься теплее"
    return out.distinct().take(4)
}

@Composable
private fun TipsCard(t: List<String>) {
    AppCard(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp), color = Pal.Butter) {
        t.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Pal.Ink) }
    }
}

/** Стрелка «куда дует» (метеонаправление — откуда, поэтому +180°). */
@Composable
fun WindArrow(fromDeg: Int, modifier: Modifier) {
    val wobble = rememberInfiniteTransition(label = "wind").animateFloat(-6f, 6f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "w")
    Icon(Icons.Rounded.Navigation, "Направление ветра", tint = Pal.SkyInk, modifier = modifier.rotate(fromDeg + 180f + wobble.value))
}

@Composable
fun HumidityDrop(h: Int, modifier: Modifier) {
    val wave = rememberInfiniteTransition(label = "hum").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "h")
    Canvas(modifier) {
        val w = size.width; val hh = size.height
        // капля поменьше и круглее: шар снизу и короткий носик сверху
        val r = w * 0.34f; val cy = hh * 0.62f
        val drop = Path().apply {
            moveTo(w / 2, hh * 0.1f)
            cubicTo(w / 2 + r * 0.35f, hh * 0.3f, w / 2 + r, cy - r * 0.55f, w / 2 + r, cy)
            cubicTo(w / 2 + r, cy + r * 1.05f, w / 2 - r, cy + r * 1.05f, w / 2 - r, cy)
            cubicTo(w / 2 - r, cy - r * 0.55f, w / 2 - r * 0.35f, hh * 0.3f, w / 2, hh * 0.1f)
        }
        clipPath(drop) {
            drawRect(Pal.Sky)
            val level = hh * (1 - h / 100f)
            val wp = Path().apply {
                moveTo(0f, level)
                for (x in 0..20) lineTo(x / 20f * w, level + kotlin.math.sin((x / 20f + wave.value) * 2 * Math.PI).toFloat() * hh * 0.04f)
                lineTo(w, hh); lineTo(0f, hh); close()
            }
            drawPath(wp, Pal.SkyInk.copy(alpha = 0.6f))
        }
        drawPath(drop, Pal.SkyInk, style = Stroke(2f))
    }
}

/** Дуга солнца: восход, закат и где солнце сейчас. */
@Composable
private fun SunCard(d: WeatherData, dayIdx: Int) {
    val day = d.dayAt(dayIdx) ?: return
    val rise = runCatching { java.time.LocalTime.parse(day.sunrise) }.getOrNull() ?: return
    val set = runCatching { java.time.LocalTime.parse(day.sunset) }.getOrNull() ?: return
    val now = if (dayIdx == 0) d.hours.firstOrNull()?.time?.toLocalTime() ?: java.time.LocalTime.now() else java.time.LocalTime.of(0, 0)
    val len = java.time.Duration.between(rise, set)
    val p = (java.time.Duration.between(rise, now).seconds.toFloat() / len.seconds).coerceIn(-0.05f, 1.05f)
    AppCard(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)) {
        SectionLabel("Солнце · световой день ${len.toHours()} ч ${len.toMinutes() % 60} мин")
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val w = size.width; val h = size.height
            drawLine(Pal.Line, Offset(0f, h - 6f), Offset(w, h - 6f), 3f)
            val arc = Path().apply { moveTo(w * 0.08f, h - 6f); quadraticBezierTo(w / 2, -h * 0.75f, w * 0.92f, h - 6f) }
            drawPath(arc, Pal.ButterInk.copy(alpha = 0.35f), style = Stroke(4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
            if (p in 0f..1f) {
                val t = p
                val x = (1 - t) * (1 - t) * w * 0.08f + 2 * (1 - t) * t * w / 2 + t * t * w * 0.92f
                val y = (1 - t) * (1 - t) * (h - 6f) + 2 * (1 - t) * t * (-h * 0.75f) + t * t * (h - 6f)
                drawCircle(Color(0xFFFCE7A6), 18f, Offset(x, y)); drawCircle(Color(0xFFF6D77B), 11f, Offset(x, y))
            }
        }
        Row {
            Text("Восход ${day.sunrise}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("Закат ${day.sunset}", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun DayDetail(d: WeatherData, date: LocalDate, onDismiss: () -> Unit) {
    val day = d.days.firstOrNull { it.date == date } ?: return
    val hrs = d.allHours.filter { it.time.toLocalDate() == date && it.time.hour % 2 == 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } },
        title = { Text(date.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + ", ${date.dayOfMonth}.${"%02d".format(date.monthValue)}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WeatherGlyph(wmo(day.code).second, true, Modifier.size(44.dp), animated = true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${fmtT(day.tMin)} … ${fmtT(day.tMax)}", style = MaterialTheme.typography.titleLarge)
                            Text(wmo(day.code).first, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    com.pank.vsevodnom.ui.utils.InfoGroup("За день", listOfNotNull(
                        "Осадки" to "${day.rainProb}%" + (day.precipSum?.let { " · %.1f мм".format(it).replace('.', ',') } ?: ""),
                        day.windMax?.let { "Ветер до" to "${it.roundToInt()} м/с" },
                        "УФ-индекс" to "${day.uv.roundToInt()} · ${uvText(day.uv)}",
                        "Восход / закат" to "${day.sunrise} / ${day.sunset}",
                    ))
                }
                if (hrs.isEmpty()) item { Text("Почасового прогноза на этот день нет", style = MaterialTheme.typography.bodySmall) }
                items(hrs) { h ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d:00".format(h.time.hour), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
                        WeatherGlyph(wmo(h.code).second, h.isDay, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(fmtT(h.temp), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(44.dp))
                        Text(if (h.rainProb >= 10) "☂ ${h.rainProb}%" else "", style = MaterialTheme.typography.labelMedium, color = Pal.SkyInk, modifier = Modifier.width(56.dp))
                        h.wind?.let { w -> h.windDir?.let { WindArrow(it, Modifier.size(16.dp)) }; Text(" ${w.roundToInt()} м/с", style = MaterialTheme.typography.labelMedium, color = Pal.Muted) }
                    }
                }
            }
        }
    )
}

// ---------------- иконки показателей ----------------

@Composable
fun UvIcon(uv: Double, modifier: Modifier) {
    val spin by rememberInfiniteTransition(label = "uv").animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "s")
    val c = when { uv < 3 -> Color(0xFF7CC59A); uv < 6 -> Color(0xFFE9C46A); uv < 8 -> Color(0xFFF0A982); else -> Color(0xFFB27BD6) }
    Canvas(modifier) {
        val r = size.minDimension * 0.22f
        rotate(spin) { for (i in 0 until 8) { val a = i * Math.PI / 4; drawLine(c, center + Offset((kotlin.math.cos(a) * r * 1.45).toFloat(), (kotlin.math.sin(a) * r * 1.45).toFloat()), center + Offset((kotlin.math.cos(a) * r * 2.1).toFloat(), (kotlin.math.sin(a) * r * 2.1).toFloat()), 3f) } }
        drawCircle(c, r)
    }
}

@Composable
fun PressureGauge(mm: Double, modifier: Modifier) {
    val p = ((mm - 730) / 50).toFloat().coerceIn(0f, 1f)
    val wob by rememberInfiniteTransition(label = "pr").animateFloat(-0.02f, 0.02f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "w")
    Canvas(modifier) {
        val r = size.minDimension * 0.42f
        drawArc(Pal.Line, 180f, 180f, false, Offset(center.x - r, center.y - r * 0.6f), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(5f))
        drawArc(Pal.SkyInk.copy(alpha = 0.6f), 180f, 180f * p, false, Offset(center.x - r, center.y - r * 0.6f), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(5f))
        val a = Math.PI * (1 + (p + wob).coerceIn(0f, 1f))
        val c = Offset(center.x, center.y + r * 0.4f)
        drawLine(Pal.Ink, c, c + Offset((kotlin.math.cos(a) * r * 0.85).toFloat(), (kotlin.math.sin(a) * r * 0.85).toFloat()), 3f)
        drawCircle(Pal.Ink, 3.5f, c)
    }
}

@Composable
fun AirDot(aqi: Int, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "air").animateFloat(0.75f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "p")
    val c = when { aqi <= 20 -> Color(0xFF5FB287); aqi <= 40 -> Color(0xFF9AD3A8); aqi <= 60 -> Color(0xFFE9C46A); aqi <= 80 -> Color(0xFFF0A982); else -> Color(0xFFE0607E) }
    Canvas(modifier) { drawCircle(c.copy(alpha = 0.3f), size.minDimension / 2 * pulse); drawCircle(c, size.minDimension * 0.28f) }
}

@Composable
fun VisibilityIcon(m: Double, modifier: Modifier) {
    val k = (m / 10_000).toFloat().coerceIn(0.15f, 1f)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val eye = Path().apply { moveTo(w * 0.05f, h / 2); quadraticBezierTo(w / 2, h * 0.05f, w * 0.95f, h / 2); quadraticBezierTo(w / 2, h * 0.95f, w * 0.05f, h / 2); close() }
        drawPath(eye, Pal.SkyInk.copy(alpha = 0.25f + 0.5f * k), style = Stroke(3f))
        drawCircle(Pal.SkyInk.copy(alpha = 0.3f + 0.6f * k), w * 0.14f, center)
    }
}

/** День по номеру: −1 — вчера (листается стрелкой «‹» от «Сегодня»), 0 — сегодня, дальше — прогноз. */
internal fun WeatherData.dayAt(i: Int) = if (i == -1) yesterday else days.getOrNull(i)
