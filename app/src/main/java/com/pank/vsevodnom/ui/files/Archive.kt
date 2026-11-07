package com.pank.vsevodnom.ui.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Запись в архиве. */
data class ArcEntry(val name: String, val size: Long, val dir: Boolean)

/**
 * Архивы: zip, rar (RAR4; формат RAR5 библиотекой не поддерживается), tar, tar.gz/tgz, gz.
 * Файл копируется во временную папку, распаковка — в «Загрузки/<имя архива>/».
 */
object Archives {
    fun kind(name: String) = name.lowercase().let { n ->
        when { n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") -> "zip"; n.endsWith(".rar") -> "rar"
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"; n.endsWith(".tar") -> "tar"; n.endsWith(".gz") -> "gz"; else -> "zip" }
    }

    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val f = File(ctx.cacheDir, "arc/$name").apply { parentFile?.mkdirs() }
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Обход архива: для каждой записи вызывает cb(запись, поток с содержимым). */
    fun walk(f: File, kind: String, cb: (ArcEntry, InputStream?) -> Unit) {
        when (kind) {
            "rar" -> com.github.junrar.Archive(f).use { a ->
                for (h in a.fileHeaders) {
                    val e = ArcEntry(h.fileName.replace('\\', '/'), h.fullUnpackSize, h.isDirectory)
                    if (h.isDirectory) cb(e, null) else a.getInputStream(h).use { cb(e, it) }
                }
            }
            "tar", "tgz" -> (if (kind == "tgz") TarArchiveInputStream(GzipCompressorInputStream(f.inputStream().buffered())) else TarArchiveInputStream(f.inputStream().buffered())).use { t ->
                while (true) { val e = t.nextTarEntry ?: break; cb(ArcEntry(e.name, e.size, e.isDirectory), if (e.isDirectory) null else t) }
            }
            "gz" -> GzipCompressorInputStream(f.inputStream().buffered()).use { cb(ArcEntry(f.name.removeSuffix(".gz"), -1, false), it) }
            else -> ZipInputStream(f.inputStream().buffered()).use { z ->
                while (true) { val e = z.nextEntry ?: break; cb(ArcEntry(e.name, e.size, e.isDirectory), if (e.isDirectory) null else z) }
            }
        }
    }

    fun list(f: File, kind: String): List<ArcEntry> { val out = ArrayList<ArcEntry>(); walk(f, kind) { e, _ -> out += e }; return out }

    /** Распаковка в Загрузки. only — имена нужных записей (null — всё). */
    fun extract(ctx: Context, f: File, kind: String, only: Set<String>? = null): Int {
        val base = "Download/" + f.name.substringBeforeLast('.').removeSuffix(".tar") + "/"
        var n = 0
        walk(f, kind) { e, ins ->
            if (e.dir || ins == null || (only != null && e.name !in only)) return@walk
            val clean = e.name.split('/').filter { it.isNotBlank() && it != ".." }   // защита от «../» в архиве
            if (clean.isEmpty()) return@walk
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, clean.last())
                put(MediaStore.Downloads.RELATIVE_PATH, base + clean.dropLast(1).joinToString("/").let { if (it.isBlank()) "" else "$it/" })
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return@walk
            ctx.contentResolver.openOutputStream(u)!!.use { ins.copyTo(it) }
            n++
        }
        return n
    }

    /** Упаковать выбранные файлы в zip. */
    fun zip(ctx: Context, files: List<Uri>, out: Uri): Int {
        var n = 0
        ctx.contentResolver.openOutputStream(out)!!.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                val names = HashSet<String>()
                files.forEach { u ->
                    var name = displayName(ctx, u); var k = 1
                    while (!names.add(name)) name = "${name.substringBeforeLast('.')}_${k++}.${name.substringAfterLast('.', "")}"
                    z.putNextEntry(ZipEntry(name)); ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(z) }; z.closeEntry(); n++
                }
            }
        }
        return n
    }
}

private fun human(b: Long) = when { b < 0 -> ""; b < 1024 -> "$b Б"; b < 1024 * 1024 -> "${b / 1024} КБ"; else -> "%.1f МБ".format(b / 1048576.0).replace('.', ',') }

@Composable
fun ArchiveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<ArcEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var toZip by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = true; msg = null; selected.clear()
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(ctx, u)
                    val f = Archives.copyToCache(ctx, u, name)
                    f to Archives.list(f, Archives.kind(name))
                }
            }.onSuccess { (f, l) -> file = f; entries = l }
                .onFailure { file = null; entries = emptyList(); msg = if (it.javaClass.name.contains("junrar")) "Этот RAR не открывается: скорее всего формат RAR5 — его библиотека не читает. Zip, tar и старые RAR работают." else "Не открылся: ${it.message}" }
            busy = false
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> toZip = l }
    val makeZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { out ->
        if (out != null && toZip.isNotEmpty()) scope.launch { busy = true; msg = runCatching { withContext(Dispatchers.IO) { Archives.zip(ctx, toZip, out) } }.fold({ "В архив упаковано файлов: $it" }, { "Не получилось: ${it.message}" }); toZip = emptyList(); busy = false }
    }
    LaunchedEffect(toZip) { if (toZip.isNotEmpty()) makeZip.launch("архив.zip") }
    ScreenScaffold("Архивы", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Открыть архив", { open.launch(arrayOf("application/zip", "application/x-rar-compressed", "application/vnd.rar", "application/x-tar", "application/gzip", "*/*")) }, Modifier.weight(1f))
                PillButton("Создать zip", { pickMany.launch(arrayOf("*/*")) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            file?.let { f ->
                AppCard(Modifier.fillMaxWidth(), color = Pal.Butter) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text("${entries.count { !it.dir }} файлов · ${human(entries.sumOf { it.size.coerceAtLeast(0) })} · распаковка в «Загрузки/${f.name.substringBeforeLast('.')}»", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Распаковать всё", {
                            scope.launch { busy = true; msg = runCatching { withContext(Dispatchers.IO) { Archives.extract(ctx, f, Archives.kind(f.name)) } }.fold({ "Распаковано файлов: $it. Смотри в «Загрузки»." }, { "Ошибка: ${it.message}" }); busy = false }
                        }, Modifier.weight(1f))
                        if (selected.isNotEmpty()) PillButton("Выбранные (${selected.size})", {
                            scope.launch { busy = true; msg = runCatching { withContext(Dispatchers.IO) { Archives.extract(ctx, f, Archives.kind(f.name), selected.toSet()) } }.fold({ "Распаковано: $it" }, { "Ошибка: ${it.message}" }); busy = false }
                        }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (file == null && msg == null) item { EmptyHint("Открой .zip, .rar, .tar или .gz — покажу, что внутри, и распакую в «Загрузки».") }
            items(entries.filter { !it.dir }) { e ->
                val sel = e.name in selected
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Pal.Lilac else Pal.Card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sel, { if (it) selected.add(e.name) else selected.remove(e.name) })
                    Icon(Icons.Rounded.InsertDriveFile, null, tint = Pal.Muted, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(e.name.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (e.name.contains('/')) Text(e.name.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Text(human(e.size), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
