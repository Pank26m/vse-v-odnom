package com.pank.vsevodnom.ui.calc

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*

/**
 * Разбор и вычисление выражений: + − × ÷ ^, скобки, %, !, функции (sin cos tan ln log √ abs),
 * константы π и e, переменная x (для графиков и уравнений). Считаем в double и округляем
 * до 12 значащих цифр — поэтому 0,1 + 0,2 = 0,3.
 */
object Expr {
    var degrees = true

    class P(val s: String, val x: Double?) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }
        private var lastPct = false

        fun expr(): Double {
            var v = term()
            while (true) {
                when {
                    eat('+') -> { val r = term(); v += if (lastPct) v * r else r; lastPct = false }
                    eat('−') || eat('-') -> { val r = term(); v -= if (lastPct) v * r else r; lastPct = false }
                    else -> return v
                }
            }
        }
        fun term(): Double {
            var v = power()
            while (true) {
                when {
                    eat('×') || eat('*') -> { v *= power(); lastPct = false }
                    eat('÷') || eat('/') -> { val d = power(); lastPct = false; if (d == 0.0) throw ArithmeticException("Деление на ноль"); v /= d }
                    // неявное умножение: 2π, 3x, 2(1+1), 2sin30
                    peek().isLetter() || peek() == '(' || peek() == 'π' || peek() == '√' -> { v *= power(); lastPct = false }
                    else -> return v
                }
            }
        }
        fun power(): Double {
            val b = unary()
            return if (eat('^')) b.pow(power()) else b
        }
        fun unary(): Double = when {
            eat('−') || eat('-') -> -unary()
            eat('+') -> unary()
            else -> postfix(atom())
        }
        fun postfix(v0: Double): Double {
            var v = v0
            lastPct = false
            while (true) {
                when {
                    eat('!') -> { if (v < 0 || v != floor(v) || v > 170) throw IllegalArgumentException("Факториал только целых 0…170"); v = (1..v.toInt()).fold(1.0) { a, k -> a * k } }
                    eat('%') -> { v /= 100; lastPct = true }
                    eat('²') -> v *= v
                    else -> return v
                }
            }
        }
        private fun trig(f: (Double) -> Double, a: Double) = f(if (degrees) Math.toRadians(a) else a)
        fun atom(): Double {
            if (eat('(')) { val v = expr(); eat(')'); return v }
            if (eat('π')) return PI
            if (eat('√')) return sqrt(unary())
            if (peek().isDigit() || peek() == ',' || peek() == '.') {
                val st = i
                while (peek().isDigit() || peek() == ',' || peek() == '.') i++
                return s.substring(st, i).replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("Ошибка в числе")
            }
            if (peek().isLetter()) {
                val st = i
                while (peek().isLetter()) i++
                val name = s.substring(st, i)
                return when (name) {
                    "x" -> x ?: throw IllegalArgumentException("x есть только в уравнениях и графиках")
                    "e" -> E
                    "sin" -> trig(::sin, unary()); "cos" -> trig(::cos, unary()); "tan" -> trig(::tan, unary())
                    "asin" -> asin(unary()).let { if (degrees) Math.toDegrees(it) else it }
                    "acos" -> acos(unary()).let { if (degrees) Math.toDegrees(it) else it }
                    "atan" -> atan(unary()).let { if (degrees) Math.toDegrees(it) else it }
                    "ln" -> ln(unary()); "log" -> log10(unary()); "abs" -> abs(unary()); "sqrt" -> sqrt(unary())
                    else -> throw IllegalArgumentException("Не знаю «$name»")
                }
            }
            throw IllegalArgumentException("Ошибка")
        }
    }

    fun eval(s: String, x: Double? = null): Double {
        val open = s.count { it == '(' } - s.count { it == ')' }
        val p = P(s.replace(" ", "") + ")".repeat(open.coerceAtLeast(0)), x)
        val v = p.expr()
        if (p.i != p.s.length) throw IllegalArgumentException("Ошибка")
        if (v.isNaN()) throw IllegalArgumentException("Не определено")
        if (v.isInfinite()) throw ArithmeticException("Бесконечность")
        return v
    }

    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val r = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = if (r.abs() >= BigDecimal("1e15") || r.abs() < BigDecimal("1e-9")) r.toString() else r.toPlainString()
        val neg = plain.startsWith("-"); val body = plain.removePrefix("-")
        if (body.contains('E')) return (if (neg) "−" else "") + body.replace('.', ',')
        val (ip, fr) = body.split('.').let { it[0] to it.getOrNull(1) }
        return (if (neg) "−" else "") + ip.reversed().chunked(3).joinToString(" ").reversed() + (fr?.let { ",$it" } ?: "")
    }
}

/** Решение уравнений с x: линейные и квадратные — с шагами, остальное — численно. */
object Solver {
    data class Result(val steps: List<String>, val roots: List<Double>)

    private fun f(l: String, r: String, x: Double) = Expr.eval(l, x) - Expr.eval(r, x)
    private fun n(v: Double) = Expr.format(v)

    fun solve(eq: String): Result {
        val parts = eq.split('=')
        require(parts.size == 2) { "Нужно уравнение со знаком «=»" }
        val (l, r) = parts[0] to parts[1]
        require(l.contains('x') || r.contains('x')) { "В уравнении нет x" }
        val c = f(l, r, 0.0); val f1 = f(l, r, 1.0); val fm = f(l, r, -1.0)
        val a = (f1 + fm) / 2 - c; val b = (f1 - fm) / 2
        val poly = listOf(2.0, -3.0, 0.5, 7.0).all { x -> abs(f(l, r, x) - (a * x * x + b * x + c)) < 1e-6 * (1 + abs(f(l, r, x))) }
        val steps = ArrayList<String>()
        if (poly && abs(a) < 1e-12) {
            steps += "Переносим всё в левую часть:"
            steps += "${n(b)}·x ${if (c >= 0) "+" else "−"} ${n(abs(c))} = 0"
            if (abs(b) < 1e-12) return Result(steps + if (abs(c) < 1e-12) "Верно при любом x" else "Решений нет", emptyList())
            steps += "x = ${n(-c)} ÷ ${n(b)}"
            val x = -c / b
            steps += "x = ${n(x)}"
            return Result(steps, listOf(x))
        }
        if (poly) {
            steps += "Приводим к виду a·x² + b·x + c = 0:"
            steps += "a = ${n(a)}, b = ${n(b)}, c = ${n(c)}"
            val d = b * b - 4 * a * c
            steps += "Дискриминант D = b² − 4ac = ${n(b * b)} − ${n(4 * a * c)} = ${n(d)}"
            return when {
                d < -1e-12 -> Result(steps + "D < 0 — действительных корней нет", emptyList())
                abs(d) <= 1e-12 -> { val x = -b / (2 * a); Result(steps + "D = 0 — один корень: x = −b ÷ 2a = ${n(x)}", listOf(x)) }
                else -> {
                    val sd = sqrt(d)
                    steps += "√D = ${n(sd)}"
                    val x1 = (-b + sd) / (2 * a); val x2 = (-b - sd) / (2 * a)
                    steps += "x₁ = (−b + √D) ÷ 2a = ${n(x1)}"
                    steps += "x₂ = (−b − √D) ÷ 2a = ${n(x2)}"
                    Result(steps, listOf(x1, x2))
                }
            }
        }
        // численно: ищем смены знака на отрезке и уточняем делением пополам
        steps += "Уравнение не линейное и не квадратное — ищу корни численно на отрезке [−100; 100]"
        val roots = ArrayList<Double>()
        var prevX = -100.0; var prev = runCatching { f(l, r, prevX) }.getOrNull()
        var x = -100.0
        while (x < 100) {
            x += 0.05
            val cur = runCatching { f(l, r, x) }.getOrNull()
            if (prev != null && cur != null && (prev == 0.0 || prev * cur < 0) && abs(prev - cur) < 1e3) {
                var lo = prevX; var hi = x
                repeat(60) { val m = (lo + hi) / 2; val fm2 = f(l, r, m); if (f(l, r, lo) * fm2 <= 0) hi = m else lo = m }
                val root = (lo + hi) / 2
                if (roots.none { abs(it - root) < 1e-6 }) roots += root
            }
            prevX = x; prev = cur
        }
        steps += if (roots.isEmpty()) "Корней на этом отрезке не найдено" else roots.joinToString("\n") { "x ≈ ${n(it)}" }
        return Result(steps, roots)
    }
}

private object History {
    private fun p(ctx: Context) = ctx.getSharedPreferences("calc", Context.MODE_PRIVATE)
    fun load(ctx: Context) = p(ctx).getString("h", "")!!.split('\n').filter { it.isNotBlank() }
    fun save(ctx: Context, l: List<String>) = p(ctx).edit().putString("h", l.take(200).joinToString("\n")).apply()
}

private val OPS = setOf('+', '−', '×', '÷', '^')

// ================= Двумерная запись (как в Photomath) =================

/** Узел формулы. Строки (MRow) — это места, куда можно ставить курсор. */
sealed class MNode
class MRow(val items: MutableList<MNode> = mutableListOf()) { fun isEmpty() = items.isEmpty() }
class MChar(val c: Char) : MNode()
class MFrac(val num: MRow = MRow(), val den: MRow = MRow()) : MNode()
class MSup(val exp: MRow = MRow()) : MNode()
class MRoot(val index: MRow?, val body: MRow = MRow()) : MNode()
class MFunc(val name: String, val arg: MRow = MRow()) : MNode()
class MAbs(val body: MRow = MRow()) : MNode()

fun MNode.children(): List<MRow> = when (this) {
    is MFrac -> listOf(num, den); is MSup -> listOf(exp); is MRoot -> listOfNotNull(index, body)
    is MFunc -> listOf(arg); is MAbs -> listOf(body); is MChar -> emptyList()
}

fun MRow.lin(): String = items.joinToString("") { it.lin() }
fun MNode.lin(): String = when (this) {
    is MChar -> c.toString()
    is MFrac -> "((${num.lin()})÷(${den.lin()}))"
    is MSup -> "^(${exp.lin()})"
    is MRoot -> if (index == null) "√(${body.lin()})" else "((${body.lin()})^(1÷(${index.lin()})))"
    is MFunc -> "$name(${arg.lin()})"
    is MAbs -> "abs(${body.lin()})"
}

/** Редактор: дерево формулы и курсор (строка + позиция). */
class MathEditor {
    var root = MRow(); private set
    var row by mutableStateOf(root); private set
    var idx by mutableIntStateOf(0); private set
    var ver by mutableIntStateOf(0); private set

    private fun changed() { ver++ }
    fun clear() { root = MRow(); row = root; idx = 0; changed() }
    fun place(r: MRow, i: Int) { row = r; idx = i.coerceIn(0, r.items.size); changed() }
    fun isEmpty() = root.isEmpty()

    /** Родитель строки: узел, строка, где он лежит, и его индекс. */
    private fun parentOf(target: MRow, r: MRow = root): Triple<MNode, MRow, Int>? {
        r.items.forEachIndexed { k, n ->
            n.children().forEach { ch -> if (ch === target) return Triple(n, r, k); parentOf(target, ch)?.let { return it } }
        }
        return null
    }

    fun insert(n: MNode) { row.items.add(idx, n); idx++; changed() }
    fun char(c: Char) = insert(MChar(c))
    fun text(t: String) = t.forEach { char(it) }

    fun enter(n: MNode, first: MRow) { row.items.add(idx, n); row = first; idx = 0; changed() }

    fun fraction() {
        // число или скобка перед курсором становится числителем
        var st = idx
        if (st > 0 && (row.items[st - 1] as? MChar)?.c == ')') {
            var depth = 0
            while (st > 0) {
                val c = (row.items[st - 1] as? MChar)?.c
                if (c == ')') depth++; if (c == '(') depth--
                st--
                if (depth == 0) break
            }
        } else while (st > 0 && (row.items[st - 1] as? MChar)?.c?.let { it.isLetterOrDigit() || it == ',' || it == 'π' } == true) st--
        val f = MFrac()
        if (st < idx) {
            val moved = row.items.subList(st, idx).toList(); repeat(idx - st) { row.items.removeAt(st) }
            f.num.items.addAll(moved); idx = st
            row.items.add(idx, f); row = f.den; idx = 0; changed()
        } else enter(f, f.num)
    }
    fun power() { val p = MSup(); enter(p, p.exp) }
    fun square() { insert(MSup(MRow(mutableListOf(MChar('2'))))) }
    fun sqrt() { val r = MRoot(null); enter(r, r.body) }
    fun nroot() { val r = MRoot(MRow()); enter(r, r.index!!) }
    fun func(name: String) { val f = MFunc(name); enter(f, f.arg) }
    fun abs() { val a = MAbs(); enter(a, a.body) }

    fun right() {
        if (idx < row.items.size) {
            val n = row.items[idx]
            val ch = n.children()
            if (ch.isNotEmpty()) { row = ch.first(); idx = 0 } else idx++
        } else {
            val (n, pr, k) = parentOf(row) ?: return
            val ch = n.children(); val at = ch.indexOfFirst { it === row }
            if (at < ch.size - 1) { row = ch[at + 1]; idx = 0 } else { row = pr; idx = k + 1 }
        }
        changed()
    }
    fun left() {
        if (idx > 0) {
            val n = row.items[idx - 1]
            val ch = n.children()
            if (ch.isNotEmpty()) { row = ch.last(); idx = row.items.size } else idx--
        } else {
            val (n, pr, k) = parentOf(row) ?: return
            val ch = n.children(); val at = ch.indexOfFirst { it === row }
            if (at > 0) { row = ch[at - 1]; idx = row.items.size } else { row = pr; idx = k }
        }
        changed()
    }
    fun up() {
        val (n, _, _) = parentOf(row) ?: return
        if (n is MFrac && row === n.den) { row = n.num; idx = row.items.size; changed() }
    }
    fun down() {
        val (n, _, _) = parentOf(row) ?: return
        if (n is MFrac && row === n.num) { row = n.den; idx = row.items.size; changed() }
    }
    fun backspace() {
        if (idx > 0) {
            val n = row.items[idx - 1]
            if (n is MChar || n.children().all { it.isEmpty() }) { row.items.removeAt(idx - 1); idx-- }
            else { row = n.children().last(); idx = row.items.size }
        } else {
            val (n, pr, k) = parentOf(row) ?: return
            if (n.children().all { it.isEmpty() }) { pr.items.removeAt(k); row = pr; idx = k }
            else {
                // выносим содержимое наружу: «распаковываем» дробь/корень/степень
                val flat = n.children().flatMapIndexed { i, r -> (if (i > 0 && n is MFrac) listOf(MChar('÷')) else emptyList()) + r.items }
                pr.items.removeAt(k); pr.items.addAll(k, flat); row = pr; idx = k + flat.size
            }
        }
        changed()
    }
    fun load(linear: String) { clear(); text(linear) }
}

@Composable
fun CalcScreen(onBack: () -> Unit) {
    ScreenScaffold("Калькулятор", onBack) { MathCalculator() }
}

private val FUN_KEYS = setOf("sin", "cos", "tan", "ln", "log")

@Composable
private fun MathCalculator() {
    val ctx = LocalContext.current
    val ed = remember { MathEditor() }
    var history by remember { mutableStateOf(History.load(ctx)) }
    var deg by remember { mutableStateOf(Expr.degrees) }
    var showSteps by remember { mutableStateOf(false) }
    val v = ed.ver
    val lin = remember(v) { ed.root.lin() }
    val hasX = 'x' in lin
    val isEq = '=' in lin
    val result: Result<String> = remember(lin, deg) {
        runCatching {
            when {
                lin.isBlank() -> ""
                isEq -> Solver.solve(lin).let { r -> if (r.roots.isEmpty()) r.steps.last() else r.roots.joinToString("   ") { "x = ${Expr.format(it)}" } }
                hasX -> ""
                else -> "= " + Expr.format(Expr.eval(lin))
            }
        }
    }
    val steps = remember(lin) { if (isEq) runCatching { Solver.solve(lin).steps }.getOrNull() else null }

    fun save() {
        val r = result.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        history = listOf("${lin.replace("((", "(").replace("))", ")")}  $r") + history; History.save(ctx, history)
        if (r.startsWith("= ")) ed.load(r.removePrefix("= ").replace(" ", ""))
    }

    fun press(k: String) {
        when (k) {
            "C" -> ed.clear(); "⌫" -> ed.backspace(); "←" -> ed.left(); "→" -> ed.right(); "↑" -> ed.up(); "↓" -> ed.down()
            "a/b" -> ed.fraction(); "xⁿ" -> ed.power(); "x²" -> ed.square(); "√" -> ed.sqrt(); "ⁿ√" -> ed.nroot(); "|a|" -> ed.abs()
            "✓" -> save()
            in FUN_KEYS -> ed.func(k)
            else -> ed.text(k)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // история сверху
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), reverseLayout = true, horizontalAlignment = Alignment.End) {
            items(history.take(60)) { h ->
                Text(h, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().clickable { h.substringAfterLast("= ", "").replace(" ", "").takeIf { it.isNotBlank() }?.let { ed.text(it) } }.padding(vertical = 5.dp))
            }
            if (history.isNotEmpty()) item { TextButton({ history = emptyList(); History.save(ctx, history) }) { Text("Очистить историю") } }
        }
        // формула
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterEnd) {
            key(v) { RowView(ed.root, ed, 30f) }
        }
        // ответ
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (deg) "DEG" else "RAD", style = MaterialTheme.typography.labelLarge, color = Pal.ButterInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Butter).clickable { Expr.degrees = !Expr.degrees; deg = Expr.degrees }.padding(8.dp, 4.dp))
            if (steps != null) Text(if (showSteps) "Скрыть шаги" else "Шаги", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(Pal.Lilac).clickable { showSteps = !showSteps }.padding(8.dp, 4.dp))
            Spacer(Modifier.weight(1f))
            Text(result.getOrNull() ?: if (lin.isBlank()) "" else "…", style = MaterialTheme.typography.headlineMedium, color = Pal.Ink, maxLines = 2, textAlign = TextAlign.End,
                modifier = Modifier.clickable { save() })
        }
        if (showSteps && steps != null) AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heightIn(max = 200.dp), color = Pal.Butter, padding = 12.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) { steps.forEachIndexed { i, st -> Text("${i + 1}. $st", style = MaterialTheme.typography.bodyMedium) } }
        }
        if (hasX && !isEq) AppCard(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp, vertical = 6.dp), padding = 6.dp) {
            Text("y = … · график", style = MaterialTheme.typography.labelSmall)
            GraphCanvas(lin, 10f, Modifier.fillMaxSize())
        }
        // клавиатура
        val keys = listOf(
            listOf("x", "=", "(", ")", "a/b", "xⁿ"),
            listOf("√", "ⁿ√", "π", "e", "|a|", "!"),
            listOf("sin", "cos", "tan", "ln", "log", "x²"),
            listOf("7", "8", "9", "÷", "⌫", "C"),
            listOf("4", "5", "6", "×", "←", "→"),
            listOf("1", "2", "3", "−", "↑", "↓"),
            listOf("0", ",", "%", "+", "✓"),
        )
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        val (bg, fg) = when {
                            k == "✓" -> Pal.Ink to Color.White
                            k.length == 1 && k[0] in OPS -> Pal.Butter to Pal.ButterInk
                            k in setOf("C", "⌫", "←", "→", "↑", "↓") -> Pal.Lilac to Pal.LilacInk
                            k[0].isDigit() || k == "," -> Pal.Card to Pal.Ink
                            else -> Pal.Sky to Pal.SkyInk
                        }
                        Key(k, bg, fg, 50.dp, Modifier.weight(if (k == "✓") 2.1f else 1f)) { press(k) }
                    }
                }
            }
        }
    }
}

/** Мигающий курсор. */
@Composable
private fun Caret(h: Float) {
    val blink by rememberInfiniteTransition(label = "c").animateFloat(1f, 0f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "b")
    Box(Modifier.width(2.5.dp).height((h * 1.25f).dp).background(Pal.ButterInk.copy(alpha = blink)))
}

/** Строка формулы: элементы в ряд, курсор, пустое место — пунктирный квадратик. */
@Composable
private fun RowView(r: MRow, ed: MathEditor, size: Float) {
    val here = ed.row === r
    Row(verticalAlignment = Alignment.CenterVertically) {
        // тап в начало строки
        Box(Modifier.width(6.dp).height((size * 1.2f).dp).clickable { ed.place(r, 0) })
        if (r.isEmpty()) {
            Box(Modifier.size((size * 0.75f).dp, (size * 1.0f).dp).clip(RoundedCornerShape(4.dp))
                .background(if (here) Pal.Butter else Pal.Line).clickable { ed.place(r, 0) })
            if (here) Caret(size)
        } else {
            if (here && ed.idx == 0) Caret(size)
            r.items.forEachIndexed { i, n ->
                NodeView(n, ed, size) { ed.place(r, i + 1) }
                if (here && ed.idx == i + 1) Caret(size)
            }
        }
    }
}

@Composable
private fun NodeView(n: MNode, ed: MathEditor, size: Float, onTap: () -> Unit) {
    val ink = Pal.Ink
    when (n) {
        is MChar -> Text(
            when (n.c) { '×' -> "×"; '÷' -> "÷"; else -> n.c.toString() },
            fontSize = size.sp, color = if (n.c.isLetter() && n.c != 'x' && n.c != 'e') Pal.SkyInk else ink,
            modifier = Modifier.clickable(onClick = onTap).padding(horizontal = if (n.c in "+−×÷=") 3.dp else 0.5.dp)
        )
        is MFrac -> Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RowView(n.num, ed, size * 0.85f)
            Box(Modifier.fillMaxWidth().height(2.dp).background(ink).clickable(onClick = onTap))
            RowView(n.den, ed, size * 0.85f)
        }
        is MSup -> Box(Modifier.padding(bottom = (size * 0.9f).dp)) { RowView(n.exp, ed, size * 0.62f) }
        is MRoot -> Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
            if (n.index != null) Box(Modifier.align(Alignment.Top)) { RowView(n.index, ed, size * 0.5f) }
            Canvas(Modifier.width((size * 0.55f).dp).fillMaxHeight().clickable(onClick = onTap)) {
                val w = this.size.width; val h = this.size.height
                drawPath(Path().apply { moveTo(0f, h * 0.6f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.6f, h); lineTo(w, 2f) }, ink, style = Stroke(4f))
            }
            Column(Modifier.width(IntrinsicSize.Max)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
                Box(Modifier.padding(top = 3.dp, end = 3.dp)) { RowView(n.body, ed, size * 0.92f) }
            }
        }
        is MFunc -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.name, fontSize = (size * 0.9f).sp, color = Pal.SkyInk, modifier = Modifier.clickable(onClick = onTap))
            Text("(", fontSize = size.sp, color = Pal.Muted)
            RowView(n.arg, ed, size)
            Text(")", fontSize = size.sp, color = Pal.Muted)
        }
        is MAbs -> Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(horizontal = 3.dp).width(2.dp).fillMaxHeight().background(ink).clickable(onClick = onTap))
            RowView(n.body, ed, size)
            Box(Modifier.padding(horizontal = 3.dp).width(2.dp).fillMaxHeight().background(ink))
        }
    }
}

@Composable
private fun Key(k: String, bg: Color, fg: Color, h: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(h).clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(k, style = if (k.length > 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge, color = fg)
    }
}

/** График y = f(x); масштаб по Y — автоматически. */
@Composable
private fun GraphCanvas(fx: String, range: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val pts = (0..400).mapNotNull { i ->
            val x = -range + 2 * range * i / 400f
            runCatching { x to Expr.eval(fx, x.toDouble()).toFloat() }.getOrNull()?.takeIf { it.second.isFinite() }
        }
        val ys = pts.map { it.second }.filter { abs(it) < 1e6 }
        val yMax = (ys.maxOfOrNull { abs(it) } ?: 1f).coerceIn(1f, 1e6f) * 1.1f
        fun sx(x: Float) = (x + range) / (2 * range) * w
        fun sy(y: Float) = h / 2 - y / yMax * h / 2
        drawLine(Pal.Line, Offset(0f, h / 2), Offset(w, h / 2), 3f)
        drawLine(Pal.Line, Offset(w / 2, 0f), Offset(w / 2, h), 3f)
        val p = Path(); var pen = false; var lastY = 0f
        pts.forEach { (x, y) ->
            val yy = sy(y)
            if (!pen || abs(yy - lastY) > h) { p.moveTo(sx(x), yy); pen = true } else p.lineTo(sx(x), yy)
            lastY = yy
        }
        drawPath(p, Pal.ButterInk, style = Stroke(5f))
    }
}
