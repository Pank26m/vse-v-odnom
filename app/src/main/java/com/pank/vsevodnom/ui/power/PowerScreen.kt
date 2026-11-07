package com.pank.vsevodnom.ui.power

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.Outage
import com.pank.vsevodnom.data.Power
import com.pank.vsevodnom.data.PowerState
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private val OffFill = Color(0xFF474B66)
private val MaybeFill = Color(0xFFE2E1EA)
private val OnBolt = Color(0xFFE9A92B)
private val NowRing = Color(0xFF4F86C6)

@Composable
fun PowerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(Power.group(ctx)) }
    var notify by remember { mutableStateOf(Power.notify(ctx)) }
    var quiet by remember { mutableStateOf(Power.quiet(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var dayTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(com.pank.vsevodnom.data.Net.online) { loading = true; runCatching { Power.refresh(ctx, true) }; loading = false }
    val st = Power.state
    fun web(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }

    ScreenScaffold("Свет", onBack, actions = {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { scope.launch { loading = true; runCatching { Power.refresh(ctx, true) }; loading = false } })
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val (bg, title) = when {
                    st == null -> Pal.Card to "Укажи свою очередь"
                    st.emergency -> Pal.Rose to "Экстренные отключения — график не действует"
                    st.offNow -> Pal.Rose to "Сейчас света нет · ${st.nearestText().removePrefix("Ближайшее ").replaceFirstChar { it.uppercase() }}"
                    else -> Pal.Butter to "Свет есть · ${st.nearestText().removePrefix("Ближайшее ")}"
                }
                AppCard(Modifier.fillMaxWidth(), color = bg) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    st?.let { Text("Очередь ${it.group} · источник: ${it.source}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (st != null) item {
                AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Сегодня", "Завтра").forEachIndexed { i, t ->
                            Column(Modifier.weight(1f).clickable { dayTab = i }.padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t, style = MaterialTheme.typography.titleMedium, color = if (dayTab == i) NowRing else Pal.Ink)
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (dayTab == i) NowRing else Color.Transparent))
                            }
                        }
                    }
                    HorizontalDivider(color = Pal.Line)
                    HourGrid(st, LocalDate.now().plusDays(dayTab.toLong()), dayTab == 0)
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Напоминать за 30 минут до отключения", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(notify, { notify = it; Power.setNotify(ctx, it); scope.launch { runCatching { Power.refresh(ctx, true) } } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Не беспокоить с 23:00 до 08:00", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(quiet, { quiet = it; Power.setQuiet(ctx, it); scope.launch { runCatching { Power.refresh(ctx, true) } } })
                    }
                    Text("При экстренных отключениях напоминаний нет — график в такие дни не действует.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
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
            item { SectionLabel("Графики на сайтах") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SiteButton("ДТЭК Киевские\nэлектросети", Modifier.weight(1f)) { web("https://www.dtek-kem.com.ua/ua/shutdowns") }
                    SiteButton("YASNO", Modifier.weight(1f)) { web("https://yasno.ua/schedule-turn-off-electricity") }
                }
            }
        }
    }
}

@Composable
private fun SiteButton(title: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Pal.Butter), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, tint = Pal.ButterInk, modifier = Modifier.size(20.dp)) }
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Pal.Muted, modifier = Modifier.size(18.dp))
    }
}

/** Доля часа [0..60) без света: список отрезков (начало, конец) в минутах внутри часа. */
private fun parts(list: List<Outage>, day: LocalDate, h: Int): List<Pair<Int, Int>> = list.filter { it.day == day }.mapNotNull { o ->
    val a = maxOf(o.fromMin, h * 60); val b = minOf(o.toMin, h * 60 + 60)
    if (b > a) (a - h * 60) to (b - h * 60) else null
}

/** Табличка 24 часов: тёмное — нет света, светлое с молнией — есть, серое — возможно; частичные часы закрашены по минутам. */
@Composable
private fun HourGrid(st: PowerState, day: LocalDate, today: Boolean) {
    val now = LocalTime.now()
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.emergency) Text("Сейчас экстренные отключения: график может не соблюдаться.", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
        for (row in 0 until 6) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (col in 0 until 4) {
                val h = row * 4 + col
                val off = parts(st.outages, day, h)
                val maybe = parts(st.possible, day, h)
                val offMin = off.sumOf { it.second - it.first }
                val full = offMin >= 60
                val isNow = today && now.hour == h
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Pal.Card)
                        .border(if (isNow) 2.dp else 1.dp, if (isNow) NowRing else Pal.Line, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        maybe.forEach { (a, b) -> drawRect(MaybeFill, Offset(size.width * a / 60f, 0f), Size(size.width * (b - a) / 60f, size.height)) }
                        off.forEach { (a, b) -> drawRect(OffFill, Offset(size.width * a / 60f, 0f), Size(size.width * (b - a) / 60f, size.height)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            full -> Box(Modifier.size(14.dp)) {
                                Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Canvas(Modifier.size(14.dp)) { drawLine(Color.White, Offset(1f, 1f), Offset(size.width - 1f, size.height - 1f), 3f, StrokeCap.Round) }
                            }
                            offMin == 0 && maybe.isEmpty() -> Icon(Icons.Rounded.Bolt, null, tint = OnBolt, modifier = Modifier.size(14.dp))
                            else -> {}
                        }
                        Text(
                            "%02d:00".format(h), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                            color = if (full) Color.White else Pal.Ink
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Legend(Modifier.border(2.dp, NowRing, CircleShape), Pal.Card, "текущий час")
        Legend(Modifier.border(1.dp, Pal.Line, CircleShape), Pal.Card, "есть свет")
        Legend(Modifier, OffFill, "нет света")
        Legend(Modifier, MaybeFill, "возможное отключение")
        val list = st.outages.filter { it.day == day }
        Text(if (list.isEmpty()) "Отключений по графику нет" else "Без света: " + list.joinToString(", ") { it.label() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Legend(ring: Modifier, fill: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(fill).then(ring))
        Text("  $text", style = MaterialTheme.typography.bodyMedium)
    }
}
