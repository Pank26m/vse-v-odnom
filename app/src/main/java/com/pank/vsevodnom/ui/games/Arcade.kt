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

/** Счёт и рекорд в шапке. */
@Composable
fun ScoreBar(score: Int, best: Int, extra: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$score", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(end = 12.dp))
        Text("рекорд $best", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
    }
}
