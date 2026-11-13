package com.pank.vsevodnom.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Вариант из поиска: Q-id Викиданных, название, описание («американский телесериал 2008 года»). */
data class MovieHit(val id: String, val title: String, val description: String)

/** Всё, что удалось узнать о фильме/сериале. */
data class MovieInfo(
    val id: String, val title: String, val original: String, val kind: String, val years: String,
    val directors: List<String>, val writers: List<String>, val cast: List<String>, val genres: List<String>, val countries: List<String>,
    val seasons: Int?, val episodes: Int?, val duration: Int?, val imdbId: String, val imdbRating: Double?, val imdbVotes: Long?,
    val poster: String, val description: String, val wiki: String,
) {
    fun toJson(): String = JSONObject()
        .put("original", original).put("years", years).put("directors", JSONArray(directors)).put("writers", JSONArray(writers))
        .put("cast", JSONArray(cast)).put("genres", JSONArray(genres)).put("countries", JSONArray(countries))
        .put("seasons", seasons ?: JSONObject.NULL).put("episodes", episodes ?: JSONObject.NULL).put("duration", duration ?: JSONObject.NULL)
        .put("imdb", imdbId).put("imdbRating", imdbRating ?: JSONObject.NULL).put("imdbVotes", imdbVotes ?: JSONObject.NULL)
        .put("description", description).put("wiki", wiki).toString()

    companion object {
        private fun arr(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        /** Разбор сохранённого details из MediaItem. */
        fun fromItem(m: MediaItem): MovieInfo? = runCatching {
            val o = JSONObject(m.details)
            MovieInfo(m.extId, m.title, o.optString("original"), m.kind, o.optString("years", m.year), arr(o, "directors"), arr(o, "writers"), arr(o, "cast"),
                arr(o, "genres"), arr(o, "countries"), o.optInt("seasons").takeIf { !o.isNull("seasons") }, o.optInt("episodes").takeIf { !o.isNull("episodes") },
                o.optInt("duration").takeIf { !o.isNull("duration") }, o.optString("imdb"), o.optDouble("imdbRating").takeIf { !o.isNull("imdbRating") && !it.isNaN() },
                o.optLong("imdbVotes").takeIf { !o.isNull("imdbVotes") }, m.poster, o.optString("description"), o.optString("wiki"))
        }.getOrNull()
    }
}

/**
 * Фильмы и сериалы из открытых источников, без ключей:
 * Викиданные (поиск, режиссёр, актёры, жанры, годы), Википедия (описание, постер), IMDb (постер и оценка зрителей).
 */
object Movies {
    private const val UA = "VseVOdnom/0.9 (personal Android app)"
    private val SERIES = setOf("Q5398426", "Q1259759", "Q581714", "Q63952888", "Q117467246", "Q21191270", "Q3464665")
    private val SCREEN_WORDS = Regex("(фильм|сериал|мультфильм|мультсериал|аниме|мини-сериал|телефильм|film|series|serial|movie|фільм|телесеріал|серіал|мультфільм)", RegexOption.IGNORE_CASE)

    class HttpError(val code: Int) : java.io.IOException("HTTP $code")

    /** Запрос с повтором: Викиданные при частых запросах (поиск на каждую букву) отвечают 429 — ждём и пробуем снова. */
    private fun get(url: String, browser: Boolean = false): String {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", if (browser) "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36" else UA)
            c.setRequestProperty("Accept-Language", "ru,uk;q=0.8,en;q=0.6")
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            try {
                val code = c.responseCode
                if (code == 429 || code >= 500) {
                    last = HttpError(code)
                    val wait = c.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 5)?.times(1000) ?: (800L shl attempt)
                    Thread.sleep(wait); continue
                }
                if (code >= 400) throw HttpError(code)
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) { if (e is HttpError && e.code in 400..499 && e.code != 429) throw e; last = e; Thread.sleep(500L shl attempt) }
            finally { c.disconnect() }
        }
        throw last ?: java.io.IOException("нет ответа")
    }
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<MovieHit>>()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Подсказки при вводе названия — как поиск города в погоде. */
    suspend fun search(q: String): List<MovieHit> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        searchCache[q.lowercase()]?.let { return@withContext it }
        val seen = LinkedHashMap<String, MovieHit>()
        for (lang in listOf("ru", "uk", "en")) {
            val j = JSONObject(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=20&language=$lang&uselang=ru&search=" + enc(q)))
            val a = j.optJSONArray("search") ?: continue
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (id !in seen) seen[id] = MovieHit(id, o.optString("label"), o.optString("description"))
            }
            if (seen.values.count { SCREEN_WORDS.containsMatchIn(it.description) } >= 3) break
        }
        parseHits(seen.values.toList()).also { searchCache[q.lowercase()] = it }
    }

    /** Оставляем фильмы и сериалы; если ничего не похоже — показываем всё. */
    fun parseHits(all: List<MovieHit>): List<MovieHit> {
        val screen = all.filter { SCREEN_WORDS.containsMatchIn(it.description) }
        return (screen.ifEmpty { all }).take(12)
    }

    private fun claimIds(e: JSONObject, p: String): List<String> {
        val a = e.optJSONObject("claims")?.optJSONArray(p) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.ifBlank { null } }
    }
    private fun claimStr(e: JSONObject, p: String): String? =
        e.optJSONObject("claims")?.optJSONArray(p)?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")?.let { v ->
            when (v) { is String -> v; is JSONObject -> v.optString("time").ifBlank { v.optString("amount") }; else -> null }
        }
    private fun year(t: String?) = t?.let { Regex("[+-]?(\\d{4})").find(it)?.groupValues?.get(1) }
    private fun label(o: JSONObject?) = o?.optJSONObject("labels")?.let { l -> listOf("ru", "uk", "en").firstNotNullOfOrNull { l.optJSONObject(it)?.optString("value") } }

    private fun labels(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        ids.distinct().chunked(50).forEach { part ->
            val j = JSONObject(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=ru|uk|en&ids=" + part.joinToString("|")))
            val ents = j.optJSONObject("entities") ?: return@forEach
            part.forEach { id -> label(ents.optJSONObject(id))?.let { out[id] = it } }
        }
        return out
    }

    suspend fun details(id: String): MovieInfo = withContext(Dispatchers.IO) {
        val e = JSONObject(get("https://www.wikidata.org/wiki/Special:EntityData/$id.json")).getJSONObject("entities").let { it.optJSONObject(id) ?: it.getJSONObject(it.keys().next()) }
        val dir = claimIds(e, "P57"); val wri = claimIds(e, "P58").take(4); val cast = claimIds(e, "P161").take(10)
        val gen = claimIds(e, "P136").take(5); val cou = claimIds(e, "P495").take(3); val inst = claimIds(e, "P31")
        val names = runCatching { labels(dir + wri + cast + gen + cou) }.getOrDefault(emptyMap())
        val y1 = year(claimStr(e, "P580")) ?: year(claimStr(e, "P577"))
        val y2 = year(claimStr(e, "P582"))
        val title = label(e) ?: id
        val original = e.optJSONObject("claims")?.optJSONArray("P1476")?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("text").orEmpty()
        val imdb = claimStr(e, "P345").orEmpty()
        val desc0 = e.optJSONObject("descriptions")?.let { d -> listOf("ru", "uk", "en").firstNotNullOfOrNull { d.optJSONObject(it)?.optString("value") } }.orEmpty()
        val series = inst.any { it in SERIES } || desc0.contains("сериал", true) || desc0.contains("серіал", true) || desc0.contains("series", true)
        // Википедия: описание и постер
        val links = e.optJSONObject("sitelinks")
        var description = ""; var poster = ""; var wiki = ""
        for (w in listOf("ruwiki", "ukwiki", "enwiki")) {
            val t = links?.optJSONObject(w)?.optString("title")?.ifBlank { null } ?: continue
            val host = w.removeSuffix("wiki") + ".wikipedia.org"
            runCatching {
                val s = JSONObject(get("https://$host/api/rest_v1/page/summary/" + enc(t.replace(' ', '_')).replace("+", "%20")))
                if (description.isBlank()) description = s.optString("extract")
                if (poster.isBlank()) poster = s.optJSONObject("originalimage")?.optString("source") ?: s.optJSONObject("thumbnail")?.optString("source") ?: ""
                if (wiki.isBlank()) wiki = s.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "https://$host/wiki/" + enc(t)
            }
            if (description.isNotBlank() && poster.isNotBlank()) break
        }
        // IMDb: постер (надёжнее) и оценка зрителей
        var rating: Double? = null; var votes: Long? = null
        if (imdb.isNotBlank()) {
            runCatching {
                val s = JSONObject(get("https://v3.sg.media-imdb.com/suggestion/x/$imdb.json"))
                s.optJSONArray("d")?.optJSONObject(0)?.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() }?.let { poster = it.replace("._V1_.", "._V1_SX600.") }
            }
            runCatching {
                val html = get("https://www.imdb.com/title/$imdb/", browser = true)
                rating = Regex("\"ratingValue\"\\s*:\\s*([0-9.]+)").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
                votes = Regex("\"ratingCount\"\\s*:\\s*([0-9]+)").find(html)?.groupValues?.get(1)?.toLongOrNull()
            }
        }
        if (poster.isBlank()) (claimStr(e, "P3383") ?: claimStr(e, "P18"))?.let { poster = "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc(it.replace(' ', '_')) + "?width=500" }
        MovieInfo(
            id, title, original.takeIf { !it.equals(title, true) }.orEmpty(), if (series) "series" else "film",
            listOfNotNull(y1, y2?.takeIf { it != y1 }).joinToString("–"),
            dir.mapNotNull(names::get), wri.mapNotNull(names::get), cast.mapNotNull(names::get), gen.mapNotNull(names::get), cou.mapNotNull(names::get),
            claimStr(e, "P2437")?.trimStart('+')?.toDoubleOrNull()?.toInt(), claimStr(e, "P1113")?.trimStart('+')?.toDoubleOrNull()?.toInt(),
            claimStr(e, "P2047")?.trimStart('+')?.toDoubleOrNull()?.toInt(), imdb, rating, votes, poster, description.ifBlank { desc0 }, wiki,
        )
    }
}
