package com.pank.vsevodnom.ui.games

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTransformGestures
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
class Node(val id: Int, val x: Float, val y: Float, val outpost: Boolean, val q: Int = 0, val r: Int = 0) {
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
    val symmetric: Boolean = true, val mixed: Boolean = false, val brain0: Brain = Brain.HUNTER,
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

    /** Радиус шестиугольника в долях карты (0…1). Объявлен ДО init: иначе инициализатор (0.05) затирал посчитанное
     *  в generate() значение — на больших картах клетки рисовались огромными и налезали друг на друга (баг 0.42). */
    var hexSize = 0.05f; private set

    init { generate() }

    // ---------------- генерация ----------------
    /**
     * Карта — кусок бесконечной сетки шестиугольников (радиус по размеру: S 4, L 6, XL 9, XXL 12 → до ~400 клеток).
     * Часть клеток — «пустырь» (их нет). Мосты — только между соседними клетками: в центре — во все стороны,
     * к краю — реже (90% → ~65%), но у каждой клетки минимум один мост, и вся карта связна.
     * Симметрия: решения (есть клетка, есть мост, аванпост) одинаковы для всех поворотов карты:
     * 2 игрока — поворот на 180°, 3 — на 120°, 4 — поворот 180° + зеркало.
     */
    private fun generate() {
        val R = listOf(4, 6, 9, 12)[size.coerceIn(0, 3)]
        // кубические координаты: x = q, z = r, y = −q−r
        fun key(q: Int, r: Int) = (q + 64) * 256 + (r + 64)
        val all = ArrayList<Pair<Int, Int>>()
        for (q in -R..R) for (r in -R..R) if (abs(q + r) <= R) all += q to r
        val k = if (symmetric) players else 1
        fun imagesRaw(q: Int, r: Int): List<Pair<Int, Int>> {
            val x = q; val z = r; val y = -q - r
            val rot180 = -x to -z
            val rot120 = z to y          // (x,y,z) → (z,x,y): q' = z, r' = y
            val rot240 = y to x
            val mirror = x to y          // меняем y и z местами: q' = x, r' = y
            val mirror180 = -x to -y
            return when (k) { 2 -> listOf(q to r, rot180); 3 -> listOf(q to r, rot120, rot240); 4 -> listOf(q to r, rot180, mirror, mirror180); else -> listOf(q to r) }
        }
        fun images(q: Int, r: Int): List<Pair<Int, Int>> = imagesRaw(q, r).distinct()
        val alive = HashSet<Int>(); all.forEach { (q, r) -> alive += key(q, r) }
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to -1, -1 to 1)
        fun dist(q: Int, r: Int) = (abs(q) + abs(r) + abs(q + r)) / 2
        fun connectedCells(): Boolean {
            val start = all.firstOrNull { key(it.first, it.second) in alive } ?: return true
            val seen = HashSet<Int>(); val st = ArrayDeque(listOf(start))
            while (st.isNotEmpty()) { val (q, r) = st.removeLast(); if (!seen.add(key(q, r))) continue
                dirs.forEach { (dq, dr) -> val n = key(q + dq, r + dr); if (n in alive && n !in seen) st.add((q + dq) to (r + dr)) } }
            return seen.size == alive.size
        }
        // пустыри: ~22% клеток (не в самом центре), орбитами, не разрывая карту
        val holes = (all.size * 0.22).toInt()
        var removed = 0
        for ((q, r) in all.shuffled(rnd)) {
            if (removed >= holes) break
            if (dist(q, r) <= 1 || key(q, r) !in alive) continue
            val orb = images(q, r).map { key(it.first, it.second) }.filter { it in alive }
            alive.removeAll(orb.toSet())
            if (connectedCells()) removed += orb.size else alive.addAll(orb)
        }
        val cellsQR = all.filter { key(it.first, it.second) in alive }
        // мосты между соседями
        val bridges = HashSet<Pair<Int, Int>>()
        fun ek(a: Int, b: Int) = if (a < b) a to b else b to a
        fun edgeImages(q1: Int, r1: Int, q2: Int, r2: Int): List<Pair<Int, Int>> {
            val i1 = imagesRaw(q1, r1); val i2 = imagesRaw(q2, r2)
            return i1.indices.map { t -> ek(key(i1[t].first, i1[t].second), key(i2[t].first, i2[t].second)) }.distinct()
        }
        val decided = HashSet<Pair<Int, Int>>()
        for ((q, r) in cellsQR) for ((dq, dr) in dirs) {
            val q2 = q + dq; val r2 = r + dr
            if (key(q2, r2) !in alive) continue
            val e = ek(key(q, r), key(q2, r2)); if (e in decided) continue
            val orb = edgeImages(q, r, q2, r2)
            decided.addAll(orb)
            val far = maxOf(dist(q, r), dist(q2, r2)).toFloat() / R
            val pKeep = if (far <= 0.35f) 1f else 0.92f - 0.3f * ((far - 0.35f) / 0.65f)
            if (rnd.nextFloat() < pKeep) bridges.addAll(orb)
        }
        val qrOf = HashMap<Int, Pair<Int, Int>>(); cellsQR.forEach { qrOf[key(it.first, it.second)] = it }
        fun nbAlive(c: Int): List<Int> { val (q, r) = qrOf[c]!!; return dirs.map { key(q + it.first, r + it.second) }.filter { it in alive } }
        // у каждой клетки минимум один мост
        for (c in qrOf.keys) if (bridges.none { it.first == c || it.second == c }) {
            val n = nbAlive(c).randomOrNull(rnd) ?: continue
            val (q, r) = qrOf[c]!!; val (q2, r2) = qrOf[n]!!
            bridges.addAll(edgeImages(q, r, q2, r2))
        }
        // связность по мостам: соединяем куски, пока карта не станет одной
        while (true) {
            val comp = HashMap<Int, Int>(); var id = 0
            for (c in qrOf.keys) if (c !in comp) {
                val st = ArrayDeque(listOf(c)); comp[c] = id
                while (st.isNotEmpty()) { val x = st.removeLast(); bridges.filter { it.first == x || it.second == x }.forEach { e -> val o = if (e.first == x) e.second else e.first; if (o !in comp) { comp[o] = id; st.add(o) } } }
                id++
            }
            if (id <= 1) break
            val pair = qrOf.keys.flatMap { c -> nbAlive(c).filter { comp[it] != comp[c] }.map { c to it } }.randomOrNull(rnd) ?: break
            val (q, r) = qrOf[pair.first]!!; val (q2, r2) = qrOf[pair.second]!!
            bridges.addAll(edgeImages(q, r, q2, r2))
        }
        // аванпосты: ~7% клеток, орбитами
        val outpost = HashSet<Int>()
        val cand = cellsQR.filter { dist(it.first, it.second) >= 2 }.shuffled(rnd)
        for ((q, r) in cand) { if (outpost.size >= (cellsQR.size * 0.07f).toInt().coerceAtLeast(k)) break
            if (key(q, r) in outpost) continue; images(q, r).forEach { outpost += key(it.first, it.second) } }
        // узлы: координаты на экране — центры шестиугольников (остриём вверх), нормировка в 0…1
        val px = cellsQR.map { (q, r) -> sqrt(3f) * (q + r / 2f) }; val py = cellsQR.map { (_, r) -> 1.5f * r }
        val minX = px.min() - 1f; val maxX = px.max() + 1f; val minY = py.min() - 1f; val maxY = py.max() + 1f
        val span = maxOf(maxX - minX, maxY - minY)
        val idOf = HashMap<Int, Int>()
        cellsQR.forEachIndexed { i, (q, r) ->
            idOf[key(q, r)] = i
            cells += Node(i, (px[i] - minX + (span - (maxX - minX)) / 2) / span, (py[i] - minY + (span - (maxY - minY)) / 2) / span, key(q, r) in outpost, q, r)
        }
        hexSize = 1f / span
        bridges.forEach { (a, b) -> edges += idOf[a]!! to idOf[b]!! }
        adj.clear(); cells.forEach { adj[it.id] = ArrayList() }; edges.forEach { (a, b) -> adj[a]!!.add(cells[b]); adj[b]!!.add(cells[a]) }
        // старты
        if (mixed) {
            cells.shuffled(rnd).forEachIndexed { i, c -> c.owner = i % players; c.v = 2 }
        } else {
            val starts: List<Node> = if (symmetric && k > 1) {
                val jit = cellsQR.associateWith { rnd.nextFloat() }
                val far = cellsQR.filter { key(it.first, it.second) !in outpost }.sortedByDescending { dist(it.first, it.second) + jit[it]!! }
                    .firstOrNull { (q, r) -> images(q, r).size == k } ?: cellsQR.first()
                images(far.first, far.second).mapNotNull { idOf[key(it.first, it.second)]?.let { cells[it] } }
            } else {
                val l = arrayListOf(cells.filter { !it.outpost }.random(rnd))
                while (l.size < players) l += cells.filter { it !in l && !it.outpost }.maxBy { c -> l.minOf { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) } }
                l
            }
            starts.take(players).forEachIndexed { i, h -> h.owner = i; h.v = 2 }
        }
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
                // божество: «таран» — все очки в самую сильную клетку у цели, которую она уже бьёт с шансом ≥50%; аванпосты под угрозой — защитить
                Brain.GOD -> border.maxBy { h ->
                    val canHit = nbrs(h).any { it.owner != turn && it.v > 0 && chance(h.v, it.v) >= 0.4 }
                    val outDanger = if (h.outpost) (nbrs(h).filter { it.owner != turn && it.owner >= 0 }.maxOfOrNull { e -> chance(e.v, h.v) } ?: 0.0) else 0.0
                    (if (canHit) 10.0 + h.v else 0.0) + outDanger * 12 + (nbrs(h).filter { it.owner != turn && it.v > 0 }.maxOfOrNull { t -> chance(h.v + 1, t.v) - chance(h.v, t.v) } ?: 0.0) + rnd.nextDouble() * 0.001
                }
                // мастер: очко туда, где оно больше всего прибавляет шанс удара (или защиты)
                else -> border.maxBy { h ->
                    val atk = nbrs(h).filter { it.owner != turn && it.v > 0 }.maxOfOrNull { t -> (chance(h.v + 1, t.v) - chance(h.v, t.v)) * (if (t.outpost) 1.5 else 1.0) } ?: 0.0
                    val def = nbrs(h).filter { it.owner != turn && it.owner >= 0 }.maxOfOrNull { e -> chance(e.v, h.v) - chance(e.v, h.v + 1) } ?: 0.0
                    // божество ещё и копит «таран»: чем сильнее клетка у слабой цели, тем ценнее очко в неё
                    val ram = if (brain == Brain.GOD) nbrs(h).filter { it.owner != turn && it.v > 0 }.maxOfOrNull { t -> if (h.v >= t.v) 0.1 * h.v else 0.0 } ?: 0.0 else 0.0
                    atk + ram + def * (if (brain == Brain.GOD) (if (h.outpost) 1.0 else 0.2) else 0.5) + rnd.nextDouble() * 0.001
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
    fun brainOf(p: Int): Brain = if (p == 0) brain0 else brains.getOrElse(p - 1) { Brain.HUNTER }

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
            // мастер: удары с шансом от 50%, сперва пустые клетки и аванпосты; очки — туда, где больше прибавят шанс
            Brain.MASTER -> opts.filter { (s, t) -> t.v == 0 || chance(s.v, t.v) >= minOf(0.5, minP) }
                .maxByOrNull { (s, t) -> chance(s.v, t.v) + (if (t.v == 0) 1.0 else 0.0) + (if (t.outpost) 0.2 else 0.0) }
            // божество: бьёт всё, где шанс не хуже 50%, порядок — пустые, слабейший соперник, аванпосты, самый верный удар;
            // не оголяет свой аванпост под сильным соседом
            Brain.GOD -> opts.filter { (s, t) -> t.v == 0 || chance(s.v, t.v) >= minOf(0.5, minP) }.maxByOrNull { (s, t) ->
                val p = chance(s.v, t.v)
                val behind = nbrs(s).filter { it.owner != turn && it.owner >= 0 }.maxOfOrNull { chance(it.v, 1) } ?: 0.0
                p + (if (t.v == 0) 1.0 else 0.0) + (if (t.outpost) 0.3 else 0.0) +
                    (if (t.owner >= 0 && (sizes[t.owner] ?: 0) <= 3) 0.4 else 0.0) - (if (s.outpost && behind > 0.6) 0.25 else 0.0)
            }
        }
        pick ?: return false
        attack(pick.first, pick.second); return true
    }
}

/** Неоновые цвета игроков: ты — синий, боты — зелёный, жёлтый, красный (вторая — тёмный оттенок для меню). */
private val P_COLORS = listOf(Color(0xFF2F8BFF) to Color(0xFF1B4F99), Color(0xFF2ED158) to Color(0xFF1B7A35), Color(0xFFFFC400) to Color(0xFF8C6A00), Color(0xFFFF4421) to Color(0xFF992714))
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("S", "L", "XL", "XXL").forEachIndexed { i, t -> Chip(t, size == i, { size = i }, Modifier.weight(1f)) }
                Text(listOf("около 45 клеток", "около 100 клеток", "около 210 клеток", "около 370 клеток")[size], style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
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
    var turnSeq by remember { mutableIntStateOf(0) }
    // приближение: масштаб и сдвиг (двумя пальцами), кнопка — показать всю карту
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val stars = remember { val r = Random(5); List(260) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    LaunchedEffect(g, turnSeq) {
        if (g.winner != null || g.turn == 0) return@LaunchedEffect
        delay(350)
        while (g.phase == 0 && g.botAttackOnce() && g.winner == null) { ver++; delay(if (g.cells.size > 150) 160 else 300) }
        if (g.winner == null) { g.endAttack(); g.autoGrow(g.brainOf(g.turn)); ver++; delay(300); g.endTurn(); ver++; turnSeq++ }
    }
    val myTurn = g.turn == 0 && g.winner == null
    fun base(): Pair<Float, Offset> { val sc0 = minOf(boxSize.width, boxSize.height) * 0.98f; return sc0 to Offset((boxSize.width - sc0) / 2, (boxSize.height - sc0) / 2) }
    fun toScreen(x: Float, y: Float): Offset { val (sc0, o) = base(); val c = Offset(boxSize.width / 2, boxSize.height / 2); val b = Offset(o.x + x * sc0, o.y + y * sc0); return c + (b - c) * zoom + pan }
    val bg = Color(0xFF05070D)
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        // верх: неоновая полоса цвета того, кто ходит
        key(ver) {
            Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White) }
                (0 until g.players).forEach { p ->
                    val n = g.owned(p).size
                    Row(Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp)).background(if (g.turn == p) P_COLORS[p].first.copy(alpha = 0.25f) else Color(0x14FFFFFF)).padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(P_COLORS[p].first))
                        Text(" ${P_NAMES[p]} $n", style = MaterialTheme.typography.labelMedium, color = if (n == 0) Color(0xFF6B7080) else Color.White, maxLines = 1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(P_COLORS[g.turn].first))
        }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { boxSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(g) {
                    detectTransformGestures { centroid, dPan, dZoom, _ ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val nz = (zoom * dZoom).coerceIn(1f, 14f)
                        // точка под пальцами остаётся на месте
                        val b = c + (centroid - c - pan) / zoom
                        pan = centroid - c - (b - c) * nz + dPan
                        zoom = nz
                        if (zoom <= 1.001f) pan = Offset.Zero
                    }
                }
                .pointerInput(g) {
                    detectTapGestures { pos ->
                        if (g.turn != 0 || g.winner != null) return@detectTapGestures
                        val (sc0, o) = base(); val c = Offset(size.width / 2f, size.height / 2f)
                        val b = c + (pos - c - pan) / zoom
                        val h = g.at((b.x - o.x) / sc0, (b.y - o.y) / sc0, g.hexSize * 1.1f) ?: run { sel = null; ver++; return@detectTapGestures }
                        if (st.dark && !(h.owner == 0 || g.nbrs(h).any { it.owner == 0 })) return@detectTapGestures
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
                @Suppress("UNUSED_EXPRESSION") ver
                // звёздное небо
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF04060C), Color(0xFF0A1428), Color(0xFF05070D))))
                stars.forEach { (x, y, b) -> drawCircle(Color.White.copy(alpha = 0.15f + 0.5f * b * b), 0.6f + 1.3f * b * b, Offset(x * size.width, y * size.height)) }
                val (sc0, _) = base()
                val r = g.hexSize * sc0 * zoom * 0.62f   // клетки мельче, между ними видны мосты; ближе — щипком
                val s = sel
                val visible: (Node) -> Boolean = if (!st.dark) { _ -> true } else { hx -> hx.owner == 0 || g.nbrs(hx).any { it.owner == 0 } }
                fun glowLine(a: Offset, b: Offset, col: Color, w: Float) {
                    drawLine(col.copy(alpha = 0.12f), a, b, w * 4f, StrokeCap.Round); drawLine(col.copy(alpha = 0.3f), a, b, w * 2f, StrokeCap.Round); drawLine(col, a, b, w, StrokeCap.Round)
                }
                // мосты
                g.edges.forEach { (ia, ib) ->
                    val na = g.cells[ia]; val nb = g.cells[ib]
                    if (!visible(na) || !visible(nb)) return@forEach
                    val pa = toScreen(na.x, na.y); val pb = toScreen(nb.x, nb.y)
                    if (pa.x < -r * 3 && pb.x < -r * 3 || pa.y < -r * 3 && pb.y < -r * 3 || pa.x > size.width + r * 3 && pb.x > size.width + r * 3 || pa.y > size.height + r * 3 && pb.y > size.height + r * 3) return@forEach
                    val d = pb - pa; val len = d.getDistance(); if (len < 1f) return@forEach
                    val u = d / len
                    val hot = s != null && ((na === s && g.canAttack(s, nb)) || (nb === s && g.canAttack(s, na)))
                    val col = when { hot -> Color(0xFFFF3B30); na.owner >= 0 && na.owner == nb.owner -> P_COLORS[na.owner].first; else -> Color(0xFF7D828C) }
                    glowLine(pa + u * r * 0.86f, pb - u * r * 0.86f, col, maxOf(1.2f, r * 0.07f))
                }
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; color = android.graphics.Color.WHITE
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL) }
                g.cells.forEach { hx ->
                    if (!visible(hx)) return@forEach
                    val c = toScreen(hx.x, hx.y)
                    val rr = if (hx.outpost) r * 1.18f else r
                    if (c.x < -rr * 2 || c.y < -rr * 2 || c.x > size.width + rr * 2 || c.y > size.height + rr * 2) return@forEach
                    val path = Path()
                    for (k in 0 until 6) { val a = Math.toRadians(60.0 * k - 90); val px = c.x + (kotlin.math.cos(a) * rr).toFloat(); val py = c.y + (kotlin.math.sin(a) * rr).toFloat(); if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                    path.close()
                    val owned = hx.owner >= 0
                    val col = if (owned) P_COLORS[hx.owner].first else Color(0xFF9BA0AA)
                    // заливка: своя — цветом (ярче при силе), ничья — тёмная
                    drawPath(path, if (owned) col.copy(alpha = 0.35f + 0.5f * hx.v / hx.cap) else Color(0xFF0E1015))
                    // неоновая обводка: три прохода — ореол, свечение, линия
                    val target = s != null && g.canAttack(s, hx)
                    val edge = when { hx === s -> Color.White; target -> Color(0xFFFF3B30); hx.outpost && !owned -> Color(0xFFFFD36B); else -> col }
                    drawPath(path, edge.copy(alpha = 0.12f), style = Stroke(rr * 0.42f))
                    drawPath(path, edge.copy(alpha = 0.3f), style = Stroke(rr * 0.2f))
                    drawPath(path, edge, style = Stroke(maxOf(1.2f, rr * 0.07f)))
                    if (g.phase == 1 && myTurn && hx.owner == 0 && hx.v < hx.cap && g.points > 0) drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(maxOf(1f, rr * 0.05f)))
                    paint.textSize = rr * 0.72f
                    if (rr > 6f) drawContext.canvas.nativeCanvas.drawText("${hx.v}", c.x, c.y + rr * 0.26f, paint)
                    if (hx.outpost && rr > 8f) { paint.textSize = rr * 0.42f; paint.color = 0xFFFFD36B.toInt(); drawContext.canvas.nativeCanvas.drawText("★", c.x - rr * 0.55f, c.y + rr * 0.55f, paint); paint.color = android.graphics.Color.WHITE }
                    if (target && s != null && rr > 6f) { paint.textSize = rr * 0.5f; paint.color = 0xFFFF6B5E.toInt(); drawContext.canvas.nativeCanvas.drawText("${(TerritoryGame.chance(s.v, hx.v) * 100).toInt()}%", c.x, c.y - rr * 1.15f, paint); paint.color = android.graphics.Color.WHITE }
                }
            }
            // показать всю карту
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp).clip(CircleShape).background(Color(0x332F8BFF)).clickable { zoom = 1f; pan = Offset.Zero },
                contentAlignment = Alignment.Center) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.CropFree, "Вся карта", tint = Color(0xFF7FB6FF)) }
            key(ver) {
                g.winner?.let { w ->
                    if (w == 0) VictoryOverlay("Победа!", "Вся карта твоя: ${g.cells.size} клеток · ходов ${g.turns}", "Новая карта") { g = newGame(); sel = null; zoom = 1f; pan = Offset.Zero; ver++; turnSeq++ }
                    else GameOverlay("${P_NAMES[w]} захватила карту", "Бот: ${g.brainOf(w).title}", "Новая карта") { g = newGame(); sel = null; zoom = 1f; pan = Offset.Zero; ver++; turnSeq++ }
                }
            }
            if (!g.alive(0) && g.winner == null) GameOverlay("Тебя вытеснили", "Боты ещё доигрывают", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
        }
        // низ: полоса долей территории по цветам и управление
        key(ver) {
            val counts = (0 until g.players).map { g.owned(it).size.toFloat() }
            val total = counts.sum().coerceAtLeast(1f)
            Row(Modifier.fillMaxWidth().height(4.dp)) { counts.forEachIndexed { p, n -> if (n > 0) Box(Modifier.weight(n / total).fillMaxHeight().background(P_COLORS[p].first)) } }
            Column(Modifier.fillMaxWidth().background(Color(0xFF0B0F1A)).padding(16.dp, 8.dp, 16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !myTurn -> "Ходит ${P_NAMES[g.turn]} (${g.brainOf(g.turn).title})…"
                    g.phase == 0 -> if (sel == null) "Коснись своей клетки (сила 2+)" else "Коснись соседней — над ней шанс захвата"
                    else -> "Рост: раздай очки — осталось ${g.points}"
                }, style = MaterialTheme.typography.titleSmall, color = if (myTurn) Color(0xFF7FB6FF) else Color.White)
                if (g.log.isNotBlank()) Text(g.log, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B5C0))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (myTurn && g.phase == 0) PillButton("К росту →", { g.endAttack(); sel = null; ver++ }, Modifier.weight(1f), bg = Color(0xFF2F8BFF))
                    if (myTurn && g.phase == 1) {
                        PillButton("Раздать сам", { g.autoGrow(); ver++ }, Modifier.weight(1f), bg = Color(0x22FFFFFF), fg = Color.White, enabled = g.points > 0)
                        PillButton("Конец хода", { g.endTurn(); ver++; turnSeq++ }, Modifier.weight(1f), bg = Color(0xFF2F8BFF))
                    }
                    PillButton("Заново", { g = newGame(); sel = null; zoom = 1f; pan = Offset.Zero; ver++; turnSeq++ }, Modifier.weight(0.7f), bg = Color(0x22FFFFFF), fg = Color.White)
                }
            }
        }
    }
}
