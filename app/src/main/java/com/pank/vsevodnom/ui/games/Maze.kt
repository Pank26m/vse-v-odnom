package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

// Своя карта: # стена, . ягода, o звезда (тучки становятся съедобными), H дом тучек, D дверь дома, P старт
internal val MAZE_MAP = listOf(
    "###################",
    "#o.......#.......o#",
    "#.##.###.#.###.##.#",
    "#.................#",
    "#.##.#.#####.#.##.#",
    "#....#...#...#....#",
    "####.###.#.###.####",
    "####.#.......#.####",
    "####.#.##D##.#.####",
    "#......#HHH#......#",
    "####.#.#####.#.####",
    "####.#.......#.####",
    "####.#.#####.#.####",
    "#........#........#",
    "#.##.###.#.###.##.#",
    "#o.#.....P.....#.o#",
    "##.#.#.#####.#.#.##",
    "#....#...#...#....#",
    "#.######.#.######.#",
    "#.................#",
    "###################",
)
private val DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

private class Mover(var x: Int, var y: Int, var dx: Int = 0, var dy: Int = 0, var t: Float = 0f) {
    val px get() = x + dx * t
    val py get() = y + dy * t
}

private class Cloud(val home: Pair<Int, Int>, val color: Color, val style: Int) {
    val m = Mover(home.first, home.second)
    var eaten = false; var waitHome = 0f
}

private class MazeGame(var level: Int = 1, var lives: Int = 3, var score: Int = 0) {
    val w = MAZE_MAP[0].length; val h = MAZE_MAP.size
    val dots = HashSet<Pair<Int, Int>>(); val stars = HashSet<Pair<Int, Int>>()
    var start = 0 to 0
    val home = ArrayList<Pair<Int, Int>>()
    var door = 0 to 0
    init {
        MAZE_MAP.forEachIndexed { y, row -> row.forEachIndexed { x, c ->
            when (c) { '.' -> dots += x to y; 'o' -> stars += x to y; 'P' -> start = x to y; 'H' -> home += x to y; 'D' -> door = x to y }
        } }
    }
    val me = Mover(start.first, start.second)
    var want = 0 to 0
    val clouds = listOf(Color(0xFFE5485D), Color(0xFF8E5BD8), Color(0xFF4A86E8), Color(0xFFF2A33A)).mapIndexed { i, c -> Cloud(home[i % home.size], c, i).also { it.waitHome = i * 2.5f } }
    var fright = 0f; var combo = 0
    var over by androidx.compose.runtime.mutableStateOf(false); var started by androidx.compose.runtime.mutableStateOf(false); var deathPause = 0f
    var mode = 0f   // время: тучки периодически «разбредаются», потом снова гонятся

    fun wall(x: Int, y: Int, cloud: Boolean): Boolean {
        if (y !in 0 until h || x !in 0 until w) return true
        val c = MAZE_MAP[y][x]
        return c == '#' || (!cloud && (c == 'H' || c == 'D'))
    }

    private fun stepPlayer(dt: Float) {
        val sp = 5.4f + level * 0.15f
        val m = me
        if (want.first == -m.dx && want.second == -m.dy && (m.dx != 0 || m.dy != 0)) { m.x += m.dx; m.y += m.dy; m.dx = want.first; m.dy = want.second; m.t = 1 - m.t }
        if (m.dx == 0 && m.dy == 0) { if (!wall(m.x + want.first, m.y + want.second, false) && want != (0 to 0)) { m.dx = want.first; m.dy = want.second } else return }
        // поворот «впритык»: смахнул чуть позже перекрёстка (ушёл от центра клетки меньше чем на 40%) — поворачиваем здесь же, а не на следующем
        val perp = want != (0 to 0) && want.first * m.dx + want.second * m.dy == 0
        if (perp && m.t < 0.4f && !wall(m.x + want.first, m.y + want.second, false)) { m.t = 0f; m.dx = want.first; m.dy = want.second }
        m.t += sp * dt
        while (m.t >= 1f) {
            m.t -= 1f; m.x += m.dx; m.y += m.dy
            eat(m.x to m.y)
            if (want != (0 to 0) && !wall(m.x + want.first, m.y + want.second, false)) { m.dx = want.first; m.dy = want.second }
            else if (wall(m.x + m.dx, m.y + m.dy, false)) { m.dx = 0; m.dy = 0; m.t = 0f; break }
        }
    }

    /** Сколько точек съедено — экран по нему играет «вака». */
    var chomps = 0
    private fun eat(p: Pair<Int, Int>) {
        if (dots.remove(p)) { score += 10; chomps++ }
        if (stars.remove(p)) { score += 50; fright = (8f - level * 0.6f).coerceAtLeast(3f); combo = 0 }
    }

    private fun stepCloud(c: Cloud, dt: Float, chase: Boolean) {
        if (c.waitHome > 0) { c.waitHome -= dt; return }
        val m = c.m
        val sp = when { c.eaten -> 9f; fright > 0 -> 3.2f; else -> 4.6f + level * 0.25f }
        if (m.dx == 0 && m.dy == 0) choose(c, chase)
        m.t += sp * dt
        while (m.t >= 1f) {
            m.t -= 1f; m.x += m.dx; m.y += m.dy
            if (c.eaten && (m.x to m.y) in home) { c.eaten = false; c.waitHome = 1.5f; m.dx = 0; m.dy = 0; m.t = 0f; return }
            choose(c, chase)
        }
    }

    /** На каждой клетке тучка выбирает поворот: к своей цели, без разворота назад (кроме тупика). */
    private fun choose(c: Cloud, chase: Boolean) {
        val m = c.m
        val options = DIRS.filter { (dx, dy) -> !wall(m.x + dx, m.y + dy, true) }
        val forward = options.filter { !(it.first == -m.dx && it.second == -m.dy) }.ifEmpty { options }
        if (forward.isEmpty()) { m.dx = 0; m.dy = 0; return }
        val inHome = (m.x to m.y) in home || (m.x to m.y) == door
        val target: Pair<Int, Int> = when {
            c.eaten -> home[1]
            inHome -> door.first to door.second - 1
            fright > 0 -> { val d = forward.random(); m.dx = d.first; m.dy = d.second; return }
            !chase -> listOf(1 to 1, w - 2 to 1, 1 to h - 2, w - 2 to h - 2)[c.style]
            c.style == 0 -> me.x to me.y
            c.style == 1 -> (me.x + me.dx * 4) to (me.y + me.dy * 4)
            c.style == 2 -> if (hypot((me.x - m.x).toFloat(), (me.y - m.y).toFloat()) > 7) me.x to me.y else 1 to h - 2
            else -> if (Random.nextFloat() < 0.5f) me.x to me.y else Random.nextInt(w) to Random.nextInt(h)
        }
        // из дома выпускаем только через дверь вверх
        val d = forward.minBy { (dx, dy) -> val nx = m.x + dx; val ny = m.y + dy; if (!inHome && !c.eaten && MAZE_MAP[ny][nx] == 'D') 999f else hypot((nx - target.first).toFloat(), (ny - target.second).toFloat()) }
        m.dx = d.first; m.dy = d.second
    }

    fun step(dt: Float) {
        if (!started || over) return
        if (deathPause > 0) { deathPause -= dt; if (deathPause <= 0) respawn(); return }
        mode += dt
        val chase = (mode % 27f) > 7f
        if (fright > 0) fright -= dt
        stepPlayer(dt)
        clouds.forEach { stepCloud(it, dt, chase) }
        for (c in clouds) {
            if (c.waitHome > 0 && (c.m.x to c.m.y) in home) continue
            if (hypot(c.m.px - me.px, c.m.py - me.py) < 0.6f) {
                if (c.eaten) continue
                if (fright > 0) { c.eaten = true; combo++; score += 200 * combo }
                else { lives--; deathPause = 1.2f; if (lives <= 0) over = true; return }
            }
        }
    }

    private fun respawn() {
        me.x = start.first; me.y = start.second; me.dx = 0; me.dy = 0; me.t = 0f; want = 0 to 0
        clouds.forEachIndexed { i, c -> c.m.x = c.home.first; c.m.y = c.home.second; c.m.dx = 0; c.m.dy = 0; c.m.t = 0f; c.eaten = false; c.waitHome = i * 2f }
        fright = 0f
    }
    val cleared get() = dots.isEmpty() && stars.isEmpty()
}

@Composable
fun MazeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(MazeGame()) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "maze")) }
    var lastChomp by remember { mutableIntStateOf(0) }
    GameLoop(g.started && !g.over) { dt ->
        g.step(dt); tick++
        if (g.chomps != lastChomp) { if (g.chomps > lastChomp) PacSfx.chomp(); lastChomp = g.chomps }
        if (g.cleared) { g = MazeGame(g.level + 1, g.lives, g.score).apply { started = true }; lastChomp = 0 }
        if (g.over && Hi.submit(ctx, "maze", g.score)) best = g.score
    }
    ScreenScaffold("Pac-Man", onBack) {
        ScoreBar(g.score, best, "уровень ${g.level} · " + "♥".repeat(g.lives.coerceAtLeast(0)))
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E3350)).pointerInput(g) {
                var a = Offset.Zero
                detectDragGestures(onDragStart = { a = Offset.Zero; g.started = true }) { ch, d ->
                    ch.consume(); a += d
                    if (maxOf(abs(a.x), abs(a.y)) > 14f) { g.want = if (abs(a.x) > abs(a.y)) (if (a.x > 0) 1 to 0 else -1 to 0) else (if (a.y > 0) 0 to 1 else 0 to -1); a = Offset.Zero }
                }
            }) {
                tick
                val cs = minOf(size.width / g.w, size.height / g.h)
                val ox = (size.width - cs * g.w) / 2; val oy = (size.height - cs * g.h) / 2
                fun c(x: Float, y: Float) = Offset(ox + (x + 0.5f) * cs, oy + (y + 0.5f) * cs)
                MAZE_MAP.forEachIndexed { y, row -> row.forEachIndexed { x, ch ->
                    if (ch == '#') drawRoundRect(Color(0xFF6C74B8), Offset(ox + x * cs + 1, oy + y * cs + 1), Size(cs - 2, cs - 2), CornerRadius(cs * 0.3f))
                    if (ch == 'D') drawRect(Color(0xFFE7E1F7), Offset(ox + x * cs, oy + (y + 0.45f) * cs), Size(cs, cs * 0.1f))
                } }
                g.dots.forEach { (x, y) -> drawCircle(Color(0xFFFFE8B0), cs * 0.1f, c(x.toFloat(), y.toFloat())) }
                val blink = (g.mode * 4).toInt() % 2 == 0
                g.stars.forEach { (x, y) -> drawCircle(Color(0xFFF2C94C), cs * (if (blink) 0.3f else 0.24f), c(x.toFloat(), y.toFloat())) }
                // привидения
                g.clouds.forEach { cl ->
                    if (cl.eaten) return@forEach
                    val p = c(cl.m.px, cl.m.py); val r = cs * 0.44f
                    val col = when { g.fright > 0 -> if (g.fright < 2 && blink) Color.White else Color(0xFF3D5AFE); else -> cl.color }
                    val body = androidx.compose.ui.graphics.Path().apply {
                        moveTo(p.x - r, p.y + r)
                        lineTo(p.x - r, p.y)
                        arcTo(androidx.compose.ui.geometry.Rect(p.x - r, p.y - r, p.x + r, p.y + r), 180f, 180f, false)
                        lineTo(p.x + r, p.y + r)
                        // волнистый низ
                        val wv = if (blink) 0f else r * 0.33f
                        for (k in 0 until 3) { val x0 = p.x + r - k * r * 2 / 3; lineTo(x0 - r / 3 - wv * 0, p.y + r * 0.7f); lineTo(x0 - r * 2 / 3, p.y + r) }
                        close()
                    }
                    drawPath(body, col)
                    val ex = cl.m.dx * r * 0.14f; val ey = cl.m.dy * r * 0.14f
                    listOf(-1f, 1f).forEach { k ->
                        drawCircle(Color.White, r * 0.26f, Offset(p.x + k * r * 0.36f, p.y - r * 0.15f))
                        drawCircle(if (g.fright > 0) Color(0xFFFFD1DC) else Color(0xFF1C3FAA), r * 0.13f, Offset(p.x + k * r * 0.36f + ex, p.y - r * 0.15f + ey))
                    }
                }
                // Pac-Man: жёлтый круг, рот открывается и закрывается, смотрит в сторону движения
                val p = c(g.me.px, g.me.py); val r = cs * 0.45f
                val alive = g.deathPause <= 0
                val mouth = if (alive) (kotlin.math.abs(kotlin.math.sin((g.me.px + g.me.py) * Math.PI)).toFloat() * 46f + 4f) else (1f - g.deathPause.coerceIn(0f, 1f)) * 180f
                val dirDeg = when { g.me.dx > 0 -> 0f; g.me.dx < 0 -> 180f; g.me.dy > 0 -> 90f; g.me.dy < 0 -> 270f; else -> 0f }
                // немного пиксельный: круг 13×13 «пикселей», рот — вырезанный клин
                val px = 2 * r / 13f
                val dirRad = Math.toRadians(dirDeg.toDouble())
                for (i in 0 until 13) for (j in 0 until 13) {
                    val vx = (i - 6f); val vy = (j - 6f)
                    if (vx * vx + vy * vy > 6.6f * 6.6f) continue
                    // угол пикселя относительно направления взгляда
                    var ang = Math.toDegrees(kotlin.math.atan2(vy.toDouble(), vx.toDouble()) - dirRad)
                    while (ang > 180) ang -= 360; while (ang < -180) ang += 360
                    if (vx * vx + vy * vy > 1.2f && kotlin.math.abs(ang) < mouth / 2) continue
                    val shade = if (vx * kotlin.math.cos(dirRad - 1.0) + vy * kotlin.math.sin(dirRad - 1.0) < -3.5) Color(0xFFE8B923) else Color(0xFFFFD43B)
                    drawRect(shade, Offset(p.x - r + i * px, p.y - r + j * px), Size(px + 0.6f, px + 0.6f))
                }
                if (alive) {
                    val ea = Math.toRadians((dirDeg - 65).toDouble())
                    val ex = p.x + (kotlin.math.cos(ea) * r * 0.5).toFloat(); val ey = p.y + (kotlin.math.sin(ea) * r * 0.5).toFloat()
                    drawRect(Color(0xFF1C1B22), Offset(ex - px * 0.75f, ey - px * 0.75f), Size(px * 1.5f, px * 1.5f))
                }
            }
            if (!g.started) GameOverlay("Pac-Man", "Смахивай, чтобы вести Pac-Man. Съешь все точки. Большая точка — на время привидения становятся съедобными.", "Старт") { g.started = true }
            if (g.over) GameOverlay("Поймали!", "Счёт ${g.score} · уровень ${g.level}", "Ещё раз") { g = MazeGame().apply { started = true }; lastChomp = 0 }
        }
    }
}

/**
 * «Вака-вака»: своё синтезированное «чавканье» — два коротких слога (вниз «ва», вверх «ка») по очереди на каждую точку.
 * Сделано синтезом (своё), не запись из игры.
 */
private object PacSfx {
    private var flip = false
    private var lastAt = 0L
    private fun syllable(up: Boolean): ShortArray {
        val sr = com.pank.vsevodnom.ui.random.Sfx.SR
        val n = (0.115 * sr).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        for (k in 0 until n) {
            val t = k.toDouble() / n
            // частота скользит: «ва» 480→190 Гц, «ка» 190→480 Гц
            val f = if (up) 190 + 290 * t else 480 - 290 * t
            ph += 2 * Math.PI * f / sr
            // треугольник + немного квадрата — «8-битный» тембр, мягкая атака и спад
            val tri = 2 / Math.PI * kotlin.math.asin(kotlin.math.sin(ph))
            val sq = if (kotlin.math.sin(ph) >= 0) 1.0 else -1.0
            val env = kotlin.math.min(1.0, t * 25) * kotlin.math.min(1.0, (1 - t) * 8)
            out[k] = ((tri * 0.75 + sq * 0.25) * env * 0.22 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
    private val wa by lazy { syllable(false) }
    private val ka by lazy { syllable(true) }
    fun chomp() {
        val now = System.currentTimeMillis()
        if (now - lastAt < 90) return
        lastAt = now; flip = !flip
        com.pank.vsevodnom.ui.random.Sfx.playPcm("pac", if (flip) wa else ka)
    }
}
