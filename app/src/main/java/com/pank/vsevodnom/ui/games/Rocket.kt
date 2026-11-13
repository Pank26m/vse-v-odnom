package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.random.Sfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Ракета»: ракета стартует и сбрасывает ступени. По шкале внизу бегает стрелка; нажатие, когда она
 * в зелёной зоне, — зажигание/отделение следующей ступени (жёлтая середина — «идеально», бонус к высоте
 * и серия). Чем выше ступень — тем быстрее стрелка и уже зона. Промах или кончилось топливо ступени —
 * ракета кувыркается и падает. Счёт — высота в метрах.
 */
class RocketGame(private val rnd: Random = Random) {
    enum class Judge { PERFECT, GOOD, MISS }
    enum class Phase { PAD, BURN, FALL }

    /** Частица пламени/дыма: координаты в ширинах ракеты от сопла (вниз — плюс). */
    class Part(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float, val max: Float, val smoke: Boolean)
    /** Отработавшая ступень: x, y — в ширинах ракеты от места отделения. */
    class Debris(val no: Int, var x: Float, var y: Float, val vx: Float, var vy: Float, var rot: Float, val rotV: Float) { var age = 0f }

    companion object {
        const val EV_FAIL = 1
        /** Сколько ширин ракеты на экране занимает 1 м высоты (облака, земля, дым). */
        const val REL = 0.02f
        fun zoneWidth(stage: Int) = (0.30f - 0.012f * stage).coerceAtLeast(0.075f)
        fun perfectHalf(width: Float) = maxOf(width * 0.17f, 0.012f)
        /** Скорость стрелки: долей шкалы в секунду. */
        fun needleSpeed(stage: Int) = (0.7f + 0.1f * stage).coerceAtMost(3.0f)
        /** Время работы ступени: ~1,2 прохода стрелки туда-обратно + запас. */
        fun fuelTime(stage: Int) = 2.4f / needleSpeed(stage) + 0.6f
        fun judge(pos: Float, center: Float, width: Float): Judge {
            val d = abs(pos - center)
            return when {
                d <= perfectHalf(width) -> Judge.PERFECT
                d <= width / 2f -> Judge.GOOD
                else -> Judge.MISS
            }
        }
        fun formatAlt(m: Int): String = if (m < 1000) "$m м" else "%.1f км".format(java.util.Locale.forLanguageTag("ru"), m / 1000f)
    }

    var phase = Phase.PAD; private set
    var over by mutableStateOf(false)
    /** Номер работающей ступени (0 — ещё на старте). */
    var stage = 0; private set
    var altitude = 0f; private set
    var velocity = 0f; private set
    var score = 0; private set
    var streak = 0; private set
    var perfects = 0; private set
    var needle = 0f
    private var needleDir = 1f
    var zoneC = 0.5f; private set
    var zoneW = zoneWidth(0); private set
    /** Остаток топлива ступени 0…1. */
    var fuel = 1f; private set
    var time = 0f; private set
    /** 1 → 0 после отделения: ракета «съезжает» на место сброшенной ступени. */
    var shift = 0f; private set
    var sepFlash = 0f; private set
    var flash = ""; private set
    var flashSub = ""; private set
    var flashAge = 9f; private set
    var flashPerfect = false; private set
    var tilt = 0f; private set
    /** Падение: смещение вниз в высотах экрана. */
    var fallY = 0f; private set
    private var fallV = 0f
    private var tiltV = 0f
    private var fallT = 0f
    var failReason = ""; private set
    val parts = ArrayList<Part>()
    val debris = ArrayList<Debris>()

    init { newZone() }

    private fun newZone() {
        zoneW = zoneWidth(stage)
        val m = 0.04f
        zoneC = zoneW / 2f + m + rnd.nextFloat() * (1f - zoneW - 2 * m)
        needle = 0f; needleDir = 1f
    }

    private fun targetSpeed() = 35f + 20f * stage + 6f * streak.coerceAtMost(6)

    /** Нажатие: зажигание на старте или отделение ступени в полёте. */
    fun tap(): Judge? {
        if (over || phase == Phase.FALL) return null
        val j = judge(needle, zoneC, zoneW)
        if (j == Judge.MISS) { fail("Не вовремя!"); return j }
        val inFlight = phase == Phase.BURN
        if (j == Judge.PERFECT) { streak++; perfects++ } else streak = 0
        var bonus = 0
        if (inFlight) {
            if (j == Judge.PERFECT) {
                bonus = ((20 + 6 * stage) * (1f + 0.5f * (streak - 1).coerceIn(0, 4))).toInt()
                altitude += bonus
            }
            debris += Debris(stage, 0f, 0f, (rnd.nextFloat() - 0.5f) * 1.2f, 0.8f, 0f, (if (rnd.nextBoolean()) 1 else -1) * (40f + rnd.nextFloat() * 60f))
            shift = 1f; sepFlash = 1f
        }
        phase = Phase.BURN
        stage++
        fuel = 1f
        newZone()
        // вспышка зажигания: на старте — клубы дыма в стороны
        repeat(if (inFlight) 26 else 60) {
            val smoke = !inFlight || rnd.nextFloat() < 0.3f
            val wide = if (inFlight) 3f else 9f
            parts += Part((rnd.nextFloat() - 0.5f) * 0.6f, 0f, (rnd.nextFloat() - 0.5f) * wide, if (inFlight) 4f + rnd.nextFloat() * 4f else rnd.nextFloat() * 2.5f,
                0f, 0.5f + rnd.nextFloat() * (if (smoke) 1.2f else 0.3f), smoke)
        }
        flash = when { !inFlight && j == Judge.PERFECT -> "Идеальный старт!"; !inFlight -> "Пуск!"; j == Judge.PERFECT -> "ИДЕАЛЬНО!"; else -> "Хорошо" }
        flashSub = when { bonus > 0 && streak > 1 -> "+$bonus м · серия ×$streak"; bonus > 0 -> "+$bonus м"; else -> "" }
        flashPerfect = j == Judge.PERFECT; flashAge = 0f
        score = altitude.toInt()
        return j
    }

    private fun fail(reason: String) {
        if (phase == Phase.FALL) return
        failReason = reason
        score = altitude.toInt()
        phase = Phase.FALL
        tiltV = (if (rnd.nextBoolean()) 1 else -1) * (110f + rnd.nextFloat() * 90f)
        fallV = if (stage == 0) 0f else -0.12f
        fallT = 0f
        streak = 0
        flash = reason; flashSub = ""; flashPerfect = false; flashAge = 0f
        repeat(30) { parts += Part((rnd.nextFloat() - 0.5f), 0f, (rnd.nextFloat() - 0.5f) * 6f, (rnd.nextFloat() - 0.5f) * 6f, 0f, 0.6f + rnd.nextFloat() * 0.6f, true) }
    }

    private fun moveNeedle(dt: Float) {
        val s = needleSpeed(stage)
        needle += needleDir * s * dt
        if (needle > 1f) { needle = 2f - needle; needleDir = -1f }
        if (needle < 0f) { needle = -needle; needleDir = 1f }
    }

    /** Шаг игры; возвращает EV_FAIL, если ракета только что потеряла управление (кончилось топливо). */
    fun step(dt: Float): Int {
        time += dt
        flashAge += dt
        sepFlash = (sepFlash - dt * 2.5f).coerceAtLeast(0f)
        shift = (shift - dt * 4f).coerceAtLeast(0f)
        var ev = 0
        when (phase) {
            Phase.PAD -> moveNeedle(dt)
            Phase.BURN -> {
                moveNeedle(dt)
                fuel -= dt / fuelTime(stage)
                velocity += (targetSpeed() - velocity) * (dt * 1.6f).coerceAtMost(1f)
                altitude += velocity * dt
                score = altitude.toInt()
                val n = (dt * 110f).toInt() + 1
                repeat(n) {
                    parts += Part((rnd.nextFloat() - 0.5f) * 0.35f, 0f, (rnd.nextFloat() - 0.5f) * 1.6f, 6f + rnd.nextFloat() * 5f,
                        0f, 0.22f + rnd.nextFloat() * 0.25f, rnd.nextFloat() < 0.12f)
                }
                if (fuel <= 0f) { fuel = 0f; fail("Топливо кончилось!"); ev = EV_FAIL }
            }
            Phase.FALL -> {
                fallT += dt
                tilt += tiltV * dt
                fallV += 1.5f * dt
                fallY += fallV * dt
                velocity *= (1f - dt * 1.5f).coerceAtLeast(0f)
                altitude += velocity * dt * 0.3f
                if (fallT > 1.9f) over = true
            }
        }
        // частицы движутся вниз ещё и потому, что ракета летит вверх
        val drift = velocity * REL
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life >= p.max) { it.remove(); continue }
            val slow = if (p.smoke) 0.35f else 1f
            p.x += p.vx * dt * slow; p.y += (p.vy * slow + drift) * dt
        }
        if (parts.size > 500) parts.subList(0, parts.size - 500).clear()
        val di = debris.iterator()
        while (di.hasNext()) {
            val d = di.next()
            d.age += dt; d.vy += 6f * dt
            d.x += d.vx * dt; d.y += (d.vy + drift) * dt; d.rot += d.rotV * dt
            if (d.age > 3f) di.remove()
        }
        return ev
    }
}

private val STAGE_COLORS = listOf(0xFFF4F1EA, 0xFFFF8A4C, 0xFF5AA9E6, 0xFFF2C94C, 0xFFE5485D, 0xFF8E7CF0, 0xFF3FB27F)

/** Цвет неба по высоте: голубое → синее → тёмно-синее → космос. */
private val SKY = listOf(
    0f to (Color(0xFF63BFF5) to Color(0xFFD3F0FF)),
    4000f to (Color(0xFF2F7FD8) to Color(0xFF8FCBF5)),
    12000f to (Color(0xFF16357E) to Color(0xFF3F6CC0)),
    25000f to (Color(0xFF070B24) to Color(0xFF1A2558)),
    45000f to (Color(0xFF000004) to Color(0xFF0A0D25)),
)

private fun skyAt(alt: Float): Pair<Color, Color> {
    if (alt <= SKY.first().first) return SKY.first().second
    for (i in 1 until SKY.size) {
        val (a1, c1) = SKY[i]
        if (alt <= a1) {
            val (a0, c0) = SKY[i - 1]
            val t = (alt - a0) / (a1 - a0)
            return lerp(c0.first, c1.first, t) to lerp(c0.second, c1.second, t)
        }
    }
    return SKY.last().second
}

private fun hash(i: Int, k: Int): Float {
    var h = i * 374761393 + k * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
}

@Composable
fun RocketScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("rocket", Context.MODE_PRIVATE) }
    var g by remember { mutableStateOf(RocketGame()) }
    var started by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "rocket")) }
    var record by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    val stars = remember { List(110) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 1.6f, Random.nextFloat() * 6.28f) } }
    fun play(k: String, pcm: () -> ShortArray) { if (sound) runCatching { Sfx.playPcm("rocket_$k", pcm()) } }

    GameLoop(started && !g.over) { dt ->
        val ev = g.step(dt)
        tick++
        if (ev == RocketGame.EV_FAIL) play("fail") { RocketSfx.fail }
        if (g.over && Hi.submit(ctx, "rocket", g.score)) { best = g.score; record = true }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF63BFF5))) {
        Canvas(Modifier.fillMaxSize().pointerInput(g, started) {
            detectTapGestures(onPress = {
                if (started && !g.over) {
                    val first = g.phase == RocketGame.Phase.PAD
                    when (g.tap()) {
                        RocketGame.Judge.PERFECT -> { play(if (first) "ignite" else "whoosh") { if (first) RocketSfx.ignite else RocketSfx.whoosh }; play("ding") { RocketSfx.ding } }
                        RocketGame.Judge.GOOD -> play(if (first) "ignite" else "whoosh") { if (first) RocketSfx.ignite else RocketSfx.whoosh }
                        RocketGame.Judge.MISS -> play("fail") { RocketSfx.fail }
                        null -> {}
                    }
                }
            })
        }) {
            @Suppress("UNUSED_EXPRESSION") tick
            val W = size.width; val H = size.height
            val alt = g.altitude
            val (top, bottom) = skyAt(alt)
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            val rw = minOf(H * 0.068f, W * 0.13f)
            val cs = rw * RocketGame.REL          // пикселей на метр
            val baseY = H * 0.64f
            val cx = W / 2f

            // звёзды проступают выше 6 км
            val starA = ((alt - 6000f) / 14000f).coerceIn(0f, 1f)
            if (starA > 0f) stars.forEach { s ->
                val y = ((s[1] * H + alt * cs * 0.04f) % H)
                val tw = 0.65f + 0.35f * sin(g.time * 2.3f + s[3])
                drawCircle(Color.White.copy(alpha = starA * tw), s[2] * density * 0.8f, Offset(s[0] * W, y))
            }
            // планета внизу на больших высотах
            val planet = ((alt - 15000f) / 30000f).coerceIn(0f, 1f)
            if (planet > 0f) {
                val R = W * 2.2f
                val c = Offset(cx, H + R - H * 0.05f - planet * H * 0.12f)
                drawCircle(Color(0xFF4FC3F7).copy(alpha = 0.35f * planet), R + rw * 0.9f, c)
                drawCircle(Color(0xFF8FE3FF).copy(alpha = 0.5f * planet), R + rw * 0.3f, c)
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF2E7BD1), Color(0xFF0D2E66)), c.y - R, c.y - R + H * 0.3f), R, c, alpha = planet)
            }
            // дальние облака (медленнее, меньше)
            clouds(alt, cs * 0.5f, baseY, W, H, rw * 0.7f, layer = 1, alpha = 0.55f)
            // земля и стартовый стол
            val groundY = baseY + rw * 0.3f + alt * cs
            if (groundY < H + rw * 6) ground(cx, groundY, W, H, rw)
            // полосы скорости
            if (g.velocity > 160f && alt > 1500f && g.phase != RocketGame.Phase.FALL) {
                val k = ((g.velocity - 160f) / 300f).coerceIn(0f, 1f)
                for (i in 0 until 14) {
                    val x = hash(i, 7) * W
                    val len = H * (0.08f + 0.12f * hash(i, 9))
                    val y = ((hash(i, 3) * H + g.time * g.velocity * cs * 1.5f) % (H + len)) - len
                    drawLine(Color.White.copy(alpha = 0.25f * k), Offset(x, y), Offset(x, y + len), 1.5f * density)
                }
            }

            // отделённые ступени падают и кувыркаются
            g.debris.forEach { d ->
                val a = (1f - d.age / 3f).coerceIn(0f, 1f)
                val p = Offset(cx + d.x * rw, baseY - rw * 0.6f + d.y * rw)
                rotate(d.rot, p) { stageBody(p.x, p.y + rw * 0.6f, rw, rw * 1.15f, d.no, alpha = a, fins = true) }
            }

            // ракета
            val rocketY = baseY + g.fallY * H
            val sway = if (g.phase == RocketGame.Phase.BURN) sin(g.time * 17f) * rw * 0.02f else 0f
            val sh = rw * 1.15f
            val stackH = sh * 3 + rw * 1.1f
            rotate(g.tilt, Offset(cx, rocketY - stackH * 0.45f)) {
                translate(sway, 0f) {
                    // пламя и дым — в системе ракеты
                    g.parts.forEach { p ->
                        val k = p.life / p.max
                        val pos = Offset(cx + p.x * rw, rocketY + rw * 0.28f + p.y * rw)
                        if (p.smoke) drawCircle(Color(0xFFE9E6EE).copy(alpha = 0.55f * (1f - k)), rw * (0.25f + k * 0.9f), pos)
                        else drawCircle(lerp(lerp(Color(0xFFFFFBE0), Color(0xFFFFB238), (k * 2f).coerceAtMost(1f)), Color(0xFFE5483D), ((k - 0.5f) * 2f).coerceIn(0f, 1f)).copy(alpha = 1f - k * 0.8f),
                            rw * (0.22f - k * 0.12f), pos)
                    }
                    if (g.phase == RocketGame.Phase.BURN) flame(cx, rocketY + rw * 0.28f, rw, g.time)
                    val n0 = maxOf(g.stage, 1)
                    val up = sh * g.shift
                    // сопло
                    nozzle(cx, rocketY - up, rw)
                    stageBody(cx, rocketY - up, rw, sh, n0, fins = true)
                    stageBody(cx, rocketY - up - sh, rw, sh, n0 + 1)
                    stageBody(cx, rocketY - up - sh * 2, rw, sh, n0 + 2, alpha = (1f - g.shift).coerceIn(0f, 1f) * 0.6f + 0.4f)
                    nose(cx, rocketY - up - sh * 3, rw)
                    if (g.sepFlash > 0f) {
                        val k = 1f - g.sepFlash
                        drawCircle(Color.White.copy(alpha = g.sepFlash * 0.8f), rw * (0.5f + k * 1.6f), Offset(cx, rocketY - up), style = Stroke(rw * 0.12f * g.sepFlash + 1f))
                    }
                }
            }
            // ближние облака поверх ракеты
            clouds(alt, cs * 1.4f, baseY, W, H, rw * 1.3f, layer = 2, alpha = 0.85f)

            // надпись оценки
            if (g.flashAge < 1.1f && g.flash.isNotEmpty()) {
                val k = g.flashAge / 1.1f
                val y = baseY - stackH - rw * 0.8f - k * rw * 1.2f
                text(g.flash, cx, y, rw * 0.62f, if (g.flashPerfect) Color(0xFFFFD34D) else if (g.phase == RocketGame.Phase.FALL) Color(0xFFFF6B5E) else Color.White, 1f - k * k)
                if (g.flashSub.isNotEmpty()) text(g.flashSub, cx, y + rw * 0.6f, rw * 0.38f, Color.White, 1f - k * k)
            }

            // шкала момента + топливо
            if (g.phase != RocketGame.Phase.FALL) gauge(g, W, H, rw)
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, bg = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            key(tick / 4) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x66000020)).padding(16.dp, 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(RocketGame.formatAlt(g.score), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (g.stage == 0) "рекорд ${RocketGame.formatAlt(best)}" else "ступень ${g.stage} · рекорд ${RocketGame.formatAlt(best)}", color = Color(0xFFD8E6FF), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук",
                { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }, bg = Color.White.copy(alpha = 0.8f))
        }

        if (!started) GameOverlay("Ракета",
            "Стрелка бегает по шкале. Жми, когда она в зелёной зоне: первое нажатие — пуск, дальше — отделение ступени. В жёлтую середину — «идеально» и бонус к высоте. Промах или пустой бак — авария.\nРекорд: ${RocketGame.formatAlt(best)}",
            "Старт") { started = true }
        if (g.over) GameOverlay(if (record) "Новый рекорд!" else "Авария!",
            "${g.failReason}\nВысота ${RocketGame.formatAlt(g.score)} · ступеней ${(g.stage - 1).coerceAtLeast(0)} · идеально ${g.perfects}\nРекорд ${RocketGame.formatAlt(best)}",
            "Ещё раз") { g = RocketGame(); record = false; tick++ }
    }
}

private fun DrawScope.text(s: String, x: Float, y: Float, sizePx: Float, color: Color, alpha: Float) {
    val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER
        style = android.graphics.Paint.Style.STROKE; strokeWidth = sizePx * 0.14f; this.color = android.graphics.Color.argb((a * 0.6f).toInt(), 10, 14, 40)
    }
    val fill = android.graphics.Paint(stroke).apply { style = android.graphics.Paint.Style.FILL; this.color = color.copy(alpha = a / 255f).toArgb() }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, stroke)
    drawContext.canvas.nativeCanvas.drawText(s, x, y, fill)
}

/** Облака слоями: высота облака — сетка по 150 м (часть пропускается), x — по хешу. */
private fun DrawScope.clouds(alt: Float, scale: Float, baseY: Float, W: Float, H: Float, r: Float, layer: Int, alpha: Float) {
    val span = (H / scale).toInt() + 300
    val step = if (layer == 1) 150 else 260
    val from = ((alt - span) / step).toInt().coerceAtLeast(2)
    val to = ((alt + span) / step).toInt()
    for (i in from..to) {
        val ca = i * step.toFloat()
        if (ca < 300f || ca > 11000f) continue
        if (hash(i, layer) < (if (layer == 1) 0.35f else 0.55f)) continue
        val y = baseY - (ca - alt) * scale
        if (y < -r * 3 || y > H + r * 3) continue
        val fade = (1f - (ca - 8000f) / 3000f).coerceIn(0f, 1f) * alpha
        val x = ((hash(i, layer + 11) * (W + r * 6)) - r * 3)
        val s = r * (0.7f + hash(i, layer + 5) * 0.8f)
        val c = Color.White.copy(alpha = fade)
        drawCircle(c, s, Offset(x, y))
        drawCircle(c, s * 0.75f, Offset(x - s * 0.9f, y + s * 0.25f))
        drawCircle(c, s * 0.8f, Offset(x + s * 0.95f, y + s * 0.2f))
        drawCircle(c, s * 0.55f, Offset(x + s * 1.7f, y + s * 0.45f))
        drawRoundRect(c, Offset(x - s * 1.5f, y + s * 0.2f), Size(s * 3.6f, s * 0.8f), CornerRadius(s * 0.4f))
    }
}

private fun DrawScope.ground(cx: Float, gy: Float, W: Float, H: Float, rw: Float) {
    // холмы
    drawCircle(Color(0xFF7CC97A), W * 0.55f, Offset(W * 0.15f, gy + W * 0.42f))
    drawCircle(Color(0xFF8FD486), W * 0.6f, Offset(W * 0.95f, gy + W * 0.45f))
    drawRect(Color(0xFF5DB565), Offset(0f, gy + rw * 0.25f), Size(W, H + rw * 10))
    drawRect(Color(0xFF4FA35A), Offset(0f, gy + rw * 0.25f), Size(W, rw * 0.12f))
    // бетонный стол
    drawRect(Color(0xFF9AA3AF), Offset(cx - rw * 1.6f, gy - rw * 0.02f), Size(rw * 3.2f, rw * 0.32f))
    drawRect(Color(0xFF7A8390), Offset(cx - rw * 1.6f, gy + rw * 0.22f), Size(rw * 3.2f, rw * 0.08f))
    // башня обслуживания — решётка
    val tx = cx - rw * 1.55f; val tw = rw * 0.5f; val th = rw * 4.9f
    val red = Color(0xFFD9503F)
    drawLine(red, Offset(tx, gy), Offset(tx, gy - th), rw * 0.07f)
    drawLine(red, Offset(tx + tw, gy), Offset(tx + tw, gy - th), rw * 0.07f)
    var y = gy
    var k = 0
    while (y > gy - th + 1) {
        val y2 = y - tw
        drawLine(red, Offset(tx, y), Offset(tx + tw, y), rw * 0.05f)
        drawLine(red.copy(alpha = 0.8f), if (k % 2 == 0) Offset(tx, y) else Offset(tx + tw, y), if (k % 2 == 0) Offset(tx + tw, y2) else Offset(tx, y2), rw * 0.035f)
        y = y2; k++
    }
    drawLine(Color(0xFF6B7380), Offset(tx + tw, gy - th * 0.72f), Offset(cx - rw * 0.5f, gy - th * 0.72f), rw * 0.06f)
    drawCircle(Color(0xFFFFE07A), rw * 0.09f, Offset(tx + tw / 2, gy - th - rw * 0.08f))
}

private fun DrawScope.nozzle(cx: Float, y: Float, rw: Float) {
    val p = Path().apply {
        moveTo(cx - rw * 0.22f, y); lineTo(cx + rw * 0.22f, y)
        lineTo(cx + rw * 0.34f, y + rw * 0.28f); lineTo(cx - rw * 0.34f, y + rw * 0.28f); close()
    }
    drawPath(p, Brush.horizontalGradient(listOf(Color(0xFF3B3F4A), Color(0xFF8A909C), Color(0xFF3B3F4A)), cx - rw * 0.34f, cx + rw * 0.34f))
}

/** Ступень: цилиндр с объёмом, полосой и заклёпками; нижняя — с крылышками. y — низ ступени. */
private fun DrawScope.stageBody(cx: Float, y: Float, rw: Float, sh: Float, no: Int, alpha: Float = 1f, fins: Boolean = false) {
    val base = Color(STAGE_COLORS[(no - 1).mod(STAGE_COLORS.size)])
    val dark = lerp(base, Color.Black, 0.35f)
    val light = lerp(base, Color.White, 0.55f)
    if (fins) {
        listOf(-1f, 1f).forEach { s ->
            val p = Path().apply {
                moveTo(cx + s * rw * 0.5f, y - sh * 0.55f)
                lineTo(cx + s * rw * 0.92f, y - sh * 0.05f)
                lineTo(cx + s * rw * 0.92f, y + rw * 0.12f)
                lineTo(cx + s * rw * 0.5f, y - sh * 0.05f); close()
            }
            drawPath(p, Color(0xFFD9503F), alpha = alpha)
            drawPath(p, Color(0x33000000), alpha = alpha, style = Stroke(rw * 0.03f))
        }
    }
    drawRect(Brush.horizontalGradient(listOf(dark, base, light, base, dark), cx - rw / 2, cx + rw / 2), Offset(cx - rw / 2, y - sh), Size(rw, sh), alpha = alpha)
    // полоса и шов
    drawRect(Color(0xFF2B2A33).copy(alpha = 0.75f * alpha), Offset(cx - rw / 2, y - sh), Size(rw, rw * 0.06f))
    val band = if (no % 2 == 0) Color(0xFF2B2A33) else Color(0xFFD9503F)
    drawRect(band.copy(alpha = 0.85f * alpha), Offset(cx - rw / 2, y - sh * 0.62f), Size(rw, sh * 0.12f))
    for (i in 0..3) drawCircle(Color(0x55000000).copy(alpha = 0.33f * alpha), rw * 0.025f, Offset(cx - rw * 0.36f + i * rw * 0.24f, y - sh + rw * 0.13f))
}

/** Головной обтекатель с иллюминатором. y — низ обтекателя. */
private fun DrawScope.nose(cx: Float, y: Float, rw: Float) {
    val h = rw * 1.1f
    val p = Path().apply {
        moveTo(cx - rw / 2, y)
        quadraticTo(cx - rw * 0.48f, y - h * 0.65f, cx, y - h)
        quadraticTo(cx + rw * 0.48f, y - h * 0.65f, cx + rw / 2, y); close()
    }
    drawPath(p, Brush.horizontalGradient(listOf(Color(0xFFB0372A), Color(0xFFE5604F), Color(0xFFFFA092), Color(0xFFB0372A)), cx - rw / 2, cx + rw / 2))
    drawCircle(Color(0xFFDDE3EA), rw * 0.2f, Offset(cx, y - h * 0.38f))
    drawCircle(Brush.linearGradient(listOf(Color(0xFF8FD3FF), Color(0xFF2D5DA8)), Offset(cx - rw * 0.15f, y - h * 0.5f), Offset(cx + rw * 0.15f, y - h * 0.26f)), rw * 0.14f, Offset(cx, y - h * 0.38f))
    drawCircle(Color.White.copy(alpha = 0.7f), rw * 0.04f, Offset(cx - rw * 0.05f, y - h * 0.43f))
}

/** Язык пламени из сопла: мерцающие вложенные капли. */
private fun DrawScope.flame(cx: Float, y: Float, rw: Float, t: Float) {
    val f = 1f + 0.15f * sin(t * 41f) + 0.08f * sin(t * 67f)
    fun drop(w: Float, len: Float, c: Color) {
        val p = Path().apply {
            moveTo(cx - w, y)
            quadraticTo(cx - w * 0.9f, y + len * 0.6f, cx, y + len)
            quadraticTo(cx + w * 0.9f, y + len * 0.6f, cx + w, y); close()
        }
        drawPath(p, c)
    }
    drop(rw * 0.36f, rw * 1.9f * f, Color(0xCCFF6A2B))
    drop(rw * 0.27f, rw * 1.35f * f, Color(0xEEFFB238))
    drop(rw * 0.16f, rw * 0.8f * f, Color(0xFFFFF6D0))
}

/** Шкала внизу: зелёная зона, жёлтая середина, стрелка; под ней — топливо ступени. */
private fun DrawScope.gauge(g: RocketGame, W: Float, H: Float, rw: Float) {
    val d = density
    val left = W * 0.08f; val right = W * 0.92f; val gw = right - left
    val top = H - 120 * d; val gh = 30 * d
    drawRoundRect(Color(0x99101530), Offset(left - 6 * d, top - 6 * d), Size(gw + 12 * d, gh + 30 * d), CornerRadius(14 * d))
    drawRoundRect(Color(0x33FFFFFF), Offset(left, top), Size(gw, gh), CornerRadius(8 * d))
    val zl = left + (g.zoneC - g.zoneW / 2) * gw
    drawRoundRect(Color(0xFF3FD07F), Offset(zl, top), Size(g.zoneW * gw, gh), CornerRadius(6 * d))
    val ph = RocketGame.perfectHalf(g.zoneW)
    drawRoundRect(Color(0xFFFFD34D), Offset(left + (g.zoneC - ph) * gw, top), Size(ph * 2 * gw, gh), CornerRadius(4 * d))
    // стрелка
    val nx = left + g.needle * gw
    val inZone = RocketGame.judge(g.needle, g.zoneC, g.zoneW) != RocketGame.Judge.MISS
    val nc = if (inZone) Color.White else Color(0xFFFFB3AA)
    drawRoundRect(nc, Offset(nx - 2.5f * d, top - 6 * d), Size(5 * d, gh + 12 * d), CornerRadius(2.5f * d))
    val tri = Path().apply { moveTo(nx - 8 * d, top - 14 * d); lineTo(nx + 8 * d, top - 14 * d); lineTo(nx, top - 5 * d); close() }
    drawPath(tri, nc)
    // топливо
    val fy = top + gh + 8 * d
    drawRoundRect(Color(0x33FFFFFF), Offset(left, fy), Size(gw, 8 * d), CornerRadius(4 * d))
    if (g.phase == RocketGame.Phase.BURN) {
        val fc = lerp(Color(0xFFE5485D), Color(0xFFFFB238), (g.fuel * 2f).coerceAtMost(1f))
        drawRoundRect(fc, Offset(left, fy), Size(gw * g.fuel, 8 * d), CornerRadius(4 * d))
    }
    text(if (g.phase == RocketGame.Phase.PAD) "ПУСК — жми в зелёной зоне" else "ТОПЛИВО", W / 2, fy + 26 * d, 12 * d, Color(0xFFD8E6FF), 1f)
    @Suppress("UNUSED_EXPRESSION") rw
}

/** Звуки ракеты — синтез: зажигание (гул), «вжух» отделения, звон «идеально», падение со свистом. */
private object RocketSfx {
    private class Syn(sec: Double) {
        val a = DoubleArray((sec * Sfx.SR).toInt())
        private val r = java.util.Random(11)
        /** Шум через фильтр нижних частот с частотой среза cut(x) и огибающей env(x), x ∈ 0…1. */
        fun noise(at: Double, dur: Double, amp: Double, cut: (Double) -> Double, env: (Double) -> Double) {
            val s0 = (at * Sfx.SR).toInt(); val n = (dur * Sfx.SR).toInt(); var y = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break
                val x = i.toDouble() / n
                val k = (2 * PI * cut(x) / Sfx.SR).let { it / (it + 1) }
                y += k * ((r.nextDouble() * 2 - 1) - y); y2 += k * (y - y2)
                a[j] += y2 * 3 * amp * env(x)
            }
        }
        fun tone(at: Double, dur: Double, amp: Double, f: (Double) -> Double, env: (Double) -> Double) {
            val s0 = (at * Sfx.SR).toInt(); val n = (dur * Sfx.SR).toInt(); var ph = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break
                val x = i.toDouble() / n
                ph += 2 * PI * f(x) / Sfx.SR
                a[j] += sin(ph) * amp * env(x)
            }
        }
        fun pcm(gain: Double = 0.8): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-6)
            val fade = (0.004 * Sfx.SR).toInt()
            return ShortArray(a.size) { i ->
                val e = minOf(1.0, i.toDouble() / fade, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }
    val ignite by lazy {
        Syn(1.1).apply {
            tone(0.0, 0.5, 0.7, { 70 - 30 * it }) { exp(-it * 5) }
            noise(0.0, 1.1, 1.0, { 300 + 900 * it }) { x -> minOf(1.0, x * 6) * (1 - x) }
            noise(0.0, 0.08, 0.6, { 4000.0 }) { 1 - it }
        }.pcm()
    }
    val whoosh by lazy {
        Syn(0.55).apply {
            noise(0.0, 0.55, 1.0, { 500 + 3500 * sin(PI * it) }) { x -> sin(PI * x) * sin(PI * x) }
            tone(0.0, 0.12, 0.4, { 160.0 }) { exp(-it * 4) }   // щелчок замков
            noise(0.0, 0.03, 0.5, { 6000.0 }) { 1 - it }
        }.pcm(0.7)
    }
    val ding by lazy {
        Syn(0.7).apply {
            tone(0.02, 0.68, 0.6, { 1318.5 }) { exp(-it * 5) }
            tone(0.02, 0.68, 0.3, { 1975.5 }) { exp(-it * 7) }
            tone(0.02, 0.68, 0.15, { 2637.0 }) { exp(-it * 9) }
        }.pcm(0.6)
    }
    val fail by lazy {
        Syn(1.5).apply {
            tone(0.0, 1.3, 0.5, { 950 - 700 * it }) { x -> minOf(1.0, x * 10) * (1 - x) }
            noise(0.0, 0.4, 0.9, { 1500 - 1200 * it }) { exp(-it * 4) }
            noise(1.15, 0.35, 1.0, { 600.0 }) { exp(-it * 6) }
        }.pcm(0.75)
    }
}
