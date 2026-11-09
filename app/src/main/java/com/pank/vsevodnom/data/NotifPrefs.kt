package com.pank.vsevodnom.data

import android.content.Context

/** Какие уведомления присылать (Настройки → Уведомления). Обязательные для Android (звонок, запись, плеер) выключить нельзя. */
object NotifPrefs {
    data class Type(val key: String, val title: String, val sub: String, val canOff: Boolean = true, val channel: String? = null)

    val TYPES = listOf(
        Type("tasks", "Задачи ToDo", "напоминания о задачах со временем"),
        Type("habits", "Привычки", "напоминания о привычках на сегодня"),
        Type("events", "События календаря", "«Скоро: …» перед событием"),
        Type("alerts", "Тревоги", "тревога, угроза и отбой в Киеве"),
        Type("power", "Свет", "за 30 минут до отключения по графику"),
        Type("weight", "Взвешивание", "по понедельникам утром"),
        Type("focus", "Фокус", "сессия фокуса завершена"),
        Type("sms", "СМС", "новые сообщения"),
        Type("callerid", "Определитель номера", "кто звонит, если номера нет в контактах"),
        Type("commands", "Команды", "уведомления из своих команд"),
        Type("alarm", "Будильник и таймер", "звонок будильника и таймера — выключается в самих будильниках", canOff = false, channel = "alarm"),
        Type("calls", "Звонки", "экран входящего звонка — нужен для звонилки", canOff = false),
        Type("track", "Запись трека и навигация", "значок в шторке, пока идёт запись — так требует Android", canOff = false),
        Type("music", "Музыка", "плеер в шторке", canOff = false),
        Type("rec", "Диктофон", "значок в шторке, пока идёт запись", canOff = false),
        Type("dl", "Загрузки", "прогресс скачивания", canOff = false),
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences("notif_on", Context.MODE_PRIVATE)
    fun on(ctx: Context, key: String) = p(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
}
