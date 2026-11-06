package com.pank.vsevodnom.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")

fun dayLabel(d: LocalDate): String {
    val t = LocalDate.now()
    return when (d) { t -> "сегодня"; t.plusDays(1) -> "завтра"; t.minusDays(1) -> "вчера"; else -> d.format(DateTimeFormatter.ofPattern("EE, d MMM", RU)) }
}

/** «Дела»: плитки разделов — задачи, списки, привычки, даты. */
@Composable
fun TodoScreen(onBack: (() -> Unit)? = null, start: String? = null) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    var page by rememberSaveable { mutableStateOf(start) }
    val close = { page = null }
    when (page) {
        "tasks" -> { BackHandler(onBack = close); TasksPage(close); return }
        "lists" -> { BackHandler(onBack = close); ListsPage(close); return }
        "habits" -> { BackHandler(onBack = close); HabitsPage(close); return }
        "dates" -> { BackHandler(onBack = close); DatesPage(close); return }
    }
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    val habits by dao.habits().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val logs by remember { dao.habitLogs(today.minusDays(7).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    val dates by dao.birthdays().collectAsState(initial = emptyList())
    val pages by dao.pages().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { ensureListPages(ctx) }
    val t0 = today.toEpochDay()
    val open = tasks.filter { !it.done }
    val overdue = open.count { (it.dueDay ?: Long.MAX_VALUE) < t0 }
    val todayT = open.count { it.dueDay == t0 }
    val dueH = habits.filter { Todo.isDue(it, today, logs) }
    val doneH = dueH.count { h -> (logs.firstOrNull { it.habitId == h.id && it.epochDay == t0 }?.count ?: 0) >= h.perDay }
    val nextDate = dates.minByOrNull { nextOccurrence(it) }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack); Spacer(Modifier.width(12.dp)) }
            Text("Дела", style = MaterialTheme.typography.headlineMedium)
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { HubTile("Задачи", Icons.Rounded.TaskAlt, Tints.Sky, "$todayT на сегодня", if (overdue > 0) "$overdue просрочено" else "${open.size} всего", overdue > 0) { page = "tasks" } }
            item { HubTile("Привычки", Icons.Rounded.Loop, Tints.Mint, if (dueH.isEmpty()) "на сегодня нет" else "$doneH из ${dueH.size}", "${habits.size} привычек") { page = "habits" } }
            item { HubTile("Списки", Icons.Rounded.Checklist, Tints.Butter, "${pages.size} списков", "фильмы, покупки, заметки") { page = "lists" } }
            item { HubTile("Даты", Icons.Rounded.Cake, Tints.Rose, nextDate?.let { it.name } ?: "пока пусто", nextDate?.let { "через ${java.time.Duration.between(today.atStartOfDay(), nextOccurrence(it).atStartOfDay()).toDays()} дн." } ?: "дни рождения, праздники") { page = "dates" } }
            item(span = { GridItemSpan(2) }) {
                val list = open.filter { it.dueDay != null && it.dueDay!! <= t0 }.sortedWith(compareBy({ it.dueDay }, { -it.priority })).take(6)
                if (list.isNotEmpty()) AppCard(Modifier.fillMaxWidth(), onClick = { page = "tasks" }) {
                    SectionLabel("Сегодня")
                    list.forEach { t ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Flag, null, Modifier.size(14.dp), tint = prioColor(t.priority))
                            Text("  " + t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                            if ((t.dueDay ?: 0) < t0) Text("просрочено", style = MaterialTheme.typography.labelSmall, color = Pal.RoseInk)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubTile(title: String, icon: ImageVector, tint: Tint, big: String, small: String, alert: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.height(150.dp).clip(RoundedCornerShape(18.dp)).background(tint.bg).clickable(onClick = onClick).padding(16.dp)) {
        Icon(icon, null, tint = tint.ink, modifier = Modifier.size(32.dp))
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(big, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(small, style = MaterialTheme.typography.bodySmall, color = if (alert) Pal.RoseInk else Pal.Muted, maxLines = 1)
    }
}
