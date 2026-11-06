package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch

/** «Покупки»: несколько независимых списков (продукты, Эпицентр…). */
@Composable
fun ShopListsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    LaunchedEffect(Unit) { ensureListPages(ctx) }
    val pages by dao.pages().collectAsState(initial = emptyList())
    val entries by dao.allEntries().collectAsState(initial = emptyList())
    val lists = pages.filter { it.type == "SHOP" }
    var open by remember { mutableStateOf<ListPage?>(null) }
    var edit by remember { mutableStateOf<ListPage?>(null) }
    open?.let { p -> BackHandler { open = null }; ShopPage(lists.firstOrNull { it.id == p.id } ?: p) { open = null }; return }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Покупки", onBack) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lists.isEmpty()) item { EmptyHint("Списков нет. Нажми «+» — например «Продукты» или «Эпицентр».") }
                items(lists, key = { it.id }) { p ->
                    val t = Tints.all[p.tint % 6]
                    val mine = entries.filter { it.pageId == p.id }
                    val left = mine.count { !it.done }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { open = p }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(t.bg), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ShoppingCart, null, tint = t.ink) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (mine.isEmpty()) "пусто" else if (left == 0) "всё куплено" else "осталось $left из ${mine.size}", style = MaterialTheme.typography.bodySmall, color = if (left > 0) t.ink else Pal.Muted)
                        }
                        IconButton({ edit = p }) { Icon(Icons.Rounded.MoreVert, "Изменить", tint = Pal.Muted) }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = ListPage(name = "", type = "SHOP", tint = lists.size % 6, sort = pages.size) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новый список", tint = Color.White) }
    }
    edit?.let { p ->
        var name by remember { mutableStateOf(p.name) }
        var tint by remember { mutableIntStateOf(p.tint) }
        var del by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (p.id == 0L) "Новый список покупок" else "Список") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(name, { name = it }, "Например: Продукты, Эпицентр")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (i == tint) 32.dp else 26.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.85f else 0.35f)).clickable { tint = i }) } }
                    if (p.id != 0L) TextButton({ del = !del }) { Text(if (del) "Нажми «Удалить» ещё раз внизу" else "Удалить список", color = Pal.RoseInk) }
                }
            },
            confirmButton = {
                TextButton({
                    if (del) AppScope.io.launch { dao.deletePage(p) } else if (name.isNotBlank()) AppScope.io.launch { dao.upsertPage(p.copy(name = name.trim(), tint = tint)) }
                    edit = null
                }) { Text(if (del) "Удалить" else "Сохранить", color = if (del) Pal.RoseInk else Color.Unspecified) }
            },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}

/** Один список: галочки с зачёркиванием, порядок меняется перетаскиванием за «ручку». Купленное остаётся на месте. */
@Composable
private fun ShopPage(p: ListPage, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val dbItems by remember(p.id) { dao.entries(p.id) }.collectAsState(initial = emptyList())
    val order = remember { mutableStateListOf<ListEntry>() }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dbItems) { if (dragIdx < 0) { order.clear(); order.addAll(dbItems) } }
    var text by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<ListEntry?>(null) }
    val rowH = 58.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val t = Tints.all[p.tint % 6]
    fun add() { val s = text.trim(); if (s.isNotEmpty()) AppScope.io.launch { dao.upsertEntry(ListEntry(pageId = p.id, text = s, sort = (order.maxOfOrNull { it.sort } ?: 0) + 1)) }; text = "" }
    ScreenScaffold(p.name, onBack, actions = {
        if (order.any { it.done }) TextButton({ AppScope.io.launch { dao.clearDone(p.id) } }) { Text("Убрать купленное") }
    }) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Что купить") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Ink).clickable { add() }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Добавить", tint = Color.White) }
        }
        if (order.isNotEmpty()) Text("Осталось ${order.count { !it.done }} из ${order.size} · порядок — тяни за ⠿", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = dragIdx < 0).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            if (order.isEmpty()) EmptyHint("Пусто. Пиши пункт и жми Enter.", Modifier.padding(top = 12.dp))
            order.forEachIndexed { i, e ->
                val dragging = i == dragIdx
                Row(Modifier.fillMaxWidth().height(rowH).zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragY else 0f }
                    .padding(vertical = 3.dp).then(if (dragging) Modifier.shadow(8.dp, RoundedCornerShape(14.dp)) else Modifier)
                    .clip(RoundedCornerShape(14.dp)).background(if (e.done) t.bg.copy(alpha = 0.6f) else Pal.Card).clickable { edit = e }.padding(start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CheckCircle(e.done, t.ink, 24.dp) { AppScope.io.launch { dao.upsertEntry(e.copy(done = !e.done)) } }
                    Text(e.text, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (e.done) TextDecoration.LineThrough else null), color = if (e.done) Pal.Muted else Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                    Box(Modifier.fillMaxHeight().width(48.dp).pointerInput(e.id) {
                        detectDragGestures(
                            onDragStart = { dragIdx = order.indexOfFirst { it.id == e.id }; dragY = 0f },
                            onDragEnd = {
                                val snap = order.mapIndexed { k, x -> x.copy(sort = k) }
                                dragIdx = -1; dragY = 0f
                                AppScope.io.launch { dao.upsertEntries(snap) }
                            },
                            onDragCancel = { dragIdx = -1; dragY = 0f },
                        ) { ch, d ->
                            ch.consume()
                            if (dragIdx < 0) return@detectDragGestures
                            dragY += d.y
                            // перескакиваем через соседа, когда сдвинулись больше чем на половину строки
                            if (dragY > rowPx / 2 && dragIdx < order.lastIndex) { order.add(dragIdx + 1, order.removeAt(dragIdx)); dragIdx++; dragY -= rowPx }
                            else if (dragY < -rowPx / 2 && dragIdx > 0) { order.add(dragIdx - 1, order.removeAt(dragIdx)); dragIdx--; dragY += rowPx }
                        }
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DragIndicator, "Перетащить", tint = Pal.Muted) }
                }
            }
        }
    }
    edit?.let { e ->
        var s by remember { mutableStateOf(e.text) }
        AlertDialog(onDismissRequest = { edit = null }, text = { AppTextField(s, { s = it }, "Пункт") },
            confirmButton = { TextButton({ if (s.isNotBlank()) AppScope.io.launch { dao.upsertEntry(e.copy(text = s.trim())) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ AppScope.io.launch { dao.deleteEntry(e) }; edit = null }) { Text("Удалить", color = Pal.RoseInk) } })
    }
}
