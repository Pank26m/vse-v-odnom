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
    var dayOpen by remember { mutableStateOf<LocalDate?>(null) }
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
                            city.name,
                            style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E4F78)
                        )
                    }
                    if (data != null) {
                        Text(fmtT(data.temp), style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, letterSpacing = (-5).sp))
                        Text("Ощущается как ${fmtT(data.feels)}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF2E4F78))
                        Text(wmo(data.code).first, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        val today = data.days.firstOrNull()
                        today?.let { Text("↑ ${fmtT(it.tMax)}  ↓ ${fmtT(it.tMin)}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4A63)) }
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
                            Text(if (c.isCurrentLocation) listOf(c.name, c.region.substringBefore(',').trim()).filter { it.isNotBlank() }.joinToString(", ") else c.name,
                                style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Pal.Ink)
                        }
                    }
                }
            }
            if (error != null && data != null) Text("$error — показаны сохранённые данные", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
        }
        if (data != null) {
            tips(data).takeIf { it.isNotEmpty() }?.let { t -> item { TipsCard(t) } }
            item { HourlyCard(data) }
            item { StatsGrid(data) }
            item { SunCard(data) }
            item { DailyCard(data) { dayOpen = it } }
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
    dayOpen?.let { d -> data?.let { DayDetail(it, d) { dayOpen = null } } }
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
private fun MiniIcon(scene: Scene, isDay: Boolean) = WeatherGlyph(scene, isDay, Modifier.size(28.dp), animated = true)

@Composable
private fun StatsGrid(d: WeatherData) {
    val today = d.days.firstOrNull()
    Column(Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.weight(1f), radius = 20.dp) {
                Text("Ветер", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WindArrow(d.windDir, Modifier.size(30.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${d.windMs.roundToInt()} м/с", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                }
                Text("${windDirRu(d.windDir)}, порывы ${d.gustsMs.roundToInt()}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, maxLines = 2)
            }
            AppCard(Modifier.weight(1f), radius = 20.dp) {
                Text("Влажность", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HumidityDrop(d.humidity, Modifier.size(30.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${d.humidity}%", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                }
                Text("облачность ${d.cloud}%", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
        }
        val rest = buildList {
            add(Triple("Давление", "${d.pressureMm.roundToInt()} мм", "${d.pressureHpa.roundToInt()} гПа"))
            if (today != null) add(Triple("УФ-индекс", "${today.uv.roundToInt()}", uvText(today.uv)))
            if (d.aqi != null) add(Triple("Воздух", "AQI ${d.aqi}", aqiText(d.aqi) + (d.pm25?.let { " · PM2.5 ${it.roundToInt()}" } ?: "")))
            d.visibilityM?.let { add(Triple("Видимость", "${(it / 1000).roundToInt()} км", "")) }
        }
        rest.chunked(2).forEach { row ->
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
private fun DailyCard(d: WeatherData, onDay: (LocalDate) -> Unit) {
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
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).clickable { onDay(day.date) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

// ---------------- советы, солнце, детали дня ----------------

/** Короткие советы на сегодня по прогнозу. */
fun tips(d: WeatherData): List<String> {
    val today = d.days.firstOrNull() ?: return emptyList()
    val rest = d.hours.take(14)
    val codes = rest.map { it.code } + d.code
    val out = ArrayList<String>()
    if (codes.any { it in setOf(96, 99) }) out += "Осторожно, возможен град"
    else if (codes.any { it == 95 }) out += "Гроза — держись подальше от деревьев и воды"
    if (today.rainProb >= 50 || rest.any { it.rainProb >= 60 }) out += "Не забудь зонт"
    if (codes.any { it in 71..77 || it in 85..86 }) out += "Снег — обувь по погоде"
    if (today.uv >= 6) out += "Сильное солнце — нужен крем от загара"
    if (d.gustsMs >= 14 || (today.windMax ?: 0.0) >= 12) out += "Сильный ветер — закрепи всё лёгкое"
    if (today.tMax >= 30) out += "Жарко — пей больше воды"
    if (today.tMin <= 0 && rest.any { it.precip != null && it.precip > 0 }) out += "Возможен гололёд"
    if (d.feels <= d.temp - 4) out += "Ощущается холоднее — одевайся теплее"
    if (codes.any { it == 45 || it == 48 }) out += "Туман — осторожно на дороге"
    return out.distinct().take(3)
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
        val drop = Path().apply {
            moveTo(w / 2, hh * 0.05f)
            cubicTo(w * 0.95f, hh * 0.5f, w * 0.85f, hh * 0.95f, w / 2, hh * 0.95f)
            cubicTo(w * 0.15f, hh * 0.95f, w * 0.05f, hh * 0.5f, w / 2, hh * 0.05f)
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
private fun SunCard(d: WeatherData) {
    val day = d.days.firstOrNull() ?: return
    val rise = runCatching { java.time.LocalTime.parse(day.sunrise) }.getOrNull() ?: return
    val set = runCatching { java.time.LocalTime.parse(day.sunset) }.getOrNull() ?: return
    val now = d.hours.firstOrNull()?.time?.toLocalTime() ?: java.time.LocalTime.now()
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
