package com.pank.vsevodnom.ui.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

enum class DlFormat(val title: String) { BEST("Видео (лучшее до 1080p)"), SMALL("Видео 480p (меньше файл)"), AUDIO("Только звук (MP3)") }

data class DlJob(val id: Int, val url: String, val format: DlFormat) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("В очереди")
    var title by mutableStateOf<String?>(null)
    var resultUri by mutableStateOf<Uri?>(null)
    var failed by mutableStateOf(false)
}

object Dl {
    val jobs = mutableStateListOf<DlJob>()
    var ready by mutableStateOf(false)
    var initError by mutableStateOf<String?>(null)
    private var nextId = 1
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        if (ready) return
        runCatching { YoutubeDL.getInstance().init(ctx.applicationContext); FFmpeg.getInstance().init(ctx.applicationContext) }
            .onSuccess { ready = true }.onFailure { initError = it.message }
    }

    /** Сайты постоянно меняются — свежий yt-dlp чинит большинство ошибок. */
    suspend fun update(ctx: Context): String = runCatching {
        when (YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)) {
            YoutubeDL.UpdateStatus.DONE -> "Загрузчик обновлён"
            else -> "Уже последняя версия"
        }
    }.getOrElse { "Не удалось обновить: ${it.message}" }

    fun enqueue(ctx: Context, url: String, f: DlFormat) {
        val j = DlJob(nextId++, url.trim(), f); jobs.add(0, j)
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        scope.launch { run(ctx.applicationContext, j); if (jobs.none { it.status == "В очереди" || it.status.startsWith("Загрузка") }) ctx.stopService(Intent(ctx, DownloadService::class.java)) }
    }

    private fun run(ctx: Context, j: DlJob) {
        init(ctx)
        val tmp = File(ctx.getExternalFilesDir(null), "dl-tmp/${j.id}").apply { deleteRecursively(); mkdirs() }
        try {
            val req = YoutubeDLRequest(j.url)
            req.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
            req.addOption("--no-playlist")
            req.addOption("--restrict-filenames")
            when (j.format) {
                DlFormat.BEST -> { req.addOption("-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080]/b"); req.addOption("--merge-output-format", "mp4") }
                DlFormat.SMALL -> { req.addOption("-f", "bv*[height<=480][ext=mp4]+ba[ext=m4a]/b[height<=480]/b"); req.addOption("--merge-output-format", "mp4") }
                DlFormat.AUDIO -> { req.addOption("-x"); req.addOption("--audio-format", "mp3"); req.addOption("--audio-quality", "0") }
            }
            j.status = "Загрузка…"
            YoutubeDL.getInstance().execute(req, "job${j.id}") { p, _, line ->
                j.progress = (p / 100f).coerceIn(0f, 1f)
                if (j.title == null) Regex("Destination: .*/(.+)$").find(line)?.let { j.title = it.groupValues[1].substringBeforeLast('.') }
            }
            val out = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.length() } ?: error("Файл не появился")
            j.status = "Сохраняю…"
            j.resultUri = saveToDownloads(ctx, out, if (j.format == DlFormat.AUDIO) "audio/mpeg" else "video/mp4")
            j.title = j.title ?: out.nameWithoutExtension
            j.progress = 1f; j.status = "Готово · Загрузки/Всё в одном"
        } catch (e: Exception) {
            j.failed = true
            val m = e.message.orEmpty()
            j.status = when {
                m.contains("login", true) || m.contains("private", true) -> "Видео закрыто или требует входа"
                m.contains("Unsupported URL", true) -> "Эта ссылка не поддерживается"
                else -> "Ошибка. Попробуй «Обновить загрузчик». (${m.lines().lastOrNull { it.isNotBlank() }?.take(120) ?: "нет подробностей"})"
            }
        } finally { tmp.deleteRecursively() }
    }

    private fun saveToDownloads(ctx: Context, f: File, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Всё в одном")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Нет доступа к папке Загрузки")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }
}

/** Держит процесс живым, пока идут загрузки. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Загрузки", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java).putExtra("route", "downloads"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "dl").setSmallIcon(R.drawable.ic_notify).setContentTitle("Загружаю видео").setOngoing(true).setContentIntent(open).build()
        ServiceCompat.startForeground(this, 9, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        return START_NOT_STICKY
    }
}

/** Ссылка, пришедшая через «Поделиться» из YouTube/TikTok/Instagram. */
object SharedLink { var url by mutableStateOf<String?>(null) }

fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')')

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf(SharedLink.url ?: "") }
    var format by remember { mutableStateOf(DlFormat.BEST) }
    var updMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SharedLink.url = null; kotlinx.coroutines.withContext(Dispatchers.IO) { Dl.init(ctx) } }

    ScreenScaffold("Загрузки", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    Text("YouTube, TikTok, Instagram Reels и сотни других сайтов. Проще всего: в приложении нажми «Поделиться» → «Всё в одном».", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                AppTextField(url, { url = it }, "Ссылка на видео")
                Spacer(Modifier.height(6.dp))
                TextButton({ clip.getText()?.text?.let { t -> extractUrl(t)?.let { url = it } } }) { Text("Вставить из буфера") }
            }
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { DlFormat.entries.forEach { f -> Chip(f.title, f == format, { format = f }) } } }
            item {
                PillButton("Скачать", {
                    extractUrl(url)?.let { Dl.enqueue(ctx, it, format); url = "" }
                }, Modifier.fillMaxWidth(), enabled = extractUrl(url) != null && Dl.initError == null)
            }
            Dl.initError?.let { item { Text("Загрузчик не запустился: $it", color = Pal.RoseInk, style = MaterialTheme.typography.bodySmall) } }
            items(Dl.jobs, key = { it.id }) { j ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = {
                    j.resultUri?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, if (j.format == DlFormat.AUDIO) "audio/*" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
                }) {
                    Text(j.title ?: j.url, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(j.status, style = MaterialTheme.typography.bodySmall, color = if (j.failed) Pal.RoseInk else Pal.Muted)
                    if (!j.failed && j.progress < 1f) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            item {
                TextButton({ scope.launch { updMsg = "Обновляю…"; updMsg = kotlinx.coroutines.withContext(Dispatchers.IO) { Dl.update(ctx) } } }) { Text("Обновить загрузчик (если сайт перестал качаться)") }
                updMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Только для личного использования. Скачивание может нарушать правила площадок и авторские права — ответственность на тебе.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
