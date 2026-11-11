package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp

// ======================= Оформление: доски, фигуры, фон, звуки =======================

/** Доска: цвета клеток или картинка-текстура (assets/chess/boards). */
private data class BoardTheme(val key: String, val name: String, val light: Color, val dark: Color, val texture: String? = null)

private val BOARDS = listOf(
    BoardTheme("c:green", "Зелёная", Color(0xFFEEEED2), Color(0xFF769656)),
    BoardTheme("c:brown", "Коричневая", Color(0xFFF0D9B5), Color(0xFFB58863)),
    BoardTheme("c:blue", "Синяя", Color(0xFFDEE3E6), Color(0xFF8CA2AD)),
    BoardTheme("c:lilac", "Сиреневая", Color(0xFFF3EFE7), Color(0xFFC9BEE8)),
    BoardTheme("c:grey", "Серая", Color(0xFFDCDCDC), Color(0xFF9E9E9E)),
    BoardTheme("c:ice", "Ледяная", Color(0xFFEAF2F8), Color(0xFF8DB4D6)),
    BoardTheme("c:rose", "Розовая", Color(0xFFF7E6EA), Color(0xFFD99AA8)),
    BoardTheme("c:sand", "Песочная", Color(0xFFF5E9D3), Color(0xFFD1A86E)),
    BoardTheme("c:night", "Ночная", Color(0xFF8E9AAF), Color(0xFF3E4A61)),
    BoardTheme("c:mint", "Мятная", Color(0xFFF0F7EE), Color(0xFF7FB79C)),
) + listOf(
    "wood" to "Дерево", "wood2" to "Дерево 2", "wood3" to "Дерево 3", "wood4" to "Дерево 4", "maple" to "Клён", "maple2" to "Клён 2",
    "marble" to "Мрамор", "blue-marble" to "Синий мрамор", "green-plastic" to "Пластик", "metal" to "Металл", "olive" to "Олива", "grey" to "Графит",
    "leather" to "Кожа", "canvas2" to "Холст", "blue2" to "Синяя 2", "blue3" to "Синяя 3", "pink-pyramid" to "Пирамиды", "purple-diag" to "Диагональ",
).map { (k, n) -> BoardTheme("t:$k", n, Color.White, Color.Gray, k) }

/** Наборы фигур (assets/chess/pieces) + свои глифы. */
private val PIECE_SETS = listOf("cburnett" to "Классика", "merida" to "Мерида", "california" to "Калифорния", "cardinal" to "Кардинал", "maestro" to "Маэстро",
    "staunty" to "Стаунти", "tatiana" to "Татьяна", "gioco" to "Джоко", "fresca" to "Фреска", "chessnut" to "Каштан", "fantasy" to "Фэнтези",
    "spatial" to "Объёмные", "celtic" to "Кельтские", "pixel" to "Пиксели", "glyph" to "Значки")

/** Фон экрана шахмат. */
private val BACKS = listOf("Светлый" to Color(0xFFF6F4EF), "Тёмный" to Color(0xFF2B2926), "Графит" to Color(0xFF1F2328), "Сукно" to Color(0xFF1E4D3A),
    "Ночь" to Color(0xFF1B2440), "Песок" to Color(0xFFEFE6D5), "Сирень" to Color(0xFFEDE8F7))

/** Звуки: «Дерево» — свой синтез классического стука; остальные — наборы lichess (assets/chess/sounds). */
private val SOUNDS = listOf("wood" to "Дерево (классика)", "sfx" to "Эффекты", "piano" to "Пианино", "nes" to "8-бит", "futuristic" to "Будущее", "lisp" to "Шёпот", "off" to "Без звука")

private object ChessLook {
    var board by mutableStateOf("c:green")
    var pieces by mutableStateOf("cburnett")
    var bg by mutableIntStateOf(0)
    var sound by mutableStateOf("wood")
    var coords by mutableStateOf(true)
    private var loaded = false
    fun load(ctx: Context) {
        if (loaded) return; loaded = true
        val p = ctx.getSharedPreferences("chess", Context.MODE_PRIVATE)
        board = p.getString("board", board)!!; pieces = p.getString("pieces", pieces)!!; bg = p.getInt("bg", 0); sound = p.getString("sound", sound)!!; coords = p.getBoolean("coords", true)
    }
    fun save(ctx: Context) = ctx.getSharedPreferences("chess", Context.MODE_PRIVATE).edit()
        .putString("board", board).putString("pieces", pieces).putInt("bg", bg).putString("sound", sound).putBoolean("coords", coords).apply()
    val bgColor get() = BACKS.getOrElse(bg) { BACKS[0] }.second
    val dark get() = bgColor.red + bgColor.green + bgColor.blue < 1.5f
    val ink get() = if (dark) Color(0xFFEDEBE6) else Pal.Ink
    val muted get() = if (dark) Color(0xFFA9A59C) else Pal.Muted
    val card get() = if (dark) Color(0x22FFFFFF) else Pal.Card
}

/** Картинки фигур и досок (кэш). */
private object ChessArt {
    private val cache = HashMap<String, ImageBitmap?>()
    private fun img(ctx: Context, path: String): ImageBitmap? = cache.getOrPut(path) {
        runCatching { ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
    fun piece(ctx: Context, set: String, p: Int): ImageBitmap? {
        if (set == "glyph" || p == 0) return null
        val c = if (p > 0) "w" else "b"; val t = "?PNBRQK"[abs(p)]
        return img(ctx, "chess/pieces/$set/$c$t.webp")
    }
    fun board(ctx: Context, tex: String): ImageBitmap? = img(ctx, "chess/boards/$tex.webp")
}

private enum class Snd(val file: String) { MOVE("Move"), CAPTURE("Capture"), CHECK("Check"), MATE("Checkmate"), WIN("Victory"), LOSE("Defeat"), DRAW("Draw"), LOW("LowTime") }

/** Звуки шахмат: синтез «дерева» или mp3 из наборов. */
private object ChessSound {
    private var pool: android.media.SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    fun prepare(ctx: Context, set: String) {
        if (set == "wood" || set == "off") return
        val sp = pool ?: android.media.SoundPool.Builder().setMaxStreams(4).setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_GAME).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            .also { p -> pool = p; p.setOnLoadCompleteListener { _, id, st -> if (st == 0) ready += id } }
        Snd.entries.forEach { s ->
            val key = "$set/${s.file}"
            if (key !in ids) runCatching { ctx.assets.openFd("chess/sounds/$key.mp3").use { ids[key] = sp.load(it, 1) } }
        }
    }
    fun play(ctx: Context, s: Snd, set: String = ChessLook.sound) {
        when (set) {
            "off" -> return
            "wood" -> com.pank.vsevodnom.ui.random.Sfx.playPcm("chess", wood(s))
            else -> {
                prepare(ctx, set)
                val id = ids["$set/${s.file}"] ?: ids["$set/${Snd.MOVE.file}"] ?: return
                if (id in ready) pool?.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
    }
    private val woodCache = HashMap<Snd, ShortArray>()
    /** Стук деревянной фигуры по доске: короткий щелчок с «телом»; взятие — двойной, шах — со звонком. */
    private fun wood(s: Snd): ShortArray = woodCache.getOrPut(s) {
        val sr = com.pank.vsevodnom.ui.random.Sfx.SR
        val len = when (s) { Snd.CAPTURE -> 0.16; Snd.CHECK, Snd.MATE -> 0.32; Snd.WIN, Snd.LOSE, Snd.DRAW -> 0.6; else -> 0.09 }
        val n = (len * sr).toInt(); val out = FloatArray(n)
        val rnd = java.util.Random(7)
        fun knock(at: Double, f: Double, amp: Double) {
            val s0 = (at * sr).toInt()
            for (k in 0 until (0.08 * sr).toInt()) {
                val i = s0 + k; if (i >= n) break
                val t = k.toDouble() / sr
                val env = exp(-t * 70)
                val body = Math.sin(2 * Math.PI * f * t) * 0.6 + Math.sin(2 * Math.PI * f * 2.7 * t) * 0.25
                val click = if (k < 90) (rnd.nextDouble() * 2 - 1) * exp(-k / 18.0) else 0.0
                out[i] += ((body * env + click * 0.8) * amp).toFloat()
            }
        }
        when (s) {
            Snd.CAPTURE -> { knock(0.0, 420.0, 0.8); knock(0.055, 300.0, 1.0) }
            Snd.CHECK, Snd.MATE -> { knock(0.0, 520.0, 0.9); val f = if (s == Snd.MATE) 660.0 else 880.0
                for (k in (0.03 * sr).toInt() until n) { val t = k.toDouble() / sr - 0.03; out[k] += (Math.sin(2 * Math.PI * f * t) * exp(-t * 9) * 0.25).toFloat() } }
            Snd.WIN, Snd.LOSE, Snd.DRAW -> {
                val notes = when (s) { Snd.WIN -> listOf(523.0, 659.0, 784.0); Snd.LOSE -> listOf(392.0, 330.0, 262.0); else -> listOf(440.0, 440.0, 440.0) }
                notes.forEachIndexed { j, f -> val s0 = (j * 0.15 * sr).toInt(); for (k in 0 until (0.3 * sr).toInt()) { val i = s0 + k; if (i >= n) break; val t = k.toDouble() / sr
                    out[i] += (Math.sin(2 * Math.PI * f * t) * exp(-t * 6) * 0.3).toFloat() } }
            }
            else -> knock(0.0, 460.0, 1.0)
        }
        val mx = out.maxOf { abs(it) }.coerceAtLeast(0.01f)
        ShortArray(n) { (out[it] / mx * 0.7f * Short.MAX_VALUE).toInt().toShort() }
    }
}

// ======================= Хранение: партии, задачи =======================

private data class SavedGame(val at: Long, val moves: List<String>, val white: Boolean, val result: String, val level: Int, val minutes: Int)

private object ChessStore {
    private fun f(ctx: Context) = java.io.File(ctx.filesDir, "chess_games.json")
    fun games(ctx: Context): List<SavedGame> = runCatching {
        val a = JSONArray(f(ctx).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            SavedGame(o.getLong("at"), o.getString("m").split(' ').filter { it.isNotBlank() }, o.getBoolean("w"), o.optString("r"), o.optInt("l"), o.optInt("min"))
        }
    }.getOrDefault(emptyList())
    fun add(ctx: Context, g: SavedGame) = runCatching {
        val l = (listOf(g) + games(ctx)).take(60)
        f(ctx).writeText(JSONArray().apply { l.forEach { put(JSONObject().put("at", it.at).put("m", it.moves.joinToString(" ")).put("w", it.white).put("r", it.result).put("l", it.level).put("min", it.minutes)) } }.toString())
    }
    private fun p(ctx: Context) = ctx.getSharedPreferences("chess_puzzles", Context.MODE_PRIVATE)
    fun solved(ctx: Context): Set<String> = p(ctx).getStringSet("solved", emptySet())!!
    fun failed(ctx: Context): Set<String> = p(ctx).getStringSet("failed", emptySet())!!
    fun rating(ctx: Context) = p(ctx).getInt("rating", 1000)
    fun mark(ctx: Context, id: String, ok: Boolean, pr: Int) {
        val pp = p(ctx); val s = solved(ctx).toMutableSet(); val fl = failed(ctx).toMutableSet()
        val first = id !in s && id !in fl
        if (ok && id !in fl) s += id else if (!ok) { fl += id }
        // рейтинг задач: как Эло, только за первую попытку
        var r = rating(ctx)
        if (first) { val e = 1.0 / (1 + Math.pow(10.0, (pr - r) / 400.0)); r = (r + 32 * ((if (ok) 1.0 else 0.0) - e)).toInt().coerceIn(400, 3000) }
        pp.edit().putStringSet("solved", s).putStringSet("failed", fl).putInt("rating", r).apply()
    }
}

private data class Puzzle(val id: String, val fen: String, val moves: List<String>, val rating: Int, val themes: List<String>)
private fun loadPuzzles(ctx: Context): List<Puzzle> = runCatching {
    ctx.assets.open("chess/puzzles.csv").bufferedReader().readLines().filter { it.isNotBlank() }.map { l ->
        val p = l.split('|'); Puzzle(p[0], p[1], p[2].split(' '), p[3].toInt(), p.getOrElse(4) { "" }.split(' ').filter { it.isNotBlank() })
    }
}.getOrDefault(emptyList())

private val THEME_RU = mapOf("mate" to "мат", "mateIn1" to "мат в 1 ход", "mateIn2" to "мат в 2 хода", "mateIn3" to "мат в 3 хода", "mateIn4" to "мат в 4 хода",
    "fork" to "вилка", "pin" to "связка", "skewer" to "сквозной удар", "sacrifice" to "жертва", "hangingPiece" to "незащищённая фигура", "discoveredAttack" to "вскрытое нападение",
    "deflection" to "отвлечение", "attraction" to "завлечение", "promotion" to "превращение", "advancedPawn" to "проходная пешка", "kingsideAttack" to "атака на короля",
    "backRankMate" to "мат по последней горизонтали", "smotheredMate" to "спёртый мат", "quietMove" to "тихий ход", "defensiveMove" to "защита", "endgame" to "эндшпиль",
    "middlegame" to "миттельшпиль", "opening" to "дебют", "crushing" to "решающий перевес", "advantage" to "перевес", "trappedPiece" to "пойманная фигура",
    "clearance" to "освобождение линии", "intermezzo" to "промежуточный ход", "zugzwang" to "цугцванг", "exposedKing" to "открытый король", "doubleCheck" to "двойной шах",
    "discoveredCheck" to "вскрытый шах", "capturingDefender" to "уничтожение защитника", "interference" to "перекрытие", "rookEndgame" to "ладейный эндшпиль", "pawnEndgame" to "пешечный эндшпиль")

// ======================= Экран =======================

private val LEVEL_NAMES = listOf("Новичок", "Лёгкий", "Средний", "Сильный", "Максимум")
private data class PlaySetup(val level: Int, val minutes: Int, val white: Boolean)

@Composable
fun ChessScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChessLook.load(ctx); ChessSound.prepare(ctx, ChessLook.sound) }
    var page by remember { mutableStateOf("home") }
    var setup by remember { mutableStateOf<PlaySetup?>(null) }
    var puzzleIdx by remember { mutableIntStateOf(-1) }
    var review by remember { mutableStateOf<SavedGame?>(null) }
    var askSetup by remember { mutableStateOf(false) }
    if (page != "home") BackHandler { page = when (page) { "puzzle" -> "puzzles"; "review" -> "games"; else -> "home" } }
    Box(Modifier.fillMaxSize().background(ChessLook.bgColor)) {
        when (page) {
            "play" -> setup?.let { s -> PlayPage(s, onExit = { page = "home" }, onReview = { g -> review = g; page = "review" }) }
            "puzzles" -> PuzzlesPage(onBack = { page = "home" }) { i -> puzzleIdx = i; page = "puzzle" }
            "puzzle" -> PuzzlePage(puzzleIdx, onBack = { page = "puzzles" }) { puzzleIdx = it }
            "themes" -> ThemesPage { page = "home" }
            "games" -> GamesPage(onBack = { page = "home" }) { review = it; page = "review" }
            "review" -> review?.let { g -> ReviewPage(g) { page = "games" } }
            else -> ChessHome(onBack, onPlay = { askSetup = true }, onPuzzles = { page = "puzzles" }, onThemes = { page = "themes" }, onGames = { page = "games" })
        }
    }
    if (askSetup) PlaySetupDialog(onDismiss = { askSetup = false }) { s -> askSetup = false; setup = s; page = "play" }
}

@Composable
private fun ChessTop(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = ChessLook.ink) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = ChessLook.ink, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun ChessHome(onBack: () -> Unit, onPlay: () -> Unit, onPuzzles: () -> Unit, onThemes: () -> Unit, onGames: () -> Unit) {
    val ctx = LocalContext.current
    val solved = remember { ChessStore.solved(ctx).size }
    val games = remember { ChessStore.games(ctx) }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Шахматы", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // превью доски в выбранной теме
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) { ChessBoard(remember { Chess() }, flip = false, onTap = null) }
            @Composable fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, bg: Color, fg: Color, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(fg.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f))
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = fg.copy(alpha = 0.6f))
                }
            }
            Tile(Icons.Rounded.SmartToy, "Игра с ботом", "время от 1 до 60 минут, 5 уровней сложности", Color(0xFF7FA650), Color.White, onPlay)
            Tile(Icons.Rounded.Extension, "Задачи", "решено $solved из 500 · рейтинг ${ChessStore.rating(ctx)}", Color(0xFFDCE7FB), Color(0xFF3E63B8), onPuzzles)
            Tile(Icons.Rounded.QueryStats, "Анализ партий", if (games.isEmpty()) "сыгранные партии появятся здесь" else "партий: ${games.size} · точность, ошибки, лучшие ходы", Color(0xFFFBE7D0), Color(0xFFB65E2E), onGames)
            Tile(Icons.Rounded.Palette, "Темы", "доски, фигуры, фон, звуки", Color(0xFFEDE8F7), Color(0xFF6A55A8), onThemes)
        }
    }
}

@Composable
private fun PlaySetupDialog(onDismiss: () -> Unit, onStart: (PlaySetup) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("chess", Context.MODE_PRIVATE) }
    var level by remember { mutableIntStateOf(p.getInt("level", 2)) }
    var minutes by remember { mutableFloatStateOf(p.getInt("minutes", 10).toFloat()) }
    var color by remember { mutableIntStateOf(p.getInt("color", 2)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Игра с ботом") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Время на партию: ${minutes.toInt()} мин", style = MaterialTheme.typography.titleSmall)
            Slider(minutes, { minutes = it }, valueRange = 1f..60f, steps = 58)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 5, 10, 30, 60).forEach { m -> Chip("$m", minutes.toInt() == m, { minutes = m.toFloat() }) } }
            Text("Сложность", style = MaterialTheme.typography.titleSmall)
            LEVEL_NAMES.chunked(3).forEachIndexed { r, row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEachIndexed { c, n -> Chip(n, level == r * 3 + c, { level = r * 3 + c }) } } }
            Text("Играю за", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Белых", "Чёрных", "Случайно").forEachIndexed { i, t -> Chip(t, color == i, { color = i }) } }
        }
    }, confirmButton = { TextButton({
        p.edit().putInt("level", level).putInt("minutes", minutes.toInt()).putInt("color", color).apply()
        onStart(PlaySetup(level, minutes.toInt(), if (color == 2) kotlin.random.Random.nextBoolean() else color == 0))
    }) { Text("Играть") } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

private fun clock(ms: Long): String { val s = (ms.coerceAtLeast(0) + 999) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Звук хода по позиции после него. */
private fun soundFor(g: Chess, captured: Boolean): Snd = when { g.inCheck() && g.legal().isEmpty() -> Snd.MATE; g.inCheck() -> Snd.CHECK; captured -> Snd.CAPTURE; else -> Snd.MOVE }

@Composable
private fun PlayPage(s: PlaySetup, onExit: () -> Unit, onReview: (SavedGame) -> Unit) {
    val ctx = LocalContext.current
    val human = if (s.white) 1 else -1
    var gen by remember { mutableIntStateOf(0) }
    val game = remember(gen) { Chess() }
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember(gen) { mutableIntStateOf(-1) }
    var thinking by remember { mutableStateOf(false) }
    var promoFor by remember { mutableStateOf<List<Int>?>(null) }
    val uci = remember(gen) { mutableStateListOf<String>() }
    val sans = remember(gen) { mutableStateListOf<String>() }
    var timeW by remember(gen) { mutableLongStateOf(s.minutes * 60_000L) }
    var timeB by remember(gen) { mutableLongStateOf(s.minutes * 60_000L) }
    var flag by remember(gen) { mutableStateOf<String?>(null) }
    var resigned by remember(gen) { mutableStateOf(false) }
    var saved by remember(gen) { mutableStateOf<SavedGame?>(null) }
    val legal = remember(ver, gen) { game.legal() }
    val result = remember(ver, gen, flag, resigned) {
        flag ?: (if (resigned) "Ты сдался" else null) ?: game.result()
    }
    var lowWarned by remember(gen) { mutableStateOf(false) }

    fun play(m: Int) {
        val cap = game.b[Chess.toSq(m)] != 0 || Chess.flags(m) and Chess.F_EP != 0
        sans += game.san(m); uci += game.uci(m); game.make(m); sel = -1; ver++
        ChessSound.play(ctx, soundFor(game, cap))
    }
    // часы
    LaunchedEffect(gen, result) {
        var last = System.currentTimeMillis()
        while (result == null) {
            delay(100); val now = System.currentTimeMillis(); val dt = now - last; last = now
            if (game.moveCount == 0 && game.side == human) continue   // часы идут с первого хода
            if (game.side > 0) timeW -= dt else timeB -= dt
            val mine = if (human > 0) timeW else timeB
            if (!lowWarned && mine in 1..10_000 && s.minutes >= 1) { lowWarned = true; ChessSound.play(ctx, Snd.LOW) }
            if (timeW <= 0) { flag = if (human > 0) "Время вышло — победили чёрные" else "У бота кончилось время — победа!"; break }
            if (timeB <= 0) { flag = if (human < 0) "Время вышло — победили белые" else "У бота кончилось время — победа!"; break }
        }
    }
    LaunchedEffect(ver, gen) {
        if (result == null && game.side != human && !thinking) {
            thinking = true
            delay(500)
            val snapshot = game.copy()
            val left = if (human > 0) timeB else timeW
            val m = withContext(Dispatchers.Default) { snapshot.bestMove(s.level + 1, capMs = left / 25) }
            if (m != null && flag == null && !resigned) play(m)
            thinking = false
        }
    }
    // конец партии: звук и запись для анализа
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        val won = r.contains("победа", true) || (human > 0 && r.contains("Победили белые")) || (human < 0 && r.contains("Победили чёрные"))
        val draw = r.contains("ничья", true) || r.contains("Пат")
        delay(300); ChessSound.play(ctx, if (draw) Snd.DRAW else if (won) Snd.WIN else Snd.LOSE)
        if (uci.size >= 2) { val g = SavedGame(System.currentTimeMillis(), uci.toList(), s.white, r, s.level, s.minutes); ChessStore.add(ctx, g); saved = g }
    }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Бот · ${LEVEL_NAMES[s.level]} · ${s.minutes} мин", onExit)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerBar("Бот", if (human > 0) timeB else timeW, active = result == null && game.side != human, thinking)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                ChessBoard(game, flip = human < 0, sel = sel, legal = legal, ver = ver, onTap = if (result == null && game.side == human && !thinking) { sq ->
                    val p = game.b[sq]
                    if (sel >= 0) {
                        val c = legal.filter { Chess.from(it) == sel && Chess.toSq(it) == sq }
                        when { c.size > 1 -> { promoFor = c; return@ChessBoard }; c.size == 1 -> { play(c[0]); return@ChessBoard } }
                    }
                    sel = if (p != 0 && (p > 0) == (human > 0)) sq else -1
                } else null)
            }
            PlayerBar("Ты", if (human > 0) timeW else timeB, active = result == null && game.side == human, false)
            result?.let { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ChessLook.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r, style = MaterialTheme.typography.titleMedium, color = ChessLook.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        saved?.let { g -> PillButton("Анализ партии", { onReview(g) }, Modifier.weight(1f), bg = Color(0xFF7FA650)) }
                        PillButton("Ещё партия", { gen++ }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                    }
                }
            }
            if (result == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Ход назад", {
                    var n = if (game.side == human) 2 else 1
                    while (n-- > 0 && game.moveCount > 0) { game.unmake(); sans.removeLastOrNull(); uci.removeLastOrNull() }
                    sel = -1; ver++
                }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink, enabled = !thinking && game.moveCount > 0)
                PillButton("Сдаться", { resigned = true }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk, enabled = game.moveCount > 1)
            }
            if (sans.isNotEmpty()) Text(sans.chunked(2).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString("   "),
                style = MaterialTheme.typography.bodyMedium, color = ChessLook.muted, modifier = Modifier.padding(4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
    promoFor?.let { c -> PromoDialog(c, human, { promoFor = null }) { promoFor = null; play(it) } }
}

@Composable
private fun PlayerBar(name: String, ms: Long, active: Boolean, thinking: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name + if (thinking) " · думает…" else "", style = MaterialTheme.typography.titleSmall, color = ChessLook.ink, modifier = Modifier.weight(1f))
        Text(clock(ms), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = if (active) (if (ms < 20_000) Color.White else Color(0xFF1C1B22)) else ChessLook.muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) (if (ms < 20_000) Color(0xFFD23A3A) else Color.White) else ChessLook.card).padding(12.dp, 4.dp))
    }
}

@Composable
private fun PromoDialog(cands: List<Int>, color: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, title = { Text("Превратить пешку в") }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cands.sortedByDescending { Chess.promo(it) }.forEach { m ->
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Lilac).clickable { onPick(m) }, contentAlignment = Alignment.Center) {
                    val img = ChessArt.piece(ctx, ChessLook.pieces, Chess.promo(m) * color)
                    if (img != null) androidx.compose.foundation.Image(img, null, Modifier.size(50.dp))
                    else Text(GLYPH[Chess.promo(m)], fontSize = 36.sp, color = if (color > 0) Color.White else Color.Black)
                }
            }
        }
    })
}

// ======================= Доска =======================

private val GLYPH = arrayOf("", "♟︎", "♞︎", "♝︎", "♜︎", "♛︎", "♚︎")

/** Стрелка на доске (анализ): откуда, куда, цвет. */
private data class Arrow(val from: Int, val to: Int, val color: Color)

@Composable
private fun ChessBoard(g: Chess, flip: Boolean, sel: Int = -1, legal: List<Int> = emptyList(), ver: Int = 0, arrows: List<Arrow> = emptyList(),
                       marks: Map<Int, Color> = emptyMap(), onTap: ((Int) -> Unit)?) {
    val ctx = LocalContext.current
    val theme = BOARDS.firstOrNull { it.key == ChessLook.board } ?: BOARDS[0]
    val tex = remember(theme.texture) { theme.texture?.let { ChessArt.board(ctx, it) } }
    val set = ChessLook.pieces
    val last = g.lastMove()
    val targets = if (sel >= 0) legal.filter { Chess.from(it) == sel }.map { Chess.toSq(it) }.toSet() else emptySet()
    val check = if (g.inCheck()) g.kingSq(g.side) else -1
    val anim = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(ver) { if (last != null) { anim.snapTo(0f); anim.animateTo(1f, androidx.compose.animation.core.tween(220)) } }
    val pieceImgs = remember(set) { (-6..6).associateWith { ChessArt.piece(ctx, set, it) } }
    Canvas(Modifier.fillMaxSize().then(if (onTap != null) Modifier.pointerInput(flip, onTap) {
        detectTapGestures { p ->
            val cs = size.width / 8f
            val vf = (p.x / cs).toInt().coerceIn(0, 7); val vr = (p.y / cs).toInt().coerceIn(0, 7)
            val r = if (!flip) 7 - vr else vr; val f = if (!flip) vf else 7 - vf
            onTap(r * 8 + f)
        }
    } else Modifier)) {
        @Suppress("UNUSED_EXPRESSION") ver
        val cs = size.width / 8f
        fun xy(sq: Int): Offset { val r = Chess.rank(sq); val f = Chess.file(sq); val vf = if (!flip) f else 7 - f; val vr = if (!flip) 7 - r else r; return Offset(vf * cs, vr * cs) }
        // клетки
        if (tex != null) drawImage(tex, dstSize = IntSize(size.width.toInt(), size.height.toInt()), srcSize = IntSize(tex.width, tex.height), srcOffset = IntOffset.Zero, dstOffset = IntOffset.Zero,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        else for (sq in 0 until 64) drawRect(if ((Chess.rank(sq) + Chess.file(sq)) % 2 == 1) theme.light else theme.dark, xy(sq), Size(cs + 0.5f, cs + 0.5f))
        // подсветки
        val hl = Color(0xFFF6E05E)
        if (last != null) { drawRect(hl.copy(alpha = 0.45f), xy(Chess.from(last)), Size(cs, cs)); drawRect(hl.copy(alpha = 0.55f), xy(Chess.toSq(last)), Size(cs, cs)) }
        if (sel >= 0) drawRect(hl.copy(alpha = 0.7f), xy(sel), Size(cs, cs))
        marks.forEach { (sq, c) -> drawRect(c.copy(alpha = 0.45f), xy(sq), Size(cs, cs)) }
        if (check >= 0) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xCCFF3B30), Color.Transparent), xy(check) + Offset(cs / 2, cs / 2), cs * 0.7f), cs * 0.7f, xy(check) + Offset(cs / 2, cs / 2))
        // координаты
        if (ChessLook.coords) {
            val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = cs * 0.2f; isFakeBoldText = true }
            for (i in 0 until 8) {
                val f = if (!flip) i else 7 - i; val r = if (!flip) 7 - i else i
                paint.color = (if (tex != null) Color(0xCC000000) else (if (i % 2 == 0) theme.dark else theme.light)).let { android.graphics.Color.argb(200, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
                drawContext.canvas.nativeCanvas.drawText("${'a' + f}", i * cs + cs * 0.78f, size.height - cs * 0.06f, paint)
                drawContext.canvas.nativeCanvas.drawText("${r + 1}", cs * 0.05f, i * cs + cs * 0.22f, paint)
            }
        }
        // фигуры (последний ход — с анимацией)
        for (sq in 0 until 64) {
            val p = g.b[sq]; if (p == 0) continue
            var o = xy(sq)
            if (last != null && sq == Chess.toSq(last) && anim.value < 1f) { val f0 = xy(Chess.from(last)); o = Offset(f0.x + (o.x - f0.x) * anim.value, f0.y + (o.y - f0.y) * anim.value) }
            val img = pieceImgs[p]
            if (img != null) drawImage(img, dstOffset = IntOffset((o.x + cs * 0.04f).toInt(), (o.y + cs * 0.04f).toInt()), dstSize = IntSize((cs * 0.92f).toInt(), (cs * 0.92f).toInt()),
                srcSize = IntSize(img.width, img.height), srcOffset = IntOffset.Zero, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            else {
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = cs * 0.78f; textAlign = android.graphics.Paint.Align.CENTER
                    color = if (p > 0) android.graphics.Color.WHITE else android.graphics.Color.rgb(0x2B, 0x2A, 0x33)
                    if (p > 0) setShadowLayer(cs * 0.06f, 0f, 0f, android.graphics.Color.rgb(0x2B, 0x2A, 0x33)) }
                drawContext.canvas.nativeCanvas.drawText(GLYPH[abs(p)], o.x + cs / 2, o.y + cs * 0.8f, paint)
            }
        }
        // куда можно пойти
        targets.forEach { t -> val c = xy(t) + Offset(cs / 2, cs / 2)
            if (g.b[t] != 0) drawCircle(Color(0x33000000), cs * 0.46f, c, style = Stroke(cs * 0.08f)) else drawCircle(Color(0x33000000), cs * 0.16f, c) }
        // стрелки анализа
        arrows.forEach { a -> drawArrow(xy(a.from) + Offset(cs / 2, cs / 2), xy(a.to) + Offset(cs / 2, cs / 2), cs, a.color) }
    }
}

private fun DrawScope.drawArrow(a: Offset, b: Offset, cs: Float, c: Color) {
    val d = b - a; val len = d.getDistance(); if (len < 1f) return
    val u = Offset(d.x / len, d.y / len); val n = Offset(-u.y, u.x)
    val head = cs * 0.38f; val w = cs * 0.16f
    val end = b - u * head
    drawLine(c.copy(alpha = 0.8f), a + u * cs * 0.15f, end, w, StrokeCap.Round)
    drawPath(Path().apply { moveTo(b.x, b.y); lineTo(end.x + n.x * head * 0.6f, end.y + n.y * head * 0.6f); lineTo(end.x - n.x * head * 0.6f, end.y - n.y * head * 0.6f); close() }, c.copy(alpha = 0.85f))
}

// ======================= Задачи =======================

@Composable
private fun PuzzlesPage(onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val list = remember { loadPuzzles(ctx) }
    val solved = remember { ChessStore.solved(ctx) }; val failed = remember { ChessStore.failed(ctx) }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Задачи · ${solved.size}/${list.size}", onBack)
        val next = list.indexOfFirst { it.id !in solved && it.id !in failed }.coerceAtLeast(0)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Рейтинг задач: ${ChessStore.rating(ctx)}", style = MaterialTheme.typography.titleSmall, color = ChessLook.ink, modifier = Modifier.weight(1f))
            PillButton("Следующая", { onOpen(next) }, bg = Color(0xFF7FA650))
        }
        LazyVerticalGrid(GridCells.Fixed(5), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list) { i, p ->
                val st = when (p.id) { in solved -> 1; in failed -> 2; else -> 0 }
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(when (st) { 1 -> Color(0xFF7FA650); 2 -> Color(0xFFD9726B); else -> ChessLook.card }).clickable { onOpen(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = if (st != 0) Color.White else ChessLook.ink)
                    Text("${p.rating}", style = MaterialTheme.typography.labelSmall, color = if (st != 0) Color.White.copy(alpha = 0.85f) else ChessLook.muted)
                }
            }
        }
    }
}

@Composable
private fun PuzzlePage(idx: Int, onBack: () -> Unit, onNext: (Int) -> Unit) {
    val ctx = LocalContext.current
    val list = remember { loadPuzzles(ctx) }
    val pz = list.getOrNull(idx) ?: run { onBack(); return }
    val game = remember(idx) { Chess().also { it.loadFen(pz.fen) } }
    val me = remember(idx) { -game.side }   // первый ход — соперника
    var step by remember(idx) { mutableIntStateOf(0) }   // индекс ожидаемого хода в pz.moves
    var ver by remember(idx) { mutableIntStateOf(0) }
    var sel by remember(idx) { mutableIntStateOf(-1) }
    var state by remember(idx) { mutableIntStateOf(0) }   // 0 решаю, 1 решено, 2 ошибка
    var wrongOnce by remember(idx) { mutableStateOf(false) }
    var hint by remember(idx) { mutableStateOf(false) }
    var arrow by remember(idx) { mutableStateOf<Arrow?>(null) }
    var promoFor by remember { mutableStateOf<List<Int>?>(null) }
    val scope = rememberCoroutineScope()
    fun playUci(u: String) { game.fromUci(u)?.let { m -> val cap = game.b[Chess.toSq(m)] != 0; game.make(m); ver++; ChessSound.play(ctx, soundFor(game, cap)) } }
    LaunchedEffect(idx) { delay(700); playUci(pz.moves[0]); step = 1 }
    val legal = remember(ver) { game.legal() }
    fun tryMove(m: Int) {
        sel = -1; arrow = null
        val expected = pz.moves.getOrNull(step) ?: return
        game.make(m); val mate = game.inCheck() && game.legal().isEmpty(); game.unmake()
        if (game.uci(m) == expected || (mate && step == pz.moves.lastIndex)) {
            val cap = game.b[Chess.toSq(m)] != 0; game.make(m); ver++; ChessSound.play(ctx, soundFor(game, cap)); step++
            if (step >= pz.moves.size) { state = 1; ChessStore.mark(ctx, pz.id, !wrongOnce, pz.rating); ChessSound.play(ctx, Snd.WIN) }
            else scope.launch { delay(550); playUci(pz.moves[step]); step++ }
        } else {
            state = 2; if (!wrongOnce) { wrongOnce = true; ChessStore.mark(ctx, pz.id, false, pz.rating) }
            ChessSound.play(ctx, Snd.LOSE)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Задача ${idx + 1} · ${pz.rating}", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (state) { 1 -> "Решено! ✓"; 2 -> "Не тот ход — попробуй ещё"; else -> if (step == 0) "Смотри ход соперника…" else "Ход ${if (me > 0) "белых" else "чёрных"}: найди лучший" },
                style = MaterialTheme.typography.titleMedium, color = when (state) { 1 -> Color(0xFF7FA650); 2 -> Color(0xFFD9726B); else -> ChessLook.ink })
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                ChessBoard(game, flip = me < 0, sel = sel, legal = legal, ver = ver, arrows = listOfNotNull(arrow), onTap = if (state == 0 && game.side == me && step < pz.moves.size) { sq ->
                    val p = game.b[sq]
                    if (sel >= 0) {
                        val c = legal.filter { Chess.from(it) == sel && Chess.toSq(it) == sq }
                        when { c.size > 1 -> { promoFor = c; return@ChessBoard }; c.size == 1 -> { tryMove(c[0]); return@ChessBoard } }
                    }
                    sel = if (p != 0 && (p > 0) == (me > 0)) sq else -1
                } else null)
            }
            if (hint || state == 1) Text("Тема: " + pz.themes.mapNotNull { THEME_RU[it] }.distinct().joinToString(", ").ifBlank { "тактика" }, style = MaterialTheme.typography.bodyMedium, color = ChessLook.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    2 -> { PillButton("Ещё раз", { state = 0 }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                        PillButton("Решение", { pz.moves.getOrNull(step)?.let { u -> game.fromUci(u)?.let { m -> arrow = Arrow(Chess.from(m), Chess.toSq(m), Color(0xFF7FA650)) } }; state = 0 }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink) }
                    1 -> PillButton("Следующая задача", { onNext((idx + 1).coerceAtMost(list.lastIndex)) }, Modifier.weight(1f), bg = Color(0xFF7FA650))
                    else -> { PillButton("Подсказка", { hint = true; pz.moves.getOrNull(step)?.let { u -> game.fromUci(u)?.let { m -> sel = Chess.from(m) } } }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                        PillButton("Пропустить", { onNext((idx + 1).coerceAtMost(list.lastIndex)) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink) }
                }
            }
            Text("Задачи — из открытой базы lichess.org (CC0), от простых к сложным.", style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
        }
    }
    promoFor?.let { c -> PromoDialog(c, me, { promoFor = null }) { promoFor = null; tryMove(it) } }
}

// ======================= Темы =======================

@Composable
private fun ThemesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val preview = remember { Chess() }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Темы", { ChessLook.save(ctx); onBack() })
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) { ChessBoard(preview, flip = false, onTap = null) }
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp)) {
            listOf("Доска", "Фигуры", "Фон", "Звуки").forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clickable { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, style = MaterialTheme.typography.titleSmall, color = if (tab == i) ChessLook.ink else ChessLook.muted)
                    Box(Modifier.padding(top = 6.dp).height(3.dp).fillMaxWidth(0.7f).background(if (tab == i) ChessLook.ink else Color.Transparent))
                }
            }
        }
        val sel = Color(0xFF7FA650)
        when (tab) {
            0 -> LazyVerticalGrid(GridCells.Fixed(5), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(BOARDS) { _, b ->
                    val on = ChessLook.board == b.key
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).border(if (on) 3.dp else 0.dp, if (on) sel else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { ChessLook.board = b.key; ChessLook.save(ctx) }) {
                            val tex = remember(b.texture) { b.texture?.let { ChessArt.board(ctx, it) } }
                            Canvas(Modifier.fillMaxSize()) {
                                if (tex != null) drawImage(tex, srcOffset = IntOffset.Zero, srcSize = IntSize(tex.width / 4, tex.height / 4), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                                else { val h = size.width / 2; for (i in 0..1) for (j in 0..1) drawRect(if ((i + j) % 2 == 0) b.light else b.dark, Offset(i * h, j * h), Size(h, h)) }
                            }
                        }
                        Text(b.name, style = MaterialTheme.typography.labelSmall, color = ChessLook.muted, maxLines = 1)
                    }
                }
            }
            1 -> LazyVerticalGrid(GridCells.Fixed(5), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(PIECE_SETS) { _, (k, n) ->
                    val on = ChessLook.pieces == k
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(ChessLook.card).border(if (on) 3.dp else 0.dp, if (on) sel else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { ChessLook.pieces = k; ChessLook.save(ctx) }, contentAlignment = Alignment.Center) {
                            val img = remember(k) { ChessArt.piece(ctx, k, 2) }
                            if (img != null) androidx.compose.foundation.Image(img, n, Modifier.fillMaxSize(0.85f)) else Text(GLYPH[2], fontSize = 30.sp, color = ChessLook.ink)
                        }
                        Text(n, style = MaterialTheme.typography.labelSmall, color = ChessLook.muted, maxLines = 1)
                    }
                }
            }
            2 -> LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(BACKS) { i, (n, c) ->
                    val on = ChessLook.bg == i
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(c).border(if (on) 3.dp else 1.dp, if (on) sel else Color(0x22000000), RoundedCornerShape(10.dp))
                            .clickable { ChessLook.bg = i; ChessLook.save(ctx) })
                        Text(n, style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(SOUNDS) { _, (k, n) ->
                    val on = ChessLook.sound == k
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ChessLook.card).clickable { ChessLook.sound = k; ChessLook.save(ctx); ChessSound.prepare(ctx, k) }.padding(14.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) sel else ChessLook.muted)
                        Text(n, style = MaterialTheme.typography.bodyLarge, color = ChessLook.ink, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        if (k != "off") listOf(Snd.MOVE to "ход", Snd.CAPTURE to "взятие", Snd.CHECK to "шах").forEach { (s, t) ->
                            Text(t, style = MaterialTheme.typography.labelMedium, color = ChessLook.ink, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22000000))
                                .clickable { ChessSound.prepare(ctx, k); ChessSound.play(ctx, s, k) }.padding(8.dp, 6.dp))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Координаты на доске", color = ChessLook.ink, modifier = Modifier.weight(1f))
                        Switch(ChessLook.coords, { ChessLook.coords = it; ChessLook.save(ctx) })
                    }
                    Text("Фигуры, доски и звуки (кроме «Дерева») — из свободных наборов lichess.org; подробности — assets/chess/NOTICE.txt.", style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
                }
            }
        }
    }
}

// ======================= Партии и анализ =======================

@Composable
private fun GamesPage(onBack: () -> Unit, onOpen: (SavedGame) -> Unit) {
    val ctx = LocalContext.current
    val list = remember { ChessStore.games(ctx) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")) }
    Column(Modifier.fillMaxSize()) {
        ChessTop("Анализ партий", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item { Text("Сыграй партию с ботом — она появится здесь для разбора.", color = ChessLook.muted, modifier = Modifier.padding(8.dp)) }
            itemsIndexed(list) { _, g ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ChessLook.card).clickable { onOpen(g) }.padding(14.dp)) {
                    Text(g.result, style = MaterialTheme.typography.titleSmall, color = ChessLook.ink)
                    Text("${fmt.format(java.util.Date(g.at))} · ${if (g.white) "белыми" else "чёрными"} · бот ${LEVEL_NAMES.getOrElse(g.level) { "?" }} · ${g.moves.size / 2 + g.moves.size % 2} ходов",
                        style = MaterialTheme.typography.bodySmall, color = ChessLook.muted)
                }
            }
        }
    }
}

/** Оценка хода в анализе. */
private enum class Kind(val title: String, val mark: String, val color: Color) {
    BRILLIANT("Блестящий", "!!", Color(0xFF1BACA6)), GREAT("Замечательный", "!", Color(0xFF5C8BB0)), BEST("Лучший", "★", Color(0xFF7FA650)),
    EXCELLENT("Отлично", "👍", Color(0xFF96BC4B)), GOOD("Хорошо", "✓", Color(0xFF8BA27A)), INACCURACY("Неточность", "?!", Color(0xFFF7C045)),
    MISS("Упущенная возможность", "✕", Color(0xFFE6912C)), MISTAKE("Ошибка", "?", Color(0xFFE58F2A)), BLUNDER("Зевок", "??", Color(0xFFCA3431)),
}

private data class Ply(val san: String, val uci: String, val side: Int, val best: String, val bestSan: String, val before: Int, val after: Int, val kind: Kind, val note: String)

/** Вероятность выигрыша по оценке (формула lichess) и точность хода. */
private fun winPct(cp: Int) = 50 + 50 * (2 / (1 + exp(-0.00368208 * cp.coerceIn(-1500, 1500))) - 1)
private fun accOf(before: Int, after: Int): Double { val d = (winPct(before) - winPct(after)).coerceAtLeast(0.0); return (103.1668 * exp(-0.04354 * d) - 3.1669).coerceIn(0.0, 100.0) }
private val NAMES_GEN = arrayOf("", "пешку", "коня", "слона", "ладью", "ферзя", "короля")

@Composable
private fun ReviewPage(sg: SavedGame, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var plies by remember { mutableStateOf<List<Ply>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var at by remember { mutableIntStateOf(0) }    // сколько ходов показано на доске
    val human = if (sg.white) 1 else -1
    LaunchedEffect(sg) {
        plies = withContext(Dispatchers.Default) {
            val g = Chess()
            val moves = sg.moves.mapNotNull { u -> g.fromUci(u)?.also { g.make(it) } }
            while (g.moveCount > 0) g.unmake()
            // оценка каждой позиции (с точки зрения того, кто ходит) и лучший ход
            val evals = ArrayList<Pair<Int?, Int>>()
            for (i in 0..moves.size) {
                val r = g.analyse(380, 7)
                evals += r.first to r.second.coerceIn(-3000, 3000)
                if (i < moves.size) g.make(moves[i])
                progress = (i + 1f) / (moves.size + 1)
            }
            while (g.moveCount > 0) g.unmake()
            moves.mapIndexed { i, m ->
                val side = g.side
                val (bm, before) = evals[i]
                val after = -evals[i + 1].second
                val san = g.san(m); val bestSan = bm?.let { g.san(it) } ?: ""; val bestUci = bm?.let { g.uci(it) } ?: ""
                // предыдущий ход соперника был ошибкой — значит, этот ход «наказывает»
                val prevLoss = if (i > 0) evals[i - 1].second - (-before) else 0
                val matBefore = g.material(side) - g.material(-side)
                val capturedHere = g.b[Chess.toSq(m)]
                g.make(m)
                val matAfterMine = g.material(side) - g.material(-side)
                // ответ соперника — что он берёт
                val reply = evals[i + 1].first
                val replyTake = reply?.let { r -> g.b[Chess.toSq(r)].takeIf { it != 0 && (it > 0) == (side > 0) } }
                val mateNow = g.inCheck() && g.legal().isEmpty()
                g.unmake()
                val loss = (before - after).coerceAtLeast(0)
                val sacrifice = matAfterMine < matBefore - 150 && capturedHere == 0
                val kind = when {
                    bm == m && sacrifice && after > -50 -> Kind.BRILLIANT
                    bm == m && prevLoss >= 150 && abs(after) < 2500 -> Kind.GREAT
                    bm == m || loss <= 10 -> Kind.BEST
                    loss <= 35 -> Kind.EXCELLENT
                    loss <= 80 -> Kind.GOOD
                    before >= 250 && after < before - 200 && after > -100 -> Kind.MISS
                    loss <= 160 -> Kind.INACCURACY
                    loss <= 320 -> Kind.MISTAKE
                    else -> Kind.BLUNDER
                }
                val note = buildString {
                    if (mateNow) { append("Мат!"); return@buildString }
                    when (kind) {
                        Kind.BEST, Kind.BRILLIANT, Kind.GREAT -> append(if (kind == Kind.BRILLIANT) "Жертва, которая работает — сильнейший ход." else "Это лучший ход в позиции.")
                        Kind.EXCELLENT, Kind.GOOD -> append("Хороший ход. Сильнее было $bestSan.")
                        else -> {
                            append("Лучше было $bestSan")
                            if (before >= 2500) append(" — там был мат")
                            append(". ")
                            if (replyTake != null) append("После $san соперник забирает ${NAMES_GEN[abs(replyTake)]}. ")
                            append("Потеря ≈ ${"%.1f".format(loss / 100.0).replace('.', ',')} пешки.")
                        }
                    }
                }
                g.make(m)
                Ply(san, sg.moves[i], side, bestUci, bestSan, before, after, kind, note)
            }
        }
        at = 0
    }
    val list = plies
    Column(Modifier.fillMaxSize()) {
        ChessTop("Отчёт о партии", onBack)
        if (list == null) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Движок разбирает партию…", color = ChessLook.ink)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Text("Около полсекунды на ход. Разбор делает свой встроенный движок — это подсказка, а не оценка уровня Stockfish.", style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
            }
            return@Column
        }
        // позиция после `at` ходов
        val board = remember(at, list) { Chess().also { g -> sg.moves.take(at).forEach { u -> g.fromUci(u)?.let { g.make(it) } } } }
        val cur = list.getOrNull(at - 1)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // сводка: тренер + точность + счётчики
            val mine = list.filter { it.side == human }; val bot = list.filter { it.side != human }
            val accMe = mine.map { accOf(it.before, it.after) }.average().takeIf { !it.isNaN() } ?: 0.0
            val accBot = bot.map { accOf(it.before, it.after) }.average().takeIf { !it.isNaN() } ?: 0.0
            if (at == 0) {
                val worst = mine.maxByOrNull { it.before - it.after }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                    Text("🧑‍🏫", fontSize = 30.sp)
                    Text(buildString {
                        append(sg.result).append(". ")
                        append(when { accMe >= 85 -> "Очень точная игра."; accMe >= 70 -> "Хорошая партия, но были неточности."; accMe >= 50 -> "Было несколько серьёзных ошибок."; else -> "Партия с большим числом зевков — разберём их." })
                        if (worst != null && worst.kind.ordinal >= Kind.MISTAKE.ordinal) append(" Главный момент — ход ${list.indexOf(worst) / 2 + 1}. ${worst.san}: лучше было ${worst.bestSan}.")
                    }, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF2B2A33), modifier = Modifier.padding(start = 10.dp))
                }
            }
            EvalGraph(list, human, at) { at = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Точность (ты)" to accMe, "Точность (бот)" to accBot).forEach { (t, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f".format(v).replace('.', ','), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ChessLook.ink)
                        Text(t, style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
                    }
                }
            }
            if (at == 0) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ChessLook.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row { Text("", Modifier.weight(1f)); Text("ты", Modifier.width(48.dp), color = ChessLook.muted, style = MaterialTheme.typography.labelMedium); Text("бот", Modifier.width(48.dp), color = ChessLook.muted, style = MaterialTheme.typography.labelMedium) }
                Kind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.mark, Modifier.width(30.dp), color = k.color, fontWeight = FontWeight.Bold)
                        Text(k.title, Modifier.weight(1f), color = ChessLook.ink, style = MaterialTheme.typography.bodyMedium)
                        Text("${mine.count { it.kind == k }}", Modifier.width(48.dp), color = k.color, fontWeight = FontWeight.Bold)
                        Text("${bot.count { it.kind == k }}", Modifier.width(48.dp), color = k.color, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // доска с ходом и стрелкой лучшего
            val arrows = if (cur != null && cur.kind.ordinal > Kind.GOOD.ordinal && cur.best.isNotBlank()) {
                val prev = Chess().also { g -> sg.moves.take(at - 1).forEach { u -> g.fromUci(u)?.let { g.make(it) } } }
                listOfNotNull(prev.fromUci(cur.best)?.let { Arrow(Chess.from(it), Chess.toSq(it), Color(0xFF7FA650)) })
            } else emptyList()
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                ChessBoard(board, flip = human < 0, ver = at, arrows = arrows,
                    marks = cur?.let { c -> board.lastMove()?.let { m -> mapOf(Chess.toSq(m) to c.kind.color) } }.orEmpty(), onTap = null)
            }
            if (cur != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ChessLook.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(cur.kind.color), contentAlignment = Alignment.Center) { Text(cur.kind.mark, color = Color.White, fontWeight = FontWeight.Bold) }
                Column(Modifier.padding(start = 10.dp)) {
                    Text("${(at - 1) / 2 + 1}${if (cur.side > 0) "." else "…"} ${cur.san} — ${cur.kind.title.lowercase()}", style = MaterialTheme.typography.titleSmall, color = ChessLook.ink)
                    Text(cur.note, style = MaterialTheme.typography.bodyMedium, color = ChessLook.muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("⏮", { at = 0 }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                PillButton("◀", { if (at > 0) { at--; ChessSound.play(ctx, Snd.MOVE) } }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                PillButton("▶", { if (at < list.size) { at++; ChessSound.play(ctx, Snd.MOVE) } }, Modifier.weight(1f), bg = Color(0xFF7FA650))
                PillButton("⏭", { at = list.size }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
            }
            // все ходы с оценками
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEachIndexed { i, p ->
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (at == i + 1) p.kind.color.copy(alpha = 0.3f) else ChessLook.card).clickable { at = i + 1 }.padding(8.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (i % 2 == 0) Text("${i / 2 + 1}.", style = MaterialTheme.typography.labelSmall, color = ChessLook.muted)
                        Text(p.san, style = MaterialTheme.typography.labelLarge, color = ChessLook.ink, modifier = Modifier.padding(horizontal = 3.dp))
                        if (p.kind.ordinal >= Kind.INACCURACY.ordinal || p.kind == Kind.BRILLIANT) Text(p.kind.mark, color = p.kind.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** График оценки (за белых), точки — ошибки; нажатие — перейти к ходу. */
@Composable
private fun EvalGraph(list: List<Ply>, human: Int, at: Int, onPick: (Int) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF403D39)).pointerInput(list) {
        detectTapGestures { p -> onPick(((p.x / size.width) * list.size).toInt().coerceIn(0, list.size)) }
    }) {
        val n = list.size.coerceAtLeast(1)
        fun yOf(cpWhite: Int) = size.height * (1 - (winPct(cpWhite) / 100).toFloat())
        val pts = list.mapIndexed { i, p -> Offset(size.width * (i + 1) / n, yOf(p.after * p.side)) }
        val path = Path().apply { moveTo(0f, size.height); lineTo(0f, yOf(0)); pts.forEach { lineTo(it.x, it.y) }; lineTo(size.width, size.height); close() }
        drawPath(path, Color(0xFFF4F1EA))
        drawLine(Color(0x55888888), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        list.forEachIndexed { i, p -> if (p.kind.ordinal >= Kind.MISS.ordinal || p.kind == Kind.BRILLIANT || p.kind == Kind.GREAT) drawCircle(p.kind.color, 4.5f.dp.toPx(), pts[i]) }
        if (at > 0) drawLine(Color(0xFF7FA650), Offset(size.width * at / n, 0f), Offset(size.width * at / n, size.height), 2.dp.toPx())
        @Suppress("UNUSED_EXPRESSION") human
    }
}
