package com.pank.vsevodnom.ui.games

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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

/** Клетка карты: осевые координаты гекса, владелец (-1 — ничья) и сила 0…MAX. */
class Hex(val q: Int, val r: Int) { var owner = -1; var v = 0 }

/**
 * Правила «Территорий» (свои):
 * 1) Атака: из своей клетки силой ≥ 2 — в соседнюю чужую. Ничья с силой 0 берётся сразу.
 *    Иначе бросок: атакующий кидает столько кубиков, какова его сила, защитник — свою; больше сумма — победа.
 *    Победа: клетка переходит к атакующему с силой (сила − 1), исходная остаётся с 1.
 *    Поражение: исходная остаётся с 1, защитник теряет половину силы атаки.
 * 2) Рост: получаешь столько очков, сколько у тебя клеток, и раздаёшь по +1 (максимум MAX на клетку).
 */
class TerritoryGame(val players: Int = 4, seed: Long = System.nanoTime(), val radius: Int = 6, val botLevel: Int = 1) {
    companion object { const val MAX = 8 }
    val rnd = Random(seed)
    val cells = ArrayList<Hex>()
    private val byKey = HashMap<Long, Hex>()
    private fun key(q: Int, r: Int) = q.toLong() * 100_000 + r
    fun at(q: Int, r: Int) = byKey[key(q, r)]
    fun nbrs(h: Hex) = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to -1, -1 to 1).mapNotNull { (dq, dr) -> at(h.q + dq, h.r + dr) }

    var turn = 0; var phase = 0   // 0 атака, 1 рост
    var points = 0
    var log by mutableStateOf("")
    var winner: Int? = null
    var turns = 0

    init {
        for (q in -radius..radius) for (r in -radius..radius) if (abs(q + r) <= radius) Hex(q, r).also { cells += it; byKey[key(q, r)] = it }
        // дыры в карте, не разрывая её
        repeat(cells.size / 6) {
            val h = cells.random(rnd)
            if (h.q * h.q + h.r * h.r < 2) return@repeat
            cells.remove(h); byKey.remove(key(h.q, h.r))
            if (!connected()) { cells += h; byKey[key(h.q, h.r)] = h }
        }
        // старты — по углам карты
        val corners = listOf(-radius to 0, radius to 0, 0 to -radius, 0 to radius, radius to -radius, -radius to radius)
        corners.take(players).forEachIndexed { i, (q, r) ->
            val h = cells.minBy { dist(it, q, r) }
            h.owner = i; h.v = 2
        }
    }
    private fun dist(h: Hex, q: Int, r: Int) = (abs(h.q - q) + abs(h.r - r) + abs(h.q + h.r - q - r)) / 2
    private fun connected(): Boolean {
        if (cells.isEmpty()) return true
        val seen = HashSet<Hex>(); val st = ArrayDeque(listOf(cells[0]))
        while (st.isNotEmpty()) { val h = st.removeLast(); if (seen.add(h)) nbrs(h).forEach { if (it !in seen) st.add(it) } }
        return seen.size == cells.size
    }

    fun owned(p: Int) = cells.filter { it.owner == p }
    fun alive(p: Int) = cells.any { it.owner == p }
    fun canAttack(from: Hex, to: Hex) = from.owner == turn && phase == 0 && from.v >= 2 && to.owner != turn && to in nbrs(from)

    fun attack(from: Hex, to: Hex): Boolean {
        if (!canAttack(from, to)) return false
        val a = from.v; val d = to.v
        val win = if (d == 0) true else { val sa = (0 until a).sumOf { rnd.nextInt(1, 7) }; val sd = (0 until d).sumOf { rnd.nextInt(1, 7) }; log = "$a кубиков: $sa  против  $d: $sd"; sa > sd }
        if (win) { to.owner = turn; to.v = a - 1; from.v = 1; if (d == 0) log = "Клетка занята" else log += " — захват!" }
        else { from.v = 1; to.v = (d - a / 2).coerceAtLeast(1); log += " — отбились" }
        checkWin()
        return win
    }

    fun endAttack() { phase = 1; points = owned(turn).size + if (turn != 0 && botLevel == 2) owned(turn).size / 4 else 0 }
    fun grow(h: Hex): Boolean { if (phase != 1 || h.owner != turn || points <= 0 || h.v >= MAX) return false; h.v++; points--; return true }
    /** Раздать оставшиеся очки по границе, начиная со слабых. */
    fun autoGrow() {
        while (points > 0) {
            val border = owned(turn).filter { h -> h.v < MAX && nbrs(h).any { it.owner != turn } }.ifEmpty { owned(turn).filter { it.v < MAX } }
            if (border.isEmpty()) { points = 0; break }
            grow(border.minBy { h -> h.v - nbrs(h).filter { it.owner != turn && it.owner >= 0 }.maxOfOrNull { it.v }.let { it ?: 0 } / 2 })
        }
    }
    fun endTurn() {
        points = 0; phase = 0
        var n = turn
        do { n = (n + 1) % players } while (!alive(n) && n != turn)
        turn = n
        if (n == 0) turns++
    }
    private fun checkWin() { val alive = (0 until players).filter { alive(it) }; if (alive.size == 1) winner = alive[0] }

    /** Ход бота: одна атака за вызов (чтобы было видно), null — атаковать больше нечего. */
    fun botAttackOnce(): Boolean {
        val opts = owned(turn).filter { it.v >= 2 }.flatMap { s -> nbrs(s).filter { it.owner != turn }.map { s to it } }
        // уровень ботов: 0 лёгкий — осторожничает и часто пропускает атаки; 2 сложный — давит, бьёт и при равенстве, охотится на тебя
        val good = when (botLevel) {
            0 -> opts.filter { (s, t) -> t.v == 0 || s.v >= t.v + 2 }.filter { rnd.nextFloat() < 0.6f }
            2 -> opts.filter { (s, t) -> t.v == 0 || s.v > t.v || (s.v == t.v && s.v >= 4) || (t.owner == 0 && s.v >= t.v) }
            else -> opts.filter { (s, t) -> t.v == 0 || s.v > t.v || (s.v == t.v && s.v >= 6) }
        }
        val pick = good.maxByOrNull { (s, t) -> (if (t.v == 0) 3 else 0) + s.v - t.v + (if (t.owner == 0) (if (botLevel == 2) 3 else 1) else 0) } ?: return false
        attack(pick.first, pick.second); return true
    }
}

private val P_COLORS = listOf(Color(0xFF8E7CC3) to Color(0xFF4E3E8A), Color(0xFF6CC497) to Color(0xFF2B7A52), Color(0xFFF0A675) to Color(0xFF9C4F1F), Color(0xFFE88AA9) to Color(0xFF9C2E55))
private val P_NAMES = listOf("Ты", "Мята", "Персик", "Роза")

@Composable
fun TerritoryScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tp = remember { ctx.getSharedPreferences("territory", android.content.Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(tp.getBoolean("dark", false)) }
    var mapSize by remember { mutableIntStateOf(tp.getInt("size", 1)) }      // 0 S, 1 L, 2 XL, 3 XXL
    var level by remember { mutableIntStateOf(tp.getInt("level", 1)) }       // 0 лёгкие, 1 обычные, 2 сложные
    var opts by remember { mutableStateOf(false) }
    fun newGame() = TerritoryGame(radius = listOf(4, 6, 8, 10)[mapSize], botLevel = level)
    var g by remember { mutableStateOf(newGame()) }
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Hex?>(null) }
    var layout by remember { mutableStateOf(Triple(0f, Offset.Zero, 1f)) }   // размер гекса, центр, —
    var turnSeq by remember { mutableIntStateOf(0) }
    // ходы ботов: эффект перезапускается только при смене хода, перерисовка — через ver
    LaunchedEffect(g, turnSeq) {
        if (g.winner != null || g.turn == 0) return@LaunchedEffect
        delay(400)
        while (g.phase == 0 && g.botAttackOnce() && g.winner == null) { ver++; delay(330) }
        if (g.winner == null) { g.endAttack(); g.autoGrow(); ver++; delay(350); g.endTurn(); ver++; turnSeq++ }
    }
    val myTurn = g.turn == 0 && g.winner == null
    ScreenScaffold("Территории", onBack, actions = { RoundIconButton(androidx.compose.material.icons.Icons.Rounded.Settings, "Режимы", { opts = true }) }) {
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
                    val (sz, c, _) = layout
                    // перевод экрана в осевые координаты гекса (с округлением)
                    val x = (pos.x - c.x) / sz; val y = (pos.y - c.y) / sz
                    val qf = (sqrt(3f) / 3f * x - y / 3f); val rf = 2f / 3f * y
                    var rq = Math.round(qf); var rr = Math.round(rf); val rs = Math.round(-qf - rf)
                    val dq = abs(rq - qf); val dr = abs(rr - rf); val ds = abs(rs - (-qf - rf))
                    if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
                    val h = g.at(rq, rr) ?: run { sel = null; return@detectTapGestures }
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
                val qs = g.cells.map { sqrt(3f) * (it.q + it.r / 2f) }; val ys = g.cells.map { 1.5f * it.r }
                val w = (qs.max() - qs.min()) + 2f; val h = (ys.max() - ys.min()) + 2.2f
                val sz = minOf(size.width / w, size.height / h) * 0.98f
                val c = Offset(size.width / 2 - (qs.max() + qs.min()) / 2 * sz, size.height / 2 - (ys.max() + ys.min()) / 2 * sz)
                layout = Triple(sz, c, 1f)
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; textSize = sz * 0.62f }
                val s = sel
                val visible: (Hex) -> Boolean = if (!dark) { _ -> true } else { hx -> hx.owner == 0 || g.nbrs(hx).any { it.owner == 0 } }
                g.cells.forEach { hx ->
                    val cx = c.x + sqrt(3f) * (hx.q + hx.r / 2f) * sz; val cy = c.y + 1.5f * hx.r * sz
                    val path = Path()
                    for (k in 0 until 6) { val a = Math.toRadians(60.0 * k - 30); val px = cx + (kotlin.math.cos(a) * sz * 0.92).toFloat(); val py = cy + (kotlin.math.sin(a) * sz * 0.92).toFloat(); if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                    path.close()
                    val (fill, ink) = if (hx.owner >= 0) P_COLORS[hx.owner] else Color(0xFFE9E7EF) to Color(0xFF9A98A6)
                    val tint = if (hx.owner >= 0) fill.copy(alpha = 0.35f + 0.65f * hx.v / TerritoryGame.MAX) else fill
                    if (!visible(hx)) { drawPath(path, Color(0xFF2E3350)); drawPath(path, Color(0xFF3A3F5E), style = Stroke(sz * 0.06f)); return@forEach }
                    drawPath(path, tint)
                    val target = s != null && g.canAttack(s, hx)
                    when {
                        hx === s -> drawPath(path, ink, style = Stroke(sz * 0.16f))
                        target -> drawPath(path, Color(0xFFE5485D), style = Stroke(sz * 0.1f))
                        g.phase == 1 && myTurn && hx.owner == 0 && hx.v < TerritoryGame.MAX && g.points > 0 -> drawPath(path, ink.copy(alpha = 0.5f), style = Stroke(sz * 0.06f))
                        else -> drawPath(path, Color.White, style = Stroke(sz * 0.06f))
                    }
                    if (hx.owner >= 0 || hx.v > 0) { paint.color = if (hx.v >= 5 && hx.owner >= 0) android.graphics.Color.WHITE else ink.let { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }; drawContext.canvas.nativeCanvas.drawText("${hx.v}", cx, cy + sz * 0.22f, paint) }
                }
            }
            if (opts) androidx.compose.material3.AlertDialog(onDismissRequest = { opts = false }, title = { Text("Режимы") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Тьма", style = MaterialTheme.typography.titleSmall); Text("Видно только свои клетки и соседние", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                            androidx.compose.material3.Switch(dark, { dark = it })
                        }
                        Text("Размер карты", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("S", "L", "XL", "XXL").forEachIndexed { i, t -> Chip(t, mapSize == i, { mapSize = i }) } }
                        Text("Уровень ботов", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Лёгкие", "Обычные", "Сложные").forEachIndexed { i, t -> Chip(t, level == i, { level = i }) } }
                        Text("Размер и уровень применятся к новой карте, тьма — сразу.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton({ tp.edit().putBoolean("dark", dark).putInt("size", mapSize).putInt("level", level).apply(); opts = false; g = newGame(); sel = null; ver++; turnSeq++ }) { Text("Новая карта") } },
                dismissButton = { androidx.compose.material3.TextButton({ tp.edit().putBoolean("dark", dark).putInt("size", mapSize).putInt("level", level).apply(); opts = false; ver++ }) { Text("Готово") } })
            key(ver) {
                g.winner?.let { w ->
                    if (w == 0) VictoryOverlay("Победа!", "Вся карта твоя: ${g.cells.size} клеток · ходов ${g.turns}", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
                    else GameOverlay("${P_NAMES[w]} захватила карту", "Попробуй начать с соседних пустых клеток", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
                }
            }
            if (!g.alive(0) && g.winner == null) GameOverlay("Тебя вытеснили", "Боты ещё доигрывают", "Новая карта") { g = newGame(); sel = null; ver++; turnSeq++ }
        }
        key(ver) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !myTurn -> "Ходит ${P_NAMES[g.turn]}…"
                    g.phase == 0 -> if (sel == null) "Атака: выбери свою клетку силой 2+" else "Теперь нажми соседнюю клетку (красная рамка)"
                    else -> "Рост: раздай очки по своим клеткам — осталось ${g.points}"
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
