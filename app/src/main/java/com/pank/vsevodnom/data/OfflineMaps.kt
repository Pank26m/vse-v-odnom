package com.pank.vsevodnom.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Офлайн-карты стран: каталог и файлы лежат в релизе maps-v1 репозитория (собираются maps/build.sh на GitHub Actions).
 * Страна = векторная карта (OpenMapTiles), рельеф (raster-dem), линии высот, офлайн-поиск, граница.
 * Скачивание — системным DownloadManager (докачка, уведомление); файлы — в папке приложения на выбранном хранилище.
 */
object OfflineMaps {
    const val BASE = "https://github.com/Pank26m/vse-v-odnom/releases/download/maps-v1/"
    val KINDS = listOf("map", "dem", "contours", "search", "routing")
    val KIND_NAMES = mapOf("map" to "Карта", "dem" to "Рельеф", "contours" to "Линии высот", "search" to "Поиск", "routing" to "Маршруты")

    data class Part(val file: String, val size: Long)
    data class Country(val id: String, val name: String, val bbox: List<Double>, val built: String, val parts: Map<String, List<Part>>, val shape: String,
                       val partBuilt: Map<String, String> = emptyMap(), val metro: String? = null, val entrances: String? = null, val lines: String? = null, val linesBuilt: String? = null) {
        fun size(kinds: Collection<String> = KINDS) = kinds.sumOf { k -> parts[k].orEmpty().sumOf { it.size } }
    }
    data class Catalog(val countries: List<Country>, val world: Part?, val labels: Part?)

    var catalog by mutableStateOf<Catalog?>(null); private set
    var catalogError by mutableStateOf<String?>(null); private set
    /** Растёт при любом изменении набора скачанного — экран карты пересобирает стиль. */
    var version by mutableIntStateOf(0); private set
    /** Идёт загрузка: id → доля 0..1. */
    val progress = mutableStateMapOf<String, Float>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    // ---------- хранилище ----------
    /** Доступные хранилища: память телефона и SD-карта (если есть). */
    fun storages(ctx: Context): List<File> = ctx.getExternalFilesDirs(null).filterNotNull().filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
    fun storageIndex(ctx: Context) = prefs(ctx).getInt("storage", 0)
    fun dir(ctx: Context): File {
        val list = storages(ctx)
        val base = list.getOrNull(storageIndex(ctx)) ?: list.firstOrNull() ?: ctx.filesDir
        return File(base, "maps").apply { mkdirs() }
    }
    fun free(f: File): Long = runCatching { StatFs(f.path).availableBytes }.getOrDefault(0)

    /** Перенос карт на другое хранилище. */
    suspend fun moveStorage(ctx: Context, index: Int) = withContext(Dispatchers.IO) {
        val from = dir(ctx)
        prefs(ctx).edit().putInt("storage", index).apply()
        val to = dir(ctx)
        if (from.canonicalPath != to.canonicalPath) from.listFiles()?.forEach { f -> runCatching { f.copyTo(File(to, f.name), true); f.delete() } }
        version++
    }

    // ---------- что скачано ----------
    /** Скачанные страны: id → (дата сборки, набор частей). */
    fun installed(ctx: Context): Map<String, Pair<String, Set<String>>> {
        val o = runCatching { JSONObject(prefs(ctx).getString("installed", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k); val arr = e.optJSONArray("parts") ?: JSONArray()
            e.optString("built") to (0 until arr.length()).map { arr.getString(it) }.filter { file(ctx, k, it).exists() }.toSet()
        }.filterValues { it.second.isNotEmpty() }
    }
    private fun markInstalled(ctx: Context, id: String, built: String, kinds: Set<String>) {
        val o = runCatching { JSONObject(prefs(ctx).getString("installed", "{}")!!) }.getOrDefault(JSONObject())
        val old = o.optJSONObject(id)?.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val all = (old + kinds).distinct()
        val e = o.optJSONObject(id) ?: JSONObject()
        if (all.isEmpty()) o.remove(id) else {
            e.put("built", built).put("parts", JSONArray(all))
            catalog?.countries?.firstOrNull { it.id == id }?.let { c -> kinds.forEach { k -> e.put("b_$k", c.partBuilt[k] ?: built) } }
            o.put(id, e)
        }
        prefs(ctx).edit().putString("installed", o.toString()).apply()
        version++
    }

    fun fileName(id: String, kind: String) = when (kind) { "map" -> "$id.mbtiles"; "dem" -> "$id-dem.mbtiles"; "contours" -> "$id-contours.mbtiles"; "search" -> "$id-search.db"; "routing" -> "$id-routing.ok"; else -> "$id.$kind" }
    /** Сегменты BRouter (общие для стран): maps/segments4 (файлы .rd5). */
    fun segmentsDir(ctx: Context) = File(dir(ctx), "segments4").apply { mkdirs() }
    fun file(ctx: Context, id: String, kind: String) = File(dir(ctx), fileName(id, kind))
    fun shapeFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun metroFile(ctx: Context, id: String) = File(dir(ctx), "$id-metro.json")
    fun entrancesFile(ctx: Context, id: String) = File(dir(ctx), "$id-entrances.mbtiles")
    /** Заборы и пешие маршруты (maps/lines.py). */
    fun linesFile(ctx: Context, id: String) = File(dir(ctx), "$id-lines.mbtiles")

    /** Рельеф мира: скачанный (z0…6) или встроенный в приложение (z0…4). */
    fun worldRelief(ctx: Context): File {
        File(dir(ctx), "world-relief.mbtiles").takeIf { it.exists() && it.length() > 0 }?.let { return it }
        val f = File(ctx.filesDir, "map/world-z4.mbtiles")
        if (!f.exists()) runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            ctx.assets.open("map/world-z4.mbtiles").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        return f
    }
    fun worldLabels(ctx: Context): File? = File(dir(ctx), "world-labels.mbtiles").takeIf { it.exists() && it.length() > 0 }
    fun worldDetailed(ctx: Context) = File(dir(ctx), "world-relief.mbtiles").exists()

    /** Границы скачанных стран (GeoJSON-геометрии) — для штриховки и подложки. */
    fun shapes(ctx: Context): List<Pair<String, JSONObject>> = installed(ctx).keys.mapNotNull { id ->
        runCatching { id to JSONObject(shapeFile(ctx, id).readText()).getJSONObject("geometry") }.getOrNull()
    }

    // ---------- каталог ----------
    private fun parse(s: String): Catalog {
        val o = JSONObject(s)
        fun parts(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).let { p -> Part(p.getString("file"), p.optLong("size")) } }
        val arr = o.optJSONArray("countries") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i); val bb = c.optJSONArray("bbox")
            Country(c.getString("id"), c.optString("name", c.getString("id")), (0 until (bb?.length() ?: 0)).map { bb!!.getDouble(it) },
                c.optString("built"), KINDS.associateWith { parts(c.optJSONArray(it)) }, c.optString("shape", c.getString("id") + ".json"),
                KINDS.associateWith { k -> c.optString(k + "_built", c.optString("built")) }, c.optString("metro").ifBlank { null }, c.optString("entrances").ifBlank { null },
                c.optString("lines").ifBlank { null }, c.optString("lines_built").ifBlank { null })
        }
        fun one(k: String) = o.optJSONObject(k)?.let { Part(it.getString("file"), it.optLong("size")) }
        return Catalog(list.sortedBy { it.name }, one("world"), one("labels"))
    }

    suspend fun loadCatalog(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val cache = File(dir(ctx), "catalog.json")
        if (catalog == null && cache.exists()) runCatching { catalog = parse(cache.readText()) }
        if (!force && catalog != null && System.currentTimeMillis() - cache.lastModified() < 6 * 3600_000) return@withContext
        runCatching {
            val c = URL(BASE + "catalog.json").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            catalog = parse(txt); cache.writeText(txt); catalogError = null
        }.onFailure { if (catalog == null) catalogError = if (Net.online) "Каталог карт пока недоступен" else "Нет интернета" }
    }

    /** Какие из скачанных частей обновились на сервере (сравнение дат сборки по каждой части). */
    fun outdatedParts(ctx: Context, c: Country): Set<String> {
        val inst = installed(ctx)[c.id] ?: return emptySet()
        val e = runCatching { JSONObject(prefs(ctx).getString("installed", "{}")!!).optJSONObject(c.id) }.getOrNull() ?: return emptySet()
        return inst.second.filter { k -> val have = e.optString("b_$k", inst.first); val now = c.partBuilt[k].orEmpty(); now.isNotEmpty() && now > have }.toSet()
    }
    fun updateAvailable(ctx: Context, c: Country): Boolean = outdatedParts(ctx, c).isNotEmpty()

    // ---------- загрузка ----------
    private val active = HashMap<String, List<Long>>()

    /** Скачать части страны (или «world»/«labels»). Части больше 2 ГБ приходят кусками и склеиваются. */
    fun download(ctx: Context, c: Country, kinds: Set<String>) {
        val files = kinds.associateWith { c.parts[it].orEmpty() }.filterValues { it.isNotEmpty() }
        val parts = files.values.flatten() + Part(c.shape, 0) + listOfNotNull(c.metro?.let { Part(it, 0) }, c.entrances?.takeIf { "map" in kinds }?.let { Part(it, 0) }, c.lines?.takeIf { "map" in kinds }?.let { Part(it, 0) })
        val ids = enqueue(ctx, c.id, parts) ?: return
        savePending(ctx, c.id, JSONObject().put("type", "country").put("kinds", JSONArray(files.keys.toList())).put("ids", JSONArray(ids))
            .put("files", JSONArray(parts.map { it.file })).put("total", parts.sumOf { it.size }))
        watch(ctx, c.id, ids, parts, parts.sumOf { it.size }) { finishCountry(ctx, c, files.keys) }
    }

    private fun finishCountry(ctx: Context, c: Country, kinds: Set<String>) {
        if ("search" in kinds) OfflineSearch.closeAll()
        kinds.forEach { k ->
            val parts = c.parts[k].orEmpty()
            val target = file(ctx, c.id, k)
            if (k == "routing") {
                parts.forEach { p -> File(dir(ctx), p.file + ".dl").renameTo(File(segmentsDir(ctx), p.file).also { it.delete() }) }
                target.writeText(parts.joinToString("\n") { it.file })
            } else if (parts.size == 1) File(dir(ctx), parts[0].file + ".dl").renameTo(target.also { it.delete() })
            else if (parts.size > 1) {
                val tmp = File(target.path + ".join")
                tmp.outputStream().use { out -> parts.forEach { p -> File(dir(ctx), p.file + ".dl").inputStream().use { it.copyTo(out) } } }
                parts.forEach { p -> File(dir(ctx), p.file + ".dl").delete() }
                target.delete(); tmp.renameTo(target)
            }
        }
        File(dir(ctx), c.shape + ".dl").renameTo(shapeFile(ctx, c.id).also { it.delete() })
        c.metro?.let { m -> File(dir(ctx), "$m.dl").takeIf { it.exists() }?.renameTo(metroFile(ctx, c.id).also { it.delete() }) }
        c.entrances?.let { m -> File(dir(ctx), "$m.dl").takeIf { it.exists() }?.renameTo(entrancesFile(ctx, c.id).also { it.delete() }) }
        c.lines?.let { m -> File(dir(ctx), "$m.dl").takeIf { it.exists() }?.renameTo(linesFile(ctx, c.id).also { it.delete() }) }
        markInstalled(ctx, c.id, c.built, kinds)
    }

    fun downloadWorld(ctx: Context, p: Part, key: String) {
        val ids = enqueue(ctx, key, listOf(p)) ?: return
        savePending(ctx, key, JSONObject().put("type", "world").put("file", p.file).put("ids", JSONArray(ids)).put("total", p.size))
        watch(ctx, key, ids, listOf(p), p.size) { finishWorld(ctx, p.file) }
    }

    private fun finishWorld(ctx: Context, file: String) { File(dir(ctx), "$file.dl").renameTo(File(dir(ctx), file).also { it.delete() }); version++ }

    private fun pending(ctx: Context) = runCatching { JSONObject(prefs(ctx).getString("pending", "{}")!!) }.getOrDefault(JSONObject())
    private fun savePending(ctx: Context, key: String, o: JSONObject?) = synchronized(this) {
        val p = pending(ctx); if (o == null) p.remove(key) else p.put(key, o)
        prefs(ctx).edit().putString("pending", p.toString()).apply()
    }

    /** Мировые слои (подписи и подробный рельеф, ~20 МБ) — сами по Wi-Fi, один раз. */
    /** Мелкие файлы страны (метро, подъезды), появившиеся на сервере после скачивания карты, — докачать молча. */
    fun ensureExtras(ctx: Context) {
        val cat = catalog ?: return
        if (!Net.online) return
        installed(ctx).filterValues { "map" in it.second }.keys.forEach { id ->
            val c = cat.countries.firstOrNull { it.id == id } ?: return@forEach
            listOfNotNull(c.metro?.let { it to metroFile(ctx, id) }, c.entrances?.let { it to entrancesFile(ctx, id) }, c.lines?.let { it to linesFile(ctx, id) }).forEach { (name, f) ->
                // заборы и тропы пересобираются отдельно — новую версию докачиваем сами
                val stamp = if (f == linesFile(ctx, id)) c.linesBuilt else null
                val fresh = stamp == null || prefs(ctx).getString("built_$name", null) == stamp
                if (f.exists() && f.length() > 0 && fresh) return@forEach
                AppScope.io.launch {
                    runCatching {
                        val conn = URL(BASE + name).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000; conn.readTimeout = 90_000; conn.instanceFollowRedirects = true
                        val tmp = File(f.path + ".tmp"); conn.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        if (tmp.length() > 0 && tmp.renameTo(f)) { stamp?.let { prefs(ctx).edit().putString("built_$name", it).apply() }; version++ }
                    }
                }
            }
        }
    }

    fun autoWorld(ctx: Context) {
        val cat = catalog ?: return
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered || !Net.online) return
        if (prefs(ctx).getBoolean("no_auto_world", false)) return
        cat.labels?.takeIf { worldLabels(ctx) == null && !active.containsKey("labels") && !pending(ctx).has("labels") }?.let { downloadWorld(ctx, it, "labels") }
        cat.world?.takeIf { !worldDetailed(ctx) && !active.containsKey("world") && !pending(ctx).has("world") }?.let { downloadWorld(ctx, it, "world") }
    }

    /** После перезапуска приложения: досмотреть загрузки, начатые раньше (DownloadManager качает и без нас). */
    fun resume(ctx: Context) {
        val p = pending(ctx); val cat = catalog ?: return
        p.keys().asSequence().toList().forEach { key ->
            if (active.containsKey(key)) return@forEach
            val o = p.getJSONObject(key); val a = o.getJSONArray("ids"); val ids = (0 until a.length()).map { a.getLong(it) }
            when (o.optString("type")) {
                "world" -> { val f = o.getString("file"); watch(ctx, key, ids, listOf(Part(f, o.optLong("total"))), o.optLong("total")) { finishWorld(ctx, f) } }
                else -> {
                    val c = cat.countries.firstOrNull { it.id == key } ?: run { savePending(ctx, key, null); return@forEach }
                    val k = o.getJSONArray("kinds"); val kinds = (0 until k.length()).map { k.getString(it) }.toSet()
                    val fl = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { Part(a.getString(it), 0) } } ?: ids.map { Part("", 0) }
                    watch(ctx, key, ids, fl, o.optLong("total")) { finishCountry(ctx, c, kinds) }
                }
            }
        }
    }

    private fun enqueue(ctx: Context, key: String, parts: List<Part>): List<Long>? {
        if (active.containsKey(key)) return null
        return parts.map { p -> enqueueOne(ctx, p) }
    }

    /** Одна часть. Уже скачанную целиком (размер совпал) не качаем заново — id = -1. */
    private fun enqueueOne(ctx: Context, p: Part): Long {
        val f = File(dir(ctx), p.file + ".dl")
        if (p.size > 0 && f.length() == p.size) return -1L
        f.delete()
        return ctx.getSystemService(DownloadManager::class.java).enqueue(DownloadManager.Request(Uri.parse(BASE + p.file))
            .setTitle("Карты: ${p.file}")
            .setDestinationUri(Uri.fromFile(f))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true))
    }

    private fun watch(ctx: Context, key: String, ids0: List<Long>, parts: List<Part>, totalBytes: Long, onDone: () -> Unit) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val ids = ids0.toMutableList()
        val tries = IntArray(ids.size)
        active[key] = ids; progress[key] = 0f; lastError = null
        val total = totalBytes.coerceAtLeast(1)
        AppScope.io.launch {
            while (true) {
                var done = 0L; var ok = 0; var gaveUp: String? = null
                for (i in ids.indices) {
                    val id = ids[i]
                    if (id == -1L) { ok++; done += parts.getOrNull(i)?.size ?: 0; continue }
                    val row = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) else null
                    }
                    when {
                        row == null -> gaveUp = "загрузка отменена"
                        row.first == DownloadManager.STATUS_SUCCESSFUL -> { ok++; done += row.second.coerceAtLeast(0) }
                        row.first == DownloadManager.STATUS_FAILED -> {
                            // сервер GitHub иногда отвечает 502 — перезапускаем только эту часть, до 3 раз
                            runCatching { dm.remove(id) }
                            val p = parts.getOrNull(i)
                            if (p != null && p.file.isNotEmpty() && tries[i] < 3) { tries[i]++; ids[i] = enqueueOne(ctx, p) }
                            else gaveUp = "код ${row.third}"
                        }
                        else -> done += row.second.coerceAtLeast(0)
                    }
                }
                if (gaveUp != null) { lastError = "Загрузка прервалась ($gaveUp). Скачанные части сохранены — нажми ещё раз, докачаются остальные."; break }
                progress[key] = (done.toFloat() / total).coerceIn(0f, 0.99f)
                if (ok == ids.size) { runCatching { onDone() }.onFailure { lastError = "Не удалось сохранить: ${it.message}" }; break }
                delay(1000)
            }
            savePending(ctx, key, null)
            active.remove(key); progress.remove(key)
        }
    }

    /** Последняя ошибка загрузки — показывается в «Загрузке карт». */
    var lastError by mutableStateOf<String?>(null)

    fun cancel(ctx: Context, key: String) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        active[key]?.forEach { if (it >= 0) runCatching { dm.remove(it) } }
    }

    fun delete(ctx: Context, id: String, kinds: Set<String> = KINDS.toSet()) {
        if ("routing" in kinds) runCatching { file(ctx, id, "routing").readLines().forEach { File(segmentsDir(ctx), it.trim()).delete() } }
        kinds.forEach { file(ctx, id, it).delete() }
        val o = runCatching { JSONObject(prefs(ctx).getString("installed", "{}")!!) }.getOrDefault(JSONObject())
        o.optJSONObject(id)?.let { e ->
            val a = e.optJSONArray("parts") ?: JSONArray()
            val left = (0 until a.length()).map { a.getString(it) }.filter { it !in kinds }
            if (left.isEmpty()) { o.remove(id); listOf(shapeFile(ctx, id), metroFile(ctx, id), entrancesFile(ctx, id), linesFile(ctx, id)).forEach { it.delete() } } else e.put("parts", JSONArray(left))
        }
        prefs(ctx).edit().putString("installed", o.toString()).apply()
        version++
    }

    fun deleteWorld(ctx: Context, file: String) { File(dir(ctx), file).delete(); prefs(ctx).edit().putBoolean("no_auto_world", true).apply(); version++ }

    /** Сколько места занимают скачанные карты. */
    fun usedBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0

    fun human(b: Long) = when { b >= 1L shl 30 -> "%.1f ГБ".format(b / 1073741824.0); b >= 1L shl 20 -> "%.0f МБ".format(b / 1048576.0); b > 0 -> "${(b / 1024).coerceAtLeast(1)} КБ"; else -> "—" }
}
