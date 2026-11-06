package com.pank.vsevodnom.ui.utils

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun gb(b: Long) = String.format(Locale.US, "%.1f ГБ", b / 1_073_741_824.0).replace('.', ',')

private fun cpuMaxFreqs(): List<Int> = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
    runCatching { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 }.getOrNull()
}

private fun sensorTypeRu(t: Int) = when (t) {
    Sensor.TYPE_ACCELEROMETER -> "Акселерометр"; Sensor.TYPE_GYROSCOPE -> "Гироскоп"
    Sensor.TYPE_MAGNETIC_FIELD -> "Магнитометр (компас)"; Sensor.TYPE_PROXIMITY -> "Приближение"
    Sensor.TYPE_LIGHT -> "Освещённость"; Sensor.TYPE_PRESSURE -> "Барометр"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "Температура воздуха"; Sensor.TYPE_RELATIVE_HUMIDITY -> "Влажность"
    Sensor.TYPE_STEP_COUNTER -> "Шагомер"; Sensor.TYPE_HEART_RATE -> "Пульс"
    Sensor.TYPE_GRAVITY -> "Гравитация"; Sensor.TYPE_ROTATION_VECTOR -> "Вектор поворота"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Линейное ускорение"; Sensor.TYPE_SIGNIFICANT_MOTION -> "Значимое движение"
    Sensor.TYPE_HINGE_ANGLE -> "Угол шарнира"
    else -> null
}

fun collectDeviceInfo(ctx: Context): List<Pair<String, List<Pair<String, String>>>> {
    val out = ArrayList<Pair<String, List<Pair<String, String>>>>()
    out += "Устройство" to buildList {
        add("Модель" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        add("Кодовое имя" to Build.DEVICE)
        add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Патч безопасности" to Build.VERSION.SECURITY_PATCH)
        add("Сборка" to Build.DISPLAY)
        add("Ядро" to (System.getProperty("os.version") ?: "—"))
        add("Загрузчик" to Build.BOOTLOADER)
        val up = SystemClock.elapsedRealtime() / 1000
        add("Без перезагрузки" to "${up / 86400} д ${up / 3600 % 24} ч ${up / 60 % 60} мин")
    }
    out += "Процессор" to buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add("Чип" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        add("Ядер" to "${Runtime.getRuntime().availableProcessors()}")
        val f = cpuMaxFreqs()
        if (f.isNotEmpty()) add("Частоты" to f.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }.joinToString(" + ") { "${it.value}×${it.key} МГц" })
        add("Архитектура" to Build.SUPPORTED_ABIS.joinToString(", "))
    }
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val st = StatFs(Environment.getDataDirectory().path)
    out += "Память" to listOf(
        "ОЗУ" to "${gb(mi.totalMem)} (свободно ${gb(mi.availMem)})",
        "Хранилище" to "${gb(st.totalBytes)} (свободно ${gb(st.availableBytes)})",
    )
    out += "Экран" to buildList {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        add("Разрешение" to "${b.width()} × ${b.height()}")
        add("Плотность" to "${dm.densityDpi} dpi")
        val inches = sqrt((b.width() / dm.xdpi).let { it * it } + (b.height() / dm.ydpi).let { it * it })
        add("Диагональ (оценка)" to String.format(Locale.US, "%.1f″", inches).replace('.', ','))
        @Suppress("DEPRECATION")
        val d = ctx.display ?: wm.defaultDisplay
        add("Частота" to d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted().joinToString(" / ") + " Гц")
        if (Build.VERSION.SDK_INT >= 24) {
            val hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "тип $it" }
            }.orEmpty()
            if (hdr.isNotEmpty()) add("HDR" to hdr.joinToString(", "))
        }
    }
    runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val cams = cm.cameraIdList.map { id ->
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_FRONT -> "Фронтальная"; CameraCharacteristics.LENS_FACING_BACK -> "Основная"; else -> "Внешняя" }
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val mp = px?.let { (it.width * it.height / 1e6).roundToInt() }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            "$facing $id" to listOfNotNull(mp?.let { "$it Мп" }, focal?.let { String.format(Locale.US, "фокус %.1f мм", it) }).joinToString(", ")
        }
        if (cams.isNotEmpty()) out += "Камеры" to cams
    }
    val sm = ctx.getSystemService(SensorManager::class.java)
    out += "Датчики" to sm.getSensorList(Sensor.TYPE_ALL).mapNotNull { s -> sensorTypeRu(s.type)?.let { it to s.vendor } }.distinctBy { it.first }
    out += "Связь" to buildList {
        val pm = ctx.packageManager
        fun has(f: String) = if (pm.hasSystemFeature(f)) "есть" else "нет"
        add("NFC" to has("android.hardware.nfc"))
        add("eSIM" to has("android.hardware.telephony.euicc"))
        add("UWB" to has("android.hardware.uwb"))
        add("Bluetooth LE" to has("android.hardware.bluetooth_le"))
        add("Wi-Fi Direct" to has("android.hardware.wifi.direct"))
        add("Отпечаток" to has("android.hardware.fingerprint"))
        add("ИК-порт" to has("android.hardware.consumerir"))
    }
    return out
}

@Composable
fun DeviceInfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val info = remember { runCatching { collectDeviceInfo(ctx) }.getOrDefault(emptyList()) }
    ScreenScaffold("Об устройстве", onBack, actions = {
        RoundIconButton(Icons.Rounded.ContentCopy, "Скопировать всё", {
            val text = info.joinToString("\n\n") { (g, rows) -> g + "\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" } }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("device", text))
            Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
        })
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            info.forEach { (g, rows) -> item { InfoGroup(g, rows) } }
        }
    }
}
