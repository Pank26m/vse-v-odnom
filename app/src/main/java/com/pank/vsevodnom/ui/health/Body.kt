package com.pank.vsevodnom.ui.health

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.BodyCheck
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.map.FullScreen
import com.pank.vsevodnom.ui.theme.Pal
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DMY = DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("ru"))
private fun num(v: Double) = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e6) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd(',', '.').replace('.', ',')

/** Анализ состава тела: статус, последние данные по группам с нормой, история, новая запись (с файлом бланка). */
@Composable
fun BodyCheckScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(BodyCheck.all(ctx)) }
    var sel by remember { mutableStateOf(list.lastOrNull()) }
    var adding by remember { mutableStateOf(false) }
    if (adding) { BodyCheckEditor(list.lastOrNull(), onSave = { r -> list = (list + r).sortedBy { it.date }; BodyCheck.save(ctx, list); sel = r; adding = false }) { adding = false }; return }
    ScreenScaffold("Состав тела", onBack, actions = { RoundIconButton(Icons.Rounded.Add, "Новый анализ", { adding = true }, bg = Pal.Ink, tint = Color.White) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BodyCheckStatusCard {} }
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Sky) {
                    Text(BodyCheck.TITLE, style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk)
                    Text("Точный замер на биоимпедансных весах (в клинике или фитнес-центре, «InBody»). Приложение берёт отсюда процент жира, мышцы и висцеральный жир; " +
                        "домашний вес — ежедневная, менее точная оценка. Повторять раз в 3 месяца, утром натощак, в одно и то же время.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (list.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.reversed().forEach { r -> Chip(r.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), sel?.id == r.id, { sel = r }) }
                }
            }
            sel?.let { r ->
                val prev = list.getOrNull(list.indexOf(r) - 1)
                item {
                    Text("Анализ от " + r.date.format(DMY) + if (r.note.isNotBlank()) " · ${r.note}" else "", style = MaterialTheme.typography.titleMedium)
                    r.files.forEach { f -> TextButton({ openFile(ctx, File(f)) }) { Icon(Icons.Rounded.Description, null); Text(" Бланк: ${File(f).name}") } }
                }
                r.params.groupBy { it.group }.forEach { (g, ps) ->
                    item {
                        AppCard(Modifier.fillMaxWidth()) {
                            SectionLabel(g)
                            ps.forEach { p -> ParamRow(p, prev?.params?.firstOrNull { it.key == p.key }?.value) }
                        }
                    }
                }
                item {
                    TextButton({ list = list.filter { it.id != r.id }; BodyCheck.save(ctx, list); sel = list.lastOrNull() }) { Text("Удалить этот анализ", color = Pal.RoseInk) }
                }
            }
        }
    }
}

/** Строка параметра: значение, норма, шкала (зелёный — в норме, красный — выше, синий — ниже), изменение с прошлого анализа. */
@Composable
private fun ParamRow(p: BodyCheck.P, prev: Double?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val v = p.value
            val col = when { v == null -> Pal.Ink; p.hi != null && v > p.hi -> Pal.RoseInk; p.lo != null && v < p.lo -> Pal.SkyInk; p.lo != null || p.hi != null -> Pal.MintInk; else -> Pal.Ink }
            Text(v?.let { num(it) + if (p.unit.isNotBlank()) " ${p.unit}" else "" } ?: p.text.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall, color = col, fontWeight = FontWeight.Bold)
            if (v != null && prev != null && kotlin.math.abs(v - prev) > 0.009) Text("  " + (if (v > prev) "▲" else "▼") + num(kotlin.math.abs(v - prev)), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
        if (p.lo != null && p.hi != null && p.value != null) {
            val v = p.value
            Canvas(Modifier.fillMaxWidth().padding(top = 3.dp).height(8.dp)) {
                val span = p.hi - p.lo
                val min = minOf(p.lo - span * 0.6, v); val max = maxOf(p.hi + span * 0.6, v)
                fun x(d: Double) = ((d - min) / (max - min)).toFloat() * size.width
                drawRect(Pal.Line, Offset.Zero, size)
                drawRect(Color(0xFF9EDBBE), Offset(x(p.lo), 0f), Size(x(p.hi) - x(p.lo), size.height))
                drawCircle(if (v > p.hi) Pal.RoseInk else if (v < p.lo) Pal.SkyInk else Pal.MintInk, size.height * 0.9f, Offset(x(v), size.height / 2))
            }
            Text("норма ${num(p.lo)}–${num(p.hi)}" + if (p.text.isNotBlank()) " · ${p.text}" else "", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        } else if (p.text.isNotBlank() && p.value != null) Text(p.text, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
    }
}

/** Новый анализ: дата, все поля из прошлого бланка (с теми же нормами), файл бланка (PDF/фото). */
@Composable
private fun BodyCheckEditor(prev: BodyCheck.Rec?, onSave: (BodyCheck.Rec) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val tmpl = remember { (prev?.params ?: BodyCheck.TEMPLATE).map { it.copy(value = null) } }
    val vals = remember { mutableStateMapOf<String, String>() }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var file by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching {
            val name = com.pank.vsevodnom.ui.files.displayName(ctx, u)
            val out = File(ctx.filesDir, "bodycheck/${System.currentTimeMillis()}_$name").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(u)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            file = out.absolutePath
        }
    }
    FullScreen("Новый анализ", onCancel, actions = {
        RoundIconButton(Icons.Rounded.Check, "Сохранить", {
            val ps = tmpl.map { p -> val t = vals[p.key].orEmpty().replace(',', '.').trim(); p.copy(value = t.toDoubleOrNull(), text = if (t.toDoubleOrNull() == null && t.isNotBlank()) t else p.text) }
            onSave(BodyCheck.Rec(date.toString() + "_" + System.currentTimeMillis(), date, ps, files = listOfNotNull(file)))
        }, bg = Pal.Ink, tint = Color.White)
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Сегодня", date == LocalDate.now(), { date = LocalDate.now() })
                Chip("Вчера", date == LocalDate.now().minusDays(1), { date = LocalDate.now().minusDays(1) })
            }
            PillButton(if (file == null) "Прикрепить бланк (PDF или фото)" else "Бланк: " + File(file!!).name, { picker.launch(arrayOf("application/pdf", "image/*")) }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
            Text("Перепиши числа с бланка — пустые поля можно оставить. Нормы берутся из прошлого анализа.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            tmpl.groupBy { it.group }.forEach { (g, ps) ->
                SectionLabel(g)
                ps.forEach { p ->
                    AppTextField(vals[p.key].orEmpty(), { vals[p.key] = it }, p.name + if (p.unit.isNotBlank()) ", ${p.unit}" else "",
                        keyboardOptions = if (p.key in setOf("body_type", "muscle_fix")) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
    }
}

private fun openFile(ctx: Context, f: File) = runCatching {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(android.content.Intent(ctx, com.pank.vsevodnom.ui.files.ViewerActivity::class.java).setData(uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

// ---------------- фото до / после ----------------

private val VIEWS = listOf("front" to "Спереди", "side" to "Сбоку", "back" to "Сзади")
private fun photoDir(ctx: Context) = File(ctx.filesDir, "bodyphotos").apply { mkdirs() }
private fun photoFile(ctx: Context, date: LocalDate, view: String) = File(photoDir(ctx), "${date}_$view.jpg")
private fun dates(ctx: Context): List<LocalDate> = photoDir(ctx).listFiles().orEmpty().mapNotNull { runCatching { LocalDate.parse(it.name.substringBefore('_')) }.getOrNull() }.distinct().sorted()

private fun loadThumb(f: File, max: Int = 900): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
    var s = 1; while (o.outWidth / s > max) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

/** Фото до/после: серии по датам (спереди, сбоку, сзади), сравнение первой и последней серии. */
@Composable
fun BodyPhotosScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val ds = remember(tick) { dates(ctx) }
    var target by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    var pendingCam by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pendingCam; val t = target
        if (ok && p != null && t != null) { p.renameTo(photoFile(ctx, t.first, t.second)); tick++ } else p?.delete()
        pendingCam = null; target = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        val t = target ?: return@rememberLauncherForActivityResult
        if (u != null) runCatching { ctx.contentResolver.openInputStream(u)!!.use { i -> photoFile(ctx, t.first, t.second).outputStream().use { i.copyTo(it) } }; tick++ }
        target = null
    }
    var menuFor by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    fun shoot(d: LocalDate, v: String) {
        target = d to v
        val tmp = File(photoDir(ctx), "tmp_${System.currentTimeMillis()}.jpg"); pendingCam = tmp
        camera.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", tmp))
    }
    ScreenScaffold("Фото до / после", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac) {
                    Text("Как снимать", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
                    Text("Раз в месяц, утром, в одном месте и при том же свете. Живот открыт, руки опущены. Три кадра: спереди, сбоку (профиль), сзади. Фото хранятся только в приложении.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                val today = LocalDate.now()
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Серия за " + today.format(DMY))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VIEWS.forEach { (v, t) -> PhotoSlot(photoFile(ctx, today, v), "Добавить фото: $t", tick, Modifier.weight(1f)) { menuFor = today to v } }
                    }
                }
            }
            if (ds.size >= 2) item {
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Было → стало: ${ds.first().format(DateTimeFormatter.ofPattern("dd.MM.yy"))} → ${ds.last().format(DateTimeFormatter.ofPattern("dd.MM.yy"))}")
                    VIEWS.forEach { (v, t) ->
                        val a = photoFile(ctx, ds.first(), v); val b = photoFile(ctx, ds.last(), v)
                        if (a.exists() || b.exists()) {
                            Text(t, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhotoSlot(a, "нет", tick, Modifier.weight(1f)) {}
                                PhotoSlot(b, "нет", tick, Modifier.weight(1f)) {}
                            }
                        }
                    }
                }
            }
            items(ds.reversed().filter { it != LocalDate.now() }) { d ->
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel(d.format(DMY))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VIEWS.forEach { (v, t) -> PhotoSlot(photoFile(ctx, d, v), t, tick, Modifier.weight(1f)) { menuFor = d to v } }
                    }
                }
            }
        }
    }
    menuFor?.let { (d, v) ->
        val f = photoFile(ctx, d, v)
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(VIEWS.first { it.first == v }.second) },
            text = { Text("Снять сейчас или выбрать из галереи.") },
            confirmButton = { TextButton({ menuFor = null; shoot(d, v) }) { Text("Снять") } },
            dismissButton = {
                Row {
                    if (f.exists()) TextButton({ f.delete(); tick++; menuFor = null }) { Text("Удалить", color = Pal.RoseInk) }
                    TextButton({ menuFor = null; target = d to v; gallery.launch("image/*") }) { Text("Из галереи") }
                }
            })
    }
}

@Composable
private fun PhotoSlot(f: File, empty: String, tick: Int, modifier: Modifier, onClick: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, f.path, tick) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (f.exists()) loadThumb(f) else null } }
    Box(modifier.aspectRatio(0.66f).clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddAPhoto, null, tint = Pal.Muted)
                Text(empty, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(4.dp))
            }
    }
}
