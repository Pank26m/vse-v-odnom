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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.Chip
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.abs
import kotlin.random.Random

/** Змейка на сетке: стены убивают. */
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
    /** true — направление действительно меняется. */
    fun turn(d: Pair<Int, Int>): Boolean { if (d == dir || d.first == -dir.first && d.second == -dir.second) return false; val ch = d != pending; pending = d; return ch }
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
    val walls = true   // режим «без стен» убран
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
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF5E8F3A)).pointerInput(g) {
                var a = Offset.Zero
                detectDragGestures(onDragStart = { a = Offset.Zero; started = true }) { ch, d ->
                    ch.consume(); a += d
                    if (maxOf(abs(a.x), abs(a.y)) > 18f) {
                        val nd = if (abs(a.x) > abs(a.y)) (if (a.x > 0) 1 to 0 else -1 to 0) else (if (a.y > 0) 0 to 1 else 0 to -1)
                        // поворот сразу, без ожидания следующего такта
                        if (g.turn(nd) && started && !g.over && acc > g.period * 0.25f) { g.step(); acc = 0f; tick++; if (g.over && Hi.submit(ctx, "snake", g.score)) best = g.score }
                        a = Offset.Zero
                    }
                }
            }) {
                tick
                val cs = minOf(size.width / g.w, size.height / g.h)
                val ox = (size.width - cs * g.w) / 2; val oy = (size.height - cs * g.h) / 2
                // поле в клетку двумя оттенками зелёного и тёмная рамка
                drawRect(Color(0xFF5E8F3A), Offset(ox - cs * 0.35f, oy - cs * 0.35f), Size(cs * (g.w + 0.7f), cs * (g.h + 0.7f)))
                for (x in 0 until g.w) for (y in 0 until g.h) drawRect(if ((x + y) % 2 == 0) Color(0xFFB5DD6A) else Color(0xFFA9D35E), Offset(ox + x * cs, oy + y * cs), Size(cs + 0.5f, cs + 0.5f))
                fun cc(x: Float, y: Float) = Offset(ox + (x + 0.5f) * cs, oy + (y + 0.5f) * cs)
                // яблоко с тенью и листиком
                val fc = cc(g.food.first.toFloat(), g.food.second.toFloat())
                drawOval(Color(0x33000000), Offset(fc.x - cs * 0.36f, fc.y + cs * 0.22f), Size(cs * 0.72f, cs * 0.2f))
                drawCircle(Color(0xFFE5482D), cs * 0.4f, Offset(fc.x, fc.y + cs * 0.03f))
                drawCircle(Color.White.copy(alpha = 0.35f), cs * 0.1f, Offset(fc.x - cs * 0.15f, fc.y - cs * 0.12f))
                drawLine(Color(0xFF6B4A2A), Offset(fc.x, fc.y - cs * 0.32f), Offset(fc.x + cs * 0.04f, fc.y - cs * 0.5f), cs * 0.07f)
                drawOval(Color(0xFF4CAF50), Offset(fc.x + cs * 0.04f, fc.y - cs * 0.55f), Size(cs * 0.28f, cs * 0.14f))
                g.bonus?.let { b -> val bc = cc(b.first.toFloat(), b.second.toFloat())
                    drawCircle(Color(0xFFF2B635), cs * (0.36f + 0.06f * (g.bonusLeft % 4) / 4f), bc); drawCircle(Color.White.copy(alpha = 0.4f), cs * 0.1f, Offset(bc.x - cs * 0.12f, bc.y - cs * 0.12f)) }
                // змейка-«огурчик»: один ровный синий тон, толстая трубка с круглыми концами, большие глаза и язык
                val pts = g.body.toList()
                val line = pts.map { (x, y) -> cc(x.toFloat(), y.toFloat()) }
                val body = Color(0xFF4A78E8)
                val w = cs * 0.8f
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(line[0].x, line[0].y); line.drop(1).forEach { lineTo(it.x, it.y) }; if (line.size == 1) lineTo(line[0].x + 0.1f, line[0].y) }
                translate(cs * 0.05f, cs * 0.1f) {
                    drawPath(path, Color(0x33000000), style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                drawPath(path, body, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                val head = line[0]
                val dxh = g.dir.first.toFloat(); val dyh = g.dir.second.toFloat()
                val nx = -dyh; val ny = dxh
                drawCircle(body, cs * 0.46f, head)
                // язык: высовывается и прячется
                if (tick % 10 < 3) {
                    val base = Offset(head.x + dxh * cs * 0.42f, head.y + dyh * cs * 0.42f)
                    val tip = Offset(head.x + dxh * cs * 0.78f, head.y + dyh * cs * 0.78f)
                    drawLine(Color(0xFFE5483D), base, tip, cs * 0.08f, androidx.compose.ui.graphics.StrokeCap.Round)
                    listOf(1f, -1f).forEach { k -> drawLine(Color(0xFFE5483D), tip, Offset(tip.x + (dxh * 0.1f + nx * 0.1f * k) * cs, tip.y + (dyh * 0.1f + ny * 0.1f * k) * cs), cs * 0.06f, androidx.compose.ui.graphics.StrokeCap.Round) }
                }
                // глаза — крупные, смотрят по ходу; моргают
                val blink = (System.currentTimeMillis() / 120) % 32 == 0L
                listOf(1f, -1f).forEach { k ->
                    val e = Offset(head.x + (dxh * 0.08f + nx * 0.22f * k) * cs, head.y + (dyh * 0.08f + ny * 0.22f * k) * cs)
                    if (blink) drawLine(Color(0xFF1C2A4A), Offset(e.x - nx * cs * 0.13f, e.y - ny * cs * 0.13f), Offset(e.x + nx * cs * 0.13f, e.y + ny * cs * 0.13f), cs * 0.06f, androidx.compose.ui.graphics.StrokeCap.Round)
                    else {
                        drawCircle(Color.White, cs * 0.18f, e)
                        drawCircle(Color(0xFF1C2A4A), cs * 0.09f, Offset(e.x + dxh * cs * 0.06f, e.y + dyh * cs * 0.06f))
                        drawCircle(Color.White, cs * 0.03f, Offset(e.x + dxh * cs * 0.06f - cs * 0.03f, e.y + dyh * cs * 0.06f - cs * 0.03f))
                    }
                }
            }
            if (!started) GameOverlay("Змейка", "Смахивай в сторону поворота. Золотое яблоко — +5, исчезает быстро.", "Старт") { started = true }
            if (g.over) GameOverlay("Конец", "Длина ${g.body.size} · счёт ${g.score}", "Ещё раз") { g = SnakeGame(walls = walls); started = true }
        }
    }
}
