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
                .locationComponentOptions(org.maplibre.android.location.LocationComponentOptions.builder(ctx).compassAnimationEnabled(true).trackingAnimationDurationMultiplier(0.6f).build())
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
        heading = (heading + d * 0.35f + 360) % 360           // мягкое сглаживание без задержки в полсекунды
        if (sent.isNaN() || kotlin.math.abs(((heading - sent + 540) % 360) - 180) >= 0.7f) { sent = heading; listeners.forEach { it.onCompassChanged(heading) } }
    }
}
