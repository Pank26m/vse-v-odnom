package com.pank.vsevodnom.ui.games

import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
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
    Game("blocks", "Блоки", "падающие фигуры: собирай линии", "▦", Tints.Sky),
    Game("hockey", "Аэрохоккей", "неон, умный бот, до 7 голов", "◉", Tints.Rose),
    Game("knives", "Ножи", "метни в мишень, не задень ножи, боссы", "🗡", Tints.Peach),
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
        "blocks" -> BlocksScreen(close)
        "hockey" -> AirHockeyScreen(close)
        "knives" -> KnivesScreen(close)
        else -> {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var ver by remember { mutableIntStateOf(0) }
            var edit by remember { mutableStateOf(false) }
            if (edit) { BackHandler { edit = false; ver++ }; GamesEditor { edit = false; ver++ }; return }
            val list = remember(ver) { GameMenu.ordered(ctx, GAMES) }
            ScreenScaffold("Игры", onBack, actions = {
                com.pank.vsevodnom.ui.common.RoundIconButton(androidx.compose.material.icons.Icons.Rounded.Edit, "Настроить меню", { edit = true })
            }) {
                LazyVerticalGrid(
                    GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.key }) { g ->
                        val cover = GameMenu.cover(ctx, g.key)
                        // все плитки одного размера: высота фиксирована, подпись — до 2 строк
                        AppCard(Modifier.fillMaxWidth().height(172.dp), onClick = { open = g.key }) {
                            if (cover != null) com.pank.vsevodnom.ui.common.AppImage(cover, Modifier.fillMaxWidth().height(86.dp), maxPx = 500, radius = 12.dp)
                            else Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(g.tint.bg), contentAlignment = Alignment.Center) {
                                Text(g.glyph, fontSize = 30.sp, color = g.tint.ink)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(GameMenu.name(ctx, g), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(g.sub, style = MaterialTheme.typography.bodySmall, maxLines = if (cover != null) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Меню игр: свой порядок, свои названия, свои обложки (prefs games, файлы files/game_covers). */
private object GameMenu {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("games_menu", android.content.Context.MODE_PRIVATE)
    fun ordered(ctx: android.content.Context, all: List<Game>): List<Game> {
        val order = p(ctx).getString("order", "")!!.split(',').filter { it.isNotBlank() }
        return all.sortedBy { g -> order.indexOf(g.key).let { if (it < 0) 1000 + all.indexOf(g) else it } }
    }
    fun setOrder(ctx: android.content.Context, l: List<Game>) = p(ctx).edit().putString("order", l.joinToString(",") { it.key }).apply()
    fun name(ctx: android.content.Context, g: Game) = p(ctx).getString("name_${g.key}", null)?.takeIf { it.isNotBlank() } ?: g.title
    fun setName(ctx: android.content.Context, g: Game, n: String) = p(ctx).edit().putString("name_${g.key}", n.trim().takeIf { it != g.title }).apply()
    fun cover(ctx: android.content.Context, key: String): String? = p(ctx).getString("cover_$key", null)?.let { java.io.File(dir(ctx), it) }?.takeIf { it.exists() }?.absolutePath
    private fun dir(ctx: android.content.Context) = java.io.File(ctx.filesDir, "game_covers").apply { mkdirs() }
    /** Своя обложка: уменьшаем до 720 px и сохраняем под новым именем (иначе кэш картинок покажет старую). */
    fun setCover(ctx: android.content.Context, key: String, uri: android.net.Uri): Boolean = runCatching {
        val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return false
        val k = 720f / maxOf(src.width, src.height)
        val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        val name = "${key}_${System.currentTimeMillis()}.jpg"
        java.io.File(dir(ctx), name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        removeCover(ctx, key)
        p(ctx).edit().putString("cover_$key", name).apply(); true
    }.getOrDefault(false)
    fun removeCover(ctx: android.content.Context, key: String) {
        p(ctx).getString("cover_$key", null)?.let { java.io.File(dir(ctx), it).delete() }
        p(ctx).edit().remove("cover_$key").apply()
    }
}

/** Настройка меню игр: стрелки — порядок, нажатие на название — переименовать, картинка — своя обложка. */
@Composable
private fun GamesEditor(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list = remember { mutableStateListOf<Game>().apply { addAll(GameMenu.ordered(ctx, GAMES)) } }
    var ver by remember { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<Game?>(null) }
    var coverFor by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        val k = coverFor; coverFor = null
        if (uri != null && k != null) com.pank.vsevodnom.data.AppScope.io.launch { GameMenu.setCover(ctx, k, uri); ver++ }
    }
    fun move(i: Int, d: Int) { val j = i + d; if (j !in list.indices) return; val g = list.removeAt(i); list.add(j, g); GameMenu.setOrder(ctx, list) }
    ScreenScaffold("Меню игр", onDone) {
        androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Стрелки — порядок, название — переименовать, квадрат слева — своя картинка (долгое нажатие — убрать).", style = MaterialTheme.typography.bodySmall) }
            itemsIndexedGames(list) { i, g ->
                key(ver) {
                    val cover = GameMenu.cover(ctx, g.key)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(com.pank.vsevodnom.ui.theme.Pal.Card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(g.tint.bg)
                            .pointerInput(g.key) { detectTapGestures(onTap = { coverFor = g.key; picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onLongPress = { GameMenu.removeCover(ctx, g.key); ver++ }) }, contentAlignment = Alignment.Center) {
                            if (cover != null) com.pank.vsevodnom.ui.common.AppImage(cover, Modifier.fillMaxSize(), maxPx = 300, radius = 10.dp)
                            else Text(g.glyph, fontSize = 26.sp, color = g.tint.ink)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { rename = g }) {
                            Text(GameMenu.name(ctx, g), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("нажми, чтобы переименовать", style = MaterialTheme.typography.labelSmall, color = com.pank.vsevodnom.ui.theme.Pal.Muted)
                        }
                        androidx.compose.material3.IconButton({ move(i, -1) }, enabled = i > 0) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, "Выше") }
                        androidx.compose.material3.IconButton({ move(i, 1) }, enabled = i < list.lastIndex) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, "Ниже") }
                    }
                }
            }
        }
    }
    rename?.let { g ->
        var t by remember(g) { mutableStateOf(GameMenu.name(ctx, g)) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { rename = null }, title = { Text("Название игры") },
            text = { com.pank.vsevodnom.ui.common.AppTextField(t, { t = it }, g.title) },
            confirmButton = { androidx.compose.material3.TextButton({ GameMenu.setName(ctx, g, t.ifBlank { g.title }); rename = null; ver++ }) { Text("Сохранить") } },
            dismissButton = { androidx.compose.material3.TextButton({ GameMenu.setName(ctx, g, g.title); rename = null; ver++ }) { Text("Как было") } })
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedGames(list: List<Game>, crossinline content: @Composable (Int, Game) -> Unit) {
    list.forEachIndexed { i, g -> item(key = g.key) { content(i, g) } }
}
