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
 * Карта на MapLibre (векторные тайлы OpenFreeMap, данные © OpenStreetMap).
 * Стиль «Liberty» — светлый, с иконками заведений, ближе всего к Guru Maps.
 * Офлайн: OfflineManager скачивает тайлы выбранной области в базу карты на телефоне.
 */
object MapKit {
    data class MapStyle(val key: String, val name: String, val url: String?, val rasterTiles: String? = null, val attribution: String = "© OpenStreetMap")

    val STYLES = listOf(
        MapStyle("liberty", "Обычная (как Guru)", "https://tiles.openfreemap.org/styles/liberty"),
        MapStyle("bright", "Яркая", "https://tiles.openfreemap.org/styles/bright"),
        MapStyle("positron", "Светлая простая", "https://tiles.openfreemap.org/styles/positron"),
        MapStyle("topo", "Рельеф", null, "https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "© OpenTopoMap, OpenStreetMap"),
        MapStyle("sat", "Спутник", null, "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", "© Esri"),
    )

    fun init(ctx: Context) { MapLibre.getInstance(ctx.applicationContext) }

    fun styleBuilder(s: MapStyle): Style.Builder =
        if (s.url != null) Style.Builder().fromUri(s.url)
        else Style.Builder().fromJson(JSONObject()
            .put("version", 8)
            .put("sources", JSONObject().put("r", JSONObject().put("type", "raster").put("tileSize", 256).put("attribution", s.attribution).put("tiles", JSONArray().put(s.rasterTiles))))
            .put("layers", JSONArray().put(JSONObject().put("id", "r").put("type", "raster").put("source", "r")))
            .toString())

    @SuppressLint("MissingPermission")
    fun enableLocation(ctx: Context, map: MapLibreMap, style: Style, follow: Boolean) = runCatching {
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated) lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, style).build())
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

    /** Примерно сколько тайлов в области на уровнях zMin…zMax. */
    fun tileCount(b: LatLngBounds, zMin: Int, zMax: Int): Long {
        var n = 0L
        for (z in zMin..zMax) {
            val k = 1 shl z
            fun x(lon: Double) = ((lon + 180) / 360 * k).toInt()
            fun y(lat: Double) = ((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * k).toInt()
            n += (x(b.longitudeEast) - x(b.longitudeWest) + 1).toLong() * (y(b.latitudeSouth) - y(b.latitudeNorth) + 1)
        }
        return n
    }

    fun download(ctx: Context, styleUrl: String, bounds: LatLngBounds, maxZoom: Double, name: String, onStatus: (OfflineRegionStatus) -> Unit, onError: (String) -> Unit) {
        val def = OfflineTilePyramidRegionDefinition(styleUrl, bounds, 0.0, maxZoom, ctx.resources.displayMetrics.density)
        val meta = JSONObject().put("name", name).put("style", styleUrl).toString().toByteArray()
        OfflineManager.getInstance(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) = start(offlineRegion, onStatus, onError)
            override fun onError(error: String) = onError(error)
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
