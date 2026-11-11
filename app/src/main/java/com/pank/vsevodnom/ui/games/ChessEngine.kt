package com.pank.vsevodnom.ui.games

import kotlin.math.abs
import kotlin.random.Random

/**
 * Небольшой шахматный движок: полные правила (рокировка, взятие на проходе, превращение,
 * мат/пат, 50 ходов, троекратное повторение, недостаток материала) и поиск
 * negamax + альфа-бета + quiescence + таблица транспозиций.
 * Клетка = ряд*8 + вертикаль, ряд 0 — первая горизонталь белых.
 * Фигуры: 1 пешка, 2 конь, 3 слон, 4 ладья, 5 ферзь, 6 король; знак — цвет (+ белые).
 */
class Chess {
    val b = IntArray(64)
    var side = 1
    var castle = 15        // 1 — белые короткая, 2 — белые длинная, 4 — чёрные короткая, 8 — чёрные длинная
    var ep = -1
    var half = 0
    var full = 1
    var hash = 0L
    val history = ArrayList<Long>()

    private class Undo(val move: Int, val captured: Int, val castle: Int, val ep: Int, val half: Int, val hash: Long)
    private val undo = ArrayList<Undo>()

    companion object {
        const val F_EP = 1; const val F_CASTLE = 2; const val F_DOUBLE = 4
        fun from(m: Int) = m and 63
        fun toSq(m: Int) = (m shr 6) and 63
        fun promo(m: Int) = (m shr 12) and 7
        fun flags(m: Int) = (m shr 16) and 7
        fun mk(f: Int, t: Int, p: Int = 0, fl: Int = 0) = f or (t shl 6) or (p shl 12) or (fl shl 16)

        private val rnd = Random(20261106)
        val Z_PIECE = Array(13) { LongArray(64) { rnd.nextLong() } }
        val Z_SIDE = rnd.nextLong()
        val Z_CASTLE = LongArray(16) { rnd.nextLong() }
        val Z_EP = LongArray(8) { rnd.nextLong() }

        val KN = intArrayOf(-17, -15, -10, -6, 6, 10, 15, 17)
        val KG = intArrayOf(-9, -8, -7, -1, 1, 7, 8, 9)
        val DIAG = intArrayOf(-9, -7, 7, 9)
        val ORTH = intArrayOf(-8, -1, 1, 8)
        val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 20000)
        fun file(s: Int) = s and 7
        fun rank(s: Int) = s shr 3

        // Таблицы позиций (упрощённая оценка Томаша Михневского), с точки зрения белых, ряд 8 сверху
        private val PST_P = intArrayOf(0,0,0,0,0,0,0,0, 50,50,50,50,50,50,50,50, 10,10,20,30,30,20,10,10, 5,5,10,25,25,10,5,5, 0,0,0,20,20,0,0,0, 5,-5,-10,0,0,-10,-5,5, 5,10,10,-20,-20,10,10,5, 0,0,0,0,0,0,0,0)
        private val PST_N = intArrayOf(-50,-40,-30,-30,-30,-30,-40,-50, -40,-20,0,0,0,0,-20,-40, -30,0,10,15,15,10,0,-30, -30,5,15,20,20,15,5,-30, -30,0,15,20,20,15,0,-30, -30,5,10,15,15,10,5,-30, -40,-20,0,5,5,0,-20,-40, -50,-40,-30,-30,-30,-30,-40,-50)
        private val PST_B = intArrayOf(-20,-10,-10,-10,-10,-10,-10,-20, -10,0,0,0,0,0,0,-10, -10,0,5,10,10,5,0,-10, -10,5,5,10,10,5,5,-10, -10,0,10,10,10,10,0,-10, -10,10,10,10,10,10,10,-10, -10,5,0,0,0,0,5,-10, -20,-10,-10,-10,-10,-10,-10,-20)
        private val PST_R = intArrayOf(0,0,0,0,0,0,0,0, 5,10,10,10,10,10,10,5, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5, -5,0,0,0,0,0,0,-5, 0,0,0,5,5,0,0,0)
        private val PST_Q = intArrayOf(-20,-10,-10,-5,-5,-10,-10,-20, -10,0,0,0,0,0,0,-10, -10,0,5,5,5,5,0,-10, -5,0,5,5,5,5,0,-5, 0,0,5,5,5,5,0,-5, -10,5,5,5,5,5,0,-10, -10,0,5,0,0,0,0,-10, -20,-10,-10,-5,-5,-10,-10,-20)
        private val PST_K = intArrayOf(-30,-40,-40,-50,-50,-40,-40,-30, -30,-40,-40,-50,-50,-40,-40,-30, -30,-40,-40,-50,-50,-40,-40,-30, -30,-40,-40,-50,-50,-40,-40,-30, -20,-30,-30,-40,-40,-30,-30,-20, -10,-20,-20,-20,-20,-20,-20,-10, 20,20,0,0,0,0,20,20, 20,30,10,0,0,10,30,20)
        private val PST_K_END = intArrayOf(-50,-40,-30,-20,-20,-30,-40,-50, -30,-20,-10,0,0,-10,-20,-30, -30,-10,20,30,30,20,-10,-30, -30,-10,30,40,40,30,-10,-30, -30,-10,30,40,40,30,-10,-30, -30,-10,20,30,30,20,-10,-30, -30,-30,0,0,0,0,-30,-30, -50,-30,-30,-30,-30,-30,-30,-50)
        private val PST = arrayOf(PST_P, PST_P, PST_N, PST_B, PST_R, PST_Q, PST_K)
        /** Индекс в таблице для фигуры цвета c на клетке s. */
        fun pstIdx(s: Int, c: Int) = if (c > 0) (7 - rank(s)) * 8 + file(s) else rank(s) * 8 + file(s)
        fun pst(t: Int, s: Int, c: Int, endgame: Boolean) = if (t == 6 && endgame) PST_K_END[pstIdx(s, c)] else PST[t][pstIdx(s, c)]
    }

    init { reset() }

    fun reset() {
        b.fill(0)
        val back = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
        for (f in 0 until 8) { b[f] = back[f]; b[8 + f] = 1; b[48 + f] = -1; b[56 + f] = -back[f] }
        side = 1; castle = 15; ep = -1; half = 0; full = 1
        undo.clear(); history.clear()
        hash = computeHash(); history += hash
    }

    /** Загрузка позиции из FEN (для проверок). */
    fun loadFen(fen: String) {
        val parts = fen.trim().split(Regex("\\s+"))
        b.fill(0)
        var r = 7; var f = 0
        for (ch in parts[0]) {
            when {
                ch == '/' -> { r--; f = 0 }
                ch.isDigit() -> f += ch - '0'
                else -> {
                    val t = "pnbrqk".indexOf(ch.lowercaseChar()) + 1
                    b[r * 8 + f] = if (ch.isUpperCase()) t else -t; f++
                }
            }
        }
        side = if (parts.getOrElse(1) { "w" } == "w") 1 else -1
        castle = 0
        parts.getOrElse(2) { "-" }.forEach { when (it) { 'K' -> castle = castle or 1; 'Q' -> castle = castle or 2; 'k' -> castle = castle or 4; 'q' -> castle = castle or 8 } }
        val e = parts.getOrElse(3) { "-" }
        ep = if (e == "-") -1 else (e[1] - '1') * 8 + (e[0] - 'a')
        half = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
        full = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1
        undo.clear(); history.clear(); hash = computeHash(); history += hash
    }

    fun perft(d: Int): Long {
        if (d == 0) return 1
        var n = 0L
        for (m in pseudo()) { make(m); if (!inCheck(-side)) n += perft(d - 1); unmake() }
        return n
    }

    fun copy(): Chess {
        val c = Chess()
        b.copyInto(c.b); c.side = side; c.castle = castle; c.ep = ep; c.half = half; c.full = full; c.hash = hash
        c.history.clear(); c.history.addAll(history)
        return c
    }

    private fun zi(p: Int) = p + 6
    fun computeHash(): Long {
        var h = 0L
        for (s in 0 until 64) if (b[s] != 0) h = h xor Z_PIECE[zi(b[s])][s]
        if (side < 0) h = h xor Z_SIDE
        h = h xor Z_CASTLE[castle]
        if (ep >= 0) h = h xor Z_EP[file(ep)]
        return h
    }

    fun kingSq(c: Int): Int { for (s in 0 until 64) if (b[s] == 6 * c) return s; return -1 }

    /** Атакована ли клетка s фигурами цвета by. */
    fun attacked(s: Int, by: Int): Boolean {
        val f = file(s)
        // пешки
        val pr = if (by > 0) s - 8 else s + 8
        if (pr in 0..63) {
            if (f > 0 && b[pr - 1] == by) return true
            if (f < 7 && b[pr + 1] == by) return true
        }
        for (d in KN) { val t = s + d; if (t in 0..63 && abs(file(t) - f) <= 2 && b[t] == 2 * by) return true }
        for (d in KG) { val t = s + d; if (t in 0..63 && abs(file(t) - f) <= 1 && b[t] == 6 * by) return true }
        for (d in DIAG) {
            var t = s; var pf = f
            while (true) { t += d; if (t !in 0..63 || abs(file(t) - pf) != 1) break; pf = file(t); val p = b[t]; if (p != 0) { if (p == 3 * by || p == 5 * by) return true; break } }
        }
        for (d in ORTH) {
            var t = s; var pf = f
            while (true) { t += d; if (t !in 0..63 || (abs(d) == 1 && abs(file(t) - pf) != 1)) break; pf = file(t); val p = b[t]; if (p != 0) { if (p == 4 * by || p == 5 * by) return true; break } }
        }
        return false
    }

    fun inCheck(c: Int = side) = kingSq(c).let { it >= 0 && attacked(it, -c) }

    /** Псевдолегальные ходы (могут оставлять короля под шахом). */
    fun pseudo(capturesOnly: Boolean = false): IntArray {
        val out = IntArray(256); var n = 0
        val c = side
        for (s in 0 until 64) {
            val p = b[s]; if (p == 0 || (p > 0) != (c > 0)) continue
            val t = abs(p); val f = file(s)
            when (t) {
                1 -> {
                    val dir = 8 * c; val start = if (c > 0) 1 else 6; val last = if (c > 0) 7 else 0
                    val one = s + dir
                    if (one in 0..63 && b[one] == 0 && !capturesOnly) {
                        if (rank(one) == last) for (pp in intArrayOf(5, 4, 3, 2)) out[n++] = mk(s, one, pp)
                        else {
                            out[n++] = mk(s, one)
                            val two = one + dir
                            if (rank(s) == start && b[two] == 0) out[n++] = mk(s, two, 0, F_DOUBLE)
                        }
                    }
                    for (df in intArrayOf(-1, 1)) {
                        if (f + df !in 0..7) continue
                        val x = one + df; if (x !in 0..63) continue
                        if (b[x] != 0 && (b[x] > 0) != (c > 0)) {
                            if (rank(x) == last) for (pp in intArrayOf(5, 4, 3, 2)) out[n++] = mk(s, x, pp) else out[n++] = mk(s, x)
                        } else if (x == ep) out[n++] = mk(s, x, 0, F_EP)
                    }
                }
                2, 6 -> {
                    val ds = if (t == 2) KN else KG
                    for (d in ds) {
                        val x = s + d; if (x !in 0..63 || abs(file(x) - f) > 2) continue
                        if (t == 6 && abs(file(x) - f) > 1) continue
                        val q = b[x]; if (q != 0 && (q > 0) == (c > 0)) continue
                        if (capturesOnly && q == 0) continue
                        out[n++] = mk(s, x)
                    }
                    if (t == 6 && !capturesOnly) {
                        val home = if (c > 0) 4 else 60
                        if (s == home && !attacked(s, -c)) {
                            val kBit = if (c > 0) 1 else 4; val qBit = if (c > 0) 2 else 8
                            if (castle and kBit != 0 && b[s + 1] == 0 && b[s + 2] == 0 && b[s + 3] == 4 * c && !attacked(s + 1, -c) && !attacked(s + 2, -c))
                                out[n++] = mk(s, s + 2, 0, F_CASTLE)
                            if (castle and qBit != 0 && b[s - 1] == 0 && b[s - 2] == 0 && b[s - 3] == 0 && b[s - 4] == 4 * c && !attacked(s - 1, -c) && !attacked(s - 2, -c))
                                out[n++] = mk(s, s - 2, 0, F_CASTLE)
                        }
                    }
                }
                else -> {
                    val ds = when (t) { 3 -> DIAG; 4 -> ORTH; else -> KG }
                    for (d in ds) {
                        var x = s; var pf = f
                        while (true) {
                            x += d
                            if (x !in 0..63 || abs(file(x) - pf) > 1) break
                            pf = file(x)
                            val q = b[x]
                            if (q != 0) { if ((q > 0) != (c > 0)) out[n++] = mk(s, x); break }
                            if (!capturesOnly) out[n++] = mk(s, x)
                        }
                    }
                }
            }
        }
        return out.copyOf(n)
    }

    fun legal(): List<Int> = pseudo().filter { m -> make(m); val ok = !inCheck(-side); unmake(); ok }

    fun make(m: Int) {
        val f = from(m); val t = toSq(m); val fl = flags(m); val p = b[f]
        var cap = b[t]
        undo += Undo(m, cap, castle, ep, half, hash)
        var h = hash xor Z_CASTLE[castle]
        if (ep >= 0) h = h xor Z_EP[file(ep)]
        h = h xor Z_PIECE[zi(p)][f]
        if (fl and F_EP != 0) {
            val cs = t - 8 * side; cap = b[cs]; b[cs] = 0; h = h xor Z_PIECE[zi(cap)][cs]
            undo[undo.lastIndex] = Undo(m, cap, castle, ep, half, hash)
        } else if (cap != 0) h = h xor Z_PIECE[zi(cap)][t]
        b[f] = 0
        val np = if (promo(m) != 0) promo(m) * side else p
        b[t] = np; h = h xor Z_PIECE[zi(np)][t]
        if (fl and F_CASTLE != 0) {
            val (rf, rt) = if (t > f) (t + 1) to (t - 1) else (t - 2) to (t + 1)
            val r = b[rf]; b[rf] = 0; b[rt] = r
            h = h xor Z_PIECE[zi(r)][rf] xor Z_PIECE[zi(r)][rt]
        }
        // права на рокировку
        if (abs(p) == 6) castle = castle and (if (side > 0) 3.inv() else 12.inv())
        for (sq in intArrayOf(f, t)) when (sq) { 0 -> castle = castle and 2.inv(); 7 -> castle = castle and 1.inv(); 56 -> castle = castle and 8.inv(); 63 -> castle = castle and 4.inv() }
        ep = if (fl and F_DOUBLE != 0) (f + t) / 2 else -1
        half = if (abs(p) == 1 || cap != 0) 0 else half + 1
        if (side < 0) full++
        side = -side
        h = h xor Z_SIDE xor Z_CASTLE[castle]
        if (ep >= 0) h = h xor Z_EP[file(ep)]
        hash = h
        history += hash
    }

    fun unmake() {
        val u = undo.removeAt(undo.lastIndex)
        history.removeAt(history.lastIndex)
        side = -side
        if (side < 0) full--
        val m = u.move; val f = from(m); val t = toSq(m); val fl = flags(m)
        val moved = if (promo(m) != 0) side else b[t]
        b[f] = moved
        if (fl and F_EP != 0) { b[t] = 0; b[t - 8 * side] = u.captured } else b[t] = u.captured
        if (fl and F_CASTLE != 0) {
            val (rf, rt) = if (t > f) (t + 1) to (t - 1) else (t - 2) to (t + 1)
            b[rf] = b[rt]; b[rt] = 0
        }
        castle = u.castle; ep = u.ep; half = u.half; hash = u.hash
    }

    val moveCount get() = undo.size
    /** Сыгранные ходы по порядку (для анализа партии). */
    fun moves(): List<Int> = undo.map { it.move }

    /** Оценка позиции для анализа: (лучший ход, оценка в сантипешках для стороны, которая ходит). */
    fun analyse(timeMs: Long, maxDepth: Int = 5): Pair<Int?, Int> {
        val moves = legal()
        if (moves.isEmpty()) return null to (if (inCheck()) -100_000 else 0)
        deadline = System.currentTimeMillis() + timeMs; stop = false
        var best = moves.first(); var bestScore = 0
        for (d in 1..maxDepth) {
            var alpha = -1_000_000; var bm = 0
            for (m in order(moves.toIntArray(), 0, best)) {
                make(m); val v = -search(d - 1, -1_000_000, -alpha, 1); unmake()
                if (stop) break
                if (v > alpha) { alpha = v; bm = m }
            }
            if (stop) break
            if (bm != 0) { best = bm; bestScore = alpha }
        }
        return best to bestScore
    }
    fun lastMove(): Int? = undo.lastOrNull()?.move

    // ----------- Конец партии -----------

    fun insufficient(): Boolean {
        val pcs = (0 until 64).filter { b[it] != 0 && abs(b[it]) != 6 }
        if (pcs.isEmpty()) return true
        if (pcs.size == 1 && abs(b[pcs[0]]) in 2..3) return true
        if (pcs.size == 2 && pcs.all { abs(b[it]) == 3 } && (file(pcs[0]) + rank(pcs[0])) % 2 == (file(pcs[1]) + rank(pcs[1])) % 2) return true
        return false
    }

    fun repetitions() = history.count { it == hash }

    /** null — игра идёт; иначе текст результата. */
    fun result(): String? {
        if (legal().isEmpty()) return if (inCheck()) (if (side > 0) "Мат. Победили чёрные" else "Мат. Победили белые") else "Пат — ничья"
        if (half >= 100) return "Ничья: 50 ходов без взятий"
        if (repetitions() >= 3) return "Ничья: троекратное повторение"
        if (insufficient()) return "Ничья: недостаточно материала"
        return null
    }

    // ----------- Оценка и поиск -----------

    fun evaluate(): Int {
        var mat = 0
        for (s in 0 until 64) { val p = b[s]; if (p != 0 && abs(p) != 6 && abs(p) != 1) mat += VAL[abs(p)] }
        val endgame = mat <= 1600
        var sc = 0
        var wb = 0; var bb = 0
        for (s in 0 until 64) {
            val p = b[s]; if (p == 0) continue
            val t = abs(p); val c = if (p > 0) 1 else -1
            sc += c * (VAL[t] + pst(t, s, c, endgame))
            if (t == 3) { if (c > 0) wb++ else bb++ }
        }
        if (wb >= 2) sc += 30; if (bb >= 2) sc -= 30
        return sc * side
    }

    private val TT_SIZE = 1 shl 18
    private val ttKey = LongArray(TT_SIZE)
    private val ttVal = IntArray(TT_SIZE)  // оценка
    private val ttInfo = IntArray(TT_SIZE) // глубина | тип<<8
    private val ttMove = IntArray(TT_SIZE)
    private var nodes = 0L
    private var deadline = 0L
    private var stop = false
    private val killers = Array(64) { IntArray(2) }

    private fun order(moves: IntArray, ply: Int, ttm: Int): IntArray {
        val sc = IntArray(moves.size)
        for (i in moves.indices) {
            val m = moves[i]
            val cap = b[toSq(m)]
            sc[i] = when {
                m == ttm -> 1_000_000
                cap != 0 -> 100_000 + VAL[abs(cap)] * 10 - VAL[abs(b[from(m)])]
                promo(m) != 0 -> 90_000
                ply < 64 && (killers[ply][0] == m || killers[ply][1] == m) -> 80_000
                else -> 0
            }
        }
        return moves.indices.sortedByDescending { sc[it] }.map { moves[it] }.toIntArray()
    }

    private fun quiesce(alpha0: Int, beta: Int, ply: Int): Int {
        var alpha = alpha0
        nodes++
        val stand = evaluate()
        if (stand >= beta) return beta
        if (stand > alpha) alpha = stand
        if (ply > 40) return alpha
        for (m in order(pseudo(true), 63, 0)) {
            make(m)
            if (inCheck(-side)) { unmake(); continue }
            val v = -quiesce(-beta, -alpha, ply + 1)
            unmake()
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        return alpha
    }

    private fun search(depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if ((++nodes and 2047L) == 0L && System.currentTimeMillis() > deadline) stop = true
        if (stop) return 0
        if (ply > 0 && (half >= 100 || history.count { it == hash } >= 2)) return 0
        val check = inCheck()
        val d = if (check) depth + 1 else depth
        if (d <= 0) return quiesce(alpha0, beta, ply)
        var alpha = alpha0
        val slot = (hash and (TT_SIZE - 1).toLong()).toInt()
        var ttm = 0
        if (ttKey[slot] == hash) {
            ttm = ttMove[slot]
            val td = ttInfo[slot] and 255; val tt = ttInfo[slot] shr 8; val tv = ttVal[slot]
            if (ply > 0 && td >= d) {
                if (tt == 0) return tv
                if (tt == 1 && tv >= beta) return tv
                if (tt == 2 && tv <= alpha) return tv
            }
        }
        var best = -1_000_000; var bestM = 0; var legalN = 0
        for (m in order(pseudo(), ply, ttm)) {
            make(m)
            if (inCheck(-side)) { unmake(); continue }
            legalN++
            val v = -search(d - 1, -beta, -alpha, ply + 1)
            unmake()
            if (stop) return 0
            if (v > best) { best = v; bestM = m }
            if (v > alpha) alpha = v
            if (alpha >= beta) {
                if (b[toSq(m)] == 0 && ply < 64) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m }
                break
            }
        }
        if (legalN == 0) return if (check) -100_000 + ply else 0
        ttKey[slot] = hash; ttVal[slot] = best; ttMove[slot] = bestM
        ttInfo[slot] = d or ((if (best >= beta) 1 else if (best <= alpha0) 2 else 0) shl 8)
        return best
    }

    /**
     * Лучший ход за ограниченное время. level 1..5: глубина/время и «ошибки» на слабых уровнях.
     */
    fun bestMove(level: Int, capMs: Long = Long.MAX_VALUE): Int? {
        val moves = legal(); if (moves.isEmpty()) return null
        val (maxDepth, timeMs0, noise) = when (level) {
            1 -> Triple(1, 300L, 250)
            2 -> Triple(2, 500L, 120)
            3 -> Triple(3, 800L, 40)
            4 -> Triple(5, 1800L, 0)
            else -> Triple(64, 3500L, 0)
        }
        // на часах мало времени — думаем быстрее
        val timeMs = minOf(timeMs0, capMs.coerceAtLeast(120L))
        if (noise > 0) {
            // слабые уровни: оцениваем ходы мелко и добавляем случайность
            val scored = moves.map { m -> make(m); val v = -(if (maxDepth <= 1) quiesce(-1_000_000, 1_000_000, 0) else { deadline = System.currentTimeMillis() + timeMs; stop = false; search(maxDepth - 1, -1_000_000, 1_000_000, 1) }); unmake(); m to v + Random.nextInt(-noise, noise + 1) }
            return scored.maxByOrNull { it.second }?.first
        }
        deadline = System.currentTimeMillis() + timeMs; stop = false
        var best = moves.first()
        for (d in 1..maxDepth) {
            var alpha = -1_000_000; var bm = 0
            val slot = (hash and (TT_SIZE - 1).toLong()).toInt()
            val ttm = if (ttKey[slot] == hash) ttMove[slot] else best
            for (m in order(moves.toIntArray(), 0, ttm)) {
                make(m)
                val v = -search(d - 1, -1_000_000, -alpha, 1)
                unmake()
                if (stop) break
                if (v > alpha) { alpha = v; bm = m }
            }
            if (stop) break
            if (bm != 0) best = bm
            if (alpha > 90_000) break // нашли мат
            if (System.currentTimeMillis() > deadline - timeMs / 2) break
        }
        return best
    }

    // ----------- UCI (задачи, сохранение партий) -----------

    /** Ход в виде «e2e4», «e7e8q». */
    fun uci(m: Int): String = sqName(from(m)) + sqName(toSq(m)) + when (promo(m)) { 5 -> "q"; 4 -> "r"; 3 -> "b"; 2 -> "n"; else -> "" }
    /** Найти законный ход по «e2e4». */
    fun fromUci(u: String): Int? {
        if (u.length < 4) return null
        val f = (u[0] - 'a') + (u[1] - '1') * 8; val t = (u[2] - 'a') + (u[3] - '1') * 8
        val p = when (u.getOrNull(4)) { 'q' -> 5; 'r' -> 4; 'b' -> 3; 'n' -> 2; else -> 0 }
        return legal().firstOrNull { from(it) == f && toSq(it) == t && promo(it) == p }
    }
    /** Материал стороны c (без короля) — для «жертв» в анализе. */
    fun material(c: Int): Int { var m = 0; for (s in 0 until 64) { val p = b[s]; if (p != 0 && (p > 0) == (c > 0) && abs(p) != 6) m += VAL[abs(p)] }; return m }

    // ----------- Нотация -----------

    private val LETTER = arrayOf("", "", "К", "С", "Л", "Ф", "Кр")
    fun sqName(s: Int) = "${'a' + file(s)}${rank(s) + 1}"

    /** Ход в русской нотации (например, «Кf3», «exd5», «0-0», «e8=Ф+»). Вызывать ДО make. */
    fun san(m: Int): String {
        val f = from(m); val t = toSq(m); val p = abs(b[f])
        if (flags(m) and F_CASTLE != 0) return if (t > f) "0-0" else "0-0-0"
        val cap = b[t] != 0 || flags(m) and F_EP != 0
        val sb = StringBuilder()
        if (p == 1) { if (cap) sb.append('a' + file(f)) }
        else {
            sb.append(LETTER[p])
            val others = legal().filter { it != m && toSq(it) == t && abs(b[from(it)]) == p }
            if (others.isNotEmpty()) {
                if (others.none { file(from(it)) == file(f) }) sb.append('a' + file(f))
                else if (others.none { rank(from(it)) == rank(f) }) sb.append(rank(f) + 1)
                else sb.append(sqName(f))
            }
        }
        if (cap) sb.append('x')
        sb.append(sqName(t))
        if (promo(m) != 0) sb.append('=').append(LETTER[promo(m)])
        make(m)
        val chk = inCheck(); val mate = chk && legal().isEmpty()
        unmake()
        if (mate) sb.append('#') else if (chk) sb.append('+')
        return sb.toString()
    }
}
