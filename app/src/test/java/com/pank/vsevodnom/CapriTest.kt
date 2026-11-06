package com.pank.vsevodnom

import com.pank.vsevodnom.ui.capri.CapriParser
import com.pank.vsevodnom.ui.capri.Cmd
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapriTest {
    private val now = LocalDateTime.of(2026, 11, 6, 10, 0) // пятница
    private fun p(s: String) = CapriParser.parse(s, now)

    @Test fun events() {
        val e = p("Добавь встречу с Олегом завтра в 15:30 на 2 часа, напомни за 30 минут, важно") as Cmd.Event
        assertEquals("Встречу с Олегом", e.title); assertEquals(LocalDateTime.of(2026, 11, 7, 15, 30), e.start)
        assertEquals(120, e.minutes); assertEquals(30, e.remindBefore); assertTrue(e.important)
        val e2 = p("запиши стоматолог в понедельник в 9 утра") as Cmd.Event
        assertEquals(LocalDate.of(2026, 11, 9), e2.date); assertEquals(LocalTime.of(9, 0), e2.start!!.toLocalTime()); assertEquals("Стоматолог", e2.title)
        val e3 = p("день рождения мамы 12 декабря весь день") as Cmd.Event
        assertEquals(LocalDate.of(2026, 12, 12), e3.date); assertTrue(e3.allDay)
        val e4 = p("созвон в 3 часа дня до 16:30") as Cmd.Event
        assertEquals(LocalTime.of(15, 0), e4.start!!.toLocalTime()); assertEquals(90, e4.minutes)
    }
    @Test fun others() {
        assertTrue(p("что по тревогам?") is Cmd.Alerts)
        assertEquals(82.5, (p("вес 82,5") as Cmd.Weight).kg)
        assertEquals(4, (p("настроение хорошее") as Cmd.Mood).level)
        assertEquals(600L, (p("таймер 10 минут") as Cmd.Timer).seconds)
        assertEquals(LocalTime.of(7, 30), (p("поставь будильник на 7:30") as Cmd.Alarm).time)
        assertEquals(LocalDate.of(2026, 11, 7), (p("какие пары завтра") as Cmd.Lessons).date)
        val c = p("добавь в корзину https://prom.ua/p12345-drel.html для дачи") as Cmd.Cart
        assertEquals("https://prom.ua/p12345-drel.html", c.url)
        val pg = p("отложи 500 на отпуск") as Cmd.Piggy
        assertEquals(500.0, pg.amount); assertEquals("отпуск", pg.goal)
        assertEquals("Миша", (p("зови меня миша") as Cmd.Name).name)
        assertTrue(p("курс доллара") is Cmd.Rates)
    }
}
