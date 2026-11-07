package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal
import kotlin.math.*
import kotlin.random.Random

internal data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y); operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(k: Float) = V(x * k, y * k); fun dot(o: V) = x * o.x + y * o.y
    fun len() = hypot(x, y); fun norm() = len().let { if (it < 1e-6f) V(0f, 0f) else V(x / it, y / it) }
}

/** Трасса: ломаная (y вниз). Генерируется по номеру уровня: холмы, ямы, трамплины, ступеньки. */
internal class Track(level: Int) {
    val pts = ArrayList<V>()
    val finishX: Float
    init {
        val rnd = Random(level * 7919 + 13)
        var x = 0f; var y = 0f
        fun add(nx: Float, ny: Float) { pts += V(nx, ny); x = nx; y = ny }
        add(-400f, 0f); add(300f, 0f)
        val amp = 30f + level * 12f
        val parts = 14 + level * 3
        repeat(parts) {
            when (rnd.nextInt(if (level < 2) 3 else 6)) {
                // крутизна любого склона ≤ ~0,6 (около 30°): длина холма растёт вместе с высотой
                0 -> { val a = amp * (0.5f + rnd.nextFloat()); val len = maxOf(300f + rnd.nextFloat() * 300, a * PI.toFloat() / 0.6f); val x0 = x; val y0 = y
                    var t = 0f; while (t <= 1f) { add(x0 + len * t, y0 - sin(t * PI.toFloat()) * a); t += 0.05f } }
                1 -> { val a = amp * (0.4f + rnd.nextFloat() * 0.8f); val len = maxOf(300f + rnd.nextFloat() * 250, a * PI.toFloat() / 0.6f); val x0 = x; val y0 = y
                    var t = 0f; while (t <= 1f) { add(x0 + len * t, y0 + sin(t * PI.toFloat()) * a); t += 0.05f } }
                2 -> { add(x + 200f + rnd.nextFloat() * 200, y + (rnd.nextFloat() - 0.5f) * amp) }
                3 -> { // трамплин, затем ложбина: перепрыгни — или съедешь вниз и выберешься по склону
                    val x0 = x; val y0 = y
                    val h = 26f + level * 4f + rnd.nextFloat() * 20
                    val depth = 50f + level * 8f
                    val gap = 60f + level * 10f + rnd.nextFloat() * 40
                    val dive = (h + depth) * 1.3f
                    add(x0 + 170f, y0 - h); add(x0 + 170f + dive, y0 + depth); add(x0 + 170f + dive + gap, y0 + depth)
                    add(x0 + 170f + dive + gap + depth * 2.4f, y0 - 4f); add(x + 80f, y0)
                }
                4 -> { // ступени — пологие склоны вверх или вниз
                    val dir = if (rnd.nextBoolean()) -1 else 1
                    val hh = 14f + level * 2f
                    repeat(3) { add(x + 60f, y); add(x + hh * 1.6f, y + dir * hh) }
                    add(x + 80f, y)
                }
                else -> { // кочки: вверх-вниз к той же высоте
                    val y0 = y; val hh = 6f + level * 0.8f; val x0 = x
                    var t = 0f; while (t <= 6f) { add(x0 + t * 70f, y0 - hh * (1 - cos(t * 2 * PI.toFloat())) / 2); t += 0.125f }
                    add(x + 40f, y0)
                }
            }
        }
        add(x + 300f, y); finishX = x; add(x + 700f, y)
        // срезаем острые углы (Чайкин): колесо не застревает в изломах, трасса выглядит плавно
        repeat(2) {
            val src = pts.filterIndexed { i, p -> i == 0 || (p - pts[i - 1]).len() > 0.5f }
            pts.clear(); pts += src.first()
            for (i in 0 until src.size - 1) { val a = src[i]; val b = src[i + 1]; pts += a * 0.75f + b * 0.25f; pts += a * 0.25f + b * 0.75f }
            pts += src.last()
        }
    }

    /** Ближайшая точка трассы к p (смотрим только отрезки рядом по x). */
    fun closest(p: V, r: Float): Pair<V, V>? {
        var best: Pair<V, V>? = null; var bd = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (max(a.x, b.x) < p.x - r - 5 || min(a.x, b.x) > p.x + r + 5) continue
            val ab = b - a; val ll = ab.dot(ab); if (ll < 1e-3f) continue
            val t = ((p - a).dot(ab) / ll).coerceIn(0f, 1f)
            val c = a + ab * t; val d = (p - c).len()
            if (d < bd) { bd = d; best = c to ab.norm() }
        }
        return best
    }
}

internal class Part(var p: V, val r: Float, val m: Float) { var v = V(0f, 0f); var prev = p; var contact: V? = null; var angle = 0f }

internal class MotoGame(val level: Int) {
    val track = Track(level)
    val rear = Part(V(0f, -30f), 15f, 1f)
    val front = Part(V(56f, -30f), 15f, 1f)
    val head = Part(V(24f, -78f), 9f, 1.4f)
    private val parts = listOf(rear, front, head)
    private val rests = listOf(Triple(0, 1, 56f), Triple(0, 2, (head.p - rear.p).len()), Triple(1, 2, (head.p - front.p).len()))
    var gas = false; var brake = false; var leanBack = false; var leanFwd = false
    var crashed = false; var finished = false; var time = 0f; var started = false
    val center get() = (rear.p + front.p) * 0.5f

    fun step(dt0: Float) {
        if (!started || crashed || finished) return
        time += dt0
        val sub = 4; val dt = dt0 / sub
        repeat(sub) { sub(dt) }
        if (center.x >= track.finishX) finished = true
        if (center.y > 2000) crashed = true
    }

    private fun sub(dt: Float) {
        val g = 900f
        // наклон: вращаем раму вокруг центра
        val c = (rear.p + front.p + head.p) * (1f / 3f)
        val torque = (if (leanBack) -1f else 0f) + (if (leanFwd) 1f else 0f)
        val air = rear.contact == null && front.contact == null
        if (torque != 0f) parts.forEach { pt -> val r = pt.p - c; pt.v = pt.v + V(-r.y, r.x) * (torque * (if (air) 7f else 4f) * dt) }
        // газ: заднее колесо толкает вдоль земли
        rear.contact?.let { n ->
            val t = V(-n.y, n.x).let { if (it.x < 0) it * -1f else it }
            if (gas && rear.v.dot(t) < 600f) rear.v = rear.v + t * (1300f * dt)
        }
        if (brake) parts.forEach { if (it.contact != null) it.v = it.v * (1f - 3.5f * dt) }
        parts.forEach { pt -> pt.v = pt.v + V(0f, g * dt); pt.prev = pt.p; pt.p = pt.p + pt.v * dt; pt.contact = null }
        repeat(6) {
            for ((a, b, rest) in rests) {
                val pa = parts[a]; val pb = parts[b]
                val d = pb.p - pa.p; val l = d.len(); if (l < 1e-4f) continue
                val diff = (l - rest) / l / (1f / pa.m + 1f / pb.m)
                pa.p = pa.p + d * (diff / pa.m); pb.p = pb.p - d * (diff / pb.m)
            }
            for (w in listOf(rear, front)) {
                val (cp, _) = track.closest(w.p, w.r) ?: continue
                val d = w.p - cp; val l = d.len()
                if (l < w.r) { val n = if (l < 1e-4f) V(0f, -1f) else d * (1f / l); w.p = cp + n * w.r; w.contact = n }
            }
        }
        parts.forEach { pt -> pt.v = (pt.p - pt.prev) * (1f / dt) }
        // сцепление колёс: гасим скорость в землю и немного скольжение
        for (w in listOf(rear, front)) {
            val n = w.contact ?: continue
            val vn = w.v.dot(n)
            if (vn < 0) w.v = w.v - n * (vn * 1.15f)
            val t = V(-n.y, n.x); val vt = w.v.dot(t)
            w.angle += vt * dt / w.r
            w.v = w.v - t * (vt * 0.0006f)
        }
        // голова о землю — авария
        track.closest(head.p, head.r)?.let { (cp, _) -> if ((head.p - cp).len() < head.r) crashed = true }
    }
}

private object MotoPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("moto", Context.MODE_PRIVATE)
    fun unlocked(ctx: Context) = p(ctx).getInt("unlocked", 1)
    fun best(ctx: Context, l: Int) = p(ctx).getFloat("best$l", 0f)
    fun finish(ctx: Context, l: Int, t: Float) {
        val e = p(ctx).edit()
        if (best(ctx, l) == 0f || t < best(ctx, l)) e.putFloat("best$l", t)
        if (l + 1 > unlocked(ctx)) e.putInt("unlocked", l + 1)
        e.apply()
    }
}

private const val LEVELS_N = 8

@Composable
fun MotoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf<Int?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    val lv = level
    if (lv == null) {
        ScreenScaffold("Мотокросс", onBack) {
            Text("Газ, тормоз и наклон. Не урони гонщика головой о землю. Пройди уровень — откроется следующий.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            val unl = remember(ver) { MotoPrefs.unlocked(ctx) }
            LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items((1..LEVELS_N).toList()) { l ->
                    val open = l <= unl
                    val b = MotoPrefs.best(ctx, l)
                    Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(if (open) Pal.Mint else Pal.Line).clickable(enabled = open) { level = l },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (open) Text("$l", style = MaterialTheme.typography.headlineMedium, color = Pal.MintInk) else Icon(Icons.Rounded.Lock, null, tint = Pal.Muted)
                        if (b > 0) Text("%.1f с".format(b).replace('.', ','), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        return
    }
    var g by remember(lv, ver) { mutableStateOf(MotoGame(lv)) }
    var tick by remember { mutableIntStateOf(0) }
    var saved by remember(lv, ver) { mutableStateOf(false) }
    GameLoop(g.started && !g.crashed && !g.finished) { dt -> g.step(dt); tick++; if (g.finished && !saved) { saved = true; MotoPrefs.finish(ctx, lv, g.time) } }
    ScreenScaffold("Уровень $lv", { level = null; ver++ }) {
        Text("%.1f с".format(g.time).replace('.', ',') + (MotoPrefs.best(ctx, lv).takeIf { it > 0 }?.let { " · лучшее %.1f".format(it).replace('.', ',') } ?: ""),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                tick
                val sc = size.width / 620f
                val cam = g.center
                fun s(p: V) = Offset((p.x - cam.x) * sc + size.width * 0.35f, (p.y - cam.y) * sc + size.height * 0.55f)
                drawRect(Brush.verticalGradient(listOf(Color(0xFFDCEBFA), Color(0xFFFCE5D6))))
                // дальние горы с параллаксом
                for (k in -1..6) { val mx = k * 260f - (cam.x * 0.2f) % 260f; drawCircle(Color(0xFFE7E1F7), 160f, Offset(mx * sc, size.height * 0.75f)) }
                // земля
                val path = Path()
                val vis = g.track.pts.filter { s(it).x in -200f..size.width + 200f }
                if (vis.isNotEmpty()) {
                    path.moveTo(s(vis.first()).x, size.height + 10); vis.forEach { val q = s(it); path.lineTo(q.x, q.y) }; path.lineTo(s(vis.last()).x, size.height + 10); path.close()
                    drawPath(path, Brush.verticalGradient(listOf(Color(0xFFBFE3C8), Color(0xFF8FC9A0))))
                    val line = Path(); line.moveTo(s(vis.first()).x, s(vis.first()).y); vis.drop(1).forEach { val q = s(it); line.lineTo(q.x, q.y) }
                    drawPath(line, Color(0xFF4E9B6A), style = Stroke(4f * sc, cap = StrokeCap.Round))
                }
                // финиш: клетчатый флаг
                val fb = g.track.closest(V(g.track.finishX, 0f), 2000f)?.first ?: V(g.track.finishX, 0f)
                val f = s(V(g.track.finishX, fb.y))
                drawLine(Pal.Ink, f, Offset(f.x, f.y - 90 * sc), 3f * sc)
                for (i in 0 until 4) for (j in 0 until 3) drawRect(if ((i + j) % 2 == 0) Pal.Ink else Color.White, Offset(f.x + i * 9 * sc, f.y - 90 * sc + j * 9 * sc), androidx.compose.ui.geometry.Size(9 * sc, 9 * sc))
                // велосипед
                val r = s(g.rear.p); val fr = s(g.front.p); val h = s(g.head.p)
                listOf(g.rear to r, g.front to fr).forEach { (w, c) ->
                    drawCircle(Color(0xFF2B2A33), w.r * sc, c, style = Stroke(4f * sc))
                    for (k in 0 until 4) { val a = w.angle + k * PI.toFloat() / 4; drawLine(Color(0xFF9AA0AA), Offset(c.x - cos(a) * w.r * sc, c.y - sin(a) * w.r * sc), Offset(c.x + cos(a) * w.r * sc, c.y + sin(a) * w.r * sc), 1.2f * sc) }
                }
                val mid = Offset((r.x + fr.x) / 2, (r.y + fr.y) / 2)
                val seat = Offset(mid.x * 0.45f + h.x * 0.55f, mid.y * 0.45f + h.y * 0.55f)
                drawLine(Color(0xFFE5485D), r, seat, 4f * sc); drawLine(Color(0xFFE5485D), r, mid, 4f * sc); drawLine(Color(0xFFE5485D), mid, fr, 4f * sc)
                drawLine(Color(0xFFE5485D), mid, Offset(fr.x * 0.7f + h.x * 0.3f, fr.y * 0.7f + h.y * 0.3f), 4f * sc)
                // гонщик
                drawLine(Color(0xFF4A86E8), seat, h, 6f * sc, StrokeCap.Round)
                drawLine(Color(0xFF4A86E8), Offset((seat.x + h.x) / 2, (seat.y + h.y) / 2), Offset(fr.x * 0.7f + h.x * 0.3f, fr.y * 0.7f + h.y * 0.3f), 3f * sc, StrokeCap.Round)
                drawLine(Color(0xFF2B2A33), seat, mid, 3f * sc, StrokeCap.Round)
                drawCircle(Color(0xFFF2C94C), g.head.r * sc, h)
                drawCircle(Color(0xFF2B2A33), g.head.r * 0.35f * sc, Offset(h.x + (fr.x - r.x).sign * g.head.r * 0.4f * sc, h.y))
                // прогресс
                val prog = ((g.center.x) / g.track.finishX).coerceIn(0f, 1f)
                drawRect(Color(0x22000000), Offset(16f, 8f), androidx.compose.ui.geometry.Size(size.width - 32f, 8f))
                drawRect(Color(0xFF4E9B6A), Offset(16f, 8f), androidx.compose.ui.geometry.Size((size.width - 32f) * prog, 8f))
            }
            // управление: слева наклон, справа газ/тормоз (держать)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldButton(Icons.Rounded.RotateLeft, "Назад") { g.leanBack = it; if (it) g.started = true }
                    HoldButton(Icons.Rounded.RotateRight, "Вперёд") { g.leanFwd = it; if (it) g.started = true }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldButton(Icons.Rounded.PanTool, "Тормоз", Color(0xFFF8DDE6)) { g.brake = it; if (it) g.started = true }
                    HoldButton(Icons.Rounded.KeyboardDoubleArrowRight, "Газ", Color(0xFFDDF2E6), big = true) { g.gas = it; if (it) g.started = true }
                }
            }
            if (!g.started) GameOverlay("Уровень $lv", "Держи «Газ». В воздухе наклоняй байк, чтобы приземлиться на оба колеса.", "Поехали") { g.started = true }
            if (g.crashed) GameOverlay("Авария!", "Проехал ${(100 * (g.center.x / g.track.finishX).coerceIn(0f, 1f)).toInt()}% трассы", "Заново") { ver++ }
            if (g.finished) GameOverlay("Финиш!", "Время %.1f с".format(g.time).replace('.', ','), if (lv < LEVELS_N) "Следующий уровень" else "К уровням") {
                if (lv < LEVELS_N) { level = lv + 1; ver++ } else { level = null; ver++ }
            }
        }
    }
}

/** Кнопка, которая работает, пока её держат (можно жать несколько сразу). */
@Composable
private fun HoldButton(icon: ImageVector, label: String, bg: Color = Color.White, big: Boolean = false, onHold: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (big) 84.dp else 68.dp).clip(CircleShape).background(if (down) bg.copy(alpha = 1f) else bg.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false); down = true; onHold(true)
                    while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break }
                    down = false; onHold(false)
                }
            }, contentAlignment = Alignment.Center) { Icon(icon, label, tint = Pal.Ink, modifier = Modifier.size(if (big) 40.dp else 30.dp)) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
