package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.random.Sfx
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Цвета шаров восьмёрки (1–8, полосатые 9–15 — те же цвета). */
private val BALL_COLORS = listOf(
    Color(0xFFF2C230), Color(0xFF2453B8), Color(0xFFD23A2E), Color(0xFF5A3B9E), Color(0xFFF07A1E),
    Color(0xFF1E8A4C), Color(0xFF8A2A2A), Color(0xFF151515))
private val IVORY = Color(0xFFF5EEDC)
private val PYRAMID_CUE = Color(0xFFD9452F)

private fun ballColor(mode: PoolMode, n: Int): Color = when {
    n == 0 -> if (mode == PoolMode.PYRAMID) PYRAMID_CUE else Color(0xFFF7F5EE)
    mode == PoolMode.PYRAMID -> IVORY
    n <= 8 -> BALL_COLORS[n - 1]
    else -> BALL_COLORS[n - 9]
}

@Composable
fun BilliardsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("billiards", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(runCatching { PoolMode.valueOf(prefs.getString("mode", "EIGHT")!!) }.getOrDefault(PoolMode.EIGHT)) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var hint by remember { mutableIntStateOf(prefs.getInt("hint", 0)) }        // 0 полная, 1 короткая, 2 нет
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var gen by remember { mutableIntStateOf(0) }
    val g = remember(gen, mode) { PoolGame(mode).also { randomizeRot(it) } }
    var tick by remember { mutableIntStateOf(0) }
    var angle by remember(gen, mode) { mutableDoubleStateOf(-PI / 2) }
    var power by remember { mutableFloatStateOf(0f) }
    var sx by remember { mutableFloatStateOf(0f) }; var sy by remember { mutableFloatStateOf(0f) }
    var spinOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var botThinking by remember { mutableStateOf(false) }
    val stats = remember { intArrayOf(prefs.getInt("w", 0), prefs.getInt("l", 0)) }
    var counted by remember(gen, mode) { mutableStateOf(false) }

    fun play(ev: List<PoolEvent>) { if (sound) BilliardSfx.events(ev) }
    GameLoop(g.shooting) { dt ->
        play(g.advance(dt.toDouble()))
        tick++
    }
    // конец партии — счёт побед
    LaunchedEffect(g.winner, tick) {
        if (g.winner >= 0 && !counted) { counted = true; if (g.winner == 0) stats[0]++ else stats[1]++; prefs.edit().putInt("w", stats[0]).putInt("l", stats[1]).apply() }
    }
    // ход бота: подумать в фоне на копии, поставить биток (если с руки), довести кий, ударить
    LaunchedEffect(g, g.turn, g.shooting, g.winner, tick / 1000) {
        if (g.turn != 1 || g.shooting || g.winner >= 0 || botThinking) return@LaunchedEffect
        botThinking = true
        delay(700)
        val snap = g.snapshot()
        val shot = withContext(Dispatchers.Default) { runCatching { PoolBot.move(snap, level) }.getOrNull() }
        if (shot == null) { botThinking = false; return@LaunchedEffect }
        if (g.inHand != InHand.NONE) { g.cue.x = snap.cue.x; g.cue.y = snap.cue.y; tick++ }
        // плавно повернуть кий к выбранному углу
        val a0 = angle; var d = shot.angle - a0; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
        for (i in 1..24) { angle = a0 + d * i / 24; delay(22) }
        for (i in 1..14) { power = (shot.power * i / 14).toFloat(); delay(22) }
        delay(150)
        if (sound) BilliardSfx.cue(shot.power)
        g.shoot(shot.angle, shot.power, shot.sx, shot.sy); power = 0f
        botThinking = false; tick++
    }

    val myTurn = g.turn == 0 && !g.shooting && g.winner < 0
    Box(Modifier.fillMaxSize().background(Color(0xFF14201A))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // верх: назад, игроки, настройки
            key(tick) {
                Row(Modifier.fillMaxWidth().padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = Color.White, fontSize = 24.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(10.dp, 2.dp))
                    PlayerBox(g, 0, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    PlayerBox(g, 1, Modifier.weight(1f))
                    Text("⚙", color = Color.White, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { settings = true }.padding(10.dp, 2.dp))
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
                // слева — тонкая настройка прицела: вести пальцем вверх/вниз
                FineStrip(Modifier.width(30.dp).fillMaxHeight(), enabled = myTurn) { d -> angle += d * 0.0009; tick++ }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TableCanvas(g, angle, power, hint, tick, myTurn, botThinking,
                        onAim = { a -> angle = a; tick++ }, onPlace = { x, y -> if (g.canPlace(x, y)) { g.cue.x = x; g.cue.y = y; tick++ } })
                }
                // справа — сила: тянуть вниз и отпустить
                PowerStrip(Modifier.width(40.dp).fillMaxHeight(), power, enabled = myTurn, onPower = { power = it }) { p ->
                    if (p > 0.02f && myTurn) {
                        if (sound) BilliardSfx.cue(p.toDouble())
                        g.shoot(angle, p.toDouble(), sx.toDouble(), sy.toDouble()); tick++
                    }
                    power = 0f
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // винт: маленький биток с красной точкой
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { spinOpen = true }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(44.dp)) { drawCircle(Color(0xFFD23A2E), size.minDimension * 0.08f, Offset(size.width / 2 + sx * size.width * 0.3f, size.height / 2 - sy * size.height * 0.3f)) }
                }
                Spacer(Modifier.width(10.dp))
                key(tick) {
                    Text(when {
                        g.winner >= 0 -> g.message
                        g.shooting -> "…"
                        g.turn == 1 -> if (botThinking) "Бот целится…" else "Ход бота"
                        g.inHand != InHand.NONE -> (g.message.ifBlank { "Шар с руки" }) + " · перетащи биток" + if (g.inHand == InHand.KITCHEN) " (в «доме», ниже линии)" else ""
                        else -> g.message.ifBlank { "Твой удар: прицелься и потяни силу справа вниз" }
                    }, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
        if (g.winner == 0) VictoryOverlay("Победа!", g.message + " · побед ${stats[0]}, поражений ${stats[1]}", "Ещё партия") { gen++ }
        else if (g.winner == 1) GameOverlay("Бот выиграл", g.message + " · побед ${stats[0]}, поражений ${stats[1]}", "Ещё партия") { gen++ }
    }
    if (spinOpen) SpinDialog(sx, sy, { a, b -> sx = a; sy = b }) { spinOpen = false }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Бильярд") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Игра", style = MaterialTheme.typography.titleSmall)
            PoolMode.entries.forEach { m -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { mode = m; prefs.edit().putString("mode", m.name).apply() }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == m, { mode = m; prefs.edit().putString("mode", m.name).apply() }); Text(m.title) } }
            Text(if (mode == PoolMode.EIGHT) "Стол 9 футов, 15 шаров: забей свою группу (сплошные или полосатые), потом восьмёрку. Фол — шар с руки сопернику."
                 else "Стол 12 футов, узкие лузы. Бьёшь только красным битком; забитый шар — очко, «свояк» (биток в лузе после удара по шару) — тоже. До 8 шаров. Фол — штраф: шар возвращается.",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Text("Бот", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PoolBot.LEVELS.forEachIndexed { i, t -> Chip(t, level == i, { level = i; prefs.edit().putInt("level", i).apply() }, Modifier.weight(1f)) } }
            Text("Подсказка траектории", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Полная", "Короткая", "Нет").forEachIndexed { i, t -> Chip(t, hint == i, { hint = i; prefs.edit().putInt("hint", i).apply() }, Modifier.weight(1f)) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Звук", modifier = Modifier.weight(1f)); Switch(sound, { sound = it; prefs.edit().putBoolean("sound", it).apply() }) }
            Text("Побед ${stats[0]} · поражений ${stats[1]}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }, confirmButton = { TextButton({ settings = false; gen++ }) { Text("Новая партия") } }, dismissButton = { TextButton({ settings = false }) { Text("Готово") } })
}

/** Случайная начальная ориентация шаров — чтобы номера смотрели в разные стороны. */
private fun randomizeRot(g: PoolGame) {
    val r = kotlin.random.Random
    g.balls.forEach { b ->
        val ax = r.nextDouble(-1.0, 1.0); val ay = r.nextDouble(-1.0, 1.0); val az = r.nextDouble(-1.0, 1.0); val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-3)
        val kx = ax / l; val ky = ay / l; val kz = az / l; val a = r.nextDouble(0.0, 2 * PI); val c = cos(a); val s = sin(a); val v = 1 - c
        val m = doubleArrayOf(c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s, ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s, kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v)
        m.copyInto(b.rot)
    }
}

@Composable
private fun PlayerBox(g: PoolGame, p: Int, modifier: Modifier) {
    val active = g.turn == p && g.winner < 0
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(if (active) Color(0x33F2C230) else Color(0x14FFFFFF)).padding(6.dp, 4.dp)) {
        Text(g.who(p) + if (active) " ●" else "", color = if (active) Color(0xFFF2C230) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (g.mode == PoolMode.PYRAMID) Text("${g.score[p]} из 8", color = Color.White, fontSize = 12.sp)
        else {
            val grp = g.group[p]
            if (grp == 0) Text("стол открыт", color = Color(0xFF9FB3A8), fontSize = 11.sp)
            else Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val nums = if (grp == 1) (1..7) else (9..15)
                nums.forEach { n -> val on = g.balls.firstOrNull { it.num == n }?.on == true
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) ballColor(g.mode, n) else Color(0x33FFFFFF))) }
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Black))
            }
        }
    }
}

/** Полоса тонкой наводки: вертикальные «рифли», движение пальца меняет угол на доли градуса. */
@Composable
private fun FineStrip(modifier: Modifier, enabled: Boolean, onDelta: (Float) -> Unit) {
    var off by remember { mutableFloatStateOf(0f) }
    Canvas(modifier.padding(vertical = 30.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A1A10)).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val d = awaitFirstDown(); var last = d.position.y
            while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break
                val dy = c.position.y - last; last = c.position.y; off += dy; onDelta(dy); c.consume() }
        }
    }) {
        val step = 10.dp.toPx(); var y = (off % step + step) % step
        while (y < size.height) { drawLine(Color(0xFF6B4A2E), Offset(4f, y), Offset(size.width - 4f, y), 2f); y += step }
    }
}

/** Сила удара: потянуть вниз — кий отходит назад; отпустить — удар. */
@Composable
private fun PowerStrip(modifier: Modifier, power: Float, enabled: Boolean, onPower: (Float) -> Unit, onRelease: (Float) -> Unit) {
    val cbP by rememberUpdatedState(onPower); val cbR by rememberUpdatedState(onRelease)
    Canvas(modifier.padding(vertical = 30.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1A10)).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val d = awaitFirstDown(); var p = 0f
            while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break
                if (!c.pressed) break
                p = ((c.position.y - d.position.y) / (size.height * 0.85f)).coerceIn(0f, 1f); cbP(p); c.consume() }
            cbR(p)
        }
    }) {
        val h = size.height * power
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3FB27F), Color(0xFFF2C230), Color(0xFFE5484D))), Offset(4f, 4f), Size(size.width - 8f, h.coerceAtLeast(0f)))
        for (i in 1..9) drawLine(Color(0x33FFFFFF), Offset(size.width * 0.3f, size.height * i / 10f), Offset(size.width * 0.7f, size.height * i / 10f), 2f)
        drawContext.canvas.nativeCanvas.drawText("${(power * 100).toInt()}", size.width / 2, size.height - 12f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true })
    }
}

@Composable
private fun SpinDialog(sx0: Float, sy0: Float, onSet: (Float, Float) -> Unit, onDismiss: () -> Unit) {
    var sx by remember { mutableFloatStateOf(sx0) }; var sy by remember { mutableFloatStateOf(sy0) }
    AlertDialog(onDismissRequest = { onSet(sx, sy); onDismiss() }, title = { Text("Винт") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(220.dp).pointerInput(Unit) {
                awaitEachGesture {
                    fun set(o: Offset) { val r = size.width / 2f; var x = (o.x - r) / (r * 0.8f); var y = -(o.y - r) / (r * 0.8f); val l = hypot(x, y); if (l > 1) { x /= l; y /= l }; sx = x; sy = y }
                    val d = awaitFirstDown(); set(d.position)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; set(c.position); c.consume() }
                }
            }) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFDCD8CC)), Offset(r * 0.8f, r * 0.7f), r * 1.4f), r)
                drawCircle(Color(0x22000000), r * 0.8f, style = Stroke(2f))
                drawLine(Color(0x22000000), Offset(r, r * 0.2f), Offset(r, r * 1.8f)); drawLine(Color(0x22000000), Offset(r * 0.2f, r), Offset(r * 1.8f, r))
                drawCircle(Color(0xFFD23A2E), r * 0.09f, Offset(r + sx * r * 0.8f, r - sy * r * 0.8f))
            }
            Text(when { sy > 0.25f -> "Накат: после удара биток пойдёт вперёд"; sy < -0.25f -> "Оттяжка: биток откатится назад"; else -> "По центру: биток остановится или уйдёт по касательной" } +
                if (abs(sx) > 0.25f) (if (sx > 0) ", винт вправо — отскок от борта правее" else ", винт влево — отскок от борта левее") else "", style = MaterialTheme.typography.bodySmall)
            TextButton({ sx = 0f; sy = 0f }) { Text("По центру") }
        }
    }, confirmButton = { TextButton({ onSet(sx, sy); onDismiss() }) { Text("Готово") } })
}

/** Стол: рама, борта, сукно, лузы, шары, кий, траектория; касание — прицел (или перенос битка с руки). */
@Composable
private fun TableCanvas(g: PoolGame, angle: Double, power: Float, hint: Int, tick: Int, myTurn: Boolean, botAiming: Boolean,
                        onAim: (Double) -> Unit, onPlace: (Double, Double) -> Unit) {
    val t = g.table
    val border = t.W * 0.1          // рама с бортами (в метрах)
    var geo by remember { mutableStateOf(floatArrayOf(1f, 0f, 0f)) }   // масштаб, сдвиг x, сдвиг y
    val cbAim by rememberUpdatedState(onAim); val cbPlace by rememberUpdatedState(onPlace)
    Canvas(Modifier.fillMaxSize().pointerInput(g, myTurn) {
        if (!myTurn) return@pointerInput
        awaitEachGesture {
            val d = awaitFirstDown()
            val (k, ox, oy) = geo.let { Triple(it[0], it[1], it[2]) }
            fun world(o: Offset) = ((o.x - ox) / k).toDouble() to ((o.y - oy) / k).toDouble()
            val (wx, wy) = world(d.position)
            val dragCue = g.inHand != InHand.NONE && hypot(wx - g.cue.x, wy - g.cue.y) < t.R * 4
            fun handle(o: Offset) { val (x, y) = world(o); if (dragCue) cbPlace(x, y) else if (hypot(x - g.cue.x, y - g.cue.y) > t.R * 1.5) cbAim(atan2(y - g.cue.y, x - g.cue.x)) }
            handle(d.position)
            while (true) { val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break; if (!c.pressed) break; handle(c.position); c.consume() }
        }
    }) {
        @Suppress("UNUSED_EXPRESSION") tick
        val k = minOf(size.width / (t.W + 2 * border), size.height / (t.H + 2 * border)).toFloat()
        val ox = (size.width - t.W.toFloat() * k) / 2; val oy = (size.height - t.H.toFloat() * k) / 2
        if (geo[0] != k || geo[1] != ox || geo[2] != oy) geo = floatArrayOf(k, ox, oy)
        fun P(x: Double, y: Double) = Offset(ox + x.toFloat() * k, oy + y.toFloat() * k)
        val R = t.R.toFloat() * k
        val bw = border.toFloat() * k
        // рама — дерево
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF5A3418), Color(0xFF8A5528), Color(0xFF4A2A12)), Offset(ox - bw, oy - bw), Offset(ox + t.W.toFloat() * k + bw, oy + t.H.toFloat() * k + bw)),
            Offset(ox - bw, oy - bw), Size(t.W.toFloat() * k + 2 * bw, t.H.toFloat() * k + 2 * bw), androidx.compose.ui.geometry.CornerRadius(bw * 0.6f))
        // бортовая резина
        val cw = bw * 0.45f
        drawRect(Color(0xFF145A36), Offset(ox - cw, oy - cw), Size(t.W.toFloat() * k + 2 * cw, t.H.toFloat() * k + 2 * cw))
        // сукно
        drawRect(Brush.radialGradient(listOf(Color(0xFF2E9A5A), Color(0xFF1F7A45), Color(0xFF17633A)), P(t.W / 2, t.H / 2), (t.H * 0.7).toFloat() * k), P(0.0, 0.0), Size(t.W.toFloat() * k, t.H.toFloat() * k))
        // метки на раме (по 3 на каждой половине длинного борта и на коротких)
        for (i in 1..3) { val x = (t.W * i / 4).toFloat(); for (y in listOf(-border * 0.72, t.H + border * 0.72)) drawCircle(Color(0xFFE9DDBB), bw * 0.07f, P(x.toDouble(), y)) }
        for (i in 1..7) if (i != 4) { val y = t.H * i / 8; for (x in listOf(-border * 0.72, t.W + border * 0.72)) drawCircle(Color(0xFFE9DDBB), bw * 0.07f, P(x, y)) }
        // линия «дома» и точка пирамиды
        drawLine(Color(0x33FFFFFF), P(0.0, t.headY), P(t.W, t.headY), 2f)
        drawCircle(Color(0x55FFFFFF), R * 0.18f, P(t.footX, t.footY))
        // лузы
        t.pockets.forEachIndexed { i, (px, py) ->
            val side = py == t.H / 2
            val r = (if (side) t.sideMouth * 0.6 else t.cornerMouth * 0.75).toFloat() * k
            val c = P(px + (if (px == 0.0) -1 else 1) * (if (side) t.R * 0.8 else t.R * 0.3), py + if (side) 0.0 else (if (py == 0.0) -1 else 1) * t.R * 0.3)
            drawCircle(Color(0xFF1A1A1A), r * 1.18f, c)
            drawCircle(Brush.radialGradient(listOf(Color.Black, Color(0xFF0A0A0A), Color(0xFF2B2B2B)), c, r), r, c)
        }
        // губки — тёмные отрезки резины
        t.rails.forEach { s -> drawLine(Color(0xFF0F4A2C), P(s.ax, s.ay), P(s.bx, s.by), 3f) }

        // траектория
        val showGuide = (myTurn || botAiming) && g.cue.on && !g.shooting && g.winner < 0
        if (showGuide && hint < 2) {
            val gd = PoolAim.guide(g, angle)
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            drawLine(Color(0xCCFFFFFF), P(g.cue.x, g.cue.y), P(gd.ex, gd.ey), 2.5f, pathEffect = dash)
            drawCircle(Color(0xCCFFFFFF), R, P(gd.ex, gd.ey), style = Stroke(2f))
            val len = if (hint == 0) 0.6 else 0.14
            gd.hit?.let { h ->
                drawLine(Color(0xFFFFE08A), P(h.x, h.y), P(h.x + gd.objDx * len, h.y + gd.objDy * len), 3f, cap = StrokeCap.Round)
                if (hint == 0) drawLine(Color(0x99FFFFFF), P(gd.ex, gd.ey), P(gd.ex + gd.cueDx * len * 0.5, gd.ey + gd.cueDy * len * 0.5), 2f, pathEffect = dash)
            } ?: if (hint == 0) {
                val dx = cos(angle) * (if (gd.railNx != 0.0) -1 else 1); val dy = sin(angle) * (if (gd.railNy != 0.0) -1 else 1)
                drawLine(Color(0x88FFFFFF), P(gd.ex, gd.ey), P(gd.ex + dx * 0.4, gd.ey + dy * 0.4), 2f, pathEffect = dash)
            } else Unit
        } else if (showGuide) {
            drawLine(Color(0x66FFFFFF), P(g.cue.x, g.cue.y), P(g.cue.x + cos(angle) * 0.25, g.cue.y + sin(angle) * 0.25), 2f)
        }
        // шары: тени, потом сами шары
        g.balls.filter { it.on }.forEach { b -> drawCircle(Color(0x55000000), R * 1.02f, P(b.x, b.y) + Offset(R * 0.22f, R * 0.3f)) }
        g.balls.filter { it.on }.forEach { b -> drawBall(g.mode, b, P(b.x, b.y), R) }
        if (g.inHand != InHand.NONE && myTurn) drawCircle(Color(0xAAFFFFFF), R * 1.6f, P(g.cue.x, g.cue.y), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        // кий
        if (showGuide) {
            val back = (t.R * 1.4 + 0.015 + power * 0.28)
            val dx = cos(angle); val dy = sin(angle)
            val tip = P(g.cue.x - dx * back, g.cue.y - dy * back)
            val butt = P(g.cue.x - dx * (back + 1.45), g.cue.y - dy * (back + 1.45))
            val ferrule = P(g.cue.x - dx * (back + 0.025), g.cue.y - dy * (back + 0.025))
            drawLine(Color(0x44000000), tip + Offset(R * 0.4f, R * 0.5f), butt + Offset(R * 0.4f, R * 0.5f), R * 0.7f, cap = StrokeCap.Round)
            drawLine(Brush.linearGradient(listOf(Color(0xFFE8CFA0), Color(0xFFC8955A), Color(0xFF5A2E14), Color(0xFF1E1410)), tip, butt), tip, butt, R * 0.5f, cap = StrokeCap.Round)
            drawLine(Color(0xFFF7F3EA), tip, ferrule, R * 0.42f)
            drawCircle(Color(0xFF4A78B8), R * 0.21f, tip)
        }
    }
}

/**
 * Объёмный шар: цвет, у полосатых — белые «шапки» у полюсов, кружок с номером; всё поворачивается вместе с шаром
 * (ориентация из физики), сверху — неподвижные блик и затемнение к краю.
 */
private fun DrawScope.drawBall(mode: PoolMode, b: PoolBall, c: Offset, R: Float) {
    val base = ballColor(mode, b.num)
    val stripe = mode == PoolMode.EIGHT && b.num >= 9
    val r = b.rot
    val pole = Triple(r[2], r[5], r[8])   // ось полос/номера (локальная z)
    val side = Triple(r[0], r[3], r[6])   // локальная x
    val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c - Offset(R, R), Size(2 * R, 2 * R))) }
    clipPath(clip) {
        drawCircle(if (stripe) Color(0xFFF7F5EE) else base, R, c)
        if (stripe) {
            // цветной пояс: заливаем шар цветом, затем белые шапки у полюсов (|cos| > 0,55)
            drawCircle(base, R, c)
            for (sgn in listOf(1.0, -1.0)) cap(c, R, pole.first * sgn, pole.second * sgn, pole.third * sgn, 0.55f, Color(0xFFF7F5EE))
        }
        // кружок с номером (у сплошных — у полюса, у полосатых — на поясе), у битка номера нет
        if (b.num != 0) {
            val dirs = if (stripe) listOf(side, Triple(-side.first, -side.second, -side.third)) else listOf(pole, Triple(-pole.first, -pole.second, -pole.third))
            for (d in dirs) if (d.third > -0.2) {
                cap(c, R, d.first, d.second, d.third, 0.86f, if (mode == PoolMode.PYRAMID) IVORY else Color(0xFFF7F5EE))
                if (d.third > 0.35) {
                    val p = c + Offset((d.first * R * 0.93).toFloat(), (d.second * R * 0.93).toFloat())
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = R * 0.82f * d.third.toFloat().coerceIn(0.5f, 1f); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true }
                    drawContext.canvas.nativeCanvas.drawText("${b.num}", p.x, p.y + paint.textSize * 0.36f, paint)
                }
            }
        } else if (mode == PoolMode.EIGHT) {
            // у битка — красная точка, чтобы было видно вращение
            for (d in listOf(pole, Triple(-pole.first, -pole.second, -pole.third))) if (d.third > 0) drawCircle(Color(0xFFD23A2E), R * 0.13f * d.third.toFloat().coerceAtLeast(0.4f),
                c + Offset((d.first * R * 0.9).toFloat(), (d.second * R * 0.9).toFloat()))
        }
        // объём: затемнение к краю и блик (источник света сверху-слева, не крутится)
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color(0x66000000)), c - Offset(R * 0.25f, R * 0.3f), R * 1.35f), R, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color(0x00FFFFFF)), c - Offset(R * 0.38f, R * 0.42f), R * 0.45f), R * 0.45f, c - Offset(R * 0.38f, R * 0.42f))
        drawCircle(Color(0x66FFFFFF), R * 0.08f, c - Offset(R * 0.4f, R * 0.45f))
    }
}

/**
 * «Шапка» сферы вокруг направления (dx, dy, dz) с косинусом угла cosA: проекция её края — эллипс.
 * Видна, если обращена к нам (dz > −0,2); у края шара сплющивается.
 */
private fun DrawScope.cap(c: Offset, R: Float, dx: Double, dy: Double, dz: Double, cosA: Float, color: Color) {
    if (dz < -0.2) return
    val sinA = sqrt(1f - cosA * cosA)
    val center = c + Offset((dx * cosA * R).toFloat(), (dy * cosA * R).toFloat())
    val ang = Math.toDegrees(atan2(dy, dx)).toFloat()
    val major = sinA * R; val minor = (sinA * R * abs(dz).toFloat()).coerceAtLeast(R * 0.04f)
    withTransform({ rotate(ang, center) }) {
        drawOval(color, Offset(center.x - minor, center.y - major), Size(2 * minor, 2 * major))
    }
}

/** Звуки бильярда — синтез: щелчок шаров (громкость от скорости), борт, луза, удар кия. */
private object BilliardSfx {
    private const val SR = Sfx.SR
    private val cache = HashMap<String, ShortArray>()
    private var lastHit = 0L; private var lastRail = 0L
    private fun make(sec: Double, f: (FloatArray, java.util.Random) -> Unit): ShortArray {
        val a = FloatArray((sec * SR).toInt()); val r = java.util.Random(5); f(a, r)
        val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4f); val fade = (0.003 * SR).toInt()
        return ShortArray(a.size) { i -> val e = minOf(1.0, (a.size - 1 - i).toDouble() / fade); (a[i] / peak * 0.9 * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }
    private fun modes(a: FloatArray, at: Double, list: List<Pair<Double, Double>>, amp: Double) {
        val s0 = (at * SR).toInt()
        list.forEachIndexed { m, (f, d) -> for (i in 0 until (SR * 5.0 / d).toInt()) { val j = s0 + i; if (j >= a.size) break; val t = i.toDouble() / SR
            a[j] += (sin(2 * PI * f * t) * exp(-t * d) * amp / (1 + m * 0.6)).toFloat() } }
    }
    private fun noise(a: FloatArray, r: java.util.Random, at: Double, dur: Double, cutoff: Double, amp: Double) {
        val s0 = (at * SR).toInt(); val n = (dur * SR).toInt(); val k = (2 * PI * cutoff / SR).let { it / (it + 1) }; var y = 0.0
        for (i in 0 until n) { val j = s0 + i; if (j >= a.size) break; y += k * ((r.nextDouble() * 2 - 1) - y); a[j] += (y * 2.5 * amp * (1 - i.toDouble() / n)).toFloat() }
    }
    private fun play(key: String, gain: Float, make: () -> ShortArray) {
        val pcm = synchronized(cache) { cache.getOrPut(key, make) }
        val p = if (gain >= 0.99f) pcm else ShortArray(pcm.size) { (pcm[it] * gain).toInt().toShort() }
        runCatching { Sfx.playPcm(key + (gain * 4).toInt(), p) }
    }
    fun cue(power: Double) = play("bl_cue", (0.35 + power * 0.65).toFloat()) { make(0.12) { a, r -> modes(a, 0.003, listOf(1150.0 to 140.0, 2300.0 to 220.0), 0.8); noise(a, r, 0.003, 0.012, 5000.0, 0.5) } }
    fun events(ev: List<PoolEvent>) {
        val now = System.currentTimeMillis()
        ev.forEach { e -> when (e) {
            is PoolEvent.Hit -> if (now - lastHit > 25 && e.speed > 0.05) { lastHit = now
                play("bl_hit", (e.speed / 4).coerceIn(0.12, 1.0).toFloat()) { make(0.09) { a, r -> modes(a, 0.002, listOf(3100.0 to 380.0, 4700.0 to 520.0, 6900.0 to 700.0), 0.9); noise(a, r, 0.002, 0.004, 9000.0, 0.6) } } }
            is PoolEvent.Rail -> if (now - lastRail > 40 && e.speed > 0.08) { lastRail = now
                play("bl_rail", (e.speed / 3).coerceIn(0.15, 1.0).toFloat()) { make(0.18) { a, r -> modes(a, 0.003, listOf(190.0 to 45.0, 330.0 to 70.0, 720.0 to 140.0), 0.9); noise(a, r, 0.003, 0.01, 2000.0, 0.4) } } }
            is PoolEvent.Pot -> play("bl_pot", 1f) { make(0.55) { a, r ->
                modes(a, 0.005, listOf(420.0 to 60.0, 900.0 to 90.0), 0.5); modes(a, 0.08, listOf(380.0 to 50.0, 760.0 to 80.0), 0.35); modes(a, 0.16, listOf(150.0 to 25.0, 260.0 to 40.0), 0.6); noise(a, r, 0.16, 0.25, 600.0, 0.3) } }
        } }
    }
}
