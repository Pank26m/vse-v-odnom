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
                    if (maxOf(abs(a.x), abs(a.y)) > 28f) { g.turn(if (abs(a.x) > abs(a.y)) (if (a.x > 0) 1 to 0 else -1 to 0) else (if (a.y > 0) 0 to 1 else 0 to -1)); a = Offset.Zero }
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
                // змейка-«огурчик»: гладкая трубка с плавным ходом между клетками
                val pts = g.body.toList()
                val f = if (started && !g.over) (acc / g.period).coerceIn(0f, 1f) else 0f
                val centers = ArrayList<Offset>(pts.size + 1)
                pts.forEachIndexed { k, (x, y) -> centers += cc(x.toFloat(), y.toFloat()) }
                // голова уже чуть впереди, хвост подтягивается
                val dxh = g.dir.first.toFloat(); val dyh = g.dir.second.toFloat()
                val head = Offset(centers[0].x + dxh * cs * f, centers[0].y + dyh * cs * f)
                val tail = if (centers.size > 1) Offset(centers.last().x + (centers[centers.size - 2].x - centers.last().x) * f, centers.last().y + (centers[centers.size - 2].y - centers.last().y) * f) else centers.last()
                val line = listOf(head) + centers.drop(1).dropLast(1) + listOf(tail)
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(line[0].x, line[0].y); line.drop(1).forEach { lineTo(it.x, it.y) } }
                val w = cs * 0.78f
                translate(cs * 0.06f, cs * 0.12f) {
                    drawPath(path, Color(0x30000000), style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                // тело: от тёмно-синей головы к светлому хвосту — отрезками
                val n = line.size
                for (k in n - 1 downTo 1) {
                    val t = k.toFloat() / (n - 1)
                    val col = androidx.compose.ui.graphics.lerp(Color(0xFF2350B8), Color(0xFF5B8CF5), t)
                    drawLine(col, line[k], line[k - 1], w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                // блик вдоль спины
                for (k in n - 1 downTo 1) drawLine(Color.White.copy(alpha = 0.12f), Offset(line[k].x - cs * 0.12f, line[k].y - cs * 0.12f), Offset(line[k - 1].x - cs * 0.12f, line[k - 1].y - cs * 0.12f), w * 0.25f, androidx.compose.ui.graphics.StrokeCap.Round)
                // голова: чуть шире, большие глаза смотрят по ходу, иногда моргают
                drawCircle(Color(0xFF2350B8), cs * 0.47f, head)
                val nx = -dyh; val ny = dxh
                val blink = (tick % 37) in 0..1 || (tick % 53) == 0
                listOf(1f, -1f).forEach { k ->
                    val e = Offset(head.x + (dxh * 0.12f + nx * 0.24f * k) * cs, head.y + (dyh * 0.12f + ny * 0.24f * k) * cs)
                    if (blink) drawLine(Color(0xFF0E2A6B), Offset(e.x - nx * cs * 0.13f, e.y - ny * cs * 0.13f), Offset(e.x + nx * cs * 0.13f, e.y + ny * cs * 0.13f), cs * 0.06f, androidx.compose.ui.graphics.StrokeCap.Round)
                    else {
                        drawCircle(Color.White, cs * 0.17f, e)
                        drawCircle(Color(0xFF1C2A4A), cs * 0.09f, Offset(e.x + dxh * cs * 0.06f, e.y + dyh * cs * 0.06f))
                        drawCircle(Color.White, cs * 0.03f, Offset(e.x + dxh * cs * 0.06f - cs * 0.03f, e.y + dyh * cs * 0.06f - cs * 0.03f))
                    }
                }
                // ноздри
                listOf(1f, -1f).forEach { k -> drawCircle(Color(0xFF14306E), cs * 0.025f, Offset(head.x + (dxh * 0.36f + nx * 0.09f * k) * cs, head.y + (dyh * 0.36f + ny * 0.09f * k) * cs)) }
            }
            if (!started) GameOverlay("Змейка", "Смахивай в сторону поворота. Золотое яблоко — +5, исчезает быстро.", "Старт") { started = true }
            if (g.over) GameOverlay("Конец", "Длина ${g.body.size} · счёт ${g.score}", "Ещё раз") { g = SnakeGame(walls = walls); started = true }
        }
    }
}
