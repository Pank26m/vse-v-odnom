package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.abs
import kotlin.random.Random

/**
 * «Блоки» — падающие фигуры из четырёх клеток (механика как в тетрисе, своё название и оформление).
 * Поле 10×20, 7 фигур «мешком» (каждая по разу за семь), поворот с отскоками от стен, тень падения,
 * запас (hold), три следующих, уровень каждые 10 линий — быстрее. Очки: 1/2/3/4 линии = 100/300/500/800 × уровень,
 * мягкое падение +1 за клетку, бросок +2.
 */
class BlocksGame(private val rnd: Random = Random) {
    companion object {
        const val W = 10; const val H = 20
        // фигуры: клетки в своей системе (поворот вокруг центра 4×4 для I и O, 3×3 для остальных)
        val SHAPES = listOf(
            listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),   // I
            listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1),   // O
            listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),   // T
            listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),   // S
            listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),   // Z
            listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),   // J
            listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1),   // L
        )
        val COLORS = listOf(Color(0xFF39D0E8), Color(0xFFF6CF3C), Color(0xFFB065F0), Color(0xFF4FD668), Color(0xFFF2525A), Color(0xFF4A7CF2), Color(0xFFF59A33))
    }
    /** Поле: 0 — пусто, иначе номер фигуры + 1. */
    val grid = IntArray(W * H)
    var kind = 0; var rot = 0; var px = 3; var py = -1
    var hold = -1; private var holdUsed = false
    private val bag = ArrayList<Int>()
    val next = ArrayList<Int>()
    var score = 0; var lines = 0; var level = 1
    var over = false
    /** Последние убранные линии — для вспышки. */
    var cleared: List<Int> = emptyList(); var clearedAt = 0L

    init { refill(); spawn() }

    private fun refill() { while (next.size < 4) { if (bag.isEmpty()) bag.addAll((0..6).shuffled(rnd)); next += bag.removeAt(0) } }
    /** Номер текущей фигуры: жест вниз действует только на ту, с которой начался (баг 0.42 — следующая падала сразу). */
    var pieceNo = 0; private set
    private fun spawn(k: Int = next.removeAt(0)) {
        pieceNo++
        refill(); kind = k; rot = 0; px = 3; py = if (k == 0) -1 else 0; holdUsed = false
        if (!fits(kind, rot, px, py)) over = true
    }

    fun cells(k: Int = kind, r: Int = rot, x: Int = px, y: Int = py): List<Pair<Int, Int>> {
        val n = if (k == 0 || k == 1) 4 else 3
        return SHAPES[k].map { (cx, cy) ->
            var a = cx; var b = cy
            repeat(r and 3) { val t = a; a = n - 1 - b; b = t }   // поворот по часовой
            (x + a) to (y + b)
        }
    }
    fun fits(k: Int, r: Int, x: Int, y: Int) = cells(k, r, x, y).all { (cx, cy) -> cx in 0 until W && cy < H && (cy < 0 || grid[cy * W + cx] == 0) }

    fun move(dx: Int): Boolean { if (over) return false; if (fits(kind, rot, px + dx, py)) { px += dx; return true }; return false }
    /** Поворот с «отскоками» от стен и пола (как в современных версиях — пробуем сдвиги). */
    fun rotate(dir: Int = 1): Boolean {
        if (over || kind == 1) return false
        val nr = (rot + dir + 4) % 4
        for ((dx, dy) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, -2 to 0, 2 to 0, -1 to -1, 1 to -1)) if (fits(kind, nr, px + dx, py + dy)) { rot = nr; px += dx; py += dy; return true }
        return false
    }
    /** Шаг вниз; false — фигура легла. */
    fun down(soft: Boolean = false): Boolean {
        if (over) return false
        if (fits(kind, rot, px, py + 1)) { py++; if (soft) score += 1; return true }
        lock(); return false
    }
    fun drop() { if (over) return; var n = 0; while (fits(kind, rot, px, py + 1)) { py++; n++ }; score += n * 2; lock() }
    fun ghostY(): Int { var y = py; while (fits(kind, rot, px, y + 1)) y++; return y }
    fun swapHold() {
        if (holdUsed || over) return
        val cur = kind
        if (hold < 0) { hold = cur; spawn() } else { val h = hold; hold = cur; spawn(h) }
        holdUsed = true
    }
    private fun lock() {
        cells().forEach { (x, y) -> if (y < 0) { over = true; return } ; grid[y * W + x] = kind + 1 }
        val full = (0 until H).filter { y -> (0 until W).all { grid[y * W + it] != 0 } }
        if (full.isNotEmpty()) {
            cleared = full; clearedAt = System.currentTimeMillis()
            val keep = (0 until H).filter { it !in full }
            val old = grid.copyOf(); grid.fill(0)
            keep.reversed().forEachIndexed { i, y -> val ny = H - 1 - i; for (x in 0 until W) grid[ny * W + x] = old[y * W + x] }
            score += listOf(0, 100, 300, 500, 800)[full.size.coerceAtMost(4)] * level
            lines += full.size; level = 1 + lines / 10
        }
        spawn()
    }
    /** Время между шагами вниз (с), по уровню. */
    val period get() = (0.8 * Math.pow(0.85, (level - 1).toDouble())).coerceAtLeast(0.06).toFloat()
}

@Composable
fun BlocksScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(BlocksGame()) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var acc by remember { mutableFloatStateOf(0f) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "blocks")) }
    GameLoop(started && !over && !paused) { dt ->
        acc += dt
        if (acc >= g.period) { acc = 0f; g.down(); tick++ }
        if (g.over) { over = true; if (Hi.submit(ctx, "blocks", g.score)) best = g.score }
    }
    fun act(f: () -> Unit) { if (!started || over || paused) return; f(); tick++; if (g.over) { over = true; if (Hi.submit(ctx, "blocks", g.score)) best = g.score } }
    val bg = Color(0xFF0E1324)
    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // верхняя полоса: назад, счёт, уровень, линии, рекорд, пауза
            key(tick) {
                Row(Modifier.fillMaxWidth().padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("←", color = Color.White, fontSize = 24.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(10.dp, 2.dp))
                    @Composable fun Stat(t: String, v: String, m: Modifier) = Column(m.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(6.dp, 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t, color = Color(0xFF8E97B5), fontSize = 10.sp, maxLines = 1); Text(v, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Stat("Очки", "${g.score}", Modifier.weight(1.3f)); Stat("Ур.", "${g.level}", Modifier.weight(0.8f)); Stat("Линии", "${g.lines}", Modifier.weight(1f)); Stat("Рекорд", "${maxOf(best, g.score)}", Modifier.weight(1.3f))
                    if (started && !over) Text(if (paused) "▶" else "❚❚", color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { paused = !paused }.padding(10.dp, 4.dp))
                }
                // запас и следующие фигуры — тонкой строкой
                Row(Modifier.fillMaxWidth().padding(10.dp, 0.dp, 10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(70.dp).height(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).clickable { act { g.swapHold() } }, contentAlignment = Alignment.Center) {
                        if (g.hold >= 0) Canvas(Modifier.size(56.dp, 32.dp)) { preview(g.hold) } else Text("запас", color = Color(0xFF59607A), fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("дальше", color = Color(0xFF8E97B5), fontSize = 11.sp)
                    g.next.take(3).forEachIndexed { i, k -> Canvas(Modifier.size(if (i == 0) 52.dp else 40.dp, if (i == 0) 32.dp else 26.dp)) { preview(k) } }
                }
            }
            // поле — на всю оставшуюся высоту: тап — поворот, тянуть влево/вправо — сдвиг, вниз — ускорить, резкий взмах вниз — бросить
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
                val cell = minOf(maxWidth / BlocksGame.W, maxHeight / BlocksGame.H)
                Canvas(Modifier.size(cell * BlocksGame.W, cell * BlocksGame.H).clip(RoundedCornerShape(8.dp)).background(Color(0xFF070A14))
                    .pointerInput(g, started, paused) {
                        awaitEachGesture {
                            val d = awaitFirstDown(); val c = size.width / BlocksGame.W.toFloat()
                            val piece = g.pieceNo
                            var ax = 0f; var ay = 0f; var moved = false; val t0 = System.currentTimeMillis(); var totalY = 0f
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                if (!ch.pressed) break
                                val dl = ch.position - ch.previousPosition; ch.consume()
                                // фигура легла посреди жеста — остаток жеста новую не трогает
                                if (g.pieceNo != piece) { moved = true; continue }
                                ax += dl.x; ay += dl.y; totalY += dl.y
                                while (ax > c * 0.9f) { act { g.move(1) }; ax -= c * 0.9f; moved = true }
                                while (ax < -c * 0.9f) { act { g.move(-1) }; ax += c * 0.9f; moved = true }
                                while (ay > c && g.pieceNo == piece) { act { g.down(soft = true) }; ay -= c; moved = true }
                            }
                            val dtm = System.currentTimeMillis() - t0
                            when {
                                g.pieceNo != piece -> {}
                                totalY > c * 3 && dtm < 220 -> act { g.drop() }   // взмах вниз — бросок
                                !moved && dtm < 300 -> act { g.rotate() }           // тап — поворот
                            }
                        }
                    }) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val cs = size.width / BlocksGame.W
                    for (x in 1 until BlocksGame.W) drawLine(Color(0x14FFFFFF), Offset(x * cs, 0f), Offset(x * cs, size.height), 1f)
                    for (y in 1 until BlocksGame.H) drawLine(Color(0x14FFFFFF), Offset(0f, y * cs), Offset(size.width, y * cs), 1f)
                    val flash = System.currentTimeMillis() - g.clearedAt < 250
                    for (y in 0 until BlocksGame.H) for (x in 0 until BlocksGame.W) { val v = g.grid[y * BlocksGame.W + x]; if (v != 0) block(x * cs, y * cs, cs, BlocksGame.COLORS[v - 1]) }
                    if (flash) g.cleared.forEach { y -> drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, y * cs), Size(size.width, cs)) }
                    if (!g.over) {
                        val gy = g.ghostY()
                        g.cells(y = gy).forEach { (x, y) -> if (y >= 0) drawRoundRect(BlocksGame.COLORS[g.kind].copy(alpha = 0.35f), Offset(x * cs + 2, y * cs + 2), Size(cs - 4, cs - 4), CornerRadius(cs * 0.15f), style = Stroke(2f)) }
                        g.cells().forEach { (x, y) -> if (y >= 0) block(x * cs, y * cs, cs, BlocksGame.COLORS[g.kind]) }
                    }
                }
            }
        }
        if (!started) GameOverlay("Блоки", "Тап по полю — повернуть. Тяни влево/вправо — двигать, вниз — ускорить, резкий взмах вниз — бросить. «Запас» — отложить фигуру.", "Старт") { started = true }
        if (over) GameOverlay("Поле заполнено", "Очки ${g.score} · линий ${g.lines} · уровень ${g.level} · рекорд $best", "Ещё раз") { g = BlocksGame(); over = false; acc = 0f; started = true; tick++ }
    }
}

/** Блок с объёмом: светлая грань сверху-слева, тень снизу-справа, блик. */
private fun DrawScope.block(x: Float, y: Float, cs: Float, c: Color) {
    val p = cs * 0.06f
    drawRoundRect(Brush.linearGradient(listOf(c, Color(c.red * 0.7f, c.green * 0.7f, c.blue * 0.7f)), Offset(x, y), Offset(x + cs, y + cs)), Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.16f))
    drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(x + cs * 0.18f, y + cs * 0.14f), Size(cs * 0.5f, cs * 0.16f), CornerRadius(cs * 0.08f))
    drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.16f), style = Stroke(cs * 0.05f))
}

/** Маленькая фигура в окошке «дальше»/«запас». */
private fun DrawScope.preview(k: Int) {
    val cells = BlocksGame.SHAPES[k]
    val minX = cells.minOf { it.first }; val maxX = cells.maxOf { it.first }; val minY = cells.minOf { it.second }; val maxY = cells.maxOf { it.second }
    val cs = minOf(size.width / 4.5f, size.height / 2.5f)
    val ox = (size.width - (maxX - minX + 1) * cs) / 2 - minX * cs; val oy = (size.height - (maxY - minY + 1) * cs) / 2 - minY * cs
    cells.forEach { (x, y) -> block(ox + x * cs, oy + y * cs, cs, BlocksGame.COLORS[k]) }
}
