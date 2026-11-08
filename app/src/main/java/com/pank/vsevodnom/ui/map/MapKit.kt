package com.pank.vsevodnom.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Карта на MapLibre: общие помощники (местоположение, жизненный цикл MapView, старые офлайн-области 0.15).
 * Стиль карты собирает MapStyle, офлайн-карты стран — OfflineMaps.
 */
object MapKit {
    @Volatile private var httpSet = false
    fun init(ctx: Context) {
        MapLibre.getInstance(ctx.applicationContext)
        if (!httpSet) runCatching {
            // онлайн-слои из .ms: часть серверов (Google и др.) не отдаёт тайлы без «браузерного» User-Agent
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { ch ->
                val r = ch.request()
                val h = r.url.host
                ch.proceed(if (h.endsWith("google.com") || h.endsWith("googleapis.com") || h.contains("khms") || h.contains("yandex"))
                    r.newBuilder().header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36").build() else r)
            }.build()
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
            httpSet = true
        }
    }

    @SuppressLint("MissingPermission")
    fun enableLocation(ctx: Context, map: MapLibreMap, style: Style, follow: Boolean) = runCatching {
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, style)
                .locationComponentOptions(org.maplibre.android.location.LocationComponentOptions.builder(ctx).compassAnimationEnabled(false).trackingAnimationDurationMultiplier(0.25f).build())
                .build())
            lc.compassEngine = SmoothCompass(ctx)
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        if (follow) lc.cameraMode = CameraMode.TRACKING
    }

    // ---------------- офлайн ----------------
    data class Region(val r: OfflineRegion, val name: String, var done: Long = 0, var total: Long = 0, var bytes: Long = 0, var complete: Boolean = false)

    fun list(ctx: Context, cb: (List<Region>) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                cb(offlineRegions.orEmpty().map { Region(it, runCatching { JSONObject(String(it.metadata)).optString("name") }.getOrDefault("Область")) })
            }
            override fun onError(error: String) = cb(emptyList())
        })
    }

    fun start(r: OfflineRegion, onStatus: (OfflineRegionStatus) -> Unit, onError: (String) -> Unit) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) { onStatus(status); if (status.isComplete) r.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            override fun onError(error: OfflineRegionError) = onError(error.message)
            override fun mapboxTileCountLimitExceeded(limit: Long) = onError("Слишком много тайлов: $limit")
        })
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun status(r: OfflineRegion, cb: (OfflineRegionStatus) -> Unit) = r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) { status?.let(cb) }
        override fun onError(error: String?) {}
    })

    fun delete(r: OfflineRegion, done: () -> Unit) = r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = done()
        override fun onError(error: String) = done()
    })

    fun human(b: Long) = when { b >= 1L shl 30 -> "%.1f ГБ".format(b / 1073741824.0); b >= 1L shl 20 -> "%.0f МБ".format(b / 1048576.0); else -> "${b / 1024} КБ" }
}

/** MapView с жизненным циклом экрана. */
@Composable
fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val view = remember { MapKit.init(ctx); MapView(ctx).apply { onCreate(null) } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose { owner.lifecycle.removeObserver(obs); view.onPause(); view.onStop(); view.onDestroy() }
    }
    return view
}

fun camera(lat: Double, lon: Double, zoom: Double): CameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()

@Suppress("unused") private val keepColor = AColor.BLACK

/**
 * Свой компас вместо встроенного MapLibre (тот обновляется раз в ~0,5 с и дёргается):
 * датчик поворота (rotation vector) ~30 раз в секунду, сглаживание по кругу, пропуск изменений меньше 0,7°.
 */
class SmoothCompass(ctx: Context) : org.maplibre.android.location.CompassEngine, android.hardware.SensorEventListener {
    private val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
    private val wm = ctx.getSystemService(android.view.WindowManager::class.java)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<org.maplibre.android.location.CompassListener>()
    /** Точный режим (карта крутится по компасу): шаг 0,7°. Иначе — 3°: дрожь руки не перерисовывает карту 30 раз в секунду. */
    @Volatile var fine = false
    private var heading = 0f; private var sent = Float.NaN; private var acc = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private val r = FloatArray(9); private val rr = FloatArray(9); private val o = FloatArray(3)

    override fun addCompassListener(l: org.maplibre.android.location.CompassListener) {
        if (listeners.isEmpty()) sensor?.let { sm.registerListener(this, it, 33_000) }
        listeners += l
    }
    override fun removeCompassListener(l: org.maplibre.android.location.CompassListener) {
        listeners -= l; if (listeners.isEmpty()) sm.unregisterListener(this)
    }
    override fun getLastHeading() = heading
    override fun getLastAccuracySensorStatus() = acc
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) { acc = a; listeners.forEach { it.onCompassAccuracyChange(a) } }
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values)
        @Suppress("DEPRECATION") val rot = wm.defaultDisplay.rotation
        val (ax, ay) = when (rot) {
            android.view.Surface.ROTATION_90 -> android.hardware.SensorManager.AXIS_Y to android.hardware.SensorManager.AXIS_MINUS_X
            android.view.Surface.ROTATION_180 -> android.hardware.SensorManager.AXIS_MINUS_X to android.hardware.SensorManager.AXIS_MINUS_Y
            android.view.Surface.ROTATION_270 -> android.hardware.SensorManager.AXIS_MINUS_Y to android.hardware.SensorManager.AXIS_X
            else -> android.hardware.SensorManager.AXIS_X to android.hardware.SensorManager.AXIS_Y
        }
        android.hardware.SensorManager.remapCoordinateSystem(r, ax, ay, rr)
        android.hardware.SensorManager.getOrientation(rr, o)
        val h = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
        var d = h - heading; if (d > 180) d -= 360; if (d < -180) d += 360
        heading = (heading + d * 0.65f + 360) % 360           // лёгкое сглаживание дрожи, без «льда»
        if (sent.isNaN() || kotlin.math.abs(((heading - sent + 540) % 360) - 180) >= (if (fine) 0.7f else 3f)) { sent = heading; listeners.forEach { it.onCompassChanged(heading) } }
    }
}

/**
 * Жесты карты как в Guru/галерее: щипок следует за пальцами 1:1, поворот свободный при любом расстоянии между пальцами
 * (MapLibre по умолчанию выключал поворот, если первым начался зум — так было при далёких пальцах).
 */
internal fun tuneGestures(m: MapLibreMap, density: Float) = runCatching {
    val u = m.uiSettings
    u.isRotateGesturesEnabled = true
    u.isZoomGesturesEnabled = true
    u.isDoubleTapGesturesEnabled = true
    u.isQuickZoomGesturesEnabled = false            // свой зум одним пальцем — QuickZoom
    u.isDisableRotateWhenScaling = false
    u.isIncreaseRotateThresholdWhenScaling = false
    u.isIncreaseScaleThresholdWhenRotating = false
    val g = m.gesturesManager
    g.rotateGestureDetector.angleThreshold = 7f        // по умолчанию ~15°, у нас было 18 — поворот «не брался»
    g.shoveGestureDetector.pixelDeltaThreshold = 10f * density
    g.shoveGestureDetector.maxShoveAngle = 45f
}

/** Почти север (меньше [deg] градусов в любую сторону) — повод выровнять. */
internal fun nearNorth(bearing: Double, deg: Double = 10.0): Boolean {
    val b = ((bearing % 360) + 360) % 360
    val d = if (b > 180) 360 - b else b
    return d > 0.05 && d <= deg
}

/**
 * Масштаб одним пальцем: коснуться, отпустить и сразу коснуться снова, затем вести вниз (ближе) или вверх (дальше), пока палец на экране.
 * Сделан сам — не зависит от настроек жестов MapLibre. Обычный двойной тап (без ведения) по-прежнему приближает.
 */
internal class QuickZoom(private val view: MapView, private val density: Float, private val map: () -> MapLibreMap?, private val keepCenter: () -> Boolean) : android.view.View.OnTouchListener {
    var active = false; private set
    private var lastUpT = 0L; private var lastUpX = 0f; private var lastUpY = 0f
    private var armed = false; private var downX = 0f; private var downY = 0f; private var downT = 0L; private var lastY = 0f; private var fx = 0; private var fy = 0
    private val timeout = android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 60
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: android.view.View, e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                armed = e.eventTime - lastUpT < timeout && kotlin.math.hypot(e.x - lastUpX, e.y - lastUpY) < 48 * density
                active = false; downX = e.x; downY = e.y; downT = e.eventTime; lastY = e.y; fx = e.x.toInt(); fy = e.y.toInt()
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> { armed = false; if (active) { active = false; return true } }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (armed && !active && kotlin.math.abs(e.y - downY) > 10 * density) {
                    active = true; lastY = e.y
                    // MapLibre видел начало двойного тапа — отменяем, иначе он приблизит при отпускании
                    val c = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    view.onTouchEvent(c); c.recycle()
                }
                if (active) {
                    val m = map() ?: return true
                    val dz = (e.y - lastY) / (190 * density).toDouble()      // ~190dp на один уровень
                    lastY = e.y
                    if (dz != 0.0) m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(zoomAround(m, dz, if (keepCenter()) null else android.graphics.PointF(fx.toFloat(), fy.toFloat()), view.width / 2f, view.height / 2f)))
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val was = active; active = false
                // первое касание считается, только если это короткий тап (не сдвиг карты)
                val tap = e.eventTime - downT < 300 && kotlin.math.hypot(e.x - downX, e.y - downY) < 16 * density
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !was && !armed && tap) { lastUpT = e.eventTime; lastUpX = e.x; lastUpY = e.y } else lastUpT = 0
                armed = false
                if (was) return true
            }
        }
        return active
    }
}

/**
 * Камера после зума на [dz] уровней вокруг точки экрана [f] (точка под пальцем остаётся на месте).
 * Встроенный zoomBy(dz, точка) переносил центр в эту точку на каждом шаге — карту «носило» по миру.
 */
internal fun zoomAround(m: MapLibreMap, dz: Double, f: android.graphics.PointF?, cx: Float, cy: Float): CameraPosition {
    val c = m.cameraPosition
    val z = (c.zoom + dz).coerceIn(m.minZoomLevel, m.maxZoomLevel)
    if (f == null) return CameraPosition.Builder(c).zoom(z).build()
    val k = Math.pow(2.0, z - c.zoom)
    // новый центр — та точка, что сейчас на f + (центр − f) / k
    val q = android.graphics.PointF((f.x + (cx - f.x) / k).toFloat(), (f.y + (cy - f.y) / k).toFloat())
    val t = m.projection.fromScreenLocation(q)
    return CameraPosition.Builder(c).target(t).zoom(z).build()
}

/**
 * Онлайн-мир (слои «…@w») выключен, пока весь экран внутри скачанных стран: тайлы OpenFreeMap тогда не грузятся
 * и не разбираются — зум внутри страны не делит силы телефона с невидимой картой.
 */
internal class WorldToggle {
    private var rings: List<DoubleArray> = emptyList()
    private var shown: Boolean? = null
    private var at = 0L
    fun reset(r: List<DoubleArray>) { rings = r; shown = null }
    fun check(m: MapLibreMap, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - at < 250) return
        at = now
        val st = m.style?.takeIf { it.isFullyLoaded } ?: return
        val b = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val dx = (b.longitudeEast - b.longitudeWest) * 0.15; val dy = (b.latitudeNorth - b.latitudeSouth) * 0.15   // запас — мир успевает появиться до края
        val show = !MapStyle.viewInside(rings, b.longitudeWest - dx, b.latitudeSouth - dy, b.longitudeEast + dx, b.latitudeNorth + dy)
        if (show == shown) return
        shown = show
        val vis = org.maplibre.android.style.layers.PropertyFactory.visibility(if (show) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE)
        st.layers.forEach { l -> if (l.id.endsWith(MapStyle.WORLD_SUFFIX)) l.setProperties(vis) }
    }
}
