package com.pank.vsevodnom.ui.movies

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.setContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.pank.vsevodnom.data.Tg
import com.pank.vsevodnom.data.TgFilms
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Источник для плеера: читает видео из Telegram по мере загрузки. TDLib качает файл с того места, куда смотрим
 * (перемотка = новая загрузка с нового места), плеер ждёт, пока нужный кусок появится. Файл целиком не нужен.
 */
@OptIn(UnstableApi::class)
class TgDataSource(private val fileId: Int, private val total: Long) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var end = 0L
    private var availEnd = 0L
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        transferInitializing(spec)
        pos = spec.position
        end = if (spec.length != C.LENGTH_UNSET.toLong()) pos + spec.length else total
        availEnd = pos
        Tg.download(fileId, pos)
        transferStarted(spec)
        return end - pos
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= end) return C.RESULT_END_OF_INPUT
        var waited = 0L
        while (true) {
            if (pos >= availEnd) availEnd = pos + Tg.prefix(fileId, pos)
            if (availEnd > pos) {
                val path = Tg.file(fileId)?.local?.path?.takeIf { it.isNotBlank() }
                    ?: runCatching { Tg.call(TdApi.GetFile(fileId), 5_000).local.path }.getOrNull()?.takeIf { it.isNotBlank() }
                if (path != null) {
                    if (path != rafPath) { runCatching { raf?.close() }; raf = RandomAccessFile(path, "r"); rafPath = path }
                    val r = raf!!
                    r.seek(pos)
                    val n = r.read(buffer, offset, minOf(length.toLong(), availEnd - pos, end - pos).toInt())
                    if (n > 0) { pos += n; bytesTransferred(n); return n }
                }
            }
            // загрузка встала или Telegram качает другое место файла (после перемотки плеер читал конец файла) —
            // каждые ~1,2 с без прогресса просим снова именно с текущего места
            val f = Tg.file(fileId)
            if (f != null && !f.local.isDownloadingCompleted && (!f.local.isDownloadingActive || waited % 1200 == 0L && waited > 0)) Tg.download(fileId, pos)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            Tg.waitUpdate(400); waited += 400
            if (waited > 90_000) throw IOException("Telegram не отдаёт видео — проверь интернет")
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf?.close() }; raf = null; rafPath = null
        if (uri != null) { uri = null; transferEnded() }
    }
}

/**
 * Отдельный экран плеера: сразу горизонтальный, без пересоздания при повороте (раньше плеер был окном внутри
 * главного экрана — поворот пересоздавал экран, и плеер исчезал). Получает канал, список серий по порядку и номер.
 */
class TgPlayerActivity : androidx.activity.ComponentActivity() {
    /** Режим «картинка в картинке» — плеер прячет всё, кроме видео. */
    var pip by mutableStateOf(false)
    /** Видео идёт — при сворачивании сразу уходим в «картинку в картинке». */
    var playing = false

    fun enterPip() {
        runCatching {
            val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9))
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setSeamlessResizeEnabled(true)
            enterPictureInPictureMode(b.build())
        }
    }
    // отдельная задача (для «картинки в картинке»): новый фильм при уже открытом плеере — перезапуск экрана
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (playing) enterPip() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); pip = isInPictureInPictureMode
    }

    companion object {
        fun intent(ctx: android.content.Context, chatId: Long, order: List<Long>, index: Int, title: String) =
            android.content.Intent(ctx, TgPlayerActivity::class.java).putExtra("chat", chatId).putExtra("order", order.toLongArray()).putExtra("index", index).putExtra("title", title)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Tg.start(this)
        val chat = intent.getLongExtra("chat", 0L)
        val order = intent.getLongArrayExtra("order")?.toList().orEmpty()
        val start = intent.getIntExtra("index", 0)
        val title = intent.getStringExtra("title").orEmpty()
        val all = TgFilms.load(this, chat).associateBy { it.msgId }
        val entries = order.mapNotNull { all[it] }
        setContent {
            com.pank.vsevodnom.ui.theme.AppTheme {
                if (entries.isEmpty()) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Видео не найдено — обнови список канала", color = Color.White) }
                else {
                    var idx by rememberSaveable { mutableIntStateOf(start.coerceIn(0, entries.lastIndex)) }
                    val e = entries[idx]
                    TgPlayerContent(e, title + (e.parsed.episode?.let { " · ${e.parsed.season ?: 1} сезон, $it серия" } ?: ""),
                        hasNext = idx < entries.lastIndex, onNext = { idx++ }, onDismiss = { finish() },
                        entries = entries, onPick = { idx = it }, pip = pip, onPip = { enterPip() }, onPlaying = { playing = it })
                }
            }
        }
    }
}

/** Плеер: видео из Telegram, продолжение с места, «следующая серия». После закрытия скачанное удаляется. */
@OptIn(UnstableApi::class)
@Composable
fun TgPlayerContent(entry: TgFilms.Entry, title: String, hasNext: Boolean, onNext: () -> Unit, onDismiss: () -> Unit,
                    entries: List<TgFilms.Entry> = listOf(entry), onPick: (Int) -> Unit = {}, pip: Boolean = false, onPip: () -> Unit = {}, onPlaying: (Boolean) -> Unit = {}) {
    // название, «следующая», серии — только вместе с кнопками плеера (нажатие на экран), иначе — только фильм
    var controls by remember { mutableStateOf(true) }
    var episodes by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var fileInfo by remember(entry) { mutableStateOf<Pair<Int, Long>?>(null) }
    var err by remember(entry) { mutableStateOf<String?>(null) }
    var buffering by remember(entry) { mutableStateOf(true) }
    LaunchedEffect(entry) {
        runCatching {
            // клиент Telegram мог ещё стартовать — подождём готовности
            var waited = 0
            while (Tg.auth != Tg.Auth.READY && waited < 15_000) { delay(250); waited += 250 }
            val m = Tg.message(entry.chatId, entry.msgId)
            val f = TgFilms.fileOf(m) ?: error("В сообщении нет видео")
            fileInfo = f.id to (f.size.takeIf { it > 0 } ?: f.expectedSize)
        }.onFailure { err = it.message ?: "Не удалось открыть видео" }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fi = fileInfo
        if (fi != null) {
            val player = remember(fi) {
                val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 120_000, 1_000, 3_000).build()
                ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
                    val src = ProgressiveMediaSource.Factory { TgDataSource(fi.first, fi.second) }
                        .createMediaSource(MediaItem.fromUri("tg://${entry.chatId}/${entry.msgId}"))
                    setMediaSource(src)
                    TgFilms.position(ctx, entry).takeIf { it > 0 }?.let { seekTo(it) }
                    prepare(); playWhenReady = true
                }
            }
            DisposableEffect(player) {
                val l = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { err = "Ошибка воспроизведения: " + (error.cause?.message ?: error.errorCodeName) }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { onPlaying(isPlaying) }
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_ENDED) { TgFilms.setPosition(ctx, entry, player.duration, player.duration); if (hasNext) onNext() }
                    }
                }
                player.addListener(l)
                onDispose {
                    if (player.playbackState != Player.STATE_ENDED) TgFilms.setPosition(ctx, entry, player.currentPosition, player.duration)
                    player.removeListener(l); player.release()
                    Tg.forget(fi.first)   // не храним фильм на телефоне
                }
            }
            // позиция — каждые 10 секунд (если приложение закроют)
            LaunchedEffect(player) { while (true) { delay(10_000); if (player.isPlaying) TgFilms.setPosition(ctx, entry, player.currentPosition, player.duration) } }
            AndroidView({ c -> PlayerView(c).apply {
                this.player = player; keepScreenOn = true; setShowNextButton(false); setShowPreviousButton(false)
                controllerShowTimeoutMs = 3500
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
            } }, Modifier.fillMaxSize(), update = { v -> v.useController = !pip; if (pip) v.hideController() })
            @Suppress("UNUSED_EXPRESSION") Tg.fileTick
            val f = Tg.file(fi.first)
            if (buffering && err == null && !pip)
                Text("Загрузка из Telegram…" + (f?.local?.downloadedSize?.takeIf { it > 0 }?.let { " ${it / 1_048_576} МБ" } ?: ""),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        } else if (err == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        err?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        if (!pip) androidx.compose.animation.AnimatedVisibility(controls || fileInfo == null || err != null, Modifier.align(Alignment.TopStart),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", tint = Color.White) }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                if (entries.size > 1) TextButton({ episodes = true }) { Icon(Icons.AutoMirrored.Rounded.List, null, tint = Color.White); Text(" Серии", color = Color.White) }
                if (hasNext) TextButton(onNext) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White); Text(" Следующая", color = Color.White) }
                IconButton(onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Картинка в картинке", tint = Color.White) }
            }
        }
        if (episodes && !pip) EpisodePicker(entries, entries.indexOf(entry), onPick = { episodes = false; onPick(it) }, onDismiss = { episodes = false })
    }
}

/** Выбор серии: сезоны стрелками ‹ ›, серии — списком. */
@Composable
private fun EpisodePicker(entries: List<TgFilms.Entry>, cur: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val seasons = remember(entries) { entries.map { it.parsed.season ?: 1 }.distinct().sorted() }
    var si by remember { mutableIntStateOf(seasons.indexOf(entries.getOrNull(cur)?.parsed?.season ?: 1).coerceAtLeast(0)) }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).androidx_clickable(onDismiss), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxHeight().width(340.dp).background(Color(0xF21C1C1E)).androidx_clickable {}.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (si > 0) si-- }, enabled = si > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Предыдущий сезон", tint = if (si > 0) Color.White else Color.Gray) }
                Text("${seasons.getOrNull(si) ?: 1} сезон", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ if (si < seasons.lastIndex) si++ }, enabled = si < seasons.lastIndex) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Следующий сезон", tint = if (si < seasons.lastIndex) Color.White else Color.Gray) }
            }
            val list = entries.withIndex().filter { (it.value.parsed.season ?: 1) == seasons.getOrNull(si) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(list.size) { k ->
                    val (i, e) = list[k]
                    val pos = TgFilms.position(ctx, e)
                    Row(Modifier.fillMaxWidth().androidx_clickable { onPick(i) }.background(if (i == cur) Color(0x332AABEE) else Color.Transparent).padding(12.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.parsed.episode ?: (k + 1)} серия", color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(when { pos == -1L -> "✓"; pos > 0 -> "начата"; else -> "" }, color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun Modifier.androidx_clickable(onClick: () -> Unit) = this.then(Modifier.clickable(
    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null, onClick = onClick))
