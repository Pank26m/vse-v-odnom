package com.pank.vsevodnom.ui.markets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.CATALOG
import com.pank.vsevodnom.data.MarketsApi
import com.pank.vsevodnom.data.Quote
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Up = Color(0xFF2F7D57)
private val Down = Color(0xFFA8456A)

fun fmtPrice(v: Double): String {
    val digits = when { v >= 1000 -> 0; v >= 10 -> 2; v >= 1 -> 3; else -> 4 }
    val s = String.format(Locale.US, "%,.${digits}f", v)
    return s.replace(",", " ").replace('.', ',')
}

fun fmtPct(p: Double) = (if (p >= 0) "+" else "−") + String.format(Locale.US, "%.2f", kotlin.math.abs(p)).replace('.', ',') + "%"

/** Кэш котировок на время работы приложения. */
object QuoteCache { val map = mutableStateMapOf<String, Quote>() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarketsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(MarketsApi.watchlist(ctx)) }
    val errors = remember { mutableStateMapOf<String, String>() }
    var tick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(list, tick) {
        while (true) {
            loading = true
            coroutineScope {
                list.map { s ->
                    async {
                        runCatching { MarketsApi.quote(s) }
                            .onSuccess { QuoteCache.map[s] = it; errors.remove(s) }
                            .onFailure { errors[s] = "нет данных" }
                    }
                }.awaitAll()
            }
            loading = false
            delay(60_000)
        }
    }

    val d = detail
    if (d != null) { QuoteDetail(d) { detail = null }; return }

    ScreenScaffold("Курсы", onBack, actions = {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { tick++ })
        RoundIconButton(Icons.Rounded.Add, "Добавить", { adding = true }, bg = Pal.Ink, tint = Color.White)
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it }) { s ->
                val q = QuoteCache.map[s]
                Box(Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { detail = s }, onLongClick = { removing = s })) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(MarketsApi.nameOf(ctx, s), style = MaterialTheme.typography.titleMedium)
                                Text(s, style = MaterialTheme.typography.labelSmall)
                            }
                            if (q != null && q.closes.size > 1) Sparkline(q.closes, Modifier.width(80.dp).height(32.dp), (q.changePct ?: 0.0) >= 0)
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(q?.let { fmtPrice(it.price) } ?: (errors[s] ?: "…"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                                q?.changePct?.let { Text(fmtPct(it), style = MaterialTheme.typography.labelLarge, color = if (it >= 0) Up else Down) }
                            }
                        }
                    }
                }
            }
            item { Text("Нажми — график, удерживай — убрать из списка. Данные: Yahoo Finance, НБУ, CoinGecko. Не инвестиционный совет.", style = MaterialTheme.typography.labelSmall) }
        }
    }

    if (adding) AddInstrument(list, onDismiss = { adding = false }) { s ->
        list = list + s; MarketsApi.setWatchlist(ctx, list); adding = false
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Убрать ${MarketsApi.nameOf(ctx, s)}?") },
            confirmButton = { TextButton({ list = list - s; MarketsApi.setWatchlist(ctx, list); removing = null }) { Text("Убрать", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ removing = null }) { Text("Отмена") } }
        )
    }
}

@Composable
fun Sparkline(v: List<Double>, modifier: Modifier, up: Boolean) {
    Canvas(modifier) {
        if (v.size < 2) return@Canvas
        val lo = v.min(); val hi = v.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val p = Path()
        v.forEachIndexed { i, y ->
            val x = i / (v.size - 1f) * size.width
            val yy = size.height - ((y - lo) / span).toFloat() * size.height
            if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy)
        }
        drawPath(p, (if (up) Up else Down).copy(alpha = 0.7f), style = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun QuoteDetail(symbol: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ranges = listOf("1d" to "День", "5d" to "Неделя", "1mo" to "Месяц", "6mo" to "Полгода", "1y" to "Год", "5y" to "5 лет")
    var range by remember { mutableStateOf("1mo") }
    var q by remember { mutableStateOf(QuoteCache.map[symbol]) }
    var err by remember { mutableStateOf<String?>(null) }
    var touch by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(range) {
        runCatching { MarketsApi.quote(symbol, range) }.onSuccess { q = it; err = null }.onFailure { err = "Не удалось загрузить график" }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    ScreenScaffold(MarketsApi.nameOf(ctx, symbol), onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val cur = q
            if (cur != null) {
                val first = cur.closes.firstOrNull()
                val shownIdx = touch?.coerceIn(0, cur.closes.lastIndex)
                val shownPrice = shownIdx?.let { cur.closes[it] } ?: cur.price
                Text(fmtPrice(shownPrice) + if (cur.currency.isNotBlank()) " ${cur.currency}" else "", style = MaterialTheme.typography.displayMedium)
                val ch = if (range == "1d") cur.changePct else first?.let { (cur.price - it) / it * 100 }
                Row {
                    if (shownIdx != null) {
                        val t = Instant.ofEpochSecond(cur.times[shownIdx]).atZone(ZoneId.systemDefault())
                        Text(t.format(DateTimeFormatter.ofPattern(if (range in listOf("1d", "5d")) "dd.MM HH:mm" else "dd.MM.yyyy")), style = MaterialTheme.typography.bodySmall)
                    } else ch?.let { Text("${fmtPct(it)} за период", style = MaterialTheme.typography.titleSmall, color = if (it >= 0) Up else Down) }
                }
                AppCard(Modifier.fillMaxWidth()) {
                    BigChart(cur.closes, Modifier.fillMaxWidth().height(240.dp), onTouch = { touch = it })
                }
                if (cur.delayedNote.isNotBlank()) Text(cur.delayedNote, style = MaterialTheme.typography.labelSmall)
            }
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ranges.forEach { (r, t) -> Chip(t, r == range, { range = r }, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BigChart(v: List<Double>, modifier: Modifier, onTouch: (Int?) -> Unit) {
    var w by remember { mutableFloatStateOf(1f) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    Canvas(
        modifier.then(Modifier.pointerInputDrag(v.size, { touchX = it; onTouch(it?.let { x -> ((x / w) * (v.size - 1)).toInt() }) }))
    ) {
        w = size.width
        if (v.size < 2) return@Canvas
        val lo = v.min(); val hi = v.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val up = v.last() >= v.first()
        val pts = v.mapIndexed { i, y -> Offset(i / (v.size - 1f) * size.width, size.height - 8f - ((y - lo) / span).toFloat() * (size.height - 16f)) }
        val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        val c = if (up) Up else Down
        drawPath(fill, c.copy(alpha = 0.08f))
        drawPath(line, c.copy(alpha = 0.8f), style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        touchX?.let { x ->
            val i = ((x / size.width) * (v.size - 1)).toInt().coerceIn(0, v.lastIndex)
            drawLine(Pal.Muted.copy(alpha = 0.5f), Offset(pts[i].x, 0f), Offset(pts[i].x, size.height), 2f)
            drawCircle(c, 9f, pts[i])
        }
    }
}

private fun Modifier.pointerInputDrag(key: Int, onX: (Float?) -> Unit): Modifier =
    this.then(Modifier.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onX(down.position.x)
            do {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: break
                if (ch.pressed) { onX(ch.position.x); ch.consume() }
            } while (ev.changes.any { it.pressed })
            onX(null)
        }
    })

@Composable
private fun AddInstrument(current: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить") },
        confirmButton = {
            TextButton({
                val s = custom.trim().uppercase()
                if (s.isNotEmpty() && s !in current) {
                    if (customName.isNotBlank()) MarketsApi.setCustomName(ctx, s, customName.trim())
                    onAdd(s)
                }
            }, enabled = custom.isNotBlank()) { Text("Добавить тикер") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                CATALOG.filter { it.symbol !in current }.groupBy { it.group }.forEach { (g, items) ->
                    item { Text(g, style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                    items(items) { i ->
                        Row(Modifier.fillMaxWidth().clickable { onAdd(i.symbol) }.padding(vertical = 10.dp)) {
                            Text(i.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(i.symbol, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("Любой тикер Yahoo Finance (например MSFT, ^FTSE, CHFUAH=X):", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    AppTextField(custom, { custom = it }, "Тикер")
                    Spacer(Modifier.height(6.dp))
                    AppTextField(customName, { customName = it }, "Название (необязательно)")
                }
            }
        }
    )
}
