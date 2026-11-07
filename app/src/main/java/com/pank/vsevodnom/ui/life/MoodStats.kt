package com.pank.vsevodnom.ui.life

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.MoodEntry
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.SectionLabel
import com.pank.vsevodnom.ui.theme.Pal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val RU = Locale("ru")

/** Подсчёты по настроению за месяц — чистые функции, проверяются тестом. */
object MoodMath {
    fun day(m: MoodEntry): LocalDate = Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault()).toLocalDate()
    fun inMonth(all: List<MoodEntry>, ym: YearMonth) = all.filter { YearMonth.from(day(it)) == ym }
    fun counts(list: List<MoodEntry>): IntArray = IntArray(5).also { c -> list.forEach { c[(it.level - 1).coerceIn(0, 4)]++ } }

    /** Индекс настроения 0…100: среднее по записям, 1 → 0, 5 → 100. */
    fun score(list: List<MoodEntry>): Int? = list.takeIf { it.isNotEmpty() }?.let { l -> ((l.map { it.level }.average() - 1) / 4 * 100).toInt() }

    /** Стабильность 0…100: чем меньше скачки среднего между соседними днями, тем выше. Нужно хотя бы 2 дня. */
    fun stability(list: List<MoodEntry>): Int? {
        val days = list.groupBy { day(it) }.toSortedMap().values.map { d -> d.map { it.level }.average() }
        if (days.size < 2) return null
        val jumps = days.zipWithNext { a, b -> abs(a - b) }.average()
        return (100 - jumps / 4 * 100 * 1.6).coerceIn(0.0, 100.0).toInt()
    }
}

@Composable
fun MoodStats(moods: List<MoodEntry>) {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    val cur = MoodMath.inMonth(moods, ym)
    val prev = MoodMath.inMonth(moods, ym.minusMonths(1))
    val c = MoodMath.counts(cur); val pc = MoodMath.counts(prev)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ ym = ym.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Раньше") }
                Text(ym.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton({ ym = ym.plusMonths(1) }, enabled = ym < YearMonth.now()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Позже") }
            }
            MonthFaces(cur, ym)
        }
        // наблюдение: какое настроение изменилось сильнее всего по сравнению с прошлым месяцем
        val diff = (0..4).maxByOrNull { abs(c[it] - pc[it]) }
        if (diff != null && cur.isNotEmpty() && c[diff] != pc[diff]) {
            val d = c[diff] - pc[diff]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Sky).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(30.dp)) { moodFace(diff + 1, MOOD_COLORS[diff]) }
                Text("  В этом месяце «${MOOD_NAMES[diff]}» — ${c[diff]} раз. Это ${if (d > 0) "больше" else "меньше"}, чем в прошлом месяце (${if (d > 0) "+" else ""}$d).",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Text("Счётчик настроения", style = MaterialTheme.typography.titleMedium)
            Text("Сколько раз за месяц было каждое настроение", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val total = c.sum()
                    val stroke = size.height * 0.28f
                    val r = minOf(size.width / 2, size.height) - stroke / 2
                    val tl = Offset(size.width / 2 - r, size.height - r)
                    if (total == 0) drawArc(Pal.Line, 180f, 180f, false, tl, Size(2 * r, 2 * r), style = Stroke(stroke))
                    var a = 180f
                    // от «Супер» слева к «Ужасно» справа
                    (4 downTo 0).forEach { i -> if (c[i] > 0) { val sw = 180f * c[i] / total; drawArc(MOOD_COLORS[i], a, sw - 1.5f, false, tl, Size(2 * r, 2 * r), style = Stroke(stroke)); a += sw } }
                }
                Text("${c.sum()}", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (4 downTo 0).forEach { i ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Canvas(Modifier.size(40.dp)) { moodFace(i + 1, MOOD_COLORS[i].copy(alpha = if (c[i] > 0) 1f else 0.4f)) }
                            if (c[i] > 0) Box(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(20.dp).clip(CircleShape).background(MOOD_COLORS[i]).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${c[i]}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Text(MOOD_NAMES[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Text("Стабильность настроения", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            val st = MoodMath.stability(cur)
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(72.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).border(1.dp, Pal.Line, RoundedCornerShape(14.dp)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(st?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
                    HorizontalDivider(Modifier.width(36.dp), color = Pal.Muted)
                    Text("100", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                StabilityBars(cur, ym, Modifier.weight(1f).height(88.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Pal.Line, RoundedCornerShape(14.dp)).padding(10.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(if (st == null) "Отмечай настроение хотя бы два дня — посчитаю, насколько оно ровное." else "Чем больше число, тем ровнее настроение: без резких скачков от дня к дню.",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            MoodMath.score(cur)?.let { Text("Средний уровень за месяц: $it из 100", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** Календарь месяца: в каждом дне — лицо среднего настроения; если за день было разное — лицо поделено пополам. */
@Composable
private fun MonthFaces(list: List<MoodEntry>, ym: YearMonth) {
    val byDay = list.groupBy { MoodMath.day(it).dayOfMonth }
    val lead = ym.atDay(1).dayOfWeek.value - 1
    Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Pal.Muted) } }
    for (w in 0 until (lead + ym.lengthOfMonth() + 6) / 7) Row {
        for (d in 0 until 7) {
            val n = w * 7 + d - lead + 1
            Column(Modifier.weight(1f).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (n in 1..ym.lengthOfMonth()) {
                    val e = byDay[n].orEmpty()
                    Box(Modifier.size(34.dp)) {
                        if (e.isEmpty()) Canvas(Modifier.fillMaxSize()) { drawCircle(Pal.Line, size.minDimension / 2 * 0.86f) }
                        else {
                            val lo = e.minOf { it.level }; val hi = e.maxOf { it.level }
                            val avg = Math.round(e.map { it.level }.average()).toInt().coerceIn(1, 5)
                            Canvas(Modifier.fillMaxSize()) {
                                if (lo == hi) moodFace(avg, MOOD_COLORS[avg - 1])
                                else { drawArc(MOOD_COLORS[lo - 1], 90f, 180f, true); drawArc(MOOD_COLORS[hi - 1], 270f, 180f, true); moodFace(avg, Color.Transparent) }
                            }
                        }
                    }
                    Text("$n", style = MaterialTheme.typography.labelSmall, color = if (ym.atDay(n) == LocalDate.now()) Pal.LilacInk else Pal.Muted)
                }
            }
        }
    }
}

/** Столбики по дням: высота — насколько день отклонился от среднего за месяц; точки — дни без записей. */
@Composable
private fun StabilityBars(list: List<MoodEntry>, ym: YearMonth, modifier: Modifier) {
    val byDay = list.groupBy { MoodMath.day(it).dayOfMonth }.mapValues { (_, v) -> v.map { it.level }.average() }
    val mean = byDay.values.takeIf { it.isNotEmpty() }?.average() ?: 3.0
    val last = if (ym == YearMonth.now()) LocalDate.now().dayOfMonth else ym.lengthOfMonth()
    Canvas(modifier) {
        val step = size.width / last
        for (d in 1..last) {
            val x = step * (d - 0.5f); val cy = size.height / 2
            val v = byDay[d]
            if (v == null) drawCircle(Color(0xFFD9D9DE), step * 0.18f, Offset(x, cy))
            else {
                val h = (0.15f + (abs(v - mean) / 2.0).toFloat().coerceAtMost(1f) * 0.85f) * size.height / 2
                drawLine(Color(0xFF2FB38A), Offset(x, cy - h), Offset(x, cy + h), strokeWidth = step * 0.45f, cap = StrokeCap.Round)
            }
        }
    }
}
