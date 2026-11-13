package com.pank.vsevodnom.ui.device

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.device.InfoRow
import com.pank.vsevodnom.data.device.InfoSection
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Единый вид «Анализатора устройства»: все разделы собраны из этих деталей —
 * карточка-секция, плитка, кольцо, полоса, живой график, чипы. Шесть приложений-образцов — один стиль.
 */
object DevColors {
    val series = listOf(Color(0xFF5B8DEF), Color(0xFF3FB27F), Color(0xFFF2994A), Color(0xFFE5484D), Color(0xFF9B6CE6), Color(0xFF17A2B8),
        Color(0xFFD4A017), Color(0xFFEC6CB9), Color(0xFF6B7A8F), Color(0xFF8BC34A))
    val good = Color(0xFF2F9E6A); val warn = Color(0xFFD08A19); val bad = Color(0xFFD9483B); val off = Color(0xFFB7B5BE)
    fun level(f: Float) = when { f < 0.6f -> good; f < 0.85f -> warn; else -> bad }
}

/** Страница раздела: шапка + прокручиваемый список. */
@Composable
fun DevPage(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    ScreenScaffold(title, onBack, actions) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Загрузка секций в фоне (чтение /sys, Camera2 и т.п. — не на главном потоке). */
@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): T? {
    var v by remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) { v = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } }
    return v
}

@Composable
fun Loading(text: String = "Собираю данные…") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(text, color = Pal.Muted)
    }
}

/** Карточка-секция «ключ — значение». Долгое нажатие на строку — скопировать значение. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionCard(s: InfoSection, tint: Tint = Tints.Lilac, maxRows: Int = Int.MAX_VALUE) {
    if (s.rows.isEmpty() && s.note == null) return
    val ctx = LocalContext.current
    var all by remember(s) { mutableStateOf(s.rows.size <= maxRows) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card)) {
        if (s.title.isNotBlank()) Row(Modifier.fillMaxWidth().background(tint.bg.copy(alpha = 0.55f)).padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint.ink))
            Text("  " + s.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = tint.ink, modifier = Modifier.weight(1f))
            Text("${s.rows.size}", style = MaterialTheme.typography.labelSmall, color = tint.ink.copy(alpha = 0.6f))
        }
        (if (all) s.rows else s.rows.take(maxRows)).forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Pal.Line))
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(r.key, r.value))
                Toast.makeText(ctx, "Скопировано: ${r.key}", Toast.LENGTH_SHORT).show()
            }).padding(16.dp, 9.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(0.44f).padding(end = 10.dp)) {
                    Text(r.key, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    r.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pal.Muted.copy(alpha = 0.7f)) }
                }
                if (r.ok != null) Row(Modifier.weight(0.56f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(if (r.ok) DevColors.good.copy(alpha = 0.15f) else DevColors.off.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(if (r.ok) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(14.dp), tint = if (r.ok) DevColors.good else DevColors.off)
                    }
                    Text("  " + r.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (r.ok) Pal.Ink else Pal.Muted)
                } else Text(r.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(0.56f))
            }
        }
        if (!all) Text("Показать все (${s.rows.size})", style = MaterialTheme.typography.labelLarge, color = tint.ink,
            modifier = Modifier.fillMaxWidth().clickable { all = true }.padding(16.dp, 12.dp))
        s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp)) }
        if (s.note == null) Spacer(Modifier.height(4.dp))
    }
}

fun LazyListScope.sections(list: List<InfoSection>?, tint: Tint = Tints.Lilac, maxRows: Int = Int.MAX_VALUE) {
    if (list == null) { item { Loading() }; return }
    list.forEachIndexed { i, s -> item(key = "sec$i${s.title}") { SectionCard(s, tint, maxRows) } }
}

/** Плитка с цифрой: подпись, крупное значение, пояснение. */
@Composable
fun StatTile(label: String, value: String, sub: String? = null, tint: Tint = Tints.Sky, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Pal.Card).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint.ink)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 2) }
        extra()
    }
}

/** Кольцо заполнения с подписью в центре. */
@Composable
fun Ring(fraction: Float, size: Dp, color: Color, stroke: Dp = 9.dp, track: Color = Pal.Line, content: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx(); val r = Size(this.size.width - w, this.size.height - w); val o = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, o, r, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, o, r, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Полоса заполнения. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Pal.Line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** Сегментированная полоса (например, ОЗУ: приложения / кэш / свободно). */
@Composable
fun StackBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Pal.Line)) {
        val sum = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        parts.filter { it.first > 0 }.forEach { (f, c) -> Box(Modifier.weight(f / sum).fillMaxHeight().background(c)) }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (t, c) -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Text(" $t", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) } }
    }
}

@Composable
fun TagChips(tags: List<String>, tint: Tint = Tints.Sky) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = tint.ink, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(tint.bg).padding(8.dp, 3.dp)) }
    }
}

// ---------------- живые данные и графики ----------------

/** Ряд значений во времени. */
class Series(val name: String, val color: Color, cap: Int = 90) {
    val values = mutableStateListOf<Float>()
    private val capacity = cap
    /** Добавлять только из главного потока: правка списка из фона во время отрисовки роняла приложение
     *  (ConcurrentModificationException — так вылетал тест скорости в 0.44). Из фона — пересылаем на главный. */
    fun push(v: Float) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) pushNow(v) else main.post { pushNow(v) }
    }
    private fun pushNow(v: Float) { values.add(v); if (values.size > capacity) values.removeAt(0) }
    companion object { private val main = android.os.Handler(android.os.Looper.getMainLooper()) }
    val last: Float? get() = values.lastOrNull()
}

/** Опрос раз в [periodMs]: [sample] выполняется в фоне и раскладывает значения по рядам. */
@Composable
fun LiveLoop(periodMs: Long, vararg keys: Any?, sample: suspend () -> Unit) {
    LaunchedEffect(*keys) { while (true) { withContext(Dispatchers.IO) { runCatching { sample() } }; delay(periodMs) } }
}

/**
 * Живой график: сглаженные линии, заливка под одиночной линией, сетка и подписи шкалы.
 * yMax = null — шкала по максимуму данных.
 */
@Composable
fun LiveChart(series: List<Series>, modifier: Modifier = Modifier, height: Dp = 150.dp, yMin: Float = 0f, yMax: Float? = null, unit: String = "", fill: Boolean = series.size == 1, label: (Float) -> String = { v -> if (v >= 100 || v == v.toInt().toFloat()) v.toInt().toString() else com.pank.vsevodnom.data.device.Dev.fmt(v, 1) }) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = series.flatMap { it.values }
        val top = (yMax ?: (all.maxOrNull() ?: 1f).let { if (it <= yMin) yMin + 1f else it * 1.12f })
        val left = 0f; val right = size.width - 56f; val h = size.height - 6f
        val p = android.graphics.Paint().apply { color = Pal.Muted.toArgb(); textSize = 26f; isAntiAlias = true }
        for (i in 0..3) {
            val y = 3f + h * i / 3f
            drawLine(Pal.Line, Offset(left, y), Offset(right, y), 2f)
            drawContext.canvas.nativeCanvas.drawText(label(top - (top - yMin) * i / 3f) + if (i == 0) " $unit" else "", right + 8f, y + 9f, p)
        }
        series.forEach { s ->
            val v = s.values.toList(); if (v.size < 2) return@forEach
            val cap = 90
            val dx = (right - left) / (cap - 1)
            val x0 = right - dx * (v.size - 1)
            fun y(f: Float) = 3f + h * (1 - ((f - yMin) / (top - yMin)).coerceIn(0f, 1f))
            val path = Path(); path.moveTo(x0, y(v[0]))
            for (i in 1 until v.size) {
                val px = x0 + dx * (i - 1); val cx = x0 + dx * i
                path.cubicTo(px + dx / 2, y(v[i - 1]), cx - dx / 2, y(v[i]), cx, y(v[i]))
            }
            if (fill) {
                val f = Path().apply { addPath(path); lineTo(right, 3f + h); lineTo(x0, 3f + h); close() }
                drawPath(f, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0.02f))))
            }
            drawPath(path, s.color, style = Stroke(4f, cap = StrokeCap.Round))
            drawCircle(s.color, 6f, Offset(right, y(v.last())))
        }
    }
}

/** Плитка-переход в разделе. */
@Composable
fun NavTile(icon: ImageVector, title: String, sub: String, tint: Tint, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.height(118.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable(onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint.ink, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
    }
}

/** Заголовок группы на странице. */
@Composable
fun GroupTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(top = 6.dp, start = 4.dp))

/** Строки «ключ — значение» без карточки (для шапок с живыми цифрами). */
@Composable
fun KV(k: String, v: String, color: Color = Pal.Ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).padding(16.dp), content = content)

fun rowsText(s: List<InfoRow>) = s.joinToString("\n") { "${it.key}: ${it.value}" }
