package com.pank.vsevodnom.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints

private data class Game(val key: String, val title: String, val sub: String, val glyph: String, val tint: Tint)

private val GAMES = listOf(
    Game("chess", "Шахматы", "бот с часами, 500 задач, разбор партий, темы", "♞", Tints.Lilac),
    Game("durak", "Дурак", "подкидной и переводной, 1–2 бота", "♠", Tints.Rose),
    Game("sudoku", "Судоку", "4 сложности, вплоть до «Божества»", "9", Tints.Sky),
    Game("mines", "Сапёр", "поле 16×27", "✱", Tints.Mint),
    Game("merge", "2048", "сдвигай плитки, собери 2048", "²", Tints.Lilac),
    Game("snake", "Змейка", "гладкая змейка с глазками", "🐍", Tints.Mint),
    Game("maze", "Pac-Man", "вака-вака! ешь точки, убегай от привидений", "🟡", Tints.Sky),
    Game("cells", "Клетки", "огромное поле, островки, джойстик где угодно", "🫧", Tints.Rose),
    Game("ttt", "Крестики-нолики", "против бота, кто кем — случайно", "✕", Tints.Sky),
    Game("territory", "Территории", "свой редактор карты, 1–3 бота, шансы как в оригинале", "⬢", Tints.Lilac),
    Game("c4", "Четыре в ряд", "сильный бот, кто первый — случайно", "●", Tints.Rose),
    Game("sea", "Морской бой", "свой флот, корабли ручкой, взрывы", "⚓", Tints.Sky),
    Game("random", "Рандом", "монетка, колесо, шар, бутылочка, кости", "🎲", Tints.Butter),
)

@Composable
fun GamesScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { open = null }
    if (open != null) BackHandler { close() }
    when (open) {
        "mines" -> MinesScreen(close)
        "sudoku" -> SudokuScreen(close)
        "chess" -> ChessScreen(close)
        "durak" -> DurakScreen(close)
        "random" -> com.pank.vsevodnom.ui.random.RandomScreen(close)
        "merge" -> MergeScreen(close)
        "snake" -> SnakeScreen(close)
        "maze" -> MazeScreen(close)
        "cells" -> CellsScreen(close)
        "ttt" -> TicTacToeScreen(close)
        "territory" -> TerritoryScreen(close)
        "c4" -> ConnectFourScreen(close)
        "sea" -> BattleshipScreen(close)
        else -> ScreenScaffold("Игры", onBack) {
            LazyVerticalGrid(
                GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(GAMES) { g ->
                    // все плитки одного размера: высота фиксирована, подпись — до 2 строк
                    AppCard(Modifier.fillMaxWidth().height(172.dp), onClick = { open = g.key }) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(g.tint.bg), contentAlignment = Alignment.Center) {
                            Text(g.glyph, fontSize = 30.sp, color = g.tint.ink)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(g.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
