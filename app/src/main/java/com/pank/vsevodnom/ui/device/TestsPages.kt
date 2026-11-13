package com.pank.vsevodnom.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.device.*
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import com.pank.vsevodnom.ui.utils.RepairStore
import com.pank.vsevodnom.ui.utils.RepairTestScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private val EXTRA = listOf(
    Triple("gps", "GPS и спутники", "Спутники на небе, сигнал, время до определения места"),
    Triple("cams", "Камеры вживую", "Превью каждой камеры по очереди"),
    Triple("pointer", "Точность касаний", "Попади в 10 мишеней — ошибка в мм и реакция"),
    Triple("stereo", "Стерео и частоты", "Левый, правый динамик и развёртка 50 Гц – 16 кГц"),
    Triple("bt", "Bluetooth", "Поиск устройств рядом"),
    Triple("sim", "SIM-карта", "Состояние, оператор, сигнал"),
    Triple("net", "Сеть", "Роутер, DNS, интернет"),
    Triple("volUp", "Кнопка громкости +", "Нажми кнопку"),
    Triple("volDown", "Кнопка громкости −", "Нажми кнопку"),
)

@Composable
fun TestsHub(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(false) }
    var v by remember { mutableIntStateOf(0) }
    if (auto) { BackHandler { auto = false }; AutoTestPage { auto = false; v++ }; return }
    open?.let { k ->
        BackHandler { open = null; v++ }
        val fin: (Boolean?) -> Unit = { ok -> ok?.let { RepairStore.set(ctx, k, it) }; open = null; v++ }
        when (k) { "gps" -> GpsTest(fin); "cams" -> CamerasTest(fin); "pointer" -> PointerTest(fin); "stereo" -> StereoTest(fin); "bt" -> BtTest(fin)
            "sim" -> SimTest(fin); "net" -> NetTest(fin); else -> VolumeKeyTest(k == "volUp", fin) }
        return
    }
    key(v) {
        RepairTestScreen(onBack, header = {
            item {
                Card {
                    Text("Автотест", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                    Text("Одной кнопкой: датчики, накопитель (со скоростью), батарея, Wi-Fi и интернет, Bluetooth, NFC, GPS, все камеры, микрофон, отпечаток, SIM, загрузчик, нагрев — всё, где не нужен человек.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    PillButton("Запустить автотест", { auto = true }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }, extra = EXTRA, openExtra = { open = it })
    }
}

/** Нижняя строка с кнопками «Работает / Не работает». */
@Composable
private fun Verdict(fin: (Boolean?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Не работает", { fin(false) }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
        PillButton("Работает", { fin(true) }, Modifier.weight(1f), bg = Pal.Mint, fg = Pal.MintInk)
    }
}

// ---------------- автотест ----------------
private data class AutoRes(val title: String, val ok: Boolean?, val detail: String)

@SuppressLint("MissingPermission")
@Composable
private fun AutoTestPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    AskPerms(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
    val res = remember { mutableStateListOf<AutoRes>() }
    var step by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        res.clear()
        suspend fun t(title: String, f: suspend () -> Pair<Boolean?, String>) { step = title; val r = runCatching { f() }.getOrElse { false to (it.message ?: "ошибка") }; res += AutoRes(title, r.first, r.second) }
        val sm = ctx.getSystemService(SensorManager::class.java)
        listOf(Sensor.TYPE_ACCELEROMETER to "Акселерометр", Sensor.TYPE_GYROSCOPE to "Гироскоп", Sensor.TYPE_MAGNETIC_FIELD to "Магнитометр", Sensor.TYPE_LIGHT to "Датчик света",
            Sensor.TYPE_PROXIMITY to "Датчик приближения", Sensor.TYPE_PRESSURE to "Барометр", Sensor.TYPE_ROTATION_VECTOR to "Вектор поворота").forEach { (type, name) ->
            t(name) {
                val s = sm.getDefaultSensor(type) ?: return@t null to "нет в телефоне"
                val v = withTimeoutOrNull(2000) { suspendCancellableCoroutine<FloatArray> { c ->
                    val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { sm.unregisterListener(this); if (c.isActive) c.resume(e.values.copyOf()) }; override fun onAccuracyChanged(a: Sensor?, b: Int) {} }
                    sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME); c.invokeOnCancellation { sm.unregisterListener(l) } } }
                (v != null) to (v?.take(3)?.joinToString(" / ") { Dev.fmt(it, 2) } ?: "нет данных за 2 с")
            }
        }
        t("Накопитель: запись и чтение") { withContext(Dispatchers.IO) {
            val f = java.io.File(ctx.cacheDir, "disk_test.bin"); val buf = ByteArray(1 shl 20) { (it * 31).toByte() }
            val t0 = SystemClock.elapsedRealtimeNanos(); f.outputStream().use { o -> repeat(64) { o.write(buf) }; o.fd.sync() }
            val w = 64 / ((SystemClock.elapsedRealtimeNanos() - t0) / 1e9)
            val t1 = SystemClock.elapsedRealtimeNanos(); var ok = true; f.inputStream().use { i -> val b = ByteArray(1 shl 20); repeat(64) { var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }; if (!b.contentEquals(buf)) ok = false } }
            val r = 64 / ((SystemClock.elapsedRealtimeNanos() - t1) / 1e9); f.delete()
            ok to "запись ${w.toInt()} МБ/с · чтение ${r.toInt()} МБ/с (64 МБ, чтение может идти из кэша)"
        } }
        t("Батарея") { val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
            val h = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0); val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f; val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            (h == BatteryManager.BATTERY_HEALTH_GOOD && temp < 45 && mv in 3300..4600) to "${DevReport.healthName(h)}, ${Dev.fmt(temp, 1)} °C, $mv мВ" }
        t("Wi-Fi") { @Suppress("DEPRECATION") val wi = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (!wi.isWifiEnabled) null to "выключен" else (wi.connectionInfo.networkId != -1) to (if (wi.connectionInfo.networkId != -1) "подключён, ${wi.connectionInfo.rssi} дБм" else "включён, не подключён") }
        t("Интернет") { withContext(Dispatchers.IO) { val c = java.net.URL("https://www.google.com/generate_204").openConnection() as java.net.HttpURLConnection; c.connectTimeout = 5000; c.readTimeout = 5000
            val t0 = SystemClock.elapsedRealtime(); val code = c.responseCode; c.disconnect(); (code == 204) to "ответ $code за ${SystemClock.elapsedRealtime() - t0} мс" } }
        t("Bluetooth") { val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return@t false to "нет адаптера"; if (a.isEnabled) true to "включён" else null to "выключен — включи для проверки" }
        t("NFC") { val n = android.nfc.NfcAdapter.getDefaultAdapter(ctx) ?: return@t null to "нет в телефоне"; if (n.isEnabled) true to "включён" else null to "выключен" }
        t("GPS") { val lm = ctx.getSystemService(LocationManager::class.java); if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) null to "геолокация выключена" else true to "включён · чип ${lm.gnssHardwareModelName ?: "?"} — спутники в отдельном тесте" }
        val cm = ctx.getSystemService(CameraManager::class.java)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cm.cameraIdList.forEach { id ->
            t("Камера $id (${if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "передняя" else "задняя"})") {
                val ht = HandlerThread("cam").apply { start() }
                val r = withTimeoutOrNull(4000) { suspendCancellableCoroutine<String?> { c ->
                    cm.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { d.close(); if (c.isActive) c.resume(null) }
                        override fun onDisconnected(d: CameraDevice) { d.close(); if (c.isActive) c.resume("отключилась") }
                        override fun onError(d: CameraDevice, e: Int) { d.close(); if (c.isActive) c.resume("ошибка $e") }
                    }, Handler(ht.looper)) } }
                ht.quitSafely(); (r == null) to (r ?: "открылась")
            }
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) t("Микрофон") { withContext(Dispatchers.IO) {
            val sr = 44100; val n = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val ar = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, n * 4)
            val b = ShortArray(sr); ar.startRecording(); var got = 0; val t0 = SystemClock.elapsedRealtime()
            while (got < b.size && SystemClock.elapsedRealtime() - t0 < 1500) { val r = ar.read(b, got, b.size - got); if (r <= 0) break; got += r }
            ar.stop(); ar.release()
            val peak = b.take(got).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
            (peak > 30) to "уровень ${Dev.fmt(20 * kotlin.math.log10(peak.coerceAtLeast(1) / 32767.0), 0)} дБFS" + if (peak <= 30) " — тишина: микрофон закрыт или неисправен" else ""
        } }
        t("Вибромотор") { val v = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
            v.hasVibrator() to if (v.hasAmplitudeControl()) "есть, с регулировкой силы" else "есть" }
        t("Отпечаток") { val r = androidx.biometric.BiometricManager.from(ctx).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            when (r) { androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> true to "работает, отпечатки добавлены"; androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> null to "есть, но отпечатков нет"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> false to "датчик недоступен"; else -> false to "код $r" } }
        t("SIM") { val tm = ctx.getSystemService(TelephonyManager::class.java); when (tm.simState) { TelephonyManager.SIM_STATE_READY -> true to "${tm.networkOperatorName} · ${runCatching { DevConnect.netType(tm.dataNetworkType) }.getOrDefault("")}"
            TelephonyManager.SIM_STATE_ABSENT -> null to "нет SIM"; else -> false to "состояние ${tm.simState}" } }
        t("Загрузка и прошивка (по чипу)") { withContext(Dispatchers.IO) { val a = DevSystem.attestation(); val lock = a.rows.firstOrNull { it.key.startsWith("Загрузчик") }?.value; val st = a.rows.firstOrNull { it.key.startsWith("Состояние загрузки") }?.value
            (lock == "заблокирован" && st?.contains("зелён") == true).let { ok -> (if (lock == null) null else ok) to listOfNotNull(lock, st).joinToString(", ").ifEmpty { "нет данных" } } } }
        t("Нагрев") { val s = DevHardware.thermalStatus(ctx); (s < android.os.PowerManager.THERMAL_STATUS_SEVERE) to DevHardware.thermalName(s) }
        step = null
    }
    DevPage("Автотест", onBack) {
        item {
            Card {
                val ok = res.count { it.ok == true }; val bad = res.count { it.ok == false }
                Text(if (step != null) "Проверяю: $step" else if (res.isEmpty()) "Готов к проверке" else "Готово: $ok в порядке, $bad проблем, ${res.count { it.ok == null }} не проверить", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                PillButton(if (res.isEmpty()) "Запустить" else "Ещё раз", { run++ }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = step == null)
            }
        }
        if (res.isNotEmpty()) item { SectionCard(InfoSection("Результаты", res.map { InfoRow(it.title, it.detail, it.ok) }), Tints.Peach) }
        item { Text("Динамики, экран, касания и кнопки проверяются только человеком — они в списке ручных тестов.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
    }
}

// ---------------- GPS ----------------
private data class Sat(val id: Int, val cons: Int, val az: Float, val el: Float, val cn0: Float, val used: Boolean, val freq: Float?)
private fun consName(c: Int) = when (c) { GnssStatus.CONSTELLATION_GPS -> "GPS"; GnssStatus.CONSTELLATION_GLONASS -> "ГЛОНАСС"; GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"; GnssStatus.CONSTELLATION_QZSS -> "QZSS"; GnssStatus.CONSTELLATION_SBAS -> "SBAS"; GnssStatus.CONSTELLATION_IRNSS -> "NavIC"; else -> "?" }
private fun consColor(c: Int) = DevColors.series[c % DevColors.series.size]

@SuppressLint("MissingPermission")
@Composable
private fun GpsTest(fin: (Boolean?) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    AskPerms(Manifest.permission.ACCESS_FINE_LOCATION) { granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    var sats by remember { mutableStateOf<List<Sat>>(emptyList()) }
    var loc by remember { mutableStateOf<Location?>(null) }
    var ttff by remember { mutableStateOf<Int?>(null) }
    val t0 = remember { SystemClock.elapsedRealtime() }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val lm = ctx.getSystemService(LocationManager::class.java)
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) { sats = (0 until s.satelliteCount).map { i -> Sat(s.getSvid(i), s.getConstellationType(i), s.getAzimuthDegrees(i), s.getElevationDegrees(i), s.getCn0DbHz(i), s.usedInFix(i), if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) else null) } }
            override fun onFirstFix(ms: Int) { ttff = ms }
        }
        val ll = LocationListener { loc = it }
        runCatching { lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(ctx), cb); lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper()) }
        onDispose { runCatching { lm.unregisterGnssStatusCallback(cb); lm.removeUpdates(ll) } }
    }
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        DevPage("GPS и спутники", { fin(null) }) {
            item {
                Card {
                    val used = sats.count { it.used }
                    Text(if (loc != null) "Место определено" else "Ищу спутники… ${(SystemClock.elapsedRealtime() - t0) / 1000} с", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = if (loc != null) DevColors.good else Pal.Ink)
                    KV("Спутников видно / используется", "${sats.size} / $used")
                    ttff?.let { KV("Время до первого определения", "${Dev.fmt(it / 1000f, 1)} с") }
                    loc?.let { l -> KV("Координаты", "${Dev.fmt(l.latitude, 6)}, ${Dev.fmt(l.longitude, 6)}"); KV("Точность", "±${Dev.fmt(l.accuracy, 1)} м")
                        if (l.hasAltitude()) KV("Высота", "${l.altitude.toInt()} м"); if (l.hasSpeed()) KV("Скорость", "${Dev.fmt(l.speed * 3.6f, 1)} км/ч"); if (l.hasBearing()) KV("Курс", "${l.bearing.toInt()}°") }
                    val dual = sats.any { (it.freq ?: 0f) in 1.17e9f..1.18e9f }
                    KV("Двухчастотный приём (L5/E5a)", if (sats.any { it.freq != null }) (if (dual) "да" else "нет") else "нет данных")
                    Text("Тест лучше проводить на улице: в помещении спутников мало.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
            item {
                Card {
                    Text("Небо", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        val r = size.minDimension / 2 - 20; val c = center
                        listOf(1f, 2 / 3f, 1 / 3f).forEach { drawCircle(Pal.Line, r * it, c, style = Stroke(2f)) }
                        drawLine(Pal.Line, c - Offset(r, 0f), c + Offset(r, 0f), 2f); drawLine(Pal.Line, c - Offset(0f, r), c + Offset(0f, r), 2f)
                        val p = android.graphics.Paint().apply { color = Pal.Muted.toArgb(); textSize = 28f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
                        listOf("С" to Offset(0f, -r - 4), "В" to Offset(r + 10, 10f), "Ю" to Offset(0f, r + 24), "З" to Offset(-r - 10, 10f)).forEach { (t, o) -> drawContext.canvas.nativeCanvas.drawText(t, c.x + o.x, c.y + o.y, p) }
                        sats.forEach { s -> val rr = r * (90 - s.el) / 90; val a = Math.toRadians(s.az.toDouble() - 90)
                            val pt = c + Offset((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat()); val col = consColor(s.cons)
                            if (s.used) drawCircle(col, 11f, pt) else drawCircle(col, 11f, pt, style = Stroke(3f)) }
                    }
                    Legend(sats.map { it.cons }.distinct().map { "${consName(it)} ${sats.count { s -> s.cons == it }}" to consColor(it) })
                    Text("Заполненные — используются для определения места.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
            if (sats.isNotEmpty()) item {
                Card {
                    Text("Сигнал спутников (дБ·Гц)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Row(Modifier.fillMaxWidth().height(140.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        sats.sortedByDescending { it.cn0 }.forEach { s -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(14.dp).height((s.cn0 / 50f * 110).coerceIn(2f, 120f).dp).clip(RoundedCornerShape(3.dp)).background(consColor(s.cons).copy(alpha = if (s.used) 1f else 0.4f)))
                            Text("${s.id}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) } }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Pal.Bg).navigationBarsPadding()) { Verdict(fin) }
    }
}

// ---------------- камеры вживую ----------------
@SuppressLint("MissingPermission")
@Composable
private fun CamerasTest(fin: (Boolean?) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    AskPerms(Manifest.permission.CAMERA) { granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val ids = remember { runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList()) }
    var sel by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.statusBarsPadding().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ids.forEachIndexed { i, id -> val front = runCatching { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }.getOrDefault(false)
                Chip("${if (front) "Передняя" else "Задняя"} $id", sel == i, { sel = i; err = null }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!granted) Text("Нужен доступ к камере", color = Color.White)
            else if (ids.isNotEmpty()) key(sel) { CameraPreview(ids[sel]) { err = it } }
            err?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) }
        }
        Box(Modifier.background(Pal.Bg).navigationBarsPadding()) { Verdict(fin) }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(id: String, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val ch = remember(id) { cm.getCameraCharacteristics(id) }
    val size = remember(id) { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.filter { it.width <= 1920 }?.maxByOrNull { it.width * it.height } }
    var device by remember { mutableStateOf<CameraDevice?>(null) }
    val thread = remember { HandlerThread("cam-prev").apply { start() } }
    DisposableEffect(id) { onDispose { runCatching { device?.close() }; thread.quitSafely() } }
    val ratio = size?.let { it.height.toFloat() / it.width } ?: 0.75f
    AndroidView({ c -> TextureView(c).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                size?.let { st.setDefaultBufferSize(it.width, it.height) }
                runCatching { cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d; val surf = Surface(st)
                        @Suppress("DEPRECATION") d.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { runCatching { s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf) }.build(), null, Handler(thread.looper)) } }
                            override fun onConfigureFailed(s: CameraCaptureSession) { onError("Не удалось запустить превью") }
                        }, Handler(thread.looper))
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close() }
                    override fun onError(d: CameraDevice, e: Int) { d.close(); onError("Камера $id: ошибка $e") }
                }, Handler(thread.looper)) }.onFailure { onError(it.message ?: "ошибка") }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        } } }, Modifier.fillMaxWidth().aspectRatio(ratio))
}

// ---------------- точность касаний ----------------
@Composable
private fun PointerTest(fin: (Boolean?) -> Unit) {
    val dens = LocalDensity.current
    val ctx = LocalContext.current
    var target by remember { mutableStateOf<Offset?>(null) }
    var shown by remember { mutableLongStateOf(0L) }
    val errs = remember { mutableStateListOf<Float>() }; val times = remember { mutableStateListOf<Long>() }
    var box by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val pxPerMm = ctx.resources.displayMetrics.xdpi / 25.4f
    fun next() { target = Offset(Random.nextFloat() * (box.width - 160) + 80, Random.nextFloat() * (box.height - 160) + 80); shown = SystemClock.elapsedRealtime() }
    LaunchedEffect(box) { if (box.width > 0 && target == null) next() }
    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Text(if (errs.size < 10) "Попади в центр мишени · ${errs.size + 1} из 10" else "Готово: средняя ошибка ${Dev.fmt(errs.average().toFloat() / pxPerMm, 2)} мм, реакция ${times.average().toInt()} мс",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.statusBarsPadding().padding(16.dp))
        Canvas(Modifier.weight(1f).fillMaxWidth().pointerInput(errs.size) {
            detectTapGestures { o -> val t = target ?: return@detectTapGestures; if (errs.size >= 10) return@detectTapGestures
                errs += hypot(o.x - t.x, o.y - t.y); times += SystemClock.elapsedRealtime() - shown; if (errs.size < 10) next() else target = null }
        }) { box = size; target?.let { t -> listOf(60f to DevColors.bad, 40f to Color.White, 22f to DevColors.bad, 6f to Color.White).forEach { (r, c) -> drawCircle(c, r, t) } } }
        if (errs.size >= 10) Verdict { fin(it ?: (errs.average() / pxPerMm < 3)) } else Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

// ---------------- стерео и частоты ----------------
@Composable
private fun StereoTest(fin: (Boolean?) -> Unit) {
    var phase by remember { mutableStateOf("") }
    var freq by remember { mutableFloatStateOf(0f) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) { if (run == 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            phase = "Левый канал"; Tone.play(440.0, 1.6, left = true, right = false)
            phase = "Правый канал"; Tone.play(440.0, 1.6, left = false, right = true)
            phase = "Развёртка 50 Гц → 16 кГц"; Tone.sweep(50.0, 16000.0, 6.0) { freq = it.toFloat() }
            phase = "Готово — всё было слышно?"; freq = 0f
        } }
    DevPage("Стерео и частоты", { fin(null) }) {
        item { Card {
            Text("Сначала тон только слева, потом только справа, потом звук от низкого к высокому. Взрослые обычно перестают слышать где-то на 14–17 кГц.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Text(phase.ifEmpty { "Нажми «Играть»" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 8.dp))
            if (freq > 0) Text("${freq.toInt()} Гц", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = Pal.SkyInk)
            PillButton("Играть", { run++ }, Modifier.fillMaxWidth())
        } }
        item { Verdict(fin) }
    }
}

/** Генератор тона (AudioTrack, стерео). */
object Tone {
    private const val SR = 44100
    fun play(hz: Double, sec: Double, left: Boolean, right: Boolean, volume: Float = 0.6f) {
        val n = (SR * sec).toInt(); val b = ShortArray(n * 2)
        for (i in 0 until n) { val env = minOf(1.0, i / 2000.0, (n - i) / 2000.0); val v = (sin(2 * PI * hz * i / SR) * env * volume * Short.MAX_VALUE).toInt().toShort()
            b[2 * i] = if (left) v else 0; b[2 * i + 1] = if (right) v else 0 }
        out(b)
    }
    fun sweep(f0: Double, f1: Double, sec: Double, onFreq: (Double) -> Unit) {
        val n = (SR * sec).toInt(); val b = ShortArray(n * 2); var ph = 0.0
        for (i in 0 until n) { val f = f0 * Math.pow(f1 / f0, i.toDouble() / n); ph += 2 * PI * f / SR; val env = minOf(1.0, i / 2000.0, (n - i) / 2000.0)
            val v = (sin(ph) * env * 0.5 * Short.MAX_VALUE).toInt().toShort(); b[2 * i] = v; b[2 * i + 1] = v }
        val t = track(b); t.play(); val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < sec * 1000) { onFreq(f0 * Math.pow(f1 / f0, (SystemClock.elapsedRealtime() - t0) / (sec * 1000))); Thread.sleep(80) }
        t.stop(); t.release()
    }
    /** Непрерывный тон: свой поток пишет звук кусками, частота и каналы меняются на лету (для «Теста звука»). */
    class Loop {
        @Volatile var hz = 440.0
        @Volatile var left = true
        @Volatile var right = true
        @Volatile private var run = true
        private val th = Thread {
            val n = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val t = runCatching { AudioTrack.Builder().setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(n * 2).build() }.getOrNull() ?: return@Thread
            runCatching {
                t.play(); var ph = 0.0; val chunk = ShortArray(1024 * 2); var fade = 0.0
                while (run) {
                    for (i in 0 until 1024) { ph += 2 * PI * hz / SR; if (ph > 2 * PI) ph -= 2 * PI; fade = minOf(1.0, fade + 1.0 / 2000)
                        val v = (sin(ph) * 0.5 * fade * Short.MAX_VALUE).toInt().toShort(); chunk[2 * i] = if (left) v else 0; chunk[2 * i + 1] = if (right) v else 0 }
                    t.write(chunk, 0, chunk.size)
                }
            }
            runCatching { t.pause(); t.flush(); t.stop() }; runCatching { t.release() }
        }.apply { isDaemon = true; start() }
        fun stop() { run = false }
    }
    private fun track(b: ShortArray) = AudioTrack.Builder().setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(b.size * 2).build().apply { write(b, 0, b.size) }
    private fun out(b: ShortArray) { val t = track(b); t.play(); Thread.sleep((b.size / 2 * 1000L / SR) + 100); t.stop(); t.release() }
}

// ---------------- Bluetooth ----------------
@SuppressLint("MissingPermission")
@Composable
private fun BtTest(fin: (Boolean?) -> Unit) {
    val ctx = LocalContext.current
    AskPerms(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    val ad = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val found = remember { mutableStateMapOf<String, Pair<String, Int>>() }
    var scanning by remember { mutableStateOf(false) }
    DisposableEffect(scanning) {
        if (!scanning || ad == null) return@DisposableEffect onDispose {}
        val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_FOUND) { val d = @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                found[d.address] = ((runCatching { d.name }.getOrNull() ?: d.address) to i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()) }
            if (i.action == android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED) scanning = false } }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }, ContextCompat.RECEIVER_EXPORTED)
        runCatching { ad.startDiscovery() }
        onDispose { runCatching { ad.cancelDiscovery(); ctx.unregisterReceiver(rx) } }
    }
    DevPage("Bluetooth", { fin(null) }) {
        item { Card {
            Text(when { ad == null -> "Bluetooth в телефоне нет"; !ad.isEnabled -> "Bluetooth выключен — включи его"; scanning -> "Ищу устройства… найдено ${found.size}"; else -> "Найдено устройств: ${found.size}" }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            PillButton(if (scanning) "Идёт поиск…" else "Искать (12 с)", { found.clear(); scanning = true }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !scanning && ad?.isEnabled == true)
        } }
        if (found.isNotEmpty()) item { SectionCard(InfoSection("Рядом", found.values.sortedByDescending { it.second }.map { InfoRow(it.first, if (it.second > -200) "${it.second} дБм" else "") }), Tints.Lilac) }
        item { Verdict { fin(it ?: found.isNotEmpty()) } }
    }
}

// ---------------- SIM ----------------
@Composable
private fun SimTest(fin: (Boolean?) -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    AskPerms(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS) { reload++ }
    val secs = rememberLoad(reload) { DevConnect.telephony(ctx) }
    DevPage("SIM-карта", { fin(null) }) { sections(secs, Tints.Lilac); item { Verdict(fin) } }
}

// ---------------- сеть ----------------
@Composable
private fun NetTest(fin: (Boolean?) -> Unit) {
    val ctx = LocalContext.current
    var res by remember { mutableStateOf<Pair<List<InfoRow>, String>?>(null) }
    LaunchedEffect(Unit) { res = withContext(Dispatchers.IO) { NetTools.diagnose(ctx) {} } }
    DevPage("Сеть", { fin(null) }) {
        val r = res
        if (r == null) item { Loading("Проверяю роутер, DNS, интернет…") } else {
            item { SectionCard(InfoSection("Результат", r.first, r.second), Tints.Sky) }
            item { Verdict { fin(it ?: r.first.none { x -> x.ok == false }) } }
        }
    }
}

// ---------------- кнопки громкости ----------------
@Composable
private fun VolumeKeyTest(up: Boolean, fin: (Boolean?) -> Unit) {
    val fr = remember { FocusRequester() }
    var got by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(got) { if (got) { delay(700); fin(true) } }
    Box(Modifier.fillMaxSize().background(Pal.Bg).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
        val code = e.key.nativeKeyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) { if (e.type == KeyEventType.KeyDown && (code == KeyEvent.KEYCODE_VOLUME_UP) == up) got = true; true } else false
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(if (got) DevColors.good else Pal.Card), contentAlignment = Alignment.Center) {
                Text(if (up) "+" else "−", style = MaterialTheme.typography.displayLarge, color = if (got) Color.White else Pal.Ink) }
            Text(if (got) "Работает" else "Нажми кнопку громкости ${if (up) "+" else "−"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            TextButton({ fin(false) }) { Text("Не срабатывает") }
        }
    }
}
