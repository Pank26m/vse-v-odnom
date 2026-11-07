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
                g.body.forEachIndexed { i, (x, y) ->
                    val t = i.toFloat() / g.body.size
                    drawRoundRect(Color(0xFF2F7D57).copy(alpha = 1f - t * 0.45f), Offset(ox + x * cs + 1.5f, oy + y * cs + 1.5f), Size(cs - 3f, cs - 3f), CornerRadius(cs * 0.35f))
                }
                val (hx, hy) = g.body.first()
                val ex = g.dir.second.toFloat() * 0.2f; val ey = -g.dir.first.toFloat() * 0.2f
                listOf(1f, -1f).forEach { k -> drawCircle(Color.White, cs * 0.13f, Offset(ox + (hx + 0.5f + g.dir.first * 0.18f + ex * k) * cs, oy + (hy + 0.5f + g.dir.second * 0.18f + ey * k) * cs)) }
            }
            if (!started) GameOverlay("Змейка", "Смахивай в сторону поворота. Золотое яблоко — +5, исчезает быстро.", "Старт") { started = true }
            if (g.over) GameOverlay("Конец", "Длина ${g.body.size} · счёт ${g.score}", "Ещё раз") { g = SnakeGame(walls = walls); started = true }
        }
    }
}
