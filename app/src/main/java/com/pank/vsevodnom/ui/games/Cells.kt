package com.pank.vsevodnom.ui.games

import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
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
/** Победа: клетка стала такой, что занимает заметную часть чашки (радиус ≈ 1/6 поля). */
internal const val CELLS_WIN = 14000f
private const val FOOD = 380
private const val BOTS = 18
/** Безопасное ограничение координаты: у огромной клетки r > WORLD/2 и обычный coerceIn падал (так вылетало приложение). */
private fun clampIn(v: Float, r: Float): Float { val rr = r.coerceAtMost(WORLD / 2); return v.coerceIn(rr, WORLD - rr) }
private val CELL_COLORS = listOf(Color(0xFFE5485D), Color(0xFFF2A33A), Color(0xFFF2C94C), Color(0xFF3FB27F), Color(0xFF4A86E8), Color(0xFF8E5BD8), Color(0xFFE48AA8), Color(0xFF2BB5B0))
private val BOT_NAMES = listOf("Бублик", "Кекс", "Пельмень", "Клякса", "Шарик", "Мармелад", "Капля", "Пузырь", "Желе", "Комета", "Ватрушка", "Нюша")

private class Blob(var x: Float, var y: Float, var mass: Float, val color: Color, val name: String, val bot: Boolean) {
    val r get() = sqrt(mass) * 4f
    var tx = x; var ty = y; var think = 0f
    var alive = true
    var vx = 0f; var vy = 0f          // толчок после деления
    var mergeIn = 0f                   // через сколько секунд кусок может слиться обратно
    var dashIn = 0f                    // бот: перезарядка рывка
}

private class CellsGame {
    val me = Blob(WORLD / 2, WORLD / 2, 20f, Color(0xFF6A55A8), "Я", false)
    val bots = ArrayList<Blob>()
    val food = ArrayList<Triple<Float, Float, Color>>()
    var aim = Offset.Zero   // направление пальца относительно центра экрана, 0…1
    var over by androidx.compose.runtime.mutableStateOf(false); var started by androidx.compose.runtime.mutableStateOf(false)
    var won by androidx.compose.runtime.mutableStateOf(false)
    var maxMass = 20f; var time = 0f
    private fun rf() = Triple(Random.nextFloat() * WORLD, Random.nextFloat() * WORLD, CELL_COLORS.random())
    private fun spawnBot() {
        var x: Float; var y: Float
        do { x = Random.nextFloat() * WORLD; y = Random.nextFloat() * WORLD } while (hypot(x - me.x, y - me.y) < 500)
        // со временем приходят всё более крупные — и иногда «хищник» крупнее тебя
        val big = if (Random.nextFloat() < 0.15f) mass * (0.8f + Random.nextFloat() * 0.6f) else 0f
        bots += Blob(x, y, maxOf(12f + Random.nextFloat() * 70f + time * 0.9f, big), CELL_COLORS.random(), BOT_NAMES.random(), true)
    }
    private fun speed(b: Blob) = 260f / (1f + sqrt(b.mass) * 0.12f)

    /** Мои куски: после деления их несколько, потом они снова сливаются. */
    val mine = arrayListOf(me)
    val mass get() = mine.filter { it.alive }.sumOf { it.mass.toDouble() }.toFloat()
    val cx get() = mine.filter { it.alive }.let { l -> l.sumOf { (it.x * it.mass).toDouble() } / l.sumOf { it.mass.toDouble() }.coerceAtLeast(1.0) }.toFloat()
    val cy get() = mine.filter { it.alive }.let { l -> l.sumOf { (it.y * it.mass).toDouble() } / l.sumOf { it.mass.toDouble() }.coerceAtLeast(1.0) }.toFloat()
    val biggest get() = mine.filter { it.alive }.maxByOrNull { it.mass } ?: me
    // после mine: spawnBot смотрит на мой размер
    init { repeat(FOOD) { food += rf() }; repeat(BOTS) { spawnBot() } }

    /** «Выстрел» половиной себя в сторону джойстика (как в оригинале — деление). */
    fun split() {
        if (!started || over || won) return
        val len = hypot(aim.x, aim.y).takeIf { it > 0.05f } ?: return
        val ux = aim.x / len; val uy = aim.y / len
        val add = ArrayList<Blob>()
        for (p in mine.filter { it.alive && it.mass >= 36f }) {
            if (mine.size + add.size >= 8) break
            p.mass /= 2
            val q = Blob(p.x + ux * p.r, p.y + uy * p.r, p.mass, p.color, p.name, false)
            q.vx = ux * 900f; q.vy = uy * 900f
            q.mergeIn = 12f; p.mergeIn = 12f
            add += q
        }
        mine += add
    }

    fun step(dt: Float) {
        if (!started || over || won) return
        time += dt
        // мои куски плывут туда, куда наклонён джойстик, плюс толчок после деления
        val len = hypot(aim.x, aim.y)
        for (p in mine) {
            if (!p.alive) continue
            if (len > 0.05f) { val k = speed(p) * (len.coerceAtMost(1f)) * dt / len; p.x += aim.x * k; p.y += aim.y * k }
            p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= (1f - 4f * dt).coerceAtLeast(0f); p.vy *= (1f - 4f * dt).coerceAtLeast(0f)
            p.mergeIn = (p.mergeIn - dt).coerceAtLeast(0f)
            p.x = clampIn(p.x, p.r); p.y = clampIn(p.y, p.r)
        }
        // куски не наезжают друг на друга, пока не пришло время слиться
        for (i in mine.indices) for (j in i + 1 until mine.size) {
            val a = mine[i]; val b = mine[j]; if (!a.alive || !b.alive) continue
            val d = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(0.01f)
            if (a.mergeIn <= 0f && b.mergeIn <= 0f) { if (d < maxOf(a.r, b.r)) { a.mass += b.mass; b.alive = false } }
            else if (d < a.r + b.r) { val push = (a.r + b.r - d) / 2; val ux = (a.x - b.x) / d; val uy = (a.y - b.y) / d; a.x += ux * push; a.y += uy * push; b.x -= ux * push; b.y -= uy * push }
        }
        mine.removeAll { !it.alive && it !== me }
        // боты: бегут от больших, гонятся за меньшими, иначе едят корм
        val all = bots + mine.filter { it.alive }
        for (b in bots) {
            b.think -= dt
            if (b.think <= 0) {
                b.think = 0.15f + Random.nextFloat() * 0.2f
                val threat = all.filter { it !== b && it.mass > b.mass * 1.15f && hypot(it.x - b.x, it.y - b.y) < it.r + 300 }.minByOrNull { hypot(it.x - b.x, it.y - b.y) }
                // охотятся охотнее на тебя: твои куски «ближе» на 40%
                val prey = all.filter { it !== b && b.mass > it.mass * 1.15f && hypot(it.x - b.x, it.y - b.y) < b.r + 480 }
                    .minByOrNull { hypot(it.x - b.x, it.y - b.y) * (if (it.bot) 1f else 0.6f) }
                when {
                    threat != null -> { b.tx = b.x + (b.x - threat.x) * 3; b.ty = b.y + (b.y - threat.y) * 3 }
                    prey != null -> {
                        // упреждение: целятся туда, куда добыча плывёт
                        b.tx = prey.x + (if (prey.bot) 0f else aim.x * 120f); b.ty = prey.y + (if (prey.bot) 0f else aim.y * 120f)
                        val d = hypot(prey.x - b.x, prey.y - b.y)
                        if (b.dashIn <= 0f && b.mass > prey.mass * 2.2f && d < b.r + 260 && d > 1f) {
                            b.vx = (prey.x - b.x) / d * 800f; b.vy = (prey.y - b.y) / d * 800f; b.mass *= 0.95f; b.dashIn = 6f
                        }
                    }
                    else -> food.minByOrNull { hypot(it.first - b.x, it.second - b.y) }?.let { b.tx = it.first; b.ty = it.second }
                }
            }
            val dx = b.tx - b.x; val dy = b.ty - b.y; val d = hypot(dx, dy)
            if (d > 1) { val k = speed(b) * dt / d; b.x += dx * k; b.y += dy * k }
            b.x += b.vx * dt; b.y += b.vy * dt; b.vx *= (1f - 4f * dt).coerceAtLeast(0f); b.vy *= (1f - 4f * dt).coerceAtLeast(0f)
            b.dashIn -= dt
            b.x = clampIn(b.x, b.r); b.y = clampIn(b.y, b.r)
        }
        // корм
        for (b in all) {
            val it2 = food.iterator()
            while (it2.hasNext()) { val f = it2.next(); if (hypot(f.first - b.x, f.second - b.y) < b.r) { it2.remove(); b.mass += 1f } }
        }
        while (food.size < FOOD) food += rf()
        // кто кого съел: нужно быть больше на 15% и накрыть центр (свои куски друг друга не едят)
        for (a in all) for (b in all) {
            if (a === b || !a.alive || !b.alive) continue
            if (!a.bot && !b.bot) continue
            if (a.mass > b.mass * 1.15f && hypot(a.x - b.x, a.y - b.y) < a.r - b.r * 0.4f) { a.mass += b.mass * 0.8f; b.alive = false }
        }
        if (mine.none { it.alive }) over = true
        mine.removeAll { !it.alive && it !== me }
        if (!me.alive && mine.any { it.alive }) { val n = mine.first { it.alive }; mine.remove(n); mine.add(0, n) }
        bots.removeAll { !it.alive }
        while (bots.size < BOTS) spawnBot()
        // «похудение» больших — чем больше, тем быстрее; без этого игра становилась слишком лёгкой
        (bots + mine).forEach { if (it.mass > 100) it.mass -= it.mass * (0.002f + sqrt(it.mass) * 0.00004f) * dt }
        maxMass = maxOf(maxMass, mass)
        if (mass >= CELLS_WIN) won = true
    }
}

@Composable
fun CellsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(CellsGame()) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "cells")) }
    GameLoop(g.started && !g.over && !g.won) { dt -> g.step(dt); tick++; if ((g.over || g.won) && Hi.submit(ctx, "cells", g.maxMass.toInt())) best = g.maxMass.toInt() }
    ScreenScaffold("Клетки", onBack) {
        ScoreBar(g.mass.toInt(), best, "до победы ${CELLS_WIN.toInt()}" + if (g.mine.size > 1) " · кусков ${g.mine.size}" else "")
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                tick
                // масштаб: чем больше клетка, тем дальше видно
                val zoom = (size.width / (900f + sqrt(g.mass) * 24f))
                val camX = g.cx; val camY = g.cy
                fun sx(x: Float) = (x - camX) * zoom + size.width / 2
                fun sy(y: Float) = (y - camY) * zoom + size.height / 2
                drawRect(Color(0xFFF7F6FB))
                val step = 60f
                var gx = ((g.me.x - size.width / 2 / zoom) / step).toInt() * step
                while (sx(gx) < size.width) { if (gx in 0f..WORLD) drawLine(Color(0xFFE4E1EE), Offset(sx(gx), 0f), Offset(sx(gx), size.height), 1f); gx += step }
                var gy = ((g.me.y - size.height / 2 / zoom) / step).toInt() * step
                while (sy(gy) < size.height) { if (gy in 0f..WORLD) drawLine(Color(0xFFE4E1EE), Offset(0f, sy(gy)), Offset(size.width, sy(gy)), 1f); gy += step }
                drawRect(Color(0xFFB8BCC6), Offset(sx(0f), sy(0f)), androidx.compose.ui.geometry.Size(WORLD * zoom, WORLD * zoom), style = Stroke(4f))
                g.food.forEach { (x, y, c) -> val px = sx(x); val py = sy(y); if (px in -10f..size.width + 10 && py in -10f..size.height + 10) drawCircle(c, 5f * zoom, Offset(px, py)) }
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
                (g.bots + g.mine.filter { it.alive }).sortedBy { it.mass }.forEach { b ->
                    val p = Offset(sx(b.x), sy(b.y)); val r = b.r * zoom
                    if (p.x + r < 0 || p.x - r > size.width || p.y + r < 0 || p.y - r > size.height) return@forEach
                    drawCircle(b.color, r, p)
                    drawCircle(b.color.copy(alpha = 0.6f).let { Color(it.red * 0.75f, it.green * 0.75f, it.blue * 0.75f) }, r, p, style = Stroke(r * 0.08f + 2f))
                    if (r > 14) { paint.textSize = (r * 0.38f).coerceIn(12f, 60f); drawContext.canvas.nativeCanvas.drawText(b.name, p.x, p.y + paint.textSize / 3, paint) }
                }
                // топ-5
                val top = (g.bots + Blob(0f, 0f, g.mass, g.me.color, g.me.name, false)).sortedByDescending { it.mass }.take(5)
                val tp = android.graphics.Paint().apply { textSize = 34f; isAntiAlias = true; color = 0xFF6B6A75.toInt() }
                top.forEachIndexed { i, b ->
                    tp.isFakeBoldText = !b.bot
                    drawContext.canvas.nativeCanvas.drawText("${i + 1}. ${b.name} · ${b.mass.toInt()}", size.width - 300f, 50f + i * 42f, tp)
                }
            }
            // джойстик слева внизу
            var knob by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.align(Alignment.BottomStart).padding(24.dp).size(150.dp).clip(CircleShape).background(Color(0x223A3F4D)).pointerInput(g) {
                awaitEachGesture {
                    val d = awaitFirstDown(); g.started = true
                    val c = Offset(size.width / 2f, size.height / 2f); val rad = size.width / 2f
                    fun set(p: Offset) { val v = p - c; val l = v.getDistance(); val k = if (l > rad) rad / l else 1f; knob = v * k; g.aim = Offset(v.x * k / rad, v.y * k / rad) }
                    set(d.position)
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull() ?: break; if (!ch.pressed) break; ch.consume(); set(ch.position) }
                    knob = Offset.Zero; g.aim = Offset.Zero
                }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(knob.x.toInt(), knob.y.toInt()) }.size(60.dp).clip(CircleShape).background(Color(0x996A55A8)))
            }
            // деление — «выстрел» половиной себя
            Box(Modifier.align(Alignment.BottomEnd).padding(32.dp).size(84.dp).clip(CircleShape).background(Color(0xCCE5485D)).pointerInput(g) {
                detectTapGestures { g.started = true; g.split() }
            }, contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontSize = 30.sp) }
            if (!g.started) GameOverlay("Клетки", "Джойстиком слева — куда плыть. Красная кнопка — выстрелить половиной себя: так догоняют добычу. Через 12 секунд куски сливаются обратно.", "Старт") { g.started = true }
            if (g.won) VictoryOverlay("Ты съел чашку", "Размер ${g.maxMass.toInt()} за ${(g.time / 60).toInt()} мин ${(g.time % 60).toInt()} с · рекорд $best", "Ещё раз") { g = CellsGame().apply { started = true } }
            if (g.over) GameOverlay("Тебя съели", "Макс. размер ${g.maxMass.toInt()} · рекорд $best", "Ещё раз") { g = CellsGame().apply { started = true } }
        }
    }
}
