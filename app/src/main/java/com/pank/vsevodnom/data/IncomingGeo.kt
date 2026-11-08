package com.pank.vsevodnom.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Что пришло в приложение для карты: файл .ms (онлайн-карта), GPX/KML/KMZ (метки и треки),
 * ссылка из Google Карт («Поделиться» → «Всё в одном») — становится меткой.
 */
object IncomingGeo {
    /** Открыть на карте: (имя, широта, долгота, id места Google или null). */
    var open by mutableStateOf<Quad?>(null)
    var message by mutableStateOf<String?>(null)
    data class Quad(val name: String, val lat: Double, val lon: Double, val googleId: String?, val placeId: Long? = null)

    fun isMapLink(t: String) = Regex("maps\\.app\\.goo\\.gl|goo\\.gl/maps|google\\.[a-z.]+/maps|maps\\.google\\.|^geo:", RegexOption.IGNORE_CASE).containsMatchIn(t)

    fun handleFile(ctx: Context, uri: Uri) {
        val name = runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } }.getOrNull()
            ?: uri.lastPathSegment ?: "файл"
        AppScope.io.launch {
            val msg = runCatching {
                val head = ctx.contentResolver.openInputStream(uri)!!.use { it.readNBytes(4000) }.toString(Charsets.UTF_8)
                if (head.contains("customMapSource") || name.endsWith(".ms", true)) {
                    val src = ctx.contentResolver.openInputStream(uri)!!.use { OnlineMaps.parseMs(it, name) } ?: error("не похоже на .ms")
                    OnlineMaps.saveCustom(ctx, OnlineMaps.custom(ctx) + src)
                    ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("online_id", src.id).putBoolean("use_online", true).apply()
                    "Онлайн-карта «${src.name}» добавлена и включена"
                } else {
                    val root = GeoFiles.read(ctx, uri, name); "Импортировано в коллекции: ${GeoFiles.save(ctx, root, null)}"
                }
            }.getOrElse { "Не получилось открыть $name: ${it.message}" }
            withContext(Dispatchers.Main) { message = msg }
        }
    }

    /** Ссылка Google Карт → координаты (+ название и id места через Places, если есть ключ) → метка в папке «Из Google». */
    fun handleLink(ctx: Context, text: String) {
        val url = Regex("(https?://\\S+|geo:\\S+)").find(text)?.value ?: return
        AppScope.io.launch {
            val r = runCatching { resolve(url) }.getOrNull()
            if (r == null) { withContext(Dispatchers.Main) { message = "Не удалось разобрать ссылку" }; return@launch }
            var (name, lat, lon) = r
            var gid: String? = null; var addr = ""
            if (ApiKeys.google(ctx).isNotBlank()) runCatching {
                GooglePlaces.near(ctx, name.ifBlank { "%.6f,%.6f".format(java.util.Locale.US, lat, lon) }, lat, lon)?.let { d -> if (d.name.isNotBlank()) name = d.name; gid = d.id; addr = d.address; if (d.lat != 0.0) { lat = d.lat; lon = d.lon } }
            }
            if (name.isBlank()) name = text.lineSequence().firstOrNull { !it.contains("http") }?.trim()?.take(60).orEmpty().ifBlank { "Место из Google" }
            val dao = AppDb.get(ctx).map()
            val folder = dao.foldersNow().firstOrNull { it.name == "Из Google" && it.parentId == null }?.id ?: dao.upsertFolder(MapFolder(name = "Из Google"))
            val id = dao.upsertPlace(Place(name = name, lat = lat, lon = lon, folderId = folder, address = addr))
            withContext(Dispatchers.Main) { open = Quad(name, lat, lon, gid, id); message = "Метка «$name» сохранена в «Из Google»" }
        }
    }

    /** Разворачивает короткую ссылку и достаёт координаты и название. */
    fun resolve(start: String): Triple<String, Double, Double>? {
        var url = start
        repeat(6) {
            parse(url)?.let { return it }
            if (url.startsWith("geo:")) return null
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            val loc = c.getHeaderField("Location")
            if (loc == null) {
                val body = runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                Regex("https://www\\.google\\.[a-z.]+/maps/[^\"'\\s<>]+").find(body)?.value?.let { url = it.replace("\\u0026", "&").replace("&amp;", "&"); return@repeat }
                Regex("\\[null,null,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)\\]").find(body)?.let { m -> return Triple("", m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }
                return null
            }
            url = if (loc.startsWith("/")) URL(URL(url), loc).toString() else loc
        }
        return parse(url)
    }

    fun parse(u0: String): Triple<String, Double, Double>? {
        val u = runCatching { URLDecoder.decode(u0, "UTF-8") }.getOrDefault(u0)
        val name = Regex("/maps/place/([^/@]+)").find(u)?.groupValues?.get(1)?.replace('+', ' ')?.trim().orEmpty()
        val num = "(-?\\d{1,3}\\.\\d+)"
        Regex("^geo:$num,$num").find(u)?.let { return Triple(Regex("\\(([^)]+)\\)").find(u)?.groupValues?.get(1).orEmpty(), it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        Regex("!3d$num!4d$num").find(u)?.let { return Triple(name, it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        Regex("[?&](?:q|ll|query|destination)=$num,\\s*$num").find(u)?.let { return Triple(name, it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        Regex("@$num,$num").find(u)?.let { return Triple(name, it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        return null
    }
}
