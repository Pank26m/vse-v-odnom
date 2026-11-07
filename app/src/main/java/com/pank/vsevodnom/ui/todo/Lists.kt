package com.pank.vsevodnom.ui.todo

import androidx.compose.animation.core.animateFloat
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/** Создаёт три стандартные страницы и переносит в них старые записи. */
suspend fun ensureListPages(ctx: android.content.Context) {
    val dao = AppDb.get(ctx).todo()
    if (dao.pagesOnce().isEmpty()) {
        val m = dao.upsertPage(ListPage(name = "Фильмы / сериалы", type = "MOVIES", tint = 3, sort = 0))
        dao.upsertPage(ListPage(name = "Список покупок", type = "SHOP", tint = 1, sort = 1))
        val n = dao.upsertPage(ListPage(name = "Заметки", type = "NOTES", tint = 4, sort = 2))
        dao.adoptMedia(m); dao.adoptNotes(n)
    }
}

// ================= Фильмы и сериалы =================

private val WATCH = listOf("Хочу", "Смотрю", "Посмотрел")
private val WATCH_COLORS = listOf(Color(0xFFDCE7FB) to Color(0xFF3E63B8), Color(0xFFFBEFC9) to Color(0xFF8A6B12), Color(0xFFD9F1E3) to Color(0xFF2F7D57))

/** Сердечко «в избранное»: при добавлении подпрыгивает и разлетается искорками. */
@Composable
private fun FavoriteButton(on: Boolean, onToggle: () -> Unit) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val burst = remember { androidx.compose.animation.core.Animatable(0f) }
    var fire by remember { mutableIntStateOf(0) }
    LaunchedEffect(fire) {
        if (fire == 0) return@LaunchedEffect
        launch { scale.snapTo(0.5f); scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.3f, stiffness = 400f)) }
        launch { burst.snapTo(0f); burst.animateTo(1f, androidx.compose.animation.core.tween(500)) }
    }
    Box(Modifier.size(48.dp).clickable { if (!on) fire++; onToggle() }, contentAlignment = Alignment.Center) {
        if (burst.value in 0.01f..0.99f) androidx.compose.foundation.Canvas(Modifier.size(48.dp)) {
            val r = size.width * (0.25f + 0.3f * burst.value)
            for (k in 0 until 8) { val a = k * Math.PI / 4
                drawCircle(Color(0xFFE5486A).copy(alpha = 1f - burst.value), size.width * 0.04f, center + androidx.compose.ui.geometry.Offset((Math.cos(a) * r).toFloat(), (Math.sin(a) * r).toFloat())) }
        }
        Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Избранное", Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, tint = if (on) Color(0xFFE5486A) else Pal.Ink)
    }
}

@Composable
fun MoviesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    LaunchedEffect(Unit) { ensureListPages(ctx) }
    val pages by dao.pages().collectAsState(initial = emptyList())
    val moviePages = pages.filter { it.type == "MOVIES" }.map { it.id }.toSet()
    val p = pages.firstOrNull { it.type == "MOVIES" } ?: ListPage(name = "Фильмы", type = "MOVIES")
    val all by dao.media().collectAsState(initial = emptyList())
    val items = all.filter { it.pageId in moviePages || it.pageId == 0L }
    var filter by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<MediaItem?>(null) }
    val mp = remember { ctx.getSharedPreferences("movies", android.content.Context.MODE_PRIVATE) }
    var sort by remember { mutableIntStateOf(mp.getInt("sort", 0)) }   // 0 новые, 1 звёзды, 2 избранное, 3 по названию, 4 по году
    var sortMenu by remember { mutableStateOf(false) }
    val shown = items.filter {
        when (filter) { 1 -> it.kind == "film"; 2 -> it.kind == "series"; 3 -> it.status == 0; 4 -> it.status == 2; 5 -> it.favorite; else -> true }
    }.let { l -> when (sort) {
        1 -> l.sortedWith(compareBy({ -it.rating }, { -it.addedAt }))
        2 -> l.sortedWith(compareBy({ !it.favorite }, { -it.addedAt }))
        3 -> l.sortedBy { it.title.lowercase() }
        4 -> l.sortedByDescending { it.year.take(4).toIntOrNull() ?: 0 }
        else -> l.sortedWith(compareBy({ it.status == 2 }, { -it.addedAt }))
    } }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Фильмы и сериалы", onBack, actions = {
            Box {
                RoundIconButton(Icons.Rounded.SwapVert, "Сортировка", { sortMenu = true })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("Сначала новые", "По звёздам", "Избранное сверху", "По названию", "По году").forEachIndexed { i, t ->
                        DropdownMenuItem({ Text(t) }, { sort = i; mp.edit().putInt("sort", i).apply(); sortMenu = false }, trailingIcon = { if (sort == i) Icon(Icons.Rounded.Check, null) })
                    }
                }
            }
        }) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Фильмы", "Сериалы", "Хочу", "Посмотрел", "♥ Избранное").forEachIndexed { i, s -> Chip(s, filter == i, { filter = i }) }
            }
            LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (shown.isEmpty()) item(span = { GridItemSpan(3) }) { EmptyHint("Пусто. Нажми «+» и начни писать название — остальное подтянется само.") }
                items(shown, key = { it.id }) { m ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { edit = m }) {
                        Box {
                            if (m.poster.isNotBlank()) AppImage(m.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f), maxPx = 500, radius = 10.dp)
                            else Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Pal.Lilac), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = Pal.LilacInk) }
                            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { StatusBadge(m.status) }
                            if (m.link.contains("t.me", true) || m.link.startsWith("tg:")) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { TelegramBadge(22.dp) }
                            else if (m.link.isNotBlank()) Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0xCC2AABEE)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Link, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                            if (m.favorite) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0xCCFFFFFF)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Favorite, "Избранное", tint = Color(0xFFE5486A), modifier = Modifier.size(16.dp)) }
                        }
                        Text(m.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                        Stars(m.rating, 14.dp)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape).background(BLUE).clickable { search = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить", tint = Color.White)
        }
    }
    if (search) MovieSearch(onDismiss = { search = false }) { item -> search = false; edit = item.copy(pageId = p.id) }
    edit?.let { MovieEditor(it) { edit = null } }
}

@Composable
fun Stars(v: Int, size: androidx.compose.ui.unit.Dp, onSet: ((Int) -> Unit)? = null) {
    // при новой оценке звёзды по очереди «подпрыгивают» и крутятся
    val pops = remember { List(5) { androidx.compose.animation.core.Animatable(1f) } }
    val spins = remember { List(5) { androidx.compose.animation.core.Animatable(0f) } }
    var seq by remember { mutableIntStateOf(0) }
    LaunchedEffect(seq) {
        if (seq == 0) return@LaunchedEffect
        (0 until v).forEach { i ->
            launch { kotlinx.coroutines.delay(i * 70L); pops[i].snapTo(0.4f); pops[i].animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f)) }
            launch { kotlinx.coroutines.delay(i * 70L); spins[i].snapTo(-72f); spins[i].animateTo(0f, androidx.compose.animation.core.tween(420)) }
        }
    }
    Row {
        (1..5).forEach { i ->
            Icon(if (i <= v) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (i <= v) Color(0xFFF5B301) else Color(0xFFCDD1D8),
                modifier = Modifier.size(size).graphicsLayer { scaleX = pops[i - 1].value; scaleY = pops[i - 1].value; rotationZ = spins[i - 1].value }
                    .then(if (onSet != null) Modifier.clickable { onSet(if (v == i) 0 else i); seq++ } else Modifier))
        }
    }
}

/** Значок статуса на постере: посмотрел — глаз, смотрю — «!», хочу — закладка, которая покачивается. */
@Composable
fun StatusBadge(status: Int) {
    val (bg, icon) = when (status) {
        2 -> Color(0xCC2DB37A) to Icons.Rounded.Visibility
        1 -> Color(0xCCE9A92B) to Icons.Rounded.PriorityHigh
        else -> Color(0xCC5B8DEF) to Icons.Rounded.BookmarkAdd
    }
    val wiggle = if (status == 0) androidx.compose.animation.core.rememberInfiniteTransition(label = "w").animateFloat(-12f, 12f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "r").value else 0f
    Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = wiggle })
    }
}

/** Значок Telegram: бумажный самолётик в голубом круге. */
@Composable
fun TelegramBadge(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(Color(0xFF2AABEE))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, w * 0.5f); lineTo(w * 0.76f, w * 0.28f); lineTo(w * 0.66f, w * 0.74f)
            lineTo(w * 0.5f, w * 0.62f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.42f, w * 0.58f); lineTo(w * 0.68f, w * 0.36f); lineTo(w * 0.38f, w * 0.55f); close()
        }
        drawPath(p, Color.White)
    }
}

/** Поиск как у городов в погоде: пишешь — снизу варианты; выбрал — подтягивается всё остальное. */
@Composable
private fun MovieSearch(onDismiss: () -> Unit, onPicked: (MediaItem) -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<MovieHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(q, Net.online) {
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(450); busy = true; err = null
        runCatching { Movies.search(q.trim()) }.onSuccess { hits = it }.onFailure { err = "Нет связи с поиском" }
        busy = false
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                OutlinedTextField(q, { q = it }, Modifier.weight(1f).focusRequester(fr), placeholder = { Text("Название фильма или сериала") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (err != null || !Net.online) item { NetWait(err) }
                items(hits, key = { it.id }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable(enabled = loadingId == null) {
                        loadingId = h.id
                        scope.launch {
                            val info = runCatching { Movies.details(h.id) }.getOrNull()
                            loadingId = null
                            onPicked(if (info != null) MediaItem(list = "Фильмы", title = info.title, kind = info.kind, poster = info.poster, year = info.years, details = info.toJson(), extId = info.id)
                                     else MediaItem(list = "Фильмы", title = h.title, extId = h.id, year = Regex("\\d{4}").find(h.description)?.value ?: ""))
                        }
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (h.description.contains("сериал", true) || h.description.contains("серіал", true)) Icons.Rounded.LiveTv else Icons.Rounded.Movie, null, tint = Pal.LilacInk)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.titleSmall)
                            if (h.description.isNotBlank()) Text(h.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (loadingId == h.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (q.isNotBlank()) item {
                    TextButton({ onPicked(MediaItem(list = "Фильмы", title = q.trim())) }) { Text("Добавить «${q.trim()}» без поиска") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieEditor(m0: MediaItem, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var m by remember { mutableStateOf(m0) }
    var refreshing by remember { mutableStateOf(false) }
    var linkEdit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val info = remember(m.details) { MovieInfo.fromItem(m) }
    fun save() { if (m.title.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).todo().upsertMedia(m.copy(title = m.title.trim(), link = m.link.trim())) }; onDone() }
    fun open(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (u.startsWith("http") || u.startsWith("tg:")) u else "https://$u"))) }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDone) { Icon(Icons.Rounded.Close, "Закрыть") }
                Text(if (m0.id == 0L) "Добавить" else "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (m.extId.isNotBlank()) IconButton({
                    refreshing = true
                    scope.launch { runCatching { Movies.details(m.extId) }.onSuccess { i -> m = m.copy(poster = i.poster.ifBlank { m.poster }, year = i.years, kind = i.kind, details = i.toJson()) }; refreshing = false }
                }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Обновить данные") }
                FavoriteButton(m.favorite) { m = m.copy(favorite = !m.favorite); if (m0.id != 0L) AppScope.io.launch { AppDb.get(ctx).todo().upsertMedia(m.copy(title = m.title.trim())) } }
                if (m0.id != 0L) IconButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteMedia(m0) }; onDone() }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                IconButton({ save() }) { Icon(Icons.Rounded.Check, "Сохранить", tint = BLUE) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    if (m.poster.isNotBlank()) AppImage(m.poster, Modifier.width(130.dp).aspectRatio(2f / 3f), maxPx = 700, radius = 12.dp)
                    else Box(Modifier.width(130.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Pal.Lilac), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = Pal.LilacInk, modifier = Modifier.size(40.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleLarge)
                        info?.original?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(listOfNotNull(if (m.kind == "series") "Сериал" else if (m.kind == "film") "Фильм" else null, m.year.ifBlank { null },
                            info?.duration?.let { "$it мин" }, info?.seasons?.let { "$it сез." }, info?.episodes?.let { "$it сер." }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                        info?.imdbRating?.let { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("IMDb", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(5.dp, 1.dp))
                                Text("  " + "%.1f".format(r).replace('.', ',') + (info.imdbVotes?.let { "  · ${if (it >= 1000) "${it / 1000} тыс." else "$it"} оценок" } ?: ""), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Моя оценка", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        Stars(m.rating, 30.dp) { m = m.copy(rating = it) }
                    }
                }
                if (m0.id == 0L && m.extId.isBlank()) AppTextField(m.title, { m = m.copy(title = it) }, "Название")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WATCH.forEachIndexed { i, s ->
                        val (bg, ink) = WATCH_COLORS[i]
                        val sel = m.status == i
                        val c by androidx.compose.animation.animateColorAsState(if (sel) bg else Pal.Card, label = "st")
                        Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(c).clickable { m = m.copy(status = i) },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(listOf(Icons.Rounded.BookmarkAdd, Icons.Rounded.PlayArrow, Icons.Rounded.Visibility)[i], null, Modifier.size(18.dp), tint = if (sel) ink else Pal.Muted)
                            Text(" $s", style = MaterialTheme.typography.labelLarge, color = if (sel) ink else Pal.Ink)
                        }
                    }
                }
                TelegramLink(m.link, editing = linkEdit || m.link.isBlank(), onEdit = { linkEdit = true }, onChange = { m = m.copy(link = it) },
                    onConfirm = { linkEdit = false; if (m0.id != 0L && m.link.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).todo().upsertMedia(m.copy(link = m.link.trim())) } },
                    onRemove = { m = m.copy(link = ""); linkEdit = true }, onOpen = { open(m.link) })
                info?.let { i ->
                    AppCard(Modifier.fillMaxWidth()) {
                        listOf("Режиссёр" to i.directors, "Сценарий" to i.writers, "В ролях" to i.cast, "Жанр" to i.genres, "Страна" to i.countries).forEach { (k, v) ->
                            if (v.isNotEmpty()) Row(Modifier.padding(vertical = 3.dp)) {
                                Text(k, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.width(96.dp))
                                Text(v.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (i.wiki.isNotBlank()) Chip("Википедия", false, { open(i.wiki) })
                        if (i.imdbId.isNotBlank()) Chip("IMDb", false, { open("https://www.imdb.com/title/${i.imdbId}/") })
                        Chip("Найти трейлер", false, { open("https://www.youtube.com/results?search_query=" + Uri.encode("${m.title} ${m.year.take(4)} трейлер")) })
                    }
                }
                AppTextField(m.note, { m = m.copy(note = it) }, "Заметка", singleLine = false)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Ссылка на скачанный файл в Telegram: ввёл → галочка → застывает в синюю кнопку «Смотреть в Telegram», справа ⋮. */
@Composable
private fun TelegramLink(link: String, editing: Boolean, onEdit: () -> Unit, onChange: (String) -> Unit, onConfirm: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    val tg = Color(0xFF2AABEE)
    if (editing) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Send, null, tint = tg, modifier = Modifier.size(20.dp))
            BasicTextField(link, onChange, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pal.Ink), cursorBrush = SolidColor(tg),
                decorationBox = { inner -> if (link.isEmpty()) Text("Ссылка из Telegram (t.me/…)", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB8BCC6)); inner() })
            val ok = link.isNotBlank()
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (ok) tg else Pal.Ink.copy(alpha = 0.08f)).clickable(enabled = ok, onClick = onConfirm), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Готово", tint = if (ok) Color.White else Pal.Muted)
            }
        }
    } else {
        var menu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(tg).clickable(onClick = onOpen), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                Text(" Смотреть в Telegram", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ссылка") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Изменить ссылку") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Удалить ссылку", color = Pal.RoseInk) }, { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Pal.RoseInk) })
                }
            }
        }
    }
}
