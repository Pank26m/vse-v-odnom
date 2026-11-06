package com.pank.vsevodnom.ui.random

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import kotlin.math.*

private val rnd = SecureRandom()

@Composable
fun RandomScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold("Рандом", onBack) {
        Segmented(listOf("Монетка", "Колесо", "Шар", "Число", "Кубики"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Peach)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (tab) {
                0 -> Coin()
                1 -> Wheel()
                2 -> EightBall()
                3 -> NumberTab()
                else -> Dice()
            }
        }
    }
}

// ---------------- Монетка: 1 гривна ----------------

private val Gold = Color(0xFFE9C46A)
private val GoldDark = Color(0xFFB08A2E)
private val GoldLight = Color(0xFFF6DFA0)

/** Упрощённый тризуб — силуэт из трёх зубцов на основании. */
private fun DrawScope.tryzub(c: Offset, h: Float, color: Color) {
    val w = h * 0.62f
    val st = Stroke(h * 0.075f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val top = c.y - h / 2; val bot = c.y + h / 2
    // центральный зубец
    drawLine(color, Offset(c.x, top), Offset(c.x, bot - h * 0.12f), h * 0.075f)
    drawPath(Path().apply { moveTo(c.x - h * 0.07f, top + h * 0.18f); lineTo(c.x, top); lineTo(c.x + h * 0.07f, top + h * 0.18f) }, color, style = st)
    // боковые
    for (s in listOf(-1f, 1f)) {
        drawPath(Path().apply {
            moveTo(c.x + s * w / 2, top + h * 0.05f)
            lineTo(c.x + s * w / 2, bot - h * 0.3f)
            quadraticBezierTo(c.x + s * w / 2, bot - h * 0.12f, c.x, bot - h * 0.12f)
        }, color, style = st)
        drawPath(Path().apply { moveTo(c.x + s * w * 0.18f, top + h * 0.38f); quadraticBezierTo(c.x + s * w * 0.3f, bot - h * 0.3f, c.x, bot - h * 0.22f) }, color, style = st)
    }
    drawLine(color, Offset(c.x - w * 0.25f, bot), Offset(c.x + w * 0.25f, bot), h * 0.07f)
    drawLine(color, Offset(c.x, bot - h * 0.12f), Offset(c.x, bot), h * 0.075f)
}

private fun DrawScope.coinFace(heads: Boolean) {
    val r = size.minDimension / 2; val c = center
    drawCircle(Brush.radialGradient(listOf(GoldLight, Gold, GoldDark), c, r * 1.1f), r, c)
    drawCircle(GoldDark, r * 0.9f, c, style = Stroke(r * 0.03f))
    // зубчики по краю
    for (i in 0 until 72) { val a = i * 5.0 * PI / 180; drawLine(GoldDark.copy(alpha = 0.5f), Offset(c.x + cos(a).toFloat() * r * 0.94f, c.y + sin(a).toFloat() * r * 0.94f), Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r), r * 0.012f) }
    val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(140, 105, 30); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
    if (heads) {
        tryzub(Offset(c.x, c.y - r * 0.08f), r * 0.85f, GoldDark)
        p.textSize = r * 0.13f; drawContext.canvas.nativeCanvas.drawText("УКРАЇНА", c.x, c.y + r * 0.72f, p)
    } else {
        p.textSize = r * 0.75f; drawContext.canvas.nativeCanvas.drawText("1", c.x, c.y + r * 0.12f, p)
        p.textSize = r * 0.17f; drawContext.canvas.nativeCanvas.drawText("ГРИВНЯ", c.x, c.y + r * 0.42f, p)
        // веточки-венок
        for (s in listOf(-1f, 1f)) for (k in 0 until 6) {
            val a = (200 + k * 14) * PI / 180
            val x = c.x + s * cos(a).toFloat() * r * 0.68f; val y = c.y - sin(a).toFloat() * r * 0.68f
            drawOval(GoldDark.copy(alpha = 0.7f), Offset(x - r * 0.04f, y - r * 0.07f), Size(r * 0.08f, r * 0.14f))
        }
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("random", Context.MODE_PRIVATE)

@Composable
private fun Coin() {
    var heads by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var yesNo by rememberSaveable { mutableStateOf(false) }
    var stats by rememberSaveable { mutableStateOf(0 to 0) }
    val rot = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Орёл / Решка", !yesNo, { yesNo = false }); Chip("Да / Нет", yesNo, { yesNo = true }) }
    Spacer(Modifier.height(24.dp))
    // какая сторона видна в данный момент вращения
    val showHeads = run { val half = ((rot.value % 360f) + 360f) % 360f; val front = half < 90f || half > 270f; if (heads == null) true else if (front) heads!! else !heads!! }
    Canvas(Modifier.size(230.dp).graphicsLayer { rotationX = rot.value; translationY = -lift.value; cameraDistance = 14 * density }) { coinFace(showHeads) }
    Spacer(Modifier.height(16.dp))
    val label = when { heads == null || rot.isRunning -> " "; yesNo -> if (heads!!) "Да" else "Нет"; else -> if (heads!!) "Орёл" else "Решка" }
    Text(label, style = MaterialTheme.typography.displayMedium, color = Pal.PeachInk)
    Spacer(Modifier.height(16.dp))
    PillButton("Подбросить", {
        scope.launch {
            val r = rnd.nextBoolean(); heads = r
            rot.snapTo(0f)
            launch { lift.animateTo(260f, tween(600, easing = FastOutSlowInEasing)); lift.animateTo(0f, tween(600, easing = FastOutLinearInEasing)) }
            rot.animateTo(360f * 6, tween(1200, easing = LinearOutSlowInEasing))
            rot.snapTo(0f)
            stats = if (r) stats.copy(first = stats.first + 1) else stats.copy(second = stats.second + 1)
        }
    }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Text(if (yesNo) "Да ${stats.first} · Нет ${stats.second}" else "Орёл ${stats.first} · Решка ${stats.second}", style = MaterialTheme.typography.bodySmall)
}

// ---------------- Колесо фортуны ----------------

@Composable
private fun Wheel() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(prefs(ctx).getString("wheel", "Пицца\nСуши\nБургер\nШаурма\nДома готовить")!!) }
    val items = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(16)
    val angle = remember { Animatable(0f) }
    var winner by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = listOf(Pal.Sky, Pal.Mint, Pal.Peach, Pal.Lilac, Pal.Butter, Pal.Rose)

    Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            if (items.isEmpty()) return@Canvas
            val n = items.size; val sweep = 360f / n
            rotate(angle.value) {
                items.forEachIndexed { i, s ->
                    drawArc(colors[i % colors.size], -90f + i * sweep, sweep, true)
                    drawArc(Color.White, -90f + i * sweep, sweep, true, style = Stroke(4f))
                    val mid = (-90f + i * sweep + sweep / 2) * PI / 180
                    val r = size.minDimension / 2
                    val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(43, 42, 51); textSize = (r * 0.11f).coerceAtMost(46f); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                    drawContext.canvas.nativeCanvas.save()
                    drawContext.canvas.nativeCanvas.rotate((mid * 180 / PI).toFloat(), center.x, center.y)
                    drawContext.canvas.nativeCanvas.drawText(s.take(14), center.x + r * 0.58f, center.y + p.textSize / 3, p)
                    drawContext.canvas.nativeCanvas.restore()
                }
            }
            drawCircle(Color.White, 22f, center); drawCircle(Pal.Ink, 10f, center)
        }
        // стрелка сверху
        Canvas(Modifier.size(30.dp).align(Alignment.TopCenter)) {
            drawPath(Path().apply { moveTo(size.width / 2, size.height); lineTo(0f, 0f); lineTo(size.width, 0f); close() }, Pal.Ink)
        }
    }
    Text(winner ?: " ", style = MaterialTheme.typography.displayMedium.copy(fontSize = 36.sp), color = Pal.PeachInk, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    PillButton("Крутить", {
        if (items.size < 2) return@PillButton
        scope.launch {
            winner = null
            val target = angle.value + 360f * (5 + rnd.nextInt(3)) + rnd.nextFloat() * 360f
            angle.animateTo(target, tween(3600, easing = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)))
            // под стрелкой (сверху) — сектор, в который попал угол
            val n = items.size; val sweep = 360f / n
            val a = ((360f - (angle.value % 360f)) % 360f)
            winner = items[(a / sweep).toInt().coerceIn(0, n - 1)]
        }
    }, Modifier.fillMaxWidth(), enabled = !angle.isRunning)
    Spacer(Modifier.height(12.dp))
    AppTextField(text, { text = it; prefs(ctx).edit().putString("wheel", it).apply() }, "Варианты — каждый с новой строки", singleLine = false, modifier = Modifier.heightIn(min = 140.dp))
}

// ---------------- Шар предсказаний ----------------

private val ANSWERS = listOf(
    "Бесспорно", "Предрешено", "Никаких сомнений", "Определённо да", "Можешь быть уверен",
    "Мне кажется — да", "Вероятнее всего", "Хорошие перспективы", "Знаки говорят — да", "Да",
    "Пока не ясно", "Спроси позже", "Лучше не рассказывать", "Сейчас нельзя предсказать", "Сосредоточься и спроси снова",
    "Даже не думай", "Мой ответ — нет", "По моим данным — нет", "Перспективы не очень", "Весьма сомнительно",
)

@Composable
private fun EightBall() {
    var answer by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(12.dp))
    Box(Modifier.size(280.dp).graphicsLayer { translationX = shake.value }.clip(CircleShape).clickable {
        scope.launch {
            reveal.snapTo(0f)
            repeat(6) { shake.animateTo(if (it % 2 == 0) 22f else -22f, tween(60)) }
            shake.animateTo(0f, tween(60))
            answer = ANSWERS[rnd.nextInt(ANSWERS.size)]
            reveal.animateTo(1f, tween(900))
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFF55545F), Color(0xFF1B1A21), Color.Black), Offset(center.x - r * 0.35f, center.y - r * 0.4f), r * 1.4f), r)
            drawCircle(Color(0xFF0E1530), r * 0.48f)
            drawCircle(Color(0xFF2A2F4A), r * 0.48f, style = Stroke(r * 0.03f))
            if (answer != null) {
                val t = r * 0.42f
                drawPath(Path().apply { moveTo(center.x, center.y + t); lineTo(center.x - t * 0.92f, center.y - t * 0.55f); lineTo(center.x + t * 0.92f, center.y - t * 0.55f); close() },
                    Color(0xFF2347B8).copy(alpha = reveal.value))
            } else {
                val p = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = r * 0.45f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                drawContext.canvas.nativeCanvas.drawText("8", center.x, center.y + r * 0.16f, p)
            }
        }
        answer?.let {
            Text(it.uppercase(), color = Color.White.copy(alpha = reveal.value), fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 15.sp,
                modifier = Modifier.width(110.dp).offset(y = (-14).dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Задай вопрос про себя и нажми на шар", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
}

// ---------------- Число и кубики ----------------

@Composable
private fun BigResult(text: String, sub: String = "") {
    AppCard(color = Pal.Peach, radius = 18.dp, padding = 24.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.displayLarge.copy(fontSize = if (text.length > 8) 40.sp else 72.sp), textAlign = TextAlign.Center)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Pal.PeachInk, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NumberTab() {
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf("100") }
    var count by rememberSaveable { mutableStateOf("1") }
    var unique by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("—") }
    var err by rememberSaveable { mutableStateOf("") }
    BigResult(result, err)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTextField(from, { from = it }, "От", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
        AppTextField(to, { to = it }, "До", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
        AppTextField(count, { count = it }, "Сколько", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Без повторов", unique, { unique = true }); Chip("С повторами", !unique, { unique = false }) }
    Spacer(Modifier.height(16.dp))
    PillButton("Сгенерировать", {
        err = ""
        val a = from.trim().toLongOrNull(); val b = to.trim().toLongOrNull(); val n = count.trim().toIntOrNull() ?: 1
        if (a == null || b == null) { err = "Введи числа"; return@PillButton }
        val lo = minOf(a, b); val span = maxOf(a, b) - lo + 1
        if (n < 1 || n > 1000) { err = "От 1 до 1000 чисел"; return@PillButton }
        if (unique && n > span) { err = "В диапазоне только $span чисел"; return@PillButton }
        val pick = { lo + (rnd.nextDouble() * span).toLong().coerceAtMost(span - 1) }
        result = (if (unique) LinkedHashSet<Long>().apply { while (size < n) add(pick()) }.toList() else List(n) { pick() }).joinToString(", ")
    }, Modifier.fillMaxWidth())
}

private val PIPS = mapOf(1 to listOf(4), 2 to listOf(0, 8), 3 to listOf(0, 4, 8), 4 to listOf(0, 2, 6, 8), 5 to listOf(0, 2, 4, 6, 8), 6 to listOf(0, 2, 3, 5, 6, 8))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dice() {
    var n by rememberSaveable { mutableIntStateOf(2) }
    var values by rememberSaveable { mutableStateOf(listOf(1, 1)) }
    val scope = rememberCoroutineScope()
    FlowRow(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        values.forEach { v ->
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(14.dp)) {
                Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                    for (r in 0..2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (c in 0..2) Box(Modifier.size(16.dp).clip(CircleShape).background(if ((r * 3 + c) in PIPS.getValue(v)) Pal.Ink else Color.Transparent))
                    }
                }
            }
        }
    }
    Text("Сумма: ${values.sum()}", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..6).forEach { k -> Chip("$k", n == k, { n = k; values = List(k) { 1 } }) } }
    Spacer(Modifier.height(16.dp))
    PillButton("Бросить", { scope.launch { repeat(8) { values = List(n) { rnd.nextInt(6) + 1 }; delay(60L + it * 15) } } }, Modifier.fillMaxWidth())
}
