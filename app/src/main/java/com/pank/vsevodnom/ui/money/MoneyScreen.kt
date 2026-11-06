package com.pank.vsevodnom.ui.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val RU = Locale("ru")
private val CUR = mapOf("UAH" to "₴", "USD" to "$", "EUR" to "€", "PLN" to "zł", "GBP" to "£")

fun fmtMoney(v: Double, cur: String = "UAH"): String {
    val r = (v * 100).roundToLong() / 100.0
    val whole = abs(r).toLong()
    val frac = ((abs(r) - whole) * 100).roundToLong()
    val s = whole.toString().reversed().chunked(3).joinToString(" ").reversed() + if (frac > 0) ",%02d".format(frac) else ""
    return (if (r < 0) "−" else "") + s + " " + (CUR[cur] ?: cur)
}

/** Период для фильтров. */
private enum class Period(val title: String) { WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время") }

private fun range(p: Period, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (p) {
    Period.WEEK -> anchor.with(DayOfWeek.MONDAY).let { it to it.plusDays(6) }
    Period.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
    Period.YEAR -> anchor.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
    Period.ALL -> LocalDate.of(2000, 1, 1) to LocalDate.of(2100, 1, 1)
}

@Composable
fun MoneyScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    LaunchedEffect(Unit) { MoneyRepo.ensureDefaults(ctx) }
    val accounts by dao.accounts().collectAsState(initial = emptyList())
    val cats by dao.categories().collectAsState(initial = emptyList())
    val txns by dao.allTxns().collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Txn?>(null) }
    var settings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack); Spacer(Modifier.width(12.dp)) }
                Text("Деньги", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Settings, "Настройки", { settings = true })
            }
            Segmented(listOf("Обзор", "Операции", "Категории", "Счета"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Mint)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Overview(accounts, cats, txns) { edit = it }
                    1 -> Operations(accounts, cats, txns) { edit = it }
                    2 -> Categories(cats, txns)
                    else -> Accounts(accounts, txns)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink).clickable {
            edit = Txn(at = System.currentTimeMillis(), type = MoneyRepo.EXPENSE, amount = 0.0, accountId = accounts.firstOrNull()?.id ?: 0)
        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Добавить", tint = Color.White) }
    }
    edit?.let { TxnEditor(it, accounts, cats) { edit = null } }
    if (settings) MoneySettings { settings = false }
}

@Composable
private fun Overview(accounts: List<Account>, cats: List<Category>, txns: List<Txn>, onEdit: (Txn) -> Unit) {
    val z = ZoneId.systemDefault()
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var usd by remember { mutableStateOf<Double?>(null) }; var eur by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { usd = MoneyRepo.nbu("USD"); eur = MoneyRepo.nbu("EUR") }
    val (from, to) = range(Period.MONTH, anchor)
    val inMonth = txns.filter { Instant.ofEpochMilli(it.at).atZone(z).toLocalDate().let { d -> !d.isBefore(from) && !d.isAfter(to) } }
    val exp = inMonth.filter { it.type == MoneyRepo.EXPENSE }.sumOf { it.amount }
    val inc = inMonth.filter { it.type == MoneyRepo.INCOME }.sumOf { it.amount }
    val catById = cats.associateBy { it.id }
    fun root(id: Long?): Category? { var c = id?.let { catById[it] }; while (c?.parentId != null) c = catById[c.parentId]; return c }
    val byCat = inMonth.filter { it.type == MoneyRepo.EXPENSE }.groupBy { root(it.categoryId) }.mapValues { (_, l) -> l.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.weight(1f), color = Pal.Sky) { Text("Доллар (НБУ)", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk); Text(usd?.let { "%.2f ₴".format(it).replace('.', ',') } ?: "…", style = MaterialTheme.typography.headlineMedium) }
                AppCard(Modifier.weight(1f), color = Pal.Lilac) { Text("Евро (НБУ)", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk); Text(eur?.let { "%.2f ₴".format(it).replace('.', ',') } ?: "…", style = MaterialTheme.typography.headlineMedium) }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("На счетах")
                accounts.filter { !it.archived }.forEach { a -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(a.name, modifier = Modifier.weight(1f)); Text(fmtMoney(MoneyRepo.balance(a, txns), a.currency), fontWeight = FontWeight.Bold) } }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ anchor = anchor.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
                    Text(anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton({ anchor = anchor.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
                }
                Row {
                    Column(Modifier.weight(1f)) { Text("Расходы", style = MaterialTheme.typography.labelMedium, color = Pal.Muted); Text(fmtMoney(exp), style = MaterialTheme.typography.titleLarge, color = Pal.RoseInk) }
                    Column(Modifier.weight(1f)) { Text("Доходы", style = MaterialTheme.typography.labelMedium, color = Pal.Muted); Text(fmtMoney(inc), style = MaterialTheme.typography.titleLarge, color = Pal.MintInk) }
                    Column(Modifier.weight(1f)) { Text("Итог", style = MaterialTheme.typography.labelMedium, color = Pal.Muted); Text(fmtMoney(inc - exp), style = MaterialTheme.typography.titleLarge) }
                }
                if (byCat.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // кольцо расходов по категориям
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(120.dp)) {
                            var start = -90f
                            byCat.take(6).forEach { (c, v) -> val sweep = (v / exp * 360).toFloat(); drawArc(Tints.all[(c?.tint ?: 5) % 6].ink.copy(alpha = 0.7f), start, sweep, false, style = Stroke(34f)); start += sweep }
                            if (byCat.size > 6) drawArc(Pal.Line, start, 270f - start, false, style = Stroke(34f))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            byCat.take(6).forEach { (c, v) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Tints.all[(c?.tint ?: 5) % 6].ink.copy(alpha = 0.7f)))
                                    Text(" ${c?.name ?: "Без категории"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${(v / exp * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionLabel("Последние операции") }
        items(txns.take(15), key = { it.id }) { t -> TxnRow(t, accounts, cats) { onEdit(t) } }
    }
}

@Composable
fun TxnRow(t: Txn, accounts: List<Account>, cats: List<Category>, onClick: () -> Unit) {
    val acc = accounts.firstOrNull { it.id == t.accountId }
    val cat = cats.firstOrNull { it.id == t.categoryId }
    val parent = cat?.parentId?.let { p -> cats.firstOrNull { it.id == p } }
    val title = when (t.type) {
        MoneyRepo.TRANSFER -> "Перевод → ${accounts.firstOrNull { it.id == t.toAccountId }?.name ?: "?"}"
        MoneyRepo.ADJUST -> "Корректировка"
        else -> cat?.let { if (parent != null) "${parent.name} · ${it.name}" else it.name } ?: t.comment.ifBlank { "Без категории" }
    }
    val sign = when (t.type) { MoneyRepo.INCOME -> "+"; MoneyRepo.EXPENSE -> "−"; MoneyRepo.ADJUST -> if (t.amount >= 0) "+" else "−"; else -> "" }
    val tint = Tints.all[((parent ?: cat)?.tint ?: 3) % 6]
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.bg), contentAlignment = Alignment.Center) {
            Icon(when (t.type) { MoneyRepo.INCOME -> Icons.Rounded.SouthWest; MoneyRepo.TRANSFER -> Icons.Rounded.SwapHoriz; else -> Icons.Rounded.NorthEast }, null, tint = tint.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(listOfNotNull(Instant.ofEpochMilli(t.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")), acc?.name,
                t.comment.takeIf { it.isNotBlank() && cat != null }, t.tags.ifBlank { null }?.let { "#$it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(sign + fmtMoney(abs(t.amount), acc?.currency ?: "UAH"), style = MaterialTheme.typography.titleSmall,
            color = when (t.type) { MoneyRepo.INCOME -> Pal.MintInk; MoneyRepo.EXPENSE -> Pal.Ink; else -> Pal.Muted })
    }
}

@Composable
private fun Operations(accounts: List<Account>, cats: List<Category>, txns: List<Txn>, onEdit: (Txn) -> Unit) {
    val z = ZoneId.systemDefault()
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var acc by remember { mutableStateOf<Long?>(null) }
    var cat by remember { mutableStateOf<Long?>(null) }
    var q by remember { mutableStateOf("") }
    val (from, to) = range(period, anchor)
    val catIds = cat?.let { c -> setOf(c) + cats.filter { it.parentId == c }.map { it.id } }
    val list = txns.filter { t ->
        val d = Instant.ofEpochMilli(t.at).atZone(z).toLocalDate()
        !d.isBefore(from) && !d.isAfter(to) && (type == null || t.type == type) && (acc == null || t.accountId == acc || t.toAccountId == acc) &&
            (catIds == null || t.categoryId in catIds) && (q.isBlank() || t.comment.contains(q, true) || t.tags.contains(q, true) || (cats.firstOrNull { it.id == t.categoryId }?.name?.contains(q, true) == true))
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(Period.entries) { p -> Chip(p.title, period == p, { period = p }) } } }
        if (period != Period.ALL) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ anchor = when (period) { Period.WEEK -> anchor.minusWeeks(1); Period.MONTH -> anchor.minusMonths(1); else -> anchor.minusYears(1) } }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
                Text("${from.format(DateTimeFormatter.ofPattern("dd.MM.yy"))} – ${to.format(DateTimeFormatter.ofPattern("dd.MM.yy"))}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton({ anchor = when (period) { Period.WEEK -> anchor.plusWeeks(1); Period.MONTH -> anchor.plusMonths(1); else -> anchor.plusYears(1) } }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(null to "Все", MoneyRepo.EXPENSE to "Расходы", MoneyRepo.INCOME to "Доходы", MoneyRepo.TRANSFER to "Переводы")) { (k, s) -> Chip(s, type == k, { type = k }) }
                items(accounts) { a -> Chip(a.name, acc == a.id, { acc = if (acc == a.id) null else a.id }) }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cats.filter { it.parentId == null && (type == null || (type == MoneyRepo.INCOME) == it.income) }) { c -> Chip(c.name, cat == c.id, { cat = if (cat == c.id) null else c.id }) }
            }
        }
        item { AppTextField(q, { q = it }, "Поиск: комментарий, тег, категория") }
        item {
            val e = list.filter { it.type == MoneyRepo.EXPENSE }.sumOf { it.amount }; val i = list.filter { it.type == MoneyRepo.INCOME }.sumOf { it.amount }
            Text("Операций: ${list.size} · расходы ${fmtMoney(e)} · доходы ${fmtMoney(i)}", style = MaterialTheme.typography.bodySmall)
        }
        list.groupBy { Instant.ofEpochMilli(it.at).atZone(z).toLocalDate() }.forEach { (d, l) ->
            item { Text(d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp)) }
            items(l, key = { it.id }) { t -> TxnRow(t, accounts, cats) { onEdit(t) } }
        }
    }
}

@Composable
private fun Categories(cats: List<Category>, txns: List<Txn>) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    var income by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Category?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("Расходы", !income, { income = false }); Chip("Доходы", income, { income = true }); Spacer(Modifier.weight(1f)); Chip("+ категория", false, { edit = Category(name = "", income = income) }) } }
        cats.filter { it.parentId == null && it.income == income }.forEach { c ->
            item(key = c.id) {
                val subs = cats.filter { it.parentId == c.id }
                val t = Tints.all[c.tint % 6]
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { edit = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(t.ink.copy(alpha = 0.7f)))
                        Text(" ${c.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${txns.count { it.categoryId == c.id || subs.any { s -> s.id == it.categoryId } }} оп.", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        TextButton({ edit = Category(name = "", income = c.income, parentId = c.id, tint = c.tint) }) { Text("+ под") }
                    }
                    subs.forEach { s -> Text("— ${s.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp).clickable { edit = s }.padding(vertical = 2.dp)) }
                }
            }
        }
    }
    edit?.let { c ->
        var name by remember { mutableStateOf(c.name) }
        var tint by remember { mutableIntStateOf(c.tint) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (c.parentId != null) "Подкатегория" else "Категория") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(name, { name = it }, "Название")
                    if (c.parentId == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (i == tint) 30.dp else 24.dp).clip(CircleShape).background(t.ink.copy(alpha = if (i == tint) 0.8f else 0.35f)).clickable { tint = i }) } }
                    if (c.id != 0L) TextButton({ AppScope.io.launch { dao.deleteCategory(c) }; edit = null }) { Text("Удалить (операции останутся без категории)", color = Pal.RoseInk) }
                }
            },
            confirmButton = { TextButton({ if (name.isNotBlank()) AppScope.io.launch { dao.upsertCategory(c.copy(name = name.trim(), tint = tint)) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}

@Composable
private fun Accounts(accounts: List<Account>, txns: List<Txn>) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    var edit by remember { mutableStateOf<Account?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(accounts, key = { it.id }) { a ->
            AppCard(Modifier.fillMaxWidth(), color = Tints.all[a.tint % 6].bg, onClick = { edit = a }) {
                Text(a.name + if (a.archived) " (архив)" else "", style = MaterialTheme.typography.titleSmall)
                Text(fmtMoney(MoneyRepo.balance(a, txns), a.currency), style = MaterialTheme.typography.headlineMedium)
                Text("${txns.count { it.accountId == a.id || it.toAccountId == a.id }} операций", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { PillButton("+ Счёт", { edit = Account(name = "") }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink) }
    }
    edit?.let { a ->
        var name by remember { mutableStateOf(a.name) }
        var cur by remember { mutableStateOf(a.currency) }
        var initial by remember { mutableStateOf(if (a.initial != 0.0) a.initial.toString() else "") }
        var archived by remember { mutableStateOf(a.archived) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Счёт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(name, { name = it }, "Название")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("UAH", "USD", "EUR", "PLN").forEach { c -> Chip(c, cur == c, { cur = c }) } }
                    AppTextField(initial, { initial = it }, "Начальный остаток", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("В архиве", modifier = Modifier.weight(1f)); Switch(archived, { archived = it }) }
                }
            },
            confirmButton = { TextButton({ if (name.isNotBlank()) AppScope.io.launch { dao.upsertAccount(a.copy(name = name.trim(), currency = cur, initial = initial.replace(',', '.').toDoubleOrNull() ?: 0.0, archived = archived)) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}

/** Полноэкранное добавление/изменение операции. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TxnEditor(t: Txn, accounts: List<Account>, cats: List<Category>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(if (t.type == MoneyRepo.ADJUST) MoneyRepo.EXPENSE else t.type) }
    var amount by remember { mutableStateOf(if (t.amount != 0.0) abs(t.amount).toString().removeSuffix(".0") else "") }
    var acc by remember { mutableLongStateOf(t.accountId.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0) }
    var toAcc by remember { mutableStateOf(t.toAccountId) }
    var cat by remember { mutableStateOf(t.categoryId) }
    var comment by remember { mutableStateOf(t.comment) }
    var tags by remember { mutableStateOf(t.tags) }
    var at by remember { mutableLongStateOf(t.at) }
    var pickDate by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (t.id == 0L) "Новая операция" else "Операция", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (t.id != 0L) IconButton({ AppScope.io.launch { AppDb.get(ctx).money().deleteTxn(t) }; onDone() }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.RoseInk) }
            }
            Segmented(listOf("Расход", "Доход", "Перевод"), listOf(MoneyRepo.EXPENSE, MoneyRepo.INCOME, MoneyRepo.TRANSFER).indexOf(type).coerceAtLeast(0),
                { type = listOf(MoneyRepo.EXPENSE, MoneyRepo.INCOME, MoneyRepo.TRANSFER)[it]; cat = null }, track = Pal.Mint)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Сумма") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.displayMedium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
                SectionLabel(if (type == MoneyRepo.TRANSFER) "Откуда" else "Счёт")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { accounts.filter { !it.archived }.forEach { a -> Chip(a.name, acc == a.id, { acc = a.id }) } }
                if (type == MoneyRepo.TRANSFER) {
                    SectionLabel("Куда")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { accounts.filter { !it.archived && it.id != acc }.forEach { a -> Chip(a.name, toAcc == a.id, { toAcc = a.id }) } }
                } else {
                    SectionLabel("Категория")
                    val tops = cats.filter { it.parentId == null && it.income == (type == MoneyRepo.INCOME) && !it.archived }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tops.forEach { c -> Chip(c.name, cat == c.id || cats.firstOrNull { it.id == cat }?.parentId == c.id, { cat = c.id }) }
                    }
                    val parent = cat?.let { id -> cats.firstOrNull { it.id == id }?.let { it.parentId ?: it.id } }
                    val subs = cats.filter { it.parentId == parent && parent != null }
                    if (subs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { subs.forEach { s -> Chip("· ${s.name}", cat == s.id, { cat = s.id }) } }
                }
                SectionLabel("Когда")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
                    Chip("Сегодня", d == LocalDate.now(), { at = System.currentTimeMillis() })
                    Chip("Вчера", d == LocalDate.now().minusDays(1), { at = System.currentTimeMillis() - 86_400_000 })
                    Chip(d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), false, { pickDate = true })
                }
                AppTextField(comment, { comment = it }, "Комментарий")
                AppTextField(tags, { tags = it }, "Теги через запятую")
            }
            PillButton("Сохранить", {
                val v = amount.replace(',', '.').replace(" ", "").toDoubleOrNull() ?: return@PillButton
                if (v <= 0 || acc == 0L || (type == MoneyRepo.TRANSFER && toAcc == null)) return@PillButton
                AppScope.io.launch {
                    AppDb.get(ctx).money().upsertTxn(t.copy(type = type, amount = v, accountId = acc, toAccountId = if (type == MoneyRepo.TRANSFER) toAcc else null,
                        toAmount = if (type == MoneyRepo.TRANSFER) v else null, categoryId = if (type == MoneyRepo.TRANSFER) null else cat, comment = comment.trim(), tags = tags.trim(), at = at, source = t.source.ifBlank { "manual" }))
                }
                onDone()
            }, Modifier.fillMaxWidth())
        }
    }
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = at)
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { at = it + 12 * 3600_000L }; pickDate = false }) { Text("Ок") }
        }) { DatePicker(dp) }
    }
}

@Composable
private fun MoneySettings(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(MoneyRepo.monoToken(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = true
            msg = runCatching { MoneyRepo.importSumlio(ctx, ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
                .fold({ "Импортировано операций: ${it.added}, пропущено повторов: ${it.skipped}. Новых счетов: ${it.accounts}, категорий: ${it.categories}." }, { "Не получилось: ${it.message}" })
            busy = false
        }
    }
    AlertDialog(onDismissRequest = onDone, title = { Text("Деньги") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Импорт из Sumlio")
                PillButton("Выбрать файл .csv", { picker.launch(arrayOf("text/*", "*/*")) }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink, enabled = !busy)
                SectionLabel("monobank")
                Text("Токен: api.monobank.ua → войти через приложение mono → скопировать токен. Он даёт только чтение выписки.", style = MaterialTheme.typography.bodySmall)
                AppTextField(token, { token = it }, "Токен monobank")
                PillButton("Загрузить выписку за 30 дней", {
                    MoneyRepo.setMonoToken(ctx, token)
                    scope.launch { busy = true; msg = runCatching { MoneyRepo.syncMono(ctx) }.fold({ "Добавлено операций из mono: $it" }, { "mono: ${it.message}" }); busy = false }
                }, Modifier.fillMaxWidth(), enabled = !busy && token.isNotBlank())
                Text("Если карт несколько — между ними пауза минута (ограничение mono).", style = MaterialTheme.typography.labelSmall)
                SectionLabel("Другие банки")
                Text("Траты ПриватБанка и других банков приложение может ловить из их push-уведомлений: Утилиты → «Доступ к уведомлениям».", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Готово") } })
}
