package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.random.Random

/** Препятствие: камень на земле или летучка на уровне головы (под ней надо пригнуться). */
private class Ob(var x: Float, val w: Float, val h: Float, val flying: Boolean, val alt: Float)

private class RunnerGame {
    // мир в условных единицах: высота 100, земля на y = 80
    var y = 0f; var vy = 0f; var ducking = false
    var speed = 38f; var dist = 0f
    val obs = ArrayList<Ob>()
    var nextOb = 60f
    var over = false; var started = false
    var clouds = MutableList(4) { Offset(Random.nextFloat() * 200, 10f + Random.nextFloat() * 30) }
    val score get() = (dist / 4).toInt()
    val onGround get() = y <= 0f

    fun jump() { if (onGround && !over) vy = 92f }
    fun step(dt: Float, width: Float) {
        if (!started || over) return
        speed = (38f + dist / 90f).coerceAtMost(95f)
        dist += speed * dt
        // прыжок: при удержании «вниз» в воздухе падаем быстрее
        vy -= (if (ducking && !onGround) 520f else 260f) * dt
        y = (y + vy * dt).coerceAtLeast(0f); if (y == 0f) vy = 0f
        obs.forEach { it.x -= speed * dt }
        obs.removeAll { it.x + it.w < -10 }
        clouds = clouds.map { c -> Offset(if (c.x < -30) width + 20 else c.x - speed * 0.15f * dt, c.y) }.toMutableList()
        nextOb -= speed * dt
        if (nextOb <= 0) {
            val flying = dist > 400 && Random.nextFloat() < 0.3f
            obs += if (flying) Ob(width + 10, 9f, 6f, true, listOf(10f, 20f).random())
            else Ob(width + 10, 5f + Random.nextFloat() * 9f, 7f + Random.nextFloat() * 8f, false, 0f)
            nextOb = 38f + Random.nextFloat() * 45f + speed * 0.35f
        }
        // столкновение: прямоугольник героя против препятствий (с небольшим запасом)
        val heroH = if (ducking && onGround) 7f else 13f
        val hx = 14f; val hw = if (ducking && onGround) 13f else 9f
        val hb = y; val ht = y + heroH
        for (o in obs) {
            val ob = if (o.flying) o.alt else 0f; val ot = ob + o.h
            if (hx + hw - 1.5f > o.x && hx + 1.5f < o.x + o.w && ht - 1f > ob && hb + 1f < ot) { over = true; break }
        }
    }
}

@Composable
fun RunnerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(RunnerGame()) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "runner")) }
    var worldW by remember { mutableFloatStateOf(200f) }
    GameLoop(g.started && !g.over) { dt -> g.step(dt, worldW); tick++; if (g.over) { Hi.submit(ctx, "runner", g.score); best = Hi.get(ctx, "runner") } }
    ScreenScaffold("Бегун", onBack) {
        ScoreBar(g.score, best)
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().pointerInput(g) {
                // нижняя треть экрана — пригнуться (пока держишь), остальное — прыжок
                awaitEachGesture {
                    val d = awaitFirstDown()
                    if (!g.started) g.started = true
                    if (d.position.y > size.height * 0.66f) {
                        g.ducking = true
                        while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break }
                        g.ducking = false
                    } else g.jump()
                }
            }) {
                tick
                val s = size.height / 100f
                worldW = size.width / s
                val night = ((g.score / 700) % 2 == 1)
                drawRect(if (night) Color(0xFF2E3350) else Color(0xFFEAF3FB))
                val groundY = 80f * s
                g.clouds.forEach { c -> drawRoundRect(Color.White.copy(alpha = if (night) 0.15f else 0.9f), Offset(c.x * s, c.y * s), Size(22f * s, 6f * s), CornerRadius(3f * s)) }
                drawRect(if (night) Color(0xFF41476B) else Color(0xFFDCEFD9), Offset(0f, groundY), Size(size.width, size.height - groundY))
                // полоски земли бегут
                val off = (g.dist % 12f) * s
                var x = -off; while (x < size.width) { drawRect(Color(0x22000000), Offset(x, groundY + 4 * s), Size(4 * s, 0.6f * s)); x += 12 * s }
                // препятствия
                g.obs.forEach { o ->
                    if (o.flying) {
                        val cy = groundY - (o.alt + o.h / 2) * s; val cx = (o.x + o.w / 2) * s
                        drawCircle(Color(0xFF8E5BD8), o.h / 2 * s, Offset(cx, cy))
                        val wing = if ((g.dist / 6).toInt() % 2 == 0) -1f else 1f
                        drawLine(Color(0xFF8E5BD8), Offset(cx - o.w / 2 * s, cy + wing * 2 * s), Offset(cx + o.w / 2 * s, cy - wing * 2 * s), 1.6f * s)
                        drawCircle(Color.White, 0.9f * s, Offset(cx - 1.5f * s, cy - 0.8f * s))
                    } else {
                        val p = Path().apply {
                            moveTo(o.x * s, groundY); lineTo((o.x + o.w * 0.2f) * s, groundY - o.h * s)
                            lineTo((o.x + o.w * 0.75f) * s, groundY - o.h * 0.85f * s); lineTo((o.x + o.w) * s, groundY); close()
                        }
                        drawPath(p, Color(0xFFB0A898))
                    }
                }
                // герой: круглый зверёк с ушками, бегущие лапки
                val duck = g.ducking && g.onGround
                val hx = 14f * s; val hw = (if (duck) 13f else 9f) * s; val hh = (if (duck) 7f else 13f) * s
                val bottom = groundY - g.y * s
                val body = lerp(Color(0xFFF2A33A), Color(0xFFE8711A), 0.2f)
                drawRoundRect(body, Offset(hx, bottom - hh), Size(hw, hh), CornerRadius(hw / 2.2f))
                if (!duck) { drawCircle(body, 1.8f * s, Offset(hx + 2 * s, bottom - hh)); drawCircle(body, 1.8f * s, Offset(hx + hw - 2 * s, bottom - hh)) }
                drawCircle(Color.White, 1.6f * s, Offset(hx + hw * 0.72f, bottom - hh * 0.7f))
                drawCircle(Color(0xFF2B2A33), 0.8f * s, Offset(hx + hw * 0.76f, bottom - hh * 0.7f))
                val leg = if (g.onGround && (g.dist / 3).toInt() % 2 == 0) 1.5f * s else 0f
                drawLine(Color(0xFF8A5212), Offset(hx + hw * 0.3f, bottom), Offset(hx + hw * 0.3f - leg, bottom + 1.5f * s), 1.4f * s)
                drawLine(Color(0xFF8A5212), Offset(hx + hw * 0.7f, bottom), Offset(hx + hw * 0.7f + leg, bottom + 1.5f * s), 1.4f * s)
            }
            if (!g.started) GameOverlay("Бегун", "Нажми — прыжок. Держи палец внизу экрана — пригнуться под летучкой.", "Старт") { g.started = true }
            if (g.over) GameOverlay("Врезался!", "Счёт ${g.score} · рекорд $best", "Ещё раз") { g = RunnerGame().apply { started = true } }
        }
    }
}
