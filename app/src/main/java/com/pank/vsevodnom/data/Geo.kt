package com.pank.vsevodnom.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class GeoPt(val lat: Double, val lon: Double, val t: Long)

data class GeoHit(val name: String, val address: String, val lat: Double, val lon: Double, val kind: String = "")

/** Поиск и «что здесь» через Nominatim (OpenStreetMap). Политика сервиса: не чаще раза в секунду, свой User-Agent. */
object Osm {
    private var last = 0L

    private fun get(url: String): String {
        val wait = 1100 - (System.currentTimeMillis() - last)
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.8 (personal Android app)")
        c.setRequestProperty("Accept-Language", "ru,uk")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun hit(o: JSONObject): GeoHit {
        val a = o.optJSONObject("address")
        val name = o.optString("name").ifBlank {
            listOfNotNull(a?.optString("road")?.ifBlank { null }, a?.optString("house_number")?.ifBlank { null }).joinToString(", ").ifBlank { o.optString("display_name").substringBefore(",") }
        }
        val addr = o.optString("display_name").split(", ").filter { it != name }.take(4).joinToString(", ")
        return GeoHit(name, addr, o.optString("lat").toDouble(), o.optString("lon").toDouble(), o.optString("type"))
    }

    suspend fun reverse(lat: Double, lon: Double): GeoHit? = withContext(Dispatchers.IO) {
        runCatching { hit(JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=$lat&lon=$lon"))) }
            .getOrNull()?.copy(lat = lat, lon = lon)
    }

    suspend fun search(q: String, nearLat: Double, nearLon: Double): List<GeoHit> = withContext(Dispatchers.IO) {
        val box = "${nearLon - 0.6},${nearLat + 0.4},${nearLon + 0.6},${nearLat - 0.4}"
        val arr = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=8&viewbox=$box&q=" + URLEncoder.encode(q, "UTF-8")))
        (0 until arr.length()).map { hit(arr.getJSONObject(it)) }
    }

    fun distance(a: GeoPt, b: GeoPt): Double {
        val r = FloatArray(1); Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r); return r[0].toDouble()
    }

    fun encode(p: List<GeoPt>) = p.joinToString(";") { "%.6f,%.6f,%d".format(java.util.Locale.US, it.lat, it.lon, it.t / 1000) }
    fun decode(s: String) = s.split(';').mapNotNull { e -> e.split(',').takeIf { it.size >= 2 }?.let { GeoPt(it[0].toDouble(), it[1].toDouble(), (it.getOrNull(2)?.toLongOrNull() ?: 0) * 1000) } }
}

fun fmtKm(m: Double) = if (m < 1000) "${m.toInt()} м" else "%.2f км".format(m / 1000).replace('.', ',')
fun fmtDur(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }

/** Запись трека GPS: работает и с выключенным экраном (служба переднего плана). */
class TrackService : Service(), LocationListener {
    companion object {
        val points = MutableStateFlow<List<GeoPt>>(emptyList())
        val recording = MutableStateFlow(false)
        val distance = MutableStateFlow(0.0)
        var startedAt = 0L
        private const val CH = "track"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, TrackService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, TrackService::class.java).setAction("stop"))
        fun canStart(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("route", "map"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Пишу трек · ${fmtKm(distance.value)}").setContentText("Нажми, чтобы открыть карту")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Остановить и сохранить", stop).build()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finish(); return START_NOT_STICKY }
        if (recording.value) return START_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "Запись трека", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(77, notif())
        if (!canStart(this)) { stopSelf(); return START_NOT_STICKY }
        points.value = emptyList(); distance.value = 0.0; startedAt = System.currentTimeMillis(); recording.value = true
        getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 4f, this)
        return START_STICKY
    }

    override fun onLocationChanged(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 35) return
        val p = GeoPt(l.latitude, l.longitude, l.time)
        val prev = points.value.lastOrNull()
        if (prev != null) {
            val d = Osm.distance(prev, p)
            if (d < 3) return
            distance.value += d
        }
        points.value = points.value + p
        if (points.value.size % 10 == 0) runCatching { getSystemService(NotificationManager::class.java).notify(77, notif()) }
    }

    @Deprecated("старые Android") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun finish() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        val pts = points.value; val dist = distance.value; val st = startedAt
        recording.value = false
        if (pts.size >= 2) {
            val ctx = applicationContext
            AppScope.io.launch { saveTrack(ctx, pts, dist, st) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }; recording.value = false; super.onDestroy() }
}

/** Сохраняет трек и кладёт его в календарь отдельным событием. */
suspend fun saveTrack(ctx: Context, pts: List<GeoPt>, dist: Double, startedAt: Long, name: String? = null) {
    val end = pts.last().t.takeIf { it > startedAt } ?: System.currentTimeMillis()
    val title = name ?: ("Трек " + Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))
    val db = AppDb.get(ctx)
    db.map().upsertTrack(Track(name = title, startedAt = startedAt, endedAt = end, distanceM = dist, points = Osm.encode(pts)))
    db.schedule().upsertEvent(CalEvent(title = "Трек · ${fmtKm(dist)}", startMillis = startedAt, endMillis = end.coerceAtLeast(startedAt + 60_000),
        note = "$title · ${fmtDur(end - startedAt)}", tint = 1))
}
