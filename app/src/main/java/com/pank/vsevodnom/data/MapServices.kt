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
    /** Израсходовано 90% дневного лимита — дальше сведения только по кнопке. */
    fun nearlyOut(ctx: Context, svc: String) = used(ctx, svc) >= limit(ctx, svc) * 0.9
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
        /** График по дням (0 — понедельник), минуты от полуночи — из периодов Google; статус «открыто» считаем по нему сейчас. */
        val week: List<List<IntRange>>? = null,
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
        "nationalPhoneNumber,websiteUri,googleMapsUri,regularOpeningHours,photos"

    /**
     * Повторно не платим: по правилам Google хранить можно только id места (без срока) — его и запоминаем для объектов
     * карты (prefs «g_ids»), а сами сведения и фото держим в памяти, пока приложение открыто.
     */
    private val mem = object : LinkedHashMap<String, Details>(32, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Details>?) = size > 60 }
    private val photoMem = object : LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 40 }
    private fun idKey(name: String, lat: Double, lon: Double) = "%s|%.4f|%.4f".format(Locale.US, name.lowercase(), lat, lon)
    fun knownId(ctx: Context, name: String, lat: Double, lon: Double): String? =
        ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getString("g_ids", null)?.let { runCatching { JSONObject(it).optString(idKey(name, lat, lon)).ifBlank { null } }.getOrNull() }
    private fun rememberId(ctx: Context, name: String, lat: Double, lon: Double, id: String) {
        val p = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString("g_ids", "{}")!!) }.getOrDefault(JSONObject())
        if (o.length() > 3000) o.keys().asSequence().take(500).toList().forEach { o.remove(it) }
        p.edit().putString("g_ids", o.put(idKey(name, lat, lon), id).toString()).apply()
    }
    fun cached(id: String?): Details? = id?.let { synchronized(mem) { mem[it] } }
    /** Откуда последний раз взяты сведения места: mem — память (пока приложение открыто), disk — зашифрованный кэш, net — запрос к Google. */
    val source = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun cachedPhoto(name: String): android.graphics.Bitmap? = synchronized(photoMem) { photoMem[name] }

    /** Из памяти или зашифрованного кэша на телефоне (30 дней) — без запроса к Google. */
    fun stored(ctx: Context, id: String?): Details? {
        if (id == null) return null
        cached(id)?.let { source[id] = if (source[id] == "net") "mem" else source[id] ?: "mem"; return it }
        val raw = GCache.get(ctx, "d:$id") ?: return null
        return runCatching { parse(JSONObject(String(raw))) }.getOrNull()?.also { d -> synchronized(mem) { mem[id] = d }; source[id] = "disk" }
    }

    /** force=true — заново из Google (кнопка «обновить данные»), кэш перезаписывается. */
    suspend fun details(ctx: Context, id: String, force: Boolean = false): Details = withContext(Dispatchers.IO) {
        (if (force) null else stored(ctx, id)) ?: run {
            val o = req(ctx, "https://places.googleapis.com/v1/places/$id?languageCode=ru", DETAIL_FIELDS, null)
            GCache.put(ctx, "d:$id", o.toString().toByteArray())
            source[id] = "net"
            parse(o).also { d -> synchronized(mem) { mem[id] = d } }
        }
    }

    /** Найти место Google по названию рядом с точкой и сразу взять сведения (2 запроса). */
    suspend fun near(ctx: Context, name: String, lat: Double, lon: Double): Details? = withContext(Dispatchers.IO) {
        knownId(ctx, name, lat, lon)?.let { return@withContext details(ctx, it) }
        val body = JSONObject().put("textQuery", name).put("languageCode", "ru").put("pageSize", 1)
            .put("locationBias", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", 300.0)))
        // только id — по тарифу Google это «IDs Only», самый дешёвый вид поиска; расстояние проверяем уже по сведениям
        val o = req(ctx, "https://places.googleapis.com/v1/places:searchText", "places.id", body).optJSONArray("places")?.optJSONObject(0) ?: return@withContext null
        val d = details(ctx, o.getString("id"))
        val r = FloatArray(1); android.location.Location.distanceBetween(lat, lon, d.lat, d.lon, r)
        if (r[0] > 400) null else { rememberId(ctx, name, lat, lon, o.getString("id")); d }
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
        // периоды: day 0 = воскресенье; закрытие на другой день — конец после полуночи; без закрытия — круглосуточно
        val week = (o.optJSONObject("regularOpeningHours") ?: o.optJSONObject("currentOpeningHours"))?.optJSONArray("periods")?.let { a ->
            val w = MutableList<MutableList<IntRange>>(7) { ArrayList() }
            for (i in 0 until a.length()) {
                val pr = a.getJSONObject(i); val op = pr.optJSONObject("open") ?: continue; val cl = pr.optJSONObject("close")
                val d = (op.optInt("day") + 6) % 7; val st = op.optInt("hour") * 60 + op.optInt("minute")
                if (cl == null) { (0..6).forEach { w[it] = mutableListOf(0..1440) }; continue }
                val days = (cl.optInt("day") - op.optInt("day") + 7) % 7
                w[d] += st..(days * 1440 + cl.optInt("hour") * 60 + cl.optInt("minute")).let { if (it <= st) it + 1440 else it }
            }
            w.map { l -> l.sortedBy { it.first } }.takeIf { l -> l.any { it.isNotEmpty() } }
        }
        return Details(o.optString("id"), o.optJSONObject("displayName")?.optString("text").orEmpty(), o.optString("formattedAddress"),
            loc?.optDouble("latitude") ?: 0.0, loc?.optDouble("longitude") ?: 0.0,
            if (o.has("rating")) o.optDouble("rating") else null, if (o.has("userRatingCount")) o.optInt("userRatingCount") else null, price,
            o.optJSONObject("primaryTypeDisplayName")?.optString("text"), o.optString("nationalPhoneNumber").ifBlank { null }, o.optString("websiteUri").ifBlank { null },
            o.optString("googleMapsUri").ifBlank { null }, o.optJSONObject("currentOpeningHours")?.let { if (it.has("openNow")) it.optBoolean("openNow") else null },
            hours, revs, photos, week)
    }

    /** Отзывы — отдельным запросом и только по кнопке (это самый дорогой вид сведений Google). Кэш — как у сведений. */
    suspend fun reviews(ctx: Context, id: String): List<Review> = withContext(Dispatchers.IO) {
        val raw = GCache.get(ctx, "r:$id") ?: req(ctx, "https://places.googleapis.com/v1/places/$id?languageCode=ru", "reviews", null).toString().toByteArray().also { GCache.put(ctx, "r:$id", it) }
        parse(JSONObject(String(raw))).reviews
    }

    /** Фото — тоже платный запрос, поэтому грузим по одному и только по просьбе (листание). */
    fun storedPhoto(ctx: Context, name: String): android.graphics.Bitmap? = cachedPhoto(name)
        ?: GCache.get(ctx, "p:$name")?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }?.also { bm -> synchronized(photoMem) { photoMem[name] = bm } }

    suspend fun photo(ctx: Context, name: String, maxPx: Int = 800): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        storedPhoto(ctx, name)?.let { return@withContext it }
        val key = ApiKeys.google(ctx)
        if (key.isBlank() || !ApiKeys.take(ctx, "google")) return@withContext null
        runCatching {
            val c = URL("https://places.googleapis.com/v1/$name/media?maxWidthPx=$maxPx&key=" + URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
            c.setRequestProperty("X-Android-Package", ctx.packageName); c.setRequestProperty("X-Android-Cert", ApiKeys.certSha1(ctx))
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            val bytes = c.inputStream.use { it.readBytes() }
            GCache.put(ctx, "p:$name", bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.also { b -> synchronized(photoMem) { photoMem[name] = b } }
    }
}

