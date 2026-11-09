package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.animateFloat
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.theme.Pal

/** Рекорды аркад: по ключу игры. */
object Hi {
    private fun p(ctx: Context) = ctx.getSharedPreferences("arcade", Context.MODE_PRIVATE)
    fun get(ctx: Context, k: String) = p(ctx).getInt(k, 0)
    /** Сохраняет, если это новый рекорд; возвращает true, если рекорд побит. */
    fun submit(ctx: Context, k: String, v: Int): Boolean { if (v <= get(ctx, k)) return false; p(ctx).edit().putInt(k, v).apply(); return true }
}

/**
 * Игровой цикл по кадрам экрана. onFrame получает шаг времени в секундах (не больше 1/20 с,
 * чтобы после паузы игра не «прыгала»).
 */
@Composable
fun GameLoop(running: Boolean, onFrame: (Float) -> Unit) {
    val cb by rememberUpdatedState(onFrame)
    LaunchedEffect(running) {
        var last = 0L
        while (running) {
            withFrameNanos { t ->
                if (last != 0L) cb(((t - last) / 1e9f).coerceIn(0f, 0.05f))
                last = t
            }
        }
    }
}

/** Плашка поверх поля: старт / проигрыш с рекордом. */
@Composable
fun BoxScope.GameOverlay(title: String, sub: String, button: String, onClick: () -> Unit) {
    Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.93f)).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, textAlign = TextAlign.Center)
        PillButton(button, onClick, Modifier.widthIn(min = 180.dp))
    }
}

/** Экран победы: на всё поле, кубок и конфетти. Нажатия под ним не проходят на поле. */
@Composable
fun BoxScope.VictoryOverlay(title: String, sub: String, button: String, onClick: () -> Unit) {
    val anim = androidx.compose.animation.core.rememberInfiniteTransition(label = "win")
    val t by anim.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    val bits = remember { List(46) { Triple(kotlin.random.Random.nextFloat(), kotlin.random.Random.nextFloat(), listOf(0xFFE5485D, 0xFFF2C94C, 0xFF3FB27F, 0xFF4A86E8, 0xFF8E5BD8).random()) } }
    Box(Modifier.matchParentSize().background(Color(0xE6FFFFFF)).pointerInput(Unit) { detectTapGestures { } }) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            bits.forEach { (x, ph, c) ->
                val y = ((t + ph) % 1f) * (size.height + 40f) - 20f
                val w = 10f + ph * 8f
                rotate(360f * ((t * 2 + ph) % 1f), androidx.compose.ui.geometry.Offset(x * size.width, y)) {
                    drawRect(Color(c), androidx.compose.ui.geometry.Offset(x * size.width - w / 2, y - w / 4), androidx.compose.ui.geometry.Size(w, w / 2))
                }
            }
        }
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🏆", fontSize = 72.sp)
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, textAlign = TextAlign.Center)
            PillButton(button, onClick, Modifier.widthIn(min = 200.dp))
        }
    }
}

/** Счёт и рекорд в шапке. */
@Composable
fun ScoreBar(score: Int, best: Int, extra: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$score", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(end = 12.dp))
        Text("рекорд $best", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
    }
}
