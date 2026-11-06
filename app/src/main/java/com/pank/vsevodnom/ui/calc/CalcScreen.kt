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

@Composable
fun CalcScreen(onBack: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold("Калькулятор", onBack) {
        Segmented(listOf("Обычный", "Инженерный", "Уравнения", "График"), mode, { mode = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Butter)
        when (mode) {
            0, 1 -> Calculator(scientific = mode == 1)
            2 -> Equations()
            else -> Graph()
        }
    }
}

/** Поле ввода с курсором: тап по тексту ставит курсор в это место. */
@Composable
private fun CursorText(text: String, cursor: Int, onCursor: (Int) -> Unit, modifier: Modifier, big: Boolean = true) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink by rememberInfiniteTransition(label = "c").animateFloat(1f, 0f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
    Text(
        text.ifEmpty { " " },
        style = (if (big) MaterialTheme.typography.displayMedium.copy(fontSize = if (text.length > 12) 36.sp else 52.sp) else MaterialTheme.typography.headlineMedium),
        textAlign = TextAlign.End, onTextLayout = { layout = it },
        modifier = modifier
            .pointerInput(text) { detectTapGestures { pos -> layout?.let { onCursor(it.getOffsetForPosition(pos).coerceIn(0, text.length)) } } }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val r = l.getCursorRect(cursor.coerceIn(0, text.length))
                drawLine(Pal.ButterInk.copy(alpha = blink), Offset(r.left, r.top + 4), Offset(r.left, r.bottom - 4), 5f)
            }
    )
}

@Composable
private fun Calculator(scientific: Boolean) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var cursor by rememberSaveable { mutableIntStateOf(0) }
    var justEvaluated by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(History.load(ctx)) }
    var deg by remember { mutableStateOf(Expr.degrees) }

    val preview = remember(input, deg) {
        if (input.isEmpty() || input.none { it in OPS || it == '%' || it.isLetter() || it == '√' || it == '!' }) ""
        else runCatching { Expr.format(Expr.eval(input.trimEnd { it in OPS })) }.getOrDefault("")
    }

    fun insert(t: String) {
        if (justEvaluated && (t[0].isDigit() || t[0] == ',' || t[0].isLetter() || t == "√" || t == "π")) { input = ""; cursor = 0 }
        justEvaluated = false
        val c = cursor.coerceIn(0, input.length)
        // не даём два оператора подряд — заменяем
        if (t.length == 1 && t[0] in OPS && c > 0 && input[c - 1] in OPS) { input = input.substring(0, c - 1) + t + input.substring(c); return }
        input = input.substring(0, c) + t + input.substring(c); cursor = c + t.length
    }

    fun press(k: String) {
        when (k) {
            "C" -> { input = ""; cursor = 0; justEvaluated = false }
            "⌫" -> { val c = cursor.coerceIn(0, input.length); if (c > 0) { input = input.removeRange(c - 1, c); cursor = c - 1 }; justEvaluated = false }
            "=" -> {
                if (input.isEmpty()) return
                runCatching { Expr.format(Expr.eval(input.trimEnd { it in OPS })) }
                    .onSuccess { r -> history = listOf("$input = $r") + history; History.save(ctx, history); input = r.replace(" ", ""); cursor = input.length; justEvaluated = true }
                    .onFailure { history = listOf("$input → ${it.message ?: "ошибка"}") + history }
            }
            "()" -> {
                val c = cursor.coerceIn(0, input.length)
                val before = input.substring(0, c)
                val open = before.count { it == '(' } - before.count { it == ')' }
                val last = before.lastOrNull()
                insert(if (open > 0 && last != null && (last.isDigit() || last == ')' || last == '%' || last == '!' || last == 'π')) ")" else "(")
            }
            "±" -> insert("−")
            "sin", "cos", "tan", "ln", "log" -> insert("$k(")
            "√" -> insert("√(")
            "x²" -> insert("^2")
            "xʸ" -> insert("^")
            "1/x" -> { input = "1÷(" + input + ")"; cursor = input.length }
            else -> {
                if (k == ",") {
                    val c = cursor.coerceIn(0, input.length)
                    val tail = input.substring(0, c).takeLastWhile { it.isDigit() || it == ',' }
                    if (tail.contains(',')) return
                    if (tail.isEmpty()) { insert("0,"); return }
                }
                insert(k)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), reverseLayout = true, horizontalAlignment = Alignment.End) {
            items(history.take(60)) { h ->
                Text(h, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().clickable { val v = h.substringAfter("= ", "").replace(" ", ""); if (v.isNotBlank()) { input = v; cursor = v.length } }.padding(vertical = 5.dp))
            }
            if (history.isNotEmpty()) item { TextButton({ history = emptyList(); History.save(ctx, history) }) { Text("Очистить историю") } }
        }
        CursorText(input, cursor, { cursor = it; justEvaluated = false }, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Text(preview, style = MaterialTheme.typography.headlineMedium, color = Pal.Muted, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        val sci = listOf(
            listOf(if (deg) "DEG" else "RAD", "sin", "cos", "tan", "π"),
            listOf("x²", "xʸ", "√", "ln", "log"),
            listOf("!", "e", "1/x", "(", ")"),
        )
        val rows = listOf(
            listOf("C", "()", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("⌫", "0", ",", "="),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scientific) sci.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Key(k, Pal.Lilac, Pal.LilacInk, 44.dp, Modifier.weight(1f)) {
                            if (k == "DEG" || k == "RAD") { Expr.degrees = !Expr.degrees; deg = Expr.degrees } else press(k)
                        }
                    }
                }
            }
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val (bg, fg) = when {
                            k == "=" -> Pal.Ink to Color.White
                            k[0] in OPS -> Pal.Butter to Pal.ButterInk
                            k in setOf("C", "()", "%", "⌫") -> Pal.Lilac to Pal.LilacInk
                            else -> Pal.Card to Pal.Ink
                        }
                        Key(k, bg, fg, if (scientific) 58.dp else 68.dp, Modifier.weight(1f)) { press(k) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(k: String, bg: Color, fg: Color, h: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(h).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(k, style = if (k.length > 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium, color = fg)
    }
}

@Composable
private fun Equations() {
    var eq by rememberSaveable { mutableStateOf("x^2−5x+6=0") }
    val res = remember(eq) { runCatching { Solver.solve(eq.replace('-', '−')) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTextField(eq, { eq = it }, "Уравнение с x, например 2x+3=11")
        Text("Можно: x^2, √(x), sin(x), скобки. Умножение на x можно не писать: 3x.", style = MaterialTheme.typography.bodySmall)
        AppCard(Modifier.fillMaxWidth(), color = Pal.Butter) {
            res.onSuccess { r -> r.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 3.dp)) } }
                .onFailure { Text(it.message ?: "Не получилось разобрать", style = MaterialTheme.typography.bodyLarge, color = Pal.RoseInk) }
        }
        res.getOrNull()?.roots?.takeIf { it.isNotEmpty() }?.let { roots ->
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Ответ")
                Text(roots.joinToString("   ") { "x = ${Expr.format(it)}" }, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
private fun Graph() {
    var f by rememberSaveable { mutableStateOf("sin(x)·2") }
    var range by rememberSaveable { mutableFloatStateOf(10f) }
    val fx = f.replace('·', '×')
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTextField(f, { f = it }, "y = … (функция от x)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2f, 5f, 10f, 50f).forEach { r -> Chip("±${r.toInt()}", range == r, { range = r }) }
            Chip(if (Expr.degrees) "градусы" else "радианы", false, { Expr.degrees = !Expr.degrees; f = "$f " ; f = f.trimEnd() })
        }
        AppCard(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
            Canvas(Modifier.fillMaxSize()) {
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
        Text("Масштаб по Y подбирается сам. Ось X: от −${range.toInt()} до ${range.toInt()}.", style = MaterialTheme.typography.labelSmall)
    }
}
