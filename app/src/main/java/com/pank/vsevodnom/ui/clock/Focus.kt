package com.pank.vsevodnom.ui.clock

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*

// ================= Фокус и лес =================

/** Дерево: вид 0…3, живое или засохшее (если сессию бросили). */
fun DrawScope.tree(kind: Int, alive: Boolean) {
    val w = size.width; val h = size.height
    val trunk = if (alive) Color(0xFF9A6B45) else Color(0xFF8C8478)
    val leaf = if (!alive) Color(0xFFC9C1B2) else listOf(Color(0xFF7CC59A), Color(0xFF5FB287), Color(0xFF9AD3A8), Color(0xFFB4D98A))[kind % 4]
    drawRect(trunk, Offset(w * 0.45f, h * 0.55f), Size(w * 0.1f, h * 0.35f))
    when (kind % 4) {
        0 -> drawCircle(leaf, w * 0.3f, Offset(w / 2, h * 0.4f))
        1 -> drawPath(Path().apply { moveTo(w / 2, h * 0.05f); lineTo(w * 0.85f, h * 0.65f); lineTo(w * 0.15f, h * 0.65f); close() }, leaf)
        2 -> { drawCircle(leaf, w * 0.22f, Offset(w * 0.38f, h * 0.45f)); drawCircle(leaf, w * 0.22f, Offset(w * 0.62f, h * 0.45f)); drawCircle(leaf, w * 0.24f, Offset(w / 2, h * 0.28f)) }
        else -> { drawOval(leaf, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.6f)) }
    }
    drawOval(Color(0x22000000), Offset(w * 0.25f, h * 0.88f), Size(w * 0.5f, h * 0.08f))
}

private object FocusPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE)
}

@Composable
fun FocusTab() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val sessions by dao.focus().collectAsState(initial = emptyList())
    val p = remember { FocusPrefs.p(ctx) }
    var minutes by remember { mutableIntStateOf(p.getInt("len", 25)) }
    var endAt by remember { mutableLongStateOf(p.getLong("endAt", 0)) }
    var label by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endAt > 0
    LaunchedEffect(running) {
        while (endAt > 0) {
            now = System.currentTimeMillis()
            if (now >= endAt) {
                val start = endAt - p.getInt("runLen", minutes) * 60_000L
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = start, minutes = p.getInt("runLen", minutes), label = p.getString("label", "")!!, completed = true, tree = (start / 1000 % 4).toInt())) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
                if (com.pank.vsevodnom.data.NotifPrefs.on(ctx, "focus")) EventReminder.notify(ctx, "Фокус завершён", "Новое дерево в твоём лесу", 3200)
            }
            delay(500)
        }
    }
    val done = sessions.filter { it.completed }
    val todayMin = done.filter { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }.sumOf { it.minutes }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Mint) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val left = if (running) ((endAt - now) / 1000).coerceAtLeast(0) else minutes * 60L
                    val total = (if (running) p.getInt("runLen", minutes) else minutes) * 60f
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(Color.White, 0f, 360f, false, style = Stroke(16f))
                            drawArc(Pal.MintInk, -90f, 360f * (1 - left / total), false, style = Stroke(16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        // дерево растёт по мере сессии
                        Canvas(Modifier.size((60 + 80 * (1 - left / total)).dp)) { tree(1, true) }
                    }
                    Text("%02d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium)
                    if (running) Text("Не уходи — иначе дерево засохнет 🙂".replace(" 🙂", ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!running) {
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10, 15, 25, 45, 60, 90).forEach { m -> Chip("$m мин", minutes == m, { minutes = m; p.edit().putInt("len", m).apply() }) } } }
            item {
                // любое время от 5 минут до 3 часов; последнее выбранное запоминается
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Text("Своё время: $minutes мин", style = MaterialTheme.typography.labelLarge)
                    Slider(minutes.toFloat(), { minutes = (it / 5).toInt().coerceAtLeast(1) * 5 }, valueRange = 5f..180f, steps = 34,
                        onValueChangeFinished = { p.edit().putInt("len", minutes).apply() })
                }
            }
            item { AppTextField(label, { label = it }, "Над чем работаешь (необязательно)") }
            item {
                PillButton("Начать", {
                    endAt = System.currentTimeMillis() + minutes * 60_000L
                    p.edit().putLong("endAt", endAt).putInt("runLen", minutes).putInt("len", minutes).putString("label", label).apply()
                }, Modifier.fillMaxWidth())
            }
        } else item {
            PillButton("Сдаться (дерево засохнет)", {
                val len = p.getInt("runLen", minutes)
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = endAt - len * 60_000L, minutes = len - ((endAt - System.currentTimeMillis()) / 60_000).toInt(), label = label, completed = false)) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
            }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.RoseInk)
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Мой лес")
                Text("Деревьев: ${done.size} · сегодня $todayMin мин · всего ${done.sumOf { it.minutes } / 60} ч", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (sessions.isEmpty()) Text("Здесь будет расти лес из твоих фокус-сессий.", style = MaterialTheme.typography.bodySmall)
                ForestPlatform(sessions.take(64), Modifier.fillMaxWidth())
                Text("Засохшие деревья — брошенные сессии. Чем дольше фокус, тем выше дерево.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
    }
}


// ================= Лес на объёмной платформе =================

/** Объёмный (изометрический) островок: трава сверху, земля по бокам; деревья стоят в клетках, дальние — раньше ближних. */
@Composable
fun ForestPlatform(sessions: List<FocusSession>, modifier: Modifier = Modifier) {
    val n = kotlin.math.ceil(kotlin.math.sqrt(sessions.size.toDouble())).toInt().coerceIn(3, 8)
    // стабильный «случайный» порядок клеток, чтобы лес выглядел естественно
    val cells = remember(n) { (0 until n * n).shuffled(kotlin.random.Random(42)) }
    // высота под самое высокое дерево сверху + ромб + бока
    val k = 0.92f / n
    Canvas(modifier.aspectRatio(1f / (1.1f * k + 0.46f + 0.42f * k + 0.04f))) {
        val w = size.width
        val tw = w * 0.92f / n; val th = tw * 0.5f
        val cx = w / 2; val y0 = tw * 1.1f
        val depth = tw * 0.42f
        fun pt(i: Float, j: Float) = Offset(cx + (i - j) * tw / 2, y0 + (i + j) * th / 2)
        val nf = n.toFloat()
        // бока
        drawPath(Path().apply { val a = pt(0f, nf); val b = pt(nf, nf); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, b.y + depth); lineTo(a.x, a.y + depth); close() }, Color(0xFF9C7350))
        drawPath(Path().apply { val a = pt(nf, 0f); val b = pt(nf, nf); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, b.y + depth); lineTo(a.x, a.y + depth); close() }, Color(0xFF7E5A3D))
        // полоска травы по краю боков
        drawPath(Path().apply { val a = pt(0f, nf); val b = pt(nf, nf); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, b.y + depth * 0.22f); lineTo(a.x, a.y + depth * 0.22f); close() }, Color(0xFF6DAA5C))
        drawPath(Path().apply { val a = pt(nf, 0f); val b = pt(nf, nf); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, b.y + depth * 0.22f); lineTo(a.x, a.y + depth * 0.22f); close() }, Color(0xFF5C9A4E))
        // камешки в земле
        for (k in 0 until n * 2) { val f = (k + 0.5f) / (n * 2); val a = pt(f * nf, nf); drawCircle(Color(0xFF8A6446), tw * 0.04f, Offset(a.x, a.y + depth * (0.45f + 0.3f * ((k * 37) % 5) / 5f))) }
        // трава сверху — клетки двух оттенков
        for (i in 0 until n) for (j in 0 until n) {
            val c = if ((i + j) % 2 == 0) Color(0xFF9ED27E) else Color(0xFF92C973)
            drawPath(Path().apply { val a = pt(i.toFloat(), j.toFloat()); val b = pt(i + 1f, j.toFloat()); val cc = pt(i + 1f, j + 1f); val d = pt(i.toFloat(), j + 1f)
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(cc.x, cc.y); lineTo(d.x, d.y); close() }, c)
        }
        // деревья и украшения: сначала дальние клетки
        val byCell = HashMap<Int, FocusSession>(); sessions.forEachIndexed { k, s -> if (k < cells.size) byCell[cells[k]] = s }
        (0 until n * n).sortedBy { (it / n) + (it % n) }.forEach { cell ->
            val i = cell / n; val j = cell % n
            val base = pt(i + 0.5f, j + 0.5f)
            val s = byCell[cell]
            if (s == null) {
                when ((cell * 7 + 3) % 6) {
                    0 -> { drawCircle(Color(0xFFF4E28A), tw * 0.04f, base); drawCircle(Color(0xFFF7A9B8), tw * 0.035f, Offset(base.x + tw * 0.1f, base.y + th * 0.1f)) }
                    1 -> drawOval(Color(0xFFB9B4AC), Offset(base.x - tw * 0.08f, base.y - th * 0.08f), Size(tw * 0.16f, th * 0.16f))
                    else -> {}
                }
            } else {
                val kind = ((s.startedAt / 1000) % 8).toInt()
                val scale = (0.75f + (s.minutes.coerceIn(5, 120) / 120f) * 0.6f)
                isoTree(kind, s.completed, base, tw * scale)
            }
        }
    }
}

/** Дерево на платформе: 8 видов — круглое, ёлка, берёза, куст, дуб, сакура, клён, туя. */
fun DrawScope.isoTree(kind: Int, alive: Boolean, base: Offset, s: Float) {
    val dead = Color(0xFFB8AE9C); val deadTrunk = Color(0xFF8C8478)
    drawOval(Color(0x33000000), Offset(base.x - s * 0.22f, base.y - s * 0.06f), Size(s * 0.44f, s * 0.14f))
    fun trunk(wk: Float, hk: Float, c: Color = Color(0xFF8B5E3C)) = drawRect(if (alive) c else deadTrunk, Offset(base.x - s * wk / 2, base.y - s * hk), Size(s * wk, s * hk))
    fun c(x: Color) = if (alive) x else dead
    when (kind) {
        0 -> { trunk(0.08f, 0.35f); drawCircle(c(Color(0xFF6CBF7F)), s * 0.24f, Offset(base.x, base.y - s * 0.52f)); drawCircle(c(Color(0xFF8ED39A)), s * 0.1f, Offset(base.x - s * 0.08f, base.y - s * 0.6f)) }
        1 -> { trunk(0.07f, 0.2f, Color(0xFF6E4A30))
            for (k in 0..2) { val top = base.y - s * (0.95f - k * 0.2f); val half = s * (0.16f + k * 0.07f)
                drawPath(Path().apply { moveTo(base.x, top); lineTo(base.x + half, top + s * 0.3f); lineTo(base.x - half, top + s * 0.3f); close() }, c(listOf(Color(0xFF3E8E5E), Color(0xFF35804F), Color(0xFF2E7347))[k])) } }
        2 -> { drawRect(if (alive) Color(0xFFF2EFE8) else deadTrunk, Offset(base.x - s * 0.035f, base.y - s * 0.6f), Size(s * 0.07f, s * 0.6f))
            for (k in 0..3) drawRect(Color(0xFF3B3B3B), Offset(base.x - s * 0.035f, base.y - s * (0.12f + k * 0.13f)), Size(s * 0.04f, s * 0.015f))
            drawOval(c(Color(0xFFB4DB7A)), Offset(base.x - s * 0.16f, base.y - s * 0.95f), Size(s * 0.32f, s * 0.48f)) }
        3 -> { drawCircle(c(Color(0xFF5EAE6E)), s * 0.13f, Offset(base.x - s * 0.1f, base.y - s * 0.1f)); drawCircle(c(Color(0xFF6CBF7F)), s * 0.15f, Offset(base.x + s * 0.08f, base.y - s * 0.12f))
            drawCircle(c(Color(0xFF7FCB8C)), s * 0.13f, Offset(base.x, base.y - s * 0.24f)); if (alive) drawCircle(Color(0xFFE5566B), s * 0.03f, Offset(base.x + s * 0.05f, base.y - s * 0.22f)) }
        4 -> { trunk(0.12f, 0.38f, Color(0xFF7A5030))
            drawCircle(c(Color(0xFF4E9E5E)), s * 0.2f, Offset(base.x - s * 0.16f, base.y - s * 0.5f)); drawCircle(c(Color(0xFF4E9E5E)), s * 0.2f, Offset(base.x + s * 0.16f, base.y - s * 0.5f))
            drawCircle(c(Color(0xFF5FB06E)), s * 0.25f, Offset(base.x, base.y - s * 0.66f)) }
        5 -> { trunk(0.07f, 0.34f, Color(0xFF6B4432))
            drawCircle(c(Color(0xFFF4B6C8)), s * 0.22f, Offset(base.x, base.y - s * 0.55f)); drawCircle(c(Color(0xFFF8CBD8)), s * 0.14f, Offset(base.x + s * 0.13f, base.y - s * 0.46f))
            if (alive) drawCircle(Color(0xFFF8CBD8), s * 0.03f, Offset(base.x + s * 0.2f, base.y - s * 0.05f)) }
        6 -> { trunk(0.07f, 0.34f)
            drawCircle(c(Color(0xFFF0A04B)), s * 0.22f, Offset(base.x, base.y - s * 0.55f)); drawCircle(c(Color(0xFFE5793A)), s * 0.12f, Offset(base.x - s * 0.12f, base.y - s * 0.45f))
            drawCircle(c(Color(0xFFF5C16A)), s * 0.09f, Offset(base.x + s * 0.1f, base.y - s * 0.66f)) }
        else -> { trunk(0.06f, 0.14f, Color(0xFF6E4A30)); drawOval(c(Color(0xFF2F7A4D)), Offset(base.x - s * 0.11f, base.y - s * 1.0f), Size(s * 0.22f, s * 0.9f))
            drawOval(c(Color(0xFF3F8E5B)), Offset(base.x - s * 0.05f, base.y - s * 0.9f), Size(s * 0.08f, s * 0.6f)) }
    }
}
