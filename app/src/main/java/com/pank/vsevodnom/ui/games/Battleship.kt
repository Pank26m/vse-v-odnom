package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Морское поле 10×10. Флот классический: 4, 3, 3, 2, 2, 2, 1, 1, 1, 1; корабли не касаются друг друга даже углами.
 * Попал — стреляешь ещё раз. Потопленный корабль обводится точками (как на бумаге — туда стрелять бессмысленно).
 */
class Sea(rnd: Random = Random) {
    companion object {
        const val N = 10
        val FLEET = listOf(4, 3, 3, 2, 2, 2, 1, 1, 1, 1)
        const val MISS = 1; const val HIT = 2; const val SUNK = 3
    }
    /** Номер корабля в клетке или -1. */
    val ship = IntArray(N * N) { -1 }
    val ships = ArrayList<List<Int>>()
    /** Выстрелы: 0 нет, MISS, HIT (подбит), SUNK (потоплен). */
    val shot = IntArray(N * N)

    init { place(rnd) }

    fun place(rnd: Random) {
        while (true) {
            ship.fill(-1); ships.clear()
            var ok = true
            for (len in FLEET) {
                var placed = false
                for (attempt in 0 until 300) {
                    val horiz = rnd.nextBoolean()
                    val r = rnd.nextInt(if (horiz) N else N - len + 1); val c = rnd.nextInt(if (horiz) N - len + 1 else N)
                    val cells = (0 until len).map { if (horiz) r * N + c + it else (r + it) * N + c }
                    if (cells.all { free(it) }) { cells.forEach { ship[it] = ships.size }; ships += cells; placed = true; break }
                }
                if (!placed) { ok = false; break }
            }
            if (ok) return
        }
    }
    /** Клетка и все соседние (включая углы) свободны. */
    private fun free(i: Int): Boolean = around(i, true).all { ship[it] < 0 }
    fun around(i: Int, self: Boolean = false): List<Int> {
        val r = i / N; val c = i % N; val out = ArrayList<Int>(9)
        for (dr in -1..1) for (dc in -1..1) { if (!self && dr == 0 && dc == 0) continue; val rr = r + dr; val cc = c + dc; if (rr in 0 until N && cc in 0 until N) out += rr * N + cc }
        return out
    }

    /** Выстрел: MISS/HIT/SUNK; 0 — сюда уже стреляли. */
    fun fire(i: Int): Int {
        if (shot[i] != 0) return 0
        val s = ship[i]
        if (s < 0) { shot[i] = MISS; return MISS }
        shot[i] = HIT
        val cells = ships[s]
        if (cells.all { shot[it] == HIT }) {
            cells.forEach { shot[it] = SUNK }
            cells.flatMap { around(it) }.forEach { if (shot[it] == 0) shot[it] = MISS }
            return SUNK
        }
        return HIT
    }
    fun sunk(s: Int) = ships[s].all { shot[it] == SUNK }
    fun allSunk() = ships.indices.all { sunk(it) }
    fun aliveShips() = ships.indices.count { !sunk(it) }
}

/** Бот-стрелок: добивает подбитый корабль по линии, иначе стреляет «шахматкой» под самый длинный живой корабль. */
class SeaBot(val level: Int, private val rnd: Random = Random) {
    fun pick(sea: Sea): Int {
        val n = Sea.N
        val free = (0 until n * n).filter { sea.shot[it] == 0 }
        if (level == 0 && rnd.nextFloat() < 0.5f) return free.random(rnd)
        val hits = (0 until n * n).filter { sea.shot[it] == Sea.HIT }
        if (hits.isNotEmpty()) {
            val cand = if (hits.size >= 2) {
                val horiz = hits.map { it / n }.distinct().size == 1
                if (horiz) { val r = hits[0] / n; val cs = hits.map { it % n }; listOf(cs.min() - 1, cs.max() + 1).filter { it in 0 until n }.map { r * n + it } }
                else { val c = hits[0] % n; val rs = hits.map { it / n }; listOf(rs.min() - 1, rs.max() + 1).filter { it in 0 until n }.map { it * n + c } }
            } else {
                val r = hits[0] / n; val c = hits[0] % n
                listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1).filter { (a, b) -> a in 0 until n && b in 0 until n }.map { (a, b) -> a * n + b }
            }.filter { sea.shot[it] == 0 }
            if (cand.isNotEmpty()) return cand.random(rnd)
        }
        if (level >= 2) {
            // карта вероятностей: сколько способов поставить живые корабли через клетку
            val alive = sea.ships.indices.filter { !sea.sunk(it) }.map { sea.ships[it].size }
            val heat = IntArray(n * n)
            for (len in alive) for (r in 0 until n) for (c in 0 until n) for (h in listOf(true, false)) {
                if (h && c + len > n || !h && r + len > n) continue
                val cells = (0 until len).map { if (h) r * n + c + it else (r + it) * n + c }
                if (cells.all { sea.shot[it] == 0 }) cells.forEach { heat[it]++ }
            }
            val m = free.maxOf { heat[it] }
            return free.filter { heat[it] == m }.random(rnd)
        }
        val longest = sea.ships.indices.filter { !sea.sunk(it) }.maxOfOrNull { sea.ships[it].size } ?: 1
        val parity = free.filter { (it / n + it % n) % longest.coerceAtLeast(2) == 0 }
        return (parity.ifEmpty { free }).random(rnd)
    }
}

// «Бумага в клетку»: тетрадный лист, синяя ручка, красные поля
private val PAPER = Color(0xFFFBF8EF)
private val GRID = Color(0xFFBFD3EA)
private val INK = Color(0xFF233F9C)
private val RED_PEN = Color(0xFFD23A3A)
private const val LETTERS = "АБВГДЕЖЗИК"

@Composable
fun BattleshipScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sea", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var mine by remember { mutableStateOf(Sea()) }
    var foe by remember { mutableStateOf(Sea()) }
    var bot by remember { mutableStateOf(SeaBot(level)) }
    var started by remember { mutableStateOf(false) }
    var myTurn by remember { mutableStateOf(true) }
    var ver by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("Расставь флот («Перемешать») и нажми «В бой»") }
    var lastFoe by remember { mutableIntStateOf(-1) }
    var lastMine by remember { mutableIntStateOf(-1) }
    var wins by remember { mutableStateOf(prefs.getInt("w", 0) to prefs.getInt("l", 0)) }
    val over = started && (foe.allSunk() || mine.allSunk())

    fun newGame() { mine = Sea(); foe = Sea(); bot = SeaBot(level); started = false; myTurn = true; lastFoe = -1; lastMine = -1; msg = "Расставь флот («Перемешать») и нажми «В бой»"; ver++ }
    fun finish(won: Boolean) {
        wins = if (won) wins.copy(first = wins.first + 1) else wins.copy(second = wins.second + 1)
        prefs.edit().putInt("w", wins.first).putInt("l", wins.second).apply()
    }
    LaunchedEffect(myTurn, started, ver) {
        if (!started || myTurn || over) return@LaunchedEffect
        delay(650)
        while (true) {
            val i = bot.pick(mine)
            val res = mine.fire(i); lastMine = i; ver++
            if (mine.allSunk()) { msg = "Бот потопил весь твой флот"; finish(false); return@LaunchedEffect }
            if (res == Sea.MISS) { msg = "Бот промазал — твой ход"; myTurn = true; return@LaunchedEffect }
            msg = if (res == Sea.SUNK) "Бот потопил твой корабль…" else "Бот попал…"
            delay(700)
        }
    }

    ScreenScaffold("Морской бой", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!started) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Лёгкий", "Средний", "Сильный").forEachIndexed { i, t -> Chip(t, level == i, { level = i; prefs.edit().putInt("level", i).apply(); bot = SeaBot(i) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif, color = INK, modifier = Modifier.weight(1f))
                Text("${wins.first} : ${wins.second}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, color = INK)
            }
            key(ver) {
                Text("Противник · осталось кораблей: ${foe.aliveShips()}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Serif, color = INK)
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Canvas(Modifier.fillMaxSize().background(PAPER).pointerInput(foe, started, myTurn, over) {
                        detectTapGestures { p ->
                            if (!started || !myTurn || over) return@detectTapGestures
                            val cell = size.width / (Sea.N + 1f)
                            val c = ((p.x - cell) / cell).toInt(); val r = ((p.y - cell) / cell).toInt()
                            if (p.x < cell || p.y < cell || r !in 0 until Sea.N || c !in 0 until Sea.N) return@detectTapGestures
                            val i = r * Sea.N + c
                            val res = foe.fire(i); if (res == 0) return@detectTapGestures
                            lastFoe = i; ver++
                            when {
                                foe.allSunk() -> { msg = "Победа! Весь флот противника на дне"; finish(true) }
                                res == Sea.MISS -> { msg = "Мимо. Стреляет бот…"; myTurn = false }
                                res == Sea.SUNK -> msg = "Потоплен! Стреляй ещё"
                                else -> msg = "Ранен! Стреляй ещё"
                            }
                        }
                    }) { paperBoard(foe, showShips = over, last = lastFoe) }
                    if (over && foe.allSunk()) VictoryOverlay("Победа!", "Флот противника потоплен. Счёт ${wins.first} : ${wins.second}", "Новая игра") { newGame() }
                }
                Text("Мой флот" + if (started) " · осталось: ${mine.aliveShips()}" else "", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Serif, color = INK)
                Canvas(Modifier.fillMaxWidth(if (started) 0.62f else 1f).aspectRatio(1f).align(Alignment.CenterHorizontally).background(PAPER)) { paperBoard(mine, showShips = true, last = lastMine) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!started) {
                    PillButton("Перемешать", { mine = Sea(); ver++ }, Modifier.weight(1f), bg = com.pank.vsevodnom.ui.theme.Pal.Card, fg = com.pank.vsevodnom.ui.theme.Pal.Ink)
                    PillButton("В бой", { started = true; myTurn = Random.nextBoolean(); msg = if (myTurn) "Ты стреляешь первым" else "Бот стреляет первым…"; ver++ }, Modifier.weight(1f))
                } else PillButton(if (over) "Новая игра" else "Сдаться и заново", { newGame() }, Modifier.fillMaxWidth(), bg = if (over) INK else com.pank.vsevodnom.ui.theme.Pal.Card, fg = if (over) Color.White else com.pank.vsevodnom.ui.theme.Pal.Ink)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Поле на тетрадном листе: клетки, буквы/цифры, корабли ручкой, крестики попаданий и точки промахов. */
private fun DrawScope.paperBoard(sea: Sea, showShips: Boolean, last: Int) {
    val n = Sea.N
    val cell = size.width / (n + 1f)
    // тетрадная клетка по всему листу (по половине клетки поля, как в тетради)
    val small = cell / 2f
    var x = 0f; while (x <= size.width) { drawLine(GRID, Offset(x, 0f), Offset(x, size.height), 1f); x += small }
    var y = 0f; while (y <= size.height) { drawLine(GRID, Offset(0f, y), Offset(size.width, y), 1f); y += small }
    val o = Offset(cell, cell)
    // рамка поля ручкой
    drawRect(INK, o, Size(cell * n, cell * n), style = Stroke(2.5f))
    for (k in 1 until n) {
        drawLine(INK.copy(alpha = 0.35f), Offset(o.x + k * cell, o.y), Offset(o.x + k * cell, o.y + n * cell), 1.2f)
        drawLine(INK.copy(alpha = 0.35f), Offset(o.x, o.y + k * cell), Offset(o.x + n * cell, o.y + k * cell), 1.2f)
    }
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(0x23, 0x3F, 0x9C); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; textSize = cell * 0.55f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC) }
    for (k in 0 until n) {
        drawContext.canvas.nativeCanvas.drawText(LETTERS[k].toString(), o.x + (k + 0.5f) * cell, cell * 0.72f, paint)
        drawContext.canvas.nativeCanvas.drawText("${k + 1}", cell * 0.5f, o.y + (k + 0.72f) * cell, paint)
    }
    // корабли — обведены ручкой, с лёгкой штриховкой
    sea.ships.forEachIndexed { s, cells ->
        if (!showShips && !sea.sunk(s)) return@forEachIndexed
        val r0 = cells.minOf { it / n }; val r1 = cells.maxOf { it / n }; val c0 = cells.minOf { it % n }; val c1 = cells.maxOf { it % n }
        val tl = Offset(o.x + c0 * cell + 3f, o.y + r0 * cell + 3f); val sz = Size((c1 - c0 + 1) * cell - 6f, (r1 - r0 + 1) * cell - 6f)
        drawRect(INK.copy(alpha = 0.08f), tl, sz)
        drawRect(INK, tl, sz, style = Stroke(3.5f))
    }
    // выстрелы
    for (i in 0 until n * n) {
        val v = sea.shot[i]; if (v == 0) continue
        val cx = o.x + (i % n + 0.5f) * cell; val cy = o.y + (i / n + 0.5f) * cell
        when (v) {
            Sea.MISS -> drawCircle(INK, cell * 0.09f, Offset(cx, cy))
            else -> {
                val d = cell * 0.32f; val col = if (v == Sea.SUNK) RED_PEN else INK
                drawLine(col, Offset(cx - d, cy - d), Offset(cx + d, cy + d), 4f, StrokeCap.Round)
                drawLine(col, Offset(cx + d, cy - d), Offset(cx - d, cy + d), 4f, StrokeCap.Round)
            }
        }
        if (i == last) {
            // последний выстрел обведён карандашным кружком
            val p = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), cell * 0.46f)) }
            drawPath(p, RED_PEN.copy(alpha = 0.7f), style = Stroke(2f))
        }
    }
    // красные поля тетради
    drawLine(RED_PEN.copy(alpha = 0.55f), Offset(cell * 0.95f, 0f), Offset(cell * 0.95f, size.height), 2f)
}
