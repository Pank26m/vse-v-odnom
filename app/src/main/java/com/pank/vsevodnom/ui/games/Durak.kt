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

private val CARD_W = 64.dp
private val CARD_H = 92.dp

/** Рисованный портрет для В, Д, К: голова, плечи и головной убор по рангу. Цвет — по масти. */
@Composable
private fun FaceArt(c: Card, modifier: Modifier) {
    val ink = if (c.red) Pal.RoseInk else Pal.Ink
    val soft = if (c.red) Pal.Rose else Pal.Lilac
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // фон-рамка
        drawRoundRect(soft, Offset(w * 0.08f, h * 0.06f), Size(w * 0.84f, h * 0.88f), CornerRadius(w * 0.12f))
        val cx = w / 2
        // плечи
        val body = Path().apply {
            moveTo(w * 0.18f, h * 0.9f); quadraticBezierTo(w * 0.2f, h * 0.6f, cx, h * 0.6f); quadraticBezierTo(w * 0.8f, h * 0.6f, w * 0.82f, h * 0.9f); close()
        }
        drawPath(body, ink.copy(alpha = 0.75f))
        // воротник
        drawPath(Path().apply { moveTo(cx - w * 0.14f, h * 0.6f); lineTo(cx, h * 0.72f); lineTo(cx + w * 0.14f, h * 0.6f); close() }, Color.White.copy(alpha = 0.85f))
        // голова
        drawCircle(Color(0xFFFFE9D6), w * 0.17f, Offset(cx, h * 0.44f))
        drawCircle(ink, w * 0.022f, Offset(cx - w * 0.06f, h * 0.43f)); drawCircle(ink, w * 0.022f, Offset(cx + w * 0.06f, h * 0.43f))
        drawArc(ink, 20f, 140f, false, Offset(cx - w * 0.06f, h * 0.44f), Size(w * 0.12f, h * 0.06f), style = Stroke(w * 0.018f))
        val top = h * 0.44f - w * 0.17f
        when (c.rank) {
            13 -> { // король: корона с зубцами
                val crown = Path().apply {
                    moveTo(cx - w * 0.2f, top + h * 0.03f); lineTo(cx - w * 0.2f, top - h * 0.09f); lineTo(cx - w * 0.1f, top - h * 0.02f)
                    lineTo(cx, top - h * 0.11f); lineTo(cx + w * 0.1f, top - h * 0.02f); lineTo(cx + w * 0.2f, top - h * 0.09f); lineTo(cx + w * 0.2f, top + h * 0.03f); close()
                }
                drawPath(crown, Color(0xFFF6D77B)); drawPath(crown, Color(0xFFB08A2E), style = Stroke(w * 0.015f))
                // борода
                drawArc(Color(0xFFB9A07A), 0f, 180f, true, Offset(cx - w * 0.14f, h * 0.45f), Size(w * 0.28f, h * 0.14f))
            }
            12 -> { // дама: диадема и локоны
                drawCircle(Color(0xFF9A6B45), w * 0.07f, Offset(cx - w * 0.17f, h * 0.48f)); drawCircle(Color(0xFF9A6B45), w * 0.07f, Offset(cx + w * 0.17f, h * 0.48f))
                drawArc(Color(0xFF9A6B45), 180f, 180f, true, Offset(cx - w * 0.19f, top - h * 0.02f), Size(w * 0.38f, h * 0.16f))
                drawPath(Path().apply { moveTo(cx - w * 0.12f, top + h * 0.01f); lineTo(cx, top - h * 0.06f); lineTo(cx + w * 0.12f, top + h * 0.01f); close() }, Color(0xFFF6D77B))
                drawCircle(ink, w * 0.025f, Offset(cx, top - h * 0.02f))
            }
            else -> { // валет: берет с пером
                drawOval(ink.copy(alpha = 0.9f), Offset(cx - w * 0.22f, top - h * 0.05f), Size(w * 0.44f, h * 0.1f))
                drawLine(Color(0xFFF6D77B), Offset(cx + w * 0.12f, top - h * 0.02f), Offset(cx + w * 0.26f, top - h * 0.14f), w * 0.03f)
            }
        }
    }
}

@Composable
private fun CardView(c: Card?, modifier: Modifier = Modifier, selected: Boolean = false, faceDown: Boolean = false, w: Dp = CARD_W, h: Dp = CARD_H, dim: Boolean = false) {
    Box(
        modifier.size(w, h).clip(RoundedCornerShape(10.dp))
            .background(if (faceDown) Pal.LilacInk.copy(alpha = 0.75f) else if (dim) Color(0xFFF1EFEA) else Color.White)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Pal.LilacInk else Color(0xFFE3E0DA), RoundedCornerShape(10.dp))
    ) {
        if (faceDown) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                for (i in -10..20) drawLine(Color.White.copy(alpha = 0.25f), Offset(i * 12f, 0f), Offset(i * 12f + size.height, size.height), 3f)
            }
        } else if (c != null) {
            val col = if (c.red) Pal.RoseInk else Pal.Ink
            if (c.rank in 11..13) FaceArt(c, Modifier.fillMaxSize().padding(start = 12.dp, top = 14.dp, end = 4.dp, bottom = 4.dp))
            else Text(c.suitName, Modifier.align(Alignment.Center), color = col, fontSize = (w.value * if (c.rank == 14) 0.7f else 0.45f).sp)
            Column(Modifier.align(Alignment.TopStart).padding(4.dp, 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.rankName, color = col, fontWeight = FontWeight.ExtraBold, fontSize = (w.value * 0.26f).sp, lineHeight = (w.value * 0.28f).sp)
                Text(c.suitName, color = col, fontSize = (w.value * 0.2f).sp, lineHeight = (w.value * 0.22f).sp)
            }
        }
    }
}

/** Карта с анимацией появления: прилетает сверху (бот) или снизу (ты). */
@Composable
private fun FlyIn(key: Any, fromTop: Boolean, content: @Composable () -> Unit) {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(280)) }
    Box(Modifier.graphicsLayer { translationY = (1 - a.value) * (if (fromTop) -260f else 260f); alpha = 0.3f + 0.7f * a.value }) { content() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DurakScreen(onBack: () -> Unit) {
    var gen by remember { mutableIntStateOf(0) }
    val g = remember(gen) { Durak() }
    var tick by remember { mutableIntStateOf(0) }
    var pickIdx by remember(gen) { mutableIntStateOf(-1) }

    // где на экране стол и слоты атакующих карт — для перетаскивания
    var tableRect by remember { mutableStateOf(Rect.Zero) }
    val slotRects = remember(gen) { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Card?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(gen, tick, g.attacker, g.taking, g.attack.size, g.defense.toList()) {
        delay(800)
        if (g.botStep()) tick++
    }

    val me = 0
    val humanAttacking = g.attacker == me
    val unbeaten = g.unbeaten()

    fun tryPlay(c: Card, dropAt: Offset?): Boolean {
        if (humanAttacking) {
            if (dropAt != null && !tableRect.contains(dropAt)) return false
            return g.playAttack(0, c).also { if (it) tick++ }
        }
        val target = when {
            dropAt != null -> slotRects.entries.firstOrNull { it.value.inflate(30f).contains(dropAt) && it.key in unbeaten && g.beats(c, g.attack[it.key]) }?.key
                ?: if (tableRect.contains(dropAt)) unbeaten.firstOrNull { g.beats(c, g.attack[it]) } else null
            pickIdx in unbeaten && g.beats(c, g.attack[pickIdx]) -> pickIdx
            else -> unbeaten.firstOrNull { g.beats(c, g.attack[it]) }
        } ?: return false
        return g.playDefense(c, target).also { if (it) { pickIdx = -1; tick++ } }
    }

    ScreenScaffold("Дурак", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Бот · ${g.hands[1].size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(76.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-36).dp)) { repeat(g.hands[1].size.coerceAtMost(12)) { CardView(null, faceDown = true, w = 42.dp, h = 60.dp) } }
                Spacer(Modifier.weight(1f))
                Box {
                    if (g.deck.isNotEmpty()) CardView(g.trump, Modifier.padding(start = 20.dp).rotate(90f), w = 40.dp, h = 58.dp)
                    if (g.deck.size > 1) CardView(null, faceDown = true, w = 40.dp, h = 58.dp)
                }
                Column(Modifier.padding(start = 8.dp)) {
                    Text("${g.deck.size}", style = MaterialTheme.typography.titleSmall)
                    Text(g.trump.suitName, color = if (g.trump.red) Pal.RoseInk else Pal.Ink, fontSize = 18.sp)
                }
            }
            // стол — большой
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Mint)
                    .onGloballyPositioned { tableRect = it.boundsInRoot() }.padding(12.dp)
            ) {
                Column {
                    Text(
                        g.over ?: when {
                            g.taking && humanAttacking -> "Бот берёт — подкинь или «Отдать»"
                            g.taking -> "Ты берёшь"
                            humanAttacking && g.attack.isEmpty() -> "Твой ход — перетащи карту на стол"
                            humanAttacking -> if (unbeaten.isEmpty()) "Отбился. Подкинь или «Бито»" else "Бот отбивается…"
                            unbeaten.isNotEmpty() -> "Отбивайся: перетащи карту на атакующую"
                            else -> "Бот думает…"
                        },
                        style = MaterialTheme.typography.titleSmall, color = Pal.MintInk
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        g.attack.forEachIndexed { i, a ->
                            Box(Modifier.size(CARD_W + 18.dp, CARD_H + 24.dp)
                                .onGloballyPositioned { slotRects[i] = it.boundsInRoot() }
                                .clickable(enabled = !humanAttacking && g.defense[i] == null) { pickIdx = i }) {
                                FlyIn(a, fromTop = !humanAttacking) {
                                    CardView(a, selected = !humanAttacking && g.defense[i] == null && (pickIdx == i || (pickIdx !in unbeaten && unbeaten.firstOrNull() == i)))
                                }
                                g.defense[i]?.let { d -> Box(Modifier.offset(18.dp, 24.dp)) { FlyIn(d, fromTop = humanAttacking) { CardView(d) } } }
                            }
                        }
                    }
                }
                if (g.message.isNotBlank() && g.over == null) Text(g.message, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
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
            // рука: карты можно тащить пальцем или просто нажать
            Row(Modifier.horizontalScroll(rememberScrollState(), enabled = dragging == null).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.hands[0].forEach { c ->
                    val playable = when {
                        g.over != null -> false
                        humanAttacking -> g.canAttackWith(c)
                        else -> !g.taking && unbeaten.any { g.beats(c, g.attack[it]) }
                    }
                    var origin by remember(c) { mutableStateOf(Offset.Zero) }
                    val isDrag = dragging == c
                    CardView(c, Modifier
                        .onGloballyPositioned { origin = it.boundsInRoot().center }
                        .zIndex(if (isDrag) 10f else 0f)
                        .graphicsLayer { if (isDrag) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.08f; scaleY = 1.08f } }
                        .pointerInput(c, playable) {
                            if (!playable) return@pointerInput
                            // тянешь вверх — берёшь карту; ведёшь вбок — листаешь руку
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = awaitTouchSlopOrCancellation(down.id) { ch, over ->
                                    if (kotlin.math.abs(over.y) > kotlin.math.abs(over.x)) ch.consume()
                                } ?: return@awaitEachGesture
                                dragging = c; dragOrigin = origin; dragOffset = start.position - down.position
                                val ok = drag(start.id) { ch -> dragOffset += ch.positionChange(); ch.consume() }
                                val drop = dragOrigin + dragOffset
                                dragging = null; dragOffset = Offset.Zero
                                if (ok) tryPlay(c, drop)
                            }
                        }
                        .clickable(enabled = playable) { tryPlay(c, null) },
                        dim = !playable, w = 64.dp, h = 92.dp)
                }
            }
        }
    }
}
