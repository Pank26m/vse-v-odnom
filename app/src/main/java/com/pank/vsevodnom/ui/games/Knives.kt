package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Ножи»: крутится мишень, нажатие — бросок ножа снизу. Нож втыкается; попал в уже торчащий нож — конец.
 * Яблоки на краю — бонус. Этап пройден, когда брошены все ножи этапа; каждый 5-й этап — босс
 * (своя мишень, хитрое вращение, заранее воткнутые ножи). Вращение по этапам: ровное, с ускорением,
 * «качели» (смена направления), рывки «стоп-старт».
 */
class KnivesGame(private val rnd: Random = Random) {
    companion object {
        /** Угловая ширина ножа: ближе — столкновение (градусы). */
        const val KNIFE_GAP = 11f
        const val APPLE_GAP = 10f
        val BOSSES = listOf("Кремень", "Ледяной щит", "Пень-великан", "Шестерня", "Лава")
    }
    var stage = 1
    var score = 0; var apples = 0
    var knivesLeft = 0
    /** Углы воткнутых ножей относительно мишени (0° — вниз в системе мишени). */
    val stuck = ArrayList<Float>()
    val applesOn = ArrayList<Float>()
    var angle = 0f            // поворот мишени, градусы
    private var t = 0f        // время этапа
    private var pattern = 0
    var flying = -1f          // прогресс полёта ножа 0…1 (−1 — не летит)
    var over by mutableStateOf(false)
    var cleared = 0f          // >0 — анимация развала мишени после этапа
    var bounce = -1f          // анимация отскока при неудаче
    /** Разрезанные яблоки: угол на экране, время. */
    val cut = ArrayList<Pair<Float, Float>>()
    var hitFlash = 0f
    val boss get() = stage % 5 == 0

    init { setup() }

    private fun setup() {
        stuck.clear(); applesOn.clear(); t = 0f; angle = rnd.nextFloat() * 360f; cleared = 0f
        knivesLeft = (5 + stage / 2).coerceAtMost(11) + if (boss) 2 else 0
        pattern = if (boss) 4 + (stage / 5) % 2 else (stage - 1) % 4
        val pre = when { boss -> 3 + (stage / 10).coerceAtMost(3); stage <= 2 -> 0; else -> (stage / 3).coerceAtMost(4) }
        repeat(pre) { var a: Float; var tries = 0; do { a = rnd.nextFloat() * 360f; tries++ } while (stuck.any { gap(it, a) < 30f } && tries < 50); stuck += a }
        val ap = if (rnd.nextFloat() < 0.6f) 1 + (if (rnd.nextFloat() < 0.3f) 1 else 0) else 0
        repeat(ap) { var a: Float; var tries = 0; do { a = rnd.nextFloat() * 360f; tries++ } while ((stuck + applesOn).any { gap(it, a) < 25f } && tries < 50); applesOn += a }
    }

    private fun gap(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 540f) % 360f - 180f); return d }

    /** Скорость вращения (градусов/с) по узору этапа. */
    private fun speed(): Float {
        val base = 120f + stage * 4f
        return when (pattern) {
            0 -> base
            1 -> base * (0.6f + 0.8f * ((sin(t * 1.3f) + 1f) / 2f))                      // волна
            2 -> base * (if ((t / 2.2f).toInt() % 2 == 0) 1f else -1.15f)               // качели
            3 -> if ((t % 2.4f) < 1.6f) base * 1.3f else base * 0.15f                    // стоп-старт
            4 -> base * 1.25f * (if ((t / 1.6f).toInt() % 2 == 0) 1f else -1f) * (0.7f + 0.6f * abs(sin(t * 2f)))   // босс: рывки с разворотами
            else -> base * (1f + 0.9f * sin(t * 2.6f))                                    // босс: «маятник»
        }
    }

    fun throwKnife() { if (over || flying >= 0 || cleared > 0 || bounce >= 0 || knivesLeft <= 0) return; flying = 0f }

    /** Шаг; возвращает событие: 0 нет, 1 воткнулся, 2 яблоко, 3 этап, 4 проигрыш. */
    fun step(dt: Float): Int {
        hitFlash = (hitFlash - dt * 4).coerceAtLeast(0f)
        cut.replaceAll { it.first to it.second + dt }; cut.removeAll { it.second > 0.8f }
        if (bounce >= 0) { bounce += dt; if (bounce > 0.9f) { over = true }; angle += speed() * dt * 0.3f; return 0 }
        if (cleared > 0) { cleared += dt; if (cleared > 1.0f) { stage++; setup() }; return 0 }
        t += dt; angle = (angle + speed() * dt) % 360f
        var ev = 0
        if (flying >= 0) {
            flying += dt / 0.11f
            if (flying >= 1f) {
                flying = -1f
                // нож пришёл снизу: в системе мишени это угол (−angle)
                val a = ((360f - angle) % 360f + 360f) % 360f
                if (stuck.any { gap(it, a) < KNIFE_GAP }) { bounce = 0f; return 4 }
                stuck += a; knivesLeft--; score++; hitFlash = 1f; ev = 1
                applesOn.firstOrNull { gap(it, a) < APPLE_GAP }?.let { applesOn.remove(it); apples++; score += 2; cut += a to 0f; ev = 2 }
                if (knivesLeft == 0) { cleared = 0.001f; ev = 3 }
            }
        }
        return ev
    }
}

@Composable
fun KnivesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("knives", android.content.Context.MODE_PRIVATE) }
    var g by remember { mutableStateOf(KnivesGame()) }
    var started by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "knives")) }
    var bestStage by remember { mutableIntStateOf(prefs.getInt("stage", 0)) }
    var totalApples by remember { mutableIntStateOf(prefs.getInt("apples", 0)) }
    GameLoop(started && !g.over) { dt ->
        val ev = g.step(dt.coerceAtMost(0.04f)); tick++
        when (ev) {
            1 -> com.pank.vsevodnom.ui.random.Sfx.playPcm("knife", KnifeSfx.thunk)
            2 -> { com.pank.vsevodnom.ui.random.Sfx.playPcm("knife", KnifeSfx.apple); totalApples++; prefs.edit().putInt("apples", totalApples).apply() }
            3 -> { com.pank.vsevodnom.ui.random.Sfx.playPcm("knife", KnifeSfx.crack); if (g.stage > bestStage) { bestStage = g.stage; prefs.edit().putInt("stage", bestStage).apply() } }
            4 -> com.pank.vsevodnom.ui.random.Sfx.playPcm("knife", KnifeSfx.clang)
        }
        if (g.over && Hi.submit(ctx, "knives", g.score)) best = g.score
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241A4A), Color(0xFF12234A), Color(0xFF0A1730))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Color.White, fontSize = 26.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(12.dp, 2.dp))
                key(tick / 6) {
                    Text("${g.score}", color = Color(0xFFFFD36B), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            val first = (g.stage - 1) / 5 * 5 + 1
                            (first until first + 5).forEach { s ->
                                val isBoss = s % 5 == 0
                                Box(Modifier.size(if (isBoss) 14.dp else 9.dp).clip(RoundedCornerShape(50)).background(when { s < g.stage -> Color(0xFFFFD36B); s == g.stage -> Color.White; else -> Color(0x44FFFFFF) }))
                            }
                        }
                        Text(if (g.boss) "БОСС: ${KnivesGame.BOSSES[(g.stage / 5 - 1) % KnivesGame.BOSSES.size]}" else "ЭТАП ${g.stage}", color = if (g.boss) Color(0xFFFF6B5E) else Color(0xFFFFD36B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("  🍎 $totalApples", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(Modifier.fillMaxSize().pointerInput(g, started) { detectTapGestures { if (started) g.throwKnife() } }) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val cx = size.width / 2; val cy = size.height * 0.34f
                    val R = minOf(size.width, size.height) * 0.24f
                    val kLen = R * 0.95f; val kW = R * 0.11f
                    // мишень: разваливается после этапа
                    if (g.cleared > 0f) {
                        val k = g.cleared
                        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEachIndexed { i, (sx, sy) ->
                            translate(sx * R * 1.4f * k, sy * R * 1.2f * k + R * 2.2f * k * k) {
                                rotate(sx * 120f * k, Offset(cx, cy)) {
                                    clipQuarter(cx, cy, R, i) { target(cx, cy, R, g.boss, g.stage, g.angle) }
                                }
                            }
                        }
                    } else {
                        // ножи и яблоки крутятся вместе с мишенью
                        rotate(g.angle, Offset(cx, cy)) {
                            g.applesOn.forEach { a -> rotate(a, Offset(cx, cy)) { apple(Offset(cx, cy + R + R * 0.14f), R * 0.15f) } }
                            g.stuck.forEach { a -> rotate(a, Offset(cx, cy)) { knife(Offset(cx, cy + R - kLen * 0.22f), kLen, kW, false) } }
                        }
                        val shake = if (g.hitFlash > 0) (Random.nextFloat() - 0.5f) * R * 0.03f * g.hitFlash else 0f
                        translate(0f, shake - g.hitFlash * R * 0.04f) { target(cx, cy, R, g.boss, g.stage, g.angle) }
                    }
                    // разрезанные яблоки разлетаются
                    g.cut.forEach { (a, tt) ->
                        val ang = Math.toRadians((a + g.angle + 90.0))
                        val base = Offset(cx + cos(ang).toFloat() * R * 1.1f, cy + sin(ang).toFloat() * R * 1.1f)
                        listOf(-1f, 1f).forEach { s -> drawCircle(Color(0xFFE5483D).copy(alpha = 1f - tt), R * 0.08f, base + Offset(s * R * 0.5f * tt, R * 1.2f * tt * tt)) }
                        val p = android.graphics.Paint().apply { color = android.graphics.Color.argb(((1 - tt) * 255).toInt(), 255, 211, 107); textSize = R * 0.22f; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER }
                        drawContext.canvas.nativeCanvas.drawText("+1", base.x, base.y - R * 0.3f * tt, p)
                    }
                    // летящий нож / нож наготове / отскок
                    val startY = size.height * 0.86f
                    val hitY = cy + R - kLen * 0.22f
                    when {
                        g.bounce >= 0 -> { val k = g.bounce; rotate(400f * k, Offset(cx + R * 0.6f * k, hitY + R * 2.4f * k * k)) { knife(Offset(cx + R * 0.6f * k, hitY + R * 2.4f * k * k), kLen, kW, true) } }
                        g.flying >= 0 -> knife(Offset(cx, startY + (hitY - startY) * g.flying), kLen, kW, true)
                        g.knivesLeft > 0 && g.cleared <= 0f -> knife(Offset(cx, startY), kLen, kW, true)
                    }
                    // сколько ножей осталось — столбиком слева
                    repeat(g.knivesLeft) { i -> knifeIcon(Offset(size.width * 0.07f, size.height * 0.92f - i * R * 0.17f), R * 0.13f) }
                }
                if (!started) GameOverlay("Ножи", "Нажми — нож летит в мишень. Не попади в уже торчащий нож. Яблоки — бонус. Каждый 5-й этап — босс.\nРекорд: $best очков · этап $bestStage", "Старт") { started = true }
                if (g.over) GameOverlay("Нож отскочил!", "Этап ${g.stage} · очки ${g.score} · рекорд $best", "Ещё раз") { g = KnivesGame(); tick++ }
            }
        }
    }
}

/** Мишень: деревянный спил с кольцами; босс — свой вид (камень, лёд, шестерня…). */
private fun DrawScope.target(cx: Float, cy: Float, R: Float, boss: Boolean, stage: Int, angle: Float) {
    val c = Offset(cx, cy)
    drawCircle(Color.Black.copy(alpha = 0.25f), R * 1.04f, c + Offset(0f, R * 0.06f))
    if (!boss) {
        drawCircle(Color(0xFFB9763C), R, c)
        drawCircle(Color(0xFFF2C46B), R * 0.9f, c)
        rotate(angle, c) {
            for (k in 1..5) drawCircle(Color(0xFFE0A84E), R * (0.85f - k * 0.14f), c, style = Stroke(R * 0.035f))
            drawLine(Color(0x55A0662A), c + Offset(-R * 0.1f, -R * 0.2f), c + Offset(R * 0.5f, R * 0.55f), R * 0.02f)
            drawCircle(Color(0xFFD99A45), R * 0.06f, c)
        }
    } else {
        val kind = (stage / 5 - 1) % KnivesGame.BOSSES.size
        val (outer, inner, line) = when (kind) {
            0 -> Triple(Color(0xFF6B6F78), Color(0xFF9EA3AD), Color(0xFF4E525A))   // кремень
            1 -> Triple(Color(0xFF7FC6E8), Color(0xFFCDEFFB), Color(0xFF5AA9CF))   // лёд
            2 -> Triple(Color(0xFF6B3E1E), Color(0xFF9B6234), Color(0xFF4A2A12))   // пень
            3 -> Triple(Color(0xFF8C8F99), Color(0xFFC3C6CE), Color(0xFF5E616A))   // шестерня
            else -> Triple(Color(0xFF7A1E10), Color(0xFFE8521F), Color(0xFFFFB238)) // лава
        }
        rotate(angle, c) {
            if (kind == 3) for (k in 0 until 12) rotate(k * 30f, c) { drawRoundRect(outer, Offset(cx - R * 0.12f, cy - R * 1.12f), Size(R * 0.24f, R * 0.3f), CornerRadius(R * 0.04f)) }
            drawCircle(outer, R, c); drawCircle(inner, R * 0.86f, c)
            for (k in 0 until 7) rotate(k * 51f, c) { drawLine(line, c, c + Offset(0f, R * (0.5f + 0.3f * ((k * 37) % 5) / 5f)), R * 0.03f) }
            drawCircle(line, R * 0.18f, c)
            // «лицо» босса
            listOf(-1f, 1f).forEach { s -> drawCircle(Color(0xFF1C1B22), R * 0.07f, c + Offset(s * R * 0.25f, -R * 0.15f)) }
            drawArc(Color(0xFF1C1B22), 200f, 140f, false, Offset(cx - R * 0.25f, cy + R * 0.05f), Size(R * 0.5f, R * 0.3f), style = Stroke(R * 0.05f))
        }
    }
}

/** Четверть мишени (для развала). */
private fun DrawScope.clipQuarter(cx: Float, cy: Float, R: Float, i: Int, block: DrawScope.() -> Unit) {
    val l = if (i % 2 == 0) cx - R * 1.2f else cx; val tp = if (i < 2) cy - R * 1.2f else cy
    clipRect(l, tp, l + R * 1.2f, tp + R * 1.2f) { block() }
}

/** Нож остриём вверх: клинок со скосом, гарда, рукоять с обмоткой. pos — точка у острия + длина вниз. */
private fun DrawScope.knife(tip: Offset, len: Float, w: Float, up: Boolean) {
    val x = tip.x; val y = tip.y
    val blade = len * 0.55f
    val p = Path().apply { moveTo(x, y); lineTo(x + w / 2, y + w * 0.9f); lineTo(x + w / 2, y + blade); lineTo(x - w / 2, y + blade); lineTo(x - w / 2, y + w * 0.6f); close() }
    drawPath(p, Brush.horizontalGradient(listOf(Color(0xFFE9EDF2), Color(0xFFB4BCC6)), x - w / 2, x + w / 2))
    drawLine(Color.White.copy(alpha = 0.7f), Offset(x - w * 0.15f, y + w), Offset(x - w * 0.15f, y + blade * 0.95f), w * 0.12f)
    drawRoundRect(Color(0xFF8C939C), Offset(x - w * 0.9f, y + blade), Size(w * 1.8f, w * 0.35f), CornerRadius(w * 0.15f))
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8E3B1E), Color(0xFFD06A33)), x - w * 0.45f, x + w * 0.45f), Offset(x - w * 0.45f, y + blade + w * 0.35f), Size(w * 0.9f, len * 0.4f), CornerRadius(w * 0.3f))
    for (k in 1..4) drawLine(Color(0x66000000), Offset(x - w * 0.45f, y + blade + w * 0.35f + k * len * 0.08f), Offset(x + w * 0.45f, y + blade + w * 0.35f + k * len * 0.08f), w * 0.08f)
    @Suppress("UNUSED_EXPRESSION") up
}

private fun DrawScope.knifeIcon(at: Offset, s: Float) {
    rotate(-35f, at) { drawRoundRect(Color(0xFF9FC4D9), Offset(at.x - s * 0.12f, at.y - s * 0.6f), Size(s * 0.24f, s * 0.7f), CornerRadius(s * 0.1f)); drawRoundRect(Color(0xFF4F8FB0), Offset(at.x - s * 0.12f, at.y + s * 0.1f), Size(s * 0.24f, s * 0.45f), CornerRadius(s * 0.1f)) }
}

private fun DrawScope.apple(c: Offset, r: Float) {
    drawCircle(Color(0xFFE5483D), r, c); drawCircle(Color(0xFFFF7A6B), r * 0.35f, c + Offset(-r * 0.35f, -r * 0.35f))
    drawLine(Color(0xFF6B4A2A), c + Offset(0f, -r * 0.8f), c + Offset(r * 0.15f, -r * 1.3f), r * 0.18f)
    drawOval(Color(0xFF4CAF50), c + Offset(r * 0.15f, -r * 1.45f), Size(r * 0.7f, r * 0.35f))
}

/** Звуки ножа — синтез: «тук» в дерево, хруст яблока, треск мишени, звон отскока. */
private object KnifeSfx {
    private fun make(sec: Double, f: (Double) -> Double): ShortArray {
        val sr = com.pank.vsevodnom.ui.random.Sfx.SR; val n = (sec * sr).toInt()
        return ShortArray(n) { i -> (f(i.toDouble() / sr).coerceIn(-1.0, 1.0) * 0.6 * Short.MAX_VALUE).toInt().toShort() }
    }
    private val rnd = java.util.Random(3)
    val thunk by lazy { make(0.12) { t -> (Math.sin(2 * Math.PI * 180 * t) * 0.8 + (rnd.nextDouble() * 2 - 1) * Math.exp(-t * 120)) * Math.exp(-t * 35) } }
    val apple by lazy { make(0.18) { t -> ((rnd.nextDouble() * 2 - 1) * 0.7 + Math.sin(2 * Math.PI * 900 * t) * 0.3) * Math.exp(-t * 22) } }
    val crack by lazy { make(0.4) { t -> ((rnd.nextDouble() * 2 - 1) * Math.exp(-t * 9) + Math.sin(2 * Math.PI * 110 * t) * Math.exp(-t * 12)) * 0.8 } }
    val clang by lazy { make(0.5) { t -> (Math.sin(2 * Math.PI * 1320 * t) * 0.5 + Math.sin(2 * Math.PI * 1960 * t) * 0.3) * Math.exp(-t * 7) } }
}
