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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.abs

internal fun hasLoc(ctx: Context) = TrackService.canStart(ctx)

/** Что показано в карточке снизу. */
data class MapTarget(val name: String, val kind: String, val lat: Double, val lon: Double, val place: Place? = null, val props: Map<String, String> = emptyMap())

internal fun colorHex(c: Int, def: String) = if (c == 0) def else String.format("#%06X", c and 0xFFFFFF)
internal const val PLACE_COLOR = "#E0607E"
internal const val TRACK_COLOR = "#6A55A8"

/** Слои пользователя поверх карты: треки, метки, булавка, запись, маршрут. Всё — под значком «я» (он должен быть сверху). */
private fun setupOverlays(style: Style) {
    fun src(id: String) { if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()))) }
    listOf("u-tracks", "u-sel", "u-rec", "u-route", "u-done", "u-places", "u-pin", "u-stops", "u-ruler", "u-sim", "u-hl").forEach(::src)
    val below = style.layers.firstOrNull { it.id.startsWith("maplibre-location") || it.id.startsWith("mapbox-location") }?.id
    fun add(l: org.maplibre.android.style.layers.Layer) { if (below != null) style.addLayerBelow(l, below) else style.addLayer(l) }
    // выделенная тропа (пеший маршрут из тайлов)
    add(LineLayer("u-hl-l", "u-hl").withProperties(PF.lineColor("#FFFFFF"), PF.lineWidth(9f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.9f)))
    add(LineLayer("u-hl-l2", "u-hl").withProperties(PF.lineColor("#D9534F"), PF.lineWidth(5f), PF.lineCap("round"), PF.lineJoin("round")))
    // пройденная часть маршрута — серая, оставшаяся — синяя со стрелками направления
    add(LineLayer("u-done-l", "u-done").withProperties(PF.lineColor("#9AA0A6"), PF.lineWidth(6f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.85f)))
    add(LineLayer("u-route-c", "u-route").withProperties(PF.lineColor("#FFFFFF"), PF.lineWidth(9f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.9f)))
    add(LineLayer("u-route-l", "u-route").withProperties(PF.lineColor("#3E6FA8"), PF.lineWidth(6f), PF.lineCap("round"), PF.lineJoin("round")))
    add(SymbolLayer("u-route-a", "u-route").withProperties(PF.symbolPlacement("line"), PF.symbolSpacing(60f), PF.iconImage("trk-arrow"), PF.iconSize(0.8f),
        PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true), PF.iconRotationAlignment("map")))
    add(LineLayer("u-tracks-l", "u-tracks").withProperties(PF.lineColor(Expression.toColor(Expression.get("color"))), PF.lineWidth(4f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.9f)))
    // стрелки направления у всех треков — всегда, вдвое чаще, чем раньше у выбранного
    add(SymbolLayer("u-tracks-a", "u-tracks").withProperties(PF.symbolPlacement("line"), PF.symbolSpacing(40f), PF.iconImage("trk-arrow"), PF.iconSize(0.6f),
        PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true), PF.iconRotationAlignment("map")).apply { minZoom = 11f })
    // выбранный трек: заметно толще, с белой окантовкой и стрелками направления (от старта к финишу)
    add(LineLayer("u-sel-l", "u-sel").withProperties(PF.lineColor("#FFFFFF"), PF.lineWidth(13f), PF.lineCap("round"), PF.lineJoin("round"), PF.lineOpacity(0.95f)))
    add(LineLayer("u-sel-l2", "u-sel").withProperties(PF.lineColor(Expression.toColor(Expression.get("color"))), PF.lineWidth(8.5f), PF.lineCap("round"), PF.lineJoin("round")))
    add(SymbolLayer("u-sel-arrows", "u-sel").withProperties(PF.symbolPlacement("line"), PF.symbolSpacing(35f), PF.iconImage("trk-arrow"), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true), PF.iconRotationAlignment("map")))
    add(LineLayer("u-rec-l", "u-rec").withProperties(PF.lineColor("#D8456A"), PF.lineWidth(5f), PF.lineCap("round"), PF.lineJoin("round")))
    add(CircleLayer("u-stops-c", "u-stops").withProperties(PF.circleRadius(11f), PF.circleColor(Expression.toColor(Expression.get("c"))), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2.5f)))
    add(SymbolLayer("u-stops-t", "u-stops").withProperties(PF.textField(Expression.get("t")), PF.textFont(arrayOf("Noto Sans Bold")), PF.textSize(11f), PF.textColor("#FFFFFF"), PF.textAllowOverlap(true), PF.textIgnorePlacement(true)))
    add(SymbolLayer("u-ruler-t", "u-ruler").withProperties(PF.textField(Expression.get("t")), PF.textFont(arrayOf("Noto Sans Bold")), PF.textSize(12.5f), PF.textColor("#3E6FA8"),
        PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(2f), PF.textAnchor("bottom"), PF.textOffset(arrayOf(0f, -0.8f)), PF.textAllowOverlap(true)))
    add(CircleLayer("u-places-c", "u-places").withProperties(PF.circleRadius(7.5f), PF.circleColor(Expression.toColor(Expression.get("color"))), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2.5f)))
    add(SymbolLayer("u-places-t", "u-places").withProperties(PF.textField(Expression.get("name")), PF.textFont(arrayOf("Noto Sans Bold")), PF.textSize(11.5f),
        PF.textColor("#2B2A33"), PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(1.5f), PF.textAnchor("top"), PF.textOffset(arrayOf(0f, 0.9f)), PF.textOptional(true)).apply { minZoom = 11f })
    // «похожие рядом» — оранжевые точки с названиями
    add(CircleLayer("u-sim-c", "u-sim").withProperties(PF.circleRadius(8f), PF.circleColor("#F08C2E"), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2.5f)))
    add(SymbolLayer("u-sim-t", "u-sim").withProperties(PF.textField(Expression.get("name")), PF.textFont(arrayOf("Noto Sans Bold")), PF.textSize(11f),
        PF.textColor("#9A4F0B"), PF.textHaloColor("#FFFFFF"), PF.textHaloWidth(1.5f), PF.textAnchor("top"), PF.textOffset(arrayOf(0f, 0.9f)), PF.textOptional(true)))
    add(SymbolLayer("u-pin-c", "u-pin").withProperties(PF.iconImage("pin"), PF.iconAnchor("bottom"), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true)))
}

internal fun Style.setFeatures(id: String, f: List<Feature>) { getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(f)) }

internal fun line(pts: List<GeoPt>): LineString = LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })

/** Видимые папки: сама видима и все родители видимы. */
internal fun visibleFolders(folders: List<MapFolder>): Set<Long> {
    val byId = folders.associateBy { it.id }
    fun ok(f: MapFolder, depth: Int = 0): Boolean = f.visible && (f.parentId == null || depth > 30 || byId[f.parentId]?.let { ok(it, depth + 1) } ?: true)
    return folders.filter { ok(it) }.map { it.id }.toSet()
}

enum class MapSheet { NONE, SEARCH, COLLECTIONS, LAYERS, SETTINGS }

/** Повторное нажатие на вкладку «Карта» внизу: возвращает кнопки после «Рамки» (карта при этом не сбрасывается). */
object MapUi {
    var reselect by mutableIntStateOf(0)
    /** Открыть на карте из других разделов (задача ToDo): трек или точку; go — сразу «В путь». */
    var request by mutableStateOf<MapRequest?>(null)
    /** Открыть задачу ToDo («task:ID») — например, по надписи «Запланировано…». */
    var openTodo by mutableStateOf<String?>(null)
}
data class MapRequest(val trackId: Long? = null, val lat: Double = 0.0, val lon: Double = 0.0, val name: String = "", val go: Boolean = false)

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
    var pickStop by remember { mutableStateOf<Int?>(null) }
    var searchFor by remember { mutableStateOf<Int?>(null) }        // поиск для точки маршрута: индекс или −1 — новая остановка
    var trackColor by remember { mutableStateOf("") }      // раскраска выбранного трека: alt | speed | slope        // выбор точки маршрута на карте: индекс или −1 — новая остановка
    val nav = Nav.session
    var renameTrack by remember { mutableStateOf<Track?>(null) }
    val rec by TrackService.recording.collectAsState()
    val recPts by TrackService.points.collectAsState()
    val recDist by TrackService.distance.collectAsState()
    val lastSaved by TrackService.lastSaved.collectAsState()

    val view = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // часто меняющиеся значения (каждый кадр жеста / каждую секунду) — в отдельном объекте: их читают только маленькие
    // блоки (линейка, «где я», скорость), и весь экран карты при жесте не пересчитывается
    val live = remember { MapLive() }
    var myLoc by remember { mutableStateOf<GeoPt?>(null) }
    var locMode by remember { mutableIntStateOf(0) }       // 0 — свободно, 1 — за мной, 2 — по компасу
    var trackListener by remember { mutableStateOf(false) }
    var rerouting by remember { mutableStateOf(false) }
    var rerouteAt by remember { mutableStateOf<GeoPt?>(null) }
    val world = remember { WorldToggle() }
    val poiIcons = remember { HashMap<String, android.graphics.Bitmap>() }
    val poiPending = remember { HashSet<String>() }
    LaunchedEffect(locMode, style) { runCatching { (map?.locationComponent?.compassEngine as? SmoothCompass)?.fine = locMode == 2 } }
    val quickZoom = remember { QuickZoom(view, density) { map } }
    var clean by remember { mutableStateOf(false) }          // «рамка»: все кнопки спрятаны, вернуть — вкладка «Карта» внизу
    var frameMenu by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }       // навигация: показан весь маршрут
    var navFreeAt by remember { mutableLongStateOf(0L) }       // навигация: когда карту тронули рукой
    var similar by remember { mutableStateOf<Pair<String, List<GeoHit>>?>(null) }
    var simIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(MapUi.reselect) { if (MapUi.reselect > 0) { clean = false; frameMenu = false } }

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
    /** Показать точку выше центра — низ экрана занят карточкой места. */
    fun flyAbove(lat: Double, lon: Double, z: Double? = null) {
        val m = map ?: return
        if (locMode != 0) { runCatching { m.locationComponent.cameraMode = CameraMode.NONE }; locMode = 0 }
        val h = view.height.toDouble()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(z ?: maxOf(m.cameraPosition.zoom, 15.5)).bearing(m.cameraPosition.bearing)
            .padding(0.0, 0.0, 0.0, h * 0.45).build()), 650)
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
    fun open(t: MapTarget) { selTrack = null; target = t; pin(if (t.place == null) t.lat else null, t.lon); if (t.props["kind"] != "route") style?.setFeatures("u-hl", emptyList()) }
    /** Подсветить тропу целиком и показать её всю (низ экрана — под карточкой). */
    fun highlight(parts: List<Feature>) {
        style?.setFeatures("u-hl", parts.mapNotNull { it.geometry()?.let { g -> Feature.fromGeometry(g) } })
        val pts = parts.flatMap { f -> when (val g = f.geometry()) {
            is LineString -> g.coordinates(); is org.maplibre.geojson.MultiLineString -> g.coordinates().flatten(); else -> emptyList() } }.map { GeoPt(it.latitude(), it.longitude(), 0) }
        if (pts.size >= 2) runCatching {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (50 * density).toInt(), (130 * density).toInt(), (50 * density).toInt(), (view.height * 0.5).toInt()), 700)
        }
    }
    fun closeAll() {
        target = null; selTrack = null; pin(null, null); style?.setFeatures("u-hl", emptyList()); style?.setFeatures("u-sim", emptyList()); similar = null
        map?.let { m -> if ((m.cameraPosition.padding?.getOrNull(3) ?: 0.0) > 0) m.easeCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0), 300) }
    }

    // ---------- карта ----------
    LaunchedEffect(Unit) {
        OnlineMaps.applyCacheLimit(ctx)
        launch { OfflineMaps.loadCatalog(ctx); OfflineMaps.resume(ctx); OfflineMaps.autoWorld(ctx); OfflineMaps.ensureExtras(ctx) }
        view.getMapAsync { m ->
            map = m
            m.cameraPosition = camera(prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble(), prefs.getFloat("zoom", 11f).toDouble())
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionMargins((8 * density).toInt(), 0, 0, (4 * density).toInt())
            m.setMinZoomPreference(1.0)
            // жесты: свободный поворот, щипок 1:1 (без ускорения и без ограничения кадров — они давали рывки), свой зум одним пальцем
            tuneGestures(m, density)
            view.setOnTouchListener(quickZoom)
            // редкие классы, которых нет в заготовке: пока стиль грузится, m.style == null — запоминаем и добавляем после загрузки
            view.addOnStyleImageMissingListener { id ->
                if (!id.startsWith("p:")) return@addOnStyleImageMissingListener
                val st = m.style
                if (st == null) poiPending += id else runCatching { st.addImage(id, poiIcons.getOrPut(id) { poiBitmap(ctx, id.removePrefix("p:"), density) }) }
            }
            fun scale() {
                val c = m.cameraPosition; val lat = c.target?.latitude ?: 50.0
                live.bearing = c.bearing.toFloat(); live.zoom = c.zoom.toFloat()
                val mpp = m.projection.getMetersPerPixelAtLatitude(lat)
                val maxM = mpp * 84 * density
                val p = 10.0.pow(floor(log10(maxM)))
                val nice = listOf(5.0, 2.0, 1.0).map { it * p }.first { it <= maxM }
                val sb = (nice / mpp / density).dp to MapPrefs.dist(ctx, nice)
                if (sb != live.scaleBar) live.scaleBar = sb
            }
            m.addOnCameraMoveListener { scale(); world.check(m) }
            // в навигации: тронул карту рукой — ведение на паузе, через 8 с без касаний вернётся само
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && Nav.session != null) navFreeAt = System.currentTimeMillis() }
            m.addOnCameraIdleListener {
                scale(); world.check(m, force = true)
                // повернули чуть-чуть (до 10°) — выровнять на север; в режиме компаса и в навигации не трогаем
                // и в навигации, если карту крутили рукой (ведение на паузе); пока ведёт по курсу — не трогаем
                if (locMode == 0 && !quickZoom.active && nearNorth(m.cameraPosition.bearing)) m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 220)
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
                m.queryRenderedFeatures(box, "u-sim-c", "u-sim-t").firstOrNull()?.let { f ->
                    val g = f.geometry() as? Point
                    if (g != null) {
                        similar?.second?.indexOfFirst { it.lat == g.latitude() && it.lon == g.longitude() }?.takeIf { it >= 0 }?.let { simIdx = it }
                        open(MapTarget(f.getStringProperty("name") ?: "", f.getStringProperty("kind") ?: "", g.latitude(), g.longitude())); return@addOnMapClickListener true
                    }
                }
                if (target != null || selTrack != null) { closeAll(); return@addOnMapClickListener true }
                val all = m.queryRenderedFeatures(box)
                // значки (с названием или без: ворота, урны, площадки — тоже открываются), потом тропы, потом подписи улиц и т. п.
                val pointF = all.firstOrNull { it.geometry() is Point && (it.hasNonNullValueForProperty("name") || it.hasNonNullValueForProperty("class")) }
                if (pointF == null) {
                    val routeIds = m.style?.layers?.map { it.id }?.filter { it.startsWith("route@") || it.startsWith("route-n@") }.orEmpty()
                    val rf = if (routeIds.isEmpty()) null else m.queryRenderedFeatures(RectF(box.left - r / 2, box.top - r / 2, box.right + r / 2, box.bottom + r / 2), *routeIds.toTypedArray()).firstOrNull()
                    if (rf != null) {
                        val nm = rf.getStringProperty("name") ?: "Тропа"
                        val net = when (rf.getStringProperty("network")) { "iwn" -> "международный"; "nwn" -> "национальный"; "rwn" -> "региональный"; "lwn" -> "местный"; else -> null }
                        val parts = runCatching { m.style?.sources?.filter { it.id.startsWith("lines_") }?.flatMap { src ->
                            (src as? org.maplibre.android.style.sources.VectorSource)?.querySourceFeatures(arrayOf("route"), Expression.eq(Expression.get("name"), nm)).orEmpty()
                        } }.getOrNull().orEmpty()
                        val rp = rf.properties()?.entrySet()?.associate { it.key to (runCatching { it.value.asString }.getOrDefault(it.value.toString())) }.orEmpty()
                        open(MapTarget(nm, listOfNotNull("Пеший маршрут", rf.getStringProperty("ref")?.takeIf { it.isNotBlank() }, net?.let { "$it маршрут" }).joinToString(" · "),
                            ll.latitude, ll.longitude, props = rp + ("kind" to "route")))
                        pin(null, null)
                        highlight(parts)
                        return@addOnMapClickListener true
                    }
                }
                val f = pointF ?: all.firstOrNull { f -> f.hasNonNullValueForProperty("name") }
                // тропинка/дорожка (пунктир): название берём с неё или с подписи рядом; подсвечиваем всю, сведения — OSM и Википедия
                fun isPath(x: Feature) = x.geometry() !is Point && (x.getStringProperty("class") in setOf("path", "track", "pedestrian") || x.getStringProperty("subclass") in setOf("footway", "path", "cycleway", "bridleway", "steps"))
                val pathF = if (pointF == null) all.firstOrNull { isPath(it) } else null
                if (pathF != null) {
                    val big = RectF(box.left - r * 2, box.top - r * 2, box.right + r * 2, box.bottom + r * 2)
                    val nm = pathF.getStringProperty("name") ?: m.queryRenderedFeatures(big).firstOrNull { isPath(it) && it.hasNonNullValueForProperty("name") }?.getStringProperty("name")
                    val props = pathF.properties()?.entrySet()?.associate { it.key to (runCatching { it.value.asString }.getOrDefault(it.value.toString())) }.orEmpty()
                    val kindTxt = MapNames.omt(props["class"], props["subclass"]).lowercase().ifBlank { "тропа" }
                    val parts = if (nm == null) listOf(Feature.fromGeometry(pathF.geometry())) else runCatching { m.style?.sources?.filter { it.id.startsWith("omt_") || it.id == "ofm" }?.flatMap { src ->
                        (src as? org.maplibre.android.style.sources.VectorSource)?.querySourceFeatures(arrayOf("transportation_name"), Expression.eq(Expression.get("name"), nm)).orEmpty()
                    } }.getOrNull().orEmpty().ifEmpty { listOf(Feature.fromGeometry(pathF.geometry())) }
                    open(MapTarget(nm ?: "Тропа без названия", "Тропа · $kindTxt", ll.latitude, ll.longitude, props = props + ("kind" to "route") + (if (nm != null) mapOf("name" to nm) else emptyMap())))
                    pin(null, null); highlight(parts)
                    return@addOnMapClickListener true
                }
                if (f != null) {
                    val props = f.properties()?.entrySet()?.associate { it.key to (runCatching { it.value.asString }.getOrDefault(it.value.toString())) }.orEmpty()
                    val g = f.geometry()
                    val (lat, lon) = if (g is Point) g.latitude() to g.longitude() else ll.latitude to ll.longitude
                    val kind = MapNames.omt(props["class"], props["subclass"])
                    val nm = props["name:ru"]?.takeIf { MapStyle.opts(ctx).lang == "ru" } ?: props["name"] ?: kind.ifBlank { "Объект на карте" }
                    open(MapTarget(nm, kind, lat, lon, props = props))
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
        // все значки рисуем заранее (в фоне) и кладём в стиль сразу: раньше они рисовались по запросу во время зума,
        // а пока стиль догружался, запрос терялся — значки пропадали после любого переключения в «Объектах на карте»
        if (poiIcons.isEmpty()) poiIcons.putAll(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            (MapStyle.POI_GROUPS.values.flatMap { it.classes } + MapStyle.CLASS_ICON.keys + MapStyle.SUBCLASS_ICONS).distinct().associate { c -> "p:$c" to poiBitmap(ctx, c, density) }
        })
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { MapStyle.build(ctx, opts) }
        m.setStyle(Style.Builder().fromJson(json).withBitmapImages(*poiIcons.map { android.util.Pair(it.key, it.value) }.toTypedArray())) { st ->
            poiPending.toList().forEach { id -> runCatching { st.addImage(id, poiIcons.getOrPut(id) { poiBitmap(ctx, id.removePrefix("p:"), density) }) } }
            poiPending.clear()
            st.addImage("pin", pinBitmap(density)); st.addImage("trk-arrow", arrowBitmap(density))
            setupOverlays(st); style = st
            world.reset(if (opts.online == null && opts.worldOnline) MapStyle.rings(OfflineMaps.shapes(ctx).map { it.second }) else emptyList()); world.check(m, force = true)
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
    /** Камера в навигации: зум и наклон по способу и скорости; пешком и на месте — по компасу, на ходу — по курсу GPS. */
    var navCam by remember { mutableStateOf(Triple(0.0, 0.0, -1)) }      // зум, наклон, режим камеры — чтобы не дёргать без нужды
    fun navCamera(mode: String, kmh: Float) {
        if (locMode == 0 && navCam.third != -1) return      // сдвинули карту рукой — не мешаем, вернёт кнопка «где я»
        val lc = runCatching { map!!.locationComponent }.getOrNull() ?: return
        val z = when (mode) {
            "car" -> when { kmh > 90 -> 15.2; kmh > 60 -> 15.8; kmh > 30 -> 16.4; else -> 17.0 }
            "bike" -> if (kmh > 18) 16.6 else 17.2
            else -> 17.8
        }
        val tilt = when (mode) { "car" -> 55.0; "bike" -> 45.0; else -> 35.0 }
        val cam = if (mode == "foot" || kmh < 6) CameraMode.TRACKING_COMPASS else CameraMode.TRACKING_GPS
        val ok = runCatching {
            if (cam != navCam.third) {
                // режим, зум и наклон — одним переходом: отдельный zoomWhileTracking во время смены режима MapLibre пропускает
                lc.renderMode = if (cam == CameraMode.TRACKING_GPS) org.maplibre.android.location.modes.RenderMode.GPS else org.maplibre.android.location.modes.RenderMode.COMPASS
                lc.setCameraMode(cam, 900L, z, null, tilt, null)
            } else {
                if (abs(z - navCam.first) > 0.25) lc.zoomWhileTracking(z, 1200)
                if (abs(tilt - navCam.second) > 1) lc.tiltWhileTracking(tilt, 1200)
            }
        }.isSuccess
        if (ok) { navCam = Triple(z, tilt, cam); locMode = 2 }
    }
    fun navFollow() {
        val s = Nav.session ?: return
        overview = false; navCam = Triple(0.0, 0.0, -1)
        // точка «я» — в нижней трети экрана: впереди видно больше дороги
        runCatching { map!!.moveCamera(CameraUpdateFactory.paddingTo(0.0, view.height * 0.35, 0.0, 0.0)) }
        navCamera(s.mode, live.speed ?: 0f)
    }
    fun navOverview() {
        val s = Nav.session ?: return
        overview = true
        runCatching { map!!.locationComponent.cameraMode = CameraMode.NONE; locMode = 0; map!!.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0)) }
        val m = map ?: return
        runCatching {
            val b = LatLngBounds.Builder().apply { (listOfNotNull(myLoc) + s.route.points).forEach { include(LatLng(it.lat, it.lon)) } }.build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (60 * density).toInt(), (190 * density).toInt(), (60 * density).toInt(), (170 * density).toInt()), 700)
        }
    }

    // скорость, высота, автомасштаб
    LaunchedEffect(style) {
        while (true) {
            runCatching { map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation }.getOrNull()?.let { l ->
                live.speed = if (l.hasSpeed()) l.speed * 3.6f else null
                live.alt = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else if (l.hasAltitude()) l.altitude else null
                val here = GeoPt(l.latitude, l.longitude, l.time)
                // экран пересчитываем, только если сдвинулись больше чем на 3 м
                if (myLoc == null || Osm.distance(myLoc!!, here) > 3 || Nav.session != null) myLoc = here
                val speed = live.speed
                if (Nav.session != null) {
                    val needReroute = Nav.update(myLoc!!, if (l.hasAccuracy()) l.accuracy else 20f)
                    Nav.voice(speed ?: 0f)
                    Nav.session?.let { s ->
                        style?.setFeatures("u-route", listOf(Feature.fromGeometry(line(listOf(myLoc!!) + s.route.points.drop(s.seg + 1)))))
                        style?.setFeatures("u-done", if (s.seg >= 0) listOf(Feature.fromGeometry(line(s.route.points.take(s.seg + 1) + myLoc!!))) else emptyList())
                        if (!overview) navCamera(s.mode, speed ?: 0f)
                    }
                    if (needReroute && !rerouting) rerouteAt = myLoc
                } else if (locMode != 0 && MapPrefs.bool(ctx, "autozoom", true)) {
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
    LaunchedEffect(style, tracks, visF, showAll, RoutePlan.editTrack, RoutePlan.active) {
        // правленый трек прячем — на его месте новый маршрут планировщика
        // (запись GPS/импорт сами не перезаписываются — их не прячем)
        val hide = RoutePlan.editTrack?.takeIf { RoutePlan.active && routeWay(it) != null }?.id
        val list = if (!showAll) emptyList() else tracks.filter { it.visible && (it.folderId == null || it.folderId in visF) && it.id != hide }
        val feats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            list.mapNotNull { t -> Osm.decode(t.points).takeIf { it.size >= 2 }?.let { pts ->
                Feature.fromGeometry(line(pts)).also { it.addNumberProperty("id", t.id); it.addStringProperty("color", colorHex(t.color, TRACK_COLOR)) } } }
        }
        style?.setFeatures("u-tracks", feats)
    }
    // трек без высот (построенный маршрут, импорт) — один раз берём высоты из рельефа и сохраняем
    LaunchedEffect(selTrack?.id) {
        val t = selTrack ?: return@LaunchedEffect
        val nt = Dem.ensure(ctx, t)
        if (nt !== t && selTrack?.id == t.id) selTrack = nt
    }
    LaunchedEffect(style, selTrack, trackColor) {
        val t = selTrack; val pts = t?.let { Osm.decode(it.points) }.orEmpty()
        val feats = if (pts.size < 2) emptyList() else if (trackColor.isEmpty()) listOf(Feature.fromGeometry(line(pts)).also { it.addNumberProperty("id", t!!.id); it.addStringProperty("color", colorHex(t.color, TRACK_COLOR)) })
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // раскраска по высоте / скорости / уклону: отрезки цветом от зелёного к красному
            val v = TrackMath.values(TrackMath.stats(pts), trackColor)
            val ok = v.filter { !it.isNaN() }.sorted()
            if (ok.size < 2) return@withContext listOf(Feature.fromGeometry(line(pts)).also { it.addNumberProperty("id", t!!.id); it.addStringProperty("color", colorHex(t.color, TRACK_COLOR)) })
            val lo = ok[(ok.size * 0.03).toInt()]; val hi = ok[(ok.size * 0.97).toInt().coerceAtMost(ok.size - 1)].coerceAtLeast(lo + 0.1)
            val step = maxOf(1, pts.size / 600)
            (0 until pts.size - 1 step step).map { i ->
                val j = minOf(i + step, pts.size - 1)
                val c = TrackMath.ramp(((v[i].takeIf { !it.isNaN() } ?: lo) - lo) / (hi - lo))
                Feature.fromGeometry(line(pts.subList(i, j + 1))).also { it.addNumberProperty("id", t!!.id); it.addStringProperty("color", colorHex(c, TRACK_COLOR)) }
            }
        }
        style?.setFeatures("u-sel", feats)
    }
    LaunchedEffect(style, recPts) { style?.setFeatures("u-rec", if (recPts.size >= 2) listOf(Feature.fromGeometry(line(recPts))) else emptyList()) }
    // план маршрута: строим сам, когда есть начало и конец
    val needMe = RoutePlan.active && RoutePlan.points.first() == null && myLoc == null
    LaunchedEffect(RoutePlan.active, RoutePlan.rev, RoutePlan.mode, needMe) {
        if (!RoutePlan.active) { RoutePlan.route = null; return@LaunchedEffect }
        val pts = RoutePlan.points.toList()
        val start = pts.first()?.let { GeoPt(it.lat, it.lon, 0) } ?: myLoc
        val end = pts.last()
        if (end == null || start == null) { RoutePlan.route = null; RoutePlan.error = if (end != null) "Жду моё место (геолокация)…" else null; return@LaunchedEffect }
        val way = listOf(start) + pts.drop(1).filterNotNull().map { GeoPt(it.lat, it.lon, 0) }
        RoutePlan.error = null
        if (RoutePlan.mode == "line") {
            var d = 0.0; for (k in 1 until way.size) d += Osm.distance(way[k - 1], way[k])
            RoutePlan.route = Routing.Route(way, d, 0.0, source = "line"); fit(way); return@LaunchedEffect
        }
        RoutePlan.busy = true
        RoutePlan.climb = null
        runCatching { Nav.best(ctx, RoutePlan.mode, way) }
            .onSuccess { RoutePlan.route = it; fit(it.points); RoutePlan.editTrack?.takeIf { tr -> routeWay(tr) != null }?.let { tr -> saveRouteAsTrack(ctx, tr, quiet = true) } }
            .onFailure { RoutePlan.route = null; RoutePlan.error = it.message ?: "Маршрут не построен" }
        RoutePlan.busy = false
        // подъём и спуск: высоты BRouter или рельеф с телефона
        RoutePlan.route?.let { r -> RoutePlan.climb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            (if (Dem.hasAlt(r.points)) r.points else Dem.fill(ctx, r.points))?.let { TrackMath.stats(it) }?.takeIf { !it.minAlt.isNaN() }?.let { it.ascent to it.descent }
        } }
    }
    LaunchedEffect(style, RoutePlan.route, RoutePlan.rev, RoutePlan.active) {
        val st = style ?: return@LaunchedEffect
        if (Nav.session != null) return@LaunchedEffect
        val rt = RoutePlan.route.takeIf { RoutePlan.active }
        st.setFeatures("u-route", if (rt != null && rt.points.size >= 2) listOf(Feature.fromGeometry(line(rt.points))) else emptyList())
        val stops = if (!RoutePlan.active) emptyList() else RoutePlan.points.mapIndexedNotNull { i, sp -> sp?.let {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).also { f ->
                f.addStringProperty("t", when (i) { 0 -> ""; RoutePlan.points.size - 1 -> "★"; else -> RoutePlan.label(i - 1) })
                f.addStringProperty("c", when (i) { 0 -> "#3E6FA8"; RoutePlan.points.size - 1 -> "#E5484D"; else -> "#E09A1B" })
            } } }
        st.setFeatures("u-stops", stops)
        st.setFeatures("u-ruler", if (rt?.source == "line") rulerMarks(ctx, rt.points).map { (p, t) -> Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also { it.addStringProperty("t", t) } } else emptyList())
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

    fun startNav(r: Routing.Route, name: String, way: List<GeoPt> = emptyList(), mode: String = MapPrefs.navMode(ctx)) {
        Nav.initVoice(ctx); Nav.muted = !MapPrefs.bool(ctx, "voice", true)
        Nav.session = Nav.Session(r, mode, name, way.ifEmpty { listOfNotNull(r.points.firstOrNull(), r.points.lastOrNull()) }); Nav.status = null
        target = null; selTrack = null; pin(null, null)
        view.keepScreenOn = true
        style?.setFeatures("u-done", emptyList())
        navFollow()
        Nav.say("Поехали. " + Nav.distText(r.meters).replace(",", " и "))
    }
    fun stopNav() {
        Nav.session = null; Nav.status = null; view.keepScreenOn = false; overview = false
        style?.setFeatures("u-done", emptyList())
        runCatching { map!!.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0)) }
        runCatching {
            val lc = map!!.locationComponent
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS; lc.cameraMode = CameraMode.NONE; locMode = 0
            map!!.animateCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(map!!.cameraPosition).tilt(0.0).bearing(0.0).build()), 600)
        }
        style?.setFeatures("u-route", emptyList())
    }
    fun rerouteNav(from: GeoPt) {
        val s = Nav.session ?: return; val to = s.route.points.lastOrNull() ?: return
        // оставшиеся остановки — те, что ещё впереди по маршруту
        val ahead = s.way.drop(1).dropLast(1).filter { w -> s.cum[Routing.nearestIndex(s.route.points, w.lat, w.lon)] > s.along + 30 }
        rerouting = true
        scope.launch {
            runCatching { Nav.best(ctx, s.mode, listOf(from) + ahead + to) }.onSuccess { r ->
                if (Nav.session === s) { Nav.session = Nav.Session(r, s.mode, s.destName, listOf(from) + ahead + to); Nav.say("Маршрут перестроен") }
            }
            rerouting = false
        }
    }
    LaunchedEffect(rerouteAt) { rerouteAt?.let { rerouteNav(it); rerouteAt = null } }
    LaunchedEffect(navFreeAt) {
        if (navFreeAt == 0L) return@LaunchedEffect
        delay(8000)
        if (Nav.session != null && !overview && locMode == 0) navFollow()
    }
    /** «В путь» по сохранённому треку: построенный маршрут — заново от меня через его точки; запись/импорт — до начала трека и дальше по нему. */
    fun goTrack(t: Track) {
        val pts = Osm.decode(t.points); if (pts.size < 2) return
        val me = myLoc ?: run { Toast.makeText(ctx, "Жду геолокацию…", Toast.LENGTH_SHORT).show(); if (!hasLoc(ctx)) askLoc(); return }
        val mode = trackMode(t)
        Toast.makeText(ctx, "Строю путь…", Toast.LENGTH_SHORT).show()
        scope.launch {
            runCatching {
                val rw = routeWay(t)
                if (rw != null) Nav.best(ctx, rw.first, listOf(me) + rw.second.drop(1).map { GeoPt(it.lat, it.lon, 0) })
                else {
                    val lead = if (Osm.distance(me, pts[0]) > 60) Nav.best(ctx, mode, listOf(me, pts[0])) else null
                    val all = (lead?.points ?: listOf(me)) + pts
                    val v = when (mode) { "car" -> 13.9; "bike" -> 4.5; else -> 1.3 }
                    Routing.Route(all, (lead?.meters ?: 0.0) + t.distanceM, (lead?.seconds ?: 0.0) + t.distanceM / v,
                        steps = lead?.steps?.filter { it.cmd != Nav.END }.orEmpty() + Routing.Step(all.size - 1, Nav.END), source = "трек")
                }
            }.onSuccess { r -> closeAll(); startNav(r, t.name, mode = routeWay(t)?.first ?: mode) }
                .onFailure { Toast.makeText(ctx, it.message ?: "Путь не построен", Toast.LENGTH_LONG).show() }
        }
    }
    // «Начать сейчас» из задачи: маршрут построился от моего места — сразу ведём
    LaunchedEffect(RoutePlan.route) {
        val r = RoutePlan.route ?: return@LaunchedEffect
        if (!RoutePlan.autoGo || RoutePlan.points.first() != null || r.steps.isEmpty()) return@LaunchedEffect
        RoutePlan.autoGo = false
        val way = listOfNotNull(myLoc) + RoutePlan.points.drop(1).filterNotNull().map { GeoPt(it.lat, it.lon, 0) }
        startNav(r, RoutePlan.dest?.name ?: "", way, RoutePlan.mode); RoutePlan.close()
    }
    // открыть из задачи ToDo: трек или место; «Начать сейчас» — сразу в путь
    LaunchedEffect(MapUi.request, map, style) {
        val q = MapUi.request ?: return@LaunchedEffect
        if (map == null || style == null) return@LaunchedEffect
        MapUi.request = null
        val t = q.trackId?.let { dao.track(it) }
        if (t != null) {
            target = null; pin(null, null); selTrack = t; fit(Osm.decode(t.points))
            if (q.go) { delay(400); goTrack(t) }
        } else if (q.lat != 0.0 || q.lon != 0.0) {
            if (q.go) { RoutePlan.open(ctx); RoutePlan.setFrom(null); RoutePlan.setTo(MapPrefs.Spot(q.name, q.lat, q.lon, "")); RoutePlan.autoGo = true }
            else { flyAbove(q.lat, q.lon, 16.5); open(MapTarget(q.name, "", q.lat, q.lon)) }
        }
    }
    DisposableEffect(Unit) { onDispose { if (Nav.session == null) Nav.shutdownVoice() } }
    fun locTap() {
        val m = map ?: return; val st = style ?: return
        if (!hasLoc(ctx)) { askLoc(); return }
        // в навигации: карта сдвинута/повёрнута рукой — первое нажатие ставит север, второе возвращает к ведению
        if (Nav.session != null) {
            if (locMode == 0 && abs(((live.bearing + 540f) % 360f) - 180f) > 1.5f) { m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 350); navFreeAt = System.currentTimeMillis(); return }
            navFollow(); return
        }
        MapKit.enableLocation(ctx, m, st, false)
        val lc = m.locationComponent
        if (lc.lastKnownLocation == null) Toast.makeText(ctx, "Ищу спутники…", Toast.LENGTH_SHORT).show()
        when {
            // карта повёрнута и я не «привязан» — сперва просто на север
            locMode == 0 && abs(((live.bearing + 540f) % 360f) - 180f) > 1.5f -> m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 350)
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

        if (nav != null && !clean) NavBanner(Modifier.align(Alignment.TopCenter))
        // ---- слева сверху: скорость | высота, поиск, коллекции ----
        if (!clean) {
        if (nav == null && RoutePlan.active) Box(Modifier.align(Alignment.TopCenter)) { RoutePlannerTop(onClose = { RoutePlan.close() }, onSettings = { settingsStart = "nav"; sheet = MapSheet.SETTINGS }) }
        if (nav == null && !RoutePlan.active) Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.height(48.dp).widthIn(min = 106.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)
                .clickable(interactionSource = null, indication = null) {}.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                SpeedAlt(live)
            }
            MapBtn(Icons.Rounded.Search, "Поиск") { sheet = MapSheet.SEARCH }
            MapBtn(Icons.Rounded.Bookmarks, "Коллекции") { sheet = MapSheet.COLLECTIONS }
        }
        // ---- справа сверху: слои, маршрут, запись ----
        if (nav == null && !RoutePlan.active) Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
            MapBtn(Icons.Rounded.Layers, "Слои") { sheet = MapSheet.LAYERS }
            MapBtn(Icons.Rounded.Directions, "Маршрут", active = RoutePlan.active) { RoutePlan.open(ctx) }
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
        }
        // ---- слева по центру: масштаб ----
        if (!clean) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 12.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)) {
            Box(Modifier.size(48.dp).clickable { if (Nav.session != null) navFreeAt = System.currentTimeMillis(); map?.animateCamera(CameraUpdateFactory.zoomIn(), 250) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Приблизить", tint = Pal.Ink) }
            Box(Modifier.width(48.dp).height(1.dp).background(Pal.Line))
            Box(Modifier.size(48.dp).clickable { if (Nav.session != null) navFreeAt = System.currentTimeMillis(); map?.animateCamera(CameraUpdateFactory.zoomOut(), 250) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Отдалить", tint = Pal.Ink) }
        }
        }
        if (prefs.getBoolean("dev_zoom", false)) Box(Modifier.align(Alignment.CenterStart).padding(start = 14.dp, top = 130.dp)) { ZoomText(live) }

        // ---- низ: где я, линейка, настройки; карточки ----
        if (!clean) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // навигация: весь маршрут целиком; «где я» возвращает к ведению
                    if (nav != null) MapBtn(Icons.Rounded.Route, "Весь маршрут", active = overview) { if (overview) navFollow() else navOverview() }
                    LocButton(locMode, live) { locTap() }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) { ScaleBar(live) }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // долгое нажатие на «Настройки» — кнопка «Рамка»: убрать все кнопки с карты
                    if (frameMenu) Row(Modifier.shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Ink)
                        .clickable { frameMenu = false; clean = true; Toast.makeText(ctx, "Кнопки вернёт вкладка «Карта» внизу", Toast.LENGTH_SHORT).show() }
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CropFree, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Рамка", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Box(Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)
                        .combinedClickableCompat(onClick = { if (frameMenu) frameMenu = false else { settingsStart = null; sheet = MapSheet.SETTINGS } }, onLongClick = { frameMenu = !frameMenu }),
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, "Настройки карты (долгое нажатие — рамка)", tint = Pal.Ink) }
                }
            }
            if (nav != null) NavBottom(nav, onStop = { stopNav() })
            else if (RoutePlan.active && target == null && selTrack == null) RoutePlannerBottom(myLoc, places, onGo = {
                RoutePlan.route?.let { r -> val way = listOfNotNull(myLoc) + RoutePlan.points.drop(1).filterNotNull().map { GeoPt(it.lat, it.lon, 0) }
                    startNav(r, RoutePlan.dest?.name ?: "", way, RoutePlan.mode); RoutePlan.close() }
            }, onPickMap = { i -> pickStop = i }, onSearch = { i -> searchFor = i; sheet = MapSheet.SEARCH })
            similar?.let { (title, hits) -> if (target != null) {
                fun go(i: Int) { simIdx = (i + hits.size) % hits.size; val h = hits[simIdx]; flyAbove(h.lat, h.lon, 16.0); open(MapTarget(h.name, h.address, h.lat, h.lon)) }
                Row(Modifier.padding(horizontal = 10.dp).padding(bottom = 6.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Pal.Card)
                    .clickable(interactionSource = null, indication = null) {}.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ go(simIdx - 1) }) { Icon(Icons.Rounded.ChevronLeft, "Назад", tint = Pal.Ink) }
                    Text("$title рядом · ${simIdx + 1} из ${hits.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton({ go(simIdx + 1) }) { Icon(Icons.Rounded.ChevronRight, "Дальше", tint = Pal.Ink) }
                    IconButton({ similar = null; style?.setFeatures("u-sim", emptyList()) }) { Icon(Icons.Rounded.Close, "Закрыть похожие", tint = Pal.Muted) }
                }
            } }
            target?.let { t ->
                PlaceCard(t, myLoc, folders, onClose = { closeAll() },
                    onRoute = { kind, sp -> RoutePlan.open(ctx); RoutePlan.collapsed = false; when (kind) { "from" -> RoutePlan.setFrom(sp); "stop" -> RoutePlan.addStop(sp); else -> RoutePlan.setTo(sp) }; closeAll() },
                    onSaved = { pl -> open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl, if (t.place == null) mapOf("edit" to "1") else emptyMap())) },
                    onSimilar = { tt ->
                        scope.launch {
                            val vals = listOfNotNull(tt.props["subclass"], tt.props["class"].takeIf { tt.props["subclass"] == null })
                            val hits = OfflineSearch.similar(ctx, vals, tt.lat, tt.lon)
                            if (hits.isEmpty()) { Toast.makeText(ctx, if (OfflineSearch.available(ctx).isEmpty()) "Нужен офлайн-поиск страны (Загрузка карт → Поиск)" else "Похожих рядом не нашлось", Toast.LENGTH_LONG).show(); return@launch }
                            style?.setFeatures("u-sim", hits.map { h -> Feature.fromGeometry(Point.fromLngLat(h.lon, h.lat)).also { it.addStringProperty("name", h.name); it.addStringProperty("kind", h.address) } })
                            similar = tt.kind.ifBlank { "Похожие" } to hits; simIdx = 0
                            val h = hits[0]; flyAbove(h.lat, h.lon, 16.0); open(MapTarget(h.name, h.address, h.lat, h.lon))
                        }
                    })
            }
            selTrack?.let { t -> TrackCard(t, folders, onClose = { closeAll() }, onFit = { fit(Osm.decode(t.points)) }, color = trackColor, onColor = { trackColor = it },
                onEditRoute = { tr -> closeAll(); RoutePlan.load(tr) }, onGo = { tr -> goTrack(tr) }) }
        }
    }

    when (sheet) {
        MapSheet.SEARCH -> SearchScreen(map?.cameraPosition?.target, places, tracks, onDismiss = { sheet = MapSheet.NONE; searchFor = null }) { hit ->
            sheet = MapSheet.NONE
            searchFor?.let { i ->
                val sp = MapPrefs.Spot(hit.name, hit.lat, hit.lon, hit.address)
                when (i) { -1 -> RoutePlan.addStop(sp); 0 -> RoutePlan.setFrom(sp); else -> { RoutePlan.points[i] = sp; RoutePlan.rev++ } }
                searchFor = null
                return@SearchScreen
            }
            MapPrefs.addRecent(ctx, MapPrefs.Spot(hit.name, hit.lat, hit.lon, hit.address))
            fly(hit.lat, hit.lon, if (hit.kind.startsWith("place:city")) 12.0 else if (hit.kind.startsWith("place:")) 14.0 else 17.0)
            val pl = places.firstOrNull { it.lat == hit.lat && it.lon == hit.lon && it.name == hit.name }
            open(MapTarget(hit.name, pl?.let { "Моя метка" } ?: hit.address, hit.lat, hit.lon, pl, if (hit.kind.startsWith("google:")) mapOf("google_id" to hit.kind.removePrefix("google:")) else emptyMap()))
        }
        MapSheet.COLLECTIONS -> CollectionsScreen(places, tracks, folders, showAll, onShowAll = { showAll = it; prefs.edit().putBoolean("show_collections", it).apply() },
            onDismiss = { sheet = MapSheet.NONE },
            onPlace = { pl -> sheet = MapSheet.NONE; flyAbove(pl.lat, pl.lon, 16.5); open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl)) },
            onTrack = { t -> sheet = MapSheet.NONE; target = null; pin(null, null); selTrack = t; fit(Osm.decode(t.points)) },
            onShowFolder = { pts -> sheet = MapSheet.NONE; if (!showAll) { showAll = true; prefs.edit().putBoolean("show_collections", true).apply() }; fit(pts) })
        MapSheet.LAYERS -> LayersPanel(onDismiss = { sheet = MapSheet.NONE }, onChanged = { styleTick++ }, onSettings = { settingsStart = "downloads"; sheet = MapSheet.SETTINGS })
        MapSheet.SETTINGS -> MapSettingsScreen(onDismiss = { sheet = MapSheet.NONE; settingsStart = null }, onChanged = { styleTick++ }, start = settingsStart)
        MapSheet.NONE -> {}
    }
    renameTrack?.let { t -> TrackEditor(t, folders) { renameTrack = null } }

    pickStop?.let { i ->
        MapPointPicker(onDismiss = { pickStop = null }) { (n, la, lo) ->
            val sp = MapPrefs.Spot(n, la, lo, "точка на карте")
            when (i) { -1 -> RoutePlan.addStop(sp); 0 -> RoutePlan.setFrom(sp); else -> { RoutePlan.points[i] = sp; RoutePlan.rev++ } }
            pickStop = null
        }
    }
}

/** Значения, которые меняются каждый кадр или каждую секунду. */
internal class MapLive {
    var bearing by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(12f)
    var scaleBar by mutableStateOf(60.dp to "")
    var speed by mutableStateOf<Float?>(null)
    var alt by mutableStateOf<Double?>(null)
}

@Composable
private fun SpeedAlt(live: MapLive) {
    val ctx = LocalContext.current
    val (sv, su) = live.speed?.let { MapPrefs.speed(ctx, it) } ?: ("—" to MapPrefs.speed(ctx, 0f).second)
    val (av, au) = live.alt?.let { MapPrefs.alt(ctx, it) } ?: ("—" to MapPrefs.alt(ctx, 0.0).second)
    Metric(sv, su)
    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(26.dp).background(Pal.Line))
    Metric(av, au)
}

@Composable
private fun ZoomText(live: MapLive) { Text("z %.1f".format(live.zoom), style = MaterialTheme.typography.labelSmall) }

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
private fun LocButton(mode: Int, live: MapLive, onClick: () -> Unit) {
    // поворот стрелки — в фазе рисования (graphicsLayer), без пересчёта кнопки на каждый кадр
    val bearingDiffers by remember { derivedStateOf { live.bearing != 0f } }
    Box(Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(if (mode != 0) Pal.Sky else Pal.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        when {
            mode == 2 -> Canvas(Modifier.size(28.dp).graphicsLayer { rotationZ = -live.bearing }) {
                val w = size.width; val h = size.height
                drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close() }, Color(0xFFE5484D))
                drawPath(Path().apply { moveTo(w / 2, h); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close() }, Pal.SkyInk)
            }
            mode == 0 && bearingDiffers -> Canvas(Modifier.size(28.dp).graphicsLayer { rotationZ = -live.bearing }) {
                val w = size.width; val h = size.height
                drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.66f, h / 2); lineTo(w * 0.34f, h / 2); close() }, Color(0xFFE5484D))
                drawPath(Path().apply { moveTo(w / 2, h); lineTo(w * 0.66f, h / 2); lineTo(w * 0.34f, h / 2); close() }, Pal.Muted)
            }
            else -> Icon(if (mode == 1) Icons.Rounded.Navigation else Icons.Rounded.NearMe, "Где я", tint = if (mode == 1) Pal.SkyInk else Pal.Ink, modifier = Modifier.rotate(if (mode == 1) 0f else 0f))
        }
    }
}

@Composable
private fun ScaleBar(live: MapLive) {
    val (width, label) = live.scaleBar
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


/** Красная метка как в Google Картах: капля с плавным остриём, тёмно-красная точка в центре, лёгкая тень. */
internal fun pinBitmap(d: Float): android.graphics.Bitmap {
    val w = (28 * d).toInt(); val h = (42 * d).toInt()
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f; val r = w / 2f - 1.5f * d; val cy = r + 1.5f * d; val tip = h - 2f * d
    // капля: верх — окружность, бока плавно сходятся к острию (кривые Безье), как у Google
    val path = android.graphics.Path().apply {
        moveTo(cx, tip)
        cubicTo(cx - r * 0.25f, tip - r * 0.55f, cx - r, cy + r * 0.75f, cx - r, cy)
        arcTo(android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false)
        cubicTo(cx + r, cy + r * 0.75f, cx + r * 0.25f, tip - r * 0.55f, cx, tip)
        close()
    }
    // тень у острия
    p.color = 0x33000000; c.drawOval(android.graphics.RectF(cx - 4f * d, tip - 1.5f * d, cx + 4f * d, tip + 2f * d), p)
    p.color = 0xFFEA4335.toInt(); c.drawPath(path, p)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xFFB31412.toInt(); c.drawPath(path, p)
    p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFB31412.toInt(); c.drawCircle(cx, cy, r * 0.36f, p)
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
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(10.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1F6F4A))
        .clickable(interactionSource = null, indication = null) {}.padding(14.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Pal.Card)
        .clickable(interactionSource = null, indication = null) {}.padding(14.dp),
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

/** Значок заведения: кружок цвета группы с белой иконкой Tabler — у всех одинаковый размер и стиль. */
internal fun poiBitmap(ctx: Context, cls: String, d: Float): android.graphics.Bitmap {
    MapStyle.SERVICE_ICON[cls]?.let { return serviceBitmap(ctx, it, d) }
    val g = MapStyle.groupOf(cls)
    val icon = MapStyle.CLASS_ICON[cls] ?: g.icon
    val size = (22 * d).toInt()
    val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = g.color; c.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * d, p)
    val paths = com.pank.vsevodnom.ui.money.TablerIcons.load(ctx).second[icon] ?: com.pank.vsevodnom.ui.money.TablerIcons.load(ctx).second["map-pin"].orEmpty()
    val s = size * 0.6f / 24f
    c.save(); c.translate(size * 0.2f, size * 0.2f); c.scale(s, s)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2.2f; p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
    p.color = android.graphics.Color.WHITE
    paths.forEach { dd -> runCatching { c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(dd), p) } }
    c.restore()
    return b
}


/** Белая стрелка-шеврон для направления трека (смотрит вдоль линии). */
internal fun arrowBitmap(d: Float): android.graphics.Bitmap {
    val w = (12 * d).toInt(); val h = (12 * d).toInt()
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    val path = android.graphics.Path().apply { moveTo(w * 0.3f, h * 0.18f); lineTo(w * 0.72f, h * 0.5f); lineTo(w * 0.3f, h * 0.82f) }
    c.drawPath(path, p)
    return b
}

/** Сервисный значок (ворота, урна, площадка…): без кружка — тёмная иконка с белой обводкой, меньше заведений. */
internal fun serviceBitmap(ctx: Context, icon: String, d: Float): android.graphics.Bitmap {
    val size = (19 * d).toInt()
    val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val paths = com.pank.vsevodnom.ui.money.TablerIcons.load(ctx).second[icon] ?: com.pank.vsevodnom.ui.money.TablerIcons.load(ctx).second["map-pin"].orEmpty()
    val s = size * 0.78f / 24f
    c.translate(size * 0.11f, size * 0.11f); c.scale(s, s)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    val parsed = paths.mapNotNull { dd -> runCatching { androidx.core.graphics.PathParser.createPathFromPathData(dd) }.getOrNull() }
    p.color = android.graphics.Color.WHITE; p.strokeWidth = 5.2f; parsed.forEach { c.drawPath(it, p) }       // обводка, чтобы читалось на любом фоне
    p.color = 0xFF4F5966.toInt(); p.strokeWidth = 2.1f; parsed.forEach { c.drawPath(it, p) }
    return b
}
