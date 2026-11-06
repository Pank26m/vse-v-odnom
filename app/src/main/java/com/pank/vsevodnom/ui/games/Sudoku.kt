package com.pank.vsevodnom.ui.games

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
}

private data class Diff(val name: String, val clues: Int)
private val DIFFS = listOf(Diff("Лёгкий", 40), Diff("Средний", 32), Diff("Сложный", 25))

@Composable
fun SudokuScreen(onBack: () -> Unit) {
    var diff by remember { mutableIntStateOf(0) }
    var gen by remember { mutableIntStateOf(0) }
    var puzzle by remember { mutableStateOf<Pair<IntArray, IntArray>?>(null) }
    LaunchedEffect(diff, gen) { puzzle = null; puzzle = withContext(Dispatchers.Default) { SudokuGen.generate(DIFFS[diff].clues) } }

    ScreenScaffold("Судоку", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DIFFS.forEachIndexed { i, d -> Chip(d.name, i == diff, { diff = i; gen++ }) }
                Spacer(Modifier.weight(1f)); Chip("Новая", false, { gen++ })
            }
            val p = puzzle
            if (p == null) Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else key(p) { SudokuBoard(p.first, p.second) }
        }
    }
}

@Composable
private fun SudokuBoard(start: IntArray, sol: IntArray) {
    val cells = remember { mutableStateListOf(*start.toTypedArray()) }
    val notes = remember { mutableStateListOf(*Array(81) { 0 }) } // битовая маска заметок 1..9
    var sel by remember { mutableIntStateOf(-1) }
    var noteMode by remember { mutableStateOf(false) }
    var mistakes by remember { mutableIntStateOf(0) }
    var hints by remember { mutableIntStateOf(3) }
    val history = remember { ArrayDeque<Triple<Int, Int, Int>>() } // клетка, старое значение, старые заметки
    var sec by remember { mutableIntStateOf(0) }
    val solved = (0 until 81).all { cells[it] == sol[it] }
    LaunchedEffect(solved) { while (!solved) { delay(1000); sec++ } }

    fun put(v: Int) {
        if (sel < 0 || start[sel] != 0 || solved) return
        history.addLast(Triple(sel, cells[sel], notes[sel]))
        if (noteMode && v != 0) { notes[sel] = notes[sel] xor (1 shl v); return }
        cells[sel] = v; notes[sel] = 0
        if (v != 0 && v != sol[sel]) mistakes++
        if (v != 0 && v == sol[sel]) {
            // убрать эту цифру из заметок в строке, столбце и квадрате
            val r = sel / 9; val c = sel % 9
            for (k in 0 until 81) {
                val kr = k / 9; val kc = k % 9
                if (kr == r || kc == c || (kr / 3 == r / 3 && kc / 3 == c / 3)) notes[k] = notes[k] and (1 shl v).inv()
            }
        }
    }

    val selVal = if (sel >= 0) cells[sel] else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("Ошибок: $mistakes", style = MaterialTheme.typography.titleSmall, color = if (mistakes > 0) Pal.RoseInk else Pal.Muted)
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
                    val bg = when { i == sel -> Pal.Lilac; same -> Color(0xFFEDE8FA); related -> Color(0xFFF4F2EE); else -> Pal.Card }
                    Box(
                        Modifier.size(cs)
                            .padding(start = if (c % 3 == 0 && c > 0) 1.5.dp else 0.5.dp, top = if (r % 3 == 0 && r > 0) 1.5.dp else 0.5.dp, end = 0.5.dp, bottom = 0.5.dp)
                            .background(bg).clickable { sel = i },
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
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        if (notes[i] and (1 shl n) != 0) Text("$n", fontSize = (cs.value * 0.2f).sp, color = Pal.Muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..9).forEach { n ->
            val left = 9 - cells.count { it == n }
            Column(
                Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(if (left == 0) Pal.Line else Pal.Card).clickable { put(n) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text("$n", style = MaterialTheme.typography.headlineMedium, color = if (left == 0) Pal.Muted else Pal.Ink)
                Text(if (left > 0) "$left" else "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("Отмена", {
            history.removeLastOrNull()?.let { (i, v, nt) -> cells[i] = v; notes[i] = nt }
        }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        PillButton("Стереть", { put(0) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
        PillButton(if (noteMode) "Заметки ✓" else "Заметки", { noteMode = !noteMode }, Modifier.weight(1f), bg = if (noteMode) Pal.Lilac else Pal.Card, fg = Pal.Ink)
        PillButton("Подсказка $hints", {
            if (hints > 0 && sel >= 0 && cells[sel] != sol[sel]) { val nm = noteMode; noteMode = false; put(sol[sel]); noteMode = nm; hints-- }
        }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink, enabled = hints > 0)
    }
}
