package com.pank.vsevodnom.ui.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    LaunchedEffect(city?.id) {
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

    val scene = data?.let { wmo(it.code).second } ?: Scene.CLOUDY
    val isDay = data?.isDay ?: true

    LazyColumn(Modifier.fillMaxSize().background(Pal.Bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().height(440.dp)
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
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
                            listOf(city.name, city.region.substringBefore(',').takeIf { city.isCurrentLocation && it.isNotBlank() }).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E4F78)
                        )
                    }
                    if (data != null) {
                        Text(fmtT(data.temp), style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, letterSpacing = (-5).sp))
                        Text(wmo(data.code).first, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                        val today = data.days.firstOrNull()
                        Text(
                            "Ощущается как ${fmtT(data.feels)}" + (today?.let { " · ↑ ${fmtT(it.tMax)} ↓ ${fmtT(it.tMin)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4A63)
                        )
                    } else if (!loading) {
                        Text(error ?: "Загрузка…", style = MaterialTheme.typography.titleMedium)
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
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
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
                            Text(c.name, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Pal.Ink)
                        }
                    }
                }
            }
            if (error != null && data != null) Text("$error — показаны сохранённые данные", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
        }
        if (data != null) {
            item { HourlyCard(data) }
            item { StatsGrid(data) }
            item { DailyCard(data) }
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
private fun HourlyCard(d: WeatherData) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 0.dp)) {
        SectionLabel("По часам")
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(d.hours) { h ->
                val first = h == d.hours.first()
                Column(
                    Modifier.width(56.dp).clip(RoundedCornerShape(16.dp)).background(if (first) Color(0xFFE8F1FB) else Color.Transparent).padding(vertical = 8.dp),
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
private fun MiniIcon(scene: Scene, isDay: Boolean) = WeatherGlyph(scene, isDay, Modifier.size(28.dp))

@Composable
private fun StatsGrid(d: WeatherData) {
    val today = d.days.firstOrNull()
    val stats = buildList {
        add(Triple("Ветер", "${d.windMs.roundToInt()} м/с", "${windDirRu(d.windDir)}, порывы до ${d.gustsMs.roundToInt()}"))
        add(Triple("Влажность", "${d.humidity}%", "облачность ${d.cloud}%"))
        add(Triple("Давление", "${d.pressureMm.roundToInt()} мм", "${d.pressureHpa.roundToInt()} гПа"))
        if (today != null) add(Triple("УФ-индекс", "${today.uv.roundToInt()}", uvText(today.uv)))
        if (today != null) add(Triple("Восход", today.sunrise, "закат ${today.sunset}"))
        if (d.aqi != null) add(Triple("Воздух", "AQI ${d.aqi}", aqiText(d.aqi) + (d.pm25?.let { " · PM2.5 ${it.roundToInt()}" } ?: "")))
        else d.visibilityM?.let { add(Triple("Видимость", "${(it / 1000).roundToInt()} км", "")) }
    }
    Column(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (l, v, n) ->
                    AppCard(modifier = Modifier.weight(1f), radius = 20.dp) {
                        Text(l, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        Text(v, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text(n, style = MaterialTheme.typography.labelMedium, color = Pal.Muted, maxLines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DailyCard(d: WeatherData) {
    val lo = d.days.minOfOrNull { it.tMin } ?: 0.0
    val hi = d.days.maxOfOrNull { it.tMax } ?: 1.0
    val span = (hi - lo).coerceAtLeast(1.0)
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 12.dp), padding = 16.dp) {
        SectionLabel("На 10 дней")
        Spacer(Modifier.height(4.dp))
        d.days.forEach { day ->
            val name = when (day.date) {
                LocalDate.now() -> "Сегодня"
                LocalDate.now().plusDays(1) -> "Завтра"
                else -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() } + ", ${day.date.dayOfMonth}"
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Sky).clickable(onClick = onLocation).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.MyLocation, null, tint = Pal.SkyInk)
                    Spacer(Modifier.width(10.dp))
                    Text("Моё местоположение", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk)
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(q, { q = it }, "Название города")
                if (err) Text("Нет связи", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
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
