package com.pank.vsevodnom.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import globus.glmap.GLMapDownloadTask
import globus.glmap.GLMapInfo
import globus.glmap.GLMapLocaleSettings
import globus.glmap.GLMapManager
import globus.glmap.MapPoint

/**
 * Движок карты GLMap (тот же, что у Guru Maps): своя карта мира, офлайн-карты стран с рельефом для 3D.
 * Ключ API — только в настройках (prefs «api_keys»), в коде и на GitHub его нет. Цена: первые 5000 скачиваний
 * данных в месяц и 50 000 онлайн-тайлов бесплатно — одному человеку хватит навсегда.
 */
object GlMap {
    /** Инициализирован ли SDK (без ключа — нет, тогда карта работает на старом движке MapLibre). */
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Растёт при любом изменении скачанных карт — повод перерисовать список и плитки. */
    var version by mutableIntStateOf(0); private set

    fun key(ctx: Context) = ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE).getString("glmap", "").orEmpty().trim()
    fun setKey(ctx: Context, k: String) = ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE).edit().putString("glmap", k.trim()).apply()

    /** Какой движок рисует карту: glmap (по умолчанию, если есть ключ) или maplibre (запасной). */
    fun engine(ctx: Context) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getString("engine", "glmap") ?: "glmap"
    fun setEngine(ctx: Context, e: String) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("engine", e).apply()
    fun use(ctx: Context) = key(ctx).isNotBlank() && init(ctx)

    private val listener = object : GLMapManager.StateListener {
        override fun onStartDownloading(task: GLMapDownloadTask) { bump() }
        override fun onDownloadProgress(task: GLMapDownloadTask) { bump() }
        override fun onFinishDownloading(task: GLMapDownloadTask) { bump() }
        override fun onStateChanged(map: GLMapInfo?, dataSet: Int) { bump() }
    }

    private fun bump() { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) version++ else main.post { version++ } }

    @Synchronized
    fun init(ctx: Context): Boolean {
        if (ready) return true
        val k = key(ctx); if (k.isBlank()) { error = "Нет ключа GLMap"; return false }
        val ok = runCatching { GLMapManager.Initialize(ctx.applicationContext, k, null) }.onFailure { error = "GLMap: ${it.message}" }.getOrDefault(false)
        if (!ok) { error = error ?: "GLMap не запустился (мало места на телефоне?)"; return false }
        runCatching { globus.glsearch.GLSearch.Initialize(ctx.applicationContext) }
        runCatching { GLMapManager.SetTileDownloadingAllowed(Net.online) }
        runCatching { GLMapManager.addStateListener(listener) }
        refreshList()
        ready = true; error = null
        return true
    }

    var listLoading by mutableStateOf(false); private set
    var listError by mutableStateOf<String?>(null); private set
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Какой сервер GLMap отвечает (для подписи в настройках). */
    var server by mutableStateOf<String?>(null); private set
    /** Растёт, когда сменили сервер — карта перечитывает онлайн-тайлы. */
    var serverTick by mutableIntStateOf(0); private set
    private val KNOWN = listOf("https://cdn.getyourmap.com", "https://cdn.getyourmap.cc", "https://proxy.getyourmap.com")

    /** Сервер открывается: имя находится в DNS телефона и есть HTTP-ответ. */
    private fun probe(url: String): Boolean = runCatching {
        val host = java.net.URL(url).host
        java.net.InetAddress.getAllByName(host).isNotEmpty() && run {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.requestMethod = "HEAD"
            try { c.responseCode > 0 } finally { c.disconnect() }
        }
    }.getOrDefault(false)

    /**
     * У части сетей/DNS имя cdn.getyourmap.com не находится («CURL 6: Could not resolve host») — тогда ни список карт,
     * ни онлайн-тайлы не грузятся. Проверяем серверы GLMap по очереди и ставим рабочие первыми.
     */
    private fun pickServer(): Boolean {
        val cur = runCatching { GLMapManager.GetServerList()?.toList() }.getOrNull().orEmpty()
        val all = (cur + KNOWN).map { it.trimEnd('/') }.distinct()
        val ok = all.filter { probe(it) }
        if (ok.isEmpty()) return false
        val order = ok + (all - ok.toSet())
        if (order != cur.map { it.trimEnd('/') }) main.post { runCatching { GLMapManager.SetServerList(order.toTypedArray()) }; serverTick++ }
        main.post { server = java.net.URL(ok.first()).host }
        return true
    }

    /** Список карт с сервера GLMap (страны и области). Ответ приходит в фоне — состояние меняем на главном потоке. */
    fun refreshList() {
        if (listLoading) return
        listLoading = true; listError = null
        Thread {
            if (!pickServer()) {
                main.post {
                    listLoading = false; version++
                    listError = "ни один сервер GLMap не открывается (cdn.getyourmap.com, .cc, proxy). Проверь интернет; если включён «Частный DNS» " +
                        "или блокировщик рекламы (Настройки телефона → Сеть → Частный DNS) — выключи его или добавь getyourmap.com в исключения"
                }
                return@Thread
            }
            main.post {
                val ok = runCatching {
                    GLMapManager.UpdateMapList { _, err ->
                        main.post { listLoading = false; listError = err?.toString(); version++ }
                    }
                }
                if (ok.isFailure) { listLoading = false; listError = ok.exceptionOrNull()?.message }
            }
        }.start()
        // если ответа нет 40 с — даём нажать «Обновить» снова
        main.postDelayed({ if (listLoading) { listLoading = false; version++ } }, 40_000)
    }

    fun listEmpty() = runCatching { GLMapManager.GetMaps().isNullOrEmpty() }.getOrDefault(true)

    /** Онлайн-тайлы (там, где карта не скачана) — только при интернете и если не выбран «только офлайн». */
    fun allowOnline(on: Boolean) { if (ready) runCatching { GLMapManager.SetTileDownloadingAllowed(on) } }

    val locale: GLMapLocaleSettings by lazy { localeOf(arrayOf("ru", "uk", "native", "en")) }

    /** Порядок языков подписей; неизвестные движку коды отбрасываются. */
    fun localeOf(order: Array<String>): GLMapLocaleSettings {
        val valid = runCatching { GLMapLocaleSettings.getValidLanguages() }.getOrNull()
        val ok = order.filter { it == "native" || valid == null || valid.isEmpty() || it in valid }
        return if (ok.isEmpty()) GLMapLocaleSettings() else GLMapLocaleSettings(ok.toTypedArray(), GLMapLocaleSettings.UnitSystem.International)
    }

    /** Самая мелкая (не сборная) карта, покрывающая точку: страна или её часть. */
    fun mapAt(lat: Double, lon: Double): GLMapInfo? = runCatching {
        GLMapManager.MapsAtPoint(MapPoint.CreateFromGeoCoordinates(lat, lon))?.filter { !it.isCollection }?.minByOrNull { it.getSizeOnServer(GLMapInfo.DataSetMask.ALL) }
    }.getOrNull()

    private val en by lazy { localeOf(arrayOf("en", "native")) }
    fun nameEn(m: GLMapInfo) = runCatching { m.getLocalizedName(en) }.getOrNull().orEmpty()
    fun name(m: GLMapInfo) = runCatching { m.getLocalizedName(locale) }.getOrNull() ?: "Карта"

    /** Скачанные (хотя бы частично) карты. */
    fun downloaded(): List<GLMapInfo> = runCatching {
        fun walk(a: Array<GLMapInfo>?): List<GLMapInfo> = a.orEmpty().flatMap { m -> if (m.isCollection) walk(m.maps) else listOf(m) }
        walk(GLMapManager.GetMaps()).filter { it.dataSetsWithState(GLMapInfo.State.NOT_DOWNLOADED) != GLMapInfo.DataSetMask.ALL }
    }.getOrDefault(emptyList())

    /** Что из (карта, рельеф) ещё не скачано или устарело — эти части и качаем. */
    fun pending(m: GLMapInfo, mask: Int = GLMapInfo.DataSetMask.MAP or GLMapInfo.DataSetMask.ELEVATION): Int = runCatching {
        var avail = 0
        for (d in listOf(GLMapInfo.DataSetMask.MAP, GLMapInfo.DataSetMask.ELEVATION, GLMapInfo.DataSetMask.NAVIGATION)) if (d and mask != 0 && m.getSizeOnServer(d) > 0) avail = avail or d
        avail and (m.dataSetsWithState(GLMapInfo.State.NOT_DOWNLOADED) or m.dataSetsWithState(GLMapInfo.State.NEED_RESUME) or m.dataSetsWithState(GLMapInfo.State.NEED_UPDATE))
    }.getOrDefault(0)

    /** Скачан ли набор данных (MAP / NAVIGATION / ELEVATION) этой карты. */
    fun has(m: GLMapInfo, mask: Int): Boolean = runCatching { (m.dataSetsWithState(GLMapInfo.State.DOWNLOADED) or m.dataSetsWithState(GLMapInfo.State.NEED_UPDATE)) and mask != 0 }.getOrDefault(false)
    /** Состояние одного набора данных (маска MAP / NAVIGATION / ELEVATION). */
    fun state(m: GLMapInfo, mask: Int): Int = runCatching {
        listOf(GLMapInfo.State.IN_PROGRESS, GLMapInfo.State.NEED_RESUME, GLMapInfo.State.NEED_UPDATE, GLMapInfo.State.DOWNLOADED, GLMapInfo.State.REMOVED)
            .firstOrNull { m.dataSetsWithState(it) and mask != 0 } ?: GLMapInfo.State.NOT_DOWNLOADED
    }.getOrDefault(GLMapInfo.State.NOT_DOWNLOADED)
    fun downloadSet(m: GLMapInfo, mask: Int) { runCatching { GLMapManager.DownloadDataSets(m, mask) }; version++ }
    fun deleteSet(m: GLMapInfo, mask: Int) { runCatching { GLMapManager.DeleteDataSets(m, mask) }; version++ }
    fun progressOf(m: GLMapInfo, mask: Int): Float? {
        val t = runCatching { GLMapManager.getDownloadTasks(m.mapID, mask) }.getOrNull().orEmpty(); if (t.isEmpty()) return null
        val total = t.sumOf { it.total.toLong() }; val done = t.sumOf { it.downloaded.toLong() }
        return if (total > 0) done.toFloat() / total else 0f
    }
    fun cancelSet(m: GLMapInfo, mask: Int) { runCatching { GLMapManager.getDownloadTasks(m.mapID, mask) }.getOrNull()?.forEach { runCatching { it.cancel() } }; version++ }

    fun download(m: GLMapInfo) { val p = pending(m); if (p != 0) runCatching { GLMapManager.DownloadDataSets(m, p) }; version++ }
    fun delete(m: GLMapInfo) { runCatching { GLMapManager.DeleteDataSets(m, GLMapInfo.DataSetMask.ALL) }; version++ }
    fun tasks(m: GLMapInfo) = runCatching { GLMapManager.getDownloadTasks(m.mapID, GLMapInfo.DataSetMask.ALL) }.getOrNull().orEmpty()
    fun cancel(m: GLMapInfo) { tasks(m).forEach { runCatching { it.cancel() } }; version++ }

    /** Прогресс скачивания карты 0…1 или null, если не качается. */
    fun progress(m: GLMapInfo): Float? {
        val t = tasks(m); if (t.isEmpty()) return null
        val total = t.sumOf { it.total.toLong() }; val done = t.sumOf { it.downloaded.toLong() }
        return if (total > 0) (done.toFloat() / total) else 0f
    }

    fun sizeOnDisk(m: GLMapInfo) = runCatching { m.getSizeOnDisk(GLMapInfo.DataSetMask.ALL) }.getOrDefault(0L)
    fun sizeOnServer(m: GLMapInfo, mask: Int = GLMapInfo.DataSetMask.MAP or GLMapInfo.DataSetMask.ELEVATION) = runCatching { m.getSizeOnServer(mask) }.getOrDefault(0L)

    /** Высота местности из скачанного рельефа GLMap (NaN — нет данных). */
    fun elevation(lat: Double, lon: Double): Double = if (!ready) Double.NaN else runCatching { GLMapManager.ElevationAtPoint(lat, lon).toDouble() }.getOrDefault(Double.NaN)
        .takeIf { it.isFinite() && it > -500 && it < 9000 } ?: Double.NaN

    fun cacheSize() = if (!ready) 0L else runCatching { GLMapManager.GetVectorTileCacheSize() + GLMapManager.GetElevationCacheSize() }.getOrDefault(0L)
    fun dropCache() { if (ready) runCatching { GLMapManager.DropVectorTileCache(); GLMapManager.DropElevationCache() } }
}
