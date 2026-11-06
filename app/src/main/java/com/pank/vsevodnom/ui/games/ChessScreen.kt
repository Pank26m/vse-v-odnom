package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val LIGHT = Color(0xFFF3EFE7)
private val DARK = Color(0xFFC9BEE8)
private val HL = Color(0xFFF6D77B)
// сплошные глифы + U+FE0E, чтобы Android не рисовал пешку эмодзи
private val GLYPH = arrayOf("", "♟︎", "♞︎", "♝︎", "♜︎", "♛︎", "♚︎")
private val LEVEL_NAMES = listOf("Новичок", "Лёгкий", "Средний", "Сильный", "Максимум")

@Composable
fun ChessScreen(onBack: () -> Unit) {
    var level by remember { mutableIntStateOf(2) }
    var humanWhite by remember { mutableStateOf(true) }
    var gen by remember { mutableIntStateOf(0) }
    val game = remember(gen) { Chess() }
    var ver by remember { mutableIntStateOf(0) } // перерисовка после хода
    var sel by remember(gen) { mutableIntStateOf(-1) }
    var thinking by remember { mutableStateOf(false) }
    var promoFor by remember { mutableStateOf<List<Int>?>(null) }
    val moves = remember(gen) { mutableStateListOf<String>() }
    var newGame by remember { mutableStateOf(false) }
    val human = if (humanWhite) 1 else -1

    val legal = remember(ver, gen) { game.legal() }
    val result = remember(ver, gen) { game.result() }

    fun play(m: Int) { moves += game.san(m); game.make(m); sel = -1; ver++ }

    LaunchedEffect(ver, gen) {
        if (result == null && game.side != human && !thinking) {
            thinking = true
            val snapshot = game.copy()
            val m = withContext(Dispatchers.Default) { snapshot.bestMove(level + 1) }
            if (m != null) play(m)
            thinking = false
        }
    }

    ScreenScaffold("Шахматы", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result ?: when { thinking -> "Бот думает…"; game.inCheck() -> "Шах!"; game.side == human -> "Твой ход"; else -> "" },
                    style = MaterialTheme.typography.titleMedium, color = if (result != null) Pal.LilacInk else Pal.Ink, modifier = Modifier.weight(1f)
                )
                Text("Бот: ${LEVEL_NAMES[level]}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
            Captured(game, -human)
            Board(game, human, sel, legal, ver, enabled = result == null && game.side == human && !thinking) { sq ->
                val p = game.b[sq]
                if (sel >= 0) {
                    val cands = legal.filter { Chess.from(it) == sel && Chess.toSq(it) == sq }
                    when {
                        cands.size > 1 -> { promoFor = cands; return@Board }
                        cands.size == 1 -> { play(cands[0]); return@Board }
                    }
                }
                sel = if (p != 0 && (p > 0) == (human > 0)) sq else -1
            }
            Captured(game, human)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Новая", { newGame = true }, Modifier.weight(1f))
                PillButton("Ход назад", {
                    // откатываем свой ход и ответ бота
                    var n = if (game.side == human) 2 else 1
                    while (n-- > 0 && game.moveCount > 0) { game.unmake(); moves.removeLastOrNull() }
                    sel = -1; ver++
                }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink, enabled = !thinking && game.moveCount > 0)
            }
            if (moves.isNotEmpty()) AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Text(moves.chunked(2).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString("   "), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    promoFor?.let { cands ->
        AlertDialog(onDismissRequest = { promoFor = null }, confirmButton = {}, title = { Text("Превратить пешку в") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cands.sortedByDescending { Chess.promo(it) }.forEach { m ->
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Lilac).clickable { promoFor = null; play(m) }, contentAlignment = Alignment.Center) {
                            Text(GLYPH[Chess.promo(m)], fontSize = 34.sp, style = pieceStyle(human))
                        }
                    }
                }
            })
    }
    if (newGame) {
        var lv by remember { mutableIntStateOf(level) }
        var white by remember { mutableIntStateOf(if (humanWhite) 0 else 1) }
        AlertDialog(
            onDismissRequest = { newGame = false },
            title = { Text("Новая партия") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Сила бота", style = MaterialTheme.typography.labelLarge)
                    LEVEL_NAMES.chunked(3).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEachIndexed { c, n -> Chip(n, lv == r * 3 + c, { lv = r * 3 + c }) } }
                    }
                    Text("Играю за", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Белых", white == 0, { white = 0 }); Chip("Чёрных", white == 1, { white = 1 }); Chip("Случайно", white == 2, { white = 2 })
                    }
                }
            },
            confirmButton = { TextButton({ level = lv; humanWhite = if (white == 2) kotlin.random.Random.nextBoolean() else white == 0; newGame = false; gen++ }) { Text("Начать") } },
            dismissButton = { TextButton({ newGame = false }) { Text("Отмена") } }
        )
    }
}

private fun pieceStyle(color: Int) = TextStyle(
    color = if (color > 0) Color.White else Color(0xFF2B2A33),
    shadow = if (color > 0) Shadow(Color(0xFF2B2A33), Offset(0f, 0f), 6f) else null
)

@Composable
private fun Board(g: Chess, human: Int, sel: Int, legal: List<Int>, ver: Int, enabled: Boolean, onTap: (Int) -> Unit) {
    val targets = if (sel >= 0) legal.filter { Chess.from(it) == sel }.map { Chess.toSq(it) }.toSet() else emptySet()
    val last = g.lastMove()
    val checkSq = if (g.inCheck()) g.kingSq(g.side) else -1
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
        val cs = maxWidth / 8
        Column {
            for (vr in 0 until 8) Row {
                for (vf in 0 until 8) {
                    val r = if (human > 0) 7 - vr else vr
                    val f = if (human > 0) vf else 7 - vf
                    val sq = r * 8 + f
                    val p = g.b[sq]
                    val light = (r + f) % 2 == 1
                    val bg = when {
                        sq == checkSq -> Color(0xFFF3B6C6)
                        sq == sel -> HL
                        last != null && (sq == Chess.from(last) || sq == Chess.toSq(last)) -> HL.copy(alpha = 0.55f)
                        light -> LIGHT
                        else -> DARK
                    }
                    Box(Modifier.size(cs).background(bg).clickable(enabled = enabled) { onTap(sq) }, contentAlignment = Alignment.Center) {
                        if (p != 0) Text(GLYPH[abs(p)], fontSize = (cs.value * 0.72f).sp, style = pieceStyle(if (p > 0) 1 else -1))
                        if (sq in targets) Box(Modifier.size(if (p != 0) cs * 0.9f else cs * 0.28f).clip(CircleShape)
                            .background(if (p != 0) Color.Transparent else Pal.Ink.copy(alpha = 0.22f))
                            .then(if (p != 0) Modifier.background(Pal.RoseInk.copy(alpha = 0.18f)) else Modifier))
                        if (vf == 0) Text("${r + 1}", Modifier.align(Alignment.TopStart).padding(2.dp), fontSize = 9.sp, color = Pal.Muted)
                        if (vr == 7) Text("${'a' + f}", Modifier.align(Alignment.BottomEnd).padding(2.dp), fontSize = 9.sp, color = Pal.Muted)
                    }
                }
            }
        }
    }
}

/** Взятые фигуры цвета `color` (отображаются у соперника). */
@Composable
private fun Captured(g: Chess, color: Int) {
    val start = intArrayOf(0, 8, 2, 2, 2, 1, 1)
    val now = IntArray(7); g.b.forEach { if (it != 0 && (it > 0) == (color > 0)) now[abs(it)]++ }
    val lost = (5 downTo 1).flatMap { t -> List((start[t] - now[t]).coerceAtLeast(0)) { t } }
    Row(Modifier.height(24.dp)) { lost.forEach { Text(GLYPH[it], fontSize = 18.sp, style = pieceStyle(color)) } }
}
