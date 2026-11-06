package com.pank.vsevodnom.ui.health

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.utils.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HR_MEASURE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Пульсометр по стандартному Bluetooth-профилю Heart Rate (0x180D).
 * Mi Band 8 отдаёт пульс так, только если на браслете включена «Трансляция пульса»
 * (Настройки → Пульс → Трансляция / Heart rate broadcast) и идёт тренировка или включён режим.
 */
@SuppressLint("MissingPermission")
class HrMonitor(private val ctx: Context) {
    var bpm by mutableIntStateOf(0)
    var state by mutableStateOf("Не подключено")
    val found = mutableStateListOf<Pair<String, BluetoothDevice>>()
    private var gatt: BluetoothGatt? = null
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = d.name ?: result.scanRecord?.deviceName ?: ""
            val isHr = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
            val looksBand = name.contains("band", true) || name.contains("mi ", true) || name.contains("xiaomi", true) || name.contains("amazfit", true)
            if ((isHr || looksBand) && found.none { it.second.address == d.address }) found += name.ifBlank { d.address } to d
        }
    }

    fun scan() {
        val a = adapter ?: run { state = "Нет Bluetooth"; return }
        if (!a.isEnabled) { state = "Включи Bluetooth"; return }
        found.clear(); state = "Ищу пульсометры…"
        // без фильтра: часть браслетов не пишет сервис пульса в рекламный пакет
        a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
    }

    fun stopScan() { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) } }

    fun connect(d: BluetoothDevice) {
        stopScan(); state = "Подключаюсь…"
        gatt = d.connectGatt(ctx, true, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) { state = "Подключено"; g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { state = "Связь потеряна — переподключаюсь"; bpm = 0 }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASURE) ?: run { state = "Устройство не отдаёт пульс"; return }
                g.setCharacteristicNotification(ch, true)
                val desc = ch.getDescriptor(CCCD)
                if (android.os.Build.VERSION.SDK_INT >= 33) g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") { desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(desc) }
                state = "Пульс идёт"
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = parse(value)
            @Deprecated("старые Android") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION") c.value?.let(::parse)
            }
        })
    }

    private fun parse(v: ByteArray) {
        if (v.isEmpty()) return
        val flags = v[0].toInt()
        bpm = if (flags and 1 == 0) v.getOrElse(1) { 0 }.toInt() and 0xFF
        else ((v.getOrElse(1) { 0 }.toInt() and 0xFF) or ((v.getOrElse(2) { 0 }.toInt() and 0xFF) shl 8))
    }

    fun close() { stopScan(); runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null }
}

private fun zone(bpm: Int, max: Int): Pair<String, Color> {
    val p = bpm.toFloat() / max
    return when {
        p < 0.5f -> "Разминка" to Pal.Sky
        p < 0.6f -> "Восстановление" to Pal.Mint
        p < 0.7f -> "Жиросжигание" to Pal.Butter
        p < 0.8f -> "Аэробная" to Pal.Peach
        p < 0.9f -> "Анаэробная" to Pal.Rose
        else -> "Максимум" to Color(0xFFF3B6C6)
    }
}

@Composable
fun CyclingWorkout(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hr = remember { HrMonitor(ctx) }
    DisposableEffect(Unit) {
        val w = ctx.findActivity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { hr.close(); w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var running by remember { mutableStateOf(false) }
    var startAt by remember { mutableLongStateOf(0L) }
    var startInstant by remember { mutableStateOf<Instant?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val samples = remember { mutableStateListOf<Pair<Instant, Int>>() }
    var age by remember { mutableStateOf(ctx.getSharedPreferences("health", Context.MODE_PRIVATE).getInt("age", 0).takeIf { it > 0 }?.toString() ?: "") }
    var saved by remember { mutableStateOf<String?>(null) }
    val maxHr = (220 - (age.toIntOrNull() ?: 30)).coerceIn(150, 210)

    val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    fun hasBt() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (hasBt()) hr.scan() }

    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            if (hr.bpm > 0) samples += Instant.now() to hr.bpm
            delay(1000)
        }
    }

    ScreenScaffold("Велотренировка", onDone) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val (zn, zc) = zone(hr.bpm, maxHr)
                AppCard(Modifier.fillMaxWidth(), color = if (hr.bpm > 0) zc else Pal.Card, radius = 16.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (hr.bpm > 0) "${hr.bpm}" else "—", fontSize = 96.sp, style = MaterialTheme.typography.displayLarge)
                        Text(if (hr.bpm > 0) "уд/мин · $zn" else hr.state, style = MaterialTheme.typography.titleMedium)
                        val sec = if (running) (now - startAt) / 1000 else 0
                        Text("%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            if (samples.size > 2) item {
                AppCard(Modifier.fillMaxWidth()) {
                    val v = samples.map { it.second }
                    Row { Text("Средний ${v.average().toInt()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text("Макс ${v.max()}", style = MaterialTheme.typography.titleSmall) }
                    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                        val tail = v.takeLast(600); val lo = tail.min() - 5f; val hi = tail.max() + 5f
                        val p = Path(); tail.forEachIndexed { i, b -> val x = i / (tail.size - 1f) * size.width; val y = size.height - (b - lo) / (hi - lo) * size.height; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                        drawPath(p, Pal.RoseInk.copy(alpha = 0.7f), style = Stroke(4f))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!running) PillButton("Старт", {
                        running = true; startAt = SystemClock.elapsedRealtime(); startInstant = Instant.now(); samples.clear(); saved = null
                    }, Modifier.weight(1f))
                    else PillButton("Стоп и сохранить", {
                        running = false
                        val st = startInstant ?: return@PillButton
                        val end = Instant.now()
                        scope.launch {
                            runCatching {
                                val z = ZoneId.systemDefault().rules.getOffset(st)
                                val c = HealthRepo.client(ctx)
                                val recs = mutableListOf<androidx.health.connect.client.records.Record>(
                                    ExerciseSessionRecord(startTime = st, startZoneOffset = z, endTime = end, endZoneOffset = z,
                                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING, title = "Велотренировка")
                                )
                                if (samples.isNotEmpty()) recs += HeartRateRecord(startTime = st, startZoneOffset = z, endTime = end, endZoneOffset = z,
                                    samples = samples.map { HeartRateRecord.Sample(it.first, it.second.toLong()) })
                                c.insertRecords(recs)
                            }.onSuccess { saved = "Сохранено в Health Connect" }.onFailure { saved = "Не сохранилось: ${it.message}" }
                        }
                    }, Modifier.weight(1f), bg = Pal.RoseInk)
                }
            }
            saved?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = Pal.MintInk) } }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Пульсометр")
                    Text(hr.state, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    PillButton("Найти браслет", { if (hasBt()) hr.scan() else btPerm.launch(perms) }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                    hr.found.forEach { (name, d) ->
                        TextButton({ hr.connect(d) }) { Text("Подключить: $name") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Mi Band 8: на браслете Настройки → Пульс → «Трансляция пульса» (Heart rate broadcast) — включить. Если браслет не находится, значит прошивка не отдаёт пульс по стандартному протоколу.", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Возраст для зон пульса")
                    AppTextField(age, { age = it.filter(Char::isDigit).take(2); age.toIntOrNull()?.let { a -> ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit().putInt("age", a).apply() } }, "Лет",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Text("Максимальный пульс ≈ $maxHr уд/мин (220 − возраст)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
