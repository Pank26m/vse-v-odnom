package com.pank.vsevodnom.ui.capri

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Что пользователь хочет. Разбор без ИИ — правила и ключевые слова (русский и украинский). */
sealed class Cmd {
    data class Event(val title: String, val start: LocalDateTime?, val date: LocalDate, val allDay: Boolean, val minutes: Long, val remindBefore: Long?, val important: Boolean) : Cmd()
    data class Cart(val url: String, val note: String) : Cmd()
    object Alerts : Cmd()
    object Weather : Cmd()
    data class Rates(val what: String) : Cmd()
    data class Weight(val kg: Double) : Cmd()
    data class Mood(val level: Int, val note: String) : Cmd()
    data class Timer(val seconds: Long) : Cmd()
    data class Alarm(val time: LocalTime, val date: LocalDate?) : Cmd()
    data class Lessons(val date: LocalDate) : Cmd()
    data class Agenda(val date: LocalDate) : Cmd()
    data class Piggy(val amount: Double, val goal: String) : Cmd()
    data class Name(val name: String) : Cmd()
    object Help : Cmd()
    object Hello : Cmd()
    data class Unknown(val text: String) : Cmd()
}

object CapriParser {
    private val URL_RE = Regex("(?U)https?://\\S+")
    private val MONTHS = listOf(
        listOf("январ", "січ"), listOf("феврал", "лют"), listOf("март", "берез"), listOf("апрел", "квіт"), listOf("ма[йя]", "трав"), listOf("июн", "черв"),
        listOf("июл", "лип"), listOf("август", "серп"), listOf("сентябр", "верес"), listOf("октябр", "жовт"), listOf("ноябр", "листопад"), listOf("декабр", "груд"),
    )
    private val DOW = listOf(
        DayOfWeek.MONDAY to "понедельник|понеділ", DayOfWeek.TUESDAY to "вторник|вівтор", DayOfWeek.WEDNESDAY to "сред[уа]|серед",
        DayOfWeek.THURSDAY to "четверг|четвер", DayOfWeek.FRIDAY to "пятниц|п'ятниц|пʼятниц|пятн", DayOfWeek.SATURDAY to "суббот|субот", DayOfWeek.SUNDAY to "воскресень|неділ",
    )
    private val NUM_WORDS = mapOf("один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2, "дві" to 2, "три" to 3, "четыре" to 4, "чотири" to 4, "пять" to 5, "п'ять" to 5,
        "десять" to 10, "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "полчаса" to 30, "пол" to 30)

    /** Вытаскивает дату и возвращает (дата, текст без найденного фрагмента). */
    fun date(text0: String, today: LocalDate): Pair<LocalDate?, String> {
        var t = text0
        fun cut(r: Regex): MatchResult? { val m = r.find(t) ?: return null; t = t.removeRange(m.range); return m }
        cut(Regex("(?U)\\b(послезавтра|післязавтра)\\b", RegexOption.IGNORE_CASE))?.let { return today.plusDays(2) to t }
        cut(Regex("(?U)\\b(завтра)\\b", RegexOption.IGNORE_CASE))?.let { return today.plusDays(1) to t }
        cut(Regex("(?U)\\b(сегодня|сьогодні)\\b", RegexOption.IGNORE_CASE))?.let { return today to t }
        cut(Regex("(?U)через\\s+(\\d+|неделю|тиждень)\\s*(дн[яейь]*|день|днів|тижн[іяь]*|недел[иьюь]*)?", RegexOption.IGNORE_CASE))?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            val unit = m.groupValues[2].lowercase()
            return (if (m.groupValues[1].startsWith("нед") || m.groupValues[1].startsWith("тиж") || unit.startsWith("нед") || unit.startsWith("тиж")) today.plusWeeks(n.toLong()) else today.plusDays(n.toLong())) to t
        }
        cut(Regex("(?U)\\b(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\b"))?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
            runCatching { var r = LocalDate.of(y, mo, d); if (m.groupValues[3].isEmpty() && r.isBefore(today)) r = r.plusYears(1); return r to t }
        }
        for ((i, names) in MONTHS.withIndex()) {
            cut(Regex("(?U)\\b(\\d{1,2})(?:-?го)?\\s+(${names.joinToString("|")})[а-яіїє]*", RegexOption.IGNORE_CASE))?.let { m ->
                runCatching { var r = LocalDate.of(today.year, i + 1, m.groupValues[1].toInt()); if (r.isBefore(today)) r = r.plusYears(1); return r to t }
            }
        }
        for ((d, re) in DOW) {
            cut(Regex("(?U)\\b(?:в|во|у)?\\s*($re)[а-яіїє']*", RegexOption.IGNORE_CASE))?.let { return today.with(TemporalAdjusters.next(d)) to t }
        }
        return null to t
    }

    /** Время «в 15:00», «в 3 часа дня», «в полдень»; возвращает (время, текст без фрагмента). */
    fun time(text0: String): Pair<LocalTime?, String> {
        var t = text0
        fun cut(r: Regex): MatchResult? { val m = r.find(t) ?: return null; t = t.removeRange(m.range); return m }
        cut(Regex("(?U)\\bв\\s+полдень|\\bопівдні", RegexOption.IGNORE_CASE))?.let { return LocalTime.NOON to t }
        cut(Regex("(?U)(?:\\b(?:в|на|о|об|к)\\s+)?(?<!до\\s)(?<!до)(?<!\\d)(\\d{1,2})[:.](\\d{2})\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) return LocalTime.of(h, mi) to t
        }
        cut(Regex("(?U)\\b(?:в|на|о|об|к)\\s+(\\d{1,2})\\s*(час[аов]*|годин[иу]?)?\\s*(утра|ранку|дня|вечера|вечора|ночи|ночі)?", RegexOption.IGNORE_CASE))?.let { m ->
            var h = m.groupValues[1].toInt()
            val part = m.groupValues[3].lowercase()
            if ((part.startsWith("дня") || part.startsWith("веч")) && h < 12) h += 12
            if (h in 0..23) return LocalTime.of(h, 0) to t
        }
        return null to t
    }

    private fun minutesIn(numStr: String, unit: String): Long? {
        val n = numStr.toLongOrNull() ?: NUM_WORDS[numStr.lowercase()]?.toLong() ?: return null
        val u = unit.lowercase()
        return when {
            u.startsWith("мин") || u.startsWith("хв") -> n
            u.startsWith("час") || u.startsWith("год") -> n * 60
            u.startsWith("д") -> n * 1440
            else -> null
        }
    }

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Cmd {
        val raw = text.trim()
        val low = raw.lowercase()
        val today = now.toLocalDate()
        val url = URL_RE.find(raw)?.value

        if (low.isBlank()) return Cmd.Unknown(raw)
        if (Regex("(?U)^(привет|привіт|хай|здравствуй|доброе|добрый|доброго|добрий)").containsMatchIn(low)) return Cmd.Hello
        if (Regex("(?U)что ты умеешь|що ти вмієш|помощь|допомога|help|команды").containsMatchIn(low)) return Cmd.Help
        if (url != null && (Regex("(?U)корзин|кошик|купить|купити|хочу|wishlist").containsMatchIn(low) || Regex("(?U)aliexpress|prom\\.ua|rozetka|olx|temu|amazon|epicentrk|allo\\.ua|comfy|foxtrot|moyo|makeup").containsMatchIn(url)))
            return Cmd.Cart(url, raw.replace(url, "").replace(Regex("(?U)(?i)(добавь|додай|закинь|в корзину|у кошик|до кошика|корзин[уа]|купить|купити|хочу)"), "").trim(' ', ',', '.', '-'))
        if (Regex("(?U)тревог|тривог|воздушн|повітрян").containsMatchIn(low)) return Cmd.Alerts
        if (Regex("(?U)погод|дожд|зонт|холодно|тепло ли").containsMatchIn(low)) return Cmd.Weather
        if (Regex("(?U)курс|доллар|долар|евро|євро|биткоин|біткоїн|bitcoin|btc|акци|s&p|индекс|індекс").containsMatchIn(low)) return Cmd.Rates(low)
        Regex("(?U)(?:вес|вага|вешу|важу)\\D{0,10}(\\d{2,3}(?:[.,]\\d)?)").find(low)?.let { m -> m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }?.let { return Cmd.Weight(it) } }
        if (Regex("(?U)настроени|настрій|самочувств|почуваюсь|чувствую").containsMatchIn(low)) {
            val lvl = when {
                Regex("(?U)супер|отлич|чудов|прекрас|кайф|відмін").containsMatchIn(low) -> 5
                Regex("(?U)ужас|жах|отврат|кошмар|погано зовсім").containsMatchIn(low) -> 1
                Regex("(?U)плох|погано|грустн|сумно|не очень").containsMatchIn(low) -> 2
                Regex("(?U)хорош|добр|норм[^а-я]*хорош").containsMatchIn(low) -> 4
                Regex("(?U)норм|нейтрал|так себе|средн|нормаль").containsMatchIn(low) -> 3
                else -> 0
            }
            return Cmd.Mood(lvl, raw.replace(Regex("(?U)(?i).*?(настроение|настрій|самочувствие)\\s*[:-]?\\s*"), "").trim())
        }
        Regex("(?U)таймер\\D*?(\\d+|[а-яії']+)\\s*(сек|мин|хв|час|год)", RegexOption.IGNORE_CASE).find(low)?.let { m ->
            val unit = m.groupValues[2]
            val n = m.groupValues[1].toLongOrNull() ?: NUM_WORDS[m.groupValues[1]]?.toLong() ?: return@let
            return Cmd.Timer(when { unit.startsWith("сек") -> n; unit.startsWith("мин") || unit.startsWith("хв") -> n * 60; else -> n * 3600 })
        }
        if (Regex("(?U)будильник|разбуди|розбуди").containsMatchIn(low)) {
            val (d, rest) = date(raw, today)
            val (tm, _) = time(rest)
            if (tm != null) return Cmd.Alarm(tm, d)
        }
        if (Regex("(?U)\\bпар[ыау]?\\b|расписани|розклад|универ|універ|занят").containsMatchIn(low)) {
            return Cmd.Lessons(date(raw, today).first ?: today)
        }
        if (Regex("(?U)что у меня|що в мене|що у мене|какие планы|які плани|мои планы|мої плани|что запланировано").containsMatchIn(low)) {
            return Cmd.Agenda(date(raw, today).first ?: today)
        }
        Regex("(?U)(?:отлож|відклад|пополн|поповн|закинь|кинь|внеси)\\D*?(\\d+(?:[.,]\\d+)?)\\s*(?:грн|₴|гривен|гривень)?\\s*(?:на|в|у|до)?\\s*(.*)", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            if (Regex("(?U)копилк|скарбничк|отпуск|відпуст|аренд|оренд").containsMatchIn(low) || m.groupValues[2].isNotBlank())
                return Cmd.Piggy(m.groupValues[1].replace(',', '.').toDouble(), m.groupValues[2].replace(Regex("(?U)(?i)копилк[уаи]|скарбничк[уи]"), "").trim())
        }
        Regex("(?U)(?:зови меня|называй меня|меня зовут|мене звати|клич мене)\\s+([A-Za-zА-Яа-яЁёІіЇїЄєҐґ'-]+)", RegexOption.IGNORE_CASE).find(raw)?.let { return Cmd.Name(it.groupValues[1].replaceFirstChar { c -> c.uppercase() }) }

        // событие: всё, где есть «добавь/напомни/встреча» или дата/время
        val eventWords = Regex("(?U)добав|додай|созда|створ|запиш|занес|напомн|нагадай|встреч|зустріч|событи|поді|запланир|заплану|поставь", RegexOption.IGNORE_CASE)
        var t = raw
        val (d, t1) = date(t, today); t = t1
        val (tm, t2) = time(t); t = t2
        if (!eventWords.containsMatchIn(low) && d == null && tm == null) return Cmd.Unknown(raw)
        var remind: Long? = null
        Regex("(?U)(?:напомни|нагадай)[^,.]*?\\bза\\s+(\\d+|[а-яії']+)?\\s*(мин[а-я]*|хв[а-я]*|час[а-я]*|годин[а-я]*|д[еня][а-я]*)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            remind = minutesIn(m.groupValues[1].ifBlank { "1" }, m.groupValues[2]); t = t.removeRange(m.range)
        }
        var minutes = 60L; var allDay = false
        Regex("(?U)\\bна\\s+(\\d+|[а-яії']+)?\\s*(мин[а-я]*|хв[а-я]*|час[а-я]*|годин[а-я]*)\\b", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            minutesIn(m.groupValues[1].ifBlank { "1" }, m.groupValues[2])?.let { minutes = it; t = t.removeRange(m.range) }
        }
        Regex("(?U)\\bна\\s+полчаса|\\bна\\s+півгодини", RegexOption.IGNORE_CASE).find(t)?.let { minutes = 30; t = t.removeRange(it.range) }
        Regex("(?U)\\bдо\\s+(\\d{1,2})[:.](\\d{2})", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            if (tm != null) { var e = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() - (tm.hour * 60 + tm.minute); if (e <= 0) e += 1440; minutes = e.toLong() }
            t = t.removeRange(m.range)
        }
        Regex("(?U)(?:на\\s+)?весь\\s+день|цілий\\s+день|на\\s+увесь\\s+день", RegexOption.IGNORE_CASE).find(t)?.let { allDay = true; t = t.removeRange(it.range) }
        val important = Regex("(?U)важн|срочн|термінов|важлив|высок\\w*\\s+приоритет|високий\\s+пріоритет", RegexOption.IGNORE_CASE).containsMatchIn(t)
        t = t.replace(Regex("(?U)(?i)(высок\\w*|низк\\w*|високий|низький)\\s+(приоритет\\w*|пріоритет\\w*)|\\b(важн\\w*|срочн\\w*|терміново|важлив\\w*)"), "")
        t = t.replace(Regex("(?U)(?i)\\b(пожалуйста|будь ласка|капри|капрі|мне|мені|в календарь|у календар|до календаря|событие|подію|событи[ея]|добавь|додай|создай|створи|запиши|занеси|запланируй|заплануй|поставь|напомни|нагадай|что|що)\\b"), " ")
        val title = t.replace(Regex("(?U)\\s+"), " ").trim(' ', ',', '.', '-', ':').replaceFirstChar { it.uppercase() }.ifBlank { "Событие" }
        val date = d ?: today
        val start = if (allDay || tm == null) null else date.atTime(tm)
        return Cmd.Event(title, start, date, allDay || tm == null, minutes, remind, important)
    }
}
