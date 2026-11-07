package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.abs
import kotlin.random.Random

/** Змейка на сетке: стены убивают (или проходят насквозь — режим «без стен»). */
class SnakeGame(val w: Int = 18, val h: Int = 26, val walls: Boolean = true) {
    var body = ArrayDeque(listOf(w / 2 to h / 2, w / 2 to h / 2 + 1, w / 2 to h / 2 + 2)); private set
    var dir = 0 to -1; private set
    private var pending = 0 to -1
    var food = 0 to 0; private set
    var bonus: Pair<Int, Int>? = null; private set
    var bonusLeft = 0
    var score = 0; private set
    var over = false
    init { food = free() }
    private fun free(): Pair<Int, Int> { while (true) { val p = Random.nextInt(w) to Random.nextInt(h); if (p !in body && p != bonus) return p } }
    fun turn(d: Pair<Int, Int>) { if (d.first != -dir.first || d.second != -dir.second) pending = d }
    fun step() {
        if (over) return
        dir = pending
        var nx = body.first().first + dir.first; var ny = body.first().second + dir.second
        if (!walls) { nx = (nx + w) % w; ny = (ny + h) % h }
        val head = nx to ny
        if (nx !in 0 until w || ny !in 0 until h || (head in body && head != body.last())) { over = true; return }
        body.addFirst(head)
        when (head) {
            food -> { score += 1; food = free(); if (score % 5 == 0) { bonus = free(); bonusLeft = 40 } }
            bonus -> { score += 5; bonus = null }
            else -> body.removeLast()
        }
        if (bonus != null && --bonusLeft <= 0) bonus = null
    }
    val period get() = (0.16f - score * 0.002f).coerceAtLeast(0.06f)
}

@Composable
fun SnakeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var walls by remember { mutableStateOf(true) }
    var g by remember { mutableStateOf(SnakeGame(walls = walls)) }
    var started by remember { mutableStateOf(false) }
    var acc by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "snake")) }
    GameLoop(started && !g.over) { dt ->
        acc += dt
        if (acc >= g.period) { acc = 0f; g.step(); tick++; if (g.over && Hi.submit(ctx, "snake", g.score)) best = g.score }
    }
    ScreenScaffold("Змейка", onBack) {
        ScoreBar(g.score, best)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Со стенами", walls, { walls = true; g = SnakeGame(walls = true); started = false })
            Chip("Без стен", !walls, { walls = false; g = SnakeGame(walls = false); started = false })
        }
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFFE6F3EA)).pointerInput(g) {
                var a = Offset.Zero
                detectDragGestures(onDragStart = { a = Offset.Zero; started = true }) { ch, d ->
                    ch.consume(); a += d
                    if (maxOf(abs(a.x), abs(a.y)) > 28f) { g.turn(if (abs(a.x) > abs(a.y)) (if (a.x > 0) 1 to 0 else -1 to 0) else (if (a.y > 0) 0 to 1 else 0 to -1)); a = Offset.Zero }
                }
            }) {
                tick
                val cs = minOf(size.width / g.w, size.height / g.h)
                val ox = (size.width - cs * g.w) / 2; val oy = (size.height - cs * g.h) / 2
                for (x in 0 until g.w) for (y in 0 until g.h) if ((x + y) % 2 == 0) drawRect(Color(0x0F2F7D57), Offset(ox + x * cs, oy + y * cs), Size(cs, cs))
                if (g.walls) drawRect(Color(0x552F7D57), Offset(ox, oy), Size(cs * g.w, cs * g.h), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                // яблоко и бонус
                drawCircle(Color(0xFFE5485D), cs * 0.4f, Offset(ox + (g.food.first + 0.5f) * cs, oy + (g.food.second + 0.55f) * cs))
                drawLine(Color(0xFF3FB27F), Offset(ox + (g.food.first + 0.5f) * cs, oy + (g.food.second + 0.18f) * cs), Offset(ox + (g.food.first + 0.7f) * cs, oy + (g.food.second + 0.05f) * cs), cs * 0.12f)
                g.bonus?.let { b -> drawCircle(Color(0xFFF2B635), cs * (0.35f + 0.08f * (g.bonusLeft % 4) / 4f), Offset(ox + (b.first + 0.5f) * cs, oy + (b.second + 0.5f) * cs)) }
                // змея: гладкое тело из перекрывающихся кругов (сужается к хвосту), узор чешуи, голова с глазами и языком
                val pts = g.body.toList()
                fun cc(x: Float, y: Float) = Offset(ox + (x + 0.5f) * cs, oy + (y + 0.5f) * cs)
                val n = pts.size
                val sub = 4
                for (i in n - 1 downTo 0) {
                    val (x, y) = pts[i]
                    val next = if (i > 0) pts[i - 1] else null
                    val t = i.toFloat() / maxOf(1, n - 1)
                    val r0 = cs * (0.46f - 0.26f * t)
                    for (k in 0 until sub) {
                        val f = k.toFloat() / sub
                        val (px, py) = if (next != null && abs(next.first - x) + abs(next.second - y) == 1) (x + (next.first - x) * f) to (y + (next.second - y) * f) else x.toFloat() to y.toFloat()
                        val shade = if ((i + k / 2) % 3 == 0) Color(0xFF2E7A47) else Color(0xFF3C9A5B)
                        drawCircle(shade, r0, cc(px, py))
                    }
                }
                // светлое брюшко и ромбики чешуи
                pts.forEachIndexed { i, (x, y) ->
                    val t = i.toFloat() / maxOf(1, n - 1); val r0 = cs * (0.46f - 0.26f * t)
                    if (i > 0) {
                        drawCircle(Color(0xFF8FD19E).copy(alpha = 0.55f), r0 * 0.42f, cc(x.toFloat(), y.toFloat()))
                        if (i % 2 == 0) {
                            val c0 = cc(x.toFloat(), y.toFloat()); val d = r0 * 0.5f
                            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(c0.x, c0.y - d); lineTo(c0.x + d, c0.y); lineTo(c0.x, c0.y + d); lineTo(c0.x - d, c0.y); close() }, Color(0xFF235E37).copy(alpha = 0.55f))
                        }
                    }
                }
                val (hx, hy) = pts.first()
                val hc = cc(hx.toFloat(), hy.toFloat())
                val dx = g.dir.first.toFloat(); val dy = g.dir.second.toFloat()
                val nx = -dy; val ny = dx
                // голова чуть вытянута вперёд
                drawCircle(Color(0xFF3C9A5B), cs * 0.5f, hc)
                drawCircle(Color(0xFF3C9A5B), cs * 0.36f, Offset(hc.x + dx * cs * 0.22f, hc.y + dy * cs * 0.22f))
                // язык мелькает
                if (tick % 12 < 4) {
                    val tip = Offset(hc.x + dx * cs * 0.85f, hc.y + dy * cs * 0.85f)
                    drawLine(Color(0xFFE5485D), Offset(hc.x + dx * cs * 0.5f, hc.y + dy * cs * 0.5f), tip, cs * 0.07f)
                    listOf(1f, -1f).forEach { k -> drawLine(Color(0xFFE5485D), tip, Offset(tip.x + (dx * 0.12f + nx * 0.1f * k) * cs, tip.y + (dy * 0.12f + ny * 0.1f * k) * cs), cs * 0.06f) }
                }
                listOf(1f, -1f).forEach { k ->
                    val e = Offset(hc.x + (dx * 0.15f + nx * 0.24f * k) * cs, hc.y + (dy * 0.15f + ny * 0.24f * k) * cs)
                    drawCircle(Color(0xFFFFF3B0), cs * 0.14f, e); drawCircle(Color(0xFF1C1B22), cs * 0.07f, Offset(e.x + dx * cs * 0.04f, e.y + dy * cs * 0.04f))
                }
            }
            if (!started) GameOverlay("Змейка", "Смахивай в сторону поворота. Золотое яблоко — +5, исчезает быстро.", "Старт") { started = true }
            if (g.over) GameOverlay("Конец", "Длина ${g.body.size} · счёт ${g.score}", "Ещё раз") { g = SnakeGame(walls = walls); started = true }
        }
    }
}
