package com.pank.vsevodnom.ui.alerts

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
import com.pank.vsevodnom.data.AlertState
import com.pank.vsevodnom.data.AlertsApi
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AlertFill = Color(0xFFF3B6C6)
private val AlertStroke = Color(0xFFA8456A)
private val CalmFill = Color(0xFFDDF2E6)

/** Последнее известное состояние — чтобы главный экран тоже его видел. */
object AlertCache { var state by mutableStateOf<AlertState?>(null) }

fun sinceText(t: LocalDateTime?): String {
    t ?: return ""
    val m = Duration.between(t, LocalDateTime.now()).toMinutes().coerceAtLeast(0)
    val ago = if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
    return "с ${t.format(DateTimeFormatter.ofPattern("HH:mm"))} · $ago"
}

@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(AlertCache.state) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        while (true) {
            loading = true
            runCatching { AlertsApi.fetch(ctx) }
                .onSuccess { state = it; AlertCache.state = it; error = null }
                .onFailure { error = "Не удалось получить данные: ${it.message}" }
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
                    StatusCard("Киев", s?.active?.get(AlertsApi.KYIV_CITY), s?.since?.get(AlertsApi.KYIV_CITY), Modifier.weight(1f))
                    StatusCard("Киевская обл.", s?.active?.get(AlertsApi.KYIV_OBL), s?.since?.get(AlertsApi.KYIV_OBL), Modifier.weight(1f))
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    UkraineMapView(s, Modifier.fillMaxWidth().aspectRatio(MAP_W / MAP_H))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(AlertFill)); Spacer(Modifier.width(6.dp))
                        Text("тревога", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(16.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(CalmFill)); Spacer(Modifier.width(6.dp))
                        Text("спокойно", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            error?.let { item { AppCard(color = Pal.Peach, modifier = Modifier.fillMaxWidth()) { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
            if (s != null) {
                val on = s.active.filterValues { it }.keys.sorted()
                item { SectionLabel(if (on.isEmpty()) "Тревог нет нигде" else "Тревога сейчас: ${on.size}") }
                items(on) { n ->
                    AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(AlertStroke))
                            Spacer(Modifier.width(10.dp))
                            Text(n, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(sinceText(s.since[n]), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text(
                        "Источник: ${s.source} · обновлено ${Instant.ofEpochMilli(s.fetchedAt).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0)}\n" +
                            "Это справочная информация. Для мгновенных сигналов держи официальное приложение «Повітряна тривога».\n" +
                            "Карта: © участники OpenStreetMap.",
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Уведомлять о тревоге в Киеве и области", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(notify, { notify = it })
                    }
                    Text("Фоновая проверка — раз в ~15 минут (так позволяет Android), поэтому уведомление может опоздать.", style = MaterialTheme.typography.bodySmall)
                    AppTextField(token, { token = it }, "Токен alerts.in.ua (необязательно)")
                    Text("С токеном данные берутся из официального API alerts.in.ua. Токен бесплатный — запрашивается у них на сайте.", style = MaterialTheme.typography.bodySmall)
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
private fun StatusCard(title: String, active: Boolean?, since: LocalDateTime?, modifier: Modifier) {
    val bg = when (active) { true -> Pal.Rose; false -> Pal.Mint; null -> Pal.Card }
    AppCard(modifier.heightIn(min = 120.dp), color = bg) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (active == true) Pal.RoseInk else Pal.MintInk)
        Spacer(Modifier.height(6.dp))
        Text(when (active) { true -> "Тревога"; false -> "Спокойно"; null -> "—" }, style = MaterialTheme.typography.headlineLarge)
        if (since != null) Text(sinceText(since), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UkraineMapView(state: AlertState?, modifier: Modifier = Modifier) {
    val paths = remember {
        REGION_SHAPES.mapValues { (_, a) ->
            Path().apply { moveTo(a[0], a[1]); var i = 2; while (i < a.size) { lineTo(a[i], a[i + 1]); i += 2 }; close() }
        }
    }
    Canvas(modifier) {
        val sc = size.width / MAP_W
        scale(sc, sc, pivot = Offset.Zero) {
        paths.forEach { (name, p) ->
            val on = state?.active?.get(name) == true
            drawPath(p, if (on) AlertFill else CalmFill)
            drawPath(p, Color.White, style = Stroke(2.5f))
        }
        val cityOn = state?.active?.get(AlertsApi.KYIV_CITY) == true
        val c = Offset(KYIV_POINT[0], KYIV_POINT[1])
        drawCircle(Color.White, 14f, c)
        drawCircle(if (cityOn) AlertStroke else Pal.MintInk, 9f, c)
        }
    }
}
