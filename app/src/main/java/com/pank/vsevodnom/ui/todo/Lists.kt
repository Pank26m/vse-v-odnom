package com.pank.vsevodnom.ui.todo

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
    val shown = items.filter {
        when (filter) { 1 -> it.kind == "film"; 2 -> it.kind == "series"; 3 -> it.status == 0; 4 -> it.status == 2; else -> true }
    }.sortedWith(compareBy({ it.status == 2 }, { -it.addedAt }))
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Фильмы и сериалы", onBack) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Фильмы", "Сериалы", "Хочу", "Посмотрел").forEachIndexed { i, s -> Chip(s, filter == i, { filter = i }) }
            }
            LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (shown.isEmpty()) item(span = { GridItemSpan(3) }) { EmptyHint("Пусто. Нажми «+» и начни писать название — остальное подтянется само.") }
                items(shown, key = { it.id }) { m ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { edit = m }) {
                        Box {
                            if (m.poster.isNotBlank()) AppImage(m.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f), maxPx = 500, radius = 10.dp)
                            else Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Pal.Lilac), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = Pal.LilacInk) }
                            if (m.status == 2) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0xCC2DB37A)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                            if (m.link.isNotBlank()) Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0xCC2AABEE)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
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
    Row {
        (1..5).forEach { i ->
            Icon(if (i <= v) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (i <= v) Color(0xFFF5B301) else Color(0xFFCDD1D8),
                modifier = Modifier.size(size).then(if (onSet != null) Modifier.clickable { onSet(if (v == i) 0 else i) } else Modifier))
        }
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
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
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
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDone) { Icon(Icons.Rounded.Close, "Закрыть") }
                Text(if (m0.id == 0L) "Добавить" else "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (m.extId.isNotBlank()) IconButton({
                    refreshing = true
                    scope.launch { runCatching { Movies.details(m.extId) }.onSuccess { i -> m = m.copy(poster = i.poster.ifBlank { m.poster }, year = i.years, kind = i.kind, details = i.toJson()) }; refreshing = false }
                }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Обновить данные") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WATCH.forEachIndexed { i, s -> Chip(s, m.status == i, { m = m.copy(status = i) }) } }
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
