package com.pank.vsevodnom.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Интервальное повторение по Лейтнеру: коробки 0…5.
 * «Помню» — карточка переезжает в следующую коробку и вернётся позже; «Не помню» — в коробку 0, покажем снова через минуту.
 */
object Srs {
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L
    val INTERVALS = longArrayOf(MIN, DAY, 3 * DAY, 7 * DAY, 16 * DAY, 35 * DAY)

    fun answer(c: Card, right: Boolean, now: Long = System.currentTimeMillis()): Card =
        if (right) { val b = (c.box + 1).coerceAtMost(5); c.copy(box = b, due = now + INTERVALS[b], right = c.right + 1) }
        else c.copy(box = 0, due = now + MIN, wrong = c.wrong + 1)

    fun isNew(c: Card) = c.right + c.wrong == 0

    /** Сессия: сначала то, что пора повторить, потом новые (не больше newLimit). */
    suspend fun session(ctx: Context, deckId: Long, newLimit: Int = 20, total: Int = 60): List<Card> = withContext(Dispatchers.IO) {
        val all = AppDb.get(ctx).cards().dueCards(deckId, System.currentTimeMillis(), 5000)
        val review = all.filter { !isNew(it) }.take(total)
        val fresh = all.filter { isNew(it) }.take((total - review.size).coerceAtMost(newLimit))
        (review + fresh).shuffled()
    }
}

/** Встроенный словарь английского: файлы в assets/en (один набор — один файл), строки «слово|перевод». */
object English {
    data class Pack(val file: String, val key: String, val name: String, val tint: Int)
    val PACKS = listOf(
        Pack("A1", "en:A1", "A1 · начальный", 1), Pack("A2", "en:A2", "A2 · элементарный", 0), Pack("B1", "en:B1", "B1 · средний", 3),
        Pack("B2", "en:B2", "B2 · выше среднего", 2), Pack("C1", "en:C1", "C1 · продвинутый", 5),
        Pack("Phrasal", "en:phrasal", "Фразовые глаголы", 4), Pack("Feelings", "en:feelings", "Эмоции и фразы", 4), Pack("Work", "en:work", "Работа и бизнес", 3),
        Pack("Tech", "en:tech", "Техника и интернет", 0), Pack("Study", "en:study", "Учёба", 1), Pack("Travel", "en:travel", "Путешествия", 0),
        Pack("City", "en:city", "Город", 2), Pack("Home", "en:home", "Дом", 1), Pack("Food", "en:food", "Еда и кухня", 2),
        Pack("Health", "en:health", "Здоровье и тело", 5), Pack("Shopping", "en:shopping", "Покупки и деньги", 4), Pack("People", "en:people", "Люди и характер", 3),
        Pack("Nature", "en:nature", "Природа и животные", 1), Pack("Sport", "en:sport", "Спорт", 2), Pack("Extra", "en:extra", "Ещё слова", 5),
    )

    /** Читает все наборы, убирая повторы (слово попадает в первый набор по порядку уровней). */
    fun read(ctx: Context): Map<Pack, List<Pair<String, String>>> {
        val seen = HashSet<String>()
        return PACKS.associateWith { p ->
            ctx.assets.open("en/${p.file}.txt").bufferedReader().readLines().mapNotNull { l ->
                val i = l.indexOf('|'); if (i <= 0) return@mapNotNull null
                val w = l.substring(0, i).trim(); val t = l.substring(i + 1).trim()
                if (seen.add(w.lowercase())) w to t else null
            }
        }
    }

    /** Создаёт встроенные колоды при первом открытии (и добавляет новые наборы после обновлений). */
    suspend fun ensure(ctx: Context): Int = withContext(Dispatchers.IO) {
        val db = AppDb.get(ctx); val dao = db.cards()
        val have = dao.decksOnce().map { it.builtin }.toSet()
        val missing = PACKS.filter { it.key !in have }
        if (missing.isEmpty()) return@withContext 0
        val data = read(ctx)
        var n = 0
        db.withTransaction {
            missing.forEach { p ->
                val id = dao.upsertDeck(Deck(name = p.name, tint = p.tint, builtin = p.key))
                val cards = data[p].orEmpty().map { (w, t) -> Card(deckId = id, front = w, back = t) }
                cards.chunked(500).forEach { dao.insertCards(it) }; n += cards.size
            }
        }
        n
    }
}
