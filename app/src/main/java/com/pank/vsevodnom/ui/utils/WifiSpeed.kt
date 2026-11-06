package com.pank.vsevodnom.ui.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ---------------- Wi-Fi ----------------

private fun channelOf(freq: Int) = when {
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq in 5000..5900 -> (freq - 5000) / 5
    freq in 5925..7125 -> (freq - 5950) / 5
    else -> 0
}

private fun bandOf(freq: Int) = when { freq < 3000 -> "2,4 ГГц"; freq < 5925 -> "5 ГГц"; else -> "6 ГГц" }

private fun securityOf(caps: String) = when {
    caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
    caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
    caps.contains("WPA") -> "WPA"
    caps.contains("WEP") -> "WEP (небезопасно)"
    else -> "Открытая"
}

private fun quality(rssi: Int) = when { rssi >= -55 -> "отличный"; rssi >= -67 -> "хороший"; rssi >= -75 -> "средний"; rssi >= -85 -> "слабый"; else -> "очень слабый" }

@SuppressLint("MissingPermission")
@Composable
fun WifiScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var info by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var band by remember { mutableIntStateOf(0) }
    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var has by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { has = granted() }

    fun readCurrent() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wi = caps?.transportInfo as? WifiInfo
        info = wi?.let {
            val ssid = it.ssid?.trim('"')?.takeIf { s -> s != "<unknown ssid>" } ?: "Wi-Fi"
            "$ssid · ${it.rssi} дБм (${quality(it.rssi)}) · ${bandOf(it.frequency)}, канал ${channelOf(it.frequency)} · ${it.linkSpeed} Мбит/с"
        }
    }
    fun scan() {
        if (!has) { launcher.launch(perms.toTypedArray()); return }
        @Suppress("DEPRECATION")
        val started = wm.startScan()
        msg = if (started) "Сканирую…" else "Android ограничивает сканирование (4 раза за 2 минуты). Показаны последние данные."
        results = wm.scanResults.orEmpty()
        readCurrent()
    }
    DisposableEffect(has) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (has) { results = wm.scanResults.orEmpty(); msg = ""; readCurrent() } }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (has) scan()
        onDispose { ctx.unregisterReceiver(r) }
    }

    val shown = results.filter { when (band) { 1 -> it.frequency < 3000; 2 -> it.frequency in 3000..5924; 3 -> it.frequency >= 5925; else -> true } }
        .sortedByDescending { it.level }

    ScreenScaffold("Wi-Fi сканер", onBack, actions = { RoundIconButton(Icons.Rounded.Refresh, "Сканировать", { scan() }) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!has) item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    Text("Нужен доступ к местоположению — так Android защищает список сетей вокруг.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp)); PillButton("Разрешить", { launcher.launch(perms.toTypedArray()) })
                }
            }
            if (!wm.isWifiEnabled) item { AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) { Text("Wi-Fi выключен", style = MaterialTheme.typography.titleSmall) } }
            info?.let { item { AppCard(Modifier.fillMaxWidth(), color = Pal.Sky) { SectionLabel("Подключено", Pal.SkyInk); Text(it, style = MaterialTheme.typography.bodyMedium) } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Все", "2,4", "5", "6").forEachIndexed { i, t -> Chip(t, band == i, { band = i }) }
                }
            }
            if (shown.isNotEmpty()) item { ChannelChart(shown, band) }
            if (msg.isNotBlank()) item { Text(msg, style = MaterialTheme.typography.bodySmall) }
            items(shown, key = { it.BSSID + it.frequency }) { r ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            @Suppress("DEPRECATION")
                            val name = (if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else r.SSID).orEmpty().ifBlank { "(скрытая сеть)" }
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("${bandOf(r.frequency)} · канал ${channelOf(r.frequency)} · ${securityOf(r.capabilities)}" +
                                if (Build.VERSION.SDK_INT >= 30 && r.wifiStandard >= 6) " · Wi-Fi ${r.wifiStandard - 2}" else "",
                                style = MaterialTheme.typography.bodySmall)
                            Text(r.BSSID, style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${r.level} дБм", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                            Text(quality(r.level), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        }
                    }
                }
            }
        }
    }
}

/** Загруженность каналов: «горки» сетей по частоте. */
@Composable
private fun ChannelChart(list: List<ScanResult>, band: Int) {
    val b = if (band == 0) (list.groupBy { it.frequency < 3000 }.maxByOrNull { it.value.size }?.key?.let { if (it) 1 else 2 } ?: 1) else band
    val nets = list.filter { when (b) { 1 -> it.frequency < 3000; 2 -> it.frequency in 3000..5924; else -> it.frequency >= 5925 } }
    if (nets.isEmpty()) return
    val (lo, hi) = when (b) { 1 -> 2400 to 2490; 2 -> 5150 to 5850; else -> 5925 to 7125 }
    AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Text("Каналы ${if (b == 1) "2,4" else if (b == 2) "5" else "6"} ГГц", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val colors = listOf(Pal.SkyInk, Pal.MintInk, Pal.PeachInk, Pal.LilacInk, Pal.RoseInk, Pal.ButterInk)
            nets.forEachIndexed { i, r ->
                val width = if (Build.VERSION.SDK_INT >= 23) when (r.channelWidth) { 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 80; 5 -> 320; else -> 20 } else 20
                val x0 = (r.frequency - width / 2f - lo) / (hi - lo) * size.width
                val x1 = (r.frequency + width / 2f - lo) / (hi - lo) * size.width
                val h = ((r.level + 100).coerceIn(0, 70) / 70f) * size.height
                drawArc(colors[i % 6].copy(alpha = 0.5f), 180f, 180f, false, Offset(x0, size.height - h), Size(x1 - x0, h * 2), style = Stroke(4f))
            }
        }
    }
}

// ---------------- Спидтест ----------------

private fun mbps(bytes: Long, ns: Long) = if (ns <= 0) 0.0 else bytes * 8 / (ns / 1e9) / 1e6

@Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("") }
    var ping by remember { mutableStateOf<Double?>(null) }
    var jitter by remember { mutableStateOf<Double?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var live by remember { mutableDoubleStateOf(0.0) }
    var running by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun run() = scope.launch {
        running = true; err = null; ping = null; jitter = null; down = null; up = null; live = 0.0
        try {
            withContext(Dispatchers.IO) {
                phase = "Пинг"
                val pings = (1..8).map {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val c = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
                    c.useCaches = false; c.connectTimeout = 8000; c.readTimeout = 8000
                    c.inputStream.use { it.readBytes() }; c.disconnect()
                    (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                }.drop(1).sorted()
                ping = pings[pings.size / 2]
                jitter = pings.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()

                phase = "Загрузка"
                var total = 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                val deadline = t0 + 10_000_000_000L
                while (SystemClock.elapsedRealtimeNanos() < deadline && isActive) {
                    val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
                    c.useCaches = false; c.connectTimeout = 8000; c.readTimeout = 10000
                    c.inputStream.use { s ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = s.read(buf); if (n < 0) break
                            total += n; live = mbps(total, SystemClock.elapsedRealtimeNanos() - t0)
                            if (SystemClock.elapsedRealtimeNanos() > deadline) break
                        }
                    }
                    c.disconnect()
                }
                down = mbps(total, SystemClock.elapsedRealtimeNanos() - t0)

                phase = "Отдача"; live = 0.0
                var sent = 0L
                val u0 = SystemClock.elapsedRealtimeNanos()
                val udeadline = u0 + 8_000_000_000L
                val chunk = ByteArray(256 * 1024)
                while (SystemClock.elapsedRealtimeNanos() < udeadline && isActive) {
                    val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                    c.doOutput = true; c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 10000
                    c.setFixedLengthStreamingMode(8 * chunk.size)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    c.outputStream.use { o: OutputStream ->
                        repeat(8) { o.write(chunk); sent += chunk.size; live = mbps(sent, SystemClock.elapsedRealtimeNanos() - u0) }
                    }
                    runCatching { c.inputStream.use { it.readBytes() } }; c.disconnect()
                }
                up = mbps(sent, SystemClock.elapsedRealtimeNanos() - u0)
                phase = "Готово"
            }
        } catch (e: Exception) {
            err = "Тест прерван: ${e.message ?: "нет связи"}"; phase = ""
        }
        running = false
    }

    fun f(v: Double?) = v?.let { String.format(Locale.US, if (it >= 100) "%.0f" else "%.1f", it).replace('.', ',') } ?: "—"

    ScreenScaffold("Спидтест", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Butter, radius = 28.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sw = 22.dp.toPx()
                            drawArc(Pal.Card, 135f, 270f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                            // шкала логарифмическая: 1…1000 Мбит/с
                            val frac = (kotlin.math.log10(live.coerceIn(1.0, 1000.0)) / 3).toFloat()
                            drawArc(Pal.ButterInk.copy(alpha = 0.7f), 135f, 270f * frac, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (running) f(live) else f(down), style = MaterialTheme.typography.displayMedium)
                            Text("Мбит/с", style = MaterialTheme.typography.bodySmall)
                            if (phase.isNotBlank()) Text(phase, style = MaterialTheme.typography.labelLarge, color = Pal.ButterInk)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Пинг" to "${f(ping)} мс", "Джиттер" to "${f(jitter)} мс", "Загрузка" to f(down), "Отдача" to f(up)).forEach { (k, v) ->
                    AppCard(Modifier.weight(1f), padding = 10.dp, radius = 18.dp) {
                        Text(k, style = MaterialTheme.typography.labelSmall); Text(v, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
            PillButton(if (running) "Идёт тест…" else "Начать", { if (!running) run() }, Modifier.fillMaxWidth(), enabled = !running)
            Text("Сервер: Cloudflare (ближайший узел). Тест расходует ~100–300 МБ трафика на быстром интернете.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
