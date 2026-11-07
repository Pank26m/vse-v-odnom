package com.pank.vsevodnom.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R

data class Song(val id: Long, val title: String, val artist: String, val album: String, val durationMs: Long, val folder: String, val uri: Uri, val albumId: Long)

/** Музыка из памяти телефона (MediaStore) и общий плеер. */
object Music {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun scan(ctx: Context): List<Song> {
        if (!granted(ctx)) return emptyList()
        val out = ArrayList<Song>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.ALBUM_ID)
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.DURATION} > 20000", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Song(id, c.getString(1) ?: "Без названия", c.getString(2)?.takeIf { it != "<unknown>" } ?: "Неизвестный исполнитель", c.getString(3) ?: "",
                        c.getLong(4), (c.getString(5) ?: "").trimEnd('/').substringAfterLast('/'), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), c.getLong(6))
                }
            }
        }
        return out
    }

    fun art(albumId: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

object Player {
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    var index by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(0)   // 0 нет, 1 всё, 2 один трек
    val current get() = queue.getOrNull(index)
    private var mp: MediaPlayer? = null
    private var app: Context? = null

    fun position() = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)
    fun duration() = runCatching { mp?.duration ?: 0 }.getOrDefault(0)
    fun seek(ms: Int) { runCatching { mp?.seekTo(ms) } }

    fun play(ctx: Context, list: List<Song>, i: Int) { app = ctx.applicationContext; queue = list; start(i) }

    private fun start(i: Int) {
        val ctx = app ?: return
        val s = queue.getOrNull(i) ?: return
        index = i
        runCatching { mp?.release() }
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(ctx, s.uri)
            setOnCompletionListener { onEnd() }
            prepare(); start()
        }
        playing = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, MusicService::class.java))
    }

    private fun onEnd() = when {
        repeat == 2 -> start(index)
        index < queue.lastIndex || repeat == 1 || shuffle -> next()
        else -> { playing = false; refresh() }
    }

    fun toggle() { val m = mp ?: return; if (m.isPlaying) { m.pause(); playing = false } else { m.start(); playing = true }; refresh() }
    fun next() { if (queue.isEmpty()) return; start(if (shuffle) queue.indices.random() else (index + 1) % queue.size) }
    fun prev() { if (queue.isEmpty()) return; if (position() > 4000) seek(0) else start(if (index > 0) index - 1 else queue.lastIndex) }
    fun stop() { runCatching { mp?.release() }; mp = null; playing = false; index = -1; app?.let { it.stopService(Intent(it, MusicService::class.java)) } }
    private fun refresh() { app?.let { ContextCompat.startForegroundService(it, Intent(it, MusicService::class.java)) } }
}

/** Фоновое воспроизведение: уведомление с кнопками и управление с гарнитуры/экрана блокировки. */
class MusicService : Service() {
    private var session: MediaSession? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { "toggle" -> Player.toggle(); "next" -> Player.next(); "prev" -> Player.prev(); "stop" -> { Player.stop(); stopSelf(); return START_NOT_STICKY } }
        val s = Player.current ?: run { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("music", "Музыка", NotificationManager.IMPORTANCE_LOW))
        val ses = session ?: MediaSession(this, "music").also { m ->
            m.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Player.toggle(); override fun onPause() = Player.toggle()
                override fun onSkipToNext() = Player.next(); override fun onSkipToPrevious() = Player.prev()
            })
            m.isActive = true; session = m
        }
        ses.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (Player.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, Player.position().toLong(), 1f).build())
        ses.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, s.title).putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, s.artist).build())
        fun pi(a: String, rc: Int) = PendingIntent.getService(this, rc, Intent(this, MusicService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("route", "music"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "music").setSmallIcon(R.drawable.ic_notify).setContentTitle(s.title).setContentText(s.artist).setContentIntent(open)
            .setDeleteIntent(pi("stop", 5)).setOngoing(Player.playing)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Назад", pi("prev", 1)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (Player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (Player.playing) "Пауза" else "Играть", pi("toggle", 2)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next), "Дальше", pi("next", 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(ses.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(55, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(55, n)
        if (!Player.playing) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    override fun onDestroy() { session?.release(); super.onDestroy() }
}
