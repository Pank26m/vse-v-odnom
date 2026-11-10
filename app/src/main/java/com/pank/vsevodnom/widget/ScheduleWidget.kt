package com.pank.vsevodnom.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.schedule.Agenda
import com.pank.vsevodnom.data.schedule.CalendarRepo
import com.pank.vsevodnom.ui.theme.Tints
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

fun dayTitle(d: LocalDate): String {
    val today = LocalDate.now()
    val base = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    return when (d) {
        today -> "Сегодня, $base"
        today.plusDays(1) -> "Завтра, $base"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } + ", " + base
    }
}

/** Строки для виджета/списков: время, заголовок, подпись, цвет полоски. */
data class AgendaRow(val time: String, val title: String, val sub: String, val color: Int)

/** «09:00\n11:00» — начало и конец (конец не показываем, если совпадает или событие без длительности). */
private fun span(a: Long, b: Long): String {
    val z = ZoneId.systemDefault()
    val s = Instant.ofEpochMilli(a).atZone(z).toLocalTime().format(HM)
    if (b <= a) return s
    return s + "\n" + Instant.ofEpochMilli(b).atZone(z).toLocalTime().format(HM)
}

fun Agenda.row(): AgendaRow = when (this) {
    is Agenda.Pair -> AgendaRow(
        "${l.start}\n${l.end}",
        title + if (l.kind.isNotBlank()) " [${l.kind}]" else "",
        listOf(if (l.room.isNotBlank()) (if (l.room.first().isDigit()) "ауд. ${l.room}" else l.room) else null, l.teacher.ifBlank { null })
            .filterNotNull().joinToString(" · "),
        Tints.all[tint % Tints.all.size].ink.toArgb()
    )
    is Agenda.Own -> AgendaRow(
        if (e.allDay) "весь день" else span(e.startMillis, e.endMillis),
        e.title, e.note, Tints.all[e.tint % Tints.all.size].ink.toArgb()
    )
    is Agenda.Sys -> AgendaRow(
        if (allDay) "весь день" else span(begin, end),
        title, listOf(location.ifBlank { null }, calendar.ifBlank { null }).filterNotNull().joinToString(" · "),
        color or 0xFF000000.toInt()
    )
}

class ScheduleWidget : AppWidgetProvider() {
    companion object {
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, ScheduleWidget::class.java))
            if (ids.isEmpty()) return
            @Suppress("DEPRECATION")
            m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
            ids.forEach { update(ctx, m, it) }
        }

        private fun update(ctx: Context, m: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_schedule)
            v.setTextViewText(R.id.w_title, dayTitle(LocalDate.now()))
            val svc = Intent(ctx, ScheduleWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.w_list, svc)
            v.setEmptyView(R.id.w_list, R.id.w_empty)
            val open = PendingIntent.getActivity(
                ctx, 20, Intent(ctx, MainActivity::class.java).putExtra("route", "calendar"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setOnClickPendingIntent(R.id.w_title, open)
            v.setPendingIntentTemplate(R.id.w_list, open)
            m.updateAppWidget(id, v)
        }
    }

    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, m, it) }
        @Suppress("DEPRECATION")
        m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == Intent.ACTION_DATE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) refresh(ctx)
    }
}

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private sealed class Item {
            data class Day(val title: String) : Item()
            data class Row(val r: AgendaRow) : Item()
        }
        private var items: List<Item> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            val today = LocalDate.now()
            val now = java.time.LocalDateTime.now()
            val agenda = runCatching { CalendarRepo.agendaSync(ctx, today, today.plusDays(13)) }.getOrDefault(emptyList())
                .filter { a ->
                    // прошедшие сегодняшние пары не показываем
                    if (a.date != today) true else when (a) {
                        is Agenda.Pair -> java.time.LocalTime.parse(a.l.end) > now.toLocalTime()
                        is Agenda.Own -> a.e.allDay || a.e.endMillis > System.currentTimeMillis()
                        is Agenda.Sys -> a.allDay || a.end > System.currentTimeMillis()
                    }
                }
            val out = ArrayList<Item>()
            agenda.groupBy { it.date }.toSortedMap().forEach { (d, list) ->
                if (d != today) out += Item.Day(dayTitle(d))
                list.forEach { out += Item.Row(it.row()) }
            }
            items = out
        }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val it = items.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_day)
            return when (it) {
                is Item.Day -> RemoteViews(ctx.packageName, R.layout.widget_day).apply { setTextViewText(R.id.w_day, it.title) }
                is Item.Row -> RemoteViews(ctx.packageName, R.layout.widget_item).apply {
                    setTextViewText(R.id.w_item_title, it.r.title)
                    setTextViewText(R.id.w_item_sub, it.r.sub)
                    setTextViewText(R.id.w_item_time, it.r.time)
                    setInt(R.id.w_bar, "setColorFilter", it.r.color)
                    setOnClickFillInIntent(R.id.w_item_title, Intent())
                }
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
