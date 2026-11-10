package com.pank.vsevodnom.ui.movies

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.Movies
import com.pank.vsevodnom.data.Tg
import com.pank.vsevodnom.data.TgFilms
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Обложки: из памяти, иначе миниатюра Telegram (качается по одной). */
private object Covers {
    val mem = object : LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > 150 }
    suspend fun get(e: TgFilms.Entry): ImageBitmap? {
        synchronized(mem) { mem[e.msgId] }?.let { return it }
        val bm = withContext(Dispatchers.IO) {
            runCatching {
                val m = Tg.message(e.chatId, e.msgId)
                val id = TgFilms.thumbOf(m) ?: return@runCatching null
                Tg.thumb(id)?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            }.getOrNull()
        } ?: return null
        synchronized(mem) { mem[e.msgId] = bm }
        return bm
    }
    fun mini(e: TgFilms.Entry): ImageBitmap? = e.mini?.let { runCatching { val b = android.util.Base64.decode(it, 0); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
}

private fun dur(sec: Int) = if (sec <= 0) "" else if (sec >= 3600) "%d ч %02d мин".format(sec / 3600, sec / 60 % 60) else "${sec / 60} мин"
private fun hms(ms: Long) = (ms / 1000).let { s -> if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** «Кино» из канала Telegram: вход, выбор канала, фильмы и сериалы, «Смотреть онлайн». */
@Composable
fun TgCinemaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Tg.start(ctx) }
    var chat by remember { mutableStateOf(TgFilms.chat(ctx)) }
    when (Tg.auth) {
        Tg.Auth.READY -> if (chat == null) ChannelPicker(onBack) { chat = it; TgFilms.setChat(ctx, it) } else Library(chat!!, onBack, onChangeChat = { TgFilms.setChat(ctx, null); chat = null })
        else -> LoginFlow(onBack)
    }
}

// ---------------- вход ----------------

@Composable
private fun LoginFlow(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) { busy = true; msg = null; scope.launch { runCatching { block() }.onFailure { msg = it.message }; busy = false } }
    ScreenScaffold("Кино · Telegram", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (Tg.auth) {
                Tg.Auth.NO_KEYS, Tg.Auth.ERROR -> {
                    var id by remember { mutableStateOf(Tg.apiId(ctx)?.toString().orEmpty()) }
                    var hash by remember { mutableStateOf(Tg.apiHash(ctx)) }
                    AppCard(Modifier.fillMaxWidth(), color = Pal.Sky) {
                        Text("Фильмы смотрятся прямо из твоего канала в Telegram — без скачивания на телефон.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("1. Открой my.telegram.org → API development tools.\n2. Скопируй сюда api_id (число) и api_hash.\n3. Войди по номеру — код придёт в Telegram.",
                            style = MaterialTheme.typography.bodySmall, color = Pal.SkyInk)
                        TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))) } }) { Text("Открыть my.telegram.org") }
                    }
                    Tg.error?.let { Text(it, color = Pal.RoseInk, style = MaterialTheme.typography.bodyMedium) }
                    AppTextField(id, { id = it.filter(Char::isDigit) }, "api_id", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    AppTextField(hash, { hash = it.trim() }, "api_hash")
                    Text("Ключи хранятся только на телефоне.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    PillButton("Сохранить и войти", { Tg.setKeys(ctx, id, hash); Tg.restart(ctx) }, Modifier.fillMaxWidth(), enabled = id.isNotBlank() && hash.length >= 16)
                }
                Tg.Auth.STARTING, Tg.Auth.LOGGING_OUT -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Tg.Auth.WAIT_PHONE -> {
                    var ph by remember { mutableStateOf("+380") }
                    Text("Номер телефона аккаунта Telegram", style = MaterialTheme.typography.titleMedium)
                    AppTextField(ph, { ph = it }, "+380…", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    PillButton("Получить код", { run { Tg.phone(ph) } }, Modifier.fillMaxWidth(), enabled = !busy && ph.count(Char::isDigit) >= 9)
                }
                Tg.Auth.WAIT_CODE -> {
                    var c by remember { mutableStateOf("") }
                    Text(Tg.codeHint, style = MaterialTheme.typography.bodyMedium)
                    AppTextField(c, { c = it.filter(Char::isDigit).take(8) }, "Код", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    PillButton("Войти", { run { Tg.code(c) } }, Modifier.fillMaxWidth(), enabled = !busy && c.length >= 5)
                }
                Tg.Auth.WAIT_PASSWORD -> {
                    var pw by remember { mutableStateOf("") }
                    Text("Облачный пароль Telegram (двухэтапная проверка)" + if (Tg.passwordHint.isNotBlank()) "\nПодсказка: ${Tg.passwordHint}" else "", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    PillButton("Войти", { run { Tg.password(pw) } }, Modifier.fillMaxWidth(), enabled = !busy && pw.isNotBlank())
                }
                Tg.Auth.READY -> {}
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it, color = Pal.RoseInk) }
        }
    }
}

// ---------------- выбор канала ----------------

@Composable
private fun ChannelPicker(onBack: () -> Unit, onPick: (Tg.ChatRef) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Tg.ChatRef>?>(null) }
    var link by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { list = runCatching { Tg.channels() }.onFailure { msg = it.message }.getOrDefault(emptyList()) }
    ScreenScaffold("Где лежит кино?", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(link, { link = it.trim() }, "Ссылка на канал (t.me/+…)")
            PillButton("Открыть по ссылке", { scope.launch { runCatching { Tg.byLink(link) }.onSuccess(onPick).onFailure { msg = it.message } } }, Modifier.fillMaxWidth(), enabled = link.contains("t.me/"))
            msg?.let { Text(it, color = Pal.RoseInk) }
            SectionLabel("Или выбери из своих каналов")
        }
        val l = list
        if (l == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(l.sortedByDescending { it.title.contains("кино", true) }, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { onPick(c) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Campaign, null, tint = Pal.SkyInk); Text("  " + c.title, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

// ---------------- библиотека ----------------

@Composable
private fun Library(chat: Tg.ChatRef, onBack: () -> Unit, onChangeChat: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(TgFilms.load(ctx, chat.id)) }
    var loading by remember { mutableStateOf(false) }
    var found by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }   // 0 всё, 1 фильмы, 2 сериалы, 3 продолжить
    var open by remember { mutableStateOf<TgFilms.Title?>(null) }
    var menu by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() {
        if (loading) return
        loading = true; err = null
        scope.launch {
            runCatching { Tg.videos(chat.id) { found = it }.mapNotNull { TgFilms.entryOf(it) } }
                .onSuccess { l -> entries = l; withContext(Dispatchers.IO) { TgFilms.save(ctx, chat.id, l) } }
                .onFailure { err = it.message }
            loading = false
        }
    }
    LaunchedEffect(chat.id) { refresh() }
    val titles = remember(entries) { TgFilms.group(entries) }
    open?.let { t -> BackHandler { open = null; tick++ }; TitlePage(titles.firstOrNull { it.key == t.key } ?: t) { open = null; tick++ }; return }
    @Suppress("UNUSED_EXPRESSION") tick
    val shown = titles.filter { t -> (q.isBlank() || t.name.contains(q, true)) && when (filter) {
        1 -> !t.series; 2 -> t.series
        3 -> t.items.any { TgFilms.position(ctx, it) > 0 }
        else -> true
    } }
    ScreenScaffold(chat.title, onBack, actions = {
        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { refresh() })
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Другой канал") }, { menu = false; onChangeChat() })
                DropdownMenuItem({ Text("Очистить кэш (${Tg.cacheSize(ctx) / 1_048_576} МБ)") }, { menu = false; Tg.clearCache(ctx); Toast.makeText(ctx, "Кэш очищен", Toast.LENGTH_SHORT).show() })
                DropdownMenuItem({ Text("Выйти из Telegram") }, { menu = false; AppScope.io.launch { runCatching { Tg.logout() } } })
            }
        }
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(q, { q = it }, "Поиск по названию")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Всё · ${titles.size}", "Фильмы", "Сериалы", "Продолжить").forEachIndexed { i, s -> Chip(s, filter == i, { filter = i }) }
            }
            if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Читаю канал… найдено видео: $found", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            err?.let { Text(it, color = Pal.RoseInk, style = MaterialTheme.typography.bodySmall) }
        }
        if (!loading && titles.isEmpty()) EmptyHint("В канале не нашлось видео. Проверь, что выбран нужный канал (⋮ → Другой канал).")
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.key }) { t -> TitleCard(t) { open = t } }
        }
    }
}

@Composable
private fun Cover(e: TgFilms.Entry, modifier: Modifier) {
    var img by remember(e.msgId) { mutableStateOf(synchronized(Covers.mem) { Covers.mem[e.msgId] }) }
    val mini = remember(e.msgId) { Covers.mini(e) }
    LaunchedEffect(e.msgId) { if (img == null) img = Covers.get(e) }
    Box(modifier.background(Color(0xFF2B2A33))) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (mini != null) Image(mini, null, Modifier.fillMaxSize().blur(8.dp), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center).size(40.dp))
    }
}

@Composable
private fun TitleCard(t: TgFilms.Title, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val first = t.items.minByOrNull { (it.parsed.season ?: 0) * 1000 + (it.parsed.episode ?: 0) } ?: t.items.first()
    val started = t.items.firstOrNull { TgFilms.position(ctx, it) > 0 }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))) {
            Cover(first, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
            Text(if (t.series) "${t.seasons.size} сез. · ${t.items.size} сер." else dur(first.duration).ifBlank { "фильм" },
                color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (!t.series && TgFilms.watched(ctx, first)) Icon(Icons.Rounded.CheckCircle, "Просмотрено", tint = Color(0xFF6FE3A5), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            started?.let { e -> if (e.duration > 0) LinearProgressIndicator({ (TgFilms.position(ctx, e) / 1000f / e.duration).coerceIn(0f, 1f) },
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = Color(0xFFE5484D), trackColor = Color.Transparent) }
        }
        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        first.parsed.quality?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
    }
}

// ---------------- фильм / сериал ----------------

@Composable
private fun TitlePage(t: TgFilms.Title, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var season by remember(t.key) { mutableIntStateOf(t.seasons.first()) }
    var playing by remember { mutableStateOf<TgFilms.Entry?>(null) }
    var info by remember(t.key) { mutableStateOf<com.pank.vsevodnom.data.MovieInfo?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(t.key) { info = runCatching { Movies.search(t.name).firstOrNull()?.let { Movies.details(it.id) } }.getOrNull() }
    val first = t.items.minByOrNull { (it.parsed.season ?: 0) * 1000 + (it.parsed.episode ?: 0) } ?: t.items.first()
    // что включить по кнопке: начатая серия, иначе следующая после последней просмотренной, иначе первая
    @Suppress("UNUSED_EXPRESSION") tick
    val order = t.seasons.flatMap { t.episodes(it) }
    val resume = order.firstOrNull { TgFilms.position(ctx, it) > 0 } ?: order.indexOfLast { TgFilms.watched(ctx, it) }.let { i -> order.getOrNull(i + 1) } ?: order.first()
    playing?.let { e ->
        val i = order.indexOf(e)
        TgPlayerScreen(e, t.name + (e.parsed.episode?.let { " · ${e.parsed.season ?: 1} сезон, $it серия" } ?: ""), order.getOrNull(i + 1)?.takeIf { t.series },
            onNext = { playing = it }) { playing = null; tick++ }
    }
    ScreenScaffold(t.name, onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Cover(first, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)))
            }
            item {
                val pos = TgFilms.position(ctx, resume)
                PillButton(when { pos > 0 -> "Продолжить с ${hms(pos)}"; t.series -> "Смотреть онлайн · ${resume.parsed.season ?: 1} сезон, ${resume.parsed.episode ?: 1} серия"; else -> "Смотреть онлайн" },
                    { playing = resume }, Modifier.fillMaxWidth())
            }
            info?.let { m ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(m.years.ifBlank { null }, m.genres.take(3).joinToString(", ").ifBlank { null }, m.imdbRating?.let { "IMDb %.1f".format(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    }
                }
            }
            if (!t.series) item {
                Text(listOfNotNull(dur(first.duration).ifBlank { null }, first.parsed.quality, "${first.size / 1_048_576} МБ").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            if (t.series) {
                if (t.seasons.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.seasons.forEach { s -> Chip("$s сезон", season == s, { season = s }) }
                    }
                }
                items(t.episodes(season), key = { it.msgId }) { e ->
                    val pos = TgFilms.position(ctx, e)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { playing = e }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(e, Modifier.width(96.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(e.parsed.episode?.let { "$it серия" } ?: e.parsed.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(dur(e.duration).ifBlank { null }, if (pos > 0) "остановился на ${hms(pos)}" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        Icon(if (TgFilms.watched(ctx, e)) Icons.Rounded.CheckCircle else Icons.Rounded.PlayCircle, null,
                            tint = if (TgFilms.watched(ctx, e)) Pal.MintInk else Pal.SkyInk, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}
