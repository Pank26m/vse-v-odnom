package com.pank.vsevodnom.ui.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.calendar.EventEditor
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Цвета меток и треков. */
val MAP_COLORS = listOf(0, 0xFFE0607E, 0xFFE5484D, 0xFFF08C2E, 0xFFE9B12B, 0xFF3FA66B, 0xFF2E9FB8, 0xFF3E6FA8, 0xFF6A55A8, 0xFF8D6E63, 0xFF5F6B7A).map { it.toInt() }

internal fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("coords", text))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}
internal fun web(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
internal fun waze(ctx: Context, lat: Double, lon: Double) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "https://waze.com/ul?ll=%.6f,%.6f&navigate=yes", lat, lon))))
}.onFailure { Toast.makeText(ctx, "Waze не установлен", Toast.LENGTH_SHORT).show() }
internal fun share(ctx: Context, name: String, lat: Double, lon: Double) {
    val t = String.format(Locale.US, "%s\n%.6f, %.6f\nhttps://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f", name, lat, lon, lat, lon, lat, lon)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Поделиться"))
}

/** Нижняя панель поверх карты (в своём окне — чтобы не мешать жестам карты под ней). */
@Composable
internal fun BottomPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            // только нижний отступ (системная панель / клавиатура): сверху панель и так ниже строки состояния
            val ri = LocalRootInsets.current
            // на вкладках с нижним баром корневые отступы «съедены» — берём системную панель из окна активности (иначе низ списка уходил под неё)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val ctx = LocalContext.current
            val act = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            val sysBottom = act?.window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom }?.let { with(density) { it.toDp() } } ?: 0.dp
            // и ещё запасной путь — высота системной панели из ресурсов Android (на Android 16 предыдущие давали 0 и низ списка уходил под панель)
            val resBottom = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android").takeIf { it > 0 }?.let { with(density) { ctx.resources.getDimensionPixelSize(it).toDp() } } ?: 0.dp
            val ime = maxOf(ri.ime, WindowInsets.ime.asPaddingValues().calculateBottomPadding())
            val bottom = maxOf(ri.bottom, ime, sysBottom, resBottom, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 24.dp)
            val top = maxOf(ri.top, WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            Column(Modifier.fillMaxWidth().heightIn(max = screenH - top - 24.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Pal.Bg)
                .clickable(interactionSource = null, indication = null) {}.padding(top = 8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(Pal.Line))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                // место под системной панелью — вне прокрутки, чтобы последний пункт всегда был над ней. Плюс запас на высоту
                // строки состояния: окно диалога на Android 16 сдвинуто вниз на неё и низ уходил за край экрана (0.21–0.25)
                Spacer(Modifier.height(bottom + top + 16.dp))
            }
        }
    }
}

@Composable
internal fun FullScreen(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onDismiss)
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1)
                actions()
            }
            content()
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, text: String, bg: Color = Color(0xFFEDEAE4), fg: Color = Pal.Ink, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Карточка объекта как в Google Картах: шапка с оценкой → кнопки → фото → часы → сайт/соцсети → отзывы → ссылки → координаты. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceCard(t: MapTarget, me: GeoPt?, folders: List<MapFolder>, onClose: () -> Unit, onRoute: (String, MapPrefs.Spot) -> Unit, onSaved: (Place) -> Unit,
                       onSimilar: ((MapTarget) -> Unit)? = null, onWhole: ((MapTarget) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(t) { mutableStateOf<PlaceInfo.Info?>(null) }
    var g by remember(t) { mutableStateOf<GooglePlaces.Details?>(null) }
    var gErr by remember(t) { mutableStateOf<String?>(null) }
    var loading by remember(t) { mutableStateOf(false) }
    var admin by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    // сначала короткое окно, подробности — по стрелке (кроме правки только что созданной метки)
    var expanded by remember(t) { mutableStateOf(t.props["edit"] == "1") }
    var editing by remember(t) { mutableStateOf(t.props["edit"] == "1") }
    var askDelete by remember(t) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var linkEdit by remember { mutableStateOf<String?>(null) }    // правка ссылки Booking/Airbnb у метки
    var linkTick by remember { mutableIntStateOf(0) }
    var event by remember { mutableStateOf<CalEvent?>(null) }
    val photos = remember(t) { mutableStateListOf<android.graphics.Bitmap>() }
    var wikiImg by remember(t) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var allHours by remember(t) { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }          // полноэкранный просмотр фото
    var planVisit by remember { mutableStateOf(false) }
    val planKey = PlacePlans.key(t.place?.id, t.lat, t.lon)
    var plan by remember(t) { mutableStateOf(PlacePlans.get(ctx, planKey)) }
    val online = Net.online && !MapPrefs.offlineOnly(ctx)
    val isPoint = t.name == "Точка на карте"
    // объект без названия (ворота, урна, площадка…) или тропа — Google и TripAdvisor по нему не ищем
    val isRoute = t.props["kind"] == "route"
    val isStreet = t.props["kind"] == "street"
    val noName = isPoint || isRoute || isStreet || (t.place == null && t.props.isNotEmpty() && t.props["name"] == null && !t.props.containsKey("google_id"))
    var routeWiki by remember(t) { mutableStateOf<PlaceInfo.Wiki?>(null) }
    LaunchedEffect(t) { if ((isRoute || isStreet) && Net.online && !t.name.contains(" без названия")) routeWiki = PlaceInfo.wikiFor(t.name, t.props["wikipedia"], t.lat, t.lon) }
    val hasKey = ApiKeys.google(ctx).isNotBlank()
    var gAsk by remember(t) { mutableStateOf(false) }     // лимит почти исчерпан — грузим только по кнопке
    var gSpent by remember(t) { mutableIntStateOf(0) }     // сколько запросов к Google потрачено на это место сейчас
    var gSrc by remember(t) { mutableStateOf<String?>(null) }
    var plName by remember(t) { mutableStateOf(t.place?.name.orEmpty()) }
    var plNote by remember(t) { mutableStateOf(t.place?.note.orEmpty()) }
    var saveTick by remember(t) { mutableIntStateOf(0) }
    fun saveOwn() { saveTick++ }
    LaunchedEffect(saveTick) {
        if (saveTick == 0 || t.place == null) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        val pl = t.place.copy(name = plName.trim().ifBlank { t.place.name }, note = plNote.trim())
        AppScope.io.launch { AppDb.get(ctx).map().upsertPlace(pl) }
    }
    fun loadGoogle() {
        if (!online || !hasKey || noName) return
        scope.launch {
            loading = true; gAsk = false
            val before = ApiKeys.used(ctx, "google")
            runCatching { t.props["google_id"]?.let { GooglePlaces.details(ctx, it) } ?: GooglePlaces.near(ctx, t.name, t.lat, t.lon) }
                .onSuccess { g = it }.onFailure { gErr = it.message }
            gSpent += (ApiKeys.used(ctx, "google") - before).coerceAtLeast(0)
            gSrc = g?.id?.let { GooglePlaces.source[it] } ?: if (gSpent > 0) "net" else null
            loading = false
            // первое фото — сразу, остальные — по кнопке (каждое фото — отдельный платный запрос; из кэша — бесплатно)
            val b2 = ApiKeys.used(ctx, "google")
            g?.photos?.forEachIndexed { i, n -> if (i == 0) GooglePlaces.photo(ctx, n, 1000)?.let { photos += it } else GooglePlaces.storedPhoto(ctx, n)?.let { photos += it } }
            gSpent += (ApiKeys.used(ctx, "google") - b2).coerceAtLeast(0)
        }
    }
    LaunchedEffect(t) {
        admin = withContext(Dispatchers.IO) { runCatching { UaAdmin.lookup(ctx, t.lat, t.lon) }.getOrDefault(emptyList()) }
        // уже смотрели это место, пока приложение открыто, — показываем без нового запроса
        val known = withContext(Dispatchers.IO) { GooglePlaces.stored(ctx, t.props["google_id"] ?: if (noName) null else GooglePlaces.knownId(ctx, t.name, t.lat, t.lon)) }
        if (known != null) { g = known; gSrc = GooglePlaces.source[known.id]; withContext(Dispatchers.IO) { known.photos.mapNotNull { n -> GooglePlaces.storedPhoto(ctx, n) } }.forEach { photos += it } }
        else if (MapPrefs.bool(ctx, "google_auto2", false) || t.props.containsKey("google_id")) { if (ApiKeys.nearlyOut(ctx, "google")) gAsk = hasKey && online && !noName else loadGoogle() }
        if (online) {
            // у тропы с названием ищем её саму в OSM (теги: покрытие, сложность, ссылки)
            info = runCatching { PlaceInfo.load(t.name.takeIf { t.place == null && (!noName || (isRoute && t.props["name"] != null)) }, t.lat, t.lon) }.getOrNull()
            if (photos.isEmpty()) info?.wiki?.image?.let { u -> wikiImg = runCatching { loadImage(ctx, u, 700) }.getOrNull() }
        }
    }
    LaunchedEffect(routeWiki) { if (wikiImg == null && photos.isEmpty()) routeWiki?.image?.let { u -> wikiImg = runCatching { loadImage(ctx, u, 700) }.getOrNull() } }
    val title = if (isPoint) g?.name?.ifBlank { null } ?: info?.name?.ifBlank { null } ?: info?.address?.ifBlank { null } ?: t.name else t.name
    val type = g?.type ?: t.kind.ifBlank { null }
    val dist = me?.let { Osm.distance(it, GeoPt(t.lat, t.lon, 0)) }
    val tags = info?.tags.orEmpty()
    val today = (java.time.LocalDate.now().dayOfWeek.value - 1)
    val osmWeek = remember(tags["opening_hours"]) { OpeningHours.parse(tags["opening_hours"]) }
    val phone = g?.phone ?: tags["phone"] ?: tags["contact:phone"]
    val city = (info?.admin?.takeIf { it.isNotEmpty() } ?: admin).let { a -> a.getOrNull(1) ?: a.lastOrNull() }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Pal.Card)
        .clickable(interactionSource = null, indication = null) {}) {
        // шапка
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 18.dp, top = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            // своя метка: кружок с цветом и значком (как у треков) — нажать: цвет, значок
            if (t.place != null) {
                PlaceStyleButton(t.place) { onSaved(it) }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (t.place != null) androidx.compose.foundation.text.BasicTextField(plName, { plName = it; saveOwn() },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Pal.Ink), modifier = Modifier.fillMaxWidth(), maxLines = 2)
                else Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(listOfNotNull(type, if (t.place != null) "📁 " + folderPath(t.place.folderId, folders) else null, dist?.let { MapPrefs.dist(ctx, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                g?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        d.rating?.let { r -> Text("%.1f ".format(r), style = MaterialTheme.typography.titleSmall); Text("★".repeat(r.toInt().coerceIn(0, 5)), color = Color(0xFFE9A92B), fontSize = 13.sp)
                            Text(" (${d.count ?: 0})", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                        d.price?.let { Text(" · $it", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    }
                }
                // статус считаем сейчас по недельному графику (Google или OSM); «открыто сейчас» из кэша Google устаревает
                (g?.week ?: osmWeek)?.let { w ->
                    val now = java.time.LocalTime.now()
                    val st = OpeningHours.status(w, today, now.hour * 60 + now.minute)
                    Text(st.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = when { st.open && !st.soon -> Pal.MintInk; st.soon -> Color(0xFFB7791F); else -> Pal.RoseInk }, maxLines = 1)
                } ?: tags["opening_hours"]?.let { Text("Часы: $it", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1) }
                plan?.takeIf { it.first > System.currentTimeMillis() - 86_400_000L }?.let { (at, task) ->
                    Text("Запланировано на " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + " ›",
                        style = MaterialTheme.typography.labelLarge, color = Color(0xFFD13B3B), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { MapUi.openTodo = "task:$task" })
                }
                phone?.let { ph -> Text(ph, style = MaterialTheme.typography.bodyMedium, color = Pal.SkyInk, modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) } }) }
            }
            if (loading) CircularProgressIndicator(Modifier.padding(top = 10.dp).size(18.dp), strokeWidth = 2.dp)
            IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, "Свернуть", tint = Pal.Muted) }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Pal.Muted) }
        }
        // кнопки
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.place == null) ActionChip(Icons.Rounded.BookmarkAdd, "Метка", Pal.Ink, Color.White) {
                val pl = Place(name = title, lat = t.lat, lon = t.lon, address = g?.address ?: info?.address.orEmpty(), folderId = MapPrefs.p(ctx).getLong("last_folder", 0).takeIf { it > 0 })
                AppScope.io.launch { val id = AppDb.get(ctx).map().upsertPlace(pl); withContext(Dispatchers.Main) { onSaved(pl.copy(id = id)) } }
            }
            val spot = MapPrefs.Spot(title, t.lat, t.lon, type.orEmpty())
            RouteTile(0, "Отсюда") { onRoute("from", spot) }
            RouteTile(1, "Остановка") { onRoute("stop", spot) }
            RouteTile(2, "Сюда") { onRoute("to", spot) }
            ActionChip(Icons.Rounded.Navigation, "Waze", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, t.lat, t.lon) }
            ActionChip(Icons.Rounded.Share, "Поделиться", Pal.Lilac, Pal.LilacInk) { share(ctx, title, t.lat, t.lon) }
            if (t.place != null) ActionChip(Icons.Rounded.VisibilityOff, "Скрыть", Pal.Card, Pal.Ink) {
                val pl = t.place; AppScope.io.launch { AppDb.get(ctx).map().upsertPlace(pl.copy(visible = false)) }
                Toast.makeText(ctx, "Метка скрыта — вернуть в «Коллекциях» (глазок)", Toast.LENGTH_SHORT).show(); onClose()
            }
            if (t.place != null) ActionChip(Icons.Rounded.DeleteOutline, "Удалить", Pal.Rose, Pal.RoseInk) { askDelete = true }
            ActionChip(Icons.Rounded.MoreHoriz, "Ещё", Pal.Butter, Pal.ButterInk) { more = true }
        }
        if (askDelete && t.place != null) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Удалить метку «${t.place.name}»?") },
            confirmButton = { TextButton({ val pl = t.place; AppScope.io.launch { AppDb.get(ctx).map().deletePlace(pl) }; askDelete = false; onClose() }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ askDelete = false }) { Text("Отмена") } })
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.42f).dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editing && t.place != null) PlaceInlineEditor(t.place, folders) { saved -> editing = false; if (saved != null) onSaved(saved) else onClose() }
            // фото
            if (photos.isNotEmpty() || wikiImg != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (photos.isNotEmpty()) photos.toList() else listOfNotNull(wikiImg)).forEachIndexed { i, b ->
                    Image(b.asImageBitmap(), null, Modifier.height(150.dp).widthIn(min = 120.dp, max = 260.dp).clip(RoundedCornerShape(14.dp)).clickable { viewer = i }, contentScale = ContentScale.Crop)
                }
                val more = (g?.photos?.size ?: 0) - photos.size
                if (more > 0 && photos.isNotEmpty()) Box(Modifier.height(150.dp).width(110.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable {
                    scope.launch { val b0 = ApiKeys.used(ctx, "google"); g?.photos?.drop(photos.size)?.take(3)?.forEach { n -> GooglePlaces.photo(ctx, n, 1000)?.let { photos += it } }; gSpent += (ApiKeys.used(ctx, "google") - b0).coerceAtLeast(0) }
                }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Pal.SkyInk); Text("Ещё фото", style = MaterialTheme.typography.labelLarge)
                        Text("$more · по запросу", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                }
            }
            if (isRoute) RouteInfoBlock(t, routeWiki, info)
            if (isStreet) StreetBlock(t, routeWiki, info, city, onWhole, onRoute)
            // описание своей метки — правится прямо здесь
            if (t.place != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp)) {
                Text("Описание", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Box {
                    if (plNote.isEmpty()) Text("Добавить описание…", style = MaterialTheme.typography.bodyLarge, color = Pal.Muted)
                    androidx.compose.foundation.text.BasicTextField(plNote, { plNote = it; saveOwn() }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pal.Ink), modifier = Modifier.fillMaxWidth())
                }
            }
            // часы
            val hoursLines = g?.hours?.takeIf { it.isNotEmpty() }
                ?: osmWeek?.mapIndexed { i, d -> OpeningHours.NAMES[i] + ": " + OpeningHours.dayText(d) }
            hoursLines?.let { hrs ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable { allHours = !allHours }.padding(12.dp)) {
                    Text("Часы работы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    hrs.forEachIndexed { i, line ->
                        if (allHours || i == today) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(line.substringBefore(": ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(130.dp))
                            Text(line.substringAfter(": ", ""), style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    if (!allHours) Text("вся неделя ▾", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            // одной лентой: позвонить, сайт, соцсети, TripAdvisor, жильё (Booking/Airbnb)
            @Suppress("UNUSED_EXPRESSION") linkTick
            val ownBooking = t.place?.let { StayLinks.get(ctx, it.id, "booking") }
            val ownAirbnb = t.place?.let { StayLinks.get(ctx, it.id, "airbnb") }
            val stay = StayLinks.isStay(t.props["class"], t.props["subclass"], g?.type ?: t.kind)
            val q = listOfNotNull(title, g?.address?.takeIf { it.isNotBlank() } ?: city).joinToString(", ")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                phone?.let { ph -> ActionChip(Icons.Rounded.Call, "Позвонить", Pal.Mint, Pal.MintInk) { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) } } }
                (g?.site ?: tags["website"] ?: tags["contact:website"])?.let { u -> ActionChip(Icons.Rounded.Language, "Сайт", Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://$u") } }
                (tags["contact:instagram"] ?: tags["instagram"])?.let { u -> ActionChip(Icons.Rounded.PhotoCamera, "Instagram", Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://instagram.com/" + u.trimStart('@')) } }
                (tags["contact:facebook"] ?: tags["facebook"])?.let { u -> ActionChip(Icons.Rounded.Groups, "Facebook", Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://facebook.com/$u") } }
                (tags["contact:telegram"] ?: tags["telegram"])?.let { u -> ActionChip(Icons.AutoMirrored.Rounded.Send, "Telegram", Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://t.me/" + u.trimStart('@')) } }
                (ownBooking ?: if (stay && t.place == null) StayLinks.bookingSearch(q) else null)?.let { u -> ActionChip(Icons.Rounded.Hotel, "Booking", Color(0xFFDCE6F7), Color(0xFF003B95)) { web(ctx, u) } }
                (ownAirbnb ?: if (stay && t.place == null) StayLinks.airbnbSearch(q) else null)?.let { u -> ActionChip(Icons.Rounded.Cottage, "Airbnb", Color(0xFFFDE3E7), Color(0xFFD9344F)) { web(ctx, u) } }
            }
            g?.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            info?.wiki?.takeIf { !isRoute }?.let { w -> Text(w.extract, style = MaterialTheme.typography.bodySmall, maxLines = 6) }
            // отзывы — только по кнопке (самые дорогие сведения Google)
            g?.let { gd ->
                var revs by remember(gd.id) { mutableStateOf<List<GooglePlaces.Review>?>(null) }
                var revLoading by remember(gd.id) { mutableStateOf(false) }
                val rs = revs
                if (rs == null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable(enabled = !revLoading) {
                    revLoading = true
                    scope.launch {
                        revs = runCatching { GooglePlaces.reviews(ctx, gd.id) }.onFailure { Toast.makeText(ctx, it.message ?: "Не удалось загрузить отзывы", Toast.LENGTH_SHORT).show() }.getOrNull()
                        revLoading = false
                    }
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.RateReview, null, tint = Pal.SkyInk); Spacer(Modifier.width(10.dp))
                    Text("Показать отзывы Google" + (gd.count?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (revLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                else if (rs.isEmpty()) Text("Отзывов нет", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                else {
                    Text("Отзывы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    rs.take(5).forEach { r ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).padding(10.dp)) {
                            Text(r.author + " · " + r.when_, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                            Text("★".repeat(r.rating.coerceIn(0, 5)), color = Color(0xFFE9A92B), fontSize = 12.sp)
                            if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                        }
                    }
                }
            }
            if (g == null && hasKey && online && !noName && !loading) {
                val used = ApiKeys.used(ctx, "google"); val lim = ApiKeys.limit(ctx, "google")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (gAsk) Pal.Butter else Pal.Bg).clickable { loadGoogle() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (gAsk) Icons.Rounded.Info else Icons.Rounded.CloudDownload, null, tint = if (gAsk) Pal.ButterInk else Pal.SkyInk); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (gAsk) "Лимит Google почти исчерпан" else "Загрузить сведения Google", style = MaterialTheme.typography.titleSmall)
                        Text(if (gAsk) "Использовано $used из $lim на сегодня. Подтянуть оценку, фото и часы для этого места?" else "оценка, фото, часы · $used из $lim за сегодня",
                            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                }
            }
            gErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
            if (g != null) {
                val left = (ApiKeys.limit(ctx, "google") - ApiKeys.used(ctx, "google")).coerceAtLeast(0)
                val src = when {
                    gSpent > 0 -> "запрос к Google: потрачено $gSpent"
                    gSrc == "disk" -> "из зашифрованного кэша на телефоне" + (GCache.savedAt(ctx, "d:${g!!.id}")?.let { " (сохранено " + ((System.currentTimeMillis() - it) / 86_400_000L).let { d -> if (d == 0L) "сегодня" else "$d дн. назад" } + ")" } ?: "") + " · лимит не тратится"
                    else -> "из памяти (открывал, пока приложение запущено) · лимит не тратится"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (gSpent > 0) Icons.Rounded.CloudDownload else Icons.Rounded.Lock, null, tint = Pal.Muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                    Text("Сведения Google — $src · осталось $left из ${ApiKeys.limit(ctx, "google")} на сегодня", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
            // поиск этого места в других сервисах — серыми кнопками; ищем по названию и городу (улица — «название, город»), безымянное — по координатам
            val sq = listOfNotNull(title.takeIf { !noName && !title.startsWith("Улица без") && !title.startsWith("Тропа без") }, city).joinToString(", ")
            val coordsQ = String.format(Locale.US, "%.6f,%.6f", t.lat, t.lon)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Google Карты", { web(ctx, g?.mapsUrl ?: "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(if (sq.isBlank() || noName) coordsQ else sq)) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
                PillButton("OpenStreetMap", { web(ctx, t.props["osm_url"] ?: info?.osmUrl?.ifBlank { null } ?: String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=18/%.6f/%.6f", t.lat, t.lon, t.lat, t.lon)) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Википедия", { web(ctx, (routeWiki ?: info?.wiki)?.url ?: "https://uk.wikipedia.org/w/index.php?search=" + Uri.encode(sq.ifBlank { title })) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
                PillButton("TripAdvisor", { web(ctx, "https://www.tripadvisor.com/Search?q=" + Uri.encode(sq.ifBlank { coordsQ })) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
            }
            // мелочь
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { copy(ctx, MapPrefs.coords(ctx, t.lat, t.lon)) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MyLocation, null, tint = Pal.Muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(MapPrefs.coords(ctx, t.lat, t.lon), style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ContentCopy, "Копировать", tint = Pal.Muted, modifier = Modifier.size(14.dp))
            }
            val chain = info?.admin?.takeIf { it.isNotEmpty() } ?: admin
            if (chain.isNotEmpty()) Text(chain.joinToString(" › "), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            if (!online) Text("Без интернета: координаты и деление — из карт на телефоне.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }

    if (more) BottomPanel({ more = false }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        MenuRow(Icons.Rounded.Home, "Сделать «Домом»") { MapPrefs.setHome(ctx, MapPrefs.Spot("Дом", t.lat, t.lon, title)); more = false; Toast.makeText(ctx, "Дом задан", Toast.LENGTH_SHORT).show() }
        MenuRow(Icons.Rounded.Work, "Сделать «Работой»") { MapPrefs.setWork(ctx, MapPrefs.Spot("Работа", t.lat, t.lon, title)); more = false; Toast.makeText(ctx, "Работа задана", Toast.LENGTH_SHORT).show() }
        MenuRow(Icons.Rounded.EventAvailable, "Запланировать посещение места", sub = "дата и время → задача в ToDo с точкой; «Начать сейчас» строит путь") { more = false; planVisit = true }
        if (onSimilar != null && t.props["kind"] != "route" && (t.props["subclass"] != null || t.props["class"] != null))
            MenuRow(Icons.Rounded.TravelExplore, "Похожие рядом", sub = "${t.kind.ifBlank { "такие же места" }} поблизости — на карте") { more = false; onSimilar(t) }
        MenuRow(Icons.Rounded.Star, "В избранное маршрутов") { MapPrefs.setFavs(ctx, MapPrefs.favs(ctx) + MapPrefs.Spot(title.take(24), t.lat, t.lon, title)); more = false; Toast.makeText(ctx, "Добавлено в избранное маршрутов", Toast.LENGTH_SHORT).show() }
        MenuRow(Icons.Rounded.ContentCopy, "Копировать координаты") { copy(ctx, MapPrefs.coords(ctx, t.lat, t.lon)); more = false }
        if (t.place != null) {
            MenuRow(Icons.Rounded.Folder, "Папка и цвет", sub = folderPath(t.place.folderId, folders)) { editing = true; expanded = true; more = false }
            MenuRow(Icons.Rounded.Hotel, if (StayLinks.get(ctx, t.place.id, "booking") != null) "Ссылка Booking…" else "Подключить Booking") { linkEdit = "booking"; more = false }
            MenuRow(Icons.Rounded.Cottage, if (StayLinks.get(ctx, t.place.id, "airbnb") != null) "Ссылка Airbnb…" else "Подключить Airbnb") { linkEdit = "airbnb"; more = false }
        }
        MenuRow(Icons.Rounded.Map, "Открыть в другом приложении") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", t.lat, t.lon, t.lat, t.lon, Uri.encode(title))))) }; more = false
        }
    }
    event?.let { e -> EventEditor(e, onDismiss = { event = null }) }
    if (planVisit) PlanDialog("Посетить", title, onDismiss = { planVisit = false }) { at ->
        planVisit = false
        AppScope.io.launch {
            val task = planTask(ctx, null, "Посетить: $title", MapPrefs.Spot(title, t.lat, t.lon), at)
            PlacePlans.set(ctx, planKey, at, task)
            withContext(Dispatchers.Main) { plan = at to task; Toast.makeText(ctx, "Запланировано — задача в ToDo", Toast.LENGTH_SHORT).show() }
        }
    }
    viewer?.let { i -> PhotoViewer(if (photos.isNotEmpty()) photos.toList() else listOfNotNull(wikiImg), i, title) { viewer = null } }

    linkEdit?.let { kind ->
        val pl = t.place ?: return@let
        var url by remember(kind) { mutableStateOf(StayLinks.get(ctx, pl.id, kind).orEmpty()) }
        val name = if (kind == "booking") "Booking" else "Airbnb"
        AlertDialog(onDismissRequest = { linkEdit = null }, title = { Text("Ссылка $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Откройте жильё в приложении $name → «Поделиться» → «Копировать ссылку» и вставьте сюда.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    OutlinedTextField(url, { url = it.trim() }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ StayLinks.set(ctx, pl.id, kind, url.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }); linkEdit = null; linkTick++ }) { Text("Сохранить") } },
            dismissButton = { Row { if (StayLinks.get(ctx, pl.id, kind) != null) TextButton({ StayLinks.set(ctx, pl.id, kind, null); linkEdit = null; linkTick++ }) { Text("Отключить", color = Pal.RoseInk) }
                TextButton({ linkEdit = null }) { Text("Отмена") } } })
    }
}

/** Ссылки на Booking и Airbnb: поиск по названию для гостиниц с карты и свои ссылки у меток (prefs «place_links»: {id: {booking, airbnb}}). */
internal object StayLinks {
    private val CLASSES = setOf("lodging", "hotel", "hostel", "motel", "guest_house", "apartment", "apartments", "chalet", "camp_site", "campsite", "caravan_site", "alpine_hut", "wilderness_hut")
    private val WORDS = listOf("готел", "отел", "гостиниц", "хостел", "мотел", "апарт", "hotel", "hostel", "motel", "lodging", "guest", "гостьов", "гостев", "кемпинг", "кемпінг")
    fun isStay(cls: String?, sub: String?, type: String?): Boolean =
        cls in CLASSES || sub in CLASSES || type?.lowercase()?.let { tp -> WORDS.any { it in tp } } == true
    fun bookingSearch(q: String) = "https://www.booking.com/searchresults.html?ss=" + Uri.encode(q)
    fun airbnbSearch(q: String) = "https://www.airbnb.com/s/" + Uri.encode(q) + "/homes"
    private fun all(ctx: Context) = runCatching { org.json.JSONObject(MapPrefs.p(ctx).getString("place_links", "{}")!!) }.getOrDefault(org.json.JSONObject())
    fun get(ctx: Context, id: Long, kind: String): String? = all(ctx).optJSONObject(id.toString())?.optString(kind)?.ifBlank { null }
    fun set(ctx: Context, id: Long, kind: String, url: String?) {
        val a = all(ctx); val o = a.optJSONObject(id.toString()) ?: org.json.JSONObject()
        if (url == null) o.remove(kind) else o.put(kind, url)
        if (o.length() == 0) a.remove(id.toString()) else a.put(id.toString(), o)
        MapPrefs.p(ctx).edit().putString("place_links", a.toString()).apply()
    }
}

/** Правка метки прямо в карточке: имя, папка выпадающим списком, цвет, заметка. */
/** Значки меток (Tabler); пустой — просто точка. */
internal val PLACE_ICONS = listOf("", "map-pin", "home", "briefcase", "star", "heart", "coffee", "tools-kitchen-2", "shopping-cart", "car", "bike", "trekking",
    "tent", "mountain", "trees", "beach", "fish", "anchor", "building", "school", "first-aid-kit", "gas-station", "parking", "camera", "flag", "bed",
    "swimming", "plane", "train", "bus", "ticket", "music", "book", "gift", "dog", "pizza", "beer", "glass-cocktail")

/** Кружок метки (цвет + значок) — меню: цвета, все значки (как в «Финансах»), без значка. Сохраняет сразу. */
@Composable
internal fun PlaceStyleButton(p: Place, onSaved: (Place) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var icons by remember { mutableStateOf(false) }
    var cur by remember(p.id) { mutableStateOf(p) }
    fun save(np: Place) { cur = np; AppScope.io.launch { AppDb.get(ctx).map().upsertPlace(np) }; onSaved(np) }
    val col = Color(colorOr(cur.color, PLACE_COLOR))
    Box {
        PlaceDot(col, cur.icon.removePrefix("t:"), 34.dp, Modifier.clickable { menu = true })
        DropdownMenu(menu, { menu = false }) {
            listOf(MAP_COLORS.take(6), MAP_COLORS.drop(6)).forEach { row ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(if (c == 0) Color(colorOr(0, PLACE_COLOR)) else Color(c)).border(if (c == cur.color) 2.dp else 0.dp, Pal.Ink, CircleShape)
                        .clickable { save(cur.copy(color = c)); menu = false }) }
                }
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Значок…") }, { menu = false; icons = true }, leadingIcon = { Icon(Icons.Rounded.Interests, null) })
            DropdownMenuItem({ Text("Просто цветная точка") }, { save(cur.copy(icon = "")); menu = false }, leadingIcon = { Icon(Icons.Rounded.Circle, null, tint = col) })
        }
    }
    if (icons) AlertDialog(onDismissRequest = { icons = false }, confirmButton = { TextButton({ icons = false }) { Text("Закрыть") } }, title = { Text("Значок метки") },
        text = { com.pank.vsevodnom.ui.money.IconPicker("t:" + cur.icon.removePrefix("t:"), col) { save(cur.copy(icon = it.removePrefix("t:"))); icons = false } })
}

/** Значок метки как на карте: кружок цвета с белой обводкой и белой иконкой (без иконки — маленькая точка). */
@Composable
internal fun PlaceDot(col: Color, icon: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.size(size).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
        if (icon.isBlank()) Box(Modifier.size(size * 0.28f).clip(CircleShape).background(Color.White))
        else com.pank.vsevodnom.ui.money.TablerIcons.vector(ctx, icon)?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(size * 0.58f)) }
    }
}

@Composable
internal fun PlaceInlineEditor(p: Place, folders: List<MapFolder>, onDone: (Place?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember(p.id) { mutableStateOf(p.name) }
    var note by remember(p.id) { mutableStateOf(p.note) }
    var folder by remember(p.id) { mutableStateOf(p.folderId) }
    var color by remember(p.id) { mutableIntStateOf(p.color) }
    var icon by remember(p.id) { mutableStateOf(p.icon.removePrefix("t:")) }
    var open by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(name, { name = it }, "Название")
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { open = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = Pal.ButterInk); Spacer(Modifier.width(8.dp))
                Text(folderPath(folder, folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowDropDown, null, tint = Pal.Muted)
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("Без папки") }, { folder = null; open = false })
                folders.sortedBy { folderPath(it.id, folders).lowercase() }.forEach { f ->
                    DropdownMenuItem({ Text(folderPath(f.id, folders)) }, { folder = f.id; open = false })
                }
                DropdownMenuItem({ Text("+ Новая папка…", color = Pal.SkyInk) }, { newFolder = ""; open = false })
            }
        }
        ColorRow(color, { color = it })
        // значок метки на карте (как в Guru)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PLACE_ICONS.forEach { ic ->
                val sel = icon == ic
                Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (sel) Color(colorOr(color, PLACE_COLOR)) else Pal.Card).clickable { icon = ic },
                    contentAlignment = Alignment.Center) {
                    if (ic.isEmpty()) Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (sel) Color.White else Pal.Muted))
                    else com.pank.vsevodnom.ui.money.TablerIcons.vector(ctx, ic)?.let { Icon(it, ic, tint = if (sel) Color.White else Pal.Ink, modifier = Modifier.size(20.dp)) }
                }
            }
        }
        AppTextField(note, { note = it }, "Описание", singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Сохранить", {
                val saved = p.copy(name = name.trim().ifBlank { p.name }, note = note.trim(), folderId = folder, color = color, icon = icon)
                MapPrefs.p(ctx).edit().putLong("last_folder", folder ?: 0).apply()
                AppScope.io.launch { AppDb.get(ctx).map().upsertPlace(saved) }
                onDone(saved)
            }, Modifier.weight(1f))
            PillButton("Удалить", { AppScope.io.launch { AppDb.get(ctx).map().deletePlace(p) }; onDone(null) }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
        }
    }
    newFolder?.let { _ ->
        var n by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = null }, title = { Text("Новая папка") }, text = { AppTextField(n, { n = it }, "Название") },
            confirmButton = { TextButton({
                if (n.isNotBlank()) AppScope.io.launch { val id = AppDb.get(ctx).map().upsertFolder(MapFolder(name = n.trim())); withContext(Dispatchers.Main) { folder = id } }
                newFolder = null
            }) { Text("Создать") } },
            dismissButton = { TextButton({ newFolder = null }) { Text("Отмена") } })
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, text: String, tint: Color = Pal.Ink, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        }
    }
}

@Composable
internal fun ColorRow(selected: Int, onPick: (Int) -> Unit, def: Color = Color(0xFFE0607E)) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MAP_COLORS.forEach { c ->
            val col = if (c == 0) def else Color(c)
            Box(Modifier.size(32.dp).clip(CircleShape).background(col).border(if (c == selected) 3.dp else 0.dp, Pal.Ink, CircleShape).clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                if (c == selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Путь папки: «Поездки › Карпаты». */
internal fun folderPath(id: Long?, folders: List<MapFolder>): String {
    if (id == null) return "Без папки"
    val by = folders.associateBy { it.id }
    val names = ArrayList<String>(); var cur = by[id]; var guard = 0
    while (cur != null && guard++ < 30) { names += cur.name; cur = cur.parentId?.let { by[it] } }
    return names.reversed().joinToString(" › ").ifBlank { "Без папки" }
}

/** Выбор папки деревом; null — «Без папки». */
@Composable
internal fun FolderPicker(folders: List<MapFolder>, exclude: Set<Long> = emptySet(), onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf<String?>(null) }
    var parentForNew by remember { mutableStateOf<Long?>(null) }
    BottomPanel(onDismiss) {
        Text("Куда положить", style = MaterialTheme.typography.titleMedium)
        MenuRow(Icons.Rounded.FolderOff, "Без папки") { onPick(null) }
        fun children(p: Long?) = folders.filter { it.parentId == p && it.id !in exclude }.sortedBy { it.name.lowercase() }
        @Composable fun tree(p: Long?, depth: Int) {
            children(p).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = (depth * 18).dp).clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { onPick(f.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = if (f.color != 0) Color(f.color) else Pal.ButterInk); Spacer(Modifier.width(10.dp))
                    Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton({ parentForNew = f.id; newName = "" }, Modifier.size(28.dp)) { Icon(Icons.Rounded.CreateNewFolder, "Новая папка внутри", tint = Pal.Muted, modifier = Modifier.size(18.dp)) }
                }
                tree(f.id, depth + 1)
            }
        }
        tree(null, 0)
        MenuRow(Icons.Rounded.CreateNewFolder, "Новая папка") { parentForNew = null; newName = "" }
    }
    newName?.let { n ->
        var name by remember { mutableStateOf(n) }
        AlertDialog(onDismissRequest = { newName = null }, title = { Text(if (parentForNew == null) "Новая папка" else "Папка в «${folders.firstOrNull { it.id == parentForNew }?.name}»") },
            text = { AppTextField(name, { name = it }, "Название") },
            confirmButton = { TextButton({
                if (name.isBlank()) return@TextButton
                val parent = parentForNew
                AppScope.io.launch { val id = AppDb.get(ctx).map().upsertFolder(MapFolder(parentId = parent, name = name.trim())); withContext(Dispatchers.Main) { onPick(id) } }
                newName = null
            }) { Text("Создать") } },
            dismissButton = { TextButton({ newName = null }) { Text("Отмена") } })
    }
}

@Composable
internal fun PlaceEditor(p: Place, folders: List<MapFolder>, onDismiss: () -> Unit, onDone: (Place?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(p.name) }
    var note by remember { mutableStateOf(p.note) }
    var folder by remember { mutableStateOf(p.folderId) }
    var color by remember { mutableIntStateOf(p.color) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (p.id == 0L) "Новая метка" else "Метка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Название")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { pick = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = Pal.ButterInk); Spacer(Modifier.width(8.dp))
                    Text(folderPath(folder, folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
                }
                ColorRow(color, { color = it })
                AppTextField(note, { note = it }, "Описание", singleLine = false)
            }
        },
        confirmButton = {
            TextButton({
                if (name.isBlank()) return@TextButton
                val saved = p.copy(name = name.trim(), note = note.trim(), folderId = folder, color = color)
                MapPrefs.p(ctx).edit().putLong("last_folder", folder ?: 0).apply()
                AppScope.io.launch { val id = AppDb.get(ctx).map().upsertPlace(saved); withContext(Dispatchers.Main) { onDone(if (saved.id == 0L) saved.copy(id = id) else saved) } }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            if (p.id != 0L) TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deletePlace(p) }; onDone(null) }) { Text("Удалить", color = Pal.RoseInk) }
            else TextButton(onDismiss) { Text("Отмена") }
        })
    if (pick) FolderPicker(folders, onDismiss = { pick = false }) { folder = it; pick = false }
}

@Composable
internal fun TrackEditor(t: Track, folders: List<MapFolder>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(t.name) }
    var folder by remember { mutableStateOf(t.folderId) }
    var color by remember { mutableIntStateOf(t.color) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Трек") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Название")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { pick = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = Pal.ButterInk); Spacer(Modifier.width(8.dp))
                    Text(folderPath(folder, folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
                }
                ColorRow(color, { color = it }, Color(0xFF6A55A8))
            }
        },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().upsertTrack(t.copy(name = name.trim().ifBlank { t.name }, folderId = folder, color = color)) }; onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deleteTrack(t) }; onDismiss() }) { Text("Удалить", color = Pal.RoseInk) } })
    if (pick) FolderPicker(folders, onDismiss = { pick = false }) { folder = it; pick = false }
}

internal fun shareFile(ctx: Context, name: String, text: String, mime: String) {
    val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = java.io.File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_")); f.writeText(text)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить"))
}

@Composable
internal fun TrackCard(t: Track, folders: List<MapFolder>, onClose: () -> Unit, onFit: () -> Unit, color: String = "", onColor: (String) -> Unit = {},
                       onEditRoute: (Track) -> Unit = {}, onGo: (Track) -> Unit = {}) {
    val ctx = LocalContext.current
    var expanded by remember(t.id) { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val pts = remember(t.id, t.points) { Osm.decode(t.points) }
    val st = remember(t.id, t.points) { TrackMath.stats(pts) }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val col = if (t.color != 0) Color(t.color) else Color(0xFF6A55A8)
    val mode = trackMode(t)
    // название и описание правятся прямо в окне и сохраняются сами
    var name by remember(t.id) { mutableStateOf(t.name) }
    var desc by remember(t.id) { mutableStateOf(trackDesc(t).orEmpty()) }
    var tick by remember(t.id) { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        AppScope.io.launch { val cur = AppDb.get(ctx).map().track(t.id) ?: t; AppDb.get(ctx).map().upsertTrack(withDesc(cur, desc).copy(name = name.trim().ifBlank { t.name })) }
    }
    fun update(f: (Track) -> Track) { AppScope.io.launch { val cur = AppDb.get(ctx).map().track(t.id) ?: t; AppDb.get(ctx).map().upsertTrack(f(cur)) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Pal.Card)
        .clickable(interactionSource = null, indication = null) {}) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // кружок цвета (нажать — цвет и раскраска) и значок транспорта (нажать — выбрать)
            Box {
                Box(Modifier.size(30.dp).clip(CircleShape).background(col).border(3.dp, Color.White, CircleShape).clickable { colorMenu = true })
                DropdownMenu(colorMenu, { colorMenu = false }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MAP_COLORS.drop(1).take(6).forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { update { it.copy(color = c) }; onColor(""); colorMenu = false }) }
                    }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MAP_COLORS.drop(7).forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { update { it.copy(color = c) }; onColor(""); colorMenu = false }) }
                    }
                    HorizontalDivider()
                    val hasAlt = !st.minAlt.isNaN(); val hasSpeed = st.speed.any { !it.isNaN() }
                    DropdownMenuItem({ Text("По высоте" + if (!hasAlt) " — нет высот" else "") }, { onColor("alt"); colorMenu = false }, enabled = hasAlt, leadingIcon = { Icon(Icons.Rounded.Terrain, null) })
                    DropdownMenuItem({ Text("По скорости" + if (!hasSpeed) " — нет времени в треке" else "") }, { onColor("speed"); colorMenu = false }, enabled = hasSpeed, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                    DropdownMenuItem({ Text("По перепадам высот") }, { onColor("slope"); colorMenu = false }, enabled = hasAlt, leadingIcon = { Icon(Icons.Rounded.TrendingUp, null) })
                    DropdownMenuItem({ Text("Без раскраски") }, { onColor(""); colorMenu = false })
                }
            }
            Spacer(Modifier.width(6.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // название правится только в раскрытом окне
                if (expanded) androidx.compose.foundation.text.BasicTextField(name, { name = it; tick++ }, textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Pal.Ink),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Pal.Bg).padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 3)
                else Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.clickable { expanded = true })
                // для кого построен трек — просто надпись
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(modeIcon(mode), null, tint = Pal.Muted, modifier = Modifier.size(14.dp))
                    Text(" " + when (mode) { "car" -> "на авто"; "bike" -> "на велосипеде"; else -> "пешком" }, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                }
                Box {
                    Text(Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " · 📁 " + folderPath(t.folderId, folders) + " ▾",
                        style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.clickable { folderMenu = true })
                    DropdownMenu(folderMenu, { folderMenu = false }) {
                        DropdownMenuItem({ Text("Без папки") }, { folderMenu = false; update { it.copy(folderId = null) } })
                        folders.sortedBy { folderPath(it.id, folders).lowercase() }.forEach { f -> DropdownMenuItem({ Text(folderPath(f.id, folders)) }, { folderMenu = false; update { it.copy(folderId = f.id) } }) }
                    }
                }
                trackPlan(t)?.let { at ->
                    Text("Запланировано на " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + if (trackTask(t) != null) " ›" else "",
                        style = MaterialTheme.typography.labelLarge, color = Color(0xFFD13B3B), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = trackTask(t) != null) { MapUi.openTodo = "task:${trackTask(t)}" })
                }
            }
            IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, "Подробнее", tint = Pal.Muted) }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Pal.Muted) }
        }
        // описание: заголовок, под ним текст; править — только в раскрытом окне
        if (expanded || desc.isNotBlank()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Описание", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
            if (expanded) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Pal.Bg).padding(horizontal = 6.dp, vertical = 4.dp)) {
                if (desc.isEmpty()) Text("Добавить описание…", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                androidx.compose.foundation.text.BasicTextField(desc, { desc = it; tick++ }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Pal.Ink), modifier = Modifier.fillMaxWidth())
            } else Text(desc, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Путь", MapPrefs.dist(ctx, t.distanceM))
            if (st.movingMs > 0) Stat("В движении", fmtDur(st.movingMs))
            if (st.movingAvg > 0) Stat("Средняя", "%.1f %s".format(st.movingAvg, MapPrefs.speed(ctx, 0f).second))
            if (!st.minAlt.isNaN()) { Stat("Подъём", "%.0f м".format(st.ascent)); Stat("Спуск", "%.0f м".format(st.descent)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Rounded.PlayArrow, "В путь", Color(0xFF3E6FA8), Color.White) { onGo(t) }
            ActionChip(Icons.Rounded.ZoomOutMap, "Весь трек", Pal.Sky, Pal.SkyInk, onFit)
            ActionChip(Icons.Rounded.AltRoute, if (routeWay(t) != null) "Изменить маршрут" else "Остановки", Pal.Butter, Pal.ButterInk) { onEditRoute(t) }
            ActionChip(Icons.Rounded.VisibilityOff, "Скрыть", Pal.Bg, Pal.Ink) {
                update { it.copy(visible = false) }
                Toast.makeText(ctx, "Трек скрыт — вернуть в «Коллекциях» (глазок)", Toast.LENGTH_SHORT).show(); onClose()
            }
            ActionChip(Icons.Rounded.MoreHoriz, "Ещё", Pal.Lilac, Pal.LilacInk) { more = true }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.5f).dp).verticalScroll(rememberScrollState()).background(Pal.Bg).padding(12.dp)) { TrackStatsContent(t) }
    }
    if (more) BottomPanel({ more = false }) {
        Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        MenuRow(Icons.Rounded.EventAvailable, if (trackPlan(t) != null) "Перенести поездку" else "Запланировать", sub = "дата и время → задача в ToDo и сводке") { more = false; plan = true }
        MenuRow(Icons.Rounded.AltRoute, "Изменить маршрут", sub = if (routeWay(t) != null) "старт, финиш, остановки (порядок), транспорт" else "контрольные точки трека станут остановками") { more = false; onEditRoute(t) }
        MenuRow(Icons.Rounded.SwapHoriz, "Развернуть направление", sub = "старт и финиш меняются местами") {
            more = false
            update { cur ->
                val p2 = Osm.decode(cur.points).reversed()
                val meta = routeWay(cur)?.let { (m, w) -> org.json.JSONObject(cur.meta).put("mode", m).put("way", org.json.JSONArray(w.reversed().map { org.json.JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })).toString() } ?: cur.meta
                cur.copy(points = Osm.encode(p2), meta = meta)
            }
        }
        MenuRow(Icons.Rounded.IosShare, "Экспорт GPX") { more = false; shareFile(ctx, t.name + ".gpx", GeoFiles.gpxOf(emptyList(), listOf(t)), "application/gpx+xml") }
        MenuRow(Icons.Rounded.IosShare, "Экспорт KML (Google Earth)") { more = false; shareFile(ctx, t.name + ".kml", GeoFiles.kmlOf(t.name, emptyList(), emptyList(), listOf(t), null), "application/vnd.google-earth.kml+xml") }
        pts.firstOrNull()?.let { a -> MenuRow(Icons.Rounded.Navigation, "Waze к началу трека") { more = false; waze(ctx, a.lat, a.lon) } }
        MenuRow(Icons.Rounded.DeleteOutline, "Удалить трек", tint = Pal.RoseInk) { more = false; askDelete = true }
    }
    if (plan) PlanDialog(if (trackPlan(t) != null) "Перенести" else "Запланировать", t.name, onDismiss = { plan = false }) { at ->
        plan = false
        AppScope.io.launch {
            val cur = AppDb.get(ctx).map().track(t.id) ?: t
            AppDb.get(ctx).map().upsertTrack(cur.copy(meta = runCatching { if (cur.meta.isBlank()) org.json.JSONObject() else org.json.JSONObject(cur.meta) }.getOrDefault(org.json.JSONObject()).put("plan", at).toString()))
            val end = Osm.decode(cur.points).lastOrNull()
            val task = planTask(ctx, t.id, cur.name, routeWay(cur)?.second?.lastOrNull() ?: end?.let { MapPrefs.Spot("Финиш", it.lat, it.lon) }, at)
            AppDb.get(ctx).map().track(t.id)?.let { tr -> AppDb.get(ctx).map().upsertTrack(tr.copy(meta = org.json.JSONObject(tr.meta.ifBlank { "{}" }).put("task", task).toString())) }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Запланировано — задача в ToDo", Toast.LENGTH_SHORT).show() }
        }
    }
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Удалить трек «${t.name}»?") },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deleteTrack(t) }; askDelete = false; onClose() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Отмена") } })
}

/** Транспорт трека: из meta «mode»; у записанных — пешком, пока не выбрано. */
internal fun trackMode(t: Track): String = runCatching { org.json.JSONObject(t.meta).optString("mode") }.getOrNull()?.ifBlank { null } ?: "foot"
internal fun withMode(t: Track, m: String): Track = t.copy(meta = runCatching { if (t.meta.isBlank()) org.json.JSONObject() else org.json.JSONObject(t.meta) }.getOrDefault(org.json.JSONObject()).put("mode", m).toString())
internal fun modeIcon(m: String) = when (m) { "car" -> Icons.Rounded.DirectionsCar; "bike" -> Icons.AutoMirrored.Rounded.DirectionsBike; else -> Icons.AutoMirrored.Rounded.DirectionsWalk }

@Composable
private fun Stat(label: String, v: String) {
    Column { Text(v, style = MaterialTheme.typography.titleSmall); Text(label, fontSize = 11.sp, color = Pal.Muted) }
}

// ---------------- слои ----------------

@Composable
internal fun LayersPanel(onDismiss: () -> Unit, onChanged: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { MapPrefs.p(ctx) }
    var useOnline by remember { mutableStateOf(p.getBoolean("use_online", false)) }
    var onlineId by remember { mutableStateOf(p.getString("online_id", "osm") ?: "osm") }
    var variant by remember { mutableStateOf(p.getString("variant", "car") ?: "car") }
    var hill by remember { mutableStateOf(p.getBoolean("hillshade", true)) }
    var contours by remember { mutableStateOf(p.getBoolean("contours", true)) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(OnlineMaps.all(ctx)) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "карта.ms"
        val src = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { OnlineMaps.parseMs(it, name) } }.getOrNull()
        if (src == null) Toast.makeText(ctx, "Не похоже на файл .ms", Toast.LENGTH_LONG).show()
        else { OnlineMaps.saveCustom(ctx, OnlineMaps.custom(ctx) + src); sources = OnlineMaps.all(ctx); Toast.makeText(ctx, "Добавлено: ${src.name}", Toast.LENGTH_SHORT).show() }
    }
    fun set(k: String, v: Boolean) { p.edit().putBoolean(k, v).apply(); onChanged() }

    BottomPanel(onDismiss) {
        Text("Слои", style = MaterialTheme.typography.titleLarge)
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            SwitchRow("Тени рельефа", "горы и холмы объёмнее", hill) { hill = it; set("hillshade", it) }
            SwitchRow("Линии высот", "горизонтали с подписями высот", contours) { contours = it; set("contours", it) }
            Text("3D-рельеф включён всегда — наклони карту двумя пальцами. Без интернета он работает там, где скачана «Топография». 3D-домов в движке GLMap нет (как и в Guru Maps).",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(12.dp, 4.dp, 12.dp, 10.dp))
        }
        SectionLabel("Вид карты")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("car", "Авто", Icons.Rounded.DirectionsCar), Triple("foot", "Пешком", Icons.AutoMirrored.Rounded.DirectionsWalk), Triple("bike", "Вело", Icons.AutoMirrored.Rounded.DirectionsBike)).forEach { (k, n, ic) ->
                val sel = !useOnline && variant == k
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Sky else Pal.Card).border(if (sel) 2.dp else 0.dp, Pal.SkyInk, RoundedCornerShape(16.dp))
                    .clickable { variant = k; useOnline = false; p.edit().putString("variant", k).putBoolean("use_online", false).apply(); onChanged() }.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, null, tint = if (sel) Pal.SkyInk else Pal.Ink); Text(n, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(onSettings) { Text("Загрузка карт — страны и области ›") }
        SectionLabel("Онлайн-карты")
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            sources.forEach { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    onlineId = s.id; useOnline = true; p.edit().putString("online_id", s.id).putBoolean("use_online", true).apply(); onChanged()
                }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(useOnline && onlineId == s.id, null)
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    IconButton({
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { runCatching {
                                val u = s.layers.first().tiles.first().replace("{z}", "10").replace("{x}", "598").replace("{y}", "345").replace("{quadkey}", "1202102330")
                                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                                c.connectTimeout = 8000; c.readTimeout = 8000
                                "Ответ сервера: ${c.responseCode} ${c.contentType ?: ""}\n${u.take(90)}"
                            }.getOrElse { "Ошибка: ${it.message}" } }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Rounded.NetworkCheck, "Проверить", tint = Pal.Muted) }
                    if (!s.builtIn) IconButton({
                        OnlineMaps.saveCustom(ctx, OnlineMaps.custom(ctx).filter { it.id != s.id }); sources = OnlineMaps.all(ctx)
                        if (onlineId == s.id && useOnline) { useOnline = false; p.edit().putBoolean("use_online", false).apply(); onChanged() }
                    }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.Muted) }
                }
            }
        }
        PillButton("Добавить карту из файла .ms", { import.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
        Text("Онлайн-карта рисуется вместо векторной (метки, треки и маршрут — поверх). Тайлы сохраняются в кэш: Настройки → Кэш карт.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    }
}

@Composable
internal fun SwitchRow(title: String, sub: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onChange(!value) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Pal.Ink else Pal.Muted)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        }
        Switch(value, onChange, enabled = enabled)
    }
}

// ---------------- маршрут ----------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))


/** Плитка «откуда / остановка / сюда» — значок пути (S-линия) с точкой в начале, посередине или стрелкой в конце. */
@Composable
internal fun RouteTile(kind: Int, label: String, onClick: () -> Unit) {
    val ink = Pal.SkyInk
    Box(Modifier.size(width = 50.dp, height = 40.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Sky).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 24.dp, height = 17.dp)) {
            val w = size.width; val h = size.height; val sw = 1.5.dp.toPx()
            val st = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val r = h * 0.25f
            // S: снизу справа налево, дуга, вправо, дуга, влево наверх
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.22f, h - sw); lineTo(w * 0.72f, h - sw)
                quadraticTo(w * 0.95f, h - sw, w * 0.95f, h * 0.75f); quadraticTo(w * 0.95f, h * 0.5f, w * 0.72f, h * 0.5f)
                lineTo(w * 0.3f, h * 0.5f)
                quadraticTo(w * 0.06f, h * 0.5f, w * 0.06f, h * 0.25f); quadraticTo(w * 0.06f, sw, w * 0.3f, sw)
                lineTo(w * 0.78f, sw)
            }
            drawPath(path, ink, style = st)
            val dot = 2.4.dp.toPx()
            when (kind) {
                0 -> drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(w * 0.14f, h - sw))
                1 -> { drawCircle(Pal.Sky, dot + sw, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)); drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)) }
                else -> {
                    drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.78f, sw), androidx.compose.ui.geometry.Offset(w * 0.66f, sw - r * 0.9f + sw), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.78f, sw), androidx.compose.ui.geometry.Offset(w * 0.66f, sw + r * 0.9f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(ink, dot, androidx.compose.ui.geometry.Offset(w * 0.92f, sw + 1f))
                }
            }
        }
    }
}

/** Фото на весь экран: листание, «Сохранить в галерею», «Отправить». */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PhotoViewer(list: List<android.graphics.Bitmap>, start: Int, title: String, onDismiss: () -> Unit) {
    if (list.isEmpty()) { onDismiss(); return }
    val ctx = LocalContext.current
    val pager = androidx.compose.foundation.pager.rememberPagerState(start.coerceIn(0, list.size - 1)) { list.size }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                Image(list[i].asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().dialogBars().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${list.size}", color = Color.White, modifier = Modifier.weight(1f))
                IconButton({ savePhoto(ctx, list[pager.currentPage], title) }) { Icon(Icons.Rounded.Download, "Сохранить в галерею", tint = Color.White) }
                IconButton({ sharePhoto(ctx, list[pager.currentPage], title) }) { Icon(Icons.Rounded.Share, "Отправить", tint = Color.White) }
            }
        }
    }
}

private fun photoName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "photo" } + "_" + System.currentTimeMillis() + ".jpg"

internal fun savePhoto(ctx: Context, b: android.graphics.Bitmap, title: String) {
    runCatching {
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, photoName(title))
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Всё в одном")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
    }.onSuccess { Toast.makeText(ctx, "Сохранено в галерею", Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(ctx, "Не сохранилось: ${it.message}", Toast.LENGTH_LONG).show() }
}

internal fun sharePhoto(ctx: Context, b: android.graphics.Bitmap, title: String) {
    runCatching {
        val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = java.io.File(dir, photoName(title)); f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить"))
    }
}

/** Описание трека хранится в Track.meta (JSON, ключ «desc») — без новой версии базы. */
internal fun trackDesc(t: Track): String? = runCatching { org.json.JSONObject(t.meta).optString("desc").ifBlank { null } }.getOrNull()
internal fun withDesc(t: Track, d: String): Track {
    val o = runCatching { if (t.meta.isBlank()) org.json.JSONObject() else org.json.JSONObject(t.meta) }.getOrDefault(org.json.JSONObject())
    if (d.isBlank()) o.remove("desc") else o.put("desc", d.trim())
    return t.copy(meta = if (o.length() == 0) "" else o.toString())
}

/** Улица: вид дороги, покрытие, полосы, скорость, город/район, Википедия; «Показать всю улицу», маршрут к её середине и 4 ссылки внизу. */
@Composable
private fun StreetBlock(t: MapTarget, wiki: PlaceInfo.Wiki?, info: PlaceInfo.Info?, city: String?, onWhole: ((MapTarget) -> Unit)?, onRoute: (String, MapPrefs.Spot) -> Unit) {
    val ctx = LocalContext.current
    val p = t.props
    val named = !t.name.startsWith("Улица без") && t.name.isNotBlank()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Об улице", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        @Composable fun row(k: String, v: String?) { if (!v.isNullOrBlank()) Row { Text(k, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.width(120.dp)); Text(v, style = MaterialTheme.typography.bodyMedium) } }
        row("Вид дороги", Streets.kind(p["highway"]))
        row("Где", listOfNotNull(info?.admin?.let { a -> a.drop(1).takeLast(3).reversed().joinToString(", ").ifBlank { null } } ?: city).firstOrNull())
        row("Покрытие", p["surface"]?.let { Streets.SURFACE[it] ?: it })
        row("Полос", p["lanes"])
        row("Скорость", p["maxspeed"]?.let { if (it.all(Char::isDigit)) "$it км/ч" else it })
        row("Одностороннее", if (p["oneway"] == "yes") "да" else null)
        row("Освещение", p["lit"]?.let { if (it == "yes") "есть" else "нет" })
        row("Номер дороги", p["ref"])
        row("Прежнее название", p["old_name"])
        wiki?.let { w ->
            var full by remember(w) { mutableStateOf(false) }
            Text(w.extract, style = MaterialTheme.typography.bodyMedium, maxLines = if (full) Int.MAX_VALUE else 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clickable { full = !full })
        }
        if (named && onWhole != null) Row(Modifier.padding(top = 6.dp)) { ActionChip(Icons.Rounded.Timeline, "Показать всю улицу", Pal.Sky, Pal.SkyInk) { onWhole(t) } }
    }
}

@Composable
private fun LinkTile(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Pal.SkyInk, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Сведения о тропе/маршруте из OSM (длина, откуда—куда, оператор, описание) и Википедии, ссылки на OSM и сайт. */
@Composable
private fun RouteInfoBlock(t: MapTarget, wiki: PlaceInfo.Wiki?, info: PlaceInfo.Info?) {
    val ctx = LocalContext.current
    val p = t.props
    val tg = info?.tags.orEmpty()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("О маршруте", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        @Composable fun row(k: String, v: String?) { if (!v.isNullOrBlank()) Row { Text(k, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.width(110.dp)); Text(v, style = MaterialTheme.typography.bodyMedium) } }
        row("Длина", p["distance"]?.let { d -> d.replace(',', '.').toDoubleOrNull()?.let { "%.1f км".format(it) } ?: d })
        row("Номер", p["ref"])
        row("Откуда", p["from"]); row("Куда", p["to"])
        row("Кольцевой", if (p["roundtrip"] == "yes") "да" else null)
        row("Ведёт", p["operator"])
        row("Знак", p["symbol"])
        row("Покрытие", (p["surface"] ?: tg["surface"])?.let { SURFACES[it] ?: it })
        row("Сложность", tg["sac_scale"]?.let { SAC[it] ?: it })
        row("Ширина", tg["width"]?.let { "$it м" })
        row("Освещение", tg["lit"]?.let { if (it == "yes") "есть" else "нет" })
        row("Велосипед", tg["bicycle"]?.let { if (it in setOf("yes", "designated")) "можно" else if (it == "no") "нельзя" else it })
        p["description"]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (p["osm"] == null && p["distance"] == null && tg.isEmpty() && wiki == null) Text(if (Net.online) "Подсвечена на карте целиком. Ищу сведения в OpenStreetMap и Википедии…" else "Подсвечена на карте целиком. Сведения из OSM и Википедии — при интернете.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        wiki?.let { w ->
            var full by remember(w) { mutableStateOf(false) }
            Text(w.extract, style = MaterialTheme.typography.bodyMedium, maxLines = if (full) Int.MAX_VALUE else 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clickable { full = !full })
            Text("Читать в Википедии ›", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk, modifier = Modifier.clickable { web(ctx, w.url) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (p["osm"]?.let { "https://www.openstreetmap.org/relation/$it" } ?: info?.osmUrl?.ifBlank { null })?.let { u -> ActionChip(Icons.Rounded.Map, "OpenStreetMap", Pal.Lilac, Pal.LilacInk) { web(ctx, u) } }
            p["website"]?.let { u -> ActionChip(Icons.Rounded.Language, "Сайт", Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://$u") } }
            p["osm"]?.let { id -> ActionChip(Icons.Rounded.Route, "Профиль высот", Pal.Sky, Pal.SkyInk) { web(ctx, "https://hiking.waymarkedtrails.org/#route?id=$id") } }
        }
    }
}

private val SURFACES = mapOf("asphalt" to "асфальт", "paved" to "твёрдое", "unpaved" to "грунт", "ground" to "грунт", "dirt" to "земля", "gravel" to "гравий",
    "fine_gravel" to "мелкий гравий", "compacted" to "укатанное", "grass" to "трава", "sand" to "песок", "paving_stones" to "плитка", "concrete" to "бетон",
    "wood" to "дерево", "rock" to "камень", "pebblestone" to "галька", "mud" to "грязь")
private val SAC = mapOf("hiking" to "лёгкая (T1)", "mountain_hiking" to "горная (T2)", "demanding_mountain_hiking" to "сложная горная (T3)",
    "alpine_hiking" to "альпийская (T4)", "demanding_alpine_hiking" to "сложная альпийская (T5)", "difficult_alpine_hiking" to "очень сложная (T6)")
