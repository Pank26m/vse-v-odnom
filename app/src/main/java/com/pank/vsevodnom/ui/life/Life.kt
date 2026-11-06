package com.pank.vsevodnom.ui.life

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.capri.MOOD_NAMES
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.markets.fmtPrice
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val MOOD_COLORS = listOf(Color(0xFFE0607E), Color(0xFFF0A982), Color(0xFFE9CD6A), Color(0xFF8FCFA8), Color(0xFF6FB6E8))

/** Рисованное лицо настроения 1…5: от грустного к радостному. */
fun DrawScope.moodFace(level: Int, color: Color) {
    val r = size.minDimension / 2; val c = center
    drawCircle(color, r, c)
    val ink = Color(0xFF2B2A33)
    drawCircle(ink, r * 0.1f, Offset(c.x - r * 0.33f, c.y - r * 0.18f)); drawCircle(ink, r * 0.1f, Offset(c.x + r * 0.33f, c.y - r * 0.18f))
    val curve = (level - 3) * 0.18f // −0.36 грустно … +0.36 радостно
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - r * 0.42f, c.y + r * 0.32f)
        quadraticBezierTo(c.x, c.y + r * (0.32f + curve * 1.6f), c.x + r * 0.42f, c.y + r * 0.32f)
    }
    drawPath(p, ink, style = Stroke(r * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

/** Строка настроения для главного: 5 лиц, нажатие — запись. */
@Composable
fun MoodPicker(onPicked: (Int) -> Unit, modifier: Modifier = Modifier, selected: Int? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..5).reversed().forEach { l ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPicked(l) }.padding(4.dp)) {
                Canvas(Modifier.size(if (selected == l) 46.dp else 40.dp)) { moodFace(l, MOOD_COLORS[l - 1].copy(alpha = if (selected == null || selected == l) 1f else 0.45f)) }
                Text(MOOD_NAMES[l - 1], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun MoodScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).life() }
    val moods by dao.moods().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf<Int?>(null) }
    ScreenScaffold("Дневник настроения", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AppCard(Modifier.fillMaxWidth()) { SectionLabel("Как ты сейчас?"); Spacer(Modifier.height(8.dp)); MoodPicker({ adding = it }) } }
            item { MoodChart(moods) }
            if (moods.isEmpty()) item { EmptyHint("Записей пока нет") }
            items(moods, key = { it.id }) { m ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(34.dp)) { moodFace(m.level, MOOD_COLORS[m.level - 1]) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(MOOD_NAMES[m.level - 1], style = MaterialTheme.typography.titleSmall)
                            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodyMedium)
                            Text(Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton({ AppScope.io.launch { dao.deleteMood(m) } }) { Text("✕", color = Pal.Muted) }
                    }
                }
            }
        }
    }
    adding?.let { l -> MoodNoteDialog(l) { adding = null } }
}

@Composable
fun MoodNoteDialog(level: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDone, title = { Text(MOOD_NAMES[level - 1]) },
        text = { Column { Text("Пара слов о самочувствии (можно пусто)", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)); AppTextField(note, { note = it }, "Заметка", singleLine = false) } },
        confirmButton = { TextButton({ AppScope.io.launch { AppDb.get(ctx).life().addMood(MoodEntry(level = level, note = note.trim())) }; onDone() }) { Text("Записать") } },
        dismissButton = { TextButton(onDone) { Text("Отмена") } })
}

/** Средний уровень по дням за 30 дней. */
@Composable
private fun MoodChart(moods: List<MoodEntry>) {
    if (moods.size < 2) return
    val z = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (29 downTo 0).map { today.minusDays(it.toLong()) }
    val avg = moods.groupBy { Instant.ofEpochMilli(it.at).atZone(z).toLocalDate() }.mapValues { (_, l) -> l.map { it.level }.average() }
    AppCard(Modifier.fillMaxWidth()) {
        SectionLabel("30 дней · средний по дню ${"%.1f".format(moods.take(30).map { it.level }.average()).replace('.', ',')}")
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val bw = size.width / days.size
            days.forEachIndexed { i, d ->
                val v = avg[d] ?: return@forEachIndexed
                val h = (v / 5.0 * size.height).toFloat()
                drawRoundRect(MOOD_COLORS[(v.toInt() - 1).coerceIn(0, 4)], Offset(i * bw + bw * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(bw * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(6f))
            }
        }
    }
}

// ================= Корзина =================

@Composable
fun CartScreen(onBack: () -> Unit, startNew: Boolean = false) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).life() }
    val items by dao.cart().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(startNew) }
    var edit by remember { mutableStateOf<CartItem?>(null) }
    ScreenScaffold("Корзина", onBack, actions = { RoundIconButton(Icons.Rounded.Add, "Добавить", { adding = true }, bg = Pal.Ink, tint = Color.White) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val open = items.filter { !it.bought }
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    SectionLabel("Хочу купить: ${open.size}", Pal.PeachInk)
                    val total = open.filter { it.currency.isBlank() || it.currency == "UAH" }.mapNotNull { it.price }.sum()
                    if (total > 0) Text("≈ ${fmtPrice(total)} ₴", style = MaterialTheme.typography.headlineMedium)
                    Text("Добавляй ссылки здесь, через «Поделиться → Всё в одном» или фразой Капри: «добавь в корзину» + ссылка.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(items, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { if (c.url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.bought, { b -> AppScope.io.launch { dao.upsertCart(c.copy(bought = b)) } })
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (c.bought) TextDecoration.LineThrough else null), maxLines = 2)
                            Text(listOfNotNull(c.store.ifBlank { null }, c.note.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        c.price?.let { Text("${fmtPrice(it)} ${if (c.currency == "UAH" || c.currency.isBlank()) "₴" else c.currency}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold)) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Изменить") }, { menu = false; edit = c })
                                DropdownMenuItem({ Text("Поделиться") }, { menu = false; ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${c.title}\n${c.url}"), c.title)) })
                                DropdownMenuItem({ Text("Удалить", color = Pal.RoseInk) }, { menu = false; AppScope.io.launch { dao.deleteCart(c) } })
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var url by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("В корзину") },
            text = { Column { AppTextField(url, { url = it }, "Ссылка на товар или название"); if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } },
            confirmButton = {
                TextButton({
                    val u = Regex("https?://\\S+").find(url)?.value
                    if (u == null) { if (url.isNotBlank()) AppScope.io.launch { dao.upsertCart(CartItem(title = url.trim())) }; adding = false; return@TextButton }
                    busy = true
                    scope.launch {
                        val info = runCatching { ProductInfo.fetch(u) }.getOrNull()
                        AppScope.io.launch { dao.upsertCart(CartItem(url = u, title = info?.title ?: u.substringAfter("//").substringBefore('/'), price = info?.price, currency = info?.currency ?: "", image = info?.image ?: "", store = info?.store ?: "")) }
                        busy = false; adding = false
                    }
                }, enabled = !busy) { Text("Добавить") }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Отмена") } })
    }
    edit?.let { c ->
        var title by remember { mutableStateOf(c.title) }
        var price by remember { mutableStateOf(c.price?.let { fmtPrice(it).replace(" ", "") } ?: "") }
        var note by remember { mutableStateOf(c.note) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Товар") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AppTextField(title, { title = it }, "Название"); AppTextField(price, { price = it }, "Цена, ₴"); AppTextField(note, { note = it }, "Заметка") } },
            confirmButton = { TextButton({ AppScope.io.launch { dao.upsertCart(c.copy(title = title.trim(), price = price.replace(',', '.').replace(" ", "").toDoubleOrNull(), currency = if (price.isBlank()) c.currency else "UAH", note = note.trim())) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}
