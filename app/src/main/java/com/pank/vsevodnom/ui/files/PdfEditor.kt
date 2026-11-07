package com.pank.vsevodnom.ui.files

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.PdfConvert
import com.pank.vsevodnom.data.PdfWork
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF-инструменты: страницы (удалить, повернуть, переставить, дублировать, добавить пустую/картинки/другой PDF),
 * рисование и надписи поверх страницы, текст из PDF (редактор → TXT/DOCX/XLSX/PDF), конвертации Word/Excel/картинки → PDF.
 */
@Composable
fun PdfScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val work = remember { PdfWork(ctx, "документ.pdf") }
    var ver by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val sel = remember { mutableStateListOf<Int>() }
    var draw by remember { mutableStateOf<Int?>(null) }
    var textEdit by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }   // pdf | docx | xlsx

    fun run(label: String, block: suspend () -> Unit) = scope.launch {
        busy = true; msg = null
        runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { msg = "$label: ${it.message ?: it.javaClass.simpleName}" }
        sel.clear(); ver++; busy = false
    }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) run("Не открылся") { work.load(u); work.name = displayName(ctx, u); loaded = true }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { l ->
        if (l.isNotEmpty()) run("Картинки") {
            if (loaded) work.addImages(l) else { val f = File(ctx.cacheDir, "pdfwork/img.pdf"); PdfConvert.imagesToPdf(ctx, l, f); work.loadFile(f); work.name = "картинки.pdf"; loaded = true }
        }
    }
    val otherPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) run("Не добавился") { work.appendPdf(u) } }
    val docToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) run("Не сконвертировался") {
            val name = displayName(ctx, u)
            val text = when {
                name.endsWith(".docx", true) -> docxText(ctx, u)
                name.endsWith(".xlsx", true) -> xlsxText(ctx, u)
                else -> ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val f = File(ctx.cacheDir, "pdfwork/doc.pdf")
            f.outputStream().use { PdfConvert.textToPdf(text, it, mono = name.endsWith(".xlsx", true), title = name.substringBeforeLast('.')) }
            work.loadFile(f); work.name = name.substringBeforeLast('.') + ".pdf"; loaded = true
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { u ->
        val kind = pendingSave; pendingSave = null
        if (u != null && kind != null) run("Не сохранилось") {
            ctx.contentResolver.openOutputStream(u)!!.use { os ->
                when (kind) {
                    "pdf" -> work.saveTo(os)
                    "docx" -> PdfConvert.textToDocx(textEdit ?: work.text(), os)
                    "xlsx" -> PdfConvert.textToXlsx(textEdit ?: work.text(), os)
                    "txt" -> os.write((textEdit ?: work.text()).toByteArray())
                    "textpdf" -> PdfConvert.textToPdf(textEdit ?: "", os)
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show() }
        }
    }
    fun save(kind: String, ext: String) { pendingSave = kind; saver.launch(work.name.substringBeforeLast('.') + ext) }

    draw?.let { p -> BackHandler { draw = null }; DrawPage(work, p, onCancel = { draw = null }) { bmp -> draw = null; run("Не наложилось") { work.stamp(p, bmp) } }; return }
    textEdit?.let { t -> BackHandler { textEdit = null }; TextEditor(t, { textEdit = it }, onSave = { k -> when (k) { "docx" -> save("docx", ".docx"); "xlsx" -> save("xlsx", ".xlsx"); "txt" -> save("txt", ".txt"); else -> save("textpdf", ".pdf") } }) { textEdit = null }; return }

    ScreenScaffold(if (loaded) work.name else "PDF", onBack, actions = {
        if (loaded) Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Сохранить PDF…") }, { menu = false; save("pdf", ".pdf") }, leadingIcon = { Icon(Icons.Rounded.Save, null) })
                DropdownMenuItem({ Text("Отправить") }, {
                    menu = false
                    val f = File(ctx.cacheDir, "share/${work.name}").apply { parentFile?.mkdirs() }; work.file.copyTo(f, true)
                    val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), work.name))
                }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                DropdownMenuItem({ Text("Текст из PDF (редактор)") }, { menu = false; scope.launch { busy = true; textEdit = runCatching { withContext(Dispatchers.IO) { work.text() } }.getOrElse { "Не удалось извлечь текст: ${it.message}" }.ifBlank { "В этом PDF нет текстового слоя (скорее всего это скан)." }; busy = false } }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                DropdownMenuItem({ Text("В Word (.docx)") }, { menu = false; save("docx", ".docx") }, leadingIcon = { Icon(Icons.Rounded.Description, null) })
                DropdownMenuItem({ Text("В Excel (.xlsx)") }, { menu = false; save("xlsx", ".xlsx") }, leadingIcon = { Icon(Icons.Rounded.TableChart, null) })
                DropdownMenuItem({ Text("Закрыть файл") }, { menu = false; loaded = false; sel.clear() }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
            }
        }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk, modifier = Modifier.padding(16.dp, 4.dp)) }
        if (!loaded) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StartTile(Icons.Rounded.PictureAsPdf, "Открыть PDF", "Страницы, рисование, текст, в Word/Excel") { openPdf.launch(arrayOf("application/pdf")) }
                StartTile(Icons.Rounded.Image, "Фото → PDF", "Несколько картинок в один документ") { images.launch("image/*") }
                StartTile(Icons.Rounded.Description, "Word / Excel / TXT → PDF", "Текст документа переносится в PDF") { docToPdf.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "*/*")) }
                StartTile(Icons.Rounded.EditNote, "Новый текст → PDF / Word", "Напиши текст и сохрани в нужном формате") { textEdit = "" }
                Text("Что важно: удаление, поворот, перестановка страниц и объединение сохраняют текст PDF. Рисунок и надписи накладываются поверх страницы. Из сканов (картинок) текст извлечь нельзя — для этого нужно распознавание, его пока нет.", style = MaterialTheme.typography.bodySmall)
            }
            return@ScreenScaffold
        }
        // панель действий со страницами
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val one = sel.singleOrNull()
            Tool(Icons.Rounded.Draw, "Рисовать", one != null) { draw = one }
            Tool(Icons.Rounded.RotateRight, "Повернуть", sel.isNotEmpty()) { run("Поворот") { work.rotate(sel.toSet()) } }
            Tool(Icons.Rounded.DeleteOutline, "Удалить", sel.isNotEmpty() && sel.size < work.pages) { run("Удаление") { work.delete(sel.toSet()) } }
            Tool(Icons.Rounded.ContentCopy, "Дубль", sel.isNotEmpty()) { run("Дублирование") { work.duplicate(sel.toSet()) } }
            Tool(Icons.Rounded.ArrowUpward, "Выше", one != null && one > 0) { run("Перенос") { work.move(one!!, -1) } }
            Tool(Icons.Rounded.ArrowDownward, "Ниже", one != null && one < work.pages - 1) { run("Перенос") { work.move(one!!, 1) } }
            Tool(Icons.Rounded.NoteAdd, "Пустая", true) { run("Страница") { work.addBlank(sel.maxOrNull() ?: (work.pages - 1)) } }
            Tool(Icons.Rounded.AddPhotoAlternate, "Фото", true) { images.launch("image/*") }
            Tool(Icons.Rounded.PostAdd, "Ещё PDF", true) { otherPdf.launch(arrayOf("application/pdf")) }
        }
        Text("${work.pages} стр. · нажми на страницу, чтобы выбрать" + if (sel.isNotEmpty()) " · выбрано ${sel.size}" else "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 6.dp))
        key(ver) {
            LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed((0 until work.pages).toList()) { _, i ->
                    var bmp by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) { bmp = withContext(Dispatchers.IO) { work.render(i, 300) } }
                    val s = i in sel
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).border(if (s) 3.dp else 1.dp, if (s) Pal.LilacInk else Pal.Line, RoundedCornerShape(10.dp))
                        .clickable { if (s) sel.remove(i) else sel.add(i) }.background(Color.White), horizontalAlignment = Alignment.CenterHorizontally) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) } ?: Box(Modifier.fillMaxWidth().aspectRatio(0.7f))
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StartTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Rose), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Pal.RoseInk) }
        Column(Modifier.padding(start = 14.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if (enabled) Pal.Card else Pal.Card.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onClick).padding(10.dp, 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (enabled) Pal.Ink else Pal.Muted)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) Pal.Ink else Pal.Muted)
    }
}

/** Рисование и надписи поверх страницы. Сохраняется прозрачным слоем — текст страницы не теряется. */
@Composable
private fun DrawPage(work: PdfWork, page: Int, onCancel: () -> Unit, onApply: (Bitmap) -> Unit) {
    data class St(val pts: List<Offset>, val color: Color, val w: Float)
    data class Label(val p: Offset, val text: String, val color: Color, val size: Float)
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page) { bmp = withContext(Dispatchers.IO) { work.render(page, 1400) } }
    val strokes = remember { mutableStateListOf<St>() }
    val labels = remember { mutableStateListOf<Label>() }
    var cur by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color(0xFFE5485D)) }
    var width by remember { mutableFloatStateOf(6f) }
    var marker by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Offset?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val colors = listOf(Color(0xFFE5485D), Color(0xFF2B2A33), Color(0xFF4A86E8), Color(0xFF3FB27F), Color(0xFFF2C94C))
    fun apply() {
        val b = bmp ?: return
        if (viewSize.width == 0) return
        val k = b.width.toFloat() / viewSize.width
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        strokes.forEach { s ->
            val p = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeWidth = s.w * k; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; this.color = s.color.toArgb() }
            val path = android.graphics.Path(); s.pts.firstOrNull()?.let { path.moveTo(it.x * k, it.y * k) }; s.pts.drop(1).forEach { path.lineTo(it.x * k, it.y * k) }
            c.drawPath(path, p)
        }
        labels.forEach { l -> c.drawText(l.text, l.p.x * k, l.p.y * k, android.graphics.Paint().apply { isAntiAlias = true; textSize = l.size * k; this.color = l.color.toArgb() }) }
        onApply(out)
    }
    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel) { Icon(Icons.Rounded.Close, "Отмена") }
            Text("Страница ${page + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton({ if (labels.isNotEmpty() && (strokes.isEmpty() || textMode)) labels.removeAt(labels.lastIndex) else if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Отменить") }
            IconButton({ apply() }) { Icon(Icons.Rounded.Check, "Применить", tint = Pal.LilacInk) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp).verticalScroll(rememberScrollState(), enabled = false)) {
            bmp?.let { b ->
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(textMode) {
                            if (textMode) detectTapGestures { ask = it }
                            else detectDragGestures(onDragStart = { cur = listOf(it) }, onDragEnd = { strokes.add(St(cur, if (marker) color.copy(alpha = 0.35f) else color, if (marker) width * 3 else width)); cur = emptyList() }) { ch, _ -> cur = cur + ch.position }
                        }) {
                        viewSize = IntSize(size.width.toInt(), size.height.toInt())
                        fun path(p: List<Offset>) = Path().apply { p.firstOrNull()?.let { moveTo(it.x, it.y) }; p.drop(1).forEach { lineTo(it.x, it.y) } }
                        strokes.forEach { drawPath(path(it.pts), it.color, style = Stroke(it.w, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                        if (cur.isNotEmpty()) drawPath(path(cur), if (marker) color.copy(alpha = 0.35f) else color, style = Stroke(if (marker) width * 3 else width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        labels.forEach { l -> drawContext.canvas.nativeCanvas.drawText(l.text, l.p.x, l.p.y, android.graphics.Paint().apply { isAntiAlias = true; textSize = l.size; this.color = l.color.toArgb() }) }
                    }
                }
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Ручка", !marker && !textMode, { marker = false; textMode = false })
            Chip("Маркер", marker && !textMode, { marker = true; textMode = false })
            Chip("Текст", textMode, { textMode = true })
            Spacer(Modifier.weight(1f))
            colors.forEach { c -> Box(Modifier.size(if (c == color) 30.dp else 24.dp).clip(CircleShape).background(c).clickable { color = c }) }
        }
        Text(if (textMode) "Нажми на место, где нужна надпись" else "Рисуй пальцем", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    }
    ask?.let { p ->
        var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("Надпись") }, text = { AppTextField(t, { t = it }, "Текст") },
            confirmButton = { TextButton({ if (t.isNotBlank()) labels.add(Label(p, t, color, 34f)); ask = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ ask = null }) { Text("Отмена") } })
    }
}

/** Редактор текста: правка извлечённого текста или новый документ → TXT / Word / Excel / PDF. */
@Composable
private fun TextEditor(text: String, onChange: (String) -> Unit, onSave: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Закрыть") }
            Text("Текст", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${text.length} симв.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
        }
        BasicTextField(text, onChange, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp).verticalScroll(rememberScrollState()),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Pal.Ink, fontFamily = FontFamily.Default, fontSize = 15.sp), cursorBrush = SolidColor(Pal.LilacInk))
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("PDF", { onSave("pdf") }, Modifier.weight(1f))
            PillButton("Word", { onSave("docx") }, Modifier.weight(1f), bg = Pal.Sky, fg = Pal.SkyInk)
            PillButton("Excel", { onSave("xlsx") }, Modifier.weight(1f), bg = Pal.Mint, fg = Pal.MintInk)
            PillButton("TXT", { onSave("txt") }, Modifier.weight(0.8f), bg = Pal.Card, fg = Pal.Ink)
        }
    }
}
