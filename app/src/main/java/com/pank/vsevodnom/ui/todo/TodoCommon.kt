package com.pank.vsevodnom.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay

/** Цвет акцента раздела ToDo — в палитре приложения. */
val ACCENT = Pal.LilacInk

/**
 * Приоритет: 0 нет, 1 низкий (зелёный), 2 средний (жёлтый), 3 высокий (красный).
 * Хранится в TaskItem.priority.
 */
val PRIO = listOf("Без приоритета" to Color(0xFFB8BCC6), "Низкий" to Color(0xFF3FB27F), "Средний" to Color(0xFFF2B635), "Высокий" to Color(0xFFE5485D))
fun prioColor(p: Int) = PRIO[p.coerceIn(0, 3)].second

/** Строка, которая при выполнении сначала показывает галочку, а потом плавно уезжает. */
@Composable
fun Leaving(key: Any, onLeft: () -> Unit, content: @Composable (done: Boolean, leave: () -> Unit) -> Unit) {
    val vis = remember(key) { MutableTransitionState(true) }
    var done by remember(key) { mutableStateOf(false) }
    var fired by remember(key) { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(380); vis.targetState = false } }
    LaunchedEffect(vis.isIdle, vis.currentState) { if (!vis.currentState && vis.isIdle && !fired) { fired = true; onLeft() } }
    AnimatedVisibility(vis, exit = fadeOut(tween(260)) + shrinkVertically(tween(320))) { content(done) { done = true } }
}

/** Круглая галочка с анимацией: заливка растёт из центра, галочка дорисовывается. */
@Composable
fun CheckCircle(checked: Boolean, color: Color, size: Dp = 28.dp, onClick: () -> Unit) {
    val p by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 380f), label = "chk")
    val pressScale by animateFloatAsState(if (checked) 1f else 1f, label = "s")
    Box(Modifier.size(size + 8.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).scale(pressScale * (1f + 0.12f * kotlin.math.sin(p * Math.PI).toFloat()))) {
            val r = this.size.minDimension / 2
            drawCircle(color.copy(alpha = 0.16f + 0.0f * p), r)
            drawCircle(color, r, style = Stroke(r * 0.16f))
            if (p > 0f) drawCircle(color, r * p.coerceIn(0f, 1f))
            if (p > 0.3f) {
                val t = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
                val a = Offset(r * 0.55f, r * 1.02f); val b = Offset(r * 0.88f, r * 1.34f); val c = Offset(r * 1.46f, r * 0.68f)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    if (t < 0.4f) lineTo(a.x + (b.x - a.x) * t / 0.4f, a.y + (b.y - a.y) * t / 0.4f)
                    else { lineTo(b.x, b.y); val k = (t - 0.4f) / 0.6f; lineTo(b.x + (c.x - b.x) * k, b.y + (c.y - b.y) * k) }
                }
                drawPath(path, Color.White, style = Stroke(r * 0.2f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Огонёк серии, как в Duolingo: горит, если сегодня уже отмечено; серый — если серия под угрозой. */
@Composable
fun StreakFlame(n: Int, active: Boolean, size: Dp = 22.dp) {
    val inf = rememberInfiniteTransition(label = "fl")
    val flick by inf.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(size).scale(if (active && n > 0) flick else 1f)) {
            val w = this.size.width; val h = this.size.height
            val outer = Path().apply {
                moveTo(w * 0.5f, 0f)
                cubicTo(w * 0.62f, h * 0.22f, w * 0.95f, h * 0.38f, w * 0.92f, h * 0.66f)
                cubicTo(w * 0.9f, h * 0.9f, w * 0.7f, h, w * 0.5f, h)
                cubicTo(w * 0.28f, h, w * 0.08f, h * 0.88f, w * 0.08f, h * 0.64f)
                cubicTo(w * 0.08f, h * 0.44f, w * 0.26f, h * 0.36f, w * 0.3f, h * 0.18f)
                cubicTo(w * 0.42f, h * 0.3f, w * 0.44f, h * 0.12f, w * 0.5f, 0f); close()
            }
            val inner = Path().apply {
                moveTo(w * 0.52f, h * 0.42f)
                cubicTo(w * 0.62f, h * 0.56f, w * 0.74f, h * 0.66f, w * 0.7f, h * 0.8f)
                cubicTo(w * 0.66f, h * 0.94f, w * 0.34f, h * 0.94f, w * 0.31f, h * 0.8f)
                cubicTo(w * 0.29f, h * 0.68f, w * 0.42f, h * 0.6f, w * 0.52f, h * 0.42f); close()
            }
            if (active && n > 0) {
                drawPath(outer, Brush.verticalGradient(listOf(Color(0xFFFFB02E), Color(0xFFFF6B1A))))
                drawPath(inner, Brush.verticalGradient(listOf(Color(0xFFFFF1A6), Color(0xFFFFC93C))))
            } else {
                drawPath(outer, Color(0xFFD5D8DE)); drawPath(inner, Color(0xFFEDEFF2))
            }
        }
        Text(" $n", style = MaterialTheme.typography.titleSmall, color = if (active && n > 0) Color(0xFFE8711A) else Pal.Muted)
    }
}

/** Карточка-раздел формы: подпись и содержимое на полупрозрачной подложке. */
@Composable
fun FormCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
        content()
    }
}

/** Варианты ровной сеткой (а не «плавающими» чипами): невыбранные — полупрозрачные плитки. */
@Composable
fun <T> ChoiceGrid(items: List<Pair<T, String>>, selected: (T) -> Boolean, columns: Int = 3, onClick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, label) ->
                    val sel = selected(k)
                    Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) ACCENT else Pal.Ink.copy(alpha = 0.06f)).clickable { onClick(k) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Pal.Ink, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Выбор приоритета — три точки (красная, жёлтая, зелёная) и «без». */
@Composable
fun PriorityDots(value: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(3, 2, 1, 0).forEach { p ->
            val sel = value == p
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) prioColor(p).copy(alpha = 0.16f) else Pal.Ink.copy(alpha = 0.04f))
                .clickable { onChange(p) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (sel) 22.dp else 18.dp).clip(CircleShape).then(if (p == 0) Modifier.border(2.dp, prioColor(p), CircleShape) else Modifier.background(prioColor(p))))
                Text(listOf("Нет", "Низкий", "Средний", "Высокий")[p], style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), color = if (sel) Pal.Ink else Pal.Muted)
            }
        }
    }
}

/** Поле с разметкой и панелью: жирный, курсив, H1–H3, точки, нумерация, чек-лист. */
@Composable
fun RichField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, placeholder: String, modifier: Modifier = Modifier, minHeight: Dp = 90.dp, toolbarOnTop: Boolean = false) {
    val bar: @Composable () -> Unit = { RichToolbar(value, onChange) }
    Column(modifier) {
        if (toolbarOnTop) bar()
        BasicTextField(value, { onChange(Rich.onEdit(value, it)) }, Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 6.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pal.Ink, lineHeight = 24.sp), cursorBrush = SolidColor(ACCENT), visualTransformation = Rich.transformation,
            decorationBox = { inner -> if (value.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB8BCC6)); inner() })
        if (!toolbarOnTop) bar()
    }
}

@Composable
fun RichToolbar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.05f)).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun B(ic: androidx.compose.ui.graphics.vector.ImageVector, d: String, f: () -> TextFieldValue) = IconButton({ onChange(f()) }) { Icon(ic, d, tint = Pal.Ink) }
        @Composable fun T(label: String, d: String, f: () -> TextFieldValue) = Box(Modifier.size(44.dp).clip(CircleShape).clickable { onChange(f()) }, contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleSmall, color = Pal.Ink) }
        B(Icons.Rounded.FormatBold, "Жирный") { Rich.wrap(value, "**") }
        B(Icons.Rounded.FormatItalic, "Курсив") { Rich.wrap(value, "*") }
        B(Icons.Rounded.FormatUnderlined, "Подчеркнуть") { Rich.wrap(value, "__") }
        B(Icons.Rounded.FormatStrikethrough, "Зачеркнуть") { Rich.wrap(value, "~~") }
        T("H1", "Заголовок 1") { Rich.linePrefix(value, "# ") }
        T("H2", "Заголовок 2") { Rich.linePrefix(value, "## ") }
        T("H3", "Заголовок 3") { Rich.linePrefix(value, "### ") }
        B(Icons.AutoMirrored.Rounded.FormatListBulleted, "Точки") { Rich.linePrefix(value, "- ") }
        B(Icons.Rounded.FormatListNumbered, "Нумерация") { Rich.linePrefix(value, "num") }
        B(Icons.Rounded.CheckBox, "Пункт дела / отметить") { Rich.toggleCheckAtCursor(value) }
        B(Icons.Rounded.FormatQuote, "Цитата") { Rich.linePrefix(value, "> ") }
    }
}
