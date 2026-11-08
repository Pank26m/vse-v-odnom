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
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Pal.Bg)
                .clickable(interactionSource = null, indication = null) {}.dialogBars().padding(top = 8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(Pal.Line))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
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

/** Карточка объекта: OSM и Википедия, координаты, область/район/громада, кнопки «Сохранить», «Маршрут», «Waze». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceCard(t: MapTarget, me: GeoPt?, folders: List<MapFolder>, onClose: () -> Unit, onRoute: (MapPrefs.Spot) -> Unit, onSaved: (Place) -> Unit) {
    val ctx = LocalContext.current
    var info by remember(t) { mutableStateOf<PlaceInfo.Info?>(null) }
    var loading by remember(t) { mutableStateOf(false) }
    var admin by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember(t) { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Place?>(null) }
    var more by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<CalEvent?>(null) }
    var img by remember(t) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(t) {
        admin = withContext(Dispatchers.IO) { runCatching { UaAdmin.lookup(ctx, t.lat, t.lon) }.getOrDefault(emptyList()) }
        if (Net.online && !MapPrefs.offlineOnly(ctx)) {
            loading = true
            info = runCatching { PlaceInfo.load(t.name.takeIf { t.place == null && it != "Точка на карте" }, t.lat, t.lon) }.getOrNull()
            loading = false
            info?.wiki?.image?.let { u -> img = runCatching { loadImage(ctx, u, 700) }.getOrNull() }
        }
    }
    val title = if (t.name == "Точка на карте") info?.name?.ifBlank { null } ?: info?.address?.ifBlank { null } ?: t.name else t.name
    val folderName = t.place?.folderId?.let { id -> folders.firstOrNull { it.id == id }?.name }
    val dist = me?.let { Osm.distance(it, GeoPt(t.lat, t.lon, 0)) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Pal.Card)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, top = 12.dp, end = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                val sub = listOfNotNull(t.kind.ifBlank { null }, folderName?.let { "📁 $it" }, dist?.let { MapPrefs.dist(ctx, it) }).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                val addr = info?.address?.takeIf { it.isNotBlank() && it != title } ?: t.place?.address?.ifBlank { null }
                if (addr != null) Text(addr, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (loading) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp)
            IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, "Подробнее", tint = Pal.Muted) }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Pal.Muted) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.place == null) ActionChip(Icons.Rounded.BookmarkAdd, "Сохранить метку", Pal.Ink, Color.White) {
                edit = Place(name = title, lat = t.lat, lon = t.lon, note = "", address = info?.address.orEmpty(), folderId = MapPrefs.p(ctx).getLong("last_folder", 0).takeIf { it > 0 })
            } else ActionChip(Icons.Rounded.Edit, "Изменить", Pal.Ink, Color.White) { edit = t.place }
            ActionChip(Icons.Rounded.Directions, "Маршрут", Pal.Sky, Pal.SkyInk) { onRoute(MapPrefs.Spot(title, t.lat, t.lon, t.kind)) }
            ActionChip(Icons.Rounded.Navigation, "Waze", Color(0xFFDDF4FB), Color(0xFF1B7A99)) { waze(ctx, t.lat, t.lon) }
            ActionChip(Icons.Rounded.Share, "Поделиться") { share(ctx, title, t.lat, t.lon) }
            ActionChip(Icons.Rounded.MoreHoriz, "Ещё") { more = true }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { copy(ctx, MapPrefs.coords(ctx, t.lat, t.lon)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MyLocation, null, tint = Pal.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(MapPrefs.coords(ctx, t.lat, t.lon), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ContentCopy, "Копировать", tint = Pal.Muted, modifier = Modifier.size(16.dp))
            }
            val chain = (info?.admin?.takeIf { it.isNotEmpty() } ?: admin)
            if (chain.isNotEmpty()) Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.AccountTree, null, tint = Pal.Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(chain.joinToString(" › "), style = MaterialTheme.typography.bodySmall)
            }
            t.place?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            info?.tags?.let { tags ->
                PlaceInfo.TAG_NAMES.forEach { (k, label) ->
                    tags[k]?.let { v ->
                        val click: (() -> Unit)? = when {
                            k.contains("phone") -> ({ runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + v.substringBefore(';').trim()))) }; Unit })
                            k.contains("website") || k.startsWith("contact:") -> ({ web(ctx, if (v.startsWith("http")) v else "https://$v"); Unit })
                            else -> null
                        }
                        Row(Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable { click() } else Modifier)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.width(120.dp))
                            Text(v.replace(";", "\n"), style = MaterialTheme.typography.bodySmall, color = if (click != null) Pal.SkyInk else Pal.Ink)
                        }
                    }
                }
            }
            GoogleBlock(t, title)
            info?.wiki?.let { w ->
                HorizontalDivider(color = Pal.Line)
                img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                Text(w.title, style = MaterialTheme.typography.titleSmall)
                Text(w.extract, style = MaterialTheme.typography.bodySmall)
                Text("Википедия ›", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk, modifier = Modifier.clickable { web(ctx, w.url) })
            }
            if (info == null && !loading) Text(if (!Net.online) "Без интернета: координаты и деление — из карт на телефоне." else if (MapPrefs.offlineOnly(ctx)) "Онлайн-сведения выключены в настройках." else "", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            info?.osmUrl?.takeIf { it.isNotBlank() }?.let { u -> Text("Открыть в OpenStreetMap ›", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.clickable { web(ctx, u) }) }
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
    edit?.let { p -> PlaceEditor(p, folders, onDismiss = { edit = null }) { saved -> edit = null; if (saved != null) onSaved(saved) else onClose() } }
    event?.let { e -> EventEditor(e, onDismiss = { event = null }) }
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
            if (ApiKeys.tomtom(ctx).isNotBlank()) SwitchRow("Пробки (TomTom)", "загруженность дорог, ДТП и перекрытия; нужен интернет", traffic) { traffic = it; set("traffic", it) }
            else Text("Пробки — после ввода ключа TomTom (Настройки → Ключи и сервисы).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(12.dp, 4.dp))
            Text("3D-рельеф — в третьем этапе. Слои работают для скачанных стран.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(12.dp, 4.dp, 12.dp, 10.dp))
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
        PillButton("Скачать карты стран", onSettings, Modifier.fillMaxWidth(), bg = Pal.Lilac, fg = Pal.LilacInk)
        SectionLabel("Онлайн-карты")
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            sources.forEach { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    onlineId = s.id; useOnline = true; p.edit().putString("online_id", s.id).putBoolean("use_online", true).apply(); onChanged()
                }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(useOnline && onlineId == s.id, null)
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 6.dp))
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
internal fun RoutePanel(to: MapPrefs.Spot?, me: GeoPt?, places: List<Place>, onDismiss: () -> Unit, onTarget: (MapPrefs.Spot) -> Unit, onLine: (List<GeoPt>, String) -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dest by remember { mutableStateOf(to) }
    var mode by remember { mutableStateOf(MapPrefs.navMode(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(MapPrefs.home(ctx)) }
    var work by remember { mutableStateOf(MapPrefs.work(ctx)) }
    val recents = remember { MapPrefs.recents(ctx) }
    fun pick(s: MapPrefs.Spot) { dest = s; onTarget(s) }
    fun straight(d: MapPrefs.Spot) {
        val from = me ?: run { Toast.makeText(ctx, "Нет моего места — включи геолокацию", Toast.LENGTH_SHORT).show(); return }
        onLine(listOf(from, GeoPt(d.lat, d.lon, 0)), "По прямой · " + MapPrefs.dist(ctx, Osm.distance(from, GeoPt(d.lat, d.lon, 0))))
        onDismiss()
    }
    BottomPanel(onDismiss) {
        Text("Маршрут", style = MaterialTheme.typography.titleLarge)
        AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            Text("Откуда: " + (if (me != null) "моё место" else "моё место (ищу спутники…)"), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Text("Куда: " + (dest?.name ?: "выбери ниже"), style = MaterialTheme.typography.titleSmall)
            dest?.sub?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("car" to "Авто", "bike" to "Вело", "foot" to "Пешком", "line" to "Прямая").forEach { (k, n) -> Chip(n, mode == k, { mode = k; MapPrefs.setStr(ctx, "nav_mode", k) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (busy) "Строю…" else "Построить", {
                val d = dest ?: return@PillButton
                if (mode == "line") { straight(d); return@PillButton }
                val from = me ?: run { Toast.makeText(ctx, "Нет моего места — включи геолокацию", Toast.LENGTH_SHORT).show(); return@PillButton }
                if (!Net.online || !MapPrefs.bool(ctx, "online_nav", true) || MapPrefs.offlineOnly(ctx)) {
                    Toast.makeText(ctx, "Офлайн-маршруты по дорогам будут во втором этапе. Пока — прямая линия или Waze.", Toast.LENGTH_LONG).show(); straight(d); return@PillButton
                }
                busy = true
                scope.launch {
                    val useTT = ApiKeys.tomtom(ctx).isNotBlank() && MapPrefs.bool(ctx, "tomtom_route", true)
                    runCatching { if (useTT) runCatching { TomTom.route(ctx, mode, from, GeoPt(d.lat, d.lon, 0)) }.getOrElse { Routing.route(mode, from, GeoPt(d.lat, d.lon, 0)) } else Routing.route(mode, from, GeoPt(d.lat, d.lon, 0)) }
                        .onSuccess { r -> onLine(r.points, MapPrefs.dist(ctx, r.meters) + " · " + fmtDur((r.seconds * 1000).toLong()) + (if (r.delay >= 60) " · пробки +" + fmtDur((r.delay * 1000).toLong()) else "") + (if (useTT && mode == "car") " · с пробками" else " · по дорогам")); onDismiss() }
                        .onFailure { Toast.makeText(ctx, "Сервер маршрутов не ответил — рисую по прямой", Toast.LENGTH_SHORT).show(); straight(d) }
                    busy = false
                }
            }, Modifier.weight(1f), enabled = dest != null)
            PillButton("Waze", { dest?.let { waze(ctx, it.lat, it.lon) } }, Modifier.weight(1f), bg = Color(0xFFDDF4FB), fg = Color(0xFF1B7A99), enabled = dest != null)
        }
        if (to != null) TextButton({ onClear(); onDismiss() }) { Text("Убрать маршрут", color = Pal.RoseInk) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Дом", Icons.Rounded.Home, home), Triple("Работа", Icons.Rounded.Work, work)).forEach { (n, ic, s) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable {
                    if (s != null) pick(s.copy(name = n))
                    else dest?.let { d -> val ns = MapPrefs.Spot(n, d.lat, d.lon, d.name); if (n == "Дом") { MapPrefs.setHome(ctx, ns); home = ns } else { MapPrefs.setWork(ctx, ns); work = ns } }
                        ?: Toast.makeText(ctx, "Открой место на карте → «Ещё» → «Сделать «$n»»", Toast.LENGTH_LONG).show()
                }.padding(12.dp)) {
                    Icon(ic, null, tint = Pal.SkyInk)
                    Text(n, style = MaterialTheme.typography.titleSmall)
                    Text(s?.sub?.ifBlank { null } ?: if (s != null) "задано" else if (dest != null) "нажми — задать сюда" else "не задано", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                }
            }
        }
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
        Text("Маршрут по дорогам сейчас строится онлайн (OSRM, серверы FOSSGIS). Без интернета — по прямой; офлайн-навигация — второй этап.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    }
}

/** Сведения Google о месте: грузятся по кнопке (или сами, если включено) — каждый запрос в счёт дневного лимита. */
@Composable
internal fun GoogleBlock(t: MapTarget, title: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (ApiKeys.google(ctx).isBlank() || !Net.online || MapPrefs.offlineOnly(ctx) || title == "Точка на карте") return
    var d by remember(t) { mutableStateOf<GooglePlaces.Details?>(null) }
    var busy by remember(t) { mutableStateOf(false) }
    var err by remember(t) { mutableStateOf<String?>(null) }
    var photoIdx by remember(t) { mutableIntStateOf(-1) }
    var photo by remember(t) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showAllHours by remember(t) { mutableStateOf(false) }
    fun load() {
        if (busy) return; busy = true; err = null
        scope.launch {
            runCatching { val gid = t.props["google_id"]; if (gid != null) GooglePlaces.details(ctx, gid) else GooglePlaces.near(ctx, title, t.lat, t.lon) }
                .onSuccess { d = it; if (it == null) err = "Google не нашёл это место рядом" }.onFailure { err = it.message }
            busy = false
        }
    }
    LaunchedEffect(t) { if (MapPrefs.bool(ctx, "google_auto", false) || t.props.containsKey("google_id")) load() }
    HorizontalDivider(color = Pal.Line)
    val g = d
    if (g == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Google", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton({ load() }) { Text("Рейтинг, отзывы, часы") }
        }
        err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        g.rating?.let { Text("★ %.1f".format(it), style = MaterialTheme.typography.titleMedium, color = Color(0xFFE09A1B)); Spacer(Modifier.width(6.dp)) }
        Text(listOfNotNull(g.count?.let { "$it отзывов" }, g.price, g.type).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.weight(1f))
        g.openNow?.let { Text(if (it) "Открыто" else "Закрыто", style = MaterialTheme.typography.labelLarge, color = if (it) Pal.MintInk else Pal.RoseInk) }
    }
    if (g.hours.isNotEmpty()) {
        val todayIdx = (java.time.LocalDate.now().dayOfWeek.value - 1).coerceIn(0, g.hours.size - 1)
        Text(if (showAllHours) g.hours.joinToString("\n") else g.hours[todayIdx] + "  ▾", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { showAllHours = !showAllHours })
    }
    g.phone?.let { ph -> Text("📞 $ph", style = MaterialTheme.typography.bodyMedium, color = Pal.SkyInk, modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) } }) }
    g.site?.let { s -> Text("🌐 " + s.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/'), style = MaterialTheme.typography.bodyMedium, color = Pal.SkyInk, maxLines = 1, modifier = Modifier.clickable { web(ctx, s) }) }
    if (g.photos.isNotEmpty()) {
        photo?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
        TextButton({
            val next = (photoIdx + 1) % g.photos.size; photoIdx = next
            scope.launch { photo = GooglePlaces.photo(ctx, g.photos[next]) ?: photo.also { Toast.makeText(ctx, "Фото не загрузилось (лимит?)", Toast.LENGTH_SHORT).show() } }
        }) { Text(if (photoIdx < 0) "Показать фото (${g.photos.size})" else "Следующее фото ${photoIdx + 1}/${g.photos.size}") }
    }
    g.reviews.take(5).forEach { r ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).padding(10.dp)) {
            Text("★".repeat(r.rating.coerceIn(0, 5)) + "  " + r.author + " · " + r.when_, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 6)
        }
    }
    g.mapsUrl?.let { u -> Text("Открыть в Google Картах ›", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.clickable { web(ctx, u) }) }
}
