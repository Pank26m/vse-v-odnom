package com.pank.vsevodnom.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Сведения о месте из интернета: OpenStreetMap (Nominatim — адрес, часы работы, телефон, сайт, ссылка на Википедию)
 * и краткая статья Википедии. Без ключей. Nominatim — не чаще раза в секунду (Osm сам ждёт).
 */
object PlaceInfo {
    data class Info(
        val name: String = "",
        val address: String = "",
        val admin: List<String> = emptyList(),
        val tags: Map<String, String> = emptyMap(),
        val osmUrl: String = "",
        val wiki: Wiki? = null,
    )
    data class Wiki(val title: String, val extract: String, val image: String?, val url: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.16 (personal Android app)")
        c.setRequestProperty("Accept-Language", "ru,uk,en")
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun info(o: JSONObject): Info {
        val a = o.optJSONObject("address") ?: JSONObject()
        val ex = o.optJSONObject("extratags") ?: JSONObject()
        val tags = ex.keys().asSequence().associateWith { ex.optString(it) }
        val admin = listOf("country", "state", "region", "district", "county", "municipality", "city", "town", "village", "suburb", "city_district", "borough", "quarter")
            .mapNotNull { k -> a.optString(k).ifBlank { null } }.distinct()
        val street = listOfNotNull(a.optString("road").ifBlank { null }, a.optString("house_number").ifBlank { null }).joinToString(", ")
        val type = o.optString("osm_type"); val id = o.optLong("osm_id")
        return Info(o.optString("name"), street.ifBlank { o.optString("display_name").split(", ").take(3).joinToString(", ") }, admin, tags,
            if (type.isNotBlank() && id > 0) "https://www.openstreetmap.org/$type/$id" else "")
    }

    /** Найти объект: по имени рядом с точкой, иначе — что по адресу этой точки. */
    suspend fun load(name: String?, lat: Double, lon: Double, withWiki: Boolean = true): Info? = withContext(Dispatchers.IO) {
        val base = "https://nominatim.openstreetmap.org/"
        val o: JSONObject? = runCatching {
            if (!name.isNullOrBlank()) {
                val d = 0.004
                val box = "${lon - d},${lat + d},${lon + d},${lat - d}"
                val arr = JSONArray(Osm.raw(base + "search?format=jsonv2&addressdetails=1&extratags=1&limit=1&bounded=1&viewbox=$box&q=" + URLEncoder.encode(name, "UTF-8")))
                if (arr.length() > 0) arr.getJSONObject(0) else null
            } else null
        }.getOrNull() ?: runCatching { JSONObject(Osm.raw(base + "reverse?format=jsonv2&addressdetails=1&extratags=1&zoom=18&lat=$lat&lon=$lon")) }.getOrNull()
        val inf = o?.let { info(it) } ?: return@withContext null
        if (!withWiki) return@withContext inf
        val w = inf.tags["wikipedia"]?.let { wikiTag ->
            val lang = wikiTag.substringBefore(':', "uk").ifBlank { "uk" }; val title = wikiTag.substringAfter(':')
            summary(lang, title)?.let { s -> if (lang != "ru") ruTitle(lang, title)?.let { summary("ru", it) } ?: s else s }
        } ?: inf.tags["wikidata"]?.let { qid -> wikidataTitle(qid)?.let { (l, t) -> summary(l, t) } }
        inf.copy(wiki = w)
    }

    /**
     * Статья Википедии для тропы/маршрута: по тегу wikipedia («uk:Название») или поиском по названию (в заголовке
     * должно быть главное слово названия — чтобы не подсунуть случайную статью).
     */
    suspend fun wikiFor(name: String, tag: String?): Wiki? = withContext(Dispatchers.IO) {
        tag?.takeIf { it.isNotBlank() }?.let { w ->
            val lang = w.substringBefore(':', "uk").ifBlank { "uk" }; val title = w.substringAfter(':')
            return@withContext summary(lang, title)?.let { s -> if (lang != "ru") ruTitle(lang, title)?.let { summary("ru", it) } ?: s else s }
        }
        val key = name.lowercase().split(Regex("[^a-zа-яёіїєґ0-9]+")).filter { it.length >= 4 }.maxByOrNull { it.length } ?: return@withContext null
        for (lang in listOf("ru", "uk")) {
            val t = runCatching {
                val o = JSONObject(get("https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + URLEncoder.encode(name, "UTF-8")))
                val a = o.getJSONObject("query").getJSONArray("search")
                (0 until a.length()).map { a.getJSONObject(it).getString("title") }.firstOrNull { it.lowercase().contains(key.take(key.length - 1)) }
            }.getOrNull() ?: continue
            summary(lang, t)?.let { return@withContext it }
        }
        null
    }

    private fun summary(lang: String, title: String): Wiki? = runCatching {
        val o = JSONObject(get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")))
        Wiki(o.optString("title"), o.optString("extract"), o.optJSONObject("thumbnail")?.optString("source"),
            o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "https://$lang.wikipedia.org/wiki/$title")
    }.getOrNull()?.takeIf { it.extract.isNotBlank() }

    /** Есть ли русская статья — через межъязыковые ссылки. */
    private fun ruTitle(lang: String, title: String): String? = runCatching {
        val o = JSONObject(get("https://$lang.wikipedia.org/w/api.php?action=query&prop=langlinks&lllang=ru&format=json&titles=" + URLEncoder.encode(title, "UTF-8")))
        val pages = o.getJSONObject("query").getJSONObject("pages")
        pages.getJSONObject(pages.keys().next()).optJSONArray("langlinks")?.getJSONObject(0)?.optString("*")
    }.getOrNull()?.ifBlank { null }

    private fun wikidataTitle(qid: String): Pair<String, String>? = runCatching {
        val o = JSONObject(get("https://www.wikidata.org/wiki/Special:EntityData/$qid.json")).getJSONObject("entities").getJSONObject(qid).getJSONObject("sitelinks")
        listOf("ruwiki" to "ru", "ukwiki" to "uk", "enwiki" to "en").firstNotNullOfOrNull { (k, l) -> o.optJSONObject(k)?.optString("title")?.let { l to it } }
    }.getOrNull()

    /** Подписи полезных тегов OSM. */
    val TAG_NAMES = linkedMapOf(
        "opening_hours" to "Часы работы", "phone" to "Телефон", "contact:phone" to "Телефон", "website" to "Сайт", "contact:website" to "Сайт",
        "email" to "Почта", "cuisine" to "Кухня", "wheelchair" to "Доступ на коляске", "internet_access" to "Интернет", "operator" to "Владелец",
        "brand" to "Сеть", "ele" to "Высота", "population" to "Население", "fee" to "Платно", "capacity" to "Мест", "level" to "Этаж",
        "contact:instagram" to "Instagram", "contact:facebook" to "Facebook", "contact:telegram" to "Telegram",
    )
}
