package com.pank.vsevodnom.ui.games

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * «Лабиринт с шариком» — деревянная доска с лунками, стальной шарик, управление наклоном телефона.
 * Логика (генератор уровней и физика) — обычные классы без Compose: их проверяет TiltMazeTest.
 * Единица длины — клетка лабиринта (1×1); стены стоят на линиях сетки, толщина 2·WALL_H.
 */

/** Стена (прямоугольник) в клетках поля. */
data class TmRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    /** Расстояние от точки до прямоугольника (0 — внутри). */
    fun dist(x: Float, y: Float): Float {
        val dx = max(max(l - x, 0f), x - r); val dy = max(max(t - y, 0f), y - b)
        return sqrt(dx * dx + dy * dy)
    }
}

/** Лунка: центр в клетках, клетка лабиринта, в которой она стоит. */
data class TmHole(val x: Float, val y: Float, val cell: Int)

/**
 * Уровень: лабиринт «рекурсивным возвратом» по сиду, стены — слитые полосы, лунки — у стен
 * (касаются стены своей клетки), чаще всего на пути к финишу; на поворотах — во внешнем углу.
 * Проходимость гарантирована построением: между лунками и между лункой и противоположной стеной
 * всегда остаётся проход не меньше [margin] диаметров шарика.
 */
class TiltLevel private constructor(
    /** Номер уровня (0 — случайный). */
    val number: Int,
    val seed: Long,
    val cols: Int,
    val rows: Int,
    /** Сложность 0…1. */
    val difficulty: Float,
    val ballR: Float,
    val holeR: Float,
    /** Ширина прохода мимо лунки, в диаметрах шарика. */
    val margin: Float,
    private val wallE: BooleanArray,
    private val wallS: BooleanArray,
    val start: Int,
    val finish: Int,
    /** Путь от старта к финишу (клетки). */
    val path: List<Int>,
    val walls: List<TmRect>,
    val holes: List<TmHole>,
) {
    companion object {
        const val LEVELS = 100
        /** Половина толщины стены. */
        const val WALL_H = 0.06f
        /** Лунка больше шарика во столько раз. */
        const val HOLE_K = 1.3f
        /** Шарик падает, когда центр ближе CAPTURE·радиус лунки к её центру. */
        const val CAPTURE = 0.75f
        /** Сложность «случайного» режима. */
        const val RANDOM_LEVEL = 80

        fun seedOf(n: Int): Long = 0x5EED_7117L + n * 7919L

        internal fun openOf(wallE: BooleanArray, wallS: BooleanArray, cols: Int, rows: Int, c: Int, d: Int): Boolean {
            val x = c % cols; val y = c / cols
            return when (d) {
                0 -> y > 0 && !wallS[c - cols]
                1 -> x < cols - 1 && !wallE[c]
                2 -> y < rows - 1 && !wallS[c]
                else -> x > 0 && !wallE[c - 1]
            }
        }

        /**
         * Уровень [n] (1…100). [seed] — по умолчанию из номера (детерминированно), [level] — по какому
         * уровню брать сложность (для случайного режима).
         */
        fun generate(n: Int, seed: Long = seedOf(n), level: Int = n): TiltLevel {
            val lvl = level.coerceIn(1, LEVELS)
            val t = (lvl - 1f) / (LEVELS - 1f)
            val rnd = Random(seed)
            val cols = 5 + (lvl - 1) * 4 / LEVELS            // 5…8
            val rows = (cols * 1.65f).roundToInt()            // 8…13
            val m = 3.2f - t                                   // проход мимо лунки: 3,2 → 2,2 диаметра
            val free = 1f - 2 * WALL_H
            val r = free / (2f * (HOLE_K + m))                 // так, чтобы лунка у стены + проход = клетка
            val R = HOLE_K * r
            val total = cols * rows
            val wallE = BooleanArray(total) { true }
            val wallS = BooleanArray(total) { true }
            fun open(c: Int, d: Int) = openOf(wallE, wallS, cols, rows, c, d)
            fun nb(c: Int, d: Int) = when (d) { 0 -> c - cols; 1 -> c + 1; 2 -> c + cols; else -> c - 1 }

            // лабиринт: рекурсивный возврат (стек вместо рекурсии)
            val start = rnd.nextInt(cols)
            val seen = BooleanArray(total); val stack = IntArray(total); var sp = 0
            seen[start] = true; stack[sp++] = start
            val cand = IntArray(4)
            while (sp > 0) {
                val c = stack[sp - 1]; val cx = c % cols; val cy = c / cols
                var k = 0
                if (cy > 0 && !seen[c - cols]) cand[k++] = 0
                if (cx < cols - 1 && !seen[c + 1]) cand[k++] = 1
                if (cy < rows - 1 && !seen[c + cols]) cand[k++] = 2
                if (cx > 0 && !seen[c - 1]) cand[k++] = 3
                if (k == 0) { sp--; continue }
                val d = cand[rnd.nextInt(k)]
                val n2 = nb(c, d)
                when (d) { 0 -> wallS[n2] = false; 1 -> wallE[c] = false; 2 -> wallS[c] = false; else -> wallE[n2] = false }
                seen[n2] = true; stack[sp++] = n2
            }

            // финиш — самая дальняя по лабиринту клетка в нижней части
            val dist = IntArray(total) { -1 }; val par = IntArray(total) { -1 }
            val q = IntArray(total); var qh = 0; var qt = 0
            dist[start] = 0; q[qt++] = start
            while (qh < qt) {
                val c = q[qh++]
                for (d in 0..3) if (open(c, d)) { val o = nb(c, d); if (dist[o] < 0) { dist[o] = dist[c] + 1; par[o] = c; q[qt++] = o } }
            }
            val low = (rows * 0.6f).toInt()
            var finish = -1
            for (c in 0 until total) if (c / cols >= low && (finish < 0 || dist[c] > dist[finish])) finish = c
            val path = ArrayList<Int>()
            run { var c = finish; while (c >= 0) { path += c; c = par[c] } }
            path.reverse()

            // стены: слитые полосы по линиям сетки
            val walls = ArrayList<TmRect>()
            for (j in 0..rows) {
                var i = 0
                while (i < cols) {
                    val has = { x: Int -> j == 0 || j == rows || wallS[(j - 1) * cols + x] }
                    if (!has(i)) { i++; continue }
                    val i0 = i; while (i < cols && has(i)) i++
                    walls += TmRect(i0 - WALL_H, j - WALL_H, i + WALL_H, j + WALL_H)
                }
            }
            for (i in 0..cols) {
                var j = 0
                while (j < rows) {
                    val has = { y: Int -> i == 0 || i == cols || wallE[y * cols + i - 1] }
                    if (!has(j)) { j++; continue }
                    val j0 = j; while (j < rows && has(j)) j++
                    walls += TmRect(i - WALL_H, j0 - WALL_H, i + WALL_H, j + WALL_H)
                }
            }

            // лунки
            val holes = ArrayList<TmHole>()
            val sx = start % cols + 0.5f; val sy = start / cols + 0.5f
            val fx = finish % cols + 0.5f; val fy = finish / cols + 0.5f
            val minGap = free - 1e-4f                        // = 2R + 2·m·2r/2: центр-центр двух лунок
            fun hyp(a: Float, b: Float) = sqrt(a * a + b * b)
            fun fits(x: Float, y: Float): Boolean {
                if (hyp(x - fx, y - fy) < minGap + 0.05f) return false
                if (hyp(x - sx, y - sy) < R + 2 * m * r + r) return false
                return holes.none { hyp(it.x - x, it.y - y) < minGap }
            }
            val lo = WALL_H + R; val hi = 1f - WALL_H - R
            fun tryPlace(c: Int, corner: Pair<Int, Int>?): Boolean {
                val wallSides = (0..3).filter { !open(c, it) }
                if (wallSides.isEmpty()) return false
                val cx = c % cols; val cy = c / cols
                for (attempt in 0 until 6) {
                    var u: Float; var v: Float
                    if (corner != null && attempt < 2 && !open(c, corner.first) && !open(c, corner.second)) {
                        val set = setOf(corner.first, corner.second)
                        u = if (1 in set) hi else lo
                        v = if (0 in set) lo else hi
                    } else {
                        val d = wallSides[rnd.nextInt(wallSides.size)]
                        val along = lo + rnd.nextFloat() * (hi - lo)
                        when (d) { 0 -> { u = along; v = lo }; 1 -> { u = hi; v = along }; 2 -> { u = along; v = hi }; else -> { u = lo; v = along } }
                    }
                    val x = cx + u; val y = cy + v
                    if (fits(x, y)) { holes += TmHole(x, y, c); return true }
                }
                return false
            }
            fun dirOf(a: Int, b: Int) = when (b - a) { -cols -> 0; 1 -> 1; cols -> 2; else -> 3 }
            val pPath = 0.38f + 0.5f * t
            val pOff = 0.2f + 0.3f * t
            for (i in 2 until path.size - 1) {
                if (rnd.nextFloat() >= pPath) continue
                val din = dirOf(path[i - 1], path[i]); val dout = dirOf(path[i], path[i + 1])
                // на повороте — внешний угол (туда шарик и летит по инерции)
                tryPlace(path[i], if (din != dout) din to (dout + 2) % 4 else null)
            }
            val onPath = path.toHashSet()
            val others = (0 until total).filter { it !in onPath }.shuffled(rnd)
            for (c in others) if (rnd.nextFloat() < pOff) tryPlace(c, null)
            if (holes.size < 3) for (c in path.drop(2)) { if (holes.size >= 3) break; tryPlace(c, null) }

            return TiltLevel(n, seed, cols, rows, t, r, R, m, wallE, wallS, start, finish, path, walls, holes)
        }
    }

    /** Разгон: клеток/с² на 1 м/с² наклона. */
    val accelK = 3.0f + 1.3f * difficulty
    /** Сопротивление качению (клеток/с²) и вязкое (1/с). */
    val friction = 0.42f - 0.14f * difficulty
    val damping = 0.32f - 0.1f * difficulty

    val startX get() = start % cols + 0.5f
    val startY get() = start / cols + 0.5f
    val finishX get() = finish % cols + 0.5f
    val finishY get() = finish / cols + 0.5f

    fun open(c: Int, d: Int) = openOf(wallE, wallS, cols, rows, c, d)

    fun cellAt(x: Float, y: Float): Int {
        val cx = x.toInt().coerceIn(0, cols - 1); val cy = y.toInt().coerceIn(0, rows - 1)
        return cy * cols + cx
    }

    /** Для каждой клетки: стены рядом (клетка, расширенная на 0,6). */
    val nearWalls: Array<IntArray> = Array(cols * rows) { c ->
        val l = c % cols - 0.6f; val t = c / cols - 0.6f; val r = l + 2.2f; val b = t + 2.2f
        walls.indices.filter { val w = walls[it]; w.r >= l && w.l <= r && w.b >= t && w.t <= b }.toIntArray()
    }

    /** Для каждой клетки: лунки, до которых шарик из неё может дотянуться. */
    val nearHoles: Array<IntArray> = Array(cols * rows) { c ->
        val e = holeR + 0.6f
        val l = c % cols - e; val t = c / cols - e; val r = c % cols + 1 + e; val b = c / cols + 1 + e
        holes.indices.filter { val h = holes[it]; h.x in l..r && h.y in t..b }.toIntArray()
    }

    /** Расстояние от точки до ближайшей стены. */
    fun wallDist(x: Float, y: Float): Float {
        var best = 9f
        for (i in nearWalls[cellAt(x, y)]) { val d = walls[i].dist(x, y); if (d < best) best = d }
        return best
    }

    /**
     * Поиск в ширину по клеткам от старта к финишу; клетка с лункой непроходима, если мимо лунки
     * не остаётся прохода в [margin] диаметров.
     */
    fun gridSolvable(): Boolean {
        val need = 2 * margin * ballR - 1e-4f
        val blocked = BooleanArray(cols * rows)
        for (h in holes) {
            val u = h.x - h.cell % cols; val v = h.y - h.cell / cols
            val gaps = floatArrayOf(u - WALL_H - holeR, 1 - WALL_H - u - holeR, v - WALL_H - holeR, 1 - WALL_H - v - holeR)
            if (gaps.max() < need) blocked[h.cell] = true
        }
        if (blocked[start] || blocked[finish]) return false
        val seen = BooleanArray(cols * rows); val q = ArrayDeque<Int>()
        seen[start] = true; q += start
        while (q.isNotEmpty()) {
            val c = q.removeFirst(); if (c == finish) return true
            for (d in 0..3) if (open(c, d)) {
                val o = when (d) { 0 -> c - cols; 1 -> c + 1; 2 -> c + cols; else -> c - 1 }
                if (!seen[o] && !blocked[o]) { seen[o] = true; q += o }
            }
        }
        return false
    }

    /**
     * Проверка «по-настоящему»: сетка точек с шагом [step], точка свободна, если центр шарика там не
     * касается стен и не попадает в зону затягивания лунки — с запасом 0,6 радиуса шарика с каждой стороны.
     * Ищем путь от старта до финиша.
     */
    fun freeSpaceSolvable(step: Float = 0.04f): Boolean {
        val s = 0.6f * ballR
        val nx = (cols / step).toInt(); val ny = (rows / step).toInt()
        fun free(i: Int, j: Int): Boolean {
            val x = (i + 0.5f) * step; val y = (j + 0.5f) * step
            if (wallDist(x, y) < ballR + s) return false
            for (k in nearHoles[cellAt(x, y)]) { val h = holes[k]; val dx = h.x - x; val dy = h.y - y; if (dx * dx + dy * dy < (holeR + s) * (holeR + s)) return false }
            return true
        }
        val si = (startX / step).toInt(); val sj = (startY / step).toInt()
        val fi = (finishX / step).toInt(); val fj = (finishY / step).toInt()
        if (!free(si, sj) || !free(fi, fj)) return false
        val seen = BooleanArray(nx * ny); val q = IntArray(nx * ny); var qh = 0; var qt = 0
        seen[sj * nx + si] = true; q[qt++] = sj * nx + si
        while (qh < qt) {
            val p = q[qh++]; val i = p % nx; val j = p / nx
            if (i == fi && j == fj) return true
            if (i > 0 && !seen[p - 1] && free(i - 1, j)) { seen[p - 1] = true; q[qt++] = p - 1 }
            if (i < nx - 1 && !seen[p + 1] && free(i + 1, j)) { seen[p + 1] = true; q[qt++] = p + 1 }
            if (j > 0 && !seen[p - nx] && free(i, j - 1)) { seen[p - nx] = true; q[qt++] = p - nx }
            if (j < ny - 1 && !seen[p + nx] && free(i, j + 1)) { seen[p + nx] = true; q[qt++] = p + nx }
        }
        return false
    }
}

/**
 * Физика шарика: ускорение от наклона, сопротивление качению, удары о стены (окружность против
 * прямоугольников, с потерей скорости), затягивание в лунку и падение. Шаг дробится на подшаги по
 * 1/480 с — быстрый шарик не проскакивает тонкую стену. Ориентация шарика (матрица 3×3) крутится по
 * пройденному пути — по ней рисуются метки на поверхности.
 */
class TiltBall(val lv: TiltLevel) {
    companion object {
        const val EV_FALL = 1; const val EV_WIN = 2; const val EV_RESPAWN = 3; const val EV_DONE = 4
        const val FALL_T = 0.4f
        const val WIN_T = 0.55f
        const val SUB = 1f / 480f
        const val MAX_V = 10f
        const val BOUNCE = 0.42f
        /** Мёртвая зона наклона, м/с². */
        const val DEAD = 0.06f
        const val PULL = 26f
        const val PULL0 = 5f
    }
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    /** Поворот шарика: столбцы — оси тела в осях экрана (x вправо, y вниз, z — в доску). */
    val rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    /** Время падения (−1 — катится). */
    var fallT = -1f; private set
    /** Куда падает: индекс лунки, −2 — финиш. */
    var target = -1; private set
    var targetX = 0f; private set
    var targetY = 0f; private set
    private var fx0 = 0f; private var fy0 = 0f
    var scale = 1f; private set
    var dark = 0f; private set
    /** Самый сильный удар о стену за последний шаг (клеток/с, по нормали). */
    var impact = 0f; private set
    var done = false; private set
    private var rolls = 0
    val falling get() = fallT >= 0f
    val fallProgress get() = if (fallT < 0f) 0f else (fallT / (if (target == -2) WIN_T else FALL_T)).coerceIn(0f, 1f)

    init { reset() }

    fun reset() {
        x = lv.startX; y = lv.startY; vx = 0f; vy = 0f
        fallT = -1f; target = -1; scale = 1f; dark = 0f; done = false; impact = 0f
    }

    /**
     * Шаг [dt] с; [gx], [gy] — наклон в осях экрана (м/с², вправо и вниз).
     * Возвращает событие EV_* или 0.
     */
    fun step(dt: Float, gx: Float, gy: Float): Int {
        impact = 0f
        if (done) return 0
        if (fallT >= 0f) {
            fallT += dt
            val k = fallProgress
            val e = 1f - (1f - k) * (1f - k)
            val nx = fx0 + (targetX - fx0) * e; val ny = fy0 + (targetY - fy0) * e
            roll(nx - x, ny - y); x = nx; y = ny
            if (target == -2) { scale = 1f - 0.3f * k; dark = 0.55f * k } else { scale = 1f - 0.6f * k; dark = k }
            if (k >= 1f) {
                return if (target == -2) { done = true; EV_DONE } else { reset(); EV_RESPAWN }
            }
            return 0
        }
        var ax0 = gx; var ay0 = gy
        val gm = sqrt(gx * gx + gy * gy)
        if (gm < DEAD) { ax0 = 0f; ay0 = 0f } else { val f = (gm - DEAD) / gm; ax0 *= f; ay0 *= f }
        ax0 *= lv.accelK; ay0 *= lv.accelK
        val R = lv.holeR; val capR = R * TiltLevel.CAPTURE
        var left = dt
        while (left > 1e-6f) {
            val h = min(SUB, left); left -= h
            var ax = ax0; var ay = ay0
            var cap = -1
            for (i in lv.nearHoles[lv.cellAt(x, y)]) {
                val o = lv.holes[i]; val dx = o.x - x; val dy = o.y - y; val d = sqrt(dx * dx + dy * dy)
                if (d < R) {
                    if (d < capR) { cap = i; break }
                    val f = PULL0 + PULL * (1f - d / R); ax += dx / d * f; ay += dy / d * f
                }
            }
            if (cap == -1) {
                val dx = lv.finishX - x; val dy = lv.finishY - y; val d = sqrt(dx * dx + dy * dy)
                if (d < R) { if (d < capR) cap = -2 else { val f = PULL0 + PULL * (1f - d / R); ax += dx / d * f; ay += dy / d * f } }
            }
            if (cap != -1) {
                target = cap; fallT = 0f; fx0 = x; fy0 = y
                if (cap == -2) { targetX = lv.finishX; targetY = lv.finishY } else { targetX = lv.holes[cap].x; targetY = lv.holes[cap].y }
                vx = 0f; vy = 0f
                return if (cap == -2) EV_WIN else EV_FALL
            }
            vx += ax * h; vy += ay * h
            val sp = sqrt(vx * vx + vy * vy)
            if (sp > 0f) {
                val ns = (sp - (lv.friction + lv.damping * sp) * h).coerceIn(0f, MAX_V)
                vx = vx / sp * ns; vy = vy / sp * ns
            }
            val ox = x; val oy = y
            x += vx * h; y += vy * h
            collide()
            roll(x - ox, y - oy)
        }
        return 0
    }

    private fun collide() {
        val r = lv.ballR
        for (pass in 0 until 2) {
            for (i in lv.nearWalls[lv.cellAt(x, y)]) {
                val w = lv.walls[i]
                val qx = x.coerceIn(w.l, w.r); val qy = y.coerceIn(w.t, w.b)
                val dx = x - qx; val dy = y - qy; val d2 = dx * dx + dy * dy
                if (d2 >= r * r) continue
                val nx: Float; val ny: Float; val pen: Float
                if (d2 > 1e-10f) {
                    val d = sqrt(d2); nx = dx / d; ny = dy / d; pen = r - d
                } else {
                    // центр внутри стены — выталкиваем по кратчайшей оси
                    val dl = x - w.l; val dr = w.r - x; val dt = y - w.t; val db = w.b - y
                    val mn = minOf(minOf(dl, dr), minOf(dt, db))
                    when (mn) { dl -> { nx = -1f; ny = 0f }; dr -> { nx = 1f; ny = 0f }; dt -> { nx = 0f; ny = -1f }; else -> { nx = 0f; ny = 1f } }
                    pen = mn + r
                }
                x += nx * pen; y += ny * pen
                val vn = vx * nx + vy * ny
                if (vn < 0f) {
                    if (-vn > impact) impact = -vn
                    vx -= (1f + BOUNCE) * vn * nx; vy -= (1f + BOUNCE) * vn * ny
                    val tx = -ny; val ty = nx; val vt = vx * tx + vy * ty
                    vx -= vt * 0.03f * tx; vy -= vt * 0.03f * ty
                }
            }
        }
    }

    /** Качение без проскальзывания: поворот на путь/радиус вокруг оси (dy, −dx, 0). */
    private fun roll(dx: Float, dy: Float) {
        val d = sqrt(dx * dx + dy * dy); if (d < 1e-7f) return
        val ang = d / lv.ballR
        val ux = dy / d; val uy = -dx / d
        val c = cos(ang); val s = sin(ang); val cc = 1f - c
        val m00 = c + ux * ux * cc; val m01 = ux * uy * cc; val m02 = uy * s
        val m10 = ux * uy * cc; val m11 = c + uy * uy * cc; val m12 = -ux * s
        val m20 = -uy * s; val m21 = ux * s; val m22 = c
        val a = rot.copyOf()
        for (j in 0..2) {
            val b0 = a[j]; val b1 = a[3 + j]; val b2 = a[6 + j]
            rot[j] = m00 * b0 + m01 * b1 + m02 * b2
            rot[3 + j] = m10 * b0 + m11 * b1 + m12 * b2
            rot[6 + j] = m20 * b0 + m21 * b1 + m22 * b2
        }
        if (++rolls % 64 == 0) orthonormalize()
    }

    private fun orthonormalize() {
        // столбцы 0 и 1 — Грам–Шмидт, третий — их векторное произведение
        var ax = rot[0]; var ay = rot[3]; var az = rot[6]
        var n = sqrt(ax * ax + ay * ay + az * az); ax /= n; ay /= n; az /= n
        var bx = rot[1]; var by = rot[4]; var bz = rot[7]
        val dp = ax * bx + ay * by + az * bz; bx -= dp * ax; by -= dp * ay; bz -= dp * az
        n = sqrt(bx * bx + by * by + bz * bz); bx /= n; by /= n; bz /= n
        rot[0] = ax; rot[3] = ay; rot[6] = az; rot[1] = bx; rot[4] = by; rot[7] = bz
        rot[2] = ay * bz - az * by; rot[5] = az * bx - ax * bz; rot[8] = ax * by - ay * bx
    }
}

// ───────────────────────────── экран ─────────────────────────────

@Composable
fun TiltMazeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("tiltmaze", Context.MODE_PRIVATE) }
    var unlocked by remember { mutableIntStateOf(prefs.getInt("unlocked", 1).coerceIn(1, TiltLevel.LEVELS)) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    /** Играемый уровень: null — меню, 0 — случайный. */
    var playing by remember { mutableStateOf<Int?>(null) }
    var seed by remember { mutableLongStateOf(0L) }
    var menuTick by remember { mutableIntStateOf(0) }
    val toggleSound = { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }

    val p = playing
    if (p != null) {
        BackHandler { playing = null; menuTick++ }
        TiltPlay(
            no = p, seed = seed, prefs = prefs, sound = sound, onSound = toggleSound,
            onExit = { playing = null; menuTick++ },
            onWin = { if (p in 1 until TiltLevel.LEVELS && p + 1 > unlocked) { unlocked = p + 1; prefs.edit().putInt("unlocked", unlocked).apply() } },
            onNext = {
                when {
                    p == 0 -> seed = Random.nextLong()
                    p < TiltLevel.LEVELS -> { playing = p + 1; seed = TiltLevel.seedOf(p + 1) }
                    else -> { playing = null; menuTick++ }
                }
            },
        )
        return
    }

    ScreenScaffold("Лабиринт с шариком", onBack, actions = {
        RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук", toggleSound)
    }) {
        @Suppress("UNUSED_EXPRESSION") menuTick
        val cal = prefs.getFloat("cal_x", 0f) != 0f || prefs.getFloat("cal_y", 0f) != 0f
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Наклоняй телефон и доведи стальной шарик до финиша, не уронив в лунку. Пройдено ${unlocked - 1} из ${TiltLevel.LEVELS}.",
                style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Играть: уровень $unlocked", { seed = TiltLevel.seedOf(unlocked); playing = unlocked }, Modifier.weight(1f), bg = Color(0xFF8A5428))
                PillButton("Случайный", { seed = Random.nextLong(); playing = 0 }, bg = Color(0xFF5A3A22))
            }
            if (cal) Text("Калибровка задана · сбросить", style = MaterialTheme.typography.labelLarge, color = Pal.PeachInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { prefs.edit().remove("cal_x").remove("cal_y").apply(); menuTick++ }.padding(vertical = 4.dp))
        }
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..TiltLevel.LEVELS).toList()) { n ->
                val locked = n > unlocked
                val best = prefs.getLong("best_$n", 0L)
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                    .background(when { locked -> Pal.Line; best > 0 -> Color(0xFFE2B47A); else -> Color(0xFFF6DDB6) })
                    .clickable(enabled = !locked) { seed = TiltLevel.seedOf(n); playing = n },
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (locked) Pal.Muted.copy(alpha = 0.5f) else Color(0xFF4A2810))
                        when {
                            locked -> Icon(Icons.Rounded.Lock, null, tint = Pal.Muted.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                            best > 0 -> Text(tmFmt(best / 1000f), fontSize = 10.sp, color = Color(0xFF6B3A1C))
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.tmActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.tmActivity()
    else -> null
}

private fun tmFmt(sec: Float): String {
    val t = (sec * 10).toInt()
    return "${t / 600}:${((t / 10) % 60).toString().padStart(2, '0')}.${t % 10}"
}

@Composable
private fun TiltPlay(
    no: Int, seed: Long, prefs: SharedPreferences, sound: Boolean,
    onSound: () -> Unit, onExit: () -> Unit, onWin: () -> Unit, onNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val lv = remember(no, seed) { if (no == 0) TiltLevel.generate(0, seed, TiltLevel.RANDOM_LEVEL) else TiltLevel.generate(no, seed) }
    val ball = remember(lv) { TiltBall(lv) }
    var started by remember(lv) { mutableStateOf(false) }
    var won by remember(lv) { mutableStateOf(false) }
    var time by remember(lv) { mutableFloatStateOf(0f) }
    var attempts by remember(lv) { mutableIntStateOf(1) }
    var frame by remember { mutableIntStateOf(0) }
    var newBest by remember(lv) { mutableStateOf(false) }
    var best by remember(lv) { mutableLongStateOf(if (no > 0) prefs.getLong("best_$no", 0L) else 0L) }
    var calX by remember { mutableFloatStateOf(prefs.getFloat("cal_x", 0f)) }
    var calY by remember { mutableFloatStateOf(prefs.getFloat("cal_y", 0f)) }
    val raw = remember { FloatArray(2) }
    var hasSensor by remember { mutableStateOf(true) }
    var lastClack by remember { mutableLongStateOf(0L) }
    var clackKey by remember { mutableIntStateOf(0) }

    // экран не гаснет, ориентация — портрет
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val act = ctx.tmActivity()
        val prev = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { view.keepScreenOn = false; if (act != null && prev != null) act.requestedOrientation = prev }
    }

    // датчик наклона: гравитация (или акселерометр с фильтром), только пока экран на переднем плане
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasSensor = sensor != null
        val isAcc = sensor?.type == Sensor.TYPE_ACCELEROMETER
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val (sx, sy) = when (rotation) {
                    Surface.ROTATION_90 -> -e.values[1] to e.values[0]
                    Surface.ROTATION_180 -> -e.values[0] to -e.values[1]
                    Surface.ROTATION_270 -> e.values[1] to -e.values[0]
                    else -> e.values[0] to e.values[1]
                }
                if (isAcc) { raw[0] += (sx - raw[0]) * 0.2f; raw[1] += (sy - raw[1]) * 0.2f } else { raw[0] = sx; raw[1] = sy }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        var on = false
        fun reg() { if (!on && sensor != null && sm != null) { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME); on = true } }
        fun unreg() { if (on) { sm?.unregisterListener(listener); on = false } }
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) reg() else if (ev == Lifecycle.Event.ON_PAUSE) unreg() }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) reg()
        onDispose { owner.lifecycle.removeObserver(obs); unreg() }
    }

    GameLoop(started && !won) { dt ->
        // показания: x вправо-вверх → шарик катится влево; y (верх телефона поднят) → шарик вниз
        val gx = -(raw[0] - calX); val gy = raw[1] - calY
        val ev = ball.step(dt, gx, gy)
        if (!ball.done) time += dt
        val now = System.nanoTime()
        if (ball.impact > 0.5f && now - lastClack > 45_000_000L) {
            lastClack = now
            if (sound) TiltSfx.clack(ball.impact, clackKey++)
        }
        when (ev) {
            TiltBall.EV_FALL -> { if (sound) TiltSfx.drop(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            TiltBall.EV_WIN -> if (sound) TiltSfx.win()
            TiltBall.EV_RESPAWN -> attempts++
            TiltBall.EV_DONE -> {
                won = true
                val ms = (time * 1000).toLong()
                if (no > 0) {
                    if (best == 0L || ms < best) { newBest = true; best = ms; prefs.edit().putLong("best_$no", ms).apply() } else newBest = false
                    onWin()
                }
            }
        }
        frame++
    }

    val woodInk = Color(0xFFFFE2B8)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2416), Color(0xFF22140A))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "К уровням", onExit, bg = Color(0x33FFFFFF), tint = Color.White)
                Column(Modifier.weight(1f)) {
                    Text(if (no == 0) "Случайный" else "Уровень $no", color = woodInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("лунок ${lv.holes.size}" + if (best > 0) " · рекорд ${tmFmt(best / 1000f)}" else "", color = woodInk.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук", onSound, bg = Color(0x33FFFFFF), tint = Color.White)
                RoundIconButton(Icons.Rounded.Refresh, "Заново", {
                    ball.reset(); time = 0f; attempts = 1; won = false; started = true; frame++
                }, bg = Color(0x33FFFFFF), tint = Color.White)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tmFmt(time), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 92.dp))
                Text("попытка $attempts", color = woodInk.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("Калибровка", color = woodInk, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)).clickable {
                        calX = raw[0]; calY = raw[1]
                        prefs.edit().putFloat("cal_x", calX).putFloat("cal_y", calY).apply()
                        android.widget.Toast.makeText(ctx, "Готово: этот наклон теперь «ровно»", android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            var box by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.weight(1f).fillMaxWidth().padding(6.dp).onSizeChanged { box = it }) {
                val board = remember(lv, box) { if (box.width > 0 && box.height > 0) renderTiltBoard(lv, box.width, box.height, density) else null }
                Canvas(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION") frame
                    if (board != null) drawImage(board)
                    val g = tiltGeom(lv, size.width, size.height)
                    drawTiltBall(lv, ball, g)
                }
                when {
                    !hasSensor -> GameOverlay("Нет датчика", "На телефоне не найден датчик наклона — играть нельзя.", "Назад", onExit)
                    won -> VictoryOverlay(
                        if (no == 0) "Пройдено!" else "Уровень $no пройден!",
                        "Время ${tmFmt(time)} · попыток $attempts" + (if (no > 0) if (newBest) "\nНовый рекорд!" else "\nРекорд ${tmFmt(best / 1000f)}" else ""),
                        when { no == 0 -> "Новый лабиринт"; no < TiltLevel.LEVELS -> "Дальше"; else -> "К уровням" },
                    ) { onNext() }
                    !started -> GameOverlay(
                        if (no == 0) "Случайный лабиринт" else "Уровень $no",
                        "Наклоняй телефон — шарик катится. Доведи его от «старта» до «финиша», не уронив в лунку.\n" +
                            "Держи телефон, как удобно, и нажми «Калибровка» — этот наклон станет «ровным».",
                        "Старт",
                    ) { ball.reset(); time = 0f; attempts = 1; started = true }
                }
            }
        }
    }
}

// ───────────────────────────── рисование ─────────────────────────────

/** Размещение поля на экране: размер клетки и левый верхний угол (в пикселях). */
private class TmGeom(val s: Float, val ox: Float, val oy: Float) {
    fun px(x: Float) = ox + x * s
    fun py(y: Float) = oy + y * s
}

private const val FRAME = 0.32f

private fun tiltGeom(lv: TiltLevel, w: Float, h: Float): TmGeom {
    val s = min(w / (lv.cols + 2 * FRAME), h / (lv.rows + 2 * FRAME))
    return TmGeom(s, (w - lv.cols * s) / 2f, (h - lv.rows * s) / 2f)
}

/** Неподвижная часть (доска, лунки, стены) рисуется один раз в картинку. */
private fun renderTiltBoard(lv: TiltLevel, w: Int, h: Int, density: Density): ImageBitmap {
    val bmp = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
        drawTiltBoard(lv, tiltGeom(lv, w.toFloat(), h.toFloat()))
    }
    return bmp
}

/**
 * Волокна дерева: широкие полосы оттенка, волнистые линии вдоль волокон, огибающие сучки.
 * [horizontal] — волокна вдоль x.
 */
private fun DrawScope.woodGrain(l: Float, t: Float, w: Float, h: Float, rnd: Random, step: Float, horizontal: Boolean, ink: Color, knots: Int) {
    val len = if (horizontal) w else h
    val across = if (horizontal) h else w
    fun pt(u: Float, v: Float) = if (horizontal) Offset(l + u, t + v) else Offset(l + v, t + u)
    var v = 0f
    while (v < across) {
        val bw = step * (2f + rnd.nextFloat() * 6f)
        val c = if (rnd.nextBoolean()) ink.copy(alpha = 0.04f + rnd.nextFloat() * 0.06f) else Color.White.copy(alpha = 0.03f + rnd.nextFloat() * 0.05f)
        drawRect(c, pt(0f, v), if (horizontal) Size(w, bw) else Size(bw, h))
        v += bw * (0.8f + rnd.nextFloat())
    }
    val ks = List(knots) { Triple(rnd.nextFloat() * len, rnd.nextFloat() * across, step * (2.2f + rnd.nextFloat() * 3f)) }
    val seg = max(5f, len / 90f)
    v = -step * 2
    val path = Path()
    while (v < across + step * 2) {
        path.reset()
        val amp = step * (0.3f + rnd.nextFloat() * 0.8f); val wl = len * (0.3f + rnd.nextFloat() * 0.9f); val ph = rnd.nextFloat() * 6.283f
        val amp2 = step * 0.2f; val wl2 = len * 0.07f * (0.5f + rnd.nextFloat())
        var u = -seg; var first = true
        while (u <= len + seg) {
            var dv = amp * sin(u / wl * 6.283f + ph) + amp2 * sin(u / wl2 * 6.283f + ph * 2f)
            for ((ku, kv, kr) in ks) {
                val du = (u - ku) / (kr * 2.4f); val dvv = (v - kv) / (kr * 1.8f)
                dv += sign(v - kv) * kr * 1.1f * exp(-du * du - dvv * dvv * 0.6f)
            }
            val o = pt(u, v + dv)
            if (first) { path.moveTo(o.x, o.y); first = false } else path.lineTo(o.x, o.y)
            u += seg
        }
        drawPath(path, ink.copy(alpha = 0.06f + rnd.nextFloat() * 0.2f), style = Stroke(width = step * (0.1f + rnd.nextFloat() * 0.3f)))
        v += step * (0.45f + rnd.nextFloat() * 1.1f)
    }
    for ((ku, kv, kr) in ks) {
        val c = pt(ku, kv)
        for (i in 4 downTo 0) {
            val a = kr * (0.35f + i * 0.28f); val b = kr * (0.22f + i * 0.17f)
            val sz = if (horizontal) Size(a * 2, b * 2) else Size(b * 2, a * 2)
            drawOval(ink.copy(alpha = 0.10f + (4 - i) * 0.035f), c - Offset(sz.width / 2, sz.height / 2), sz, style = Stroke(step * 0.22f))
        }
        val sz = if (horizontal) Size(kr * 0.6f, kr * 0.4f) else Size(kr * 0.4f, kr * 0.6f)
        drawOval(ink.copy(alpha = 0.45f), c - Offset(sz.width / 2, sz.height / 2), sz)
    }
}

private fun DrawScope.drawTiltBoard(lv: TiltLevel, g: TmGeom) {
    val s = g.s; val bw = lv.cols * s; val bh = lv.rows * s
    val rnd = Random(lv.seed xor 0x77L)
    val fr = FRAME * s
    val fl = g.ox - fr; val ft = g.oy - fr; val fw = bw + 2 * fr; val fh = bh + 2 * fr
    // рамка из тёмного дерева с тенью
    drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(fl + fr * 0.12f, ft + fr * 0.22f), Size(fw, fh), CornerRadius(fr * 0.6f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF7E4824), Color(0xFF5A2F14), Color(0xFF6E3C1C)), Offset(fl, ft), Offset(fl + fw, ft + fh)),
        Offset(fl, ft), Size(fw, fh), CornerRadius(fr * 0.6f))
    clipRect(fl, ft, fl + fw, ft + fh) { woodGrain(fl, ft, fw, fh, rnd, s * 0.06f, false, Color(0xFF2A1406), 2) }
    drawRoundRect(Color(0x40FFD9A8), Offset(fl + 1.5f, ft + 1.5f), Size(fw - 3f, fh - 3f), CornerRadius(fr * 0.6f), style = Stroke(2f))
    // поле — светлое дерево
    clipRect(g.ox, g.oy, g.ox + bw, g.oy + bh) {
        drawRect(Brush.linearGradient(listOf(Color(0xFFEBC48E), Color(0xFFDDAA6C), Color(0xFFE6B87E)), Offset(g.ox, g.oy), Offset(g.ox + bw, g.oy + bh)),
            Offset(g.ox, g.oy), Size(bw, bh))
        woodGrain(g.ox, g.oy, bw, bh, rnd, s * 0.07f, true, Color(0xFF7A4318), 2 + lv.cols / 3)
        // тень от рамки на поле
        drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), g.oy, g.oy + s * 0.14f), Offset(g.ox, g.oy), Size(bw, s * 0.14f))
        drawRect(Brush.horizontalGradient(listOf(Color(0x55000000), Color.Transparent), g.ox, g.ox + s * 0.1f), Offset(g.ox, g.oy), Size(s * 0.1f, bh))
    }
    // нарисованная линия пути, как на игрушке
    val pp = Path()
    lv.path.forEachIndexed { i, c ->
        val x = g.px(c % lv.cols + 0.5f); val y = g.py(c / lv.cols + 0.5f)
        if (i == 0) pp.moveTo(x, y) else pp.lineTo(x, y)
    }
    drawPath(pp, Color(0x55231008), style = Stroke(s * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // лунки
    lv.holes.forEach { h -> drawHole(Offset(g.px(h.x), g.py(h.y)), lv.holeR * s, true) }
    // старт
    val st = Offset(g.px(lv.startX), g.py(lv.startY))
    drawCircle(Color(0xCC2F7D57), s * 0.27f, st, style = Stroke(s * 0.04f))
    drawCircle(Color(0x332F7D57), s * 0.25f, st)
    paintLabel("СТАРТ", st + Offset(0f, s * 0.41f), s * 0.12f, android.graphics.Color.argb(210, 40, 100, 70))
    // финиш — латунная чашка
    val fi = Offset(g.px(lv.finishX), g.py(lv.finishY)); val fR = lv.holeR * s
    drawCircle(Color(0x55000000), fR * 1.42f, fi + Offset(fR * 0.08f, fR * 0.12f))
    drawCircle(Brush.linearGradient(listOf(Color(0xFFFFEDB0), Color(0xFFD4A645), Color(0xFF7A5718)), fi - Offset(fR, fR), fi + Offset(fR, fR)), fR * 1.38f, fi)
    drawCircle(Color(0xFF6A4A16), fR * 1.08f, fi)
    drawHole(fi, fR, false)
    paintLabel("ФИНИШ", fi + Offset(0f, s * 0.43f), s * 0.12f, android.graphics.Color.argb(220, 120, 40, 20))
    // стены: тени, тело с волокнами, светлая и тёмная кромки
    val th = 2 * TiltLevel.WALL_H * s
    lv.walls.forEach { w ->
        val l = g.px(w.l); val t = g.py(w.t); val rw = (w.r - w.l) * s; val rh = (w.b - w.t) * s
        for (k in 1..3) {
            val o = th * 0.2f * k
            drawRoundRect(Color.Black.copy(alpha = 0.11f), Offset(l + o * 0.7f, t + o), Size(rw, rh), CornerRadius(th * 0.4f))
        }
    }
    val wrnd = Random(lv.seed + 5)
    lv.walls.forEach { w ->
        val l = g.px(w.l); val t = g.py(w.t); val r = g.px(w.r); val b = g.py(w.b)
        val horiz = (w.r - w.l) >= (w.b - w.t)
        val cols = listOf(Color(0xFFCB9056), Color(0xFFA2662F), Color(0xFF7C4A22))
        drawRoundRect(if (horiz) Brush.verticalGradient(cols, t, b) else Brush.horizontalGradient(cols, l, r), Offset(l, t), Size(r - l, b - t), CornerRadius(th * 0.2f))
        clipRect(l, t, r, b) { woodGrain(l, t, r - l, b - t, wrnd, th * 0.22f, horiz, Color(0xFF3E1F08), 0) }
        val lw = max(1.2f, th * 0.08f)
        if (horiz) {
            drawLine(Color(0x99FFE6C0), Offset(l + th * 0.2f, t + lw / 2), Offset(r - th * 0.2f, t + lw / 2), lw)
            drawLine(Color(0x80301505), Offset(l + th * 0.2f, b - lw / 2), Offset(r - th * 0.2f, b - lw / 2), lw)
        } else {
            drawLine(Color(0x99FFE6C0), Offset(l + lw / 2, t + th * 0.2f), Offset(l + lw / 2, b - th * 0.2f), lw)
            drawLine(Color(0x80301505), Offset(r - lw / 2, t + th * 0.2f), Offset(r - lw / 2, b - th * 0.2f), lw)
        }
    }
}

/** Надпись «краской» по центру. */
private fun DrawScope.paintLabel(text: String, at: Offset, size: Float, color: Int) {
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; letterSpacing = 0.12f
    }
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, p)
}

/** Лунка с глубиной: тёмное кольцо краски, тень внутри сверху-слева, светлая кромка снизу-справа. */
private fun DrawScope.drawHole(c: Offset, R: Float, ring: Boolean) {
    if (ring) drawCircle(Color(0xD92A160A), R * 1.16f, c)
    drawCircle(Brush.linearGradient(listOf(Color(0xFF0B0603), Color(0xFF2A180C), Color(0xFF7A4C28)), c - Offset(R, R), c + Offset(R, R)), R, c)
    drawCircle(Brush.radialGradient(0f to Color.Black, 0.5f to Color.Black, 0.82f to Color(0xB0000000), 1f to Color.Transparent,
        center = c + Offset(-R * 0.14f, -R * 0.14f), radius = R * 0.9f), R, c)
    drawArc(Color(0x70FFE0B0), 5f, 80f, false, c - Offset(R, R), Size(2 * R, 2 * R), style = Stroke(max(1f, R * 0.07f)))
    drawArc(Color(0x80000000), 190f, 90f, false, c - Offset(R * 0.97f, R * 0.97f), Size(R * 1.94f, R * 1.94f), style = Stroke(max(1f, R * 0.12f)))
}

/** Метки на поверхности шарика (в осях шарика) и их вид: 0 — тёмное пятно, 1 — отблеск, 2 — точка. */
private val BALL_MARKS: List<FloatArray> = run {
    val n = 14; val golden = 2.39996f
    List(n) { i ->
        val z = 1f - 2f * (i + 0.5f) / n; val rad = sqrt(1f - z * z); val a = golden * i
        floatArrayOf(cos(a) * rad, sin(a) * rad, z, (i % 3).toFloat())
    }
}

private fun DrawScope.drawTiltBall(lv: TiltLevel, ball: TiltBall, g: TmGeom) {
    val c = Offset(g.px(ball.x), g.py(ball.y))
    val r0 = lv.ballR * g.s
    val rr = r0 * ball.scale
    val k = ball.fallProgress
    if (ball.falling) {
        val hc = Offset(g.px(ball.targetX), g.py(ball.targetY))
        val clipR = lv.holeR * g.s + (1f - k) * (1f - k) * 3f * r0
        val clip = Path().apply { addOval(Rect(hc, clipR)) }
        clipPath(clip) { drawSteelBall(c, rr, ball.rot, ball.dark, 1f - k * 0.3f) }
    } else {
        // мягкая тень на доске (свет сверху-слева)
        drawCircle(Brush.radialGradient(0f to Color(0x73000000), 0.6f to Color(0x33000000), 1f to Color.Transparent,
            center = c + Offset(rr * 0.3f, rr * 0.38f), radius = rr * 1.25f), rr * 1.25f, c + Offset(rr * 0.3f, rr * 0.38f))
        drawSteelBall(c, rr, ball.rot, 0f, 1f)
    }
}

/** Стальной шарик: объём градиентом, отражение доски снизу, метки, крутящиеся при качении, неподвижный блик. */
private fun DrawScope.drawSteelBall(c: Offset, rr: Float, rot: FloatArray, dark: Float, alpha: Float) {
    if (rr <= 0.5f) return
    val clip = Path().apply { addOval(Rect(c, rr)) }
    clipPath(clip) {
        drawCircle(Brush.radialGradient(0f to Color(0xFFFFFFFF), 0.12f to Color(0xFFE6EAEF), 0.45f to Color(0xFFADB5BF),
            0.78f to Color(0xFF5F6874), 1f to Color(0xFF2A2F37), center = c + Offset(-rr * 0.35f, -rr * 0.4f), radius = rr * 1.6f), rr, c, alpha = alpha)
        // отражение светлого дерева в нижней части
        drawCircle(Brush.radialGradient(0f to Color(0x99C08850), 1f to Color.Transparent, center = c + Offset(rr * 0.15f, rr * 0.95f), radius = rr * 0.95f), rr, c, alpha = alpha)
        // метки — видны только на обращённой к нам половине (z < 0)
        for (m in BALL_MARKS) {
            val wx = rot[0] * m[0] + rot[1] * m[1] + rot[2] * m[2]
            val wy = rot[3] * m[0] + rot[4] * m[1] + rot[5] * m[2]
            val wz = rot[6] * m[0] + rot[7] * m[1] + rot[8] * m[2]
            if (wz > -0.08f) continue
            val depth = -wz
            val p = c + Offset(wx * rr, wy * rr)
            val type = m[3].toInt()
            val big = rr * when (type) { 0 -> 0.26f; 1 -> 0.17f; else -> 0.09f }
            val col = when (type) { 0 -> Color(0xFF3A424D).copy(alpha = 0.32f * depth); 1 -> Color.White.copy(alpha = 0.5f * depth); else -> Color(0xFF1E242C).copy(alpha = 0.5f * depth) }
            val ang = Math.toDegrees(atan2(wy, wx).toDouble()).toFloat()
            rotate(ang, p) { drawOval(col, p - Offset(big * depth / 2, big / 2), Size(big * depth, big), alpha = alpha) }
        }
        // затемнение к краю
        drawCircle(Brush.radialGradient(0.68f to Color.Transparent, 1f to Color(0x77000000), center = c, radius = rr), rr, c, alpha = alpha)
        // неподвижный блик от источника света
        val hl = c + Offset(-rr * 0.36f, -rr * 0.42f)
        drawCircle(Brush.radialGradient(0f to Color(0xF2FFFFFF), 0.35f to Color(0x80FFFFFF), 1f to Color.Transparent, center = hl, radius = rr * 0.45f), rr * 0.45f, hl, alpha = alpha)
        drawCircle(Color.White.copy(alpha = 0.95f * alpha), rr * 0.08f, hl)
        drawArc(Color.White.copy(alpha = 0.22f * alpha), 15f, 70f, false, c - Offset(rr * 0.86f, rr * 0.86f), Size(rr * 1.72f, rr * 1.72f), style = Stroke(rr * 0.09f))
        if (dark > 0f) drawCircle(Color.Black.copy(alpha = (dark * 0.85f).coerceIn(0f, 1f)), rr, c)
    }
}

// ───────────────────────────── звуки ─────────────────────────────

/**
 * Звуки лабиринта — синтез: «тук» стального шарика о деревянную планку (громкость и звонкость по силе
 * удара, 4 уровня), падение в лунку (скатывание и глухой удар внутри), попадание в латунную чашку финиша.
 */
private object TiltSfx {
    private const val SR = com.pank.vsevodnom.ui.random.Sfx.SR

    private class W(sec: Double, seed: Int) {
        val a = FloatArray((sec * SR).toInt())
        val r = java.util.Random(seed.toLong())
        /** Затухающие моды (частота, затухание 1/с). */
        fun modes(at: Double, list: List<Pair<Double, Double>>, amp: Double) {
            val s0 = (at * SR).toInt()
            list.forEachIndexed { m, (f, d) ->
                val len = (SR * 6.0 / d).toInt(); val ph = r.nextDouble() * 2 * PI
                for (i in 0 until len) {
                    val j = s0 + i; if (j >= a.size) break
                    val t = i.toDouble() / SR
                    a[j] += (sin(2 * PI * f * t + ph) * exp(-t * d) * amp / (1 + m * 0.5)).toFloat()
                }
            }
        }
        /** Шум через фильтр нижних частот с огибающей env(0…1). */
        fun noise(at: Double, dur: Double, cutoff: Double, amp: Double, env: (Double) -> Double) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); val k = (2 * PI * cutoff / SR).let { it / (it + 1) }
            var y = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j >= a.size) break
                y += k * ((r.nextDouble() * 2 - 1) - y); y2 += k * (y - y2)
                a[j] += (y2 * 3 * amp * env(i.toDouble() / n)).toFloat()
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

    private val WOOD = listOf(840.0 to 75.0, 1470.0 to 95.0, 2390.0 to 140.0, 3880.0 to 190.0)
    private val STEEL = listOf(6150.0 to 70.0, 9050.0 to 95.0)

    private val clacks: List<ShortArray> by lazy {
        listOf(0.22, 0.42, 0.66, 0.95).mapIndexed { i, gain ->
            val w = W(0.16, 11 + i)
            w.noise(0.0, 0.0025, 6000.0, 0.8) { 1 - it }
            w.modes(0.0, WOOD, 1.0)
            w.modes(0.0, STEEL, 0.15 + 0.12 * i)
            w.pcm(gain)
        }
    }
    private val dropPcm: ShortArray by lazy {
        val w = W(0.65, 21)
        w.noise(0.0, 0.09, 2200.0, 0.35) { 1 - it * 0.6 }                         // скатывание через кромку
        w.modes(0.09, listOf(150.0 to 22.0, 265.0 to 30.0, 430.0 to 45.0, 980.0 to 90.0), 1.0)
        w.modes(0.21, listOf(170.0 to 30.0, 300.0 to 40.0, 1050.0 to 110.0), 0.45)
        w.modes(0.28, listOf(190.0 to 40.0, 1100.0 to 130.0), 0.22)
        w.pcm(0.9)
    }
    private val winPcm: ShortArray by lazy {
        val w = W(1.4, 31)
        w.modes(0.0, listOf(1250.0 to 6.0, 2980.0 to 9.0, 4400.0 to 14.0), 0.7)  // шарик в латунной чашке
        w.modes(0.07, listOf(1260.0 to 9.0, 3000.0 to 12.0), 0.35)
        w.modes(0.16, listOf(1568.0 to 3.5, 3136.0 to 6.0), 0.45)                  // колокольчик
        w.modes(0.32, listOf(2093.0 to 3.0, 4186.0 to 5.0), 0.45)
        w.pcm(0.85)
    }

    private fun play(key: String, pcm: ShortArray) { runCatching { com.pank.vsevodnom.ui.random.Sfx.playPcm(key, pcm) } }

    /** Удар о стену: [speed] — скорость по нормали (клеток/с). */
    fun clack(speed: Float, n: Int) {
        val lvl = ((speed - 0.5f) / 1.8f).toInt().coerceIn(0, 3)
        play("tilt_c${n % 3}", clacks[lvl])
    }
    fun drop() = play("tilt_drop", dropPcm)
    fun win() = play("tilt_win", winPcm)
}
