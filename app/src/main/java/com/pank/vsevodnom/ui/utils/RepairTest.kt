package com.pank.vsevodnom.ui.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

internal enum class Res { NONE, OK, FAIL }

private data class Check(val key: String, val title: String, val hint: String)

/** Результаты тестов сохраняются (prefs repair_test) — видно и после перезапуска, и в сводке анализатора. */
object RepairStore {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("repair_test", android.content.Context.MODE_PRIVATE)
    private val OLD = setOf("buttons", "camBack", "camFront")   // тесты, убранные в 0.45 (есть volUp/volDown и «Камеры»)
    fun all(ctx: android.content.Context): Map<String, Boolean> = p(ctx).all.mapNotNull { (k, v) -> if (k in OLD) null else (v as? Boolean)?.let { k to it } }.toMap()
    fun set(ctx: android.content.Context, key: String, ok: Boolean?) { p(ctx).edit().apply { if (ok == null) remove(key) else putBoolean(key, ok) }.apply() }
    fun clear(ctx: android.content.Context) = p(ctx).edit().clear().apply()
    val manualCount get() = CHECKS.size
}

private val CHECKS = listOf(
    Check("colors", "Экран: цвета", "Битые пиксели, пятна, выгорание"),
    Check("gradient", "Экран: градиенты", "Плавные переходы без полос"),
    Check("motion", "Экран: движение", "Шлейфы и плавность (как TestUFO)"),
    Check("touch", "Сенсорный экран", "Закрась все клетки пальцем"),
    Check("multitouch", "Мультитач", "Приложи несколько пальцев сразу"),
    Check("speaker", "Основной динамик", "Играет тон — слышно громко и чисто?"),
    Check("earpiece", "Разговорный динамик", "Поднеси к уху — слышно тон?"),
    Check("mic", "Микрофон", "Запись 3 секунды и воспроизведение"),
    Check("vibro", "Вибрация", "Телефон вибрирует?"),
    Check("flash", "Фонарик", "Включается?"),
    Check("proximity", "Датчик приближения", "Закрой верх экрана рукой"),
    Check("light", "Датчик освещённости", "Прикрой и открой — значение меняется?"),
    Check("imu", "Акселерометр и гироскоп", "Покрути телефон — цифры меняются?"),
    Check("compass", "Компас", "Покрути — направление меняется?"),
    Check("charge", "Зарядка", "Подключи кабель"),
    Check("biometric", "Отпечаток / лицо", "Приложи палец"),
)

@Composable
fun RepairTestScreen(onBack: () -> Unit, header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}, extra: List<Triple<String, String, String>> = emptyList(), openExtra: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val res = remember { mutableStateMapOf<String, Res>().apply { RepairStore.all(ctx).forEach { (k, v) -> put(k, if (v) Res.OK else Res.FAIL) } } }
    // любое изменение — сразу в сохранённые результаты
    LaunchedEffect(res.toMap()) { res.forEach { (k, v) -> RepairStore.set(ctx, k, when (v) { Res.OK -> true; Res.FAIL -> false; else -> null }) } }
    var running by remember { mutableStateOf<Check?>(null) }
    val full = remember { mutableStateOf<String?>(null) }

    when (full.value) {
        "touch" -> { TouchGridTest({ full.value = null; if (res["touch"] != Res.OK) running = CHECKS.first { it.key == "touch" } }, { res["touch"] = Res.OK }); return }
        "colors", "gradient", "motion" -> {
            val k = full.value!!
            val done = { full.value = null; running = CHECKS.first { it.key == k } }
            when (k) { "colors" -> ColorsTest(done); "gradient" -> GradientTest(done); else -> MotionTest(done) }
            return
        }
        "multitouch" -> { MultiTouch { n -> full.value = null; res["multitouch"] = if (n >= 2) Res.OK else Res.FAIL }; return }
    }

    ScreenScaffold("Тесты телефона", onBack, actions = {
        RoundIconButton(Icons.Rounded.ContentCopy, "Скопировать отчёт", {
            val txt = "Тест ${Build.MANUFACTURER} ${Build.MODEL}\n" + (CHECKS.map { it.key to it.title } + extra.map { it.first to it.second }).joinToString("\n") {
                (when (res[it.first]) { Res.OK -> "✔"; Res.FAIL -> "✘"; else -> "–" }) + " " + it.second
            }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", txt))
            Toast.makeText(ctx, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        })
    }) {
        val keys = (CHECKS.map { it.key } + extra.map { it.first }).toSet()
        val ok = res.filterKeys { it in keys }.values.count { it == Res.OK }; val fail = res.filterKeys { it in keys }.values.count { it == Res.FAIL }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    Text("Пройдено $ok из ${keys.size}" + if (fail > 0) " · проблем: $fail" else "", style = MaterialTheme.typography.titleLarge)
                    Text("Нажми на пункт, чтобы проверить. Результаты сохраняются. Отчёт можно скопировать и отправить мастеру.", style = MaterialTheme.typography.bodySmall)
                    TextButton({ res.clear(); RepairStore.clear(ctx) }) { Text("Сбросить результаты") }
                }
            }
            header()
            items(extra, key = { "x" + it.first }) { (k, t, h) ->
                val r = RepairStore.all(ctx)[k]?.let { if (it) Res.OK else Res.FAIL } ?: res[k] ?: Res.NONE
                AppCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { openExtra(k) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(when (r) { Res.OK -> Pal.Mint; Res.FAIL -> Pal.Rose; else -> Pal.Line }), contentAlignment = Alignment.Center) {
                            Text(when (r) { Res.OK -> "✓"; Res.FAIL -> "✕"; else -> "" }, color = when (r) { Res.OK -> Pal.MintInk; else -> Pal.RoseInk }) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.titleSmall); Text(h, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            items(CHECKS) { c ->
                val r = res[c.key] ?: Res.NONE
                AppCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = {
                    if (c.key in setOf("touch", "multitouch", "colors", "gradient", "motion")) full.value = c.key else running = c
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(when (r) { Res.OK -> Pal.Mint; Res.FAIL -> Pal.Rose; else -> Pal.Line }),
                            contentAlignment = Alignment.Center
                        ) { Text(when (r) { Res.OK -> "✓"; Res.FAIL -> "✕"; else -> "" }, color = when (r) { Res.OK -> Pal.MintInk; else -> Pal.RoseInk }) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall)
                            Text(c.hint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    running?.let { c ->
        CheckDialog(c, onResult = { r -> res[c.key] = r; running = null }, onDismiss = { running = null })
    }
}

@Composable
private fun MultiTouch(onDone: (Int) -> Unit) {
    Immersive()
    val pts = remember { mutableStateMapOf<Long, Offset>() }
    var maxN by remember { mutableIntStateOf(0) }
    BackHandler { onDone(maxN) }
    Box(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                ev.changes.forEach { ch -> if (ch.pressed) pts[ch.id.value] = ch.position else pts.remove(ch.id.value); ch.consume() }
                maxN = maxOf(maxN, pts.size)
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val colors = listOf(Pal.SkyInk, Pal.MintInk, Pal.PeachInk, Pal.LilacInk, Pal.RoseInk, Pal.ButterInk)
            pts.values.forEachIndexed { i, p -> drawCircle(colors[i % 6].copy(alpha = 0.4f), 90f, p); drawCircle(colors[i % 6], 14f, p) }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Касаний сейчас: ${pts.size}", style = MaterialTheme.typography.titleLarge)
            Text("Максимум: $maxN", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            PillButton("Готово", { onDone(maxN) })
        }
    }
}

/** Синусоида на нужное устройство вывода. */
private suspend fun playTone(ctx: Context, earpiece: Boolean, seconds: Int = 3) = withContext(Dispatchers.IO) {
    val am = ctx.getSystemService(AudioManager::class.java)
    val rate = 44100
    val n = rate * seconds
    val buf = ShortArray(n) { i -> (sin(2 * PI * 880 * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort() }
    val attrs = AudioAttributes.Builder()
        .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    if (earpiece) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { am.setCommunicationDevice(it) }
        } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
    }
    val track = AudioTrack.Builder().setAudioAttributes(attrs)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    try {
        track.write(buf, 0, n); track.play()
        delay(seconds * 1000L + 200)
    } finally {
        track.release()
        if (earpiece) { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
    }
}

/** Запись 3 секунд с микрофона и воспроизведение через динамик. */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun micRoundTrip(onPhase: (String) -> Unit, levelOut: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val rate = 16000
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
    val total = rate * 3
    val data = ShortArray(total)
    onPhase("Говори… идёт запись")
    rec.startRecording()
    var off = 0
    while (off < total) {
        val r = rec.read(data, off, minOf(1024, total - off)); if (r <= 0) break
        var peak = 0; for (k in off until off + r) peak = maxOf(peak, kotlin.math.abs(data[k].toInt()))
        levelOut(peak / 32767f); off += r
    }
    rec.stop(); rec.release()
    onPhase("Воспроизвожу запись…")
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(total * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    track.write(data, 0, off); track.play(); delay(3300); track.release()
    onPhase("Слышно свой голос?")
}

@Composable
private fun sensorValues(type: Int): State<FloatArray?> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { st.value = e.values.copyOf() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return st
}

private fun v3(v: FloatArray?) = v?.take(3)?.joinToString("  ") { String.format(Locale.US, "%6.2f", it) } ?: "нет датчика"

@Composable
private fun CheckDialog(c: Check, onResult: (Res) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { runCatching { micRoundTrip({ phase = it }, { level = it }) }.onFailure { phase = "Ошибка записи: ${it.message}" } }
        else phase = "Нет разрешения на микрофон"
    }

    // автоматические проверки
    var autoOk by remember { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        when (c.key) {
            "colors", "gradient", "motion" -> Text("Всё выглядело нормально?")
            "speaker", "earpiece" -> {
                LaunchedEffect(Unit) { phase = "Играет тон…"; runCatching { playTone(ctx, c.key == "earpiece") }; phase = "Было слышно?" }
                Text(phase)
            }
            "mic" -> {
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        runCatching { micRoundTrip({ phase = it }, { level = it }) }.onFailure { phase = "Ошибка записи: ${it.message}" }
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }
                Column { Text(phase); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { level }, Modifier.fillMaxWidth()) }
            }
            "vibro" -> {
                LaunchedEffect(Unit) {
                    val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1))
                }
                Text("Вибрирует три раза. Почувствовал?")
            }
            "flash" -> {
                var on by remember { mutableStateOf(false) }
                val cm = remember { ctx.getSystemService(CameraManager::class.java) }
                val id = remember { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
                DisposableEffect(Unit) { onDispose { id?.let { runCatching { cm.setTorchMode(it, false) } } } }
                if (id == null) Text("Вспышка не найдена") else Column {
                    Text("Фонарик ${if (on) "включён" else "выключен"}")
                    Spacer(Modifier.height(8.dp))
                    PillButton(if (on) "Выключить" else "Включить", { on = !on; runCatching { cm.setTorchMode(id, on) } })
                }
            }
            "proximity" -> {
                val v by sensorValues(Sensor.TYPE_PROXIMITY)
                var seenNear by remember { mutableStateOf(false) }; var seenFar by remember { mutableStateOf(false) }
                val max = remember { ctx.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PROXIMITY)?.maximumRange ?: 5f }
                LaunchedEffect(v) { v?.get(0)?.let { if (it < max) seenNear = true else seenFar = true }; if (seenNear && seenFar) autoOk = true }
                Text(if (v == null) "Нет данных" else if ((v?.get(0) ?: max) < max) "БЛИЗКО" else "далеко")
            }
            "light" -> { val v by sensorValues(Sensor.TYPE_LIGHT); Text(v?.let { "${it[0].toInt()} люкс" } ?: "нет датчика", style = MaterialTheme.typography.headlineMedium) }
            "imu" -> {
                val a by sensorValues(Sensor.TYPE_ACCELEROMETER); val g by sensorValues(Sensor.TYPE_GYROSCOPE)
                Column { Text("Акселерометр:\n${v3(a)}"); Spacer(Modifier.height(8.dp)); Text("Гироскоп:\n${v3(g)}") }
            }
            "compass" -> {
                val m by sensorValues(Sensor.TYPE_MAGNETIC_FIELD); val a by sensorValues(Sensor.TYPE_ACCELEROMETER)
                val az = remember(m, a) {
                    val mm = m; val aa = a
                    if (mm == null || aa == null) null else {
                        val r = FloatArray(9); val o = FloatArray(3)
                        if (SensorManager.getRotationMatrix(r, null, aa, mm)) { SensorManager.getOrientation(r, o); ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toInt() } else null
                    }
                }
                Text(az?.let { "$it°" } ?: "нет данных", style = MaterialTheme.typography.headlineMedium)
            }
            "charge" -> {
                var plugged by remember { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) } }
                    ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) }
                    onDispose { ctx.unregisterReceiver(r) }
                }
                LaunchedEffect(plugged) { if (plugged != 0) autoOk = true }
                Text(if (plugged != 0) "Зарядка определяется ✓" else "Ждём кабель…")
            }
            "biometric" -> {
                LaunchedEffect(Unit) {
                    val act = ctx.findActivity() as? FragmentActivity
                    val can = BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    if (act == null || can != BiometricManager.BIOMETRIC_SUCCESS) { phase = "Биометрия не настроена в системе"; return@LaunchedEffect }
                    BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { phase = "Распознано ✓"; autoOk = true }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { phase = errString.toString() }
                        override fun onAuthenticationFailed() { phase = "Не распознано, попробуй ещё" }
                    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Проверка биометрии").setNegativeButtonText("Отмена")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
                }
                Text(phase.ifBlank { "Приложи палец или посмотри в камеру" })
            }
        }
    }
    LaunchedEffect(autoOk) { if (autoOk) { delay(600); onResult(Res.OK) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = { Column { Text(c.hint, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)); content() } },
        confirmButton = { TextButton({ onResult(Res.OK) }) { Text("Работает", color = Pal.MintInk) } },
        dismissButton = { TextButton({ onResult(Res.FAIL) }) { Text("Не работает", color = Pal.RoseInk) } }
    )
}
