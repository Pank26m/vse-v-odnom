package com.pank.vsevodnom.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pank.vsevodnom.data.GeoHit
import com.pank.vsevodnom.data.GeoPt

/**
 * Движок, который рисует карту. Экран карты (кнопки, карточки, маршрут, навигация) общий, а рисование — через этот
 * интерфейс (сейчас одна реализация — GLMap, 3D-рельеф всегда; MapLibre вырезан в 0.28).
 */
internal interface MapEngine {
    val view: android.view.View
    /** Растёт после каждой (пере)загрузки стиля — повод заново положить свои слои. 0 — ещё не готов. */
    val gen: Int

    fun start(l: Listener, lat: Double, lon: Double, zoom: Double)
    /** Собрать стиль заново (настройки, «Объекты на карте», скачанные карты). */
    fun reloadStyle()

    // ---- камера ----
    val zoom: Double
    val bearing: Float
    fun center(): GeoPt?
    /** Плавно к точке; above — точка выше центра (низ экрана занят карточкой). zoom null — не меньше 15,5. */
    fun flyTo(lat: Double, lon: Double, zoom: Double?, above: Boolean = false)
    /** Показать все точки; отступы в пикселях. */
    fun fit(pts: List<GeoPt>, l: Int, t: Int, r: Int, b: Int)
    fun zoomBy(dz: Double)
    fun rotate(bearing: Double, tilt: Double?, ms: Int)
    /** Снять сдвиг центра (после карточки или навигации). */
    fun clearPadding()

    // ---- моё место ----
    fun enableLocation()
    fun location(): android.location.Location?
    /** 0 — свободно, 1 — за мной, 2 — по компасу; zoom — сразу приблизить. */
    fun setFollow(mode: Int, zoom: Double? = null)
    fun zoomFollow(z: Double)
    /** Камера навигации: зум, наклон, по курсу GPS (gps) или по компасу; changed — сменился режим. */
    fun navCamera(z: Double, tilt: Double, gps: Boolean, changed: Boolean): Boolean
    /** «Я» в нижней трети экрана — впереди видно больше дороги. */
    fun navPadding(on: Boolean)
    fun endNav()

    // ---- свои слои ----
    fun setPlaces(items: List<OvPlace>, cluster: Boolean = false)
    fun setTracks(items: List<OvLine>)
    fun setSelected(items: List<OvLine>)
    fun setRec(pts: List<GeoPt>)
    fun setRoute(pts: List<GeoPt>)
    fun setDone(pts: List<GeoPt>)
    fun setStops(items: List<OvStop>)
    fun setRuler(items: List<Pair<GeoPt, String>>)
    fun setSimilar(hits: List<GeoHit>)
    fun setPin(lat: Double?, lon: Double?)
    fun setHighlight(lines: List<List<GeoPt>>)

    // ---- нажатия ----
    /** Свой объект под пальцем: метка, трек, «похожее». */
    fun userObjectAt(x: Float, y: Float): UserHit?
    /** Объект карты под пальцем (заведение, тропа, улица…) и линии для подсветки. */
    fun featureAt(x: Float, y: Float, lat: Double, lon: Double): FeatureHit?

    fun onStart() {}
    fun onStop() {}
    fun dispose() {}

    interface Listener {
        fun onCamera(bearing: Float, zoom: Float, metersPerPixel: Double)
        fun onIdle(lat: Double, lon: Double, zoom: Double)
        /** Карту тронули рукой (для паузы ведения в навигации). */
        fun onGesture()
        fun onTap(x: Float, y: Float, lat: Double, lon: Double)
        fun onLongTap(lat: Double, lon: Double)
        /** Ведение за мной снято жестом или сменилось. */
        fun onFollow(mode: Int)
    }
}

internal data class OvPlace(val id: Long, val lat: Double, val lon: Double, val name: String, val color: Int, val icon: String = "")
internal data class OvLine(val id: Long, val pts: List<GeoPt>, val color: Int)
internal data class OvStop(val lat: Double, val lon: Double, val text: String, val color: Int)

internal sealed class UserHit {
    data class PlaceHit(val id: Long) : UserHit()
    /** Кружок с числом (сгруппированные метки) — приблизить. */
    data class ClusterHit(val lat: Double, val lon: Double) : UserHit()
    data class TrackHit(val id: Long) : UserHit()
    data class SimHit(val lat: Double, val lon: Double, val name: String, val kind: String) : UserHit()
}
/** lines != null — линейный объект (тропа): булавку не ставим, подсвечиваем линии. */
internal data class FeatureHit(val target: MapTarget, val lines: List<List<GeoPt>>? = null)

internal fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
internal fun argb(hex: String): Int = android.graphics.Color.parseColor(hex)
internal fun colorOr(c: Int, def: String) = if (c == 0) argb(def) else (c or 0xFF000000.toInt())

/** Движок карты — GLMap (нужен ключ). Без ключа или если GLMap не запустился — null (экран карты попросит ключ). С жизненным циклом экрана. */
@Composable
internal fun rememberEngine(): MapEngine? {
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val eng = remember { if (com.pank.vsevodnom.data.GlMap.use(ctx)) GlEngine(ctx, density) else null } ?: return null
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> eng.onStart(); Lifecycle.Event.ON_STOP -> eng.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) eng.onStart()
        onDispose { owner.lifecycle.removeObserver(obs); eng.onStop(); eng.dispose() }
    }
    return eng
}
