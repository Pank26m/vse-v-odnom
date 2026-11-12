package com.pank.vsevodnom.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay

data class Card(val rank: Int, val suit: Int) {
    val rankName get() = when (rank) { 11 -> "В"; 12 -> "Д"; 13 -> "К"; 14 -> "Т"; else -> "$rank" }
    val suitName get() = SUITS[suit]
    val red get() = suit == 1 || suit == 2
    companion object { val SUITS = listOf("♠︎", "♥︎", "♦︎", "♣︎") }
}

/**
 * Дурак на 2–3 игрока. Игрок 0 — человек, остальные — боты.
 * Подкидной: подкидывают все, кроме отбивающегося. Переводной: пока ни одна карта не побита, отбивающийся может
 * положить карту того же достоинства — и отбиваться будет следующий (если у него хватает карт).
 * Кто ходит первым — случайно.
 */
class Durak(val players: Int = 2, val transfer: Boolean = false, rnd: kotlin.random.Random = kotlin.random.Random) {
    val deck = ArrayList<Card>()
    val hands = Array(players) { mutableStateListOf<Card>() }
    val attack = mutableStateListOf<Card>()
    val defense = mutableStateListOf<Card?>()
    var trump: Card
    var attacker by mutableIntStateOf(0)
    var defender by mutableIntStateOf(1)
    var taking by mutableStateOf(false)   // отбивающийся сказал «беру», остальные могут подкинуть
    /** Кто из подкидывающих сказал «пас» с последней выложенной карты. */
    val passed = mutableStateListOf<Int>()
    var roundLimit = 6
    var message by mutableStateOf("")
    var over by mutableStateOf<String?>(null)
    var discarded by mutableIntStateOf(0)
    /** Кто вышел из игры (без карт при пустой колоде), по порядку. */
    val out = mutableStateListOf<Int>()
    /** Кто положил карту на стол — откуда она «вылетает». */
    val by = HashMap<Card, Int>()

    init {
        for (s in 0..3) for (r in 6..14) deck += Card(r, s)
        deck.shuffle(rnd)
        trump = deck.first() // нижняя карта колоды (берётся последней)
        repeat(6) { for (p in 0 until players) hands[p] += deck.removeAt(deck.lastIndex) }
        attacker = rnd.nextInt(players)
        defender = next(attacker)
        startRound()
        sort()
    }

    fun inGame(p: Int) = p !in out
    fun next(p: Int): Int { var n = p; repeat(players) { n = (n + 1) % players; if (inGame(n) && n != p) return n }; return p }
    /** Кто сейчас может подкидывать: все в игре, кроме отбивающегося. */
    fun throwers() = (0 until players).filter { inGame(it) && it != defender }

    fun beats(d: Card, a: Card) = (d.suit == a.suit && d.rank > a.rank) || (d.suit == trump.suit && a.suit != trump.suit)
    fun ranksOnTable() = (attack + defense.filterNotNull()).map { it.rank }.toSet()
    fun unbeaten() = attack.indices.filter { defense[it] == null }
    fun canThrow(p: Int, c: Card) = p != defender && inGame(p) && attack.size < roundLimit &&
        (if (attack.isEmpty()) p == attacker else c.rank in ranksOnTable()) && (taking || unbeaten().size < hands[defender].size)
    fun canThrowAny(p: Int) = hands[p].any { canThrow(p, it) }
    /** Перевод: ни одна карта ещё не побита, все на столе одного достоинства, у следующего хватает карт. */
    fun canTransfer(c: Card): Boolean {
        if (!transfer || taking || attack.isEmpty() || defense.any { it != null }) return false
        if (attack.any { it.rank != c.rank }) return false
        val nx = next(defender)
        return nx != defender && hands[nx].size >= attack.size + 1
    }

    fun sort() = hands.forEach { h -> val s = h.sortedWith(compareBy({ it.suit == trump.suit }, { it.rank }, { it.suit })); h.clear(); h.addAll(s) }

    private fun startRound() {
        attack.clear(); defense.clear(); taking = false; passed.clear()
        roundLimit = minOf(6, hands[defender].size)
    }

    fun playAttack(p: Int, c: Card): Boolean {
        if (over != null || !canThrow(p, c)) return false
        hands[p].remove(c); by[c] = p; attack += c; defense += null; passed.clear()
        return true
    }

    fun playTransfer(c: Card): Boolean {
        if (over != null || !canTransfer(c)) return false
        hands[defender].remove(c); by[c] = defender; attack += c; defense += null
        attacker = defender; defender = next(defender); passed.clear()
        roundLimit = minOf(6, hands[defender].size + 0).coerceAtLeast(attack.size)
        message = "Перевод!"
        return true
    }

    fun playDefense(c: Card, idx: Int): Boolean {
        if (over != null || taking || defense.getOrNull(idx) != null || !beats(c, attack[idx])) return false
        hands[defender].remove(c); by[c] = defender; defense[idx] = c; passed.clear()
        return true
    }

    fun pass(p: Int) { if (p !in passed) passed += p }

    /** Кон можно закрывать: все подкидывающие сказали «пас» (или им нечего), и всё побито — либо «беру». */
    fun roundCanEnd(): Boolean {
        if (attack.isEmpty() || over != null) return false
        if (!taking && unbeaten().isNotEmpty()) return false
        return throwers().all { it in passed || !canThrowAny(it) }
    }

    fun finishRound() {
        val takers = taking
        if (takers) {
            hands[defender].addAll(attack); hands[defender].addAll(defense.filterNotNull())
            message = if (defender == 0) "Ты взял" else "${name(defender)} взял"
        } else {
            discarded += attack.size + defense.count { it != null }
            message = "Бито"
        }
        // добор: сначала заходивший, потом остальные подкидывавшие, отбивавшийся — последним
        val order = listOf(attacker) + throwers().filter { it != attacker } + defender
        for (p in order) while (hands[p].size < 6 && deck.isNotEmpty()) hands[p] += deck.removeAt(deck.lastIndex)
        if (deck.isEmpty()) (0 until players).filter { inGame(it) && hands[it].isEmpty() }.forEach { out += it }
        val left = (0 until players).filter { inGame(it) }
        if (left.size <= 1) {
            over = when {
                left.isEmpty() -> "Ничья"
                left[0] == 0 -> "Ты остался дураком"
                else -> "Победа! ${name(left[0])} — дурак"
            }
        } else {
            // кто заходит: после «бито» — отбивавшийся, после «взял» — следующий за ним
            val first = if (takers) next(defender) else (if (inGame(defender)) defender else next(defender))
            attacker = first; defender = next(attacker)
        }
        startRound(); sort()
    }

    fun name(p: Int) = if (p == 0) "Ты" else if (players == 2) "Бот" else "Бот $p"

    // ---------------- Бот ----------------

    private fun cost(c: Card) = c.rank + if (c.suit == trump.suit) 20 else 0

    /** Один шаг бота p. true — что-то сделал. */
    fun botStep(p: Int): Boolean {
        if (over != null || !inGame(p)) return false
        val h = hands[p]
        if (p == defender) {
            if (taking || unbeaten().isEmpty()) return false
            // перевод дешёвой некозырной картой, если можно
            if (transfer) h.filter { canTransfer(it) && it.suit != trump.suit }.minByOrNull { cost(it) }?.let { playTransfer(it); return true }
            val idx = unbeaten().first()
            val a = attack[idx]
            val c = h.filter { beats(it, a) }.minByOrNull { cost(it) }
            // дорогой козырь в начале игры против мелкой карты — лучше взять
            val tooExpensive = c != null && c.suit == trump.suit && c.rank >= 12 && deck.size > 8 && a.rank <= 9 && attack.size == 1
            if (c == null || tooExpensive) { taking = true; message = "${name(p)} берёт"; return true }
            playDefense(c, idx); return true
        }
        if (p in passed) return false
        if (attack.isEmpty()) {
            if (p != attacker) return false
            val c = h.minByOrNull { cost(it) - (h.count { o -> o.rank == it.rank } - 1) * 3 } ?: return false
            playAttack(p, c); return true
        }
        // подкидываем, когда всё побито или отбивающийся берёт
        if (!taking && unbeaten().isNotEmpty()) return false
        val late = deck.size <= 4
        val add = h.filter { canThrow(p, it) && (late || taking || (it.suit != trump.suit && it.rank <= 11)) }.minByOrNull { cost(it) }
        if (add != null) { playAttack(p, add); return true }
        pass(p); return true
    }
}

private val RED = Color(0xFFC62828)
private val BLACK = Color(0xFF1C1B22)
private val FELT = listOf(Color(0xFF3F9C70), Color(0xFF256346))

/** Масть контуром: ♠ 0, ♥ 1, ♦ 2, ♣ 3 — рисуем сами, чтобы выглядело как на настоящих картах. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.suitPip(suit: Int, c: Offset, sz: Float, color: Color, flip: Boolean = false) {
    fun X(x: Float) = c.x + (x - 0.5f) * sz
    fun Y(y: Float) = c.y + ((if (flip) 1 - y else y) - 0.5f) * sz
    when (suit) {
        1 -> drawPath(Path().apply {
            moveTo(X(0.5f), Y(0.95f))
            cubicTo(X(0.1f), Y(0.66f), X(-0.04f), Y(0.36f), X(0.2f), Y(0.13f))
            cubicTo(X(0.35f), Y(0.0f), X(0.5f), Y(0.1f), X(0.5f), Y(0.26f))
            cubicTo(X(0.5f), Y(0.1f), X(0.65f), Y(0.0f), X(0.8f), Y(0.13f))
            cubicTo(X(1.04f), Y(0.36f), X(0.9f), Y(0.66f), X(0.5f), Y(0.95f)); close()
        }, color)
        2 -> drawPath(Path().apply { moveTo(X(0.5f), Y(0.02f)); lineTo(X(0.88f), Y(0.5f)); lineTo(X(0.5f), Y(0.98f)); lineTo(X(0.12f), Y(0.5f)); close() }, color)
        0 -> drawPath(Path().apply {
            moveTo(X(0.5f), Y(0.02f))
            cubicTo(X(0.92f), Y(0.36f), X(1.04f), Y(0.6f), X(0.8f), Y(0.75f))
            cubicTo(X(0.66f), Y(0.84f), X(0.53f), Y(0.76f), X(0.52f), Y(0.68f))
            lineTo(X(0.62f), Y(0.98f)); lineTo(X(0.38f), Y(0.98f)); lineTo(X(0.48f), Y(0.68f))
            cubicTo(X(0.47f), Y(0.76f), X(0.34f), Y(0.84f), X(0.2f), Y(0.75f))
            cubicTo(X(-0.04f), Y(0.6f), X(0.08f), Y(0.36f), X(0.5f), Y(0.02f)); close()
        }, color)
        else -> {
            drawCircle(color, sz * 0.21f, Offset(X(0.5f), Y(0.27f)))
            drawCircle(color, sz * 0.21f, Offset(X(0.26f), Y(0.6f)))
            drawCircle(color, sz * 0.21f, Offset(X(0.74f), Y(0.6f)))
            drawPath(Path().apply { moveTo(X(0.47f), Y(0.45f)); lineTo(X(0.53f), Y(0.45f)); lineTo(X(0.63f), Y(0.98f)); lineTo(X(0.37f), Y(0.98f)); close() }, color)
        }
    }
}

// расположение значков на числовых картах (доли ширины/высоты поля)
private val PIPS: Map<Int, List<Pair<Float, Float>>> = run {
    val l = 0.27f; val m = 0.5f; val r = 0.73f
    val six = listOf(l to 0.18f, r to 0.18f, l to 0.5f, r to 0.5f, l to 0.82f, r to 0.82f)
    val fourCol = listOf(0.18f, 0.39f, 0.61f, 0.82f).flatMap { y -> listOf(l to y, r to y) }
    mapOf(
        6 to six,
        7 to six + (m to 0.34f),
        8 to six + listOf(m to 0.34f, m to 0.66f),
        9 to fourCol + (m to 0.5f),
        10 to fourCol + listOf(m to 0.285f, m to 0.715f),
    )
}

/** Классическая карта: индексы в двух углах, значки по раскладке, у картинок — портрет в рамке. */
/** Скин карт: классическая колода (картинки) или своя рисованная. */
object CardSkin { var classic by mutableStateOf(true) }

/** Классическая карта из assets/cards (SVG-cards, LGPL) — кэш картинок. */
private object CardImages {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    fun get(ctx: android.content.Context, c: Card): androidx.compose.ui.graphics.ImageBitmap? = cache.getOrPut("${c.suit}_${c.rank}") {
        runCatching { ctx.assets.open("cards/${c.suit}_${c.rank}.webp").use { android.graphics.BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull() ?: return null
    }
}

@Composable
private fun CardFace(c: Card, w: Dp, h: Dp, modifier: Modifier = Modifier) {
    if (CardSkin.classic) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val img = remember(c) { CardImages.get(ctx, c) }
        if (img != null) {
            Box(modifier.size(w, h).shadow(3.dp, RoundedCornerShape(w * 0.07f)).clip(RoundedCornerShape(w * 0.07f)).background(Color.White)) {
                androidx.compose.foundation.Image(img, "${c.rankName}${c.suitName}", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            }
            return
        }
    }
    CardFaceOwn(c, w, h, modifier)
}

@Composable
private fun CardFaceOwn(c: Card, w: Dp, h: Dp, modifier: Modifier = Modifier) {
    val col = if (c.red) RED else BLACK
    Box(modifier.size(w, h).shadow(3.dp, RoundedCornerShape(w * 0.09f)).clip(RoundedCornerShape(w * 0.09f)).background(Color.White)
        .border(0.8.dp, Color(0xFFD9D6CF), RoundedCornerShape(w * 0.09f))) {
        Canvas(Modifier.fillMaxSize()) {
            val W = size.width; val H = size.height
            // поле для значков
            val fx = W * 0.2f; val fy = H * 0.1f; val fw = W * 0.6f; val fh = H * 0.8f
            when (c.rank) {
                14 -> suitPip(c.suit, Offset(W / 2, H / 2), W * 0.42f, col)
                in 11..13 -> {
                    drawRoundRect(col.copy(alpha = 0.55f), Offset(fx, fy), Size(fw, fh), CornerRadius(W * 0.04f), style = Stroke(W * 0.012f))
                }
                else -> PIPS[c.rank]?.forEach { (px, py) -> suitPip(c.suit, Offset(fx + px * fw, fy + py * fh), W * 0.2f, col, flip = py > 0.55f) }
            }
            // малые значки под индексами
            suitPip(c.suit, Offset(W * 0.115f, H * 0.235f), W * 0.13f, col)
            suitPip(c.suit, Offset(W * 0.885f, H * 0.765f), W * 0.13f, col, flip = true)
        }
        if (c.rank in 11..13) FaceArt(c, Modifier.padding(horizontal = w * 0.21f, vertical = h * 0.11f).fillMaxSize())
        // «10» — две цифры: уже шрифт и шире поле (раньше обрезалось до «1»)
        val two = c.rankName.length > 1
        val fsz = (w.value * (if (two) 0.16f else 0.2f)).sp
        Text(c.rankName, Modifier.align(Alignment.TopStart).padding(start = w * 0.02f, top = h * 0.015f).width(w * (if (two) 0.22f else 0.15f)), color = col,
            fontWeight = FontWeight.Bold, fontSize = fsz, lineHeight = (w.value * 0.22f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            letterSpacing = (-1).sp, maxLines = 1, softWrap = false)
        Text(c.rankName, Modifier.align(Alignment.BottomEnd).padding(end = w * 0.02f, bottom = h * 0.015f).width(w * (if (two) 0.22f else 0.15f)).rotate(180f), color = col,
            fontWeight = FontWeight.Bold, fontSize = fsz, lineHeight = (w.value * 0.22f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            letterSpacing = (-1).sp, maxLines = 1, softWrap = false)
    }
}

/** Рубашка: классическая решётка. */
@Composable
private fun CardBack(w: Dp, h: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(w, h).shadow(2.dp, RoundedCornerShape(w * 0.09f)).clip(RoundedCornerShape(w * 0.09f)).background(Color.White).padding(w * 0.06f)) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(w * 0.05f)).background(Color(0xFF9E2A2B))) {
            val st = size.width / 6
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(Color.White.copy(alpha = 0.28f), Offset(x, 0f), Offset(x + size.height, size.height), size.width * 0.025f)
                drawLine(Color.White.copy(alpha = 0.28f), Offset(x + size.height, 0f), Offset(x, size.height), size.width * 0.025f)
                x += st
            }
            drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(size.width * 0.08f, size.width * 0.08f), Size(size.width * 0.84f, size.height - size.width * 0.16f),
                CornerRadius(size.width * 0.06f), style = Stroke(size.width * 0.02f))
        }
    }
}

/** Рисованный портрет для В, Д, К: голова, плечи и головной убор по рангу. */
@Composable
private fun FaceArt(c: Card, modifier: Modifier) {
    val ink = if (c.red) RED else BLACK
    val soft = if (c.red) Color(0xFFF7D9D4) else Color(0xFFDCE3F2)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(soft, Offset(w * 0.03f, h * 0.02f), Size(w * 0.94f, h * 0.96f))
        val cx = w / 2
        // плечи и мантия
        val body = Path().apply {
            moveTo(w * 0.1f, h * 0.98f); quadraticBezierTo(w * 0.14f, h * 0.6f, cx, h * 0.58f); quadraticBezierTo(w * 0.86f, h * 0.6f, w * 0.9f, h * 0.98f); close()
        }
        drawPath(body, ink.copy(alpha = 0.85f))
        drawPath(Path().apply { moveTo(cx - w * 0.16f, h * 0.6f); lineTo(cx, h * 0.76f); lineTo(cx + w * 0.16f, h * 0.6f); close() }, Color(0xFFF6D77B))
        // голова
        drawCircle(Color(0xFFFFE6D0), w * 0.2f, Offset(cx, h * 0.42f))
        drawCircle(BLACK, w * 0.026f, Offset(cx - w * 0.07f, h * 0.41f)); drawCircle(BLACK, w * 0.026f, Offset(cx + w * 0.07f, h * 0.41f))
        drawArc(BLACK, 20f, 140f, false, Offset(cx - w * 0.07f, h * 0.42f), Size(w * 0.14f, h * 0.06f), style = Stroke(w * 0.02f))
        val top = h * 0.42f - w * 0.2f
        when (c.rank) {
            13 -> {
                val crown = Path().apply {
                    moveTo(cx - w * 0.24f, top + h * 0.03f); lineTo(cx - w * 0.24f, top - h * 0.1f); lineTo(cx - w * 0.12f, top - h * 0.02f)
                    lineTo(cx, top - h * 0.12f); lineTo(cx + w * 0.12f, top - h * 0.02f); lineTo(cx + w * 0.24f, top - h * 0.1f); lineTo(cx + w * 0.24f, top + h * 0.03f); close()
                }
                drawPath(crown, Color(0xFFF2C94C)); drawPath(crown, Color(0xFF9C7A22), style = Stroke(w * 0.018f))
                drawArc(Color(0xFFB08B5A), 0f, 180f, true, Offset(cx - w * 0.17f, h * 0.43f), Size(w * 0.34f, h * 0.15f))
            }
            12 -> {
                drawCircle(Color(0xFF8A5A3A), w * 0.08f, Offset(cx - w * 0.2f, h * 0.47f)); drawCircle(Color(0xFF8A5A3A), w * 0.08f, Offset(cx + w * 0.2f, h * 0.47f))
                drawArc(Color(0xFF8A5A3A), 180f, 180f, true, Offset(cx - w * 0.22f, top - h * 0.02f), Size(w * 0.44f, h * 0.17f))
                drawPath(Path().apply { moveTo(cx - w * 0.14f, top + h * 0.01f); lineTo(cx, top - h * 0.07f); lineTo(cx + w * 0.14f, top + h * 0.01f); close() }, Color(0xFFF2C94C))
                drawCircle(ink, w * 0.03f, Offset(cx, top - h * 0.02f))
            }
            else -> {
                drawOval(ink, Offset(cx - w * 0.26f, top - h * 0.05f), Size(w * 0.52f, h * 0.11f))
                drawLine(Color(0xFFF2C94C), Offset(cx + w * 0.14f, top - h * 0.02f), Offset(cx + w * 0.3f, top - h * 0.15f), w * 0.035f)
            }
        }
        suitPip(c.suit, Offset(w * 0.17f, h * 0.12f), w * 0.2f, ink)
    }
}

/** Карта с анимацией появления: прилетает сверху (бот) или снизу (ты). */
@Composable
private fun FlyIn(key: Any, fromTop: Boolean, dx: Float = 0f, content: @Composable () -> Unit) {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer {
        translationY = (1 - a.value) * (if (fromTop) -520f else 620f); translationX = (1 - a.value) * dx
        rotationZ = (1 - a.value) * (if (dx < 0) -25f else 25f); alpha = 0.3f + 0.7f * a.value
    }) { content() }
}
/** Откуда летит карта игрока p: ты — снизу, бот — сверху (в игре на троих — слева или справа). */
private fun flyFrom(g: Durak, p: Int?): Pair<Boolean, Float> = when {
    p == null || p == 0 -> false to 0f
    g.players > 2 -> true to (if (p == 1) -380f else 380f)
    else -> true to 0f
}

@Composable
fun DurakScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dp = remember { ctx.getSharedPreferences("durak", android.content.Context.MODE_PRIVATE) }
    var bots by remember { mutableIntStateOf(dp.getInt("bots", 1)) }
    var transfer by remember { mutableStateOf(dp.getBoolean("transfer", false)) }
    LaunchedEffect(Unit) { CardSkin.classic = dp.getBoolean("classic", true) }
    var opts by remember { mutableStateOf(false) }
    var gen by remember { mutableIntStateOf(0) }
    val g = remember(gen) { Durak(players = bots + 1, transfer = transfer) }
    var tick by remember { mutableIntStateOf(0) }

    var tableRect by remember { mutableStateOf(Rect.Zero) }
    val slotRects = remember(gen) { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Card?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var transferRect by remember { mutableStateOf(Rect.Zero) }

    // ход игры: закрыть кон, если можно; иначе — шаг очередного бота. Человек без подходящих карт «пасует» сам.
    LaunchedEffect(gen, tick, g.attacker, g.defender, g.taking, g.attack.size, g.defense.toList(), g.passed.size) {
        // ходы ботов между собой — медленнее, чтобы было видно, кто что положил
        val botsOnly = g.defender != 0 && (g.attack.isEmpty() && g.attacker != 0 || 0 in g.passed || !g.inGame(0))
        delay(if (botsOnly) 1400 else 900)
        if (g.over != null) return@LaunchedEffect
        if (0 != g.defender && g.inGame(0) && 0 !in g.passed && g.attack.isNotEmpty() && (g.taking || g.unbeaten().isEmpty()) && !g.canThrowAny(0)) g.pass(0)
        if (g.roundCanEnd()) { g.finishRound(); tick++; return@LaunchedEffect }
        // сначала отбивающийся бот, потом подкидывающие по кругу
        val order = listOf(g.defender) + g.throwers()
        for (p in order) if (p != 0 && g.botStep(p)) { tick++; return@LaunchedEffect }
    }

    val me = 0
    val iDefend = g.defender == me && g.inGame(me)
    val iThrow = g.defender != me && g.inGame(me)
    val unbeaten = g.unbeaten()

    /** Кладут перетаскиванием: на стол — ход/подкинуть (или перевод, если отбиваешься), на карту — отбой. */
    fun tryPlay(c: Card, dropAt: Offset): Boolean {
        if (g.over != null) return false
        if (iThrow) {
            if (!tableRect.contains(dropAt)) return false
            return g.playAttack(0, c).also { if (it) tick++ else { g.message = "Этой картой сейчас нельзя"; tick++ } }
        }
        if (!iDefend || g.taking) return false
        // перевод — только в пунктирную ячейку справа; на карту — отбиться ею (даже если перевести тоже можно)
        val target = slotRects.entries.firstOrNull { it.value.inflate(24f).contains(dropAt) && it.key in unbeaten }?.key
        if (target == null && g.canTransfer(c) && transferRect.inflate(30f).contains(dropAt)) return g.playTransfer(c).also { tick++ }
        val t = target ?: if (tableRect.contains(dropAt) && unbeaten.size == 1) unbeaten.first() else null
        if (t == null) return false
        if (!g.beats(c, g.attack[t])) { g.message = "Эта карта не бьёт"; tick++; return false }
        return g.playDefense(c, t).also { if (it) tick++ }
    }

    ScreenScaffold("Дурак", onBack, actions = { RoundIconButton(androidx.compose.material.icons.Icons.Rounded.Tune, "Настройки", { opts = true }) }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // соперники и колода
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1 until g.players).forEach { p ->
                    val role = when { !g.inGame(p) -> "вышел"; p == g.defender -> "отбивается"; p == g.attacker -> "ходит"; p in g.passed -> "пас"; else -> "" }
                    Column(Modifier.weight(1f)) {
                        Text("${g.name(p)} · ${g.hands[p].size}" + if (role.isNotEmpty()) " · $role" else "", style = MaterialTheme.typography.labelMedium,
                            color = if (p == g.defender) Pal.RoseInk else Pal.Ink, maxLines = 1)
                        Box(Modifier.height(58.dp)) {
                            val n = g.hands[p].size.coerceAtMost(10)
                            repeat(n) { i -> CardBack(40.dp, 56.dp, Modifier.offset(x = (i * (if (g.players > 2) 9 else 14)).dp)) }
                        }
                    }
                }
                Box(Modifier.width(92.dp).height(76.dp)) {
                    if (g.deck.isNotEmpty()) CardFace(g.trump, 50.dp, 72.dp, Modifier.align(Alignment.CenterEnd).offset(x = (-4).dp).rotate(90f))
                    if (g.deck.size > 1) CardBack(50.dp, 72.dp, Modifier.align(Alignment.CenterStart))
                    if (g.deck.isEmpty()) Text("козырь ${g.trump.suitName}", Modifier.align(Alignment.Center), color = if (g.trump.red) RED else BLACK, style = MaterialTheme.typography.labelLarge)
                    else Text("${g.deck.size}", Modifier.align(Alignment.BottomStart).clip(RoundedCornerShape(6.dp)).background(Pal.Ink).padding(5.dp, 1.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            // стол — сукно; карты большие, по центру
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(FELT))
                    .onGloballyPositioned { tableRect = it.boundsInRoot() }.padding(10.dp)
            ) {
                Text(
                    g.over ?: when {
                        !g.inGame(me) -> "Ты вышел — не дурак. Боты доигрывают"
                        iDefend && g.taking -> "Ты берёшь"
                        iDefend && unbeaten.isNotEmpty() -> "Отбивайся: перетащи карту на карту" + if (g.transfer && g.defense.all { it == null }) " (или в пунктир справа — перевод)" else ""
                        iDefend -> "Отбился — ждём подкидывающих"
                        g.attack.isEmpty() && g.attacker == me -> "Твой ход — перетащи карту на стол"
                        g.attack.isEmpty() -> "${g.name(g.attacker)} ходит на ${g.name(g.defender)}…"
                        g.taking -> "${g.name(g.defender)} берёт — подкинь или «Пас»"
                        unbeaten.isEmpty() && me !in g.passed -> "Отбился. Подкинь или «Бито»"
                        else -> "${g.name(g.defender)} отбивается…"
                    },
                    Modifier.align(Alignment.TopStart), style = MaterialTheme.typography.titleSmall, color = Color.White
                )
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    g.attack.forEachIndexed { i, a ->
                        Box(Modifier.size(84.dp + 18.dp, 120.dp + 22.dp).onGloballyPositioned { slotRects[i] = it.boundsInRoot() }) {
                            val (ft, fx) = flyFrom(g, g.by[a])
                            FlyIn(a, fromTop = ft, dx = fx) { CardFace(a, 84.dp, 120.dp) }
                            g.defense[i]?.let { d -> val (dt, dxx) = flyFrom(g, g.by[d]); Box(Modifier.offset(18.dp, 22.dp).rotate(8f)) { FlyIn(d, fromTop = dt, dx = dxx) { CardFace(d, 84.dp, 120.dp) } } }
                        }
                    }
                    // переводной: тащишь карту того же достоинства — справа пунктирная «ячейка», брось туда — перевод
                    val dc = dragging
                    if (dc != null && iDefend && g.canTransfer(dc)) Box(Modifier.size(84.dp + 18.dp, 120.dp + 22.dp).onGloballyPositioned { transferRect = it.boundsInRoot() }) {
                        Canvas(Modifier.size(84.dp, 120.dp)) {
                            drawRoundRect(Color.White.copy(alpha = 0.9f), cornerRadius = CornerRadius(size.width * 0.08f),
                                style = Stroke(5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
                        }
                        Text("перевод", Modifier.align(Alignment.Center).offset(x = (-9).dp, y = (-11).dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    } else transferRect = Rect.Zero
                }
                if (g.message.isNotBlank() && g.over == null) Text(g.message, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.over != null) PillButton("Ещё партию", { gen++ }, Modifier.weight(1f))
                else {
                    if (iThrow && g.attack.isNotEmpty() && (unbeaten.isEmpty() || g.taking) && me !in g.passed)
                        PillButton(if (g.taking) "Пас" else "Бито", { g.pass(me); tick++ }, Modifier.weight(1f))
                    if (iDefend && unbeaten.isNotEmpty() && !g.taking)
                        PillButton("Беру", { g.taking = true; g.message = "Ты берёшь"; tick++ }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
                    PillButton("Заново", { gen++ }, Modifier.weight(0.6f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
            // рука веером
            BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp).padding(bottom = 10.dp)) {
                val cw = 92.dp; val ch = 132.dp
                val n = g.hands[0].size
                val step = if (n <= 1) 0.dp else minOf(cw * 0.62f, (maxWidth - cw) / (n - 1))
                val startX = (maxWidth - (cw + step * (n - 1).coerceAtLeast(0))) / 2
                g.hands[0].forEachIndexed { i, c ->
                    var origin by remember(c) { mutableStateOf(Offset.Zero) }
                    val isDrag = dragging == c
                    val tilt = if (n > 1) (i - (n - 1) / 2f) * (16f / n.coerceAtLeast(4)) else 0f
                    val lift = kotlin.math.abs(i - (n - 1) / 2f) * (if (n > 1) 18f / n else 0f)
                    CardFace(c, cw, ch, Modifier
                        .offset(x = startX + step * i, y = lift.dp + 4.dp)
                        .zIndex(if (isDrag) 100f else i.toFloat())
                        .onGloballyPositioned { origin = it.boundsInRoot().center }
                        .graphicsLayer {
                            rotationZ = if (isDrag) 0f else tilt
                            if (isDrag) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.06f; scaleY = 1.06f }
                        }
                        .pointerInput(c, gen) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = awaitTouchSlopOrCancellation(down.id) { ch2, _ -> ch2.consume() } ?: return@awaitEachGesture
                                dragging = c; dragOffset = start.position - down.position
                                val ok = drag(start.id) { ch2 -> dragOffset += ch2.positionChange(); ch2.consume() }
                                val drop = origin + dragOffset
                                dragging = null; dragOffset = Offset.Zero
                                if (ok) tryPlay(c, drop)
                            }
                        })
                }
            }
        }
    }
    if (opts) androidx.compose.material3.AlertDialog(onDismissRequest = { opts = false }, title = { Text("Дурак") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Соперников", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 2).forEach { n -> Chip(if (n == 1) "1 бот" else "2 бота", bots == n, { bots = n }) } }
            Text("Игра", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Подкидной", !transfer, { transfer = false }); Chip("Переводной", transfer, { transfer = true }) }
            Text("Карты", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip("Классические", CardSkin.classic, { CardSkin.classic = true }); Chip("Рисованные", !CardSkin.classic, { CardSkin.classic = false }) }
            Text("Классическая колода — SVG-cards (David Bellot, LGPL-2.1). Кто ходит первым — случайно.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }, confirmButton = { androidx.compose.material3.TextButton({
        dp.edit().putInt("bots", bots).putBoolean("transfer", transfer).putBoolean("classic", CardSkin.classic).apply(); opts = false; gen++
    }) { Text("Новая партия") } }, dismissButton = { androidx.compose.material3.TextButton({ dp.edit().putBoolean("classic", CardSkin.classic).apply(); opts = false }) { Text("Готово") } })
}
