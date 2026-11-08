package com.pank.vsevodnom.ui.map

import android.Manifest
import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal fun hasLoc(ctx: Context) = TrackService.canStart(ctx)

/** Что показано в карточке снизу. */
data class MapTarget(val name: String, val kind: String, val lat: Double, val lon: Double, val place: Place? = null, val props: Map<String, String> = emptyMap())

internal fun colorHex(c: Int, def: String) = if (c == 0) def else String.format("#%06X", c and 0xFFFFFF)
internal const val PLACE_COLOR = "#E0607E"
internal const val TRACK_COLOR = "#6A55A8"

/** Слои пользователя поверх карты: треки, метки, булавка, запись, маршрут. */
private fun setupOverlays(style: Style) {
    fun src(id: String) { if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()))) }
    listOf("u-tracks", "u-sel", "u-rec", "u-route", "u-places", "u-pin").forEach(::src)
    style.addLayer(LineLayer("u-route-l", "u-route").withProperties(PF.lineColor("#3E6FA8"), PF.lineWidth(5f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.85f)))
    style.addLayer(LineLayer("u-tracks-l", "u-tracks").withProperties(PF.lineColor(Expression.toColor(Expression.get("color"))), PF.lineWidth(4f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.9f)))
    style.addLayer(LineLayer("u-sel-l", "u-sel").withProperties(PF.lineColor("#FFFFFF"), PF.lineWidth(9f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.9f)))
    style.addLayer(LineLayer("u-sel-l2", "u-sel").withProperties(PF.lineColor(Expression.toColor(Expression.get("color"))), PF.lineWidth(5.5f), PF.lineCap("round"), PF.lineJoin("round")))
    style.addLayer(LineLayer("u-rec-l", "u-rec").withProperties(PF.lineColor("#D8456A"), PF.lineWidth(5f), PF.lineCap("round"), PF.lineJoin("round")))
    style.addLayer(CircleLayer("u-places-c", "u-places").withProperties(PF.circleRadius(7.5f), PF.circleColor(Expression.toColor(Expression.get("color"))), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2.5f)))
    style.addLayer(SymbolLayer("u-places-t", "u-places").withProperties(PF.textField(Expression.get("name")), PF.textFont(arrayOf("Noto Sans Bold")), PF.textSize(11.5f),
        PF.textColor("#2B2A33"), PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(1.5f), PF.textAnchor("top"), PF.textOffset(arrayOf(0f, 0.9f)), PF.textOptional(true)).withProperties().apply { minZoom = 11f })
    style.addLayer(SymbolLayer("u-pin-c", "u-pin").withProperties(PF.iconImage("pin"), PF.iconAnchor("bottom"), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true)))
}

internal fun Style.setFeatures(id: String, f: List<Feature>) { getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(f)) }

internal fun line(pts: List<GeoPt>): LineString = LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })

/** Видимые папки: сама видима и все родители видимы. */
internal fun visibleFolders(folders: List<MapFolder>): Set<Long> {
    val byId = folders.associateBy { it.id }
    fun ok(f: MapFolder, depth: Int = 0): Boolean = f.visible && (f.parentId == null || depth > 30 || byId[f.parentId]?.let { ok(it, depth + 1) } ?: true)
    return folders.filter { ok(it) }.map { it.id }.toSet()
}

enum class MapSheet { NONE, SEARCH, COLLECTIONS, LAYERS, ROUTE, SETTINGS }

@Composable
fun MapScreen(@Suppress("UNUSED_PARAMETER") onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val dao = remember { AppDb.get(ctx).map() }
    val scope = rememberCoroutineScope()
    val prefs = remember { MapPrefs.p(ctx) }
    val places by dao.places().collectAsState(initial = emptyList())
    val tracks by dao.tracks().collectAsState(initial = emptyList())
    val folders by dao.folders().collectAsState(initial = emptyList())
    var styleTick by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(prefs.getBoolean("show_collections", true)) }
    var sheet by remember { mutableStateOf(MapSheet.NONE) }
    var settingsStart by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<MapTarget?>(null) }
    var selTrack by remember { mutableStateOf<Track?>(null) }
    var routeTo by remember { mutableStateOf<MapPrefs.Spot?>(null) }
    var routeLine by remember { mutableStateOf<List<GeoPt>>(emptyList()) }
    var routeInfo by remember { mutableStateOf("") }
    var lastRoute by remember { mutableStateOf<Triple<Routing.Route, String, Boolean>?>(null) }   // маршрут, куда, от моего места
    val nav = Nav.session
    var renameTrack by remember { mutableStateOf<Track?>(null) }
    val rec by TrackService.recording.collectAsState()
    val recPts by TrackService.points.collectAsState()
    val recDist by TrackService.distance.collectAsState()
    val lastSaved by TrackService.lastSaved.collectAsState()

    val view = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var speed by remember { mutableStateOf<Float?>(null) }
    var alt by remember { mutableStateOf<Double?>(null) }
    var myLoc by remember { mutableStateOf<GeoPt?>(null) }
    var locMode by remember { mutableIntStateOf(0) }       // 0 — свободно, 1 — за мной, 2 — по компасу
    var bearing by remember { mutableFloatStateOf(0f) }
    var zoomNow by remember { mutableFloatStateOf(12f) }
    var scaleBar by remember { mutableStateOf(60.dp to "") }
    var trackListener by remember { mutableStateOf(false) }
    var rerouting by remember { mutableStateOf(false) }
    var show3d by remember { mutableStateOf(false) }
    var rerouteAt by remember { mutableStateOf<GeoPt?>(null) }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val m = map; val st = style
        if (hasLoc(ctx) && m != null && st != null) MapKit.enableLocation(ctx, m, st, false)
    }
    fun askLoc() = locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    fun fly(lat: Double, lon: Double, z: Double? = null) {
        val m = map ?: return
        if (locMode != 0) { runCatching { m.locationComponent.cameraMode = CameraMode.NONE }; locMode = 0 }
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z ?: maxOf(m.cameraPosition.zoom, 15.5)), 650)
    }
    fun fit(pts: List<GeoPt>) {
        val m = map ?: return
        if (pts.size == 1) { fly(pts[0].lat, pts[0].lon); return }
        if (pts.size < 2) return
        runCatching {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (90 * density).toInt(), (190 * density).toInt(), (90 * density).toInt(), (200 * density).toInt()), 700)
        }
    }
    fun pin(lat: Double?, lon: Double?) { style?.setFeatures("u-pin", if (lat == null || lon == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat)))) }
    fun open(t: MapTarget) { selTrack = null; target = t; pin(if (t.place == null) t.lat else null, t.lon) }
    fun closeAll() { target = null; selTrack = null; pin(null, null) }

    // ---------- карта ----------
    LaunchedEffect(Unit) {
        OnlineMaps.applyCacheLimit(ctx)
        launch { OfflineMaps.loadCatalog(ctx); OfflineMaps.resume(ctx); OfflineMaps.autoWorld(ctx) }
        view.getMapAsync { m ->
            map = m
            m.cameraPosition = camera(prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble(), prefs.getFloat("zoom", 11f).toDouble())
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionMargins((8 * density).toInt(), 0, 0, (4 * density).toInt())
            m.setMinZoomPreference(1.0)
            fun scale() {
                val c = m.cameraPosition; val lat = c.target?.latitude ?: 50.0
                bearing = c.bearing.toFloat(); zoomNow = c.zoom.toFloat()
                val mpp = m.projection.getMetersPerPixelAtLatitude(lat)
                val maxM = mpp * 84 * density
                val p = 10.0.pow(floor(log10(maxM)))
                val nice = listOf(5.0, 2.0, 1.0).map { it * p }.first { it <= maxM }
                scaleBar = (nice / mpp / density).dp to MapPrefs.dist(ctx, nice)
            }
            m.addOnCameraMoveListener { scale() }
            m.addOnCameraIdleListener {
                scale()
                val c = m.cameraPosition
                c.target?.let { t -> prefs.edit().putFloat("zoom", c.zoom.toFloat()).putFloat("lat", t.latitude.toFloat()).putFloat("lon", t.longitude.toFloat()).apply() }
            }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val r = 18 * density
                val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
                m.queryRenderedFeatures(box, "u-places-c", "u-places-t").firstOrNull()?.getNumberProperty("id")?.toLong()?.let { id ->
                    AppScope.io.launch {
                        val pl = AppDb.get(ctx).map().placesNow().firstOrNull { it.id == id } ?: return@launch
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl)) }
                    }
                    return@addOnMapClickListener true
                }
                m.queryRenderedFeatures(box, "u-tracks-l", "u-sel-l2").firstOrNull()?.getNumberProperty("id")?.toLong()?.let { id ->
                    AppScope.io.launch {
                        val t = AppDb.get(ctx).map().track(id) ?: return@launch
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { target = null; pin(null, null); selTrack = t }
                    }
                    return@addOnMapClickListener true
                }
                if (target != null || selTrack != null) { closeAll(); return@addOnMapClickListener true }
                val f = m.queryRenderedFeatures(box).firstOrNull { f -> f.hasNonNullValueForProperty("name") }
                if (f != null) {
                    val props = f.properties()?.entrySet()?.associate { it.key to (runCatching { it.value.asString }.getOrDefault(it.value.toString())) }.orEmpty()
                    val g = f.geometry()
                    val (lat, lon) = if (g is Point) g.latitude() to g.longitude() else ll.latitude to ll.longitude
                    val nm = props["name:ru"]?.takeIf { MapStyle.opts(ctx).lang == "ru" } ?: props["name"].orEmpty()
                    open(MapTarget(nm, MapNames.omt(props["class"], props["subclass"]), lat, lon, props = props))
                }
                true
            }
            m.addOnMapLongClickListener { ll -> open(MapTarget("Точка на карте", "", ll.latitude, ll.longitude)); true }
        }
    }
    LaunchedEffect(map, styleTick, OfflineMaps.version) {
        val m = map ?: return@LaunchedEffect
        val opts = MapStyle.opts(ctx)
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.build(ctx, opts))) { st ->
            st.addImage("pin", pinBitmap(density))
            setupOverlays(st); style = st
            m.isDebugActive = prefs.getBoolean("dev_tiles", false)
            if (hasLoc(ctx)) {
                MapKit.enableLocation(ctx, m, st, false)
                if (!trackListener) runCatching {
                    trackListener = true
                    m.locationComponent.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                        override fun onCameraTrackingDismissed() { locMode = 0 }
                        override fun onCameraTrackingChanged(currentMode: Int) {
                            locMode = when (currentMode) { CameraMode.TRACKING_COMPASS, CameraMode.TRACKING_GPS -> 2; CameraMode.TRACKING -> 1; else -> 0 }
                        }
                    })
                }
                runCatching {
                    if (locMode == 1) m.locationComponent.cameraMode = CameraMode.TRACKING
                    if (locMode == 2) m.locationComponent.cameraMode = CameraMode.TRACKING_COMPASS
                }
            } else if (!prefs.getBoolean("asked_loc", false)) { prefs.edit().putBoolean("asked_loc", true).apply(); askLoc() }
        }
    }
    // скорость, высота, автомасштаб
    LaunchedEffect(style) {
        while (true) {
            runCatching { map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation }.getOrNull()?.let { l ->
                speed = if (l.hasSpeed()) l.speed * 3.6f else null
                alt = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else if (l.hasAltitude()) l.altitude else null
                myLoc = GeoPt(l.latitude, l.longitude, l.time)
                if (Nav.session != null) {
                    val needReroute = Nav.update(myLoc!!, if (l.hasAccuracy()) l.accuracy else 20f)
                    Nav.voice(speed ?: 0f)
                    Nav.session?.let { s -> style?.setFeatures("u-route", listOf(Feature.fromGeometry(line(listOf(myLoc!!) + s.route.points.drop(s.seg + 1))))) }
                    if (needReroute && !rerouting) rerouteAt = myLoc
                }
                if (locMode != 0 && MapPrefs.bool(ctx, "autozoom", true)) {
                    val s = speed ?: 0f
                    val z = when { s > 90 -> 13.5; s > 55 -> 14.5; s > 25 -> 15.5; s > 8 -> 16.3; else -> null }
                    if (z != null) runCatching { map?.locationComponent?.zoomWhileTracking(z, 1200) }
                }
            }
            delay(1000)
        }
    }
    // данные пользователя на карте
    val visF = remember(folders) { visibleFolders(folders) }
    LaunchedEffect(style, places, visF, showAll) {
        style?.setFeatures("u-places", if (!showAll) emptyList() else places.filter { it.visible && (it.folderId == null || it.folderId in visF) }.map { pl ->
            Feature.fromGeometry(Point.fromLngLat(pl.lon, pl.lat)).also { it.addNumberProperty("id", pl.id); it.addStringProperty("name", pl.name); it.addStringProperty("color", colorHex(pl.color, PLACE_COLOR)) }
        })
    }
    LaunchedEffect(style, tracks, visF, showAll) {
        val list = if (!showAll) emptyList() else tracks.filter { it.visible && (it.folderId == null || it.folderId in visF) }
        val feats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            list.mapNotNull { t -> Osm.decode(t.points).takeIf { it.size >= 2 }?.let { pts ->
                Feature.fromGeometry(line(pts)).also { it.addNumberProperty("id", t.id); it.addStringProperty("color", colorHex(t.color, TRACK_COLOR)) } } }
        }
        style?.setFeatures("u-tracks", feats)
    }
    LaunchedEffect(style, selTrack) {
        val t = selTrack; val pts = t?.let { Osm.decode(it.points) }.orEmpty()
        style?.setFeatures("u-sel", if (pts.size >= 2) listOf(Feature.fromGeometry(line(pts)).also { it.addNumberProperty("id", t!!.id); it.addStringProperty("color", colorHex(t.color, TRACK_COLOR)) }) else emptyList())
    }
    LaunchedEffect(style, recPts) { style?.setFeatures("u-rec", if (recPts.size >= 2) listOf(Feature.fromGeometry(line(recPts))) else emptyList()) }
    LaunchedEffect(style, routeLine) { style?.setFeatures("u-route", if (routeLine.size >= 2) listOf(Feature.fromGeometry(line(routeLine))) else emptyList()) }
    // маршрут «по прямой» обновляется вместе с моим местом
    LaunchedEffect(routeTo, myLoc) {
        val to = routeTo ?: run { if (routeInfo.startsWith("По прямой")) { routeLine = emptyList(); routeInfo = "" }; return@LaunchedEffect }
        val me = myLoc ?: return@LaunchedEffect
        if (routeInfo.isEmpty() || routeInfo.startsWith("По прямой")) {
            routeLine = listOf(me, GeoPt(to.lat, to.lon, 0))
            routeInfo = "По прямой · " + MapPrefs.dist(ctx, Osm.distance(me, GeoPt(to.lat, to.lon, 0)))
        }
    }
    // трек сохранён: если автосохранение выключено — спросить имя и папку
    // пришло извне: ссылка Google Карт → метка, файл .ms / GPX / KML
    LaunchedEffect(IncomingGeo.message) {
        IncomingGeo.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); IncomingGeo.message = null; styleTick++ }
    }
    LaunchedEffect(IncomingGeo.open, map) {
        val q = IncomingGeo.open ?: return@LaunchedEffect
        if (map == null) return@LaunchedEffect
        IncomingGeo.open = null
        fly(q.lat, q.lon, 16.5)
        val pl = q.placeId?.let { id -> dao.placesNow().firstOrNull { it.id == id } }
        open(MapTarget(q.name, if (pl != null) "Моя метка" else "", q.lat, q.lon, pl, q.googleId?.let { mapOf("google_id" to it) } ?: emptyMap()))
    }
    LaunchedEffect(lastSaved) {
        val id = lastSaved ?: return@LaunchedEffect
        TrackService.lastSaved.value = null
        if (!MapPrefs.bool(ctx, "autosave_track", true)) renameTrack = dao.track(id)
        else Toast.makeText(ctx, "Трек сохранён в коллекции", Toast.LENGTH_SHORT).show()
    }

    fun startNav(r: Routing.Route, name: String) {
        Nav.initVoice(ctx); Nav.muted = !MapPrefs.bool(ctx, "voice", true)
        Nav.session = Nav.Session(r, MapPrefs.navMode(ctx), name); Nav.status = null
        target = null; selTrack = null; pin(null, null)
        view.keepScreenOn = true
        runCatching {
            val lc = map!!.locationComponent
            lc.cameraMode = CameraMode.TRACKING_GPS; lc.renderMode = org.maplibre.android.location.modes.RenderMode.GPS
            lc.zoomWhileTracking(17.0, 800); lc.tiltWhileTracking(if (Nav.session?.mode == "car") 50.0 else 30.0, 800)
            locMode = 2
        }
        Nav.say("Поехали. " + Nav.distText(r.meters).replace(",", " и "))
    }
    fun stopNav() {
        Nav.session = null; Nav.status = null; view.keepScreenOn = false
        runCatching {
            val lc = map!!.locationComponent
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS; lc.cameraMode = CameraMode.NONE; locMode = 0
            map!!.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(map!!.cameraPosition).tilt(0.0).bearing(0.0).build()), 600)
        }
        routeTo = null; routeLine = emptyList(); routeInfo = ""; lastRoute = null
    }
    fun rerouteNav(from: GeoPt) {
        val s = Nav.session ?: return; val to = s.route.points.lastOrNull() ?: return
        rerouting = true
        scope.launch {
            runCatching { Nav.best(ctx, s.mode, from, to) }.onSuccess { r ->
                if (Nav.session === s) { Nav.session = Nav.Session(r, s.mode, s.destName); Nav.say("Маршрут перестроен") }
            }
            rerouting = false
        }
    }
    LaunchedEffect(rerouteAt) { rerouteAt?.let { rerouteNav(it); rerouteAt = null } }
    DisposableEffect(Unit) { onDispose { if (Nav.session == null) Nav.shutdownVoice() } }
    fun locTap() {
        val m = map ?: return; val st = style ?: return
        if (!hasLoc(ctx)) { askLoc(); return }
        MapKit.enableLocation(ctx, m, st, false)
        val lc = m.locationComponent
        if (lc.lastKnownLocation == null) Toast.makeText(ctx, "Ищу спутники…", Toast.LENGTH_SHORT).show()
        when {
            locMode == 0 -> { lc.cameraMode = CameraMode.TRACKING; lc.zoomWhileTracking(maxOf(m.cameraPosition.zoom, 15.5), 700); locMode = 1 }
            locMode == 1 -> { lc.cameraMode = CameraMode.TRACKING_COMPASS; locMode = 2 }
            else -> {
                lc.cameraMode = CameraMode.NONE; locMode = 0
                m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()), 500)
            }
        }
    }
    fun recTap() {
        if (rec) { TrackService.stop(ctx); return }
        if (!hasLoc(ctx)) { askLoc(); return }
        TrackService.start(ctx)
        val m = map; val st = style
        if (m != null && st != null) MapKit.enableLocation(ctx, m, st, false)
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        AndroidView({ view }, Modifier.fillMaxSize())

        if (nav != null) NavBanner(Modifier.align(Alignment.TopCenter))
        // ---- слева сверху: скорость | высота, поиск, коллекции ----
        if (nav == null) Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val (sv, su) = speed?.let { MapPrefs.speed(ctx, it) } ?: ("—" to MapPrefs.speed(ctx, 0f).second)
                val (av, au) = alt?.let { MapPrefs.alt(ctx, it) } ?: ("—" to MapPrefs.alt(ctx, 0.0).second)
                Metric(sv, su)
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(26.dp).background(Pal.Line))
                Metric(av, au)
            }
            MapBtn(Icons.Rounded.Search, "Поиск") { sheet = MapSheet.SEARCH }
            MapBtn(Icons.Rounded.Bookmarks, "Коллекции") { sheet = MapSheet.COLLECTIONS }
        }
        // ---- справа сверху: слои, маршрут, запись ----
        if (nav == null) Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
            MapBtn(Icons.Rounded.Layers, "Слои") { sheet = MapSheet.LAYERS }
            MapBtn(Icons.Rounded.Directions, "Маршрут", active = routeTo != null) { sheet = MapSheet.ROUTE }
            MapBtn(Icons.Rounded.Landscape, "3D-рельеф") { show3d = true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(rec, enter = fadeIn(), exit = fadeOut()) {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    Row(Modifier.padding(end = 8.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(MapPrefs.dist(ctx, recDist), style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(Pal.Line))
                        val s = (now - TrackService.startedAt).coerceAtLeast(0) / 1000
                        Text(if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Box(Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(if (rec) Color(0xFFE5484D) else Pal.Card).clickable { recTap() },
                    contentAlignment = Alignment.Center) {
                    if (rec) Icon(Icons.Rounded.Stop, "Остановить запись", tint = Color.White)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                        Text("REC", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    }
                }
            }
        }
        // ---- слева по центру: масштаб ----
        Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)) {
            Box(Modifier.size(48.dp).clickable { map?.animateCamera(CameraUpdateFactory.zoomIn(), 250) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Приблизить", tint = Pal.Ink) }
            Box(Modifier.width(48.dp).height(1.dp).background(Pal.Line))
            Box(Modifier.size(48.dp).clickable { map?.animateCamera(CameraUpdateFactory.zoomOut(), 250) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Отдалить", tint = Pal.Ink) }
        }
        if (prefs.getBoolean("dev_zoom", false)) Text("z %.1f".format(zoomNow), Modifier.align(Alignment.CenterStart).padding(start = 14.dp, top = 130.dp), style = MaterialTheme.typography.labelSmall)

        // ---- низ: где я, линейка, настройки; карточки ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                LocButton(locMode, bearing) { locTap() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) { ScaleBar(scaleBar.first, scaleBar.second) }
                MapBtn(Icons.Rounded.Settings, "Настройки карты") { settingsStart = null; sheet = MapSheet.SETTINGS }
            }
            if (nav != null) NavBottom(nav, onStop = { stopNav() })
            else if (routeTo != null && target == null && selTrack == null) RouteStrip(routeTo!!, routeInfo, onOpen = { sheet = MapSheet.ROUTE },
                onGo = lastRoute?.takeIf { it.third && it.first.steps.isNotEmpty() }?.let { lr -> { startNav(lr.first, lr.second) } }) { routeTo = null; routeLine = emptyList(); routeInfo = ""; lastRoute = null }
            target?.let { t ->
                PlaceCard(t, myLoc, folders, onClose = { closeAll() }, onRoute = { s -> routeTo = s; routeInfo = ""; sheet = MapSheet.ROUTE }, onSaved = { pl -> open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl, if (t.place == null) mapOf("edit" to "1") else emptyMap())) })
            }
            selTrack?.let { t -> TrackCard(t, folders, onClose = { closeAll() }, onFit = { fit(Osm.decode(t.points)) }) }
        }
    }

    when (sheet) {
        MapSheet.SEARCH -> SearchScreen(map?.cameraPosition?.target, places, tracks, onDismiss = { sheet = MapSheet.NONE }) { hit ->
            sheet = MapSheet.NONE
            MapPrefs.addRecent(ctx, MapPrefs.Spot(hit.name, hit.lat, hit.lon, hit.address))
            fly(hit.lat, hit.lon, if (hit.kind.startsWith("place:city")) 12.0 else if (hit.kind.startsWith("place:")) 14.0 else 17.0)
            val pl = places.firstOrNull { it.lat == hit.lat && it.lon == hit.lon && it.name == hit.name }
            open(MapTarget(hit.name, pl?.let { "Моя метка" } ?: hit.address, hit.lat, hit.lon, pl, if (hit.kind.startsWith("google:")) mapOf("google_id" to hit.kind.removePrefix("google:")) else emptyMap()))
        }
        MapSheet.COLLECTIONS -> CollectionsScreen(places, tracks, folders, showAll, onShowAll = { showAll = it; prefs.edit().putBoolean("show_collections", it).apply() },
            onDismiss = { sheet = MapSheet.NONE },
            onPlace = { pl -> sheet = MapSheet.NONE; fly(pl.lat, pl.lon, 16.5); open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl)) },
            onTrack = { t -> sheet = MapSheet.NONE; target = null; pin(null, null); selTrack = t; fit(Osm.decode(t.points)) },
            onShowFolder = { pts -> sheet = MapSheet.NONE; if (!showAll) { showAll = true; prefs.edit().putBoolean("show_collections", true).apply() }; fit(pts) })
        MapSheet.LAYERS -> LayersPanel(onDismiss = { sheet = MapSheet.NONE }, onChanged = { styleTick++ }, onSettings = { settingsStart = "downloads"; sheet = MapSheet.SETTINGS },
            on3D = { sheet = MapSheet.NONE; show3d = true })
        MapSheet.ROUTE -> RoutePanel(routeTo, myLoc, places, onDismiss = { sheet = MapSheet.NONE }, onTarget = { routeTo = it; routeInfo = "" },
            onLine = { pts, info -> routeLine = pts; routeInfo = info; lastRoute = null; if (pts.size >= 2) fit(pts) },
            onRoute = { r, name, fromMe -> lastRoute = r?.let { Triple(it, name, fromMe) } },
            onClear = { routeTo = null; routeLine = emptyList(); routeInfo = "" })
        MapSheet.SETTINGS -> MapSettingsScreen(onDismiss = { sheet = MapSheet.NONE; settingsStart = null }, onChanged = { styleTick++ }, start = settingsStart)
        MapSheet.NONE -> {}
    }
    renameTrack?.let { t -> TrackEditor(t, folders) { renameTrack = null } }
    if (show3d) map?.cameraPosition?.let { c -> Terrain3DScreen(c.target?.latitude ?: 48.16, c.target?.longitude ?: 24.5, c.zoom, c.bearing) { show3d = false } }
}

@Composable
private fun Metric(v: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(unit, fontSize = 10.sp, color = Pal.Muted)
    }
}

@Composable
internal fun MapBtn(icon: ImageVector, d: String, size: Dp = 48.dp, active: Boolean = false, f: () -> Unit) =
    Box(Modifier.size(size).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(if (active) Pal.Sky else Pal.Card).clickable(onClick = f), contentAlignment = Alignment.Center) {
        Icon(icon, d, tint = if (active) Pal.SkyInk else Pal.Ink)
    }

/** Кнопка «где я»: стрелка контуром — свободно, залитая — карта за мной, компас — карта крутится по компасу. */
@Composable
private fun LocButton(mode: Int, bearing: Float, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(if (mode != 0) Pal.Sky else Pal.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        when {
            mode == 2 -> Canvas(Modifier.size(28.dp).rotate(-bearing)) {
                val w = size.width; val h = size.height
                drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close() }, Color(0xFFE5484D))
                drawPath(Path().apply { moveTo(w / 2, h); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close() }, Pal.SkyInk)
            }
            mode == 0 && bearing != 0f -> Canvas(Modifier.size(28.dp).rotate(-bearing)) {
                val w = size.width; val h = size.height
                drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.66f, h / 2); lineTo(w * 0.34f, h / 2); close() }, Color(0xFFE5484D))
                drawPath(Path().apply { moveTo(w / 2, h); lineTo(w * 0.66f, h / 2); lineTo(w * 0.34f, h / 2); close() }, Pal.Muted)
            }
            else -> Icon(if (mode == 1) Icons.Rounded.Navigation else Icons.Rounded.NearMe, "Где я", tint = if (mode == 1) Pal.SkyInk else Pal.Ink, modifier = Modifier.rotate(if (mode == 1) 0f else 0f))
        }
    }
}

@Composable
private fun ScaleBar(width: Dp, label: String) {
    if (label.isEmpty()) return
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(label, fontSize = 10.sp, color = Pal.Ink)
        Canvas(Modifier.width(width).height(5.dp)) {
            val y = size.height - 1f
            drawLine(Pal.Ink, Offset(0f, y), Offset(size.width, y), 2.5f, StrokeCap.Square)
            drawLine(Pal.Ink, Offset(0f, 0f), Offset(0f, y), 2.5f)
            drawLine(Pal.Ink, Offset(size.width, 0f), Offset(size.width, y), 2.5f)
        }
    }
}

@Composable
private fun RouteStrip(to: MapPrefs.Spot, info: String, onOpen: () -> Unit, onGo: (() -> Unit)? = null, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Directions, null, tint = Pal.SkyInk)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(to.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
        if (onGo != null) Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF2F7D57)).clickable(onClick = onGo).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Поехали", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClear) { Icon(Icons.Rounded.Close, "Убрать маршрут") }
    }
}

/** Красная метка «как везде» для выбранной точки. */
internal fun pinBitmap(d: Float): android.graphics.Bitmap {
    val w = (30 * d).toInt(); val h = (42 * d).toInt()
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = w / 2f - d
    val path = android.graphics.Path().apply {
        addCircle(w / 2f, r + d, r, android.graphics.Path.Direction.CW)
        moveTo(w / 2f - r * 0.7f, r + d + r * 0.7f); lineTo(w / 2f, h - d); lineTo(w / 2f + r * 0.7f, r + d + r * 0.7f); close()
    }
    p.color = 0x40000000; c.save(); c.translate(0f, d); c.drawPath(path, p); c.restore()
    p.color = 0xFFE5484D.toInt(); c.drawPath(path, p)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(w / 2f, r + d, r * 0.38f, p)
    return b
}


/** Значок манёвра. */
internal fun navIcon(cmd: Int): ImageVector = when (cmd) {
    Nav.TL -> Icons.Rounded.TurnLeft; Nav.TR -> Icons.Rounded.TurnRight
    Nav.TSLL -> Icons.Rounded.TurnSlightLeft; Nav.TSLR -> Icons.Rounded.TurnSlightRight
    Nav.TSHL -> Icons.Rounded.TurnSharpLeft; Nav.TSHR -> Icons.Rounded.TurnSharpRight
    Nav.KL, Nav.EL -> Icons.Rounded.ForkLeft; Nav.KR, Nav.ER -> Icons.Rounded.ForkRight
    Nav.TU, Nav.TLU, Nav.TRU -> Icons.Rounded.UTurnLeft
    Nav.RNDB, Nav.RNLB -> Icons.Rounded.RoundaboutRight
    Nav.END -> Icons.Rounded.SportsScore
    else -> Icons.Rounded.Straight
}

@Composable
private fun NavBanner(modifier: Modifier) {
    val st = Nav.status
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(10.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1F6F4A)).padding(14.dp)) {
        if (st == null) { Text("Ищу спутники…", color = Color.White, style = MaterialTheme.typography.titleMedium); return@Column }
        if (st.off) { Text("Вы сошли с маршрута — перестраиваю…", color = Color.White, style = MaterialTheme.typography.titleMedium); return@Column }
        val step = st.step
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(navIcon(step?.cmd ?: Nav.END), null, tint = Color.White, modifier = Modifier.size(54.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Nav.distText(st.toStep), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(Nav.phrase(step?.cmd ?: Nav.END, step?.exit ?: 0).replaceFirstChar { it.uppercase() }, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
            }
        }
        st.next?.let { n ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Затем ", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                Icon(navIcon(n.cmd), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NavBottom(s: Nav.Session, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val st = Nav.status
    val left = st?.left ?: s.total
    val sec = st?.leftSec ?: s.route.seconds
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Pal.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(fmtDur((sec * 1000).toLong()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2F7D57))
            Text(MapPrefs.dist(ctx, left) + " · прибытие " + java.time.LocalTime.now().plusSeconds(sec.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " · " + s.destName,
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
        }
        IconButton({ Nav.muted = !Nav.muted }) { Icon(if (Nav.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Голос", tint = Pal.Ink) }
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Rose).clickable(onClick = onStop).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Завершить", color = Pal.RoseInk, style = MaterialTheme.typography.labelLarge)
        }
    }
}
