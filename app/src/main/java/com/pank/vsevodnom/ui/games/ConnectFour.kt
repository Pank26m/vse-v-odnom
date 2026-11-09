package com.pank.vsevodnom.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * «Четыре в ряд»: поле 7×6, фишка падает вниз столбца. Клетка: 0 пусто, 1 ты, 2 бот.
 * Бот — перебор с отсечением (негамакс, альфа-бета) на глубину по уровню.
 */
object C4 {
    const val W = 7; const val H = 6
    fun empty() = IntArray(W * H)
    /** Строка, куда упадёт фишка в столбце c (0 — верх), или -1, если столбец полон. */
    fun dropRow(b: IntArray, c: Int): Int { for (r in H - 1 downTo 0) if (b[r * W + c] == 0) return r; return -1 }
    fun canDrop(b: IntArray, c: Int) = b[c] == 0
    fun full(b: IntArray) = (0 until W).none { canDrop(b, it) }

    /** Победитель и его четвёрка (индексы клеток) или null. */
    fun winner(b: IntArray): Pair<Int, List<Int>>? {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until H) for (c in 0 until W) {
            val p = b[r * W + c]; if (p == 0) continue
            for ((dr, dc) in dirs) {
                val cells = (0 until 4).map { r + dr * it to c + dc * it }
                if (cells.all { (rr, cc) -> rr in 0 until H && cc in 0 until W && b[rr * W + cc] == p }) return p to cells.map { (rr, cc) -> rr * W + cc }
            }
        }
        return null
    }

    /** Оценка позиции для игрока p: окна по 4 клетки, центр ценнее. */
    private fun eval(b: IntArray, p: Int): Int {
        val o = 3 - p; var s = 0
        for (r in 0 until H) if (b[r * W + 3] == p) s += 3 else if (b[r * W + 3] == o) s -= 3
        fun win(cells: IntArray) {
            var mine = 0; var their = 0; var free = 0
            for (i in cells) when (b[i]) { p -> mine++; o -> their++; else -> free++ }
            if (their == 0) s += when (mine) { 3 -> 5; 2 -> 2; else -> 0 }
            if (mine == 0) s -= when (their) { 3 -> 6; 2 -> 2; else -> 0 }
        }
        for (r in 0 until H) for (c in 0 until W) {
            if (c + 3 < W) win(IntArray(4) { r * W + c + it })
            if (r + 3 < H) win(IntArray(4) { (r + it) * W + c })
            if (r + 3 < H && c + 3 < W) win(IntArray(4) { (r + it) * W + c + it })
            if (r + 3 < H && c - 3 >= 0) win(IntArray(4) { (r + it) * W + c - it })
        }
        return s
    }

    private val ORDER = intArrayOf(3, 2, 4, 1, 5, 0, 6)

    private fun negamax(b: IntArray, p: Int, depth: Int, a0: Int, beta: Int): Int {
        winner(b)?.let { return if (it.first == p) 100_000 + depth else -100_000 - depth }
        if (full(b)) return 0
        if (depth == 0) return eval(b, p)
        var a = a0; var best = Int.MIN_VALUE + 1
        for (c in ORDER) {
            val r = dropRow(b, c); if (r < 0) continue
            b[r * W + c] = p
            val v = -negamax(b, 3 - p, depth - 1, -beta, -a)
            b[r * W + c] = 0
            if (v > best) best = v
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    /** Лучший столбец для p. level: 0 лёгкий, 1 средний, 2 сильный. */
    fun best(b0: IntArray, p: Int, level: Int, rnd: Random = Random): Int {
        val b = b0.copyOf()
        val free = (0 until W).filter { canDrop(b, it) }
        if (level == 0 && rnd.nextFloat() < 0.35f) return free.random(rnd)
        val depth = when (level) { 0 -> 2; 1 -> 4; else -> 7 }
        var bestC = free.first(); var bestV = Int.MIN_VALUE
        for (c in ORDER) {
            val r = dropRow(b, c); if (r < 0) continue
            b[r * W + c] = p
            val v = -negamax(b, 3 - p, depth - 1, -1_000_000, 1_000_000) + rnd.nextInt(2)
            b[r * W + c] = 0
            if (v > bestV) { bestV = v; bestC = c }
        }
        return bestC
    }
}

private val C4_ME = Color(0xFFE5485D)
private val C4_BOT = Color(0xFFF2C94C)
private val C4_BOARD = Color(0xFF3E6FD6)

@Composable
fun ConnectFourScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("c4", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var b by remember { mutableStateOf(C4.empty()) }
    var turn by remember { mutableIntStateOf(1) }
    var botFirst by remember { mutableStateOf(false) }
    var score by remember { mutableStateOf(Triple(prefs.getInt("w", 0), prefs.getInt("l", 0), prefs.getInt("d", 0))) }
    var last by remember { mutableIntStateOf(-1) }
    val drop = remember { Animatable(1f) }
    val win = remember(b) { C4.winner(b) }
    val draw = win == null && C4.full(b)
    var counted by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    fun reset() { b = C4.empty(); last = -1; counted = false; turn = if (botFirst) 2 else 1 }
    suspend fun put(c: Int) {
        val r = C4.dropRow(b, c); if (r < 0) return
        drop.snapTo(0f)
        b = b.copyOf().also { it[r * C4.W + c] = turn }; last = r * C4.W + c
        turn = 3 - turn
        drop.animateTo(1f, tween(90 + r * 45))
    }
    LaunchedEffect(win, draw) {
        if (counted || (win == null && !draw)) return@LaunchedEffect
        counted = true
        score = when { win?.first == 1 -> score.copy(first = score.first + 1); win?.first == 2 -> score.copy(second = score.second + 1); else -> score.copy(third = score.third + 1) }
        prefs.edit().putInt("w", score.first).putInt("l", score.second).putInt("d", score.third).apply()
    }
    LaunchedEffect(b, turn) {
        if (turn == 2 && win == null && !draw) {
            delay(250)
            val c = withContext(Dispatchers.Default) { C4.best(b, 2, level) }
            scope.launch { put(c) }   // не внутри эффекта: смена b отменила бы анимацию падения
        }
    }
    ScreenScaffold("Четыре в ряд", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Лёгкий", "Средний", "Сильный").forEachIndexed { i, t -> Chip(t, level == i, { level = i; prefs.edit().putInt("level", i).apply(); reset() }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Я хожу первым", !botFirst, { botFirst = false; reset() }); Chip("Бот первым", botFirst, { botFirst = true; reset() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("победы" to score.first, "ничьи" to score.third, "поражения" to score.second).forEach { (k, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$v", style = MaterialTheme.typography.headlineMedium); Text(k, style = MaterialTheme.typography.labelLarge, color = Pal.Muted) }
                }
            }
            Text(when { win?.first == 1 -> "Ты собрал четыре!"; win?.first == 2 -> "Бот собрал четыре"; draw -> "Ничья — поле заполнено"; turn == 2 -> "Бот думает…"; else -> "Твой ход: нажми на столбец" },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Box(Modifier.fillMaxWidth().aspectRatio(C4.W / (C4.H + 0.2f)).clip(RoundedCornerShape(20.dp))) {
                Canvas(Modifier.fillMaxSize().pointerInput(b, turn, win) {
                    detectTapGestures { p ->
                        if (turn != 1 || win != null || draw) return@detectTapGestures
                        val c = (p.x / (size.width / C4.W)).toInt().coerceIn(0, C4.W - 1)
                        if (C4.canDrop(b, c)) scope.launch { put(c) }
                    }
                }) {
                    val cw = size.width / C4.W; val ch = size.height / C4.H
                    val rad = minOf(cw, ch) * 0.38f
                    drawRoundRect(C4_BOARD, cornerRadius = CornerRadius(40f))
                    val hl = win?.second.orEmpty().toSet()
                    for (r in 0 until C4.H) for (c in 0 until C4.W) {
                        val i = r * C4.W + c
                        val cx = cw * (c + 0.5f); val cy = ch * (r + 0.5f)
                        drawCircle(Color(0xFF2A55B5), rad + 3f, Offset(cx, cy + 3f))
                        drawCircle(Color.White, rad, Offset(cx, cy))
                        val v = b[i]; if (v == 0) continue
                        // последняя фишка падает сверху
                        val y = if (i == last) -rad + (cy + rad) * drop.value else cy
                        val col = if (v == 1) C4_ME else C4_BOT
                        drawCircle(col, rad, Offset(cx, y))
                        drawCircle(Color.Black.copy(alpha = 0.12f), rad * 0.72f, Offset(cx, y), style = Stroke(rad * 0.1f))
                        if (i in hl) drawCircle(Color.White, rad * 0.3f, Offset(cx, y))
                    }
                }
                if (win?.first == 1) VictoryOverlay("Четыре в ряд!", "Бот (${listOf("лёгкий", "средний", "сильный")[level]}) повержен", "Ещё партия") { reset() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(18.dp)) { drawCircle(C4_ME) }; Text("ты", style = MaterialTheme.typography.labelLarge)
                Canvas(Modifier.size(18.dp)) { drawCircle(C4_BOT) }; Text("бот", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
            }
            PillButton("Новая партия", { reset() }, Modifier.fillMaxWidth())
        }
    }
}
