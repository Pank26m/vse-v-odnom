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
 * три следующих, уровень каждые 10 линий — быстрее. Очки: 1/2/3/4 линии = 100/300/500/800 × уровень,
 * ускоренное падение +1 за клетку. С 0.45: «запаса» нет, мгновенного броска нет — взмах вниз только ускоряет
 * падение (сильнее взмах — быстрее), фигура видимо летит, а не телепортируется. Скины и звук — в настройках.
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
    /** Сколько фигур легло (для звука укладки). */
    var locks = 0; private set

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
        locks++
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

/** Скин «Блоков»: фон, поле, сетка, палитра и вид клетки. */
private data class BlkSkin(val key: String, val title: String, val bg: Color, val field: Color, val grid: Color, val text: Color, val sub: Color,
                           val colors: List<Color>, val style: Int)   // style: 0 объём, 1 ретро-кирпич, 2 ЖК-пиксель, 3 неон, 4 пастель
private val SKINS = listOf(
    BlkSkin("modern", "Современный", Color(0xFF0E1324), Color(0xFF070A14), Color(0x14FFFFFF), Color.White, Color(0xFF8E97B5), BlocksGame.COLORS, 0),
    BlkSkin("retro", "Аркада 80-х", Color(0xFF000000), Color(0xFF000000), Color(0x00000000), Color(0xFFE8E8E8), Color(0xFF9A9A9A),
        listOf(Color(0xFFE8E8E8), Color(0xFFB0B0B0), Color(0xFFD04848), Color(0xFF48A048), Color(0xFF4878D0), Color(0xFFD0A048), Color(0xFFA048C8)), 1),
    BlkSkin("lcd", "Зелёный экран", Color(0xFF0B1A0B), Color(0xFF9BB07A), Color(0x1A1F3A12), Color(0xFFB8E0A0), Color(0xFF6E9A58),
        List(7) { Color(0xFF1F3A12) }, 2),
    BlkSkin("neon", "Неон", Color(0xFF05010D), Color(0xFF07021A), Color(0x22A060FF), Color.White, Color(0xFF9A88C8),
        listOf(Color(0xFF00F0FF), Color(0xFFFFE600), Color(0xFFD13BFF), Color(0xFF39FF6A), Color(0xFFFF3B6B), Color(0xFF3B7BFF), Color(0xFFFF9B2E)), 3),
    BlkSkin("pastel", "Пастель", Color(0xFFF4F1EC), Color(0xFFFFFFFF), Color(0x14000000), Color(0xFF2B2B33), Color(0xFF8A8A96),
        listOf(Color(0xFF8ED8E6), Color(0xFFF5DD8A), Color(0xFFC9A6F0), Color(0xFF9BDDA6), Color(0xFFF1A1A6), Color(0xFF9DB5F2), Color(0xFFF4BE8A)), 4),
)

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
    val prefs = remember { ctx.getSharedPreferences("blocks", android.content.Context.MODE_PRIVATE) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var skin by remember { mutableStateOf(SKINS.firstOrNull { it.key == prefs.getString("skin", "modern") } ?: SKINS[0]) }
    var settings by remember { mutableStateOf(false) }
    // ускоренное падение после взмаха вниз: клеток в секунду (0 — нет); действует до укладки этой фигуры
    var boost by remember { mutableFloatStateOf(0f) }
    var boostPiece by remember { mutableIntStateOf(-1) }
    var boostAcc by remember { mutableFloatStateOf(0f) }
    var lastLocks by remember { mutableIntStateOf(0) }
    fun sfx(f: () -> Unit) { if (sound) f() }
    fun afterStep() {
        if (g.locks != lastLocks) {
            lastLocks = g.locks
            if (System.currentTimeMillis() - g.clearedAt < 60) sfx { GameSfx.blkLine(g.cleared.size) } else sfx { GameSfx.blkLock() }
        }
    }
    GameLoop(started && !over && !paused && !settings) { dt ->
        if (boost > 0f && boostPiece == g.pieceNo) {
            boostAcc += dt * boost
            while (boostAcc >= 1f && boostPiece == g.pieceNo) { boostAcc -= 1f; g.down(soft = true); afterStep() }
            acc = 0f; tick++
        } else {
            boost = 0f
            acc += dt
            if (acc >= g.period) { acc = 0f; g.down(); afterStep(); tick++ }
        }
        if (g.over) { over = true; if (Hi.submit(ctx, "blocks", g.score)) best = g.score }
    }
    fun act(f: () -> Unit) { if (!started || over || paused) return; f(); afterStep(); tick++; if (g.over) { over = true; if (Hi.submit(ctx, "blocks", g.score)) best = g.score } }
    val sk = skin
    Box(Modifier.fillMaxSize().background(sk.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // верхняя полоса: назад, счёт, уровень, линии, рекорд, пауза, настройки
            key(tick) {
                Row(Modifier.fillMaxWidth().padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("←", color = sk.text, fontSize = 24.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(10.dp, 2.dp))
                    @Composable fun Stat(t: String, v: String, m: Modifier) = Column(m.clip(RoundedCornerShape(10.dp)).background(sk.text.copy(alpha = 0.08f)).padding(6.dp, 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t, color = sk.sub, fontSize = 10.sp, maxLines = 1); Text(v, color = sk.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Stat("Очки", "${g.score}", Modifier.weight(1.3f)); Stat("Ур.", "${g.level}", Modifier.weight(0.8f)); Stat("Линии", "${g.lines}", Modifier.weight(1f)); Stat("Рекорд", "${maxOf(best, g.score)}", Modifier.weight(1.3f))
                    if (started && !over) Text(if (paused) "▶" else "❚❚", color = sk.text, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { paused = !paused }.padding(10.dp, 4.dp))
                    Text("⚙", color = sk.text, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { settings = true }.padding(8.dp, 2.dp))
                }
                // следующие фигуры — тонкой строкой
                Row(Modifier.fillMaxWidth().padding(10.dp, 0.dp, 10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Text("дальше", color = sk.sub, fontSize = 11.sp)
                    g.next.take(3).forEachIndexed { i, k -> Canvas(Modifier.size(if (i == 0) 52.dp else 40.dp, if (i == 0) 32.dp else 26.dp)) { preview(k, sk) } }
                }
            }
            // поле: тап — поворот, тянуть влево/вправо — сдвиг, вниз — вести фигуру пальцем; взмах вниз — ускорить падение
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
                val cell = minOf(maxWidth / BlocksGame.W, maxHeight / BlocksGame.H)
                Canvas(Modifier.size(cell * BlocksGame.W, cell * BlocksGame.H).clip(RoundedCornerShape(if (sk.style == 1 || sk.style == 2) 2.dp else 8.dp)).background(sk.field)
                    .pointerInput(g, started, paused) {
                        awaitEachGesture {
                            val d = awaitFirstDown(); val c = size.width / BlocksGame.W.toFloat()
                            val piece = g.pieceNo
                            var ax = 0f; var ay = 0f; var moved = false; val t0 = System.currentTimeMillis(); var totalY = 0f; var totalX = 0f
                            // скорость пальца по последним событиям (пикс/мс)
                            var vy = 0f; var lastT = t0
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                if (!ch.pressed) break
                                val dl = ch.position - ch.previousPosition; ch.consume()
                                val now = System.currentTimeMillis(); val dtm = (now - lastT).coerceAtLeast(1); lastT = now
                                vy = vy * 0.6f + (dl.y / dtm) * 0.4f
                                // фигура легла посреди жеста — остаток жеста новую не трогает
                                if (g.pieceNo != piece) { moved = true; continue }
                                ax += dl.x; ay += dl.y; totalY += dl.y; totalX += abs(dl.x)
                                while (ax > c * 0.9f) { act { if (g.move(1)) sfx { GameSfx.blkMove() } }; ax -= c * 0.9f; moved = true }
                                while (ax < -c * 0.9f) { act { if (g.move(-1)) sfx { GameSfx.blkMove() } }; ax += c * 0.9f; moved = true }
                                while (ay > c && g.pieceNo == piece) { act { g.down(soft = true) }; ay -= c; moved = true }
                            }
                            val dtm = System.currentTimeMillis() - t0
                            when {
                                g.pieceNo != piece -> {}
                                // взмах вниз: ускоряем падение пропорционально силе взмаха — без телепорта
                                totalY > c * 0.8f && totalY > totalX && vy > 0.6f -> { boost = (vy * 30f).coerceIn(14f, 70f); boostPiece = piece; boostAcc = 0f }
                                !moved && dtm < 300 -> act { if (g.rotate()) sfx { GameSfx.blkTurn() } }   // тап — поворот
                            }
                        }
                    }) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val cs = size.width / BlocksGame.W
                    if (sk.grid.alpha > 0f) {
                        for (x in 1 until BlocksGame.W) drawLine(sk.grid, Offset(x * cs, 0f), Offset(x * cs, size.height), 1f)
                        for (y in 1 until BlocksGame.H) drawLine(sk.grid, Offset(0f, y * cs), Offset(size.width, y * cs), 1f)
                    }
                    if (sk.style == 2) for (y in 0 until BlocksGame.H) for (x in 0 until BlocksGame.W) drawRect(Color(0x0F1F3A12), Offset(x * cs + cs * 0.1f, y * cs + cs * 0.1f), Size(cs * 0.8f, cs * 0.8f))
                    val flash = System.currentTimeMillis() - g.clearedAt < 250
                    for (y in 0 until BlocksGame.H) for (x in 0 until BlocksGame.W) { val v = g.grid[y * BlocksGame.W + x]; if (v != 0) block(x * cs, y * cs, cs, sk.colors[v - 1], sk.style) }
                    if (flash) g.cleared.forEach { y -> drawRect((if (sk.style == 4) Color.Black else Color.White).copy(alpha = 0.3f), Offset(0f, y * cs), Size(size.width, cs)) }
                    if (!g.over) {
                        val gy = g.ghostY()
                        g.cells(y = gy).forEach { (x, y) -> if (y >= 0) drawRoundRect(sk.colors[g.kind].copy(alpha = 0.4f), Offset(x * cs + 2, y * cs + 2), Size(cs - 4, cs - 4), CornerRadius(if (sk.style == 1 || sk.style == 2) 0f else cs * 0.15f), style = Stroke(2f)) }
                        g.cells().forEach { (x, y) -> if (y >= 0) block(x * cs, y * cs, cs, sk.colors[g.kind], sk.style) }
                    }
                }
            }
        }
        if (!started) GameOverlay("Блоки", "Тап по полю — повернуть. Тяни влево/вправо — двигать, вниз — вести фигуру. Взмах вниз ускоряет падение: чем сильнее, тем быстрее.", "Старт") { started = true }
        if (over) GameOverlay("Поле заполнено", "Очки ${g.score} · линий ${g.lines} · уровень ${g.level} · рекорд $best", "Ещё раз") { g = BlocksGame(); over = false; acc = 0f; boost = 0f; lastLocks = 0; started = true; tick++ }
        if (settings) androidx.compose.material3.AlertDialog(onDismissRequest = { settings = false }, title = { Text("Настройки") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Звук", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(sound, { sound = it; prefs.edit().putBoolean("sound", it).apply() })
                }
                Text("Оформление", style = MaterialTheme.typography.labelLarge)
                SKINS.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s0 ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(s0.bg).clickable { skin = s0; prefs.edit().putString("skin", s0.key).apply() }
                                .then(if (s0 == skin) Modifier.background(Color.Transparent) else Modifier).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Canvas(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(s0.field)) {
                                    val c = size.height / 3
                                    listOf(0 to 2, 1 to 2, 2 to 2, 1 to 1, 4 to 2, 4 to 1, 5 to 2).forEachIndexed { i, (x, y) -> block(x * c + 2, y * c, c, s0.colors[i % 7], s0.style) }
                                }
                                Text((if (s0 == skin) "✓ " else "") + s0.title, color = s0.text, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = { androidx.compose.material3.TextButton({ settings = false }) { Text("Готово") } })
    }
}

/** Клетка в стиле скина: 0 объём, 1 ретро-кирпич, 2 ЖК-пиксель, 3 неон, 4 пастель. */
private fun DrawScope.block(x: Float, y: Float, cs: Float, c: Color, style: Int = 0) {
    val p = cs * 0.06f
    when (style) {
        1 -> { // аркада 80-х: плоский квадрат с фаской
            drawRect(c, Offset(x + 1, y + 1), Size(cs - 2, cs - 2))
            drawRect(Color.White.copy(alpha = 0.45f), Offset(x + 1, y + 1), Size(cs - 2, cs * 0.12f)); drawRect(Color.White.copy(alpha = 0.45f), Offset(x + 1, y + 1), Size(cs * 0.12f, cs - 2))
            drawRect(Color.Black.copy(alpha = 0.45f), Offset(x + 1, y + cs - 1 - cs * 0.12f), Size(cs - 2, cs * 0.12f)); drawRect(Color.Black.copy(alpha = 0.45f), Offset(x + cs - 1 - cs * 0.12f, y + 1), Size(cs * 0.12f, cs - 2))
        }
        2 -> { // зелёный ЖК: тёмный пиксель с рамкой и точкой в центре
            drawRect(c, Offset(x + cs * 0.06f, y + cs * 0.06f), Size(cs * 0.88f, cs * 0.88f), style = Stroke(cs * 0.12f))
            drawRect(c, Offset(x + cs * 0.3f, y + cs * 0.3f), Size(cs * 0.4f, cs * 0.4f))
        }
        3 -> { // неон: светящийся контур
            drawRoundRect(c.copy(alpha = 0.18f), Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.18f))
            drawRoundRect(c.copy(alpha = 0.35f), Offset(x + p * 0.4f, y + p * 0.4f), Size(cs - p * 0.8f, cs - p * 0.8f), CornerRadius(cs * 0.2f), style = Stroke(cs * 0.14f))
            drawRoundRect(c, Offset(x + p * 1.5f, y + p * 1.5f), Size(cs - 3 * p, cs - 3 * p), CornerRadius(cs * 0.16f), style = Stroke(cs * 0.06f))
        }
        4 -> { // пастель: мягкий плоский кубик
            drawRoundRect(c, Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.24f))
            drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(x + cs * 0.2f, y + cs * 0.16f), Size(cs * 0.36f, cs * 0.12f), CornerRadius(cs * 0.06f))
        }
        else -> { // объём
            drawRoundRect(Brush.linearGradient(listOf(c, Color(c.red * 0.7f, c.green * 0.7f, c.blue * 0.7f)), Offset(x, y), Offset(x + cs, y + cs)), Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.16f))
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(x + cs * 0.18f, y + cs * 0.14f), Size(cs * 0.5f, cs * 0.16f), CornerRadius(cs * 0.08f))
            drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(x + p, y + p), Size(cs - 2 * p, cs - 2 * p), CornerRadius(cs * 0.16f), style = Stroke(cs * 0.05f))
        }
    }
}

/** Маленькая фигура в окошке «дальше». */
private fun DrawScope.preview(k: Int, sk: BlkSkin) {
    val cells = BlocksGame.SHAPES[k]
    val minX = cells.minOf { it.first }; val maxX = cells.maxOf { it.first }; val minY = cells.minOf { it.second }; val maxY = cells.maxOf { it.second }
    val cs = minOf(size.width / 4.5f, size.height / 2.5f)
    val ox = (size.width - (maxX - minX + 1) * cs) / 2 - minX * cs; val oy = (size.height - (maxY - minY + 1) * cs) / 2 - minY * cs
    cells.forEach { (x, y) -> block(ox + x * cs, oy + y * cs, cs, sk.colors[k], sk.style) }
}
