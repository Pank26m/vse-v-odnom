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
import androidx.compose.material.icons.rounded.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.markets.fmtPrice
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val MOOD_NAMES = listOf("Ужасно", "Плохо", "Нормально", "Хорошо", "Супер")
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
    val voice = remember { VoiceNote(ctx, "mood") }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    var bigPhoto by remember { mutableStateOf<String?>(null) }
    ScreenScaffold("Дневник настроения", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AppCard(Modifier.fillMaxWidth()) { SectionLabel("Как ты сейчас?"); Spacer(Modifier.height(8.dp)); MoodPicker({ adding = it }) } }
            item { MoodChart(moods) }
            item { MoodStats(moods) }
            if (moods.isEmpty()) item { EmptyHint("Записей пока нет") }
            items(moods, key = { it.id }) { m ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Canvas(Modifier.size(34.dp)) { moodFace(m.level, MOOD_COLORS[m.level - 1]) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(MOOD_NAMES[m.level - 1], style = MaterialTheme.typography.titleSmall)
                            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodyMedium)
                            if (m.photo.isNotBlank()) AppImage(m.photo, Modifier.fillMaxWidth().height(160.dp).clickable { bigPhoto = m.photo }, maxPx = 900)
                            if (m.audio.isNotBlank() && java.io.File(m.audio).exists()) VoiceChip(m.audio, voice)
                            Text(Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton({ AppScope.io.launch { dao.deleteMood(m); listOf(m.photo, m.audio).filter { it.startsWith(ctx.filesDir.path) }.forEach { java.io.File(it).delete() } } }) { Text("✕", color = Pal.Muted) }
                    }
                }
            }
        }
    }
    adding?.let { l -> MoodNoteDialog(l) { adding = null } }
    bigPhoto?.let { p ->
        androidx.compose.ui.window.Dialog({ bigPhoto = null }) { AppImage(p, Modifier.fillMaxWidth().aspectRatio(0.75f).clickable { bigPhoto = null }, maxPx = 1600) }
    }
}

@Composable
fun MoodNoteDialog(level: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf("") }
    val voice = remember { VoiceNote(ctx, "mood") }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { copyIntoApp(ctx, it, "mood")?.let { p -> photo = p } } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) voice.start() }
    fun cancel() { voice.release(); listOf(photo, audio).filter { it.isNotBlank() }.forEach { java.io.File(it).delete() }; onDone() }
    AlertDialog(onDismissRequest = { cancel() }, title = { Text(MOOD_NAMES[level - 1]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Пара слов о самочувствии (можно пусто)", style = MaterialTheme.typography.bodySmall)
                AppTextField(note, { note = it }, "Заметка", singleLine = false)
                if (photo.isNotBlank()) Box {
                    AppImage(photo, Modifier.fillMaxWidth().height(140.dp))
                    IconButton({ java.io.File(photo).delete(); photo = "" }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Rounded.Close, "Убрать фото", tint = Color.White) }
                }
                if (audio.isNotBlank()) VoiceChip(audio, voice, onDelete = { voice.stopPlay(); java.io.File(audio).delete(); audio = "" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Фото", photo.isNotBlank(), { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    if (voice.recording) {
                        Chip("Стоп", true, { voice.stop()?.let { audio = it } })
                        RecordingTimer()
                    } else if (audio.isBlank()) Chip("Голосом", false, {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) voice.start()
                        else mic.launch(android.Manifest.permission.RECORD_AUDIO)
                    })
                }
            }
        },
        confirmButton = {
            TextButton({
                if (voice.recording) voice.stop()?.let { audio = it }
                voice.stopPlay()
                val e = MoodEntry(level = level, note = note.trim(), photo = photo, audio = audio)
                AppScope.io.launch { AppDb.get(ctx).life().addMood(e) }; onDone()
            }) { Text("Записать") }
        },
        dismissButton = { TextButton({ cancel() }) { Text("Отмена") } })
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

private fun cur(c: CartItem) = if (c.currency == "UAH" || c.currency.isBlank()) "₴" else c.currency

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartScreen(onBack: () -> Unit, startNew: Boolean = false) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).life() }
    val items by dao.cart().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf(if (startNew) CartItem(title = "") else null) }
    var tab by remember { mutableIntStateOf(0) }
    var tag by remember { mutableStateOf<String?>(null) }
    val allTags = items.flatMap { it.tagList() }.distinct().sorted()
    val groupsPref = remember { ctx.getSharedPreferences("cart", android.content.Context.MODE_PRIVATE) }
    var extraGroups by remember { mutableStateOf(groupsPref.getStringSet("groups", emptySet())!!.toList()) }
    val groups = (allTags + extraGroups).distinct().sorted()
    var newGroup by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    ScreenScaffold("Корзина", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(listOf("Хочу · ${items.count { !it.bought }}", "Куплено · ${items.count { it.bought }}"), tab, { tab = it }, Modifier.fillMaxWidth())
            // группы плитками: «алик», «важное», …
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun G(name: String, count: Int, sel: Boolean, onClick: () -> Unit) =
                    Column(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) Pal.PeachInk else Pal.Peach).clickable(onClick = onClick).padding(10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = if (sel) Color.White else Pal.Ink, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Text("$count", style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White.copy(alpha = 0.8f) else Pal.PeachInk)
                    }
                G("Все", items.count { it.bought == (tab == 1) }, tag == null) { tag = null }
                groups.forEach { g -> G(g, items.count { it.bought == (tab == 1) && g in it.tagList() }, tag == g) { tag = if (tag == g) null else g } }
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { newGroup = "" }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новая группа", tint = Pal.PeachInk) }
            }
        }
        Spacer(Modifier.height(8.dp))
        val shown = items.filter { it.bought == (tab == 1) && (tag == null || tag in it.tagList()) }
            .let { l -> if (tab == 1) l.sortedByDescending { it.boughtAt ?: it.addedAt } else l }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), color = if (tab == 0) Pal.Peach else Pal.Mint) {
                    val total = shown.filter { it.currency.isBlank() || it.currency == "UAH" }.mapNotNull { it.price }.sum()
                    SectionLabel(if (tab == 0) "Хочу купить: ${shown.size}" else "Куплено: ${shown.size}", if (tab == 0) Pal.PeachInk else Pal.MintInk)
                    if (total > 0) Text("${if (tab == 0) "≈ " else ""}${fmtPrice(total)} ₴", style = MaterialTheme.typography.headlineMedium)
                    if (tab == 0) Text("Галочка — переносит в «Куплено». Дата покупки видна в ToDo и в календаре.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (shown.isEmpty()) item { EmptyHint(if (tab == 0) "Пусто" else "Пока ничего не куплено") }
            items(shown, key = { it.id }) { c ->
                AppCard(Modifier.fillMaxWidth(), padding = 10.dp, onClick = { edit = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.bought, { b -> AppScope.io.launch { dao.upsertCart(c.copy(bought = b, boughtAt = if (b) System.currentTimeMillis() else null)) } })
                        if (c.photo.isNotBlank() || c.image.isNotBlank()) { AppImage(c.photo.ifBlank { c.image }, Modifier.size(56.dp), maxPx = 200); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (c.priority > 0) Box(Modifier.padding(end = 6.dp).size(9.dp).clip(CircleShape).background(com.pank.vsevodnom.ui.todo.prioColor(c.priority)))
                                Text(c.title, style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (c.bought) TextDecoration.LineThrough else null), maxLines = 2)
                            }
                            val sub = listOfNotNull(
                                c.store.ifBlank { null },
                                c.planDay?.let { "купить " + LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd.MM")) }?.takeIf { !c.bought },
                                c.boughtAt?.takeIf { c.bought }?.let { "куплено " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yy")) },
                                c.note.ifBlank { null },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            if (c.tagList().isNotEmpty()) Text(c.tagList().joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = Pal.PeachInk)
                        }
                        c.price?.let { Text("${fmtPrice(it)} ${cur(c)}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold)) }
                    }
                }
            }
        }
    }
    Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
        .clickable { edit = CartItem(title = "", tags = tag ?: "", priority = 0) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Добавить", tint = Color.White) }
    }
    edit?.let { c -> CartEditor(c, groups) { edit = null } }
    newGroup?.let { g0 ->
        var g by remember { mutableStateOf(g0) }
        AlertDialog(onDismissRequest = { newGroup = null }, title = { Text("Новая группа") }, text = { AppTextField(g, { g = it.replace(",", "") }, "Например: алик, важное") },
            confirmButton = { TextButton({ val n = g.trim(); if (n.isNotBlank()) { extraGroups = (extraGroups + n).distinct(); groupsPref.edit().putStringSet("groups", extraGroups.toSet()).apply(); tag = n }; newGroup = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ newGroup = null }) { Text("Отмена") } })
    }
}

fun CartItem.tagList() = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CartEditor(c: CartItem, knownTags: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).life() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(c.url) }
    var title by remember { mutableStateOf(c.title) }
    var price by remember { mutableStateOf(c.price?.let { fmtPrice(it).replace(" ", "").replace("\u00a0", "") } ?: "") }
    var currency by remember { mutableStateOf(c.currency) }
    var store by remember { mutableStateOf(c.store) }
    var note by remember { mutableStateOf(c.note) }
    var image by remember { mutableStateOf(c.image) }
    var photo by remember { mutableStateOf(c.photo) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(c.tagList()) } }
    var newTag by remember { mutableStateOf("") }
    var planDay by remember { mutableStateOf(c.planDay) }
    var busy by remember { mutableStateOf(false) }
    var prio by remember { mutableIntStateOf(c.priority) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { copyIntoApp(ctx, it, "cart")?.let { p -> photo = p } } }

    fun save() {
        val u = Regex("https?://\\S+").find(url)?.value ?: ""
        val t = title.trim().ifBlank { url.trim() }
        if (t.isBlank()) return
        val item = c.copy(url = u, title = t, price = price.replace(',', '.').replace(" ", "").replace("\u00a0", "").toDoubleOrNull(),
            currency = if (price.isBlank()) currency else currency.ifBlank { "UAH" }, store = store.trim().ifBlank { runCatching { java.net.URL(u).host.removePrefix("www.") }.getOrDefault("") },
            note = note.trim(), image = image, photo = photo, tags = (tags + newTag.trim()).filter { it.isNotBlank() }.distinct().joinToString(","), planDay = planDay)
        // дата покупки сама видна в ToDo и в календаре — отдельное событие не создаём
        AppScope.io.launch { dao.upsertCart(item.copy(priority = prio)) }
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss)
                Text(if (c.id == 0L) "В корзину" else "Товар", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                RoundIconButton(Icons.Rounded.Check, "Сохранить", { save() }, bg = Pal.Ink, tint = Color.White)
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { AppTextField(url, { url = it }, "Ссылка на товар") }
                            if (c.url.isNotBlank()) IconButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) } }) { Icon(Icons.Rounded.OpenInNew, "Открыть") }
                        }
                        Spacer(Modifier.height(8.dp))
                        AppTextField(title, { title = it }, "Название")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { AppTextField(price, { price = it }, "Цена") }
                            Box(Modifier.weight(1f)) { AppTextField(store, { store = it }, "Магазин") }
                        }
                        Text("AliExpress и большинство магазинов не отдают данные без браузера, поэтому название и цену вписываешь сам. Ссылка сохраняется — кнопкой рядом её можно открыть.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Приоритет")
                        Spacer(Modifier.height(6.dp))
                        com.pank.vsevodnom.ui.todo.PriorityDots(prio) { prio = it }
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Фото")
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (photo.isNotBlank() || image.isNotBlank()) AppImage(photo.ifBlank { image }, Modifier.size(96.dp), maxPx = 400)
                            Column {
                                TextButton({ picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (photo.isBlank()) "Выбрать из галереи" else "Другое фото") }
                                if (photo.isNotBlank()) TextButton({ photo = "" }) { Text("Убрать своё фото", color = Pal.RoseInk) }
                            }
                        }
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Когда купить")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(planDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) } ?: "Выбрать дату", planDay != null, { pickDate = true })
                            if (planDay != null) TextButton({ planDay = null }) { Text("Без даты") }
                        }
                        if (planDay != null) Text("Будет в ToDo и в календаре на этот день.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Теги")
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (knownTags + tags + listOf("Одежда", "Техника", "Дом", "Подарок", "Учёба", "Еда")).distinct().forEach { t ->
                                Chip(t, t in tags, { if (t in tags) tags.remove(t) else tags.add(t) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { AppTextField(newTag, { newTag = it }, "Новый тег") }
                            IconButton({ newTag.trim().takeIf { it.isNotBlank() }?.let { if (it !in tags) tags.add(it) }; newTag = "" }) { Icon(Icons.Rounded.Add, "Добавить тег") }
                        }
                    }
                }
                item { AppCard(Modifier.fillMaxWidth()) { AppTextField(note, { note = it }, "Заметка (размер, цвет…)", singleLine = false) } }
                if (c.id != 0L) item {
                    Row {
                        TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${c.title}\n${c.url}"), c.title)) }) { Text("Поделиться") }
                        Spacer(Modifier.weight(1f))
                        TextButton({ confirmDel = true }) { Text("Удалить", color = Pal.RoseInk) }
                    }
                }
            }
        }
    }
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = (planDay?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { planDay = Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay() }; pickDate = false }) { Text("Ок") }
        }) { DatePicker(dp) }
    }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Удалить «${c.title}»?") },
        confirmButton = { TextButton({ AppScope.io.launch { dao.deleteCart(c); if (c.photo.startsWith(ctx.filesDir.path)) java.io.File(c.photo).delete() }; confirmDel = false; onDismiss() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } })
}
