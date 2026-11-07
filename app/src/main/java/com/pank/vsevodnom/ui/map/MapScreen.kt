package com.pank.vsevodnom.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.calendar.EventEditor
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class Layer(val name: String, val src: ITileSource)

private val LAYERS by lazy {
    listOf(
        Layer("Обычная", TileSourceFactory.MAPNIK),
        Layer("Рельеф", TileSourceFactory.OpenTopo),
        Layer("Велосипедная", XYTileSource("CyclOSM", 0, 20, 256, ".png",
            arrayOf("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"), "© OpenStreetMap, CyclOSM")),
        Layer("Транспорт", XYTileSource("OPNVKarte", 0, 18, 256, ".png", arrayOf("https://tileserver.memomaps.de/tilegen/"), "© OpenStreetMap, memomaps")),
        Layer("Спутник", object : OnlineTileSourceBase("EsriSat", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"), "© Esri") {
            override fun getTileURLString(i: Long) = baseUrl + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
        }),
    )
}

private class MapHolder {
    val markers = mutableListOf<Marker>()
    var pin: Marker? = null
    var trackLine: Polyline? = null
    var recLine: Polyline? = null
    var me: MyLocationNewOverlay? = null
}

private fun hasLoc(ctx: Context) = TrackService.canStart(ctx)

@Composable
fun MapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).map() }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("map", Context.MODE_PRIVATE) }
    val places by dao.places().collectAsState(initial = emptyList())
    val tracks by dao.tracks().collectAsState(initial = emptyList())
    var layer by remember { mutableIntStateOf(prefs.getInt("layer", 0).coerceIn(0, LAYERS.size - 1)) }
    var picked by remember { mutableStateOf<GeoHit?>(null) }
    var pickedLoading by remember { mutableStateOf(false) }
    var pickedPlace by remember { mutableStateOf<Place?>(null) }
    var shownTrack by remember { mutableStateOf<Track?>(null) }
    var sheet by remember { mutableIntStateOf(0) }   // 1 места, 2 треки, 3 слои
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoHit>>(emptyList()) }
    var editPlace by remember { mutableStateOf<Place?>(null) }
    var eventFor by remember { mutableStateOf<CalEvent?>(null) }
    val rec by TrackService.recording.collectAsState()
    val recPts by TrackService.points.collectAsState()
    val recDist by TrackService.distance.collectAsState()
    val h = remember { MapHolder() }

    val map = remember {
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = ctx.packageName
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(prefs.getFloat("zoom", 12f).toDouble())
            controller.setCenter(GeoPoint(prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble()))
        }
    }

    fun showPin(p: GeoPoint) {
        h.pin?.let { map.overlays.remove(it) }
        h.pin = Marker(map).apply { position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setInfoWindow(null) }
        map.overlays.add(h.pin); map.invalidate()
    }
    fun clearPin() { h.pin?.let { map.overlays.remove(it) }; h.pin = null; map.invalidate() }
    fun whatsHere(p: GeoPoint) {
        pickedPlace = null; results = emptyList()
        showPin(p)
        picked = GeoHit("Точка на карте", "%.5f, %.5f".format(p.latitude, p.longitude), p.latitude, p.longitude)
        pickedLoading = true
        scope.launch { Osm.reverse(p.latitude, p.longitude)?.let { if (picked?.lat == p.latitude) picked = it }; pickedLoading = false }
    }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLoc(ctx)) h.me?.apply { enableMyLocation(); enableFollowLocation() }
    }

    // разовая настройка слоёв карты
    LaunchedEffect(Unit) {
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (picked != null || pickedPlace != null || results.isNotEmpty()) { picked = null; pickedPlace = null; results = emptyList(); clearPin() } else whatsHere(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean { whatsHere(p); return true }
        }))
        h.me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map).also { map.overlays.add(it) }
        if (hasLoc(ctx)) h.me?.enableMyLocation()
        else locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(layer) { map.setTileSource(LAYERS[layer].src); prefs.edit().putInt("layer", layer).apply() }
    LaunchedEffect(places) {
        h.markers.forEach { map.overlays.remove(it) }; h.markers.clear()
        places.forEach { pl ->
            h.markers += Marker(map).apply {
                position = GeoPoint(pl.lat, pl.lon); title = pl.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> picked = null; clearPin(); pickedPlace = pl; true }
            }
        }
        map.overlays.addAll(h.markers); map.invalidate()
    }
    LaunchedEffect(shownTrack) {
        h.trackLine?.let { map.overlays.remove(it) }; h.trackLine = null
        shownTrack?.let { t ->
            val pts = Osm.decode(t.points).map { GeoPoint(it.lat, it.lon) }
            if (pts.size >= 2) {
                h.trackLine = Polyline(map).apply { setPoints(pts); outlinePaint.color = 0xFF6A55A8.toInt(); outlinePaint.strokeWidth = 12f }
                map.overlays.add(1, h.trackLine)
                map.post { runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120) } }
            }
        }
        map.invalidate()
    }
    LaunchedEffect(recPts) {
        h.recLine?.let { map.overlays.remove(it) }; h.recLine = null
        if (recPts.size >= 2) {
            h.recLine = Polyline(map).apply { setPoints(recPts.map { GeoPoint(it.lat, it.lon) }); outlinePaint.color = 0xFFA8456A.toInt(); outlinePaint.strokeWidth = 12f }
            map.overlays.add(1, h.recLine)
        }
        map.invalidate()
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    map.onPause()
                    prefs.edit().putFloat("zoom", map.zoomLevelDouble.toFloat()).putFloat("lat", map.mapCenter.latitude.toFloat()).putFloat("lon", map.mapCenter.longitude.toFloat()).apply()
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            prefs.edit().putFloat("zoom", map.zoomLevelDouble.toFloat()).putFloat("lat", map.mapCenter.latitude.toFloat()).putFloat("lon", map.mapCenter.longitude.toFloat()).apply()
            map.onDetach()
        }
    }

    fun eventAt(title: String, note: String) {
        val st = LocalDate.now().plusDays(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        eventFor = CalEvent(title = title, startMillis = st, endMillis = st + 3600_000, note = note)
    }
    fun route(lat: Double, lon: Double, name: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"))) }
            .onFailure { Toast.makeText(ctx, "Нет приложения для маршрута", Toast.LENGTH_SHORT).show() }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        AndroidView({ map }, Modifier.fillMaxSize())

        // верх: назад + поиск
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.shadow(4.dp, RoundedCornerShape(50))) { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack) }
                Row(Modifier.weight(1f).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Pal.Muted)
                    TextField(query, { query = it }, placeholder = { Text("Поиск места или адреса") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val q = query.trim(); if (q.isEmpty()) return@KeyboardActions
                            scope.launch {
                                results = runCatching { Osm.search(q, map.mapCenter.latitude, map.mapCenter.longitude) }.getOrElse { emptyList() }
                                if (results.isEmpty()) Toast.makeText(ctx, "Ничего не нашлось", Toast.LENGTH_SHORT).show()
                            }
                        }),
                        modifier = Modifier.weight(1f))
                    if (query.isNotEmpty()) IconButton({ query = ""; results = emptyList() }) { Icon(Icons.Rounded.Close, "Очистить") }
                }
            }
            if (results.isNotEmpty()) AppCard(Modifier.fillMaxWidth().heightIn(max = 320.dp), padding = 4.dp) {
                LazyColumn {
                    items(results) { r ->
                        Column(Modifier.fillMaxWidth().clickable {
                            results = emptyList(); pickedPlace = null; picked = r
                            val p = GeoPoint(r.lat, r.lon); showPin(p); map.controller.animateTo(p, 17.0, 600L)
                        }.padding(12.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(r.address, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }

        // справа: кнопки
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(Icons.Rounded.Layers, "Слои") { sheet = 3 },
                Triple(Icons.Rounded.Bookmarks, "Мои места") { sheet = 1 },
                Triple(Icons.Rounded.Route, "Треки") { sheet = 2 },
                Triple(Icons.Rounded.MyLocation, "Где я") {
                    if (!hasLoc(ctx)) locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else { h.me?.enableMyLocation(); h.me?.myLocation?.let { map.controller.animateTo(it, 16.0, 600L) } ?: Toast.makeText(ctx, "Ищу спутники…", Toast.LENGTH_SHORT).show() }
                },
            ).forEach { (ic, d, f) -> Box(Modifier.shadow(4.dp, RoundedCornerShape(50))) { RoundIconButton(ic, d, f) } }
        }

        // низ: карточка точки/места, запись трека
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            picked?.let { p ->
                AppCard(Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(p.address, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        if (pickedLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Событие", { eventAt(p.name, p.address + "\ngeo:%.6f,%.6f".format(java.util.Locale.US, p.lat, p.lon)) }, Modifier.weight(1f))
                        PillButton("Сохранить", { editPlace = Place(name = p.name, lat = p.lat, lon = p.lon, folder = prefs.getString("last_folder", "") ?: "", note = p.address) }, Modifier.weight(1f))
                        RoundIconButton(Icons.Rounded.Directions, "Маршрут", { route(p.lat, p.lon, p.name) }, bg = Pal.Lilac)
                    }
                }
            }
            pickedPlace?.let { pl ->
                AppCard(Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp))) {
                    Text(pl.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(pl.folder.ifBlank { "Без папки" }, pl.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Событие", { eventAt(pl.name, pl.note + "\ngeo:%.6f,%.6f".format(java.util.Locale.US, pl.lat, pl.lon)) }, Modifier.weight(1f))
                        PillButton("Изменить", { editPlace = pl }, Modifier.weight(1f))
                        RoundIconButton(Icons.Rounded.Directions, "Маршрут", { route(pl.lat, pl.lon, pl.name) }, bg = Pal.Lilac)
                    }
                }
            }
            shownTrack?.takeIf { picked == null && pickedPlace == null }?.let { t ->
                AppCard(Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp)), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text("${fmtKm(t.distanceM)} · ${fmtDur(t.endedAt - t.startedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ shownTrack = null }) { Icon(Icons.Rounded.Close, "Скрыть") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (rec) {
                    Row(Modifier.shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Pal.Rose).clickable { TrackService.stop(ctx) }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Stop, null, tint = Pal.RoseInk)
                        Spacer(Modifier.width(8.dp))
                        Text("Стоп · ${fmtKm(recDist)} · ${fmtDur(System.currentTimeMillis() - TrackService.startedAt)}", style = MaterialTheme.typography.labelLarge, color = Pal.RoseInk)
                    }
                } else {
                    Row(Modifier.shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Pal.Card).clickable {
                        if (!hasLoc(ctx)) locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else { TrackService.start(ctx); h.me?.enableFollowLocation() }
                    }.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FiberManualRecord, null, tint = Pal.RoseInk)
                        Spacer(Modifier.width(8.dp))
                        Text("Записать трек", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (sheet == 3) AlertDialog(onDismissRequest = { sheet = 0 }, confirmButton = { TextButton({ sheet = 0 }) { Text("Готово") } }, title = { Text("Слой карты") },
        text = {
            Column {
                LAYERS.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { layer = i; sheet = 0 }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(layer == i, { layer = i; sheet = 0 }); Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Карты: OpenStreetMap и партнёры. Сохранённые места и треки рисуются поверх любого слоя.", style = MaterialTheme.typography.bodySmall)
            }
        })
    if (sheet == 1) PlacesSheet(places, onDismiss = { sheet = 0 }, onPick = { pl ->
        sheet = 0; picked = null; clearPin(); pickedPlace = pl; map.controller.animateTo(GeoPoint(pl.lat, pl.lon), 17.0, 600L)
    }, onEdit = { editPlace = it })
    if (sheet == 2) TracksSheet(tracks, onDismiss = { sheet = 0 }, onPick = { sheet = 0; shownTrack = it })
    editPlace?.let { pl -> PlaceEditor(pl, places.map { it.folder }.filter { it.isNotBlank() }.distinct(), onDismiss = { editPlace = null }) { saved ->
        if (saved == null) pickedPlace = null else { pickedPlace = null; picked = null; clearPin(); prefs.edit().putString("last_folder", saved.folder).apply() }
    } }
    eventFor?.let { e -> EventEditor(e, onDismiss = { eventFor = null }) }
}

@Composable
private fun FullDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onDismiss)
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            content()
        }
    }
}

@Composable
private fun PlacesSheet(places: List<Place>, onDismiss: () -> Unit, onPick: (Place) -> Unit, onEdit: (Place) -> Unit) {
    var folder by remember { mutableStateOf<String?>(null) }
    val folders = places.map { it.folder.ifBlank { "Без папки" } }.distinct().sorted()
    FullDialog("Мои места", onDismiss) {
        if (places.isEmpty()) EmptyHint("Пока пусто. Нажми на карту в нужном месте → «Сохранить».", Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Все", folder == null, { folder = null })
            folders.forEach { f -> Chip(f, folder == f, { folder = f }) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            places.filter { folder == null || it.folder.ifBlank { "Без папки" } == folder }.groupBy { it.folder.ifBlank { "Без папки" } }.forEach { (f, list) ->
                item { SectionLabel("$f · ${list.size}") }
                items(list, key = { it.id }) { pl ->
                    AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { onPick(pl) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Place, null, tint = Pal.LilacInk)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleSmall)
                                if (pl.note.isNotBlank()) Text(pl.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            IconButton({ onEdit(pl) }) { Icon(Icons.Rounded.Edit, "Изменить", tint = Pal.Muted) }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun TracksSheet(tracks: List<Track>, onDismiss: () -> Unit, onPick: (Track) -> Unit) {
    val ctx = LocalContext.current
    var rename by remember { mutableStateOf<Track?>(null) }
    FullDialog("Треки", onDismiss) {
        val total = tracks.sumOf { it.distanceM }
        if (tracks.isEmpty()) EmptyHint("Треков пока нет. На карте — «Записать трек». Каждый трек сам попадает в календарь.", Modifier.padding(16.dp))
        else Text("Всего ${tracks.size} · ${fmtKm(total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracks, key = { it.id }) { t ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { onPick(t) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Route, null, tint = Pal.RoseInk)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text(Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) +
                                " · ${fmtKm(t.distanceM)} · ${fmtDur(t.endedAt - t.startedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ rename = t }) { Icon(Icons.Rounded.Edit, "Изменить", tint = Pal.Muted) }
                    }
                }
            }
        }
    }
    rename?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        var folder by remember(t.id) { mutableStateOf(t.folder) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Трек") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AppTextField(name, { name = it }, "Название"); AppTextField(folder, { folder = it }, "Папка") } },
            confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().upsertTrack(t.copy(name = name.trim().ifBlank { t.name }, folder = folder.trim())) }; rename = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deleteTrack(t) }; rename = null }) { Text("Удалить", color = Pal.RoseInk) } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(p: Place, folders: List<String>, onDismiss: () -> Unit, onDone: (Place?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(p.name) }
    var folder by remember { mutableStateOf(p.folder) }
    var note by remember { mutableStateOf(p.note) }
    val defaults = listOf("Дом", "Еда", "Учёба", "Хочу сходить", "Магазины", "Спорт")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (p.id == 0L) "Новое место" else "Место") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(name, { name = it }, "Название")
                AppTextField(folder, { folder = it }, "Папка")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (folders + defaults).distinct().forEach { f -> Chip(f, folder == f, { folder = f }) }
                }
                AppTextField(note, { note = it }, "Заметка", singleLine = false)
            }
        },
        confirmButton = {
            TextButton({
                if (name.isBlank()) return@TextButton
                val saved = p.copy(name = name.trim(), folder = folder.trim(), note = note.trim())
                AppScope.io.launch { AppDb.get(ctx).map().upsertPlace(saved) }
                onDone(saved); onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            if (p.id != 0L) TextButton({ AppScope.io.launch { AppDb.get(ctx).map().deletePlace(p) }; onDone(null); onDismiss() }) { Text("Удалить", color = Pal.RoseInk) }
            else TextButton(onDismiss) { Text("Отмена") }
        })
}
