package com.pank.vsevodnom.ui.todo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Простое форматирование заметок (хранится как текст с метками):
 * **жирный**, *курсив*, __подчёркнутый__, ~~зачёркнутый~~, «# » заголовок, «- » точки, «1. » нумерация, «[ ] » / «[x] » чек-лист.
 */
object Rich {
    private val INLINE = listOf(
        Regex("\\*\\*(.+?)\\*\\*") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("__(.+?)__") to SpanStyle(textDecoration = TextDecoration.Underline),
        Regex("~~(.+?)~~") to SpanStyle(textDecoration = TextDecoration.LineThrough),
        Regex("(?<![*\\w])\\*(?!\\*)(.+?)(?<!\\*)\\*(?![*\\w])") to SpanStyle(fontStyle = FontStyle.Italic),
    )
    private val MARK = SpanStyle(color = Color(0xFFB8BCC6), fontSize = 12.sp)
    val NUM = Regex("^(\\d+)\\. ")

    /** Для поля ввода: метки видны бледными, текст между ними уже оформлен. Позиции не сдвигаются. */
    fun styled(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        var pos = 0
        text.split('\n').forEach { line ->
            when {
                line.startsWith("### ") -> { addStyle(MARK, pos, pos + 4); addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp), pos + 4, pos + line.length) }
                line.startsWith("## ") -> { addStyle(MARK, pos, pos + 3); addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 19.sp), pos + 3, pos + line.length) }
                line.startsWith("# ") -> { addStyle(MARK, pos, pos + 2); addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), pos + 2, pos + line.length) }
                line.startsWith("> ") -> { addStyle(MARK, pos, pos + 2); addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF6B6A75)), pos + 2, pos + line.length) }
                line.startsWith("- ") || line.startsWith("• ") -> addStyle(SpanStyle(color = Color(0xFF4772F5), fontWeight = FontWeight.Bold), pos, pos + 1)
                line.startsWith("[x] ") -> { addStyle(SpanStyle(color = Color(0xFF4772F5)), pos, pos + 3); addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF9AA0AA)), pos + 4, pos + line.length) }
                line.startsWith("[ ] ") -> addStyle(SpanStyle(color = Color(0xFF4772F5)), pos, pos + 3)
                NUM.containsMatchIn(line) -> NUM.find(line)?.let { addStyle(SpanStyle(color = Color(0xFF4772F5), fontWeight = FontWeight.Bold), pos, pos + it.value.length) }
            }
            INLINE.forEach { (re, st) ->
                re.findAll(line).forEach { m ->
                    val open = (m.value.length - m.groupValues[1].length) / 2
                    addStyle(MARK, pos + m.range.first, pos + m.range.first + open)
                    addStyle(MARK, pos + m.range.last + 1 - open, pos + m.range.last + 1)
                    addStyle(st, pos + m.range.first + open, pos + m.range.last + 1 - open)
                }
            }
            pos += line.length + 1
        }
    }

    val transformation = VisualTransformation { t -> TransformedText(styled(t.text), OffsetMapping.Identity) }

    /** Для плиток и просмотра: метки убраны. */
    fun render(text: String): AnnotatedString = buildAnnotatedString {
        text.split('\n').forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            var line = raw
            var lineStyle: SpanStyle? = null
            when {
                line.startsWith("### ") -> { line = line.drop(4); lineStyle = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                line.startsWith("## ") -> { line = line.drop(3); lineStyle = SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                line.startsWith("# ") -> { line = line.drop(2); lineStyle = SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
                line.startsWith("> ") -> { append("┃ "); line = line.drop(2); lineStyle = SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF6B6A75)) }
                line.startsWith("- ") || line.startsWith("• ") -> { append("•  "); line = line.drop(2) }
                line.startsWith("[x] ") -> { append("☑ "); line = line.drop(4); lineStyle = SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF9AA0AA)) }
                line.startsWith("[ ] ") -> { append("☐ "); line = line.drop(4) }
            }
            val start = length
            // разбираем inline-метки последовательно
            var rest = line
            while (rest.isNotEmpty()) {
                val hit = INLINE.mapNotNull { (re, st) -> re.find(rest)?.let { it to st } }.minByOrNull { it.first.range.first }
                if (hit == null) { append(rest); break }
                val (m, st) = hit
                append(rest.substring(0, m.range.first))
                val s0 = length; append(m.groupValues[1]); addStyle(st, s0, length)
                rest = rest.substring(m.range.last + 1)
            }
            lineStyle?.let { addStyle(it, start, length) }
        }
    }

    /** Обернуть выделение метками (или вставить пару и поставить курсор внутрь). */
    fun wrap(v: TextFieldValue, mark: String): TextFieldValue {
        val s = v.selection.min; val e = v.selection.max
        val t = v.text
        if (s != e && t.substring(s, e).let { it.startsWith(mark) && it.endsWith(mark) && it.length >= mark.length * 2 }) {
            val inner = t.substring(s + mark.length, e - mark.length)
            return TextFieldValue(t.substring(0, s) + inner + t.substring(e), TextRange(s, s + inner.length))
        }
        val nt = t.substring(0, s) + mark + t.substring(s, e) + mark + t.substring(e)
        return TextFieldValue(nt, if (s == e) TextRange(s + mark.length) else TextRange(s + mark.length, e + mark.length))
    }

    /** Переключить префикс строки (заголовок, точки, нумерация, чек-лист) для текущей строки. */
    fun linePrefix(v: TextFieldValue, kind: String): TextFieldValue {
        val t = v.text
        val ls = t.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(0)).let { if (v.selection.min == 0) 0 else it + 1 }.coerceAtMost(t.length)
        val le = t.indexOf('\n', ls).let { if (it < 0) t.length else it }
        val line = t.substring(ls, le)
        val existing = listOf("### ", "## ", "# ", "> ", "- ", "• ", "[ ] ", "[x] ").firstOrNull { line.startsWith(it) } ?: NUM.find(line)?.value
        val bare = existing?.let { line.removePrefix(it) } ?: line
        val prefix = when (kind) {
            "num" -> { val prev = t.substring(0, ls).trimEnd('\n').substringAfterLast('\n'); (NUM.find(prev)?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1).toString() + ". " }
            else -> kind
        }
        val same = existing != null && (existing == prefix || (kind == "num" && NUM.matches(existing.trimEnd() + " ")) )
        val newLine = if (same) bare else prefix + bare
        val nt = t.substring(0, ls) + newLine + t.substring(le)
        val shift = newLine.length - line.length
        return TextFieldValue(nt, TextRange((v.selection.end + shift).coerceIn(ls, ls + newLine.length)))
    }

    /** Enter в списке продолжает список; Enter на пустом пункте — заканчивает его. */
    fun onEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return new
        val p = new.selection.start - 1
        if (p < 0 || new.text[p] != '\n') return new
        val prevStart = new.text.lastIndexOf('\n', p - 1) + 1
        val prev = new.text.substring(prevStart, p)
        val prefix = when {
            prev.startsWith("- ") -> "- "; prev.startsWith("• ") -> "• "
            prev.startsWith("[ ] ") || prev.startsWith("[x] ") -> "[ ] "
            NUM.containsMatchIn(prev) -> (NUM.find(prev)!!.groupValues[1].toInt() + 1).toString() + ". "
            else -> return new
        }
        val prevPrefix = listOf("- ", "• ", "[ ] ", "[x] ").firstOrNull { prev.startsWith(it) } ?: NUM.find(prev)!!.value
        if (prev == prevPrefix) { // пустой пункт — выходим из списка
            val nt = new.text.substring(0, prevStart) + new.text.substring(p + 1)
            return TextFieldValue(nt, TextRange(prevStart))
        }
        val nt = new.text.substring(0, p + 1) + prefix + new.text.substring(p + 1)
        return TextFieldValue(nt, TextRange(p + 1 + prefix.length))
    }

    /** Кнопка чек-листа: обычная строка → «[ ] », «[ ] » → «[x] » (зачёркнуто), «[x] » → «[ ] ». */
    fun toggleCheckAtCursor(v: TextFieldValue): TextFieldValue {
        val t = v.text
        val ls = if (v.selection.min == 0) 0 else t.lastIndexOf('\n', v.selection.min - 1) + 1
        val le = t.indexOf('\n', ls).let { if (it < 0) t.length else it }
        val line = t.substring(ls, le)
        val nl = when { line.startsWith("[ ] ") -> "[x] " + line.drop(4); line.startsWith("[x] ") -> "[ ] " + line.drop(4); else -> return linePrefix(v, "[ ] ") }
        return TextFieldValue(t.substring(0, ls) + nl + t.substring(le), v.selection)
    }

    /** Отметить/снять пункт чек-листа в строке номер idx (для просмотра). */
    fun toggleCheck(text: String, idx: Int): String = text.split('\n').mapIndexed { i, l ->
        if (i != idx) l else if (l.startsWith("[ ] ")) "[x] " + l.drop(4) else if (l.startsWith("[x] ")) "[ ] " + l.drop(4) else l
    }.joinToString("\n")
}
