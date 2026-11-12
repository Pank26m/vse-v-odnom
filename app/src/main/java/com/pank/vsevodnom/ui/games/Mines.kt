package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlin.random.Random

private data class Level(val name: String, val cols: Int, val rows: Int, val mines: Int)
/** Поле 16 по ширине × 27 по высоте (целиком на экране); перед игрой — сколько мин. */
private val LEVELS = listOf(Level("Минимум бомб", 16, 27, 55), Level("Норма", 16, 27, 85), Level("Максимум бомб", 16, 27, 115))

private class MineBoard(val cols: Int, val rows: Int, val mines: Int) {
    val mine = BooleanArray(cols * rows)
    val open = mutableStateListOf(*Array(cols * rows) { false })
    val flag = mutableStateListOf(*Array(cols * rows) { false })
    val near = IntArray(cols * rows)
    var placed by mutableStateOf(false)
    var lost by mutableStateOf(false)
    var boom = -1

    fun nb(i: Int): List<Int> {
        val x = i % cols; val y = i / cols
        val out = ArrayList<Int>(8)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until cols && ny in 0 until rows) out += ny * cols + nx
        }
        return out
    }

    /** Первое нажатие всегда безопасно: мины ставим вокруг него после первого хода. */
    fun place(safe: Int) {
        val forbidden = (nb(safe) + safe).toSet()
        val cells = (0 until cols * rows).filter { it !in forbidden }.shuffled(Random(System.nanoTime()))
        cells.take(mines).forEach { mine[it] = true }
        for (i in mine.indices) near[i] = nb(i).count { mine[it] }
        placed = true
    }

    fun reveal(i: Int) {
        if (lost || open[i] || flag[i]) return
        if (!placed) place(i)
        if (mine[i]) { lost = true; boom = i; for (k in mine.indices) if (mine[k]) open[k] = true; return }
        val stack = ArrayDeque<Int>(); stack += i
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (open[c] || flag[c]) continue
            open[c] = true
            if (near[c] == 0) nb(c).forEach { if (!open[it]) stack += it }
        }
    }

    /** Нажатие на открытую цифру: если флагов вокруг столько же — открыть остальное. */
    fun chord(i: Int) {
        if (!open[i] || near[i] == 0) return
        val n = nb(i)
        if (n.count { flag[it] } == near[i]) n.forEach { reveal(it) }
    }

    val won get() = placed && !lost && (0 until cols * rows).all { mine[it] || open[it] }
    val flags get() = flag.count { it }
}

private val NUM_COLORS = listOf(Color.Transparent, Color(0xFF3E6FA8), Color(0xFF2F7D57), Color(0xFFA8456A), Color(0xFF6A55A8),
    Color(0xFFB65E2E), Color(0xFF2B8A8A), Color(0xFF2B2A33), Color(0xFF6B6A75))

private fun best(ctx: Context, lvl: String) = ctx.getSharedPreferences("games", Context.MODE_PRIVATE).getInt("mines_$lvl", 0)
private fun setBest(ctx: Context, lvl: String, s: Int) = ctx.getSharedPreferences("games", Context.MODE_PRIVATE).edit().putInt("mines_$lvl", s).apply()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var lvl by remember { mutableIntStateOf(1) }
    var chosen by remember { mutableStateOf(false) }
    var gen by remember { mutableIntStateOf(0) }
    val L = LEVELS[lvl]
    val b = remember(lvl, gen) { MineBoard(L.cols, L.rows, L.mines) }
    var flagMode by remember { mutableStateOf(false) }
    var sec by remember(lvl, gen) { mutableIntStateOf(0) }
    val done = b.lost || b.won
    LaunchedEffect(lvl, gen, b.placed, done) { while (b.placed && !done) { delay(1000); sec++ } }
    LaunchedEffect(b.won) { if (b.won) { val old = best(ctx, L.name); if (old == 0 || sec < old) setBest(ctx, L.name, sec) } }

    if (!chosen) {
        ScreenScaffold("Сапёр", onBack) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сколько мин на поле 16×27?", style = MaterialTheme.typography.titleMedium)
                LEVELS.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(listOf(Pal.Mint, Pal.Sky, Pal.Rose)[i]).clickable { lvl = i; gen++; chosen = true }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("✱".repeat(i + 1), fontSize = 22.sp, color = listOf(Pal.MintInk, Pal.SkyInk, Pal.RoseInk)[i], modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.titleMedium)
                            Text("${l.mines} мин · ${l.mines * 100 / (l.cols * l.rows)}% поля" + (best(ctx, l.name).takeIf { it > 0 }?.let { " · рекорд $it с" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                    }
                }
            }
        }
        return
    }
    androidx.activity.compose.BackHandler { chosen = false }
    ScreenScaffold("Сапёр · ${L.name}", { chosen = false }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✱ ${L.mines - b.flags}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    when { b.won -> "Победа! $sec с"; b.lost -> "Бум!"; else -> "%d:%02d".format(sec / 60, sec % 60) },
                    style = MaterialTheme.typography.titleLarge, color = when { b.won -> Pal.MintInk; b.lost -> Pal.RoseInk; else -> Pal.Ink }
                )
                Spacer(Modifier.weight(1f))
                best(ctx, L.name).takeIf { it > 0 }?.let { Text("рекорд $it с", style = MaterialTheme.typography.labelMedium, color = Pal.Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Открывать", !flagMode, { flagMode = false })
                Chip("Ставить флаги", flagMode, { flagMode = true })
                Spacer(Modifier.weight(1f))
                Chip("Заново", false, { gen++ })
            }
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // клетка — чтобы всё поле влезло и по ширине, и по высоте
            val cell: Dp = minOf((maxWidth - 2.dp * L.cols) / L.cols, (maxHeight - 2.dp * L.rows - 8.dp) / L.rows).coerceIn(14.dp, 40.dp)
            Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (y in 0 until L.rows) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (x in 0 until L.cols) {
                        val i = y * L.cols + x
                        val o = b.open[i]
                        Box(
                            Modifier.size(cell).clip(RoundedCornerShape(cell / 5))
                                .background(when { o && b.mine[i] && i == b.boom -> Pal.RoseInk; o && b.mine[i] -> Pal.Rose; o -> Pal.Card; else -> Color(0xFFD9E7DF) })
                                .combinedClickable(
                                    enabled = !done,
                                    onClick = {
                                        when {
                                            o -> b.chord(i)
                                            flagMode -> b.flag[i] = !b.flag[i]
                                            else -> b.reveal(i)
                                        }
                                    },
                                    onLongClick = { if (!o) { b.flag[i] = !b.flag[i]; haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val fs = (cell.value * 0.5f).sp
                            when {
                                o && b.mine[i] -> Text("✱", fontSize = fs, color = Pal.Ink)
                                o && b.near[i] > 0 -> Text("${b.near[i]}", fontSize = fs, fontWeight = FontWeight.ExtraBold, color = NUM_COLORS[b.near[i]])
                                b.lost && b.flag[i] && !b.mine[i] -> Text("✕", fontSize = fs, color = Pal.RoseInk)
                                !o && b.flag[i] -> Text("⚑", fontSize = fs, color = Pal.RoseInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
