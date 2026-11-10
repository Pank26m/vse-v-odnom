package com.pank.vsevodnom.ui.map

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.ApiKeys
import com.pank.vsevodnom.data.AppScope
import kotlinx.coroutines.launch
import com.pank.vsevodnom.data.GCache
import com.pank.vsevodnom.data.GlMap
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import globus.glmap.GLMapInfo
import globus.glmap.GLMapManager

/** На карте: эта страна не скачана — маленькая плашка «Скачать … · размер» (карта + рельеф для 3D без сети). */
@Composable
internal fun GlDownloadHint(live: MapLive) {
    val ctx = LocalContext.current
    val c = live.center ?: return
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    val m = remember(c.lat.toInt(), c.lon.toInt(), v) { GlMap.mapAt(c.lat, c.lon) } ?: return
    val prefs = remember { MapPrefs.p(ctx) }
    var off by remember(m.mapID) { mutableStateOf(prefs.getBoolean("gl_hint_off_${m.mapID}", false)) }
    val prog = GlMap.progress(m)
    val need = GlMap.pending(m)
    if (off || (need == 0 && prog == null)) return
    Row(Modifier.shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)
        .clickable { if (prog == null) { GlMap.download(m); Toast.makeText(ctx, "Скачиваю «${GlMap.name(m)}»: карта и рельеф", Toast.LENGTH_SHORT).show() } }
        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (prog == null) Icons.Rounded.Download else Icons.Rounded.Downloading, null, tint = Pal.SkyInk, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (prog != null) "${GlMap.name(m)} · ${(prog * 100).toInt()}%" else "${GlMap.name(m)} · ${MapKit.human(GlMap.sizeOnServer(m, need))}",
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (prog == null) IconButton({ off = true; prefs.edit().putBoolean("gl_hint_off_${m.mapID}", true).apply() }, Modifier.size(30.dp)) {
            Icon(Icons.Rounded.Close, "Не предлагать", tint = Pal.Muted, modifier = Modifier.size(16.dp))
        } else Spacer(Modifier.width(8.dp))
    }
}

// ---------------- загрузка карт (библиотека как в Guru) ----------------

/** Страны по континентам (ISO 3166-1 alpha-2). */
private val CONTINENTS: List<Pair<String, Set<String>>> = listOf(
    "Европа" to "AD AL AT BA BE BG BY CH CY CZ DE DK EE ES FI FO FR GB GG GI GR HR HU IE IM IS IT JE LI LT LU LV MC MD ME MK MT NL NO PL PT RO RS RU SE SI SK SM UA VA XK AX SJ".split(" ").toSet(),
    "Азия" to "AE AF AM AZ BD BH BN BT CN GE HK ID IL IN IQ IR JO JP KG KH KP KR KW KZ LA LB LK MM MN MO MV MY NP OM PH PK PS QA SA SG SY TH TJ TL TM TR TW UZ VN YE".split(" ").toSet(),
    "Африка" to "AO BF BI BJ BW CD CF CG CI CM CV DJ DZ EG EH ER ET GA GH GM GN GQ GW KE KM LR LS LY MA MG ML MR MU MW MZ NA NE NG RE RW SC SD SH SL SN SO SS ST SZ TD TG TN TZ UG YT ZA ZM ZW".split(" ").toSet(),
    "Северная Америка" to "AG AI AW BB BL BM BQ BS BZ CA CR CU CW DM DO GD GL GP GT HN HT JM KN KY LC MF MQ MS MX NI PA PM PR SV SX TC TT US VC VG VI".split(" ").toSet(),
    "Южная Америка" to "AR BO BR CL CO EC FK GF GY PE PY SR UY VE".split(" ").toSet(),
    "Австралия и Океания" to "AS AU CK FJ FM GU KI MH MP NC NF NR NU NZ PF PG PN PW SB TK TO TV VU WF WS".split(" ").toSet(),
    "Антарктида" to "AQ GS HM BV TF".split(" ").toSet(),
)

private fun continentOf(m: GLMapInfo): String {
    val iso = runCatching { m.isoCode }.getOrNull()?.uppercase()?.take(2) ?: return "Другое"
    return CONTINENTS.firstOrNull { iso in it.second }?.first ?: "Другое"
}

private fun mapsOf(a: Array<GLMapInfo>?): List<GLMapInfo> = a.orEmpty().sortedBy { GlMap.name(it) }
private fun leaves(m: GLMapInfo): List<GLMapInfo> = if (m.isCollection) mapsOf(m.maps).flatMap { leaves(it) } else listOf(m)
private fun onDevice(m: GLMapInfo): Boolean = leaves(m).any { GlMap.sizeOnDisk(it) > 0 || GlMap.progress(it) != null }

/** Настройки → «Загрузка карт»: скачанные сверху, проверка обновлений, библиотека мира по континентам. */
@Composable
internal fun GlDownloadsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    var open by remember { mutableStateOf<GLMapInfo?>(null) }            // открытая страна-коллекция (области)
    var continent by remember { mutableStateOf<String?>(null) }
    val all = remember(v) { if (GlMap.ready) runCatching { mapsOf(GLMapManager.GetMaps()) }.getOrDefault(emptyList()) else emptyList() }
    LaunchedEffect(Unit) { if (GlMap.ready && all.isEmpty()) GlMap.refreshList() }
    val title = open?.let { GlMap.name(it) } ?: continent ?: "Загрузка карт"
    FullScreen(title, { when { open != null -> open = null; continent != null -> continent = null; else -> onBack() } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!GlMap.ready) { Text("Нужен ключ GLMap: Настройки карты → Ключи и сервисы.", color = Pal.RoseInk); return@Column }
            val sel = open
            when {
                sel != null -> mapsOf(sel.maps).forEach { GlMapRow(it) }
                continent != null -> all.filter { continentOf(it) == continent }.forEach { m ->
                    if (m.isCollection) CountryRow(m) { open = m } else GlMapRow(m)
                }
                else -> {
                    val have = all.filter { onDevice(it) }.flatMap { if (it.isCollection) leaves(it).filter { l -> onDevice(l) } else listOf(it) }
                    val upd = have.filter { GlMap.pending(it, GLMapInfo.DataSetMask.ALL) and (it.dataSetsWithState(GLMapInfo.State.NEED_UPDATE)) != 0 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Скачанные", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton({ GlMap.refreshList(); Toast.makeText(ctx, "Проверяю обновления…", Toast.LENGTH_SHORT).show() }, enabled = !GlMap.listLoading) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (GlMap.listLoading) "Проверяю…" else "Проверить обновления")
                        }
                    }
                    if (upd.isNotEmpty()) PillButton("Обновить всё (${upd.size})", { upd.forEach { m -> GlMap.downloadSet(m, m.dataSetsWithState(GLMapInfo.State.NEED_UPDATE)) } }, Modifier.fillMaxWidth())
                    if (have.isEmpty()) Text("Пока ничего. Без скачанных карт GLMap показывает карту онлайн; для работы без сети, офлайн-маршрутов и 3D-рельефа скачай страну или область.",
                        style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    have.forEach { GlMapRow(it) }
                    SectionLabel("Все карты мира")
                    when {
                        all.isEmpty() && GlMap.listLoading -> Text("Загружаю список карт…", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        all.isEmpty() -> {
                            Text(GlMap.listError?.let { "Список карт не загрузился: $it" } ?: "Список карт пуст.", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
                            PillButton("Обновить список", { GlMap.refreshList() }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                        }
                        else -> (CONTINENTS.map { it.first } + "Другое").forEach { c ->
                            val n = all.count { continentOf(it) == c }
                            if (n > 0) MenuRow(Icons.Rounded.Public, c, sub = "$n " + plural(n, "страна", "страны", "стран")) { continent = c }
                        }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return if (m10 == 1 && m100 != 11) one else if (m10 in 2..4 && m100 !in 12..14) few else many
}

@Composable
private fun CountryRow(m: GLMapInfo, onOpen: () -> Unit) {
    val n = runCatching { m.maps?.size ?: 0 }.getOrDefault(0)
    val have = leaves(m).count { onDevice(it) }
    MenuRow(Icons.Rounded.Map, GlMap.name(m), sub = "$n " + plural(n, "область", "области", "областей") + if (have > 0) " · скачано $have" else "") { onOpen() }
}

private val SETS = listOf(
    Triple(GLMapInfo.DataSetMask.MAP, "Карта", Icons.Rounded.Map),
    Triple(GLMapInfo.DataSetMask.NAVIGATION, "Навигация", Icons.Rounded.Navigation),
    Triple(GLMapInfo.DataSetMask.ELEVATION, "Топография", Icons.Rounded.Terrain),
)

/** Страна или область: три строки — карта, навигационные данные, топография (рельеф для 3D и высот). */
@Composable
private fun GlMapRow(m: GLMapInfo) {
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.mapID, GlMap.progress(m) != null) { while (GlMap.progress(m) != null) { kotlinx.coroutines.delay(700); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    AppCard(Modifier.fillMaxWidth()) {
        Text(GlMap.name(m), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        SETS.forEach { (mask, name, icon) ->
            val size = GlMap.sizeOnServer(m, mask)
            if (size <= 0) return@forEach
            val st = GlMap.state(m, mask)
            val prog = GlMap.progressOf(m, mask)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (st == GLMapInfo.State.DOWNLOADED) Pal.MintInk else Pal.Muted, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(when {
                        prog != null -> "Скачиваю… ${(prog * 100).toInt()}% из ${MapKit.human(size)}"
                        st == GLMapInfo.State.DOWNLOADED -> "Скачано"
                        st == GLMapInfo.State.NEED_UPDATE -> "Есть обновление · ${MapKit.human(size)}"
                        st == GLMapInfo.State.NEED_RESUME -> "Прервано — докачать"
                        else -> MapKit.human(size)
                    }, style = MaterialTheme.typography.bodySmall, color = if (st == GLMapInfo.State.NEED_UPDATE) Pal.PeachInk else Pal.Muted)
                    if (prog != null) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                when {
                    prog != null -> SetButton(Icons.Rounded.Close, "Отменить", Pal.Muted) { GlMap.cancelSet(m, mask) }
                    st == GLMapInfo.State.DOWNLOADED -> SetButton(Icons.Rounded.DeleteOutline, "Удалить", Pal.RoseInk) { GlMap.deleteSet(m, mask) }
                    st == GLMapInfo.State.NEED_UPDATE -> Row {
                        SetButton(Icons.Rounded.Refresh, "Обновить", Pal.SkyInk) { GlMap.downloadSet(m, mask) }
                        SetButton(Icons.Rounded.DeleteOutline, "Удалить", Pal.RoseInk) { GlMap.deleteSet(m, mask) }
                    }
                    else -> SetButton(Icons.Rounded.Download, "Скачать", Pal.SkyInk) { GlMap.downloadSet(m, mask) }
                }
            }
        }
    }
}

@Composable
private fun SetButton(icon: ImageVector, d: String, tint: Color, onClick: () -> Unit) = IconButton(onClick) { Icon(icon, d, tint = tint) }

// ---------------- ключи и сервисы ----------------

private class Service(val name: String, val what: String, val url: String, val icon: ImageVector)

private val SERVICES = listOf(
    Service("GLMap (движок Guru Maps)", "карта, 3D-рельеф, офлайн-карты, поиск и маршруты", "https://user.globus.software/apps/", Icons.Rounded.Terrain),
    Service("Google Cloud", "ключ Places API, лимиты и оплата", "https://console.cloud.google.com/apis/credentials", Icons.Rounded.Cloud),
    Service("Google Карты", "открыть место в Google по названию", "https://www.google.com/maps", Icons.Rounded.Map),
    Service("OpenStreetMap", "данные карты, сведения о местах, правки", "https://www.openstreetmap.org", Icons.Rounded.Public),
    Service("Nominatim", "адрес по точке, сведения о месте, границы районов", "https://nominatim.openstreetmap.org", Icons.Rounded.Search),
    Service("Overpass API", "вся улица целиком (онлайн)", "https://overpass-api.de", Icons.Rounded.Route),
    Service("OSRM (FOSSGIS)", "запасной онлайн-маршрут", "https://routing.openstreetmap.de", Icons.Rounded.Directions),
    Service("Википедия и Викиданные", "описания мест, троп и улиц, фото", "https://ru.wikipedia.org", Icons.AutoMirrored.Rounded.MenuBook),
    Service("Waze", "навигация на машине", "https://www.waze.com", Icons.Rounded.Navigation),
    Service("TripAdvisor", "отзывы о местах", "https://www.tripadvisor.com", Icons.Rounded.TravelExplore),
    Service("Booking", "жильё по названию гостиницы", "https://www.booking.com", Icons.Rounded.Hotel),
    Service("Airbnb", "жильё рядом", "https://www.airbnb.com", Icons.Rounded.Cottage),
    Service("EasyWay", "общественный транспорт (в планах)", "https://www.eway.in.ua", Icons.Rounded.DirectionsBus),
    Service("Онлайн-карты", "OpenStreetMap, OpenTopoMap, Esri (спутник), CyclOSM, свои .ms", "https://wiki.openstreetmap.org/wiki/Raster_tile_providers", Icons.Rounded.Layers),
)

/** Настройки → «Ключи и сервисы»: у каждого сервиса своя плитка с ключом и настройками; внизу — все подключённые сервисы со ссылками. */
@Composable
internal fun KeysPage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var glKey by remember { mutableStateOf(GlMap.key(ctx)) }
    var google by remember { mutableStateOf(ApiKeys.google(ctx)) }
    var gLimit by remember { mutableStateOf(ApiKeys.limit(ctx, "google").toString()) }
    var gAuto by remember { mutableStateOf(MapPrefs.bool(ctx, "google_auto2", false)) }
    var gSearch by remember { mutableStateOf(MapPrefs.bool(ctx, "google_default", true)) }
    var ewLogin by remember { mutableStateOf(ctx.getSharedPreferences("api_keys", 0).getString("easyway_login", "").orEmpty()) }
    var ewKey by remember { mutableStateOf(ctx.getSharedPreferences("api_keys", 0).getString("easyway", "").orEmpty()) }
    FullScreen("Ключи и сервисы", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ключи хранятся только на телефоне (в коде и на GitHub их нет).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            ServiceCard("GLMap · Guru", Icons.Rounded.Terrain, "https://user.globus.software/apps/") {
                AppTextField(glKey, { glKey = it.trim() }, "Ключ API GLMap")
                Text(when { GlMap.ready -> "Работает" + (GlMap.server?.let { " · сервер $it" } ?: ""); glKey.isBlank() -> "Без ключа карта не работает"; else -> GlMap.error ?: "" },
                    style = MaterialTheme.typography.bodySmall, color = if (GlMap.ready) Pal.MintInk else Pal.RoseInk)
                Text("Бесплатно: 5000 скачиваний карт и 50 000 онлайн-тайлов в месяц.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                PillButton("Сохранить ключ GLMap", {
                    GlMap.setKey(ctx, glKey); MapUi.engineTick++; onChanged()
                    Toast.makeText(ctx, if (GlMap.ready) "Сохранено. Новый ключ заработает после перезапуска приложения" else "Сохранено", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
            }
            ServiceCard("Telegram · кино", Icons.Rounded.PlayCircle, "https://my.telegram.org/apps") {
                var tgId by remember { mutableStateOf(com.pank.vsevodnom.data.Tg.apiId(ctx)?.toString().orEmpty()) }
                var tgHash by remember { mutableStateOf(com.pank.vsevodnom.data.Tg.apiHash(ctx)) }
                AppTextField(tgId, { tgId = it.filter(Char::isDigit) }, "api_id")
                AppTextField(tgHash, { tgHash = it.trim() }, "api_hash")
                Text("Для «Кино · Telegram» (ToDo → Фильмы): смотреть фильмы из своего канала онлайн.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                PillButton("Сохранить ключи Telegram", {
                    com.pank.vsevodnom.data.Tg.setKeys(ctx, tgId, tgHash); com.pank.vsevodnom.data.Tg.restart(ctx)
                    Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
            }
            ServiceCard("Google Places", Icons.Rounded.Cloud, "https://console.cloud.google.com/apis/credentials") {
                AppTextField(google, { google = it }, "Ключ API (AIza…)")
                AppTextField(gLimit, { gLimit = it.filter(Char::isDigit) }, "Запросов в день (рекомендую 150)")
                Text("Сегодня: ${ApiKeys.used(ctx, "google")} из ${ApiKeys.limit(ctx, "google")}. Поиск — 1 запрос, сведения о месте — 2, каждое фото — 1; из кэша — 0.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                SwitchRow("Искать через Google по умолчанию", null, gSearch) { gSearch = it; MapPrefs.setBool(ctx, "google_default", it) }
                SwitchRow("Сведения Google сразу в карточке", "иначе — по кнопке, экономит лимит", gAuto) { gAuto = it; MapPrefs.setBool(ctx, "google_auto2", it) }
                var gc by remember { mutableStateOf(GCache.enabled(ctx)) }
                var gcSize by remember { mutableLongStateOf(GCache.size(ctx)) }
                SwitchRow("Хранить сведения и фото 30 дней", "зашифровано · ${gcSize / 1024} КБ — повторный просмотр места не тратит лимит", gc) { gc = it; GCache.setEnabled(ctx, it); gcSize = GCache.size(ctx) }
                if (gcSize > 0) TextButton({ GCache.clear(ctx); gcSize = 0 }) { Text("Очистить кэш Google") }
                PillButton("Сохранить ключ Google", {
                    ApiKeys.set(ctx, "google", google); gLimit.toIntOrNull()?.let { ApiKeys.setLimit(ctx, "google", it) }
                    onChanged(); Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Лимиты", { web(ctx, "https://console.cloud.google.com/apis/api/places.googleapis.com/quotas") }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                    PillButton("Оплата", { web(ctx, "https://console.cloud.google.com/billing") }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
            ServiceCard("EasyWay", Icons.Rounded.DirectionsBus, "https://www.eway.in.ua") {
                AppTextField(ewLogin, { ewLogin = it }, "Логин API")
                AppTextField(ewKey, { ewKey = it }, "Пароль / ключ API")
                Text("Для будущего: общественный транспорт, остановки и время прибытия. Доступ к API выдаёт EasyWay по запросу.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                PillButton("Сохранить", { ctx.getSharedPreferences("api_keys", 0).edit().putString("easyway_login", ewLogin.trim()).putString("easyway", ewKey.trim()).apply(); Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show() },
                    Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
            }
            ServiceCard("Waze", Icons.Rounded.Navigation, "https://www.waze.com") {
                Text("Ключ не нужен: кнопка «Waze» в карточке места и в маршруте на машине открывает навигацию в Waze.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            SectionLabel("Подключённые сервисы")
            SERVICES.forEach { s -> MenuRow(s.icon, s.name, sub = s.what) { web(ctx, s.url) } }
            Text("Google: в консоли ограничь ключ «Приложения Android» (пакет и SHA-1 — в Настройках → О приложении) и API «Places API (New)».", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}

@Composable
private fun ServiceCard(title: String, icon: ImageVector, url: String, content: @Composable ColumnScope.() -> Unit) {
    val ctx = LocalContext.current
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Sky), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Pal.SkyInk) }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            IconButton({ web(ctx, url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть сайт", tint = Pal.Muted) }
        }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
