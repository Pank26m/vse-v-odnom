package com.pank.vsevodnom.ui.calc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Разбор выражения: + − × ÷, скобки, проценты, унарный минус. Считаем в BigDecimal, без ошибок вида 0.1+0.2. */
object Expr {
    private val MC = MathContext(20, RoundingMode.HALF_UP)

    class P(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun expr(): BigDecimal {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; val r = term(); v = v.add(pctOf(v, r), MC) }
                    '−', '-' -> { i++; val r = term(); v = v.subtract(pctOf(v, r), MC) }
                    else -> return v
                }
            }
        }
        // «200 + 10%» = 220, как на обычном калькуляторе
        private var lastWasPct = false
        private fun pctOf(base: BigDecimal, r: BigDecimal): BigDecimal =
            if (lastWasPct) { lastWasPct = false; base.multiply(r, MC) } else r

        fun term(): BigDecimal {
            var v = factor()
            while (true) {
                when (peek()) {
                    '×', '*' -> { i++; v = v.multiply(factor(), MC); lastWasPct = false }
                    '÷', '/' -> {
                        i++
                        val d = factor(); lastWasPct = false
                        if (d.signum() == 0) throw ArithmeticException("Деление на ноль")
                        v = v.divide(d, MC)
                    }
                    else -> return v
                }
            }
        }
        fun factor(): BigDecimal {
            if (peek() == '−' || peek() == '-') { i++; return factor().negate() }
            var v: BigDecimal
            if (peek() == '(') {
                i++; v = expr()
                if (peek() == ')') i++
            } else {
                val st = i
                while (peek().isDigit() || peek() == ',' || peek() == '.') i++
                if (st == i) throw IllegalArgumentException("Ошибка")
                v = BigDecimal(s.substring(st, i).replace(',', '.'))
            }
            lastWasPct = false
            if (peek() == '%') { i++; v = v.divide(BigDecimal(100), MC); lastWasPct = true }
            return v
        }
    }

    fun eval(s: String): BigDecimal {
        val open = s.count { it == '(' } - s.count { it == ')' }
        val p = P(s + ")".repeat(open.coerceAtLeast(0)))
        val v = p.expr()
        if (p.i != p.s.length) throw IllegalArgumentException("Ошибка")
        return v
    }

    fun format(v: BigDecimal): String {
        val r = v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = if (r.abs() >= BigDecimal("1e12") || (r.signum() != 0 && r.abs() < BigDecimal("1e-9"))) r.toString()
        else r.toPlainString()
        // разделяем тысячи пробелом, дробь — запятой
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        if (body.contains('E')) return (if (neg) "−" else "") + body.replace('.', ',')
        val (intPart, frac) = body.split('.').let { it[0] to it.getOrNull(1) }
        val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
        return (if (neg) "−" else "") + grouped + (frac?.let { ",$it" } ?: "")
    }
}

private val OPS = setOf('+', '−', '×', '÷')

@Composable
fun CalcScreen(onBack: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var justEvaluated by rememberSaveable { mutableStateOf(false) }
    val history = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    val clip = LocalClipboardManager.current

    val preview = remember(input) {
        if (input.isEmpty() || input.none { it in OPS || it == '%' }) ""
        else runCatching { Expr.format(Expr.eval(input.trimEnd { it in OPS })) }.getOrDefault("")
    }

    fun press(k: String) {
        when (k) {
            "C" -> { input = ""; justEvaluated = false }
            "⌫" -> { input = input.dropLast(1); justEvaluated = false }
            "=" -> {
                if (input.isEmpty()) return
                val res = runCatching { Expr.format(Expr.eval(input.trimEnd { it in OPS })) }
                res.onSuccess { r ->
                    history.add(0, "$input = $r")
                    if (history.size > 50) history.removeAt(history.lastIndex)
                    input = r.replace(" ", "").replace('−', '−')
                    justEvaluated = true
                }.onFailure { input = ""; history.add(0, it.message ?: "Ошибка") }
            }
            "()" -> {
                val open = input.count { it == '(' } - input.count { it == ')' }
                val last = input.lastOrNull()
                input += if (open > 0 && last != null && (last.isDigit() || last == ')' || last == '%')) ")" else "("
                justEvaluated = false
            }
            "±" -> {
                // меняем знак у последнего числа
                val idx = input.indexOfLast { !(it.isDigit() || it == ',') } + 1
                input = if (idx > 0 && input[idx - 1] == '−' && (idx == 1 || input[idx - 2] in OPS || input[idx - 2] == '('))
                    input.removeRange(idx - 1, idx)
                else input.substring(0, idx) + "−" + input.substring(idx)
            }
            else -> {
                val c = k[0]
                if (c in OPS) {
                    if (input.isEmpty()) { if (c == '−') input = "−"; return }
                    input = if (input.last() in OPS) input.dropLast(1) + c else input + c
                    justEvaluated = false
                    return
                }
                if (justEvaluated && (c.isDigit() || c == ',')) input = ""
                justEvaluated = false
                if (c == ',') {
                    val tail = input.takeLastWhile { it.isDigit() || it == ',' }
                    if (tail.contains(',')) return
                    if (tail.isEmpty()) { input += "0,"; return }
                }
                input += k
            }
        }
    }

    ScreenScaffold("Калькулятор", onBack) {
        val listState = rememberLazyListState()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            state = listState, reverseLayout = true, horizontalAlignment = Alignment.End
        ) {
            items(history) { h ->
                Text(
                    h, style = MaterialTheme.typography.bodyLarge, color = Pal.Muted, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().clickable { input = h.substringAfter("= ").replace(" ", "") }.padding(vertical = 6.dp)
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.End) {
            Text(
                input.ifEmpty { "0" },
                style = MaterialTheme.typography.displayMedium.copy(fontSize = if (input.length > 12) 36.sp else 56.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { clip.setText(AnnotatedString(input)) }
            )
            Text(preview, style = MaterialTheme.typography.headlineMedium, color = Pal.Muted, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        val rows = listOf(
            listOf("C", "()", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ",", "="),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        val (bg, fg) = when {
                            k == "=" -> Pal.Ink to Color.White
                            k[0] in OPS -> Pal.Butter to Pal.ButterInk
                            k == "C" || k == "()" || k == "%" -> Pal.Lilac to Pal.LilacInk
                            else -> Pal.Card to Pal.Ink
                        }
                        Box(
                            Modifier.weight(1f).height(68.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                                .clickable { press(k) },
                            contentAlignment = Alignment.Center
                        ) { Text(k, style = MaterialTheme.typography.headlineMedium, color = fg) }
                    }
                }
            }
            Row {
                Spacer(Modifier.weight(3f))
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { press("⌫") },
                    contentAlignment = Alignment.Center
                ) { Text("⌫", style = MaterialTheme.typography.titleLarge, color = Pal.Muted) }
            }
        }
    }
}
