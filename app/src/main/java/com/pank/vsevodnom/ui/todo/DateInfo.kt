package com.pank.vsevodnom.ui.todo

import android.content.Context
import com.pank.vsevodnom.data.Birthday
import java.time.LocalDate

/**
 * Подписи дат (0.45): сколько исполнится/исполнилось, «могло бы исполниться» после даты смерти,
 * название годовщины свадьбы. Дата смерти хранится в prefs «dates» (death_<id> = эпоха-день), база не меняется.
 */
object DateInfo {
    /** Год считаем неизвестным, если он явно выдуман (контакты Google/iOS ставят 1604/1900/1910…). */
    fun realYear(y: Int?): Int? = y?.takeIf { it in 1920..LocalDate.now().year }

    private fun p(ctx: Context) = ctx.getSharedPreferences("dates", Context.MODE_PRIVATE)
    fun death(ctx: Context, id: Long): LocalDate? = p(ctx).getLong("death_$id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }
    fun memorialId(ctx: Context, id: Long): Long? = p(ctx).getLong("memorial_$id", 0L).takeIf { it != 0L }
    fun setDeath(ctx: Context, id: Long, d: LocalDate?, memorialId: Long?) {
        p(ctx).edit().apply {
            if (d == null) { remove("death_$id"); remove("memorial_$id") } else { putLong("death_$id", d.toEpochDay()); memorialId?.let { putLong("memorial_$id", it) } }
        }.apply()
    }

    /** Названия годовщин свадьбы (как принято в Украине и России). */
    val WEDDING = mapOf(
        1 to "ситцевая", 2 to "бумажная", 3 to "кожаная", 4 to "льняная", 5 to "деревянная", 6 to "чугунная", 7 to "медная", 8 to "жестяная",
        9 to "фаянсовая", 10 to "оловянная (розовая)", 11 to "стальная", 12 to "никелевая", 13 to "кружевная", 14 to "агатовая", 15 to "хрустальная",
        16 to "топазовая", 17 to "бронзовая", 18 to "бирюзовая", 19 to "гранатовая", 20 to "фарфоровая", 21 to "опаловая", 22 to "бронзовая",
        23 to "бериллиевая", 24 to "атласная", 25 to "серебряная", 26 to "нефритовая", 27 to "красного дерева", 28 to "никелевая", 29 to "бархатная",
        30 to "жемчужная", 31 to "смуглая", 32 to "медная", 33 to "оловянная", 34 to "янтарная", 35 to "коралловая", 36 to "фарфоровая", 37 to "муслиновая",
        38 to "ртутная", 39 to "креповая", 40 to "рубиновая", 44 to "топазовая", 45 to "сапфировая", 46 to "лавандовая", 47 to "кашемировая",
        48 to "аметистовая", 49 to "кедровая", 50 to "золотая", 55 to "изумрудная", 60 to "бриллиантовая", 65 to "железная", 70 to "благодатная",
        75 to "коронная", 80 to "дубовая",
    )
    fun weddingName(n: Int): String? = WEDDING[n]?.let { "$it свадьба" }

    private fun years(n: Int) = when { n % 100 in 11..14 -> "лет"; n % 10 == 1 -> "год"; n % 10 in 2..4 -> "года"; else -> "лет" }

    /**
     * Подпись про возраст для даты в день [on] (обычно — ближайшее наступление):
     * «исполнится 30» / «исполнилось 30» (если день уже прошёл в этом году), «могло бы исполниться 80»,
     * у свадьбы — «10 лет · оловянная свадьба».
     */
    fun line(ctx: Context?, b: Birthday, on: LocalDate, today: LocalDate = LocalDate.now()): String? {
        val y = if (b.kind == "birthday") realYear(b.year) else b.year
        y ?: return null
        val n = on.year - y
        if (n <= 0) return null
        val dead = ctx?.let { death(it, b.id) }
        return when (b.kind) {
            "birthday" -> when {
                dead != null -> "могло бы исполниться $n"
                on.isBefore(today) -> "исполнилось $n"
                on == today -> "сегодня $n"
                else -> "исполнится $n"
            }
            "wedding" -> "$n ${years(n)}" + (weddingName(n)?.let { " · $it" } ?: "")
            "memorial" -> "$n ${years(n)} со дня смерти"
            else -> "будет $n"
        }
    }
}
