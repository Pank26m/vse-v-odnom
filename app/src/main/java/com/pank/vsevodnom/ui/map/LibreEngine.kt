package com.pank.vsevodnom.ui.map

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pank.vsevodnom.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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

/** Запасной движок — MapLibre со своим стилем (MapStyle) и офлайн-картами стран из нашей сборки. */
internal class LibreEngine(private val ctx: Context, private val density: Float) : MapEngine {
    val mv: MapView = run { MapKit.init(ctx); MapView(ctx).apply { onCreate(null) } }
    override val view get() = mv
    override var gen by mutableIntStateOf(0); private set
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var l: MapEngine.Listener
    private val world = WorldToggle()
    private val poiIcons = HashMap<String, android.graphics.Bitmap>()
    private val poiPending = HashSet<String>()
    private val quickZoom = QuickZoom(mv, density) { map }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var follow = 0
    private var trackListener = false
    private val prefs = MapPrefs.p(ctx)

    override fun onStart() = mv.onStart()
    override fun onStop() = mv.onStop()
    override fun dispose() { scope.cancel(); mv.onDestroy() }

    override fun start(l: MapEngine.Listener, lat: Double, lon: Double, zoom: Double) {
        this.l = l
        mv.getMapAsync { m ->
            map = m
            m.cameraPosition = camera(lat, lon, zoom)
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionMargins((8 * density).toInt(), 0, 0, (4 * density).toInt())
            m.setMinZoomPreference(1.0)
            // жесты: свободный поворот, щипок 1:1 (без ускорения и без ограничения кадров — они давали рывки), свой зум одним пальцем
            tuneGestures(m, density)
            mv.setOnTouchListener(quickZoom)
            // редкие классы, которых нет в заготовке: пока стиль грузится, m.style == null — запоминаем и добавляем после загрузки
            mv.addOnStyleImageMissingListener { id ->
                if (!id.startsWith("p:")) return@addOnStyleImageMissingListener
                val st = m.style
                if (st == null) poiPending += id else runCatching { st.addImage(id, poiIcons.getOrPut(id) { poiBitmap(ctx, id.removePrefix("p:"), density) }) }
            }
            fun cam() {
                val c = m.cameraPosition; val la = c.target?.latitude ?: 50.0
                l.onCamera(c.bearing.toFloat(), c.zoom.toFloat(), m.projection.getMetersPerPixelAtLatitude(la))
            }
            m.addOnCameraMoveListener { cam(); world.check(m) }
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) l.onGesture() }
            m.addOnCameraIdleListener {
                cam(); world.check(m, force = true)
                // повернули чуть-чуть (до 10°) — выровнять на север; пока ведёт по компасу/курсу — не трогаем
                if (follow == 0 && !quickZoom.active && nearNorth(m.cameraPosition.bearing)) m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 220)
                val c = m.cameraPosition
                c.target?.let { t -> l.onIdle(t.latitude, t.longitude, c.zoom) }
            }
            m.addOnMapClickListener { ll -> val p = m.projection.toScreenLocation(ll); l.onTap(p.x, p.y, ll.latitude, ll.longitude); true }
            m.addOnMapLongClickListener { ll -> l.onLongTap(ll.latitude, ll.longitude); true }
            reloadStyle()
        }
    }

    override fun reloadStyle() {
        val m = map ?: return
        scope.launch {
            val opts = MapStyle.opts(ctx)
            style = null
            // все значки рисуем заранее (в фоне) и кладём в стиль сразу: раньше они рисовались по запросу во время зума,
            // а пока стиль догружался, запрос терялся — значки пропадали после любого переключения в «Объектах на карте»
            if (poiIcons.isEmpty()) poiIcons.putAll(withContext(Dispatchers.Default) {
                (MapStyle.POI_GROUPS.values.flatMap { it.classes } + MapStyle.CLASS_ICON.keys + MapStyle.SUBCLASS_ICONS).distinct().associate { c -> "p:$c" to poiBitmap(ctx, c, density) }
            })
            val json = withContext(Dispatchers.Default) { MapStyle.build(ctx, opts) }
            m.setStyle(Style.Builder().fromJson(json).withBitmapImages(*poiIcons.map { android.util.Pair(it.key, it.value) }.toTypedArray())) { st ->
                poiPending.toList().forEach { id -> runCatching { st.addImage(id, poiIcons.getOrPut(id) { poiBitmap(ctx, id.removePrefix("p:"), density) }) } }
                poiPending.clear()
                st.addImage("pin", pinBitmap(density)); st.addImage("trk-arrow", arrowBitmap(density))
                setupOverlays(st); style = st
                world.reset(if (opts.online == null && opts.worldOnline) MapStyle.rings(OfflineMaps.shapes(ctx).map { it.second }) else emptyList()); world.check(m, force = true)
                m.isDebugActive = prefs.getBoolean("dev_tiles", false)
                if (hasLoc(ctx)) { enableLocation(); setFollow(follow) }
                gen++
            }
        }
    }

    // ---------- камера ----------
    override val zoom get() = map?.cameraPosition?.zoom ?: 12.0
    override val bearing get() = map?.cameraPosition?.bearing?.toFloat() ?: 0f
    override fun center() = map?.cameraPosition?.target?.let { GeoPt(it.latitude, it.longitude, 0) }

    override fun flyTo(lat: Double, lon: Double, zoom: Double?, above: Boolean) {
        val m = map ?: return
        val z = zoom ?: maxOf(m.cameraPosition.zoom, 15.5)
        if (!above) { m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), 650); return }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(z).bearing(m.cameraPosition.bearing)
            .padding(0.0, 0.0, 0.0, mv.height * 0.45).build()), 650)
    }
    override fun fit(pts: List<GeoPt>, l: Int, t: Int, r: Int, b: Int) {
        val m = map ?: return
        if (pts.size < 2) return
        runCatching {
            val bb = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bb, l, t, r, b), 700)
        }
    }
    override fun zoomBy(dz: Double) { map?.animateCamera(if (dz > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), 250) }
    override fun rotate(bearing: Double, tilt: Double?, ms: Int) {
        val m = map ?: return
        if (tilt == null) m.animateCamera(CameraUpdateFactory.bearingTo(bearing), ms)
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(bearing).tilt(tilt).build()), ms)
    }
    override fun clearPadding() {
        map?.let { m -> if ((m.cameraPosition.padding?.getOrNull(3) ?: 0.0) > 0) m.easeCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0), 300) }
    }

    // ---------- моё место ----------
    override fun enableLocation() {
        val m = map ?: return; val st = style ?: return
        MapKit.enableLocation(ctx, m, st, false)
        if (!trackListener) runCatching {
            trackListener = true
            m.locationComponent.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { setFine(0); follow = 0; l.onFollow(0) }
                override fun onCameraTrackingChanged(currentMode: Int) {
                    val f = when (currentMode) { CameraMode.TRACKING_COMPASS, CameraMode.TRACKING_GPS -> 2; CameraMode.TRACKING -> 1; else -> 0 }
                    setFine(f); follow = f; l.onFollow(f)
                }
            })
        }
    }
    private fun setFine(mode: Int) { runCatching { (map?.locationComponent?.compassEngine as? SmoothCompass)?.fine = mode == 2 } }
    override fun location() = runCatching { map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation }.getOrNull()
    override fun setFollow(mode: Int, zoom: Double?) {
        follow = mode
        val lc = runCatching { map!!.locationComponent.takeIf { it.isLocationComponentActivated } }.getOrNull() ?: return
        runCatching {
            lc.cameraMode = when (mode) { 1 -> CameraMode.TRACKING; 2 -> CameraMode.TRACKING_COMPASS; else -> CameraMode.NONE }
            if (zoom != null && mode != 0) lc.zoomWhileTracking(zoom, 700)
        }
        setFine(mode)
    }
    override fun zoomFollow(z: Double) { runCatching { map?.locationComponent?.zoomWhileTracking(z, 1200) } }
    override fun navCamera(z: Double, tilt: Double, gps: Boolean, changed: Boolean): Boolean {
        val lc = runCatching { map!!.locationComponent }.getOrNull() ?: return false
        val cam = if (gps) CameraMode.TRACKING_GPS else CameraMode.TRACKING_COMPASS
        return runCatching {
            if (changed) {
                // режим, зум и наклон — одним переходом: отдельный zoomWhileTracking во время смены режима MapLibre пропускает
                lc.renderMode = if (gps) RenderMode.GPS else RenderMode.COMPASS
                lc.setCameraMode(cam, 900L, z, null, tilt, null)
            } else {
                lc.zoomWhileTracking(z, 1200)
                lc.tiltWhileTracking(tilt, 1200)
            }
            follow = 2; setFine(2)
        }.isSuccess
    }
    override fun navPadding(on: Boolean) { runCatching { map!!.moveCamera(CameraUpdateFactory.paddingTo(0.0, if (on) mv.height * 0.35 else 0.0, 0.0, 0.0)) } }
    override fun endNav() {
        navPadding(false)
        runCatching {
            val lc = map!!.locationComponent
            lc.renderMode = RenderMode.COMPASS; lc.cameraMode = CameraMode.NONE; follow = 0
            map!!.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map!!.cameraPosition).tilt(0.0).bearing(0.0).build()), 600)
        }
    }

    // ---------- свои слои ----------
    private fun set(id: String, f: List<Feature>) { style?.setFeatures(id, f) }
    private fun line(pts: List<GeoPt>): LineString = LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })
    private fun pt(lat: Double, lon: Double) = Feature.fromGeometry(Point.fromLngLat(lon, lat))

    override fun setPlaces(items: List<OvPlace>) = set("u-places", items.map { p ->
        pt(p.lat, p.lon).also { it.addNumberProperty("id", p.id); it.addStringProperty("name", p.name); it.addStringProperty("color", hex(p.color)) } })
    private fun lines(items: List<OvLine>) = items.filter { it.pts.size >= 2 }.map { t ->
        Feature.fromGeometry(line(t.pts)).also { it.addNumberProperty("id", t.id); it.addStringProperty("color", hex(t.color)) } }
    override fun setTracks(items: List<OvLine>) = set("u-tracks", lines(items))
    override fun setSelected(items: List<OvLine>) = set("u-sel", lines(items))
    override fun setRec(pts: List<GeoPt>) = set("u-rec", if (pts.size >= 2) listOf(Feature.fromGeometry(line(pts))) else emptyList())
    override fun setRoute(pts: List<GeoPt>) = set("u-route", if (pts.size >= 2) listOf(Feature.fromGeometry(line(pts))) else emptyList())
    override fun setDone(pts: List<GeoPt>) = set("u-done", if (pts.size >= 2) listOf(Feature.fromGeometry(line(pts))) else emptyList())
    override fun setStops(items: List<OvStop>) = set("u-stops", items.map { s -> pt(s.lat, s.lon).also { it.addStringProperty("t", s.text); it.addStringProperty("c", hex(s.color)) } })
    override fun setRuler(items: List<Pair<GeoPt, String>>) = set("u-ruler", items.map { (p, t) -> pt(p.lat, p.lon).also { it.addStringProperty("t", t) } })
    override fun setSimilar(hits: List<GeoHit>) = set("u-sim", hits.map { h -> pt(h.lat, h.lon).also { it.addStringProperty("name", h.name); it.addStringProperty("kind", h.address) } })
    override fun setPin(lat: Double?, lon: Double?) = set("u-pin", if (lat == null || lon == null) emptyList() else listOf(pt(lat, lon)))
    override fun setHighlight(lines: List<List<GeoPt>>) = set("u-hl", lines.filter { it.size >= 2 }.map { Feature.fromGeometry(line(it)) })

    // ---------- нажатия ----------
    private fun box(x: Float, y: Float, k: Float = 1f): RectF { val r = 18 * density * k; return RectF(x - r, y - r, x + r, y + r) }

    override fun userObjectAt(x: Float, y: Float): UserHit? {
        val m = map ?: return null
        val b = box(x, y)
        m.queryRenderedFeatures(b, "u-places-c", "u-places-t").firstOrNull()?.getNumberProperty("id")?.toLong()?.let { return UserHit.PlaceHit(it) }
        m.queryRenderedFeatures(b, "u-tracks-l", "u-sel-l2").firstOrNull()?.getNumberProperty("id")?.toLong()?.let { return UserHit.TrackHit(it) }
        m.queryRenderedFeatures(b, "u-sim-c", "u-sim-t").firstOrNull()?.let { f ->
            val g = f.geometry() as? Point ?: return null
            return UserHit.SimHit(g.latitude(), g.longitude(), f.getStringProperty("name") ?: "", f.getStringProperty("kind") ?: "")
        }
        return null
    }

    private fun props(f: Feature) = f.properties()?.entrySet()?.associate { it.key to (runCatching { it.value.asString }.getOrDefault(it.value.toString())) }.orEmpty()
    private fun geoLines(parts: List<Feature>): List<List<GeoPt>> = parts.flatMap { f -> when (val g = f.geometry()) {
        is LineString -> listOf(g.coordinates()); is org.maplibre.geojson.MultiLineString -> g.coordinates(); else -> emptyList() } }
        .map { cs -> cs.map { GeoPt(it.latitude(), it.longitude(), 0) } }

    override fun featureAt(x: Float, y: Float, lat: Double, lon: Double): FeatureHit? {
        val m = map ?: return null
        val b = box(x, y); val r = 18 * density
        val all = m.queryRenderedFeatures(b)
        // значки (с названием или без: ворота, урны, площадки — тоже открываются), потом тропы, потом подписи улиц и т. п.
        val pointF = all.firstOrNull { it.geometry() is Point && (it.hasNonNullValueForProperty("name") || it.hasNonNullValueForProperty("class")) }
        if (pointF == null) {
            val routeIds = m.style?.layers?.map { it.id }?.filter { it.startsWith("route@") || it.startsWith("route-n@") }.orEmpty()
            val rf = if (routeIds.isEmpty()) null else m.queryRenderedFeatures(RectF(b.left - r / 2, b.top - r / 2, b.right + r / 2, b.bottom + r / 2), *routeIds.toTypedArray()).firstOrNull()
            if (rf != null) {
                val nm = rf.getStringProperty("name") ?: "Тропа"
                val net = when (rf.getStringProperty("network")) { "iwn" -> "международный"; "nwn" -> "национальный"; "rwn" -> "региональный"; "lwn" -> "местный"; else -> null }
                val parts = runCatching { m.style?.sources?.filter { it.id.startsWith("lines_") }?.flatMap { src ->
                    (src as? org.maplibre.android.style.sources.VectorSource)?.querySourceFeatures(arrayOf("route"), Expression.eq(Expression.get("name"), nm)).orEmpty()
                } }.getOrNull().orEmpty()
                return FeatureHit(MapTarget(nm, listOfNotNull("Пеший маршрут", rf.getStringProperty("ref")?.takeIf { it.isNotBlank() }, net?.let { "$it маршрут" }).joinToString(" · "),
                    lat, lon, props = props(rf) + ("kind" to "route")), geoLines(parts))
            }
        }
        val f = pointF ?: all.firstOrNull { it.hasNonNullValueForProperty("name") }
        // тропинка/дорожка (пунктир): название берём с неё или с подписи рядом; подсвечиваем всю, сведения — OSM и Википедия
        fun isPath(x: Feature) = x.geometry() !is Point && (x.getStringProperty("class") in setOf("path", "track", "pedestrian") || x.getStringProperty("subclass") in setOf("footway", "path", "cycleway", "bridleway", "steps"))
        val pathF = if (pointF == null) all.firstOrNull { isPath(it) } else null
        if (pathF != null) {
            val big = RectF(b.left - r * 2, b.top - r * 2, b.right + r * 2, b.bottom + r * 2)
            val nm = pathF.getStringProperty("name") ?: m.queryRenderedFeatures(big).firstOrNull { isPath(it) && it.hasNonNullValueForProperty("name") }?.getStringProperty("name")
            val pr = props(pathF)
            val kindTxt = MapNames.omt(pr["class"], pr["subclass"]).lowercase().ifBlank { "тропа" }
            val parts = if (nm == null) listOf(Feature.fromGeometry(pathF.geometry())) else runCatching { m.style?.sources?.filter { it.id.startsWith("omt_") || it.id == "ofm" }?.flatMap { src ->
                (src as? org.maplibre.android.style.sources.VectorSource)?.querySourceFeatures(arrayOf("transportation_name"), Expression.eq(Expression.get("name"), nm)).orEmpty()
            } }.getOrNull().orEmpty().ifEmpty { listOf(Feature.fromGeometry(pathF.geometry())) }
            return FeatureHit(MapTarget(nm ?: "Тропа без названия", "Тропа · $kindTxt", lat, lon, props = pr + ("kind" to "route") + (if (nm != null) mapOf("name" to nm) else emptyMap())), geoLines(parts))
        }
        if (f == null) return null
        val pr = props(f)
        val g = f.geometry()
        val (la, lo) = if (g is Point) g.latitude() to g.longitude() else lat to lon
        val kind = MapNames.omt(pr["class"], pr["subclass"])
        val nm = pr["name:ru"]?.takeIf { MapStyle.opts(ctx).lang == "ru" } ?: pr["name"] ?: kind.ifBlank { "Объект на карте" }
        return FeatureHit(MapTarget(nm, kind, la, lo, props = pr))
    }
}

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
