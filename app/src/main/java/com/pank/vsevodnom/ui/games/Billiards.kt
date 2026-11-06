package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

/**
 * Простой бильярд («американка»): любой шар в любую лузу засчитывается тому, кто бил.
 * Забил — бьёшь ещё. Свояк (биток в лузе) — переход хода и биток возвращается.
 * Побеждает тот, кто первым забьёт 8 шаров. Мир: 1 × 2 условных единицы.
 */
private class Ball(var x: Float, var y: Float, val n: Int) { var vx = 0f; var vy = 0f; var inPocket = false }

private class Table {
    val W = 1f; val H = 2f; val R = 0.032f
    val pockets = listOf(Offset(0f, 0f), Offset(W, 0f), Offset(0f, H / 2), Offset(W, H / 2), Offset(0f, H), Offset(W, H))
    val pocketR = R * 1.9f
    val balls = ArrayList<Ball>()
    val cue get() = balls[0]

    init {
        balls += Ball(W / 2, H * 0.78f, 0)
        var n = 1
        val apexY = H * 0.28f
        for (row in 0 until 5) for (k in 0..row) {
            val x = W / 2 + (k - row / 2f) * 2.02f * R
            val y = apexY - row * 2.02f * R * sqrt(3f) / 2f
            balls += Ball(x + Random.nextFloat() * 0.0006f, y, n++)
        }
    }

    val moving get() = balls.any { !it.inPocket && (abs(it.vx) > 1e-4f || abs(it.vy) > 1e-4f) }

    /** Шаг симуляции. Возвращает номера шаров, упавших в лузы за шаг. */
    fun step(dt: Float): List<Int> {
        val potted = ArrayList<Int>()
        val sub = 8
        val h = dt / sub
        repeat(sub) {
            for (b in balls) if (!b.inPocket) {
                b.x += b.vx * h; b.y += b.vy * h
                // трение сукна
                val sp = hypot(b.vx, b.vy)
                if (sp > 0) {
                    val ns = max(0f, sp - 0.55f * h)
                    b.vx *= ns / sp; b.vy *= ns / sp
                }
                // лузы
                for (p in pockets) if (hypot(b.x - p.x, b.y - p.y) < pocketR) { b.inPocket = true; b.vx = 0f; b.vy = 0f; potted += b.n; break }
                if (b.inPocket) continue
                // борта
                if (b.x < R) { b.x = R; b.vx = -b.vx * 0.82f }
                if (b.x > W - R) { b.x = W - R; b.vx = -b.vx * 0.82f }
                if (b.y < R) { b.y = R; b.vy = -b.vy * 0.82f }
                if (b.y > H - R) { b.y = H - R; b.vy = -b.vy * 0.82f }
            }
            // соударения шаров (равные массы, почти упругие)
            for (i in balls.indices) for (j in i + 1 until balls.size) {
                val a = balls[i]; val c = balls[j]
                if (a.inPocket || c.inPocket) continue
                val dx = c.x - a.x; val dy = c.y - a.y
                val d = hypot(dx, dy)
                if (d < 2 * R && d > 0) {
                    val nx = dx / d; val ny = dy / d
                    val overlap = 2 * R - d
                    a.x -= nx * overlap / 2; a.y -= ny * overlap / 2; c.x += nx * overlap / 2; c.y += ny * overlap / 2
                    val rel = (a.vx - c.vx) * nx + (a.vy - c.vy) * ny
                    if (rel > 0) {
                        val imp = rel * 0.96f
                        a.vx -= imp * nx; a.vy -= imp * ny; c.vx += imp * nx; c.vy += imp * ny
                    }
                }
            }
        }
        return potted
    }

    fun shoot(dirX: Float, dirY: Float, power: Float) {
        val l = hypot(dirX, dirY).takeIf { it > 0 } ?: return
        val v = 0.6f + power * 3.6f
        cue.vx = dirX / l * v; cue.vy = dirY / l * v
    }

    /** Возврат битка после свояка — на свободное место в «доме». */
    fun respawnCue() {
        val c = cue; c.inPocket = false; c.vx = 0f; c.vy = 0f
        var y = H * 0.78f; var x = W / 2
        var tries = 0
        while (balls.any { it !== c && !it.inPocket && hypot(it.x - x, it.y - y) < 2.2f * R } && tries++ < 200) {
            x = R + Random.nextFloat() * (W - 2 * R); y = H * 0.7f + Random.nextFloat() * H * 0.25f
        }
        c.x = x; c.y = y
    }

    private fun segClear(ax: Float, ay: Float, bx: Float, by: Float, skip: Set<Ball>): Boolean {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        for (o in balls) {
            if (o.inPocket || o in skip) continue
            val t = (((o.x - ax) * dx + (o.y - ay) * dy) / l2).coerceIn(0f, 1f)
            if (hypot(ax + dx * t - o.x, ay + dy * t - o.y) < 2 * R * 0.98f) return false
        }
        return true
    }

    /** Ход бота: перебор «шар → луза», выбор самого удобного удара. Возвращает (dx, dy, сила). */
    fun botShot(skill: Float): Triple<Float, Float, Float> {
        val c = cue
        var best: Triple<Float, Float, Float>? = null; var bestScore = -1e9f
        for (b in balls) {
            if (b.n == 0 || b.inPocket) continue
            for (p in pockets) {
                val px = p.x - b.x; val py = p.y - b.y; val pd = hypot(px, py)
                val gx = b.x - px / pd * 2 * R; val gy = b.y - py / pd * 2 * R   // точка «призрачного шара»
                val cx = gx - c.x; val cy = gy - c.y; val cd = hypot(cx, cy)
                val cosCut = (cx * px + cy * py) / (cd * pd)
                if (cosCut < 0.25f) continue
                if (!segClear(c.x, c.y, gx, gy, setOf(c, b)) || !segClear(b.x, b.y, p.x, p.y, setOf(b))) continue
                val score = cosCut * 3 - (cd + pd) * 0.8f
                if (score > bestScore) { bestScore = score; best = Triple(cx, cy, ((cd + pd) / 2.2f / cosCut).coerceIn(0.15f, 1f)) }
            }
        }
        val shot = best ?: run {
            // нет чистого удара — просто бьём в ближайший шар
            val t = balls.filter { it.n != 0 && !it.inPocket }.minByOrNull { hypot(it.x - c.x, it.y - c.y) } ?: return Triple(0f, -1f, 0.5f)
            Triple(t.x - c.x, t.y - c.y, 0.6f)
        }
        val ang = atan2(shot.second, shot.first) + (Random.nextFloat() - 0.5f) * (1f - skill) * 0.12f
        return Triple(cos(ang), sin(ang), shot.third)
    }
}

@Composable
fun BilliardsScreen(onBack: () -> Unit) {
    var gen by remember { mutableIntStateOf(0) }
    val t = remember(gen) { Table() }
    var frame by remember { mutableLongStateOf(0L) }
    var turn by remember(gen) { mutableIntStateOf(0) } // 0 — ты, 1 — бот
    val score = remember(gen) { mutableStateListOf(0, 0) }
    var aim by remember { mutableStateOf<Offset?>(null) }     // точка пальца в координатах стола
    var potThisShot by remember { mutableStateOf(false) }
    var foul by remember { mutableStateOf(false) }
    var shotActive by remember { mutableStateOf(false) }
    var msg by remember(gen) { mutableStateOf("Тяни назад от битка и отпускай") }
    var skill by remember { mutableFloatStateOf(0.6f) }
    val winner = when { score[0] >= 8 -> "Победа! 8 шаров твои"; score[1] >= 8 -> "Бот забил 8 — его победа"; else -> null }

    // физика
    LaunchedEffect(gen) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && shotActive) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.033f)
                    val pots = t.step(dt)
                    pots.forEach { n -> if (n == 0) foul = true else { score[turn]++; potThisShot = true } }
                    if (!t.moving) {
                        shotActive = false
                        if (foul) { t.respawnCue(); msg = if (turn == 0) "Свояк — ход бота" else "Бот положил свояка — твой ход"; turn = 1 - turn }
                        else if (!potThisShot) { turn = 1 - turn; msg = if (turn == 0) "Твой ход" else "Ход бота" }
                        else msg = if (turn == 0) "Забил! Бей ещё" else "Бот забил и бьёт ещё"
                        foul = false; potThisShot = false
                    }
                }
                last = now; frame = now
            }
        }
    }
    // бот
    LaunchedEffect(turn, shotActive, gen) {
        if (turn == 1 && !shotActive && winner == null) {
            delay(900)
            val (dx, dy, p) = t.botShot(skill)
            t.shoot(dx, dy, p); shotActive = true
        }
    }

    ScreenScaffold("Бильярд", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ты ${score[0]} : ${score[1]} Бот", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Chip("Бот: " + when { skill < 0.5f -> "слабый"; skill < 0.8f -> "средний"; else -> "сильный" }, false, {
                    skill = when { skill < 0.5f -> 0.6f; skill < 0.8f -> 0.92f; else -> 0.3f }
                })
            }
            Text(winner ?: msg, style = MaterialTheme.typography.bodyMedium, color = if (winner != null) Pal.LilacInk else Pal.Muted)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratio = t.W / t.H
                val w = minOf(maxWidth, maxHeight * ratio)
                Canvas(
                    Modifier.size(w, w / ratio).pointerInput(gen) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (turn != 0 || shotActive || winner != null) return@awaitEachGesture
                            val scale = size.width / t.W
                            fun toTable(o: Offset) = Offset(o.x / scale, o.y / scale)
                            aim = toTable(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.first()
                                if (!ch.pressed) break
                                aim = toTable(ch.position); ch.consume()
                            }
                            val a = aim
                            aim = null
                            if (a != null) {
                                val dx = t.cue.x - a.x; val dy = t.cue.y - a.y
                                val dist = hypot(dx, dy)
                                if (dist > t.R * 1.5f) { t.shoot(dx, dy, ((dist - t.R) / 0.6f).coerceIn(0.05f, 1f)); shotActive = true; msg = "" }
                            }
                        }
                    }
                ) {
                    frame.let { } // перерисовка каждый кадр
                    val s = size.width / t.W
                    fun P(x: Float, y: Float) = Offset(x * s, y * s)
                    drawRoundRect(Color(0xFFE8C9A8), size = size, cornerRadius = CornerRadius(18f * density / 2))
                    val inset = 8f
                    drawRoundRect(Color(0xFFBFE3CF), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(10f))
                    drawLine(Color.White.copy(alpha = 0.4f), P(0f, t.H * 0.75f), P(t.W, t.H * 0.75f), 2f)
                    t.pockets.forEach { drawCircle(Color(0xFF3C3A44), t.pocketR * s, P(it.x, it.y)) }
                    // прицел
                    val a = aim
                    if (a != null) {
                        val dx = t.cue.x - a.x; val dy = t.cue.y - a.y; val d = hypot(dx, dy)
                        if (d > 0.001f) {
                            val ux = dx / d; val uy = dy / d
                            drawLine(Pal.Ink.copy(alpha = 0.35f), P(t.cue.x, t.cue.y), P(t.cue.x + ux * 1.2f, t.cue.y + uy * 1.2f), 3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                            // кий
                            val back = 0.06f + (d - t.R).coerceIn(0f, 0.6f) * 0.4f
                            drawLine(Color(0xFF9A6B45), P(t.cue.x - ux * (t.R + back), t.cue.y - uy * (t.R + back)), P(t.cue.x - ux * (t.R + back + 0.9f), t.cue.y - uy * (t.R + back + 0.9f)), 9f)
                            val power = ((d - t.R) / 0.6f).coerceIn(0f, 1f)
                            drawRect(Pal.Card, Offset(12f, size.height - 30f), Size(size.width * 0.4f, 12f))
                            drawRect(Pal.PeachInk, Offset(12f, size.height - 30f), Size(size.width * 0.4f * power, 12f))
                        }
                    }
                    t.balls.forEach { b ->
                        if (b.inPocket) return@forEach
                        val c = P(b.x, b.y)
                        drawCircle(Color.Black.copy(alpha = 0.12f), t.R * s, c + Offset(2f, 3f))
                        drawCircle(if (b.n == 0) Color(0xFFFCD7B8) else Color(0xFFFFFBF2), t.R * s, c)
                        drawCircle(Color.White.copy(alpha = 0.8f), t.R * s * 0.3f, c + Offset(-t.R * s * 0.35f, -t.R * s * 0.35f))
                        if (b.n > 0) drawContext.canvas.nativeCanvas.drawText("${b.n}", c.x, c.y + t.R * s * 0.38f, android.graphics.Paint().apply {
                            color = android.graphics.Color.rgb(43, 42, 51); textSize = t.R * s * 1.05f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true
                        })
                        else drawCircle(Pal.PeachInk.copy(alpha = 0.6f), t.R * s, c, style = Stroke(2f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                PillButton("Новая партия", { gen++ }, Modifier.weight(1f))
            }
        }
    }
}
