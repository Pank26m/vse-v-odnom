package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.runtime.key
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
import androidx.compose.foundation.horizontalScroll
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
import com.pank.vsevodnom.ui.markets.fmtPrice
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
    val cart by remember { AppDb.get(ctx).life().cart() }.collectAsState(initial = emptyList())
    val groupsPref = remember { ctx.getSharedPreferences("cart", android.content.Context.MODE_PRIVATE) }
    var extraGroups by remember { mutableStateOf(groupsPref.getStringSet("groups", emptySet())!!.toList()) }
    val markets = (cart.flatMap { with(com.pank.vsevodnom.ui.life.CartTags) { it.tagList() } } + extraGroups).distinct().sorted()
    var market by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var newMarket by remember { mutableStateOf<String?>(null) }
    var piggy by remember { mutableStateOf(false) }
    if (piggy) { BackHandler { piggy = false }; com.pank.vsevodnom.ui.piggy.PiggyScreen { piggy = false }; return }
    open?.let { p -> BackHandler { open = null }; ShopPage(lists.firstOrNull { it.id == p.id } ?: p) { open = null }; return }
    market?.let { m -> BackHandler { market = null }; MarketPage(m, markets) { market = null }; return }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Покупки", onBack) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // копилки — коплю на покупку
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Mint).clickable { piggy = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Savings, null, tint = Pal.MintInk) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Копилка", style = MaterialTheme.typography.titleMedium, color = Pal.MintInk)
                            Text("откладываю на покупки", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.MintInk)
                    }
                }
                if (lists.isEmpty() && markets.isEmpty()) item { EmptyHint("Пусто. «+» — список покупок («Сходи в магазин») или корзина маркетплейса («Алик»).") }
                if (lists.isNotEmpty()) item { SectionLabel("Списки покупок") }
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
                if (markets.isNotEmpty()) item { SectionLabel("Корзины маркетплейсов") }
                items(markets, key = { "m$it" }) { m ->
                    val mine = cart.filter { m in with(com.pank.vsevodnom.ui.life.CartTags) { it.tagList() } }
                    val want = mine.filter { !it.bought }
                    val sum = want.mapNotNull { it.price }.sum()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { market = m }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Peach), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Storefront, null, tint = Pal.PeachInk) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m, style = MaterialTheme.typography.titleMedium)
                            Text(if (mine.isEmpty()) "пусто" else "хочу ${want.size}" + (if (sum > 0) " · ≈ ${fmtPrice(sum)} ₴" else "") + " · куплено ${mine.size - want.size}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { addMenu = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Создать", tint = Color.White)
            DropdownMenu(addMenu, { addMenu = false }) {
                DropdownMenuItem({ Text("Создать список покупок") }, { addMenu = false; edit = ListPage(name = "", type = "SHOP", tint = lists.size % 6, sort = pages.size) }, leadingIcon = { Icon(Icons.Rounded.ShoppingCart, null) })
                DropdownMenuItem({ Text("Создать корзину маркетплейса") }, { addMenu = false; newMarket = "" }, leadingIcon = { Icon(Icons.Rounded.Storefront, null) })
            }
        }
    }
    newMarket?.let { g0 ->
        var g by remember { mutableStateOf(g0) }
        AlertDialog(onDismissRequest = { newMarket = null }, title = { Text("Новая корзина маркетплейса") }, text = { AppTextField(g, { g = it.replace(",", "") }, "Например: Алик, Rozetka, Temu") },
            confirmButton = { TextButton({ val n = g.trim(); if (n.isNotBlank()) { extraGroups = (extraGroups + n).distinct(); groupsPref.edit().putStringSet("groups", extraGroups.toSet()).apply(); market = n }; newMarket = null }) { Text("Создать") } },
            dismissButton = { TextButton({ newMarket = null }) { Text("Отмена") } })
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
    val rowH = 64.dp
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
              // key: строка «едет» вместе со своим пунктом, иначе после обмена жест обрывается и пункт застревает
              key(e.id) {
                val dragging = i == dragIdx
                Row(Modifier.fillMaxWidth().height(rowH).zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragY else 0f }
                    .padding(vertical = 3.dp).then(if (dragging) Modifier.shadow(8.dp, RoundedCornerShape(14.dp)) else Modifier)
                    .clip(RoundedCornerShape(14.dp)).background(if (e.done) t.bg.copy(alpha = 0.6f) else Pal.Card).clickable { edit = e }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (e.priority > 0) Box(Modifier.padding(end = 6.dp).size(9.dp).clip(CircleShape).background(prioColor(e.priority)))
                            Text(e.text, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (e.done) TextDecoration.LineThrough else null), color = if (e.done) Pal.Muted else Pal.Ink, maxLines = 1)
                        }
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                    }
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
                    CheckDivider()
                    CheckCircle(e.done, CHECK_GREY, CHECK_SIZE) { AppScope.io.launch { dao.upsertEntry(e.copy(done = !e.done)) } }
                }
              }
            }
        }
    }
    edit?.let { e ->
        var s by remember { mutableStateOf(e.text) }
        var n by remember { mutableStateOf(e.note) }
        var pr by remember { mutableIntStateOf(e.priority) }
        AlertDialog(onDismissRequest = { edit = null }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(s, { s = it }, "Пункт")
                AppTextField(n, { n = it }, "Описание (необязательно)", singleLine = false)
                Text("Приоритет", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                PriorityDots(pr) { pr = it }
            }
        },
            confirmButton = { TextButton({ if (s.isNotBlank()) AppScope.io.launch { dao.upsertEntry(e.copy(text = s.trim(), note = n.trim(), priority = pr)) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ AppScope.io.launch { dao.deleteEntry(e) }; edit = null }) { Text("Удалить", color = Pal.RoseInk) } })
    }
}


/** Корзина маркетплейса («Алик»): товары плитками по две — большое фото, название, цена, магазин; фильтры и сортировка. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MarketPage(group: String, groups: List<String>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).life() }
    val all by dao.cart().collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }       // 0 — новые, 1 — дешевле, 2 — дороже, 3 — важнее
    var prio by remember { mutableStateOf<Int?>(null) }
    var edit by remember { mutableStateOf<CartItem?>(null) }
    val mine = all.filter { group in with(com.pank.vsevodnom.ui.life.CartTags) { it.tagList() } }
    val shown = mine.filter { it.bought == (tab == 1) && (prio == null || it.priority == prio) }.let { l ->
        when (sort) { 1 -> l.sortedBy { it.price ?: Double.MAX_VALUE }; 2 -> l.sortedByDescending { it.price ?: -1.0 }; 3 -> l.sortedByDescending { it.priority }; else -> l.sortedByDescending { it.addedAt } }
    }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(group, onBack) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf("Хочу · ${mine.count { !it.bought }}", "Куплено · ${mine.count { it.bought }}"), tab, { tab = it }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Новые", "Дешевле", "Дороже", "Важнее").forEachIndexed { i, n -> Chip(n, sort == i, { sort = i }) }
                    Spacer(Modifier.width(6.dp))
                    listOf(3, 2, 1).forEach { p -> Chip(listOf("", "Низкий", "Средний", "Высокий")[p], prio == p, { prio = if (prio == p) null else p }) }
                }
                val total = shown.mapNotNull { it.price }.sum()
                if (total > 0) Text((if (tab == 0) "Всего ≈ " else "Потрачено ") + fmtPrice(total) + " ₴", style = MaterialTheme.typography.titleMedium)
            }
            if (shown.isEmpty()) EmptyHint(if (tab == 0) "Пусто. «+» — добавить товар: фото, ссылка, цена." else "Пока ничего не куплено", Modifier.padding(16.dp))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val c = shown[i]
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable { edit = c }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Pal.Bg), contentAlignment = Alignment.Center) {
                            if (c.photo.isNotBlank() || c.image.isNotBlank()) AppImage(c.photo.ifBlank { c.image }, Modifier.fillMaxSize(), maxPx = 600)
                            else Icon(Icons.Rounded.ShoppingBag, null, tint = Pal.Muted, modifier = Modifier.size(40.dp))
                            if (c.priority > 0) Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(12.dp).clip(CircleShape).background(prioColor(c.priority)))
                        }
                        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (c.bought) TextDecoration.LineThrough else null), maxLines = 2)
                                c.price?.let { Text(fmtPrice(it) + " " + (if (c.currency.isBlank() || c.currency == "UAH") "₴" else c.currency), style = MaterialTheme.typography.titleMedium, color = Pal.Ink) }
                                Text(listOfNotNull(c.store.ifBlank { null }, c.planDay?.let { "купить " + java.time.LocalDate.ofEpochDay(it).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")) }).joinToString(" · ").ifBlank { " " },
                                    style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
                            }
                            CheckCircle(c.bought, CHECK_GREY, 22.dp) { AppScope.io.launch { dao.upsertCart(c.copy(bought = !c.bought, boughtAt = if (!c.bought) System.currentTimeMillis() else null)) } }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = CartItem(title = "", tags = group, priority = 0) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Добавить товар", tint = Color.White) }
    }
    edit?.let { c -> com.pank.vsevodnom.ui.life.CartEditor(c, groups) { edit = null } }
}
