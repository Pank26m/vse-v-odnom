package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")

fun dayLabel(d: LocalDate): String {
    val t = LocalDate.now()
    return when (d) { t -> "сегодня"; t.plusDays(1) -> "завтра"; t.minusDays(1) -> "вчера"; else -> d.format(DateTimeFormatter.ofPattern("EE, d MMM", RU)) }
}

/** ToDo: плитки разделов, а ниже — общая лента всего незавершённого с ярлыками. */
@Composable
fun TodoScreen(onBack: (() -> Unit)? = null, start: String? = null) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    var page by rememberSaveable { mutableStateOf(start) }
    val close = { page = null }
    when (page) {
        "tasks" -> { BackHandler(onBack = close); TasksPage(close); return }
        "notes" -> { BackHandler(onBack = close); NotesPage(close); return }
        "movies" -> { BackHandler(onBack = close); MoviesPage(close); return }
        "shop" -> { BackHandler(onBack = close); ShopListsPage(close); return }
        "habits" -> { BackHandler(onBack = close); HabitsPage(close); return }
        "dates" -> { BackHandler(onBack = close); DatesPage(close); return }
        "stats" -> { BackHandler(onBack = close); TodoStatsPage(close); return }
        "cart" -> { BackHandler(onBack = close); com.pank.vsevodnom.ui.life.CartScreen(close); return }
    }
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    val habits by dao.habits().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val logs by remember { dao.habitLogs(today.minusDays(400).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    val dates by dao.birthdays().collectAsState(initial = emptyList())
    val notes by dao.notes().collectAsState(initial = emptyList())
    val media by dao.media().collectAsState(initial = emptyList())
    val entries by dao.allEntries().collectAsState(initial = emptyList())
    val pages by dao.pages().collectAsState(initial = emptyList())
    val cart by remember { AppDb.get(ctx).life().cart() }.collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { ensureListPages(ctx) }
    val t0 = today.toEpochDay()
    val open = tasks.filter { !it.done }
    val overdue = open.count { (it.dueDay ?: Long.MAX_VALUE) < t0 }
    val todayT = open.count { it.dueDay == t0 }
    val dueH = habits.filter { Todo.isDue(it, today, logs) }
    val undoneH = dueH.filter { !habitDone(it, logs, today) }
    val soonDates = dates.filter { Duration.between(today.atStartOfDay(), nextOccurrence(it).atStartOfDay()).toDays() <= 7 }.sortedBy { nextOccurrence(it) }
    val shopIds = pages.filter { it.type == "SHOP" }.map { it.id }.toSet()
    val shopLeft = entries.count { it.pageId in shopIds && !it.done }
    var edit by remember { mutableStateOf<TaskItem?>(null) }
    var habit by remember { mutableStateOf<Habit?>(null) }

    var detail by remember { mutableStateOf<Habit?>(null) }
    var bday by remember { mutableStateOf<Birthday?>(null) }
    var gift by remember { mutableStateOf<Birthday?>(null) }
    var doneTick by remember { mutableIntStateOf(0) }   // перерисовать после «поздравил»

    // переход из сводки: открыть конкретную задачу, привычку или дату
    LaunchedEffect(TodoNav.target, tasks, habits, dates) {
        val t = TodoNav.target ?: return@LaunchedEffect
        val kind = t.substringBefore(':'); val id = t.substringAfter(':').toLongOrNull()
        when (kind) {
            "page" -> { TodoNav.target = null; page = t.substringAfter(':') }
            "task" -> tasks.firstOrNull { it.id == id }?.let { TodoNav.target = null; edit = it }
            "habit" -> habits.firstOrNull { it.id == id }?.let { TodoNav.target = null; detail = it }
            "bday" -> dates.firstOrNull { it.id == id }?.let { TodoNav.target = null; bday = it }
        }
    }

    val tick = doneTick
    fun bdaysOn(d: LocalDate) = dates.filter { it.day == d.dayOfMonth && it.month == d.monthValue }
    fun tasksOn(d: LocalDate) = open.filter { if (d == today) (it.dueDay ?: Long.MAX_VALUE) <= t0 else it.dueDay == d.toEpochDay() }
        .sortedWith(compareBy({ it.dueDay }, { -it.priority }, { it.dueMinute ?: 9999 }))

    com.pank.vsevodnom.ui.calendar.CalendarScreen(
        onBack = onBack,
        title = "ToDo",
        header = {
            item(key = "tiles") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // «Статистика» — ровно по ширине плитки под ней
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Spacer(Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { page = "stats" }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Rounded.Insights, null, tint = ACCENT, modifier = Modifier.size(20.dp)); Text(" Статистика", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HubTile("Задачи", Icons.Rounded.TaskAlt, Tints.Sky, "$todayT на сегодня", if (overdue > 0) "$overdue просрочено" else "${open.size} открыто", overdue > 0, Modifier.weight(1f)) { page = "tasks" }
                        HubTile("Привычки", Icons.Rounded.Loop, Tints.Mint, if (dueH.isEmpty()) "сегодня нет" else "${dueH.size - undoneH.size} из ${dueH.size}", "${habits.size} всего", false, Modifier.weight(1f)) { page = "habits" }
                        HubTile("Заметки", Icons.Rounded.StickyNote2, Tints.Butter, "${notes.count { !it.archived && it.deletedAt == null }}", "блокнот", false, Modifier.weight(1f)) { page = "notes" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HubTile("Фильмы", Icons.Rounded.Movie, Tints.Lilac, "${media.count { it.status == 2 }} посмотрел", "${media.count { it.status != 2 }} в планах", false, Modifier.weight(1f)) { page = "movies" }
                        HubTile("Покупки", Icons.Rounded.ShoppingCart, Tints.Peach, if (shopLeft > 0) "купить $shopLeft" else "всё куплено", "${shopIds.size} списков", false, Modifier.weight(1f)) { page = "shop" }
                        HubTile("Даты", Icons.Rounded.Cake, Tints.Rose, soonDates.firstOrNull()?.name ?: "${dates.size}", soonDates.firstOrNull()?.let { "через ${Duration.between(today.atStartOfDay(), nextOccurrence(it).atStartOfDay()).toDays()} дн." } ?: "дни рождения", false, Modifier.weight(1f)) { page = "dates" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val want = cart.filter { !it.bought }
                        HubTile("Корзина", Icons.Rounded.ShoppingBag, Tints.Peach, if (want.isEmpty()) "пусто" else "хочу ${want.size}", want.mapNotNull { it.planDay }.minOrNull()?.let { "купить ${dateLabel(it)}" } ?: "вишлист", false, Modifier.weight(1f)) { page = "cart" }
                        Spacer(Modifier.weight(2f).padding(5.dp))
                    }
                    Text("Календарь", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        dots = { d ->
            (if (tasksOn(d).isNotEmpty() && !d.isBefore(today)) listOf(Tints.Sky.ink) else emptyList()) +
                (if (cart.any { !it.bought && it.planDay == d.toEpochDay() }) listOf(Pal.PeachInk) else emptyList()) +
                (if (bdaysOn(d).isNotEmpty()) listOf(Pal.RoseInk) else emptyList())
        },
        dayExtras = { d ->
            val k = d.toEpochDay()
            val dt = tasksOn(d)
            if (d.isBefore(today)) return@CalendarScreen
            items(dt, key = { "t$k-${it.id}" }) { t -> Labeled("Задача", Tints.Sky) { TaskRow(t) { edit = t } } }
            if (d == today) items(undoneH, key = { "h$k-${it.id}" }) { h ->
                Leaving(h.id to t0, onLeft = { }) { _, leave ->
                    Labeled("Привычка", Tints.Mint) { HabitRowFeed(h, logs, onDone = leave) { detail = h } }
                }
            }
            items(bdaysOn(d).filter { tick >= 0 && !BdayDone.isDone(ctx, it.id, d) }, key = { "d$k-${it.id}" }) { b ->
                Labeled("Дата", Tints.Rose) { BirthdayRow(b, d, onOpen = { bday = b }) { doneTick++ } }
            }
            items(cart.filter { !it.bought && (it.planDay == k || (d == today && (it.planDay ?: Long.MAX_VALUE) < k)) }, key = { "c$k-${it.id}" }) { c ->
                Labeled("Корзина", Tints.Peach) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { page = "cart" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckCircle(false, Pal.PeachInk, 24.dp) { AppScope.io.launch { AppDb.get(ctx).life().upsertCart(c.copy(bought = true, boughtAt = System.currentTimeMillis())) } }
                        CheckDivider()
                        if (c.priority > 0) Box(Modifier.padding(end = 6.dp).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(prioColor(c.priority)))
                        Text("Купить: ${c.title}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                        c.price?.let { Text("%.0f ₴".format(it), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            if (d == today && shopLeft > 0) item(key = "shop$k") {
                Labeled("Покупки", Tints.Peach) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { page = "shop" }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ShoppingCart, null, tint = Pal.PeachInk)
                        Text("  Купить: $shopLeft", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(pages.filter { it.id in shopIds && entries.any { e -> e.pageId == it.id && !e.done } }.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        },
    )
    edit?.let { TaskEditor(it, tasks.flatMap { t -> t.tagList() }.distinct()) { edit = null } }
    habit?.let { h -> HabitEditor(h) { habit = null } }
    detail?.let { h -> HabitDetail(habits.firstOrNull { it.id == h.id } ?: h, logs, onEdit = { habit = h; detail = null }) { detail = null } }
    bday?.let { DateEditor(it, onGift = { g -> gift = g }) { bday = null } }
    gift?.let { b -> GiftDialog(b) { gift = null } }
}

/** Куда перейти внутри ToDo, когда открываем раздел снаружи (из сводки): «task:ID», «habit:ID», «bday:ID», «page:shop». */
object TodoNav { var target by mutableStateOf<String?>(null) }

/** Дата (день рождения) с отметкой «поздравил»: после нажатия плавно уезжает. */
@Composable
fun BirthdayRow(b: Birthday, day: LocalDate, onOpen: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    Leaving(b.id to day, onLeft = { BdayDone.mark(ctx, b.id, day); onDone() }) { done, leave ->
        val col = DATE_COLORS[b.tint % DATE_COLORS.size]
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Cake, null, tint = col) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(b.name, style = MaterialTheme.typography.titleSmall)
                Text((if (b.kind == "birthday") "День рождения" else "Дата") + (b.year?.let { " · ${day.year - it}" } ?: "") + if (day == today) " · сегодня" else "", style = MaterialTheme.typography.bodySmall)
            }
            CheckDivider()
            CheckCircle(done, col, 26.dp) { leave() }
        }
    }
}

/** Привычка в общей ленте: при отметке плавно уезжает (выполненное не мешает). */
@Composable
private fun HabitRowFeed(h: Habit, logs: List<HabitLog>, onDone: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val t = Tints.all[h.tint % 6]
    var checked by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.bg), contentAlignment = Alignment.Center) { Text(h.icon.ifBlank { "✓" }) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(h.title, style = MaterialTheme.typography.bodyLarge)
            StreakFlame(Todo.streak(h, logs, LocalDate.now()), false, 14.dp)
        }
        CheckDivider()
        CheckCircle(checked, t.ink, 26.dp) {
            checked = true
            // запись — после анимации, чтобы строка успела уехать
            AppScope.io.launch { delay(650); Todo.tickHabit(ctx, h, LocalDate.now()) }
            onDone()
        }
    }
}

@Composable
private fun Labeled(label: String, tint: Tint, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint.ink, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp).clip(RoundedCornerShape(6.dp)).background(tint.bg).padding(8.dp, 2.dp))
        content()
    }
}

@Composable
private fun HubTile(title: String, icon: ImageVector, tint: Tint, big: String, small: String, alert: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(124.dp).clip(RoundedCornerShape(18.dp)).background(tint.bg).clickable(onClick = onClick).padding(12.dp)) {
        Icon(icon, null, tint = tint.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(big, style = MaterialTheme.typography.bodySmall, color = Pal.Ink, maxLines = 1)
        Text(small, style = MaterialTheme.typography.labelSmall, color = if (alert) Pal.RoseInk else Pal.Muted, maxLines = 1)
    }
}
