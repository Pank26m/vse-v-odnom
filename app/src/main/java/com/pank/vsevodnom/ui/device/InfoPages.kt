package com.pank.vsevodnom.ui.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.device.*
import com.pank.vsevodnom.ui.common.AppTextField
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Обычный раздел: секции собираются в фоне и показываются карточками. */
@Composable
fun InfoPage(title: String, onBack: () -> Unit, tint: Tint, load: (Context) -> List<InfoSection>) {
    val ctx = LocalContext.current
    val data = rememberLoad(title) { load(ctx) }
    DevPage(title, onBack) { sections(data, tint) }
}

/** Разрешения, без которых раздел покажет не всё; просим один раз при входе. */
@Composable
fun AskPerms(vararg perms: String, onResult: () -> Unit = {}) {
    val ctx = LocalContext.current
    val need = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    LaunchedEffect(Unit) { if (need.isNotEmpty()) l.launch(need.toTypedArray()) }
}

// ---------------- процессор ----------------
@Composable
fun CpuPage(onBack: () -> Unit) {
    val cores = remember { DevCpu.cores() }
    val n = cores.size
    val series = remember { cores.map { Series("Ядро ${it.id}", DevColors.series[it.id % DevColors.series.size]) } }
    var cur by remember { mutableStateOf<List<Long?>>(emptyList()) }
    var mode by remember { mutableIntStateOf(0) }   // 0 МГц, 1 % от максимума
    LiveLoop(700, Unit) {
        val f = DevCpu.curFreqs(n); cur = f
        f.forEachIndexed { i, v -> series[i].push(((v ?: 0L) / 1000f)) }
    }
    val ctx = LocalContext.current
    val secs = rememberLoad(Unit) { DevReport.cpu(ctx) }
    var tis by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    if (tis) { BackHandler { tis = false }; TimeInStatePage { tis = false }; return }
    DevPage("Процессор", onBack) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Частоты ядер вживую", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("МГц", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                }
                LiveChart(series, height = 190.dp, yMax = (cores.mapNotNull { it.maxKhz }.maxOrNull() ?: 3_000_000) / 1000f, unit = "МГц")
                Legend(series.map { it.name to it.color })
            }
        }
        item {
            Card {
                cores.chunked(3).forEach { row ->
                    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            val v = cur.getOrNull(c.id)
                            val frac = v?.let { ((it - (c.minKhz ?: 0)).toFloat() / ((c.maxKhz ?: 1) - (c.minKhz ?: 0)).coerceAtLeast(1)) } ?: 0f
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(DevColors.series[c.id % 10].copy(alpha = 0.1f)).padding(8.dp)) {
                                Text("Ядро ${c.id}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                                Text(v?.let { "${it / 1000} МГц" } ?: "спит", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text(c.partName?.removePrefix("Cortex-") ?: "", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                                Bar(frac, DevColors.level(frac), Modifier.padding(top = 4.dp), 5.dp)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Загрузку в процентах Android приложениям не отдаёт с 8-й версии — по частоте видно, насколько процессор занят.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        sections(secs, Tints.Sky)
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Время на частотах", { tis = true }, Modifier.weight(1f), bg = Pal.Sky, fg = Pal.SkyInk)
            PillButton(if (raw) "Скрыть cpuinfo" else "Сырой cpuinfo", { raw = !raw }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        } }
        if (raw) item { Card { Text(DevCpu.cpuinfoRaw(), fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
    }
}

/** Сколько времени с загрузки каждый кластер провёл на каждой частоте. */
@Composable
fun TimeInStatePage(onBack: () -> Unit) {
    val data = rememberLoad(Unit) { DevCpu.timeInState() }
    DevPage("Время на частотах", onBack) {
        if (data == null) item { Loading() }
        else if (data.isEmpty()) item { Card { Text("Android не дал прочитать статистику частот (time_in_state) на этом телефоне.") } }
        else data.forEach { (name, list) ->
            item {
                Card {
                    Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    val total = list.sumOf { it.second }.coerceAtLeast(1)
                    list.filter { it.second > 0 }.forEach { (f, t) ->
                        val frac = t.toFloat() / total
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${f / 1000} МГц", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(86.dp))
                            Bar(frac, DevColors.series[0], Modifier.weight(1f), 7.dp)
                            Text("  ${Dev.fmt(frac * 100, 1)} %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                        }
                    }
                    Text("Всего ${Dev.dur(total / 1000)}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
        }
    }
}

// ---------------- графика ----------------
@Composable
fun GpuPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val secs = rememberLoad(Unit) { DevReport.gpu(ctx) }
    val freq = remember { Series("Частота", DevColors.series[4]) }
    val load = remember { Series("Загрузка", DevColors.series[3]) }
    var avail by remember { mutableStateOf<Boolean?>(null) }
    LiveLoop(1000, Unit) {
        val f = DevCpu.gpuFreqMhz(); val l = DevCpu.gpuLoad()
        avail = f != null || l != null
        f?.let { freq.push(it.toFloat()) }; l?.let { load.push(it.toFloat()) }
    }
    DevPage("Графика", onBack) {
        item {
            Card {
                Text("ГП вживую", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                when (avail) {
                    null -> Loading()
                    false -> Text("Этот телефон не даёт приложениям читать частоту и загрузку графического процессора — показываю всё, что отдаёт драйвер.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    true -> { if (freq.values.isNotEmpty()) { KV("Частота", "${freq.last?.toInt()} МГц"); LiveChart(listOf(freq), height = 110.dp, unit = "МГц") }
                        if (load.values.isNotEmpty()) { KV("Загрузка", "${load.last?.toInt()} %"); LiveChart(listOf(load), height = 90.dp, yMax = 100f, unit = "%") } }
                }
            }
        }
        sections(secs, Tints.Lilac, maxRows = 40)
    }
}

// ---------------- память ----------------
@Composable
fun MemoryPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val used = remember { Series("Занято", DevColors.series[3]) }
    var mem by remember { mutableStateOf<DevHardware.Mem?>(null) }
    var mi by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LiveLoop(1000, Unit) { val m = DevHardware.mem(ctx); mem = m; mi = DevHardware.meminfo(); used.push((m.total - m.avail) / 1_048_576f) }
    val secs = rememberLoad(Unit) { DevHardware.memory(ctx) }
    DevPage("Память", onBack) {
        item {
            val m = mem
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val frac = m?.let { (it.total - it.avail).toFloat() / it.total } ?: 0f
                    Ring(frac, 96.dp, DevColors.level(frac)) { Text("${(frac * 100).toInt()} %", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("ОЗУ ${m?.let { DevHardware.marketing(it.total) } ?: ""}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        m?.let { KV("Занято", Dev.bytes(it.total - it.avail)!!); KV("Свободно", Dev.bytes(it.avail)!!); KV("Всего", Dev.bytes(it.total)!!) }
                    }
                }
                if (mi.isNotEmpty() && m != null) {
                    val cached = (mi["Cached"] ?: 0) + (mi["Buffers"] ?: 0); val free = mi["MemFree"] ?: 0
                    val kernel = (mi["Slab"] ?: 0) + (mi["KernelStack"] ?: 0) + (mi["PageTables"] ?: 0)
                    val apps = (m.total - free - cached - kernel).coerceAtLeast(0)
                    Spacer(Modifier.height(10.dp))
                    StackBar(listOf(apps.toFloat() to DevColors.series[3], cached.toFloat() to DevColors.series[0], kernel.toFloat() to DevColors.series[4], free.toFloat() to DevColors.series[1]))
                    Spacer(Modifier.height(6.dp))
                    Legend(listOf("Приложения ${Dev.bytes(apps)}" to DevColors.series[3], "Кэш ${Dev.bytes(cached)}" to DevColors.series[0], "Ядро ${Dev.bytes(kernel)}" to DevColors.series[4], "Свободно ${Dev.bytes(free)}" to DevColors.series[1]))
                }
                Spacer(Modifier.height(8.dp))
                LiveChart(listOf(used), height = 110.dp, yMin = 0f, yMax = (m?.total ?: 1) / 1_048_576f, unit = "МБ")
            }
        }
        sections(secs, Tints.Butter)
    }
}

// ---------------- температура ----------------
@Composable
fun ThermalPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val batT = remember { Series("Батарея °C", DevColors.series[2]) }
    val room = remember { Series("Запас %", DevColors.series[1]) }
    var status by remember { mutableIntStateOf(0) }
    var zones by remember { mutableStateOf<List<DevHardware.Zone>>(emptyList()) }
    LiveLoop(1500, Unit) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batT.push((i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
        DevHardware.headroom(ctx)?.let { room.push(((1 - it) * 100).coerceIn(0f, 100f)) }
        status = DevHardware.thermalStatus(ctx); zones = DevHardware.thermalZones()
    }
    DevPage("Температура", onBack) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val r = (room.last ?: 100f) / 100f
                    Ring(1 - r, 92.dp, DevColors.level(1 - r)) { Text("${(room.last ?: 0f).toInt()} %", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(DevHardware.thermalName(status).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text("Запас до снижения частот (по данным Android)", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        KV("Батарея", "${Dev.fmt(batT.last ?: 0f, 1)} °C")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LiveChart(listOf(batT), height = 100.dp, yMin = 15f, yMax = 50f, unit = "°C")
                if (room.values.isNotEmpty()) LiveChart(listOf(room), height = 80.dp, yMax = 100f, unit = "%")
            }
        }
        item {
            if (zones.isEmpty()) Card { Text("Отдельные термодатчики (процессор, корпус, модем) Android на этом телефоне от приложений закрывает — видны только батарея и общий тепловой статус.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
            else SectionCard(InfoSection("Термозоны (${zones.size})", zones.map { InfoRow(it.name, "${Dev.fmt(it.celsius, 1)} °C") }), Tints.Rose)
        }
    }
}

// ---------------- сеть ----------------
@Composable
fun NetworkPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AskPerms(Manifest.permission.ACCESS_FINE_LOCATION)
    val down = remember { Series("↓ загрузка", DevColors.series[1]) }; val up = remember { Series("↑ отдача", DevColors.series[2]) }
    var lastRx by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }; var lastTx by remember { mutableLongStateOf(TrafficStats.getTotalTxBytes()) }
    var lastT by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var short by remember { mutableStateOf("") }
    LiveLoop(1000, Unit) {
        val now = SystemClock.elapsedRealtime(); val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val dt = (now - lastT).coerceAtLeast(1) / 1000f
        down.push((rx - lastRx) * 8 / 1e6f / dt); up.push((tx - lastTx) * 8 / 1e6f / dt); lastRx = rx; lastTx = tx; lastT = now
        short = DevConnect.shortNet(ctx)
    }
    var reload by remember { mutableIntStateOf(0) }
    val secs = rememberLoad(reload) { DevConnect.network(ctx) + DevConnect.wifiCaps(ctx) }
    var ip by remember { mutableStateOf<List<InfoRow>?>(null) }
    var ipBusy by remember { mutableStateOf(false) }
    DevPage("Сеть", onBack) {
        item {
            Card {
                Text(short, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                KV("↓ загрузка", "${Dev.fmt(down.last ?: 0f, 2)} Мбит/с", DevColors.series[1]); KV("↑ отдача", "${Dev.fmt(up.last ?: 0f, 2)} Мбит/с", DevColors.series[2])
                LiveChart(listOf(down, up), height = 130.dp, unit = "Мбит/с"); Legend(listOf(down.name to down.color, up.name to up.color))
                Text("Трафик всего с включения: ↓ ${Dev.bytes(TrafficStats.getTotalRxBytes())} · ↑ ${Dev.bytes(TrafficStats.getTotalTxBytes())}; мобильный ↓ ${Dev.bytes(TrafficStats.getMobileRxBytes())}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Внешний IP и провайдер", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    TextButton({ ipBusy = true }, enabled = !ipBusy) { Text(if (ip == null) "Узнать" else "Обновить") }
                }
                if (ipBusy) LaunchedEffect(Unit) { ip = withContext(Dispatchers.IO) { NetTools.publicIp() }; ipBusy = false }
                ip?.forEach { KV(it.key, it.value) }
                Text("Запрос к ipwho.is — только по нажатию.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        sections(secs, Tints.Sky)
    }
}

// ---------------- связь ----------------
@Composable
fun ConnectPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    AskPerms(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT) { reload++ }
    val sig = remember { Series("Сигнал, дБм", DevColors.series[0]) }
    var sigText by remember { mutableStateOf("") }
    LiveLoop(2000, Unit) {
        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.let { s -> sig.push(s.dbm.toFloat()); sigText = "${s.dbm} дБм · уровень ${s.level} из 4 · ${DevConnect.netType(tm.dataNetworkType)}" }
        }
    }
    val secs = rememberLoad(reload) { DevConnect.telephony(ctx) + DevConnect.bluetooth(ctx) + DevConnect.nfcUwb(ctx) + DevConnect.gnss(ctx) }
    DevPage("Связь", onBack) {
        if (sig.values.isNotEmpty()) item {
            Card {
                Text("Мобильный сигнал вживую", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(sigText, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                LiveChart(listOf(sig), height = 110.dp, yMin = -130f, yMax = -50f, unit = "дБм")
                Text("−50…−85 — отлично, −85…−105 — нормально, ниже −110 — слабо.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        sections(secs, Tints.Lilac)
    }
}

// ---------------- камеры ----------------
@Composable
fun CameraPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cams = rememberLoad(Unit) { DevMedia.cameras(ctx) }
    var sel by remember { mutableIntStateOf(0) }
    DevPage("Камеры", onBack) {
        if (cams == null) { item { Loading() }; return@DevPage }
        if (cams.isEmpty()) { item { Card { Text("Камеры не найдены") } }; return@DevPage }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cams.forEachIndexed { i, c -> Chip(c.title + (c.mp?.let { " · ${it.toInt()} Мп" } ?: ""), sel == i, { sel = i }) }
            }
        }
        val c = cams[sel.coerceIn(0, cams.lastIndex)]
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Peach).padding(16.dp)) {
                Text(c.title, style = MaterialTheme.typography.labelLarge, color = Pal.PeachInk)
                Text(c.headline, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                Spacer(Modifier.height(8.dp)); TagChips(c.tags, Tints.Peach)
            }
        }
        sections(c.sections, Tints.Peach)
        item { Text("Android может отдавать несколько камер одной стороны как одну «логическую» — её модули показаны отдельно. Если мегапикселей меньше, чем в рекламе, телефон снимает с бинингом (4 пикселя в 1), а полное разрешение указано в «Максимальное».", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
    }
}

// ---------------- датчики ----------------
@Composable
fun SensorsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember { DevMedia.sensors(ctx) }
    var open by remember { mutableStateOf<Sensor?>(null) }
    open?.let { s -> BackHandler { open = null }; SensorLive(s) { open = null }; return }
    val std = list.filter { DevMedia.isStandard(it) }; val priv = list.filterNot { DevMedia.isStandard(it) }
    DevPage("Датчики", onBack) {
        item { Card { Text("${list.size} датчиков", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            Text("${std.size} стандартных · ${priv.size} частных (Google и производителя) · ${list.count { it.isWakeUpSensor }} будят телефон", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) } }
        item { GroupTitle("Стандартные — нажми, чтобы увидеть вживую") }
        items(std, key = { "s" + it.name + it.type + it.id }) { s -> SensorRow(s) { open = s } }
        if (priv.isNotEmpty()) { item { GroupTitle("Частные") }; items(priv, key = { "p" + it.name + it.type + it.id }) { s -> SensorRow(s) { open = s } } }
    }
}

@Composable
private fun SensorRow(s: Sensor, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Butter), contentAlignment = Alignment.Center) {
            Icon(when (s.type) { Sensor.TYPE_LIGHT -> Icons.Rounded.LightMode; Sensor.TYPE_PROXIMITY -> Icons.Rounded.Sensors; Sensor.TYPE_PRESSURE -> Icons.Rounded.Speed
                Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> Icons.Rounded.Explore; Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> Icons.Rounded.DirectionsWalk
                Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> Icons.Rounded.Cached; else -> if (DevMedia.isStandard(s)) Icons.Rounded.Vibration else Icons.Rounded.Lock },
                null, tint = Pal.ButterInk, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(DevMedia.sensorType(s), style = MaterialTheme.typography.titleSmall)
            Text("${s.name} · ${s.vendor}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
        }
        if (s.isWakeUpSensor) Text("будит", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
    }
}

/** Датчик вживую: значения осей, график, сведения. */
@Composable
fun SensorLive(s: Sensor, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val axes = remember { mutableStateListOf<Series>() }
    var values by remember { mutableStateOf(FloatArray(0)) }
    var events by remember { mutableIntStateOf(0) }
    var rate by remember { mutableFloatStateOf(0f) }
    DisposableEffect(s) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        var t0 = SystemClock.elapsedRealtime(); var cnt = 0; var lastPush = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                events++; cnt++
                val now = SystemClock.elapsedRealtime()
                if (now - t0 >= 1000) { rate = cnt * 1000f / (now - t0); cnt = 0; t0 = now }
                values = e.values.copyOf()
                if (axes.isEmpty()) e.values.take(3).forEachIndexed { i, _ -> axes += Series(listOf("X", "Y", "Z")[i], DevColors.series[i]) }
                if (now - lastPush >= 60 || s.reportingMode != Sensor.REPORTING_MODE_CONTINUOUS) { lastPush = now; e.values.take(3).forEachIndexed { i, v -> axes.getOrNull(i)?.push(v) } }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) sm.requestTriggerSensor(object : android.hardware.TriggerEventListener() {
            override fun onTrigger(event: android.hardware.TriggerEvent) { events++; values = event.values.copyOf() }
        }, s) else sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    val unit = DevMedia.sensorUnit(s.type)
    DevPage(DevMedia.sensorType(s), onBack) {
        item {
            Card {
                Text(s.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                if (events == 0) Text(if (s.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS) "Жду данные…" else "Событий пока не было — этот датчик срабатывает на действие (движение, жест, касание).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                else {
                    values.take(6).forEachIndexed { i, v -> KV(if (values.size >= 3 && i < 3) listOf("X", "Y", "Z")[i] else "Значение ${i + 1}", "${Dev.fmt(v, 3)} $unit", DevColors.series.getOrElse(i) { Pal.Ink }) }
                    if (s.type == Sensor.TYPE_ACCELEROMETER || s.type == Sensor.TYPE_GRAVITY) KV("Модуль", "${Dev.fmt(kotlin.math.sqrt(values.take(3).sumOf { (it * it).toDouble() }).toFloat(), 3)} $unit")
                    if (s.type == Sensor.TYPE_MAGNETIC_FIELD) KV("Сила поля", "${Dev.fmt(kotlin.math.sqrt(values.take(3).sumOf { (it * it).toDouble() }).toFloat(), 1)} мкТл")
                    KV("Событий", "$events · ${Dev.fmt(rate, 0)} в секунду")
                    if (axes.isNotEmpty()) { LiveChart(axes.toList(), height = 160.dp, yMin = axes.flatMap { it.values }.minOrNull()?.let { minOf(it, 0f) } ?: 0f, unit = unit); if (axes.size > 1) Legend(axes.map { it.name to it.color }) }
                }
            }
        }
        item { SectionCard(InfoSection("Сведения", DevMedia.sensorRows(s)), Tints.Butter) }
    }
}

// ---------------- кодеки ----------------
@Composable
fun CodecsPage(onBack: () -> Unit) {
    val all = rememberLoad(Unit) { DevMedia.codecs() }
    var kind by remember { mutableIntStateOf(0) }   // 0 все, 1 видео, 2 аудио
    var dir by remember { mutableIntStateOf(0) }    // 0 все, 1 декодеры, 2 кодеры
    var hw by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<DevMedia.Codec?>(null) }
    open?.let { c -> BackHandler { open = null }; val d = remember(c) { DevMedia.codecDetails(c) }; DevPage(c.name, { open = null }) { sections(d, Tints.Sky) }; return }
    DevPage("Кодеки", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(q, { q = it }, "Поиск: avc, hevc, av01, aac, opus…")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Все", kind == 0, { kind = 0 }); Chip("Видео", kind == 1, { kind = 1 }); Chip("Аудио", kind == 2, { kind = 2 })
                    Chip("Декодеры", dir == 1, { dir = if (dir == 1) 0 else 1 }); Chip("Кодеры", dir == 2, { dir = if (dir == 2) 0 else 2 }); Chip("Аппаратные", hw, { hw = !hw })
                }
            }
        }
        val l = all?.filter { c -> (kind == 0 || (kind == 1) != c.audio) && (dir == 0 || (dir == 2) == c.encoder) && (!hw || c.hardware) &&
            (q.isBlank() || c.name.contains(q, true) || c.types.any { it.contains(q, true) }) }
        if (l == null) item { Loading() } else {
            item { Text("${l.size} из ${all.size}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted) }
            items(l, key = { it.name + it.types.joinToString() }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { open = c }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (c.audio) Icons.Rounded.MusicNote else Icons.Rounded.Movie, null, tint = if (c.hardware) DevColors.good else Pal.Muted)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(c.types.joinToString(", ") + " · " + (if (c.encoder) "кодер" else "декодер") + (if (c.hardware) " · аппаратный" else " · программный"), style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 2)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
                }
            }
        }
    }
}

// ---------------- разделы ----------------
@Composable
fun MountsPage(onBack: () -> Unit) {
    val list = rememberLoad(Unit) { DevHardware.mounts() }
    DevPage("Разделы", onBack) {
        if (list == null) { item { Loading() }; return@DevPage }
        val sorted = list.sortedWith(compareBy({ it.total == null }, { it.point }))
        items(sorted, key = { it.point }) { m ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.point, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TagChips(listOf(m.fs, if (m.rw) "запись" else "только чтение"), if (m.rw) Tints.Mint else Tints.Lilac)
                }
                Text(m.dev, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                m.total?.let { t -> val used = t - (m.avail ?: 0); val f = used.toFloat() / t
                    Spacer(Modifier.height(6.dp)); Bar(f, DevColors.level(f), height = 6.dp)
                    Text("${Dev.bytes(used)} из ${Dev.bytes(t)} · ${(f * 100).toInt()} %", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
            }
        }
    }
}

// ---------------- функции и свойства ----------------
@Composable
fun FeaturesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    val feats = rememberLoad(Unit) { DevSystem.features(ctx) }
    val props = rememberLoad(Unit) { Dev.props() }
    DevPage("Функции и свойства", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.pank.vsevodnom.ui.common.Segmented(listOf("Функции", "Библиотеки", "getprop"), tab, { tab = it })
                AppTextField(q, { q = it }, "Поиск")
            }
        }
        when (tab) {
            0, 1 -> feats?.getOrNull(tab)?.let { s -> item { SectionCard(s.copy(rows = s.rows.filter { q.isBlank() || it.key.contains(q, true) }), Tints.Lilac) } } ?: item { Loading() }
            else -> props?.let { p -> item { SectionCard(InfoSection("Свойства системы (${p.size})", p.filter { q.isBlank() || it.key.contains(q, true) || it.value.contains(q, true) }.map { InfoRow(it.key, it.value) }), Tints.Lilac) }
                item { Text("Только то, что Android разрешает прочитать приложению; закрытые свойства не видны.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) } } ?: item { Loading() }
        }
    }
}
