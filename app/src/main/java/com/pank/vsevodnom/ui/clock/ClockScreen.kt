package com.pank.vsevodnom.ui.clock

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.alarm.AlarmScheduler
import com.pank.vsevodnom.alarm.TimerState
import com.pank.vsevodnom.alarm.nextTrigger
import com.pank.vsevodnom.data.AlarmEntity
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.WorldCity
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ClockScreen(onBack: () -> Unit, startTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    ScreenScaffold("Часы", onBack) {
        Segmented(
            listOf("Мир", "Будильник", "Секундомер", "Таймер"), tab, { tab = it },
            Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> WorldTab()
            1 -> AlarmsTab()
            2 -> StopwatchTab()
            else -> TimerTab()
        }
    }
}

@Composable
private fun tickingNow(periodMs: Long = 1000): State<ZonedDateTime> {
    val s = remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { s.value = ZonedDateTime.now(); delay(periodMs) } }
    return s
}

// ------------------------- Мировое время -------------------------

@Composable
private fun WorldTab() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).cities() }
    val cities by dao.world().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val now by tickingNow()
    var adding by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(color = Pal.Lilac, radius = 28.dp, padding = 24.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(now.format(HM), style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp))
                        Text(":%02d".format(now.second), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF5B4A93), modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Text(utcLabel(now), style = MaterialTheme.typography.labelLarge, color = Color(0xFF5B4A93))
                    val d = now.toLocalDate()
                    Text(
                        "${d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }}, ${d.dayOfMonth} ${monthGenitive(d.monthValue)}",
                        style = MaterialTheme.typography.titleSmall, color = Color(0xFF5B4A93)
                    )
                }
            }
        }
        items(cities, key = { it.id }) { c ->
            val z = runCatching { ZoneId.of(c.zoneId) }.getOrNull() ?: return@items
            val t = now.withZoneSameInstant(z)
            val diffH = (t.offset.totalSeconds - now.offset.totalSeconds) / 3600.0
            val dayShift = t.toLocalDate().compareTo(now.toLocalDate())
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(if (diffH == 0.0) "как у тебя" else formatDiff(diffH))
                                if (dayShift > 0) append(" · завтра") else if (dayShift < 0) append(" · вчера")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(t.format(HM), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { scope.launch { dao.deleteWorld(c) } }) {
                        Icon(Icons.Rounded.Close, "Убрать ${c.name}", tint = Pal.Muted)
                    }
                }
            }
        }
        item {
            PillButton("+ Добавить город", { adding = true }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.LilacInk)
        }
    }

    if (adding) CityPicker(
        onDismiss = { adding = false },
        onPick = { name, zone -> scope.launch { dao.addWorld(WorldCity(zoneId = zone, name = name, sort = cities.size)) }; adding = false }
    )
}

/** «Киев · UTC+2 (EET)» — свой часовой пояс. */
private fun utcLabel(t: ZonedDateTime): String {
    val sec = t.offset.totalSeconds
    val sign = if (sec >= 0) "+" else "−"
    val h = kotlin.math.abs(sec) / 3600; val m = kotlin.math.abs(sec) / 60 % 60
    val short = t.zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).takeIf { !it.startsWith("GMT") && !it.contains('/') }
    val city = CITY_ZONES.firstOrNull { it.second == t.zone.id }?.first ?: t.zone.id.substringAfterLast('/').replace('_', ' ')
    return "$city · UTC$sign$h" + (if (m > 0) ":%02d".format(m) else "") + (short?.let { " ($it)" } ?: "")
}

private fun monthGenitive(m: Int) = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря"
)[m - 1]

private fun formatDiff(h: Double): String {
    val sign = if (h > 0) "+" else "−"
    val a = kotlin.math.abs(h)
    val whole = a.toInt()
    val min = ((a - whole) * 60).toInt()
    return if (min == 0) "$sign$whole ч" else "$sign$whole ч $min мин"
}

@Composable
private fun CityPicker(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var online by remember { mutableStateOf<List<com.pank.vsevodnom.data.WeatherApi.Place>>(emptyList()) }
    val local = remember(q) { CITY_ZONES.filter { it.first.contains(q, ignoreCase = true) } }
    LaunchedEffect(q) {
        online = emptyList()
        if (q.trim().length < 2) return@LaunchedEffect
        delay(350)
        online = runCatching { com.pank.vsevodnom.data.WeatherApi.search(q.trim()) }.getOrDefault(emptyList()).filter { it.timezone.isNotBlank() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Добавить город") },
        text = {
            Column {
                AppTextField(q, { q = it }, "Любой город мира")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(local) { (name, zone) -> CityRow(name, "", zone) { onPick(name, zone) } }
                    items(online.filter { p -> local.none { it.first.equals(p.name, true) } }) { p -> CityRow(p.name, p.region, p.timezone) { onPick(p.name, p.timezone) } }
                    if (q.length >= 2 && local.isEmpty() && online.isEmpty()) item { Text("Ищу… (нужен интернет)", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    )
}

@Composable
private fun CityRow(name: String, region: String, zone: String, onClick: () -> Unit) {
    val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (region.isNotBlank()) Text(region, style = MaterialTheme.typography.bodySmall)
        }
        Text(ZonedDateTime.now(z).format(HM), style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------- Будильники -------------------------

private val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private fun daysText(mask: Int): String = when (mask) {
    0 -> "Один раз"
    0b1111111 -> "Каждый день"
    0b0011111 -> "По будням"
    0b1100000 -> "По выходным"
    else -> DAY_SHORT.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
}

private fun untilText(a: AlarmEntity): String {
    val next = nextTrigger(a)
    val d = Duration.between(LocalDateTime.now(), next)
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h > 0) "через $h ч $m мин" else "через $m мин"
}

@Composable
private fun AlarmsTab() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).alarms() }
    val alarms by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    tickingNow(30_000) // обновлять «через N мин»

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PermissionWarnings() }
        if (alarms.isEmpty()) item { EmptyHint("Будильников пока нет") }
        items(alarms, key = { it.id }) { a ->
            AppCard(modifier = Modifier.fillMaxWidth(), onClick = { editing = a }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "%02d:%02d".format(a.hour, a.minute),
                            style = MaterialTheme.typography.displayMedium,
                            color = if (a.enabled) Pal.Ink else Pal.Muted
                        )
                        Text(
                            listOfNotNull(a.label.ifBlank { null }, daysText(a.daysMask), if (a.enabled) untilText(a) else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = a.enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                val upd = a.copy(enabled = on)
                                dao.upsert(upd)
                                if (on) AlarmScheduler.schedule(ctx, upd) else AlarmScheduler.cancel(ctx, upd)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF9C88D6))
                    )
                }
            }
        }
        item {
            PillButton("+ Новый будильник", { editing = AlarmEntity(hour = 7, minute = 30, daysMask = 0b0011111) }, Modifier.fillMaxWidth())
        }
    }

    editing?.let { a ->
        AlarmEditor(
            initial = a,
            onDismiss = { editing = null },
            onSave = { upd ->
                scope.launch {
                    val id = dao.upsert(upd.copy(enabled = true))
                    val saved = if (upd.id == 0L) upd.copy(id = id, enabled = true) else upd.copy(enabled = true)
                    AlarmScheduler.schedule(ctx, saved)
                }
                editing = null
            },
            onDelete = if (a.id != 0L) ({
                scope.launch { AlarmScheduler.cancel(ctx, a); dao.delete(a) }
                editing = null
            }) else null
        )
    }
}

@Composable
private fun PermissionWarnings() {
    val ctx = LocalContext.current
    val canExact = AlarmScheduler.canExact(ctx)
    val canFull = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    if (canExact && canFull) return
    AppCard(color = Pal.Peach, modifier = Modifier.fillMaxWidth()) {
        Text("Нужны разрешения", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
        Spacer(Modifier.height(4.dp))
        if (!canExact) {
            Text("Точные будильники выключены — может звонить с опозданием.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Разрешить") }
        }
        if (!canFull) {
            Text("Полноэкранный будильник выключен — будет только уведомление.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Разрешить") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(initial: AlarmEntity, onDismiss: () -> Unit, onSave: (AlarmEntity) -> Unit, onDelete: (() -> Unit)?) {
    val tp = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var mask by remember { mutableIntStateOf(initial.daysMask) }
    var label by remember { mutableStateOf(initial.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(initial.copy(hour = tp.hour, minute = tp.minute, daysMask = mask, label = label.trim())) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить", color = Pal.RoseInk) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(tp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_SHORT.forEachIndexed { i, d ->
                        val on = mask and (1 shl i) != 0
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (on) Pal.Ink else Pal.Line)
                                .clickable { mask = mask xor (1 shl i) },
                            contentAlignment = Alignment.Center
                        ) { Text(d, style = MaterialTheme.typography.labelMedium, color = if (on) Color.White else Pal.Ink) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(label, { label = it }, "Подпись")
            }
        }
    )
}

// ------------------------- Секундомер -------------------------

private object Stopwatch {
    var startedAt by mutableLongStateOf(0L)
    var accumulated by mutableLongStateOf(0L)
    var running by mutableStateOf(false)
    val laps = mutableStateListOf<Long>()
    fun elapsed(now: Long) = accumulated + if (running) now - startedAt else 0
}

private fun fmtStopwatch(ms: Long): Pair<String, String> {
    val m = ms / 60_000
    val s = (ms / 1000) % 60
    val cs = (ms / 10) % 100
    val main = if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%02d:%02d".format(m, s)
    return main to ",%02d".format(cs)
}

@Composable
private fun StopwatchTab() {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Stopwatch.running) {
        while (Stopwatch.running) { now = SystemClock.elapsedRealtime(); delay(31) }
        now = SystemClock.elapsedRealtime()
    }
    val el = Stopwatch.elapsed(now)
    val (main, frac) = fmtStopwatch(el)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(main, style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp))
            Text(frac, style = MaterialTheme.typography.headlineLarge, color = Pal.Muted, modifier = Modifier.padding(bottom = 12.dp))
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                if (Stopwatch.running) "Круг" else "Сброс",
                {
                    if (Stopwatch.running) Stopwatch.laps.add(0, el)
                    else { Stopwatch.accumulated = 0; Stopwatch.laps.clear() }
                },
                Modifier.weight(1f), bg = Pal.Lilac, fg = Pal.Ink, enabled = Stopwatch.running || el > 0
            )
            PillButton(
                if (Stopwatch.running) "Стоп" else "Старт",
                {
                    val t = SystemClock.elapsedRealtime()
                    if (Stopwatch.running) { Stopwatch.accumulated += t - Stopwatch.startedAt; Stopwatch.running = false }
                    else { Stopwatch.startedAt = t; Stopwatch.running = true }
                },
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(Stopwatch.laps.size) { i ->
                val total = Stopwatch.laps[i]
                val prev = Stopwatch.laps.getOrNull(i + 1) ?: 0L
                val (lm, lf) = fmtStopwatch(total - prev)
                val (tm, tf) = fmtStopwatch(total)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("Круг ${Stopwatch.laps.size - i}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("$lm$lf", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(16.dp))
                    Text("$tm$tf", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                }
                HorizontalDivider(color = Pal.Line)
            }
        }
    }
}

// ------------------------- Таймер -------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerTab() {
    val ctx = LocalContext.current
    val st by TimerState.s.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.running) {
        while (st.running) { now = SystemClock.elapsedRealtime(); delay(200) }
    }
    val left = TimerState.remaining(now)
    val idle = !st.running && !st.paused
    val progress = if (st.totalMs > 0) left.toFloat() / st.totalMs else 0f

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(260.dp).padding(8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 18.dp.toPx()
                drawArc(Color(0xFFEEEAF6), 0f, 360f, false, style = Stroke(sw))
                drawArc(Color(0xFF9C88D6), -90f, 360f * progress, false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            val totalSec = (left + 999) / 1000
            Text(
                if (totalSec >= 3600) "%d:%02d:%02d".format(totalSec / 3600, totalSec / 60 % 60, totalSec % 60)
                else "%02d:%02d".format(totalSec / 60, totalSec % 60),
                style = MaterialTheme.typography.displayMedium
            )
        }
        Spacer(Modifier.height(16.dp))
        if (idle) {
            val presets = listOf(1, 3, 5, 10, 15, 25, 30, 60)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { m ->
                    Chip(if (m == 60) "1 ч" else "$m мин", st.totalMs == m * 60_000L, { TimerState.setTotal(m * 60_000L) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-60_000L to "−1 мин", -10_000L to "−10 с", 10_000L to "+10 с", 60_000L to "+1 мин").forEach { (d, t) ->
                    Chip(t, false, { TimerState.setTotal((st.totalMs + d).coerceIn(10_000L, 24 * 3600_000L)) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Сброс", { TimerState.cancel(ctx) }, Modifier.weight(1f), bg = Pal.Lilac, fg = Pal.Ink, enabled = !idle)
            PillButton(
                if (st.running) "Пауза" else "Старт",
                { if (st.running) TimerState.pause(ctx) else TimerState.start(ctx) },
                Modifier.weight(1f)
            )
        }
    }
}
