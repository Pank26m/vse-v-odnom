package com.pank.vsevodnom.data.schedule

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Одна пара. Текст — как в исходном файле (на украинском), не переводим. */
data class ParsedLesson(
    val date: LocalDate,
    val pair: Int,
    val start: String,
    val end: String,
    val subject: String,
    val kind: String,
    val room: String,
    val teacher: String,
)

data class ParsedSchedule(
    val group: String,
    val from: LocalDate?,
    val to: LocalDate?,
    val lessons: List<ParsedLesson>,
)

/**
 * Разбор таблицы «Розклад групи» из портала (ACY Portal):
 *  строка дня:   «Пн» | 02.11.2026 | 09.11.2026 | …   — даты недель по столбцам
 *  строка пары:  «2 пара\n10:30-11:50» | ячейка | ячейка | …
 *  ячейка:       «Предмет [Лк]\nауд. 302\nПрізвище І.Б.»
 */
object ScheduleParser {
    private val DAY = Regex("^(пн|вт|ср|чт|пт|сб|нд|вс)(\\s|$)", RegexOption.IGNORE_CASE)
    private val PAIR = Regex("(\\d+)\\s*пара\\s*(\\d{1,2}[:.]\\d{2})\\s*[-–—]\\s*(\\d{1,2}[:.]\\d{2})", RegexOption.IGNORE_CASE)
    private val DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")
    private val KIND = Regex("^(.*?)\\s*\\[([^\\]]+)]\\s*$")
    private val DMY = DateTimeFormatter.ofPattern("d.M.yyyy")

    private fun date(s: String): LocalDate? = DATE.find(s)?.let {
        runCatching { LocalDate.parse("${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}", DMY) }.getOrNull()
    }

    private fun time(s: String) = s.replace('.', ':').let { if (it.length == 4) "0$it" else it }

    fun parse(sheets: List<XlsReader.Sheet>): ParsedSchedule {
        var group = ""
        var from: LocalDate? = null
        var to: LocalDate? = null
        val out = ArrayList<ParsedLesson>()
        for (sh in sheets) {
            var weekDates: Map<Int, LocalDate> = emptyMap()
            for (r in 0 until sh.rows) {
                val head = sh[r, 0].trim()
                if (head.isEmpty()) continue
                if (group.isEmpty() && head.contains("Розклад", true)) group = head.substringAfter(':', "").trim()
                val range = DATE.findAll(head).toList()
                if (range.size == 2 && from == null && !DAY.containsMatchIn(head)) {
                    from = date(range[0].value); to = date(range[1].value)
                    continue
                }
                if (DAY.containsMatchIn(head)) {
                    weekDates = (1 until sh.cols).mapNotNull { c -> date(sh[r, c])?.let { c to it } }.toMap()
                    continue
                }
                val m = PAIR.find(head.replace('\n', ' ')) ?: continue
                val pair = m.groupValues[1].toInt()
                val start = time(m.groupValues[2]); val end = time(m.groupValues[3])
                for ((c, d) in weekDates) {
                    val cell = sh[r, c].trim()
                    if (cell.isEmpty()) continue
                    // в одной ячейке может быть несколько пар (подгруппы) — делим по пустой строке
                    cell.split(Regex("\\n\\s*\\n")).forEach { block ->
                        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) return@forEach
                        val km = KIND.find(lines[0])
                        val subject = km?.groupValues?.get(1)?.trim() ?: lines[0]
                        val kind = km?.groupValues?.get(2)?.trim() ?: ""
                        val room = lines.firstOrNull { it.startsWith("ауд", true) }?.substringAfter('.')?.trim() ?: ""
                        val teacher = lines.drop(1).firstOrNull { !it.startsWith("ауд", true) } ?: ""
                        out += ParsedLesson(d, pair, start, end, subject, kind, room, teacher)
                    }
                }
            }
        }
        if (out.isEmpty()) throw XlsReader.XlsException("Не нашёл в файле ни одной пары. Это точно расписание из портала?")
        val f = from ?: out.minOf { it.date }
        val t = to ?: out.maxOf { it.date }
        return ParsedSchedule(group, f, t, out.sortedWith(compareBy({ it.date }, { it.pair })))
    }

    fun parse(bytes: ByteArray): ParsedSchedule = parse(XlsReader.read(bytes))
}
