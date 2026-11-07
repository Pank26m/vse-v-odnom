package com.pank.vsevodnom.data

/**
 * Границы областей, районов и громад для карты тревог.
 * Источник: ukrainian_geodata (Sławomir Matuszak, CC BY 4.0) — деление после реформы 2020 года, 136 районов.
 * Файлы assets/geo/raions.txt и hromady.txt собраны скриптом: проекция (x — долгота·cos 48.5°, y — широта),
 * ширина 2000 единиц, упрощение Дугласа—Пекера. Строка: тип|область|[район|[громада|]]кольца;
 * кольца через «;», точки «dx,dy» через пробел (первая — абсолютная).
 */
object AlertGeo {
    const val W = 2000f
    const val H = 1342f

    class Area(val oblast: String, val raion: String?, val name: String, val rings: List<FloatArray>) {
        val key get() = if (raion == null) oblast else "$oblast|$name"
        val minX = rings.minOf { r -> (r.indices step 2).minOf { r[it] } }
        val maxX = rings.maxOf { r -> (r.indices step 2).maxOf { r[it] } }
        val minY = rings.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
        val maxY = rings.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }

        /** Точка внутри (правило чёт-нечет — дырки учитываются). */
        fun contains(x: Float, y: Float): Boolean {
            if (x < minX || x > maxX || y < minY || y > maxY) return false
            var c = false
            rings.forEach { r ->
                var j = r.size - 2; var i = 0
                while (i < r.size) {
                    val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
                    if ((yi > y) != (yj > y) && x < xi + (y - yi) * (xj - xi) / (yj - yi)) c = !c
                    j = i; i += 2
                }
            }
            return c
        }

        /** Центр для подписи — центр масс самого большого кольца. */
        val center: Pair<Float, Float> by lazy {
            val r = rings.maxBy { area(it) }
            var a = 0f; var cx = 0f; var cy = 0f; var j = r.size - 2; var i = 0
            while (i < r.size) {
                val f = r[j] * r[i + 1] - r[i] * r[j + 1]
                a += f; cx += (r[j] + r[i]) * f; cy += (r[j + 1] + r[i + 1]) * f
                j = i; i += 2
            }
            if (a == 0f) (minX + maxX) / 2 to (minY + maxY) / 2 else cx / (3 * a) to cy / (3 * a)
        }
    }

    private fun area(r: FloatArray): Float {
        var s = 0f; var j = r.size - 2; var i = 0
        while (i < r.size) { s += r[j] * r[i + 1] - r[i] * r[j + 1]; j = i; i += 2 }
        return kotlin.math.abs(s) / 2
    }

    private fun rings(s: String): List<FloatArray> = s.split(';').filter { it.isNotBlank() }.map { ring ->
        val pts = ring.split(' ')
        val out = FloatArray(pts.size * 2)
        var x = 0; var y = 0
        pts.forEachIndexed { k, p ->
            val c = p.indexOf(',')
            x += p.substring(0, c).toInt(); y += p.substring(c + 1).toInt()
            out[k * 2] = x.toFloat(); out[k * 2 + 1] = y.toFloat()
        }
        out
    }

    class GeoMap(val oblasts: List<Area>, val raions: List<Area>)

    fun parseMap(text: String): GeoMap {
        val o = ArrayList<Area>(); val r = ArrayList<Area>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            val p = l.split('|')
            when (p[0]) {
                "O" -> rings(p[2]).takeIf { it.isNotEmpty() }?.let { o += Area(p[1], null, p[1], it) }
                "R" -> rings(p[3]).takeIf { it.isNotEmpty() }?.let { r += Area(p[1], p[2], p[2], it) }
            }
        }
        return GeoMap(o, r)
    }

    fun parseHromady(text: String): List<Area> = text.lineSequence().filter { it.startsWith("H|") }.mapNotNull { l ->
        val p = l.split('|'); rings(p[4]).takeIf { it.isNotEmpty() }?.let { Area(p[1], p[2], p[3], it) }
    }.toList()

    // ---------------- раскраска ----------------

    /** Цвет на карте: 0 — спокойно, 1 — жёлтая (угроза), 2 — красная (тревога). */
    data class Paint(
        val oblast: Map<String, Int>,
        val raion: Map<String, Int>,      // «область|район»
        val hromada: Map<String, Int>,    // «область|громада» (имя громады как в геоданных)
        /** Районы, где тревога только в части (громада/город), — чтобы показать, где именно. */
        val raionPartial: Set<String>,
    )

    private fun stem(s: String) = s.lowercase().replace('’', '\'').substringBefore(' ')

    /**
     * Ищет громаду из ответа API среди громад геоданных.
     * В API и в геоданных названия расходятся во втором слове («територіальна» / «міська» / «сільська»),
     * поэтому сравниваем первое слово, и только внутри своей области (и района, если он известен).
     */
    fun findHromada(item: AlertItem, all: List<Area>): Area? {
        val pool = all.filter { it.oblast == item.oblast && (item.raion == null || it.raion == item.raion) }
        val t = item.title.removePrefix("м. ").removePrefix("смт ").removePrefix("с. ")
        if (item.kind == "hromada") return pool.firstOrNull { stem(it.name) == stem(t) }
        // город: «Марганець» → «Марганецька міська», «Кривий Ріг» → «Криворізька», «Запоріжжя» → «Запорізька»:
        // берём громаду с самым длинным общим началом (не меньше 4 букв и половины слова), при равенстве — міську
        val w = stem(t); if (w.length < 4) return null
        fun common(a: String) = a.zip(w).takeWhile { (x, y) -> x == y }.size
        val need = maxOf(4, w.length / 2)
        return pool.map { it to common(stem(it.name)) }.filter { it.second >= need }
            .maxWithOrNull(compareBy<Pair<Area, Int>> { it.second }.thenBy { if ("міськ" in it.first.name) 1 else 0 })?.first
    }

    fun paint(state: AlertState?, map: GeoMap, hromady: List<Area>?): Paint {
        val obl = HashMap<String, Int>(); val ray = HashMap<String, Int>(); val hr = HashMap<String, Int>(); val part = HashSet<String>()
        state ?: return Paint(obl, ray, hr, part)
        fun up(m: HashMap<String, Int>, k: String, v: Int) { if ((m[k] ?: 0) < v) m[k] = v }
        if (!state.districtLevel) {
            state.level.forEach { (k, l) -> if (l == AlertLevel.FULL) obl[k] = 2 }
            return Paint(obl, ray, hr, part)
        }
        val raionOf = map.raions.associateBy { it.key }
        state.items.forEach { a ->
            val v = if (a.red) 2 else 1
            when {
                a.kind == "oblast" || a.oblast == AlertsApi.KYIV_CITY || a.oblast == AlertsApi.SEVASTOPOL -> up(obl, a.oblast, v)
                a.kind == "raion" -> {
                    val k = "${a.oblast}|${a.title}"
                    if (k in raionOf) up(ray, k, v) else up(obl, a.oblast, v)   // неизвестный район — красим область
                }
                else -> {
                    val h = hromady?.let { findHromada(a, it) }
                    if (h != null) { up(hr, "${h.oblast}|${h.name}", v); part += "${h.oblast}|${h.raion}" }
                    else a.raion?.let { part += "${a.oblast}|$it" }
                }
            }
        }
        return Paint(obl, ray, hr, part)
    }
}
