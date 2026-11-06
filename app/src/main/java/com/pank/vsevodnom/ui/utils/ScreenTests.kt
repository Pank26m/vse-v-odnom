package com.pank.vsevodnom.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.ScreenScaffold
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Полноэкранный режим без системных панелей на время теста. */
@Composable
fun Immersive() {
    val act = LocalContext.current.findActivity() ?: return
    DisposableEffect(Unit) {
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
fun ScreenTestScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf<String?>(null) }
    when (mode) {
        "colors" -> ColorsTest { mode = null }
        "gradient" -> GradientTest { mode = null }
        "touch" -> TouchGridTest({ mode = null })
        "motion" -> MotionTest { mode = null }
        else -> ScreenScaffold("Тест экрана", onBack) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("colors", "Цвета", "Чистые цвета на весь экран — ищи битые пиксели, пятна и выгорание. Касание — следующий цвет."),
                    Triple("gradient", "Градиенты", "Плавность переходов: не должно быть полос и ступенек."),
                    Triple("touch", "Касания", "Проведи пальцем по всем клеткам — проверка мёртвых зон тачскрина."),
                    Triple("motion", "Движение", "Бегущие объекты на трёх скоростях и счётчик кадров — шлейфы и плавность."),
                ).forEach { (k, t, s) ->
                    item {
                        AppCard(Modifier.fillMaxWidth(), onClick = { mode = k }) {
                            Text(t, style = MaterialTheme.typography.titleMedium)
                            Text(s, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private val TEST_COLORS = listOf(
    Color.Black to "Чёрный", Color.White to "Белый", Color.Red to "Красный", Color.Green to "Зелёный",
    Color.Blue to "Синий", Color(0xFF808080) to "Серый 50%", Color(0xFF202020) to "Тёмно-серый",
)

@Composable
private fun ColorsTest(onDone: () -> Unit) {
    Immersive(); BackHandler(onBack = onDone)
    var i by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    Box(
        Modifier.fillMaxSize().background(TEST_COLORS[i].first)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); hint = false; if (i == TEST_COLORS.lastIndex) onDone() else i++ } },
        contentAlignment = Alignment.Center
    ) {
        if (hint) Text("${TEST_COLORS[i].second}\nкасание — дальше, «назад» — выход", color = if (i == 1) Color.Black else Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GradientTest(onDone: () -> Unit) {
    Immersive(); BackHandler(onBack = onDone)
    Column(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onDone() } }) {
        listOf(Color.White, Color.Red, Color.Green, Color.Blue).forEach { c ->
            Box(Modifier.weight(1f).fillMaxWidth().background(Brush.horizontalGradient(listOf(Color.Black, c))))
        }
    }
}

/** Сетка клеток: каждая закрашивается при касании. onPassed — когда закрашены все. */
@Composable
fun TouchGridTest(onDone: () -> Unit, onPassed: () -> Unit = {}) {
    Immersive(); BackHandler(onBack = onDone)
    val cols = 9; val rows = 18
    val hit = remember { mutableStateListOf(*Array(cols * rows) { false }) }
    var size by remember { mutableStateOf(Size.Zero) }
    val left = hit.count { !it }
    LaunchedEffect(left) { if (left == 0) { onPassed(); onDone() } }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { ch ->
                            val p = ch.position
                            val c = (p.x / (this.size.width / cols.toFloat())).toInt().coerceIn(0, cols - 1)
                            val r = (p.y / (this.size.height / rows.toFloat())).toInt().coerceIn(0, rows - 1)
                            if (!hit[r * cols + c]) hit[r * cols + c] = true
                            ch.consume()
                        }
                    }
                }
            }
        ) {
            size = this.size
            val cw = this.size.width / cols; val rh = this.size.height / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                val on = hit[r * cols + c]
                drawRect(if (on) Color(0xFF9FD9B8) else Color(0xFFF3F1EC), Offset(c * cw + 2, r * rh + 2), Size(cw - 4, rh - 4))
            }
        }
        Text("Осталось клеток: $left · «назад» — выход", Modifier.align(Alignment.Center), color = Color(0xFF6B6A75))
    }
}

@Composable
private fun MotionTest(onDone: () -> Unit) {
    Immersive(); BackHandler(onBack = onDone)
    var t by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L; var frames = 0; var acc = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) { acc += now - last; frames++ }
                if (acc >= 1_000_000_000L) { fps = (frames * 1e9 / acc).roundToInt(); frames = 0; acc = 0 }
                last = now; t = now
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF2B2A33)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onDone() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val lanes = listOf(240f, 480f, 960f) // пикселей в секунду
            val sec = t / 1e9f
            lanes.forEachIndexed { i, speed ->
                val y = size.height * (0.25f + i * 0.25f)
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), 2f)
                val x = (sec * speed) % (size.width + 120f) - 60f
                // «тарелка»
                drawOval(Color(0xFFDCEBFA), Offset(x - 50, y - 14), Size(100f, 28f))
                drawOval(Color(0xFFB9A9E8), Offset(x - 24, y - 34), Size(48f, 34f))
                drawCircle(Color(0xFFFCE7A6), 5f, Offset(x - 20, y + 2)); drawCircle(Color(0xFFFCE7A6), 5f, Offset(x, y + 4)); drawCircle(Color(0xFFFCE7A6), 5f, Offset(x + 20, y + 2))
            }
        }
        Text("$fps кадр/с · 240 / 480 / 960 пикс/с · касание — выход", Modifier.align(Alignment.TopCenter).padding(top = 32.dp), color = Color.White)
    }
}
