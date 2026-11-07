package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.random.Random

/** Пара стеблей с просветом: верх — сверху до gapTop, низ — от gapTop+gap до земли. */
private class Gate(var x: Float, val gapTop: Float, var passed: Boolean = false)

private class FlyerGame {
    // мир: ширина ~100, высота 160, земля на 150
    var y = 70f; var vy = 0f
    var t = 0f
    val gates = ArrayList<Gate>()
    var score = 0
    var started = false; var over = false
    val gap = 42f; val gateW = 15f; val speed = 34f
    val x = 28f; val r = 5.2f
    fun flap() { if (!over) { started = true; vy = -62f } }
    fun step(dt: Float, w: Float) {
        t += dt
        if (!started) { y = 70f + kotlin.math.sin(t * 3) * 3; return }
        if (over) { if (y < 150 - r) { vy += 260f * dt; y = (y + vy * dt).coerceAtMost(150 - r) }; return }
        vy += 260f * dt; y += vy * dt
        if (gates.isEmpty() || gates.last().x < w - 58f) gates += Gate(w + 10, 22f + Random.nextFloat() * (150f - 44f - gap))
        gates.forEach { it.x -= speed * dt }
        gates.removeAll { it.x < -gateW - 5 }
        gates.forEach { g -> if (!g.passed && g.x + gateW < x) { g.passed = true; score++ } }
        if (y + r >= 150f || y - r <= 0f) over = true
        for (g in gates) {
            if (x + r > g.x && x - r < g.x + gateW && (y - r < g.gapTop || y + r > g.gapTop + gap)) {
                // точнее: круг против двух прямоугольников
                val cx = x.coerceIn(g.x, g.x + gateW)
                val topHit = run { val cy = y.coerceIn(0f, g.gapTop); (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r }
                val botHit = run { val cy = y.coerceIn(g.gapTop + gap, 150f); (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r }
                if (topHit || botHit) over = true
            }
        }
    }
}

@Composable
fun FlyerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(FlyerGame()) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "flyer")) }
    var worldW by remember { mutableFloatStateOf(100f) }
    var reported by remember { mutableStateOf(false) }
    GameLoop(true) { dt -> g.step(dt, worldW); tick++; if (g.over && !reported) { reported = true; if (Hi.submit(ctx, "flyer", g.score)) best = g.score } }
    ScreenScaffold("Пёрышко", onBack) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().pointerInput(g) { detectTapGestures(onPress = { if (!g.over) g.flap() }) }) {
                tick
                val s = size.height / 160f
                worldW = size.width / s
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDCEBFA), Color(0xFFF8E9F0))))
                // дальние холмы
                val hillOff = (g.t * 6f) % 60f
                var hx = -hillOff
                while (hx < worldW + 60) { drawCircle(Color(0xFFCFE6D6), 30f * s, Offset(hx * s, 150f * s)); hx += 60f }
                g.gates.forEach { gt ->
                    val stem = Color(0xFF9CC9A6); val bud = Color(0xFFE48AA8)
                    drawRoundRect(stem, Offset(gt.x * s, -10f), Size(g.gateW * s, gt.gapTop * s + 10f), CornerRadius(6f * s))
                    drawRoundRect(stem, Offset(gt.x * s, (gt.gapTop + g.gap) * s), Size(g.gateW * s, (150f - gt.gapTop - g.gap) * s), CornerRadius(6f * s))
                    // цветок-колокольчик на концах стеблей
                    drawCircle(bud, g.gateW * 0.62f * s, Offset((gt.x + g.gateW / 2) * s, gt.gapTop * s))
                    drawCircle(bud, g.gateW * 0.62f * s, Offset((gt.x + g.gateW / 2) * s, (gt.gapTop + g.gap) * s))
                }
                drawRect(Color(0xFFBFDDB8), Offset(0f, 150f * s), Size(size.width, size.height - 150f * s))
                // шмель: полосатое тельце, крылышки машут
                val cx = g.x * s; val cy = g.y * s; val r = g.r * s
                rotate((g.vy * 0.6f).coerceIn(-30f, 70f), Offset(cx, cy)) {
                    val flap = if ((g.t * 18).toInt() % 2 == 0) -0.7f else 0.2f
                    drawOval(Color.White.copy(alpha = 0.85f), Offset(cx - r * 0.6f, cy - r * (1.6f - flap)), Size(r * 1.1f, r * 0.9f))
                    drawOval(Color(0xFFF2C94C), Offset(cx - r * 1.2f, cy - r), Size(r * 2.4f, r * 2f))
                    drawRect(Color(0xFF3A3340), Offset(cx - r * 0.35f, cy - r * 0.95f), Size(r * 0.35f, r * 1.9f))
                    drawRect(Color(0xFF3A3340), Offset(cx + r * 0.3f, cy - r * 0.85f), Size(r * 0.3f, r * 1.7f))
                    drawCircle(Color.White, r * 0.35f, Offset(cx + r * 0.75f, cy - r * 0.25f))
                    drawCircle(Color(0xFF2B2A33), r * 0.17f, Offset(cx + r * 0.85f, cy - r * 0.25f))
                    drawPath(Path().apply { moveTo(cx - r * 1.2f, cy); lineTo(cx - r * 1.65f, cy - r * 0.15f); lineTo(cx - r * 1.2f, cy + r * 0.25f); close() }, Color(0xFF3A3340))
                }
                drawContext.canvas.nativeCanvas.apply {
                    val p = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 22f * s; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; setShadowLayer(6f, 0f, 2f, 0x55000000) }
                    if (g.started) drawText("${g.score}", size.width / 2, 26f * s, p)
                }
            }
            if (!g.started && !g.over) GameOverlay("Пёрышко", "Нажимай, чтобы шмель взлетал. Пролетай между стеблями. Рекорд: $best", "Полетели") { g.flap() }
            if (g.over) GameOverlay("Упс!", "Пролетел ${g.score} · рекорд $best", "Ещё раз") { g = FlyerGame(); reported = false }
        }
    }
}
