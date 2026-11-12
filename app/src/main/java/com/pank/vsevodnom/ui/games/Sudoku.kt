package com.pank.vsevodnom.ui.games

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Генератор судоку: полная сетка + удаление клеток с проверкой единственности решения. */
object SudokuGen {
    private fun ok(g: IntArray, i: Int, v: Int): Boolean {
        val r = i / 9; val c = i % 9
        for (k in 0 until 9) { if (g[r * 9 + k] == v || g[k * 9 + c] == v) return false }
        val br = r / 3 * 3; val bc = c / 3 * 3
        for (y in br until br + 3) for (x in bc until bc + 3) if (g[y * 9 + x] == v) return false
        return true
    }

    private fun fill(g: IntArray, rnd: Random): Boolean {
        val i = g.indexOf(0); if (i < 0) return true
        for (v in (1..9).shuffled(rnd)) { if (ok(g, i, v)) { g[i] = v; if (fill(g, rnd)) return true; g[i] = 0 } }
        return false
    }

    /** Считает решения до limit (нам нужно знать: 1 или больше). */
    fun count(g: IntArray, limit: Int = 2): Int {
        // клетка с наименьшим числом вариантов — сильно ускоряет перебор
        var best = -1; var bestN = 10
        for (i in 0 until 81) if (g[i] == 0) {
            var n = 0; for (v in 1..9) if (ok(g, i, v)) n++
            if (n < bestN) { bestN = n; best = i; if (n == 0) return 0 }
        }
        if (best < 0) return 1
        var total = 0
        for (v in 1..9) if (ok(g, best, v)) {
            g[best] = v; total += count(g, limit - total); g[best] = 0
            if (total >= limit) break
        }
        return total
    }

    fun generate(clues: Int, seed: Long = System.nanoTime()): Pair<IntArray, IntArray> {
        val rnd = Random(seed)
        val sol = IntArray(81); fill(sol, rnd)
        val puz = sol.copyOf()
        var left = 81
        for (i in (0 until 81).shuffled(rnd)) {
            if (left <= clues) break
            val keep = puz[i]; puz[i] = 0
            if (count(puz.copyOf()) != 1) puz[i] = keep else left--
        }
        return puz to sol
    }

    /** Решается ли одной логикой «одиночек» (голые + скрытые одиночки). true — головоломка «человеческая». */
    fun easyLogic(p: IntArray): Boolean {
        val g = p.copyOf()
        val cand = IntArray(81)
        fun recalc() { for (i in 0 until 81) { cand[i] = 0; if (g[i] == 0) for (v in 1..9) if (ok(g, i, v)) cand[i] = cand[i] or (1 shl v) } }
        var progress = true
        while (progress) {
            progress = false; recalc()
            for (i in 0 until 81) if (g[i] == 0 && Integer.bitCount(cand[i]) == 1) { g[i] = Integer.numberOfTrailingZeros(cand[i]); progress = true }
            if (progress) continue
            // скрытые одиночки: цифра возможна только в одной клетке строки/столбца/квадрата
            val units = (0 until 9).map { r -> (0 until 9).map { r * 9 + it } } + (0 until 9).map { c -> (0 until 9).map { it * 9 + c } } +
                (0 until 9).map { b -> (0 until 9).map { k -> (b / 3 * 3 + k / 3) * 9 + b % 3 * 3 + k % 3 } }
            loop@ for (u in units) for (v in 1..9) {
                if (u.any { g[it] == v }) continue
                val where = u.filter { g[it] == 0 && cand[it] and (1 shl v) != 0 }
                if (where.size == 1) { g[where[0]] = v; progress = true; break@loop }
            }
        }
        return g.none { it == 0 }
    }

    /**
     * «Божество»: убираем всё, что можно (минимальная головоломка, ~21–24 подсказки), и берём только те,
     * что не решаются одиночками — дальше нужны тяжёлые приёмы или перебор.
     */
    fun generateGod(seed: Long = System.nanoTime()): Pair<IntArray, IntArray> {
        val rnd = Random(seed)
        var best: Pair<IntArray, IntArray>? = null
        val t0 = System.currentTimeMillis()
        repeat(40) {
            val sol = IntArray(81); fill(sol, rnd)
            val puz = sol.copyOf()
            for (i in (0 until 81).shuffled(rnd)) { val keep = puz[i]; puz[i] = 0; if (count(puz.copyOf()) != 1) puz[i] = keep }
            val clues = puz.count { it != 0 }
            if (!easyLogic(puz)) {
                if (best == null || clues < best!!.first.count { it != 0 }) best = puz to sol
                if (clues <= 23) return best!!
            }
            if (System.currentTimeMillis() - t0 > 12_000 && best != null) return best!!
        }
        return best ?: generate(22, seed)
    }
}

private data class Diff(val name: String, val sub: String, val clues: Int, val bg: Color, val ink: Color)
private val DIFFS = listOf(
    Diff("Лёгкая", "40 подсказок — разминка", 40, Color(0xFFDDF3E6), Color(0xFF2F7D57)),
    Diff("Средняя", "32 подсказки", 32, Color(0xFFDCE7FB), Color(0xFF3E63B8)),
    Diff("Максимальная", "25 подсказок — прежняя самая сложная", 25, Color(0xFFFBE7D0), Color(0xFFB65E2E)),
    Diff("Божество", "минимум подсказок, одиночками не решить — почти невозможно", 0, Color(0xFF2B2A33), Color(0xFFF2C94C)),
)

private fun sbest(ctx: android.content.Context, d: Int) = ctx.getSharedPreferences("games", android.content.Context.MODE_PRIVATE).getInt("sudoku_best_$d", 0)

@Composable
fun SudokuScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var diff by remember { mutableStateOf<Int?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    var puzzle by remember { mutableStateOf<Pair<IntArray, IntArray>?>(null) }
    LaunchedEffect(diff, gen) {
        val d = diff ?: return@LaunchedEffect
        puzzle = null
        puzzle = withContext(Dispatchers.Default) { if (DIFFS[d].clues == 0) SudokuGen.generateGod() else SudokuGen.generate(DIFFS[d].clues) }
    }
    val d = diff
    if (d == null) {
        // меню: сложность плитками во всю ширину
        ScreenScaffold("Судоку", onBack) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DIFFS.forEachIndexed { i, x ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(x.bg).clickable { diff = i; gen++ }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(x.ink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Text(listOf("1", "2", "3", "∞")[i], fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = x.ink)
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(x.name, style = MaterialTheme.typography.titleLarge, color = x.ink)
                            Text(x.sub, style = MaterialTheme.typography.bodySmall, color = x.ink.copy(alpha = 0.75f))
                            sbest(ctx, i).takeIf { it > 0 }?.let { Text("рекорд %d:%02d".format(it / 60, it % 60), style = MaterialTheme.typography.labelMedium, color = x.ink) }
                        }
                    }
                }
            }
        }
        return
    }
    androidx.activity.compose.BackHandler { diff = null }
    ScreenScaffold("Судоку · " + DIFFS[d].name, { diff = null }, actions = {
        RoundIconButton(Icons.Rounded.Refresh, "Новая", { gen++ })
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = puzzle
            if (p == null) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (DIFFS[d].clues == 0) Text("Ищу головоломку, достойную божества…", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 10.dp))
                }
            }
            else key(p) { SudokuBoard(p.first, p.second, maxErr = if (DIFFS[d].clues == 0) 1 else 3, onRestart = { gen++ }, onMenu = { diff = null }) { sec ->
                val old = sbest(ctx, d); if (old == 0 || sec < old) ctx.getSharedPreferences("games", android.content.Context.MODE_PRIVATE).edit().putInt("sudoku_best_$d", sec).apply()
            } }
        }
    }
}

@Composable
private fun SudokuBoard(start: IntArray, sol: IntArray, maxErr: Int, onRestart: () -> Unit, onMenu: () -> Unit, onSolved: (Int) -> Unit) {
    val cells = remember { mutableStateListOf(*start.toTypedArray()) }
    val notes = remember { mutableStateListOf(*Array(81) { 0 }) } // битовая маска заметок 1..9
    var sel by remember { mutableIntStateOf(-1) }
    var noteMode by remember { mutableStateOf(false) }
    var mistakes by remember { mutableIntStateOf(0) }
    val history = remember { ArrayDeque<Triple<Int, Int, Int>>() } // клетка, старое значение, старые заметки
    var sec by remember { mutableIntStateOf(0) }
    var hlDigit by remember { mutableIntStateOf(0) }   // подсветка цифры (нажал на цифру в поле)
    val solved = (0 until 81).all { cells[it] == sol[it] }
    // поражение: 3 ошибки (в «Божестве» — первая же)
    val lost = mistakes >= maxErr
    LaunchedEffect(solved, lost) { if (solved) onSolved(sec); while (!solved && !lost) { delay(1000); sec++ } }

    fun clearNotesAround(i: Int, v: Int) {
        val r = i / 9; val c = i % 9
        for (k in 0 until 81) {
            val kr = k / 9; val kc = k % 9
            if (kr == r || kc == c || (kr / 3 == r / 3 && kc / 3 == c / 3)) notes[k] = notes[k] and (1 shl v).inv()
        }
    }
    fun put(v: Int) {
        if (sel < 0 || start[sel] != 0 || solved || lost) return
        history.addLast(Triple(sel, cells[sel], notes[sel]))
        if (noteMode && v != 0) { if (cells[sel] == 0) notes[sel] = notes[sel] xor (1 shl v); return }
        cells[sel] = v; notes[sel] = 0
        if (v != 0) hlDigit = v
        if (v != 0 && v != sol[sel]) mistakes++
        if (v != 0 && v == sol[sel]) clearNotesAround(sel, v)
    }
    /** «Пианино»: провёл по цифрам — все они карандашом в клетку. */
    fun putNotes(ds: Set<Int>) {
        if (sel < 0 || start[sel] != 0 || cells[sel] != 0 || solved || ds.isEmpty()) return
        history.addLast(Triple(sel, cells[sel], notes[sel]))
        var m = notes[sel]; ds.forEach { m = m or (1 shl it) }; notes[sel] = m
    }

    // подсветка цифры: нажал на поставленную цифру — подсветились все такие (и карандашные); нажал на неё же ещё раз — подсветка снялась
    val selVal = hlDigit
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(if (maxErr == 1) "без права на ошибку" else "Ошибок: $mistakes из $maxErr", style = MaterialTheme.typography.titleSmall, color = if (mistakes > 0) Pal.RoseInk else Pal.Muted)
    }
    if (lost) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Rose).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Поражение", style = MaterialTheme.typography.titleLarge, color = Pal.RoseInk)
        Text(if (maxErr == 1) "В «Божестве» ошибка — сразу конец." else "Три ошибки — партия проиграна.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Начать заново", onRestart, Modifier.weight(1f))
            PillButton("В меню", onMenu, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        }
    }
    if (solved) AppCard(Modifier.fillMaxWidth(), color = Pal.Mint) { Text("Решено за %d:%02d!".format(sec / 60, sec % 60), style = MaterialTheme.typography.titleLarge, color = Pal.MintInk) }
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Pal.Ink).padding(2.dp)) {
        val cs = maxWidth / 9
        Column {
            for (r in 0 until 9) Row {
                for (c in 0 until 9) {
                    val i = r * 9 + c
                    val v = cells[i]
                    val related = sel >= 0 && (sel / 9 == r || sel % 9 == c || (sel / 27 == r / 3 && sel % 9 / 3 == c / 3))
                    val same = selVal != 0 && v == selVal
                    val wrong = v != 0 && v != sol[i]
                    val bg = when { i == sel -> Pal.Lilac; same -> Color(0xFFDCD3F7); related -> Color(0xFFF4F2EE); else -> Pal.Card }
                    Box(
                        Modifier.size(cs)
                            .padding(start = if (c % 3 == 0 && c > 0) 1.5.dp else 0.5.dp, top = if (r % 3 == 0 && r > 0) 1.5.dp else 0.5.dp, end = 0.5.dp, bottom = 0.5.dp)
                            .background(bg).clickable {
                                if (cells[i] != 0 && sel == i && hlDigit == cells[i]) { sel = -1; hlDigit = 0 }
                                else { sel = i; if (cells[i] != 0) hlDigit = cells[i] }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (v != 0) Text(
                            "$v", fontSize = (cs.value * 0.55f).sp,
                            fontWeight = if (start[i] != 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = when { wrong -> Pal.RoseInk; start[i] != 0 -> Pal.Ink; else -> Pal.LilacInk }
                        ) else if (notes[i] != 0) Column(Modifier.fillMaxSize().padding(1.dp)) {
                            for (nr in 0 until 3) Row(Modifier.weight(1f)) {
                                for (nc in 0 until 3) {
                                    val n = nr * 3 + nc + 1
                                    val on = notes[i] and (1 shl n) != 0
                                    // карандашные цифры той же, что выбрана, — подсвечены
                                    val hl = on && n == selVal
                                    Box(Modifier.weight(1f).fillMaxHeight().padding(0.5.dp).clip(RoundedCornerShape(3.dp)).background(if (hl) Color(0xFF6A55A8) else Color.Transparent),
                                        contentAlignment = Alignment.Center) {
                                        if (on) Text("$n", fontSize = (cs.value * 0.22f).sp, fontWeight = if (hl) FontWeight.Bold else FontWeight.Normal, color = if (hl) Color.White else Pal.Muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // полоса цифр: нажатие — цифра (или заметка); в режиме карандаша можно «проехать» пальцем по цифрам, как по клавишам
    var swiped by remember { mutableStateOf<Set<Int>>(emptySet()) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val wpx = constraints.maxWidth.toFloat()
        fun digitAt(x: Float) = (x / (wpx / 9f)).toInt().coerceIn(0, 8) + 1
        Row(Modifier.fillMaxSize().pointerInput(noteMode, sel) {
            detectDragGestures(
                onDragStart = { o -> swiped = setOf(digitAt(o.x)) },
                onDrag = { ch, _ -> ch.consume(); val d = digitAt(ch.position.x); if (d !in swiped) swiped = swiped + d },
                onDragEnd = {
                    val ds = swiped; swiped = emptySet()
                    if (ds.size >= 2) {
                        // провёл от 1 до 4 — заполняется 1, 2, 3, 4 (весь отрезок между крайними)
                        val full = (ds.min()..ds.max()).toSet()
                        if (noteMode) putNotes(full) else { val nm = noteMode; noteMode = true; putNotes(full); noteMode = nm }
                    } else ds.firstOrNull()?.let { put(it) }
                },
                onDragCancel = { swiped = emptySet() })
        }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..9).forEach { n ->
                val left = 9 - cells.count { it == n }
                val on = n in swiped
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
                        .background(when { on -> Pal.Lilac; n == selVal -> Color(0xFFE9E3FB); left == 0 -> Pal.Line; else -> Pal.Card }).clickable { put(n) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text("$n", style = MaterialTheme.typography.headlineMedium, color = if (left == 0) Pal.Muted else Pal.Ink)
                    Text(if (left > 0) "$left" else "", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
        }
    }
    // кнопки — плитками во всю ширину
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, t: String, on: Boolean = false, onClick: () -> Unit) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) Pal.Lilac else Pal.Card).clickable(onClick = onClick).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = if (on) Pal.LilacInk else Pal.Ink)
                Text(t, style = MaterialTheme.typography.labelLarge, color = if (on) Pal.LilacInk else Pal.Ink, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Tile(Icons.AutoMirrored.Rounded.Undo, "Отмена") { history.removeLastOrNull()?.let { (i, v, nt) -> cells[i] = v; notes[i] = nt } }
        Tile(Icons.AutoMirrored.Rounded.Backspace, "Стереть") { put(0) }
        Tile(Icons.Rounded.Edit, if (noteMode) "Карандаш ✓" else "Карандаш", noteMode) { noteMode = !noteMode }
    }
    if (noteMode) Text("Карандаш: проведи пальцем по цифрам — все они попадут в клетку", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
}
