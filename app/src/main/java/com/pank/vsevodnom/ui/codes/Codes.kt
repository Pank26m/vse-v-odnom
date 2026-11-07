package com.pank.vsevodnom.ui.codes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.pank.vsevodnom.data.Net
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** История сканов (последние 60) — в настройках, без базы. */
object ScanHistory {
    data class Item(val value: String, val format: String, val at: Long)
    private fun p(ctx: Context) = ctx.getSharedPreferences("codes", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<Item> = runCatching { val a = JSONArray(p(ctx).getString("h", "[]")); (0 until a.length()).map { a.getJSONObject(it).let { o -> Item(o.getString("v"), o.optString("f"), o.getLong("t")) } } }.getOrDefault(emptyList())
    fun add(ctx: Context, i: Item) { val l = (listOf(i) + all(ctx).filter { it.value != i.value }).take(60); p(ctx).edit().putString("h", JSONArray(l.map { JSONObject().put("v", it.value).put("f", it.format).put("t", it.at) }).toString()).apply() }
    fun clear(ctx: Context) = p(ctx).edit().remove("h").apply()
}

private fun fmtName(f: Int) = when (f) {
    Barcode.FORMAT_QR_CODE -> "QR"; Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"; Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_CODE_128 -> "Code 128"; Barcode.FORMAT_CODE_39 -> "Code 39"; Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"; Barcode.FORMAT_PDF417 -> "PDF417"; else -> "Код"
}

/** Генерация картинки кода через ZXing. */
fun makeCode(text: String, format: BarcodeFormat, w: Int = 800, h: Int = if (format == BarcodeFormat.QR_CODE) 800 else 320): Bitmap {
    val m = MultiFormatWriter().encode(text, format, w, h, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

/** PDF A4 для печати: один большой код или лист наклеек (копии сеткой), с подписью. */
fun codePdf(ctx: Context, out: Uri, bmp: Bitmap, caption: String, copies: Int) {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    val paint = Paint().apply { textSize = 12f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    val cols = when { copies <= 1 -> 1; copies <= 6 -> 2; else -> 3 }
    val rows = ((copies + cols - 1) / cols).coerceAtLeast(1)
    val cw = 595f / cols; val ch = 842f / rows
    for (i in 0 until copies.coerceAtLeast(1)) {
        val cx = (i % cols) * cw; val cy = (i / cols) * ch
        val s = minOf(cw * 0.8f / bmp.width, (ch - 40f) * 0.8f / bmp.height)
        val dw = bmp.width * s; val dh = bmp.height * s
        c.drawBitmap(bmp, null, android.graphics.RectF(cx + (cw - dw) / 2, cy + (ch - 30f - dh) / 2, cx + (cw + dw) / 2, cy + (ch - 30f + dh) / 2), null)
        if (caption.isNotBlank()) c.drawText(caption.take(60), cx + cw / 2, cy + (ch - 30f + dh) / 2 + 18f, paint)
    }
    doc.finishPage(page)
    ctx.contentResolver.openOutputStream(out)!!.use { doc.writeTo(it) }
    doc.close()
}

private fun savePng(ctx: Context, bmp: Bitmap, name: String): Boolean = runCatching {
    val v = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Всё в одном") }
    val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
    ctx.contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}.isSuccess

private fun shareBmp(ctx: Context, bmp: Bitmap) {
    val f = File(ctx.cacheDir, "share/code.png").apply { parentFile?.mkdirs() }
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Код"))
}

/** Товар по штрихкоду — открытая база Open Food Facts (еда, косметика). */
private suspend fun lookupProduct(code: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,brands,quantity").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VseVOdnom/0.12 (personal app)"); c.connectTimeout = 8000; c.readTimeout = 10000
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        if (o.optInt("status") != 1) null else o.getJSONObject("product").let { p -> listOf(p.optString("brands"), p.optString("product_name"), p.optString("quantity")).filter { it.isNotBlank() }.joinToString(" · ") }
    }.getOrNull()
}

@Composable
fun CodesScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ScreenScaffold("Коды", onBack) {
        Segmented(listOf("Сканер", "Создать"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        if (tab == 0) Scanner() else Generator()
    }
}

@Composable
private fun Scanner() {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(ScanHistory.all(ctx)) }
    var last by remember { mutableStateOf<ScanHistory.Item?>(null) }
    var product by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(last) {
        product = null
        val v = last?.value ?: return@LaunchedEffect
        if (v.all { it.isDigit() } && v.length in 8..14) product = lookupProduct(v) ?: "В открытой базе товара нет — поищи в интернете"
    }
    fun scan() {
        err = null
        GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { b -> val i = ScanHistory.Item(b.rawValue ?: "", fmtName(b.format), System.currentTimeMillis()); ScanHistory.add(ctx, i); history = ScanHistory.all(ctx); last = i }
            .addOnFailureListener { err = "Сканер не запустился: ${it.message}. Нужны сервисы Google Play (на Pixel есть) — при первом запуске модуль докачивается." }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(22.dp)).background(Pal.Ink).clickable { scan() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.QrCodeScanner, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Text("Сканировать штрихкод или QR", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
        err?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) } }
        last?.let { l -> item { ResultCard(l, product) } }
        if (history.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) { SectionLabel("История"); Spacer(Modifier.weight(1f)); TextButton({ ScanHistory.clear(ctx); history = emptyList() }) { Text("Очистить") } }
        }
        items(history, key = { it.at }) { h ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { last = h }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (h.format == "QR") Icons.Rounded.QrCode2 else Icons.Rounded.ViewWeek, null, tint = Pal.Muted)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(h.value, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(h.format + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(h.at)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(i: ScanHistory.Item, product: String?) {
    val ctx = LocalContext.current
    val v = i.value
    fun open(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac) {
        Text(i.format, style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk)
        Text(v, style = MaterialTheme.typography.titleMedium)
        product?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        if (v.startsWith("WIFI:")) {
            val ssid = Regex("S:([^;]*)").find(v)?.groupValues?.get(1); val pass = Regex("P:([^;]*)").find(v)?.groupValues?.get(1)
            Text("Wi-Fi «$ssid», пароль: ${pass ?: "нет"}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (v.startsWith("http")) Chip("Открыть", false, { open(v) })
            Chip("Копировать", false, {
                val text = Regex("P:([^;]*)").find(v)?.groupValues?.get(1)?.takeIf { v.startsWith("WIFI:") } ?: v
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Код", text)); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            })
            Chip("Поиск", false, { open("https://www.google.com/search?q=" + Uri.encode(v)) })
            Chip("Отправить", false, { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v), "Код")) })
        }
        if (!Net.online && product == null && v.all { it.isDigit() }) Text("Нет интернета — описание товара подтянется позже", style = MaterialTheme.typography.labelSmall)
    }
}

private enum class Kind(val title: String) { TEXT("Текст"), URL("Ссылка"), WIFI("Wi-Fi"), CONTACT("Контакт"), PHONE("Телефон"), EMAIL("Почта"), GEO("Место"), BAR("Штрихкод") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Generator() {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(Kind.TEXT) }
    val f = remember { mutableStateMapOf<String, String>() }
    var copies by remember { mutableIntStateOf(1) }
    var barFormat by remember { mutableStateOf(BarcodeFormat.CODE_128) }
    fun g(k: String) = f[k].orEmpty()
    val content = when (kind) {
        Kind.TEXT -> g("text"); Kind.URL -> g("url").let { if (it.isBlank() || it.startsWith("http")) it else "https://$it" }
        Kind.WIFI -> if (g("ssid").isBlank()) "" else "WIFI:T:${if (g("pass").isBlank()) "nopass" else "WPA"};S:${g("ssid")};P:${g("pass")};;"
        Kind.CONTACT -> if (g("name").isBlank()) "" else "BEGIN:VCARD\nVERSION:3.0\nFN:${g("name")}\nTEL:${g("tel")}\nEMAIL:${g("mail")}\nORG:${g("org")}\nEND:VCARD"
        Kind.PHONE -> g("tel").takeIf { it.isNotBlank() }?.let { "tel:$it" } ?: ""
        Kind.EMAIL -> g("mail").takeIf { it.isNotBlank() }?.let { "mailto:$it?subject=${Uri.encode(g("subj"))}" } ?: ""
        Kind.GEO -> if (g("lat").isBlank()) "" else "geo:${g("lat")},${g("lon")}"
        Kind.BAR -> g("bar")
    }
    val format = if (kind == Kind.BAR) barFormat else BarcodeFormat.QR_CODE
    val bmp = remember(content, format) { if (content.isBlank()) null else runCatching { makeCode(content, format) }.getOrNull() }
    val caption = when (kind) { Kind.WIFI -> "Wi-Fi: ${g("ssid")}"; Kind.CONTACT -> g("name"); Kind.BAR -> g("bar"); else -> g("caption") }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null && bmp != null) runCatching { codePdf(ctx, u, bmp, caption, copies) }.onSuccess { Toast.makeText(ctx, "PDF сохранён", Toast.LENGTH_SHORT).show() }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Kind.entries.forEach { k -> Chip(k.title, kind == k, { kind = k }) } } }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                @Composable fun F(k: String, hint: String) { AppTextField(f[k].orEmpty(), { f[k] = it }, hint); Spacer(Modifier.height(6.dp)) }
                when (kind) {
                    Kind.TEXT -> { F("text", "Любой текст"); F("caption", "Подпись под кодом (для печати)") }
                    Kind.URL -> { F("url", "Ссылка"); F("caption", "Подпись") }
                    Kind.WIFI -> { F("ssid", "Название сети"); F("pass", "Пароль") }
                    Kind.CONTACT -> { F("name", "Имя"); F("tel", "Телефон"); F("mail", "Почта"); F("org", "Организация") }
                    Kind.PHONE -> { F("tel", "Номер"); F("caption", "Подпись") }
                    Kind.EMAIL -> { F("mail", "Адрес"); F("subj", "Тема письма"); F("caption", "Подпись") }
                    Kind.GEO -> { F("lat", "Широта, например 50.4501"); F("lon", "Долгота, например 30.5234"); F("caption", "Подпись") }
                    Kind.BAR -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(BarcodeFormat.CODE_128 to "Code 128", BarcodeFormat.EAN_13 to "EAN-13", BarcodeFormat.CODE_39 to "Code 39").forEach { (fm, s) -> Chip(s, barFormat == fm, { barFormat = fm }) }
                        }
                        Spacer(Modifier.height(6.dp))
                        F("bar", if (barFormat == BarcodeFormat.EAN_13) "12–13 цифр" else "Текст или номер")
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(if (format == BarcodeFormat.QR_CODE) 0.75f else 1f))
                else Text(if (content.isBlank()) "Заполни поля — код появится здесь" else "Такой код не построить (для EAN-13 нужны цифры)", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
            }
        }
        if (bmp != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Копий на листе A4: ", style = MaterialTheme.typography.bodyMedium)
                    listOf(1, 4, 6, 12).forEach { n -> Chip("$n", copies == n, { copies = n }, Modifier.padding(start = 6.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("PDF для печати", { pdf.launch("код.pdf") }, Modifier.weight(1f))
                    PillButton("PNG", { if (savePng(ctx, bmp, "code_${System.currentTimeMillis()}")) Toast.makeText(ctx, "Сохранено в Pictures/Всё в одном", Toast.LENGTH_SHORT).show() }, Modifier.weight(0.6f), bg = Pal.Card, fg = Pal.Ink)
                    PillButton("Отправить", { shareBmp(ctx, bmp) }, Modifier.weight(0.8f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
        }
    }
}
