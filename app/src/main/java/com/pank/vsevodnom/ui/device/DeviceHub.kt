package com.pank.vsevodnom.ui.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.device.*
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Sect(val key: String, val title: String, val sub: String, val icon: ImageVector, val tint: Tint)

private val SECTS = listOf(
    Sect("system", "Система", "Android, сборка, разделы, модули", Icons.Rounded.Android, Tints.Mint),
    Sect("cpu", "Процессор", "Ядра, частоты вживую, кластеры", Icons.Rounded.Memory, Tints.Sky),
    Sect("gpu", "Графика", "ГП, OpenGL, Vulkan, расширения", Icons.Rounded.Gradient, Tints.Lilac),
    Sect("memory", "Память", "ОЗУ вживую, накопитель, тома", Icons.Rounded.Storage, Tints.Butter),
    Sect("display", "Экран", "Разрешение, частоты, HDR, панель", Icons.Rounded.Smartphone, Tints.Peach),
    Sect("battery", "Батарея", "Зарядка, разрядка, ёмкость, история", Icons.Rounded.BatteryChargingFull, Tints.Mint),
    Sect("thermal", "Температура", "Нагрев, троттлинг, термозоны", Icons.Rounded.Thermostat, Tints.Rose),
    Sect("network", "Сеть", "Подключение, IP, DNS, скорость вживую", Icons.Rounded.Language, Tints.Sky),
    Sect("wifi", "Wi-Fi анализатор", "Каналы, сети вокруг, устройства в сети", Icons.Rounded.Wifi, Tints.Sky),
    Sect("connect", "Связь", "SIM и соты, Bluetooth, NFC, GPS", Icons.Rounded.CellTower, Tints.Lilac),
    Sect("camera", "Камеры", "Все модули, сенсоры, объективы, видео", Icons.Rounded.PhotoCamera, Tints.Peach),
    Sect("sensors", "Датчики", "Все датчики с живыми значениями", Icons.Rounded.Sensors, Tints.Butter),
    Sect("audio", "Звук и вибрация", "Динамики, микрофоны, вибромотор", Icons.Rounded.GraphicEq, Tints.Rose),
    Sect("apps", "Приложения", "Список, анализ, разрешения, трафик", Icons.Rounded.Apps, Tints.Mint),
    Sect("security", "Безопасность", "Аттестация чипом, загрузчик, root, DRM", Icons.Rounded.Shield, Tints.Lilac),
    Sect("codecs", "Кодеки", "Аудио и видео: аппаратные и программные", Icons.Rounded.VideoLibrary, Tints.Sky),
    Sect("inputs", "Устройства ввода", "Сенсор, кнопки, отпечаток", Icons.Rounded.TouchApp, Tints.Peach),
    Sect("mounts", "Разделы", "Монтирование, файловые системы", Icons.Rounded.AccountTree, Tints.Butter),
    Sect("usb", "USB", "Режимы, подключённые устройства", Icons.Rounded.Usb, Tints.Mint),
    Sect("features", "Функции и свойства", "Все функции устройства и getprop", Icons.Rounded.ListAlt, Tints.Lilac),
    Sect("tests", "Тесты", "Проверка железа с отчётом", Icons.Rounded.FactCheck, Tints.Rose),
    Sect("tools", "Инструменты", "Уровень, пипетка, компас, монитор…", Icons.Rounded.Handyman, Tints.Sky),
)

/** «Анализатор устройства»: сводка вживую + разделы. Всё читается с этого телефона, ничего не вписано заранее. */
@Composable
fun DeviceHub(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { page = null }
    if (page != null) BackHandler { close() }
    when (page) {
        "system" -> InfoPage("Система", close, Tints.Mint) { DevSystem.system(it) }
        "cpu" -> CpuPage(close)
        "gpu" -> GpuPage(close)
        "memory" -> MemoryPage(close)
        "display" -> InfoPage("Экран", close, Tints.Peach) { DevHardware.display(it) }
        "battery" -> BatteryHub(close)
        "thermal" -> ThermalPage(close)
        "network" -> NetworkPage(close)
        "wifi" -> WifiAnalyzer(close)
        "connect" -> ConnectPage(close)
        "camera" -> CameraPage(close)
        "sensors" -> SensorsPage(close)
        "audio" -> InfoPage("Звук и вибрация", close, Tints.Rose) { DevHardware.audio(it) + DevHardware.vibrator(it) }
        "apps" -> AppsHub(close)
        "security" -> InfoPage("Безопасность", close, Tints.Lilac) { DevSystem.security(it) }
        "codecs" -> CodecsPage(close)
        "inputs" -> InfoPage("Устройства ввода", close, Tints.Peach) { DevHardware.inputs(it) }
        "mounts" -> MountsPage(close)
        "usb" -> InfoPage("USB", close, Tints.Mint) { DevHardware.usb(it) }
        "features" -> FeaturesPage(close)
        "tests" -> TestsHub(close)
        "tools" -> ToolsHub(close)
        else -> HubHome(onBack) { page = it }
    }
}

@Composable
private fun HubHome(onBack: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(false) }
    DevPage("Устройство", onBack, actions = { RoundIconButton(Icons.Rounded.Summarize, "Полный отчёт", { report = true }, bg = Pal.Ink, tint = Color.White) }) {
        item { Summary(open) }
        item { GroupTitle("Разделы") }
        SECTS.chunked(2).forEach { row ->
            item(key = "row" + row.first().key) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { s -> NavTile(s.icon, s.title, s.sub, s.tint, Modifier.weight(1f)) { open(s.key) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Card { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Summarize, null, tint = Pal.LilacInk)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Всё о телефоне одним файлом", style = MaterialTheme.typography.titleSmall)
                    Text("Все разделы в TXT, JSON или PDF — отправить или сохранить", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                TextButton({ report = true }) { Text("Отчёт") }
            } }
        }
    }
    if (report) ReportDialog { report = false }
}

/** Сводка вживую: чип, время работы, ЦП, ОЗУ, накопитель, батарея, сеть, нагрев. */
@Composable
private fun Summary(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val head = rememberLoad(Unit) {
        Triple(DevSystem.deviceName(ctx), DevCpu.soc()?.name ?: if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            DevCpu.cores().let { cs -> DevCpu.clusters(cs).joinToString(" + ") { "${it.cores.size}×${it.name.removePrefix("Cortex-")}" } })
    }
    val cores = remember { DevCpu.cores() }
    val n = cores.size
    val maxF = cores.map { it.maxKhz ?: 1L }
    val minF = cores.map { it.minKhz ?: 0L }
    val load = remember { Series("ЦП", DevColors.series[0]) }
    var cur by remember { mutableStateOf<List<Long?>>(emptyList()) }
    var mem by remember { mutableStateOf<DevHardware.Mem?>(null) }
    val memS = remember { Series("ОЗУ", DevColors.series[3]) }
    val batS = remember { Series("мА", DevColors.series[4 % DevColors.series.size]) }
    var down by remember { mutableStateOf(0.0) }; var up by remember { mutableStateOf(0.0) }
    val downS = remember { Series("↓", DevColors.series[1]) }; val upS = remember { Series("↑", DevColors.series[2]) }
    var bat by remember { mutableStateOf<Triple<Int, Double?, Float>?>(null) }
    var thermal by remember { mutableStateOf<Pair<Int, Float?>?>(null) }
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    var lastRx by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }; var lastTx by remember { mutableLongStateOf(TrafficStats.getTotalTxBytes()) }
    var lastT by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var tick by remember { mutableIntStateOf(0) }
    LiveLoop(1000, Unit) {
        val f = DevCpu.curFreqs(n); cur = f
        // «нагрузка» по частоте: /proc/stat приложениям закрыт, частота — честная косвенная оценка
        val l = f.mapIndexedNotNull { i, v -> v?.let { ((it - minF[i]).toFloat() / (maxF[i] - minF[i]).coerceAtLeast(1)).coerceIn(0f, 1f) } }
        load.push(if (l.isEmpty()) 0f else l.average().toFloat() * 100)
        val m = DevHardware.mem(ctx); mem = m; memS.push((m.total - m.avail) * 100f / m.total)
        val now = SystemClock.elapsedRealtime(); val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        val dt = (now - lastT).coerceAtLeast(1) / 1000.0
        down = (rx - lastRx) * 8 / 1e6 / dt; up = (tx - lastTx) * 8 / 1e6 / dt; lastRx = rx; lastTx = tx; lastT = now
        downS.push(down.toFloat()); upS.push(up.toFloat())
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        bat = Triple(i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0,
            DevReport.curMa(bm), (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
        bat?.second?.let { batS.push(kotlin.math.abs(it).toFloat()) }
        if (tick++ % 3 == 0) thermal = DevHardware.thermalStatus(ctx) to DevHardware.headroom(ctx)
    }
    val up0 = SystemClock.elapsedRealtime(); val awake = SystemClock.uptimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // шапка
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Ink).padding(18.dp)) {
            Text(head?.first ?: Build.MODEL, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
            Text(listOfNotNull(head?.second, DevSystem.androidName(Build.VERSION.SDK_INT).first).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
            head?.third?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f)) }
            Spacer(Modifier.height(10.dp))
            Text("Работает ${Dev.dur(up0 / 1000)} · в глубоком сне ${(up0 - awake) * 100 / up0.coerceAtLeast(1)} %", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FE3C1))
        }
        // ЦП вживую
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { open("cpu") }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Процессор", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Text("${load.last?.toInt() ?: 0} % частоты", style = MaterialTheme.typography.labelLarge, color = DevColors.level((load.last ?: 0f) / 100))
            }
            LiveChart(listOf(load), height = 90.dp, yMax = 100f, unit = "%")
            // сетка ядер с текущей частотой
            cur.chunked(3).forEachIndexed { r, row ->
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { k, v ->
                        val i = r * 3 + k; val frac = v?.let { (it - minF[i]).toFloat() / (maxF[i] - minF[i]).coerceAtLeast(1) } ?: 0f
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(DevColors.level(frac).copy(alpha = 0.12f)).padding(8.dp, 5.dp)) {
                            Text("Ядро $i", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                            Text(v?.let { "${it / 1000} МГц" } ?: "спит", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = DevColors.level(frac))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val m = mem
            StatTile("ОЗУ", m?.let { "${(m.total - m.avail) * 100 / m.total} %" } ?: "…", m?.let { "${Dev.bytes(m.total - m.avail)} из ${Dev.bytes(m.total)}" }, Tints.Rose, Modifier.weight(1f), { open("memory") }) {
                LiveChart(listOf(memS), height = 44.dp, yMax = 100f, label = { "" })
            }
            val st = remember { android.os.StatFs(android.os.Environment.getDataDirectory().path) }
            val usedF = 1f - st.availableBytes.toFloat() / st.totalBytes
            StatTile("Накопитель", "${(usedF * 100).toInt()} %", "свободно ${Dev.bytes(st.availableBytes)}", Tints.Butter, Modifier.weight(1f), { open("memory") }) {
                Spacer(Modifier.height(10.dp)); Bar(usedF, DevColors.level(usedF))
                Text("из ${DevHardware.marketing(st.totalBytes)}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // одинаковые по высоте плитки: у обеих по одной строке подписи и живой график
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val b = bat
            StatTile("Батарея", b?.let { "${it.first} % · ${Dev.fmt(it.third, 0)}°" } ?: "…", b?.second?.let { ma -> "${if (ma > 0) "+" else ""}${ma.toInt()} мА сейчас" } ?: "ток недоступен", Tints.Mint, Modifier.weight(1f).fillMaxHeight(), { open("battery") }) {
                LiveChart(listOf(batS), height = 44.dp, label = { "" })
            }
            StatTile("Сеть", "↓ ${Dev.fmt(down, if (down < 10) 1 else 0)} · ↑ ${Dev.fmt(up, if (up < 10) 1 else 0)}", "Мбит/с сейчас", Tints.Sky, Modifier.weight(1f).fillMaxHeight(), { open("network") }) {
                LiveChart(listOf(downS, upS), height = 44.dp, label = { "" })
            }
        }
        val counts = rememberLoad(Unit) { Triple(com.pank.vsevodnom.ui.utils.RepairStore.all(ctx).count { it.value }, DevMedia.sensors(ctx).size, ctx.packageManager.getInstalledPackages(0).size) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Тесты", "${counts?.first ?: 0} пройдено", "проверка железа", Tints.Rose, Modifier.weight(1f), { open("tests") })
            StatTile("Датчики", "${counts?.second ?: "…"}", "живые значения", Tints.Butter, Modifier.weight(1f), { open("sensors") })
            StatTile("Приложения", "${counts?.third ?: "…"}", "анализ и права", Tints.Mint, Modifier.weight(1f), { open("apps") })
        }
        thermal?.let { (st, hr) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { open("thermal") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Thermostat, null, tint = if (st >= 3) DevColors.bad else if (st >= 1) DevColors.warn else DevColors.good)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Нагрев: " + DevHardware.thermalName(st), style = MaterialTheme.typography.titleSmall)
                    hr?.let { Text("Запас до снижения частот: ${((1 - it) * 100).toInt().coerceIn(0, 100)} %", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                }
                hr?.let { Ring(it.coerceIn(0f, 1f), 40.dp, DevColors.level(it), 5.dp) }
            }
        }
    }
}

/** Отчёт обо всём: выбор формата → сбор всех разделов в фоне → отправить или сохранить. */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var fmt by remember { mutableIntStateOf(2) }
    var busy by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = file ?: return@rememberLauncherForActivityResult
        if (uri != null) AppScope.io.launch { runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } } }
    }
    fun build(then: (File) -> Unit) {
        busy = "Начинаю…"
        AppScope.io.launch {
            val ch = DevReport.collect(ctx) { busy = it }
            val dir = File(ctx.cacheDir, "report").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val name = "Устройство ${Build.MODEL} ${java.time.LocalDate.now()}".replace(' ', '_')
            val f = when (fmt) { 0 -> File(dir, "$name.txt").apply { writeText(DevReport.txt(ctx, ch)) }; 1 -> File(dir, "$name.json").apply { writeText(DevReport.json(ctx, ch)) }
                else -> File(dir, "$name.pdf").also { DevReport.pdf(ctx, ch, it) } }
            withContext(Dispatchers.Main) { busy = null; file = f; then(f) }
        }
    }
    fun share(f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (f.extension == "pdf") "application/pdf" else if (f.extension == "json") "application/json" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отчёт об устройстве"))
    }
    AlertDialog(onDismissRequest = { if (busy == null) onDismiss() }, title = { Text("Полный отчёт") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Все разделы анализатора: система, безопасность, процессор, графика, память, экран, батарея, сеть, связь, камеры, датчики, звук, кодеки, разделы, функции и свойства системы.", style = MaterialTheme.typography.bodySmall)
            com.pank.vsevodnom.ui.common.Segmented(listOf("TXT", "JSON", "PDF"), fmt, { fmt = it })
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  $it", style = MaterialTheme.typography.bodySmall) } }
            file?.let { Text("Готово: ${it.name} · ${Dev.bytes(it.length())}", style = MaterialTheme.typography.bodySmall, color = DevColors.good) }
        }
    }, confirmButton = { TextButton({ if (busy == null) build { share(it) } }, enabled = busy == null) { Text("Отправить") } },
        dismissButton = { TextButton({ if (busy == null) build { f -> saver.launch(f.name) } }, enabled = busy == null) { Text("Сохранить") } })
}
