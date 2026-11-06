package com.pank.vsevodnom.ui.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.focusRequester
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val RU = Locale("ru")
private val CUR = mapOf("UAH" to "грн.", "USD" to "$", "EUR" to "€", "PLN" to "zł", "GBP" to "£")
private val RED = Color(0xFFE5485D)
private val GREEN = Color(0xFF2DB37A)

fun fmtMoney(v: Double, cur: String = "UAH"): String {
    val r = (v * 100).roundToLong() / 100.0
    val whole = abs(r).toLong()
    val frac = ((abs(r) - whole) * 100).roundToLong()
    val s = whole.toString().reversed().chunked(3).joinToString(" ").reversed() + if (frac > 0) (if (frac % 10 == 0L) ",${frac / 10}" else ",%02d".format(frac)) else ""
    return (if (r < 0) "−" else "") + s + " " + (CUR[cur] ?: cur)
}

/** Период: месяц (по умолчанию), неделя, год, всё время. */
private enum class Period(val title: String) { WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время") }

private class PeriodState {
    var period by mutableStateOf(Period.MONTH)
    var anchor by mutableStateOf(LocalDate.now())
    val range: Pair<LocalDate, LocalDate> get() = when (period) {
        Period.WEEK -> anchor.with(DayOfWeek.MONDAY).let { it to it.plusDays(6) }
        Period.MONTH -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        Period.YEAR -> anchor.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
        Period.ALL -> LocalDate.of(2000, 1, 1) to LocalDate.of(2100, 1, 1)
    }
    fun shift(n: Long) { anchor = when (period) { Period.WEEK -> anchor.plusWeeks(n); Period.MONTH -> anchor.plusMonths(n); Period.YEAR -> anchor.plusYears(n); Period.ALL -> anchor } }
    val title: String get() = when (period) {
        Period.MONTH -> anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }
        Period.YEAR -> "${anchor.year}"
        Period.WEEK -> range.let { (a, b) -> "${a.format(DateTimeFormatter.ofPattern("d MMM", RU))} – ${b.format(DateTimeFormatter.ofPattern("d MMM", RU))}" }
        Period.ALL -> "Всё время"
    }
    fun contains(ms: Long): Boolean { val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate(); val (a, b) = range; return !d.isBefore(a) && !d.isAfter(b) }
}

@Composable
fun MoneyScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    LaunchedEffect(Unit) { MoneyRepo.ensureDefaults(ctx) }
    val accounts by dao.accounts().collectAsState(initial = emptyList())
    val cats by dao.categories().collectAsState(initial = emptyList())
    val txns by dao.allTxns().collectAsState(initial = emptyList())
    val prefs = remember { ctx.getSharedPreferences("money", android.content.Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableIntStateOf(prefs.getInt("tab", 1)) }
    var hide by remember { mutableStateOf(prefs.getBoolean("hide", false)) }
    var edit by remember { mutableStateOf<Txn?>(null) }
    val ps = remember { PeriodState() }
    var usd by remember { mutableStateOf<Double?>(null) }; var eur by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Net.online) { if (usd == null) usd = MoneyRepo.nbu("USD"); if (eur == null) eur = MoneyRepo.nbu("EUR") }
    val rate: (String) -> Double? = { c -> when (c) { "UAH" -> 1.0; "USD" -> usd; "EUR" -> eur; else -> null } }
    val total = accounts.filter { !it.archived }.sumOf { a -> MoneyRepo.balance(a, txns) * (rate(a.currency) ?: 0.0) }
    fun newTxn(type: String = MoneyRepo.EXPENSE, cat: Long? = null) {
        edit = Txn(at = System.currentTimeMillis(), type = type, amount = 0.0, accountId = accounts.firstOrNull { !it.archived }?.id ?: 0, categoryId = cat)
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        // шапка: общий баланс (можно скрыть), курсы мелко
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { hide = !hide; prefs.edit().putBoolean("hide", hide).apply() }) {
                    Text("Общий баланс ", style = MaterialTheme.typography.bodyMedium)
                    Icon(if (hide) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Скрыть", Modifier.size(18.dp), tint = Pal.Muted)
                }
                Text(if (hide) "••••• грн." else fmtMoney(total), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
                if (usd != null || eur != null) Text(listOfNotNull(usd?.let { "$ " + "%.2f".format(it).replace('.', ',') }, eur?.let { "€ " + "%.2f".format(it).replace('.', ',') }).joinToString("   ") + "  · НБУ",
                    style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            }
            if (onBack != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { RoundIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Назад", onBack) }
        }
        if (tab in 1..3) PeriodBar(ps)
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> AccountsTab(accounts, txns, hide)
                1 -> CategoriesTab(cats, txns, ps, onAdd = { type, cat -> newTxn(type, cat) })
                2 -> OperationsTab(accounts, cats, txns, ps, onEdit = { edit = it })
                3 -> StatsTab(cats, txns, ps)
                else -> MoreTab()
            }
            if (tab == 2) ExtendedFloatingActionButton(onClick = { newTxn() }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = Color(0xFFD3E4FB), contentColor = Pal.Ink,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Добавить") })
        }
        // свой нижний бар раздела
        Row(Modifier.fillMaxWidth().background(Color(0xFFE6EEF8)).navigationBarsPadding().padding(vertical = 6.dp)) {
            listOf(
                Triple("Счета", Icons.Rounded.AccountBalanceWallet, 0), Triple("Категории", Icons.Rounded.Category, 1),
                Triple("Операции", Icons.AutoMirrored.Rounded.ReceiptLong, 2), Triple("Статистика", Icons.Rounded.BarChart, 3), Triple("Больше", Icons.Rounded.Apps, 4),
            ).forEach { (t, ic, i) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { tab = i; prefs.edit().putInt("tab", i).apply() }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) Color(0xFFCFE0F5) else Color.Transparent).padding(horizontal = 14.dp, vertical = 3.dp)) {
                        Icon(ic, t, tint = if (tab == i) Color(0xFF2F5C8F) else Pal.Muted, modifier = Modifier.size(22.dp))
                    }
                    Text(t, style = MaterialTheme.typography.labelSmall, color = if (tab == i) Color(0xFF2F5C8F) else Pal.Muted, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
    edit?.let { TxnEditor(it, accounts, cats) { edit = null } }
}

@Composable
private fun PeriodBar(ps: PeriodState) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ ps.shift(-1) }, enabled = ps.period != Period.ALL) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ps.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)); Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(menu, { menu = false }) {
                Period.entries.forEach { p -> DropdownMenuItem({ Text(p.title) }, { ps.period = p; menu = false }) }
                DropdownMenuItem({ Text("Сегодня") }, { ps.anchor = LocalDate.now(); menu = false })
            }
        }
        IconButton({ ps.shift(1) }, enabled = ps.period != Period.ALL) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
    }
}

/** Значок категории в цветном квадрате. */
@Composable
fun CatBadge(c: Category?, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val col = catColor(c)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(col.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(catIcon(c), null, tint = col, modifier = Modifier.size(size * 0.55f))
    }
}

private fun rootOf(id: Long?, byId: Map<Long, Category>): Category? { var c = id?.let { byId[it] }; while (c?.parentId != null) c = byId[c.parentId]; return c }

// ================= Категории (главная раздела) =================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CategoriesTab(cats: List<Category>, txns: List<Txn>, ps: PeriodState, onAdd: (String, Long?) -> Unit) {
    var income by rememberSaveable { mutableStateOf(false) }
    var editCat by remember { mutableStateOf<Category?>(null) }
    var editMode by remember { mutableStateOf(false) }
    val byId = cats.associateBy { it.id }
    val inP = txns.filter { ps.contains(it.at) }
    val exp = inP.filter { it.type == MoneyRepo.EXPENSE }.sumOf { it.amount }
    val inc = inP.filter { it.type == MoneyRepo.INCOME }.sumOf { it.amount }
    val type = if (income) MoneyRepo.INCOME else MoneyRepo.EXPENSE
    val sums = inP.filter { it.type == type }.groupBy { rootOf(it.categoryId, byId)?.id }.mapValues { (_, l) -> l.sumOf { it.amount } }
    val tops = cats.filter { it.parentId == null && it.income == income && !it.archived }
    val total = if (income) inc else exp
    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFE3ECF7)).padding(8.dp)) {
                listOf(false to "Расходы", true to "Доходы").forEach { (inc0, t) ->
                    val sel = income == inc0
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) Color.White else Color.Transparent).clickable { income = inc0 }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t, style = MaterialTheme.typography.bodyLarge, color = if (sel) Pal.Ink else Pal.Muted)
                        Text(fmtMoney(if (inc0) inc else exp), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium), color = if (inc0) GREEN else RED)
                    }
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editMode) "Нажми на категорию, чтобы изменить" else "Нажми на категорию — добавить операцию", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.weight(1f))
                IconButton({ editMode = !editMode }) { Icon(if (editMode) Icons.Rounded.Check else Icons.Rounded.Edit, "Редактировать категории") }
            }
        }
        items(tops, key = { it.id }) { c ->
            val v = sums[c.id] ?: 0.0
            val col = catColor(c)
            val share = if (total > 0) (v / total).toFloat() else 0f
            Box(Modifier.height(78.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFFDCE3EC), RoundedCornerShape(14.dp)).background(Color.White)
                .combinedClickable(onClick = { if (editMode) editCat = c else onAdd(type, c.id) }, onLongClick = { editCat = c })) {
                // заливка снизу — доля категории в сумме периода
                if (share > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(share.coerceIn(0.04f, 1f)).fillMaxHeight(0.5f).background(col.copy(alpha = 0.16f)))
                Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CatBadge(c, 48.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(fmtMoney(v), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = if (v > 0) col else Pal.Muted, maxLines = 1)
                    }
                }
                if (editMode) Icon(Icons.Rounded.Edit, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp), tint = Pal.Muted)
            }
        }
        item {
            Box(Modifier.height(78.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFFDCE3EC), RoundedCornerShape(14.dp)).clickable { editCat = Category(name = "", income = income, tint = tops.size % CAT_COLORS.size) },
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Add, null, tint = Pal.Muted); Text(" Категория", color = Pal.Muted) }
            }
        }
    }
    editCat?.let { CategoryEditor(it, cats) { editCat = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(c: Category, cats: List<Category>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    var name by remember { mutableStateOf(c.name) }
    var tint by remember { mutableIntStateOf(c.tint) }
    var icon by remember { mutableStateOf(c.icon.ifBlank { guessIcon(c.name) }) }
    var sub by remember { mutableStateOf("") }
    val subs = cats.filter { it.parentId == c.id && c.id != 0L }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (c.id == 0L) "Новая категория" else "Категория", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Check, "Сохранить", {
                    if (name.isNotBlank()) AppScope.io.launch { dao.upsertCategory(c.copy(name = name.trim(), tint = tint, icon = icon)) }
                    onDone()
                }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatBadge(c.copy(tint = tint, icon = icon), 56.dp); Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { AppTextField(name, { name = it; if (c.icon.isBlank()) icon = guessIcon(it) }, "Название") }
                }
                SectionLabel("Цвет")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CAT_COLORS.forEachIndexed { i, col -> Box(Modifier.size(if (i == tint) 36.dp else 30.dp).clip(CircleShape).background(col).clickable { tint = i }) }
                }
                SectionLabel("Значок")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CAT_ICONS.forEach { (k, ic) ->
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (k == icon) CAT_COLORS[tint % CAT_COLORS.size].copy(alpha = 0.18f) else Color.White).clickable { icon = k }, contentAlignment = Alignment.Center) {
                            Icon(ic, k, tint = if (k == icon) CAT_COLORS[tint % CAT_COLORS.size] else Pal.Muted)
                        }
                    }
                }
                if (c.id != 0L && c.parentId == null) {
                    SectionLabel("Подкатегории")
                    subs.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${s.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton({ AppScope.io.launch { dao.deleteCategory(s) } }) { Icon(Icons.Rounded.Close, "Удалить", tint = Pal.Muted) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AppTextField(sub, { sub = it }, "Новая подкатегория") }
                        IconButton({ if (sub.isNotBlank()) { val n = sub.trim(); AppScope.io.launch { dao.upsertCategory(Category(name = n, income = c.income, parentId = c.id, tint = tint)) }; sub = "" } }) { Icon(Icons.Rounded.Add, "Добавить") }
                    }
                }
                if (c.id != 0L) Row {
                    TextButton({ AppScope.io.launch { dao.upsertCategory(c.copy(archived = true)) }; onDone() }) { Text("Скрыть") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ AppScope.io.launch { dao.deleteCategory(c) }; onDone() }) { Text("Удалить (операции останутся)", color = Pal.RoseInk) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ================= Операции =================

@Composable
private fun OperationsTab(accounts: List<Account>, cats: List<Category>, txns: List<Txn>, ps: PeriodState, onEdit: (Txn) -> Unit) {
    val z = ZoneId.systemDefault()
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val (from, to) = ps.range
    val startMs = from.atStartOfDay(z).toInstant().toEpochMilli()
    val endMs = to.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    val active = accounts.filter { !it.archived && it.currency == "UAH" }
    val startBal = active.sumOf { a -> MoneyRepo.balance(a, txns.filter { it.at < startMs }) }
    val endBal = active.sumOf { a -> MoneyRepo.balance(a, txns.filter { it.at < endMs }) }
    val byId = cats.associateBy { it.id }
    val list = txns.filter { ps.contains(it.at) && (q.isBlank() || it.comment.contains(q, true) || it.tags.contains(q, true) || byId[it.categoryId]?.name?.contains(q, true) == true) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.5.dp, Color(0xFFDCE3EC), RoundedCornerShape(16.dp)).padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Начальный баланс", style = MaterialTheme.typography.bodySmall, color = Pal.Ink); Text(fmtMoney(startBal), style = MaterialTheme.typography.titleMedium, color = if (startBal >= 0) GREEN else RED) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Конечный баланс", style = MaterialTheme.typography.bodySmall, color = Pal.Ink); Text(fmtMoney(endBal), style = MaterialTheme.typography.titleMedium, color = if (endBal >= 0) GREEN else RED) }
            }
        }
        item {
            if (searching) Box(Modifier.padding(top = 8.dp)) { AppTextField(q, { q = it }, "Поиск: комментарий, тег, категория") }
            else TextButton({ searching = true }) { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Text(" Поиск") }
        }
        if (list.isEmpty()) item { EmptyHint("В этом периоде операций нет") }
        list.groupBy { Instant.ofEpochMilli(it.at).atZone(z).toLocalDate() }.forEach { (d, l) ->
            item(key = "d$d") {
                val sum = l.sumOf { when (it.type) { MoneyRepo.INCOME -> it.amount; MoneyRepo.EXPENSE -> -it.amount; else -> 0.0 } }
                Column(Modifier.padding(top = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Medium))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.format(DateTimeFormatter.ofPattern("EEEE", RU)), style = MaterialTheme.typography.bodyMedium)
                            Text(d.format(DateTimeFormatter.ofPattern("MMMM yyyy", RU)), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text((if (sum > 0) "+" else "") + fmtMoney(sum), style = MaterialTheme.typography.titleMedium, color = if (sum >= 0) GREEN else RED)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = Color(0xFFDCE3EC))
                }
            }
            items(l, key = { it.id }) { t -> TxnRow(t, accounts, cats) { onEdit(t) } }
        }
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
        else -> cat?.let { if (parent != null) "${parent.name} (${it.name})" else it.name } ?: t.comment.ifBlank { "Без категории" }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.type == MoneyRepo.TRANSFER) Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Line), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SwapHoriz, null, tint = Pal.Muted) }
        else CatBadge(parent ?: cat, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CreditCard, null, Modifier.size(18.dp), tint = Pal.Ink)
                Text(" " + listOfNotNull(acc?.name, t.comment.takeIf { it.isNotBlank() && cat != null }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        Text(fmtMoney(abs(t.amount), acc?.currency ?: "UAH"), style = MaterialTheme.typography.titleMedium,
            color = when (t.type) { MoneyRepo.INCOME -> GREEN; MoneyRepo.EXPENSE -> RED; else -> Pal.Muted })
    }
}

// ================= Статистика =================

@Composable
private fun StatsTab(cats: List<Category>, txns: List<Txn>, ps: PeriodState) {
    var income by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    val byId = cats.associateBy { it.id }
    val type = if (income) MoneyRepo.INCOME else MoneyRepo.EXPENSE
    val inP = txns.filter { ps.contains(it.at) && it.type == type }
    val total = inP.sumOf { it.amount }
    val sums = inP.groupBy { rootOf(it.categoryId, byId) }.map { (c, l) -> c to l.sumOf { it.amount } }.sortedByDescending { it.second }
    val zero = cats.filter { it.parentId == null && it.income == income && !it.archived && sums.none { s -> s.first?.id == it.id } }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Расходы", !income, { income = false; selected = null }); Chip("Доходы", income, { income = true; selected = null })
            }
        }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, Color(0xFFDCE3EC), RoundedCornerShape(18.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val stroke = size.minDimension * 0.095f
                    val d = size.minDimension - stroke
                    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                    if (total <= 0) drawArc(Color(0xFFE3E8EF), 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                    var start = -90f
                    val gap = if (sums.size > 1) 1.6f else 0f
                    sums.forEach { (c, v) ->
                        val sweep = (v / total * 360).toFloat()
                        val dim = selected != null && c?.id != selected
                        drawArc(catColor(c).copy(alpha = if (dim) 0.25f else 1f), start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(if (c?.id == selected) stroke * 1.25f else stroke, cap = StrokeCap.Butt))
                        start += sweep
                    }
                }
                val sc = sums.firstOrNull { it.first?.id == selected }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { selected = null }) {
                    if (sc != null) {
                        CatBadge(sc.first, 56.dp)
                        Text(sc.first?.name ?: "Без категории", style = MaterialTheme.typography.titleMedium)
                        Text(fmtMoney(sc.second), style = MaterialTheme.typography.titleLarge, color = catColor(sc.first))
                        Text("${(sc.second / total * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    } else {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEDEFF3)), contentAlignment = Alignment.Center) { Text("$", fontSize = 28.sp, color = Pal.Muted) }
                        Text(if (total > 0) fmtMoney(total) else "Нет операций", style = MaterialTheme.typography.titleMedium, color = Pal.Muted)
                        Text("Не выбрано", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, Color(0xFFDCE3EC), RoundedCornerShape(18.dp)).padding(vertical = 8.dp)) {
                (sums + zero.map { it to 0.0 }).forEach { (c, v) ->
                    val share = if (total > 0) (v / total).toFloat() else 0f
                    val col = catColor(c)
                    Row(Modifier.fillMaxWidth().clickable { selected = if (selected == c?.id) null else c?.id }.background(if (selected != null && selected == c?.id) col.copy(alpha = 0.06f) else Color.Transparent).padding(16.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CatBadge(c, 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row { Text(c?.name ?: "Без категории", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1); Text(fmtMoney(v), style = MaterialTheme.typography.bodyLarge) }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Canvas(Modifier.weight(1f).height(10.dp)) {
                                    val h = size.height; val w = size.width
                                    val filled = (w * share).coerceAtLeast(if (share > 0) h * 2 else 0f)
                                    if (filled > 0) drawRoundRect(col, Offset.Zero, Size(filled, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                                    val rest = w - filled - (if (filled > 0) h else 0f)
                                    if (rest > 0) {
                                        drawRoundRect(Color(0xFFD6E2F2), Offset(w - rest, 0f), Size(rest, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                                        drawCircle(col, h * 0.22f, Offset(w - h / 2, h / 2))
                                    }
                                }
                                Text("${(share * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= Счета =================

@Composable
private fun AccountsTab(accounts: List<Account>, txns: List<Txn>, hide: Boolean) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    var edit by remember { mutableStateOf<Account?>(null) }
    var transfer by remember { mutableStateOf<Txn?>(null) }
    val cats by dao.categories().collectAsState(initial = emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(accounts, key = { it.id }) { a ->
            val b = MoneyRepo.balance(a, txns)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0xFFDCE3EC), RoundedCornerShape(16.dp)).background(Color.White).clickable { edit = a }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(CAT_COLORS[a.tint % CAT_COLORS.size].copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(if (a.name.contains("налич", true) || a.name.contains("готів", true)) Icons.Rounded.Payments else Icons.Rounded.CreditCard, null, tint = CAT_COLORS[a.tint % CAT_COLORS.size])
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name + if (a.archived) " (архив)" else "", style = MaterialTheme.typography.bodyLarge)
                    Text("${txns.count { it.accountId == a.id || it.toAccountId == a.id }} операций", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (hide) "•••" else fmtMoney(b, a.currency), style = MaterialTheme.typography.titleMedium, color = if (b >= 0) GREEN else RED)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("+ Счёт", { edit = Account(name = "", tint = accounts.size % CAT_COLORS.size) }, Modifier.weight(1f), bg = Pal.Card, fg = Pal.Ink)
                PillButton("Перевод", { transfer = Txn(at = System.currentTimeMillis(), type = MoneyRepo.TRANSFER, amount = 0.0, accountId = accounts.firstOrNull()?.id ?: 0) }, Modifier.weight(1f), enabled = accounts.size >= 2)
            }
        }
    }
    transfer?.let { TxnEditor(it, accounts, cats) { transfer = null } }
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
                    if (a.id != 0L) TextButton({ AppScope.io.launch { dao.deleteAccount(a) }; edit = null }) { Text("Удалить счёт", color = Pal.RoseInk) }
                }
            },
            confirmButton = { TextButton({ if (name.isNotBlank()) AppScope.io.launch { dao.upsertAccount(a.copy(name = name.trim(), currency = cur, initial = initial.replace(',', '.').toDoubleOrNull() ?: 0.0, archived = archived)) }; edit = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ edit = null }) { Text("Отмена") } })
    }
}

// ================= Больше =================

@Composable
private fun MoreTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).money() }
    val cats by dao.categories().collectAsState(initial = emptyList())
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
    var showHidden by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Импорт из Sumlio")
                Text("Повторы пропускаются, можно загружать файл заново.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PillButton("Выбрать файл .csv", { picker.launch(arrayOf("text/*", "*/*")) }, Modifier.fillMaxWidth(), enabled = !busy)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), onClick = { showHidden = !showHidden }) {
                SectionLabel("Скрытые категории · ${cats.count { it.archived }}")
                if (showHidden) cats.filter { it.archived }.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        CatBadge(c, 32.dp); Text("  ${c.name}", modifier = Modifier.weight(1f))
                        TextButton({ AppScope.io.launch { dao.upsertCategory(c.copy(archived = false)) } }) { Text("Вернуть") }
                    }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac) {
                Text("Расходы и доходы вводятся вручную: «Категории» → нажми на нужную плитку, или «Операции» → «Добавить». Долгое нажатие на плитку — изменить категорию.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ================= Редактор операции =================

/** Полноэкранное добавление/изменение операции. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (t.id == 0L) runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (t.id == 0L) "Новая операция" else "Операция", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (t.id != 0L) IconButton({ AppScope.io.launch { AppDb.get(ctx).money().deleteTxn(t) }; onDone() }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Pal.RoseInk) }
            }
            Segmented(listOf("Расход", "Доход", "Перевод"), listOf(MoneyRepo.EXPENSE, MoneyRepo.INCOME, MoneyRepo.TRANSFER).indexOf(type).coerceAtLeast(0),
                { val nt = listOf(MoneyRepo.EXPENSE, MoneyRepo.INCOME, MoneyRepo.TRANSFER)[it]; if (nt != type) { type = nt; cat = null } }, track = Pal.Sky)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth().focusRequester(fr), label = { Text("Сумма, грн") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.displayMedium.copy(color = if (type == MoneyRepo.INCOME) GREEN else if (type == MoneyRepo.EXPENSE) RED else Pal.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
                if (type != MoneyRepo.TRANSFER) {
                    SectionLabel("Категория")
                    val tops = cats.filter { it.parentId == null && it.income == (type == MoneyRepo.INCOME) && !it.archived }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
                        tops.forEach { c ->
                            val sel = cat == c.id || cats.firstOrNull { it.id == cat }?.parentId == c.id
                            Column(Modifier.width(78.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) catColor(c).copy(alpha = 0.14f) else Color.Transparent).clickable { cat = c.id }.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CatBadge(c, 44.dp)
                                Text(c.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center, color = if (sel) catColor(c) else Pal.Ink)
                            }
                        }
                    }
                    val parent = cat?.let { id -> cats.firstOrNull { it.id == id }?.let { it.parentId ?: it.id } }
                    val subs = cats.filter { it.parentId == parent && parent != null }
                    if (subs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { subs.forEach { s -> Chip(s.name, cat == s.id, { cat = if (cat == s.id) parent else s.id }) } }
                }
                SectionLabel(if (type == MoneyRepo.TRANSFER) "Откуда" else "Счёт")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { accounts.filter { !it.archived }.forEach { a -> Chip(a.name, acc == a.id, { acc = a.id }) } }
                if (type == MoneyRepo.TRANSFER) {
                    SectionLabel("Куда")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { accounts.filter { !it.archived && it.id != acc }.forEach { a -> Chip(a.name, toAcc == a.id, { toAcc = a.id }) } }
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
