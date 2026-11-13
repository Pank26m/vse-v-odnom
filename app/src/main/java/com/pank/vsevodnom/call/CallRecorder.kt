package com.pank.vsevodnom.call

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.R
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Запись разговоров.
 * С Android 10 обычное приложение во время звонка получает от микрофона тишину; исключение — приложение
 * с включённой службой специальных возможностей (так работают ACR/Cube). Поэтому есть пустая служба
 * [CallRecAccess] — она ничего не читает, только даёт право записывать. Линию (голос собеседника «из провода»)
 * сторонним приложениям Android не отдаёт: пишется микрофон — твой голос чётко, собеседник слышен на громкой связи.
 *
 * Хранение: files/callrec/<id>.crec (аудио) и <id>.meta (номер, имя, направление) — зашифрованы.
 * Ключ файла случайный, сам он зашифрован ключом Android Keystore (из телефона не вынимается).
 * Шифр — AES-GCM блоками по 64 КБ (чтобы не держать весь файл в памяти). Срок — 90 дней, кроме закреплённых.
 */
object CallRec {
    const val KEEP_DAYS = 90L
    private const val ALIAS = "callrec_key"
    private const val MAGIC = 0x43524543   // "CREC"
    private const val CHUNK = 64 * 1024

    data class Meta(val id: Long, val number: String, val name: String?, val incoming: Boolean, val durMs: Long, val pinned: Boolean = false)

    /** Идёт ли запись сейчас. */
    var active by mutableStateOf(false)
    var version by mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("callrec", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()
    /** Источник звука: 0 микрофон, 1 распознавание речи, 2 связь (VOICE_COMMUNICATION), 3 линия (почти везде запрещено). */
    fun source(ctx: Context) = prefs(ctx).getInt("src", 1)   // по умолчанию «распознавание речи» — у владельца работает только он
    /** Усиление при прослушивании (LoudnessEnhancer, мБ): собеседник без громкой связи записывается тихо. */
    fun boost(ctx: Context) = prefs(ctx).getInt("boost", 1200)
    fun setBoost(ctx: Context, mb: Int) = prefs(ctx).edit().putInt("boost", mb).apply()
    fun setSource(ctx: Context, s: Int) = prefs(ctx).edit().putInt("src", s).apply()
    val SOURCES = listOf("Микрофон", "Распознавание речи", "Связь (VOICE_COMMUNICATION)", "Линия (обычно запрещено)")
    fun pinned(ctx: Context): Set<String> = prefs(ctx).getStringSet("pinned", emptySet())!!
    fun setPinned(ctx: Context, id: Long, on: Boolean) { prefs(ctx).edit().putStringSet("pinned", if (on) pinned(ctx) + "$id" else pinned(ctx) - "$id").apply(); version++ }

    fun dir(ctx: Context) = File(ctx.filesDir, "callrec").apply { mkdirs() }
    fun tmpDir(ctx: Context) = File(ctx.noBackupFilesDir, "callrec_tmp").apply { mkdirs() }
    fun playDir(ctx: Context) = File(ctx.cacheDir, "callrec_play").apply { mkdirs() }

    /** Включена ли наша служба специальных возможностей (без неё во время звонка пишется тишина). */
    fun accessOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, CallRecAccess::class.java).flattenToString()
        return s.split(':').any { it.equals(me, true) }
    }

    // ---------- шифрование ----------
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun encrypt(input: InputStream, output: OutputStream) {
        val dk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrap = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val wrapped = wrap.doFinal(dk)
        val out = DataOutputStream(output.buffered())
        out.writeInt(MAGIC); out.writeInt(wrap.iv.size); out.write(wrap.iv); out.writeInt(wrapped.size); out.write(wrapped)
        val key = SecretKeySpec(dk, "AES")
        val buf = ByteArray(CHUNK); val rnd = SecureRandom()
        while (true) {
            var n = 0
            while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
            if (n == 0) break
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(buf, 0, n)
            out.writeInt(ct.size); out.write(iv); out.write(ct)
            if (n < CHUNK) break
        }
        out.writeInt(0); out.flush()
        dk.fill(0)
    }

    private fun decrypt(input: InputStream, output: OutputStream) {
        val inp = DataInputStream(input.buffered())
        require(inp.readInt() == MAGIC) { "не наш файл" }
        val wiv = ByteArray(inp.readInt()).also { inp.readFully(it) }
        val wrapped = ByteArray(inp.readInt()).also { inp.readFully(it) }
        val dk = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, wiv)) }.doFinal(wrapped)
        val key = SecretKeySpec(dk, "AES")
        while (true) {
            val len = inp.readInt(); if (len <= 0) break
            val iv = ByteArray(12).also { inp.readFully(it) }
            val ct = ByteArray(len).also { inp.readFully(it) }
            output.write(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(ct))
        }
        output.flush(); dk.fill(0)
    }

    /** Зашифровать готовую запись и стереть открытый файл. */
    fun seal(ctx: Context, tmp: File, meta: Meta) {
        runCatching {
            val d = dir(ctx)
            File(d, "${meta.id}.crec.part").let { part -> tmp.inputStream().use { i -> part.outputStream().use { o -> encrypt(i, o) } }; part.renameTo(File(d, "${meta.id}.crec")) }
            val js = JSONObject().put("n", meta.number).put("name", meta.name ?: "").put("in", meta.incoming).put("dur", meta.durMs).toString()
            File(d, "${meta.id}.meta").outputStream().use { o -> encrypt(js.byteInputStream(), o) }
        }
        tmp.delete(); version++
    }

    fun list(ctx: Context): List<Meta> {
        cleanup(ctx)
        val pins = pinned(ctx)
        return dir(ctx).listFiles { f -> f.name.endsWith(".meta") }.orEmpty().mapNotNull { f ->
            runCatching {
                val bo = java.io.ByteArrayOutputStream(); f.inputStream().use { decrypt(it, bo) }
                val j = JSONObject(bo.toString("UTF-8"))
                val id = f.name.removeSuffix(".meta").toLong()
                Meta(id, j.optString("n"), j.optString("name").ifBlank { null }, j.optBoolean("in"), j.optLong("dur"), "$id" in pins)
            }.getOrNull()
        }.sortedByDescending { it.id }
    }

    /** Старше 90 дней — удалить (кроме закреплённых); недописанные остатки — тоже. */
    fun cleanup(ctx: Context) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val pins = pinned(ctx)
        dir(ctx).listFiles().orEmpty().forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            if (f.name.endsWith(".part") || (id != null && id < limit && "$id" !in pins)) f.delete()
        }
        // открытые копии для прослушивания живут только пока слушаешь
        playDir(ctx).listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }.forEach { it.delete() }
        // запись, которую не успели зашифровать (приложение убили) — шифруем сейчас
        if (!active) tmpDir(ctx).listFiles().orEmpty().forEach { t ->
            val id = t.nameWithoutExtension.toLongOrNull()
            if (id == null || t.length() == 0L) t.delete() else seal(ctx, t, Meta(id, "", null, false, durationOf(t)))
        }
    }

    fun delete(ctx: Context, id: Long) { File(dir(ctx), "$id.crec").delete(); File(dir(ctx), "$id.meta").delete(); setPinned(ctx, id, false) }

    /** Расшифровать во временный файл для прослушивания в приложении. */
    fun openForPlay(ctx: Context, id: Long): File {
        val out = File(playDir(ctx), "$id.m4a")
        if (!out.exists()) File(dir(ctx), "$id.crec").inputStream().use { i -> out.outputStream().use { o -> decrypt(i, o) } }
        return out
    }
    fun clearPlay(ctx: Context) { playDir(ctx).listFiles().orEmpty().forEach { it.delete() } }

    /** Скачать на устройство (открытый .m4a в выбранное место). */
    fun export(ctx: Context, id: Long, uri: android.net.Uri) {
        File(dir(ctx), "$id.crec").inputStream().use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { o -> decrypt(i, o) } }
    }

    fun durationOf(f: File): Long = runCatching {
        MediaMetadataRetriever().use { it.setDataSource(f.absolutePath); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0 }
    }.getOrDefault(0)

    // ---------- управление ----------
    fun start(ctx: Context, number: String, name: String?, incoming: Boolean) {
        if (active || !enabled(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CallRecService::class.java)
                .putExtra("n", number).putExtra("name", name ?: "").putExtra("in", incoming))
        }
    }
    fun stop(ctx: Context) { if (active) runCatching { ctx.startService(Intent(ctx, CallRecService::class.java).setAction("stop")) } }
}

/** Служба записи: держит микрофон, пока идёт разговор. Уведомление — минимальное (без значка в строке состояния). */
class CallRecService : Service() {
    private var rec: MediaRecorder? = null
    private var tmp: File? = null
    private var meta: CallRec.Meta? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finish(); return START_NOT_STICKY }
        if (rec != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("callrec", "Запись разговора", NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null); setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 75, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "callrec").setSmallIcon(R.drawable.ic_notify).setContentTitle("Разговор записывается")
            .setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE).build()
        runCatching { ServiceCompat.startForeground(this, 7071, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        val id = System.currentTimeMillis()
        meta = CallRec.Meta(id, intent?.getStringExtra("n") ?: "", intent?.getStringExtra("name")?.ifBlank { null }, intent?.getBooleanExtra("in", false) ?: false, 0)
        val f = File(CallRec.tmpDir(this), "$id.m4a")
        val sources = listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_CALL)
        val want = sources.getOrElse(CallRec.source(this)) { MediaRecorder.AudioSource.MIC }
        // выбранный источник; если телефон не дал — микрофон
        for (src in listOf(want, MediaRecorder.AudioSource.MIC).distinct()) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            val ok = runCatching {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1); r.setAudioSamplingRate(32_000); r.setAudioEncodingBitRate(64_000)
                r.setOutputFile(f.absolutePath)
                r.prepare(); r.start()
            }.isSuccess
            if (ok) { rec = r; tmp = f; break } else { r.release(); f.delete() }
        }
        if (rec == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        CallRec.active = true
        return START_NOT_STICKY
    }

    private fun finish() {
        val r = rec; val f = tmp; val m = meta
        rec = null; tmp = null
        if (r != null) { runCatching { r.stop() }; r.release() }
        CallRec.active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (f != null && m != null && f.length() > 0) {
            val dur = CallRec.durationOf(f)
            Thread { CallRec.seal(applicationContext, f, m.copy(durMs = dur)); stopSelf() }.start()
        } else { f?.delete(); stopSelf() }
    }

    override fun onDestroy() { if (rec != null) finish(); super.onDestroy() }
}

/** Пустая служба специальных возможностей: ничего не читает и не нажимает — нужна только затем,
 *  чтобы Android не глушил микрофон во время звонка. */
class CallRecAccess : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
