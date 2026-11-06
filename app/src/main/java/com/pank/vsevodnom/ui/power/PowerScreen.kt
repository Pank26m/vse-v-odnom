package com.pank.vsevodnom.ui.power

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.Outage
import com.pank.vsevodnom.data.Power
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private val DOW = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
fun PowerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(Power.group(ctx)) }
    var notify by remember { mutableStateOf(Power.notify(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var manualEdit by remember { mutableStateOf(false) }
    LaunchedEffect(com.pank.vsevodnom.data.Net.online) { loading = true; runCatching { Power.refresh(ctx, true) }; loading = false }
    val st = Power.state

    ScreenScaffold("Свет", onBack, actions = {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { scope.launch { loading = true; runCatching { Power.refresh(ctx, true) }; loading = false } })
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val next = st?.nextOffInMin
                val (bg, title) = when {
                    st == null -> Pal.Card to "Укажи свою очередь"
                    st.offNow -> Pal.Rose to "Сейчас света нет"
                    st.emergency -> Pal.Rose to "Экстренные отключения — график не действует"
                    next != null -> Pal.Butter to "Следующее отключение через ${next / 60} ч ${next % 60} мин"
                    else -> Pal.Mint to "Сегодня отключений больше нет"
                }
                AppCard(Modifier.fillMaxWidth(), color = bg) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    st?.let { Text("Очередь ${it.group} · источник: ${it.source}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            st?.let { s ->
                listOf(LocalDate.now() to "Сегодня", LocalDate.now().plusDays(1) to "Завтра").forEach { (d, label) ->
                    item { DayBar(label, s.outages.filter { it.day == d }, d == LocalDate.now()) }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Моя очередь (группа)")
                    Spacer(Modifier.height(6.dp))
                    AppTextField(group, { group = it }, "Например 3.1")
                    Text("Номер очереди видно в «Київ Цифровий» или на сайте YASNO по своему адресу.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PillButton("Сохранить", { Power.setGroup(ctx, group); scope.launch { loading = true; runCatching { Power.refresh(ctx, true) }; loading = false } }, Modifier.fillMaxWidth())
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row { Text("Напоминать за 30 минут до отключения", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Switch(notify, { notify = it; Power.setNotify(ctx, it) }) }
                    Text("При экстренных отключениях напоминаний нет — график в такие дни не действует.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth(), onClick = { manualEdit = true }) {
                    SectionLabel("Ручной график")
                    Text("Если источник не отвечает — впиши часы отключений по дням недели, напоминания будут по ним.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (manualEdit) {
        val vals = remember { mutableStateListOf(*Array(7) { Power.manual(ctx, it) }) }
        AlertDialog(onDismissRequest = { manualEdit = false }, title = { Text("Ручной график") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text("Формат: 08:00-12:00, 18:00-22:00", style = MaterialTheme.typography.bodySmall) }
                    items(7) { i -> AppTextField(vals[i], { vals[i] = it }, DOW[i]) }
                }
            },
            confirmButton = { TextButton({ (0..6).forEach { Power.setManual(ctx, it, vals[it]) }; manualEdit = false; scope.launch { runCatching { Power.refresh(ctx, true) } } }) { Text("Сохранить") } },
            dismissButton = { TextButton({ manualEdit = false }) { Text("Отмена") } })
    }
}

/** Полоса суток: серым — свет есть, красным — отключение, линия — сейчас. */
@Composable
private fun DayBar(label: String, outages: List<Outage>, isToday: Boolean) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionLabel(label + if (outages.isEmpty()) " · без отключений" else "")
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = size.width
            drawRoundRect(Pal.Mint, size = size, cornerRadius = CornerRadius(8f))
            outages.forEach { o -> drawRoundRect(Color(0xFFF3B6C6), Offset(w * o.fromMin / 1440f, 0f), Size(w * (o.toMin - o.fromMin) / 1440f, size.height), CornerRadius(8f)) }
            if (isToday) { val n = LocalTime.now(); val x = w * (n.hour * 60 + n.minute) / 1440f; drawLine(Pal.Ink, Offset(x, -4f), Offset(x, size.height + 4f), 4f) }
        }
        Row(Modifier.fillMaxWidth()) { listOf("00", "06", "12", "18", "24").forEachIndexed { i, t -> Text(t, style = MaterialTheme.typography.labelSmall, modifier = if (i < 4) Modifier.weight(1f) else Modifier) } }
        outages.forEach { Text("Нет света ${it.label()}", style = MaterialTheme.typography.bodyMedium) }
    }
}
