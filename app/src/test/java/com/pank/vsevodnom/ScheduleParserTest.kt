package com.pank.vsevodnom

import com.pank.vsevodnom.data.schedule.ScheduleParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка на реальном файле из портала. Путь — в переменной SCHEDULE_XLS (сам файл в репозиторий не кладём). */
class ScheduleParserTest {
    @Test fun parsesPortalFile() {
        val path = System.getenv("SCHEDULE_XLS")
        assumeTrue(path != null && File(path).exists())
        val s = ScheduleParser.parse(File(path!!).readBytes())
        s.lessons.forEach { println(it) }
        println("group=${s.group} ${s.from}..${s.to} total=${s.lessons.size}")
        assertEquals("МБГ-23-2", s.group)
        assertEquals(LocalDate.of(2026, 11, 5), s.from)
        val thu = s.lessons.filter { it.date == LocalDate.of(2026, 11, 5) }
        assertEquals(2, thu.size)
        assertEquals("ТіВ", thu[0].subject); assertEquals("Пз", thu[0].kind); assertEquals("194", thu[0].room)
        assertEquals("Бистров Д.О.", thu[0].teacher); assertEquals("09:00", thu[0].start)
        assertTrue(s.lessons.any { it.room == "Дистанційно" })
    }
}
