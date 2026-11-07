package com.pank.vsevodnom.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Рабочий PDF: правки делаются через PDFBox (текст страниц сохраняется), после каждой правки
 * файл сохраняется в кэш, а миниатюры рисует системный PdfRenderer.
 */
class PdfWork(private val ctx: Context, var name: String) {
    val file = File(ctx.cacheDir, "pdfwork/current.pdf").apply { parentFile?.mkdirs() }
    var pages = 0; private set

    companion object {
        private var inited = false
        fun init(ctx: Context) { if (!inited) { PDFBoxResourceLoader.init(ctx.applicationContext); inited = true } }
    }

    fun load(uri: Uri) { init(ctx); ctx.contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }; refresh() }
    fun loadFile(f: File) { init(ctx); f.copyTo(file, true); refresh() }
    private fun refresh() { pages = PDDocument.load(file).use { it.numberOfPages } }

    private fun edit(block: (PDDocument) -> Unit) {
        init(ctx)
        val tmp = File(file.parentFile, "tmp.pdf")
        PDDocument.load(file).use { d -> block(d); d.save(tmp) }
        tmp.copyTo(file, true); tmp.delete(); refresh()
    }

    fun delete(idx: Set<Int>) = edit { d -> idx.sortedDescending().forEach { if (d.numberOfPages > 1) d.removePage(it) } }
    fun rotate(idx: Set<Int>) = edit { d -> idx.forEach { val p = d.getPage(it); p.rotation = (p.rotation + 90) % 360 } }

    /** Новый порядок страниц (индексы старых, можно с повторами — для дублирования). */
    fun reorder(order: List<Int>) {
        init(ctx)
        val tmp = File(file.parentFile, "tmp.pdf")
        PDDocument.load(file).use { src -> PDDocument().use { out -> order.forEach { out.importPage(src.getPage(it)) }; out.save(tmp) } }
        tmp.copyTo(file, true); tmp.delete(); refresh()
    }

    fun move(i: Int, dir: Int) { val o = (0 until pages).toMutableList(); val j = i + dir; if (j !in o.indices) return; o[i] = j.also { o[j] = i }; reorder(o) }
    fun duplicate(idx: Set<Int>) = reorder((0 until pages).flatMap { if (it in idx) listOf(it, it) else listOf(it) })

    fun addBlank(after: Int) = edit { d ->
        val p = PDPage(PDRectangle.A4)
        if (after >= d.numberOfPages - 1) d.addPage(p) else d.pages.insertBefore(p, d.getPage(after + 1))
    }

    /** Картинки — новыми страницами A4 (картинка вписана по центру). */
    fun addImages(uris: List<Uri>) = edit { d -> uris.forEach { u -> addImagePage(ctx, d, u) } }

    fun appendPdf(uri: Uri) {
        init(ctx)
        val other = File(file.parentFile, "other.pdf"); ctx.contentResolver.openInputStream(uri)!!.use { i -> other.outputStream().use { i.copyTo(it) } }
        val tmp = File(file.parentFile, "tmp.pdf")
        PDDocument.load(file).use { d -> PDDocument.load(other).use { o -> o.pages.forEach { d.importPage(it) }; d.save(tmp) } }
        tmp.copyTo(file, true); tmp.delete(); other.delete(); refresh()
    }

    /** Наложить прозрачный рисунок (поверх страницы целиком). Текст страницы остаётся текстом. */
    fun stamp(page: Int, overlay: Bitmap) = edit { d ->
        val p = d.getPage(page); val box = p.mediaBox
        val img = LosslessFactory.createFromImage(d, overlay)
        PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs -> cs.drawImage(img, box.lowerLeftX, box.lowerLeftY, box.width, box.height) }
    }

    fun text(from: Int = 0, to: Int = pages - 1): String { init(ctx); return PDDocument.load(file).use { d -> PDFTextStripper().apply { startPage = from + 1; endPage = to + 1 }.getText(d) } }

    fun render(page: Int, width: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(page).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }
        }
    }.getOrNull()

    fun saveTo(out: OutputStream) = file.inputStream().use { it.copyTo(out) }
}

private fun loadBitmap(ctx: Context, u: Uri, max: Int = 2400): Bitmap? = runCatching {
    val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1; while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

private fun addImagePage(ctx: Context, d: PDDocument, u: Uri) {
    val bmp = loadBitmap(ctx, u) ?: return
    val page = PDPage(if (bmp.width > bmp.height) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4)
    d.addPage(page)
    val img = JPEGFactory.createFromImage(d, bmp, 0.9f)
    val box = page.mediaBox; val m = 18f
    val s = minOf((box.width - 2 * m) / bmp.width, (box.height - 2 * m) / bmp.height)
    val w = bmp.width * s; val h = bmp.height * s
    PDPageContentStream(d, page).use { it.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
}

object PdfConvert {
    /** Картинки → новый PDF. */
    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: File) {
        PdfWork.init(ctx)
        PDDocument().use { d -> uris.forEach { addImagePage(ctx, d, it) }; d.save(out) }
    }

    /** Текст → PDF (A4, перенос строк; моноширинный шрифт — для таблиц). Кириллица — системным шрифтом. */
    fun textToPdf(text: String, out: OutputStream, mono: Boolean = false, title: String = "") {
        val doc = PdfDocument()
        val tp = TextPaint().apply { isAntiAlias = true; textSize = if (mono) 8.5f else 11f; typeface = if (mono) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.SANS_SERIF }
        val w = 595; val h = 842; val m = 40
        val full = (if (title.isNotBlank()) "$title\n\n" else "") + text
        val layout = StaticLayout.Builder.obtain(full, 0, full.length, tp, w - 2 * m).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
        var line = 0; var pageNo = 1
        while (line < layout.lineCount || pageNo == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page.canvas
            val top = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - top <= h - 2 * m) last++
            c.save(); c.translate(m.toFloat(), (m - top).toFloat()); c.clipRect(0, top, w - 2 * m, if (last > 0) layout.getLineBottom(last - 1) else top); layout.draw(c); c.restore()
            c.drawText("$pageNo", w / 2f, h - 18f, Paint().apply { textSize = 9f; textAlign = Paint.Align.CENTER; color = android.graphics.Color.GRAY })
            doc.finishPage(page)
            if (last == line) break
            line = last; pageNo++
        }
        doc.writeTo(out); doc.close()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").filter { it == '\t' || it == '\n' || it >= ' ' }

    /** Текст → DOCX (минимальный документ Word: абзацы). */
    fun textToDocx(text: String, out: OutputStream) = ZipOutputStream(out).use { z ->
        fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        val body = text.split('\n').joinToString("") { l -> "<w:p><w:r><w:t xml:space=\"preserve\">${esc(l)}</w:t></w:r></w:p>" }
        put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""")
    }

    /** Текст → XLSX: строки — строки таблицы, столбцы — по табуляции или двум и более пробелам. */
    fun textToXlsx(text: String, out: OutputStream) = ZipOutputStream(out).use { z ->
        fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Лист1" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        fun col(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { val r = (n - 1) % 26; s = ('A' + r) + s; n = (n - 1) / 26 }; return s }
        val rows = text.lines().filter { it.isNotBlank() }.mapIndexed { r, l ->
            val cells = l.split(Regex("\t| {2,}")).map { it.trim() }
            "<row r=\"${r + 1}\">" + cells.mapIndexed { c, v ->
                val num = v.replace(',', '.').replace(" ", "").toDoubleOrNull()
                if (num != null && v.isNotBlank()) "<c r=\"${col(c)}${r + 1}\"><v>$num</v></c>" else "<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        put("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
    }
}
