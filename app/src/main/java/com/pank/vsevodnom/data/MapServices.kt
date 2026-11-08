package com.pank.vsevodnom.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * Ключи внешних сервисов карты (вводит владелец в настройках, в коде и репозитории их нет)
 * и дневной счётчик запросов — чтобы не выйти за бесплатный лимит.
 */
object ApiKeys {
    private fun p(ctx: Context) = ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE)
    fun google(ctx: Context) = p(ctx).getString("google", "").orEmpty().trim()
    fun tomtom(ctx: Context) = p(ctx).getString("tomtom", "").orEmpty().trim()
    fun set(ctx: Context, k: String, v: String) = p(ctx).edit().putString(k, v.trim()).apply()

    /** Предел запросов в день по сервису (google / tomtom). */
    fun limit(ctx: Context, svc: String) = p(ctx).getInt("limit_$svc", if (svc == "google") 150 else 1500)
    fun setLimit(ctx: Context, svc: String, n: Int) = p(ctx).edit().putInt("limit_$svc", n).apply()
    fun used(ctx: Context, svc: String): Int {
        val today = LocalDate.now().toString()
        return if (p(ctx).getString("day_$svc", "") == today) p(ctx).getInt("used_$svc", 0) else 0
    }
    /** Занять один запрос; false — лимит на сегодня исчерпан. */
    @Synchronized fun take(ctx: Context, svc: String): Boolean {
        val n = used(ctx, svc)
        if (n >= limit(ctx, svc)) return false
        p(ctx).edit().putString("day_$svc", LocalDate.now().toString()).putInt("used_$svc", n + 1).apply()
        return true
    }

    /** SHA-1 подписи приложения — Google проверяет её, если ключ ограничен «Приложениями Android». */
    @Volatile private var cert: String? = null
    fun certSha1(ctx: Context): String = cert ?: runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = info.signingInfo!!.apkContentsSigners[0].toByteArray()
        MessageDigest.getInstance("SHA-1").digest(sig).joinToString("") { "%02X".format(it) }
    }.getOrDefault("").also { cert = it }
}

class LimitException(msg: String) : Exception(msg)

/** Google Places API (New): поиск и сведения о месте — рейтинг, отзывы, часы, телефон, сайт, фото. */
object GooglePlaces {
    data class Review(val author: String, val rating: Int, val text: String, val when_: String)
    data class Details(
        val id: String, val name: String, val address: String, val lat: Double, val lon: Double,
        val rating: Double?, val count: Int?, val price: String?, val type: String?,
        val phone: String?, val site: String?, val mapsUrl: String?, val openNow: Boolean?, val hours: List<String>,
        val reviews: List<Review>, val photos: List<String>,
    )

    private fun req(ctx: Context, url: String, fields: String, body: JSONObject?): JSONObject {
        val key = ApiKeys.google(ctx)
        if (key.isBlank()) throw LimitException("Нет ключа Google — Настройки карты → Ключи и сервисы")
        if (!ApiKeys.take(ctx, "google")) throw LimitException("Лимит Google на сегодня исчерпан (${ApiKeys.limit(ctx, "google")})")
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("X-Goog-Api-Key", key)
        c.setRequestProperty("X-Goog-FieldMask", fields)
        c.setRequestProperty("X-Android-Package", ctx.packageName)
        c.setRequestProperty("X-Android-Cert", ApiKeys.certSha1(ctx))
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault("код $code")
            throw Exception("Google: $msg")
        }
        return JSONObject(txt)
    }

    private const val SEARCH_FIELDS = "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount,places.primaryTypeDisplayName"

    suspend fun search(ctx: Context, q: String, lat: Double, lon: Double): List<GeoHit> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("textQuery", q).put("languageCode", "ru").put("pageSize", 10)
            .put("locationBias", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", 30000.0)))
        val arr = req(ctx, "https://places.googleapis.com/v1/places:searchText", SEARCH_FIELDS, body).optJSONArray("places") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val loc = o.getJSONObject("location")
            val r = if (o.has("rating")) " · ★ %.1f (%d)".format(o.optDouble("rating"), o.optInt("userRatingCount")) else ""
            GeoHit(o.optJSONObject("displayName")?.optString("text").orEmpty(),
                (o.optJSONObject("primaryTypeDisplayName")?.optString("text")?.let { "$it · " } ?: "") + o.optString("formattedAddress") + r,
                loc.getDouble("latitude"), loc.getDouble("longitude"), "google:" + o.getString("id"))
        }
    }

    private const val DETAIL_FIELDS = "id,displayName,formattedAddress,location,rating,userRatingCount,priceLevel,primaryTypeDisplayName," +
        "nationalPhoneNumber,websiteUri,googleMapsUri,currentOpeningHours,regularOpeningHours,reviews,photos"

    suspend fun details(ctx: Context, id: String): Details = withContext(Dispatchers.IO) {
        parse(req(ctx, "https://places.googleapis.com/v1/places/$id?languageCode=ru", DETAIL_FIELDS, null))
    }

    /** Найти место Google по названию рядом с точкой и сразу взять сведения (2 запроса). */
    suspend fun near(ctx: Context, name: String, lat: Double, lon: Double): Details? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("textQuery", name).put("languageCode", "ru").put("pageSize", 1)
            .put("locationBias", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", 300.0)))
        val o = req(ctx, "https://places.googleapis.com/v1/places:searchText", "places.id,places.location", body).optJSONArray("places")?.optJSONObject(0) ?: return@withContext null
        val loc = o.getJSONObject("location")
        val r = FloatArray(1); android.location.Location.distanceBetween(lat, lon, loc.getDouble("latitude"), loc.getDouble("longitude"), r)
        if (r[0] > 400) null else details(ctx, o.getString("id"))
    }

    private fun parse(o: JSONObject): Details {
        val loc = o.optJSONObject("location")
        val hoursObj = o.optJSONObject("currentOpeningHours") ?: o.optJSONObject("regularOpeningHours")
        val hours = hoursObj?.optJSONArray("weekdayDescriptions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val revs = o.optJSONArray("reviews")?.let { a -> (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            Review(r.optJSONObject("authorAttribution")?.optString("displayName").orEmpty(), r.optInt("rating"),
                r.optJSONObject("text")?.optString("text") ?: r.optJSONObject("originalText")?.optString("text").orEmpty(), r.optString("relativePublishTimeDescription"))
        } }.orEmpty()
        val photos = o.optJSONArray("photos")?.let { a -> (0 until minOf(a.length(), 6)).map { a.getJSONObject(it).getString("name") } }.orEmpty()
        val price = when (o.optString("priceLevel")) { "PRICE_LEVEL_INEXPENSIVE" -> "₴"; "PRICE_LEVEL_MODERATE" -> "₴₴"; "PRICE_LEVEL_EXPENSIVE" -> "₴₴₴"; "PRICE_LEVEL_VERY_EXPENSIVE" -> "₴₴₴₴"; else -> null }
        return Details(o.optString("id"), o.optJSONObject("displayName")?.optString("text").orEmpty(), o.optString("formattedAddress"),
            loc?.optDouble("latitude") ?: 0.0, loc?.optDouble("longitude") ?: 0.0,
            if (o.has("rating")) o.optDouble("rating") else null, if (o.has("userRatingCount")) o.optInt("userRatingCount") else null, price,
            o.optJSONObject("primaryTypeDisplayName")?.optString("text"), o.optString("nationalPhoneNumber").ifBlank { null }, o.optString("websiteUri").ifBlank { null },
            o.optString("googleMapsUri").ifBlank { null }, o.optJSONObject("currentOpeningHours")?.let { if (it.has("openNow")) it.optBoolean("openNow") else null },
            hours, revs, photos)
    }

    /** Фото — тоже платный запрос, поэтому грузим по одному и только по просьбе (листание). */
    suspend fun photo(ctx: Context, name: String, maxPx: Int = 800): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val key = ApiKeys.google(ctx)
        if (key.isBlank() || !ApiKeys.take(ctx, "google")) return@withContext null
        runCatching {
            val c = URL("https://places.googleapis.com/v1/$name/media?maxWidthPx=$maxPx&key=" + URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
            c.setRequestProperty("X-Android-Package", ctx.packageName); c.setRequestProperty("X-Android-Cert", ApiKeys.certSha1(ctx))
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}

/** TomTom: слой пробок (растровые тайлы) и маршрут с учётом текущих пробок. */
object TomTom {
    fun flowTiles(key: String) = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?tileSize=256&key=$key"
    fun incidentTiles(key: String) = "https://api.tomtom.com/traffic/map/4/tile/incidents/s3/{z}/{x}/{y}.png?tileSize=256&key=$key"

    suspend fun route(ctx: Context, mode: String, from: GeoPt, to: GeoPt): Routing.Route = withContext(Dispatchers.IO) {
        val key = ApiKeys.tomtom(ctx)
        if (key.isBlank()) throw LimitException("Нет ключа TomTom")
        if (!ApiKeys.take(ctx, "tomtom")) throw LimitException("Лимит TomTom на сегодня исчерпан")
        val tm = when (mode) { "bike" -> "bicycle"; "foot" -> "pedestrian"; else -> "car" }
        val url = String.format(Locale.US, "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?traffic=true&travelMode=%s&routeType=fastest&instructionsType=coded&language=ru-RU&key=%s",
            from.lat, from.lon, to.lat, to.lon, tm, URLEncoder.encode(key, "UTF-8"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        if (c.responseCode !in 200..299) throw Exception("TomTom: код ${c.responseCode}")
        val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("routes").getJSONObject(0)
        val s = r.getJSONObject("summary")
        val pts = ArrayList<GeoPt>()
        val legs = r.getJSONArray("legs")
        for (i in 0 until legs.length()) { val a = legs.getJSONObject(i).getJSONArray("points"); for (j in 0 until a.length()) a.getJSONObject(j).let { pts += GeoPt(it.getDouble("latitude"), it.getDouble("longitude"), 0) } }
        val steps = ArrayList<Routing.Step>()
        r.optJSONObject("guidance")?.optJSONArray("instructions")?.let { a ->
            for (i in 0 until a.length()) {
                val ins = a.getJSONObject(i)
                val m = ins.optString("maneuver")
                val cmd = when {
                    m == "ARRIVE" || m == "ARRIVE_LEFT" || m == "ARRIVE_RIGHT" -> Nav.END
                    m.startsWith("ROUNDABOUT") -> Nav.RNDB
                    m == "TURN_LEFT" -> Nav.TL; m == "TURN_RIGHT" -> Nav.TR
                    m == "SHARP_LEFT" -> Nav.TSHL; m == "SHARP_RIGHT" -> Nav.TSHR
                    m == "BEAR_LEFT" -> Nav.TSLL; m == "BEAR_RIGHT" -> Nav.TSLR
                    m == "KEEP_LEFT" || m == "MOTORWAY_EXIT_LEFT" -> Nav.KL; m == "KEEP_RIGHT" || m == "MOTORWAY_EXIT_RIGHT" -> Nav.KR
                    m == "MAKE_UTURN" || m == "TRY_MAKE_UTURN" -> Nav.TU
                    else -> 0
                }
                if (cmd != 0) steps += Routing.Step(ins.optInt("pointIndex").coerceIn(0, maxOf(0, pts.size - 1)), cmd, ins.optInt("roundaboutExitNumber", 0))
            }
        }
        Routing.Route(pts, s.getDouble("lengthInMeters"), s.getDouble("travelTimeInSeconds"), s.optDouble("trafficDelayInSeconds", 0.0), steps, "TomTom")
    }
}
