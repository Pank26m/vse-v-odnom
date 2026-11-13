package com.pank.vsevodnom.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.device.Dev
import com.pank.vsevodnom.data.device.DevConnect
import com.pank.vsevodnom.data.device.InfoRow
import com.pank.vsevodnom.data.device.InfoSection
import com.pank.vsevodnom.data.device.NetTools
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.common.Segmented
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Состояние сканирования: последние результаты и сигнал каждой сети во времени. */
private class WifiState {
    var results by mutableStateOf<List<ScanResult>>(emptyList())
    var stamp by mutableLongStateOf(0L)
    val history = mutableStateMapOf<String, Series>()
    var connected by mutableStateOf<String?>(null)   // BSSID
    var paused by mutableStateOf(false)
}

private fun ScanResult.name() = (if (Build.VERSION.SDK_INT >= 33) wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") SSID)?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "(скрытая сеть)"
private fun ScanResult.bandIdx() = when { frequency < 3000 -> 0; frequency < 5925 -> 1; else -> 2 }
private fun ScanResult.width() = DevConnect.widthMhz(channelWidth)
private fun ScanResult.center() = if (channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ && centerFreq0 > 0) centerFreq0 else frequency
private fun ScanResult.std() = if (Build.VERSION.SDK_INT >= 30) when (wifiStandard) { 4 -> "Wi-Fi 4"; 5 -> "Wi-Fi 5"; 6 -> "Wi-Fi 6"; 8 -> "Wi-Fi 7"; 1 -> "Wi-Fi"; else -> null } else null

/** Оценка канала 0–100: чем меньше сильных соседей перекрывают его 20 МГц, тем лучше. */
private fun channelScore(ch: Int, band: Int, list: List<ScanResult>, exclude: String? = null): Pair<Int, Int> {
    val f = when (band) { 0 -> 2407 + ch * 5; 1 -> 5000 + ch * 5; else -> 5950 + ch * 5 }
    var load = 0.0; var n = 0
    list.filter { it.bandIdx() == band && it.BSSID != exclude }.forEach { r ->
        val lo = r.center() - r.width() / 2; val hi = r.center() + r.width() / 2
        val ov = (minOf(hi, f + 10) - maxOf(lo, f - 10)).coerceAtLeast(0) / 20.0
        if (ov > 0) { n++; load += ov * (((r.level + 100) / 60.0).coerceIn(0.05, 1.2)).let { it * it } * 45 }
    }
    return (100 - load).toInt().coerceIn(0, 100) to n
}
private fun scoreText(s: Int) = when { s >= 90 -> "Превосходно"; s >= 75 -> "Очень хорошо"; s >= 55 -> "Хорошо"; s >= 35 -> "Занят"; else -> "Перегружен" }
private fun scoreColor(s: Int) = when { s >= 75 -> DevColors.good; s >= 45 -> DevColors.warn; else -> DevColors.bad }
private val CH24 = (1..13).toList()
private val CH5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
private val CH6 = (1..233 step 16).toList()

@SuppressLint("MissingPermission")
@Composable
fun WifiAnalyzer(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AskPerms(Manifest.permission.ACCESS_FINE_LOCATION, *(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyArray()))
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    val st = remember { WifiState() }
    var band by remember { mutableIntStateOf(1) }
    var tab by remember { mutableIntStateOf(0) }
    fun update() {
        val r = runCatching { wm.scanResults }.getOrNull().orEmpty()
        st.results = r; st.stamp = System.currentTimeMillis()
        @Suppress("DEPRECATION") st.connected = wm.connectionInfo?.bssid
        r.forEachIndexed { i, s -> st.history.getOrPut(s.BSSID) { Series(s.name(), DevColors.series[st.history.size % DevColors.series.size], 120) }.push(s.level.toFloat()) }
    }
    DisposableEffect(Unit) {
        val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (!st.paused) update() } }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    LaunchedEffect(Unit) { update(); while (true) { if (!st.paused) { @Suppress("DEPRECATION") wm.startScan(); update() }; delay(8000) } }
    // стартовый диапазон — тот, к которому подключены
    LaunchedEffect(st.connected) { st.results.firstOrNull { it.BSSID == st.connected }?.let { band = it.bandIdx() } }
    ScreenScaffold("Wi-Fi анализатор", onBack, actions = {
        RoundIconButton(if (st.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (st.paused) "Продолжить" else "Пауза", { st.paused = !st.paused })
    }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Segmented(listOf("2,4 ГГц", "5 ГГц", "6 ГГц"), band, { band = it })
            val n = st.results.count { it.bandIdx() == band }
            Text("$n сетей в диапазоне · обновлено ${if (st.stamp > 0) ((System.currentTimeMillis() - st.stamp) / 1000).toString() + " с назад" else "…"}" + if (!wm.isWifiEnabled) " · Wi-Fi выключен" else "",
                style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(vertical = 6.dp))
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) { 0 -> channelsTab(st, band); 1 -> timeTab(st, band); 2 -> bestTab(st, band); 3 -> apsTab(st, band); 4 -> devicesTab(); else -> toolsTab(st) }
            }
        }
        Row(Modifier.fillMaxWidth().background(Pal.Card).padding(4.dp)) {
            listOf(Triple("Каналы", Icons.Rounded.BarChart, 0), Triple("Время", Icons.Rounded.ShowChart, 1), Triple("Лучшие", Icons.Rounded.AutoAwesome, 2),
                Triple("Точки", Icons.Rounded.Router, 3), Triple("Устр-ва", Icons.Rounded.Devices, 4), Triple("Ещё", Icons.Rounded.Handyman, 5)).forEach { (t, ic, i) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { tab = i }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, t, tint = if (tab == i) Pal.SkyInk else Pal.Muted); Text(t, style = MaterialTheme.typography.labelSmall, color = if (tab == i) Pal.Ink else Pal.Muted, maxLines = 1)
                }
            }
        }
    }
}

// ---------------- каналы ----------------
private fun LazyListScope.channelsTab(st: WifiState, band: Int) {
    item {
        val list = st.results.filter { it.bandIdx() == band }.sortedBy { it.level }
        val chs = when (band) { 0 -> CH24; 1 -> CH5; else -> CH6 }
        val fMin = when (band) { 0 -> 2400; 1 -> 5160; else -> 5945 }; val fMax = when (band) { 0 -> 2495; 1 -> 5845; else -> 7125 }
        Card {
            Canvas(Modifier.fillMaxWidth().height(380.dp)) {
                val left = 54f; val right = size.width - 8f; val top = 10f; val bottom = size.height - 34f
                fun X(f: Int) = left + (f - fMin).toFloat() / (fMax - fMin) * (right - left)
                fun Y(db: Int) = top + (-20 - db) / 80f * (bottom - top)
                val p = android.graphics.Paint().apply { color = Pal.Muted.toArgb(); textSize = 24f; isAntiAlias = true }
                for (db in -20 downTo -100 step 10) { drawLine(Pal.Line, Offset(left, Y(db)), Offset(right, Y(db)), 2f); drawContext.canvas.nativeCanvas.drawText("$db", 4f, Y(db) + 8, p) }
                chs.forEach { c -> val f = when (band) { 0 -> 2407 + c * 5; 1 -> 5000 + c * 5; else -> 5950 + c * 5 }; val x = X(f)
                    drawLine(Pal.Line.copy(alpha = 0.6f), Offset(x, top), Offset(x, bottom), 1.5f)
                    if (band != 1 || c in listOf(36, 52, 100, 116, 132, 149, 165)) drawContext.canvas.nativeCanvas.drawText("$c", x - 10, bottom + 26, p) }
                list.forEachIndexed { i, r ->
                    val col = DevColors.series[(r.BSSID.hashCode() and 0x7fffffff) % DevColors.series.size]
                    val mine = r.BSSID == st.connected
                    val x0 = X(r.center() - r.width() / 2); val x1 = X(r.center() + r.width() / 2); val y = Y(r.level.coerceAtLeast(-99))
                    val path = Path().apply { moveTo(x0, bottom); cubicTo(x0 + (x1 - x0) * 0.12f, y, x1 - (x1 - x0) * 0.12f, y, x1, bottom) }
                    drawPath(path, col.copy(alpha = 0.16f))
                    drawPath(path, col, style = Stroke(if (mine) 6f else 3f))
                    val lp = android.graphics.Paint().apply { color = col.toArgb(); textSize = if (mine) 26f else 22f; isAntiAlias = true; isFakeBoldText = mine; textAlign = android.graphics.Paint.Align.CENTER }
                    drawContext.canvas.nativeCanvas.drawText(r.name().take(16) + (if (mine) " ★" else "") + " ~${NetTools.distanceM(r.level, r.frequency).toInt()}м", (x0 + x1) / 2, y + 0.75f * (bottom - y) * 0f - 8, lp)
                }
            }
            Text("Высота купола — сила сигнала (дБм), ширина — полоса канала. ★ — твоя сеть. Расстояние примерное: стены его сильно искажают.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
}

// ---------------- время ----------------
private fun LazyListScope.timeTab(st: WifiState, band: Int) {
    item {
        val ids = st.results.filter { it.bandIdx() == band }.sortedByDescending { it.level }.take(8).map { it.BSSID }
        val ser = ids.mapNotNull { st.history[it] }
        Card {
            Text("Сигнал сетей во времени", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            if (ser.isEmpty()) Text("Жду результаты сканирования…", color = Pal.Muted) else {
                LiveChart(ser, height = 260.dp, yMin = -100f, yMax = -20f, unit = "дБм")
                Legend(ser.map { it.name to it.color })
            }
            Text("Android даёт сканировать Wi-Fi не чаще 4 раз за 2 минуты — точки появляются ступеньками. Чаще — если отключить «Ограничение сканирования Wi-Fi» в параметрах разработчика.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
}

// ---------------- лучшие каналы ----------------
private fun LazyListScope.bestTab(st: WifiState, band: Int) {
    val chs = when (band) { 0 -> CH24; 1 -> CH5; else -> CH6 }
    val scores = chs.map { c -> c to channelScore(c, band, st.results) }
    val good = if (band == 0) scores.filter { it.first in listOf(1, 6, 11) } else scores
    val best = good.maxByOrNull { it.second.first }
    item {
        best?.let { (c, s) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Ring(s.first / 100f, 90.dp, scoreColor(s.first)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$c", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)); Text("канал", style = MaterialTheme.typography.labelSmall) } }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Лучший канал ${listOf("2,4", "5", "6")[band]} ГГц", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                    Text("Канал $c", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
                    Text("${scoreText(s.first)} · ${s.first} %", color = scoreColor(s.first), style = MaterialTheme.typography.titleSmall)
                    Text(if (s.second == 0) "Свободный — без перекрытий" else "Перекрывается с ${s.second} сет.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
        }
    }
    val mine = st.results.firstOrNull { it.BSSID == st.connected }
    if (mine != null && mine.bandIdx() == band) item {
        val c = DevConnect.channelOf(mine.frequency); val s = channelScore(c, band, st.results, mine.BSSID)
        Card {
            Text("ТЫ ПОДКЛЮЧЁН ЗДЕСЬ", style = MaterialTheme.typography.labelMedium, color = DevColors.good)
            Text(mine.name(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text("Канал $c · ${scoreText(s.first)} ${s.first} %", style = MaterialTheme.typography.bodyMedium)
            if (best != null && best.first != c && best.second.first - s.first >= 10) Text("Переход на канал ${best.first} может снизить помехи — меняется в настройках роутера.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            else Text("Твой канал — один из лучших, менять не нужно.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
    item { GroupTitle("Все каналы") }
    items(scores, key = { "ch${it.first}" }) { (c, s) ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (c == best?.first) Pal.Butter else Pal.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$c", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Bar(s.first / 100f, scoreColor(s.first), height = 7.dp)
                Text("${scoreText(s.first)} · " + if (s.second == 0) "свободно" else "${s.second} сет.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
            Text("  ${s.first} %", style = MaterialTheme.typography.titleSmall, color = scoreColor(s.first))
        }
    }
}

// ---------------- точки доступа ----------------
private fun LazyListScope.apsTab(st: WifiState, band: Int) {
    item {
        var sort by remember { mutableIntStateOf(0) }
        val ctx = LocalContext.current
        val list = st.results.filter { it.bandIdx() == band }.let { l -> when (sort) { 1 -> l.sortedBy { it.name().lowercase() }; 2 -> l.sortedBy { it.frequency }; else -> l.sortedByDescending { it.level } } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("по силе", sort == 0, { sort = 0 }); Chip("по имени", sort == 1, { sort = 1 }); Chip("по каналу", sort == 2, { sort = 2 }) }
            if (list.isEmpty()) Card { Text("Сетей не видно. Нужен включённый Wi-Fi и доступ к местоположению.", style = MaterialTheme.typography.bodySmall) }
            list.forEach { r -> ApCard(r, r.BSSID == st.connected, NetTools.vendor(ctx, r.BSSID)) }
        }
    }
}

@Composable
private fun ApCard(r: ScanResult, mine: Boolean, vendor: String?) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (mine) Pal.Mint else Pal.Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mine) Icon(Icons.Rounded.Wifi, null, tint = Pal.MintInk, modifier = Modifier.size(18.dp))
                Text(" " + r.name(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                r.std()?.let { Text("  $it", style = MaterialTheme.typography.labelSmall, color = Pal.SkyInk) }
            }
            Text("${r.BSSID} · ~${NetTools.distanceM(r.level, r.frequency).toInt()} м", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Text("${r.frequency} МГц (${r.width()} МГц) · канал ${DevConnect.channelOf(r.frequency)}" + if (r.center() != r.frequency) " (центр ${DevConnect.channelOf(r.center())})" else "", style = MaterialTheme.typography.bodySmall)
            Text(DevConnect.security(r.capabilities) + (vendor?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            if (mine) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ NetTools.gateway(ctx)?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$it"))) } } }) { Text("Роутер") }
            }
        }
        Gauge(r.level)
    }
}

/** Круглый стрелочный индикатор дБм. */
@Composable
private fun Gauge(db: Int) {
    val frac = ((db + 100) / 70f).coerceIn(0f, 1f)
    Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Pal.Line, 135f, 270f, false, style = Stroke(8f, cap = StrokeCap.Round))
            drawArc(DevColors.level(1 - frac), 135f, 270f * frac, false, style = Stroke(8f, cap = StrokeCap.Round))
            val a = Math.toRadians(135.0 + 270 * frac); val r = size.minDimension / 2 - 14
            drawLine(Pal.Ink, center, center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()), 4f, StrokeCap.Round)
            drawCircle(Pal.Ink, 5f, center)
        }
        Text("$db", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.offset(y = 20.dp))
    }
}

// ---------------- устройства в сети ----------------
private fun LazyListScope.devicesTab() {
    item { LanScan() }
}

@Composable
fun LanScan() {
    val ctx = LocalContext.current
    var hosts by remember { mutableStateOf<List<NetTools.Host>?>(null) }
    var prog by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) { if (run == 0) return@LaunchedEffect; hosts = null; hosts = NetTools.scanLan(ctx) { a, b -> prog = a to b }; prog = null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(hosts?.let { "${it.size} устройств в сети" } ?: "Кто в моей сети", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                    Text("Перебор адресов + имена служб (mDNS). Без лимитов.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                PillButton(if (run == 0) "Найти" else "Обновить", { run++ }, enabled = prog == null)
            }
            prog?.let { (a, b) -> Spacer(Modifier.height(8.dp)); Bar(a / b.toFloat(), DevColors.series[0]); Text("Проверено $a из $b", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
        }
        hosts?.forEach { h ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (h.router) Pal.Butter else Pal.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Pal.Sky), contentAlignment = Alignment.Center) {
                    Icon(when { h.router -> Icons.Rounded.Router; h.me -> Icons.Rounded.Smartphone; h.kind.contains("ТВ") -> Icons.Rounded.Tv; h.kind.contains("принтер") -> Icons.Rounded.Print
                        h.kind.contains("камера") -> Icons.Rounded.Videocam; h.kind.contains("компьютер") || h.kind.contains("ПК") -> Icons.Rounded.Computer; h.kind.contains("Apple") -> Icons.Rounded.PhoneIphone
                        h.kind.contains("умный") -> Icons.Rounded.Lightbulb; else -> Icons.Rounded.DevicesOther }, null, tint = Pal.SkyInk)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(h.name ?: h.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                    Text(h.ip + (h.ms?.let { " · ${Dev.fmt(it, 0)} мс" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Pal.SkyInk)
                    if (h.ports.isNotEmpty() || h.services.isNotEmpty()) Text((h.services.filter { it.startsWith("_") }.map { it.removePrefix("_").substringBefore("._") } + h.ports.map { "порт $it" }).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 2)
                }
                Text(h.kind, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        Text("Android 10+ не показывает приложениям MAC-адреса чужих устройств, поэтому производитель известен только по именам служб. Устройства, которые не отвечают на запросы (спят), могут не найтись.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
    }
}

// ---------------- инструменты ----------------
private fun LazyListScope.toolsTab(st: WifiState) {
    item { SpeedTestCard() }
    item { DiagnoseCard() }
    item { SignalTracker(st) }
    item {
        val ctx = LocalContext.current
        Card {
            Text("Настройки роутера", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text("Блокировка устройств, смена пароля и канала делаются в веб-панели роутера — у каждого производителя своя, и сторонним приложениям она закрыта. Открыть панель:", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            val gw = NetTools.gateway(ctx)
            PillButton(gw?.let { "Открыть http://$it" } ?: "Нет подключения к Wi-Fi", { gw?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$it"))) } } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = gw != null)
        }
    }
}

@Composable
fun SpeedTestCard() {
    var stage by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<Double?>(null) }
    var res by remember { mutableStateOf<NetTools.Speed?>(null) }
    val speed = remember { Series("Скорость", DevColors.series[0], 120) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тест скорости интернета", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text("Сервер speed.cloudflare.com · ~100–300 МБ трафика", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
            PillButton(if (stage == null) "Старт" else "…", {
                if (stage != null) return@PillButton
                speed.values.clear(); res = null; stage = "Старт…"
                AppScope.io.launch { val r = NetTools.speedTest { s, v -> stage = s; live = v; v?.let { speed.push(it.toFloat()) } }; res = r; stage = null; live = null }
            }, enabled = stage == null)
        }
        stage?.let { Text("$it ${live?.let { v -> "${Dev.fmt(v, 1)} Мбит/с" } ?: ""}", style = MaterialTheme.typography.titleMedium, color = Pal.SkyInk) }
        if (speed.values.size > 1) LiveChart(listOf(speed), height = 100.dp, unit = "Мбит/с")
        res?.let { r ->
            Row(Modifier.padding(top = 6.dp)) {
                listOf("Загрузка" to r.downMbps?.let { "${Dev.fmt(it, 1)} Мбит/с" }, "Отдача" to r.upMbps?.let { "${Dev.fmt(it, 1)} Мбит/с" }, "Пинг" to r.pingMs?.let { "${it.toInt()} мс" }).forEach { (t, v) ->
                    Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.labelSmall, color = Pal.Muted); Text(v ?: "ошибка", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                }
            }
            r.jitterMs?.let { Text("Джиттер ${Dev.fmt(it, 1)} мс", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
        }
    }
}

@Composable
private fun DiagnoseCard() {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf<String?>(null) }
    var res by remember { mutableStateOf<Pair<List<InfoRow>, String>?>(null) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Диагностика сети", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)); Text("Роутер, DNS, интернет, HTTPS — с советом", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
            PillButton(if (step == null) "Проверить" else "…", { if (step == null) { step = "…"; res = null; AppScope.io.launch { res = NetTools.diagnose(ctx) { step = it }; step = null } } }, enabled = step == null)
        }
        step?.let { Text(it, color = Pal.Muted) }
        res?.let { (rows, advice) -> rows.forEach { r -> KV(r.key, r.value, if (r.ok == false) DevColors.bad else if (r.ok == true) DevColors.good else Pal.Ink) }
            Text(advice, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** Трекер сигнала: выбери сеть и ходи с телефоном — стрелка и писк чаще, когда ближе к точке. */
@Composable
private fun SignalTracker(st: WifiState) {
    var sel by remember { mutableStateOf<String?>(null) }
    var beep by remember { mutableStateOf(false) }
    val r = st.results.firstOrNull { it.BSSID == sel }
    LaunchedEffect(beep, sel) {
        if (!beep) return@LaunchedEffect
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        try { while (beep) { val lvl = st.results.firstOrNull { it.BSSID == sel }?.level ?: -100
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 60); delay((((lvl + 100) / 70f).coerceIn(0f, 1f)).let { 1400 - it * 1250 }.toLong()) } } finally { tg.release() }
    }
    Card {
        Text("Трекер сигнала", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        Text("Найти точку доступа: выбери сеть и ходи — чем ближе, тем выше стрелка и чаще писк.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            st.results.sortedByDescending { it.level }.take(12).forEach { s -> Chip(s.name().take(18), sel == s.BSSID, { sel = s.BSSID }) }
        }
        if (r != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Gauge(r.level)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(r.name(), style = MaterialTheme.typography.titleSmall); Text("~${NetTools.distanceM(r.level, r.frequency).toInt()} м · ${r.level} дБм", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                Switch(beep, { beep = it })
            }
            st.history[r.BSSID]?.let { LiveChart(listOf(it), height = 90.dp, yMin = -100f, yMax = -20f, unit = "дБм") }
        }
    }
}
