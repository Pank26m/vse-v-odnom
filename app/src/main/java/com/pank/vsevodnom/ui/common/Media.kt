package com.pank.vsevodnom.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val bmpCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1; while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}

/** Картинка из интернета (кешируется на диске), из файла приложения или из галереи. */
suspend fun loadImage(ctx: Context, src: String, maxPx: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
    if (src.isBlank()) return@withContext null
    bmpCache.get("$src@$maxPx")?.let { return@withContext it }
    val bytes = runCatching {
        when {
            src.startsWith("http") -> {
                val key = MessageDigest.getInstance("MD5").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
                val f = File(ctx.cacheDir, "img/$key")
                if (f.exists()) f.readBytes() else {
                    val c = URL(src).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VseVOdnom")
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    c.inputStream.use { it.readBytes() }.also { f.parentFile?.mkdirs(); f.writeBytes(it) }
                }
            }
            src.startsWith("/") -> File(src).readBytes()
            else -> ctx.contentResolver.openInputStream(Uri.parse(src))!!.use { it.readBytes() }
        }
    }.getOrNull() ?: return@withContext null
    decodeSampled(bytes, maxPx)?.also { bmpCache.put("$src@$maxPx", it) }
}

@Composable
fun AppImage(src: String, modifier: Modifier, maxPx: Int = 600, radius: Dp = 12.dp) {
    val ctx = LocalContext.current
    var bmp by remember(src) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src) { bmp = loadImage(ctx, src, maxPx) }
    Box(modifier.clip(RoundedCornerShape(radius)).background(Pal.Line), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.Image, null, tint = Pal.Muted)
    }
}

/** Копирует выбранный файл внутрь приложения: так он переживёт удаление из галереи и попадёт в резервную копию. */
fun copyIntoApp(ctx: Context, uri: Uri, dir: String, ext: String = "jpg"): String? = runCatching {
    val d = File(ctx.filesDir, dir).apply { mkdirs() }
    val f = File(d, "${System.currentTimeMillis()}.$ext")
    ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
    f.absolutePath
}.getOrNull()

/** Маленький диктофон для заметок: запись в файл приложения и проигрывание. */
class VoiceNote(private val ctx: Context, private val dir: String) {
    private var rec: MediaRecorder? = null
    private var player: MediaPlayer? = null
    var recording by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    private var file: File? = null

    fun start(): Boolean = runCatching {
        val d = File(ctx.filesDir, dir).apply { mkdirs() }
        file = File(d, "voice_${System.currentTimeMillis()}.m4a")
        rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000); setAudioSamplingRate(44_100)
            setOutputFile(file!!.absolutePath)
            prepare(); start()
        }
        recording = true
    }.isSuccess

    fun stop(): String? {
        runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null; recording = false
        return file?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
    }

    fun play(path: String) {
        stopPlay()
        player = runCatching { MediaPlayer().apply { setDataSource(path); prepare(); start(); setOnCompletionListener { stopPlay() } } }.getOrNull()
        playing = player != null
    }

    fun stopPlay() { runCatching { player?.release() }; player = null; playing = false }
    fun release() { stop(); stopPlay() }
}

/** Плашка голосовой заметки: ▶ / ■ и длительность. */
@Composable
fun VoiceChip(path: String, voice: VoiceNote, onDelete: (() -> Unit)? = null) {
    val dur = remember(path) {
        runCatching { android.media.MediaMetadataRetriever().use { it.setDataSource(path); it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } }.getOrNull() ?: 0L
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Lilac).clickable { if (voice.playing) voice.stopPlay() else voice.play(path) }.padding(10.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (voice.playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = Pal.LilacInk)
        Spacer(Modifier.width(6.dp))
        Text("Голосовая · ${dur / 60000}:%02d".format(dur / 1000 % 60), style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
        if (onDelete != null) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted, modifier = Modifier.size(18.dp).clickable(onClick = onDelete)) }
    }
}

/** Таймер записи. */
@Composable
fun RecordingTimer() {
    var s by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); s++ } }
    Text("● ${s / 60}:%02d".format(s % 60), style = MaterialTheme.typography.labelLarge, color = Pal.RoseInk)
}
