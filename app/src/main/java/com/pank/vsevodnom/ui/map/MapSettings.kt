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

/** Настройки карты — как в Guru Maps, без «объектов на карте», шрифта, темы, главного экрана и автоблокировки. */
@Composable
internal fun MapSettingsScreen(onDismiss: () -> Unit, onChanged: () -> Unit, start: String? = null) {
    var page by remember { mutableStateOf(start) }
    when (page) {
        null -> FullScreen("Настройки карты", onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuRow(Icons.Rounded.Layers, "Источник карты", sub = "офлайн (авто, пешком, вело) или онлайн-карты") { page = "source" }
                MenuRow(Icons.Rounded.CloudDownload, "Загрузка карт", sub = "страны, размеры, обновления") { page = "downloads" }
                MenuRow(Icons.Rounded.Storage, "Кэш карт", sub = "онлайн-тайлы на телефоне") { page = "cache" }
                MenuRow(Icons.Rounded.Navigation, "Навигация", sub = "способ, голос, автомасштаб, запись трека") { page = "nav" }
                MenuRow(Icons.Rounded.Backup, "Резервные копии", sub = "все метки и треки — в файл KML и обратно") { page = "backup" }
                MenuRow(Icons.Rounded.SdCard, "Хранилище карт", sub = "память телефона или карта памяти") { page = "storage" }
                MenuRow(Icons.Rounded.Straighten, "Единицы измерения", sub = "расстояние, высота, координаты") { page = "units" }
                MenuRow(Icons.Rounded.FilterList, "Объекты на карте", sub = "какие значки показывать, номера домов, остановки") { page = "filter" }
                MenuRow(Icons.Rounded.Key, "Ключи и сервисы", sub = "Google (места, отзывы), TomTom (пробки), лимиты") { page = "keys" }
                MenuRow(Icons.Rounded.Lock, "Конфиденциальность", sub = "история, работа без интернета") { page = "privacy" }
                MenuRow(Icons.Rounded.Code, "Для разработчиков") { page = "dev" }
                Text("Данные карт © участники OpenStreetMap (ODbL), стиль — OSM Liberty / OpenMapTiles, рельеф — Mapzen Terrain Tiles, мир — Natural Earth.",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        "source" -> LayersPanel(onDismiss = { page = null }, onChanged = onChanged, onSettings = { page = "downloads" })
        "downloads" -> DownloadsPage(onBack = { if (start != null) onDismiss() else page = null })
        "cache" -> CachePage { page = null }
        "nav" -> NavPage { page = null }
        "backup" -> BackupPage { page = null }
        "storage" -> StoragePage { page = null }
        "units" -> UnitsPage(onChanged) { page = null }
        "privacy" -> PrivacyPage { page = null }
        "dev" -> DevPage(onChanged) { page = null }
        "keys" -> KeysPage(onChanged) { page = null }
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

// ---------------- загрузка карт ----------------

@Composable
private fun DownloadsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<OfflineMaps.Country?>(null) }
    LaunchedEffect(Unit) { loading = true; OfflineMaps.loadCatalog(ctx, force = true); OfflineMaps.resume(ctx); loading = false }
    LaunchedEffect(OfflineMaps.progress.size) { while (OfflineMaps.progress.isNotEmpty()) { delay(1000); tick++ } }
    val installed = remember(OfflineMaps.version, tick) { OfflineMaps.installed(ctx) }
    val cat = OfflineMaps.catalog
    val dir = OfflineMaps.dir(ctx)
    Page("Загрузка карт", onBack) {
        Text("Свободно: ${OfflineMaps.human(OfflineMaps.free(dir))} · карты занимают ${OfflineMaps.human(OfflineMaps.usedBytes(ctx))}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        if (loading && cat == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Загружаю список…") }
        OfflineMaps.catalogError?.takeIf { cat == null }?.let { Text(it, color = Pal.RoseInk) }
        OfflineMaps.lastError?.let { Text(it, color = Pal.RoseInk, modifier = Modifier.clickable { OfflineMaps.lastError = null }) }
        SectionLabel("Мир")
        val world = cat?.world
        WorldRow("Карта мира подробнее", "рельеф мира до масштаба ~10 км (встроенная — до ~100 км)", world, OfflineMaps.worldDetailed(ctx), "world")
        WorldRow("Подписи и границы мира", "страны, города, границы областей на карте мира", cat?.labels, OfflineMaps.worldLabels(ctx) != null, "labels")
        SectionLabel("Страны")
        if (cat != null && cat.countries.isEmpty()) Text("Карты стран ещё собираются на сервере (GitHub). Загляни позже — список обновится сам.", style = MaterialTheme.typography.bodyMedium)
        cat?.countries?.forEach { c ->
            val inst = installed[c.id]
            val prog = OfflineMaps.progress[c.id]
            AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Public, null, tint = if (inst != null) Pal.MintInk else Pal.Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(OfflineMaps.KINDS.filter { c.parts[it].orEmpty().isNotEmpty() }.joinToString(" · ") { k ->
                            OfflineMaps.KIND_NAMES[k] + " " + OfflineMaps.human(c.size(listOf(k))) + if (inst?.second?.contains(k) == true) " ✓" else ""
                        }, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        Text("Сборка ${c.built}" + if (inst != null && OfflineMaps.updateAvailable(ctx, c)) " · есть обновление" else "", style = MaterialTheme.typography.bodySmall, color = if (inst != null && OfflineMaps.updateAvailable(ctx, c)) Pal.PeachInk else Pal.Muted)
                    }
                }
                if (prog != null) {
                    LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 10.dp))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${(prog * 100).toInt()}% из ${OfflineMaps.human(c.size())}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton({ OfflineMaps.cancel(ctx, c.id) }) { Text("Отменить") }
                    }
                } else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val missing = OfflineMaps.KINDS.filter { c.parts[it].orEmpty().isNotEmpty() && inst?.second?.contains(it) != true }
                    if (inst == null) PillButton("Скачать · ${OfflineMaps.human(c.size())}", { ask = c }, Modifier.weight(1f))
                    else {
                        val upd = OfflineMaps.outdatedParts(ctx, c)
                        if (upd.isNotEmpty()) PillButton("Обновить · ${OfflineMaps.human(c.size(upd))}", { OfflineMaps.download(ctx, c, upd); tick++ }, Modifier.weight(1f))
                        else if (missing.isNotEmpty()) PillButton("Докачать · ${OfflineMaps.human(c.size(missing))}", { OfflineMaps.download(ctx, c, missing.toSet()); tick++ }, Modifier.weight(1f), bg = Pal.Lilac, fg = Pal.LilacInk)
                        PillButton("Удалить", { OfflineSearch.closeAll(); OfflineMaps.delete(ctx, c.id); tick++ }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
                    }
                }
            }
        }
        Text("Скачивание идёт в фоне (видно в уведомлениях). Нужен Wi-Fi или много мобильного трафика. Файлы — с сервера GitHub проекта, собраны из OpenStreetMap.",
            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton({ scope.launch { loading = true; OfflineMaps.loadCatalog(ctx, true); loading = false } }) { Text("Обновить список") }
    }
    ask?.let { c ->
        val sel = remember { mutableStateMapOf<String, Boolean>().apply { OfflineMaps.KINDS.forEach { put(it, c.parts[it].orEmpty().isNotEmpty()) } } }
        AlertDialog(onDismissRequest = { ask = null }, title = { Text(c.name) },
            text = {
                Column {
                    OfflineMaps.KINDS.filter { c.parts[it].orEmpty().isNotEmpty() }.forEach { k ->
                        Row(Modifier.fillMaxWidth().clickable { if (k != "map") sel[k] = !(sel[k] ?: false) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(sel[k] == true, { if (k != "map") sel[k] = it }, enabled = k != "map")
                            Text("${OfflineMaps.KIND_NAMES[k]} · ${OfflineMaps.human(c.size(listOf(k)))}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    val total = c.size(sel.filterValues { it }.keys)
                    Text("Всего ${OfflineMaps.human(total)}, свободно ${OfflineMaps.human(OfflineMaps.free(OfflineMaps.dir(ctx)))}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            },
            confirmButton = { TextButton({ OfflineMaps.download(ctx, c, sel.filterValues { it }.keys); ask = null; tick++ }) { Text("Скачать") } },
            dismissButton = { TextButton({ ask = null }) { Text("Отмена") } })
    }
}

@Composable
private fun WorldRow(title: String, sub: String, part: OfflineMaps.Part?, have: Boolean, key: String) {
    val ctx = LocalContext.current
    val prog = OfflineMaps.progress[key]
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(sub + (part?.let { " · " + OfflineMaps.human(it.size) } ?: ""), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            when {
                prog != null -> CircularProgressIndicator({ prog }, Modifier.size(28.dp))
                have -> TextButton({ part?.let { OfflineMaps.deleteWorld(ctx, it.file) } }) { Text("Удалить", color = Pal.RoseInk) }
                part != null -> TextButton({ OfflineMaps.downloadWorld(ctx, part, key) }) { Text("Скачать") }
                else -> Text("скоро", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }
    }
}

// ---------------- кэш ----------------

@Composable
private fun CachePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var size by remember { mutableLongStateOf(OnlineMaps.cacheBytes(ctx)) }
    var limit by remember { mutableLongStateOf(OnlineMaps.cacheLimit(ctx)) }
    var old by remember { mutableStateOf<List<MapKit.Region>>(emptyList()) }
    LaunchedEffect(Unit) { MapKit.list(ctx) { old = it } }
    Page("Кэш карт", onBack) {
        AppCard(Modifier.fillMaxWidth()) {
            Text("Онлайн-тайлы на телефоне: ${OfflineMaps.human(size)}", style = MaterialTheme.typography.titleMedium)
            Text("Всё, что смотрел на онлайн-картах, остаётся и открывается без интернета. Само не удаляется — только при достижении предела ниже.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
        Choice("Предел кэша", listOf("1073741824" to "1 ГБ", "2147483648" to "2 ГБ", "5368709120" to "5 ГБ", "10737418240" to "10 ГБ", "0" to "Без предела"), limit.toString()) {
            limit = it.toLong(); OnlineMaps.applyCacheLimit(ctx, limit)
        }
        PillButton("Очистить кэш", { OnlineMaps.clearCache(ctx) { size = OnlineMaps.cacheBytes(ctx); Toast.makeText(ctx, "Кэш очищен", Toast.LENGTH_SHORT).show() } }, Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
        if (old.isNotEmpty()) {
            SectionLabel("Старые офлайн-области (версия 0.15)")
            old.forEach { r ->
                MenuRow(Icons.Rounded.DeleteOutline, "Удалить «${r.name}»", Pal.RoseInk) { MapKit.delete(r.r) { MapKit.list(ctx) { l -> old = l; size = OnlineMaps.cacheBytes(ctx) } } }
            }
            Text("Они были для онлайн-стиля OpenFreeMap. Теперь карты стран скачиваются целиком в «Загрузке карт».", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}

// ---------------- навигация ----------------

@Composable
private fun NavPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(MapPrefs.navMode(ctx)) }
    var voice by remember { mutableStateOf(MapPrefs.bool(ctx, "voice", true)) }
    var preferOff by remember { mutableStateOf(MapPrefs.bool(ctx, "prefer_offline_route", false)) }
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
            SwitchRow("Предпочитать офлайн-маршруты", "строить на телефоне даже с интернетом (без пробок)", preferOff, enabled = Nav.offlineAvailable(ctx)) { preferOff = it; MapPrefs.setBool(ctx, "prefer_offline_route", it) }
            SwitchRow("Избегать платных дорог", "офлайн, TomTom и OSRM", avoidToll) { avoidToll = it; MapPrefs.setBool(ctx, "avoid_toll", it) }
            SwitchRow("Избегать паромов", "TomTom и OSRM (офлайн-профиль паромы и так не любит)", avoidFerry) { avoidFerry = it; MapPrefs.setBool(ctx, "avoid_ferry", it) }
            SwitchRow("Автомасштаб", "в движении карта сама отдаляется по скорости", autozoom) { autozoom = it; MapPrefs.setBool(ctx, "autozoom", it) }
            SwitchRow("Онлайн-маршруты", "строить по дорогам через интернет (OSRM)", onlineNav) { onlineNav = it; MapPrefs.setBool(ctx, "online_nav", it) }
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

// ---------------- хранилище ----------------

@Composable
private fun StoragePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember { OfflineMaps.storages(ctx) }
    var cur by remember { mutableIntStateOf(OfflineMaps.storageIndex(ctx)) }
    var busy by remember { mutableStateOf(false) }
    Page("Хранилище карт", onBack) {
        list.forEachIndexed { i, f ->
            val name = if (i == 0) "Память телефона" else "Карта памяти"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(enabled = !busy && i != cur) {
                busy = true; scope.launch { OfflineSearch.closeAll(); OfflineMaps.moveStorage(ctx, i); cur = i; busy = false }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(cur == i, null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text("Свободно ${OfflineMaps.human(OfflineMaps.free(f))}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Переношу карты…") }
        Text("Карты лежат в папке приложения (Android/data/…/maps). При удалении приложения удаляются и они.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
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
    val scope = rememberCoroutineScope()
    var tiles by remember { mutableStateOf(MapPrefs.bool(ctx, "dev_tiles", false)) }
    var zoom by remember { mutableStateOf(MapPrefs.bool(ctx, "dev_zoom", false)) }
    Page("Для разработчиков", onBack) {
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            SwitchRow("Границы тайлов", "сетка и номера тайлов поверх карты", tiles) { tiles = it; MapPrefs.setBool(ctx, "dev_tiles", it); onChanged() }
            SwitchRow("Показывать зум", null, zoom) { zoom = it; MapPrefs.setBool(ctx, "dev_zoom", it) }
        }
        MenuRow(Icons.Rounded.Refresh, "Пересобрать стиль карты") { onChanged(); Toast.makeText(ctx, "Готово", Toast.LENGTH_SHORT).show() }
        MenuRow(Icons.Rounded.CloudSync, "Перечитать каталог карт") { scope.launch { OfflineMaps.loadCatalog(ctx, true); Toast.makeText(ctx, OfflineMaps.catalogError ?: "Стран в каталоге: ${OfflineMaps.catalog?.countries?.size ?: 0}", Toast.LENGTH_SHORT).show() } }
        AppCard(Modifier.fillMaxWidth()) {
            Text("Каталог: ${OfflineMaps.BASE}catalog.json", style = MaterialTheme.typography.bodySmall)
            Text("Папка карт: ${OfflineMaps.dir(ctx).path}", style = MaterialTheme.typography.bodySmall)
            Text("Скачано: ${OfflineMaps.installed(ctx).entries.joinToString { "${it.key} (${it.value.first}: ${it.value.second.joinToString("+")})" }.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            Text("Рельеф мира: ${OfflineMaps.worldRelief(ctx).name}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- ключи ----------------

@Composable
private fun KeysPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var google by remember { mutableStateOf(ApiKeys.google(ctx)) }
    var tomtom by remember { mutableStateOf(ApiKeys.tomtom(ctx)) }
    var gLimit by remember { mutableStateOf(ApiKeys.limit(ctx, "google").toString()) }
    var tLimit by remember { mutableStateOf(ApiKeys.limit(ctx, "tomtom").toString()) }
    var gAuto by remember { mutableStateOf(MapPrefs.bool(ctx, "google_auto", true)) }
    var gSearch by remember { mutableStateOf(MapPrefs.bool(ctx, "google_default", true)) }
    var ttRoute by remember { mutableStateOf(MapPrefs.bool(ctx, "tomtom_route", true)) }
    Page("Ключи и сервисы", onBack) {
        Text("Ключи хранятся только на телефоне (в коде и на GitHub их нет). Каждый запрос считается — при достижении дневного предела приложение перестаёт обращаться к сервису до завтра.",
            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        AppCard(Modifier.fillMaxWidth()) {
            Text("Google Places", style = MaterialTheme.typography.titleMedium)
            AppTextField(google, { google = it }, "Ключ API (AIza…)")
            AppTextField(gLimit, { gLimit = it.filter(Char::isDigit) }, "Запросов в день (рекомендую 150)")
            Text("Сегодня: ${ApiKeys.used(ctx, "google")} из ${ApiKeys.limit(ctx, "google")}. Поиск — 1 запрос, сведения о месте — 2, каждое фото — 1.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            SwitchRow("Искать через Google по умолчанию", null, gSearch) { gSearch = it; MapPrefs.setBool(ctx, "google_default", it) }
            SwitchRow("Сведения Google сразу в карточке", "иначе — по кнопке, экономит лимит", gAuto) { gAuto = it; MapPrefs.setBool(ctx, "google_auto", it) }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Text("TomTom", style = MaterialTheme.typography.titleMedium)
            AppTextField(tomtom, { tomtom = it }, "Ключ API")
            AppTextField(tLimit, { tLimit = it.filter(Char::isDigit) }, "Маршрутов в день")
            Text("Сегодня маршрутов: ${ApiKeys.used(ctx, "tomtom")}. Слой пробок включается в «Слоях».", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            SwitchRow("Маршрут на машине с учётом пробок", "иначе — OSRM без пробок", ttRoute) { ttRoute = it; MapPrefs.setBool(ctx, "tomtom_route", it) }
        }
        PillButton("Сохранить", {
            ApiKeys.set(ctx, "google", google); ApiKeys.set(ctx, "tomtom", tomtom)
            gLimit.toIntOrNull()?.let { ApiKeys.setLimit(ctx, "google", it) }; tLimit.toIntOrNull()?.let { ApiKeys.setLimit(ctx, "tomtom", it) }
            onChanged(); Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth())
        Text("Google: в консоли ограничь ключ «Приложения Android» (пакет и SHA-1 — в Настройках → О приложении) и API «Places API (New)». Waze: кнопка в карточке и маршруте открывает навигацию в Waze.",
            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    }
}

@Composable
private fun FilterPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = MapPrefs.p(ctx)
    var hidden by remember { mutableStateOf(p.getStringSet("hidden_poi", MapStyle.DEFAULT_HIDDEN)!!.toSet()) }
    fun save(h: Set<String>) { hidden = h; p.edit().putStringSet("hidden_poi", h).apply(); onChanged() }
    Page("Объекты на карте", onBack) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card)) {
            MapStyle.POI_GROUPS.entries.forEachIndexed { i, (k, g) ->
                val on = k !in hidden
                Row(Modifier.fillMaxWidth().clickable { save(if (on) hidden + k else hidden - k) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(g.color)), contentAlignment = Alignment.Center) {
                        com.pank.vsevodnom.ui.money.TablerIcons.vector(ctx, g.icon)?.let { Icon(it, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) }
                    }
                    Text(g.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                    if (on) Icon(Icons.Rounded.Check, "Показано", tint = Pal.SkyInk)
                }
                if (i < MapStyle.POI_GROUPS.size - 1) HorizontalDivider(color = Pal.Line, modifier = Modifier.padding(start = 60.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Показать всё", { save(emptySet()) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
            PillButton("Как было", { save(MapStyle.DEFAULT_HIDDEN) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        }
    }
}
