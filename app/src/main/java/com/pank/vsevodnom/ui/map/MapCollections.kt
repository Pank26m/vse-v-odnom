package com.pank.vsevodnom.ui.map

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Коллекции: папки в папках, метки и треки в папках или без папки; выбор (долгое нажатие) → переместить / скрыть / удалить;
 * «глаз» у папки — показать/скрыть на карте, «на карте» — показать всю папку; импорт GPX/KML/KMZ (экспорт из Guru Maps).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionsScreen(
    places: List<Place>, tracks: List<Track>, folders: List<MapFolder>, showAll: Boolean, onShowAll: (Boolean) -> Unit,
    onDismiss: () -> Unit, onPlace: (Place) -> Unit, onTrack: (Track) -> Unit, onShowFolder: (List<GeoPt>) -> Unit,
) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).map() }
    var cur by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }   // «f12», «p3», «t5»
    var moving by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf<MapFolder?>(null) }
    var editPlace by remember { mutableStateOf<Place?>(null) }
    var editTrack by remember { mutableStateOf<Track?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val byId = folders.associateBy { it.id }

    fun descendants(id: Long): Set<Long> { val out = HashSet<Long>(); fun go(x: Long) { folders.filter { it.parentId == x }.forEach { if (out.add(it.id)) go(it.id) } }; go(id); return out }
    fun pointsOf(folderIds: Set<Long>?): List<GeoPt> {
        val ps = places.filter { folderIds == null && it.folderId == null || folderIds != null && it.folderId in folderIds }.map { GeoPt(it.lat, it.lon, 0) }
        val ts = tracks.filter { folderIds == null && it.folderId == null || folderIds != null && it.folderId in folderIds }.flatMap { t -> Osm.decode(t.points).let { p -> if (p.size > 50) p.filterIndexed { i, _ -> i % (p.size / 50) == 0 } else p } }
        return ps + ts
    }
    fun count(id: Long): Int { val ids = descendants(id) + id; return places.count { it.folderId in ids } + tracks.count { it.folderId in ids } }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } }.getOrNull() ?: "Импорт"
        importing = "Читаю $name…"
        val target = cur
        AppScope.io.launch {
            val msg = runCatching { val root = GeoFiles.read(ctx, uri, name); val n = GeoFiles.save(ctx, root, target); "Импортировано: $n (папка «${root.name}»)" }.getOrElse { "Не получилось прочитать: ${it.message}" }
            withContext(Dispatchers.Main) { importing = null; Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }

    val title = cur?.let { byId[it]?.name } ?: "Коллекции"
    FullScreen(title, onDismiss = { if (selected.isNotEmpty()) selected = emptySet() else if (cur != null) cur = byId[cur]?.parentId else onDismiss() }, actions = {
        var byDate by remember { mutableStateOf(MapPrefs.bool(ctx, "coll_by_date", false)) }
        IconButton({ byDate = !byDate; MapPrefs.setBool(ctx, "coll_by_date", byDate) }) {
            Icon(if (byDate) Icons.Rounded.Schedule else Icons.Rounded.SortByAlpha, if (byDate) "Сначала новые" else "По алфавиту", tint = Pal.Muted) }
        IconButton({ onShowAll(!showAll) }) { Icon(if (showAll) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (showAll) "Скрыть всё на карте" else "Показать всё на карте", tint = if (showAll) Pal.SkyInk else Pal.Muted) }
        IconButton({ newFolder = true }) { Icon(Icons.Rounded.CreateNewFolder, "Новая папка") }
        IconButton({ import.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, "Импорт GPX/KML") }
    }) {
        if (cur != null) Text(folderPath(cur, folders), style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(horizontal = 20.dp))
        importing?.let { Row(Modifier.padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  $it", style = MaterialTheme.typography.bodySmall) } }
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Выбрано: ${selected.size}", style = MaterialTheme.typography.labelLarge)
            Chip("Переместить", false, { moving = true })
            Chip("Показать", false, { val s = selected; AppScope.io.launch { show(dao, s, true) }; selected = emptySet() })
            Chip("Скрыть", false, { val s = selected; AppScope.io.launch { show(dao, s, false) }; selected = emptySet() })
            Chip("Удалить", false, {
                val s = selected
                AppScope.io.launch {
                    val fids = s.filter { it[0] == 'f' }.map { it.drop(1).toLong() }.toSet()
                    val all = fids + fids.flatMap { descendants(it) }
                    dao.deletePlaces(s.filter { it[0] == 'p' }.map { it.drop(1).toLong() } + places.filter { it.folderId in all }.map { it.id })
                    dao.deleteTracks(s.filter { it[0] == 't' }.map { it.drop(1).toLong() } + tracks.filter { it.folderId in all }.map { it.id })
                    dao.deleteFolders(all.toList())
                }
                selected = emptySet()
            })
        }
        if (cur == null) AppTextField(query, { query = it }, "Найти в коллекциях", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val q = query.trim().lowercase()
        val subFolders = if (q.isNotEmpty()) folders.filter { it.name.lowercase().contains(q) } else folders.filter { it.parentId == cur }.sortedWith(compareBy({ it.sort }, { it.name.lowercase() }))
        val byDate = MapPrefs.bool(ctx, "coll_by_date", false)
        val ps = if (q.isNotEmpty()) places.filter { it.name.lowercase().contains(q) } else places.filter { it.folderId == cur }.let { l -> if (byDate) l.sortedByDescending { it.createdAt } else l.sortedBy { it.name.lowercase() } }
        val ts = if (q.isNotEmpty()) tracks.filter { it.name.lowercase().contains(q) } else tracks.filter { it.folderId == cur }
        // быстрый выбор для чистки: всё здесь, только метки/треки, по цвету, по значку
        if (ps.isNotEmpty() || ts.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var selMenu by remember { mutableStateOf(false) }
            Box {
                Chip("Выбрать ▾", false, { selMenu = true })
                DropdownMenu(selMenu, { selMenu = false }) {
                    fun pick(keys: List<String>) { selected = selected + keys; selMenu = false }
                    DropdownMenuItem({ Text("Всё здесь (${ps.size + ts.size})") }, { pick(ps.map { "p${it.id}" } + ts.map { "t${it.id}" }) }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) })
                    if (ps.isNotEmpty()) DropdownMenuItem({ Text("Все метки (${ps.size})") }, { pick(ps.map { "p${it.id}" }) }, leadingIcon = { Icon(Icons.Rounded.Place, null) })
                    if (ts.isNotEmpty()) DropdownMenuItem({ Text("Все треки (${ts.size})") }, { pick(ts.map { "t${it.id}" }) }, leadingIcon = { Icon(Icons.Rounded.Timeline, null) })
                    val colors = (ps.map { it.color } + ts.map { it.color }).distinct()
                    if (colors.size > 1) {
                        HorizontalDivider(); Text("  По цвету", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        colors.forEach { c ->
                            val n = ps.count { it.color == c } + ts.count { it.color == c }
                            DropdownMenuItem({ Text("$n шт.") }, { pick(ps.filter { it.color == c }.map { "p${it.id}" } + ts.filter { it.color == c }.map { "t${it.id}" }) },
                                leadingIcon = { Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(colorOr(c, PLACE_COLOR)))) })
                        }
                    }
                    val icons = ps.map { it.icon.removePrefix("t:") }.distinct()
                    if (icons.size > 1) {
                        HorizontalDivider(); Text("  По значку", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        icons.forEach { ic ->
                            DropdownMenuItem({ Text("${ps.count { it.icon.removePrefix("t:") == ic }} шт." + if (ic.isBlank()) " · без значка" else "") }, { pick(ps.filter { it.icon.removePrefix("t:") == ic }.map { "p${it.id}" }) },
                                leadingIcon = { PlaceDot(Color(colorOr(0, PLACE_COLOR)), ic, 22.dp) })
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) Chip("Снять выбор", false, { selected = emptySet() })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (subFolders.isEmpty() && ps.isEmpty() && ts.isEmpty()) item {
                EmptyHint(if (cur == null) "Пока пусто. Нажми на место на карте → «Сохранить метку», запиши трек (REC) или импортируй GPX/KML из Guru Maps (значок файла сверху)." else "Папка пуста", Modifier.padding(top = 20.dp))
            }
            if (cur != null && (ps.isNotEmpty() || ts.isNotEmpty() || subFolders.isNotEmpty())) item {
                PillButton("Показать папку на карте", { onShowFolder(pointsOf(descendants(cur!!) + cur!!)) }, Modifier.fillMaxWidth(), bg = Pal.Sky, fg = Pal.SkyInk)
            }
            items(subFolders, key = { "f${it.id}" }) { f ->
                val k = "f${f.id}"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (k in selected) Pal.Lilac else Pal.Card)
                    .combinedClickable(onClick = { if (selected.isNotEmpty()) selected = selected.toggle(k) else { cur = f.id; query = "" } }, onLongClick = { selected = selected.toggle(k) })
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = if (f.color != 0) Color(f.color) else Pal.ButterInk)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall)
                        Text("${count(f.id)} объектов", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                    IconButton({ AppScope.io.launch { dao.upsertFolder(f.copy(visible = !f.visible)) } }) { Icon(if (f.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Видимость", tint = if (f.visible) Pal.SkyInk else Pal.Muted) }
                    IconButton({ onShowFolder(pointsOf(descendants(f.id) + f.id)) }) { Icon(Icons.Rounded.Map, "На карте", tint = Pal.Muted) }
                    IconButton({ editFolder = f }) { Icon(Icons.Rounded.MoreVert, "Изменить", tint = Pal.Muted) }
                }
            }
            items(ps, key = { "p${it.id}" }) { pl ->
                val k = "p${pl.id}"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (k in selected) Pal.Lilac else Pal.Card)
                    .combinedClickable(onClick = { if (selected.isNotEmpty()) selected = selected.toggle(k) else onPlace(pl) }, onLongClick = { selected = selected.toggle(k) })
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = if (pl.color != 0) Color(pl.color) else Color(0xFFE0607E))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(pl.name, style = MaterialTheme.typography.titleSmall)
                        val sub = if (q.isNotEmpty()) folderPath(pl.folderId, folders) else pl.note.ifBlank { pl.address }
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                    }
                    IconButton({ AppScope.io.launch { dao.upsertPlace(pl.copy(visible = !pl.visible)) } }) { Icon(if (pl.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Видимость", tint = if (pl.visible) Pal.SkyInk else Pal.Muted) }
                    IconButton({ editPlace = pl }) { Icon(Icons.Rounded.Edit, "Изменить", tint = Pal.Muted) }
                }
            }
            items(ts, key = { "t${it.id}" }) { t ->
                val k = "t${t.id}"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (k in selected) Pal.Lilac else Pal.Card)
                    .combinedClickable(onClick = { if (selected.isNotEmpty()) selected = selected.toggle(k) else onTrack(t) }, onLongClick = { selected = selected.toggle(k) })
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Route, null, tint = if (t.color != 0) Color(t.color) else Color(0xFF6A55A8))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        Text(MapPrefs.dist(ctx, t.distanceM) + (if (t.endedAt > t.startedAt) " · " + fmtDur(t.endedAt - t.startedAt) else "") + if (q.isNotEmpty()) " · " + folderPath(t.folderId, folders) else "",
                            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                    IconButton({ AppScope.io.launch { dao.upsertTrack(t.copy(visible = !t.visible)) } }) { Icon(if (t.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Видимость", tint = if (t.visible) Pal.SkyInk else Pal.Muted) }
                    IconButton({ editTrack = t }) { Icon(Icons.Rounded.Edit, "Изменить", tint = Pal.Muted) }
                }
            }
            if (cur == null && (places.isNotEmpty() || tracks.isNotEmpty())) item {
                Text("Всего: ${places.size} меток, ${tracks.size} треков, ${folders.size} папок. Долгое нажатие — выбрать несколько и переместить.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }
    }

    if (moving) {
        val fids = selected.filter { it[0] == 'f' }.map { it.drop(1).toLong() }.toSet()
        FolderPicker(folders, exclude = fids + fids.flatMap { descendants(it) }, onDismiss = { moving = false }) { to ->
            val s = selected
            AppScope.io.launch {
                dao.movePlaces(s.filter { it[0] == 'p' }.map { it.drop(1).toLong() }, to)
                dao.moveTracks(s.filter { it[0] == 't' }.map { it.drop(1).toLong() }, to)
                dao.moveFolders(s.filter { it[0] == 'f' }.map { it.drop(1).toLong() }, to)
            }
            selected = emptySet(); moving = false
        }
    }
    if (newFolder) FolderEditor(MapFolder(parentId = cur, name = ""), onDismiss = { newFolder = false })
    editFolder?.let { f -> FolderEditor(f, onDismiss = { editFolder = null }, onExport = {
        AppScope.io.launch {
            val kml = GeoFiles.kmlOf(f.name, dao.foldersNow(), dao.placesNow(), dao.tracksNow(), f.id)
            withContext(Dispatchers.Main) { shareFile(ctx, f.name + ".kml", kml, "application/vnd.google-earth.kml+xml") }
        }
    }) }
    editPlace?.let { p -> PlaceEditor(p, folders, onDismiss = { editPlace = null }) { editPlace = null } }
    editTrack?.let { t -> TrackEditor(t, folders) { editTrack = null } }
}

private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k

private suspend fun show(dao: MapDao, s: Set<String>, v: Boolean) {
    dao.showPlaces(s.filter { it[0] == 'p' }.map { it.drop(1).toLong() }, v)
    dao.showTracks(s.filter { it[0] == 't' }.map { it.drop(1).toLong() }, v)
    dao.showFolders(s.filter { it[0] == 'f' }.map { it.drop(1).toLong() }, v)
}

@Composable
private fun FolderEditor(f: MapFolder, onDismiss: () -> Unit, onExport: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(f.name) }
    var color by remember { mutableIntStateOf(f.color) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (f.id == 0L) "Новая папка" else "Папка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Название")
                ColorRow(color, { color = it }, Color(0xFF8A6B12))
                if (onExport != null) TextButton(onExport) { Text("Экспорт папки в KML") }
            }
        },
        confirmButton = { TextButton({
            if (name.isBlank()) return@TextButton
            AppScope.io.launch { AppDb.get(ctx).map().upsertFolder(f.copy(name = name.trim(), color = color)) }; onDismiss()
        }) { Text("Сохранить") } },
        dismissButton = {
            if (f.id != 0L) TextButton({
                // папку удаляем, её содержимое и подпапки переезжают на уровень выше
                AppScope.io.launch {
                    val dao = AppDb.get(ctx).map()
                    dao.movePlaces(dao.placesNow().filter { it.folderId == f.id }.map { it.id }, f.parentId)
                    dao.moveTracks(dao.tracksNow().filter { it.folderId == f.id }.map { it.id }, f.parentId)
                    dao.moveFolders(dao.foldersNow().filter { it.parentId == f.id }.map { it.id }, f.parentId)
                    dao.deleteFolder(f)
                }
                onDismiss()
            }) { Text("Удалить папку", color = Pal.RoseInk) } else TextButton(onDismiss) { Text("Отмена") }
        })
}
