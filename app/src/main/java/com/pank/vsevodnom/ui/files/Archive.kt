package com.pank.vsevodnom.ui.files

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import me.zhanghai.android.libarchive.Archive as LA
import me.zhanghai.android.libarchive.ArchiveEntry as LAE

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
 * Архивы: zip, rar (RAR4 и RAR5 — через libarchive, запасной путь — junrar), 7z, tar, tar.gz/tgz, gz.
 * Файл копируется во временную папку, распаковка — в «Загрузки/<имя архива>/».
 */
object Archives {
    fun kind(name: String) = name.lowercase().let { n ->
        when { n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") -> "zip"; n.endsWith(".rar") -> "rar"; n.endsWith(".7z") -> "rar"
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"; n.endsWith(".tar") -> "tar"; n.endsWith(".gz") -> "gz"; else -> "zip" }
    }

    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val f = File(ctx.cacheDir, "arc/$name").apply { parentFile?.mkdirs() }
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Обход архива: для каждой записи вызывает cb(запись, поток с содержимым). */
    fun walk(f: File, kind: String, pass: String? = null, cb: (ArcEntry, InputStream?) -> Unit) {
        // с паролем — только libarchive (зашифрованные zip/7z/rar4); RAR5 с паролем libarchive не расшифровывает
        if (pass != null) { walkLibarchive(f, pass, cb); return }
        when (kind) {
            // libarchive; если он не смог открыть файл вообще — пробуем junrar (старые RAR)
            "rar" -> if (runCatching { var seen = 0; runCatching { walkLibarchive(f, null) { e, i -> seen++; cb(e, i) } }.onFailure { if (seen > 0) throw IllegalStateException(it.message) else throw it } }.exceptionOrNull()?.let { it !is IllegalStateException } == true) com.github.junrar.Archive(f).use { a ->
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

    /** libarchive: читает RAR5, RAR4, 7z и многое другое. */
    private fun walkLibarchive(f: File, pass: String?, cb: (ArcEntry, InputStream?) -> Unit) {
        val a = LA.readNew()
        try {
            LA.readSupportFilterAll(a); LA.readSupportFormatAll(a)
            if (!pass.isNullOrEmpty()) LA.readAddPassphrase(a, pass.toByteArray())
            LA.readOpenFileName(a, f.absolutePath.toByteArray(), 64 * 1024)
            val buf = java.nio.ByteBuffer.allocateDirect(64 * 1024)
            while (true) {
                val e = LA.readNextHeader(a)
                if (e == 0L) break
                val name = (runCatching { LAE.pathnameUtf8(e) }.getOrNull() ?: LAE.pathname(e)?.let { String(it) } ?: "без имени").replace('\\', '/')
                val dir = LAE.filetype(e) == 0x4000
                val size = if (LAE.sizeIsSet(e)) LAE.size(e) else -1L
                if (dir) { cb(ArcEntry(name, size, true), null); continue }
                var used = false
                val ins = object : InputStream() {
                    private var eof = false
                    private fun fill(): Boolean {
                        if (eof) return false
                        if (buf.hasRemaining() && used) return true
                        buf.clear(); LA.readData(a, buf); buf.flip(); used = true
                        if (!buf.hasRemaining()) eof = true
                        return !eof
                    }
                    override fun read(): Int = if (fill()) buf.get().toInt() and 0xff else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (!fill()) return -1
                        val n = minOf(len, buf.remaining()); buf.get(b, off, n); return n
                    }
                }
                buf.clear(); buf.flip()
                cb(ArcEntry(name, size, false), ins)
            }
            LA.readClose(a)
        } finally { runCatching { LA.readFree(a) } }
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

    /** Достать записи во временную папку — чтобы посмотреть в приложении или отправить. */
    fun extractToTemp(ctx: Context, f: File, kind: String, only: Set<String>): List<File> {
        val base = File(ctx.cacheDir, "arc-view/" + f.name).apply { mkdirs() }
        val out = ArrayList<File>()
        walk(f, kind) { e, ins ->
            if (e.dir || ins == null || e.name !in only) return@walk
            val clean = e.name.split('/').filter { it.isNotBlank() && it != ".." }
            if (clean.isEmpty()) return@walk
            val t = File(base, clean.joinToString("/")).apply { parentFile?.mkdirs() }
            t.outputStream().use { ins.copyTo(it) }
            out += t
        }
        return out
    }

    /** Собрать архив заново в zip, подменив изменённые файлы (rar/7z записывать нельзя — результат всегда zip). */
    fun rezip(f: File, kind: String, replaced: Map<String, File>, out: java.io.OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            walk(f, kind) { e, ins ->
                if (e.dir) return@walk
                z.putNextEntry(ZipEntry(e.name))
                val r = replaced[e.name]
                if (r != null) r.inputStream().use { it.copyTo(z) } else ins?.copyTo(z)
                z.closeEntry(); n++
            }
        }
        return n
    }

    val TEXT_EXT = setOf("txt", "md", "json", "xml", "csv", "html", "htm", "css", "js", "kt", "java", "py", "log", "ini", "cfg", "conf", "yaml", "yml", "srt", "sql", "sh", "properties")

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
                .onFailure { file = null; entries = emptyList(); msg = if (it.javaClass.name.contains("junrar")) "Этот RAR не открылся: возможно, он повреждён или защищён паролем." else "Не открылся: ${it.message}" }
            busy = false
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> toZip = l }
    val makeZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { out ->
        if (out != null && toZip.isNotEmpty()) scope.launch { busy = true; msg = runCatching { withContext(Dispatchers.IO) { Archives.zip(ctx, toZip, out) } }.fold({ "В архив упаковано файлов: $it" }, { "Не получилось: ${it.message}" }); toZip = emptyList(); busy = false }
    }
    LaunchedEffect(toZip) { if (toZip.isNotEmpty()) makeZip.launch("архив.zip") }
    // просмотр и правка прямо из архива
    var viewing by remember { mutableStateOf<File?>(null) }
    var editing by remember { mutableStateOf<Pair<String, File>?>(null) }
    val edited = remember { mutableStateMapOf<String, File>() }
    val saveEdited = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { out ->
        val f = file
        if (out != null && f != null) scope.launch { busy = true; msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(out)!!.use { Archives.rezip(f, Archives.kind(f.name), edited.toMap(), it) } } }.fold({ "Сохранено: $it файлов, изменённых ${edited.size}" }, { "Не получилось: ${it.message}" }); busy = false }
    }
    fun uriOf(t: File) = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", t)
    fun share(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriOf(it) })
        val i = if (uris.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
            else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        ctx.startActivity(android.content.Intent.createChooser(i.setType("*/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить"))
    }
    viewing?.let { t ->
        androidx.activity.compose.BackHandler { viewing = null }
        FileViewer(uriOf(t), { viewing = null })
        return
    }
    editing?.let { (name, t) ->
        var text by remember(t) { mutableStateOf(runCatching { t.readText() }.getOrDefault("")) }
        androidx.activity.compose.BackHandler { editing = null }
        ScreenScaffold(name.substringAfterLast('/'), { editing = null }, actions = {
            RoundIconButton(Icons.Rounded.Check, "Сохранить", { t.writeText(text); edited[name] = t; editing = null; msg = "Изменения сохранены. Чтобы получить архив с ними — «Сохранить архив»." }, bg = Pal.Ink, tint = androidx.compose.ui.graphics.Color.White)
        }) {
            androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Pal.Ink))
        }
        return
    }
    ScreenScaffold("Архивы", onBack) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Открыть архив", { open.launch(arrayOf("application/zip", "application/x-rar-compressed", "application/vnd.rar", "application/x-7z-compressed", "application/x-tar", "application/gzip", "*/*")) }, Modifier.weight(1f))
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
                    if (selected.isNotEmpty() || edited.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected.isNotEmpty()) PillButton("Отправить (${selected.size})", {
                            scope.launch { busy = true; runCatching { withContext(Dispatchers.IO) { Archives.extractToTemp(ctx, f, Archives.kind(f.name), selected.toSet()) } }.onSuccess { l -> share(l.map { t -> edited.entries.firstOrNull { it.value.name == t.name }?.value ?: t }) }.onFailure { msg = "Ошибка: ${it.message}" }; busy = false }
                        }, Modifier.weight(1f), bg = Pal.Lilac, fg = Pal.LilacInk)
                        if (edited.isNotEmpty()) PillButton("Сохранить архив", { saveEdited.launch(f.name.substringBeforeLast('.') + " (изменён).zip") }, Modifier.weight(1f), bg = Pal.Mint, fg = Pal.MintInk)
                    }
                    Text("Нажми на файл — откроется прямо здесь. Текстовые файлы можно изменить.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (file == null && msg == null) item { EmptyHint("Открой .zip, .rar (в том числе RAR5), .7z, .tar или .gz — покажу, что внутри, и распакую в «Загрузки».") }
            items(entries.filter { !it.dir }) { e ->
                val sel = e.name in selected
                val ext = e.name.substringAfterLast('.', "").lowercase()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) Pal.Lilac else Pal.Card).clickable {
                    val f = file ?: return@clickable
                    edited[e.name]?.let { viewing = it; return@clickable }
                    scope.launch { busy = true; runCatching { withContext(Dispatchers.IO) { Archives.extractToTemp(ctx, f, Archives.kind(f.name), setOf(e.name)).firstOrNull() } }.onSuccess { it?.let { t -> viewing = t } }.onFailure { msg = "Не открылся: ${it.message}" }; busy = false }
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sel, { if (it) selected.add(e.name) else selected.remove(e.name) })
                    Icon(Icons.Rounded.InsertDriveFile, null, tint = Pal.Muted, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(e.name.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (e.name.contains('/')) Text(e.name.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Text(human(e.size) + if (e.name in edited) " · изменён" else "", style = MaterialTheme.typography.labelSmall)
                    if (ext in Archives.TEXT_EXT) IconButton({
                        val f = file ?: return@IconButton
                        edited[e.name]?.let { editing = e.name to it; return@IconButton }
                        scope.launch { runCatching { withContext(Dispatchers.IO) { Archives.extractToTemp(ctx, f, Archives.kind(f.name), setOf(e.name)).firstOrNull() } }.onSuccess { it?.let { t -> editing = e.name to t } } }
                    }) { Icon(Icons.Rounded.Edit, "Изменить", tint = Pal.Muted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}
