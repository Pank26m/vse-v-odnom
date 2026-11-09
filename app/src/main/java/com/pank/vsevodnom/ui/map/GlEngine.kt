package com.pank.vsevodnom.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pank.vsevodnom.data.*
import globus.glmap.*
import globus.glsearch.GLSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Основной движок — GLMap (тот же, что у Guru Maps): векторная карта, 3D-рельеф всегда включён (высота гор от уровня
 * моря видна при наклоне карты двумя пальцами), тени рельефа, горизонтали. Свои слои — треки (GLMapTrack, цвет по точкам),
 * метки (векторный слой со своим MapCSS), булавка, остановки и «я» — картинки.
 */
internal class GlEngine(private val ctx: Context, private val density: Float) : MapEngine {
    private val mapView = GLMapView(ctx)
    private val r: GLMapViewRenderer get() = mapView.renderer
    override val view get() = mapView
    override var gen by mutableIntStateOf(0); private set
    private lateinit var l: MapEngine.Listener
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var disposed = false

    // ---------- запуск ----------
    override fun start(l: MapEngine.Listener, lat: Double, lon: Double, zoom: Double) {
        this.l = l
        r.mapGeoCenter = MapGeoPoint(lat, lon)
        r.mapZoom = zoom
        // 3D всегда: рельеф поднимается от уровня моря по-настоящему (1:1), наклон — двумя пальцами вверх/вниз
        r.altitudeScale = 1f
        r.setAttributionPosition(GLMapViewRenderer.GLMapPlacement.BottomLeft)
        applyLocale()
        reloadStyle()
        r.setMapDidMoveCallback { main.post { moved() } }
        mapView.setOnTouchListener(touch)
        setupObjects()
        GlMap.allowOnline(Net.online)
        // центр карты сразу после открытия — для плашки «Скачать страну» (раньше появлялась только после сдвига карты)
        main.postDelayed(idle, 800)
    }

    private fun applyLocale() {
        val lang = MapStyle.opts(ctx).lang
        val order = when (lang) { "ru" -> arrayOf("ru", "uk", "native", "en"); "en" -> arrayOf("en", "native"); else -> arrayOf("native", "ru", "en") }
        runCatching { r.localeSettings = GlMap.localeOf(order) }
    }

    /** Опции стандартного стиля GLMap из наших настроек: способ передвижения → City/Outdoor + Car/Hike/Bike, группы значков → «Объекты на карте». */
    private fun styleOptions(o: MapStyle.Opts): Map<String, String> {
        val m = HashMap<String, String>()
        when (o.variant) {
            MapStyle.Variant.CAR -> { m["Style"] = "City"; m["SubStyle"] = "Car" }
            MapStyle.Variant.FOOT -> { m["Style"] = "Outdoor"; m["SubStyle"] = "Hike" }
            MapStyle.Variant.BIKE -> { m["Style"] = "Outdoor"; m["SubStyle"] = "Bike" }
        }
        val shown = MapStyle.POI_GROUPS.keys - o.hiddenPoi
        for ((opt, groups) in GL_GROUPS) if (groups.any { it in shown }) m[opt] = "1"
        return m
    }

    override fun reloadStyle() {
        val o = MapStyle.opts(ctx)
        applyLocale()
        runCatching {
            r.drawHillshades = o.hillshade
            r.drawElevationLines = o.contours
            r.drawSlopes = false
        }
        val ok = runCatching {
            GLMapStyleParser { name -> asset("DefaultStyle.bundle/$name") ?: asset(name) }.use { p ->
                p.setOptions(styleOptions(o), true)
                p.parseFromResources()?.use { st -> r.setStyle(st); true } ?: false
            }
        }.getOrDefault(false)
        if (ok) r.reloadTiles()
        gen++
    }

    private fun asset(path: String): ByteArray? = try { ctx.assets.open(path).use { it.readBytes() } } catch (_: Exception) { null }

    override fun onStart() { started = true; runCatching { r.setRenderingEnabled(true) }; if (locOn) startLoc() }
    override fun onStop() { started = false; runCatching { r.setRenderingEnabled(false) }; stopLoc() }
    override fun dispose() {
        if (disposed) return
        disposed = true; scope.cancel(); stopLoc(); main.removeCallbacksAndMessages(null)
        runCatching { mapView.dispose() }
    }

    // ---------- камера ----------
    override val zoom get() = runCatching { r.mapZoom }.getOrDefault(12.0)
    override val bearing get() = runCatching { norm(r.mapAngle) }.getOrDefault(0f)
    override fun center(): GeoPt? = runCatching { r.mapGeoCenter.let { GeoPt(it.lat, it.lon, 0) } }.getOrNull()

    private fun norm(a: Float) = ((a % 360f) + 360f) % 360f
    private fun mp(lat: Double, lon: Double) = MapPoint.CreateFromGeoCoordinates(lat, lon)

    /** Где на экране рисуется центр карты (origin задан от левого нижнего угла в долях экрана). */
    private var ox = 0.5f; private var oy = 0.5f
    private fun originPx() = mapView.width * ox to mapView.height * (1 - oy)

    /** Центр карты, при котором точка p окажется в точке экрана (ax, ay) при зуме z. */
    private fun centerFor(p: MapPoint, ax: Float, ay: Float, z: Double): MapPoint {
        val (cx, cy) = originPx()
        val d = runCatching { r.convertDisplayDeltaToInternal((cx - ax).toDouble(), (cy - ay).toDouble()) }.getOrNull() ?: return p
        val k = 2.0.pow(zoom - z)
        return MapPoint(p.x + d.x * k, p.y + d.y * k)
    }

    /** Сменить точку привязки центра без скачка картинки. */
    private fun setOrigin(x: Float, y: Float) {
        if (x == ox && y == oy) return
        val w = mapView.width; val h = mapView.height
        if (w == 0 || h == 0) { ox = x; oy = y; runCatching { r.setMapOrigin(x, y) }; return }
        val under = runCatching { r.convertDisplayToInternal((w * x).toDouble(), (h * (1 - y)).toDouble()) }.getOrNull()
        ox = x; oy = y
        runCatching { r.setMapOrigin(x, y); if (under != null) r.mapCenter = under }
    }

    private fun anim(sec: Double, block: (GLMapAnimation) -> Unit) = runCatching {
        r.animate { a -> a.setDuration(sec); a.setTransition(GLMapAnimation.EaseInOut); block(a) }
    }.getOrNull()

    override fun flyTo(lat: Double, lon: Double, zoom: Double?, above: Boolean) {
        val z = zoom ?: maxOf(this.zoom, 15.5)
        val p = mp(lat, lon)
        val c = if (above) centerFor(p, mapView.width / 2f, mapView.height * 0.275f, z) else centerFor(p, originPx().first, originPx().second, z)
        val far = center()?.let { Osm.distance(it, GeoPt(lat, lon, 0)) > 30_000 } ?: false
        anim(if (far) 1.2 else 0.65) { a ->
            if (far) a.flyToMode = GLMapAnimation.FlyToMode.Enabled
            r.mapZoom = z; r.mapCenter = c
        }
    }

    override fun fit(pts: List<GeoPt>, l: Int, t: Int, r0: Int, b: Int) {
        if (pts.size < 2) return
        val w = mapView.width; val h = mapView.height
        if (w == 0 || h == 0) return
        val bb = GLMapBBox().apply { pts.forEach { addPoint(mp(it.lat, it.lon)) } }
        val aw = (w - l - r0).coerceAtLeast(w / 4); val ah = (h - t - b).coerceAtLeast(h / 4)
        val z = runCatching { r.mapZoomForBBox(bb, aw, ah) }.getOrNull()?.takeIf { it.isFinite() }?.coerceIn(2.0, 18.0) ?: 15.0
        val c = centerFor(bb.center(), l + aw / 2f, t + ah / 2f, z)
        anim(0.7) { r.mapZoom = z; r.mapCenter = c }
    }

    override fun zoomBy(dz: Double) { val z = (zoom + dz).coerceIn(1.0, 20.0); anim(0.25) { r.mapZoom = z } }
    override fun rotate(bearing: Double, tilt: Double?, ms: Int) { anim(ms / 1000.0) { r.mapAngle = bearing.toFloat(); if (tilt != null) r.mapPitch = tilt.toFloat() } }
    override fun clearPadding() { if (Nav.session == null) setOrigin(0.5f, 0.5f) }

    // ---------- жесты ----------
    private var down = false
    private var dragged = false
    private var downX = 0f; private var downY = 0f
    private val slop = 10 * density
    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val g = runCatching { MapGeoPoint(r.convertDisplayToInternal(e.x.toDouble(), e.y.toDouble())) }.getOrNull() ?: return false
            l.onTap(e.x, e.y, g.lat, g.lon); return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (dragged) return
            val g = runCatching { MapGeoPoint(r.convertDisplayToInternal(e.x.toDouble(), e.y.toDouble())) }.getOrNull() ?: return
            l.onLongTap(g.lat, g.lon)
        }
    })
    @SuppressLint("ClickableViewAccessibility")
    private val touch = android.view.View.OnTouchListener { _, e ->
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; dragged = false; downX = e.x; downY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> { dragged = true; l.onGesture() }
            MotionEvent.ACTION_MOVE -> if (!dragged && hypot(e.x - downX, e.y - downY) > slop) {
                dragged = true; l.onGesture()
                // сдвинули карту одним пальцем — ведение за мной снимается (как в MapLibre); щипок и поворот его не снимают
                if (e.pointerCount == 1 && follow != 0) { follow = 0; compass.fine = false; l.onFollow(0) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; scheduleIdle() }
        }
        false     // жесты карты (сдвиг, щипок, поворот, наклон, зум одним пальцем) обрабатывает сам GLMap
    }

    private val idle = Runnable { onIdle() }
    private fun scheduleIdle() { main.removeCallbacks(idle); main.postDelayed(idle, 350) }
    private var lastCam = 0L
    private fun moved() {
        if (disposed) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCam >= 16) { lastCam = now; l.onCamera(bearing, zoom.toFloat(), metersPerPixel()) }
        labelsByZoom()
        scheduleIdle()
    }
    private fun onIdle() {
        if (disposed || down) return
        l.onCamera(bearing, zoom.toFloat(), metersPerPixel())
        // повернули чуть-чуть (до 10°) — выровнять на север; пока ведёт по компасу/курсу — не трогаем
        if (follow == 0 && nearNorth(bearing.toDouble())) anim(0.22) { r.mapAngle = 0f }
        center()?.let { l.onIdle(it.lat, it.lon, zoom) }
    }
    private fun metersPerPixel(): Double = runCatching {
        val w = mapView.width.toDouble(); val h = mapView.height.toDouble()
        val a = MapGeoPoint(r.convertDisplayToInternal(w / 2 - 50, h / 2)); val b = MapGeoPoint(r.convertDisplayToInternal(w / 2 + 50, h / 2))
        Osm.distance(GeoPt(a.lat, a.lon, 0), GeoPt(b.lat, b.lon, 0)) / 100.0
    }.getOrDefault(10.0).takeIf { it.isFinite() && it > 0 } ?: 10.0

    // ---------- моё место ----------
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val compass = SmoothCompass(ctx)
    private var locOn = false
    private var locRunning = false
    private var last: Location? = null
    private var heading = Float.NaN
    private var follow = 0
    private var navGps = false
    private var locAnim: GLMapAnimation? = null
    private val locListener = LocationListener { onLocation(it) }
    private val compassListener = object : org.maplibre.android.location.CompassListener {
        override fun onCompassChanged(userHeading: Float) { heading = userHeading; onHeading() }
        override fun onCompassAccuracyChange(compassStatus: Int) {}
    }

    override fun enableLocation() { locOn = true; if (started) startLoc() }

    @SuppressLint("MissingPermission")
    private fun startLoc() {
        if (locRunning || !hasLoc(ctx)) return
        locRunning = true
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) } ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocation(it) }
        }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper()) }
        runCatching { compass.addCompassListener(compassListener) }
    }
    private fun stopLoc() {
        if (!locRunning) return
        locRunning = false
        runCatching { lm.removeUpdates(locListener) }
        runCatching { compass.removeCompassListener(compassListener) }
    }

    private fun onLocation(loc: Location) {
        if (disposed) return
        val prev = last
        // сеть не перебивает свежий GPS
        if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER && prev.provider == LocationManager.GPS_PROVIDER &&
            loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos < 10_000_000_000L) return
        last = loc
        val p = mp(loc.latitude, loc.longitude)
        val first = prev == null || meDot?.isHidden != false
        locAnim?.cancel(false)
        if (first) {
            meDot?.position = p; meDot?.isHidden = false
            meCone?.position = p
            if (follow != 0) r.mapCenter = p
        } else locAnim = runCatching {
            r.animate { a ->
                a.setTransition(GLMapAnimation.Linear); a.setDuration(1.0)
                meDot?.let { a.setPosition(it, p) }; meCone?.let { a.setPosition(it, p) }
                if (follow != 0) {
                    r.mapCenter = p
                    if (follow == 2 && navGps && loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) r.mapAngle = loc.bearing
                }
            }
        }.getOrNull()
        onHeading()
    }

    private fun onHeading() {
        val course = last?.takeIf { navGps && follow == 2 && it.hasBearing() && it.hasSpeed() && it.speed > 1.5f }?.bearing
        val h = course ?: heading
        if (h.isNaN()) { meCone?.isHidden = true; return }
        meCone?.let { it.isHidden = meDot?.isHidden != false; it.angle = -h }
        // по компасу: карта поворачивается за телефоном
        if (follow == 2 && course == null && !down) runCatching { r.mapAngle = heading }
    }

    override fun location(): Location? = last

    override fun setFollow(mode: Int, zoom: Double?) {
        follow = mode
        compass.fine = mode == 2
        if (mode == 0) navGps = false
        val loc = last ?: return
        if (mode == 0) return
        val p = mp(loc.latitude, loc.longitude)
        val z = zoom
        val c = centerFor(p, originPx().first, originPx().second, z ?: this.zoom)
        anim(0.7) { if (z != null) r.mapZoom = z; r.mapCenter = c; if (mode == 2 && !heading.isNaN()) r.mapAngle = heading }
        l.onFollow(mode)
    }

    override fun zoomFollow(z: Double) { anim(1.2) { r.mapZoom = z } }

    override fun navCamera(z: Double, tilt: Double, gps: Boolean, changed: Boolean): Boolean {
        navGps = gps
        follow = 2; compass.fine = true
        val loc = last
        anim(if (changed) 0.9 else 1.2) {
            r.mapZoom = z; r.mapPitch = tilt.toFloat()
            if (changed && loc != null) r.mapCenter = mp(loc.latitude, loc.longitude)
        }
        return true
    }
    override fun navPadding(on: Boolean) = if (on) setOrigin(0.5f, 0.35f) else setOrigin(0.5f, 0.5f)
    override fun endNav() {
        setOrigin(0.5f, 0.5f)
        follow = 0; navGps = false; compass.fine = false
        anim(0.6) { r.mapPitch = 0f; r.mapAngle = 0f }
    }

    // ---------- свои объекты ----------
    private var meDot: GLMapImage? = null
    private var meCone: GLMapImage? = null
    private var pin: GLMapImage? = null

    private fun setupObjects() {
        runCatching {
            val cone = coneBitmap(density); val dot = meBitmap(density)
            meCone = GLMapImage(99).apply { setBitmap(cone); isHidden = true; rotatesWithMap = true; setOffset(cone.width / 2, cone.height / 2); r.add(this) }
            meDot = GLMapImage(100).apply { setBitmap(dot); setOffset(dot.width / 2, dot.height / 2); isHidden = true; r.add(this) }
            pin = GLMapImage(90).apply { val b = pinBitmap(density); setBitmap(b); setOffset(b.width / 2, 0); isHidden = true; r.add(this) }
        }
    }

    override fun setPin(lat: Double?, lon: Double?) {
        val p = pin ?: return
        if (lat == null || lon == null) { p.isHidden = true; return }
        p.position = mp(lat, lon); p.isHidden = false
    }

    /** Линия трека: точки с цветом (ARGB). */
    private fun trackData(pts: List<GeoPt>, colors: IntArray?, color: Int): GLMapTrackData =
        GLMapTrackData({ i, np -> val p = pts[i]; GLMapTrackData.setPointDataGeo(np, p.lat, p.lon, colors?.get(i) ?: color) }, pts.size)

    private val styles = HashMap<String, GLMapVectorStyle>()
    private fun st(css: String) = styles.getOrPut(css) { GLMapVectorStyle.createStyle(css)!! }

    /** Набор треков одного слоя (заменяется целиком). */
    private inner class Tracks(private val order: Int) {
        private val items = ArrayList<GLMapTrack>()
        var job: Job? = null
        fun clear() { items.forEach { runCatching { r.remove(it); it.dispose() } }; items.clear() }
        fun put(list: List<Triple<List<GeoPt>, IntArray?, Int>>, css: String) {
            job?.cancel()
            job = scope.launch {
                val data = withContext(Dispatchers.Default) { list.filter { it.first.size >= 2 }.map { (pts, cs, c) -> runCatching { trackData(pts, cs, c) }.getOrNull() } }
                clear()
                for (d in data) if (d != null) runCatching { GLMapTrack(order).apply { setData(d, st(css), null); r.add(this) } }.getOrNull()?.let { items += it }
            }
        }
    }
    private val tTracks = Tracks(10)
    private val tHlCase = Tracks(14); private val tHl = Tracks(15)
    private val tSelCase = Tracks(20); private val tSel = Tracks(21)
    private val tRec = Tracks(22)
    private val tDone = Tracks(28)
    private val tRouteCase = Tracks(29); private val tRoute = Tracks(30)

    private val ARROWS = "fill-image:\"trk-arrow.svg\";"
    private fun a(c: Int, alpha: Int) = (c and 0xFFFFFF) or (alpha shl 24)

    /** Для нажатий: точки треков в координатах карты (прорежены). */
    private var trackHits: List<Pair<Long, Array<MapPoint>>> = emptyList()
    private var selHits: List<Pair<Long, Array<MapPoint>>> = emptyList()
    private fun hitPts(items: List<OvLine>) = items.filter { it.pts.size >= 2 }.map { t ->
        val step = maxOf(1, t.pts.size / 400)
        t.id to (t.pts.indices step step).map { mp(t.pts[it].lat, t.pts[it].lon) }.plus(mp(t.pts.last().lat, t.pts.last().lon)).toTypedArray()
    }

    override fun setTracks(items: List<OvLine>) {
        tTracks.put(items.map { Triple(it.pts, null, a(it.color, 0xE6)) }, "{width:4pt;$ARROWS}")
        scope.launch { trackHits = withContext(Dispatchers.Default) { hitPts(items) } }
    }
    override fun setSelected(items: List<OvLine>) {
        val pts = ArrayList<GeoPt>(); val cs = ArrayList<Int>()
        items.forEach { s -> s.pts.forEach { pts += it; cs += a(s.color, 0xFF) } }
        val one = if (pts.size >= 2) listOf(Triple(pts as List<GeoPt>, cs.toIntArray(), 0)) else emptyList()
        tSelCase.put(one.map { Triple(it.first, null, a(0xFFFFFF, 0xF2)) }, "{width:13pt;}")
        tSel.put(one, "{width:8.5pt;$ARROWS}")
        scope.launch { selHits = withContext(Dispatchers.Default) { hitPts(items.take(1).map { it.copy(pts = pts) }) } }
    }
    override fun setRec(pts: List<GeoPt>) = tRec.put(listOf(Triple(pts, null, argb("#D8456A"))), "{width:5pt;}")
    override fun setRoute(pts: List<GeoPt>) {
        tRouteCase.put(listOf(Triple(pts, null, a(0xFFFFFF, 0xE6))), "{width:9pt;}")
        tRoute.put(listOf(Triple(pts, null, argb("#3E6FA8"))), "{width:6pt;$ARROWS}")
    }
    override fun setDone(pts: List<GeoPt>) = tDone.put(listOf(Triple(pts, null, a(0x9AA0A6, 0xD9))), "{width:6pt;}")
    override fun setHighlight(lines: List<List<GeoPt>>) {
        tHlCase.put(lines.map { Triple(it, null, a(0xFFFFFF, 0xE6)) }, "{width:9pt;}")
        tHl.put(lines.map { Triple(it, null, argb("#D9534F")) }, "{width:5pt;}")
    }

    // точки: метки, «похожие», линейка — векторный слой из GeoJSON со своим стилем
    private inner class Points(private val order: Int) {
        private var layer: GLMapVectorLayer? = null
        private var objs: GLMapVectorObjectList? = null
        fun put(geojson: String?, css: String) {
            runCatching {
                if (geojson == null) { layer?.isHidden = true; return }
                val list = GLMapVectorObject.createFromGeoJSONOrThrow(geojson)
                val lay = layer ?: GLMapVectorLayer(order).also { layer = it; r.add(it) }
                lay.setVectorObjects(list, GLMapVectorCascadeStyle.createStyle(css)!!, null)
                lay.isHidden = false
                objs?.dispose(); objs = list
            }
        }
        fun hide(h: Boolean) { layer?.isHidden = h }
    }
    private val pPlaces = Points(40)
    private val pSim = Points(41)
    private val pRuler = Points(42)
    private var placeHits: List<OvPlace> = emptyList()
    private var simHits: List<GeoHit> = emptyList()
    private var placesHaveLabels = false

    private fun q(s: String) = org.json.JSONObject.quote(s)
    private fun fc(feats: List<String>) = """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
    private fun feat(lat: Double, lon: Double, props: String) = """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{$props}}"""

    override fun setPlaces(items: List<OvPlace>) {
        placeHits = items
        if (items.isEmpty()) { pPlaces.put(null, ""); return }
        val colors = items.map { it.color and 0xFFFFFF }.distinct()
        val css = buildString {
            append("node{icon-image:\"u-dot.svg\";icon-scale:1;z-index:5;}")
            append("node|z11-{text:eval(tag('name'));text-color:#2B2A33;font-size:11.5;font-weight:bold;font-stroke-width:3pt;font-stroke-color:#FFFFFFE6;text-priority:20;}")
            colors.forEach { c -> append("node[c=c%06X]{icon-tint:#%06X;}".format(c, c)) }
        }
        pPlaces.put(fc(items.map { feat(it.lat, it.lon, "\"name\":${q(it.name)},\"c\":\"c%06X\"".format(it.color and 0xFFFFFF)) }), css)
    }
    override fun setSimilar(hits: List<GeoHit>) {
        simHits = hits
        if (hits.isEmpty()) { pSim.put(null, ""); return }
        pSim.put(fc(hits.map { feat(it.lat, it.lon, "\"name\":${q(it.name)}") }),
            "node{icon-image:\"u-dot.svg\";icon-tint:#F08C2E;icon-scale:1.1;text:eval(tag('name'));text-color:#9A4F0B;font-size:11;font-weight:bold;font-stroke-width:3pt;font-stroke-color:#FFFFFFE6;text-priority:21;}")
    }
    override fun setRuler(items: List<Pair<GeoPt, String>>) {
        if (items.isEmpty()) { pRuler.put(null, ""); return }
        pRuler.put(fc(items.map { (p, t) -> feat(p.lat, p.lon, "\"t\":${q(t)}") }),
            "node{text:eval(tag('t'));text-color:#3E6FA8;font-size:12.5;font-weight:bold;font-stroke-width:3pt;font-stroke-color:#FFFFFF;text-priority:30;}")
    }
    private fun labelsByZoom() {}

    // остановки маршрута — картинки (кружок с номером)
    private val stops = ArrayList<GLMapImage>()
    override fun setStops(items: List<OvStop>) {
        stops.forEach { runCatching { r.remove(it); it.dispose() } }; stops.clear()
        items.forEach { s -> runCatching {
            val b = stopBitmap(s.text, s.color, density)
            stops += GLMapImage(60).apply { setBitmap(b); setOffset(b.width / 2, b.height / 2); position = mp(s.lat, s.lon); r.add(this) }
        } }
    }

    // ---------- нажатия ----------
    private fun disp(p: MapPoint) = runCatching { r.convertInternalToDisplay(p) }.getOrNull()

    override fun userObjectAt(x: Float, y: Float): UserHit? {
        val rad = 22 * density
        fun near(lat: Double, lon: Double): Float? = disp(mp(lat, lon))?.let { p -> hypot(p.x - x, p.y - y).takeIf { it < rad } ?: (if (abs(p.x - x) < rad * 2 && y - p.y in 0f..rad * 1.4f) rad else null) }
        placeHits.mapNotNull { pl -> near(pl.lat, pl.lon)?.let { it to pl } }.minByOrNull { it.first }?.let { return UserHit.PlaceHit(it.second.id) }
        val tp = runCatching { r.convertDisplayToInternal(x.toDouble(), y.toDouble()) }.getOrNull() ?: return null
        val tol = runCatching { r.convertDisplayDeltaToInternal((18 * density).toDouble(), 0.0).let { hypot(it.x, it.y) } }.getOrNull() ?: return null
        fun onLine(hits: List<Pair<Long, Array<MapPoint>>>): Long? = hits.firstOrNull { (_, ps) -> (1 until ps.size).any { i -> segDist(tp, ps[i - 1], ps[i]) < tol } }?.first
        (onLine(selHits) ?: onLine(trackHits))?.let { return UserHit.TrackHit(it) }
        simHits.mapNotNull { h -> near(h.lat, h.lon)?.let { it to h } }.minByOrNull { it.first }?.let { (_, h) -> return UserHit.SimHit(h.lat, h.lon, h.name, h.address) }
        return null
    }
    private fun segDist(p: MapPoint, a: MapPoint, b: MapPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun GLMapVectorObject.tag(k: String): String? = runCatching { valueForKey(k)?.use { it.string } }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Линии объекта (дорожка, маршрут) в координатах. */
    private fun GLMapVectorObject.lines(): List<List<GeoPt>> = runCatching {
        val out = ArrayList<List<GeoPt>>()
        val tmp = MapPoint()
        val lc = lineCount
        if (lc <= 0) return@runCatching out
        for (li in 0 until lc) {
            val s = getLinePointStart(li); val n = getLinePointCount(li)
            out += (s until s + n).map { i -> getPointAtIndex(i, tmp); val g = MapGeoPoint(tmp); GeoPt(g.lat, g.lon, 0) }
        }
        out
    }.getOrDefault(emptyList())

    override fun featureAt(x: Float, y: Float, lat: Double, lon: Double): FeatureHit? {
        val loc = runCatching { r.localeSettings }.getOrNull() ?: GlMap.locale
        // заведение, остановка, вершина… — объект карты под пальцем
        val hit = runCatching { r.state?.use { s -> GLSearch.MapObjectNearPoint(s, x, y, (20 * density / 2.6).toDouble().coerceAtLeast(20.0)) } }.getOrNull()
        if (hit != null) return hit.use { o ->
            val props = HashMap<String, String>()
            for (k in TAGS) o.tag(k)?.let { props[k] = it }
            val key = PRIMARY.firstOrNull { props[it] != null }
            val v = key?.let { props[it] }
            if (v != null) { props["subclass"] = v; props["class"] = if (key == "shop") "shop" else v }
            val info = runCatching { GLSearch.GetDisplayInfo(o, loc) }.getOrNull()
            val title = info?.title?.let { runCatching { it.string }.getOrNull() }
            val cat = info?.category?.let { c -> runCatching { c.localizedName(loc)?.use { it.string } }.getOrNull() }
            runCatching { info?.close() }
            val name = runCatching { o.localizedName(loc)?.use { it.string } }.getOrNull()?.takeIf { it.isNotBlank() } ?: props["name"] ?: title
            name?.let { props["name"] = it }
            val kind = cat ?: MapNames.omt(props["class"], props["subclass"])
            val g = runCatching { MapGeoPoint(o.point()) }.getOrNull()
            val path = props["highway"] in PATHS
            if (o.lineCount > 0 && !path) return@use FeatureHit(MapTarget(name ?: kind.ifBlank { "Улица" }, kind, lat, lon, props = props))
            if (path) {
                val ln = o.lines()
                val nm = name ?: "Тропа без названия"
                return@use FeatureHit(MapTarget(nm, if (path) "Тропа · ${MapNames.omt(null, props["highway"]).lowercase()}" else kind, lat, lon,
                    props = props + ("kind" to "route")), ln.ifEmpty { null })
            }
            FeatureHit(MapTarget(name ?: kind.ifBlank { "Объект на карте" }, kind, g?.lat ?: lat, g?.lon ?: lon, props = props))
        }
        // пеший/вело маршрут (отношение OSM) рядом — показываем его целиком
        runCatching {
            r.state?.use { s ->
                val tp = r.convertDisplayToInternal(x.toDouble(), y.toDouble())
                val rels = GLSearch.RelationsNearPoint(s, tp, (24 * density).toDouble(), emptyArray()).orEmpty()
                val rel = rels.firstOrNull { it.tag("route") in setOf("hiking", "foot", "bicycle", "mtb", "nordic_walking") } ?: rels.firstOrNull()
                rel?.let { rr ->
                    val props = HashMap<String, String>()
                    for (k in TAGS + listOf("network", "ref", "distance", "from", "to", "operator", "osmc:symbol", "roundtrip")) rr.tag(k)?.let { props[k] = it }
                    val nm = runCatching { rr.localizedName(loc)?.use { it.string } }.getOrNull() ?: props["name"] ?: props["ref"] ?: "Маршрут"
                    props["name"] = nm
                    // линии маршрута целиком — только из скачанной карты (из онлайн-тайлов их не собрать)
                    val ln = GlMap.mapAt(lat, lon)?.takeIf { GlMap.sizeOnDisk(it) > 0 }?.let { mi -> runCatching { GLSearch.LoadMergedRelationLines(rr, true, mi)?.use { it.lines() } }.getOrNull() }.orEmpty()
                    val net = when (props["network"]) { "iwn" -> "международный"; "nwn" -> "национальный"; "rwn" -> "региональный"; "lwn" -> "местный"; else -> null }
                    rels.forEach { runCatching { it.dispose() } }
                    return FeatureHit(MapTarget(nm, listOfNotNull(if (props["route"] == "bicycle" || props["route"] == "mtb") "Веломаршрут" else "Пеший маршрут",
                        props["ref"]?.takeIf { it != nm }, net?.let { "$it маршрут" }).joinToString(" · "), lat, lon, props = props + ("kind" to "route")), ln.ifEmpty { null })
                }
            }
        }
        return null
    }

    companion object {
        /** Группы значков GLMap ← наши группы из «Объектов на карте». */
        val GL_GROUPS = mapOf(
            "Food" to listOf("food"), "Bar" to listOf("bars"), "Shop" to listOf("mall"), "AllShops" to listOf("shop"), "Beauty" to listOf("beauty"),
            "BuildingName" to listOf("housenumbers"), "CityService" to listOf("police", "office"), "Culture" to listOf("museum", "arts"),
            "Education" to listOf("edu"), "Entertaiment" to listOf("arts", "park"), "Financial" to listOf("bank"), "Hotel" to listOf("hotel"),
            "Medical" to listOf("health"), "Postal" to listOf("post"), "PublicTransport" to listOf("transit"), "Railway" to listOf("rail", "metro"),
            "Sport" to listOf("sport"))
        val PRIMARY = listOf("amenity", "shop", "tourism", "leisure", "historic", "office", "craft", "healthcare", "emergency", "natural", "railway",
            "public_transport", "aeroway", "aerialway", "man_made", "barrier", "sport", "highway", "place", "waterway", "landuse", "building")
        val TAGS = PRIMARY + listOf("name", "name:ru", "name:uk", "name:en", "opening_hours", "phone", "contact:phone", "website", "contact:website", "email",
            "wikipedia", "wikidata", "cuisine", "operator", "brand", "ele", "description", "addr:street", "addr:housenumber", "addr:city",
            "contact:instagram", "contact:facebook", "contact:telegram", "route", "surface", "sac_scale", "trail_visibility", "smoothness", "level", "station", "subway")
        val PATHS = setOf("footway", "path", "cycleway", "bridleway", "steps", "track", "pedestrian")
    }
}

/** «Я»: синяя точка в белом кольце с лёгкой тенью. */
internal fun meBitmap(d: Float): Bitmap {
    val s = (26 * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0x33000000; c.drawCircle(s / 2f, s / 2f + 1 * d, s / 2f - 1 * d, p)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, p)
    p.color = 0xFF3896FC.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 5 * d, p)
    return b
}

/** Конус направления (куда смотрит телефон / куда еду). Смотрит вверх. */
internal fun coneBitmap(d: Float): Bitmap {
    val s = (72 * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val cx = s / 2f; val cy = s / 2f
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.RadialGradient(cx, cy, s / 2f, 0x993896FC.toInt(), 0x003896FC, android.graphics.Shader.TileMode.CLAMP)
    val path = android.graphics.Path().apply {
        moveTo(cx, cy)
        arcTo(android.graphics.RectF(0f, 0f, s.toFloat(), s.toFloat()), -90f - 32f, 64f)
        close()
    }
    c.drawPath(path, p)
    return b
}

/** Кружок остановки маршрута с подписью (номер, буква, ★). */
internal fun stopBitmap(text: String, color: Int, d: Float): Bitmap {
    val s = (26 * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.color = color or 0xFF000000.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 2.5f * d, p)
    if (text.isNotEmpty()) {
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 11.5f * d; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText(text, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
    }
    return b
}
