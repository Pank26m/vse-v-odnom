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
        m.t += sp * dt
        while (m.t >= 1f) {
            m.t -= 1f; m.x += m.dx; m.y += m.dy
            eat(m.x to m.y)
            if (want != (0 to 0) && !wall(m.x + want.first, m.y + want.second, false)) { m.dx = want.first; m.dy = want.second }
            else if (wall(m.x + m.dx, m.y + m.dy, false)) { m.dx = 0; m.dy = 0; m.t = 0f; break }
        }
    }

    private fun eat(p: Pair<Int, Int>) {
        if (dots.remove(p)) score += 10
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
    GameLoop(g.started && !g.over) { dt ->
        g.step(dt); tick++
        if (g.cleared) g = MazeGame(g.level + 1, g.lives, g.score).apply { started = true }
        if (g.over && Hi.submit(ctx, "maze", g.score)) best = g.score
    }
    ScreenScaffold("Лабиринт", onBack) {
        ScoreBar(g.score, best, "уровень ${g.level} · " + "♥".repeat(g.lives.coerceAtLeast(0)))
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E3350)).pointerInput(g) {
                var a = Offset.Zero
                detectDragGestures(onDragStart = { a = Offset.Zero; g.started = true }) { ch, d ->
                    ch.consume(); a += d
                    if (maxOf(abs(a.x), abs(a.y)) > 24f) { g.want = if (abs(a.x) > abs(a.y)) (if (a.x > 0) 1 to 0 else -1 to 0) else (if (a.y > 0) 0 to 1 else 0 to -1); a = Offset.Zero }
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
                g.dots.forEach { (x, y) -> drawCircle(Color(0xFFF8DDE6), cs * 0.1f, c(x.toFloat(), y.toFloat())) }
                val blink = (g.mode * 4).toInt() % 2 == 0
                g.stars.forEach { (x, y) -> drawCircle(Color(0xFFF2C94C), cs * (if (blink) 0.3f else 0.24f), c(x.toFloat(), y.toFloat())) }
                // тучки
                g.clouds.forEach { cl ->
                    val p = c(cl.m.px, cl.m.py); val r = cs * 0.42f
                    val col = when { cl.eaten -> Color.Transparent; g.fright > 0 -> if (g.fright < 2 && blink) Color.White else Color(0xFFB8C7E8); else -> cl.color }
                    if (!cl.eaten) {
                        drawCircle(col, r * 0.62f, Offset(p.x - r * 0.4f, p.y + r * 0.1f)); drawCircle(col, r * 0.62f, Offset(p.x + r * 0.4f, p.y + r * 0.1f)); drawCircle(col, r * 0.72f, Offset(p.x, p.y - r * 0.2f))
                        drawRoundRect(col, Offset(p.x - r * 0.95f, p.y), Size(r * 1.9f, r * 0.55f), CornerRadius(r * 0.3f))
                    }
                    val ex = cl.m.dx * r * 0.12f; val ey = cl.m.dy * r * 0.12f
                    listOf(-1f, 1f).forEach { k -> drawCircle(Color.White, r * 0.2f, Offset(p.x + k * r * 0.3f, p.y - r * 0.1f)); drawCircle(Color(0xFF1C1B22), r * 0.1f, Offset(p.x + k * r * 0.3f + ex, p.y - r * 0.1f + ey)) }
                }
                // ёжик-игрок
                val p = c(g.me.px, g.me.py); val r = cs * 0.42f
                val alive = g.deathPause <= 0
                val col = if (alive) Color(0xFF8BD3A4) else Color(0xFF8BD3A4).copy(alpha = g.deathPause.coerceIn(0f, 1f))
                for (k in 0 until 8) { val a = Math.toRadians(k * 45.0 + 22); drawLine(Color(0xFF3E8C5E).copy(alpha = col.alpha), p, Offset(p.x + (kotlin.math.cos(a) * r * 1.15).toFloat(), p.y + (kotlin.math.sin(a) * r * 1.15).toFloat()), cs * 0.12f) }
                drawCircle(col, r, p)
                drawCircle(Color.White, r * 0.28f, Offset(p.x + g.me.dx * r * 0.35f, p.y + g.me.dy * r * 0.35f - r * 0.15f))
                drawCircle(Color(0xFF1C1B22), r * 0.14f, Offset(p.x + g.me.dx * r * 0.42f, p.y + g.me.dy * r * 0.42f - r * 0.15f))
            }
            if (!g.started) GameOverlay("Лабиринт", "Смахивай, чтобы вести ёжика. Собери все ягоды. Звёздочка — на время тучки становятся съедобными.", "Старт") { g.started = true }
            if (g.over) GameOverlay("Тучки победили", "Счёт ${g.score} · уровень ${g.level}", "Ещё раз") { g = MazeGame().apply { started = true } }
        }
    }
}
