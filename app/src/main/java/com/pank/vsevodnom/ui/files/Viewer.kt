package com.pank.vsevodnom.ui.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.schedule.XlsReader
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.util.zip.ZipInputStream

enum class Kind { PDF, IMAGE, TEXT, DOCX, EPUB, XLSX, XLS, OTHER }

fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Файл"

fun kindOf(name: String, mime: String?): Kind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || ext == "pdf" -> Kind.PDF
        mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> Kind.IMAGE
        ext == "docx" -> Kind.DOCX
        ext == "epub" -> Kind.EPUB
        ext == "xlsx" -> Kind.XLSX
        ext == "xls" || mime == "application/vnd.ms-excel" -> Kind.XLS
        mime?.startsWith("text/") == true || ext in setOf("txt", "md", "csv", "json", "xml", "log", "ini", "yaml", "yml", "html", "htm", "kt", "java", "py", "js", "srt", "conf") -> Kind.TEXT
        else -> Kind.OTHER
    }
}

@Composable
fun FileViewer(uri: Uri, onBack: () -> Unit, onImportSchedule: ((Uri) -> Unit)? = null) {
    val ctx = LocalContext.current
    val name = remember(uri) { displayName(ctx, uri) }
    val mime = remember(uri) { ctx.contentResolver.getType(uri) }
    val kind = remember(name, mime) { kindOf(name, mime) }
    ScreenScaffold(name, onBack, actions = {
        RoundIconButton(Icons.Rounded.Share, "Поделиться", {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
        })
        RoundIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть в другом приложении", {
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть в…")) }
        })
    }) {
        when (kind) {
            Kind.PDF -> PdfView(uri)
            Kind.IMAGE -> ImageView(uri)
            Kind.TEXT -> TextLoader { readText(ctx, uri) }
            Kind.DOCX -> TextLoader { docxText(ctx, uri) }
            Kind.EPUB -> TextLoader { epubText(ctx, uri) }
            Kind.XLSX -> TextLoader(mono = true) { xlsxText(ctx, uri) }
            Kind.XLS -> Column {
                if (onImportSchedule != null) PillButton("Загрузить как расписание пар", { onImportSchedule(uri) }, Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp))
                TextLoader(mono = true) { xlsText(ctx, uri) }
            }
            Kind.OTHER -> EmptyHint("Этот формат я пока не открываю. Нажми ↗ — откроется в подходящем приложении.")
        }
    }
}

// ---------------- PDF ----------------

@Composable
private fun PdfView(uri: Uri) {
    val ctx = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val mutex = remember { Mutex() }
    val cache = remember { object : LruCache<Int, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: Int, value: Bitmap) = value.byteCount } }
    DisposableEffect(uri) {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            renderer = pfd?.let { PdfRenderer(it) }
        } catch (e: SecurityException) { err = "PDF защищён паролем — такой я пока не открываю" } catch (e: Exception) { err = "Не удалось открыть PDF: ${e.message}" }
        onDispose { runCatching { renderer?.close() }; runCatching { pfd?.close() }; cache.evictAll() }
    }
    err?.let { EmptyHint(it); return }
    val r = renderer ?: return
    val count = r.pageCount
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Pal.Line)) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // двумя пальцами — зум и сдвиг; одним — обычная прокрутка
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                scale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                                val maxX = (scale - 1) * size.width / 2
                                offX = (offX + ev.calculatePan().x).coerceIn(-maxX, maxX)
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (scale <= 1.01f) { scale = 1f; offX = 0f }
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offX },
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(count) { i ->
                var bmp by remember(i) { mutableStateOf(cache.get(i)) }
                val ratio = remember(i) { runCatching { r.openPage(i).use { it.height.toFloat() / it.width } }.getOrDefault(1.414f) }
                LaunchedEffect(i) {
                    if (bmp == null) bmp = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                r.openPage(i).use { p ->
                                    val w = (widthPx * 1.6f).toInt().coerceAtMost(2400)
                                    val b = Bitmap.createBitmap(w, (w * p.height.toFloat() / p.width).toInt(), Bitmap.Config.ARGB_8888)
                                    b.eraseColor(AColor.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b
                                }
                            }.getOrNull()
                        }
                    }?.also { cache.put(i, it) }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(androidx.compose.ui.graphics.Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), "Страница ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
        }
        val first = list.firstVisibleItemIndex
        Text("${first + 1} / $count", Modifier.align(Alignment.BottomCenter).padding(12.dp)
            .background(Pal.Ink.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------- Картинки ----------------

@Composable
private fun ImageView(uri: Uri) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var err by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var sample = 1
                while (o.outWidth / sample > 4096 || o.outHeight / sample > 4096) sample *= 2
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.getOrNull()
        }
        err = bmp == null
    }
    if (err) { EmptyHint("Не удалось открыть картинку"); return }
    val b = bmp ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1E1D24))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val ev = awaitPointerEvent()
                        scale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                        off = if (scale > 1f) off + ev.calculatePan() else androidx.compose.ui.geometry.Offset.Zero
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y }, contentScale = ContentScale.Fit)
    }
}

// ---------------- Текстовые форматы ----------------

@Composable
private fun TextLoader(mono: Boolean = false, load: suspend () -> String) {
    var text by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableFloatStateOf(15f) }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { runCatching { load() }.getOrElse { "Не удалось прочитать файл: ${it.message}" } } }
    val t = text ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("A−", false, { size = (size - 1).coerceAtLeast(10f) }); Chip("A+", false, { size = (size + 1).coerceAtMost(28f) })
        }
        SelectionContainer(Modifier.weight(1f)) {
            val base = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            Text(
                t, style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.45f).sp, fontFamily = if (mono) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily),
                modifier = if (mono) base.horizontalScroll(rememberScrollState()) else base
            )
        }
    }
}

private const val MAX_TEXT = 3_000_000

private fun java.io.InputStream.readLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}

private fun readText(ctx: Context, uri: Uri): String {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { s -> s.readLimited(MAX_TEXT) }
    val txt = String(bytes, Charsets.UTF_8)
    // старые файлы в Windows-1251 дают «кракозябры» — пробуем
    return if (txt.count { it == '�' } > 20) String(bytes, charset("windows-1251")) else txt
}

private fun zipEntries(ctx: Context, uri: Uri, want: (String) -> Boolean): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ctx.contentResolver.openInputStream(uri)!!.use { s ->
        ZipInputStream(s).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && want(e.name)) out[e.name] = z.readLimited(20_000_000)
            }
        }
    }
    return out
}

/** DOCX: текст абзацев из word/document.xml. Форматирование и картинки не показываем. */
private fun docxText(ctx: Context, uri: Uri): String {
    val xml = zipEntries(ctx, uri) { it == "word/document.xml" }["word/document.xml"] ?: return "Пустой документ"
    val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    p.setInput(xml.inputStream(), "UTF-8")
    val sb = StringBuilder()
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.name) { "w:tab" -> sb.append('\t'); "w:br" -> sb.append('\n') }
            XmlPullParser.TEXT -> sb.append(p.text)
            XmlPullParser.END_TAG -> if (p.name == "w:p") sb.append("\n\n")
        }
    }
    return sb.toString().trim()
}

private fun stripHtml(h: String): String = h
    .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("(?i)</(p|div|h[1-6]|li)>"), "\n\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\n{3,}"), "\n\n").trim()

/** EPUB: главы по порядку из оглавления (spine), только текст. */
private fun epubText(ctx: Context, uri: Uri): String {
    val all = zipEntries(ctx, uri) { it.endsWith(".opf") || it.endsWith(".xml") || it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
    val container = all["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: ""
    val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: all.keys.firstOrNull { it.endsWith(".opf") }
    val opf = opfPath?.let { all[it]?.toString(Charsets.UTF_8) }
    val base = opfPath?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
    val order: List<String> = if (opf != null) {
        val manifest = Regex("<item\\s[^>]*>").findAll(opf).associate { m ->
            val tag = m.value
            (Regex("id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: "") to (Regex("href=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: "")
        }
        Regex("<itemref\\s[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { manifest[it.groupValues[1]]?.let { h -> base + java.net.URLDecoder.decode(h, "UTF-8") } }.toList()
    } else all.keys.filter { it.endsWith("html") }.sorted()
    val title = opf?.let { Regex("<dc:title[^>]*>([^<]+)").find(it)?.groupValues?.get(1) }
    return (listOfNotNull(title) + order.mapNotNull { all[it]?.toString(Charsets.UTF_8)?.let(::stripHtml) }).joinToString("\n\n— — —\n\n")
}

private fun table(rows: List<List<String>>): String {
    if (rows.isEmpty()) return "Пустая таблица"
    val cols = rows.maxOf { it.size }
    val w = (0 until cols).map { c -> rows.maxOf { (it.getOrNull(c) ?: "").replace('\n', ' ').length.coerceAtMost(40) } }
    return rows.joinToString("\n") { r -> (0 until cols).joinToString(" │ ") { c -> (r.getOrNull(c) ?: "").replace('\n', ' ').take(40).padEnd(w[c]) } }
}

private fun xlsText(ctx: Context, uri: Uri): String {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    return XlsReader.read(bytes).joinToString("\n\n") { sh ->
        "▌ ${sh.name}\n" + table((0 until sh.rows).map { r -> (0 until sh.cols).map { c -> sh[r, c] } })
    }
}

/** XLSX: первые листы, общие строки и числа. */
private fun xlsxText(ctx: Context, uri: Uri): String {
    val e = zipEntries(ctx, uri) { it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet") || it == "xl/workbook.xml" }
    val shared = ArrayList<String>()
    e["xl/sharedStrings.xml"]?.let { b ->
        val p = Xml.newPullParser(); p.setInput(b.inputStream(), "UTF-8")
        var cur: StringBuilder? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> if (p.name == "si") cur = StringBuilder()
                XmlPullParser.TEXT -> cur?.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "si") { shared += cur.toString(); cur = null }
            }
        }
    }
    val names = e["xl/workbook.xml"]?.toString(Charsets.UTF_8)?.let { Regex("<sheet [^>]*name=\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
    return e.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.take(10).mapIndexed { idx, k ->
        val p = Xml.newPullParser(); p.setInput(e.getValue(k).inputStream(), "UTF-8")
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        var ref = ""; var type = ""; var value = StringBuilder(); var inV = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "c" -> { ref = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t") ?: ""; value = StringBuilder() }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val col = ref.takeWhile { it.isLetter() }.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
                        val row = ref.dropWhile { it.isLetter() }.toIntOrNull() ?: continue
                        val v = if (type == "s") shared.getOrNull(value.toString().toIntOrNull() ?: -1) ?: "" else value.toString()
                        rows.getOrPut(row) { sortedMapOf() }[col] = v
                    }
                }
            }
        }
        val maxC = rows.values.maxOfOrNull { it.keys.maxOrNull() ?: 0 } ?: 0
        "▌ ${names.getOrNull(idx) ?: "Лист ${idx + 1}"}\n" + table(rows.values.map { r -> (0..maxC).map { r[it] ?: "" } })
    }.joinToString("\n\n")
}
