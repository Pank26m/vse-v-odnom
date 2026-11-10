package com.pank.vsevodnom.data

import android.content.Context
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кино из канала Telegram: каждое сообщение с видео → запись (фильм или серия). Сериалы собираются по названию,
 * сезон и серия берутся из подписи («2 сезон 6 серия», «S02E06», «2x06») или из имени файла («…_2s6s1q720.mp4»).
 * Разбор — чистые функции (тест TgFilmsTest). Список канала хранится на телефоне (files/tg/index_<канал>.json),
 * самих видео на телефоне нет.
 */
object TgFilms {
    data class Parsed(val title: String, val season: Int?, val episode: Int?, val quality: String?)

    data class Entry(
        val msgId: Long, val chatId: Long, val caption: String, val fileName: String, val duration: Int,
        val width: Int, val height: Int, val size: Long, val date: Int, val mini: String?,
    ) {
        val parsed by lazy { parse(caption, fileName) }
        fun toJson(): JSONObject = JSONObject().put("m", msgId).put("c", chatId).put("cap", caption).put("fn", fileName).put("d", duration)
            .put("w", width).put("h", height).put("s", size).put("dt", date).put("mini", mini ?: "")
        companion object {
            fun of(o: JSONObject) = Entry(o.getLong("m"), o.getLong("c"), o.optString("cap"), o.optString("fn"), o.optInt("d"), o.optInt("w"), o.optInt("h"),
                o.optLong("s"), o.optInt("dt"), o.optString("mini").ifBlank { null })
        }
    }

    /** Фильм или сериал (серии по сезонам). */
    data class Title(val key: String, val name: String, val series: Boolean, val items: List<Entry>) {
        val seasons: List<Int> get() = items.mapNotNull { it.parsed.season }.distinct().sorted().ifEmpty { listOf(1) }
        fun episodes(season: Int) = items.filter { (it.parsed.season ?: 1) == season }.sortedWith(compareBy({ it.parsed.episode ?: 0 }, { it.msgId }))
        val newest: Int get() = items.maxOf { it.date }
    }

    // ---------- разбор ----------
    private val STUDIOS = listOf("кубик в кубе", "kubik³", "kubik3", "kubik", "пифагор", "lostfilm", "hdrezka studio", "hdrezka", "rezka", "jaskier", "newstudio", "alexfilm",
        "tvshows", "amedia", "дубляж", "ozz", "red head sound", "coldfilm", "baibako", "sdi media", "novamedia", "flarrow", "ultradox", "кравец", "гоблин",
        "профессиональный многоголосый", "многоголосый", "двухголосый", "одноголосый", "оригинал", "субтитры", "netflix", "hd", "fullhd", "webrip", "web-dl", "bdrip", "hdrip")
    private val LAT_SE = Regex("s(\\d{1,2})\\s*[ ._-]?\\s*e(\\d{1,3})")
    private val X_SE = Regex("(?:^|[^0-9a-z])(\\d{1,2})x(\\d{2,3})(?:$|[^0-9])")
    private val SEASON = listOf(Regex("(\\d{1,2})\\s*(?:-?й\\s*)?(?:сезон|season|sezon)"), Regex("(?:сезон|season|sezon)\\s*[:№#]?\\s*(\\d{1,2})"))
    private val EPISODE = listOf(Regex("(\\d{1,3})\\s*(?:-?я\\s*)?(?:серия|серія|эпизод|епізод|episode|ep)"), Regex("(?:серия|серія|эпизод|епізод|episode|ep)\\s*[:№#.]?\\s*(\\d{1,3})"))
    private val KUBIK = Regex("(?:^|[^0-9])(\\d{1,2})s(\\d{1,3})(?:s\\d+)?(?:q\\d+)?(?:$|[^0-9])")
    private val QUALITY = Regex("(?:\\[?q?(2160|1080|720|480|360)p?\\]?)")

    /** Название, сезон, серия, качество — из подписи и имени файла. */
    fun parse(caption: String, fileName: String): Parsed {
        val fn = fileName.substringBeforeLast('.').replace('_', ' ').replace('.', ' ')
        val cap = caption.trim()
        val low = (cap + "\n" + fn).lowercase()
        var season: Int? = null; var episode: Int? = null
        LAT_SE.find(low)?.let { season = it.groupValues[1].toInt(); episode = it.groupValues[2].toInt() }
        if (season == null) X_SE.find(low)?.let { season = it.groupValues[1].toInt(); episode = it.groupValues[2].toInt() }
        if (season == null) SEASON.firstNotNullOfOrNull { it.find(low) }?.let { season = it.groupValues[1].toInt() }
        if (episode == null) EPISODE.firstNotNullOfOrNull { it.find(low) }?.let { episode = it.groupValues[1].toInt() }
        if (season == null && episode == null) KUBIK.find(fn.lowercase())?.let { season = it.groupValues[1].toInt(); episode = it.groupValues[2].toInt() }
        if (episode != null && season == null) season = 1
        val quality = QUALITY.find(fn.lowercase())?.groupValues?.get(1)?.let { "${it}p" }
        val titleSrc = cap.lines().map { it.trim() }.firstOrNull { line -> line.isNotBlank() && clean(line).length >= 2 } ?: fn
        val title = clean(titleSrc).ifBlank { clean(fn) }.ifBlank { fileName }
        return Parsed(title, season, episode, quality)
    }

    /** Название без сезона/серии, качества, студий озвучки, скобок и хвостов вроде «|». */
    fun clean(s0: String): String {
        var s = s0.replace('_', ' ')
        s = s.substringBefore(" | ").substringBefore("|")
        s = s.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\([^)]*\\)"), " ")
        var low = s.lowercase()
        for (r in listOf(LAT_SE, X_SE, KUBIK) + SEASON + EPISODE) {
            r.find(low)?.let { m -> s = s.removeRange(m.range); low = s.lowercase() }
        }
        for (st in STUDIOS) {
            var i = low.indexOf(st)
            while (i >= 0) { s = s.removeRange(i, i + st.length); low = s.lowercase(); i = low.indexOf(st) }
        }
        s = s.replace(Regex("(?:2160|1080|720|480|360)p"), " ").replace(Regex("q(?:2160|1080|720|480|360)"), " ")
        s = s.replace(Regex("[\\s.,:;·•–—-]+$"), "").replace(Regex("^[\\s.,:;·•–—-]+"), "").replace(Regex("\\s{2,}"), " ").trim()
        return s
    }

    /** Ключ для сборки серий одного сериала: только буквы и цифры, без регистра. */
    fun key(title: String) = title.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    fun group(entries: List<Entry>): List<Title> = entries.groupBy { key(it.parsed.title) }.map { (k, l) ->
        val series = l.any { it.parsed.episode != null || it.parsed.season != null } || l.size > 1
        val name = l.groupingBy { it.parsed.title }.eachCount().maxByOrNull { it.value }?.key ?: l.first().parsed.title
        Title(k, name, series, l)
    }.sortedByDescending { it.newest }

    // ---------- из TDLib ----------
    /** Видео-файл сообщения (видео или документ-видео). */
    fun fileOf(m: TdApi.Message): TdApi.File? = when (val c = m.content) {
        is TdApi.MessageVideo -> c.video.video
        is TdApi.MessageDocument -> c.document.document.takeIf { c.document.mimeType.startsWith("video") || c.document.fileName.lowercase().let { n -> n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".webm") } }
        else -> null
    }

    fun entryOf(m: TdApi.Message): Entry? {
        val f = fileOf(m) ?: return null
        return when (val c = m.content) {
            is TdApi.MessageVideo -> Entry(m.id, m.chatId, c.caption?.text.orEmpty(), c.video.fileName.orEmpty(), c.video.duration, c.video.width, c.video.height,
                f.size.takeIf { it > 0 } ?: f.expectedSize, m.date, c.video.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
            is TdApi.MessageDocument -> Entry(m.id, m.chatId, c.caption?.text.orEmpty(), c.document.fileName.orEmpty(), 0, 0, 0,
                f.size.takeIf { it > 0 } ?: f.expectedSize, m.date, c.document.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
            else -> null
        }
    }

    /** Обложка сообщения (файл миниатюры TDLib). */
    fun thumbOf(m: TdApi.Message): Int? = when (val c = m.content) {
        is TdApi.MessageVideo -> c.video.thumbnail?.file?.id
        is TdApi.MessageDocument -> c.document.thumbnail?.file?.id
        else -> null
    }

    // ---------- хранение списка ----------
    private fun indexFile(ctx: Context, chatId: Long) = File(ctx.filesDir, "tg/index_$chatId.json")
    fun load(ctx: Context, chatId: Long): List<Entry> = runCatching {
        val a = JSONArray(indexFile(ctx, chatId).readText()); (0 until a.length()).map { Entry.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun save(ctx: Context, chatId: Long, l: List<Entry>) = runCatching {
        indexFile(ctx, chatId).apply { parentFile?.mkdirs() }.writeText(JSONArray().apply { l.forEach { put(it.toJson()) } }.toString())
    }

    // ---------- канал и позиция просмотра ----------
    private fun sp(ctx: Context) = ctx.getSharedPreferences("tg", Context.MODE_PRIVATE)
    fun chat(ctx: Context): Tg.ChatRef? = sp(ctx).getLong("chat", 0L).takeIf { it != 0L }?.let { Tg.ChatRef(it, sp(ctx).getString("chat_title", "Кино").orEmpty()) }
    fun setChat(ctx: Context, c: Tg.ChatRef?) { sp(ctx).edit().putLong("chat", c?.id ?: 0L).putString("chat_title", c?.title.orEmpty()).apply() }
    fun position(ctx: Context, e: Entry): Long = sp(ctx).getLong("pos_${e.chatId}_${e.msgId}", 0L)
    fun setPosition(ctx: Context, e: Entry, ms: Long, durMs: Long) {
        val done = durMs > 0 && ms > durMs - 3 * 60_000   // последние 3 минуты (титры) — считаем просмотренным
        sp(ctx).edit().putLong("pos_${e.chatId}_${e.msgId}", if (done) -1L else ms).putLong("last_${e.chatId}", e.msgId).apply()
    }
    /** Просмотрено (−1) / начато (>0) / не начато (0). */
    fun watched(ctx: Context, e: Entry) = position(ctx, e) == -1L
    fun lastWatched(ctx: Context, chatId: Long): Long = sp(ctx).getLong("last_$chatId", 0L)
}
