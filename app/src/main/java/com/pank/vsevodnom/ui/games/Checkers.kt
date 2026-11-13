package com.pank.vsevodnom.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.random.Sfx
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
//  Движок русских шашек (без Compose)
// =====================================================================================

/**
 * Ход: path — поля, по которым прошла шашка (откуда, [промежуточные посадки], куда),
 * caps — сбитые шашки по порядку, promo — шашка стала дамкой (в том числе посреди взятия).
 */
class CkMove(val path: IntArray, val caps: IntArray, val promo: Boolean) {
    val from: Int get() = path[0]
    val to: Int get() = path[path.size - 1]
    val isCapture: Boolean get() = caps.isNotEmpty()
    override fun toString() = CheckersEngine.notation(this)
}

/**
 * Русские шашки: доска 8×8 (поле = r*8+c, r=0 — сторона чёрных), тёмные поля — (r+c) нечётно.
 * Клетка: 0 пусто, 1 белая простая, 2 белая дамка, −1 чёрная простая, −2 чёрная дамка. Белые ходят первыми (вверх).
 *
 * Правила: простая ходит вперёд на одно поле, бьёт и вперёд, и назад; бить обязательно, цепочку — до конца;
 * дошла до последнего ряда во время взятия — сразу дамка и продолжает бить как дамка; дамка «дальнобойная»,
 * после взятия встаёт на любое свободное поле за сбитой, но если с части полей бой продолжается — только на них;
 * сбитые снимаются после хода, через уже сбитую перепрыгнуть второй раз нельзя («турецкий удар»).
 * Правила большинства нет — любую цепочку можно выбрать.
 */
object CheckersEngine {
    const val MATE = 100_000
    private const val INF = 1_000_000
    private val NO_CAPS = IntArray(0)
    private val DR = intArrayOf(-1, -1, 1, 1)
    private val DC = intArrayOf(-1, 1, -1, 1)

    /** 32 тёмных поля. */
    val DARK: IntArray = (0 until 64).filter { (it / 8 + it % 8) % 2 == 1 }.toIntArray()

    fun sq(r: Int, c: Int) = r * 8 + c
    /** «c3» — как в шашечной нотации (a1 — левый нижний угол белых). */
    fun name(s: Int) = "${'a' + s % 8}${8 - s / 8}"
    fun parse(n: String): Int = sq(8 - (n[1] - '0'), n[0] - 'a')
    fun notation(m: CkMove) = m.path.joinToString(if (m.isCapture) ":" else "-") { name(it) }

    fun start(): IntArray {
        val b = IntArray(64)
        for (s in DARK) { val r = s / 8; if (r < 3) b[s] = -1 else if (r > 4) b[s] = 1 }
        return b
    }

    private fun inside(r: Int, c: Int) = r in 0..7 && c in 0..7
    private fun promoRow(side: Int) = if (side == 1) 0 else 7

    /** Перебор цепочек взятий одной шашкой (поиск в глубину). */
    private class Gen(val b: IntArray, val side: Int, val out: ArrayList<CkMove>) {
        val captured = BooleanArray(64)
        val path = IntArray(16)
        val caps = IntArray(16)
        var origKing = false

        fun from(s: Int) {
            val p = b[s]
            origKing = abs(p) == 2
            b[s] = 0           // поле, откуда пошли, свободно: дамка может пройти через него
            path[0] = s
            dfs(s, origKing, 1, 0)
            b[s] = p
        }

        /** true, если отсюда есть взятие (тогда завершённые ходы добавлены глубже). */
        fun dfs(at: Int, king: Boolean, pn: Int, cn: Int): Boolean {
            var found = false
            val r = at / 8; val c = at % 8
            for (d in 0 until 4) {
                val dr = DR[d]; val dc = DC[d]
                if (king) {
                    var rr = r + dr; var cc = c + dc
                    while (inside(rr, cc) && b[rr * 8 + cc] == 0) { rr += dr; cc += dc }
                    if (!inside(rr, cc)) continue
                    val v = rr * 8 + cc
                    if (b[v] * side >= 0 || captured[v]) continue     // своя или уже сбитая — преграда
                    var lr = rr + dr; var lc = cc + dc
                    val lands = IntArray(7); var ln = 0
                    while (inside(lr, lc) && b[lr * 8 + lc] == 0) { lands[ln++] = lr * 8 + lc; lr += dr; lc += dc }
                    if (ln == 0) continue
                    found = true
                    captured[v] = true; caps[cn] = v
                    var cont = false
                    for (i in 0 until ln) { path[pn] = lands[i]; if (dfs(lands[i], true, pn + 1, cn + 1)) cont = true }
                    if (!cont) for (i in 0 until ln) {
                        path[pn] = lands[i]
                        out.add(CkMove(path.copyOf(pn + 1), caps.copyOf(cn + 1), !origKing))
                    }
                    captured[v] = false
                } else {
                    val r1 = r + dr; val c1 = c + dc; val r2 = r + 2 * dr; val c2 = c + 2 * dc
                    if (!inside(r2, c2)) continue
                    val n = r1 * 8 + c1; val l = r2 * 8 + c2
                    if (b[n] * side >= 0 || captured[n] || b[l] != 0) continue
                    found = true
                    captured[n] = true; caps[cn] = n; path[pn] = l
                    val becomesKing = r2 == promoRow(side)
                    if (!dfs(l, becomesKing, pn + 1, cn + 1)) out.add(CkMove(path.copyOf(pn + 1), caps.copyOf(cn + 1), becomesKing))
                    captured[n] = false
                }
            }
            return found
        }
    }

    /** Все законные ходы стороны side (если есть взятия — только они). */
    fun moves(b: IntArray, side: Int): List<CkMove> {
        val caps = ArrayList<CkMove>()
        val g = Gen(b, side, caps)
        for (s in DARK) if (b[s] * side > 0) g.from(s)
        if (caps.isNotEmpty()) return caps
        val out = ArrayList<CkMove>()
        for (s in DARK) {
            val p = b[s]; if (p * side <= 0) continue
            val r = s / 8; val c = s % 8
            if (abs(p) == 1) {
                val r2 = r - side
                for (dc in intArrayOf(-1, 1)) {
                    val c2 = c + dc
                    if (inside(r2, c2) && b[r2 * 8 + c2] == 0) out.add(CkMove(intArrayOf(s, r2 * 8 + c2), NO_CAPS, r2 == promoRow(side)))
                }
            } else for (d in 0 until 4) {
                var rr = r + DR[d]; var cc = c + DC[d]
                while (inside(rr, cc) && b[rr * 8 + cc] == 0) { out.add(CkMove(intArrayOf(s, rr * 8 + cc), NO_CAPS, false)); rr += DR[d]; cc += DC[d] }
            }
        }
        return out
    }

    fun hasCapture(b: IntArray, side: Int): Boolean = moves(b, side).firstOrNull()?.isCapture == true

    fun applied(b: IntArray, m: CkMove): IntArray {
        val n = b.copyOf()
        val p = n[m.from]
        n[m.from] = 0
        for (x in m.caps) n[x] = 0
        n[m.to] = if (m.promo) 2 * Integer.signum(p) else p
        return n
    }

    // ---------------- хэш позиции (Зобрист) ----------------
    private val ZOB = Random(20261113).let { r -> LongArray(64 * 5) { r.nextLong() } }
    private val ZSIDE = Random(77).nextLong()
    fun hash(b: IntArray, side: Int): Long {
        var h = if (side == 1) ZSIDE else 0L
        for (s in DARK) { val v = b[s]; if (v != 0) h = h xor ZOB[s * 5 + v + 2] }
        return h
    }

    // ---------------- оценка ----------------
    /** Продвижение простой по числу пройденных рядов (0…6). */
    private val ADV = intArrayOf(0, 2, 4, 7, 10, 14, 20, 0)
    private val CENTER = IntArray(64).also { a ->
        for (s in 0 until 64) { val r = s / 8; val c = s % 8
            a[s] = if (r in 3..4 && c in 2..5) 7 else if (r in 2..5 && c in 1..6) 3 else 0 }
    }
    private val KCENTER = IntArray(64).also { a ->
        for (s in 0 until 64) { val r = s / 8; val c = s % 8
            a[s] = (if (r + c == 7) 14 else 0) + (if (r in 2..5 && c in 2..5) 4 else 0) }
    }

    /** Оценка для стороны side (плюс — ей лучше). */
    fun eval(b: IntArray, side: Int): Int {
        var wm = 0; var bm = 0; var wk = 0; var bk = 0
        var pos = 0; var wBack = 0; var bBack = 0; var wAdv = 0; var bAdv = 0
        var wMob = 0; var bMob = 0
        for (s in DARK) {
            val v = b[s]; if (v == 0) continue
            val r = s / 8; val c = s % 8
            when (v) {
                1 -> { wm++; wAdv += ADV[7 - r]; pos += CENTER[s]; if (r == 7) wBack += if (c == 2 || c == 4) 10 else 5
                    if (r > 0) { if (c > 0 && b[s - 9] == 0) wMob++; if (c < 7 && b[s - 7] == 0) wMob++ } }
                -1 -> { bm++; bAdv += ADV[r]; pos -= CENTER[s]; if (r == 0) bBack += if (c == 3 || c == 5) 10 else 5
                    if (r < 7) { if (c > 0 && b[s + 7] == 0) bMob++; if (c < 7 && b[s + 9] == 0) bMob++ } }
                2 -> { wk++; pos += KCENTER[s]; wMob += kingMob(b, r, c) }
                -2 -> { bk++; pos -= KCENTER[s]; bMob += kingMob(b, r, c) }
            }
        }
        val total = wm + bm + wk + bk
        val kingVal = if (total <= 8) 300 else 260
        val mat = (wm - bm) * 100 + (wk - bk) * kingVal
        var s = mat + pos
        val advMul = if (total <= 10) 2 else 1
        s += (wAdv - bAdv) * advMul
        if (bk == 0 && total > 10) s += wBack
        if (wk == 0 && total > 10) s -= bBack
        s += (wMob - bMob) * 2
        // при перевесе — размениваться выгодно
        if (mat > 0) s += (24 - total) * 3 else if (mat < 0) s -= (24 - total) * 3
        return s * side
    }

    private fun kingMob(b: IntArray, r: Int, c: Int): Int {
        var n = 0
        for (d in 0 until 4) { val rr = r + DR[d]; val cc = c + DC[d]; if (inside(rr, cc) && b[rr * 8 + cc] == 0) n++ }
        return n
    }

    // ---------------- поиск ----------------
    private object Timeout : RuntimeException() {
        private fun readResolve(): Any = Timeout
        override fun fillInStackTrace(): Throwable = this
    }

    val LEVELS = listOf("Новичок", "Любитель", "Мастер", "Сильный")

    /**
     * Негамакс с альфа-бета, итеративным углублением, таблицей позиций и продлением взятий.
     * hist — позиции, уже бывшие в партии (повтор считается ничьей).
     */
    private class Search(val deadline: Long, val abort: () -> Boolean, val hist: Set<Long>, ttBits: Int) {
        val mask = (1 shl ttBits) - 1
        val tKey = LongArray(1 shl ttBits)
        val tVal = IntArray(1 shl ttBits)
        val tDepth = ByteArray(1 shl ttBits)
        val tFlag = ByteArray(1 shl ttBits)
        val tBest = ByteArray(1 shl ttBits)
        val history = IntArray(64 * 64)
        val pathKeys = LongArray(MAXPLY + 2)
        var nodes = 0L

        fun nm(b: IntArray, side: Int, depth: Int, a0: Int, beta: Int, ply: Int): Int {
            if ((++nodes and 1023L) == 0L && (System.nanoTime() > deadline || abort())) throw Timeout
            val h = hash(b, side)
            if (ply > 0) {
                if (h in hist) return 0
                var i = ply - 2
                while (i >= 0) { if (pathKeys[i] == h) return 0; i -= 2 }
            }
            if (ply >= MAXPLY) return eval(b, side)
            pathKeys[ply] = h
            val ms = moves(b, side)
            if (ms.isEmpty()) return -MATE + ply
            // за горизонтом досчитываем только взятия (они обязательны)
            if (depth <= 0 && !ms[0].isCapture) return eval(b, side)
            val slot = (h and mask.toLong()).toInt()
            var ttMove = -1
            if (tKey[slot] == h) {
                ttMove = tBest[slot].toInt()
                if (depth > 0 && tDepth[slot] >= depth) {
                    var v = tVal[slot]
                    if (v > MATE - 1000) v -= ply else if (v < -MATE + 1000) v += ply
                    when (tFlag[slot].toInt()) {
                        0 -> return v
                        1 -> if (v >= beta) return v
                        2 -> if (v <= a0) return v
                    }
                }
            }
            val n = ms.size
            val order = IntArray(n) { it }
            val score = IntArray(n) { i ->
                val m = ms[i]
                when {
                    i == ttMove -> 1 shl 28
                    m.isCapture -> 1_000_000 + m.caps.size * 1000 + (if (m.promo) 500 else 0)
                    m.promo -> 900_000
                    else -> history[m.from * 64 + m.to]
                }
            }
            // единственный ход — не тратим глубину
            val step = if (n == 1) 0 else 1
            var best = -INF; var bestI = 0; var a = a0
            for (k in 0 until n) {
                var bi = k
                for (j in k + 1 until n) if (score[order[j]] > score[order[bi]]) bi = j
                val t = order[k]; order[k] = order[bi]; order[bi] = t
                val i = order[k]
                val m = ms[i]
                val v = -nm(applied(b, m), -side, depth - step, -beta, -a, ply + 1)
                if (v > best) { best = v; bestI = i }
                if (v > a) a = v
                if (a >= beta) { if (!m.isCapture) history[m.from * 64 + m.to] += depth * depth + 1; break }
            }
            if (depth >= 0) {
                val flag = if (best <= a0) 2 else if (best >= beta) 1 else 0
                var sv = best
                if (sv > MATE - 1000) sv += ply else if (sv < -MATE + 1000) sv -= ply
                tKey[slot] = h; tVal[slot] = sv; tDepth[slot] = depth.coerceAtMost(120).toByte(); tFlag[slot] = flag.toByte()
                tBest[slot] = bestI.coerceAtMost(127).toByte()
            }
            return best
        }

        companion object { const val MAXPLY = 64 }
    }

    /**
     * Ход бота. level: 0 новичок (глубина 1 и иногда случайный ход), 1 любитель (3), 2 мастер (6),
     * 3 сильный (по времени ~1,5 с). hist — ключи позиций партии; abort — прервать (ушли с экрана).
     */
    fun best(
        b0: IntArray, side: Int, level: Int, rnd: Random = Random,
        hist: Set<Long> = emptySet(), abort: () -> Boolean = { false }, timeMs: Long = 1500,
    ): CkMove? {
        val b = b0.copyOf()
        val ms = moves(b, side)
        if (ms.size <= 1) return ms.firstOrNull()
        if (level <= 0 && rnd.nextFloat() < 0.3f) return ms.random(rnd)
        val maxDepth = when (level) { 0 -> 1; 1 -> 3; 2 -> 6; else -> 40 }
        val limit = if (level >= 3) timeMs else 6000L
        val s = Search(System.nanoTime() + limit * 1_000_000L, abort, hist, if (level >= 2) 18 else 15)
        val order = ms.indices.shuffled(rnd).toMutableList()   // равные ходы — в случайном порядке
        var bestI = order[0]
        for (d in 1..maxDepth) {
            var a = -INF; var bi = order[0]; var firstDone = false
            try {
                for (i in order) {
                    s.pathKeys[0] = hash(b, side)
                    val v = -s.nm(applied(b, ms[i]), -side, d - 1, -INF, -a, 1)
                    if (v > a) { a = v; bi = i }
                    firstDone = true
                }
            } catch (e: Timeout) {
                if (firstDone) bestI = bi
                break
            }
            bestI = bi
            order.remove(bi); order.add(0, bi)
            if (a > MATE - 1000 || a < -MATE + 1000) break
        }
        return ms[bestI]
    }
}

/** Партия: доска, чей ход, история для отмены, ничьи (15 ходов дамками без взятий, троекратный повтор). */
class CheckersGame {
    var board: IntArray = CheckersEngine.start(); private set
    var side: Int = 1; private set
    /** Полуходы подряд дамками без взятий и ходов простыми. */
    var quietKing = 0; private set
    var lastMove: CkMove? = null; private set
    private class Snap(val board: IntArray, val side: Int, val quiet: Int, val last: CkMove?)
    private val snaps = ArrayList<Snap>()
    private val keys = arrayListOf(CheckersEngine.hash(board, side))

    val plies: Int get() = snaps.size
    fun legal(): List<CkMove> = CheckersEngine.moves(board, side)
    fun count(color: Int) = CheckersEngine.DARK.count { board[it] * color > 0 }

    fun play(m: CkMove) {
        snaps.add(Snap(board, side, quietKing, lastMove))
        val king = abs(board[m.from]) == 2
        board = CheckersEngine.applied(board, m)
        quietKing = if (king && !m.isCapture) quietKing + 1 else 0
        side = -side
        lastMove = m
        keys.add(CheckersEngine.hash(board, side))
    }

    private fun pop() {
        val s = snaps.removeAt(snaps.size - 1)
        board = s.board; side = s.side; quietKing = s.quiet; lastMove = s.last
        keys.removeAt(keys.size - 1)
    }

    /** Был ли уже ход стороны color (есть что отменять). */
    fun hasMoveOf(color: Int) = snaps.any { it.side == color }

    /** Отменяет ход игрока human и ответ бота (возвращает ход игроку). */
    fun undo(human: Int): Boolean {
        if (!hasMoveOf(human)) return false
        do pop() while (snaps.isNotEmpty() && side != human)
        return true
    }

    /** Расставить позицию (для тестов и задач). */
    fun setup(b: IntArray, toMove: Int) { reset(); board = b.copyOf(); side = toMove; keys.clear(); keys.add(CheckersEngine.hash(board, side)) }

    fun reset() { board = CheckersEngine.start(); side = 1; quietKing = 0; lastMove = null; snaps.clear(); keys.clear(); keys.add(CheckersEngine.hash(board, side)) }

    /** Ключи прежних позиций (без текущей) — для бота: повтор = ничья. */
    fun pastKeys(): Set<Long> = keys.subList(0, keys.size - 1).toHashSet()

    /** null — игра идёт; 1/−1 — победил этот цвет; 0 — ничья. */
    fun result(): Int? {
        if (legal().isEmpty()) return -side
        if (drawReason() != null) return 0
        return null
    }

    fun drawReason(): String? {
        if (quietKing >= 30) return "15 ходов дамками без взятий"
        val k = keys[keys.size - 1]
        if (keys.count { it == k } >= 3) return "позиция повторилась трижды"
        return null
    }
}

// =====================================================================================
//  Звуки: деревянный щелчок шашки — свой синтез
// =====================================================================================

private object CheckersSfx {
    private const val SR = Sfx.SR
    private val cache = HashMap<String, ShortArray>()

    private class W(sec: Double) {
        val a = FloatArray((sec * SR).toInt())
        val r = Random(11)
        /** Удар дерева: затухающие моды (частота, затухание 1/с) и короткий щелчок шума. */
        fun knock(at: Double, modes: List<Pair<Double, Double>>, amp: Double, click: Double = 0.5) {
            val s0 = (at * SR).toInt()
            modes.forEachIndexed { m, (f, d) ->
                val len = (SR * 5.0 / d).toInt()
                for (i in 0 until len) {
                    val j = s0 + i; if (j !in a.indices) break
                    val t = i.toDouble() / SR
                    a[j] += (sin(2 * PI * f * t) * exp(-t * d) * amp / (1 + m * 0.7)).toFloat()
                }
            }
            val n = (0.004 * SR).toInt(); var lo = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break
                val x = r.nextDouble() * 2 - 1; lo += 0.25 * (x - lo)
                a[j] += ((x - lo) * click * amp * (1 - i.toDouble() / n)).toFloat()
            }
        }
        fun pcm(gain: Double): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4f)
            val fade = (0.003 * SR).toInt()
            return ShortArray(a.size) { i ->
                val e = minOf(1.0, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }

    private val WOOD = listOf(1180.0 to 75.0, 2470.0 to 120.0, 3900.0 to 190.0, 620.0 to 55.0)

    private fun render(key: String): ShortArray = when (key) {
        "move" -> W(0.16).run { knock(0.0, WOOD, 1.0); pcm(0.6) }
        "cap" -> W(0.26).run {
            knock(0.0, WOOD.map { it.first * 0.82 to it.second }, 1.0)
            knock(0.07, listOf(760.0 to 40.0, 1650.0 to 70.0, 2900.0 to 120.0), 0.7, 0.8)
            pcm(0.75)
        }
        "king" -> W(0.75).run {
            listOf(523.25, 659.25, 783.99, 1046.5).forEachIndexed { i, f -> knock(0.09 * i, listOf(f to 7.0, f * 4 to 18.0), 0.6, 0.1) }
            pcm(0.6)
        }
        else -> W(0.8).run {   // конец партии: два мягких удара
            knock(0.0, listOf(392.0 to 6.0, 1568.0 to 15.0), 0.7, 0.1); knock(0.18, listOf(523.25 to 5.0, 2093.0 to 14.0), 0.7, 0.1); pcm(0.55)
        }
    }

    var enabled = true
    fun play(key: String) {
        if (!enabled) return
        val pcm = synchronized(cache) { cache.getOrPut(key) { render(key) } }
        runCatching { Sfx.playPcm("ck_$key", pcm) }
    }
}

// =====================================================================================
//  Экран
// =====================================================================================

private class CkTheme(val name: String, val light: Color, val dark: Color, val frame: Color, val grain: Boolean)

private val CK_THEMES = listOf(
    CkTheme("Классика", Color(0xFFF2E8D5), Color(0xFF4F4038), Color(0xFF2F2621), false),
    CkTheme("Дерево", Color(0xFFEBCB97), Color(0xFFA5642F), Color(0xFF6A3B19), true),
    CkTheme("Зелёная", Color(0xFFEEEED2), Color(0xFF6F9A55), Color(0xFF3F5F33), false),
)

/** То, что рисуется поверх доски во время хода: доска «до», путь, сбитые. */
private class CkView(val before: IntArray, val path: List<Int>, val caps: List<Int>, val piece: Int)

@Composable
fun CheckersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("checkers", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1).coerceIn(0, 3)) }
    var colorPref by remember { mutableIntStateOf(prefs.getInt("color", 0).coerceIn(0, 2)) }   // 0 случайно, 1 белые, 2 чёрные
    var themeIdx by remember { mutableIntStateOf(prefs.getInt("theme", 1).coerceIn(0, CK_THEMES.size - 1)) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var stats by remember { mutableStateOf(Triple(prefs.getInt("w", 0), prefs.getInt("d", 0), prefs.getInt("l", 0))) }
    LaunchedEffect(sound) { CheckersSfx.enabled = sound }

    fun pickHuman(pref: Int) = when (pref) { 1 -> 1; 2 -> -1; else -> if (Random.nextBoolean()) 1 else -1 }

    val game = remember { CheckersGame() }
    var ver by remember { mutableIntStateOf(0) }
    var human by remember { mutableIntStateOf(pickHuman(colorPref)) }
    var sel by remember { mutableIntStateOf(-1) }
    var partial by remember { mutableStateOf<List<Int>>(emptyList()) }
    var view by remember { mutableStateOf<CkView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var counted by remember { mutableStateOf(false) }
    val pos = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }

    val legal = remember(ver) { game.legal() }
    val result = remember(ver) { game.result() }
    val drawWhy = remember(ver) { game.drawReason() }
    val flip = human == -1
    val mustCapture = legal.firstOrNull()?.isCapture == true

    fun commit(m: CkMove, fromSeg: Int) {
        val before = game.board.copyOf()
        val piece = before[m.from]
        game.play(m); ver++
        sel = -1; partial = emptyList()
        view = CkView(before, m.path.toList(), m.caps.toList(), piece)
        busy = true
        animJob = scope.launch {
            try {
                fade.snapTo(1f)
                pos.snapTo(fromSeg.toFloat())
                for (i in fromSeg until m.path.size - 1) {
                    val r1 = m.path[i] / 8; val c1 = m.path[i] % 8; val r2 = m.path[i + 1] / 8; val c2 = m.path[i + 1] % 8
                    val dist = maxOf(abs(r2 - r1), abs(c2 - c1))
                    pos.animateTo(i + 1f, tween(130 + 45 * dist, easing = FastOutSlowInEasing))
                    CheckersSfx.play(if (m.isCapture) "cap" else "move")
                }
                if (m.promo) CheckersSfx.play("king")
                if (m.isCapture) fade.animateTo(0f, tween(280, easing = LinearEasing))
            } finally {
                view = null; busy = false
            }
        }
    }

    /** Нажатие на поле (уже в координатах доски). */
    fun tap(s: Int) {
        if (busy || result != null || game.side != human || thinking) return
        fun step(to: Int) {
            val np = (if (partial.isEmpty()) listOf(sel) else partial) + to
            val cands = legal.filter { m -> m.path.size >= np.size && np.indices.all { m.path[it] == np[it] } }
            val done = cands.firstOrNull { it.path.size == np.size }
            if (done != null) { commit(done, np.size - 2); return }
            val c0 = cands.firstOrNull() ?: return
            // посреди цепочки: шашка прыгает, ждём следующее поле
            view = CkView(game.board.copyOf(), np, c0.caps.take(np.size - 1), game.board[np[0]])
            partial = np
            busy = true
            animJob = scope.launch {
                try {
                    pos.snapTo(np.size - 2f); fade.snapTo(1f)
                    pos.animateTo(np.size - 1f, tween(200, easing = FastOutSlowInEasing))
                    CheckersSfx.play("cap")
                } finally { busy = false }
            }
        }
        if (partial.size > 1) {
            val nexts = legal.filter { m -> m.path.size > partial.size && partial.indices.all { m.path[it] == partial[it] } }.map { it.path[partial.size] }
            if (s in nexts) step(s)
            return
        }
        if (sel >= 0 && legal.any { it.from == sel && it.path[1] == s }) { step(s); return }
        if (legal.any { it.from == s }) { sel = if (sel == s) -1 else s; partial = emptyList() }
        else { sel = -1; partial = emptyList() }
    }

    fun newGame() {
        animJob?.cancel()
        game.reset(); human = pickHuman(colorPref); ver++
        sel = -1; partial = emptyList(); view = null; busy = false; counted = false
    }

    fun undo() {
        if (busy) return
        if (partial.size > 1) { sel = -1; partial = emptyList(); view = null; return }   // передумал посреди цепочки
        if (game.undo(human)) { ver++; sel = -1; partial = emptyList(); view = null; counted = false }
    }

    // ход бота: в фоне, анимация — в своей корутине (смена ver отменила бы эффект)
    LaunchedEffect(ver, busy, human) {
        if (busy || result != null || game.side == human) return@LaunchedEffect
        thinking = true
        try {
            delay(250)
            val b = game.board.copyOf(); val side = game.side; val hist = game.pastKeys(); val lv = level
            val m = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                CheckersEngine.best(b, side, lv, hist = hist, abort = { job?.isActive == false })
            }
            thinking = false
            if (m != null && game.side == side) commit(m, 0)
        } finally { thinking = false }
    }

    LaunchedEffect(ver) {
        val r = result ?: return@LaunchedEffect
        if (counted) return@LaunchedEffect
        counted = true
        stats = when (r) { human -> stats.copy(first = stats.first + 1); 0 -> stats.copy(second = stats.second + 1); else -> stats.copy(third = stats.third + 1) }
        prefs.edit().putInt("w", stats.first).putInt("d", stats.second).putInt("l", stats.third).apply()
        delay(700); CheckersSfx.play("end")
    }

    val theme = CK_THEMES[themeIdx]
    val onTap by rememberUpdatedState<(Int) -> Unit>({ tap(it) })

    ScreenScaffold("Шашки", onBack, actions = {
        RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук", {
            sound = !sound; prefs.edit().putBoolean("sound", sound).apply()
        })
        RoundIconButton(Icons.Rounded.Tune, "Настройки", { showSettings = true })
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("победы" to stats.first, "ничьи" to stats.second, "поражения" to stats.third).forEach { (k, v) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$v", style = MaterialTheme.typography.headlineMedium)
                            Text(k, style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                        }
                    }
                }
                // соперник сверху
                PlayerStrip(if (human == 1) -1 else 1, "Бот · ${CheckersEngine.LEVELS[level]}", 12 - game.count(human), active = game.side != human && result == null)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(theme.frame).padding(6.dp)) {
                    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).pointerInput(flip) {
                        detectTapGestures { p ->
                            val cs = size.width / 8f
                            val dc = (p.x / cs).toInt().coerceIn(0, 7); val dr = (p.y / cs).toInt().coerceIn(0, 7)
                            val r = if (flip) 7 - dr else dr; val c = if (flip) 7 - dc else dc
                            onTap(r * 8 + c)
                        }
                    }) {
                        if (ver < 0) return@Canvas   // чтение ver — перерисовка после каждого хода
                        drawBoard(theme, flip)
                        val cs = size.width / 8f
                        fun center(s: Int): Offset {
                            val r = s / 8; val c = s % 8
                            val dr = if (flip) 7 - r else r; val dc = if (flip) 7 - c else c
                            return Offset((dc + 0.5f) * cs, (dr + 0.5f) * cs)
                        }
                        fun cell(s: Int, color: Color) {
                            val o = center(s); drawRect(color, Offset(o.x - cs / 2, o.y - cs / 2), Size(cs, cs))
                        }
                        // последний ход
                        game.lastMove?.let { lm -> if (view == null) lm.path.forEach { cell(it, Color(0xFFFFD54F).copy(alpha = 0.38f)) } }
                        val v = view
                        val shown = v?.before ?: game.board
                        // какими шашками можно ходить (если надо бить — подсказать)
                        if (v == null && result == null && game.side == human && !busy) {
                            if (sel >= 0) cell(sel, Color(0xFF66BB6A).copy(alpha = 0.45f))
                            if (mustCapture && sel < 0) legal.map { it.from }.distinct().forEach { s ->
                                drawCircle(Color(0xFFFF7043), cs * 0.46f, center(s), style = Stroke(cs * 0.06f))
                            }
                        }
                        // шашки
                        val movingFrom = v?.path?.firstOrNull() ?: -1
                        for (s in CheckersEngine.DARK) {
                            val p = shown[s]; if (p == 0 || s == movingFrom) continue
                            var alpha = 1f
                            if (v != null) {
                                val k = v.caps.indexOf(s)
                                if (k >= 0) alpha = if (pos.value >= k + 0.5f) 0.45f * fade.value else 1f
                            }
                            if (alpha > 0.01f) drawChecker(center(s), cs * 0.4f, p > 0, abs(p) == 2, alpha, if (alpha < 1f) Color(0xFFE53935) else null)
                        }
                        // движущаяся шашка
                        if (v != null && v.path.size > 1) {
                            val last = v.path.size - 1
                            val t = pos.value.coerceIn(0f, last.toFloat())
                            val i = t.toInt().coerceAtMost(last - 1); val f = t - i
                            val a = center(v.path[i]); val b = center(v.path[i + 1])
                            val lift = sin(f * PI).toFloat() * cs * 0.12f
                            val at = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f - lift)
                            val promoRow = if (v.piece > 0) 0 else 7
                            val promoAt = v.path.indexOfFirst { it / 8 == promoRow }
                            val king = abs(v.piece) == 2 || (promoAt > 0 && t >= promoAt - 0.05f)
                            drawChecker(at, cs * 0.4f * (1f + lift / cs), v.piece > 0, king, 1f, null)
                        }
                        // куда можно пойти
                        if (result == null && game.side == human && !busy) {
                            val dests = when {
                                partial.size > 1 -> legal.filter { m -> m.path.size > partial.size && partial.indices.all { m.path[it] == partial[it] } }.map { it.path[partial.size] }
                                sel >= 0 -> legal.filter { it.from == sel }.map { it.path[1] }
                                else -> emptyList()
                            }.distinct()
                            dests.forEach { s ->
                                if (mustCapture) drawCircle(Color(0xFF2E7D32).copy(alpha = 0.75f), cs * 0.3f, center(s), style = Stroke(cs * 0.07f))
                                else drawCircle(Color(0xFF2E7D32).copy(alpha = 0.6f), cs * 0.15f, center(s))
                            }
                        }
                    }
                }
                PlayerStrip(human, if (human == 1) "Ты · белые" else "Ты · чёрные", 12 - game.count(-human), active = game.side == human && result == null)
                Text(
                    when {
                        result != null -> when (result) { human -> "Победа!"; 0 -> "Ничья: ${drawWhy ?: ""}"; else -> "Бот выиграл" }
                        game.side != human -> "Бот думает…"
                        partial.size > 1 -> "Бей дальше — цепочку надо закончить"
                        mustCapture -> "Нужно бить"
                        game.plies == 0 -> "Ты ходишь первым"
                        else -> "Твой ход"
                    } + (game.lastMove?.let { if (result == null) "  ·  $it" else "" } ?: ""),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val canUndo = !busy && (partial.size > 1 || game.hasMoveOf(human))
                    Row(
                        Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card.copy(alpha = if (canUndo) 1f else 0.5f))
                            .clickable(enabled = canUndo) { undo() }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.Undo, null, tint = if (canUndo) Pal.Ink else Pal.Muted)
                        Text("Отменить", style = MaterialTheme.typography.labelLarge, color = if (canUndo) Pal.Ink else Pal.Muted)
                    }
                    PillButton("Новая партия", { newGame() }, Modifier.weight(1f))
                }
            }
            if (result != null && !busy) {
                when (result) {
                    human -> VictoryOverlay("Победа!", "Бот «${CheckersEngine.LEVELS[level]}» повержен", "Ещё партия") { newGame() }
                    0 -> GameOverlay("Ничья", drawWhy ?: "", "Ещё партия") { newGame() }
                    else -> GameOverlay("Поражение", if (game.count(human) == 0) "Все твои шашки сбиты" else "Тебе некуда ходить", "Ещё партия") { newGame() }
                }
            }
        }
    }

    if (showSettings) CheckersSettings(
        level, colorPref, themeIdx,
        onLevel = { level = it; prefs.edit().putInt("level", it).apply() },
        onColor = { colorPref = it; prefs.edit().putInt("color", it).apply() },
        onTheme = { themeIdx = it; prefs.edit().putInt("theme", it).apply() },
        onNew = { showSettings = false; newGame() },
        onDismiss = { showSettings = false },
    )
}

/** Полоска игрока: цвет шашки, имя, сколько шашек он сбил. */
@Composable
private fun PlayerStrip(color: Int, title: String, took: Int, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) Pal.Card else Pal.Card.copy(alpha = 0.5f))
            .then(if (active) Modifier.border(1.5.dp, Pal.LilacInk.copy(alpha = 0.5f), RoundedCornerShape(14.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.size(22.dp)) { drawChecker(center, size.minDimension * 0.45f, color > 0, false, 1f, null) }
        Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f))
        if (took > 0) {
            Canvas(Modifier.size(16.dp)) { drawChecker(center, size.minDimension * 0.45f, color < 0, false, 1f, null) }
            Text("× $took", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
        }
    }
}

@Composable
private fun CheckersSettings(
    level: Int, color: Int, theme: Int,
    onLevel: (Int) -> Unit, onColor: (Int) -> Unit, onTheme: (Int) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Шашки") },
        confirmButton = { PillButton("Новая партия", onNew) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Сложность бота")
                Segmented(CheckersEngine.LEVELS, level, onLevel)
                SectionLabel("Мой цвет (со следующей партии)")
                Segmented(listOf("Случайно", "Белые", "Чёрные"), color, onColor)
                SectionLabel("Доска")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CK_THEMES.forEachIndexed { i, t ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTheme(i) }
                            .then(if (i == theme) Modifier.border(2.dp, Pal.Ink, RoundedCornerShape(12.dp)) else Modifier).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
                                val cs = size.width / 4
                                for (r in 0 until 4) for (c in 0 until 4) drawRect(if ((r + c) % 2 == 1) t.dark else t.light, Offset(c * cs, r * cs), Size(cs, cs))
                                drawChecker(Offset(cs * 1.5f, cs * 0.5f), cs * 0.4f, false, false, 1f, null)
                                drawChecker(Offset(cs * 0.5f, cs * 3.5f), cs * 0.4f, true, true, 1f, null)
                            }
                            Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
                Text("Русские шашки: бить обязательно, простая бьёт и назад, дамка ходит и бьёт на любое расстояние.",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        })
}

/** Поля доски, древесные волокна и подписи a–h / 1–8. */
private fun DrawScope.drawBoard(t: CkTheme, flip: Boolean) {
    val cs = size.width / 8f
    for (r in 0 until 8) for (c in 0 until 8) {
        val dark = (r + c) % 2 == 1
        val base = if (dark) t.dark else t.light
        drawRect(Brush.linearGradient(listOf(base, lerpColor(base, Color.Black, if (dark) 0.12f else 0.05f)),
            Offset(c * cs, r * cs), Offset((c + 1) * cs, (r + 1) * cs)), Offset(c * cs, r * cs), Size(cs, cs))
    }
    if (t.grain) {
        val rnd = Random(5)
        repeat(46) { k ->
            val y0 = rnd.nextFloat() * size.height
            val amp = cs * (0.03f + rnd.nextFloat() * 0.06f); val freq = 1.5f + rnd.nextFloat() * 2.5f; val ph = rnd.nextFloat() * 6.28f
            val p = Path()
            var x = 0f
            p.moveTo(0f, y0)
            while (x <= size.width) { p.lineTo(x, y0 + amp * sin(ph + x / size.width * freq * 6.28f)); x += size.width / 40 }
            drawPath(p, Color(0xFF3B1F0B).copy(alpha = if (k % 3 == 0) 0.10f else 0.05f), style = Stroke(if (k % 4 == 0) 2.2f else 1.2f))
        }
    }
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = cs * 0.2f; isFakeBoldText = true }
    for (i in 0 until 8) {
        val c = if (flip) 7 - i else i
        val r = if (flip) 7 - i else i
        // буквы по нижнему ряду, цифры по левому столбцу; цвет — контрастный к полю
        paint.color = (if ((7 + i) % 2 == 1) t.light else t.dark).copy(alpha = 0.85f).toArgb()
        drawContext.canvas.nativeCanvas.drawText("${'a' + c}", i * cs + cs * 0.80f, size.height - cs * 0.07f, paint)
        paint.color = (if (i % 2 == 1) t.light else t.dark).copy(alpha = 0.85f).toArgb()
        drawContext.canvas.nativeCanvas.drawText("${8 - r}", cs * 0.06f, i * cs + cs * 0.24f, paint)
    }
}

private fun lerpColor(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, a.alpha)

/** Шашка: тень, объёмная заливка, обод, канавка, блик; дамка — с короной. */
private fun DrawScope.drawChecker(c: Offset, r: Float, white: Boolean, king: Boolean, alpha: Float, mark: Color?) {
    val light = if (white) Color(0xFFFFFCF4) else Color(0xFF76675E)
    val mid = if (white) Color(0xFFEADBC0) else Color(0xFF332A25)
    val dark = if (white) Color(0xFFB09A76) else Color(0xFF120E0C)
    drawCircle(Color.Black.copy(alpha = 0.32f * alpha), r * 1.02f, c + Offset(r * 0.06f, r * 0.14f))
    // боковина (толщина шашки)
    drawCircle(dark.copy(alpha = alpha), r, c + Offset(0f, r * 0.07f))
    drawCircle(Brush.radialGradient(listOf(light, mid, dark), center = c - Offset(r * 0.35f, r * 0.42f), radius = r * 1.6f), r * 0.96f, c, alpha = alpha)
    drawCircle(dark.copy(alpha = 0.55f * alpha), r * 0.94f, c, style = Stroke(r * 0.06f))
    drawCircle(dark.copy(alpha = 0.45f * alpha), r * 0.66f, c, style = Stroke(r * 0.07f))
    drawCircle(light.copy(alpha = 0.55f * alpha), r * 0.6f, c + Offset(-r * 0.02f, -r * 0.03f), style = Stroke(r * 0.03f))
    drawOval(Color.White.copy(alpha = (if (white) 0.55f else 0.22f) * alpha), c + Offset(-r * 0.62f, -r * 0.72f), Size(r * 0.62f, r * 0.34f))
    if (king) {
        val p = Path().apply {
            moveTo(c.x - r * 0.42f, c.y + r * 0.26f)
            lineTo(c.x - r * 0.5f, c.y - r * 0.22f)
            lineTo(c.x - r * 0.22f, c.y + r * 0.02f)
            lineTo(c.x, c.y - r * 0.36f)
            lineTo(c.x + r * 0.22f, c.y + r * 0.02f)
            lineTo(c.x + r * 0.5f, c.y - r * 0.22f)
            lineTo(c.x + r * 0.42f, c.y + r * 0.26f)
            close()
        }
        drawPath(p, Brush.verticalGradient(listOf(Color(0xFFFFE9A0), Color(0xFFE0A91C), Color(0xFFB07A0A)), c.y - r * 0.36f, c.y + r * 0.26f), alpha = alpha)
        drawPath(p, Color(0xFF6E4A00).copy(alpha = alpha), style = Stroke(r * 0.05f))
        for (x in listOf(-0.5f to -0.22f, 0f to -0.36f, 0.5f to -0.22f)) drawCircle(Color(0xFFFFF3C4).copy(alpha = alpha), r * 0.07f, Offset(c.x + r * x.first, c.y + r * x.second))
    }
    if (mark != null) {
        val s = r * 0.35f
        drawLine(mark.copy(alpha = 0.9f), c + Offset(-s, -s), c + Offset(s, s), r * 0.12f)
        drawLine(mark.copy(alpha = 0.9f), c + Offset(s, -s), c + Offset(-s, s), r * 0.12f)
    }
}
