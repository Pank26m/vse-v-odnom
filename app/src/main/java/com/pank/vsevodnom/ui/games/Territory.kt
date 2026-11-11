package com.pank.vsevodnom.ui.games

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Точка карты: позиция (0…1), владелец (-1 — ничья), сила, аванпост (вмещает до 12). */
class Node(val id: Int, val x: Float, val y: Float, val outpost: Boolean) {
    var owner = -1; var v = 0
    val cap get() = if (outpost) TerritoryGame.MAX else TerritoryGame.NORMAL
}

/** Уровни ботов. */
enum class Brain(val title: String, val sub: String) {
    DUMB("Тупица", "ходит наугад, часто ошибается"),
    HUNTER("Охотник", "обычный: бьёт, когда шанс не хуже 50%"),
    MASTER("Мастер", "сильный: считает шансы, держит границу, берёт аванпосты"),
    GOD("Божество", "почти идеален: просчитывает ответный удар и давит слабейшего"),
}

/**
 * «Территории» — карта из точек, соединённых мостами.
 *
 * ЗАХВАТ — чистый шанс (как в оригинале), без кубиков:
 *   атакующий a ≥ защитник d → P(победы) = 1 − d / (2a)   (2 на 2 → 50%, 2 на 1 → 75%, 4 на 1 → 87,5%, 8 на 2 → 87,5%)
 *   атакующий a < защитник d → P(победы) = a / (2d)       (1 на 2 → 25%, 2 на 4 → 25%)
 *   P(a,d) + P(d,a) = 1 — формула симметрична. Пустую ничью точку (0) берут без боя.
 * Победа: точка переходит атакующему с силой a − 1, исходная остаётся с 1.
 * Поражение: исходная остаётся с 1, защитник теряет половину силы атаки (но не меньше 1 остаётся).
 * Рост: очки = число своих точек (аванпост считается за 2), раздаются по +1; обычная точка — до 8, аванпост — до 12.
 */
class TerritoryGame(
    val players: Int = 4, seed: Long = System.nanoTime(), val size: Int = 1, val brains: List<Brain> = List(3) { Brain.HUNTER },
    val symmetric: Boolean = true, val mixed: Boolean = false,
) {
    companion object {
        const val MAX = 12; const val NORMAL = 8
        /** Шанс атаки a на d. */
        fun chance(a: Int, d: Int): Double = when { d <= 0 -> 1.0; a <= 0 -> 0.0; a >= d -> 1.0 - d / (2.0 * a); else -> a / (2.0 * d) }
    }
    val rnd = Random(seed)
    val cells = ArrayList<Node>()
    val edges = ArrayList<Pair<Int, Int>>()
    private val adj = HashMap<Int, MutableList<Node>>()
    fun nbrs(h: Node): List<Node> = adj[h.id].orEmpty()

    var turn = 0; var phase = 0   // 0 атака, 1 рост
    var points = 0
    var log by mutableStateOf("")
    var winner: Int? = null
    var turns = 0
    /** Ходов подряд без единого захвата — боты становятся смелее, чтобы партия не зависала. */
    private var quiet = 0
    private var capturedThisTurn = false

    init { generate() }

    // ---------------- генерация ----------------
    private fun generate() {
        val total = listOf(14, 24, 36, 52)[size.coerceIn(0, 3)]
        val pts = ArrayList<Pair<Float, Float>>()
        val minD = listOf(0.17f, 0.13f, 0.105f, 0.088f)[size.coerceIn(0, 3)]
        fun okPt(x: Float, y: Float) = pts.none { (px, py) -> (px - x) * (px - x) + (py - y) * (py - y) < minD * minD }
        val k = if (symmetric) players else 1
        if (symmetric) {
            // одна доля круга, остальные — её повороты: карта, мосты и аванпосты одинаковы для всех
            val per = total / k
            var tries = 0
            val sector = ArrayList<Pair<Float, Float>>()
            while (sector.size < per && tries < 5000) {
                tries++
                val a = rnd.nextFloat() * (2 * Math.PI / k).toFloat(); val r = 0.12f + 0.34f * sqrt(rnd.nextFloat())
                val cand = (0 until k).map { j -> val aa = a + j * 2 * Math.PI.toFloat() / k; (0.5f + kotlin.math.cos(aa) * r) to (0.5f + kotlin.math.sin(aa) * r) }
                if (cand.all { (x, y) -> okPt(x, y) } && (k == 1 || dist(cand[0], cand[1]) > minD)) { sector += cand[0]; pts.addAll(cand) }
            }
            if (rnd.nextBoolean()) pts += 0.5f to 0.5f   // иногда точка в центре — общая для всех
        } else {
            var tries = 0
            while (pts.size < total && tries < 8000) { tries++; val x = 0.05f + rnd.nextFloat() * 0.9f; val y = 0.05f + rnd.nextFloat() * 0.9f; if (okPt(x, y)) pts += x to y }
        }
        val n = pts.size
        // орбиты: точка и её повороты (для симметрии решений)
        val orbitOf = IntArray(n) { it }
        if (symmetric && k > 1) {
            val per = (n - (if (n % k == 1) 1 else 0)) / k
            for (i in 0 until per * k) orbitOf[i] = i / k
            if (n % k == 1) orbitOf[n - 1] = 10_000
        }
        val outpost = BooleanArray(n)
        val orbits = orbitOf.distinct().filter { it != 10_000 }.shuffled(rnd)
        orbits.take((orbits.size * 0.18f).toInt().coerceAtLeast(1)).forEach { o -> (0 until n).filter { orbitOf[it] == o }.forEach { outpost[it] = true } }
        if (orbitOf.getOrNull(n - 1) == 10_000) outpost[n - 1] = true
        pts.forEachIndexed { i, (x, y) -> cells += Node(i, x, y, outpost[i]) }
        // мосты: граф Габриэля (плоский, без пересечений), часть мостов убираем (симметрично по орбитам), сохраняя связность
        val all = ArrayList<Pair<Int, Int>>()
        for (a in 0 until n) for (b in a + 1 until n) {
            val mx = (pts[a].first + pts[b].first) / 2; val my = (pts[a].second + pts[b].second) / 2
            val r2 = (dist(pts[a], pts[b]) / 2).let { it * it } * 0.999f
            if ((0 until n).none { c -> c != a && c != b && (pts[c].first - mx).let { it * it } + (pts[c].second - my).let { it * it } < r2 } && dist(pts[a], pts[b]) < minD * 3.2f) all += a to b
        }
        edges.addAll(all)
        fun rebuild() { adj.clear(); cells.forEach { adj[it.id] = ArrayList() }; edges.forEach { (a, b) -> adj[a]!!.add(cells[b]); adj[b]!!.add(cells[a]) } }
        rebuild()
        if (!connectedNow()) { edges.clear(); edges.addAll(all) }   // на всякий случай
        val edgeOrbit = HashMap<Pair<Int, Int>, String>()
        all.forEach { (a, b) -> edgeOrbit[a to b] = listOf(orbitOf[a], orbitOf[b]).sorted().joinToString(":") + ":" + (if (symmetric) ((b - a + 1000 * k) % k) else a * 1000 + b) }
        edgeOrbit.values.distinct().shuffled(rnd).forEach { o ->
            if (rnd.nextFloat() > 0.35f) return@forEach
            val drop = all.filter { edgeOrbit[it] == o }
            edges.removeAll(drop.toSet()); rebuild()
            if (!connectedNow()) { edges.addAll(drop); rebuild() }
        }
        rebuild()
        // старты
        if (mixed) {
            // «Перемешка»: все точки заняты, вперемешку, по 2 в каждой
            val order = cells.shuffled(rnd)
            order.forEachIndexed { i, c -> c.owner = i % players; c.v = 2 }
        } else {
            val starts: List<Node> = if (symmetric && k > 1) {
                // самая дальняя от центра орбита — по точке на игрока
                val far = (0 until n).filter { orbitOf[it] != 10_000 && !outpost[it] }.maxByOrNull { dist(pts[it], 0.5f to 0.5f) } ?: 0
                (0 until n).filter { orbitOf[it] == orbitOf[far] }.map { cells[it] }
            } else {
                val l = arrayListOf(cells.filter { !it.outpost }.random(rnd))
                while (l.size < players) l += cells.filter { it !in l && !it.outpost }.maxBy { c -> l.minOf { dist(c.x to c.y, it.x to it.y) } }
                l
            }
            starts.take(players).forEachIndexed { i, h -> h.owner = i; h.v = 2 }
        }
    }
    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))
    private fun connectedNow(): Boolean {
        if (cells.isEmpty()) return true
        val seen = HashSet<Int>(); val st = ArrayDeque(listOf(cells[0]))
        while (st.isNotEmpty()) { val h = st.removeLast(); if (seen.add(h.id)) nbrs(h).forEach { if (it.id !in seen) st.add(it) } }
        return seen.size == cells.size
    }

    fun at(x: Float, y: Float, maxD: Float): Node? = cells.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < maxD * maxD }
    fun owned(p: Int) = cells.filter { it.owner == p }
    fun alive(p: Int) = cells.any { it.owner == p }
    fun canAttack(from: Node, to: Node) = from.owner == turn && phase == 0 && from.v >= 2 && to.owner != turn && to in nbrs(from)

    fun attack(from: Node, to: Node): Boolean {
        if (!canAttack(from, to)) return false
        val a = from.v; val d = to.v
        val p = chance(a, d)
        val win = d == 0 || rnd.nextDouble() < p
        log = if (d == 0) "Точка занята" else "$a против $d — шанс ${(p * 100).toInt()}%: " + if (win) "захват!" else "отбились"
        if (win) { to.owner = turn; to.v = (a - 1).coerceIn(1, to.cap); from.v = 1; capturedThisTurn = true }
        else { from.v = 1; to.v = (d - a / 2).coerceAtLeast(1) }
        checkWin()
        return win
    }

    fun endAttack() { phase = 1; points = owned(turn).sumOf { if (it.outpost) 2 else 1 } }
    fun grow(h: Node): Boolean { if (phase != 1 || h.owner != turn || points <= 0 || h.v >= h.cap) return false; h.v++; points--; return true }

    /** Угроза точке: сильнейший чужой сосед. */
    private fun threat(h: Node) = nbrs(h).filter { it.owner != h.owner && it.owner >= 0 }.maxOfOrNull { it.v } ?: 0

    /** Раздать оставшиеся очки (для своего «Раздать сам» и для ботов — по уровню). */
    fun autoGrow(brain: Brain = Brain.HUNTER) {
        while (points > 0) {
            val mine = owned(turn).filter { it.v < it.cap }
            if (mine.isEmpty()) { points = 0; break }
            val border = mine.filter { h -> nbrs(h).any { it.owner != turn } }.ifEmpty { mine }
            val pick = when (brain) {
                Brain.DUMB -> mine.random(rnd)
                Brain.HUNTER -> border.minBy { h -> h.v - threat(h) / 2 }
                // мастер/божество: копят «таран» у самой выгодной цели и закрывают опасные места
                else -> border.maxBy { h ->
                    val tgt = nbrs(h).filter { it.owner != turn }.maxOfOrNull { t -> (if (t.outpost) 4.0 else 1.0) * chance(h.v + 1, t.v) - chance(h.v, t.v) } ?: 0.0
                    val danger = (threat(h) - h.v).coerceAtLeast(0) * (if (h.outpost) 2.0 else 1.0)
                    tgt * 10 + danger + (if (brain == Brain.GOD && h.v >= 4) 1.5 else 0.0)
                }
            }
            grow(pick)
        }
    }
    fun endTurn() {
        points = 0; phase = 0
        quiet = if (capturedThisTurn) 0 else quiet + 1; capturedThisTurn = false
        var n = turn
        do { n = (n + 1) % players } while (!alive(n) && n != turn)
        turn = n
        if (n == 0) turns++
    }
    private fun checkWin() { val alive = (0 until players).filter { alive(it) }; if (alive.size == 1) winner = alive[0] }
    fun brainOf(p: Int): Brain = if (p == 0) Brain.HUNTER else brains.getOrElse(p - 1) { Brain.HUNTER }

    /** Ход бота: одна атака за вызов (чтобы было видно), false — больше атаковать не будет. */
    fun botAttackOnce(): Boolean = botAttackOnceAs(brainOf(turn))
    fun botAttackOnceAs(b: Brain): Boolean {
        val opts = owned(turn).filter { it.v >= 2 }.flatMap { s -> nbrs(s).filter { it.owner != turn }.map { s to it } }
        if (opts.isEmpty()) return false
        // сила игроков — чтобы давить лидера/слабейшего
        val sizes = (0 until players).associateWith { owned(it).size }
        // граница забита до предела — расти некуда, ждать бессмысленно: идём на равные шансы
        val border = owned(turn).filter { h -> nbrs(h).any { it.owner != turn } }
        val saturated = (border.isNotEmpty() && border.all { it.v >= it.cap - 1 }) || quiet > 3 * players
        // очень долгая партия (обмены туда-сюда) — боты рискуют сильнее, чтобы она закончилась
        val minP = when { turns > 160 -> 0.3; turns > 100 -> 0.4; saturated -> 0.5; else -> 1.0 }
        val pick: Pair<Node, Node>? = when (b) {
            Brain.DUMB -> opts.filter { rnd.nextFloat() < 0.55f }.randomOrNull(rnd)?.takeIf { rnd.nextFloat() < 0.8f }
            Brain.HUNTER -> opts.filter { (s, t) -> chance(s.v, t.v) >= minOf(0.5, minP) }.maxByOrNull { (s, t) -> chance(s.v, t.v) + (if (t.v == 0) 1.0 else 0.0) + (if (t.owner == 0) 0.1 else 0.0) }
            Brain.MASTER -> opts.map { (s, t) ->
                val p = chance(s.v, t.v)
                val value = (if (t.outpost) 2.5 else 1.0) + (if (t.v == 0) 1.0 else 0.0) + (if (t.owner >= 0 && (sizes[t.owner] ?: 0) <= 2) 1.5 else 0.0)
                Triple(s, t, p * value - (1 - p) * 0.8)
            }.filter { (s, t, _) -> chance(s.v, t.v) >= minOf(0.6, minP) || t.v == 0 }.maxByOrNull { it.third }?.let { it.first to it.second }
            Brain.GOD -> opts.map { (s, t) ->
                val p = chance(s.v, t.v)
                // после захвата: новая точка с силой s.v−1 — выдержит ли ответ соседей; исходная остаётся с 1
                val after = s.v - 1
                val counter = nbrs(t).filter { it.owner != turn && it.owner >= 0 && it !== t }.maxOfOrNull { chance(it.v, after) } ?: 0.0
                val behind = nbrs(s).filter { it.owner != turn && it.owner >= 0 }.maxOfOrNull { chance(it.v, 1) } ?: 0.0
                val leader = sizes.maxByOrNull { it.value }?.key
                val value = (if (t.outpost) 3.0 else 1.0) + (if (t.v == 0) 1.2 else 0.0) +
                    (if (t.owner >= 0 && (sizes[t.owner] ?: 0) <= 2) 2.0 else 0.0) + (if (t.owner == leader && leader != turn) 0.6 else 0.0)
                Triple(s, t, p * value * (1 - 0.5 * counter) - (1 - p) * 1.0 - behind * (if (s.outpost) 1.2 else 0.4))
            }.filter { (s, t, ev) -> (ev > 0.15 || saturated) && (chance(s.v, t.v) >= minOf(0.55, minP) || t.v == 0) }.maxByOrNull { it.third }?.let { it.first to it.second }
        }
        pick ?: return false
        attack(pick.first, pick.second); return true
    }
}

private val P_COLORS = listOf(Color(0xFF8E7CC3) to Color(0xFF4E3E8A), Color(0xFF6CC497) to Color(0xFF2B7A52), Color(0xFFF0A675) to Color(0xFF9C4F1F), Color(0xFFE88AA9) to Color(0xFF9C2E55))
private val P_NAMES = listOf("Ты", "Мята", "Персик", "Роза")

/** Настройки партии (главный экран «Территорий»). */
private class TerrSetup(val size: Int, val enemies: Int, val brains: List<Brain>, val dark: Boolean, val symmetric: Boolean, val mixed: Boolean)

@Composable
fun TerritoryScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tp = remember { ctx.getSharedPreferences("territory", android.content.Context.MODE_PRIVATE) }
    var setup by remember { mutableStateOf<TerrSetup?>(null) }
    val s = setup
    if (s == null) { TerritoryMenu(tp, onBack) { setup = it }; return }
    androidx.activity.compose.BackHandler { setup = null }
    TerritoryPlay(s, onBack = { setup = null })
}

/** Главный экран: редактор партии. */
@Composable
private fun TerritoryMenu(tp: android.content.SharedPreferences, onBack: () -> Unit, onStart: (TerrSetup) -> Unit) {
    var size by remember { mutableIntStateOf(tp.getInt("size2", 1)) }
    var enemies by remember { mutableIntStateOf(tp.getInt("enemies", 3)) }
    val brains = remember { mutableStateListOf(*Array(3) { i -> Brain.entries.getOrElse(tp.getInt("brain$i", 1)) { Brain.HUNTER } }) }
    var dark by remember { mutableStateOf(tp.getBoolean("dark", false)) }
    var sym by remember { mutableStateOf(tp.getBoolean("sym", true)) }
    var mixed by remember { mutableStateOf(tp.getBoolean("mixed", false)) }
    ScreenScaffold("Территории", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            @Composable fun Card(title: String, content: @Composable ColumnScope.() -> Unit) =
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pal.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium); content()
                }
            Card("Размер карты") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("S", "L", "XL", "XXL").forEachIndexed { i, t -> Chip(t, size == i, { size = i }, Modifier.weight(1f)) } }
            }
            Card("Противники") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { n -> Chip("$n", enemies == n, { enemies = n }, Modifier.weight(1f)) } }
                (0 until enemies).forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(P_COLORS[i + 1].first))
                        Text("  ${P_NAMES[i + 1]}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(90.dp))
                        var menu by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { menu = true }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(brains[i].title, style = MaterialTheme.typography.labelLarge)
                                    Text(brains[i].sub, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
                                }
                                Text("▾", color = Pal.Muted)
                            }
                            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                Brain.entries.forEach { b -> androidx.compose.material3.DropdownMenuItem({ Column { Text(b.title); Text(b.sub, style = MaterialTheme.typography.labelSmall, color = Pal.Muted) } }, { brains[i] = b; menu = false }) }
                            }
                        }
                    }
                }
            }
            Card("Режимы") {
                @Composable fun Sw(t: String, sub: String, v: Boolean, on: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    androidx.compose.material3.Switch(v, on)
                }
                Sw("Тьма", "видно только свои точки и соседние", dark) { dark = it }
                Sw("Симметрия", if (sym) "карта, мосты и аванпосты одинаковы у всех" else "асимметрия: точки, мосты и аванпосты — как попало", sym) { sym = it }
                Sw("Перемешка", "все точки сразу заняты вперемешку, по 2 в каждой; ходишь первым", mixed) { mixed = it }
            }
            PillButton("Играть", {
                tp.edit().putInt("size2", size).putInt("enemies", enemies).putBoolean("dark", dark).putBoolean("sym", sym).putBoolean("mixed", mixed)
                    .apply { brains.forEachIndexed { i, b -> putInt("brain$i", b.ordinal) } }.apply()
                onStart(TerrSetup(size, enemies, brains.toList(), dark, sym, mixed))
            }, Modifier.fillMaxWidth())
            Text("Захват — по шансу: 2 на 2 — 50%, 2 на 1 — 75%, 1 на 2 — 25%. Формула: если сил больше или поровну — 1 − защита/(2·атака), если меньше — атака/(2·защита). Аванпост ★ вмещает до 12 и даёт 2 очка роста.",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}

@Composable
private fun TerritoryPlay(st: TerrSetup, onBack: () -> Unit) {
    fun newGame() = TerritoryGame(players = st.enemies + 1, size = st.size, brains = st.brains, symmetric = st.symmetric, mixed = st.mixed)
    var g by remember { mutableStateOf(newGame()) }
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Node?>(null) }
    var layout by remember { mutableStateOf(Triple(0f, Offset.Zero, 1f)) }   // масштаб, сдвиг
    var turnSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(g, turnSeq) {
        if (g.winner != null || g.turn == 0) return@LaunchedEffect
        delay(400)
        while (g.phase == 0 && g.botAttackOnce() && g.winner == null) { ver++; delay(330) }
        if (g.winner == null) { g.endAttack(); g.autoGrow(g.brainOf(g.turn)); ver++; delay(350); g.endTurn(); ver++; turnSeq++ }
    }
    val myTurn = g.turn == 0 && g.winner == null
    ScreenScaffold("Территории", onBack) {
        key(ver) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until g.players).forEach { p ->
                    val n = g.owned(p).size
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (g.turn == p) P_COLORS[p].first.copy(alpha = 0.35f) else Pal.Card).padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(P_COLORS[p].first))
                        Text(" ${P_NAMES[p]} $n", style = MaterialTheme.typography.labelMedium, color = if (n == 0) Pal.Muted else Pal.Ink, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize().pointerInput(g) {
                detectTapGestures { pos ->
                    if (g.turn != 0 || g.winner != null || layout.first <= 0f) return@detectTapGestures
                    val (sc, o, _) = layout
                    val h = g.at((pos.x - o.x) / sc, (pos.y - o.y) / sc, 0.06f) ?: run { sel = null; ver++; return@detectTapGestures }
                    if (g.phase == 0) {
                        val s = sel
                        when {
                            s != null && g.canAttack(s, h) -> { g.attack(s, h); sel = if (h.owner == 0 && h.v >= 2) h else null }
                            h.owner == 0 && h.v >= 2 -> sel = h
                            else -> sel = null
                        }
                    } else g.grow(h)
                    ver++
                }
            }) {
                ver
                val sc = minOf(size.width, size.height) * 0.98f
                val o = Offset((size.width - sc) / 2, (size.height - sc) / 2)
                layout = Triple(sc, o, 1f)
                fun pt(n: Node) = Offset(o.x + n.x * sc, o.y + n.y * sc)
                val s = sel
                val visible: (Node) -> Boolean = if (!st.dark) { _ -> true } else { hx -> hx.owner == 0 || g.nbrs(hx).any { it.owner == 0 } }
                val r0 = sc * 0.036f * (if (g.cells.size > 40) 0.85f else 1f)
                // мосты
                g.edges.forEach { (a, b) ->
                    val na = g.cells[a]; val nb = g.cells[b]
                    if (!visible(na) && !visible(nb)) return@forEach
                    val hot = s != null && ((na === s && g.canAttack(s, nb)) || (nb === s && g.canAttack(s, na)))
                    drawLine(if (hot) Color(0xFFE5485D) else Color(0xFFCFCBD9), pt(na), pt(nb), if (hot) r0 * 0.28f else r0 * 0.18f)
                }
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                g.cells.forEach { hx ->
                    val c = pt(hx)
                    val r = if (hx.outpost) r0 * 1.35f else r0
                    val path = Path()
                    for (k in 0 until 6) { val a = Math.toRadians(60.0 * k - 30); val px = c.x + (kotlin.math.cos(a) * r).toFloat(); val py = c.y + (kotlin.math.sin(a) * r).toFloat(); if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                    path.close()
                    if (!visible(hx)) { drawPath(path, Color(0xFF2E3350)); return@forEach }
                    val (fill, ink) = if (hx.owner >= 0) P_COLORS[hx.owner] else Color(0xFFE9E7EF) to Color(0xFF9A98A6)
                    val tint = if (hx.owner >= 0) fill.copy(alpha = 0.4f + 0.6f * hx.v / hx.cap) else fill
                    drawPath(path, tint)
                    val target = s != null && g.canAttack(s, hx)
                    when {
                        hx === s -> drawPath(path, ink, style = Stroke(r * 0.22f))
                        target -> drawPath(path, Color(0xFFE5485D), style = Stroke(r * 0.16f))
                        g.phase == 1 && myTurn && hx.owner == 0 && hx.v < hx.cap && g.points > 0 -> drawPath(path, ink.copy(alpha = 0.6f), style = Stroke(r * 0.1f))
                        hx.outpost -> drawPath(path, Color(0xFFF2B635), style = Stroke(r * 0.14f))
                        else -> drawPath(path, Color.White, style = Stroke(r * 0.08f))
                    }
                    paint.textSize = r * 0.85f
                    paint.color = if (hx.v >= hx.cap * 0.6f && hx.owner >= 0) android.graphics.Color.WHITE else android.graphics.Color.argb(255, (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
                    if (hx.owner >= 0 || hx.v > 0) drawContext.canvas.nativeCanvas.drawText("${hx.v}", c.x, c.y + r * 0.3f, paint)
                    if (hx.outpost) { paint.textSize = r * 0.5f; paint.color = 0xFFC48A12.toInt(); drawContext.canvas.nativeCanvas.drawText("★", c.x, c.y - r * 0.95f, paint) }
                    // шанс при выбранной точке — над целью
                    if (target && s != null) { paint.textSize = r * 0.55f; paint.color = 0xFFE5485D.toInt(); drawContext.canvas.nativeCanvas.drawText("${(TerritoryGame.chance(s.v, hx.v) * 100).toInt()}%", c.x, c.y + r * 1.75f, paint) }
                }
            }
            key(ver) {
                g.winner?.let { w ->
                    if (w == 0) VictoryOverlay("Победа!", "Вся карта твоя: ${g.cells.size} точек · ходов ${g.turns}", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
                    else GameOverlay("${P_NAMES[w]} захватила карту", "Бот: ${g.brainOf(w).title}", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
                }
            }
            if (!g.alive(0) && g.winner == null) GameOverlay("Тебя вытеснили", "Боты ещё доигрывают", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
        }
        key(ver) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !myTurn -> "Ходит ${P_NAMES[g.turn]} (${g.brainOf(g.turn).title})…"
                    g.phase == 0 -> if (sel == null) "Атака: выбери свою точку силой 2+" else "Нажми соседнюю точку — над ней шанс захвата"
                    else -> "Рост: раздай очки по своим точкам — осталось ${g.points}"
                }, style = MaterialTheme.typography.titleSmall)
                if (g.log.isNotBlank()) Text(g.log, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (myTurn && g.phase == 0) PillButton("К росту →", { g.endAttack(); sel = null; ver++ }, Modifier.weight(1f))
                    if (myTurn && g.phase == 1) {
                        PillButton("Раздать сам", { g.autoGrow(); ver++ }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink, enabled = g.points > 0)
                        PillButton("Конец хода", { g.endTurn(); ver++; turnSeq++ }, Modifier.weight(1f))
                    }
                    PillButton("Заново", { g = newGame(); sel = null; ver++; turnSeq++ }, Modifier.weight(0.7f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
        }
    }
}
