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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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

/** Правила подкидного дурака на двоих. Игрок 0 — человек, 1 — бот. */
class Durak {
    val deck = ArrayList<Card>()
    val hands = arrayOf(mutableStateListOf<Card>(), mutableStateListOf<Card>())
    val attack = mutableStateListOf<Card>()
    val defense = mutableStateListOf<Card?>()
    var trump: Card
    var attacker by mutableIntStateOf(0)
    var taking by mutableStateOf(false)   // защитник сказал «беру», атакующий может подкинуть
    var roundLimit = 6
    var message by mutableStateOf("")
    var over by mutableStateOf<String?>(null)
    var discarded by mutableIntStateOf(0)

    init {
        for (s in 0..3) for (r in 6..14) deck += Card(r, s)
        deck.shuffle()
        trump = deck.first() // нижняя карта колоды (берётся последней)
        repeat(6) { hands[0] += deck.removeAt(deck.lastIndex); hands[1] += deck.removeAt(deck.lastIndex) }
        // первым ходит тот, у кого младший козырь
        val low = (0..1).map { p -> hands[p].filter { it.suit == trump.suit }.minOfOrNull { it.rank } ?: 99 }
        attacker = if (low[1] < low[0]) 1 else 0
        startRound()
        sort()
    }

    val defender get() = 1 - attacker
    fun beats(d: Card, a: Card) = (d.suit == a.suit && d.rank > a.rank) || (d.suit == trump.suit && a.suit != trump.suit)
    fun ranksOnTable() = (attack + defense.filterNotNull()).map { it.rank }.toSet()
    fun unbeaten() = attack.indices.filter { defense[it] == null }
    fun canAttackWith(c: Card) = attack.size < roundLimit && (attack.isEmpty() || c.rank in ranksOnTable()) &&
        (taking || unbeaten().size < hands[defender].size || attack.isEmpty())

    fun sort() = hands.forEach { h -> val s = h.sortedWith(compareBy({ it.suit == trump.suit }, { it.rank }, { it.suit })); h.clear(); h.addAll(s) }

    private fun startRound() {
        attack.clear(); defense.clear(); taking = false
        roundLimit = minOf(6, hands[defender].size)
    }

    fun playAttack(p: Int, c: Card): Boolean {
        if (p != attacker || over != null || !canAttackWith(c)) return false
        hands[p].remove(c); attack += c; defense += null
        return true
    }

    fun playDefense(c: Card, idx: Int): Boolean {
        if (over != null || taking || defense.getOrNull(idx) != null || !beats(c, attack[idx])) return false
        hands[defender].remove(c); defense[idx] = c
        return true
    }

    /** Атакующий закончил: «бито» или отдать карты взявшему. */
    fun finishRound() {
        if (taking) {
            hands[defender].addAll(attack); hands[defender].addAll(defense.filterNotNull())
            message = if (defender == 0) "Ты взял" else "Бот взял"
            draw(); startRound()  // атакующий остаётся тем же
        } else {
            discarded += attack.size + defense.count { it != null }
            message = "Бито"
            draw(); attacker = defender; startRound()
        }
        sort(); checkOver()
    }

    private fun draw() {
        for (p in listOf(attacker, defender)) while (hands[p].size < 6 && deck.isNotEmpty()) hands[p] += deck.removeAt(deck.lastIndex)
    }

    private fun checkOver() {
        if (deck.isNotEmpty()) return
        val e0 = hands[0].isEmpty(); val e1 = hands[1].isEmpty()
        over = when { e0 && e1 -> "Ничья"; e0 -> "Победа! Бот остался дураком"; e1 -> "Ты остался дураком"; else -> null }
    }

    // ---------------- Бот ----------------

    private fun cost(c: Card) = c.rank + if (c.suit == trump.suit) 20 else 0

    /** Один шаг бота. Возвращает true, если что-то сделал. */
    fun botStep(): Boolean {
        if (over != null) return false
        val me = 1
        if (attacker == me) {
            val h = hands[me]
            if (attack.isEmpty()) {
                // заходим с младшей некозырной; пара одинаковых — ещё лучше
                val c = h.minByOrNull { cost(it) - (h.count { o -> o.rank == it.rank } - 1) * 3 } ?: return false
                playAttack(me, c); return true
            }
            if (!taking && unbeaten().isNotEmpty()) return false // ждём защиту
            val late = deck.size <= 4
            val add = h.filter { canAttackWith(it) && (late || (it.suit != trump.suit && it.rank <= 11)) }.minByOrNull { cost(it) }
            if (add != null) { playAttack(me, add); return true }
            finishRound(); return true
        } else {
            if (taking || unbeaten().isEmpty()) return false
            val idx = unbeaten().first()
            val a = attack[idx]
            val opts = hands[me].filter { beats(it, a) }.sortedBy { cost(it) }
            val c = opts.firstOrNull()
            // дорогой козырь в начале игры против мелкой карты — лучше взять
            val tooExpensive = c != null && c.suit == trump.suit && c.rank >= 12 && deck.size > 8 && a.rank <= 9 && attack.size == 1
            if (c == null || tooExpensive) { taking = true; message = "Бот берёт"; return true }
            playDefense(c, idx); return true
        }
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
@Composable
private fun CardFace(c: Card, w: Dp, h: Dp, modifier: Modifier = Modifier) {
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
        Text(c.rankName, Modifier.align(Alignment.TopStart).padding(start = w * 0.04f, top = h * 0.015f).width(w * 0.15f), color = col,
            fontWeight = FontWeight.Bold, fontSize = (w.value * 0.2f).sp, lineHeight = (w.value * 0.22f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            letterSpacing = (-1).sp)
        Text(c.rankName, Modifier.align(Alignment.BottomEnd).padding(end = w * 0.04f, bottom = h * 0.015f).width(w * 0.15f).rotate(180f), color = col,
            fontWeight = FontWeight.Bold, fontSize = (w.value * 0.2f).sp, lineHeight = (w.value * 0.22f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            letterSpacing = (-1).sp)
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
private fun FlyIn(key: Any, fromTop: Boolean, content: @Composable () -> Unit) {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(280)) }
    Box(Modifier.graphicsLayer { translationY = (1 - a.value) * (if (fromTop) -260f else 260f); alpha = 0.3f + 0.7f * a.value }) { content() }
}

@Composable
fun DurakScreen(onBack: () -> Unit) {
    var gen by remember { mutableIntStateOf(0) }
    val g = remember(gen) { Durak() }
    var tick by remember { mutableIntStateOf(0) }

    // где на экране стол и слоты атакующих карт — для перетаскивания
    var tableRect by remember { mutableStateOf(Rect.Zero) }
    val slotRects = remember(gen) { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Card?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(gen, tick, g.attacker, g.taking, g.attack.size, g.defense.toList()) {
        delay(800)
        if (g.botStep()) tick++
    }

    val me = 0
    val humanAttacking = g.attacker == me
    val unbeaten = g.unbeaten()

    /** Карту кладут только перетаскиванием: на стол — атака, на карту соперника — отбой. */
    fun tryPlay(c: Card, dropAt: Offset): Boolean {
        if (g.over != null) return false
        if (humanAttacking) {
            if (!tableRect.contains(dropAt)) return false
            return g.playAttack(0, c).also { if (it) tick++ else g.message = "Этой картой сейчас нельзя" }
        }
        if (g.taking) return false
        val target = slotRects.entries.firstOrNull { it.value.inflate(24f).contains(dropAt) && it.key in unbeaten }?.key
            ?: if (tableRect.contains(dropAt) && unbeaten.size == 1) unbeaten.first() else null
        if (target == null) return false
        if (!g.beats(c, g.attack[target])) { g.message = "Эта карта не бьёт"; tick++; return false }
        return g.playDefense(c, target).also { if (it) tick++ }
    }

    ScreenScaffold("Дурак", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // соперник и колода
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(56.dp)) {
                    Text("Бот", style = MaterialTheme.typography.titleSmall)
                    Text("${g.hands[1].size} карт", style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.weight(1f).height(76.dp)) {
                    val n = g.hands[1].size.coerceAtMost(14)
                    repeat(n) { i -> CardBack(50.dp, 72.dp, Modifier.offset(x = (i * 16).dp)) }
                }
                Box(Modifier.width(96.dp).height(76.dp)) {
                    if (g.deck.isNotEmpty()) CardFace(g.trump, 50.dp, 72.dp, Modifier.align(Alignment.CenterEnd).offset(x = (-4).dp).rotate(90f))
                    if (g.deck.size > 1) CardBack(50.dp, 72.dp, Modifier.align(Alignment.CenterStart))
                    if (g.deck.isEmpty()) Text("козырь ${g.trump.suitName}", Modifier.align(Alignment.Center), color = if (g.trump.red) RED else BLACK, style = MaterialTheme.typography.labelLarge)
                    else Text("${g.deck.size}", Modifier.align(Alignment.BottomStart).clip(RoundedCornerShape(6.dp)).background(Pal.Ink).padding(5.dp, 1.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            // стол — сукно
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(FELT))
                    .onGloballyPositioned { tableRect = it.boundsInRoot() }.padding(12.dp)
            ) {
                Column {
                    Text(
                        g.over ?: when {
                            g.taking && humanAttacking -> "Бот берёт — подкинь или «Отдать»"
                            g.taking -> "Ты берёшь"
                            humanAttacking && g.attack.isEmpty() -> "Твой ход — перетащи карту на стол"
                            humanAttacking -> if (unbeaten.isEmpty()) "Отбился. Подкинь или «Бито»" else "Бот отбивается…"
                            unbeaten.isNotEmpty() -> "Отбивайся: перетащи карту на карту бота"
                            else -> "Бот думает…"
                        },
                        style = MaterialTheme.typography.titleSmall, color = Color.White
                    )
                    Spacer(Modifier.height(10.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        g.attack.forEachIndexed { i, a ->
                            Box(Modifier.size(70.dp + 16.dp, 100.dp + 20.dp).onGloballyPositioned { slotRects[i] = it.boundsInRoot() }) {
                                FlyIn(a, fromTop = !humanAttacking) { CardFace(a, 70.dp, 100.dp) }
                                g.defense[i]?.let { d -> Box(Modifier.offset(16.dp, 20.dp).rotate(8f)) { FlyIn(d, fromTop = humanAttacking) { CardFace(d, 70.dp, 100.dp) } } }
                            }
                        }
                    }
                }
                if (g.message.isNotBlank() && g.over == null) Text(g.message, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.over != null) PillButton("Ещё партию", { gen++ }, Modifier.weight(1f))
                else {
                    if (humanAttacking && g.attack.isNotEmpty() && (unbeaten.isEmpty() || g.taking))
                        PillButton(if (g.taking) "Отдать" else "Бито", { g.finishRound(); tick++ }, Modifier.weight(1f))
                    if (!humanAttacking && unbeaten.isNotEmpty() && !g.taking)
                        PillButton("Беру", { g.taking = true; g.message = "Ты берёшь"; tick++ }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
                    PillButton("Заново", { gen++ }, Modifier.weight(0.6f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
            // рука веером: большие карты внахлёст, кладутся только перетаскиванием
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
}
