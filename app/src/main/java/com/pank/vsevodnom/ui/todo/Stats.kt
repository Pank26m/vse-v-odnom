package com.pank.vsevodnom.ui.todo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")

/** Статистика по всему ToDo: задачи, привычки, фильмы, заметки, покупки, даты. */
@Composable
fun TodoStatsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val today = LocalDate.now()
    val tasks by dao.tasks().collectAsState(initial = emptyList())
    val habits by dao.habits().collectAsState(initial = emptyList())
    val logs by remember { dao.habitLogs(today.minusDays(400).toEpochDay(), today.toEpochDay()) }.collectAsState(initial = emptyList())
    val media by dao.media().collectAsState(initial = emptyList())
    val notes by dao.notes().collectAsState(initial = emptyList())
    val entries by dao.allEntries().collectAsState(initial = emptyList())
    val pages by dao.pages().collectAsState(initial = emptyList())
    val dates by dao.birthdays().collectAsState(initial = emptyList())
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val doneDays = tasks.filter { it.done && it.doneAt != null }.groupingBy { day(it.doneAt!!) }.eachCount()
    val last14 = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val month30 = (0..29).sumOf { doneDays[today.minusDays(it.toLong())] ?: 0 }
    val best = doneDays.maxByOrNull { it.value }
    val open = tasks.filter { !it.done }

    ScreenScaffold("Статистика", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                StatBlock("Задачи", Tints.Sky) {
                    NumRow("Всего" to "${tasks.size}", "Сделано" to "${tasks.count { it.done }}", "Открыто" to "${open.size}", "Просрочено" to "${open.count { (it.dueDay ?: Long.MAX_VALUE) < today.toEpochDay() }}")
                    NumRow("Сегодня закрыл" to "${doneDays[today] ?: 0}", "За неделю" to "${(0..6).sumOf { doneDays[today.minusDays(it.toLong())] ?: 0 }}", "За месяц" to "$month30", "В день" to "%.1f".format(month30 / 30.0).replace('.', ','))
                    Text("Закрыто задач за 14 дней", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                    Bars(last14.map { doneDays[it] ?: 0 }, last14.map { it.dayOfMonth.toString() }, Pal.SkyInk)
                    best?.let { Text("Лучший день: ${it.key.format(DateTimeFormatter.ofPattern("d MMMM", RU))} — ${it.value} задач", style = MaterialTheme.typography.bodySmall) }
                    if (open.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 2, 1, 0).forEach { p ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(prioColor(p).copy(alpha = 0.14f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${open.count { it.priority == p }}", style = MaterialTheme.typography.titleMedium)
                                Text(listOf("без", "низкий", "средний", "высокий")[p], style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item {
                val week = habits.fold(0 to 0) { a, h -> val r = habitRate(h, logs, today.minusDays(6), today); (a.first + r.first) to (a.second + r.second) }
                val month = habits.fold(0 to 0) { a, h -> val r = habitRate(h, logs, today.minusDays(29), today); (a.first + r.first) to (a.second + r.second) }
                StatBlock("Привычки", Tints.Mint) {
                    NumRow("Активных" to "${habits.size}", "Неделя" to pct(week), "Месяц" to pct(month), "Рекорд" to "${habits.maxOfOrNull { bestStreak(it, logs) } ?: 0} дн.")
                    val perDay = last14.map { d -> habits.count { habitDone(it, logs, d) } }
                    Text("Отмечено привычек по дням", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                    Bars(perDay, last14.map { it.dayOfMonth.toString() }, Pal.MintInk)
                    habits.sortedByDescending { habitRate(it, logs, today.minusDays(29), today).let { r -> if (r.second == 0) 0.0 else r.first.toDouble() / r.second } }.forEach { h ->
                        val r = habitRate(h, logs, today.minusDays(29), today)
                        val share = if (r.second == 0) 0f else r.first.toFloat() / r.second
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.icon.ifBlank { "✓" } + " " + h.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(130.dp), maxLines = 1)
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Pal.Ink.copy(alpha = 0.06f))) {
                                Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Tints.all[h.tint % 6].ink))
                            }
                            Spacer(Modifier.width(8.dp)); StreakFlame(Todo.streak(h, logs, today), habitDone(h, logs, today), 14.dp)
                        }
                    }
                }
            }
            item {
                StatBlock("Фильмы и сериалы", Tints.Lilac) {
                    NumRow("Посмотрел" to "${media.count { it.status == 2 }}", "Смотрю" to "${media.count { it.status == 1 }}", "Хочу" to "${media.count { it.status == 0 }}", "В Telegram" to "${media.count { it.link.isNotBlank() }}")
                    val rated = media.filter { it.rating > 0 }
                    NumRow("Фильмов" to "${media.count { it.kind == "film" }}", "Сериалов" to "${media.count { it.kind == "series" }}", "Моя оценка" to (if (rated.isEmpty()) "—" else "%.1f★".format(rated.map { it.rating }.average()).replace('.', ',')), "Всего" to "${media.size}")
                }
            }
            item {
                val live = notes.filter { it.deletedAt == null && !it.archived }
                StatBlock("Заметки", Tints.Butter) {
                    NumRow("Заметок" to "${live.size}", "Закреплено" to "${live.count { it.pinned }}", "В архиве" to "${notes.count { it.archived && it.deletedAt == null }}", "В корзине" to "${notes.count { it.deletedAt != null }}")
                }
            }
            item {
                val ids = pages.filter { it.type == "SHOP" }.map { it.id }.toSet()
                val mine = entries.filter { it.pageId in ids }
                StatBlock("Покупки", Tints.Peach) { NumRow("Списков" to "${ids.size}", "Куплено" to "${mine.count { it.done }}", "Осталось" to "${mine.count { !it.done }}", "Пунктов" to "${mine.size}") }
            }
            item {
                StatBlock("Даты", Tints.Rose) {
                    NumRow("Всего" to "${dates.size}", "Дни рождения" to "${dates.count { it.kind == "birthday" }}",
                        "За 30 дней" to "${dates.count { Duration.between(today.atStartOfDay(), nextOccurrence(it).atStartOfDay()).toDays() <= 30 }}", "Из контактов" to "${dates.count { it.contactKey.isNotBlank() }}")
                }
            }
        }
    }
}

private fun pct(r: Pair<Int, Int>) = if (r.second == 0) "—" else "${r.first * 100 / r.second}%"

@Composable
private fun StatBlock(title: String, tint: Tint, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint.ink)
        content()
    }
}

@Composable
private fun NumRow(vararg items: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (k, v) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.04f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(k, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Bars(values: List<Int>, labels: List<String>, color: Color) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val bw = size.width / values.size
        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / max * (size.height - 4)).coerceAtLeast(if (v > 0) 6f else 3f)
            drawRoundRect(if (v > 0) color.copy(alpha = if (i == values.lastIndex) 0.9f else 0.55f) else color.copy(alpha = 0.12f), Offset(i * bw + bw * 0.18f, size.height - h), Size(bw * 0.64f, h), CornerRadius(8f))
        }
    }
    Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) } }
}
