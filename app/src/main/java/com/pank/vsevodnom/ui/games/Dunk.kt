package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.RoundIconButton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Кольцо «Броска». Мир: ширина 100, ось y — вверх. Кольцо — отрезок между двумя дужками (точки-«шарики»
 * радиуса RIM_R) с наклоном angle (рад, против часовой); «верх» кольца — нормаль (−sin, cos).
 */
class DunkHoop(x: Float, y: Float) {
    var x = x; var y = y
    var baseX = x; var ampX = 0f; var speedX = 0f
    var baseAngle = 0f; var ampAngle = 0f; var speedA = 0f
    var phase = 0f
    var angle = 0f
    /** Кольцо с мячом стоит на месте (качание/езда прекращаются). */
    var frozen = false
    /** Отклик сетки при попадании 1 → 0. */
    var netKick = 0f
    /** > 0 — старое кольцо исчезает (0…1). */
    var fade = 0f
    val moving get() = ampX > 0f || ampAngle > 0f

    fun update(t: Float) {
        if (frozen) return
        x = baseX + ampX * sin(t * speedX + phase)
        angle = baseAngle + ampAngle * sin(t * speedA + phase * 1.7f)
    }
    fun rimX(side: Int) = x + side * DunkGame.HOOP_R * cos(angle)
    fun rimY(side: Int) = y + side * DunkGame.HOOP_R * sin(angle)
    fun localX(px: Float, py: Float) = (px - x) * cos(angle) + (py - y) * sin(angle)
    fun localY(px: Float, py: Float) = -(px - x) * sin(angle) + (py - y) * cos(angle)
    /** Где лежит мяч в сетке. */
    fun restX() = x + sin(angle) * DunkGame.REST
    fun restY() = y - cos(angle) * DunkGame.REST
}

/**
 * «Бросок»: мяч лежит в сетке кольца; тянешь назад, как рогатку, отпускаешь — летит по параболе.
 * Нужно попасть в следующее кольцо (они чередуются слева/справа и поднимаются). Без касания дужки — «чисто»:
 * серия чистых растит очки (+1 за попадание, +1+серия за чистое, +1 за отскок от стены) и поджигает мяч.
 * Мяч ниже экрана — конец. С 8 очков кольца ездят, с 16 — качаются, с 24 — бывают наклонены.
 */
class DunkGame(private val rnd: Random = Random) {
    companion object {
        const val W = 100f
        const val BALL_R = 4.6f
        const val HOOP_R = 9.5f
        const val RIM_R = 0.9f
        const val G = 270f
        const val PULL_MAX = 30f
        const val PULL_K = 8.4f
        const val PULL_MIN = 3.5f
        const val REST = 3.4f
        const val E_RIM = 0.55f
        const val E_WALL = 0.82f
        /** Кольца не ближе к стене (между дужкой и стеной мяч не должен застревать). */
        const val WALL_GAP = 13f

        const val EV_LAUNCH = 1; const val EV_RIM = 2; const val EV_WALL = 4; const val EV_SCORE = 8
        const val EV_SWISH = 16; const val EV_OVER = 32; const val EV_FIRE = 64; const val EV_BACK = 128

        const val S_REST = 0; const val S_FLY = 1; const val S_SETTLE = 2
    }

    /** [0] — кольцо с мячом, [1] — цель. */
    val hoops = ArrayList<DunkHoop>()
    /** Исчезающие пройденные кольца. */
    val gone = ArrayList<DunkHoop>()
    var bx = 0f; var by = 0f; var vx = 0f; var vy = 0f
    var spin = 0f; private var spinV = 0f
    var state = S_REST
    var over by mutableStateOf(false)
    var score = 0; var streak = 0; var hoopsDone = 0
    var rimTouched = false; private var wallTouched = false
    var camY = 0f; var viewH = 180f
    var aiming = false; var pullX = 0f; var pullY = 0f
    /** След мяча (x, y) — для огня. */
    val trail = ArrayDeque<Pair<Float, Float>>()
    var popup = ""; var popupT = 0f; var popupX = 0f; var popupY = 0f
    var t = 0f
    private var flightT = 0f; private var settleT = 0f; private var sx0 = 0f; private var sy0 = 0f
    private var from: DunkHoop? = null
    private var pendingEv = 0
    val onFire get() = streak >= 2

    init {
        hoops += DunkHoop(28f + rnd.nextFloat() * 8f, 0f).apply { frozen = true }
        hoops += nextHoop(hoops[0])
        placeBall(); snapCamera()
    }

    fun camTarget() = hoops[0].y - viewH * 0.3f
    fun snapCamera() { camY = camTarget() }

    private fun nextHoop(prev: DunkHoop): DunkHoop {
        val right = prev.x < W / 2
        val dy = 24f + rnd.nextFloat() * 14f + min(6f, score * 0.15f)
        val x = if (right) 60f + rnd.nextFloat() * 18f else 22f + rnd.nextFloat() * 18f
        val h = DunkHoop(x, prev.y + dy)
        h.phase = rnd.nextFloat() * 2f * PI.toFloat()
        if (score >= 8 && rnd.nextFloat() < min(0.55f, 0.2f + (score - 8) * 0.03f)) {
            val amp = 5f + rnd.nextFloat() * min(10f, 3f + score * 0.15f)
            h.ampX = amp; h.speedX = 0.8f + rnd.nextFloat() * 0.8f + min(0.8f, score * 0.01f)
            h.baseX = x.coerceIn(WALL_GAP + HOOP_R + amp, W - WALL_GAP - HOOP_R - amp)
        }
        if (score >= 16 && rnd.nextFloat() < min(0.5f, 0.15f + (score - 16) * 0.025f)) {
            h.ampAngle = 0.2f + rnd.nextFloat() * 0.3f; h.speedA = 0.9f + rnd.nextFloat() * 0.8f
        }
        if (score >= 24 && rnd.nextFloat() < 0.35f) h.baseAngle = (if (right) 1f else -1f) * (0.15f + rnd.nextFloat() * 0.25f)
        h.update(t)
        return h
    }

    // ---- прицел ----
    fun canAim() = !over && state == S_REST
    fun beginAim() { if (canAim()) { aiming = true; pullX = 0f; pullY = 0f } }
    /** Натяжение (мировые единицы): куда полетит мяч. Ограничено PULL_MAX. */
    fun setPull(x: Float, y: Float) {
        if (!aiming) return
        val l = hypot(x, y); val k = if (l > PULL_MAX) PULL_MAX / l else 1f
        pullX = x * k; pullY = y * k
    }
    fun cancelAim() { aiming = false; pullX = 0f; pullY = 0f }
    /** Отпустил палец: бросок, если натянул достаточно и вверх. */
    fun release(): Boolean {
        if (!aiming) return false
        aiming = false
        val ok = hypot(pullX, pullY) >= PULL_MIN && pullY > 1f
        if (ok) launch(pullX * PULL_K, pullY * PULL_K)
        pullX = 0f; pullY = 0f
        return ok
    }
    fun power() = hypot(pullX, pullY) / PULL_MAX
    fun aimAngle() = atan2(-pullX, pullY).coerceIn(-0.95f, 0.95f)
    /** На сколько оттянута сетка. */
    fun netStretch() = if (aiming) hypot(pullX, pullY) * 0.22f else 0f

    fun launch(lvx: Float, lvy: Float) {
        if (over || state != S_REST) return
        aiming = false
        from = hoops[0]; state = S_FLY
        vx = lvx; vy = lvy; spinV = -vx * 0.07f
        rimTouched = false; wallTouched = false; flightT = 0f
        trail.clear(); pendingEv = pendingEv or EV_LAUNCH
    }

    /** Скорость, с которой мяч из текущего места пролетит через (tx, ty) сверху вниз (вершина на apexAbove выше). */
    fun velocityTo(tx: Float, ty: Float, apexAbove: Float = 14f): Pair<Float, Float> {
        val apex = max(by, ty) + apexAbove
        val vy0 = sqrt(2f * G * (apex - by)); val vyT = sqrt(2f * G * (apex - ty))
        val time = (vy0 + vyT) / G
        return (tx - bx) / time to vy0
    }

    /** Точки пунктира прицела (x, y, …): та же физика без дужек, со стенами. */
    fun preview(): FloatArray {
        if (!aiming) return FloatArray(0)
        var x = bx; var y = by; var px = pullX * PULL_K; var py = pullY * PULL_K
        val out = ArrayList<Float>()
        val h = 1f / 120f
        val steps = (60 + 60 * power()).toInt()
        for (i in 1..steps) {
            py -= G * h; x += px * h; y += py * h
            if (x < BALL_R) { x = BALL_R; px = -px * E_WALL }
            if (x > W - BALL_R) { x = W - BALL_R; px = -px * E_WALL }
            if (i % 6 == 0) { out += x; out += y }
        }
        return out.toFloatArray()
    }

    private fun placeBall() {
        val h = hoops[0]
        bx = h.restX() - pullX * 0.22f
        by = h.restY() - pullY * 0.22f
    }

    /** Шаг игры (dt в секундах). Возвращает набор событий EV_*. */
    fun step(dt: Float): Int {
        var ev = pendingEv; pendingEv = 0
        if (over) return ev
        t += dt
        for (h in hoops) h.update(t)
        val h0 = hoops[0]
        val tilt = if (aiming && state == S_REST) aimAngle() else 0f
        h0.angle += (tilt - h0.angle) * min(1f, dt * 16f)
        for (h in hoops) h.netKick = max(0f, h.netKick - dt * 2.5f)
        gone.forEach { it.fade += dt * 2f }; gone.removeAll { it.fade >= 1f }
        popupT = max(0f, popupT - dt)
        spin += spinV * dt
        when (state) {
            S_REST -> { placeBall(); spinV *= 0.95f; if (trail.isNotEmpty()) trail.removeFirst() }
            S_SETTLE -> {
                settleT += dt
                val k = min(1f, settleT / 0.18f); val e = 1f - (1f - k) * (1f - k)
                bx = sx0 + (h0.restX() - sx0) * e; by = sy0 + (h0.restY() - sy0) * e
                spinV *= 0.9f
                if (trail.isNotEmpty()) trail.removeFirst()
                if (k >= 1f) state = S_REST
            }
            S_FLY -> {
                flightT += dt
                ev = ev or physics(dt)
                trail.addLast(bx to by); while (trail.size > 14) trail.removeFirst()
                if (state == S_FLY && (by < camY - BALL_R * 3f || flightT > 8f)) { over = true; ev = ev or EV_OVER }
            }
        }
        val target = if (state == S_FLY) max(camTarget(), by - viewH * 0.78f) else camTarget()
        camY += (target - camY) * min(1f, dt * 3.2f)
        return ev
    }

    private fun physics(dt: Float): Int {
        var ev = 0
        val n = max(1, ceil(dt / (1f / 240f)).toInt()); val h = dt / n
        for (i in 0 until n) {
            if (state != S_FLY) break
            vy -= G * h
            val ox = bx; val oy = by
            bx += vx * h; by += vy * h
            if (bx < BALL_R) { bx = BALL_R; if (vx < 0f) { vx = -vx * E_WALL; spinV = -spinV * 0.7f; wallTouched = true; ev = ev or EV_WALL } }
            if (bx > W - BALL_R) { bx = W - BALL_R; if (vx > 0f) { vx = -vx * E_WALL; spinV = -spinV * 0.7f; wallTouched = true; ev = ev or EV_WALL } }
            for (hp in hoops.toList()) {
                val rising = hp === from && vy > 0f
                if (!rising) {
                    for (side in intArrayOf(-1, 1)) {
                        val imp = collideRim(hp.rimX(side), hp.rimY(side))
                        if (imp > 25f) ev = ev or EV_RIM
                    }
                } else continue
                val ly0 = hp.localY(ox, oy); val ly1 = hp.localY(bx, by); val lx1 = hp.localX(bx, by)
                val vly = -vx * sin(hp.angle) + vy * cos(hp.angle)
                if (ly0 > 0f && ly1 <= 0f && abs(lx1) < HOOP_R - RIM_R - 0.4f && vly < 0f) { ev = ev or catchIn(hp); break }
            }
        }
        return ev
    }

    /** Столкновение с дужкой: возвращает скорость удара (0 — не было). */
    private fun collideRim(rx: Float, ry: Float): Float {
        val dx = bx - rx; val dy = by - ry; val d = hypot(dx, dy); val m = BALL_R + RIM_R
        if (d >= m || d < 1e-4f) return 0f
        val nx = dx / d; val ny = dy / d
        bx = rx + nx * m; by = ry + ny * m
        val vn = vx * nx + vy * ny
        if (vn >= 0f) return 0f
        val tx = -ny; val ty = nx; val vt = vx * tx + vy * ty
        val vn2 = -vn * E_RIM; val vt2 = vt * 0.94f
        vx = nx * vn2 + tx * vt2; vy = ny * vn2 + ty * vt2
        spinV = -vt2 / BALL_R * 0.6f
        rimTouched = true
        return -vn
    }

    private fun catchIn(h: DunkHoop): Int {
        state = S_SETTLE; settleT = 0f; sx0 = bx; sy0 = by; vx = 0f; vy = 0f
        h.netKick = 1f
        if (h !== hoops.getOrNull(1)) return EV_BACK
        val swish = !rimTouched
        val wasFire = onFire
        streak = if (swish) streak + 1 else 0
        var pts = if (swish) 1 + min(streak, 9) else 1
        if (wallTouched) pts += 1
        score += pts; hoopsDone++
        popup = when {
            swish && wallTouched -> "От стены и чисто! +$pts"
            swish && streak >= 2 -> "Чисто ×$streak! +$pts"
            swish -> "Чисто! +$pts"
            wallTouched -> "От стены! +$pts"
            else -> "+1"
        }
        popupT = 1.3f; popupX = h.x; popupY = h.y + 12f
        h.frozen = true; h.baseX = h.x
        val old = hoops.removeAt(0); old.fade = 0.001f; gone += old
        hoops += nextHoop(h)
        var ev = EV_SCORE
        if (swish) ev = ev or EV_SWISH
        if (onFire && !wasFire) ev = ev or EV_FIRE
        return ev
    }
}

// ================= экран =================

@Composable
fun DunkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("dunk", Context.MODE_PRIVATE) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var g by remember { mutableStateOf(DunkGame()) }
    var started by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "dunk")) }
    var record by remember { mutableStateOf(false) }
    val playing = started && !g.over
    val view = LocalView.current
    DisposableEffect(playing) { view.keepScreenOn = playing; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { DunkSfx.all() } } }
    GameLoop(playing) { dt ->
        val ev = g.step(dt); tick++
        if (sound && ev != 0) DunkSfx.play(ev)
        if (g.over && Hi.submit(ctx, "dunk", g.score)) { best = g.score; record = true }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFFCE5D6))) {
        Canvas(Modifier.fillMaxSize().pointerInput(g, started) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (!started || !g.canAim()) return@awaitEachGesture
                g.beginAim()
                val s = size.width / DunkGame.W
                var done = false
                while (!done) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id }
                    if (c == null) { g.cancelAim(); done = true }
                    else if (!c.pressed) { g.release(); done = true }
                    else { g.setPull((down.position.x - c.position.x) / s, (c.position.y - down.position.y) / s); c.consume() }
                }
                tick++
            }
        }) {
            @Suppress("UNUSED_EXPRESSION") tick
            val s = size.width / DunkGame.W
            g.viewH = size.height / s
            if (!started) g.snapCamera()
            drawDunk(g, s)
        }
        // шапка
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, bg = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text("рекорд $best", color = Color(0xCC3A2A40), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук",
                    { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }, bg = Color.White.copy(alpha = 0.7f))
            }
            key(tick / 3) {
                Text("${g.score}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                if (g.onFire && g.state != DunkGame.S_FLY) Text("в огне ×${g.streak}", color = Color(0xFFFF6A2B), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (!started) GameOverlay("Бросок",
            "Потяни мяч назад, как рогатку, и отпусти — он полетит. Попади в следующее кольцо. Не задел дужку — «чисто»: очки за серию растут, мяч загорается. Мимо и вниз — конец.\nРекорд: $best",
            "Старт") { started = true; g.snapCamera() }
        if (g.over) GameOverlay(if (record) "Новый рекорд!" else "Мимо!", "Очки ${g.score} · колец ${g.hoopsDone} · рекорд $best", "Ещё раз") {
            g = DunkGame(); record = false; tick++
        }
    }
}

/** Фон: мягкий градиент, меняется с высотой; светлые круги с параллаксом. */
private val DUNK_SKIES = listOf(
    listOf(Color(0xFFFFE3D3), Color(0xFFF8C9D8)),
    listOf(Color(0xFFE6E0FA), Color(0xFFC9D9F8)),
    listOf(Color(0xFFD7F1EA), Color(0xFFBFE2F3)),
    listOf(Color(0xFFFFF0C9), Color(0xFFFFCFB8)),
    listOf(Color(0xFFF3D9F5), Color(0xFFD4C8F6)),
)

private fun DrawScope.drawDunk(g: DunkGame, s: Float) {
    val viewH = size.height / s
    fun sx(x: Float) = x * s
    fun sy(y: Float) = (g.camY + viewH - y) * s
    // небо
    val k = (g.camY / 500f).coerceAtLeast(0f)
    val i0 = k.toInt() % DUNK_SKIES.size; val i1 = (i0 + 1) % DUNK_SKIES.size; val f = k - k.toInt()
    val top = lerp(DUNK_SKIES[i0][1], DUNK_SKIES[i1][1], f); val bottom = lerp(DUNK_SKIES[i0][0], DUNK_SKIES[i1][0], f)
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    // круги-«боке» с параллаксом 0,4
    val par = g.camY * 0.4f
    val cell = 70f
    val firstCell = ((par - 40f) / cell).toInt() - 1
    for (c in firstCell..firstCell + (viewH / cell).toInt() + 3) {
        val r = Random(c * 7919 + 13)
        repeat(2) {
            val x = r.nextFloat() * 100f; val y = c * cell + r.nextFloat() * cell
            val rad = 6f + r.nextFloat() * 16f
            val yy = (par + viewH - y) * s
            drawCircle(Color.White.copy(alpha = 0.18f + r.nextFloat() * 0.12f), rad * s, Offset(x * s, yy))
        }
    }
    // исчезающие кольца
    g.gone.forEach { h -> hoopBack(h, s, ::sx, ::sy, 1f - h.fade); hoopFront(h, s, ::sx, ::sy, 0f, false, 1f - h.fade) }
    g.hoops.forEach { h -> hoopBack(h, s, ::sx, ::sy, 1f) }
    // пунктир прицела
    val pts = g.preview()
    var j = 0
    while (j + 1 < pts.size) {
        val a = 1f - j / pts.size.toFloat()
        drawCircle(Color.White.copy(alpha = 0.9f * a), s * (1.1f - 0.4f * j / pts.size), Offset(sx(pts[j]), sy(pts[j + 1])))
        j += 2
    }
    // огненный след
    if (g.onFire && g.trail.size > 1) {
        val n = g.trail.size
        g.trail.forEachIndexed { i, (x, y) ->
            val q = (i + 1f) / n
            val c = lerp(Color(0xFFFF3D1F), Color(0xFFFFD447), q)
            drawCircle(c.copy(alpha = 0.55f * q), DunkGame.BALL_R * s * (0.35f + 0.65f * q), Offset(sx(x), sy(y)))
        }
    }
    if (g.onFire) {
        // языки пламени над мячом
        val c = Offset(sx(g.bx), sy(g.by))
        for (q in 0 until 5) {
            val ph = g.t * 9f + q * 1.3f
            val dx = sin(ph) * 0.9f + (q - 2) * 1.2f
            val h = 3f + 2.2f * abs(sin(ph * 0.7f + q))
            drawCircle(Color(0xFFFF8A2B).copy(alpha = 0.35f), (1.6f + 0.4f * (q % 2)) * s, c + Offset(dx * s, -(DunkGame.BALL_R + h * 0.6f) * s))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0x66FF9A3D), Color(0x00FF9A3D)), c, DunkGame.BALL_R * s * 2.2f), DunkGame.BALL_R * s * 2.2f, c)
    }
    // мяч
    ball(Offset(sx(g.bx), sy(g.by)), DunkGame.BALL_R * s, g.spin)
    // сетка и передняя дужка — поверх мяча
    g.hoops.forEachIndexed { idx, h ->
        val sit = idx == 0 && g.state != DunkGame.S_FLY
        hoopFront(h, s, ::sx, ::sy, if (idx == 0) g.netStretch() else 0f, sit, 1f, if (sit) h.localX(g.bx, g.by) else 0f, if (sit) h.localY(g.bx, g.by) else 0f)
    }
    // надпись «Чисто!»
    if (g.popupT > 0f) {
        val a = min(1f, g.popupT / 0.4f)
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255); textSize = s * 5.2f; isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER; setShadowLayer(s * 0.8f, 0f, s * 0.3f, android.graphics.Color.argb((a * 120).toInt(), 120, 40, 60))
        }
        val x = g.popupX.coerceIn(28f, 72f)
        drawContext.canvas.nativeCanvas.drawText(g.popup, sx(x), sy(g.popupY + (1.3f - g.popupT) * 8f), p)
    }
}

private val RIM_BACK = Color(0xFFB8442A)
private val RIM_FRONT = Color(0xFFFF6B3D)

/** Задняя половина дужки (за мячом). */
private fun DrawScope.hoopBack(h: DunkHoop, s: Float, sx: (Float) -> Float, sy: (Float) -> Float, alpha: Float) {
    val c = Offset(sx(h.x), sy(h.y)); val r = DunkGame.HOOP_R * s; val e = r * 0.3f
    rotate(-(h.angle * 180f / PI.toFloat()), c) {
        drawArc(RIM_BACK.copy(alpha = alpha), 180f, 180f, false, Offset(c.x - r, c.y - e), Size(2 * r, 2 * e), style = Stroke(DunkGame.RIM_R * 1.4f * s))
    }
}

/** Сетка (растягивается, когда мяч в ней и тянут) и передняя дужка. bx/by — мяч в системе кольца. */
private fun DrawScope.hoopFront(h: DunkHoop, s: Float, sx: (Float) -> Float, sy: (Float) -> Float, stretch: Float, sit: Boolean, alpha: Float, blx: Float = 0f, bly: Float = 0f) {
    val c = Offset(sx(h.x), sy(h.y)); val r = DunkGame.HOOP_R * s; val e = r * 0.3f
    rotate(-(h.angle * 180f / PI.toFloat()), c) {
        val rows = 5; val cols = 8
        val kick = h.netKick
        val depth = (11f + stretch * 0.9f + (if (sit) 1.6f else 0f) + kick * 4f * sin(kick * 9f)) * s
        val netC = Color.White.copy(alpha = 0.92f * alpha)
        val pts = Array(rows) { row ->
            val q = row / (rows - 1f)
            var rad = r * (1f - 0.42f * q)
            val yy = c.y + depth * q
            // мяч распирает сетку
            if (sit) {
                val ballY = c.y - bly * s; val dy = (yy - ballY) / s
                if (abs(dy) < DunkGame.BALL_R) rad = max(rad, (sqrt(DunkGame.BALL_R * DunkGame.BALL_R - dy * dy) + 0.6f) * s)
            }
            Array(cols) { i ->
                val phi = PI * (i + if (row % 2 == 1) 0.5 else 0.0) / (cols - 1)
                val ex = e * (1f - 0.6f * q)
                Offset(c.x + (cos(phi) * rad).toFloat(), yy + (sin(phi) * ex).toFloat())
            }
        }
        for (row in 0 until rows - 1) for (i in 0 until cols) {
            val a = pts[row][i]
            val b1 = pts[row + 1][i]; val b2 = pts[row + 1].getOrNull(if (row % 2 == 0) i - 1 else i + 1)
            drawLine(netC, a, b1, s * 0.35f, StrokeCap.Round)
            if (b2 != null) drawLine(netC, a, b2, s * 0.35f, StrokeCap.Round)
        }
        drawArc(RIM_FRONT.copy(alpha = alpha), 0f, 180f, false, Offset(c.x - r, c.y - e), Size(2 * r, 2 * e), style = Stroke(DunkGame.RIM_R * 2f * s, cap = StrokeCap.Round))
        drawArc(Color.White.copy(alpha = 0.35f * alpha), 20f, 140f, false, Offset(c.x - r, c.y - e), Size(2 * r, 2 * e), style = Stroke(DunkGame.RIM_R * 0.5f * s, cap = StrokeCap.Round))
        listOf(-1f, 1f).forEach { side -> drawCircle(Color(0xFFD9502C).copy(alpha = alpha), DunkGame.RIM_R * 1.15f * s, Offset(c.x + side * r, c.y)) }
    }
}

/** Мяч: оранжевый с объёмом, швы крутятся. */
private fun DrawScope.ball(c: Offset, R: Float, spin: Float) {
    drawCircle(Color(0x33000000), R, c + Offset(R * 0.08f, R * 0.12f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFA45C), Color(0xFFF07A2E), Color(0xFFC9531B)), c + Offset(-R * 0.3f, -R * 0.35f), R * 1.5f), R, c)
    val seam = Color(0xFF6B2A10); val w = R * 0.09f
    rotate(spin * 180f / PI.toFloat(), c) {
        drawLine(seam, c + Offset(0f, -R), c + Offset(0f, R), w)
        drawLine(seam, c + Offset(-R, 0f), c + Offset(R, 0f), w)
        drawArc(seam, -50f, 100f, false, Offset(c.x - R * 2.15f, c.y - R * 0.95f), Size(R * 1.9f, R * 1.9f), style = Stroke(w))
        drawArc(seam, 130f, 100f, false, Offset(c.x + R * 0.25f, c.y - R * 0.95f), Size(R * 1.9f, R * 1.9f), style = Stroke(w))
    }
    drawCircle(Color.White.copy(alpha = 0.28f), R * 0.32f, c + Offset(-R * 0.38f, -R * 0.42f))
}

// ================= звуки =================

/** Синтез коротких звуков «Броска»: дужка, стена, «вжух» сетки, бросок, огонь, проигрыш. */
private object DunkSfx {
    private const val SR = com.pank.vsevodnom.ui.random.Sfx.SR

    private class Syn(sec: Double) {
        val a = DoubleArray((sec * SR).toInt())
        val r = java.util.Random(11)
        fun tone(at: Double, dur: Double, amp: Double, f: (Double) -> Double, env: (Double) -> Double) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); var ph = 0.0
            for (i in 0 until n) { val j = s0 + i; if (j !in a.indices) break; val x = i.toDouble() / n; ph += 2 * PI * f(x) / SR; a[j] += sin(ph) * amp * env(x) }
        }
        fun noise(at: Double, dur: Double, amp: Double, cutoff: (Double) -> Double, hp: Boolean, env: (Double) -> Double) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); var y = 0.0; var y2 = 0.0; var lo = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break; val x = i.toDouble() / n
                val w = 2 * PI * cutoff(x) / SR; val k = w / (w + 1)
                y += k * ((r.nextDouble() * 2 - 1) - y); y2 += k * (y - y2)
                var v = y2 * 3; if (hp) { lo += 0.03 * (v - lo); v -= lo }
                a[j] += v * amp * env(x)
            }
        }
        fun hit(at: Double, modes: List<Pair<Double, Double>>, amp: Double) {
            val s0 = (at * SR).toInt()
            modes.forEachIndexed { m, (f, d) ->
                for (i in 0 until (SR * 5.0 / d).toInt()) { val j = s0 + i; if (j !in a.indices) break; val t = i.toDouble() / SR
                    a[j] += sin(2 * PI * f * t) * exp(-t * d) * amp / (1 + m * 0.6) }
            }
        }
        fun pcm(gain: Double = 0.75): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4); val fade = (0.003 * SR).toInt()
            return ShortArray(a.size) { i -> val e = minOf(1.0, i.toDouble() / fade, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }

    val rim by lazy { Syn(0.4).apply { hit(0.0, listOf(540.0 to 16.0, 1210.0 to 26.0, 2380.0 to 40.0, 3900.0 to 60.0), 0.8); noise(0.0, 0.01, 0.5, { 4000.0 }, true) { 1 - it } }.pcm(0.6) }
    val wall by lazy { Syn(0.18).apply { tone(0.0, 0.16, 0.9, { 150 - 70 * it }) { exp(-it * 6) }; noise(0.0, 0.05, 0.6, { 500.0 }, false) { 1 - it } }.pcm(0.6) }
    val swish by lazy { Syn(0.3).apply { noise(0.0, 0.28, 1.0, { 2500 + 5000 * it }, true) { x -> sin(PI * x).let { it * it } * (1 - x * 0.4) } }.pcm(0.55) }
    val net by lazy { Syn(0.22).apply { noise(0.0, 0.18, 0.8, { 1800 + 2500 * it }, true) { x -> sin(PI * x) }; tone(0.0, 0.08, 0.5, { 200 - 60 * it }) { 1 - it } }.pcm(0.5) }
    val launch by lazy { Syn(0.14).apply { noise(0.0, 0.13, 0.7, { 700 + 900 * it }, false) { x -> sin(PI * x) }; tone(0.0, 0.1, 0.25, { 220 + 160 * it }) { 1 - it } }.pcm(0.35) }
    val fire by lazy { Syn(0.55).apply { listOf(660.0, 880.0, 1320.0).forEachIndexed { i, f -> tone(i * 0.07, 0.35, 0.6, { f }) { exp(-it * 4) } } ; noise(0.0, 0.5, 0.25, { 3000.0 }, true) { 1 - it } }.pcm(0.5) }
    val over by lazy { Syn(0.6).apply { tone(0.0, 0.55, 0.8, { 420 - 260 * it }) { (1 - it) * (0.75 + 0.25 * sin(it * 60)) } }.pcm(0.45) }

    fun play(ev: Int) {
        val (key, pcm) = when {
            ev and DunkGame.EV_OVER != 0 -> "dunk_over" to over
            ev and DunkGame.EV_FIRE != 0 -> "dunk_fire" to fire
            ev and DunkGame.EV_SWISH != 0 -> "dunk_net" to swish
            ev and (DunkGame.EV_SCORE or DunkGame.EV_BACK) != 0 -> "dunk_net" to net
            ev and DunkGame.EV_RIM != 0 -> "dunk_rim" to rim
            ev and DunkGame.EV_WALL != 0 -> "dunk_wall" to wall
            ev and DunkGame.EV_LAUNCH != 0 -> "dunk_launch" to launch
            else -> return
        }
        com.pank.vsevodnom.ui.random.Sfx.playPcm(key, pcm)
        // дужка + попадание в одном кадре: дужку тоже слышно
        if (ev and DunkGame.EV_RIM != 0 && key != "dunk_rim") com.pank.vsevodnom.ui.random.Sfx.playPcm("dunk_rim", rim)
    }

    /** Для теста: собрать все звуки. */
    fun all() = listOf(rim, wall, swish, net, launch, fire, over)
}

/** Для тестов: звуки «Броска» собираются без ошибок (длина в отсчётах). */
fun dunkSoundSizes(): List<Int> = DunkSfx.all().map { it.size }
