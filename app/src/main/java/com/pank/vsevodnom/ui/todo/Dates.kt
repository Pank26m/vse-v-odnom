package com.pank.vsevodnom.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")
val DATE_COLORS = listOf(Color(0xFFE5485D), Color(0xFFF2A33A), Color(0xFFF5C518), Color(0xFF2DB37A), Color(0xFF4772F5), Color(0xFF8E5BD8), Color(0xFF9E9E9E))
private val KINDS = listOf("birthday" to "День рождения", "holiday" to "Праздник", "anniversary" to "Годовщина", "other" to "Другое")
private fun kindIcon(k: String) = when (k) { "birthday" -> Icons.Rounded.Cake; "holiday" -> Icons.Rounded.Celebration; "anniversary" -> Icons.Rounded.Favorite; else -> Icons.Rounded.Event }

fun nextOccurrence(b: Birthday, from: LocalDate = LocalDate.now()): LocalDate =
    runCatching { MonthDay.of(b.month, b.day.coerceAtMost(Month.of(b.month).maxLength())).atYear(from.year).let { if (it.isBefore(from)) it.plusYears(1) else it } }.getOrDefault(from.plusYears(1))

@Composable
fun DatesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val dates by dao.birthdays().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Birthday?>(null) }
    var color by remember { mutableStateOf<Int?>(null) }
    var showPublic by remember { mutableStateOf(true) }
    val today = LocalDate.now()
    val shown = dates.filter { color == null || it.tint == color }.sortedWith(compareBy({ nextOccurrence(it) }, { -it.priority }))
    val holidays = (Holidays.all(today.year) + Holidays.all(today.year + 1)).filterKeys { !it.isBefore(today) && it.isBefore(today.plusMonths(6)) }.toSortedMap()
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Даты", onBack) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Все · ${dates.size}", color == null, { color = null })
                DATE_COLORS.indices.filter { i -> dates.any { it.tint == i } }.forEach { i ->
                    Box(Modifier.size(if (color == i) 32.dp else 26.dp).clip(CircleShape).background(DATE_COLORS[i]).clickable { color = if (color == i) null else i })
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { EmptyHint("Добавь дни рождения, годовщины и свои праздники — они будут повторяться каждый год и попадут в сводку.") }
                var lastMonth: YearMonth? = null
                shown.forEach { b ->
                    val n = nextOccurrence(b)
                    val ym = YearMonth.from(n)
                    if (ym != lastMonth) { lastMonth = ym; item(key = "m$ym") { SectionLabel(n.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }) } }
                    item(key = b.id) {
                        val days = Duration.between(today.atStartOfDay(), n.atStartOfDay()).toDays()
                        val col = DATE_COLORS[b.tint % DATE_COLORS.size]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { edit = b }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(col.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(kindIcon(b.kind), null, tint = col) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.name, style = MaterialTheme.typography.titleSmall)
                                Text(n.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)) + (b.year?.let { " · ${if (b.kind == "birthday") "исполнится" else "будет"} ${n.year - it}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            if (b.priority > 0) Icon(Icons.Rounded.Flag, null, Modifier.size(18.dp), tint = prioColor(b.priority))
                            Text(when (days) { 0L -> " сегодня!"; 1L -> " завтра"; else -> " через $days дн." }, style = MaterialTheme.typography.labelLarge, color = if (days <= 7) col else Pal.Muted)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Государственные и церковные праздники", style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.weight(1f))
                        Switch(showPublic, { showPublic = it })
                    }
                }
                if (showPublic) items(holidays.entries.toList()) { (d, name) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(d.format(DateTimeFormatter.ofPattern("d MMM", RU)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(CircleShape).background(BLUE).clickable { edit = Birthday(name = "", day = today.dayOfMonth, month = today.monthValue) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить дату", tint = Color.White)
        }
    }
    edit?.let { DateEditor(it) { edit = null } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateEditor(b0: Birthday, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var b by remember { mutableStateOf(b0) }
    var year by remember { mutableStateOf(b0.year?.toString() ?: "") }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDone, title = { Text(if (b0.id == 0L) "Новая дата" else "Дата") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(b.name, { b = b.copy(name = it) }, "Название или имя")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { KINDS.forEach { (k, s) -> Chip(s, b.kind == k, { b = b.copy(kind = k) }) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(MonthDay.of(b.month, b.day.coerceAtMost(Month.of(b.month).maxLength())).atYear(2024).format(DateTimeFormatter.ofPattern("d MMMM", RU)), true, { pick = true })
                    Box(Modifier.width(110.dp)) { AppTextField(year, { year = it.filter(Char::isDigit).take(4) }, "Год (необяз.)") }
                }
                Text("Повторяется каждый год.", style = MaterialTheme.typography.bodySmall)
                SectionLabel("Приоритет")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..3).forEach { p -> IconButton({ b = b.copy(priority = p) }) { Icon(if (p == 0) Icons.Rounded.OutlinedFlag else Icons.Rounded.Flag, PRIO[p].first, tint = if (b.priority == p) PRIO[p].second else PRIO[p].second.copy(alpha = 0.3f)) } }
                }
                SectionLabel("Цвет")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DATE_COLORS.forEachIndexed { i, c -> Box(Modifier.size(if (b.tint == i) 32.dp else 26.dp).clip(CircleShape).background(c).clickable { b = b.copy(tint = i) }) }
                }
                AppTextField(b.note, { b = b.copy(note = it) }, "Заметка (идея подарка…)")
                if (b0.id != 0L) TextButton({ AppScope.io.launch { AppDb.get(ctx).todo().deleteBirthday(b0) }; onDone() }) { Text("Удалить", color = Pal.RoseInk) }
            }
        },
        confirmButton = { TextButton({ if (b.name.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).todo().upsertBirthday(b.copy(name = b.name.trim(), year = year.toIntOrNull())) }; onDone() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onDone) { Text("Отмена") } })
    if (pick) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.of(year.toIntOrNull()?.coerceIn(1900, 2100) ?: LocalDate.now().year, b.month, b.day.coerceAtMost(28)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pick = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); b = b.copy(day = d.dayOfMonth, month = d.monthValue) }; pick = false }) { Text("Ок") }
        }) { DatePicker(dp) }
    }
}
