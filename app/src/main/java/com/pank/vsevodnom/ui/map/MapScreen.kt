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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private fun hasLoc(ctx: Context) = TrackService.canStart(ctx)

/** Слои поверх любой карты: места, выбранная точка, показанный трек, записываемый трек. */
private fun setupOverlays(style: Style) {
    fun src(id: String) { if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()))) }
    listOf("track", "rec", "places", "pin").forEach(::src)
    if (style.getLayer("track-l") == null) style.addLayer(LineLayer("track-l", "track").withProperties(PropertyFactory.lineColor("#6A55A8"), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
    if (style.getLayer("rec-l") == null) style.addLayer(LineLayer("rec-l", "rec").withProperties(PropertyFactory.lineColor("#D8456A"), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap("round")))
    if (style.getLayer("places-l") == null) style.addLayer(CircleLayer("places-l", "places").withProperties(PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#E0607E"), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
    if (style.getLayer("pin-l") == null) style.addLayer(CircleLayer("pin-l", "pin").withProperties(PropertyFactory.circleRadius(10f), PropertyFactory.circleColor("#4F86C6"), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
}

private fun Style.set(id: String, f: List<Feature>) { getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(f)) }

@Composable
fun MapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).map() }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("map", Context.MODE_PRIVATE) }
    val places by dao.places().collectAsState(initial = emptyList())
    val tracks by dao.tracks().collectAsState(initial = emptyList())
    var styleKey by remember { mutableStateOf(prefs.getString("style", "liberty") ?: "liberty") }
    var picked by remember { mutableStateOf<GeoHit?>(null) }
    var pickedLoading by remember { mutableStateOf(false) }
    var pickedPlace by remember { mutableStateOf<Place?>(null) }
    var shownTrack by remember { mutableStateOf<Track?>(null) }
    var sheet by remember { mutableIntStateOf(0) }   // 1 места, 2 треки, 3 слои, 4 офлайн
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoHit>>(emptyList()) }
    var editPlace by remember { mutableStateOf<Place?>(null) }
    var eventFor by remember { mutableStateOf<CalEvent?>(null) }
    val rec by TrackService.recording.collectAsState()
    val recPts by TrackService.points.collectAsState()
    val recDist by TrackService.distance.collectAsState()

    val view = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var speed by remember { mutableStateOf<Float?>(null) }
    var alt by remember { mutableStateOf<Double?>(null) }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val m = map; val st = style
        if (hasLoc(ctx) && m != null && st != null) MapKit.enableLocation(ctx, m, st, true)
    }

    fun showPin(lat: Double, lon: Double) { style?.set("pin", listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat)))) }
    fun clearPin() { style?.set("pin", emptyList()) }
    fun whatsHere(lat: Double, lon: Double) {
        pickedPlace = null; results = emptyList()
        showPin(lat, lon)
        picked = GeoHit("Точка на карте", "%.5f, %.5f".format(lat, lon), lat, lon)
        pickedLoading = true
        scope.launch { Osm.reverse(lat, lon)?.let { if (picked?.lat == lat) picked = it }; pickedLoading = false }
    }
    fun center(): LatLng = map?.cameraPosition?.target ?: LatLng(50.4501, 30.5234)
    fun fly(lat: Double, lon: Double, z: Double = 16.5) { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), 600) }

    // карта и стиль
    LaunchedEffect(Unit) {
        view.getMapAsync { m ->
            map = m
            m.cameraPosition = camera(prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble(), prefs.getFloat("zoom", 12f).toDouble())
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassMargins(0, 260, 40, 0)
            m.addOnMapClickListener { ll ->
                val f = m.queryRenderedFeatures(m.projection.toScreenLocation(ll), "places-l").firstOrNull()
                val id = f?.getNumberProperty("id")?.toLong()
                if (id != null) { picked = null; clearPin(); pickedPlace = places.firstOrNull { it.id == id } ?: pickedPlace }
                else if (picked != null || pickedPlace != null || results.isNotEmpty()) { picked = null; pickedPlace = null; results = emptyList(); clearPin() }
                else whatsHere(ll.latitude, ll.longitude)
                true
            }
            m.addOnMapLongClickListener { ll -> whatsHere(ll.latitude, ll.longitude); true }
            m.addOnCameraIdleListener {
                val c = m.cameraPosition
                c.target?.let { t -> prefs.edit().putFloat("zoom", c.zoom.toFloat()).putFloat("lat", t.latitude.toFloat()).putFloat("lon", t.longitude.toFloat()).apply() }
            }
        }
    }
    LaunchedEffect(map, styleKey) {
        val m = map ?: return@LaunchedEffect
        prefs.edit().putString("style", styleKey).apply()
        val s = MapKit.STYLES.firstOrNull { it.key == styleKey } ?: MapKit.STYLES[0]
        style = null
        m.setStyle(MapKit.styleBuilder(s)) { st ->
            setupOverlays(st); style = st
            if (hasLoc(ctx)) MapKit.enableLocation(ctx, m, st, false)
            else locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    // скорость и высота — как в Guru: плашка слева сверху
    LaunchedEffect(style) {
        while (true) {
            runCatching { map?.locationComponent?.lastKnownLocation }.getOrNull()?.let { l -> speed = if (l.hasSpeed()) l.speed * 3.6f else null; alt = if (l.hasAltitude()) l.altitude else null }
            kotlinx.coroutines.delay(1500)
        }
    }
    LaunchedEffect(style, places) {
        style?.set("places", places.map { pl -> Feature.fromGeometry(Point.fromLngLat(pl.lon, pl.lat)).also { it.addNumberProperty("id", pl.id); it.addStringProperty("name", pl.name) } })
    }
    LaunchedEffect(style, shownTrack) {
        val st = style ?: return@LaunchedEffect
        val pts = shownTrack?.let { Osm.decode(it.points) }.orEmpty()
        st.set("track", if (pts.size >= 2) listOf(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))) else emptyList())
        if (pts.size >= 2) runCatching {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120), 700)
        }
    }
    LaunchedEffect(style, recPts) {
        style?.set("rec", if (recPts.size >= 2) listOf(Feature.fromGeometry(LineString.fromLngLats(recPts.map { Point.fromLngLat(it.lon, it.lat) }))) else emptyList())
    }

    fun eventAt(title: String, note: String) {
        val st = LocalDate.now().plusDays(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        eventFor = CalEvent(title = title, startMillis = st, endMillis = st + 3600_000, note = note)
    }
    fun route(lat: Double, lon: Double, name: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"))) }
            .onFailure { Toast.makeText(ctx, "Нет приложения для маршрута", Toast.LENGTH_SHORT).show() }
    }
    @Composable fun MapBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, d: String, f: () -> Unit) =
        Box(Modifier.size(52.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = f), contentAlignment = Alignment.Center) {
            Icon(icon, d, tint = Pal.Ink)
        }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        AndroidView({ view }, Modifier.fillMaxSize())

        // верх: назад + поиск
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
                Row(Modifier.weight(1f).height(52.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Pal.Muted)
                    TextField(query, { query = it }, placeholder = { Text("Поиск места или адреса") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val q = query.trim(); if (q.isEmpty()) return@KeyboardActions
                            scope.launch {
                                val c = center()
                                results = runCatching { Osm.search(q, c.latitude, c.longitude) }.getOrElse { emptyList() }
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
                            showPin(r.lat, r.lon); fly(r.lat, r.lon, 17.0)
                        }.padding(12.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(r.address, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            } else if (speed != null || alt != null) Row(Modifier.shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(speed?.let { "%.0f".format(it) } ?: "-.--", style = MaterialTheme.typography.titleLarge); Text(" км/ч", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(24.dp).background(Pal.Line))
                Text("↑ " + (alt?.let { "%.0f".format(it) } ?: "—"), style = MaterialTheme.typography.titleLarge); Text(" м", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }

        // справа: слои, места, треки, офлайн
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 140.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapBtn(Icons.Rounded.Layers, "Слои") { sheet = 3 }
            MapBtn(Icons.Rounded.Bookmarks, "Мои места") { sheet = 1 }
            MapBtn(Icons.Rounded.Route, "Треки") { sheet = 2 }
            MapBtn(Icons.Rounded.CloudDownload, "Офлайн-карты") { sheet = 4 }
        }
        // слева: масштаб и «где я»
        Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapBtn(Icons.Rounded.Add, "Приблизить") { map?.animateCamera(CameraUpdateFactory.zoomIn(), 250) }
            MapBtn(Icons.Rounded.Remove, "Отдалить") { map?.animateCamera(CameraUpdateFactory.zoomOut(), 250) }
            MapBtn(Icons.Rounded.MyLocation, "Где я") {
                val m = map; val st = style
                if (!hasLoc(ctx)) locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else if (m != null && st != null) {
                    MapKit.enableLocation(ctx, m, st, true)
                    runCatching { m.locationComponent.lastKnownLocation }.getOrNull()?.let { fly(it.latitude, it.longitude, 16.0) } ?: Toast.makeText(ctx, "Ищу спутники…", Toast.LENGTH_SHORT).show()
                }
            }
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
                    Row(Modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Rose).clickable { TrackService.stop(ctx) }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Stop, null, tint = Pal.RoseInk)
                        Spacer(Modifier.width(8.dp))
                        Text("Стоп · ${fmtKm(recDist)} · ${fmtDur(System.currentTimeMillis() - TrackService.startedAt)}", style = MaterialTheme.typography.labelLarge, color = Pal.RoseInk)
                    }
                } else {
                    Column(Modifier.size(56.dp).shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable {
                        if (!hasLoc(ctx)) locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else { TrackService.start(ctx); val m = map; val st = style; if (m != null && st != null) MapKit.enableLocation(ctx, m, st, true) }
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Rounded.FiberManualRecord, null, tint = Pal.RoseInk, modifier = Modifier.size(16.dp))
                        Text("REC", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    if (sheet == 3) AlertDialog(onDismissRequest = { sheet = 0 }, confirmButton = { TextButton({ sheet = 0 }) { Text("Готово") } }, title = { Text("Вид карты") },
        text = {
            Column {
                MapKit.STYLES.forEach { l ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { styleKey = l.key; sheet = 0 }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(styleKey == l.key, { styleKey = l.key; sheet = 0 }); Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Векторные карты OpenFreeMap на данных © OpenStreetMap. Офлайн работают первые три вида.", style = MaterialTheme.typography.bodySmall)
            }
        })
    if (sheet == 4) OfflineSheet(MapKit.STYLES.firstOrNull { it.key == styleKey && it.url != null } ?: MapKit.STYLES[0], { map?.projection?.visibleRegion?.latLngBounds }, { map?.cameraPosition?.zoom ?: 12.0 }) { sheet = 0 }
    if (sheet == 1) PlacesSheet(places, onDismiss = { sheet = 0 }, onPick = { pl ->
        sheet = 0; picked = null; clearPin(); pickedPlace = pl; fly(pl.lat, pl.lon, 17.0)
    }, onEdit = { editPlace = it })
    if (sheet == 2) TracksSheet(tracks, onDismiss = { sheet = 0 }, onPick = { sheet = 0; shownTrack = it })
    editPlace?.let { pl -> PlaceEditor(pl, places.map { it.folder }.filter { it.isNotBlank() }.distinct(), onDismiss = { editPlace = null }) { saved ->
        if (saved == null) pickedPlace = null else { pickedPlace = null; picked = null; clearPin(); prefs.edit().putString("last_folder", saved.folder).apply() }
    } }
    eventFor?.let { e -> EventEditor(e, onDismiss = { eventFor = null }) }
}

/** Офлайн-карты: скачать видимую область, список скачанного с размером, удаление. */
@Composable
private fun OfflineSheet(style: MapKit.MapStyle, bounds: () -> LatLngBounds?, zoom: () -> Double, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var regions by remember { mutableStateOf<List<MapKit.Region>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("Киев") }
    var maxZ by remember { mutableFloatStateOf(15f) }
    val sizes = remember { mutableStateMapOf<Long, String>() }
    LaunchedEffect(tick) {
        MapKit.list(ctx) { l -> regions = l; l.forEach { r -> MapKit.status(r.r) { st -> sizes[r.r.id] = (if (st.isComplete) "" else "${st.completedResourceCount}/${st.requiredResourceCount} · ") + MapKit.human(st.completedResourceSize) } } }
    }
    val b = remember { bounds() }
    val tiles = b?.let { MapKit.tileCount(it, 0, maxZ.toInt()) } ?: 0
    FullDialog("Офлайн-карты", onDismiss) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Скачать то, что сейчас видно на экране", style = MaterialTheme.typography.titleSmall)
                    Text("Вид: ${style.name}. Приблизь или отдали карту до нужной области перед открытием этого окна.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    Spacer(Modifier.height(8.dp))
                    AppTextField(name, { name = it }, "Название области")
                    Text("Детализация до уровня ${maxZ.toInt()} (${if (maxZ >= 16) "все дома и заведения" else if (maxZ >= 14) "улицы и здания" else "города и дороги"})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Slider(maxZ, { maxZ = it }, valueRange = 10f..16f, steps = 5)
                    Text("≈ ${tiles} тайлов, примерно ${MapKit.human(tiles * 25_000L)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PillButton(if (progress == null) "Скачать" else "Качаю…", {
                        val bb = b ?: return@PillButton
                        if (progress != null) return@PillButton
                        progress = "0%"
                        MapKit.download(ctx, style.url!!, bb, maxZ.toDouble(), name.ifBlank { "Область" }, { st ->
                            val pct = if (st.requiredResourceCount > 0) st.completedResourceCount * 100 / st.requiredResourceCount else 0
                            progress = if (st.isComplete) null else "$pct% · ${MapKit.human(st.completedResourceSize)}"
                            if (st.isComplete) tick++
                        }) { e -> progress = null; Toast.makeText(ctx, "Ошибка: $e", Toast.LENGTH_LONG).show() }
                        tick++
                    }, Modifier.fillMaxWidth())
                    progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item { SectionLabel(if (regions.isEmpty()) "Скачанных областей нет" else "Скачано") }
            items(regions, key = { it.r.id }) { r ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Map, null, tint = Pal.SkyInk)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(sizes[r.r.id] ?: "…", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        IconButton({ MapKit.start(r.r, { tick++ }) { } }) { Icon(Icons.Rounded.Refresh, "Докачать / обновить") }
                        IconButton({ MapKit.delete(r.r) { tick++ } }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.RoseInk) }
                    }
                }
            }
            item { Text("Скачанные области работают без интернета: карта, поиск по сохранённым местам и треки. Поиск адресов (Nominatim) — только онлайн.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        }
    }
}

@Composable
private fun FullDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
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
