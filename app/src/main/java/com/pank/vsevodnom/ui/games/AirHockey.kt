package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Аэрохоккей: поле 1 × 1.8 (условные единицы), ворота сверху (бот) и снизу (ты). Шайба отскакивает от бортов,
 * удар битой передаёт ей скорость биты. Бот: защищает ворота (встаёт между шайбой и центром ворот),
 * предсказывает отскоки от бортов и бьёт, когда шайба на его половине; скорость биты ограничена — обыграть можно.
 */
class HockeyGame(val level: Int = 1) {
    companion object { const val W = 1f; const val H = 1.8f; const val PUCK_R = 0.045f; const val BAT_R = 0.085f; const val GOAL = 0.34f; const val WIN = 7 }
    var px = W / 2; var py = H / 2; var vx = 0f; var vy = 0f
    var mx = W / 2; var my = H * 0.85f; var mvx = 0f; var mvy = 0f        // моя бита
    var bx = W / 2; var by = H * 0.15f; var bvx = 0f; var bvy = 0f        // бита бота
    var me = 0; var bot = 0
    var pause = 0f
    private var stall = 0f
    /** Искры: x, y, vx, vy, жизнь, цвет. */
    val sparks = ArrayList<FloatArray>()
    var lastHit = 0L

    private fun reset(toMe: Boolean) { px = W / 2; py = if (toMe) H * 0.62f else H * 0.38f; vx = 0f; vy = 0f; pause = 0.8f; bx = W / 2; by = H * 0.15f }

    fun moveMine(x: Float, y: Float, dt: Float) {
        val nx = x.coerceIn(BAT_R, W - BAT_R); val ny = y.coerceIn(H / 2 + BAT_R, H - BAT_R)
        if (dt > 0) { mvx = (nx - mx) / dt; mvy = (ny - my) / dt }
        mx = nx; my = ny
    }

    private fun spark(x: Float, y: Float, color: Int) {
        repeat(14) { val a = Random.nextFloat() * 6.28f; val s = 0.2f + Random.nextFloat() * 0.6f
            sparks += floatArrayOf(x, y, kotlin.math.cos(a) * s, kotlin.math.sin(a) * s, 0.5f + Random.nextFloat() * 0.4f, color.toFloat()) }
    }

    /** Где шайба пересечёт линию y (с отскоками от бортов); null — туда не летит. */
    private fun predictX(lineY: Float): Float? {
        if (vy == 0f || (lineY - py) / vy < 0) return null
        val t = (lineY - py) / vy
        var x = px + vx * t
        val span = W - 2 * PUCK_R
        x -= PUCK_R
        val m = ((x % (2 * span)) + 2 * span) % (2 * span)
        x = if (m <= span) m else 2 * span - m
        return x + PUCK_R
    }

    private fun botThink(dt: Float) {
        val maxSp = when (level) { 0 -> 0.9f; 1 -> 1.35f; else -> 1.8f }
        var tx: Float; var ty: Float
        val homeY = H * 0.1f
        if (py < H / 2 + 0.1f && (vy < 0.5f || py < by + 0.3f)) {
            // шайба у меня (у бота): бить — подойти сзади (со стороны своих ворот) и толкнуть к чужим воротам
            // целится в угол ворот подальше от моей биты
            val aimX = W / 2 + (if (mx < W / 2) 1 else -1) * GOAL * 0.38f
            val dirX = aimX - px; val dirY = H - py; val l = hypot(dirX, dirY)
            // уже за шайбой (со стороны своих ворот и на линии удара) — бьёт «насквозь» на полной скорости; иначе — заходит за неё
            val behindX = px - dirX / l * (PUCK_R + BAT_R) * 1.1f; val behindY = py - dirY / l * (PUCK_R + BAT_R) * 1.1f
            val lined = hypot(bx - behindX, by - behindY) < (PUCK_R + BAT_R) * 0.9f || (by < py - BAT_R && abs((bx - px) * dirY / l - (by - py) * dirX / l) < BAT_R)
            if (lined) { tx = px + dirX / l * 0.2f; ty = py + dirY / l * 0.2f } else { tx = behindX; ty = behindY }
            if (py < by) { tx = px + (if (px < W / 2) 0.12f else -0.12f); ty = homeY }   // шайба за спиной — объехать
        } else {
            // защита: встать на линии между шайбой и центром ворот; если шайба летит к воротам — туда, где она пройдёт
            val pred = if (vy < 0) predictX(homeY + 0.12f) else null
            tx = pred ?: (W / 2 + (px - W / 2) * 0.45f); ty = homeY + 0.1f
        }
        tx = tx.coerceIn(BAT_R, W - BAT_R); ty = ty.coerceIn(BAT_R, H / 2 - BAT_R)
        val dx = tx - bx; val dy = ty - by; val d = hypot(dx, dy)
        val sp = if (ty > by && py < H / 2 + 0.1f) maxSp else minOf(maxSp, d / dt.coerceAtLeast(0.001f))
        bvx = if (d > 0.001f) dx / d * sp else 0f; bvy = if (d > 0.001f) dy / d * sp else 0f
        bx += bvx * dt; by += bvy * dt
    }

    private fun collide(bx: Float, by: Float, bvx: Float, bvy: Float, mine: Boolean) {
        val dx = px - bx; val dy = py - by; val d = hypot(dx, dy)
        if (d < PUCK_R + BAT_R && d > 0f) {
            val nx = dx / d; val ny = dy / d
            px = bx + nx * (PUCK_R + BAT_R); py = by + ny * (PUCK_R + BAT_R)
            val rvx = vx - bvx; val rvy = vy - bvy
            val dot = rvx * nx + rvy * ny
            if (dot < 0) { vx -= 2 * dot * nx; vy -= 2 * dot * ny }
            vx += bvx * 0.35f; vy += bvy * 0.35f
            val sp = hypot(vx, vy); val max = 4.2f; if (sp > max) { vx = vx / sp * max; vy = vy / sp * max }
            val now = System.currentTimeMillis()
            if (now - lastHit > 120) { spark(px, py, if (mine) 0xFF3B30 else 0x2ED158); lastHit = now }
        }
    }

    /** Шаг физики; возвращает 1 — гол мне в пользу, −1 — гол боту, 0 — нет. */
    fun step(dt0: Float): Int {
        sparks.forEach { it[0] += it[2] * dt0; it[1] += it[3] * dt0; it[4] -= dt0 }; sparks.removeAll { it[4] <= 0 }
        if (pause > 0) { pause -= dt0; return 0 }
        val n = 4; val dt = dt0 / n
        var goal = 0
        repeat(n) {
            botThink(dt)
            px += vx * dt; py += vy * dt
            vx *= (1 - 0.25f * dt); vy *= (1 - 0.25f * dt)
            if (px < PUCK_R) { px = PUCK_R; vx = abs(vx) * 0.92f; spark(px, py, 0x2F8BFF) }
            if (px > W - PUCK_R) { px = W - PUCK_R; vx = -abs(vx) * 0.92f; spark(px, py, 0xFFC400) }
            val inGoal = abs(px - W / 2) < GOAL / 2
            if (py < PUCK_R) { if (inGoal) { goal = 1; return@repeat } else { py = PUCK_R; vy = abs(vy) * 0.92f } }
            if (py > H - PUCK_R) { if (inGoal) { goal = -1; return@repeat } else { py = H - PUCK_R; vy = -abs(vy) * 0.92f } }
            collide(mx, my, mvx, mvy, true); collide(bx, by, bvx, bvy, false)
            // бита не выдавливает шайбу за борт: держим её на поле
            if (px < PUCK_R) { px = PUCK_R; vx = abs(vx) }
            if (px > W - PUCK_R) { px = W - PUCK_R; vx = -abs(vx) }
            if (!inGoal) { if (py < PUCK_R) { py = PUCK_R; vy = abs(vy) }; if (py > H - PUCK_R) { py = H - PUCK_R; vy = -abs(vy) } }
        }
        // шайба замерла (зажата или стоит там, куда не достать) — через 2,5 с вбрасывание в центр
        if (hypot(vx, vy) < 0.06f) stall += dt0 else stall = 0f
        if (stall > 2.5f) { stall = 0f; reset(py > H / 2) }
        if (goal == 1) { me++; spark(px, 0f, 0xFF3B30); reset(false) }
        if (goal == -1) { bot++; spark(px, H, 0x2ED158); reset(true) }
        mvx *= 0.5f; mvy *= 0.5f
        return goal
    }
}

private val NEON_RED = Color(0xFFFF3B30); private val NEON_GREEN = Color(0xFF2ED158); private val NEON_BLUE = Color(0xFF2F8BFF); private val NEON_YELLOW = Color(0xFFFFC400)

@Composable
fun AirHockeyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hockey", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var g by remember { mutableStateOf(HockeyGame(level)) }
    var started by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val over = g.me >= HockeyGame.WIN || g.bot >= HockeyGame.WIN
    GameLoop(started && !paused && !over) { dt ->
        touch?.let { t -> g.moveMine((t.x - origin.x) / scale, (t.y - origin.y) / scale, dt) }
        g.step(dt.coerceAtMost(0.033f)); tick++
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Color.White, fontSize = 26.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(12.dp, 2.dp))
                Text("Аэрохоккей", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (started && !over) Text(if (paused) "▶" else "❚❚", color = NEON_BLUE, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { paused = !paused }.padding(14.dp, 4.dp))
            }
            Canvas(Modifier.weight(1f).fillMaxWidth().padding(10.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); touch = d.position
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == d.id } ?: break; if (!ch.pressed) break; ch.consume(); touch = ch.position }
                    touch = null
                }
            }) {
                @Suppress("UNUSED_EXPRESSION") tick
                val sc = minOf(size.width / HockeyGame.W, size.height / HockeyGame.H)
                val ox = (size.width - HockeyGame.W * sc) / 2; val oy = (size.height - HockeyGame.H * sc) / 2
                scale = sc; origin = Offset(ox, oy)
                fun P(x: Float, y: Float) = Offset(ox + x * sc, oy + y * sc)
                val w = HockeyGame.W * sc; val h = HockeyGame.H * sc
                val gw = HockeyGame.GOAL * sc
                // разметка
                drawLine(Color(0xFF3A3A3A), P(0f, HockeyGame.H / 2), P(HockeyGame.W, HockeyGame.H / 2), 3f)
                drawCircle(Color(0xFF3A3A3A), w * 0.18f, P(HockeyGame.W / 2, HockeyGame.H / 2), style = Stroke(3f))
                drawArc(Color(0xFF3A3A3A), 0f, 180f, false, Offset(ox + w / 2 - w * 0.2f, oy - w * 0.2f), Size(w * 0.4f, w * 0.4f), style = Stroke(3f))
                drawArc(Color(0xFF3A3A3A), 180f, 180f, false, Offset(ox + w / 2 - w * 0.2f, oy + h - w * 0.2f), Size(w * 0.4f, w * 0.4f), style = Stroke(3f))
                // неоновые борта: 4 цвета по четвертям, просвет на воротах
                fun neon(a: Offset, b: Offset, c: Color) { drawLine(c.copy(alpha = 0.15f), a, b, 22f, StrokeCap.Round); drawLine(c.copy(alpha = 0.35f), a, b, 12f, StrokeCap.Round); drawLine(Color.White.copy(alpha = 0.9f), a, b, 3f, StrokeCap.Round); drawLine(c, a, b, 6f, StrokeCap.Round) }
                neon(Offset(ox, oy), Offset(ox, oy + h / 2), NEON_GREEN); neon(Offset(ox + w, oy), Offset(ox + w, oy + h / 2), NEON_RED)
                neon(Offset(ox, oy + h / 2), Offset(ox, oy + h), NEON_BLUE); neon(Offset(ox + w, oy + h / 2), Offset(ox + w, oy + h), NEON_YELLOW)
                neon(Offset(ox, oy), Offset(ox + w / 2 - gw / 2, oy), NEON_GREEN); neon(Offset(ox + w / 2 + gw / 2, oy), Offset(ox + w, oy), NEON_RED)
                neon(Offset(ox, oy + h), Offset(ox + w / 2 - gw / 2, oy + h), NEON_BLUE); neon(Offset(ox + w / 2 + gw / 2, oy + h), Offset(ox + w, oy + h), NEON_YELLOW)
                // счёт крупно на половинах
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = w * 0.22f; isFakeBoldText = true }
                paint.color = android.graphics.Color.argb(60, 46, 209, 88); drawContext.canvas.nativeCanvas.drawText("${g.bot}", ox + w / 2, oy + h * 0.3f, paint)
                paint.color = android.graphics.Color.argb(60, 255, 59, 48); drawContext.canvas.nativeCanvas.drawText("${g.me}", ox + w / 2, oy + h * 0.78f, paint)
                // искры
                g.sparks.forEach { sp -> val c = Color(0xFF000000 or sp[5].toLong()); drawCircle(c.copy(alpha = (sp[4] * 1.6f).coerceAtMost(1f)), 3.5f, P(sp[0], sp[1])) }
                // биты: светящееся кольцо с цветным центром
                fun bat(x: Float, y: Float, c: Color) {
                    val r = HockeyGame.BAT_R * sc; val ctr = P(x, y)
                    drawCircle(c.copy(alpha = 0.18f), r * 1.5f, ctr); drawCircle(c.copy(alpha = 0.35f), r * 1.2f, ctr)
                    drawCircle(Color.White, r, ctr); drawCircle(c, r * 0.5f, ctr)
                }
                bat(g.bx, g.by, NEON_GREEN); bat(g.mx, g.my, NEON_RED)
                // шайба
                val pr = HockeyGame.PUCK_R * sc; val pc = P(g.px, g.py)
                drawCircle(NEON_YELLOW.copy(alpha = 0.25f), pr * 1.8f, pc); drawCircle(Color.White, pr, pc)
            }
        }
        if (!started) Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Аэрохоккей", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Веди красную биту пальцем по своей половине. До ${HockeyGame.WIN} голов.", color = Color(0xFFB0B5C0))
                listOf("Средний", "Сильный", "Очень сильный").forEachIndexed { i, t ->
                    Text(t, color = if (level == i) Color.Black else Color.White, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (level == i) NEON_BLUE else Color(0x22FFFFFF)).clickable { level = i }.padding(22.dp, 10.dp))
                }
                Text("Играть", color = Color.Black, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(NEON_RED).clickable {
                    prefs.edit().putInt("level", level).apply(); g = HockeyGame(level); started = true
                }.padding(36.dp, 12.dp))
            }
        }
        if (over) GameOverlay(if (g.me > g.bot) "Победа ${g.me}:${g.bot}!" else "Бот выиграл ${g.bot}:${g.me}", "Ещё партия?", "Играть") { g = HockeyGame(level) }
    }
}

