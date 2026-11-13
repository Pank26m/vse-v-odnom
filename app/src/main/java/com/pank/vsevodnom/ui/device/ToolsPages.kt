package com.pank.vsevodnom.ui.device

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.device.*
import com.pank.vsevodnom.ui.common.AppNav
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.Segmented
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private data class Tool(val key: String, val title: String, val sub: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: com.pank.vsevodnom.ui.theme.Tint)
private val TOOLS = listOf(
    Tool("level", "Уровень", "Пузырьковый: плоскость и ребро", Icons.Rounded.Straighten, Tints.Mint),
    Tool("color", "Пипетка цвета", "HEX, RGB, HSL с фото или камеры", Icons.Rounded.Colorize, Tints.Rose),
    Tool("compass", "Компас", "Истинный север с поправкой на склонение", Icons.Rounded.Explore, Tints.Sky),
    Tool("audio", "Тест звука", "Тон 20 Гц – 20 кГц, левый/правый", Icons.Rounded.GraphicEq, Tints.Lilac),
    Tool("float", "Плавающий монитор", "ЦП, ОЗУ, ток, температура поверх всех окон", Icons.Rounded.PictureInPicture, Tints.Peach),
    Tool("cpu", "Монитор ЦП", "Частоты всех ядер вживую", Icons.Rounded.Memory, Tints.Sky),
    Tool("tis", "Время на частотах", "Сколько ядра провели на каждой частоте", Icons.Rounded.Timer, Tints.Butter),
    Tool("speed", "Скорость интернета", "Загрузка, отдача, пинг", Icons.Rounded.Speed, Tints.Mint),
    Tool("lan", "Устройства в сети", "Кто подключён к моему Wi-Fi", Icons.Rounded.Devices, Tints.Sky),
    Tool("ip", "Мой IP", "Внешний адрес, провайдер, город", Icons.Rounded.Public, Tints.Lilac),
    Tool("center", "Инфо-центр", "Скрытые и системные экраны Android", Icons.Rounded.SettingsApplications, Tints.Butter),
    Tool("fake", "Не подделка ли?", "Сверка заявленного с реальным железом", Icons.Rounded.VerifiedUser, Tints.Rose),
    Tool("recorder", "Диктофон", "Открыть диктофон", Icons.Rounded.Mic, Tints.Peach),
    Tool("codes", "Сканер кодов", "QR и штрихкоды", Icons.Rounded.QrCodeScanner, Tints.Mint),
)

@Composable
fun ToolsHub(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { open = null }
    if (open != null) BackHandler { close() }
    when (open) {
        "level" -> LevelTool(close); "color" -> ColorTool(close); "compass" -> CompassTool(close); "audio" -> AudioTool(close); "float" -> FloatTool(close)
        "cpu" -> CpuPage(close); "tis" -> TimeInStatePage(close)
        "speed" -> DevPage("Скорость интернета", close) { item { SpeedTestCard() } }
        "lan" -> DevPage("Устройства в сети", close) { item { LanScan() } }
        "ip" -> IpTool(close); "center" -> InfoCenter(close); "fake" -> FakeCheck(close)
        else -> DevPage("Инструменты", onBack) {
            TOOLS.chunked(2).forEach { row -> item(key = row.first().key) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t -> NavTile(t.icon, t.title, t.sub, t.tint, Modifier.weight(1f)) { if (t.key == "recorder" || t.key == "codes") AppNav.route = t.key else open = t.key } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            } }
        }
    }
}

/** Значения датчика (null — нет датчика). */
@Composable
fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_GAME): State<FloatArray?> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = ctx.getSystemService(SensorManager::class.java); val s = sm.getDefaultSensor(type)
        val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { st.value = e.values.copyOf() }; override fun onAccuracyChanged(a: Sensor?, b: Int) {} }
        if (s != null) sm.registerListener(l, s, rate)
        onDispose { sm.unregisterListener(l) }
    }
    return st
}

// ---------------- уровень ----------------
@Composable
private fun LevelTool(onBack: () -> Unit) {
    val g by rememberSensor(Sensor.TYPE_GRAVITY)
    var cal by remember { mutableStateOf(0f to 0f) }
    val v = g ?: floatArrayOf(0f, 0f, 9.81f)
    val roll = Math.toDegrees(atan2(v[0].toDouble(), v[2].toDouble())).toFloat() - cal.first
    val pitch = Math.toDegrees(atan2(v[1].toDouble(), v[2].toDouble())).toFloat() - cal.second
    val edge = abs(v[2]) < 4.5f   // телефон стоит на ребре
    val edgeAngle = Math.toDegrees(atan2(v[0].toDouble(), v[1].toDouble())).toFloat()
    val flat = abs(roll) < 0.5f && abs(pitch) < 0.5f
    DevPage("Уровень", onBack) {
        item {
            Card {
                if (!edge) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2 - 10
                            drawCircle(if (flat) DevColors.good.copy(alpha = 0.15f) else Pal.Line, r)
                            listOf(1f, 0.5f, 0.15f).forEach { drawCircle(Pal.Muted.copy(alpha = 0.4f), r * it, style = Stroke(2f)) }
                            drawLine(Pal.Muted.copy(alpha = 0.4f), center - Offset(r, 0f), center + Offset(r, 0f), 2f); drawLine(Pal.Muted.copy(alpha = 0.4f), center - Offset(0f, r), center + Offset(0f, r), 2f)
                            val k = r / 20f
                            val b = center + Offset((-roll * k).coerceIn(-r, r), (pitch * k).coerceIn(-r, r))
                            drawCircle(if (flat) DevColors.good else DevColors.warn, r * 0.13f, b)
                        }
                    }
                    Row { KV("Наклон влево/вправо", "${Dev.fmt(roll, 1)}°") }
                    KV("Наклон вперёд/назад", "${Dev.fmt(pitch, 1)}°")
                } else {
                    Text("${Dev.fmt(abs(edgeAngle), 1)}°", style = MaterialTheme.typography.displayMedium, color = if (abs(edgeAngle) < 0.5f || abs(abs(edgeAngle) - 90) < 0.5f) DevColors.good else Pal.Ink)
                    Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(Pal.Line)) {
                        Canvas(Modifier.fillMaxSize()) { val x = size.width / 2 + (edgeAngle.coerceIn(-20f, 20f) / 20f) * (size.width / 2 - 30); drawCircle(DevColors.good, 24f, Offset(x, size.height / 2)) }
                    }
                }
                Text(if (flat) "Ровно" else if (edge) "Режим ребра: поставь телефон на торец" else "Положи телефон на поверхность", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    PillButton("Обнулить", { cal = (roll + cal.first) to (pitch + cal.second) }, Modifier.weight(1f), bg = Pal.Sky, fg = Pal.SkyInk)
                    PillButton("Сброс", { cal = 0f to 0f }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                }
                Text("«Обнулить» — если поверхность заведомо ровная, а телефон показывает неноль (калибровка).", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
    }
}

// ---------------- пипетка ----------------
@Composable
private fun ColorTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var pick by remember { mutableStateOf<Int?>(null) }
    var at by remember { mutableStateOf<Offset?>(null) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    val hist = remember { mutableStateListOf<Int>() }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { uri ->
        bmp = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) bmp = b }
    fun sample(o: Offset) {
        val b = bmp ?: return; if (view.width == 0) return
        // изображение вписано (Fit): переводим точку экрана в пиксель картинки
        val k = minOf(view.width.toFloat() / b.width, view.height.toFloat() / b.height)
        val ox = (view.width - b.width * k) / 2; val oy = (view.height - b.height * k) / 2
        val x = ((o.x - ox) / k).toInt(); val y = ((o.y - oy) / k).toInt()
        if (x in 0 until b.width && y in 0 until b.height) { at = o; pick = b.getPixel(x, y) }
    }
    DevPage("Пипетка цвета", onBack) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Из галереи", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f))
            PillButton("Сфотографировать", { camera.launch(null) }, Modifier.weight(1f), bg = Pal.Sky, fg = Pal.SkyInk)
        } }
        bmp?.let { b -> item {
            Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black).onSizeChanged { view = it }
                .pointerInput(b) { detectTapGestures { sample(it) } }.pointerInput(b) { detectDragGestures { ch, _ -> sample(ch.position) } }) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                at?.let { o -> Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White, 26f, o, style = Stroke(5f)); drawCircle(Color.Black, 30f, o, style = Stroke(2f)); pick?.let { drawCircle(Color(it), 20f, o) } } }
            }
        } }
        pick?.let { c -> item {
            val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
            val mx = maxOf(r, g, bl) / 255f; val mn = minOf(r, g, bl) / 255f; val l = (mx + mn) / 2; val s = if (mx == mn) 0f else (mx - mn) / (1 - abs(2 * l - 1))
            val kk = 1 - mx; val cy = if (kk < 1) (1 - r / 255f - kk) / (1 - kk) else 0f; val mg = if (kk < 1) (1 - g / 255f - kk) / (1 - kk) else 0f; val yl = if (kk < 1) (1 - bl / 255f - kk) / (1 - kk) else 0f
            val hex = "#%02X%02X%02X".format(r, g, bl)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(c)))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(hex, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)); Text(nearestName(r, g, bl), style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    TextButton({ ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("color", hex)); if (c !in hist) hist.add(0, c); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() }) { Text("Копировать") }
                }
                KV("RGB", "$r, $g, $bl"); KV("HSL", "${hsv[0].toInt()}°, ${(s * 100).toInt()} %, ${(l * 100).toInt()} %"); KV("HSV", "${hsv[0].toInt()}°, ${(hsv[1] * 100).toInt()} %, ${(hsv[2] * 100).toInt()} %")
                KV("CMYK", "${(cy * 100).toInt()}, ${(mg * 100).toInt()}, ${(yl * 100).toInt()}, ${(kk * 100).toInt()}"); KV("Android", "0x%08X".format(c))
            }
        } }
        if (hist.isNotEmpty()) item { Card { Text("Скопированные", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { hist.take(8).forEach { h -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(h)).clickable { pick = h }) } } } }
    }
}
private fun nearestName(r: Int, g: Int, b: Int): String {
    val names = listOf("чёрный" to 0x000000, "белый" to 0xFFFFFF, "серый" to 0x808080, "красный" to 0xE53935, "оранжевый" to 0xFB8C00, "жёлтый" to 0xFDD835, "зелёный" to 0x43A047,
        "бирюзовый" to 0x00ACC1, "голубой" to 0x4FC3F7, "синий" to 0x1E88E5, "фиолетовый" to 0x8E24AA, "розовый" to 0xF06292, "коричневый" to 0x795548, "бежевый" to 0xD7CCC8, "тёмно-синий" to 0x1A237E, "оливковый" to 0x827717)
    return "≈ " + names.minBy { (_, c) -> val dr = r - (c shr 16 and 255); val dg = g - (c shr 8 and 255); val db = b - (c and 255); 2 * dr * dr + 4 * dg * dg + 3 * db * db }.first
}

// ---------------- компас ----------------
@SuppressLint("MissingPermission")
@Composable
private fun CompassTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rv by rememberSensor(Sensor.TYPE_ROTATION_VECTOR)
    val mag by rememberSensor(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI)
    val decl = remember { runCatching { ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        ?: ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let { l ->
        GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination } }
    var az by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rv) { rv?.let { v -> val m = FloatArray(9); SensorManager.getRotationMatrixFromVector(m, v); val o = FloatArray(3); SensorManager.getOrientation(m, o)
        val a = ((Math.toDegrees(o[0].toDouble()).toFloat() + (decl ?: 0f)) + 360) % 360
        // сглаживание по кругу
        val d = ((a - az + 540) % 360) - 180; az = (az + d * 0.25f + 360) % 360 } }
    val dirs = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
    DevPage("Компас", onBack) {
        item {
            Card {
                Text("${az.toInt()}° ${dirs[((az + 22.5f) / 45).toInt() % 8]}", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.align(Alignment.CenterHorizontally))
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                        val r = size.minDimension / 2
                        drawCircle(Pal.Line, r); drawCircle(Pal.Muted.copy(alpha = 0.3f), r, style = Stroke(3f))
                        rotate(-az) {
                            for (i in 0 until 72) { val a = Math.toRadians(i * 5.0 - 90); val l = if (i % 18 == 0) 34f else if (i % 6 == 0) 22f else 10f
                                drawLine(if (i == 0) DevColors.bad else Pal.Muted, center + Offset((cos(a) * (r - l)).toFloat(), (sin(a) * (r - l)).toFloat()), center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()), if (i % 6 == 0) 4f else 2f) }
                            val p = android.graphics.Paint().apply { textSize = 46f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                            listOf("С" to 0, "В" to 90, "Ю" to 180, "З" to 270).forEach { (t, d) -> val a = Math.toRadians(d - 90.0); p.color = if (t == "С") DevColors.bad.toArgb() else Pal.Ink.toArgb()
                                drawContext.canvas.nativeCanvas.drawText(t, center.x + (cos(a) * (r - 70)).toFloat(), center.y + (sin(a) * (r - 70)).toFloat() + 16, p) }
                        }
                        drawLine(DevColors.bad, center, center - Offset(0f, r - 40), 8f); drawCircle(Pal.Ink, 10f, center)
                    }
                }
                KV("Магнитное склонение", decl?.let { "${Dev.fmt(it, 1)}° (учтено — показан истинный север)" } ?: "нет места — показан магнитный север")
                mag?.let { m -> KV("Сила поля", "${Dev.fmt(sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]), 1)} мкТл (норма 25–65)") }
                Text("Если стрелка «плавает» — помаши телефоном восьмёркой для калибровки и убери от магнитов и металла.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
    }
}

// ---------------- тест звука ----------------
@Composable
private fun AudioTool(onBack: () -> Unit) {
    var pos by remember { mutableFloatStateOf(0.5f) }   // логарифмическая шкала 20 Гц – 20 кГц
    val hz = 20.0 * 1000.0.pow(pos.toDouble())
    var ch by remember { mutableIntStateOf(2) }
    var loop by remember { mutableStateOf<Tone.Loop?>(null) }
    // частота и каналы меняются на лету — без пересоздания звука (в 0.44 из-за этого вылетало)
    LaunchedEffect(hz, ch, loop) { loop?.let { it.hz = hz; it.left = ch != 1; it.right = ch != 0 } }
    fun stop() { loop?.stop(); loop = null }
    DisposableEffect(Unit) { onDispose { stop() } }
    DevPage("Тест звука", { stop(); onBack() }) {
        item {
            Card {
                Text("${hz.toInt()} Гц", style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.align(Alignment.CenterHorizontally))
                Slider(pos, { pos = it })
                Row { listOf(20, 100, 440, 1000, 5000, 15000).forEach { f -> Chip("$f", false, { pos = (ln(f / 20.0) / ln(1000.0)).toFloat() }, Modifier.padding(end = 4.dp)) } }
                Segmented(listOf("Левый", "Правый", "Оба"), ch, { ch = it })
                PillButton(if (loop == null) "Играть" else "Стоп", { if (loop == null) loop = Tone.Loop().also { it.hz = hz; it.left = ch != 1; it.right = ch != 0 } else stop() }, Modifier.fillMaxWidth().padding(top = 10.dp))
                Text("Низкие частоты (ниже ~150 Гц) маленький динамик почти не воспроизводит — это нормально. Хрип на средних — признак повреждения.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
    }
}

// ---------------- плавающий монитор ----------------
@Composable
private fun FloatTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var can by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    LiveLoop(1000, Unit) { can = Settings.canDrawOverlays(ctx) }
    DevPage("Плавающий монитор", onBack) {
        item {
            Card {
                Text("Маленькое окно поверх всех приложений: частота процессора, ОЗУ, ток батареи, температура, скорость сети. Его можно перетаскивать; нажатие — закрыть.", style = MaterialTheme.typography.bodyMedium)
                if (!can) PillButton("Разрешить показ поверх других окон", { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }, Modifier.fillMaxWidth().padding(top = 10.dp))
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    PillButton("Включить", { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, FloatMonitorService::class.java)) }, Modifier.weight(1f))
                    PillButton("Выключить", { ctx.stopService(Intent(ctx, FloatMonitorService::class.java)) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
        }
    }
}

// ---------------- мой IP ----------------
@Composable
private fun IpTool(onBack: () -> Unit) {
    var run by remember { mutableIntStateOf(1) }
    val rows = rememberLoad(run) { NetTools.publicIp() }
    DevPage("Мой IP", onBack, actions = { TextButton({ run++ }) { Text("Обновить") } }) {
        if (rows == null) item { Loading("Спрашиваю ipwho.is…") } else item { SectionCard(InfoSection("Внешний адрес", rows, "Это адрес, под которым тебя видят сайты. Запрос уходит к ipwho.is только когда открываешь этот экран."), Tints.Lilac) }
    }
}

// ---------------- инфо-центр ----------------
private data class Shortcut(val title: String, val sub: String, val intent: Intent)
@Composable
private fun InfoCenter(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember {
        listOf(Shortcut("О телефоне", "модель, IMEI, версии", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
            Shortcut("Расход батареи (система)", "по приложениям", Intent(Intent.ACTION_POWER_USAGE_SUMMARY)),
            Shortcut("Батарея: здоровье и защита зарядки", "Pixel", Intent("com.google.android.settings.BATTERY_HEALTH_SETTINGS").setPackage("com.android.settings")),
            Shortcut("Параметры разработчика", "", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
            Shortcut("Тестирование (радиомодуль)", "*#*#4636#*#*: сеть, сигнал, тип сети", Intent().setComponent(ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"))),
            Shortcut("Тестирование (общее)", "", Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TestingSettingsActivity"))),
            Shortcut("Диагностика Pixel", "фирменная проверка железа Google", Intent().setComponent(ComponentName("com.google.android.apps.diagnosticstool", "com.google.android.apps.diagnosticstool.MainActivity"))),
            Shortcut("Хранилище", "", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            Shortcut("Передача данных", "", Intent(Settings.ACTION_DATA_USAGE_SETTINGS)),
            Shortcut("Конфиденциальность", "кто что использовал", Intent("android.intent.action.REVIEW_PERMISSION_USAGE")),
            Shortcut("Приложения по умолчанию", "", Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
            Shortcut("Доступ к статистике", "", Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            Shortcut("Показ поверх окон", "", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)),
            Shortcut("Спец. возможности", "", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Shortcut("Доступ к уведомлениям", "", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            Shortcut("Обновление системы", "", Intent("android.settings.SYSTEM_UPDATE_SETTINGS")),
            Shortcut("Безопасность", "", Intent(Settings.ACTION_SECURITY_SETTINGS)),
            Shortcut("Экран", "", Intent(Settings.ACTION_DISPLAY_SETTINGS)), Shortcut("Звук", "", Intent(Settings.ACTION_SOUND_SETTINGS)),
            Shortcut("Wi-Fi", "", Intent(Settings.ACTION_WIFI_SETTINGS)), Shortcut("Bluetooth", "", Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            Shortcut("NFC", "", Intent(Settings.ACTION_NFC_SETTINGS)), Shortcut("Геолокация", "", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            Shortcut("Язык", "", Intent(Settings.ACTION_LOCALE_SETTINGS)), Shortcut("Дата и время", "", Intent(Settings.ACTION_DATE_SETTINGS)),
        ).filter { ctx.packageManager.resolveActivity(it.intent, 0) != null }
    }
    val tm = remember { ctx.getSystemService(android.telephony.TelephonyManager::class.java) }
    val dialer = remember { com.pank.vsevodnom.call.CallState.isDefault(ctx) }
    DevPage("Инфо-центр", onBack) {
        item { Text("Только экраны, которые этот телефон реально открывает (${list.size}).", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
        list.forEach { s -> item(key = s.title) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { runCatching { ctx.startActivity(s.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Toast.makeText(ctx, "Телефон не дал открыть", Toast.LENGTH_SHORT).show() } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(s.title, style = MaterialTheme.typography.titleSmall); if (s.sub.isNotBlank()) Text(s.sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                Icon(Icons.Rounded.OpenInNew, null, tint = Pal.Muted)
            }
        } }
        item {
            Card {
                Text("Секретные коды", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(if (dialer) "«Всё в одном» — звонилка по умолчанию, поэтому коды можно запускать отсюда." else "Коды запускает только звонилка по умолчанию.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                listOf("4636" to "сведения о телефоне, сеть", "426" to "диагностика push-уведомлений Google").forEach { (c, d) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = dialer) { runCatching { tm.sendDialerSpecialCode(c) }.onFailure { Toast.makeText(ctx, "Не сработало: ${it.message}", Toast.LENGTH_SHORT).show() } }.padding(vertical = 8.dp)) {
                        Text("*#*#$c#*#*", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(140.dp)); Text(d, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                }
            }
        }
    }
}

// ---------------- проверка подлинности ----------------
@Composable
private fun FakeCheck(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data = rememberLoad(Unit) {
        val rows = ArrayList<InfoRow>()
        val soc = DevCpu.soc(); val gl = DevCpu.gl(); val cores = DevCpu.cores(); val cl = DevCpu.clusters(cores)
        val att = DevSystem.attestation()
        val lock = att.rows.firstOrNull { it.key.startsWith("Загрузчик") }?.value; val boot = att.rows.firstOrNull { it.key.startsWith("Состояние загрузки") }?.value
        rows += InfoRow("Аппаратная аттестация", if (boot != null) "$boot, загрузчик $lock" else "нет данных", boot?.contains("зелён") == true, "подпись делает чип — подделать нельзя")
        val brandOk = Build.FINGERPRINT.lowercase().startsWith(Build.BRAND.lowercase()) && Build.TAGS?.contains("release-keys") == true && Build.TYPE == "user"
        rows += InfoRow("Подпись прошивки", "${Build.TYPE} · ${Build.TAGS}", brandOk, "официальные прошивки: user + release-keys")
        val claimedFlagship = Regex("pixel [6-9]|galaxy s2|galaxy z|iphone|xiaomi 1[3-5]|oneplus 1[1-3]", RegexOption.IGNORE_CASE).containsMatchIn("${Build.MANUFACTURER} ${Build.MODEL}")
        val weakCores = cl.all { (it.name).contains(Regex("A5[35]|A53")) }
        rows += InfoRow("Процессор соответствует классу", "${soc?.name ?: Build.HARDWARE} · ${cl.joinToString(" + ") { "${it.cores.size}×${it.name}" }}", !(claimedFlagship && weakCores),
            if (claimedFlagship && weakCores) "заявлен флагман, а ядра только экономичные" else null)
        val gpuExpected = soc?.gpu?.substringBefore(' ')?.lowercase()
        val gpuReal = gl.renderer?.lowercase()
        rows += InfoRow("Графика совпадает с чипом", "${gl.renderer ?: "?"} (ожидается ${soc?.gpu ?: "?"})", if (gpuExpected == null || gpuReal == null) null else gpuReal.contains(gpuExpected.take(4)))
        val m = DevHardware.mem(ctx); val ramGb = m.total / 1e9
        rows += InfoRow("ОЗУ правдоподобно", "${DevHardware.marketing(m.total)} (система видит ${Dev.fmt(ramGb, 1)} ГБ)", ramGb > 0.5)
        val ssm = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
        val total = runCatching { ssm.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT) }.getOrNull()
        val data = android.os.StatFs(android.os.Environment.getDataDirectory().path).totalBytes
        rows += InfoRow("Объём памяти честный", "${total?.let { DevHardware.marketing(it) } ?: "?"}, раздел данных ${Dev.bytes(data)}", total?.let { data <= it && data > it * 0.6 })
        val sensors = DevMedia.sensors(ctx).size
        rows += InfoRow("Датчики", "$sensors шт.", if (claimedFlagship) sensors >= 15 else sensors >= 5, if (claimedFlagship && sensors < 15) "у настоящих флагманов датчиков намного больше" else null)
        val cams = runCatching { ctx.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        rows += InfoRow("Камеры отвечают", "$cams шт.", cams > 0)
        val gms = runCatching { ctx.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false)
        rows += InfoRow("Сервисы Google", if (gms) "есть" else "нет", gms)
        val bad = rows.count { it.ok == false }
        rows to when { bad == 0 -> "Признаков подделки нет: железо совпадает с заявленным, а чип безопасности подтверждает оригинальную прошивку."; bad == 1 -> "Одна странность — посмотри пункт с ✗. Само по себе это не доказывает подделку."; else -> "Несколько несоответствий — телефон может быть подделкой или с изменённой прошивкой." }
    }
    DevPage("Не подделка ли?", onBack) {
        if (data == null) item { Loading("Сверяю железо и прошивку…") } else {
            item { Card { Text(data.second, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) } }
            item { SectionCard(InfoSection("Проверки", data.first), Tints.Rose) }
            item { Text("Подделки часто показывают в «Об устройстве» чужую модель и раздутые ОЗУ/память, но реальные чип, графика, датчики и аттестация их выдают.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
        }
    }
}
