package com.pank.vsevodnom.data.schedule

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.data.CalEvent
import com.pank.vsevodnom.data.Lesson
import com.pank.vsevodnom.data.SubjectAlias
import com.pank.vsevodnom.widget.ScheduleWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Один пункт повестки дня — пара, своё событие или событие из системного календаря. */
sealed class Agenda {
    abstract val sortKey: LocalDateTime
    abstract val date: LocalDate

    data class Pair(val l: Lesson, val alias: SubjectAlias?) : Agenda() {
        override val date: LocalDate get() = LocalDate.ofEpochDay(l.epochDay)
        override val sortKey: LocalDateTime get() = date.atTime(LocalTime.parse(l.start))
        val title get() = alias?.fullName?.takeIf { it.isNotBlank() } ?: l.subject
        val tint get() = alias?.tint ?: tintFor(l.subject)
    }

    data class Own(val e: CalEvent, override val date: LocalDate) : Agenda() {
        override val sortKey: LocalDateTime
            get() = if (e.allDay) date.atStartOfDay() else
                LocalDateTime.ofInstant(Instant.ofEpochMilli(e.startMillis), ZoneId.systemDefault()).let { if (it.toLocalDate() < date) date.atStartOfDay() else it }
    }

    data class Sys(
        val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
        val color: Int, val calendar: String, val location: String, override val date: LocalDate,
    ) : Agenda() {
        override val sortKey: LocalDateTime
            get() = if (allDay) date.atStartOfDay() else
                LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), ZoneId.systemDefault()).let { if (it.toLocalDate() < date) date.atStartOfDay() else it }
    }
}

/** Стабильный цвет предмета по названию, пока пользователь не выбрал свой. */
fun tintFor(s: String): Int = (s.hashCode() and 0x7fffffff) % 6

object CalendarRepo {
    fun hasSysPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun dayStart(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** События из системных календарей (Google Календарь и всё, что туда синхронизируется). */
    fun systemEvents(ctx: Context, from: LocalDate, toInclusive: LocalDate): List<Agenda.Sys> {
        if (!hasSysPermission(ctx)) return emptyList()
        val start = dayStart(from)
        val end = dayStart(toInclusive.plusDays(1))
        val b: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start); ContentUris.appendId(b, end)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = ArrayList<Agenda.Sys>()
        runCatching {
            ctx.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    val begin = c.getLong(2); val endMs = c.getLong(3)
                    // события на весь день хранятся в UTC
                    val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                    val d0 = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
                    val d1 = Instant.ofEpochMilli((endMs - 1).coerceAtLeast(begin)).atZone(zone).toLocalDate()
                    var d = maxOf(d0, from)
                    while (!d.isAfter(minOf(d1, toInclusive))) {
                        out += Agenda.Sys(
                            c.getLong(0), c.getString(1) ?: "(без названия)", begin, endMs, allDay,
                            c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "", d
                        )
                        d = d.plusDays(1)
                    }
                }
            }
        }
        return out
    }

    /** Синхронно собирает повестку на период — для виджета. */
    fun agendaSync(ctx: Context, from: LocalDate, toInclusive: LocalDate): List<Agenda> {
        val dao = AppDb.get(ctx).schedule()
        val aliases = dao.aliasesSync().associateBy { it.abbr }
        val lessons = dao.lessonsSync(from.toEpochDay(), toInclusive.toEpochDay()).map { Agenda.Pair(it, aliases[it.subject]) }
        val own = dao.eventsSync(dayStart(from), dayStart(toInclusive.plusDays(1))).flatMap { expandOwn(it, from, toInclusive) }
        return (lessons + own + systemEvents(ctx, from, toInclusive)).sortedBy { it.sortKey }
    }

    fun expandOwn(e: CalEvent, from: LocalDate, toInclusive: LocalDate): List<Agenda.Own> {
        val z = ZoneId.systemDefault()
        val d0 = Instant.ofEpochMilli(e.startMillis).atZone(z).toLocalDate()
        val d1 = Instant.ofEpochMilli((e.endMillis - 1).coerceAtLeast(e.startMillis)).atZone(z).toLocalDate()
        val out = ArrayList<Agenda.Own>()
        var d = maxOf(d0, from)
        while (!d.isAfter(minOf(d1, toInclusive))) { out += Agenda.Own(e, d); d = d.plusDays(1) }
        return out
    }

    data class ImportResult(val group: String, val from: LocalDate, val to: LocalDate, val count: Int, val newSubjects: List<String>)

    suspend fun importSchedule(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw XlsReader.XlsException("Не удалось открыть файл")
        val s = ScheduleParser.parse(bytes)
        val from = s.from ?: s.lessons.first().date
        val to = s.to ?: s.lessons.last().date
        val dao = AppDb.get(ctx).schedule()
        val known = dao.aliasesSync().map { it.abbr }.toSet()
        dao.replaceRange(from.toEpochDay(), to.toEpochDay(), s.lessons.map {
            Lesson(epochDay = it.date.toEpochDay(), pair = it.pair, start = it.start, end = it.end,
                subject = it.subject, kind = it.kind, room = it.room, teacher = it.teacher)
        })
        val subjects = s.lessons.map { it.subject }.distinct()
        subjects.filter { it !in known }.forEach { dao.upsertAlias(SubjectAlias(it, "", tintFor(it))) }
        ScheduleWidget.refresh(ctx)
        ImportResult(s.group, from, to, s.lessons.size, subjects.filter { it !in known })
    }
}
