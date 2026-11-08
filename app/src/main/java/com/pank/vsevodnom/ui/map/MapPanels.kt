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
            val bottom = maxOf(ri.bottom, ri.ime, sysBottom, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())
            val top = maxOf(ri.top, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            Column(Modifier.padding(top = top + 24.dp).fillMaxWidth().wrapContentHeight(Alignment.Bottom).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Pal.Bg)
                .clickable(interactionSource = null, indication = null) {}.padding(top = 8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(Pal.Line))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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
                       onSimilar: ((MapTarget) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(t) { mutableStateOf<PlaceInfo.Info?>(null) }
    var g by remember(t) { mutableStateOf<GooglePlaces.Details?>(null) }
    var gErr by remember(t) { mutableStateOf<String?>(null) }
    var loading by remember(t) { mutableStateOf(false) }
    var admin by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember(t) { mutableStateOf(true) }
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
    var schedule by remember { mutableStateOf(false) }
    val online = Net.online && !MapPrefs.offlineOnly(ctx)
    val isPoint = t.name == "Точка на карте"
    // объект без названия (ворота, урна, площадка…) или тропа — Google и TripAdvisor по нему не ищем
    val noName = isPoint || (t.place == null && t.props.isNotEmpty() && t.props["name"] == null && !t.props.containsKey("google_id"))
    val hasKey = ApiKeys.google(ctx).isNotBlank()
    var gAsk by remember(t) { mutableStateOf(false) }     // лимит почти исчерпан — грузим только по кнопке
    fun loadGoogle() {
        if (!online || !hasKey || noName) return
        scope.launch {
            loading = true; gAsk = false
            runCatching { t.props["google_id"]?.let { GooglePlaces.details(ctx, it) } ?: GooglePlaces.near(ctx, t.name, t.lat, t.lon) }
                .onSuccess { g = it }.onFailure { gErr = it.message }
            loading = false
            g?.photos?.take(3)?.forEach { n -> GooglePlaces.photo(ctx, n, 1000)?.let { photos += it } }
        }
    }
    LaunchedEffect(t) {
        admin = withContext(Dispatchers.IO) { runCatching { UaAdmin.lookup(ctx, t.lat, t.lon) }.getOrDefault(emptyList()) }
        // уже смотрели это место, пока приложение открыто, — показываем без нового запроса
        val known = GooglePlaces.cached(t.props["google_id"] ?: if (noName) null else GooglePlaces.knownId(ctx, t.name, t.lat, t.lon))
        if (known != null) { g = known; known.photos.take(3).forEach { n -> GooglePlaces.cachedPhoto(n)?.let { photos += it } } }
        else if (MapPrefs.bool(ctx, "google_auto", true) || t.props.containsKey("google_id")) { if (ApiKeys.nearlyOut(ctx, "google")) gAsk = hasKey && online && !noName else loadGoogle() }
        if (online) {
            info = runCatching { PlaceInfo.load(t.name.takeIf { t.place == null && !noName }, t.lat, t.lon) }.getOrNull()
            if (photos.isEmpty()) info?.wiki?.image?.let { u -> wikiImg = runCatching { loadImage(ctx, u, 700) }.getOrNull() }
        }
    }
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
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(listOfNotNull(type, if (t.place != null) "📁 " + folderPath(t.place.folderId, folders) else null, dist?.let { MapPrefs.dist(ctx, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                g?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        d.rating?.let { r -> Text("%.1f ".format(r), style = MaterialTheme.typography.titleSmall); Text("★".repeat(r.toInt().coerceIn(0, 5)), color = Color(0xFFE9A92B), fontSize = 13.sp)
                            Text(" (${d.count ?: 0})", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                        d.price?.let { Text(" · $it", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    }
                    d.openNow?.let { open ->
                        val todayLine = d.hours.getOrNull(today)?.substringAfter(": ", "").orEmpty()
                        Text((if (open) "Открыто" else "Закрыто") + if (todayLine.isNotBlank()) " · сегодня $todayLine" else "", style = MaterialTheme.typography.bodyMedium,
                            color = if (open) Pal.MintInk else Pal.RoseInk, maxLines = 1)
                    }
                }
                if (g?.openNow == null) osmWeek?.let { w ->
                    val now = java.time.LocalTime.now()
                    val st = OpeningHours.status(w, today, now.hour * 60 + now.minute)
                    Text(st.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = when { st.open && !st.soon -> Pal.MintInk; st.soon -> Color(0xFFB7791F); else -> Pal.RoseInk }, maxLines = 1)
                } ?: tags["opening_hours"]?.takeIf { g == null }?.let { Text("Часы: $it", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1) }
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
            } else ActionChip(Icons.Rounded.Edit, if (editing) "Готово" else "Изменить", Pal.Ink, Color.White) { editing = !editing }
            val spot = MapPrefs.Spot(title, t.lat, t.lon, type.orEmpty())
            RouteTile(0, "Отсюда") { onRoute("from", spot) }
            RouteTile(1, "Остановка") { onRoute("stop", spot) }
            RouteTile(2, "Сюда") { onRoute("to", spot) }
            ActionChip(Icons.Rounded.Navigation, "Waze", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, t.lat, t.lon) }
            ActionChip(Icons.Rounded.Share, "Поделиться", Pal.Lilac, Pal.LilacInk) { share(ctx, title, t.lat, t.lon) }
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
            }
            t.place?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
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
                if (!noName) ActionChip(Icons.Rounded.TravelExplore, "TripAdvisor", Color(0xFFDDF3E8), Color(0xFF00744A)) { web(ctx, "https://www.tripadvisor.com/Search?q=" + Uri.encode(q)) }
                (ownBooking ?: if (stay && t.place == null) StayLinks.bookingSearch(q) else null)?.let { u -> ActionChip(Icons.Rounded.Hotel, "Booking", Color(0xFFDCE6F7), Color(0xFF003B95)) { web(ctx, u) } }
                (ownAirbnb ?: if (stay && t.place == null) StayLinks.airbnbSearch(q) else null)?.let { u -> ActionChip(Icons.Rounded.Cottage, "Airbnb", Color(0xFFFDE3E7), Color(0xFFD9344F)) { web(ctx, u) } }
            }
            g?.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            info?.wiki?.let { w -> Text(w.extract, style = MaterialTheme.typography.bodySmall, maxLines = 6) }
            // отзывы
            g?.reviews?.takeIf { it.isNotEmpty() }?.let { rs ->
                Text("Отзывы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                rs.take(5).forEach { r ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).padding(10.dp)) {
                        Text(r.author + " · " + r.when_, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        Text("★".repeat(r.rating.coerceIn(0, 5)), color = Color(0xFFE9A92B), fontSize = 12.sp)
                        if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                    }
                }
            }
            if (g == null && hasKey && online && !noName && !loading) {
                val used = ApiKeys.used(ctx, "google"); val lim = ApiKeys.limit(ctx, "google")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (gAsk) Pal.Butter else Pal.Bg).clickable { loadGoogle() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (gAsk) Icons.Rounded.Info else Icons.Rounded.CloudDownload, null, tint = if (gAsk) Pal.ButterInk else Pal.SkyInk); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (gAsk) "Лимит Google почти исчерпан" else "Загрузить сведения Google", style = MaterialTheme.typography.titleSmall)
                        Text(if (gAsk) "Использовано $used из $lim на сегодня. Подтянуть оценку, фото и часы для этого места?" else "оценка, фото, часы, отзывы · $used из $lim за сегодня",
                            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                }
            }
            gErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // в Google Картах ищем по названию и городу — так открывается само заведение, а не голые координаты
                PillButton("Google Карты", { web(ctx, g?.mapsUrl ?: if (noName) String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", t.lat, t.lon)
                    else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(listOfNotNull(title, city).joinToString(", "))) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
                PillButton("OpenStreetMap", { web(ctx, info?.osmUrl?.ifBlank { null } ?: String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=18/%.6f/%.6f", t.lat, t.lon, t.lat, t.lon)) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
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
        if (onSimilar != null && t.props["kind"] != "route" && (t.props["subclass"] != null || t.props["class"] != null))
            MenuRow(Icons.Rounded.TravelExplore, "Похожие рядом", sub = "${t.kind.ifBlank { "такие же места" }} поблизости — на карте") { more = false; onSimilar(t) }
        MenuRow(Icons.Rounded.EventAvailable, "Запланировать маршрут", sub = "дата и время → задача в ToDo и сводке") { more = false; schedule = true }
        MenuRow(Icons.Rounded.Star, "В избранное маршрутов") { MapPrefs.setFavs(ctx, MapPrefs.favs(ctx) + MapPrefs.Spot(title.take(24), t.lat, t.lon, title)); more = false; Toast.makeText(ctx, "Добавлено в избранное маршрутов", Toast.LENGTH_SHORT).show() }
        MenuRow(Icons.Rounded.Event, "Событие в календаре") {
            val st = LocalDate.now().plusDays(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            event = CalEvent(title = title, startMillis = st, endMillis = st + 3600_000, note = String.format(Locale.US, "geo:%.6f,%.6f", t.lat, t.lon)); more = false
        }
        MenuRow(Icons.Rounded.ContentCopy, "Копировать координаты") { copy(ctx, MapPrefs.coords(ctx, t.lat, t.lon)); more = false }
        if (t.place != null) {
            MenuRow(Icons.Rounded.Hotel, if (StayLinks.get(ctx, t.place.id, "booking") != null) "Ссылка Booking…" else "Подключить Booking") { linkEdit = "booking"; more = false }
            MenuRow(Icons.Rounded.Cottage, if (StayLinks.get(ctx, t.place.id, "airbnb") != null) "Ссылка Airbnb…" else "Подключить Airbnb") { linkEdit = "airbnb"; more = false }
        }
        MenuRow(Icons.Rounded.Map, "Открыть в другом приложении") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", t.lat, t.lon, t.lat, t.lon, Uri.encode(title))))) }; more = false
        }
    }
    event?.let { e -> EventEditor(e, onDismiss = { event = null }) }
    viewer?.let { i -> PhotoViewer(if (photos.isNotEmpty()) photos.toList() else listOfNotNull(wikiImg), i, title) { viewer = null } }
    if (schedule) RouteScheduleDialog(title, t.lat, t.lon) { schedule = false }
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
@Composable
internal fun PlaceInlineEditor(p: Place, folders: List<MapFolder>, onDone: (Place?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember(p.id) { mutableStateOf(p.name) }
    var note by remember(p.id) { mutableStateOf(p.note) }
    var folder by remember(p.id) { mutableStateOf(p.folderId) }
    var color by remember(p.id) { mutableIntStateOf(p.color) }
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
        AppTextField(note, { note = it }, "Описание", singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Сохранить", {
                val saved = p.copy(name = name.trim().ifBlank { p.name }, note = note.trim(), folderId = folder, color = color)
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
internal fun TrackCard(t: Track, folders: List<MapFolder>, onClose: () -> Unit, onFit: () -> Unit, color: String = "", onColor: (String) -> Unit = {}, onEditRoute: (Track) -> Unit = {}) {
    val ctx = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    var expanded by remember(t.id) { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val pts = remember(t.id, t.points) { Osm.decode(t.points) }
    val st = remember(t.id, t.points) { TrackMath.stats(pts) }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val col = if (t.color != 0) Color(t.color) else Color(0xFF6A55A8)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Pal.Card)
        .clickable(interactionSource = null, indication = null) {}) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " · " + folderPath(t.folderId, folders),
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, "Подробнее", tint = Pal.Muted) }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Pal.Muted) }
        }
        trackDesc(t)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), maxLines = if (expanded) 20 else 3) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Путь", MapPrefs.dist(ctx, t.distanceM))
            if (st.movingMs > 0) Stat("В движении", fmtDur(st.movingMs))
            if (st.movingAvg > 0) Stat("Средняя", "%.1f %s".format(st.movingAvg, MapPrefs.speed(ctx, 0f).second))
            if (!st.minAlt.isNaN()) { Stat("Подъём", "%.0f м".format(st.ascent)); Stat("Спуск", "%.0f м".format(st.descent)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Rounded.Edit, "Изменить", Pal.Ink, Color.White) { edit = true }
            ActionChip(Icons.Rounded.DeleteOutline, "Удалить", Pal.Rose, Pal.RoseInk) { askDelete = true }
            ActionChip(Icons.Rounded.ZoomOutMap, "Весь трек", Pal.Sky, Pal.SkyInk, onFit)
            Box {
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Butter).clickable { colorMenu = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(col).border(2.dp, Color.White, CircleShape)); Spacer(Modifier.width(6.dp))
                    Text(when (color) { "alt" -> "По высоте"; "speed" -> "По скорости"; "slope" -> "По перепадам"; else -> "Цвет" }, style = MaterialTheme.typography.labelLarge, color = Pal.ButterInk)
                }
                DropdownMenu(colorMenu, { colorMenu = false }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MAP_COLORS.drop(1).take(6).forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable {
                            AppScope.io.launch { AppDb.get(ctx).map().upsertTrack(t.copy(color = c)) }; onColor(""); colorMenu = false }) }
                    }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MAP_COLORS.drop(7).forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable {
                            AppScope.io.launch { AppDb.get(ctx).map().upsertTrack(t.copy(color = c)) }; onColor(""); colorMenu = false }) }
                    }
                    HorizontalDivider()
                    val hasAlt = !st.minAlt.isNaN(); val hasSpeed = st.speed.any { !it.isNaN() }
                    DropdownMenuItem({ Text("По высоте" + if (!hasAlt) " — нет высот в треке" else "") }, { onColor("alt"); colorMenu = false }, enabled = hasAlt, leadingIcon = { Icon(Icons.Rounded.Terrain, null) })
                    DropdownMenuItem({ Text("По скорости" + if (!hasSpeed) " — нет времени в треке" else "") }, { onColor("speed"); colorMenu = false }, enabled = hasSpeed, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                    DropdownMenuItem({ Text("По перепадам высот") }, { onColor("slope"); colorMenu = false }, enabled = hasAlt, leadingIcon = { Icon(Icons.Rounded.TrendingUp, null) })
                    DropdownMenuItem({ Text("Без раскраски") }, { onColor(""); colorMenu = false })
                }
            }
            pts.firstOrNull()?.let { a -> ActionChip(Icons.Rounded.Navigation, "Waze к началу", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, a.lat, a.lon) } }
            pts.lastOrNull()?.let { e -> ActionChip(Icons.Rounded.SportsScore, "Waze к концу", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, e.lat, e.lon) } }
            ActionChip(Icons.Rounded.IosShare, "GPX", Pal.Lilac, Pal.LilacInk) { shareFile(ctx, t.name + ".gpx", GeoFiles.gpxOf(emptyList(), listOf(t)), "application/gpx+xml") }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.5f).dp).verticalScroll(rememberScrollState()).background(Pal.Bg).padding(12.dp)) { TrackStatsContent(t) }
    }
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Удалить трек «${t.name}»?") },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deleteTrack(t) }; askDelete = false; onClose() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Отмена") } })
    if (edit) TrackEditPanel(t, folders, onDismiss = { edit = false }, onEditRoute = { edit = false; onEditRoute(t) })
}

/** Правка трека: название, папка, направление; для построенных маршрутов — старт, финиш, остановки, транспорт. */
@Composable
private fun TrackEditPanel(t: Track, folders: List<MapFolder>, onDismiss: () -> Unit, onEditRoute: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(t.name) }
    var desc by remember { mutableStateOf(trackDesc(t).orEmpty()) }
    var folder by remember { mutableStateOf(t.folderId) }
    var open by remember { mutableStateOf(false) }
    BottomPanel(onDismiss) {
        Text("Трек", style = MaterialTheme.typography.titleLarge)
        AppTextField(name, { name = it }, "Название")
        AppTextField(desc, { desc = it }, "Описание", singleLine = false)
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { open = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = Pal.ButterInk); Spacer(Modifier.width(8.dp))
                Text(folderPath(folder, folders), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ArrowDropDown, null, tint = Pal.Muted)
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("Без папки") }, { folder = null; open = false })
                folders.sortedBy { folderPath(it.id, folders).lowercase() }.forEach { f -> DropdownMenuItem({ Text(folderPath(f.id, folders)) }, { folder = f.id; open = false }) }
            }
        }
        MenuRow(Icons.Rounded.AltRoute, "Изменить маршрут", sub = "старт, финиш, остановки (порядок), транспорт") { onEditRoute() }
        MenuRow(Icons.Rounded.SwapHoriz, "Развернуть направление", sub = "старт и финиш меняются местами") {
            AppScope.io.launch {
                val pts = Osm.decode(t.points).reversed()
                val meta = routeWay(t)?.let { (m, w) -> org.json.JSONObject(t.meta).put("mode", m).put("way", org.json.JSONArray(w.reversed().map { org.json.JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })).toString() } ?: t.meta
                AppDb.get(ctx).map().upsertTrack(t.copy(points = Osm.encode(pts), meta = meta))
            }
            onDismiss()
        }
        PillButton("Сохранить", { AppScope.io.launch { AppDb.get(ctx).map().upsertTrack(withDesc(t, desc).copy(name = name.trim().ifBlank { t.name }, folderId = folder)) }; onDismiss() }, Modifier.fillMaxWidth())
    }
}

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
    var relief by remember { mutableStateOf(p.getBoolean("relief", true)) }
    var traffic by remember { mutableStateOf(p.getBoolean("traffic", false)) }
    var b3d by remember { mutableStateOf(p.getBoolean("buildings3d", false)) }
    var metro by remember { mutableStateOf(p.getBoolean("metro", true)) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(OnlineMaps.all(ctx)) }
    val installed = remember(OfflineMaps.version) { OfflineMaps.installed(ctx) }
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
            SwitchRow("Линии высот", "через 20 м, подписи через 100 м", contours) { contours = it; set("contours", it) }
            SwitchRow("3D-дома", "объёмные здания вблизи; на слабой графике может подтормаживать", b3d) { b3d = it; set("buildings3d", it) }
            Text("Слои рельефа работают для скачанных стран.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(12.dp, 4.dp, 12.dp, 10.dp))
        }
        SectionLabel("Офлайн-карты")
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
        Text(if (installed.isEmpty()) "Страны ещё не скачаны — пока видна только карта мира." else "Скачано: " + installed.keys.joinToString { id -> OfflineMaps.catalog?.countries?.firstOrNull { it.id == id }?.name ?: id },
            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
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
        Text("Онлайн-тайлы сохраняются в кэш и показываются без интернета там, где уже смотрел. Кэш чистится только вручную: Настройки → Кэш карт.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
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
    Box(Modifier.size(width = 52.dp, height = 42.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Sky).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 22.dp)) {
            val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
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
            val dot = 3.2.dp.toPx()
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

/** «Запланировать маршрут»: дата и время → задача в ToDo (с местом во вложении и напоминанием за 30 минут). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteScheduleDialog(name: String, lat: Double, lon: Double, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    val tp = rememberTimePickerState(10, 0, true)
    if (step == 0) DatePickerDialog(onDismissRequest = onDone, confirmButton = { TextButton({ step = 1 }) { Text("Дальше") } }, dismissButton = { TextButton(onDone) { Text("Отмена") } }) {
        DatePicker(dp, title = { Text("Когда едем в «${name.take(30)}»", Modifier.padding(16.dp)) })
    } else AlertDialog(onDismissRequest = onDone, title = { Text("Во сколько") }, text = { TimePicker(tp) },
        confirmButton = { TextButton({
            val day = java.time.Instant.ofEpochMilli(dp.selectedDateMillis ?: System.currentTimeMillis()).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val att = com.pank.vsevodnom.ui.todo.NoteAtt(place = Triple(name, lat, lon))
            val item = TaskItem(title = "Маршрут: $name", note = String.format(Locale.US, "geo:%.6f,%.6f", lat, lon), dueDay = day.toEpochDay(), dueMinute = tp.hour * 60 + tp.minute,
                remindBefore = 30, tags = "маршрут", attachments = att.toJson())
            AppScope.io.launch {
                val id = AppDb.get(ctx).todo().upsertTask(item)
                Todo.scheduleReminder(ctx, item.copy(id = id))
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "Запланировано: ${day.format(DateTimeFormatter.ofPattern("d MMM"))} в %02d:%02d — в ToDo".format(tp.hour, tp.minute), Toast.LENGTH_LONG).show() }
            }
            onDone()
        }) { Text("Запланировать") } }, dismissButton = { TextButton({ step = 0 }) { Text("Назад") } })
}

/** Описание трека хранится в Track.meta (JSON, ключ «desc») — без новой версии базы. */
internal fun trackDesc(t: Track): String? = runCatching { org.json.JSONObject(t.meta).optString("desc").ifBlank { null } }.getOrNull()
internal fun withDesc(t: Track, d: String): Track {
    val o = runCatching { if (t.meta.isBlank()) org.json.JSONObject() else org.json.JSONObject(t.meta) }.getOrDefault(org.json.JSONObject())
    if (d.isBlank()) o.remove("desc") else o.put("desc", d.trim())
    return t.copy(meta = if (o.length() == 0) "" else o.toString())
}
