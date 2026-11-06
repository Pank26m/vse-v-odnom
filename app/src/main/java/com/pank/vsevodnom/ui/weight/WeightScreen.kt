package com.pank.vsevodnom.ui.weight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.WeightEntry
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val DM = DateTimeFormatter.ofPattern("dd.MM")
private val DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun fmtKg(v: Double) = "%.1f".format(v).replace('.', ',')

fun daysToMonday(): Int {
    val today = LocalDate.now()
    val next = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    return (next.toEpochDay() - today.toEpochDay()).toInt()
}

@Composable
fun WeightScreen(onBack: () -> Unit, startNew: Boolean = false) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).weight() }
    val entries by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(startNew) }
    var preview by remember { mutableStateOf<List<com.pank.vsevodnom.data.WeightCsv.Row>?>(null) }
    var importErr by remember { mutableStateOf<String?>(null) }
    fun readCsv(u: android.net.Uri) {
        runCatching { com.pank.vsevodnom.data.WeightCsv.parse(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
            .onSuccess { if (it.isEmpty()) importErr = "В файле не нашлось записей веса" else preview = it }
            .onFailure { importErr = "Не получилось прочитать: ${it.message}" }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(::readCsv) }
    LaunchedEffect(Unit) { com.pank.vsevodnom.ui.files.WeightImport.pending?.let { com.pank.vsevodnom.ui.files.WeightImport.pending = null; readCsv(it) } }

    ScreenScaffold("Вес", onBack, actions = {
        RoundIconButton(androidx.compose.material.icons.Icons.Rounded.FileUpload, "Импорт из CSV", { picker.launch(arrayOf("text/*", "text/csv", "*/*")) })
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    val last = entries.lastOrNull()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(last?.let { "${fmtKg(it.kg)} кг" } ?: "— кг", style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                        val monthAgo = LocalDate.now().minusDays(30).toEpochDay()
                        val ref = entries.lastOrNull { it.epochDay <= monthAgo } ?: entries.firstOrNull()
                        if (last != null && ref != null && ref != last) {
                            val d = last.kg - ref.kg
                            Text(
                                (if (d > 0) "+" else if (d < 0) "−" else "") + fmtKg(kotlin.math.abs(d)) + " с ${ref.dateStr()}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (d <= 0) Pal.MintInk else Pal.PeachInk
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    WeightChart(entries.takeLast(26), Modifier.fillMaxWidth().height(140.dp))
                    Spacer(Modifier.height(12.dp))
                    val today = LocalDate.now()
                    Text(
                        if (today.dayOfWeek == DayOfWeek.MONDAY) "Сегодня понедельник — день взвешивания"
                        else "Следующее взвешивание через ${daysToMonday()} дн. Напомню в понедельник в 9:00",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    PillButton("Записать вес", { dialog = true }, Modifier.fillMaxWidth(), bg = Pal.Mint, fg = Color(0xFF1F5C3F))
                }
            }
            if (entries.isEmpty()) item { EmptyHint("Записей пока нет") }
            items(entries.reversed(), key = { it.id }) { e ->
                AppCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.dateStr(full = true), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        Text("${fmtKg(e.kg)} кг", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { scope.launch { dao.delete(e) } }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.Muted) }
                    }
                }
            }
        }
    }

    preview?.let { rows ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Импорт веса") },
            text = { Text("Найдено записей: ${rows.size}\nс ${rows.first().day.format(DMY)} по ${rows.last().day.format(DMY)}\nот ${fmtKg(rows.minOf { it.kg })} до ${fmtKg(rows.maxOf { it.kg })} кг\n\nЕсли на какой-то день вес уже записан — заменю значением из файла.") },
            confirmButton = {
                TextButton({
                    com.pank.vsevodnom.data.AppScope.io.launch {
                        rows.forEach { r -> val ex = dao.byDay(r.day.toEpochDay()); dao.upsert(WeightEntry(id = ex?.id ?: 0, epochDay = r.day.toEpochDay(), kg = r.kg)) }
                    }
                    preview = null
                }) { Text("Импортировать") }
            },
            dismissButton = { TextButton({ preview = null }) { Text("Отмена") } }
        )
    }
    importErr?.let { AlertDialog(onDismissRequest = { importErr = null }, text = { Text(it) }, confirmButton = { TextButton({ importErr = null }) { Text("Ок") } }) }
    if (dialog) {
        var text by remember { mutableStateOf(entries.lastOrNull()?.let { fmtKg(it.kg) } ?: "") }
        var dayOffset by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Вес") },
            text = {
                Column {
                    AppTextField(text, { text = it }, "Килограммы", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Сегодня", dayOffset == 0, { dayOffset = 0 })
                        Chip("Вчера", dayOffset == 1, { dayOffset = 1 })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.replace(',', '.').trim().toDoubleOrNull()
                    if (v != null && v in 20.0..400.0) {
                        scope.launch {
                            val day = LocalDate.now().minusDays(dayOffset.toLong()).toEpochDay()
                            val ex = dao.byDay(day)
                            dao.upsert(WeightEntry(id = ex?.id ?: 0, epochDay = day, kg = v))
                        }
                        dialog = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Отмена") } }
        )
    }
}

private fun WeightEntry.dateStr(full: Boolean = false) = LocalDate.ofEpochDay(epochDay).format(if (full) DMY else DM)

@Composable
fun WeightChart(entries: List<WeightEntry>, modifier: Modifier = Modifier, line: Color = Color(0xFF5FB287)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (i in 0..2) drawLine(Pal.Line, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), 2f)
        if (entries.size < 2) {
            entries.firstOrNull()?.let { drawCircle(Pal.MintInk, 10f, Offset(w / 2, h / 2)) }
            return@Canvas
        }
        val lo = entries.minOf { it.kg } - 0.5
        val hi = entries.maxOf { it.kg } + 0.5
        val d0 = entries.first().epochDay.toFloat(); val d1 = entries.last().epochDay.toFloat()
        val pts = entries.map {
            Offset(
                (it.epochDay - d0) / (d1 - d0).coerceAtLeast(1f) * (w - 24f) + 12f,
                (h - 12f) - ((it.kg - lo) / (hi - lo)).toFloat() * (h - 24f)
            )
        }
        val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(p, line, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Pal.MintInk, 10f, pts.last())
    }
}
