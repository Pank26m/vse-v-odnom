package com.pank.vsevodnom.ui.recorder

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.alarm.Channels
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RecState {
    var recording by mutableStateOf(false)
    var startedAt by mutableLongStateOf(0L)
    val levels = mutableStateListOf<Float>()
    var version by mutableIntStateOf(0) // меняется, когда появляется новый файл
}

fun recordingsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Recordings").apply { mkdirs() }

/** Запись идёт в сервисе — не прерывается, если выключить экран или уйти из приложения. */
class RecorderService : Service() {
    companion object { const val STOP = "stop" }

    private var rec: MediaRecorder? = null
    private var file: File? = null
    private val h = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val amp = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
            RecState.levels.add((amp / 32767f).coerceIn(0f, 1f))
            if (RecState.levels.size > 120) RecState.levels.removeAt(0)
            h.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stop(); return START_NOT_STICKY }
        if (rec != null) return START_NOT_STICKY
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("rec", "Запись диктофона", android.app.NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null); enableVibration(false); setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("route", "recorder"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 41, Intent(this, RecorderService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "rec")
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("Идёт запись").setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .setContentIntent(open).addAction(0, "Остановить", stopPi).build()
        ServiceCompat.startForeground(this, 8, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

        val name = "Запись " + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".m4a"
        val f = File(recordingsDir(this), name)
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            rec = r; file = f
            RecState.recording = true; RecState.startedAt = SystemClock.elapsedRealtime(); RecState.levels.clear()
            h.post(poll)
        } catch (e: Exception) {
            f.delete(); stop()
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        h.removeCallbacks(poll)
        rec?.runCatching { stop() }?.onFailure { file?.delete() }
        rec?.release(); rec = null
        RecState.recording = false; RecState.version++
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { if (rec != null) stop(); super.onDestroy() }
}

private fun durationMs(f: File): Long = runCatching {
    MediaMetadataRetriever().use { it.setDataSource(f.absolutePath); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0 }
}.getOrDefault(0)

fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun RecorderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<Pair<File, Long>>>(emptyList()) }
    LaunchedEffect(RecState.version) {
        files = (recordingsDir(ctx).listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList())
            .sortedByDescending { it.lastModified() }.map { it to durationMs(it) }
    }
    var playing by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(RecState.recording) { while (RecState.recording) { now = SystemClock.elapsedRealtime(); delay(200) } }
    LaunchedEffect(player) { while (player != null) { pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(200) } }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    fun stopPlay() { player?.release(); player = null; playing = null; pos = 0 }
    fun play(f: File) {
        stopPlay()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { stopPlay() }
        }
        playing = f
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
    }
    fun toggleRec() {
        if (RecState.recording) { ctx.startService(Intent(ctx, RecorderService::class.java).setAction(RecorderService.STOP)); return }
        stopPlay()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    var tab by remember { mutableIntStateOf(0) }
    ScreenScaffold("Диктофон", onBack) {
        Row(Modifier.padding(16.dp, 0.dp, 16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Записи", tab == 0, { tab = 0 })
            Chip("Разговоры", tab == 1, { stopPlay(); tab = 1 })
        }
        if (tab == 1) { CallRecordsTab(); return@ScreenScaffold }
        AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Pal.Rose, radius = 16.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (RecState.recording) fmtDur(now - RecState.startedAt) else "Готов к записи", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                    val l = RecState.levels
                    val n = 60
                    val bw = size.width / n
                    for (i in 0 until n) {
                        val v = l.getOrNull(l.size - n + i) ?: 0f
                        val hgt = (0.06f + v * 0.94f) * size.height
                        drawLine(Pal.RoseInk.copy(alpha = 0.6f), Offset(i * bw + bw / 2, (size.height - hgt) / 2), Offset(i * bw + bw / 2, (size.height + hgt) / 2), bw * 0.55f, cap = StrokeCap.Round)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(if (RecState.recording) Pal.Ink else Pal.RoseInk).clickable { toggleRec() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (RecState.recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (RecState.recording) "Остановить" else "Записать", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (files.isEmpty()) item { EmptyHint("Записей пока нет") }
            items(files, key = { it.first.name }) { (f, dur) ->
                val isPlaying = playing == f
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Пауза" else "Слушать",
                            { if (isPlaying) stopPlay() else runCatching { play(f) } }, bg = Pal.Rose, tint = Pal.RoseInk)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                fmtDur(dur) + " · " + Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) +
                                    " · " + "%.1f МБ".format(f.length() / 1_048_576.0),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Поделиться") }, {
                                    menu = false
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                    ctx.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Отправить запись"))
                                })
                                DropdownMenuItem({ Text("Переименовать") }, { menu = false; renaming = f })
                                DropdownMenuItem({ Text("Удалить", color = Pal.RoseInk) }, { menu = false; deleting = f })
                            }
                        }
                    }
                    if (isPlaying && dur > 0) {
                        Slider(
                            value = pos.toFloat() / dur, onValueChange = { player?.seekTo((it * dur).toInt()); pos = (it * dur).toInt() },
                            colors = SliderDefaults.colors(thumbColor = Pal.RoseInk, activeTrackColor = Pal.RoseInk)
                        )
                        Text("${fmtDur(pos.toLong())} / ${fmtDur(dur)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    renaming?.let { f ->
        var name by remember { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Название") },
            text = { AppTextField(name, { name = it }, "Название") },
            confirmButton = {
                TextButton({
                    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    if (clean.isNotEmpty()) { if (playing == f) stopPlay(); f.renameTo(File(f.parentFile, "$clean.${f.extension}")); RecState.version++ }
                    renaming = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Отмена") } })
    }
    deleting?.let { f ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить «${f.nameWithoutExtension}»?") },
            confirmButton = { TextButton({ if (playing == f) stopPlay(); f.delete(); RecState.version++; deleting = null }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } })
    }
}
