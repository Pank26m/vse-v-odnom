package com.pank.vsevodnom.ui.map

import android.content.Context

/** Общие помощники карты. */
object MapKit {
    fun human(b: Long) = when { b >= 1L shl 30 -> "%.1f ГБ".format(b / 1073741824.0); b >= 1L shl 20 -> "%.0f МБ".format(b / 1048576.0); else -> "${b / 1024} КБ" }
}

/**
 * Свой компас: датчик поворота (rotation vector) ~30 раз в секунду, лёгкое сглаживание по кругу,
 * изменения меньше порога не рассылаются (0,7° в режиме «по компасу», иначе 3° — дрожь руки не перерисовывает карту).
 */
class SmoothCompass(ctx: Context) : android.hardware.SensorEventListener {
    fun interface Listener { fun onHeading(deg: Float) }
    private val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
    private val wm = ctx.getSystemService(android.view.WindowManager::class.java)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    @Volatile var fine = false
    private var heading = 0f; private var sent = Float.NaN
    private val r = FloatArray(9); private val rr = FloatArray(9); private val o = FloatArray(3)

    fun add(l: Listener) {
        if (listeners.isEmpty()) sensor?.let { sm.registerListener(this, it, 33_000) }
        listeners += l
    }
    fun remove(l: Listener) {
        listeners -= l; if (listeners.isEmpty()) sm.unregisterListener(this)
    }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
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
        heading = (heading + d * 0.65f + 360) % 360
        if (sent.isNaN() || kotlin.math.abs(((heading - sent + 540) % 360) - 180) >= (if (fine) 0.7f else 3f)) { sent = heading; listeners.forEach { it.onHeading(heading) } }
    }
}

/** Почти север (меньше [deg] градусов в любую сторону) — повод выровнять. */
internal fun nearNorth(bearing: Double, deg: Double = 10.0): Boolean {
    val b = ((bearing % 360) + 360) % 360
    val d = if (b > 180) 360 - b else b
    return d > 0.05 && d <= deg
}
