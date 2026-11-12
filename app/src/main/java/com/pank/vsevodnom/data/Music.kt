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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
    /** Короче этого — «не музыка» (звонки, голосовые, звуки). По умолчанию 30 с. */
    fun minSec(ctx: Context) = prefs(ctx).getInt("min_sec", 30)
    fun setMinSec(ctx: Context, v: Int) = prefs(ctx).edit().putInt("min_sec", v).apply()
    /** Треки, которые владелец пометил «Не музыка» — в списке не показываются. */
    fun hidden(ctx: Context): Set<Long> = prefs(ctx).getStringSet("not_music", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun setHidden(ctx: Context, id: Long, hide: Boolean) {
        val cur = prefs(ctx).getStringSet("not_music", emptySet())!!.toMutableSet()
        if (hide) cur += id.toString() else cur -= id.toString()
        prefs(ctx).edit().putStringSet("not_music", cur).apply()
    }

    /** Вся музыка без помеченных «не музыка»; [withHidden] — вернуть и их (для списка скрытых). */
    fun scan(ctx: Context, withHidden: Boolean = false): List<Song> {
        if (!granted(ctx)) return emptyList()
        val hid = if (withHidden) emptySet() else hidden(ctx)
        val minMs = minSec(ctx) * 1000L
        val out = ArrayList<Song>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.ALBUM_ID)
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.DURATION} >= $minMs", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in hid) continue
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
    fun jump(i: Int) { if (i in queue.indices) start(i) }
    fun next() { if (queue.isEmpty()) return; start(if (shuffle) queue.indices.random() else (index + 1) % queue.size) }
    fun prev() { if (queue.isEmpty()) return; if (position() > 4000) seek(0) else start(if (index > 0) index - 1 else queue.lastIndex) }
    fun stop() { runCatching { mp?.release() }; mp = null; playing = false; index = -1; app?.let { it.stopService(Intent(it, MusicService::class.java)) } }
    private fun refresh() { app?.let { ContextCompat.startForegroundService(it, Intent(it, MusicService::class.java)) } }
}

/** Фоновое воспроизведение: уведомление с кнопками и управление с гарнитуры/экрана блокировки. */
class MusicService : Service() {
    private var session: MediaSession? = null
    private var art: android.graphics.Bitmap? = null
    private var artFor = -1L
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { "toggle" -> Player.toggle(); "next" -> Player.next(); "prev" -> Player.prev(); "stop" -> { Player.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY } }
        val s = Player.current ?: run { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("music", "Музыка", NotificationManager.IMPORTANCE_LOW))
        val ses = session ?: MediaSession(this, "music").also { m ->
            m.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!Player.playing) Player.toggle() }
                override fun onPause() { if (Player.playing) Player.toggle() }
                override fun onSkipToNext() = Player.next(); override fun onSkipToPrevious() = Player.prev()
                override fun onSeekTo(pos: Long) { Player.seek(pos.toInt()); startService(Intent(this@MusicService, MusicService::class.java)) }
                override fun onStop() { Player.stop() }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) { if (action == "close") Player.stop() }
            })
            m.isActive = true; session = m
        }
        // карточка в шторке (Android 13+ рисует её по сессии): обложка, длительность, перемотка, ✕ — закрыть плеер
        ses.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .addCustomAction(PlaybackState.CustomAction.Builder("close", "Закрыть", android.R.drawable.ic_menu_close_clear_cancel).build())
            .setState(if (Player.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, Player.position().toLong(), 1f).build())
        if (artFor != s.id) { artFor = s.id; art = runCatching { if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(s.uri, android.util.Size(512, 512), null) else null }.getOrNull() }
        ses.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, s.title).putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, s.artist)
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, s.album).putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, Player.duration().toLong().takeIf { it > 0 } ?: s.durationMs)
            .apply { art?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build())
        fun pi(a: String, rc: Int) = PendingIntent.getService(this, rc, Intent(this, MusicService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("route", "music"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "music").setSmallIcon(R.drawable.ic_notify).setContentTitle(s.title).setContentText(s.artist).setContentIntent(open)
            .setDeleteIntent(pi("stop", 5)).setOngoing(Player.playing).setLargeIcon(art).setColorized(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Назад", pi("prev", 1)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (Player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (Player.playing) "Пауза" else "Играть", pi("toggle", 2)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next), "Дальше", pi("next", 3)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Закрыть", pi("stop", 4)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(ses.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(55, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(55, n)
        if (!Player.playing) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    override fun onDestroy() { session?.release(); super.onDestroy() }
}
