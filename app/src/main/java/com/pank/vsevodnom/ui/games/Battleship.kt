package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import com.pank.vsevodnom.ui.theme.Pal
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
class Sea(rnd: Random = Random, random: Boolean = true) {
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

    init { if (random) place(rnd) }

    /** Можно ли поставить корабль в эти клетки (не касаясь других, кроме корабля skip). */
    fun canPut(cells: List<Int>, skip: Int = -1): Boolean = cells.all { i -> i in 0 until N * N && around(i, true).all { ship[it] < 0 || ship[it] == skip } }
    /** Поставить свой корабль; возвращает его номер или -1. */
    fun put(cells: List<Int>): Int {
        if (!canPut(cells)) return -1
        cells.forEach { ship[it] = ships.size }; ships += cells; return ships.size - 1
    }
    /** Убрать корабль (номера остальных пересчитываются). */
    fun remove(s: Int) {
        if (s !in ships.indices) return
        ships.removeAt(s); ship.fill(-1); ships.forEachIndexed { k, l -> l.forEach { ship[it] = k } }
    }
    /** Клетки корабля длиной len от клетки start. */
    fun cellsFrom(start: Int, len: Int, horiz: Boolean): List<Int>? {
        val r = start / N; val c = start % N
        if (horiz && c + len > N || !horiz && r + len > N) return null
        return (0 until len).map { if (horiz) r * N + c + it else (r + it) * N + c }
    }

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

/** Бот-стрелок (в игре — только сильный: карта вероятностей): добивает подбитый корабль по линии, иначе стреляет «шахматкой» под самый длинный живой корабль. */
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

/** Анимация на поле: взрыв попадания или гибель корабля (разлом и погружение). */
private class Fx(val foe: Boolean, val cells: List<Int>, val sunk: Boolean, val at: Long, val horiz: Boolean)

@Composable
fun BattleshipScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sea", android.content.Context.MODE_PRIVATE) }
    var mine by remember { mutableStateOf(Sea(random = false)) }
    var foe by remember { mutableStateOf(Sea()) }
    val bot = remember { SeaBot(2) }
    var started by remember { mutableStateOf(false) }
    var myTurn by remember { mutableStateOf(true) }
    var ver by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(1) }   // 0 поле боя, 1 моя карта
    var msg by remember { mutableStateOf("Собери флот на «Моей карте»: выбери корабль снизу и нажми клетку") }
    var lastFoe by remember { mutableIntStateOf(-1) }
    var lastMine by remember { mutableIntStateOf(-1) }
    var wins by remember { mutableStateOf(prefs.getInt("w", 0) to prefs.getInt("l", 0)) }
    // конструктор: какой длины корабль в руке и как повёрнут
    var pickLen by remember { mutableIntStateOf(4) }
    var horiz by remember { mutableStateOf(true) }
    val fx = remember { mutableStateListOf<Fx>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fx.size) { while (fx.isNotEmpty()) { withFrameMillis { now = it }; fx.removeAll { now - it.at > (if (it.sunk) 2600 else 1100) } } }
    val over = started && (foe.allSunk() || mine.allSunk())
    val placed = mine.ships.map { it.size }
    val left = Sea.FLEET.toMutableList().also { l -> placed.forEach { l.remove(it) } }

    fun boom(foeBoard: Boolean, sea: Sea, i: Int, res: Int) {
        if (res == Sea.MISS || res == 0) return
        val s = sea.ship[i]
        val cells = if (res == Sea.SUNK && s >= 0) sea.ships[s] else listOf(i)
        val h = cells.map { it / Sea.N }.distinct().size == 1
        fx += Fx(foeBoard, cells, res == Sea.SUNK, System.nanoTime() / 1_000_000, h)
    }
    fun newGame() { mine = Sea(random = false); foe = Sea(); started = false; myTurn = true; lastFoe = -1; lastMine = -1; tab = 1; pickLen = 4
        msg = "Собери флот на «Моей карте»: выбери корабль снизу и нажми клетку"; fx.clear(); ver++ }
    fun finish(won: Boolean) {
        wins = if (won) wins.copy(first = wins.first + 1) else wins.copy(second = wins.second + 1)
        prefs.edit().putInt("w", wins.first).putInt("l", wins.second).apply()
    }
    // ход бота: ключи без ver — иначе каждый выстрел перезапускал бы эффект и обрывал его посреди задержки
    LaunchedEffect(myTurn, started, foe) {
        if (!started || myTurn || over) return@LaunchedEffect
        delay(500); tab = 1; delay(450)
        while (true) {
            val i = bot.pick(mine)
            val res = mine.fire(i); lastMine = i; boom(false, mine, i, res); ver++
            if (mine.allSunk()) { msg = "Бот потопил весь твой флот"; finish(false); return@LaunchedEffect }
            if (res == Sea.MISS) { msg = "Бот промазал — твой ход"; delay(800); tab = 0; myTurn = true; return@LaunchedEffect }
            msg = if (res == Sea.SUNK) "Бот потопил твой корабль…" else "Бот попал…"
            delay(if (res == Sea.SUNK) 1900 else 1000)
        }
    }

    ScreenScaffold("Морской бой", onBack, actions = {
        Text("${wins.first} : ${wins.second}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, color = INK)
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(msg, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif, color = INK)
            key(ver) {
                if (tab == 0) {
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
                                lastFoe = i; boom(true, foe, i, res); ver++
                                when {
                                    foe.allSunk() -> { msg = "Победа! Весь флот противника на дне"; finish(true) }
                                    res == Sea.MISS -> { msg = "Мимо. Стреляет бот…"; myTurn = false }
                                    res == Sea.SUNK -> msg = "Потоплен! Стреляй ещё"
                                    else -> msg = "Ранен! Стреляй ещё"
                                }
                            }
                        }) { now; paperBoard(foe, showShips = over, last = lastFoe, fx = fx.filter { it.foe }, now = now) }
                        if (over && foe.allSunk() && fx.isEmpty()) VictoryOverlay("Победа!", "Флот противника потоплен. Счёт ${wins.first} : ${wins.second}", "Новая игра") { newGame() }
                    }
                    if (!started) Text("Сначала расставь флот на «Моей карте»", style = MaterialTheme.typography.bodySmall, color = INK)
                } else {
                    Text(if (started) "Мой флот · осталось: ${mine.aliveShips()}" else "Мой флот · осталось поставить: ${left.size}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Serif, color = INK)
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(PAPER).pointerInput(started, pickLen, horiz, ver) {
                        detectTapGestures { p ->
                            if (started) return@detectTapGestures
                            val cell = size.width / (Sea.N + 1f)
                            val c = ((p.x - cell) / cell).toInt(); val r = ((p.y - cell) / cell).toInt()
                            if (p.x < cell || p.y < cell || r !in 0 until Sea.N || c !in 0 until Sea.N) return@detectTapGestures
                            val i = r * Sea.N + c
                            val s = mine.ship[i]
                            if (s >= 0) { val len = mine.ships[s].size; mine.remove(s); pickLen = len; ver++; return@detectTapGestures }   // снять корабль обратно в руку
                            if (pickLen !in left) return@detectTapGestures
                            val cells = mine.cellsFrom(i, pickLen, horiz) ?: mine.cellsFrom(i, pickLen, !horiz)?.also { horiz = !horiz }
                            if (cells == null || mine.put(cells) < 0) { msg = "Сюда нельзя: корабли не касаются даже углами"; ver++; return@detectTapGestures }
                            val rest = Sea.FLEET.toMutableList().also { l -> mine.ships.forEach { l.remove(it.size) } }
                            pickLen = rest.maxOrNull() ?: 0
                            msg = if (rest.isEmpty()) "Флот готов — жми «В бой»" else "Дальше: корабль на ${rest.max()} (нажми клетку)"
                            ver++
                        }
                    }) { now; paperBoard(mine, showShips = true, last = lastMine, fx = fx.filter { !it.foe }, now = now) }
                    if (!started) {
                        // док: корабли, которые осталось поставить
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(4, 3, 2, 1).forEach { len ->
                                val n = left.count { it == len }
                                Column(Modifier.weight(len + 1f).clip(RoundedCornerShape(10.dp)).background(if (pickLen == len && n > 0) Color(0x22233F9C) else Color.Transparent)
                                    .clickable(enabled = n > 0) { pickLen = len }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                                        if (n > 0) drawShip(Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), len, true, len * 7, INK, 0f)
                                        else drawLine(INK.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
                                    }
                                    Text("×$n", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Serif, color = if (n > 0) INK else INK.copy(alpha = 0.4f))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(if (horiz) "⟷ Горизонтально" else "↕ Вертикально", { horiz = !horiz }, Modifier.weight(1f), bg = Pal.Card, fg = INK)
                            PillButton("Случайно", { mine = Sea(); pickLen = 0; msg = "Флот готов — жми «В бой»"; ver++ }, Modifier.weight(1f), bg = Pal.Card, fg = INK)
                            PillButton("Очистить", { mine = Sea(random = false); pickLen = 4; ver++ }, Modifier.weight(1f), bg = Pal.Card, fg = INK)
                        }
                        Text("Выбери корабль снизу, нажми клетку — поставится от неё вправо (или вниз). Нажал на поставленный — он снова в руке.",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Serif, color = INK.copy(alpha = 0.8f))
                        PillButton("В бой", {
                            started = true; myTurn = Random.nextBoolean(); tab = if (myTurn) 0 else 1
                            msg = if (myTurn) "Ты стреляешь первым" else "Бот стреляет первым…"; ver++
                        }, Modifier.fillMaxWidth(), enabled = left.isEmpty(), bg = INK)
                    } else PillButton(if (over) "Новая игра" else "Сдаться и заново", { newGame() }, Modifier.fillMaxWidth(), bg = if (over) INK else Pal.Card, fg = if (over) Color.White else Pal.Ink)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // две вкладки снизу
        Row(Modifier.fillMaxWidth().background(Pal.Card).padding(vertical = 6.dp)) {
            listOf("Поле боя" to "⚔", "Моя карта" to "⚓").forEachIndexed { i, (t, g) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { tab = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (tab == i) Color(0x22233F9C) else Color.Transparent).padding(horizontal = 18.dp, vertical = 2.dp)) {
                        Text(g, fontSize = 20.sp, color = INK)
                    }
                    Text(t, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Serif, color = if (tab == i) INK else Pal.Muted)
                }
            }
        }
    }
}

// ---------------- рисование «ручкой» ----------------

/** Линия от руки: два прохода с лёгкой кривизной и дрожью (одинаковые от кадра к кадру — по seed). */
private fun DrawScope.penLine(a: Offset, b: Offset, seed: Int, color: Color = INK, w: Float = 2.4f) {
    val r = Random(seed)
    repeat(2) { pass ->
        val mx = (a.x + b.x) / 2 + (r.nextFloat() - 0.5f) * 3f; val my = (a.y + b.y) / 2 + (r.nextFloat() - 0.5f) * 3f
        val j = (r.nextFloat() - 0.5f) * 1.6f
        val p = Path().apply { moveTo(a.x + j, a.y - j); quadraticTo(mx, my, b.x - j, b.y + j) }
        drawPath(p, color.copy(alpha = if (pass == 0) 1f else 0.55f), style = Stroke(if (pass == 0) w else w * 0.6f, cap = StrokeCap.Round))
    }
}

/**
 * Корабль ручкой: корпус с острым носом, палуба, рубка, пушки/труба (по длине), иллюминаторы, штриховка борта.
 * [broken] 0…1 — разлом пополам (половины расходятся и кренятся).
 */
private fun DrawScope.drawShip(tl: Offset, sz: Size, len: Int, horiz: Boolean, seed: Int, color: Color, broken: Float) {
    if (!horiz) {
        // вертикальный — тот же рисунок, повёрнутый на 90°
        val c = Offset(tl.x + sz.width / 2, tl.y + sz.height / 2)
        rotate(90f, c) { drawShip(Offset(c.x - sz.height / 2, c.y - sz.width / 2), Size(sz.height, sz.width), len, true, seed, color, broken) }
        return
    }
    val w = sz.width; val h = sz.height
    fun half(left: Boolean, block: DrawScope.() -> Unit) {
        if (broken <= 0f) { block(); return }
        val pivot = Offset(tl.x + w / 2, tl.y + h)
        val ang = (if (left) -1 else 1) * 22f * broken
        clipRect(left = if (left) tl.x - 20 else tl.x + w / 2, top = tl.y - h * 2, right = if (left) tl.x + w / 2 else tl.x + w + 20, bottom = tl.y + h * 3) {
            translate((if (left) -1 else 1) * w * 0.06f * broken, h * 0.15f * broken) { rotate(ang, pivot) { block() } }
        }
    }
    listOf(true, false).forEach { left -> half(left) {
        val y0 = tl.y + h * 0.18f; val y1 = tl.y + h * 0.82f
        val bow = tl.x + w; val stern = tl.x + w * 0.02f
        // корпус: корма скруглена, нос заострён
        val hull = Path().apply {
            moveTo(stern + h * 0.25f, y0)
            lineTo(bow - h * 0.55f, y0)
            quadraticTo(bow - h * 0.1f, y0 + (y1 - y0) * 0.15f, bow, (y0 + y1) / 2)
            quadraticTo(bow - h * 0.1f, y1 - (y1 - y0) * 0.15f, bow - h * 0.55f, y1)
            lineTo(stern + h * 0.25f, y1)
            quadraticTo(stern, (y0 + y1) / 2, stern + h * 0.25f, y0)
            close()
        }
        drawPath(hull, color.copy(alpha = 0.07f))
        // штриховка нижней части борта
        clipPath(hull) {
            var x = stern - h; val r = Random(seed)
            while (x < bow + h) { penLine(Offset(x, y1 + 2), Offset(x + h * 0.5f, (y0 + y1) / 2 + 2), r.nextInt(), color.copy(alpha = 0.35f), 1.1f); x += h * 0.22f }
        }
        val r = Random(seed * 31)
        // контур корпуса — двойной проход «ручкой»
        drawPath(hull, color, style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        translate(0.8f, 0.6f) { drawPath(hull, color.copy(alpha = 0.45f), style = Stroke(1.4f)) }
        // палуба
        penLine(Offset(stern + h * 0.3f, (y0 + y1) / 2 - 1), Offset(bow - h * 0.7f, (y0 + y1) / 2 - 1), r.nextInt(), color.copy(alpha = 0.5f), 1.3f)
        // рубка и надстройки
        val mid = tl.x + w * (if (len == 1) 0.45f else 0.38f)
        val cw = h * (if (len >= 3) 0.9f else 0.6f)
        val cabin = Path().apply { addRect(androidx.compose.ui.geometry.Rect(mid - cw / 2, y0 + (y1 - y0) * 0.22f, mid + cw / 2, y1 - (y1 - y0) * 0.22f)) }
        drawPath(cabin, PAPER); drawPath(cabin, color, style = Stroke(2f))
        penLine(Offset(mid - cw / 2, (y0 + y1) / 2), Offset(mid + cw / 2, (y0 + y1) / 2), r.nextInt(), color, 1.2f)
        // труба у линкора
        if (len == 4) { drawCircle(PAPER, h * 0.16f, Offset(mid + cw * 0.9f, (y0 + y1) / 2)); drawCircle(color, h * 0.16f, Offset(mid + cw * 0.9f, (y0 + y1) / 2), style = Stroke(2f)) }
        // орудийные башни со стволами
        val guns = when (len) { 4 -> listOf(0.68f, 0.82f, 0.18f); 3 -> listOf(0.72f, 0.16f); 2 -> listOf(0.72f); else -> emptyList() }
        guns.forEach { gx ->
            val c = Offset(tl.x + w * gx, (y0 + y1) / 2)
            val toBow = gx > 0.5f
            penLine(c, Offset(c.x + (if (toBow) 1 else -1) * h * 0.42f, c.y), r.nextInt(), color, 2.4f)
            drawCircle(PAPER, h * 0.15f, c); drawCircle(color, h * 0.15f, c, style = Stroke(2f))
        }
        // иллюминаторы
        var px = stern + h * 0.45f
        while (px < bow - h * 0.8f) { if (kotlin.math.abs(px - mid) > cw * 0.7f) drawCircle(color.copy(alpha = 0.7f), h * 0.04f, Offset(px, y1 - (y1 - y0) * 0.2f)); px += h * 0.33f }
    } }
    if (broken > 0f) {
        // рваный край разлома
        val cx = tl.x + w / 2; val r = Random(seed + 5)
        var y = tl.y + h * 0.1f
        while (y < tl.y + h * 0.9f) { val ny = y + h * 0.12f; penLine(Offset(cx + (r.nextFloat() - 0.5f) * 6, y), Offset(cx + (r.nextFloat() - 0.5f) * 6, ny), r.nextInt(), RED_PEN.copy(alpha = 1f - broken * 0.5f), 1.6f); y = ny }
    }
}

/** Взрыв ручкой: красно-оранжевые лучи, искры и клубы дыма, всё разлетается и гаснет. */
private fun DrawScope.explosion(c: Offset, cell: Float, t: Float, seed: Int, big: Boolean) {
    if (t < 0f || t > 1f) return
    val r = Random(seed)
    val k = if (big) 1.6f else 1f
    val e = 1f - (1f - t) * (1f - t)
    // вспышка
    if (t < 0.35f) drawCircle(Color(0xFFFFD25A).copy(alpha = (1 - t / 0.35f) * 0.9f), cell * 0.55f * k * (0.4f + e), c)
    // лучи
    repeat(10) { i ->
        val a = i * 36f + r.nextFloat() * 20f; val rad = Math.toRadians(a.toDouble())
        val len = cell * k * (0.25f + 0.55f * e) * (0.7f + r.nextFloat() * 0.5f)
        val s = Offset(c.x + kotlin.math.cos(rad).toFloat() * cell * 0.1f, c.y + kotlin.math.sin(rad).toFloat() * cell * 0.1f)
        val f = Offset(c.x + kotlin.math.cos(rad).toFloat() * len, c.y + kotlin.math.sin(rad).toFloat() * len)
        drawLine((if (i % 2 == 0) RED_PEN else Color(0xFFF08A24)).copy(alpha = 1 - t), s, f, 3f * k, StrokeCap.Round)
    }
    // искры
    repeat(8) { val a = r.nextFloat() * 6.28f; val d = cell * k * (0.3f + 0.9f * e) * r.nextFloat(); drawCircle(Color(0xFFF08A24).copy(alpha = 1 - t), 2.5f, Offset(c.x + kotlin.math.cos(a) * d, c.y + kotlin.math.sin(a) * d - cell * 0.3f * e)) }
    // дым — штрихованные клубы, поднимаются
    repeat(3) { i ->
        val sc = Offset(c.x + (r.nextFloat() - 0.5f) * cell * 0.6f, c.y - cell * (0.2f + 0.6f * e) * (1 + i * 0.4f))
        drawCircle(Color(0xFF6B6A75).copy(alpha = (0.45f * (1 - t)).coerceAtLeast(0f)), cell * (0.15f + 0.25f * e) * k, sc, style = Stroke(1.6f))
    }
}

/** Поле на тетрадном листе: клетки, буквы/цифры, корабли ручкой, попадания, промахи, анимации. */
private fun DrawScope.paperBoard(sea: Sea, showShips: Boolean, last: Int, fx: List<Fx> = emptyList(), now: Long = 0L) {
    val n = Sea.N
    val cell = size.width / (n + 1f)
    val small = cell / 2f
    var x = 0f; while (x <= size.width) { drawLine(GRID, Offset(x, 0f), Offset(x, size.height), 1f); x += small }
    var y = 0f; while (y <= size.height) { drawLine(GRID, Offset(0f, y), Offset(size.width, y), 1f); y += small }
    val o = Offset(cell, cell)
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
    // корабли; тонущий — в анимации ниже
    val sinking = fx.filter { it.sunk }.flatMap { it.cells }.toSet()
    sea.ships.forEachIndexed { s, cells ->
        if (!showShips && !sea.sunk(s)) return@forEachIndexed
        if (cells.any { it in sinking }) return@forEachIndexed
        val r0 = cells.minOf { it / n }; val r1 = cells.maxOf { it / n }; val c0 = cells.minOf { it % n }; val c1 = cells.maxOf { it % n }
        val tl = Offset(o.x + c0 * cell + 2f, o.y + r0 * cell + 2f); val sz = Size((c1 - c0 + 1) * cell - 4f, (r1 - r0 + 1) * cell - 4f)
        val sunk = sea.sunk(s)
        // потопленный: разломан, бледный, на «дне»
        drawShip(tl, sz, cells.size, r0 == r1, s * 13 + cells.first(), if (sunk) INK.copy(alpha = 0.45f) else INK, if (sunk) 1f else 0f)
    }
    // выстрелы
    for (i in 0 until n * n) {
        val v = sea.shot[i]; if (v == 0) continue
        val cx = o.x + (i % n + 0.5f) * cell; val cy = o.y + (i / n + 0.5f) * cell
        when (v) {
            Sea.MISS -> drawCircle(INK, cell * 0.09f, Offset(cx, cy))
            else -> {
                val d = cell * 0.3f; val col = if (v == Sea.SUNK) RED_PEN else RED_PEN.copy(alpha = 0.9f)
                penLine(Offset(cx - d, cy - d), Offset(cx + d, cy + d), i * 7, col, 3.2f)
                penLine(Offset(cx + d, cy - d), Offset(cx - d, cy + d), i * 7 + 3, col, 3.2f)
                // подпалина вокруг попадания
                if (v == Sea.HIT) drawCircle(Color(0x33D23A3A), cell * 0.42f, Offset(cx, cy))
            }
        }
        if (i == last) {
            val p = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), cell * 0.46f)) }
            drawPath(p, RED_PEN.copy(alpha = 0.7f), style = Stroke(2f))
        }
    }
    // анимации
    fx.forEach { f ->
        val t = (now - f.at).toFloat()
        if (!f.sunk) {
            val i = f.cells.first(); explosion(Offset(o.x + (i % n + 0.5f) * cell, o.y + (i / n + 0.5f) * cell), cell, t / 1000f, i, false)
        } else {
            val cells = f.cells
            val r0 = cells.minOf { it / n }; val r1 = cells.maxOf { it / n }; val c0 = cells.minOf { it % n }; val c1 = cells.maxOf { it % n }
            val tl = Offset(o.x + c0 * cell + 2f, o.y + r0 * cell + 2f); val sz = Size((c1 - c0 + 1) * cell - 4f, (r1 - r0 + 1) * cell - 4f)
            // 0–0,5 с взрывы по всему кораблю, 0,4–1,4 с разлом, дальше — погружение (бледнеет, уходит вниз, круги по воде)
            val brk = ((t - 400f) / 1000f).coerceIn(0f, 1f)
            val sink = ((t - 1100f) / 1400f).coerceIn(0f, 1f)
            translate(0f, cell * 0.25f * sink) {
                drawShip(tl, sz, cells.size, f.horiz, cells.first() * 13, INK.copy(alpha = (1f - sink * 0.6f)), brk)
            }
            if (sink > 0f) {
                val c = Offset(tl.x + sz.width / 2, tl.y + sz.height / 2)
                repeat(3) { k -> val rr = (sink + k * 0.25f) % 1f; drawOval(INK.copy(alpha = 0.5f * (1 - rr)), Offset(c.x - sz.width * 0.5f * (0.6f + rr), c.y - cell * 0.2f * (0.6f + rr)),
                    Size(sz.width * (0.6f + rr), cell * 0.4f * (0.6f + rr)), style = Stroke(1.5f)) }
            }
            cells.forEachIndexed { k, i -> explosion(Offset(o.x + (i % n + 0.5f) * cell, o.y + (i / n + 0.5f) * cell), cell, (t - k * 120f) / 900f, i + 99, k == cells.size / 2) }
        }
    }
    drawLine(RED_PEN.copy(alpha = 0.55f), Offset(cell * 0.95f, 0f), Offset(cell * 0.95f, size.height), 2f)
}
