package com.pank.vsevodnom.ui.map

import android.Manifest
import android.content.Context
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
    /** Сменили движок карты или ключ — экран карты пересоздаётся. */
    var engineTick by mutableIntStateOf(0)
}
data class MapRequest(val trackId: Long? = null, val lat: Double = 0.0, val lon: Double = 0.0, val name: String = "", val go: Boolean = false)

@Composable
fun MapScreen(onBack: () -> Unit) { key(MapUi.engineTick) { MapScreenBody(onBack) } }

@Composable
private fun MapScreenBody(@Suppress("UNUSED_PARAMETER") onBack: () -> Unit) {
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
    var pickStop by remember { mutableStateOf<Int?>(null) }          // выбор точки маршрута на карте: индекс или −1 — новая остановка
    var searchFor by remember { mutableStateOf<Int?>(null) }        // поиск для точки маршрута: индекс или −1 — новая остановка
    var trackColor by remember { mutableStateOf("") }      // раскраска выбранного трека: alt | speed | slope
    val nav = Nav.session
    var renameTrack by remember { mutableStateOf<Track?>(null) }
    val rec by TrackService.recording.collectAsState()
    val recPts by TrackService.points.collectAsState()
    val recDist by TrackService.distance.collectAsState()
    val lastSaved by TrackService.lastSaved.collectAsState()

    // движок карты: GLMap (3D-рельеф) или запасной MapLibre — по настройке
    val eng = rememberEngine()
    val gen = eng.gen
    // часто меняющиеся значения (каждый кадр жеста / каждую секунду) — в отдельном объекте: их читают только маленькие
    // блоки (линейка, «где я», скорость), и весь экран карты при жесте не пересчитывается
    val live = remember { MapLive() }
    var myLoc by remember { mutableStateOf<GeoPt?>(null) }
    var locMode by remember { mutableIntStateOf(0) }       // 0 — свободно, 1 — за мной, 2 — по компасу
    var rerouting by remember { mutableStateOf(false) }
    var rerouteAt by remember { mutableStateOf<GeoPt?>(null) }
    var clean by remember { mutableStateOf(false) }          // «рамка»: все кнопки спрятаны, вернуть — вкладка «Карта» внизу
    var frameMenu by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }       // навигация: показан весь маршрут
    var navFreeAt by remember { mutableLongStateOf(0L) }       // навигация: когда карту тронули рукой
    var similar by remember { mutableStateOf<Pair<String, List<GeoHit>>?>(null) }
    var simIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(MapUi.reselect) { if (MapUi.reselect > 0) { clean = false; frameMenu = false } }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLoc(ctx)) eng.enableLocation()
    }
    fun askLoc() = locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    fun unfollow() { if (locMode != 0) { eng.setFollow(0); locMode = 0 } }
    fun fly(lat: Double, lon: Double, z: Double? = null) { unfollow(); eng.flyTo(lat, lon, z) }
    /** Показать точку выше центра — низ экрана занят карточкой места. */
    fun flyAbove(lat: Double, lon: Double, z: Double? = null) { unfollow(); eng.flyTo(lat, lon, z, above = true) }
    fun fit(pts: List<GeoPt>) {
        if (pts.size == 1) { fly(pts[0].lat, pts[0].lon); return }
        if (pts.size < 2) return
        eng.fit(pts, (90 * density).toInt(), (190 * density).toInt(), (90 * density).toInt(), (200 * density).toInt())
    }
    fun pin(lat: Double?, lon: Double?) = eng.setPin(lat, lon)
    fun open(t: MapTarget) { selTrack = null; target = t; pin(if (t.place == null) t.lat else null, t.lon); if (t.props["kind"] != "route") eng.setHighlight(emptyList()) }
    /** Подсветить тропу целиком и показать её всю (низ экрана — под карточкой). */
    fun highlight(lines: List<List<GeoPt>>) {
        eng.setHighlight(lines)
        val pts = lines.flatten()
        if (pts.size >= 2) eng.fit(pts, (50 * density).toInt(), (130 * density).toInt(), (50 * density).toInt(), (eng.view.height * 0.5).toInt())
    }
    fun closeAll() {
        target = null; selTrack = null; pin(null, null); eng.setHighlight(emptyList()); eng.setSimilar(emptyList()); similar = null
        eng.clearPadding()
    }

    // ---------- карта ----------
    LaunchedEffect(Unit) {
        OnlineMaps.applyCacheLimit(ctx)
        launch { OfflineMaps.loadCatalog(ctx); OfflineMaps.resume(ctx); OfflineMaps.autoWorld(ctx); OfflineMaps.ensureExtras(ctx) }
        eng.start(object : MapEngine.Listener {
            override fun onCamera(bearing: Float, zoom: Float, metersPerPixel: Double) {
                live.bearing = bearing; live.zoom = zoom
                val maxM = metersPerPixel * 84 * density
                if (!maxM.isFinite() || maxM <= 0) return
                val p = 10.0.pow(floor(log10(maxM)))
                val nice = listOf(5.0, 2.0, 1.0).map { it * p }.first { it <= maxM }
                val sb = (nice / metersPerPixel / density).dp to MapPrefs.dist(ctx, nice)
                if (sb != live.scaleBar) live.scaleBar = sb
            }
            override fun onIdle(lat: Double, lon: Double, zoom: Double) {
                live.center = GeoPt(lat, lon, 0)
                prefs.edit().putFloat("zoom", zoom.toFloat()).putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
            }
            // в навигации: тронул карту рукой — ведение на паузе, через 8 с без касаний вернётся само
            override fun onGesture() { if (Nav.session != null) navFreeAt = System.currentTimeMillis() }
            override fun onFollow(mode: Int) { locMode = mode }
            override fun onLongTap(lat: Double, lon: Double) = open(MapTarget("Точка на карте", "", lat, lon))
            override fun onTap(x: Float, y: Float, lat: Double, lon: Double) {
                when (val u = eng.userObjectAt(x, y)) {
                    is UserHit.PlaceHit -> { AppScope.io.launch {
                        val pl = AppDb.get(ctx).map().placesNow().firstOrNull { it.id == u.id } ?: return@launch
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { open(MapTarget(pl.name, "Моя метка", pl.lat, pl.lon, pl)) }
                    }; return }
                    is UserHit.TrackHit -> { AppScope.io.launch {
                        val t = AppDb.get(ctx).map().track(u.id) ?: return@launch
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { target = null; pin(null, null); selTrack = t }
                    }; return }
                    is UserHit.SimHit -> {
                        similar?.second?.indexOfFirst { it.lat == u.lat && it.lon == u.lon }?.takeIf { it >= 0 }?.let { simIdx = it }
                        open(MapTarget(u.name, u.kind, u.lat, u.lon)); return
                    }
                    null -> {}
                }
                if (target != null || selTrack != null) { closeAll(); return }
                val f = eng.featureAt(x, y, lat, lon) ?: return
                open(f.target)
                f.lines?.let { pin(null, null); highlight(it) }
            }
        }, prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble(), prefs.getFloat("zoom", 11f).toDouble())
    }
    // стиль заново: настройки, «Объекты на карте», скачанные карты (у GLMap — и его карты)
    var firstStyle by remember { mutableStateOf(true) }
    LaunchedEffect(styleTick, OfflineMaps.version) { if (firstStyle) firstStyle = false else eng.reloadStyle() }
    LaunchedEffect(gen) {
        if (gen == 0) return@LaunchedEffect
        if (hasLoc(ctx)) { eng.enableLocation(); if (locMode != 0) eng.setFollow(locMode) }
        else if (!prefs.getBoolean("asked_loc", false)) { prefs.edit().putBoolean("asked_loc", true).apply(); askLoc() }
    }
    LaunchedEffect(Net.online) { if (eng is GlEngine) { GlMap.allowOnline(Net.online); if (Net.online && GlMap.listEmpty()) GlMap.refreshList() } }

    /** Камера в навигации: зум и наклон по способу и скорости; пешком и на месте — по компасу, на ходу — по курсу GPS. */
    var navCam by remember { mutableStateOf(Triple(0.0, 0.0, -1)) }      // зум, наклон, режим камеры (1 — по курсу, 0 — по компасу)
    fun navCamera(mode: String, kmh: Float) {
        if (locMode == 0 && navCam.third != -1) return      // сдвинули карту рукой — не мешаем, вернёт кнопка «где я»
        val z = when (mode) {
            "car" -> when { kmh > 90 -> 15.2; kmh > 60 -> 15.8; kmh > 30 -> 16.4; else -> 17.0 }
            "bike" -> if (kmh > 18) 16.6 else 17.2
            else -> 17.8
        }
        val tilt = when (mode) { "car" -> 55.0; "bike" -> 45.0; else -> 35.0 }
        val gps = !(mode == "foot" || kmh < 6)
        val cam = if (gps) 1 else 0
        val changed = cam != navCam.third
        if (!changed && abs(z - navCam.first) <= 0.25 && abs(tilt - navCam.second) <= 1) return
        if (eng.navCamera(z, tilt, gps, changed)) { navCam = Triple(z, tilt, cam); locMode = 2 }
    }
    fun navFollow() {
        val s = Nav.session ?: return
        overview = false; navCam = Triple(0.0, 0.0, -1)
        // точка «я» — в нижней трети экрана: впереди видно больше дороги
        eng.navPadding(true)
        navCamera(s.mode, live.speed ?: 0f)
    }
    fun navOverview() {
        val s = Nav.session ?: return
        overview = true
        eng.setFollow(0); locMode = 0; eng.navPadding(false)
        eng.fit(listOfNotNull(myLoc) + s.route.points, (60 * density).toInt(), (190 * density).toInt(), (60 * density).toInt(), (170 * density).toInt())
    }

    // скорость, высота, автомасштаб
    LaunchedEffect(gen) {
        while (true) {
            eng.location()?.let { l ->
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
                        eng.setRoute(listOf(myLoc!!) + s.route.points.drop(s.seg + 1))
                        eng.setDone(if (s.seg >= 0) s.route.points.take(s.seg + 1) + myLoc!! else emptyList())
                        if (!overview) navCamera(s.mode, speed ?: 0f)
                    }
                    if (needReroute && !rerouting) rerouteAt = myLoc
                } else if (locMode != 0 && MapPrefs.bool(ctx, "autozoom", true)) {
                    val s = speed ?: 0f
                    val z = when { s > 90 -> 13.5; s > 55 -> 14.5; s > 25 -> 15.5; s > 8 -> 16.3; else -> null }
                    if (z != null && abs(z - eng.zoom) > 0.2) eng.zoomFollow(z)
                }
            }
            delay(1000)
        }
    }
    // данные пользователя на карте
    val visF = remember(folders) { visibleFolders(folders) }
    LaunchedEffect(gen, places, visF, showAll) {
        eng.setPlaces(if (!showAll) emptyList() else places.filter { it.visible && (it.folderId == null || it.folderId in visF) }.map { pl ->
            OvPlace(pl.id, pl.lat, pl.lon, pl.name, colorOr(pl.color, PLACE_COLOR)) })
    }
    LaunchedEffect(gen, tracks, visF, showAll, RoutePlan.editTrack, RoutePlan.active) {
        // правленый трек прячем — на его месте новый маршрут планировщика
        // (запись GPS/импорт сами не перезаписываются — их не прячем)
        val hide = RoutePlan.editTrack?.takeIf { RoutePlan.active && routeWay(it) != null }?.id
        val list = if (!showAll) emptyList() else tracks.filter { it.visible && (it.folderId == null || it.folderId in visF) && it.id != hide }
        val lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            list.mapNotNull { t -> Osm.decode(t.points).takeIf { it.size >= 2 }?.let { OvLine(t.id, it, colorOr(t.color, TRACK_COLOR)) } }
        }
        eng.setTracks(lines)
    }
    // трек без высот (построенный маршрут, импорт) — один раз берём высоты из рельефа и сохраняем
    LaunchedEffect(selTrack?.id) {
        val t = selTrack ?: return@LaunchedEffect
        val nt = Dem.ensure(ctx, t)
        if (nt !== t && selTrack?.id == t.id) selTrack = nt
    }
    LaunchedEffect(gen, selTrack, trackColor) {
        val t = selTrack; val pts = t?.let { Osm.decode(it.points) }.orEmpty()
        val base = t?.let { colorOr(it.color, TRACK_COLOR) } ?: 0
        val segs = if (pts.size < 2) emptyList() else if (trackColor.isEmpty()) listOf(OvLine(t!!.id, pts, base))
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // раскраска по высоте / скорости / уклону: отрезки цветом от зелёного к красному
            val v = TrackMath.values(TrackMath.stats(pts), trackColor)
            val ok = v.filter { !it.isNaN() }.sorted()
            if (ok.size < 2) return@withContext listOf(OvLine(t!!.id, pts, base))
            val lo = ok[(ok.size * 0.03).toInt()]; val hi = ok[(ok.size * 0.97).toInt().coerceAtMost(ok.size - 1)].coerceAtLeast(lo + 0.1)
            val step = maxOf(1, pts.size / 600)
            (0 until pts.size - 1 step step).map { i ->
                val j = minOf(i + step, pts.size - 1)
                val c = TrackMath.ramp(((v[i].takeIf { !it.isNaN() } ?: lo) - lo) / (hi - lo))
                OvLine(t!!.id, pts.subList(i, j + 1), colorOr(c, TRACK_COLOR))
            }
        }
        eng.setSelected(segs)
    }
    LaunchedEffect(gen, recPts) { eng.setRec(if (recPts.size >= 2) recPts else emptyList()) }
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
    LaunchedEffect(gen, RoutePlan.route, RoutePlan.rev, RoutePlan.active) {
        if (gen == 0 || Nav.session != null) return@LaunchedEffect
        val rt = RoutePlan.route.takeIf { RoutePlan.active }
        eng.setRoute(if (rt != null && rt.points.size >= 2) rt.points else emptyList())
        eng.setStops(if (!RoutePlan.active) emptyList() else RoutePlan.points.mapIndexedNotNull { i, sp -> sp?.let {
            OvStop(it.lat, it.lon, when (i) { 0 -> ""; RoutePlan.points.size - 1 -> "★"; else -> RoutePlan.label(i - 1) },
                argb(when (i) { 0 -> "#3E6FA8"; RoutePlan.points.size - 1 -> "#E5484D"; else -> "#E09A1B" }))
        } })
        eng.setRuler(if (rt?.source == "line") rulerMarks(ctx, rt.points) else emptyList())
    }
    // пришло извне: ссылка Google Карт → метка, файл .ms / GPX / KML
    LaunchedEffect(IncomingGeo.message) {
        IncomingGeo.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); IncomingGeo.message = null; styleTick++ }
    }
    LaunchedEffect(IncomingGeo.open, gen) {
        val q = IncomingGeo.open ?: return@LaunchedEffect
        if (gen == 0) return@LaunchedEffect
        IncomingGeo.open = null
        fly(q.lat, q.lon, 16.5)
        val pl = q.placeId?.let { id -> dao.placesNow().firstOrNull { it.id == id } }
        open(MapTarget(q.name, if (pl != null) "Моя метка" else "", q.lat, q.lon, pl, q.googleId?.let { mapOf("google_id" to it) } ?: emptyMap()))
    }
    // трек сохранён: если автосохранение выключено — спросить имя и папку
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
        eng.view.keepScreenOn = true
        eng.setDone(emptyList())
        navFollow()
        Nav.say("Поехали. " + Nav.distText(r.meters).replace(",", " и "))
    }
    fun stopNav() {
        Nav.session = null; Nav.status = null; eng.view.keepScreenOn = false; overview = false
        eng.setDone(emptyList())
        eng.endNav(); locMode = 0
        eng.setRoute(emptyList())
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
    LaunchedEffect(MapUi.request, gen) {
        val q = MapUi.request ?: return@LaunchedEffect
        if (gen == 0) return@LaunchedEffect
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
        if (gen == 0) return
        if (!hasLoc(ctx)) { askLoc(); return }
        val rotated = abs(((live.bearing + 540f) % 360f) - 180f) > 1.5f
        // в навигации: карта сдвинута/повёрнута рукой — первое нажатие ставит север, второе возвращает к ведению
        if (Nav.session != null) {
            if (locMode == 0 && rotated) { eng.rotate(0.0, null, 350); navFreeAt = System.currentTimeMillis(); return }
            navFollow(); return
        }
        eng.enableLocation()
        if (eng.location() == null) Toast.makeText(ctx, "Ищу спутники…", Toast.LENGTH_SHORT).show()
        when {
            // карта повёрнута и я не «привязан» — сперва просто на север
            locMode == 0 && rotated -> eng.rotate(0.0, null, 350)
            locMode == 0 -> { eng.setFollow(1, maxOf(eng.zoom, 15.5)); locMode = 1 }
            locMode == 1 -> { eng.setFollow(2); locMode = 2 }
            else -> { eng.setFollow(0); locMode = 0; eng.rotate(0.0, 0.0, 500) }
        }
    }
    fun recTap() {
        if (rec) { TrackService.stop(ctx); return }
        if (!hasLoc(ctx)) { askLoc(); return }
        TrackService.start(ctx)
        eng.enableLocation()
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        AndroidView({ eng.view }, Modifier.fillMaxSize())

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
            Box(Modifier.size(48.dp).clickable { if (Nav.session != null) navFreeAt = System.currentTimeMillis(); eng.zoomBy(1.0) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Приблизить", tint = Pal.Ink) }
            Box(Modifier.width(48.dp).height(1.dp).background(Pal.Line))
            Box(Modifier.size(48.dp).clickable { if (Nav.session != null) navFreeAt = System.currentTimeMillis(); eng.zoomBy(-1.0) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Отдалить", tint = Pal.Ink) }
        }
        }
        if (prefs.getBoolean("dev_zoom", false)) Box(Modifier.align(Alignment.CenterStart).padding(start = 14.dp, top = 130.dp)) { ZoomText(live) }
        // GLMap: карта этого места не скачана — предложить (карта + рельеф для 3D работают и без сети)
        if (!clean && nav == null && !RoutePlan.active && target == null && selTrack == null && eng is GlEngine)
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp, start = 130.dp, end = 70.dp)) { GlDownloadHint(live) }

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
                    IconButton({ similar = null; eng.setSimilar(emptyList()) }) { Icon(Icons.Rounded.Close, "Закрыть похожие", tint = Pal.Muted) }
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
                            eng.setSimilar(hits)
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
        MapSheet.SEARCH -> SearchScreen(eng.center(), places, tracks, onDismiss = { sheet = MapSheet.NONE; searchFor = null }) { hit ->
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
    /** Центр карты после остановки (не каждый кадр). */
    var center by mutableStateOf<GeoPt?>(null)
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
