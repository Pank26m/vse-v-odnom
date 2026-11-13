package com.pank.vsevodnom.ui.clock

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.alarm.*
import com.pank.vsevodnom.data.AlarmEntity
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val RU = java.util.Locale("ru")
private val SORTS = listOf("Сначала включённые", "Ближайшие", "Ранние", "Поздние", "По названию")

private fun daysLabel(a: AlarmEntity, e: AlarmExt?): String = when {
    e?.date != null -> LocalDate.ofEpochDay(e.date).format(DateTimeFormatter.ofPattern("d MMMM, EEE", RU))
    a.daysMask == 0 -> "Один раз"
    a.daysMask == 0b1111111 -> "Каждый день"
    a.daysMask == 0b0011111 -> "По будням"
    a.daysMask == 0b1100000 -> "По выходным"
    else -> DAYS.filterIndexed { i, _ -> a.daysMask and (1 shl i) != 0 }.joinToString(", ")
}

private fun until(a: AlarmEntity): String? {
    val n = nextTriggerOrNull(a) ?: return null
    val d = Duration.between(LocalDateTime.now(), n)
    val days = d.toDays(); val h = d.toHours() % 24; val m = d.toMinutes() % 60
    return "через " + listOfNotNull(days.takeIf { it > 0 }?.let { "$it дн" }, h.takeIf { it > 0 || days > 0 }?.let { "$it ч" }, "$m мин").joinToString(" ")
}

/** Вкладка «Будильник»: теги сверху (нажатие — вкл/выкл все будильники тега), сортировка, одинаковые по времени — одной строкой. */
@Composable
fun AlarmsPage() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).alarms() }
    val alarms by dao.all().collectAsState(initial = emptyList())
    var ver by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { AlarmExtStore.ensure(ctx) }
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    var sort by remember { mutableIntStateOf(AlarmSettings.sort(ctx)) }
    var sortMenu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var selfies by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = LocalDateTime.now() } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @Suppress("UNUSED_EXPRESSION") ver; @Suppress("UNUSED_EXPRESSION") now
    val ext = alarms.associate { it.id to AlarmExtStore.get(ctx, it.id) }
    val tags = alarms.flatMap { ext[it.id]?.tags.orEmpty() }.distinct().sorted()
    // одинаковые по времени/дням — одна строка со всеми тегами (звенит один)
    val groups = alarms.groupBy { alarmKey(it, ext[it.id]) }.values.map { g -> g.sortedBy { it.id } }
    val sorted = when (sort) {
        1 -> groups.sortedBy { g -> g.filter { it.enabled }.mapNotNull { nextTriggerOrNull(it) }.minOrNull() ?: LocalDateTime.MAX }
        2 -> groups.sortedBy { it[0].hour * 60 + it[0].minute }
        3 -> groups.sortedByDescending { it[0].hour * 60 + it[0].minute }
        4 -> groups.sortedBy { g -> g.joinToString { it.label }.lowercase() }
        else -> groups.sortedWith(compareBy({ g -> if (g.any { it.enabled }) 0 else 1 }, { it[0].hour * 60 + it[0].minute }))
    }
    fun setEnabled(list: List<AlarmEntity>, on: Boolean) {
        AppScope.io.launch { list.forEach { dao.upsert(it.copy(enabled = on)) }; AlarmScheduler.syncAll(ctx) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AlarmPermissionWarnings() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // тег: нажатие — включить/выключить все его будильники
                    tags.forEach { t ->
                        val list = alarms.filter { t in ext[it.id]?.tags.orEmpty() }
                        val on = list.any { it.enabled }
                        Row(Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Pal.Ink else Pal.Card).clickable { setEnabled(list, !on) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) Icons.Rounded.Alarm else Icons.Rounded.AlarmOff, null, Modifier.size(16.dp), tint = if (on) Color.White else Pal.Muted)
                            Text(" #$t", style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Pal.Ink)
                        }
                    }
                    if (tags.isEmpty()) Text("Теги появятся здесь — задай их в будильнике", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                Box {
                    RoundIconButton(Icons.Rounded.Sort, "Сортировка", { sortMenu = true })
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SORTS.forEachIndexed { i, s -> DropdownMenuItem({ Text((if (i == sort) "✓ " else "") + s) }, { sort = i; AlarmSettings.setSort(ctx, i); sortMenu = false }) }
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Настройки будильника") }, { sortMenu = false; settings = true }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                        DropdownMenuItem({ Text("Селфи будильника") }, { sortMenu = false; selfies = true }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) })
                    }
                }
            }
        }
        if (alarms.isEmpty()) item { EmptyHint("Будильников пока нет") }
        items(sorted, key = { g -> g.joinToString(",") { it.id.toString() } }) { g ->
            val a = g[0]; val e = ext[a.id]
            val on = g.any { it.enabled }
            val allTags = g.flatMap { ext[it.id]?.tags.orEmpty() }.distinct()
            AppCard(modifier = Modifier.fillMaxWidth(), onClick = { editing = a }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%02d:%02d".format(a.hour, a.minute), style = MaterialTheme.typography.displayMedium, color = if (on) Pal.Ink else Pal.Muted)
                            val names = g.map { it.label }.filter { it.isNotBlank() }.distinct()
                            if (names.isNotEmpty()) Text("  " + names.joinToString(" · "), style = MaterialTheme.typography.titleSmall, color = if (on) Pal.Ink else Pal.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Text(listOfNotNull(daysLabel(a, e), if (on) until(a) else null,
                            e?.mission?.takeIf { it != "none" }?.let { when (it) { "math" -> "примеры"; "type" -> "текст"; else -> "селфи" } }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        if (allTags.isNotEmpty()) Text(allTags.joinToString(" ") { "#$it" } + if (g.size > 1) " · совпадают — звенит один" else "", style = MaterialTheme.typography.labelMedium, color = Pal.LilacInk)
                    }
                    Switch(on, { v -> setEnabled(g, v) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF9C88D6)))
                }
            }
        }
        item { PillButton("+ Новый будильник", { editing = AlarmEntity(hour = 7, minute = 30, daysMask = 0b0011111) }, Modifier.fillMaxWidth()) }
    }
    editing?.let { a -> AlarmEditorFull(a, tags, onDone = { editing = null; ver++ }) }
    if (settings) AlarmSettingsDialog { settings = false }
    if (selfies) SelfiesDialog { selfies = false }
}

@Composable
internal fun AlarmPermissionWarnings() {
    val ctx = LocalContext.current
    val canExact = AlarmScheduler.canExact(ctx)
    val canFull = android.os.Build.VERSION.SDK_INT < 34 || ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    val overlay = android.provider.Settings.canDrawOverlays(ctx)
    if (canExact && canFull && overlay) return
    AppCard(color = Pal.Peach, modifier = Modifier.fillMaxWidth()) {
        Text("Нужны разрешения", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
        if (!canExact) { Text("Точные будильники выключены — может звонить с опозданием.", style = MaterialTheme.typography.bodyMedium)
            TextButton({ if (android.os.Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }) { Text("Разрешить") } }
        if (!canFull) { Text("Полноэкранный будильник выключен — будет только уведомление.", style = MaterialTheme.typography.bodyMedium)
            TextButton({ ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) }) { Text("Разрешить") } }
        if (!overlay) { Text("«Поверх других окон» — чтобы полноэкранный будильник нельзя было свернуть (экран сам вернётся).", style = MaterialTheme.typography.bodyMedium)
            TextButton({ ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }) { Text("Разрешить") } }
    }
}

/** Редактор будильника на весь экран: время, дни или дата, название, теги, мелодия, вибрация, нарастание, экран, погода, сводка, задание. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditorFull(a0: AlarmEntity, knownTags: List<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).alarms() }
    val tp = rememberTimePickerState(a0.hour, a0.minute, is24Hour = true)
    var a by remember { mutableStateOf(a0) }
    var e by remember { mutableStateOf(if (a0.id != 0L) AlarmExtStore.get(ctx, a0.id) else AlarmExt()) }
    var pickDate by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf<String?>(null) }
    val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = if (android.os.Build.VERSION.SDK_INT >= 33) r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                else @Suppress("DEPRECATION") r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == android.app.Activity.RESULT_OK) e = if (u == null) e.copy(sound = "", soundName = "") else
            e.copy(sound = u.toString(), soundName = runCatching { RingtoneManager.getRingtone(ctx, u)?.getTitle(ctx) }.getOrNull().orEmpty())
    }
    fun save() {
        val upd = a.copy(hour = tp.hour, minute = tp.minute, enabled = true)
        AppScope.io.launch {
            val id = dao.upsert(upd).let { if (upd.id != 0L) upd.id else it }
            AlarmExtStore.set(ctx, id, e)
            AlarmScheduler.syncAll(ctx)
        }
        onDone()
    }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (a0.id == 0L) "Новый будильник" else "Будильник", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Check, "Сохранить", { save() }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(tp) }
                Section("Название и теги") {
                    AppTextField(a.label, { a = a.copy(label = it) }, "Название (например: Пары, Спортзал)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (knownTags + e.tags).distinct().sorted().forEach { t -> Chip("#$t", t in e.tags, { e = e.copy(tags = if (t in e.tags) e.tags - t else e.tags + t) }) }
                        Chip("+ тег", false, { newTag = "" })
                    }
                    Text("Тег сверху списка включает/выключает все свои будильники. Можно несколько тегов.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                Section("Когда") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAYS.forEachIndexed { i, d ->
                            val on = a.daysMask and (1 shl i) != 0 && e.date == null
                            Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) Pal.Ink else Pal.Line).clickable { a = a.copy(daysMask = a.daysMask xor (1 shl i)); e = e.copy(date = null) },
                                contentAlignment = Alignment.Center) { Text(d, style = MaterialTheme.typography.labelMedium, color = if (on) Color.White else Pal.Ink) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.date?.let { "Один раз: " + LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMMM yyyy", RU)) } ?: if (a.daysMask == 0) "Один раз (ближайшее время)" else "Повторять по дням",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton({ pickDate = true }) { Text("На дату…") }
                        if (e.date != null) TextButton({ e = e.copy(date = null) }) { Text("Убрать") }
                    }
                }
                Section("Звук") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        ringtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, e.sound.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }))
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Pal.LilacInk); Text("  Мелодия: " + e.soundName.ifBlank { "как в системе" }, modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
                    }
                    SwitchRow("Вибрация", a.vibrate) { a = a.copy(vibrate = it) }
                    SwitchRow("Плавное пробуждение", e.ramp > 0) { e = e.copy(ramp = if (it) 60 else 0) }
                    if (e.ramp > 0) {
                        Text("Громкость нарастает ${e.ramp} с", style = MaterialTheme.typography.bodySmall)
                        Slider(e.ramp.toFloat(), { e = e.copy(ramp = (it / 5).toInt() * 5) }, valueRange = 10f..300f)
                    }
                }
                Section("Экран") {
                    SwitchRow("На весь экран", e.full) { e = e.copy(full = it) }
                    Text(if (e.full) "Свернуть нельзя: только выключить (или решить задание) или отложить." else "Только уведомление со звуком.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    // погода — только на полноэкранном
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alphaIf(!e.full)) {
                        Text("Погода на экране", modifier = Modifier.weight(1f), color = if (e.full) Pal.Ink else Pal.Muted)
                        Switch(e.weather && e.full, { e = e.copy(weather = it) }, enabled = e.full)
                    }
                    SwitchRow("После выключения — сводка дел до 12:00", e.summary) { e = e.copy(summary = it) }
                }
                Section("Задание, чтобы выключить") {
                    ChoiceRow(listOf("none" to "Нет", "math" to "Примеры", "type" to "Текст", "selfie" to "Селфи"), e.mission) { e = e.copy(mission = it) }
                    when (e.mission) {
                        "math" -> {
                            Text("Сложность: ${Missions.LEVELS[e.level.coerceIn(0, 4)]} · пример: ${Missions.math(e.level, kotlin.random.Random(7)).text}", style = MaterialTheme.typography.bodySmall)
                            Slider(e.level.toFloat(), { e = e.copy(level = it.toInt()) }, valueRange = 0f..4f, steps = 3)
                        }
                        "type" -> Text("Нужно без ошибок перепечатать фразу (регистр и знаки не важны).", style = MaterialTheme.typography.bodySmall)
                        "selfie" -> Text("Сфотографируйся фронтальной камерой — выключится, когда на фото есть лицо. Селфи копятся: ⋮ → «Селфи будильника» → коллаж месяца.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.mission != "none" && !e.full) Text("Задание работает только с полноэкранным будильником.", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
                }
                if (a0.id != 0L) PillButton("Удалить", {
                    AppScope.io.launch { AlarmScheduler.cancel(ctx, a0); dao.delete(a0); AlarmExtStore.remove(ctx, a0.id); AlarmScheduler.syncAll(ctx) }; onDone()
                }, Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = (e.date?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().plusDays(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton({ dp.selectedDateMillis?.let { e = e.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()); a = a.copy(daysMask = 0) }; pickDate = false }) { Text("Ок") }
        }, dismissButton = { TextButton({ pickDate = false }) { Text("Отмена") } }) { DatePicker(dp, showModeToggle = false) }
    }
    newTag?.let { t0 ->
        var t by remember { mutableStateOf(t0) }
        AlertDialog(onDismissRequest = { newTag = null }, title = { Text("Новый тег") }, text = { AppTextField(t, { t = it.replace("#", "").replace(",", "") }, "Например: учёба, работа") },
            confirmButton = { TextButton({ val n = t.trim(); if (n.isNotBlank()) e = e.copy(tags = (e.tags + n).distinct()); newTag = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ newTag = null }) { Text("Отмена") } })
    }
}

private fun Modifier.alphaIf(dim: Boolean) = if (dim) this.then(Modifier.background(Color.Transparent)) else this

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        content()
    }
}

@Composable
private fun SwitchRow(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(text, modifier = Modifier.weight(1f)); Switch(on, onChange) }
}

@Composable
private fun ChoiceRow(items: List<Pair<String, String>>, sel: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { (k, t) -> Chip(t, k == sel, { onPick(k) }, Modifier.weight(1f)) } }
}

/** Общие настройки: «Отложить» — включено ли и на сколько минут (выбирается заранее, а не утром). */
@Composable
private fun AlarmSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AlarmSettings.snoozeOn(ctx)) }
    var min by remember { mutableIntStateOf(AlarmSettings.snoozeMin(ctx)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Настройки будильника") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow("Кнопка «Отложить»", on) { on = it }
            if (on) {
                Text("Отложить на", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(3, 5, 10, 15, 20, 30).forEach { m -> Chip("$m", min == m, { min = m }, Modifier.weight(1f)) } }
            }
        }
    }, confirmButton = { TextButton({ AlarmSettings.set(ctx, on, min); onDismiss() }) { Text("Готово") } })
}

/** Селфи будильника по месяцам + коллаж месяца (сохраняется в «Галерею»). */
@Composable
private fun SelfiesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val months = remember(ver) { AlarmSelfies.byMonth(ctx) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss)
                Text("Селфи будильника", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
            }
            if (months.isEmpty()) EmptyHint("Селфи появятся, когда будильник с заданием «Селфи» прозвонит")
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                months.forEach { (ym, files) ->
                    item(key = ym.toString()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ym.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() } + " · ${files.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton({
                                AppScope.io.launch {
                                    val b = AlarmSelfies.collage(files) ?: return@launch
                                    val ok = runCatching {
                                        val v = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "alarm_selfies_$ym.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Всё в одном") }
                                        val u = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                                        ctx.contentResolver.openOutputStream(u)!!.use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                                    }.isSuccess
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { android.widget.Toast.makeText(ctx, if (ok) "Коллаж сохранён в «Галерею»" else "Не удалось сохранить", android.widget.Toast.LENGTH_SHORT).show() }
                                }
                            }) { Text("Коллаж") }
                        }
                    }
                    item(key = "$ym-grid") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            files.chunked(4).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    row.forEach { f -> AppImage(f.absolutePath, Modifier.weight(1f).aspectRatio(1f), maxPx = 300, radius = 10.dp) }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
