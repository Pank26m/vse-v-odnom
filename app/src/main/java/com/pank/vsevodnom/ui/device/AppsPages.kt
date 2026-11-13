package com.pank.vsevodnom.ui.device

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.device.*
import com.pank.vsevodnom.ui.common.AppTextField
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.common.Segmented
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm"))
private fun startOfDay(daysAgo: Int = 0) = LocalDate.now().minusDays(daysAgo.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
fun AppIcon(pkg: String, size: Dp = 42.dp) {
    val ctx = LocalContext.current
    val bmp = rememberLoad(pkg) { AppScan.icon(ctx, pkg) }
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Pal.Line))
}

@Composable
fun AppsHub(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val apps = rememberLoad(reload) { AppScan.list(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<Pair<String, List<AppScan.App>>?>(null) }
    open?.let { p -> BackHandler { open = null }; AppDetail(p, { open = null }) { reload++ }; return }
    group?.let { (t, l) -> BackHandler { group = null }; DevPage(t, { group = null }) { item { Text("${l.size} приложений", color = Pal.Muted) }; items(l, key = { "g" + it.pkg }) { a -> AppRow(a) { open = a.pkg } } }; return }
    ScreenScaffold("Приложения", onBack) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Список", "Анализ", "Разрешения", "Трафик", "Экранное время").forEachIndexed { i, t -> Chip(t, tab == i, { tab = i }) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (apps == null) { item { Loading("Читаю список приложений…") }; return@LazyColumn }
            when (tab) {
                0 -> listTab(apps) { open = it }
                1 -> item { AnalyzerTab(apps) { t, l -> group = t to l } }
                2 -> item { PermsTab(apps) { t, l -> group = t to l } }
                3 -> item { UsageGate { TrafficTab(apps) { open = it } } }
                else -> item { UsageGate { ScreenTimeTab(apps) { open = it } } }
            }
        }
    }
}

@Composable
private fun AppRow(a: AppScan.App, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (a.updatedSystem) Text(" ОБНОВЛЕНО", style = MaterialTheme.typography.labelSmall, color = Pal.SkyInk)
                if (!a.enabled) Text(" ВЫКЛ", style = MaterialTheme.typography.labelSmall, color = DevColors.bad)
            }
            Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
            Text("${a.version ?: "?"} · API ${a.target} · ${Dev.bytes(a.apkBytes)}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
        }
    }
}

private fun LazyListScope.listTab(apps: List<AppScan.App>, open: (String) -> Unit) {
    item {
        var kind by remember { mutableIntStateOf(0) }
        var sort by remember { mutableIntStateOf(0) }
        var q by remember { mutableStateOf("") }
        val l = apps.filter { (kind == 2 || (kind == 1) == it.system) && (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }.let { x ->
            when (sort) { 1 -> x.sortedByDescending { it.apkBytes }; 2 -> x.sortedByDescending { it.installed }; 3 -> x.sortedByDescending { it.updated }; else -> x } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(q, { q = it }, "Поиск по названию или пакету")
            Segmented(listOf("Свои ${apps.count { !it.system }}", "Системные ${apps.count { it.system }}", "Все ${apps.size}"), kind, { kind = it })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("по имени", "по размеру", "по установке", "по обновлению").forEachIndexed { i, t -> Chip(t, sort == i, { sort = i }) }
            }
            l.forEach { a -> AppRow(a) { open(a.pkg) } }
        }
    }
}

// ---------------- карточка приложения ----------------
@Composable
private fun AppDetail(pkg: String, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberLoad(pkg) { AppScan.list(ctx).firstOrNull { it.pkg == pkg } }
    val det = rememberLoad(pkg) { AppScan.details(ctx, pkg) }
    val an = rememberLoad(pkg, app) { app?.let { AppScan.analyze(ctx, it) } }
    var list by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val a = app ?: return@rememberLauncherForActivityResult
        if (uri != null) AppScope.io.launch {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                if (a.splitDirs.isEmpty()) java.io.File(a.source).inputStream().use { it.copyTo(o) }
                else ZipOutputStream(o).use { z -> (listOf(a.source) + a.splitDirs).forEach { p -> z.putNextEntry(ZipEntry(java.io.File(p).name)); java.io.File(p).inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            } }.isSuccess
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (ok) "APK сохранён" else "Не удалось", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    list?.let { (t, l) -> BackHandler { list = null }; DevPage(t, { list = null }) { item { SectionCard(InfoSection("${l.size}", l.map { InfoRow(it.substringAfterLast('.'), it) }), Tints.Mint) } }; return }
    DevPage(app?.label ?: pkg, onBack) {
        if (app == null) { item { Loading() }; return@DevPage }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 60.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        Text("Версия ${app.version} (${app.code})", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TagChips(listOfNotNull(if (app.system) "системное" else "своё", if (app.updatedSystem) "обновлено" else null, if (!app.enabled) "выключено" else null,
                    AppScan.categoryName(app.category), an?.platform), Tints.Mint)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { li -> PillButton("Открыть", { runCatching { ctx.startActivity(li) } }) }
                    PillButton("Настройки", { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }, bg = Pal.Sky, fg = Pal.SkyInk)
                    PillButton("Google Play", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) } }, bg = Pal.Mint, fg = Pal.MintInk)
                    PillButton("Извлечь APK", { saver.launch("${app.label}_${app.version}".replace(Regex("[^\\wА-Яа-я.]+"), "_") + if (app.splitDirs.isEmpty()) ".apk" else ".apks") }, bg = Pal.Butter, fg = Pal.ButterInk)
                    if (!app.system || app.updatedSystem) PillButton("Удалить", { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))); onChanged() }, bg = Pal.Rose, fg = Pal.RoseInk)
                }
            }
        }
        item {
            SectionCard(section("Сведения") {
                add("Целевой Android", "${DevSystem.androidName(app.target).first} (API ${app.target})"); add("Минимальный Android", "${DevSystem.androidName(app.min).first} (API ${app.min})")
                add("Установлено", d(app.installed)); add("Обновлено", d(app.updated)); add("Откуда", AppScan.installerName(app.installer))
                add("Размер APK", Dev.bytes(app.apkBytes) + if (app.splits > 0) " (основной + ${app.splits} частей)" else "")
                det?.sizes?.let { (a, dt, c) -> add("Приложение / данные / кэш", "${Dev.bytes(a)} / ${Dev.bytes(dt)} / ${Dev.bytes(c)}") } ?: add("Размер данных", "нужен доступ к статистике использования")
                add("Место установки", when (app.location) { 0 -> "авто"; 1 -> "только внутренняя"; 2 -> "предпочитает внешнюю"; else -> "внутренняя" })
                add("UID", app.uid); add("Путь", app.source)
                an?.let { add("Платформа", it.platform); add("Найденные SDK", it.libs.joinToString(", ").ifEmpty { "—" }); add("Алгоритм подписи", it.sigAlg) }
            }, Tints.Mint)
        }
        if (det != null) {
            item {
                Card {
                    Text("Состав приложения", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        @Composable fun b(t: String, l: List<String>) = Chip("$t · ${l.size}", false, { if (l.isNotEmpty()) list = t to l })
                        b("Activity", det.pi.activities.orEmpty().map { it.name }); b("Сервисы", det.pi.services.orEmpty().map { it.name })
                        b("Приёмники", det.pi.receivers.orEmpty().map { it.name }); b("Провайдеры", det.pi.providers.orEmpty().map { it.name })
                        b("Нативные библиотеки", det.libs); b("Метаданные", det.pi.applicationInfo?.metaData?.keySet().orEmpty().toList())
                    }
                }
            }
            item {
                val rows = det.perms.map { (p, g) -> val (label, prot) = AppScan.permLabel(ctx, p)
                    InfoRow(label.replaceFirstChar { it.uppercase() }, if (g) "разрешено" else "нет", g, p + if (AppScan.isDangerous(prot)) " · опасное" else "") }
                    .sortedWith(compareBy({ it.ok != true }, { it.hint?.contains("опасное") != true }))
                SectionCard(InfoSection("Разрешения (${det.perms.count { it.second }} из ${det.perms.size})", rows), Tints.Rose, maxRows = 25)
            }
            if (det.sig.isNotEmpty()) item {
                val c = det.sig.first()
                SectionCard(section("Подпись") {
                    add("Владелец", c.subjectX500Principal.name); add("Издатель", c.issuerX500Principal.name); add("Алгоритм", c.sigAlgName)
                    add("Действует", "${d(c.notBefore.time)} — ${d(c.notAfter.time)}")
                    add("SHA-256", MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) })
                    if (det.sig.size > 1) add("Ключей в истории", det.sig.size)
                }, Tints.Lilac)
            }
        }
    }
}

// ---------------- анализатор ----------------
@Composable
private fun AnalyzerTab(apps: List<AppScan.App>, openGroup: (String, List<AppScan.App>) -> Unit) {
    val ctx = LocalContext.current
    var withSystem by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<List<AppScan.Analysis>?>(null) }
    var prog by remember { mutableIntStateOf(0) }
    var dim by remember { mutableIntStateOf(0) }
    val target = apps.filter { withSystem || !it.system }
    LaunchedEffect(withSystem) { res = null; prog = 0; res = withContext(Dispatchers.IO) { target.map { a -> AppScan.analyze(ctx, a).also { prog++ } } } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("С системными", modifier = Modifier.weight(1f)); Switch(withSystem, { withSystem = it }) }
        val r = res
        if (r == null) { Card { Text("Разбираю APK: $prog из ${target.size}", style = MaterialTheme.typography.bodyMedium); Bar(prog / target.size.coerceAtLeast(1).toFloat(), DevColors.series[0]) }; return@Column }
        val dims = listOf("Установщик", "Целевой API", "Мин. API", "Подпись", "Платформа", "Библиотеки", "Место")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { dims.forEachIndexed { i, t -> Chip(t, dim == i, { dim = i }) } }
        val groups: List<Pair<String, List<AppScan.App>>> = when (dim) {
            0 -> r.groupBy { AppScan.installerName(it.app.installer) }.map { it.key to it.value.map { a -> a.app } }
            1 -> r.groupBy { "API ${it.app.target} · ${DevSystem.androidName(it.app.target).first.substringBefore(" (")}" }.map { it.key to it.value.map { a -> a.app } }
            2 -> r.groupBy { "API ${it.app.min} · ${DevSystem.androidName(it.app.min).first.substringBefore(" (")}" }.map { it.key to it.value.map { a -> a.app } }
            3 -> r.groupBy { it.sigAlg ?: "нет данных" }.map { it.key to it.value.map { a -> a.app } }
            4 -> r.groupBy { it.platform }.map { it.key to it.value.map { a -> a.app } }
            5 -> r.flatMap { a -> a.libs.map { it to a.app } }.groupBy({ it.first }, { it.second }).map { it.key to it.value }
            else -> r.groupBy { when (it.app.location) { 0 -> "авто"; 2 -> "предпочитает внешнюю"; 1 -> "только внутренняя"; else -> "внутренняя (по умолчанию)" } }.map { it.key to it.value.map { a -> a.app } }
        }.sortedByDescending { it.second.size }
        Card {
            Donut(groups.map { it.second.size.toFloat() }, 150.dp)
            Spacer(Modifier.height(10.dp))
            val maxN = groups.maxOfOrNull { it.second.size } ?: 1
            groups.forEachIndexed { i, (t, l) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { openGroup(t, l) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(DevColors.series[i % 10]))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(t, style = MaterialTheme.typography.bodyMedium, maxLines = 1); Bar(l.size / maxN.toFloat(), DevColors.series[i % 10], height = 5.dp) }
                    Text("${l.size}", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (dim == 5) Text("SDK определяются по компонентам и файлам APK — внутренний код без запуска не виден, поэтому список неполный.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
}

@Composable
private fun Donut(values: List<Float>, size: Dp) {
    val sum = values.sum().coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            var a = -90f; val w = 34f
            values.forEachIndexed { i, v -> val sw = 360f * v / sum
                drawArc(DevColors.series[i % 10], a, (sw - 1.2f).coerceAtLeast(0.5f), false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w)); a += sw }
        }
        Text("${sum.toInt()}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
    }
}

// ---------------- разрешения и конфиденциальность ----------------
private val GROUPS = listOf(
    "Камера" to listOf("android.permission.CAMERA"), "Микрофон" to listOf("android.permission.RECORD_AUDIO"),
    "Точное местоположение" to listOf("android.permission.ACCESS_FINE_LOCATION"), "Местоположение в фоне" to listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
    "Контакты" to listOf("android.permission.READ_CONTACTS"), "СМС" to listOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS"),
    "Журнал звонков" to listOf("android.permission.READ_CALL_LOG"), "Телефон" to listOf("android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE"),
    "Календарь" to listOf("android.permission.READ_CALENDAR"), "Фото и видео" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE"),
    "Музыка и аудио" to listOf("android.permission.READ_MEDIA_AUDIO"), "Физ. активность" to listOf("android.permission.ACTIVITY_RECOGNITION"),
    "Датчики тела" to listOf("android.permission.BODY_SENSORS"), "Устройства рядом" to listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES"),
    "Уведомления" to listOf("android.permission.POST_NOTIFICATIONS"))
private val SPECIAL = listOf("Поверх других окон" to AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, "Статистика использования" to AppOpsManager.OPSTR_GET_USAGE_STATS,
    "Все файлы" to "android:manage_external_storage", "Установка приложений" to "android:request_install_packages", "Точные будильники" to "android:schedule_exact_alarm",
    "Картинка в картинке" to AppOpsManager.OPSTR_PICTURE_IN_PICTURE)

@Composable
private fun PermsTab(apps: List<AppScan.App>, openGroup: (String, List<AppScan.App>) -> Unit) {
    val ctx = LocalContext.current
    val data = rememberLoad(apps.size) {
        val pm = ctx.packageManager
        val granted = apps.associate { a -> a.pkg to runCatching {
            val pi = pm.getPackageInfo(a.pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
            pi.requestedPermissions.orEmpty().filterIndexed { i, _ -> ((pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }.toSet()
        }.getOrDefault(emptySet()) }
        val ao = ctx.getSystemService(AppOpsManager::class.java)
        val special = SPECIAL.map { (t, op) -> t to apps.filter { a -> runCatching { ao.unsafeCheckOpNoThrow(op, a.uid, a.pkg) == AppOpsManager.MODE_ALLOWED }.getOrDefault(false) } }
        GROUPS.map { (t, ps) -> t to apps.filter { a -> granted[a.pkg].orEmpty().any { it in ps } } } to special
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data == null) { Loading(); return@Column }
        val (groups, special) = data
        Card {
            Text("Кому что разрешено", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text("Нажми на строку — список приложений. «Своих» — без системных.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            groups.forEach { (t, l) -> PermLine(t, l) { openGroup(t, l) } }
        }
        Card {
            Text("Особый доступ", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            special.forEach { (t, l) -> PermLine(t, l) { openGroup(t, l) } }
            val cr = ctx.contentResolver
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.filter { it.isNotBlank() }?.map { it.substringBefore('/') }?.let { acc ->
                val l = apps.filter { it.pkg in acc }; PermLine("Спец. возможности", l) { openGroup("Спец. возможности", l) } }
            Settings.Secure.getString(cr, "enabled_notification_listeners")?.split(':')?.filter { it.isNotBlank() }?.map { it.substringBefore('/') }?.let { nl ->
                val l = apps.filter { it.pkg in nl }; PermLine("Читают уведомления", l) { openGroup("Читают уведомления", l) } }
            Text("Когда приложение последний раз пользовалось камерой или микрофоном, Android сторонним программам не отдаёт — это видно в системной «Панели конфиденциальности».", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            TextButton({ runCatching { ctx.startActivity(Intent("android.intent.action.REVIEW_PERMISSION_USAGE")) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS)) } }) { Text("Открыть системную панель") }
        }
    }
}

@Composable
private fun PermLine(t: String, l: List<AppScan.App>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${l.count { !it.system }} своих", style = MaterialTheme.typography.labelMedium, color = if (l.any { !it.system }) Pal.RoseInk else Pal.Muted)
        Text("  · ${l.size}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
    }
}

// ---------------- трафик и экранное время ----------------
@Composable
private fun UsageGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(AppScan.hasUsage(ctx)) }
    LiveLoop(1500, Unit) { ok = AppScan.hasUsage(ctx) }
    if (ok) content() else Card {
        Text("Нужен доступ к статистике использования", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        Text("Android даёт трафик и экранное время по приложениям только после этого разрешения (включается вручную). Данные остаются на телефоне.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        PillButton("Открыть настройку", { runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun TrafficTab(apps: List<AppScan.App>, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var per by remember { mutableIntStateOf(0) }
    val from = when (per) { 0 -> startOfDay(); 1 -> startOfDay(6); else -> startOfDay(29) }
    val data = rememberLoad(per) { AppScan.traffic(ctx, from) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(listOf("Сегодня", "7 дней", "30 дней"), per, { per = it })
        if (data == null) { Loading(); return@Column }
        val byUid = apps.groupBy { it.uid }
        val rows = data.entries.map { (uid, v) -> Triple(uid, v, byUid[uid]) }.filter { it.second.first + it.second.second > 0 }.sortedByDescending { it.second.first + it.second.second }
        val wifi = data.values.sumOf { it.first }; val mob = data.values.sumOf { it.second }
        Card {
            Row { Column(Modifier.weight(1f)) { Text("Wi-Fi", style = MaterialTheme.typography.labelMedium, color = Pal.SkyInk); Text(Dev.bytes(wifi) ?: "0", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)) }
                Column(Modifier.weight(1f)) { Text("Мобильные", style = MaterialTheme.typography.labelMedium, color = Pal.PeachInk); Text(Dev.bytes(mob) ?: "0", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)) } }
        }
        val maxB = rows.maxOfOrNull { it.second.first + it.second.second } ?: 1
        rows.take(60).forEach { (uid, v, list) ->
            val a = list?.firstOrNull()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(enabled = a != null) { a?.let { open(it.pkg) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (a != null) AppIcon(a.pkg, 36.dp) else Box(Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(a?.label ?: when (uid) { 1000 -> "Система Android"; -4 -> "Удалённые приложения"; -5 -> "Точка доступа"; 0 -> "Ядро"; else -> "UID $uid" } + if ((list?.size ?: 0) > 1) " и ещё ${list!!.size - 1}" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    StackBar(listOf(v.first.toFloat() to DevColors.series[0], v.second.toFloat() to DevColors.series[2], (maxB - v.first - v.second).toFloat() to androidx.compose.ui.graphics.Color.Transparent), height = 6.dp)
                    Text("Wi-Fi ${Dev.bytes(v.first)} · моб. ${Dev.bytes(v.second)}", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
        }
    }
}

@Composable
private fun ScreenTimeTab(apps: List<AppScan.App>, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var per by remember { mutableIntStateOf(0) }
    val data = rememberLoad(per) { AppScan.screenTime(ctx, if (per == 0) startOfDay() else startOfDay(6)) }
    val week = rememberLoad(Unit) { (6 downTo 0).map { i -> val s = startOfDay(i); val e = if (i == 0) System.currentTimeMillis() else startOfDay(i - 1); AppScan.screenTime(ctx, s, e).first.sumOf { it.fgMs } } }
    val byPkg = apps.associateBy { it.pkg }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(listOf("Сегодня", "7 дней"), per, { per = it })
        week?.let { w ->
            Card {
                Text("Экран по дням", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                val mx = w.maxOrNull()?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.forEachIndexed { i, v -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${v / 3_600_000} ч", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                        Box(Modifier.fillMaxWidth().height((80 * v / mx).toInt().coerceAtLeast(2).dp).clip(RoundedCornerShape(5.dp)).background(if (i == 6) Pal.SkyInk else DevColors.series[0]))
                        Text(LocalDate.now().minusDays((6 - i).toLong()).format(DateTimeFormatter.ofPattern("EE", java.util.Locale("ru"))), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    } }
                }
            }
        }
        if (data == null) { Loading(); return@Column }
        val (list, unlocks) = data
        val total = list.sumOf { it.fgMs }
        Card { Text("Всего на экране: ${hmm(total)}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)); Text("Разблокировок: $unlocks · запусков: ${list.sumOf { it.launches }}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        val mx = list.maxOfOrNull { it.fgMs } ?: 1
        list.take(50).forEach { u ->
            val a = byPkg[u.pkg]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { open(u.pkg) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(u.pkg, 36.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(a?.label ?: u.pkg, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Bar(u.fgMs / mx.toFloat(), DevColors.series[0], height = 6.dp)
                    Text("${hmm(u.fgMs)} · открыто ${u.launches} раз", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
        }
    }
}
private fun hmm(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }
