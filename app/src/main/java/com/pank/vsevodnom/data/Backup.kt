package com.pank.vsevodnom.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pank.vsevodnom.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Резервная копия всего, кроме паролей: база (вес, дела, деньги, календарь, места…), настройки,
 * фото и голосовые заметки. Пароли — своим зашифрованным бэкапом в разделе «Пароли»:
 * их ключ живёт в чипе телефона и на другой телефон не переносится.
 */
object Backup {
    private val SKIP_FILES = setOf("vault.key", "vault.dat", "vault.dat.tmp")
    private val SKIP_PREFS = setOf("osmdroid.xml")

    suspend fun export(ctx: Context, out: Uri): String = withContext(Dispatchers.IO) {
        val db = AppDb.get(ctx)
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val data = File(ctx.applicationInfo.dataDir)
        var n = 0
        ctx.contentResolver.openOutputStream(out)!!.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
                z.putNextEntry(ZipEntry("meta.json")); z.write(JSONObject().put("app", ctx.packageName).put("version", ver).put("db", db.openHelper.readableDatabase.version).put("at", System.currentTimeMillis()).toString().toByteArray()); z.closeEntry()
                fun add(f: File, name: String) { z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++ }
                File(data, "databases").listFiles()?.filter { it.name.startsWith("app.db") }?.forEach { add(it, "databases/${it.name}") }
                File(data, "shared_prefs").listFiles()?.filter { it.name !in SKIP_PREFS }?.forEach { add(it, "shared_prefs/${it.name}") }
                ctx.filesDir.walkTopDown().filter { it.isFile && it.name !in SKIP_FILES && !it.name.startsWith("mbgl") && !it.path.contains("/.mbgl") && !it.path.contains("/files/map/") }.forEach { add(it, "files/" + it.relativeTo(ctx.filesDir).path) }
            }
        }
        "Сохранено файлов: $n"
    }

    /** Восстановление: заменяет текущие данные и перезапускает приложение. */
    suspend fun restore(ctx: Context, src: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var meta: JSONObject? = null
        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(tmp, e.name)
                    require(f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "Повреждённый архив" }
                    if (e.isDirectory) { f.mkdirs(); continue }
                    f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
                    if (e.name == "meta.json") meta = JSONObject(f.readText())
                }
            }
        }
        require(meta?.optString("app") == ctx.packageName) { "Это не резервная копия «Всё в одном»" }
        require(File(tmp, "databases/app.db").exists()) { "В копии нет базы" }
        AppDb.closeForRestore()
        val data = File(ctx.applicationInfo.dataDir)
        File(data, "databases").listFiles()?.filter { it.name.startsWith("app.db") }?.forEach { it.delete() }
        File(tmp, "databases").copyRecursively(File(data, "databases"), overwrite = true)
        File(tmp, "shared_prefs").takeIf { it.exists() }?.copyRecursively(File(data, "shared_prefs"), overwrite = true)
        File(tmp, "files").takeIf { it.exists() }?.copyRecursively(ctx.filesDir, overwrite = true)
        tmp.deleteRecursively()
        // настройки и база читаются при старте — перезапускаемся начисто
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }
}
