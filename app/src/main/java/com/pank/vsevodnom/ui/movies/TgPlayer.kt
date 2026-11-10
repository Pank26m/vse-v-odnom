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
            // загрузка встала (сеть, перемотка) — попросим снова с текущего места
            val f = Tg.file(fileId)
            if (f != null && !f.local.isDownloadingActive && !f.local.isDownloadingCompleted) Tg.download(fileId, pos)
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
                        hasNext = idx < entries.lastIndex, onNext = { idx++ }, onDismiss = { finish() })
                }
            }
        }
    }
}

/** Плеер: видео из Telegram, продолжение с места, «следующая серия». После закрытия скачанное удаляется. */
@OptIn(UnstableApi::class)
@Composable
fun TgPlayerContent(entry: TgFilms.Entry, title: String, hasNext: Boolean, onNext: () -> Unit, onDismiss: () -> Unit) {
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
                val load = DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 120_000, 2_500, 5_000).build()
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
            AndroidView({ c -> PlayerView(c).apply { this.player = player; keepScreenOn = true; setShowNextButton(false); setShowPreviousButton(false) } },
                Modifier.fillMaxSize())
            @Suppress("UNUSED_EXPRESSION") Tg.fileTick
            val f = Tg.file(fi.first)
            if (buffering && err == null)
                Text("Загрузка из Telegram…" + (f?.local?.downloadedSize?.takeIf { it > 0 }?.let { " ${it / 1_048_576} МБ" } ?: ""),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        } else if (err == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        err?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть", tint = Color.White) }
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            if (hasNext) TextButton(onNext) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White); Text(" Следующая", color = Color.White) }
        }
    }
}
