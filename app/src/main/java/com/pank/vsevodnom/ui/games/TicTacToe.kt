package com.pank.vsevodnom.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlin.random.Random

object Ttt {
    val LINES = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6))
    fun winner(b: List<Int>): Pair<Int, List<Int>>? = LINES.firstOrNull { l -> b[l[0]] != 0 && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]] }?.let { b[it[0]] to it }
    /** Минимакс: лучший ход для игрока p (1 — крестики, 2 — нолики). */
    fun best(b: List<Int>, p: Int): Int {
        fun score(bb: MutableList<Int>, turn: Int, depth: Int): Int {
            winner(bb)?.let { return if (it.first == p) 10 - depth else depth - 10 }
            if (bb.none { it == 0 }) return 0
            val scores = bb.indices.filter { bb[it] == 0 }.map { i -> bb[i] = turn; val s = score(bb, 3 - turn, depth + 1); bb[i] = 0; s }
            return if (turn == p) scores.max() else scores.min()
        }
        val free = b.indices.filter { b[it] == 0 }
        val mb = b.toMutableList()
        return free.maxBy { i -> mb[i] = p; val s = score(mb, 3 - p, 1); mb[i] = 0; s * 10 + Random.nextInt(3) }
    }
}

/** Крестики-нолики против бота (минимакс, без ошибок). Кто кем играет и кто ходит первым (✕) — каждый раз случайно. */
@Composable
fun TicTacToeScreen(onBack: () -> Unit) {
    var b by remember { mutableStateOf(List(9) { 0 }) }
    var turn by remember { mutableIntStateOf(1) }
    var youX by remember { mutableStateOf(Random.nextBoolean()) }
    var wins by remember { mutableStateOf(Triple(0, 0, 0)) }  // я, бот, ничья
    val win = Ttt.winner(b)
    val draw = win == null && b.none { it == 0 }
    val me = if (youX) 1 else 2
    val bot = 3 - me
    fun play(i: Int) {
        if (b[i] != 0 || win != null) return
        b = b.toMutableList().also { it[i] = turn }; turn = 3 - turn
        Ttt.winner(b)?.let { w -> wins = if (w.first == me) wins.copy(first = wins.first + 1) else wins.copy(second = wins.second + 1) }
            ?: run { if (b.none { it == 0 }) wins = wins.copy(third = wins.third + 1) }
    }
    LaunchedEffect(b, turn, youX) {
        if (turn == bot && win == null && !draw) {
            delay(450)
            // первый ход бота на пустом поле — случайный угол/центр (иначе всегда одинаково)
            play(if (b.all { it == 0 }) listOf(0, 2, 4, 6, 8).random() else Ttt.best(b, bot))
        }
    }
    fun reset() { b = List(9) { 0 }; turn = 1; youX = Random.nextBoolean() }
    ScreenScaffold("Крестики-нолики", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("я · " + (if (youX) "✕" else "○") to wins.first, "ничья" to wins.third, "бот · " + (if (youX) "○" else "✕") to wins.second).forEach { (k, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$v", style = MaterialTheme.typography.headlineMedium); Text(k, style = MaterialTheme.typography.labelLarge, color = Pal.Muted) }
                }
            }
            Text(when { win != null -> if (win.first == me) "Ты победил!" else "Бот победил"; draw -> "Ничья"; turn == bot -> "Бот думает…"; else -> "Твой ход — " + if (me == 1) "✕" else "○" },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(Pal.Card).padding(10.dp)) {
                Column {
                    for (r in 0 until 3) Row(Modifier.weight(1f)) {
                        for (c in 0 until 3) {
                            val i = r * 3 + c
                            val hl = win?.second?.contains(i) == true
                            Box(Modifier.weight(1f).fillMaxHeight().padding(5.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (hl) Pal.Butter else Pal.Ink.copy(alpha = 0.04f)).clickable(enabled = b[i] == 0 && win == null && turn != bot) { play(i) }) {
                                if (b[i] != 0) Mark(b[i], Modifier.fillMaxSize().padding(18.dp))
                            }
                        }
                    }
                }
            }
            PillButton("Новая партия", { reset() }, Modifier.fillMaxWidth())
        }
    }
}

/** Крестик или нолик, дорисовывается анимацией. */
@Composable
private fun Mark(p: Int, modifier: Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(260)) }
    Canvas(modifier) {
        val w = size.width; val st = Stroke(w * 0.12f, cap = StrokeCap.Round)
        if (p == 1) {
            val k1 = (a.value * 2).coerceAtMost(1f); val k2 = (a.value * 2 - 1).coerceIn(0f, 1f)
            drawLine(Pal.LilacInk, Offset(0f, 0f), Offset(w * k1, w * k1), st.width, StrokeCap.Round)
            if (k2 > 0) drawLine(Pal.LilacInk, Offset(w, 0f), Offset(w - w * k2, w * k2), st.width, StrokeCap.Round)
        } else drawArc(Color(0xFFE8711A), -90f, 360f * a.value, false, style = st)
    }
}
