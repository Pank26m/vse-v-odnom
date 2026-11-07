package com.pank.vsevodnom.ui.alerts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.alarm.AlertWorker
import com.pank.vsevodnom.data.AlertLevel
import com.pank.vsevodnom.data.AlertState
import com.pank.vsevodnom.data.AlertsApi
import com.pank.vsevodnom.data.AlertGeo
import com.pank.vsevodnom.data.RateLimited
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val AlertRed = Color(0xFFE0607E)
val AlertYellow = Color(0xFFE9B949)
val AlertGreen = Color(0xFF3FA36B)
internal val FillRed = Color(0xFFF08C9A)
internal val FillYellow = Color(0xFFF5D06A)
internal val FillCalm = Color(0xFFE4ECE7)
internal val FillPartial = Color(0xFFFBEFC4)

fun levelColor(l: AlertLevel?) = when (l) { AlertLevel.FULL -> AlertRed; AlertLevel.PARTIAL -> AlertYellow; AlertLevel.NONE -> AlertGreen; null -> Pal.Muted }

/** Последнее известное состояние — для главного экрана. */
object AlertCache { var state by mutableStateOf<AlertState?>(null) }

fun sinceText(t: LocalDateTime?): String {
    t ?: return ""
    val m = Duration.between(t, LocalDateTime.now()).toMinutes().coerceAtLeast(0)
    val ago = if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
    return "с ${t.format(DateTimeFormatter.ofPattern("HH:mm"))} · $ago"
}

/** Короткий текст для Киева и области — главная строка. */
fun kyivSummary(s: AlertState?): Pair<AlertLevel?, String> {
    s ?: return null to "Тревоги: загрузка…"
    val c = s.of(AlertsApi.KYIV_CITY); val o = s.of(AlertsApi.KYIV_OBL)
    val cityKnown = s.known(AlertsApi.KYIV_CITY)
    val worst = listOf(c, o).maxBy { it.ordinal }
    val text = when {
        c == AlertLevel.FULL && o == AlertLevel.FULL -> "Тревога · Киев и область"
        c == AlertLevel.FULL -> "Тревога в Киеве" + if (o == AlertLevel.PARTIAL) " · части области" else ""
        o == AlertLevel.FULL -> "Тревога по всей области" + if (!cityKnown) "" else " · в Киеве спокойно"
        o == AlertLevel.PARTIAL -> "Тревога в части области: " + s.districts[AlertsApi.KYIV_OBL].orEmpty().take(2).joinToString(", ")
        !cityKnown -> "Спокойно в области · по Киеву нет данных"
        else -> "Спокойно · Киев и область"
    }
    return worst to text
}

@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(AlertCache.state ?: AlertsApi.cached()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick, com.pank.vsevodnom.data.Net.online) {
        while (true) {
            loading = true
            runCatching { AlertsApi.fetch(ctx, force = tick > 0) }
                .onSuccess { state = it; AlertCache.state = it; error = null }
                .onFailure { error = if (it is RateLimited) "Источник просит не спешить — данные обновятся через ${it.waitSec} с" else "Не удалось получить данные: ${it.message}" }
            loading = false
            delay(30_000)
        }
    }

    ScreenScaffold("Тревоги", onBack, actions = {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        RoundIconButton(Icons.Rounded.Refresh, "Обновить", { tick++ })
        RoundIconButton(Icons.Rounded.Settings, "Настройки", { settings = true })
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = state
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusCard("Киев", s, AlertsApi.KYIV_CITY, Modifier.weight(1f))
                    StatusCard("Киевская обл.", s, AlertsApi.KYIV_OBL, Modifier.weight(1f))
                }
            }
            s?.districts?.get(AlertsApi.KYIV_OBL)?.takeIf { it.isNotEmpty() }?.let { d ->
                item { AppCard(Modifier.fillMaxWidth(), color = FillYellow.copy(alpha = 0.45f)) { Text("В области тревога здесь:", style = MaterialTheme.typography.labelLarge); Text(d.joinToString("\n"), style = MaterialTheme.typography.bodyMedium) } }
            }
            item {
                AppCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                    AlertMap(s, selected, { selected = it }, Modifier.fillMaxWidth().aspectRatio(AlertGeo.W / AlertGeo.H))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(FillRed); Text("тревога", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp)); Dot(FillYellow); Text("угроза", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp)); Dot(FillPartial); Text("в части района", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Два пальца — приблизить, нажми на район — подробности, двойное нажатие — сброс.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
            selected?.let { sel -> item { SelectedCard(s, sel) { selected = null } } }
            if (s != null && !s.districtLevel) item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach, onClick = { settings = true }) {
                    Text("Сейчас видно только целые области", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                    Text(if (AlertsApi.token(ctx).isBlank()) "Чтобы видеть районы и «жёлтые» угрозы, вставь токен alerts.in.ua в настройках (⚙)." else "alerts.in.ua сейчас не ответил — показан запасной источник.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (error != null || state == null) item { NetWait(error) }
            if (s != null) {
                val on = AlertsApi.OBLASTS.filter { s.of(it) != AlertLevel.NONE }
                    .sortedWith(compareByDescending<String> { s.of(it).ordinal }.thenBy { it })
                item { SectionLabel(if (on.isEmpty()) "Тревог нет нигде" else "Сейчас тревога: ${on.size}") }
                items(on) { n -> OblastCard(s, n) { selected = n } }
                item {
                    Text(
                        "Источник: ${s.source} · ${Instant.ofEpochMilli(s.fetchedAt).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0)}\n" +
                            "Где именно летит дрон, API не сообщает — только тип угрозы и текст сообщения источника, они показаны в карточках.\n" +
                            "Справочно. Для мгновенных сигналов держи официальное приложение «Повітряна тривога».\n" +
                            "Границы районов и громад: ukrainian_geodata, Sławomir Matuszak (CC BY 4.0).",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    if (settings) {
        var token by remember { mutableStateOf(AlertsApi.token(ctx)) }
        var notify by remember { mutableStateOf(AlertsApi.notifyEnabled(ctx)) }
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Тревоги") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Уведомлять о тревоге в Киеве и области", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(notify, { notify = it })
                        }
                    }
                    item { Text("Как получить токен (бесплатно, один раз):", style = MaterialTheme.typography.titleSmall) }
                    item {
                        Text("1. Открой сайт alerts.in.ua → раздел API → форма запроса доступа.\n" +
                            "2. Укажи почту и цель: «личное приложение, просмотр тревог в Киеве».\n" +
                            "3. Токен придёт письмом — длинная строка из букв и цифр.\n" +
                            "4. Скопируй и вставь в поле ниже → «Сохранить».\n\n" +
                            "Без токена работает бесплатный источник, но он видит только целые области, без районов и «жёлтых» угроз.\n" +
                            "Токен хранится только в телефоне.", style = MaterialTheme.typography.bodySmall)
                    }
                    item { TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://alerts.in.ua/"))) } }) { Text("Открыть alerts.in.ua") } }
                    item { AppTextField(token, { token = it }, "Токен alerts.in.ua") }
                }
            },
            confirmButton = {
                TextButton({
                    AlertsApi.setToken(ctx, token); AlertsApi.setNotify(ctx, notify)
                    AlertWorker.schedule(ctx)
                    settings = false; tick++
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton({ settings = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(c))

@Composable
private fun StatusCard(title: String, s: AlertState?, key: String, modifier: Modifier) {
    val known = s?.known(key) == true
    val l = if (known) s!!.of(key) else null
    val bg = when (l) { AlertLevel.FULL -> FillRed; AlertLevel.PARTIAL -> FillYellow; AlertLevel.NONE -> FillCalm; null -> Pal.Card }
    AppCard(modifier.heightIn(min = 120.dp), color = bg) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Pal.Ink)
        Spacer(Modifier.height(6.dp))
        Text(when (l) { AlertLevel.FULL -> "Тревога"; AlertLevel.PARTIAL -> "Частично"; AlertLevel.NONE -> "Спокойно"; null -> if (s == null) "…" else "Нет данных" },
            style = MaterialTheme.typography.headlineMedium)
        s?.since?.get(key)?.let { if (l != AlertLevel.NONE) Text(sinceText(it), style = MaterialTheme.typography.bodySmall) }
    }
}

