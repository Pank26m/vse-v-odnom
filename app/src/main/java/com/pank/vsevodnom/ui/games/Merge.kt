package com.pank.vsevodnom.ui.games

import androidx.compose.material3.TextButton
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal
import kotlin.math.abs
import kotlin.random.Random

/** Плитка «Слияния»: id нужен, чтобы плитка плавно ехала на новое место. */
data class MTile(val id: Int, val v: Int, val r: Int, val c: Int, val merged: Boolean = false, val born: Boolean = true)

/** Логика: сдвиг всех плиток к краю, одинаковые соседние сливаются один раз за ход. */
class MergeGame(val n: Int = 4, seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)
    var tiles = listOf<MTile>(); private set
    var score = 0; private set
    private var nextId = 1
    init { spawn(); spawn() }

    private fun spawn() {
        val free = (0 until n * n).filter { i -> tiles.none { it.r * n + it.c == i } }
        if (free.isEmpty()) return
        val i = free[rnd.nextInt(free.size)]
        tiles = tiles + MTile(nextId++, if (rnd.nextFloat() < 0.9f) 2 else 4, i / n, i % n)
    }

    /** dir: 0 влево, 1 вправо, 2 вверх, 3 вниз. Возвращает true, если что-то сдвинулось. */
    fun move(dir: Int): Boolean {
        val out = ArrayList<MTile>(); var moved = false
        for (line in 0 until n) {
            // плитки линии в порядке от края, к которому двигаем
            val cells = tiles.filter { if (dir < 2) it.r == line else it.c == line }
                .sortedBy { val p = if (dir < 2) it.c else it.r; if (dir == 0 || dir == 2) p else -p }
            var pos = 0; var last: MTile? = null
            for (t in cells.map { it.copy(merged = false, born = false) }) {
                if (last != null && last.v == t.v && !last.merged) {
                    out.remove(last)
                    val m = last.copy(v = last.v * 2, merged = true)
                    out += m; score += m.v; last = m; moved = true
                } else {
                    val p = if (dir == 0 || dir == 2) pos else n - 1 - pos
                    val nt = if (dir < 2) t.copy(c = p) else t.copy(r = p)
                    if (nt.r != t.r || nt.c != t.c) moved = true
                    out += nt; last = nt; pos++
                }
            }
        }
        if (moved) { tiles = out; spawn() }
        return moved
    }

    val max get() = tiles.maxOfOrNull { it.v } ?: 0
    fun canMove(): Boolean {
        if (tiles.size < n * n) return true
        val g = Array(n) { IntArray(n) }; tiles.forEach { g[it.r][it.c] = it.v }
        for (r in 0 until n) for (c in 0 until n) { if (c + 1 < n && g[r][c] == g[r][c + 1]) return true; if (r + 1 < n && g[r][c] == g[r + 1][c]) return true }
        return false
    }
}

private fun tileColor(v: Int): Pair<Color, Color> = when (v) {
    2 -> Color(0xFFF1EEF8) to Pal.Ink; 4 -> Color(0xFFE7E1F7) to Pal.Ink; 8 -> Color(0xFFDCEBFA) to Color(0xFF2F5C8F)
    16 -> Color(0xFFB9D6F5) to Color(0xFF1F4A78); 32 -> Color(0xFFDDF2E6) to Color(0xFF2F7D57); 64 -> Color(0xFFA9DDBF) to Color(0xFF1E5C3E)
    128 -> Color(0xFFFBF1CC) to Color(0xFF8A6B12); 256 -> Color(0xFFF7DE8A) to Color(0xFF6E540A); 512 -> Color(0xFFFCE5D6) to Color(0xFFB65E2E)
    1024 -> Color(0xFFF6BF9C) to Color(0xFF8C3F14); 2048 -> Color(0xFFF8DDE6) to Color(0xFFA8456A); else -> Color(0xFFE48AA8) to Color.White
}

@Composable
fun MergeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(MergeGame()) }
    var ver by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "merge")) }
    var won by remember { mutableStateOf(false) }
    val over = remember(ver) { !g.canMove() }
    fun go(d: Int) { if (g.move(d)) { ver++; if (Hi.submit(ctx, "merge", g.score)) best = g.score; if (g.max >= 2048 && !won) won = true } }
    ScreenScaffold("2048", onBack, actions = {
        TextButton({ g = MergeGame(); ver++; won = false }) { Text("Новая игра") }
    }) {
        key(ver) { ScoreBar(g.score, best, "макс ${g.max}") }
        Text("Смахивай — одинаковые числа сливаются. Цель: 2048.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (won) Text("2048 собрано! Можно играть дальше.", style = MaterialTheme.typography.titleSmall, color = Pal.MintInk, modifier = Modifier.padding(16.dp, 6.dp))
        // поле внизу экрана — до него удобно дотягиваться большим пальцем
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().padding(16.dp).aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color(0xFFE6E2EC))
            .pointerInput(g) {
                var acc = androidx.compose.ui.geometry.Offset.Zero
                detectDragGestures(onDragStart = { acc = androidx.compose.ui.geometry.Offset.Zero }, onDragEnd = {
                    if (maxOf(abs(acc.x), abs(acc.y)) > 40f) go(if (abs(acc.x) > abs(acc.y)) (if (acc.x < 0) 0 else 1) else (if (acc.y < 0) 2 else 3))
                }) { ch, d -> ch.consume(); acc += d }
            }.padding(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cell = maxWidth / g.n
                for (r in 0 until g.n) for (c in 0 until g.n)
                    Box(Modifier.offset(cell * c, cell * r).size(cell).padding(5.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.45f)))
                key(ver) {
                    g.tiles.forEach { t ->
                        key(t.id) {
                            val x by animateDpAsState(cell * t.c, tween(110), label = "x")
                            val y by animateDpAsState(cell * t.r, tween(110), label = "y")
                            val sc = remember { Animatable(if (t.born) 0.3f else 1f) }
                            LaunchedEffect(t.v) { if (t.merged) { sc.snapTo(1.18f); sc.animateTo(1f, tween(140)) } else if (sc.value < 1f) sc.animateTo(1f, tween(140, delayMillis = 90)) }
                            val (bg, fg) = tileColor(t.v)
                            Box(Modifier.offset(x, y).size(cell).padding(5.dp).scale(sc.value).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
                                Text("${t.v}", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = (if (t.v >= 1024) 22 else if (t.v >= 128) 26 else 30).sp)
                            }
                        }
                    }
                }
            }
            if (over) Box(Modifier.fillMaxSize()) { GameOverlay("Ходов нет", "Счёт ${g.score}", "Заново") { g = MergeGame(); ver++; won = false } }
        }
        Spacer(Modifier.height(12.dp))
    }
}
