package com.pank.vsevodnom.data

/**
 * Часы работы из OpenStreetMap (opening_hours) — самые частые записи:
 * «Mo-Fr 08:00-21:00; Sa-Su 09:00-19:00», «24/7», «Mo,We 09:00-13:00,14:00-18:00», «Su off», «10:00-22:00».
 * Праздники (PH), месяцы и прочее редкое пропускаем; если ничего не разобрали — null (показываем как есть).
 */
object OpeningHours {
    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private const val D = "(?:mo|tu|we|th|fr|sa|su)"
    private val DAY_RULE = Regex("^((?:$D(?:\\s*-\\s*$D)?\\s*,?\\s*)*)(.*)$")
    val NAMES = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    /** По дням недели (0 — понедельник) — интервалы в минутах от полуночи (конец может быть > 1440 — после полуночи). */
    fun parse(src: String?): List<List<IntRange>>? {
        val s = src?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        if (s == "24/7" || s == "00:00-24:00") return List(7) { listOf(0..1440) }
        val week = MutableList<List<IntRange>>(7) { emptyList() }
        var any = false
        for (rule0 in s.split(';')) {
            val rule = rule0.trim().removePrefix("\"").trim()
            if (rule.isEmpty() || rule.startsWith("ph")) continue
            val m = DAY_RULE.find(rule) ?: continue
            val daysPart = m.groupValues[1].trim().trimEnd(',')
            val rest = m.groupValues[2].trim()
            val days = if (daysPart.isEmpty()) (0..6).toList() else daysOf(daysPart) ?: continue
            if (rest == "off" || rest == "closed") { days.forEach { week[it] = emptyList() }; any = true; continue }
            val times = if (rest == "24/7") listOf(0..1440) else rest.split(',').map { it.trim() }.mapNotNull { span(it) }
            if (times.isEmpty()) continue
            days.forEach { week[it] = times }; any = true
        }
        return if (any) week else null
    }

    private fun daysOf(p: String): List<Int>? {
        val out = ArrayList<Int>()
        for (part in p.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val ab = part.split('-').map { it.trim() }
            val a = DAYS.indexOf(ab[0]); if (a < 0) return null
            if (ab.size == 1) out += a else {
                val b = DAYS.indexOf(ab[1]); if (b < 0) return null
                var d = a; while (true) { out += d; if (d == b) break; d = (d + 1) % 7 }
            }
        }
        return out
    }

    private fun span(t: String): IntRange? {
        val m = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\+?$""").find(t) ?: return null
        val a = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        var b = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
        if (b <= a) b += 1440
        return a..b
    }

    fun fmt(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)
    fun dayText(spans: List<IntRange>): String = when {
        spans.isEmpty() -> "Выходной"
        spans.size == 1 && spans[0].first == 0 && spans[0].last >= 1440 -> "Круглосуточно"
        else -> spans.joinToString(", ") { fmt(it.first) + "–" + fmt(it.last) }
    }

    /** Состояние сейчас: открыто ли, текст («Открыто · до 21:00», «Скоро закроется · в 21:00», «Закрыто · откроется в 9:00»). */
    data class Status(val open: Boolean, val soon: Boolean, val text: String)

    fun status(week: List<List<IntRange>>, dow: Int, minute: Int): Status {
        // открыто: сегодняшний интервал или хвост вчерашнего после полуночи
        val y = (dow + 6) % 7
        val cur = week[dow].firstOrNull { minute in it.first until it.last } ?: week[y].firstOrNull { it.last > 1440 && minute < it.last - 1440 }?.let { (it.first - 1440)..(it.last - 1440) }
        if (cur != null) {
            if (cur.first <= 0 && cur.last >= 1440 && week.all { d -> d.any { it.first == 0 && it.last >= 1440 } }) return Status(true, false, "Открыто круглосуточно")
            val left = cur.last - minute
            return if (left <= 60) Status(true, true, "Скоро закроется · в ${fmt(cur.last)}") else Status(true, false, "Открыто · до ${fmt(cur.last)}")
        }
        val next = week[dow].filter { it.first > minute }.minByOrNull { it.first }
        if (next != null) {
            val wait = next.first - minute
            return if (wait <= 60) Status(false, true, "Скоро откроется · в ${fmt(next.first)}") else Status(false, false, "Закрыто · откроется в ${fmt(next.first)}")
        }
        for (k in 1..7) {
            val d = (dow + k) % 7
            week[d].minByOrNull { it.first }?.let { sp ->
                val dn = if (k == 1) "завтра" else listOf("в пн", "во вт", "в ср", "в чт", "в пт", "в сб", "в вс")[d]
                return Status(false, false, "Закрыто · откроется $dn в ${fmt(sp.first)}")
            }
        }
        return Status(false, false, "Закрыто")
    }
}
