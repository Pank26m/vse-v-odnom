package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

private const val WORLD = 3000f
private val CELL_COLORS = listOf(Color(0xFFE5485D), Color(0xFFF2A33A), Color(0xFFF2C94C), Color(0xFF3FB27F), Color(0xFF4A86E8), Color(0xFF8E5BD8), Color(0xFFE48AA8), Color(0xFF2BB5B0))
private val BOT_NAMES = listOf("Бублик", "Кекс", "Пельмень", "Клякса", "Шарик", "Мармелад", "Капля", "Пузырь", "Желе", "Комета", "Ватрушка", "Нюша")

private class Blob(var x: Float, var y: Float, var mass: Float, val color: Color, val name: String, val bot: Boolean) {
    val r get() = sqrt(mass) * 4f
    var tx = x; var ty = y; var think = 0f
    var alive = true
}

private class CellsGame {
    val me = Blob(WORLD / 2, WORLD / 2, 20f, Color(0xFF6A55A8), "Я", false)
    val bots = ArrayList<Blob>()
    val food = ArrayList<Triple<Float, Float, Color>>()
    var aim = Offset.Zero   // направление пальца относительно центра экрана, 0…1
    var over by androidx.compose.runtime.mutableStateOf(false); var started by androidx.compose.runtime.mutableStateOf(false)
    var maxMass = 20f; var time = 0f
    init { repeat(500) { food += rf() }; repeat(14) { spawnBot() } }
    private fun rf() = Triple(Random.nextFloat() * WORLD, Random.nextFloat() * WORLD, CELL_COLORS.random())
    private fun spawnBot() {
        var x: Float; var y: Float
        do { x = Random.nextFloat() * WORLD; y = Random.nextFloat() * WORLD } while (hypot(x - me.x, y - me.y) < 500)
        bots += Blob(x, y, 10f + Random.nextFloat() * 60f + time * 0.4f, CELL_COLORS.random(), BOT_NAMES.random(), true)
    }
    private fun speed(b: Blob) = 260f / (1f + sqrt(b.mass) * 0.12f)

    fun step(dt: Float) {
        if (!started || over) return
        time += dt
        // игрок плывёт туда, куда смотрит палец
        val len = hypot(aim.x, aim.y)
        if (len > 0.05f) { val k = speed(me) * (len.coerceAtMost(1f)) * dt / len; me.x += aim.x * k; me.y += aim.y * k }
        me.x = me.x.coerceIn(me.r, WORLD - me.r); me.y = me.y.coerceIn(me.r, WORLD - me.r)
        // боты: бегут от больших, гонятся за меньшими, иначе едят корм
        val all = bots + me
        for (b in bots) {
            b.think -= dt
            if (b.think <= 0) {
                b.think = 0.3f + Random.nextFloat() * 0.3f
                val threat = all.filter { it !== b && it.mass > b.mass * 1.15f && hypot(it.x - b.x, it.y - b.y) < it.r + 260 }.minByOrNull { hypot(it.x - b.x, it.y - b.y) }
                val prey = all.filter { it !== b && b.mass > it.mass * 1.15f && hypot(it.x - b.x, it.y - b.y) < b.r + 320 }.minByOrNull { hypot(it.x - b.x, it.y - b.y) }
                when {
                    threat != null -> { b.tx = b.x + (b.x - threat.x) * 3; b.ty = b.y + (b.y - threat.y) * 3 }
                    prey != null -> { b.tx = prey.x; b.ty = prey.y }
                    else -> food.minByOrNull { hypot(it.first - b.x, it.second - b.y) }?.let { b.tx = it.first; b.ty = it.second }
                }
            }
            val dx = b.tx - b.x; val dy = b.ty - b.y; val d = hypot(dx, dy)
            if (d > 1) { val k = speed(b) * 0.92f * dt / d; b.x += dx * k; b.y += dy * k }
            b.x = b.x.coerceIn(b.r, WORLD - b.r); b.y = b.y.coerceIn(b.r, WORLD - b.r)
        }
        // корм
        for (b in all) {
            val it2 = food.iterator()
            while (it2.hasNext()) { val f = it2.next(); if (hypot(f.first - b.x, f.second - b.y) < b.r) { it2.remove(); b.mass += 1f } }
        }
        while (food.size < 500) food += rf()
        // кто кого съел: нужно быть больше на 15% и накрыть центр
        for (a in all) for (b in all) {
            if (a === b || !a.alive || !b.alive) continue
            if (a.mass > b.mass * 1.15f && hypot(a.x - b.x, a.y - b.y) < a.r - b.r * 0.4f) { a.mass += b.mass * 0.8f; b.alive = false }
        }
        if (!me.alive) over = true
        bots.removeAll { !it.alive }
        while (bots.size < 14) spawnBot()
        // медленное «похудение» больших, чтобы не росли бесконечно
        all.forEach { if (it.mass > 100) it.mass -= it.mass * 0.002f * dt }
        maxMass = maxOf(maxMass, me.mass)
    }
}

@Composable
fun CellsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(CellsGame()) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "cells")) }
    GameLoop(g.started && !g.over) { dt -> g.step(dt); tick++; if (g.over && Hi.submit(ctx, "cells", g.maxMass.toInt())) best = g.maxMass.toInt() }
    ScreenScaffold("Клетки", onBack) {
        ScoreBar(g.me.mass.toInt(), best, "размер")
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().pointerInput(g) {
                awaitEachGesture {
                    val d = awaitFirstDown(); g.started = true
                    fun set(p: Offset) { g.aim = Offset((p.x - size.width / 2f) / (size.width / 3f), (p.y - size.height / 2f) / (size.width / 3f)) }
                    set(d.position)
                    while (true) { val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break; if (!c.pressed) break; set(c.position) }
                }
            }) {
                tick
                // масштаб: чем больше клетка, тем дальше видно
                val zoom = (size.width / (900f + g.me.r * 6f))
                fun sx(x: Float) = (x - g.me.x) * zoom + size.width / 2
                fun sy(y: Float) = (y - g.me.y) * zoom + size.height / 2
                drawRect(Color(0xFFF7F6FB))
                val step = 60f
                var gx = ((g.me.x - size.width / 2 / zoom) / step).toInt() * step
                while (sx(gx) < size.width) { if (gx in 0f..WORLD) drawLine(Color(0xFFE4E1EE), Offset(sx(gx), 0f), Offset(sx(gx), size.height), 1f); gx += step }
                var gy = ((g.me.y - size.height / 2 / zoom) / step).toInt() * step
                while (sy(gy) < size.height) { if (gy in 0f..WORLD) drawLine(Color(0xFFE4E1EE), Offset(0f, sy(gy)), Offset(size.width, sy(gy)), 1f); gy += step }
                drawRect(Color(0xFFB8BCC6), Offset(sx(0f), sy(0f)), androidx.compose.ui.geometry.Size(WORLD * zoom, WORLD * zoom), style = Stroke(4f))
                g.food.forEach { (x, y, c) -> val px = sx(x); val py = sy(y); if (px in -10f..size.width + 10 && py in -10f..size.height + 10) drawCircle(c, 5f * zoom, Offset(px, py)) }
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
                (g.bots + g.me).sortedBy { it.mass }.forEach { b ->
                    val p = Offset(sx(b.x), sy(b.y)); val r = b.r * zoom
                    if (p.x + r < 0 || p.x - r > size.width || p.y + r < 0 || p.y - r > size.height) return@forEach
                    drawCircle(b.color, r, p)
                    drawCircle(b.color.copy(alpha = 0.6f).let { Color(it.red * 0.75f, it.green * 0.75f, it.blue * 0.75f) }, r, p, style = Stroke(r * 0.08f + 2f))
                    if (r > 14) { paint.textSize = (r * 0.38f).coerceIn(12f, 60f); drawContext.canvas.nativeCanvas.drawText(b.name, p.x, p.y + paint.textSize / 3, paint) }
                }
                // топ-5
                val top = (g.bots + g.me).sortedByDescending { it.mass }.take(5)
                val tp = android.graphics.Paint().apply { textSize = 34f; isAntiAlias = true; color = 0xFF6B6A75.toInt() }
                top.forEachIndexed { i, b ->
                    tp.isFakeBoldText = b === g.me
                    drawContext.canvas.nativeCanvas.drawText("${i + 1}. ${b.name} · ${b.mass.toInt()}", size.width - 300f, 50f + i * 42f, tp)
                }
            }
            if (!g.started) GameOverlay("Клетки", "Веди палец от центра экрана — клетка плывёт туда. Ешь корм и тех, кто меньше тебя. Больших — избегай.", "Старт") { g.started = true }
            if (g.over) GameOverlay("Тебя съели", "Макс. размер ${g.maxMass.toInt()} · рекорд $best", "Ещё раз") { g = CellsGame().apply { started = true } }
        }
    }
}
