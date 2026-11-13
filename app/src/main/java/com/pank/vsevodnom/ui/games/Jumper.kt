package com.pank.vsevodnom.ui.games

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.RoundIconButton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Прыгун»: бесконечный прыжок по платформам вверх. Мир: ширина 100 (края склеены), y — вверх.
 * Герой отскакивает сам; влево-вправо — наклон телефона или палец на половине экрана.
 * Платформы: обычные, ездящие, ломкие (проваливаешься), одноразовые (исчезают после прыжка);
 * на платформах — пружины, пропеллер и ранец (короткий полёт). Монстров давить сверху (снизу/сбоку — смерть),
 * чёрные дыры — смерть. Камера только вверх; очки — наибольшая высота; упал ниже экрана — конец.
 * Генератор гарантирует: между «твёрдыми» (не ломкими) платформами по высоте меньше высоты прыжка.
 */
class JumperGame(private val rnd: Random = Random) {
    companion object {
        const val W = 100f
        const val G = 330f
        const val JUMP_V = 152f
        const val JUMP_H = JUMP_V * JUMP_V / (2 * G)      // ≈ 35
        const val SPRING_V = 250f
        const val PLAT_W = 17f
        const val PLAT_H = 3.4f
        const val HALF = 4.4f
        const val BODY_H = 10f
        const val MAX_VX = 120f
        const val MONSTER_R = 6.5f
        const val HOLE_R = 7f
        const val PROP_T = 2.2f; const val PROP_V = 220f
        const val JET_T = 2.6f; const val JET_V = 360f

        const val NORMAL = 0; const val MOVING = 1; const val BREAK = 2; const val VANISH = 3
        const val ITEM_NONE = 0; const val SPRING = 1; const val PROP = 2; const val JET = 3

        const val EV_BOING = 1; const val EV_SPRING = 2; const val EV_BREAK = 4; const val EV_VANISH = 8
        const val EV_STOMP = 16; const val EV_HIT = 32; const val EV_POWER = 64; const val EV_HOLE = 128; const val EV_OVER = 256

        /** Сложность 0…1 по высоте. */
        fun diff(h: Float) = (h / 24000f).coerceIn(0f, 1f)
        fun minGap(h: Float) = 8f + 8f * diff(h)
        /** Наибольший промежуток между твёрдыми платформами: всегда < 0,8 высоты прыжка. */
        fun maxGap(h: Float) = 13f + (JUMP_H * 0.8f - 13f) * diff(h)
        /** Разница x с учётом склейки краёв (−W/2…W/2). */
        fun wdx(a: Float, b: Float): Float { var d = (a - b) % W; if (d > W / 2) d -= W; if (d < -W / 2) d += W; return d }
    }

    class Plat(var x: Float, val y: Float, val kind: Int, var item: Int = ITEM_NONE) {
        var vx = 0f
        var itemDx = 0f
        var broken = false; var fallY = 0f; var fallV = 0f
        var gone = false; var fade = 0f
        var springT = 0f
        val seed = (y * 7.3f).toInt()
        val solid get() = kind != BREAK
    }
    class Monster(var x: Float, val y: Float, val kind: Int, val seed: Int) {
        var vx = 0f; var bob = 0f
        var dead = false; var dy = 0f; var dv = 0f
    }
    class Hole(val x: Float, val y: Float, val seed: Int)

    val plats = ArrayList<Plat>()
    val monsters = ArrayList<Monster>()
    val holes = ArrayList<Hole>()
    var px = W / 2; var py = 0f; var vx = 0f; var vy = JUMP_V
    var face = 1f
    /** Полёт: ITEM_NONE / PROP / JET. */
    var fly = ITEM_NONE; var flyT = 0f
    var dying = false
    var holeT = -1f; private var holeX = 0f; private var holeY = 0f
    var over by mutableStateOf(false)
    var cause = ""
    var maxY = 0f
    val score get() = maxY.toInt()
    var camY = -20f; var viewH = 180f
    /** Управление −1…1 (наклон или касание). */
    var input = 0f
    var squash = 0f
    var t = 0f
    private var genY = 0f
    private var lastX = W / 2
    private var sinceMonster = 0; private var sinceHole = 0

    init {
        plats += Plat(W / 2, 0f, NORMAL)
        generateUpTo(260f)
    }

    private fun randX() = PLAT_W / 2 + rnd.nextFloat() * (W - PLAT_W)

    /** Достроить мир до высоты top. */
    fun generateUpTo(top: Float) {
        while (genY < top) {
            val h = genY; val d = diff(h)
            val lo = minGap(h); val hi = maxGap(h)
            val gap = lo + rnd.nextFloat() * max(0f, hi - lo)
            val y = genY + gap
            // ломкая «приманка» между твёрдыми
            if (h > 300f && rnd.nextFloat() < 0.08f + 0.3f * d) plats += Plat(randX(), genY + gap * (0.35f + 0.3f * rnd.nextFloat()), BREAK)
            val r = rnd.nextFloat()
            val pMove = if (h > 700f) 0.05f + 0.3f * d else 0f
            val pVan = if (h > 1800f) 0.04f + 0.16f * d else 0f
            val kind = when { r < pMove -> MOVING; r < pMove + pVan -> VANISH; else -> NORMAL }
            val p = Plat(randX(), y, kind)
            if (kind == MOVING) p.vx = (18f + 30f * d + rnd.nextFloat() * 10f) * (if (rnd.nextBoolean()) 1f else -1f)
            val ri = rnd.nextFloat()
            p.item = when {
                kind == VANISH -> ITEM_NONE
                ri < 0.05f -> SPRING
                h > 600f && ri < 0.065f -> PROP
                h > 3000f && ri < 0.073f -> JET
                else -> ITEM_NONE
            }
            p.itemDx = (rnd.nextFloat() - 0.5f) * (PLAT_W - 7f)
            sinceMonster++; sinceHole++
            // монстр или дыра — в стороне от обеих платформ (можно обойти)
            val danger = when {
                h > 1500f && sinceMonster > 6 && rnd.nextFloat() < 0.03f + 0.06f * d -> 1
                h > 4000f && sinceHole > 8 && rnd.nextFloat() < 0.02f + 0.04f * d -> 2
                else -> 0
            }
            if (danger != 0 && gap > 12f) {
                var x = 0f; var ok = false
                repeat(20) { if (!ok) { x = 8f + rnd.nextFloat() * (W - 16f); ok = abs(wdx(x, p.x)) > 32f && abs(wdx(x, lastX)) > 32f } }
                if (ok) {
                    val my = genY + gap * 0.5f + 14f
                    if (danger == 1) {
                        val m = Monster(x, my, if (rnd.nextFloat() < 0.4f + 0.3f * d) 1 else 0, rnd.nextInt(1000))
                        if (m.kind == 1) m.vx = (10f + 14f * d) * (if (rnd.nextBoolean()) 1f else -1f)
                        monsters += m; sinceMonster = 0
                    } else { holes += Hole(x, my, rnd.nextInt(1000)); sinceHole = 0 }
                    if (p.item == SPRING) p.item = ITEM_NONE
                    if (p.kind == MOVING) p.vx = 0f
                }
            }
            plats += p
            genY = y; lastX = p.x
        }
    }

    /** Шаг игры; возвращает набор EV_*. */
    fun step(dt: Float): Int {
        if (over) return 0
        var ev = 0
        t += dt
        // мир
        for (p in plats) {
            if (p.kind == MOVING && !p.broken) {
                p.x += p.vx * dt
                if (p.x < PLAT_W / 2) { p.x = PLAT_W / 2; p.vx = abs(p.vx) }
                if (p.x > W - PLAT_W / 2) { p.x = W - PLAT_W / 2; p.vx = -abs(p.vx) }
            }
            if (p.broken) { p.fallV += G * dt; p.fallY += p.fallV * dt }
            if (p.gone) p.fade += dt * 3f
            p.springT = max(0f, p.springT - dt * 3f)
        }
        for (m in monsters) {
            if (m.dead) { m.dv += G * dt; m.dy += m.dv * dt; continue }
            if (m.kind == 1) { m.x += m.vx * dt; if (m.x < 8f) { m.x = 8f; m.vx = abs(m.vx) }; if (m.x > W - 8f) { m.x = W - 8f; m.vx = -abs(m.vx) } }
            m.bob = sin(t * 3f + m.seed) * 1.5f
        }
        squash = max(0f, squash - dt * 5f)
        // затягивает в дыру
        if (holeT >= 0f) {
            holeT += dt
            px += (holeX - px) * min(1f, dt * 6f); py += (holeY - BODY_H / 2 - py) * min(1f, dt * 6f)
            if (holeT > 0.9f) { over = true; ev = ev or EV_OVER }
            return ev
        }
        // по горизонтали
        val target = input.coerceIn(-1f, 1f) * MAX_VX
        vx += (target - vx) * min(1f, dt * 9f)
        if (dying) vx *= 0.9f
        px = (px + vx * dt) % W; if (px < 0f) px += W
        if (abs(vx) > 8f) face = sign(vx)
        // по вертикали
        val oy = py
        if (fly != ITEM_NONE) {
            flyT -= dt; vy = if (fly == JET) JET_V else PROP_V
            if (flyT <= 0f) fly = ITEM_NONE
        } else vy -= G * dt
        py += vy * dt
        if (!dying) {
            if (vy < 0f && fly == ITEM_NONE) ev = ev or land(oy)
            // пропеллер / ранец
            if (fly == ITEM_NONE) for (p in plats) {
                if (p.broken || p.gone || (p.item != PROP && p.item != JET)) continue
                val ix = p.x + p.itemDx; val iy = p.y + PLAT_H / 2 + 3f
                if (abs(wdx(px, ix)) < HALF + 3f && iy > py - 3f && iy < py + BODY_H + 3f) {
                    fly = p.item; flyT = if (fly == JET) JET_T else PROP_T; p.item = ITEM_NONE; ev = ev or EV_POWER; break
                }
            }
            for (m in monsters) {
                if (m.dead) continue
                val my = m.y + m.bob
                val dx = abs(wdx(m.x, px)); val cy = my.coerceIn(py, py + BODY_H)
                val ddx = max(0f, dx - HALF)
                if (hypot(ddx, my - cy) < MONSTER_R) {
                    when {
                        fly != ITEM_NONE -> { m.dead = true; m.dv = -60f; ev = ev or EV_STOMP }
                        vy < 0f && py > my - 1f -> { m.dead = true; vy = JUMP_V; squash = 1f; ev = ev or EV_STOMP }
                        else -> { dying = true; cause = "Монстр поймал!"; vy = min(vy, 40f); ev = ev or EV_HIT }
                    }
                }
            }
            for (h in holes) {
                val dx = abs(wdx(h.x, px)); val cy = h.y.coerceIn(py, py + BODY_H)
                if (hypot(max(0f, dx - HALF), h.y - cy) < HOLE_R * 0.8f) {
                    holeT = 0f; holeX = h.x; holeY = h.y; cause = "Затянуло в дыру!"; fly = ITEM_NONE; ev = ev or EV_HOLE; return ev
                }
            }
        }
        maxY = max(maxY, py)
        // камера — только вверх
        val camTop = py + BODY_H / 2 - viewH * 0.48f
        if (camTop > camY) camY = camTop
        generateUpTo(camY + viewH + 60f)
        val cut = camY - 40f
        plats.removeAll { it.y < cut || it.fade >= 1f || it.fallY > 200f }
        monsters.removeAll { it.y < cut || it.dy > 300f }
        holes.removeAll { it.y < cut }
        if (py < camY - 25f) { over = true; if (cause.isEmpty()) cause = "Упал!"; ev = ev or EV_OVER }
        return ev
    }

    private fun land(oy: Float): Int {
        for (p in plats) {
            if (p.broken || p.gone) continue
            val top = p.y + PLAT_H / 2
            if (oy >= top && py <= top && abs(wdx(px, p.x)) < PLAT_W / 2 + HALF * 0.6f) {
                if (p.kind == BREAK) { p.broken = true; return EV_BREAK }
                py = top; squash = 1f
                var ev: Int
                if (p.item == SPRING && abs(wdx(px, p.x + p.itemDx)) < 4.5f) { vy = SPRING_V; p.springT = 1f; ev = EV_SPRING }
                else { vy = JUMP_V; ev = EV_BOING }
                if (p.kind == VANISH) { p.gone = true; ev = ev or EV_VANISH }
                return ev
            }
        }
        return 0
    }
}

// ================= экран =================

@Composable
fun JumperScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("jumper", Context.MODE_PRIVATE) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var useTilt by remember { mutableStateOf(prefs.getBoolean("tilt", true)) }
    var g by remember { mutableStateOf(JumperGame()) }
    var started by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "jumper")) }
    var oldBest by remember { mutableIntStateOf(best) }
    var record by remember { mutableStateOf(false) }
    val tilt = remember { floatArrayOf(0f) }
    val touch = remember { floatArrayOf(0f) }
    val playing = started && !g.over
    val view = LocalView.current
    DisposableEffect(playing) { view.keepScreenOn = playing; onDispose { view.keepScreenOn = false } }
    // наклон телефона: x гравитации (наклон вправо — x отрицательный)
    DisposableEffect(useTilt) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                var v = (-e.values[0] / 3.4f).coerceIn(-1f, 1f)
                v = if (abs(v) < 0.05f) 0f else (v - 0.05f * sign(v)) / 0.95f
                tilt[0] += (v - tilt[0]) * 0.6f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (useTilt && sensor != null) sm?.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME) else tilt[0] = 0f
        onDispose { sm?.unregisterListener(l) }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { JumperSfx.all() } } }
    GameLoop(playing) { dt ->
        g.input = if (touch[0] != 0f) touch[0] else if (useTilt) tilt[0] else 0f
        val ev = g.step(dt); tick++
        if (sound && ev != 0) JumperSfx.play(ev)
        if (g.over && Hi.submit(ctx, "jumper", g.score)) { best = g.score; record = true }
    }
    Box(Modifier.fillMaxSize().background(PAPER)) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touch[0] = if (down.position.x < size.width / 2) -1f else 1f
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.lastOrNull { it.pressed }
                    if (p == null) { touch[0] = 0f; break }
                    touch[0] = if (p.position.x < size.width / 2) -1f else 1f
                }
            }
        }) {
            @Suppress("UNUSED_EXPRESSION") tick
            val s = size.width / JumperGame.W
            g.viewH = size.height / s
            drawJumper(g, s, oldBest)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, bg = Color.White.copy(alpha = 0.75f), tint = INK)
                key(tick / 4) {
                    Text("${g.score}", color = INK, fontSize = 34.sp, fontFamily = FontFamily.Cursive, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 14.dp))
                }
                Text("рекорд $best", color = INK.copy(alpha = 0.7f), fontSize = 16.sp, fontFamily = FontFamily.Cursive, modifier = Modifier.padding(end = 8.dp))
                RoundIconButton(if (useTilt) Icons.Rounded.ScreenRotation else Icons.Rounded.TouchApp, if (useTilt) "Наклон" else "Касание",
                    { useTilt = !useTilt; prefs.edit().putBoolean("tilt", useTilt).apply() }, bg = Color.White.copy(alpha = 0.75f), tint = INK, size = 40.dp)
                Spacer(Modifier.width(6.dp))
                RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук",
                    { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }, bg = Color.White.copy(alpha = 0.75f), tint = INK, size = 40.dp)
            }
        }
        if (!started) GameOverlay("Прыгун",
            "Наклоняй телефон влево-вправо или держи палец на левой/правой половине экрана (значок вверху — выключить наклон). " +
                "Пружины подбрасывают, пропеллер и ранец несут вверх. Коричневые платформы ломаются, пунктирные — на один прыжок. " +
                "Монстров дави сверху, чёрных дыр не касайся.\nРекорд: $best",
            "Старт") { started = true }
        if (g.over) GameOverlay(if (record) "Новый рекорд!" else g.cause.ifEmpty { "Упал!" }, "Высота ${g.score} · рекорд $best", "Ещё раз") {
            oldBest = best; g = JumperGame(); record = false; tick++
        }
    }
}

// ================= рисунок «в тетради» =================

private val PAPER = Color(0xFFFBF7EC)
private val GRID = Color(0xFFCFE0EE)
private val MARGIN = Color(0xFFF0A7A7)
private val INK = Color(0xFF2D3446)

/** Детерминированный «шум» −1…1. */
private fun jh(a: Int, b: Int): Float {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f * 2f - 1f
}

/** Путь «от руки»: точки чуть дрожат поперёк, сглажено квадратичными кривыми. */
private fun wobble(pts: List<Offset>, closed: Boolean, amp: Float, seed: Int): Path {
    val src = if (closed) pts + pts[0] else pts
    val dense = ArrayList<Offset>()
    for (i in 0 until src.size - 1) {
        val a = src[i]; val b = src[i + 1]
        val len = (b - a).getDistance()
        val n = max(1, (len / (amp * 9f)).toInt().coerceAtMost(8))
        for (k in 0 until n) dense += a + (b - a) * (k / n.toFloat())
    }
    if (!closed) dense += src.last()
    val j = dense.mapIndexed { i, p -> p + Offset(jh(seed, i * 2) * amp, jh(seed, i * 2 + 1) * amp) }
    val path = Path()
    if (j.isEmpty()) return path
    path.moveTo(j[0].x, j[0].y)
    for (i in 1 until j.size) {
        val mid = (j[i - 1] + j[i]) / 2f
        path.quadraticTo(j[i - 1].x, j[i - 1].y, mid.x, mid.y)
    }
    path.lineTo(j.last().x, j.last().y)
    if (closed) path.close()
    return path
}

/** Обводка «карандашом»: два прохода с разной дрожью. */
private fun DrawScope.doodle(pts: List<Offset>, closed: Boolean, color: Color, w: Float, seed: Int, amp: Float = w * 0.6f, dash: Boolean = false) {
    val eff = if (dash) PathEffect.dashPathEffect(floatArrayOf(w * 3.5f, w * 2.5f)) else null
    drawPath(wobble(pts, closed, amp, seed), color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = eff))
    drawPath(wobble(pts, closed, amp * 1.3f, seed + 101), color.copy(alpha = color.alpha * 0.45f), style = Stroke(w * 0.55f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = eff))
}

private fun ellipsePts(c: Offset, rx: Float, ry: Float, n: Int = 18): List<Offset> =
    List(n) { i -> val a = 2 * PI * i / n; Offset(c.x + (cos(a) * rx).toFloat(), c.y + (sin(a) * ry).toFloat()) }

private fun roundRectPts(l: Float, t: Float, r: Float, b: Float, rad: Float): List<Offset> {
    val out = ArrayList<Offset>()
    val cs = listOf(Offset(r - rad, t + rad) to -90.0, Offset(r - rad, b - rad) to 0.0, Offset(l + rad, b - rad) to 90.0, Offset(l + rad, t + rad) to 180.0)
    for ((c, a0) in cs) for (k in 0..3) { val a = Math.toRadians(a0 + k * 30.0); out += Offset(c.x + (cos(a) * rad).toFloat(), c.y + (sin(a) * rad).toFloat()) }
    return out
}

/** Заливка «фломастером»: чуть мимо контура + штриховка. */
private fun DrawScope.fillSketch(pts: List<Offset>, fill: Color, u: Float, seed: Int, hatch: Boolean = true) {
    val p = wobble(pts, true, u * 0.25f, seed + 7)
    translate(u * 0.35f, u * 0.3f) { drawPath(p, fill) }
    if (hatch) clipPath(p) {
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val step = u * 1.6f; var x = minX - (maxY - minY)
        val c = Color.Black.copy(alpha = 0.09f)
        while (x < maxX) { drawLine(c, Offset(x, maxY), Offset(x + (maxY - minY), minY), u * 0.28f); x += step }
    }
}

private fun DrawScope.drawJumper(g: JumperGame, s: Float, oldBest: Int) {
    val viewH = size.height / s
    fun sy(y: Float) = (g.camY + viewH - y) * s
    val u = s
    val boil = ((g.t * 6f).toInt() % 3) * 1000
    // лист в клетку
    drawRect(PAPER)
    val cell = 5f
    var gy = (kotlin.math.floor(g.camY / cell)) * cell
    while (gy < g.camY + viewH + cell) { val y = sy(gy); drawLine(GRID, Offset(0f, y), Offset(size.width, y), 1.2f); gy += cell }
    var gx = 0f
    while (gx <= JumperGame.W) { drawLine(GRID, Offset(gx * s, 0f), Offset(gx * s, size.height), 1.2f); gx += cell }
    drawLine(MARGIN, Offset(9f * s, 0f), Offset(9f * s, size.height), 2.2f)
    // отметка прошлого рекорда
    if (oldBest > 0 && oldBest > g.camY && oldBest < g.camY + viewH) {
        val y = sy(oldBest.toFloat())
        doodle(listOf(Offset(10f * s, y), Offset(size.width - 2f * s, y)), false, Color(0xFFE5486A), u * 0.35f, 900 + boil, dash = true)
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(229, 72, 106); textSize = u * 3.4f; typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.BOLD); textAlign = android.graphics.Paint.Align.RIGHT }
        drawContext.canvas.nativeCanvas.drawText("рекорд", size.width - 3f * s, y - u * 1.2f, p)
    }
    // дыры
    g.holes.forEach { h -> hole(Offset(h.x * s, sy(h.y)), JumperGame.HOLE_R * s, g.t, h.seed + boil) }
    // платформы
    for (p in g.plats) {
        val alpha = (1f - p.fade).coerceIn(0f, 1f)
        if (alpha <= 0f) continue
        val cx = p.x * s; val cy = sy(p.y) + p.fallY * s
        val w = JumperGame.PLAT_W * s; val h = JumperGame.PLAT_H * s
        if (p.kind == JumperGame.BREAK && p.broken) {
            // две половинки разлетаются
            listOf(-1f, 1f).forEach { side ->
                val ox = cx + side * (w / 4 + p.fallY * s * 0.15f)
                rotate(side * p.fallY * 1.4f, Offset(ox, cy)) {
                    val pts = roundRectPts(ox - w / 4, cy - h / 2, ox + w / 4, cy + h / 2, h * 0.35f)
                    fillSketch(pts, Color(0xFFD2AE85), u, p.seed + side.toInt(), false); doodle(pts, true, INK, u * 0.4f, p.seed + boil + side.toInt())
                }
            }
            continue
        }
        if (p.item == JumperGame.SPRING) spring(Offset(cx + p.itemDx * s, cy - h / 2), u, p.springT, p.seed + boil)
        val pts = roundRectPts(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h * 0.45f)
        val fill = when (p.kind) { JumperGame.MOVING -> Color(0xFF8EC9F2); JumperGame.BREAK -> Color(0xFFD2AE85); JumperGame.VANISH -> Color(0xFFFFF4C2); else -> Color(0xFF9ED98A) }
        fillSketch(pts, fill.copy(alpha = alpha), u, p.seed)
        doodle(pts, true, INK.copy(alpha = alpha), u * 0.42f, p.seed + boil, dash = p.kind == JumperGame.VANISH)
        if (p.kind == JumperGame.BREAK) doodle(listOf(Offset(cx - w * 0.1f, cy - h / 2), Offset(cx + w * 0.02f, cy - h * 0.05f), Offset(cx - w * 0.06f, cy + h * 0.1f), Offset(cx + w * 0.08f, cy + h / 2)), false, INK, u * 0.3f, p.seed + 5 + boil)
        if (p.kind == JumperGame.MOVING) {
            val a = INK.copy(alpha = 0.6f)
            doodle(listOf(Offset(cx - w * 0.3f, cy), Offset(cx + w * 0.3f, cy)), false, a, u * 0.28f, p.seed + 9 + boil)
            doodle(listOf(Offset(cx - w * 0.2f, cy - h * 0.25f), Offset(cx - w * 0.3f, cy), Offset(cx - w * 0.2f, cy + h * 0.25f)), false, a, u * 0.28f, p.seed + 11 + boil)
            doodle(listOf(Offset(cx + w * 0.2f, cy - h * 0.25f), Offset(cx + w * 0.3f, cy), Offset(cx + w * 0.2f, cy + h * 0.25f)), false, a, u * 0.28f, p.seed + 13 + boil)
        }
        if (p.item == JumperGame.PROP) propCap(Offset(cx + p.itemDx * s, cy - h / 2 - u * 2.2f), u, g.t, p.seed + boil, false)
        if (p.item == JumperGame.JET) jetPack(Offset(cx + p.itemDx * s, cy - h / 2 - u * 3.2f), u, p.seed + boil, false, g.t)
    }
    // монстры
    g.monsters.forEach { m ->
        val c = Offset(m.x * s, sy(m.y + m.bob) + m.dy * s)
        monster(c, u, m, g.t, boil, Offset(g.px * s, sy(g.py + JumperGame.BODY_H / 2)))
    }
    // герой (у краёв — дважды, края склеены)
    val hx = g.px * s; val hy = sy(g.py)
    val heroScale = if (g.holeT >= 0f) (1f - g.holeT / 0.9f).coerceIn(0f, 1f) else 1f
    val heroRot = if (g.holeT >= 0f) g.holeT * 900f else if (g.dying) g.t * 500f else g.vx / JumperGame.MAX_VX * 9f
    for (shift in listOf(-size.width, 0f, size.width)) {
        val x = hx + shift
        if (x < -12f * s || x > size.width + 12f * s) continue
        rotate(heroRot, Offset(x, hy - 5f * u)) { scale(heroScale, Offset(x, hy - 5f * u)) { hero(Offset(x, hy), u, g, boil) } }
    }
}

/** Герой: круглый «бобик» с ростком на макушке, большие глаза, ножки; сжимается при отскоке. */
private fun DrawScope.hero(foot: Offset, u: Float, g: JumperGame, boil: Int) {
    val sq = g.squash
    val stretch = (g.vy / JumperGame.JUMP_V).coerceIn(-1f, 1.5f) * 0.06f
    val rx = 5.4f * u * (1f + sq * 0.16f - stretch * 0.5f)
    val ry = 5.6f * u * (1f - sq * 0.2f + stretch)
    val c = Offset(foot.x, foot.y - 1.2f * u - ry)
    val face = g.face
    // ранец — за спиной
    if (g.fly == JumperGame.JET) jetPack(Offset(c.x, c.y + u), u, 300 + boil, true, g.t)
    // ножки
    val legLift = if (g.vy > 0) 0.5f * u else 0f
    listOf(-1f, 1f).forEach { side ->
        val fc = Offset(foot.x + side * 2.3f * u, foot.y - 0.6f * u - legLift)
        doodle(listOf(Offset(fc.x, c.y + ry * 0.7f), fc), false, INK, u * 0.4f, 40 + side.toInt() + boil)
        val pts = ellipsePts(fc + Offset(side * 0.5f * u, 0f), 1.5f * u, 0.8f * u, 10)
        fillSketch(pts, Color(0xFFE58A4A), u, 50 + side.toInt(), false); doodle(pts, true, INK, u * 0.35f, 60 + side.toInt() + boil)
    }
    // росток
    val sway = sin(g.t * 4f) * 0.6f * u
    val top = Offset(c.x, c.y - ry)
    val sprTop = top + Offset(sway, -3f * u)
    doodle(listOf(top, top + Offset(sway * 0.4f, -1.5f * u), sprTop), false, Color(0xFF3E8E4A), u * 0.4f, 70 + boil)
    listOf(-1f, 1f).forEach { side ->
        val lc = sprTop + Offset(side * 1.6f * u, -0.3f * u)
        rotate(side * -25f, lc) {
            val pts = ellipsePts(lc, 1.7f * u, 0.85f * u, 12)
            fillSketch(pts, Color(0xFF8FD16E), u, 80 + side.toInt(), false); doodle(pts, true, INK, u * 0.33f, 90 + side.toInt() + boil)
        }
    }
    // тело
    val body = ellipsePts(c, rx, ry, 22)
    fillSketch(body, Color(0xFFFFB86B), u, 11)
    val belly = ellipsePts(c + Offset(0f, ry * 0.35f), rx * 0.55f, ry * 0.42f, 14)
    translate(u * 0.35f, u * 0.3f) { drawPath(wobble(belly, true, u * 0.2f, 13), Color(0xFFFFE0B0)) }
    doodle(body, true, INK, u * 0.48f, 12 + boil)
    // глаза
    val look = Offset(face * 0.55f * u, if (g.vy > 40f) -0.45f * u else 0.25f * u)
    listOf(-1f, 1f).forEach { side ->
        val ec = c + Offset(side * 1.95f * u + face * 0.5f * u, -1.3f * u)
        val eye = ellipsePts(ec, 1.5f * u, 1.85f * u, 14)
        translate(0f, 0f) { drawPath(wobble(eye, true, u * 0.15f, 20 + side.toInt()), Color.White) }
        doodle(eye, true, INK, u * 0.33f, 22 + side.toInt() + boil)
        if (g.dying || g.holeT >= 0f) {
            doodle(listOf(ec + Offset(-0.8f * u, -0.8f * u), ec + Offset(0.8f * u, 0.8f * u)), false, INK, u * 0.35f, 24 + boil)
            doodle(listOf(ec + Offset(0.8f * u, -0.8f * u), ec + Offset(-0.8f * u, 0.8f * u)), false, INK, u * 0.35f, 26 + boil)
        } else {
            drawCircle(INK, 0.75f * u, ec + look)
            drawCircle(Color.White, 0.25f * u, ec + look + Offset(-0.25f * u, -0.3f * u))
        }
    }
    // щёчки и рот
    listOf(-1f, 1f).forEach { side -> drawCircle(Color(0x55FF6F8E), 0.9f * u, c + Offset(side * 3.4f * u + face * 0.4f * u, 0.9f * u)) }
    val mc = c + Offset(face * 0.5f * u, 1.3f * u)
    if (g.vy < -150f || g.dying) doodle(ellipsePts(mc + Offset(0f, 0.3f * u), 0.6f * u, 0.8f * u, 10), true, INK, u * 0.33f, 30 + boil)
    else doodle(listOf(mc + Offset(-1.1f * u, -0.2f * u), mc + Offset(0f, 0.6f * u), mc + Offset(1.1f * u, -0.2f * u)), false, INK, u * 0.35f, 32 + boil)
    // пропеллер на голове
    if (g.fly == JumperGame.PROP) propCap(top + Offset(0f, -0.6f * u), u, g.t, 400 + boil, true)
}

/** Пружина: зигзаг и пластинка; при срабатывании вытягивается. */
private fun DrawScope.spring(base: Offset, u: Float, t: Float, seed: Int) {
    val hgt = (2.6f + 2.8f * t) * u
    val pts = ArrayList<Offset>()
    val n = 6
    for (i in 0..n) pts += base + Offset(if (i % 2 == 0) -1.2f * u else 1.2f * u, -hgt * i / n)
    doodle(pts, false, Color(0xFF5B6475), u * 0.4f, seed + 3)
    val plate = roundRectPts(base.x - 2.2f * u, base.y - hgt - 0.9f * u, base.x + 2.2f * u, base.y - hgt, 0.3f * u)
    fillSketch(plate, Color(0xFFB9C2CF), u, seed, false); doodle(plate, true, INK, u * 0.33f, seed + 5)
}

/** Бумажная кепка с пропеллером. */
private fun DrawScope.propCap(c: Offset, u: Float, t: Float, seed: Int, worn: Boolean) {
    val cap = List(9) { i -> val a = PI + PI * i / 8; Offset(c.x + (cos(a) * 2.8f * u).toFloat(), c.y + (sin(a) * 2.2f * u).toFloat()) }
    fillSketch(cap, Color(0xFFFF7A6B), u, seed, false)
    drawLine(Color(0xFFFFE07A), c + Offset(-1f * u, -1.9f * u), c + Offset(-0.4f * u, 0f), u * 0.7f)
    drawLine(Color(0xFFFFE07A), c + Offset(1f * u, -1.9f * u), c + Offset(0.4f * u, 0f), u * 0.7f)
    doodle(cap + cap[0], false, INK, u * 0.33f, seed + 2)
    val stemTop = c + Offset(0f, -3.3f * u)
    doodle(listOf(c + Offset(0f, -2.2f * u), stemTop), false, INK, u * 0.33f, seed + 4)
    val spinW = abs(cos(t * (if (worn) 40f else 6f))) * 3.4f * u + 0.4f * u
    val blade = ellipsePts(stemTop, spinW, 0.55f * u, 12)
    fillSketch(blade, Color(0xFF7FB7F0), u, seed + 6, false); doodle(blade, true, INK, u * 0.3f, seed + 8)
}

/** Ранец из двух ракет-«бутылок»; в полёте — пламя каракулями. */
private fun DrawScope.jetPack(c: Offset, u: Float, seed: Int, worn: Boolean, t: Float) {
    listOf(-1f, 1f).forEach { side ->
        val x = c.x + side * (if (worn) 5.6f else 1.7f) * u
        val pts = roundRectPts(x - 1.3f * u, c.y - 3.2f * u, x + 1.3f * u, c.y + 2.4f * u, 1.1f * u)
        fillSketch(pts, Color(0xFFC9CED8), u, seed + side.toInt(), false); doodle(pts, true, INK, u * 0.33f, seed + 3 + side.toInt())
        drawLine(Color(0xFFE5486A), Offset(x - 1.2f * u, c.y - 1.6f * u), Offset(x + 1.2f * u, c.y - 1.6f * u), u * 0.5f)
        if (worn) {
            val fl = ArrayList<Offset>()
            val len = 4f + 2f * abs(sin(t * 30f + side))
            for (i in 0..6) fl += Offset(x + (if (i % 2 == 0) -0.9f else 0.9f) * u * (1f - i / 7f), c.y + 2.6f * u + len * u * i / 6f)
            doodle(fl, false, Color(0xFFFF8A2B), u * 0.5f, (t * 20).toInt() + side.toInt())
            doodle(fl.map { it + Offset(0.3f * u, -0.5f * u) }, false, Color(0xFFFFD447), u * 0.35f, (t * 20).toInt() + 5)
        }
    }
}

/** Монстр: колючая клякса с одним глазом и зубами; летучий — с крылышками. */
private fun DrawScope.monster(c: Offset, u: Float, m: JumperGame.Monster, t: Float, boil: Int, hero: Offset) {
    val r = JumperGame.MONSTER_R * u
    rotate(if (m.dead) 180f else 0f, c) {
        if (m.kind == 1) {
            val flap = sin(t * 14f + m.seed) * 0.9f * u
            listOf(-1f, 1f).forEach { side ->
                val wpts = listOf(c + Offset(side * r * 0.7f, -r * 0.2f), c + Offset(side * r * 1.6f, -r * 0.9f + flap), c + Offset(side * r * 1.9f, -r * 0.1f + flap), c + Offset(side * r * 0.8f, r * 0.3f))
                fillSketch(wpts, Color(0xFFDDE7F5), u, m.seed + side.toInt(), false); doodle(wpts, true, INK, u * 0.33f, m.seed + 7 + side.toInt() + boil)
            }
        }
        val n = 16
        val pts = List(n) { i -> val a = 2 * PI * i / n; val rr = if (i % 2 == 0) r * 1.18f else r * 0.92f; Offset(c.x + (cos(a) * rr).toFloat(), c.y + (sin(a) * rr).toFloat()) }
        fillSketch(pts, if (m.kind == 1) Color(0xFF9FE3C8) else Color(0xFFC4A1EC), u, m.seed)
        doodle(pts, true, INK, u * 0.45f, m.seed + 1 + boil)
        // глаз следит за героем
        val ec = c + Offset(0f, -r * 0.2f)
        val eye = ellipsePts(ec, r * 0.42f, r * 0.42f, 14)
        drawPath(wobble(eye, true, u * 0.15f, m.seed + 2), Color.White)
        doodle(eye, true, INK, u * 0.33f, m.seed + 3 + boil)
        if (m.dead) {
            doodle(listOf(ec + Offset(-r * 0.25f, -r * 0.25f), ec + Offset(r * 0.25f, r * 0.25f)), false, INK, u * 0.33f, m.seed + 4)
            doodle(listOf(ec + Offset(r * 0.25f, -r * 0.25f), ec + Offset(-r * 0.25f, r * 0.25f)), false, INK, u * 0.33f, m.seed + 5)
        } else {
            val d = hero - ec; val l = d.getDistance().coerceAtLeast(1f)
            drawCircle(INK, r * 0.18f, ec + d / l * (r * 0.18f))
        }
        // рот с зубами
        val mouth = ArrayList<Offset>()
        for (i in 0..6) mouth += c + Offset(-r * 0.45f + r * 0.9f * i / 6f, r * 0.42f + (if (i % 2 == 0) 0f else r * 0.18f))
        doodle(mouth, false, INK, u * 0.33f, m.seed + 6 + boil)
    }
}

/** Чёрная дыра: тёмная воронка со спиралью-каракулей. */
private fun DrawScope.hole(c: Offset, r: Float, t: Float, seed: Int) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF14121C), Color(0xCC2D3446), Color(0x002D3446)), c, r * 1.35f), r * 1.35f, c)
    val pts = ArrayList<Offset>()
    for (i in 0..40) { val q = i / 40f; val a = q * 6 * PI + t * 3; val rr = r * (1f - q * 0.9f); pts += Offset(c.x + (cos(a) * rr).toFloat(), c.y + (sin(a) * rr).toFloat()) }
    doodle(pts, false, Color(0xFF8A7FB0), r * 0.06f, seed)
    doodle(ellipsePts(c, r, r, 20), true, INK, r * 0.07f, seed + 3)
}

// ================= звуки =================

/** Синтез звуков «Прыгуна»: «боинг» отскока, пружина, хруст, «плюх» монстра, удар, пропеллер, дыра, падение. */
private object JumperSfx {
    private const val SR = com.pank.vsevodnom.ui.random.Sfx.SR

    private class Syn(sec: Double) {
        val a = DoubleArray((sec * SR).toInt())
        val r = java.util.Random(23)
        fun tone(at: Double, dur: Double, amp: Double, f: (Double) -> Double, env: (Double) -> Double, square: Boolean = false) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); var ph = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break; val x = i.toDouble() / n; ph += 2 * PI * f(x) / SR
                val v = if (square) (if (sin(ph) >= 0) 0.6 else -0.6) else sin(ph)
                a[j] += v * amp * env(x)
            }
        }
        fun noise(at: Double, dur: Double, amp: Double, cutoff: Double, env: (Double) -> Double) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); var y = 0.0
            val w = 2 * PI * cutoff / SR; val k = w / (w + 1)
            for (i in 0 until n) { val j = s0 + i; if (j !in a.indices) break; y += k * ((r.nextDouble() * 2 - 1) - y); a[j] += y * 2.5 * amp * env(i.toDouble() / n) }
        }
        fun pcm(gain: Double = 0.7): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4); val fade = (0.003 * SR).toInt()
            return ShortArray(a.size) { i -> val e = minOf(1.0, i.toDouble() / fade, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }

    val boing by lazy { Syn(0.14).apply { tone(0.0, 0.13, 0.9, { 300 + 520 * it }, { exp(-it * 3) * min(1.0, it * 40) }) }.pcm(0.4) }
    val spring by lazy { Syn(0.45).apply { tone(0.0, 0.42, 0.9, { 240 + 700 * it + 60 * sin(it * 70) }, { exp(-it * 2.5) * min(1.0, it * 60) }) }.pcm(0.5) }
    val crack by lazy { Syn(0.25).apply { noise(0.0, 0.22, 1.0, 1800.0) { x -> exp(-x * 5) * (0.6 + 0.4 * sin(x * 90)) }; tone(0.0, 0.1, 0.5, { 160 - 60 * it }, { 1 - it }) }.pcm(0.55) }
    val vanish by lazy { Syn(0.12).apply { tone(0.0, 0.11, 0.8, { 900 + 900 * it }, { 1 - it }) }.pcm(0.3) }
    val stomp by lazy { Syn(0.25).apply { tone(0.0, 0.22, 0.9, { 380 - 300 * it }, { exp(-it * 3) }, square = true); noise(0.0, 0.06, 0.6, 900.0) { 1 - it } }.pcm(0.45) }
    val hit by lazy { Syn(0.5).apply { tone(0.0, 0.45, 0.9, { 520 * exp(-it * 1.6) }, { 1 - it }, square = true); noise(0.0, 0.08, 0.7, 2500.0) { 1 - it } }.pcm(0.45) }
    val power by lazy { Syn(0.5).apply { listOf(523.0, 659.0, 784.0, 1046.0).forEachIndexed { i, f -> tone(i * 0.08, 0.2, 0.6, { f }, { exp(-it * 3) }) } }.pcm(0.45) }
    val holeS by lazy { Syn(0.8).apply { tone(0.0, 0.78, 0.9, { 700 * exp(-it * 2.2) + 40 * sin(it * 50) }, { (1 - it) * min(1.0, it * 20) }) }.pcm(0.45) }
    val fall by lazy { Syn(0.7).apply { tone(0.0, 0.68, 0.9, { 900 - 700 * it }, { (1 - it * 0.8) * min(1.0, it * 30) }) }.pcm(0.4) }

    fun play(ev: Int) {
        val (key, pcm) = when {
            ev and JumperGame.EV_OVER != 0 -> "jump_over" to fall
            ev and JumperGame.EV_HOLE != 0 -> "jump_over" to holeS
            ev and JumperGame.EV_HIT != 0 -> "jump_hit" to hit
            ev and JumperGame.EV_STOMP != 0 -> "jump_stomp" to stomp
            ev and JumperGame.EV_POWER != 0 -> "jump_power" to power
            ev and JumperGame.EV_SPRING != 0 -> "jump_spring" to spring
            ev and JumperGame.EV_BREAK != 0 -> "jump_break" to crack
            ev and JumperGame.EV_BOING != 0 -> "jump_boing" to boing
            ev and JumperGame.EV_VANISH != 0 -> "jump_vanish" to vanish
            else -> return
        }
        com.pank.vsevodnom.ui.random.Sfx.playPcm(key, pcm)
        if (ev and JumperGame.EV_VANISH != 0 && key != "jump_vanish") com.pank.vsevodnom.ui.random.Sfx.playPcm("jump_vanish", vanish)
    }

    fun all() = listOf(boing, spring, crack, vanish, stomp, hit, power, holeS, fall)
}

/** Для тестов: звуки «Прыгуна» собираются без ошибок (длина в отсчётах). */
fun jumperSoundSizes(): List<Int> = JumperSfx.all().map { it.size }
