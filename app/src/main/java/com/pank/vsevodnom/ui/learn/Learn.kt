package com.pank.vsevodnom.ui.learn

import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch
import java.util.Locale

/** Озвучка английских слов системным синтезатором речи. */
@Composable
fun rememberTts(): (String) -> Unit {
    val ctx = LocalContext.current
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = TextToSpeech(ctx) { st -> if (st == TextToSpeech.SUCCESS) tts[0]?.language = Locale.US }
        onDispose { tts[0]?.shutdown() }
    }
    return { text -> tts[0]?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w") }
}

// ================= Свои карточки =================

@Composable
fun CardsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cards() }
    val decks by dao.decks().collectAsState(initial = emptyList())
    val stats by remember { dao.stats(System.currentTimeMillis()) }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Deck?>(null) }
    var edit by remember { mutableStateOf<Deck?>(null) }
    open?.let { d -> BackHandler { open = null }; DeckPage(d, english = false) { open = null }; return }
    val mine = decks.filter { it.builtin.isBlank() }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Карточки", onBack) {
            LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mine.isEmpty()) item(span = { GridItemSpan(2) }) { EmptyHint("Колод пока нет. Нажми «+», назови колоду (например «Анатомия») и добавляй карточки: вопрос — ответ.") }
                items(mine, key = { it.id }) { d -> DeckTile(d, stats.firstOrNull { it.deckId == d.id }, onLong = { edit = d }) { open = d } }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Deck(name = "", tint = mine.size % 6) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новая колода", tint = Color.White) }
    }
    edit?.let { d -> DeckEditor(d) { edit = null } }
}

@Composable
private fun DeckEditor(d: Deck, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(d.name) }
    var tint by remember { mutableIntStateOf(d.tint) }
    AlertDialog(onDismissRequest = onDone, title = { Text(if (d.id == 0L) "Новая колода" else "Колода") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(name, { name = it }, "Название")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (i == tint) 32.dp else 26.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.85f else 0.35f)).clickable { tint = i }) } }
                if (d.id != 0L) TextButton({ AppScope.io.launch { AppDb.get(ctx).cards().clearDeck(d.id); AppDb.get(ctx).cards().deleteDeck(d) }; onDone() }) { Text("Удалить колоду со всеми карточками", color = Pal.RoseInk) }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).cards().upsertDeck(d.copy(name = name.trim(), tint = tint)) }; onDone() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onDone) { Text("Отмена") } })
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DeckTile(d: Deck, st: DeckStat?, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val t = Tints.all[d.tint % 6]
    Column(Modifier.height(130.dp).clip(RoundedCornerShape(18.dp)).background(t.bg)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(14.dp)) {
        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Spacer(Modifier.weight(1f))
        val total = st?.total ?: 0; val learned = st?.learned ?: 0
        Text("$total карточек", style = MaterialTheme.typography.bodySmall)
        Text(if ((st?.due ?: 0) > 0) "к повторению: ${minOf(st!!.due, 999)}" else "всё повторено", style = MaterialTheme.typography.labelSmall, color = t.ink)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator({ if (total == 0) 0f else learned.toFloat() / total }, Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = t.ink, trackColor = Color.White.copy(alpha = 0.6f))
    }
}

// ================= Страница колоды: учить, тест, список =================

@Composable
fun DeckPage(d: Deck, english: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cards() }
    val cards by remember(d.id) { dao.cards(d.id) }.collectAsState(initial = emptyList())
    var mode by remember { mutableStateOf<String?>(null) }   // study | quiz
    var reverse by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Card?>(null) }
    val speak = rememberTts()
    mode?.let { m -> BackHandler { mode = null }; StudySession(d, m == "quiz", reverse, english, speak) { mode = null }; return }
    val now = System.currentTimeMillis()
    val due = cards.count { it.due <= now }
    val learned = cards.count { it.box >= 4 }
    val seen = cards.count { !Srs.isNew(it) }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(d.name, onBack) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Всего" to cards.size, "Изучено" to learned, "Начато" to seen, "Сегодня" to due).forEach { (k, v) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Pal.Card).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$v", style = MaterialTheme.typography.titleMedium); Text(k, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Учить", { mode = "study" }, Modifier.weight(1f), enabled = cards.isNotEmpty())
                        PillButton("Тест · 4 варианта", { mode = "quiz" }, Modifier.weight(1f), bg = Pal.Lilac, fg = Pal.LilacInk, enabled = cards.size >= 4)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(if (english) "Англ → рус" else "Вопрос → ответ", !reverse, { reverse = false })
                        Chip(if (english) "Рус → англ" else "Ответ → вопрос", reverse, { reverse = true })
                    }
                }
                item { AppTextField(q, { q = it }, if (english) "Поиск слова" else "Поиск карточки") }
                val shown = cards.filter { q.isBlank() || it.front.contains(q, true) || it.back.contains(q, true) }
                items(shown.take(400), key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { edit = c }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.front, style = MaterialTheme.typography.titleSmall)
                            Text(c.back, style = MaterialTheme.typography.bodySmall)
                        }
                        if (english) IconButton({ speak(c.front) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Произнести", tint = Pal.Muted) }
                        // коробка Лейтнера как точки
                        Row { (1..5).forEach { i -> Box(Modifier.padding(1.dp).size(6.dp).clip(CircleShape).background(if (c.box >= i) Pal.MintInk else Pal.Line)) } }
                    }
                }
                if (shown.size > 400) item { Text("Показаны первые 400 — уточни поиск", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Card(deckId = d.id, front = "", back = "") }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новая карточка", tint = Color.White) }
    }
    edit?.let { c -> CardEditor(c, english) { edit = null } }
}

@Composable
private fun CardEditor(c: Card, english: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var front by remember { mutableStateOf(c.front) }
    var back by remember { mutableStateOf(c.back) }
    var note by remember { mutableStateOf(c.note) }
    AlertDialog(onDismissRequest = onDone, title = { Text(if (c.id == 0L) "Новая карточка" else "Карточка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(front, { front = it }, if (english) "Слово (англ.)" else "Лицо: вопрос", singleLine = false)
                AppTextField(back, { back = it }, if (english) "Перевод" else "Оборот: ответ", singleLine = false)
                AppTextField(note, { note = it }, "Пример или подсказка (необязательно)", singleLine = false)
                if (c.id != 0L) Row {
                    TextButton({ AppScope.io.launch { AppDb.get(ctx).cards().upsertCard(c.copy(box = 0, due = 0, right = 0, wrong = 0)) }; onDone() }) { Text("Сбросить прогресс") }
                    TextButton({ AppScope.io.launch { AppDb.get(ctx).cards().deleteCard(c) }; onDone() }) { Text("Удалить", color = Pal.RoseInk) }
                }
            }
        },
        confirmButton = { TextButton({ if (front.isNotBlank() && back.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).cards().upsertCard(c.copy(front = front.trim(), back = back.trim(), note = note.trim())) }; onDone() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onDone) { Text("Отмена") } })
}

/** Сессия: карточка переворачивается по нажатию; «Помню / Не помню». В тесте — выбор из 4 вариантов. */
@Composable
private fun StudySession(d: Deck, quiz: Boolean, reverse: Boolean, english: Boolean, speak: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cards() }
    var queue by remember { mutableStateOf<List<Card>?>(null) }
    var pool by remember { mutableStateOf<List<Card>>(emptyList()) }
    var idx by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var right by remember { mutableIntStateOf(0) }; var wrong by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        queue = Srs.session(ctx, d.id, newLimit = if (english) 15 else 30)
        pool = dao.dueCards(d.id, Long.MAX_VALUE, 3000)
    }
    val q = queue
    ScreenScaffold(if (quiz) "Тест" else "Учим", onDone) {
        if (q == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@ScreenScaffold }
        if (idx >= q.size) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (q.isEmpty()) "На сегодня всё повторено" else "Готово!", style = MaterialTheme.typography.headlineMedium)
                if (q.isNotEmpty()) Text("Помню: $right · Не помню: $wrong", style = MaterialTheme.typography.titleMedium, color = Pal.Muted)
                Spacer(Modifier.height(16.dp))
                PillButton("Ещё раз", { queue = null; idx = 0; right = 0; wrong = 0; flipped = false; picked = null; round++ }, Modifier.fillMaxWidth())
            }
            return@ScreenScaffold
        }
        val c = q[idx]
        val front = if (reverse) c.back else c.front
        val back = if (reverse) c.front else c.back
        fun answer(ok: Boolean) {
            AppScope.io.launch { dao.upsertCard(Srs.answer(c, ok)) }
            if (ok) right++ else wrong++
            idx++; flipped = false; picked = null
        }
        LinearProgressIndicator({ idx.toFloat() / q.size }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        Text("${idx + 1} из ${q.size}" + if (Srs.isNew(c)) " · новое" else "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 6.dp))
        // своя анимация на каждую карточку: новая начинается сразу лицом (раньше переворот назад на долю секунды показывал перевод следующего слова)
        val rotA = remember(idx, round) { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(idx, round, flipped, picked) { rotA.animateTo(if (flipped || picked != null) 180f else 0f, tween(380)) }
        val rot = rotA.value
        val t = Tints.all[d.tint % 6]
        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp).graphicsLayer { rotationY = rot; cameraDistance = 14f * density }
            .clip(RoundedCornerShape(24.dp)).background(if (rot > 90f) Pal.Card else t.bg).clickable(enabled = !quiz) { flipped = !flipped },
            contentAlignment = Alignment.Center) {
            Column(Modifier.graphicsLayer { rotationY = if (rot > 90f) 180f else 0f }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (rot > 90f) back else front, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, fontSize = if ((if (rot > 90f) back else front).length > 30) 22.sp else 32.sp)
                if (rot > 90f && c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                if (english) IconButton({ speak(c.front) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Произнести", tint = t.ink) }
                if (!quiz && rot < 90f) Text("нажми, чтобы перевернуть", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
        }
        if (quiz) {
            val options = remember(c.id, reverse) { (pool.filter { it.id != c.id }.shuffled().take(3).map { if (reverse) it.front else it.back } + back).shuffled() }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { o ->
                    val col = when { picked == null -> Pal.Card; o == back -> Pal.Mint; o == picked -> Pal.Rose; else -> Pal.Card }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col).clickable(enabled = picked == null) { picked = o }.padding(16.dp)) {
                        Text(o, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (picked != null) PillButton("Дальше", { answer(picked == back) }, Modifier.fillMaxWidth())
            }
        } else Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Не помню", { answer(false) }, Modifier.weight(1f), bg = Pal.Rose, fg = Pal.RoseInk)
            PillButton("Помню", { answer(true) }, Modifier.weight(1f), bg = Pal.Mint, fg = Pal.MintInk)
        }
    }
}

// ================= Английский =================

@Composable
fun EnglishScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cards() }
    var importing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { runCatching { English.ensure(ctx) }; importing = false }
    val decks by dao.decks().collectAsState(initial = emptyList())
    val stats by remember { dao.stats(System.currentTimeMillis()) }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Deck?>(null) }
    open?.let { d -> BackHandler { open = null }; DeckPage(d, english = true) { open = null }; return }
    val en = decks.filter { it.builtin.startsWith("en:") }.sortedBy { d -> English.PACKS.indexOfFirst { it.key == d.builtin } }
    val st = stats.filter { s -> en.any { it.id == s.deckId } }
    ScreenScaffold("Английский", onBack) {
        if (importing && en.isEmpty()) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Готовлю словарь…") }; return@ScreenScaffold }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(2) }) {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Sky) {
                    Text("${st.sumOf { it.total }} слов", style = MaterialTheme.typography.headlineMedium)
                    Text("Изучено ${st.sumOf { it.learned }} · начато ${st.sumOf { it.seen }} · к повторению ${st.filter { it.seen > 0 }.sumOf { it.due }.coerceAtMost(st.sumOf { it.seen })}", style = MaterialTheme.typography.bodyMedium, color = Pal.SkyInk)
                    Text("Каждый день: 15 новых слов + повторение. «Помню» — слово вернётся через 1, 3, 7, 16, 35 дней.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item(span = { GridItemSpan(2) }) { SectionLabel("По уровням") }
            items(en.filter { it.builtin.matches(Regex("en:[ABC][12]")) }, key = { it.id }) { d -> DeckTile(d, stats.firstOrNull { it.deckId == d.id }) { open = d } }
            item(span = { GridItemSpan(2) }) { SectionLabel("По темам") }
            items(en.filter { !it.builtin.matches(Regex("en:[ABC][12]")) }, key = { it.id }) { d -> DeckTile(d, stats.firstOrNull { it.deckId == d.id }) { open = d } }
        }
    }
}
