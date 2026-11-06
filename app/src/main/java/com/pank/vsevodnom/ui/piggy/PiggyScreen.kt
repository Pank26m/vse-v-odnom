package com.pank.vsevodnom.ui.piggy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.GoalWithSum
import com.pank.vsevodnom.data.PiggyGoal
import com.pank.vsevodnom.data.PiggyTx
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

fun money(v: Double): String {
    val r = v.roundToLong()
    val s = abs(r).toString().reversed().chunked(3).joinToString(" ").reversed()
    return (if (r < 0) "−" else "") + s + " ₴"
}

private val DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Прогноз: когда цель будет достигнута при текущем темпе (по истории пополнений). */
private fun forecast(g: GoalWithSum, txs: List<PiggyTx>): String? {
    val left = g.goal.target - g.saved
    if (left <= 0 || txs.size < 2) return null
    val first = txs.minOf { it.at }; val last = txs.maxOf { it.at }
    val days = ((last - first) / 86_400_000.0).coerceAtLeast(1.0)
    val perDay = txs.filter { it.amount > 0 }.sumOf { it.amount } / days
    if (perDay <= 0) return null
    val date = LocalDate.now().plusDays(ceil(left / perDay).toLong())
    return if (g.goal.kind == PiggyGoal.KIND_PAYBACK) {
        val avg = txs.filter { it.amount > 0 }.map { it.amount }.average()
        "Осталось ~${ceil(left / avg).toInt()} аренд · окупится примерно ${date.format(DMY)}"
    } else "При текущем темпе — к ${date.format(DMY)}"
}

@Composable
fun PiggyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).piggy() }
    val goals by dao.goals().collectAsState(initial = emptyList())
    var editGoal by remember { mutableStateOf<PiggyGoal?>(null) }
    var openId by remember { mutableStateOf<Long?>(null) }

    val open = goals.firstOrNull { it.goal.id == openId }
    if (open != null) {
        GoalDetail(open, onBack = { openId = null }, onEdit = { editGoal = open.goal })
    } else {
        ScreenScaffold("Копилки", onBack, actions = {
            RoundIconButton(Icons.Rounded.Add, "Новая копилка", { editGoal = PiggyGoal(title = "") }, bg = Pal.Ink, tint = Color.White)
        }) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    AppCard(color = Pal.Mint, radius = 16.dp, padding = 20.dp, modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Всего отложено", Pal.MintInk)
                        Text(money(goals.filter { it.goal.kind == PiggyGoal.KIND_SAVE }.sumOf { it.saved }), style = MaterialTheme.typography.displayMedium)
                        val pay = goals.filter { it.goal.kind == PiggyGoal.KIND_PAYBACK }
                        Text(
                            "в ${goals.count { it.goal.kind == PiggyGoal.KIND_SAVE }} копилках" +
                                if (pay.isNotEmpty()) " · заработано на аренде ${money(pay.sumOf { it.saved })}" else "",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF3F5A4B)
                        )
                    }
                }
                if (goals.isEmpty()) item { EmptyHint("Создай первую копилку: «Отпуск», «Инструмент в аренде»…") }
                items(goals, key = { it.goal.id }) { g -> GoalCard(g) { openId = g.goal.id } }
            }
        }
    }

    editGoal?.let { g ->
        GoalEditor(g, onDismiss = { editGoal = null }, onDelete = if (g.id != 0L) ({ openId = null }) else null)
    }
}

@Composable
private fun GoalCard(g: GoalWithSum, onClick: () -> Unit) {
    val tint = Tints.all[g.goal.tint % Tints.all.size]
    val pct = if (g.goal.target > 0) (g.saved / g.goal.target).coerceIn(0.0, 1.0) else 1.0
    AppCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoalThumb(g.goal, 48)
            Column(Modifier.weight(1f)) {
                Text(g.goal.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (g.goal.kind == PiggyGoal.KIND_PAYBACK) "окупаемость · ${g.txCount} аренд"
                    else if (g.goal.target > 0) "${money(g.saved)} из ${money(g.goal.target)}" else money(g.saved),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (g.goal.target > 0) Column(horizontalAlignment = Alignment.End) {
                val left = (g.goal.target - g.saved).coerceAtLeast(0.0)
                Text(if (left > 0) money(left) else "готово", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                Text(if (left > 0) "осталось" else if (g.goal.kind == PiggyGoal.KIND_PAYBACK) "окупилось" else "цель", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (g.goal.target > 0) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(pct.toFloat(), tint.ink.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun ProgressBar(p: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(Pal.Line)) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(color))
        Text("${(p * 100).toInt()}%", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = Pal.Ink)
    }
}

@Composable
private fun GoalThumb(g: PiggyGoal, sizeDp: Int) {
    val ctx = LocalContext.current
    val tint = Tints.all[g.tint % Tints.all.size]
    val bmp = remember(g.photo) {
        if (g.photo.isBlank()) null else runCatching {
            ctx.contentResolver.openInputStream(android.net.Uri.parse(g.photo))?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }.getOrNull()
    }
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg), contentAlignment = Alignment.Center) {
        if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text(g.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = tint.ink)
    }
}

/** График: накоплено по времени (линия) и поступления по месяцам (столбики). */
@Composable
private fun GoalChart(txs: List<PiggyTx>, color: Color) {
    if (txs.size < 2) return
    val sorted = txs.sortedBy { it.at }
    val zone = ZoneId.systemDefault()
    var acc = 0.0
    val cum = sorted.map { acc += it.amount; it.at to acc }
    val months = sorted.groupBy { java.time.YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }.mapValues { (_, l) -> l.sumOf { it.amount } }.toSortedMap().entries.toList().takeLast(8)
    AppCard(Modifier.fillMaxWidth()) {
        SectionLabel("Накоплено со временем")
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val t0 = cum.first().first.toFloat(); val t1 = cum.last().first.toFloat().coerceAtLeast(t0 + 1)
            val hi = cum.maxOf { it.second }.coerceAtLeast(1.0).toFloat(); val lo = minOf(0f, cum.minOf { it.second }.toFloat())
            val path = androidx.compose.ui.graphics.Path()
            cum.forEachIndexed { i, (t, v) ->
                val x = (t - t0) / (t1 - t0) * size.width; val y = size.height - (v.toFloat() - lo) / (hi - lo) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
        }
        if (months.size >= 2) {
            Spacer(Modifier.height(10.dp))
            SectionLabel("По месяцам")
            val mx = months.maxOf { kotlin.math.abs(it.value) }.coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                months.forEach { (ym, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text(compact(v), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height((60 * kotlin.math.abs(v) / mx).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(6.dp)).background(if (v >= 0) color.copy(alpha = 0.6f) else Pal.RoseInk.copy(alpha = 0.5f)))
                        Text(ym.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, java.util.Locale("ru")).take(3), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun compact(v: Double) = if (kotlin.math.abs(v) >= 1000) "%.1fк".format(v / 1000).replace('.', ',').replace(",0к", "к") else v.roundToLong().toString()

@Composable
private fun GoalDetail(g: GoalWithSum, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).piggy() }
    val txs by dao.txs(g.goal.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<Boolean?>(null) } // true = пополнение, false = снятие
    val tint = Tints.all[g.goal.tint % Tints.all.size]
    val payback = g.goal.kind == PiggyGoal.KIND_PAYBACK

    androidx.activity.compose.BackHandler(onBack = onBack)
    ScreenScaffold(g.goal.title, onBack, actions = { RoundIconButton(Icons.Rounded.Edit, "Изменить", onEdit) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(color = tint.bg, radius = 16.dp, padding = 20.dp, modifier = Modifier.fillMaxWidth()) {
                    SectionLabel(if (payback) "Заработано" else "Накоплено", tint.ink)
                    Text(money(g.saved), style = MaterialTheme.typography.displayMedium)
                    if (g.goal.target > 0) {
                        Text(
                            (if (payback) "стоимость ${money(g.goal.target)}" else "цель ${money(g.goal.target)}") +
                                " · осталось ${money((g.goal.target - g.saved).coerceAtLeast(0.0))}",
                            style = MaterialTheme.typography.bodySmall, color = Pal.Ink
                        )
                        Spacer(Modifier.height(10.dp))
                        ProgressBar((g.saved / g.goal.target).toFloat().coerceIn(0f, 1f), tint.ink.copy(alpha = 0.6f))
                        if (g.saved >= g.goal.target) {
                            Spacer(Modifier.height(8.dp))
                            Text(if (payback) "Окупилось! Дальше — чистая прибыль" else "Цель достигнута", style = MaterialTheme.typography.titleSmall, color = tint.ink)
                        }
                    }
                    if (g.goal.photo.isNotBlank()) { Spacer(Modifier.height(8.dp)); GoalThumb(g.goal, 72) }
                    forecast(g, txs)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (g.goal.note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(g.goal.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (payback) "+ Аренда" else "+ Пополнить", { adding = true }, Modifier.weight(1f))
                    PillButton(if (payback) "− Расход" else "− Снять", { adding = false }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                }
            }
            if (g.goal.link.isNotBlank() || g.goal.link2.isNotBlank()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (g.goal.link.isNotBlank()) Chip("Товар ↗", false, { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(g.goal.link))) } })
                    if (g.goal.link2.isNotBlank()) Chip("Объявление ↗", false, { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(g.goal.link2))) } })
                }
            }
            item { GoalChart(txs, tint.ink) }
            if (txs.isEmpty()) item { EmptyHint("Операций пока нет") }
            items(txs, key = { it.id }) { t ->
                AppCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(t.note.ifBlank { if (t.amount >= 0) (if (payback) "Аренда" else "Пополнение") else (if (payback) "Расход" else "Снятие") }, style = MaterialTheme.typography.bodyLarge)
                            Text(Instant.ofEpochMilli(t.at).atZone(ZoneId.systemDefault()).toLocalDate().format(DMY), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            (if (t.amount >= 0) "+" else "") + money(t.amount), style = MaterialTheme.typography.titleMedium,
                            color = if (t.amount >= 0) Pal.MintInk else Pal.RoseInk
                        )
                        IconButton(onClick = { scope.launch { dao.deleteTx(t) } }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.Muted) }
                    }
                }
            }
        }
    }

    adding?.let { plus ->
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(if (plus) (if (payback) "Доход с аренды" else "Пополнить") else (if (payback) "Расход" else "Снять")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(amount, { amount = it }, "Сумма, ₴", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    // последние суммы — одним нажатием
                    val recent = txs.filter { (it.amount > 0) == plus }.map { kotlin.math.abs(it.amount) }.distinct().take(4)
                    if (recent.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { v -> Chip(money(v), false, { amount = v.roundToLong().toString() }) }
                    }
                    AppTextField(note, { note = it }, "Комментарий (необязательно)")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = amount.replace(',', '.').replace(" ", "").toDoubleOrNull()
                    if (v != null && v > 0) {
                        scope.launch { dao.addTx(PiggyTx(goalId = g.goal.id, amount = if (plus) v else -v, note = note.trim())) }
                        adding = null
                    }
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun GoalEditor(g: PiggyGoal, onDismiss: () -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).piggy() }
    val scope = com.pank.vsevodnom.data.AppScope.io
    var title by remember { mutableStateOf(g.title) }
    var kind by remember { mutableStateOf(g.kind) }
    var target by remember { mutableStateOf(if (g.target > 0) g.target.roundToLong().toString() else "") }
    var tint by remember { mutableIntStateOf(g.tint) }
    var note by remember { mutableStateOf(g.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf(g.photo) }
    var link by remember { mutableStateOf(g.link) }
    var link2 by remember { mutableStateOf(g.link2) }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; photo = u.toString() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (g.id == 0L) "Новая копилка" else "Копилка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Накопление", kind == PiggyGoal.KIND_SAVE, { kind = PiggyGoal.KIND_SAVE })
                    Chip("Окупаемость", kind == PiggyGoal.KIND_PAYBACK, { kind = PiggyGoal.KIND_PAYBACK })
                }
                AppTextField(title, { title = it }, "Название")
                AppTextField(
                    target, { target = it },
                    if (kind == PiggyGoal.KIND_PAYBACK) "Стоимость вещи, ₴" else "Цель, ₴ (можно пусто)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                AppTextField(note, { note = it }, "Заметка")
                AppTextField(link, { link = it }, "Ссылка на товар")
                if (kind == PiggyGoal.KIND_PAYBACK) AppTextField(link2, { link2 = it }, "Ссылка на объявление аренды")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoalThumb(g.copy(photo = photo, title = title.ifBlank { "?" }, tint = tint), 44)
                    TextButton({ photoPicker.launch(arrayOf("image/*")) }) { Text(if (photo.isBlank()) "Добавить фото" else "Сменить фото") }
                    if (photo.isNotBlank()) TextButton({ photo = "" }) { Text("Убрать", color = Pal.RoseInk) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tints.all.forEachIndexed { i, t ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(t.bg)
                                .then(if (i == tint) Modifier.background(t.ink.copy(alpha = 0.35f)) else Modifier)
                                .clickable { tint = i }
                        )
                    }
                }
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Удалить копилку", color = Pal.RoseInk) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) return@TextButton
                scope.launch {
                    dao.upsertGoal(g.copy(title = title.trim(), kind = kind, target = target.replace(" ", "").toDoubleOrNull() ?: 0.0, tint = tint, note = note.trim(), photo = photo, link = link.trim(), link2 = link2.trim()))
                }
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить «${g.title}» со всеми операциями?") },
        confirmButton = {
            TextButton(onClick = { scope.launch { dao.deleteGoal(g) }; confirmDelete = false; onDelete?.invoke(); onDismiss() }) {
                Text("Удалить", color = Pal.RoseInk)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
    )
}
