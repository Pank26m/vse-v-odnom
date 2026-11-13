package com.pank.vsevodnom.ui.games

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Бильярд (0.45): физика и правила без Compose — проверяется тестом BilliardsTest.
 *
 * Физика (единицы — метры и секунды, стол лежит вертикально: x поперёк, y вдоль):
 *  • шар скользит, пока точка касания с сукном движется: трение скольжения μs·g тормозит её и раскручивает шар
 *    (dv = a·dt, dω = 5/(2R)·a — так «накат» и «оттяжка» получаются сами, без подгонки);
 *  • когда точка касания остановилась — шар катится: ω = v/R, торможение качения μr·g;
 *  • боковой винт ωz затухает и при ударе о борт добавляет касательную скорость;
 *  • шар о шар — упругий удар равных масс (обмен нормальными составляющими, e = 0,95), вращение сохраняется;
 *  • борта и губки луз — отрезки, шар отражается с потерей (e = 0,78); улетевший за линию борта в проёме — в лузе.
 */
enum class PoolMode(val title: String) { EIGHT("Американский (восьмёрка)"), PYRAMID("Русская пирамида") }

class PoolBall(val num: Int) {   // 0 — биток
    var x = 0.0; var y = 0.0; var vx = 0.0; var vy = 0.0
    var wx = 0.0; var wy = 0.0; var wz = 0.0
    var on = true
    /** Ориентация для рисования (матрица 3×3 по строкам) — чтобы было видно, как шар катится. */
    val rot = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    val moving get() = on && (vx != 0.0 || vy != 0.0)
    fun copyFrom(b: PoolBall) { x = b.x; y = b.y; vx = b.vx; vy = b.vy; wx = b.wx; wy = b.wy; wz = b.wz; on = b.on }
}

class PoolTable(val mode: PoolMode) {
    // американский 9 футов: 2,54×1,27 м, шар 57,15 мм, лузы 114/127 мм; пирамида 12 футов: 3,55×1,775 м, шар 68 мм, лузы узкие — 76/86 мм
    val W = if (mode == PoolMode.EIGHT) 1.27 else 1.775
    val H = if (mode == PoolMode.EIGHT) 2.54 else 3.55
    val R = if (mode == PoolMode.EIGHT) 0.028575 else 0.034
    val cornerMouth = if (mode == PoolMode.EIGHT) 0.114 else 0.076
    val sideMouth = if (mode == PoolMode.EIGHT) 0.127 else 0.086
    /** Линия «дома»: биток с руки ставится ниже неё (игрок внизу). */
    val headY = H * 0.75
    val footX = W / 2; val footY = H * 0.25
    class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double)
    val rails = ArrayList<Seg>()
    /** Центры луз — для прицела бота и рисования. */
    val pockets = ArrayList<Pair<Double, Double>>()
    init {
        val a = cornerMouth / sqrt(2.0); val s = sideMouth / 2; val j = 0.07
        // длинные борта (x = 0 и x = W), по два отрезка с боковой лузой посередине
        for (side in 0..1) {
            val x = if (side == 0) 0.0 else W; val o = if (side == 0) -1.0 else 1.0
            rails += Seg(x, a, x, H / 2 - s); rails += Seg(x, H / 2 + s, x, H - a)
            rails += Seg(x, a, x + o * j, a - j); rails += Seg(x, H - a, x + o * j, H - a + j)                  // губки угловых
            rails += Seg(x, H / 2 - s, x + o * j, H / 2 - s + j * 0.25); rails += Seg(x, H / 2 + s, x + o * j, H / 2 + s - j * 0.25)   // губки боковой
        }
        // короткие борта (y = 0 и y = H)
        for (end in 0..1) {
            val y = if (end == 0) 0.0 else H; val o = if (end == 0) -1.0 else 1.0
            rails += Seg(a, y, W - a, y)
            rails += Seg(a, y, a - j, y + o * j); rails += Seg(W - a, y, W - a + j, y + o * j)
        }
        pockets += 0.0 to 0.0; pockets += W to 0.0; pockets += 0.0 to H / 2; pockets += W to H / 2; pockets += 0.0 to H; pockets += W to H
    }
    /** Точка прицела бота в лузу — чуть внутрь стола от центра проёма. */
    fun aimPoint(i: Int): Pair<Double, Double> {
        val (px, py) = pockets[i]
        val dx = if (px == 0.0) 1.0 else -1.0
        return if (py == H / 2) (px + dx * R * 0.3) to py
        else { val dy = if (py == 0.0) 1.0 else -1.0; (px + dx * R * 0.9) to (py + dy * R * 0.9) }
    }
}

/** События удара — для звуков и правил. */
sealed class PoolEvent {
    class Hit(val a: Int, val b: Int, val speed: Double) : PoolEvent()
    class Rail(val ball: Int, val speed: Double) : PoolEvent()
    class Pot(val ball: Int, val pocket: Int) : PoolEvent()
}

class PoolPhysics(val t: PoolTable) {
    companion object { const val G = 9.81; const val MU_S = 0.2; const val MU_R = 0.011; const val SPIN_DECAY = 14.0; const val E_BALL = 0.95; const val E_RAIL = 0.78 }
    var trackRotation = true
    val events = ArrayList<PoolEvent>()

    fun allStopped(balls: List<PoolBall>) = balls.none { it.moving }

    /** Шаг dt по всем шарам (dt ≤ 1 мс, чтобы быстрые шары не «проскакивали»). */
    fun step(balls: List<PoolBall>, dt: Double) {
        val R = t.R
        for (b in balls) {
            if (!b.moving) { if (b.on && b.wz != 0.0) b.wz = decay(b.wz, SPIN_DECAY * dt); continue }
            val ux = b.vx - R * b.wy; val uy = b.vy + R * b.wx
            val u = hypot(ux, uy)
            if (u > 0.01) {
                val ax = -MU_S * G * ux / u; val ay = -MU_S * G * uy / u
                // не перескакиваем ноль: при 3,5·|a|·dt > u — сразу качение
                if (3.5 * MU_S * G * dt >= u) { roll(b) } else {
                    b.vx += ax * dt; b.vy += ay * dt
                    b.wx += 2.5 / R * ay * dt; b.wy -= 2.5 / R * ax * dt
                }
            } else {
                val v = hypot(b.vx, b.vy)
                val nv = v - MU_R * G * dt
                if (nv <= 0.004) { b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0 }
                else { b.vx *= nv / v; b.vy *= nv / v; roll(b) }
            }
            b.wz = decay(b.wz, SPIN_DECAY * dt)
            b.x += b.vx * dt; b.y += b.vy * dt
            if (trackRotation) rotate(b, dt)
        }
        // шар о шар
        for (i in balls.indices) { val a = balls[i]; if (!a.on) continue
            for (j in i + 1 until balls.size) { val b = balls[j]; if (!b.on || (!a.moving && !b.moving)) continue
                val dx = b.x - a.x; val dy = b.y - a.y; val d2 = dx * dx + dy * dy
                if (d2 >= 4 * R * R || d2 == 0.0) continue
                val d = sqrt(d2); val nx = dx / d; val ny = dy / d
                val va = a.vx * nx + a.vy * ny; val vb = b.vx * nx + b.vy * ny
                val overlap = 2 * R - d
                a.x -= nx * overlap / 2; a.y -= ny * overlap / 2; b.x += nx * overlap / 2; b.y += ny * overlap / 2
                if (va - vb <= 0) continue
                val p = (va - vb) * (1 + E_BALL) / 2
                a.vx -= p * nx; a.vy -= p * ny; b.vx += p * nx; b.vy += p * ny
                events += PoolEvent.Hit(a.num, b.num, va - vb)
            } }
        // борта и лузы
        for (b in balls) { if (!b.on || !b.moving) continue
            for (s in t.rails) {
                val ex = s.bx - s.ax; val ey = s.by - s.ay; val l2 = ex * ex + ey * ey
                val k = (((b.x - s.ax) * ex + (b.y - s.ay) * ey) / l2).coerceIn(0.0, 1.0)
                val cx = s.ax + ex * k; val cy = s.ay + ey * k
                val dx = b.x - cx; val dy = b.y - cy; val d2 = dx * dx + dy * dy
                if (d2 >= R * R || d2 == 0.0) continue
                val d = sqrt(d2); val nx = dx / d; val ny = dy / d
                b.x += nx * (R - d); b.y += ny * (R - d)
                val vn = b.vx * nx + b.vy * ny
                if (vn >= 0) continue
                val tx = -ny; val ty = nx
                var vt = b.vx * tx + b.vy * ty
                vt += 0.25 * R * b.wz * (if (tx * ny - ty * nx > 0) 1 else -1)   // боковой винт меняет угол отскока
                b.wz *= 0.5
                val nvn = -vn * E_RAIL
                b.vx = nx * nvn + tx * vt; b.vy = ny * nvn + ty * vt
                // после борта шар немного скользит: вращение наполовину «сбито»
                b.wx *= 0.4; b.wy *= 0.4
                events += PoolEvent.Rail(b.num, -vn)
            }
            val m = R * 0.6
            if (b.x < -m || b.x > t.W + m || b.y < -m || b.y > t.H + m) {
                val p = t.pockets.indices.minBy { hypot(t.pockets[it].first - b.x, t.pockets[it].second - b.y) }
                b.on = false; b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0; b.wz = 0.0
                events += PoolEvent.Pot(b.num, p)
            }
        }
    }

    private fun decay(v: Double, by: Double) = if (abs(v) <= by) 0.0 else v - sign(v) * by
    private fun roll(b: PoolBall) { b.wx = -b.vy / t.R; b.wy = b.vx / t.R }

    /** Поворот шара на |ω|·dt вокруг оси ω (формула Родрига) — только для рисования. */
    private fun rotate(b: PoolBall, dt: Double) {
        val w = sqrt(b.wx * b.wx + b.wy * b.wy + b.wz * b.wz); if (w < 1e-6) return
        val ang = w * dt; val kx = b.wx / w; val ky = b.wy / w; val kz = b.wz / w
        val c = cos(ang); val s = sin(ang); val v = 1 - c
        val m = doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v)
        val r = b.rot; val o = r.copyOf()
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = m[i * 3] * o[j] + m[i * 3 + 1] * o[3 + j] + m[i * 3 + 2] * o[6 + j]
    }
}

/** Где можно поставить биток с руки. */
enum class InHand { NONE, KITCHEN, ANYWHERE }

/** Партия: шары, очередь, правила восьмёрки или пирамиды. Игрок 0 — ты, 1 — бот. */
class PoolGame(val mode: PoolMode, rnd: Random = Random) {
    val table = PoolTable(mode)
    val phys = PoolPhysics(table)
    val balls = ArrayList<PoolBall>()
    val cue get() = balls[0]
    var turn = 0
    var inHand = InHand.KITCHEN
    var broken = false
    /** Восьмёрка: группа игрока (1 — сплошные 1–7, 2 — полосатые 9–15, 0 — стол открыт). */
    val group = intArrayOf(0, 0)
    /** Пирамида: забито шаров (до 8). */
    val score = intArrayOf(0, 0)
    val pottedBy = arrayOf(ArrayList<Int>(), ArrayList())
    var winner = -1
    var message = ""
    var shooting = false
    // сведения о текущем ударе
    private var firstHit = -1
    private var railAfterHit = false
    private val pottedNow = ArrayList<Int>()

    init {
        balls += PoolBall(0)
        val nums: List<Int> = if (mode == PoolMode.EIGHT) {
            // 8 — в центре, в задних углах — сплошной и полосатый, остальные случайно
            val solids = (1..7).shuffled(rnd); val stripes = (9..15).shuffled(rnd)
            val rest = (solids.drop(1) + stripes.drop(1)).shuffled(rnd).toMutableList()
            val order = MutableList(15) { 0 }
            val backL = 10; val backR = 14; val center = 4
            val corners = if (rnd.nextBoolean()) listOf(solids[0], stripes[0]) else listOf(stripes[0], solids[0])
            order[center] = 8; order[backL] = corners[0]; order[backR] = corners[1]
            for (i in 0 until 15) if (i != center && i != backL && i != backR) order[i] = rest.removeAt(0)
            order
        } else (1..15).shuffled(rnd)
        val R = table.R; var k = 0
        for (row in 0..4) for (j in 0..row) {
            val b = PoolBall(nums[k++])
            b.x = table.footX + (j - row / 2.0) * 2 * R * 1.002
            b.y = table.footY - row * R * sqrt(3.0) * 1.002
            balls += b
        }
        cue.x = table.W / 2; cue.y = table.headY + (table.H - table.headY) * 0.35
        turn = rnd.nextInt(2)
        message = if (turn == 0) "Твой разбой: поставь биток в «доме» и бей" else "Разбивает бот"
    }

    fun ball(n: Int) = balls.first { it.num == n }
    fun objectsLeft() = balls.filter { it.num != 0 && it.on }
    fun groupOf(n: Int) = when (n) { in 1..7 -> 1; in 9..15 -> 2; else -> 0 }
    fun ownLeft(p: Int) = if (group[p] == 0) -1 else balls.count { it.on && groupOf(it.num) == group[p] }
    /** Какие шары игрок p может законно задеть первым. */
    fun legalTargets(p: Int): List<PoolBall> = when (mode) {
        PoolMode.PYRAMID -> objectsLeft()
        PoolMode.EIGHT -> when {
            group[p] == 0 -> objectsLeft().filter { it.num != 8 }
            ownLeft(p) == 0 -> objectsLeft().filter { it.num == 8 }
            else -> objectsLeft().filter { groupOf(it.num) == group[p] }
        }
    }

    fun canPlace(x: Double, y: Double): Boolean {
        val R = table.R
        if (x < R || x > table.W - R || y < R || y > table.H - R) return false
        if (inHand == InHand.KITCHEN && y < table.headY) return false
        return balls.none { it.num != 0 && it.on && hypot(it.x - x, it.y - y) < 2 * R + 0.001 }
    }

    /** Удар: угол (рад, 0 — вправо), сила 0…1, винт (sx — вбок, sy — верх/низ, −1…1). */
    fun shoot(angle: Double, power: Double, sx: Double, sy: Double) {
        if (shooting || winner >= 0) return
        cue.on = true
        val vmax = if (mode == PoolMode.EIGHT) 8.0 else 9.0
        val v = vmax * power.coerceIn(0.02, 1.0)
        val dx = cos(angle); val dy = sin(angle)
        cue.vx = v * dx; cue.vy = v * dy
        val k = 1.25 * v * sy.coerceIn(-1.0, 1.0) / table.R
        cue.wx = -k * dy; cue.wy = k * dx; cue.wz = 1.25 * v * sx.coerceIn(-1.0, 1.0) / table.R
        firstHit = -1; railAfterHit = false; pottedNow.clear(); phys.events.clear()
        inHand = InHand.NONE; shooting = true; message = ""
    }

    /** Продвинуть время; когда всё остановилось — разобрать удар. Возвращает события для звуков. */
    fun advance(seconds: Double, stepDt: Double = 0.0005): List<PoolEvent> {
        val out = ArrayList<PoolEvent>()
        if (!shooting) return out
        var t = 0.0
        while (t < seconds) {
            phys.step(balls, stepDt); t += stepDt
            for (e in phys.events) {
                when (e) {
                    is PoolEvent.Hit -> if (firstHit < 0 && (e.a == 0 || e.b == 0)) firstHit = if (e.a == 0) e.b else e.a
                    is PoolEvent.Rail -> if (firstHit >= 0) railAfterHit = true
                    is PoolEvent.Pot -> pottedNow += e.ball
                }
                out += e
            }
            phys.events.clear()
            if (phys.allStopped(balls)) { finishShot(); break }
        }
        return out
    }

    /** Досчитать удар до конца сразу (для бота и тестов). */
    fun runToEnd(maxSeconds: Double = 25.0, stepDt: Double = 0.001) { var t = 0.0; while (shooting && t < maxSeconds) { advance(0.05, stepDt); t += 0.05 }; if (shooting) finishShot() }

    private fun spot(n: Int) {
        val b = ball(n); b.on = true; b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0; b.wz = 0.0
        var y = table.footY
        while (balls.any { it !== b && it.on && hypot(it.x - table.footX, it.y - y) < 2 * table.R }) y -= table.R * 0.5
        b.x = table.footX; b.y = y
    }

    private fun finishShot() {
        shooting = false
        balls.forEach { it.vx = 0.0; it.vy = 0.0; it.wx = 0.0; it.wy = 0.0; it.wz = 0.0 }
        val me = turn; val other = 1 - me
        val cuePotted = 0 in pottedNow
        val objs = pottedNow.filter { it != 0 }
        val wasBreak = !broken; broken = true
        when (mode) {
            PoolMode.EIGHT -> {
                val targetsBefore = if (group[me] == 0) (1..15).filter { it != 8 } else if (ownLeftBefore(me, objs) == 0) listOf(8) else (1..15).filter { groupOf(it) == group[me] }
                val foul = when {
                    firstHit < 0 -> "Фол: биток никого не задел"
                    cuePotted -> "Фол: биток в лузе"
                    !wasBreak && firstHit !in targetsBefore -> "Фол: первым задет не свой шар"
                    !wasBreak && objs.isEmpty() && !railAfterHit -> "Фол: после удара ни один шар не коснулся борта"
                    else -> null
                }
                if (8 in objs) {
                    if (wasBreak) { spot(8); message = "Восьмёрка на разбое — возвращена на место" }
                    else {
                        val ok = foul == null && targetsBefore == listOf(8)
                        winner = if (ok) me else other
                        message = if (ok) "${who(me)} забивает восьмёрку — победа!" else "${who(me)} забивает восьмёрку не вовремя — проигрыш"
                        return
                    }
                }
                // открытый стол: группа — по первому законно забитому шару
                if (foul == null && group[me] == 0 && !wasBreak) objs.firstOrNull { it != 8 }?.let { n -> group[me] = groupOf(n); group[other] = 3 - groupOf(n) }
                objs.filter { it != 8 }.forEach { n -> val owner = if (group[0] == groupOf(n)) 0 else if (group[1] == groupOf(n)) 1 else me; pottedBy[owner] += n }
                val ownPotted = objs.any { it != 8 && (group[me] == 0 || groupOf(it) == group[me]) }
                when {
                    foul != null -> { turn = other; inHand = InHand.ANYWHERE; message = "$foul — у соперника шар с руки"; if (cuePotted) placeDefault() }
                    ownPotted -> { message = "${who(me)} забивает — ещё удар" }
                    else -> { turn = other; message = if (objs.isNotEmpty()) "Забит чужой шар — ход переходит" else "Мимо — ход переходит" }
                }
            }
            PoolMode.PYRAMID -> {
                val foul = when {
                    firstHit < 0 -> "Фол: биток никого не задел"
                    objs.isEmpty() && !cuePotted && !railAfterHit -> "Фол: после удара ни один шар не коснулся борта"
                    else -> null
                }
                if (foul != null) {
                    // штраф: шар из забитых возвращается на стол
                    pottedBy[me].lastOrNull()?.let { n -> pottedBy[me].remove(n); score[me]--; spot(n) }
                    objs.forEach { spot(it) }
                    turn = other
                    if (cuePotted) { inHand = InHand.KITCHEN; placeDefault() }
                    message = "$foul — штраф: шар возвращён, ход сопернику"
                } else {
                    val got = objs.size + if (cuePotted) 1 else 0   // свояк — биток в лузе после удара по шару — тоже очко
                    objs.forEach { pottedBy[me] += it }; score[me] += got
                    if (cuePotted) { inHand = InHand.KITCHEN; placeDefault() }
                    if (score[me] >= 8) { winner = me; message = "${who(me)} набирает 8 шаров — победа!"; return }
                    if (objectsLeft().isEmpty()) { winner = if (score[0] >= score[1]) 0 else 1; message = "Шары кончились"; return }
                    message = if (got > 0) "${who(me)}: +$got" + (if (cuePotted) " (свояк)" else "") + " — ещё удар" else { turn = other; "Мимо — ход переходит" }
                }
            }
        }
    }
    /** Сколько своих оставалось ДО этого удара. */
    private fun ownLeftBefore(p: Int, objs: List<Int>) = if (group[p] == 0) -1 else ownLeft(p) + objs.count { groupOf(it) == group[p] }
    fun who(p: Int) = if (p == 0) "Ты" else "Бот"

    private fun placeDefault() {
        cue.on = true; cue.vx = 0.0; cue.vy = 0.0
        var x = table.W / 2; var y = table.headY + (table.H - table.headY) * 0.35
        var tries = 0
        while (!canPlaceAny(x, y) && tries++ < 200) { x = table.R * 2 + (tries * 0.137 % 1.0) * (table.W - 4 * table.R); y = table.headY + 0.05 + (tries * 0.071 % 1.0) * (table.H - table.headY - 0.1) }
        cue.x = x; cue.y = y
    }
    private fun canPlaceAny(x: Double, y: Double) = balls.none { it.num != 0 && it.on && hypot(it.x - x, it.y - y) < 2 * table.R + 0.001 }

    /** Копия для расчётов бота (без вращения для рисования). */
    fun snapshot(): PoolGame {
        val g = PoolGame(mode, Random(1))
        for (i in balls.indices) { g.balls[i] = PoolBall(balls[i].num).also { it.copyFrom(balls[i]) } }
        g.turn = turn; g.inHand = inHand; g.broken = broken; g.group[0] = group[0]; g.group[1] = group[1]
        g.score[0] = score[0]; g.score[1] = score[1]; g.pottedBy[0].addAll(pottedBy[0]); g.pottedBy[1].addAll(pottedBy[1])
        g.phys.trackRotation = false
        return g
    }
}

/** Прицел и предсказание: куда пойдёт биток до первого касания. */
object PoolAim {
    class Guide(val ex: Double, val ey: Double, val hit: PoolBall?, val objDx: Double, val objDy: Double, val cueDx: Double, val cueDy: Double, val railNx: Double, val railNy: Double)

    fun guide(g: PoolGame, angle: Double): Guide {
        val t = g.table; val R = t.R; val c = g.cue
        val dx = cos(angle); val dy = sin(angle)
        var best = Double.MAX_VALUE; var hit: PoolBall? = null
        for (b in g.balls) { if (b.num == 0 || !b.on) continue
            val fx = b.x - c.x; val fy = b.y - c.y
            val along = fx * dx + fy * dy; if (along <= 0) continue
            val perp2 = fx * fx + fy * fy - along * along
            if (perp2 > 4 * R * R) continue
            val d = along - sqrt(4 * R * R - perp2)
            if (d < best) { best = d; hit = b }
        }
        // борт: прямоугольник [R, W−R]×[R, H−R]
        val tx = if (dx > 0) (t.W - R - c.x) / dx else if (dx < 0) (R - c.x) / dx else Double.MAX_VALUE
        val ty = if (dy > 0) (t.H - R - c.y) / dy else if (dy < 0) (R - c.y) / dy else Double.MAX_VALUE
        val rail = minOf(tx, ty)
        if (hit == null || rail < best) {
            val nx = if (tx < ty) -sign(dx) else 0.0; val ny = if (ty <= tx) -sign(dy) else 0.0
            return Guide(c.x + dx * rail, c.y + dy * rail, null, 0.0, 0.0, 0.0, 0.0, nx, ny)
        }
        val gx = c.x + dx * best; val gy = c.y + dy * best
        val ox = hit.x - gx; val oy = hit.y - gy; val ol = hypot(ox, oy)
        val onx = ox / ol; val ony = oy / ol
        // биток после удара без винта уходит по касательной (под 90°)
        val dot = dx * onx + dy * ony
        var cx = dx - dot * onx; var cy = dy - dot * ony; val cl = hypot(cx, cy)
        if (cl > 1e-6) { cx /= cl; cy /= cl } else { cx = 0.0; cy = 0.0 }
        return Guide(gx, gy, hit, onx, ony, cx, cy, 0.0, 0.0)
    }
}

/** Бот: перебирает «шар → луза», отбирает лучшие по геометрии и проверяет физикой. Чем выше уровень — тем точнее и тем больше вариантов. */
object PoolBot {
    val LEVELS = listOf("Новичок", "Любитель", "Мастер", "Профи")
    data class Shot(val angle: Double, val power: Double, val sx: Double, val sy: Double)
    private data class Cand(val angle: Double, val cut: Double, val dist: Double, val ease: Double)

    private fun clear(g: PoolGame, ax: Double, ay: Double, bx: Double, by: Double, skip: Set<Int>): Boolean {
        val R = g.table.R; val ex = bx - ax; val ey = by - ay; val l2 = ex * ex + ey * ey; if (l2 < 1e-9) return true
        return g.balls.none { b -> b.on && b.num !in skip && run {
            val k = (((b.x - ax) * ex + (b.y - ay) * ey) / l2).coerceIn(0.0, 1.0)
            hypot(b.x - (ax + ex * k), b.y - (ay + ey * k)) < 2 * R * 0.98 } }
    }

    private fun candidates(g: PoolGame, p: Int): List<Cand> {
        val t = g.table; val R = t.R; val c = g.cue; val out = ArrayList<Cand>()
        for (b in g.legalTargets(p)) for (pi in t.pockets.indices) {
            val (axp, ayp) = t.aimPoint(pi)
            val dx = axp - b.x; val dy = ayp - b.y; val dl = hypot(dx, dy); if (dl < 1e-6) continue
            val ux = dx / dl; val uy = dy / dl
            // в боковую лузу под острым углом не идёт
            if (t.pockets[pi].second == t.H / 2 && abs(ux) < 0.45) continue
            val gx = b.x - ux * 2 * R; val gy = b.y - uy * 2 * R
            if (gx < R || gx > t.W - R || gy < R || gy > t.H - R) continue
            val cx = gx - c.x; val cy = gy - c.y; val cl = hypot(cx, cy); if (cl < 1e-6) continue
            val cos = (cx * ux + cy * uy) / cl; if (cos < 0.26) continue   // срез больше ~75°
            if (!clear(g, c.x, c.y, gx, gy, setOf(0, b.num))) continue
            if (!clear(g, b.x, b.y, axp, ayp, setOf(0, b.num))) continue
            val ease = cos * cos / (1 + cl * 0.6) / (1 + dl * 0.9)
            out += Cand(atan2(cy, cx), cos, cl + dl, ease)
        }
        return out.sortedByDescending { it.ease }
    }

    /** Сколько простых ударов у игрока p в этой позиции (для оценки выхода). */
    private fun easyShots(g: PoolGame, p: Int) = candidates(g, p).count { it.cut > 0.7 }

    private fun value(g0: PoolGame, me: Int, s: Shot): Double {
        val g = g0.snapshot(); g.turn = me
        g.shoot(s.angle, s.power, s.sx, s.sy); g.runToEnd(stepDt = 0.001)
        if (g.winner == me) return 10_000.0
        if (g.winner >= 0) return -10_000.0
        var v = 0.0
        v += if (g.mode == PoolMode.EIGHT) {
            val mineBefore = g0.ownLeft(me).let { if (it < 0) 7 else it }; val mineAfter = g.ownLeft(me).let { if (it < 0) 7 else it }
            (mineBefore - mineAfter) * 100.0
        } else (g.score[me] - g0.score[me]) * 100.0
        if (g.turn == me) v += 40 + easyShots(g, me) * 12.0 else v -= easyShots(g, 1 - me) * 10.0 + if (g.inHand != InHand.NONE) 60 else 0
        return v
    }

    /** Ход бота. Если биток с руки — сначала ставит его. */
    fun move(g: PoolGame, level: Int, rnd: Random = Random): Shot {
        val me = g.turn
        if (g.inHand != InHand.NONE) place(g, rnd)
        if (!g.broken) return Shot(-Math.PI / 2 + (rnd.nextDouble() - 0.5) * 0.01, 1.0, 0.0, 0.1)   // разбой — сильно и прямо
        val noise = listOf(0.05, 0.02, 0.008, 0.0025)[level.coerceIn(0, 3)] * (if (g.mode == PoolMode.PYRAMID) 0.45 else 1.0)   // у пирамиды лузы узкие
        val nCand = listOf(1, 4, 7, 10)[level.coerceIn(0, 3)]
        val powers = if (level == 0) listOf(0.45) else if (level == 1) listOf(0.35, 0.6) else listOf(0.28, 0.45, 0.7)
        val spins = if (level >= 3) listOf(0.0 to 0.0, 0.0 to 0.5, 0.0 to -0.5) else if (level == 2) listOf(0.0 to 0.0, 0.0 to -0.4) else listOf(0.0 to 0.0)
        val cands = candidates(g, me).take(nCand)
        var best: Shot? = null; var bestV = Double.NEGATIVE_INFINITY
        for (c in cands) for (pw in powers) for ((sx, sy) in spins) {
            val s = Shot(c.angle, pw, sx, sy)
            val v = if (level == 0) c.ease else value(g, me, s)
            if (v > bestV) { bestV = v; best = s }
        }
        val shot = best ?: safety(g, me)
        return shot.copy(angle = shot.angle + rnd.nextDouble(-noise, noise), power = (shot.power * (1 + rnd.nextDouble(-noise, noise) * 3)).coerceIn(0.05, 1.0))
    }

    /** Нет хорошего удара — хотя бы законно задеть свой шар (прямо в ближайший). */
    private fun safety(g: PoolGame, me: Int): Shot {
        val c = g.cue
        val t = g.legalTargets(me).filter { clear(g, c.x, c.y, it.x, it.y, setOf(0, it.num)) }.minByOrNull { hypot(it.x - c.x, it.y - c.y) }
            ?: g.legalTargets(me).minByOrNull { hypot(it.x - c.x, it.y - c.y) } ?: return Shot(-Math.PI / 2, 0.4, 0.0, 0.0)
        return Shot(atan2(t.y - c.y, t.x - c.x), 0.35, 0.0, 0.0)
    }

    /** Поставить биток с руки туда, откуда лучший удар. */
    private fun place(g: PoolGame, rnd: Random) {
        val t = g.table; var bestXY = g.cue.x to g.cue.y; var bestE = -1.0
        repeat(80) {
            val x = t.R * 2 + rnd.nextDouble() * (t.W - 4 * t.R)
            val y = if (g.inHand == InHand.KITCHEN) t.headY + t.R + rnd.nextDouble() * (t.H - t.headY - 2 * t.R) else t.R * 2 + rnd.nextDouble() * (t.H - 4 * t.R)
            if (!g.canPlace(x, y)) return@repeat
            val ox = g.cue.x; val oy = g.cue.y; g.cue.x = x; g.cue.y = y
            val e = candidates(g, g.turn).firstOrNull()?.ease ?: 0.0
            g.cue.x = ox; g.cue.y = oy
            if (e > bestE) { bestE = e; bestXY = x to y }
        }
        g.cue.x = bestXY.first; g.cue.y = bestXY.second
    }
}
