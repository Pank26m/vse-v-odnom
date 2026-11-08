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
            val bottom = maxOf(ri.bottom, ri.ime, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())
            val top = maxOf(ri.top, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            Column(Modifier.padding(top = top + 24.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Pal.Bg)
                .clickable(interactionSource = null, indication = null) {}.padding(top = 8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(Pal.Line))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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
private fun ActionChip(icon: ImageVector, text: String, bg: Color = Pal.Card, fg: Color = Pal.Ink, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Карточка объекта как в Google Картах: шапка с оценкой → кнопки → фото → часы → сайт/соцсети → отзывы → ссылки → координаты. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceCard(t: MapTarget, me: GeoPt?, folders: List<MapFolder>, onClose: () -> Unit, onRoute: (MapPrefs.Spot) -> Unit, onSaved: (Place) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(t) { mutableStateOf<PlaceInfo.Info?>(null) }
    var g by remember(t) { mutableStateOf<GooglePlaces.Details?>(null) }
    var gErr by remember(t) { mutableStateOf<String?>(null) }
    var loading by remember(t) { mutableStateOf(false) }
    var admin by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember(t) { mutableStateOf(true) }
    var editing by remember(t) { mutableStateOf(t.props["edit"] == "1") }
    var more by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<CalEvent?>(null) }
    val photos = remember(t) { mutableStateListOf<android.graphics.Bitmap>() }
    var wikiImg by remember(t) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var allHours by remember(t) { mutableStateOf(false) }
    val online = Net.online && !MapPrefs.offlineOnly(ctx)
    val isPoint = t.name == "Точка на карте"
    val hasKey = ApiKeys.google(ctx).isNotBlank()
    fun loadGoogle() {
        if (!online || !hasKey || isPoint) return
        scope.launch {
            loading = true
            runCatching { t.props["google_id"]?.let { GooglePlaces.details(ctx, it) } ?: GooglePlaces.near(ctx, t.name, t.lat, t.lon) }
                .onSuccess { g = it }.onFailure { gErr = it.message }
            loading = false
            g?.photos?.take(3)?.forEach { n -> GooglePlaces.photo(ctx, n, 700)?.let { photos += it } }
        }
    }
    LaunchedEffect(t) {
        admin = withContext(Dispatchers.IO) { runCatching { UaAdmin.lookup(ctx, t.lat, t.lon) }.getOrDefault(emptyList()) }
        if (MapPrefs.bool(ctx, "google_auto", true) || t.props.containsKey("google_id")) loadGoogle()
        if (online) {
            info = runCatching { PlaceInfo.load(t.name.takeIf { t.place == null && !isPoint }, t.lat, t.lon) }.getOrNull()
            if (photos.isEmpty()) info?.wiki?.image?.let { u -> wikiImg = runCatching { loadImage(ctx, u, 700) }.getOrNull() }
        }
    }
    val title = if (isPoint) g?.name?.ifBlank { null } ?: info?.name?.ifBlank { null } ?: info?.address?.ifBlank { null } ?: t.name else t.name
    val type = g?.type ?: t.kind.ifBlank { null }
    val dist = me?.let { Osm.distance(it, GeoPt(t.lat, t.lon, 0)) }
    val tags = info?.tags.orEmpty()
    val today = (java.time.LocalDate.now().dayOfWeek.value - 1)
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Pal.Card)) {
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
                tags["opening_hours"]?.takeIf { g == null }?.let { Text("Часы: $it", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1) }
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
            ActionChip(Icons.Rounded.Directions, "Маршрут", Pal.Sky, Pal.SkyInk) { onRoute(MapPrefs.Spot(title, t.lat, t.lon, type.orEmpty())) }
            ActionChip(Icons.Rounded.Navigation, "Waze", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, t.lat, t.lon) }
            g?.phone?.let { ph -> ActionChip(Icons.Rounded.Call, "Позвонить") { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) } } }
            ActionChip(Icons.Rounded.Share, "Поделиться") { share(ctx, title, t.lat, t.lon) }
            ActionChip(Icons.Rounded.MoreHoriz, "Ещё") { more = true }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.42f).dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editing && t.place != null) PlaceInlineEditor(t.place, folders) { saved -> editing = false; if (saved != null) onSaved(saved) else onClose() }
            // фото
            if (photos.isNotEmpty() || wikiImg != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (photos.isNotEmpty()) photos.toList() else listOfNotNull(wikiImg)).forEach { b ->
                    Image(b.asImageBitmap(), null, Modifier.height(150.dp).widthIn(min = 120.dp, max = 260.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                }
            }
            t.place?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            // часы
            g?.hours?.takeIf { it.isNotEmpty() }?.let { hrs ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable { allHours = !allHours }.padding(12.dp)) {
                    Text("Часы работы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    hrs.forEachIndexed { i, line ->
                        if (allHours || i == today) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(line.substringBefore(": ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(120.dp))
                            Text(line.substringAfter(": ", ""), style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    if (!allHours) Text("вся неделя ▾", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            // сайт и соцсети
            val links = buildList {
                (g?.site ?: tags["website"] ?: tags["contact:website"])?.let { add(Triple(Icons.Rounded.Language, "Сайт", it)) }
                (tags["contact:instagram"] ?: tags["instagram"])?.let { add(Triple(Icons.Rounded.PhotoCamera, "Instagram", if (it.startsWith("http")) it else "https://instagram.com/" + it.trimStart('@'))) }
                (tags["contact:facebook"] ?: tags["facebook"])?.let { add(Triple(Icons.Rounded.Groups, "Facebook", if (it.startsWith("http")) it else "https://facebook.com/$it")) }
                (tags["contact:telegram"] ?: tags["telegram"])?.let { add(Triple(Icons.AutoMirrored.Rounded.Send, "Telegram", if (it.startsWith("http")) it else "https://t.me/" + it.trimStart('@'))) }
            }
            if (links.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                links.forEach { (ic, n, u) -> ActionChip(ic, n, Pal.Lilac, Pal.LilacInk) { web(ctx, if (u.startsWith("http")) u else "https://$u") } }
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
            if (g == null && hasKey && online && !isPoint && !loading) TextButton({ loadGoogle() }) { Text("Загрузить сведения Google") }
            gErr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Google Карты", { web(ctx, g?.mapsUrl ?: String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", t.lat, t.lon)) }, Modifier.weight(1f), bg = Pal.Bg, fg = Pal.Ink)
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
        MenuRow(Icons.Rounded.Event, "Событие в календаре") {
            val st = LocalDate.now().plusDays(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            event = CalEvent(title = title, startMillis = st, endMillis = st + 3600_000, note = String.format(Locale.US, "geo:%.6f,%.6f", t.lat, t.lon)); more = false
        }
        MenuRow(Icons.Rounded.ContentCopy, "Копировать координаты") { copy(ctx, MapPrefs.coords(ctx, t.lat, t.lon)); more = false }
        MenuRow(Icons.Rounded.Map, "Открыть в другом приложении") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", t.lat, t.lon, t.lat, t.lon, Uri.encode(title))))) }; more = false
        }
    }
    event?.let { e -> EventEditor(e, onDismiss = { event = null }) }
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
        AppTextField(note, { note = it }, "Заметка", singleLine = false)
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
                AppTextField(note, { note = it }, "Заметка", singleLine = false)
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
internal fun TrackCard(t: Track, folders: List<MapFolder>, onClose: () -> Unit, onFit: () -> Unit) {
    val ctx = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    val pts = remember(t.id, t.points) { Osm.decode(t.points) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Pal.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (t.color != 0) Color(t.color) else Color(0xFF6A55A8)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " · " + folderPath(t.folderId, folders),
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Pal.Muted) }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("Путь", MapPrefs.dist(ctx, t.distanceM))
            if (t.endedAt > t.startedAt) Stat("Время", fmtDur(t.endedAt - t.startedAt))
            if (t.endedAt > t.startedAt + 60_000) Stat("Средняя", MapPrefs.speed(ctx, (t.distanceM / ((t.endedAt - t.startedAt) / 3600_000.0) / 1000).toFloat()).let { "${it.first} ${it.second}" })
            Stat("Точек", pts.size.toString())
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Rounded.ZoomOutMap, "Весь трек", Pal.Sky, Pal.SkyInk, onFit)
            ActionChip(Icons.Rounded.Edit, "Изменить") { edit = true }
            ActionChip(Icons.Rounded.IosShare, "GPX") { shareFile(ctx, t.name + ".gpx", GeoFiles.gpxOf(emptyList(), listOf(t)), "application/gpx+xml") }
            pts.lastOrNull()?.let { e -> ActionChip(Icons.Rounded.Navigation, "Waze к концу") { waze(ctx, e.lat, e.lon) } }
        }
    }
    if (edit) TrackEditor(t, folders) { edit = false; onClose() }
}

@Composable
private fun Stat(label: String, v: String) {
    Column { Text(v, style = MaterialTheme.typography.titleSmall); Text(label, fontSize = 11.sp, color = Pal.Muted) }
}

// ---------------- слои ----------------

@Composable
internal fun LayersPanel(onDismiss: () -> Unit, onChanged: () -> Unit, onSettings: () -> Unit, on3D: (() -> Unit)? = null) {
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
    var houseNum by remember { mutableStateOf(p.getBoolean("housenumbers", true)) }
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
            SwitchRow("Рельеф мира", "цветная карта мира на мелком масштабе", relief) { relief = it; set("relief", it) }
            SwitchRow("3D-дома", "объёмные здания вблизи; на слабой графике может подтормаживать", b3d) { b3d = it; set("buildings3d", it) }
            SwitchRow("Номера домов", null, houseNum) { houseNum = it; set("housenumbers", it) }
            if (ApiKeys.tomtom(ctx).isNotBlank()) SwitchRow("Пробки (TomTom)", "загруженность дорог, ДТП и перекрытия; нужен интернет", traffic) { traffic = it; set("traffic", it) }
            else Text("Пробки — после ввода ключа TomTom (Настройки → Ключи и сервисы).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(12.dp, 4.dp))
            if (on3D != null) MenuRow(Icons.Rounded.Landscape, "Открыть 3D-рельеф", sub = "горы объёмом, наклон и вращение; для скачанных стран") { on3D() }
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

@Composable
internal fun RoutePanel(to: MapPrefs.Spot?, me: GeoPt?, places: List<Place>, onDismiss: () -> Unit, onTarget: (MapPrefs.Spot) -> Unit, onLine: (List<GeoPt>, String) -> Unit, onClear: () -> Unit, onRoute: (Routing.Route?, String, Boolean) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dest by remember { mutableStateOf(to) }
    var from by remember { mutableStateOf<MapPrefs.Spot?>(null) }       // null — моё место
    var mode by remember { mutableStateOf(MapPrefs.navMode(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(MapPrefs.home(ctx)) }
    var work by remember { mutableStateOf(MapPrefs.work(ctx)) }
    var favs by remember { mutableStateOf(MapPrefs.favs(ctx)) }
    var pickFor by remember { mutableStateOf<String?>(null) }           // "from" | "to"
    var newFav by remember { mutableStateOf<String?>(null) }
    var editFav by remember { mutableStateOf<MapPrefs.Spot?>(null) }
    val recents = remember { MapPrefs.recents(ctx) }
    fun pick(s: MapPrefs.Spot) { dest = s; onTarget(s) }
    fun start(): GeoPt? = from?.let { GeoPt(it.lat, it.lon, 0) } ?: me
    fun straight(d: MapPrefs.Spot) {
        val f = start() ?: run { Toast.makeText(ctx, "Нет моего места — включи геолокацию или выбери «Откуда»", Toast.LENGTH_SHORT).show(); return }
        onLine(listOf(f, GeoPt(d.lat, d.lon, 0)), "По прямой · " + MapPrefs.dist(ctx, Osm.distance(f, GeoPt(d.lat, d.lon, 0))))
        onDismiss()
    }
    BottomPanel(onDismiss) {
        Text("Маршрут", style = MaterialTheme.typography.titleLarge)
        AppCard(Modifier.fillMaxWidth(), padding = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Pal.SkyInk)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Откуда", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    Text(from?.name ?: (if (me != null) "Моё место" else "Моё место (ищу спутники…)"), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
                if (from != null) IconButton({ from = null }) { Icon(Icons.Rounded.MyLocation, "Моё место", tint = Pal.Muted) }
                IconButton({ pickFor = "from" }) { Icon(Icons.Rounded.AddLocationAlt, "Выбрать на карте", tint = Pal.SkyInk) }
            }
            HorizontalDivider(color = Pal.Line, modifier = Modifier.padding(start = 28.dp))
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE5484D))); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Куда", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    Text(dest?.name ?: "Выбери ниже или на карте", style = MaterialTheme.typography.bodyLarge, maxLines = 1, color = if (dest == null) Pal.Muted else Pal.Ink)
                }
                IconButton({ pickFor = "to" }) { Icon(Icons.Rounded.AddLocationAlt, "Выбрать на карте", tint = Color(0xFFE5484D)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("car", "Авто", Icons.Rounded.DirectionsCar), Triple("bike", "Вело", Icons.AutoMirrored.Rounded.DirectionsBike),
                Triple("foot", "Пешком", Icons.AutoMirrored.Rounded.DirectionsWalk), Triple("line", "Прямая", Icons.Rounded.Straighten)).forEach { (k, n, ic) ->
                val sel = mode == k
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Sky else Pal.Card).border(if (sel) 2.dp else 0.dp, Pal.SkyInk, RoundedCornerShape(16.dp))
                    .clickable { mode = k; MapPrefs.setStr(ctx, "nav_mode", k) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, null, tint = if (sel) Pal.SkyInk else Pal.Ink); Text(n, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (busy) "Строю…" else "Построить", {
                val d = dest ?: return@PillButton
                if (mode == "line") { straight(d); return@PillButton }
                val f = start() ?: run { Toast.makeText(ctx, "Нет моего места — включи геолокацию или выбери «Откуда»", Toast.LENGTH_SHORT).show(); return@PillButton }
                busy = true
                scope.launch {
                    val to2 = GeoPt(d.lat, d.lon, 0)
                    runCatching { Nav.best(ctx, mode, f, to2) }
                        .onSuccess { r ->
                            onLine(r.points, MapPrefs.dist(ctx, r.meters) + " · " + fmtDur((r.seconds * 1000).toLong()) + (if (r.delay >= 60) " · пробки +" + fmtDur((r.delay * 1000).toLong()) else "") + " · " + r.source)
                            onRoute(r, d.name, from == null); onDismiss()
                        }
                        .onFailure { Toast.makeText(ctx, (it.message ?: "Маршрут не построен") + " — рисую по прямой", Toast.LENGTH_LONG).show(); straight(d) }
                    busy = false
                }
            }, Modifier.weight(1f), enabled = dest != null)
            PillButton("Waze", { dest?.let { waze(ctx, it.lat, it.lon) } }, Modifier.weight(1f), bg = Color(0xFFDDF4FB), fg = Color(0xFF1B7A99), enabled = dest != null)
        }
        if (to != null) TextButton({ onClear(); onDismiss() }) { Text("Убрать маршрут", color = Pal.RoseInk) }
        SectionLabel("Избранное")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun FavTile(ic: ImageVector, n: String, s: MapPrefs.Spot?, onSet: (MapPrefs.Spot) -> Unit, onLong: (() -> Unit)? = null) {
                Column(Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card).combinedClickableCompat(onClick = {
                    if (s != null) pick(s.copy(name = n))
                    else dest?.let { d -> onSet(MapPrefs.Spot(n, d.lat, d.lon, d.name)) } ?: Toast.makeText(ctx, "Сначала выбери «Куда», потом нажми плитку — запомню туда", Toast.LENGTH_LONG).show()
                }, onLongClick = onLong).padding(12.dp)) {
                    Icon(ic, null, tint = Pal.SkyInk); Text(n, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(s?.sub?.ifBlank { null } ?: if (s != null) "задано" else "не задано", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                }
            }
            FavTile(Icons.Rounded.Home, "Дом", home, { MapPrefs.setHome(ctx, it); home = it }, { MapPrefs.setHome(ctx, null); home = null })
            FavTile(Icons.Rounded.Work, "Работа", work, { MapPrefs.setWork(ctx, it); work = it }, { MapPrefs.setWork(ctx, null); work = null })
            favs.forEach { f -> FavTile(Icons.Rounded.Star, f.name, f, {}, { editFav = f }) }
            Column(Modifier.width(80.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable {
                if (dest == null) Toast.makeText(ctx, "Сначала выбери «Куда» (на карте или из списка)", Toast.LENGTH_LONG).show() else newFav = ""
            }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Add, "Добавить", tint = Pal.SkyInk); Text("Добавить", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        Text("Долгое нажатие на плитку — убрать.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        if (recents.isNotEmpty()) {
            SectionLabel("Недавние")
            recents.take(6).forEach { r -> MenuRow(Icons.Rounded.History, r.name, sub = r.sub.ifBlank { null }) { pick(r) } }
        }
        if (places.isNotEmpty()) {
            SectionLabel("Мои метки")
            AppTextField(filter, { filter = it }, "Найти метку")
            places.filter { filter.isBlank() || it.name.contains(filter, true) }.sortedBy { p -> me?.let { Osm.distance(it, GeoPt(p.lat, p.lon, 0)) } ?: 0.0 }.take(12).forEach { pl ->
                MenuRow(Icons.Rounded.Place, pl.name, sub = me?.let { MapPrefs.dist(ctx, Osm.distance(it, GeoPt(pl.lat, pl.lon, 0))) }) { pick(MapPrefs.Spot(pl.name, pl.lat, pl.lon, "Моя метка")) }
            }
        }
        Text(if (Nav.offlineAvailable(ctx)) "Есть офлайн-маршруты: без интернета строятся на телефоне. С интернетом на машине — TomTom с пробками." else "Офлайн-маршруты: Настройки карты → Загрузка карт → часть «Маршруты». Сейчас — онлайн (TomTom/OSRM).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    }
    pickFor?.let { which ->
        MapPointPicker(onDismiss = { pickFor = null }) { (n, la, lo) ->
            val s = MapPrefs.Spot(n, la, lo, "точка на карте")
            if (which == "from") from = s else pick(s)
            pickFor = null
        }
    }
    newFav?.let { _ ->
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFav = null }, title = { Text("Новое избранное") },
            text = { Column { Text("Место: ${dest?.name}", style = MaterialTheme.typography.bodySmall); AppTextField(name, { name = it }, "Название (Учёба, Спорт…)") } },
            confirmButton = { TextButton({ val d = dest; if (d != null && name.isNotBlank()) { favs = favs + MapPrefs.Spot(name.trim(), d.lat, d.lon, d.name); MapPrefs.setFavs(ctx, favs) }; newFav = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ newFav = null }) { Text("Отмена") } })
    }
    editFav?.let { f ->
        AlertDialog(onDismissRequest = { editFav = null }, title = { Text(f.name) }, text = { Text(f.sub) },
            confirmButton = { TextButton({ favs = favs - f; MapPrefs.setFavs(ctx, favs); editFav = null }) { Text("Убрать", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ editFav = null }) { Text("Оставить") } })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

