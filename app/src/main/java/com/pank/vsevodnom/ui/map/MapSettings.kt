package com.pank.vsevodnom.ui.map

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Настройки карты (GLMap): загрузки, слои, объекты, навигация, ключи, копии, единицы. */
@Composable
internal fun MapSettingsScreen(onDismiss: () -> Unit, onChanged: () -> Unit, start: String? = null) {
    var page by remember { mutableStateOf(start) }
    when (page) {
        null -> FullScreen("Настройки карты", onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuRow(Icons.Rounded.CloudDownload, "Загрузка карт", sub = "библиотека стран: карта, навигация, топография; обновления") { page = "downloads" }
                MenuRow(Icons.Rounded.FilterList, "Объекты на карте", sub = "какие значки показывать, маршруты OSM") { page = "filter" }
                MenuRow(Icons.Rounded.Navigation, "Навигация", sub = "способ, голос, автомасштаб, запись трека") { page = "nav" }
                MenuRow(Icons.Rounded.Storage, "Кэш карт", sub = "онлайн-тайлы и рельеф на телефоне") { page = "cache" }
                MenuRow(Icons.Rounded.Backup, "Резервные копии", sub = "все метки и треки — в файл KML и обратно") { page = "backup" }
                MenuRow(Icons.Rounded.Straighten, "Единицы измерения", sub = "расстояние, высота, координаты, язык подписей") { page = "units" }
                MenuRow(Icons.Rounded.Lock, "Конфиденциальность", sub = "история, работа без интернета") { page = "privacy" }
                MenuRow(Icons.Rounded.Code, "Для разработчиков") { page = "dev" }
                Text("Карта © участники OpenStreetMap (ODbL), движок GLMap © GLOBUS (globus.software). Рельеф мира — Natural Earth / Mapzen.",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        "downloads" -> GlDownloadsPage(onBack = { if (start != null) onDismiss() else page = null })
        "cache" -> CachePage { page = null }
        "nav" -> NavPage { page = null }
        "backup" -> BackupPage { page = null }
        "units" -> UnitsPage(onChanged) { page = null }
        "privacy" -> PrivacyPage { page = null }
        "dev" -> DevPage(onChanged) { page = null }
        "keys", "engine" -> KeysPage(onChanged) { if (start != null) onDismiss() else page = null }
        "filter" -> FilterPage(onChanged) { page = null }
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) = FullScreen(title, onBack) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    AppCard(Modifier.fillMaxWidth(), padding = 6.dp) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(10.dp, 6.dp))
        options.forEach { (k, n) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(k) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == k, { onPick(k) }); Text(n, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------- кэш ----------------

@Composable
private fun CachePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var raster by remember { mutableLongStateOf(OnlineMaps.cacheBytes(ctx)) }
    var gl by remember { mutableLongStateOf(GlMap.cacheSize()) }
    Page("Кэш карт", onBack) {
        AppCard(Modifier.fillMaxWidth()) {
            Text("Онлайн-тайлы GLMap и рельеф: ${MapKit.human(gl)}", style = MaterialTheme.typography.titleMedium)
            Text("То, что смотрел без скачанной карты. Скачанные карты сюда не входят.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
        PillButton("Очистить кэш GLMap", { GlMap.dropCache(); gl = GlMap.cacheSize(); Toast.makeText(ctx, "Очищено", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
        AppCard(Modifier.fillMaxWidth()) {
            Text("Онлайн-карты (OSM, спутник, .ms): ${MapKit.human(raster)}", style = MaterialTheme.typography.titleMedium)
        }
        PillButton("Очистить кэш онлайн-карт", { OnlineMaps.clearCache(ctx); raster = OnlineMaps.cacheBytes(ctx); Toast.makeText(ctx, "Очищено (до перезапуска карты часть тайлов может остаться)", Toast.LENGTH_LONG).show() },
            Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
    }
}

// ---------------- навигация ----------------

@Composable
private fun NavPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(MapPrefs.navMode(ctx)) }
    var voice by remember { mutableStateOf(MapPrefs.bool(ctx, "voice", true)) }
    var avoidToll by remember { mutableStateOf(MapPrefs.bool(ctx, "avoid_toll", false)) }
    var avoidFerry by remember { mutableStateOf(MapPrefs.bool(ctx, "avoid_ferry", false)) }
    var autozoom by remember { mutableStateOf(MapPrefs.bool(ctx, "autozoom", true)) }
    var autosave by remember { mutableStateOf(MapPrefs.bool(ctx, "autosave_track", true)) }
    var onlineNav by remember { mutableStateOf(MapPrefs.bool(ctx, "online_nav", true)) }
    var folder by remember { mutableStateOf(MapPrefs.trackFolder(ctx)) }
    var pick by remember { mutableStateOf(false) }
    val folders by AppDb.get(ctx).map().folders().collectAsState(initial = emptyList())
    Page("Навигация", onBack) {
        Choice("Способ передвижения по умолчанию", listOf("car" to "На машине", "bike" to "На велосипеде", "foot" to "Пешком", "line" to "По прямой"), mode) { mode = it; MapPrefs.setStr(ctx, "nav_mode", it) }
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            SwitchRow("Голосовые подсказки", "«Через 300 метров поверните направо» — голосом телефона", voice) { voice = it; MapPrefs.setBool(ctx, "voice", it); Nav.muted = !it }
            SwitchRow("Избегать платных дорог", "офлайн и онлайн", avoidToll) { avoidToll = it; MapPrefs.setBool(ctx, "avoid_toll", it) }
            SwitchRow("Избегать паромов", "офлайн и онлайн", avoidFerry) { avoidFerry = it; MapPrefs.setBool(ctx, "avoid_ferry", it) }
            SwitchRow("Автомасштаб", "в движении карта сама отдаляется по скорости", autozoom) { autozoom = it; MapPrefs.setBool(ctx, "autozoom", it) }
            SwitchRow("Онлайн-маршруты как запасной путь", "если офлайн не построился (нет навигационных данных) — через интернет", onlineNav) { onlineNav = it; MapPrefs.setBool(ctx, "online_nav", it) }
            SwitchRow("Автосохранение трека", "после «Стоп» трек сразу в коллекции; выкл. — спросить имя", autosave) { autosave = it; MapPrefs.setBool(ctx, "autosave_track", it) }
        }
        MenuRow(Icons.Rounded.Folder, "Папка для треков", sub = folderPath(folder, folders)) { pick = true }
    }
    if (pick) FolderPicker(folders, onDismiss = { pick = false }) { folder = it; MapPrefs.setTrackFolder(ctx, it); pick = false }
}

// ---------------- резервные копии ----------------

@Composable
private fun BackupPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = "Сохраняю…"
        AppScope.io.launch {
            val dao = AppDb.get(ctx).map()
            val r = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(GeoFiles.kmlOf("Коллекции", dao.foldersNow(), dao.placesNow(), dao.tracksNow(), null).toByteArray()) } }
            withContext(Dispatchers.Main) { busy = null; Toast.makeText(ctx, if (r.isSuccess) "Сохранено" else "Ошибка: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = "Читаю…"
        AppScope.io.launch {
            val msg = runCatching { val root = GeoFiles.read(ctx, uri, "Восстановлено"); "Добавлено: ${GeoFiles.save(ctx, root, null)}" }.getOrElse { "Ошибка: ${it.message}" }
            withContext(Dispatchers.Main) { busy = null; Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }
    Page("Резервные копии", onBack) {
        Text("Все метки и треки с папками — в один файл KML (его открывают Guru Maps, Google Earth, Organic Maps). Полная копия всего приложения — в Настройках приложения.",
            style = MaterialTheme.typography.bodyMedium)
        PillButton("Сохранить в файл", { save.launch("Коллекции карты.kml") }, Modifier.fillMaxWidth())
        PillButton("Загрузить из файла (GPX, KML, KMZ)", { load.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), bg = Pal.Lilac, fg = Pal.LilacInk)
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------- единицы ----------------

@Composable
private fun UnitsPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var units by remember { mutableStateOf(MapPrefs.str(ctx, "units", "metric")) }
    var coords by remember { mutableStateOf(MapPrefs.str(ctx, "coord_fmt", "dd")) }
    var lang by remember { mutableStateOf(MapPrefs.str(ctx, "lang", "local")) }
    Page("Единицы измерения", onBack) {
        Choice("Расстояние и скорость", listOf("metric" to "Километры, метры", "imperial" to "Мили, футы"), units) { units = it; MapPrefs.setStr(ctx, "units", it) }
        Choice("Координаты", listOf("dd" to "50.450100, 30.523400", "ddm" to "50°27.006'N 30°31.404'E", "dms" to "50°27'00.4\"N 30°31'24.2\"E"), coords) { coords = it; MapPrefs.setStr(ctx, "coord_fmt", it) }
        Choice("Язык подписей на карте", listOf("local" to "Местные названия (в Украине — украинские)", "ru" to "Русские, где есть", "en" to "Английские / латиница"), lang) { lang = it; MapPrefs.setStr(ctx, "lang", it); onChanged() }
    }
}

// ---------------- конфиденциальность ----------------

@Composable
private fun PrivacyPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var noHistory by remember { mutableStateOf(MapPrefs.bool(ctx, "no_history", false)) }
    var offline by remember { mutableStateOf(MapPrefs.offlineOnly(ctx)) }
    Page("Конфиденциальность", onBack) {
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            SwitchRow("Не запоминать поиск и недавние места", null, noHistory) { noHistory = it; MapPrefs.setBool(ctx, "no_history", it); if (it) MapPrefs.clearRecents(ctx) }
            SwitchRow("Только без интернета", "не отправлять запросы в OpenStreetMap, Википедию и сервер маршрутов", offline) { offline = it; MapPrefs.setBool(ctx, "offline_only", it) }
        }
        PillButton("Очистить историю поиска и недавние", { MapPrefs.clearRecents(ctx); Toast.makeText(ctx, "Очищено", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
        Text("Где ты был, знает только телефон: треки и метки хранятся в приложении. Онлайн-карты и поиск видят запрошенные места (как любой сайт).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    }
}

// ---------------- для разработчиков ----------------

@Composable
private fun DevPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var zoom by remember { mutableStateOf(MapPrefs.bool(ctx, "dev_zoom", false)) }
    var fps by remember { mutableStateOf(MapPrefs.bool(ctx, "dev_fps", false)) }
    Page("Для разработчиков", onBack) {
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            SwitchRow("Показывать зум", null, zoom) { zoom = it; MapPrefs.setBool(ctx, "dev_zoom", it) }
            SwitchRow("Счётчик кадров GLMap", null, fps) { fps = it; MapPrefs.setBool(ctx, "dev_fps", it); onChanged() }
        }
        MenuRow(Icons.Rounded.Refresh, "Пересобрать стиль карты") { onChanged(); Toast.makeText(ctx, "Готово", Toast.LENGTH_SHORT).show() }
        AppCard(Modifier.fillMaxWidth()) {
            Text("GLMap: ${if (GlMap.ready) "работает" else GlMap.error ?: "не запущен"}", style = MaterialTheme.typography.bodySmall)
            Text("Сервер: ${GlMap.server ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("Скачано карт: ${GlMap.downloaded().size}", style = MaterialTheme.typography.bodySmall)
            Text("Стиль: ${GlEngine.styleReport}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- объекты на карте ----------------

@Composable
private fun FilterPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(MapStyle.hidden(ctx)) }
    fun save(h: Set<String>) { hidden = h; MapStyle.setHidden(ctx, h); onChanged() }
    @Composable
    fun group(list: Map<String, MapStyle.Group>) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card)) {
            list.entries.forEachIndexed { i, (k, g) ->
                val on = k !in hidden
                Row(Modifier.fillMaxWidth().clickable { save(if (on) hidden + k else hidden - k) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(g.color)), contentAlignment = Alignment.Center) {
                        com.pank.vsevodnom.ui.money.TablerIcons.vector(ctx, g.icon)?.let { Icon(it, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) }
                    }
                    Text(g.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                    if (on) Icon(Icons.Rounded.Check, "Показано", tint = Pal.SkyInk)
                }
                if (i < list.size - 1) HorizontalDivider(color = Pal.Line, modifier = Modifier.padding(start = 60.dp))
            }
        }
    }
    val online = remember { MapStyle.opts(ctx).online }
    Page("Объекты на карте", onBack) {
        // онлайн-карта — готовые картинки: значки и подписи в них «впечатаны», скрыть их нельзя
        if (online != null) AppCard(Modifier.fillMaxWidth()) {
            Text("Сейчас включена онлайн-карта «${online.name}». Это готовые картинки с сервера — значки и подписи на них скрыть нельзя. " +
                "Скрытие работает на обычной (векторной) карте: кнопка слоёв на карте → без онлайн-карты.",
                style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
        }
        group(MapStyle.GROUPS)
        Text("Маршруты OpenStreetMap", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        group(MapStyle.ROUTE_GROUPS)
        Text("Подписи и метро", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        group(MapStyle.LABEL_GROUPS)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Показать всё", { save(emptySet()) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
            PillButton("Как было", { save(MapStyle.DEFAULT_HIDDEN) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        }
    }
}
