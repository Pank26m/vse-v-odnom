package com.pank.vsevodnom.ui.money

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
private val RED = Color(0xFFE5485D)
private val GREEN = Color(0xFF2DB37A)
private val zone: ZoneId get() = ZoneId.systemDefault()

/** «22 849,61 грн» — пробел между тысячами, копейки только если есть. */
fun fmtMoney(v: Double, cur: String = "UAH"): String {
    val r = (v * 100).roundToLong() / 100.0
    val whole = abs(r).toLong()
    val frac = ((abs(r) - whole) * 100).roundToLong()
    val s = whole.toString().reversed().chunked(3).joinToString(" ").reversed() + if (frac > 0) (if (frac % 10 == 0L) ",${frac / 10}" else ",%02d".format(frac)) else ""
    return (if (r < 0) "−" else "") + s + " " + (mapOf("UAH" to "грн", "USD" to "$", "EUR" to "€")[cur] ?: cur)
}

private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
private fun rootOf(id: Long?, byId: Map<Long, Category>): Category? { var c = id?.let { byId[it] }; var g = 0; while (c?.parentId != null && g++ < 5) c = byId[c.parentId]; return c }

/** Сумма для общего баланса: один кошелёк, переводы между старыми счетами в итоге не меняют сумму. */
private fun delta(t: Txn) = when (t.type) { MoneyRepo.INCOME, MoneyRepo.ADJUST -> t.amount; MoneyRepo.EXPENSE -> -t.amount; else -> (t.toAmount ?: t.amount) - t.amount }

/**
 * Финансы — свой учёт расходов: одна «копилка» (все деньги вместе), сверху — сколько свободно.
 * Вкладки: Категории (сразу ввод по плитке), Операции (по дням), Статистика (круг, по дням, средние, бюджеты).
 */
@Composable
fun MoneyScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    LaunchedEffect(Unit) { MoneyRepo.ensureDefaults(ctx); TablerIcons.load(ctx) }
    val accounts by dao.accounts().collectAsState(initial = emptyList())
    val cats by dao.categories().collectAsState(initial = emptyList())
    val all by dao.allTxns().collectAsState(initial = emptyList())
    val prefs = remember { ctx.getSharedPreferences("money", android.content.Context.MODE_PRIVATE) }
    var hide by remember { mutableStateOf(prefs.getBoolean("hide", false)) }
    var tab by remember { mutableIntStateOf(0) }
    var income by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var edit by remember { mutableStateOf<Txn?>(null) }
    var editCat by remember { mutableStateOf<Category?>(null) }
    var menu by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    val total = accounts.sumOf { it.initial } + all.sumOf { delta(it) }
    val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val inMonth = all.filter { it.at in from until to }
    val spent = inMonth.filter { it.type == MoneyRepo.EXPENSE }.sumOf { it.amount }
    val earned = inMonth.filter { it.type == MoneyRepo.INCOME }.sumOf { it.amount }
    val byId = cats.associateBy { it.id }
    val wallet = accounts.firstOrNull { !it.archived } ?: accounts.firstOrNull()

    fun newTxn(c: Category?, inc: Boolean): Txn {
        val now = LocalDate.now()
        val at = if (YearMonth.from(now) == month) System.currentTimeMillis() else month.atDay(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return Txn(at = at, type = if (inc) MoneyRepo.INCOME else MoneyRepo.EXPENSE, amount = 0.0, accountId = wallet?.id ?: 0, categoryId = c?.id)
    }

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Финансы", onBack, actions = {
            Box {
                RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Новая категория") }, { menu = false; editCat = Category(name = "", income = income, color = PALETTE[5][3].toArgb()) })
                    DropdownMenuItem({ Text("Импорт из Sumlio") }, { menu = false; importOpen = true })
                    DropdownMenuItem({ Text(if (hide) "Показывать сумму" else "Скрывать сумму") }, { menu = false; hide = !hide; prefs.edit().putBoolean("hide", hide).apply() })
                }
            }
        }) {
            // свободные деньги
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { hide = !hide; prefs.edit().putBoolean("hide", hide).apply() }) {
                    Text("Свободные деньги", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                    Icon(if (hide) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.padding(start = 6.dp).size(16.dp), tint = Pal.Muted)
                }
                Text(if (hide) "••• ••• грн" else fmtMoney(total), style = MaterialTheme.typography.headlineLarge, color = if (total < 0) RED else Pal.Ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
                    Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { month = YearMonth.now() }.padding(horizontal = 8.dp))
                    IconButton({ month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
                }
                Segmented(listOf("Категории", "Операции", "Статистика"), tab, { tab = it }, Modifier.fillMaxWidth(), track = Pal.Mint)
                Spacer(Modifier.height(10.dp))
            }
            when (tab) {
                0 -> CategoriesTab(cats, inMonth, byId, income, { income = it }, spent, earned, onAdd = { c -> edit = newTxn(c, c.income) }, onEditCat = { editCat = it },
                    onNewCat = { editCat = Category(name = "", income = income, color = PALETTE[5][3].toArgb()) })
                1 -> OperationsTab(all, inMonth, from, accounts, byId, onEdit = { edit = it })
                else -> StatsTab(cats, inMonth, byId, month, income, { income = it }, spent, earned)
            }
        }
        // добавить — квадратная кнопка внизу справа
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = newTxn(cats.firstOrNull { it.income == income && it.parentId == null && !it.archived }, income) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить", tint = Color.White)
        }
    }
    edit?.let { t -> TxnSheet(t, cats, wallet) { edit = null } }
    editCat?.let { c -> CategoryEditor(c, cats, all) { editCat = null } }
    if (importOpen) ImportDialog { importOpen = false }
}


@Composable
private fun TypeSwitch(income: Boolean, onChange: (Boolean) -> Unit, spent: Double, earned: Double) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Line).padding(5.dp)) {
        listOf(false to ("Расходы" to spent), true to ("Доходы" to earned)).forEach { (inc, p) ->
            val sel = income == inc
            val bg by animateColorAsState(if (sel) Pal.Card else Color.Transparent, label = "t")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).clickable { onChange(inc) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.first, style = MaterialTheme.typography.labelLarge, color = if (sel) Pal.Ink else Pal.Muted)
                Text(fmtMoney(p.second), style = MaterialTheme.typography.titleLarge, color = if (inc) GREEN else RED)
            }
        }
    }
}

// ================= Категории =================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoriesTab(cats: List<Category>, txns: List<Txn>, byId: Map<Long, Category>, income: Boolean, onIncome: (Boolean) -> Unit, spent: Double, earned: Double,
                          onAdd: (Category) -> Unit, onEditCat: (Category) -> Unit, onNewCat: () -> Unit) {
    val roots = cats.filter { it.parentId == null && it.income == income && !it.archived }.sortedWith(compareBy({ it.sort }, { it.name.lowercase() }))
    val sums = txns.filter { it.type == if (income) MoneyRepo.INCOME else MoneyRepo.EXPENSE }.groupBy { rootOf(it.categoryId, byId)?.id }.mapValues { (_, l) -> l.sumOf { it.amount } }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TypeSwitch(income, onIncome, spent, earned) }
        items(roots.chunked(2), key = { r -> r.joinToString { "${it.id}" } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val sum = sums[c.id] ?: 0.0
                    val col = finColor(c)
                    val over = c.budget != null && sum > c.budget
                    val part = c.budget?.takeIf { it > 0 }?.let { (sum / it).toFloat().coerceIn(0f, 1f) }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Card)
                        .border(if (over) 1.5.dp else 1.dp, if (over) RED else Pal.Line, RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { onAdd(c) }, onLongClick = { onEditCat(c) })) {
                        // заполнение бюджета — подложкой снизу вверх
                        if (part != null) Box(Modifier.matchParentSize().padding(top = (64 * (1 - part)).dp).background((if (over) RED else col).copy(alpha = 0.12f)))
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            FinBadge(c, 42.dp)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(fmtMoney(sum), style = MaterialTheme.typography.titleSmall, color = if (sum > 0) col else Pal.Muted, maxLines = 1)
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Pal.Line, RoundedCornerShape(16.dp)).clickable(onClick = onNewCat).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Add, null, tint = Pal.Muted); Text(" Категория", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
            }
        }
        item { Text("Нажми на плитку — записать ${if (income) "доход" else "расход"}. Долгое нажатие — изменить категорию, иконку, цвет и бюджет.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted) }
    }
}

// ================= Операции =================

@Composable
private fun OperationsTab(all: List<Txn>, txns: List<Txn>, from: Long, accounts: List<Account>, byId: Map<Long, Category>, onEdit: (Txn) -> Unit) {
    val start = accounts.sumOf { it.initial } + all.filter { it.at < from }.sumOf { delta(it) }
    val end = start + txns.sumOf { delta(it) }
    val days = txns.groupBy { dayOf(it.at) }.toSortedMap(compareByDescending { it })
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Pal.Line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Начало месяца", style = MaterialTheme.typography.labelMedium, color = Pal.Muted); Text(fmtMoney(start), style = MaterialTheme.typography.titleMedium, color = if (start < 0) RED else GREEN) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Конец месяца", style = MaterialTheme.typography.labelMedium, color = Pal.Muted); Text(fmtMoney(end), style = MaterialTheme.typography.titleMedium, color = if (end < 0) RED else GREEN) }
            }
        }
        if (days.isEmpty()) item { EmptyHint("В этом месяце операций нет. Нажми «+» или плитку категории.") }
        days.forEach { (d, list) ->
            item(key = "d$d") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, RU), style = MaterialTheme.typography.labelLarge)
                        Text(d.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                    val s = list.sumOf { delta(it) }
                    Text((if (s > 0) "+" else "") + fmtMoney(s), style = MaterialTheme.typography.titleSmall, color = if (s < 0) RED else if (s > 0) GREEN else Pal.Muted)
                }
                HorizontalDivider(color = Pal.Line)
            }
            items(list.sortedByDescending { it.at }, key = { it.id }) { t ->
                val c = t.categoryId?.let { byId[it] }
                val root = rootOf(t.categoryId, byId)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onEdit(t) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.type == MoneyRepo.TRANSFER || t.type == MoneyRepo.ADJUST) Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Pal.Line), contentAlignment = Alignment.Center) {
                        Icon(if (t.type == MoneyRepo.TRANSFER) Icons.Rounded.SwapHoriz else Icons.Rounded.Tune, null, tint = Pal.Muted)
                    } else FinBadge(root, 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(when (t.type) { MoneyRepo.TRANSFER -> "Перевод"; MoneyRepo.ADJUST -> "Корректировка"; else -> (root?.name ?: "Без категории") + if (c != null && c.parentId != null) " (${c.name})" else "" },
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        val sub = listOfNotNull(t.comment.ifBlank { null }, t.tags.takeIf { it.isNotBlank() }?.split(',')?.joinToString(" ") { "#${it.trim()}" }).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                    }
                    if (t.photo.isNotBlank()) Icon(Icons.Rounded.AttachFile, null, Modifier.size(16.dp), tint = Pal.Muted)
                    Text(fmtMoney(abs(delta(t)).let { if (t.type == MoneyRepo.TRANSFER) t.amount else it }), style = MaterialTheme.typography.titleSmall,
                        color = when (t.type) { MoneyRepo.INCOME -> GREEN; MoneyRepo.EXPENSE -> RED; else -> Pal.Muted })
                }
            }
        }
    }
}

// ================= Статистика =================

@Composable
private fun StatsTab(cats: List<Category>, txns: List<Txn>, byId: Map<Long, Category>, month: YearMonth, income: Boolean, onIncome: (Boolean) -> Unit, spent: Double, earned: Double) {
    val type = if (income) MoneyRepo.INCOME else MoneyRepo.EXPENSE
    val list = txns.filter { it.type == type }
    val byRoot = list.groupBy { rootOf(it.categoryId, byId) }.mapValues { (_, l) -> l.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val sum = byRoot.sumOf { it.value }
    var sel by remember(income, month) { mutableStateOf<Category?>(null) }
    val today = LocalDate.now()
    val daysPassed = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TypeSwitch(income, onIncome, spent, earned) }
        item {
            val flow = earned - spent
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.5.dp, if (flow < 0) RED else GREEN, RoundedCornerShape(16.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Денежный поток", style = MaterialTheme.typography.labelLarge)
                Text((if (flow > 0) "+" else "") + fmtMoney(flow), style = MaterialTheme.typography.titleLarge, color = if (flow < 0) RED else GREEN)
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
                        val st = size.minDimension * 0.12f
                        val r = size.minDimension / 2 - st / 2
                        if (sum <= 0) drawCircle(Pal.Line, r, style = Stroke(st))
                        var a = -90f
                        byRoot.forEach { (c, v) ->
                            val sw = (360 * v / sum).toFloat()
                            val chosen = sel == null || sel?.id == c?.id
                            drawArc(finColor(c).copy(alpha = if (chosen) 1f else 0.3f), a + 1f, (sw - 2f).coerceAtLeast(0.5f), false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(if (sel?.id == c?.id && sel != null) st * 1.3f else st))
                            a += sw
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val c = sel
                        if (c != null) { FinBadge(c, 52.dp); Text(c.name, style = MaterialTheme.typography.titleSmall); Text(fmtMoney(byRoot.firstOrNull { it.key?.id == c.id }?.value ?: 0.0), style = MaterialTheme.typography.titleMedium, color = finColor(c)) }
                        else { Text(if (income) "Доходы" else "Расходы", style = MaterialTheme.typography.labelLarge, color = Pal.Muted); Text(fmtMoney(sum), style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                if (byRoot.isEmpty()) Text("За этот месяц пусто", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                byRoot.forEach { (c, v) ->
                    val pct = if (sum > 0) v / sum else 0.0
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { sel = if (sel?.id == c?.id) null else c }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FinBadge(c, 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row { Text(c?.name ?: "Без категории", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(fmtMoney(v), style = MaterialTheme.typography.bodyMedium) }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Pal.Line)) {
                                    Box(Modifier.fillMaxWidth(pct.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(finColor(c)))
                                }
                                Text("  ${(pct * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
        item {
            // по дням месяца
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("По дням")
                val perDay = (1..month.lengthOfMonth()).map { d -> list.filter { dayOf(it.at).dayOfMonth == d }.sumOf { it.amount } }
                val max = perDay.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
                    val bw = size.width / perDay.size
                    for (k in 0..4) { val y = size.height * k / 4f; drawLine(Pal.Line, Offset(0f, y), Offset(size.width, y), 1f) }
                    perDay.forEachIndexed { i, v ->
                        if (v <= 0) return@forEachIndexed
                        val h = (v / max * size.height).toFloat()
                        drawRoundRect(if (income) GREEN else RED, Offset(i * bw + bw * 0.18f, size.height - h), Size(bw * 0.64f, h), androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
                    }
                }
                Row(Modifier.fillMaxWidth()) { listOf("1", "10", "20", "${month.lengthOfMonth()}").forEachIndexed { i, t -> Text(t, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = if (i < 3) Modifier.weight(1f) else Modifier) } }
                Text("Самый большой день: ${fmtMoney(perDay.maxOrNull() ?: 0.0)}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Средние значения · за $daysPassed дн.")
                Row(Modifier.padding(top = 6.dp)) { Text("День", modifier = Modifier.weight(1f)); Text(fmtMoney(sum / daysPassed.coerceAtLeast(1))) }
                Row(Modifier.padding(top = 4.dp)) { Text("Неделя", modifier = Modifier.weight(1f)); Text(fmtMoney(sum / daysPassed.coerceAtLeast(1) * 7)) }
                if (!income && YearMonth.from(today) == month) Row(Modifier.padding(top = 4.dp)) { Text("Прогноз на месяц", modifier = Modifier.weight(1f)); Text(fmtMoney(sum / daysPassed.coerceAtLeast(1) * month.lengthOfMonth())) }
            }
        }
        val budgets = cats.filter { it.parentId == null && it.budget != null && !it.archived && !it.income }
        if (!income && budgets.isNotEmpty()) {
            item {
                val bs = budgets.sumOf { it.budget ?: 0.0 }; val used = budgets.sumOf { b -> byRoot.firstOrNull { it.key?.id == b.id }?.value ?: 0.0 }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Бюджеты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) { Text("${fmtMoney(used)} / ${fmtMoney(bs)}", style = MaterialTheme.typography.labelMedium); Text(fmtMoney(bs - used), color = if (bs - used < 0) RED else GREEN, style = MaterialTheme.typography.titleSmall) }
                }
            }
            items(budgets, key = { "b${it.id}" }) { b ->
                val used = byRoot.firstOrNull { it.key?.id == b.id }?.value ?: 0.0; val lim = b.budget ?: 1.0; val over = used > lim
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.5.dp, if (over) RED else Pal.Line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FinBadge(b, 34.dp); Text("  ${b.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${fmtMoney(used)} / ${fmtMoney(lim)}", style = MaterialTheme.typography.labelMedium)
                    }
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Pal.Line)) {
                        Box(Modifier.fillMaxWidth((used / lim).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (over) RED else GREEN))
                    }
                    Text(if (over) "перерасход ${fmtMoney(used - lim)}" else "осталось ${fmtMoney(lim - used)}", style = MaterialTheme.typography.titleSmall, color = if (over) RED else GREEN, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                }
            }
        }
    }
}

// ================= Ввод операции =================

/** Нижняя панель ввода: дата, категория и подкатегории, сумма калькулятором, комментарий, теги, фото. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxnSheet(t0: Txn, cats: List<Category>, wallet: Account?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    val byId = cats.associateBy { it.id }
    var at by remember { mutableLongStateOf(t0.at) }
    var catId by remember { mutableStateOf(t0.categoryId) }
    val root = rootOf(catId, byId)
    val income = root?.income ?: (t0.type == MoneyRepo.INCOME)
    var expr by remember { mutableStateOf(if (t0.amount > 0) fmtMoney(t0.amount).substringBeforeLast(' ').replace(" ", "") else "") }
    var comment by remember { mutableStateOf(t0.comment) }
    var tags by remember { mutableStateOf(t0.tags) }
    var photo by remember { mutableStateOf(t0.photo) }
    var field by remember { mutableStateOf<String?>(null) }   // comment | tags
    var pickCat by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var newSub by remember { mutableStateOf<String?>(null) }
    val photoPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { copyIntoApp(ctx, it, "money")?.let { p -> photo = p } } }
    val value = remember(expr) { runCatching { com.pank.vsevodnom.ui.calc.Expr.eval(expr.ifBlank { "0" }.replace('*', '×').replace('/', '÷').replace('-', '−')) }.getOrNull() }
    val col = finColor(root)
    fun save(dup: Boolean = false) {
        val v = value ?: return
        if (v <= 0) { onDone(); return }
        val t = t0.copy(id = if (dup) 0 else t0.id, at = at, amount = v, categoryId = catId, type = if (t0.type == MoneyRepo.TRANSFER || t0.type == MoneyRepo.ADJUST) t0.type else if (income) MoneyRepo.INCOME else MoneyRepo.EXPENSE,
            accountId = if (t0.accountId == 0L) wallet?.id ?: 0 else t0.accountId, comment = comment.trim(), tags = tags.split(',', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.joinToString(","), photo = photo, source = t0.source.ifBlank { "manual" })
        AppScope.io.launch { if (t.accountId == 0L) { val id = dao.upsertAccount(Account(name = "Счёт")); dao.upsertTxn(t.copy(accountId = id)) } else dao.upsertTxn(t) }
        onDone()
    }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDone)) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Pal.Bg).clickable(enabled = false) {}.dialogBars().padding(horizontal = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Line))
                // дата
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ at -= 86_400_000L }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Вчера") }
                    Row(Modifier.weight(1f).clickable { pickDate = true }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp), tint = Pal.Muted)
                        val d = dayOf(at)
                        Text("  " + (if (d == LocalDate.now()) "Сегодня, " else if (d == LocalDate.now().minusDays(1)) "Вчера, " else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + ", ") + d.format(DateTimeFormatter.ofPattern("d MMMM", RU)), style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton({ at += 86_400_000L }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Завтра") }
                }
                // счёт и категория
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Card).border(1.dp, Pal.Line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Text("Копилка", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.AccountBalanceWallet, null, tint = Pal.SkyInk, modifier = Modifier.size(20.dp)); Text("  Все деньги", style = MaterialTheme.typography.titleSmall) }
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = 0.1f)).border(1.5.dp, col, RoundedCornerShape(16.dp)).clickable { pickCat = true }.padding(12.dp)) {
                        Text(if (income) "Доход" else "Категория", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(finIcon(root), null, tint = col, modifier = Modifier.size(20.dp)); Text("  ${root?.name ?: "Выбрать"}", style = MaterialTheme.typography.titleSmall, maxLines = 1) }
                    }
                }
                // подкатегории
                if (root != null) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.filter { it.parentId == root.id && !it.archived }.forEach { s ->
                        val on = catId == s.id
                        Text(s.name, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else col,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) col else Color.Transparent).border(1.5.dp, col, RoundedCornerShape(20.dp)).clickable { catId = if (on) root.id else s.id }.padding(14.dp, 8.dp))
                    }
                    Text("+", style = MaterialTheme.typography.labelLarge, color = col, modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.5.dp, col.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).clickable { newSub = "" }.padding(14.dp, 8.dp))
                }
                // сумма
                Text((expr.ifBlank { "0" }) + " грн", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 1,
                    color = if (income) GREEN else Pal.Ink)
                if (value != null && expr.any { it in "+−×÷" }) Text("= ${fmtMoney(value)}", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                when (field) {
                    "comment" -> AppTextField(comment, { comment = it }, "Комментарий")
                    "tags" -> AppTextField(tags, { tags = it }, "Теги через запятую")
                }
                // вложение, комментарий, теги
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton({ photoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Rounded.AttachFile, "Фото чека", tint = if (photo.isNotBlank()) col else Pal.Ink) }
                    IconButton({ field = if (field == "comment") null else "comment" }) { Icon(Icons.Rounded.ChatBubbleOutline, "Комментарий", tint = if (comment.isNotBlank()) col else Pal.Ink) }
                    IconButton({ field = if (field == "tags") null else "tags" }) { Icon(Icons.Rounded.Sell, "Теги", tint = if (tags.isNotBlank()) col else Pal.Ink) }
                }
                if (photo.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { AppImage(photo, Modifier.size(56.dp), maxPx = 200); TextButton({ photo = "" }) { Text("Убрать фото") } }
                // калькулятор
                val keys = listOf(listOf("÷", "7", "8", "9", "⌫"), listOf("×", "4", "5", "6", "C"), listOf("−", "1", "2", "3", "✓"), listOf("+", "грн", "0", ",", "✓"))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    keys.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEachIndexed { i, k ->
                                if (k == "✓" && r == 3) return@forEachIndexed
                                val okKey = k == "✓"
                                Box(Modifier.weight(1f).height(if (okKey) 118.dp else 56.dp).clip(RoundedCornerShape(16.dp)).background(if (okKey) col.copy(alpha = 0.25f) else if (i == 0) Pal.Line else Pal.Card)
                                    .clickable {
                                        when (k) {
                                            "⌫" -> expr = expr.dropLast(1); "C" -> expr = ""; "✓" -> save(); "грн" -> {}
                                            "," -> if (!expr.takeLastWhile { it.isDigit() || it == ',' }.contains(',')) expr += ","
                                            "+", "−", "×", "÷" -> if (expr.isNotEmpty() && expr.last() !in "+−×÷") expr += k
                                            else -> if (expr.length < 16) expr += k
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    when (k) {
                                        "⌫" -> Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть")
                                        "✓" -> Icon(Icons.Rounded.Check, "Готово", tint = col, modifier = Modifier.size(30.dp))
                                        else -> Text(k, fontSize = if (k.length > 1) 16.sp else 24.sp, color = Pal.Ink)
                                    }
                                }
                            }
                        }
                    }
                }
                if (t0.id != 0L) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PillButton("Удалить", { AppScope.io.launch { dao.deleteTxn(t0) }; onDone() }, bg = Pal.Rose, fg = Pal.RoseInk)
                    PillButton("Дублировать", { save(dup = true) }, bg = Pal.Card, fg = Pal.Ink)
                }
            }
        }
    }
    if (pickCat) AlertDialog(onDismissRequest = { pickCat = false }, confirmButton = { TextButton({ pickCat = false }) { Text("Закрыть") } }, title = { Text("Категория") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(false to "Расходы", true to "Доходы").forEach { (inc, title) ->
                    Text(title, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(vertical = 6.dp))
                    cats.filter { it.parentId == null && it.income == inc && !it.archived }.sortedBy { it.sort }.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            row.forEach { c ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (root?.id == c.id) finColor(c).copy(alpha = 0.15f) else Color.Transparent).clickable { catId = c.id; pickCat = false }.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) { FinBadge(c, 40.dp); Text(c.name, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        })
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = dayOf(at).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); val tm = Instant.ofEpochMilli(at).atZone(zone).toLocalTime(); at = d.atTime(tm).atZone(zone).toInstant().toEpochMilli() }; pickDate = false }) { Text("Ок") }
        }) { DatePicker(dp, showModeToggle = false) }
    }
    newSub?.let { s0 ->
        var s by remember { mutableStateOf(s0) }
        AlertDialog(onDismissRequest = { newSub = null }, title = { Text("Подкатегория") }, text = { AppTextField(s, { s = it }, "Например: Такси") },
            confirmButton = { TextButton({ val n = s.trim(); val r = root; if (n.isNotBlank() && r != null) AppScope.io.launch { val id = dao.upsertCategory(Category(name = n, income = r.income, parentId = r.id, tint = r.tint, color = r.color)); catId = id }; newSub = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ newSub = null }) { Text("Отмена") } })
    }
}

// ================= Категория =================

@Composable
private fun CategoryEditor(c0: Category, cats: List<Category>, txns: List<Txn>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).money() }
    var c by remember { mutableStateOf(c0.copy(icon = c0.icon.ifBlank { "t:dots-circle-horizontal" }, color = if (c0.color != 0) c0.color else finColor(c0).toArgb())) }
    var budget by remember { mutableStateOf(c0.budget?.let { fmtMoney(it).substringBeforeLast(' ').replace(" ", "") } ?: "") }
    var page by remember { mutableIntStateOf(0) }   // 0 основное, 1 иконка, 2 цвет
    var confirmDel by remember { mutableStateOf(false) }
    val subs = cats.filter { it.parentId == c0.id && c0.id != 0L }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (c0.id == 0L) "Новая категория" else "Категория", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Check, "Сохранить", {
                    if (c.name.isNotBlank()) AppScope.io.launch { dao.upsertCategory(c.copy(name = c.name.trim(), budget = budget.replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it > 0 })) }
                    onDone()
                }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val col = Color(c.color)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(col.copy(alpha = 0.12f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    FinBadge(c, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { AppTextField(c.name, { c = c.copy(name = it) }, "Название") }
                }
                Segmented(listOf("Тип", "Иконка", "Цвет"), page, { page = it }, Modifier.fillMaxWidth())
                when (page) {
                    1 -> IconPicker(c.icon, col) { c = c.copy(icon = it) }
                    2 -> Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { ColorPicker(col) { c = c.copy(color = it.toArgb()) } }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Расход", !c.income, { c = c.copy(income = false) }); Chip("Доход", c.income, { c = c.copy(income = true) })
                        }
                        if (!c.income) AppTextField(budget, { budget = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } }, "Бюджет на месяц, грн (необязательно)")
                        if (subs.isNotEmpty()) {
                            Text("Подкатегории", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                            subs.forEach { s -> Row(verticalAlignment = Alignment.CenterVertically) { Text("• ${s.name}", modifier = Modifier.weight(1f)); TextButton({ AppScope.io.launch { dao.upsertCategory(s.copy(archived = !s.archived)) } }) { Text(if (s.archived) "Вернуть" else "Скрыть") } } }
                        }
                        Text("Подкатегорию можно добавить прямо при вводе расхода — кнопкой «+» рядом с чипами.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                        if (c0.id != 0L) {
                            PillButton(if (c0.archived) "Вернуть из архива" else "В архив (скрыть)", { AppScope.io.launch { dao.upsertCategory(c0.copy(archived = !c0.archived)) }; onDone() }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                            PillButton("Удалить", { confirmDel = true }, Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDel) {
        val used = txns.count { t -> t.categoryId == c0.id || subs.any { it.id == t.categoryId } }
        AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Удалить «${c0.name}»?") },
            text = { Text(if (used > 0) "Операций с ней: $used — они останутся, но без категории. Лучше «В архив»." else "Операций с ней нет.") },
            confirmButton = { TextButton({ AppScope.io.launch { subs.forEach { dao.deleteCategory(it) }; dao.deleteCategory(c0) }; confirmDel = false; onDone() }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } })
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = true
            msg = runCatching { MoneyRepo.importSumlio(ctx, ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
                .fold({ "Импортировано операций: ${it.added}, пропущено повторов: ${it.skipped}." }, { "Не получилось: ${it.message}" })
            busy = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Импорт из Sumlio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выбери экспорт .csv. Повторы пропускаются, файл можно загружать снова. Все счета Sumlio складываются в одну копилку.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton({ picker.launch(arrayOf("text/*", "*/*")) }, enabled = !busy) { Text("Выбрать файл") } },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } })
}
