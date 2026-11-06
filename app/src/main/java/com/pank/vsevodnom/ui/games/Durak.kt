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

private val CARD_W = 52.dp
private val CARD_H = 76.dp

@Composable
private fun CardView(c: Card?, modifier: Modifier = Modifier, selected: Boolean = false, faceDown: Boolean = false, w: Dp = CARD_W, h: Dp = CARD_H, dim: Boolean = false) {
    Box(
        modifier.size(w, h).clip(RoundedCornerShape(10.dp))
            .background(if (faceDown) Pal.LilacInk.copy(alpha = 0.75f) else if (dim) Color(0xFFF1EFEA) else Color.White)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Pal.LilacInk else Color(0xFFE3E0DA), RoundedCornerShape(10.dp))
    ) {
        if (!faceDown && c != null) {
            val col = if (c.red) Pal.RoseInk else Pal.Ink
            Text(c.rankName, Modifier.align(Alignment.TopStart).padding(5.dp, 2.dp), color = col, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(c.suitName, Modifier.align(Alignment.Center), color = col, fontSize = 26.sp)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DurakScreen(onBack: () -> Unit) {
    var gen by remember { mutableIntStateOf(0) }
    val g = remember(gen) { Durak() }
    var tick by remember { mutableIntStateOf(0) }
    var pickIdx by remember(gen) { mutableIntStateOf(-1) } // какую атакующую карту бьём

    // бот действует, когда его очередь
    LaunchedEffect(gen, tick, g.attacker, g.taking, g.attack.size, g.defense.toList()) {
        delay(650)
        if (g.botStep()) tick++
    }

    val me = 0
    val humanAttacking = g.attacker == me
    val unbeaten = g.unbeaten()

    ScreenScaffold("Дурак", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // бот
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Бот · ${g.hands[1].size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(80.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-34).dp)) { repeat(g.hands[1].size.coerceAtMost(12)) { CardView(null, faceDown = true, w = 40.dp, h = 58.dp) } }
            }
            // колода и козырь
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    if (g.deck.isNotEmpty()) CardView(g.trump, Modifier.padding(start = 26.dp).rotate(90f), w = 44.dp, h = 64.dp)
                    if (g.deck.size > 1) CardView(null, faceDown = true, w = 44.dp, h = 64.dp)
                }
                Column {
                    Text("Колода: ${g.deck.size}", style = MaterialTheme.typography.bodyMedium)
                    Text("Козырь: ${g.trump.suitName}", style = MaterialTheme.typography.titleSmall, color = if (g.trump.red) Pal.RoseInk else Pal.Ink)
                    Text("Бито: ${g.discarded}", style = MaterialTheme.typography.bodySmall)
                }
            }
            // стол
            AppCard(Modifier.fillMaxWidth().heightIn(min = 200.dp), color = Pal.Mint) {
                Text(
                    g.over ?: when {
                        g.taking && humanAttacking -> "Бот берёт — можешь подкинуть или нажми «Отдать»"
                        g.taking -> "Ты берёшь"
                        humanAttacking && g.attack.isEmpty() -> "Твой ход — заходи"
                        humanAttacking -> if (unbeaten.isEmpty()) "Отбился. Подкинь или «Бито»" else "Бот отбивается…"
                        unbeaten.isNotEmpty() -> "Отбивайся: выбери карту"
                        else -> "Бот думает…"
                    },
                    style = MaterialTheme.typography.titleSmall, color = Pal.MintInk
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    g.attack.forEachIndexed { i, a ->
                        Box(Modifier.size(CARD_W + 16.dp, CARD_H + 22.dp).clickable(enabled = !humanAttacking && g.defense[i] == null) { pickIdx = i }) {
                            CardView(a, selected = !humanAttacking && (pickIdx == i || (pickIdx !in unbeaten && unbeaten.firstOrNull() == i)) && g.defense[i] == null)
                            g.defense[i]?.let { CardView(it, Modifier.offset(16.dp, 22.dp)) }
                        }
                    }
                }
            }
            if (g.message.isNotBlank() && g.over == null) Text(g.message, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            Spacer(Modifier.weight(1f))
            // кнопки
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
            // рука
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.hands[0].forEach { c ->
                    val playable = when {
                        g.over != null -> false
                        humanAttacking -> g.canAttackWith(c) && (g.attack.isEmpty() || unbeaten.isEmpty() || g.taking || true)
                        else -> !g.taking && unbeaten.any { g.beats(c, g.attack[it]) }
                    }
                    CardView(c, Modifier.clickable(enabled = playable) {
                        if (humanAttacking) { if (g.playAttack(0, c)) tick++ }
                        else {
                            val target = if (pickIdx in unbeaten && g.beats(c, g.attack[pickIdx])) pickIdx else unbeaten.firstOrNull { g.beats(c, g.attack[it]) } ?: -1
                            if (target >= 0 && g.playDefense(c, target)) { pickIdx = -1; tick++ }
                        }
                    }, dim = !playable, w = 58.dp, h = 84.dp)
                }
            }
        }
    }
}
