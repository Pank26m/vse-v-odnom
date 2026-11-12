package com.pank.vsevodnom.ui.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.schedule.Agenda
import com.pank.vsevodnom.ui.map.BottomPanel
import com.pank.vsevodnom.ui.map.MenuRow
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Правка событий системного календаря (Google): перенос и удаление — изменения уходят в Google при синхронизации. */
object SysEvents {
    fun canWrite(ctx: Context) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    /** Повторяющееся? (тогда перенос/удаление затронут всю серию — их делаем в календаре). */
    fun isRecurring(ctx: Context, id: Long): Boolean = runCatching {
        ctx.contentResolver.query(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.RRULE), null, null, null)
            ?.use { it.moveToFirst() && !it.getString(0).isNullOrBlank() } ?: false
    }.getOrDefault(false)
    fun move(ctx: Context, e: Agenda.Sys, start: LocalDateTime): Boolean = runCatching {
        val dur = (e.end - e.begin).coerceAtLeast(if (e.allDay) 86_400_000L else 3_600_000L)
        val begin = if (e.allDay) start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() else start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val v = ContentValues().apply { put(CalendarContract.Events.DTSTART, begin); put(CalendarContract.Events.DTEND, begin + dur) }
        ctx.contentResolver.update(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), v, null, null) > 0
    }.getOrDefault(false)
    fun delete(ctx: Context, e: Agenda.Sys): Boolean = runCatching {
        ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), null, null) > 0
    }.getOrDefault(false)
}

/** Окно события Google Календаря: время, календарь, место; перенести, удалить, открыть в календаре. */
@Composable
fun SysEventSheet(e: Agenda.Sys, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var recurring by remember { mutableStateOf(false) }
    var askDel by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(e.id) { recurring = withContext(Dispatchers.IO) { SysEvents.isRecurring(ctx, e.id) } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) pending?.invoke() else Toast.makeText(ctx, "Без доступа к календарю менять события нельзя", Toast.LENGTH_SHORT).show(); pending = null }
    fun withWrite(f: () -> Unit) { if (SysEvents.canWrite(ctx)) f() else { pending = f; perm.launch(Manifest.permission.WRITE_CALENDAR) } }
    val z = ZoneId.systemDefault()
    val start = if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDateTime() else Instant.ofEpochMilli(e.begin).atZone(z).toLocalDateTime()
    val endT = Instant.ofEpochMilli(e.end).atZone(z).toLocalDateTime()
    fun reschedule() {
        // дата → время (у события на весь день — только дата)
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            val date = java.time.LocalDate.of(y, m + 1, d)
            if (e.allDay) AppScope.io.launch { val ok = SysEvents.move(ctx, e, date.atStartOfDay()); withContext(Dispatchers.Main) { done(ctx, ok, "Перенесено"); if (ok) onChanged() } }
            else android.app.TimePickerDialog(ctx, { _, h, min ->
                AppScope.io.launch { val ok = SysEvents.move(ctx, e, date.atTime(h, min)); withContext(Dispatchers.Main) { done(ctx, ok, "Перенесено"); if (ok) onChanged() } }
            }, start.hour, start.minute, true).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    BottomPanel(onDismiss) {
        Text(e.title, style = MaterialTheme.typography.titleLarge)
        Text((if (e.allDay) start.format(DateTimeFormatter.ofPattern("d MMMM, весь день", java.util.Locale("ru")))
            else start.format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale("ru"))) + "–" + endT.format(DateTimeFormatter.ofPattern("HH:mm"))),
            style = MaterialTheme.typography.bodyLarge, color = Pal.Muted)
        if (e.calendar.isNotBlank()) Text("Календарь: ${e.calendar}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        if (e.location.isNotBlank()) Text("Место: ${e.location}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        if (recurring) Text("Это повторяющееся событие — перенос и удаление затронут всю серию, поэтому их лучше делать в календаре.", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
        else {
            MenuRow(Icons.Rounded.EditCalendar, "Перенести", sub = "новая дата и время, длительность та же") { withWrite { reschedule() } }
            MenuRow(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.RoseInk) { askDel = true }
        }
        MenuRow(Icons.Rounded.OpenInNew, "Открыть в календаре") {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))) }
                .onFailure { Toast.makeText(ctx, "Приложение календаря не найдено", Toast.LENGTH_SHORT).show() }
        }
    }
    if (askDel) AlertDialog(onDismissRequest = { askDel = false }, title = { Text("Удалить «${e.title}»?") }, text = { Text("Событие удалится и из Google Календаря.") },
        confirmButton = { TextButton({ askDel = false; withWrite { AppScope.io.launch { val ok = SysEvents.delete(ctx, e); withContext(Dispatchers.Main) { done(ctx, ok, "Удалено"); if (ok) { onChanged(); onDismiss() } } } } }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ askDel = false }) { Text("Отмена") } })
}

private fun done(ctx: Context, ok: Boolean, msg: String) = Toast.makeText(ctx, if (ok) msg else "Не получилось — календарь не разрешил изменить событие", Toast.LENGTH_SHORT).show()
