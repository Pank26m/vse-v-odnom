package com.pank.vsevodnom

import com.pank.vsevodnom.data.Holidays
import com.pank.vsevodnom.data.Power
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HolidayTest {
    @Test fun easter() {
        assertEquals("Пасха", Holidays.on(LocalDate.of(2025, 4, 20)))
        assertEquals("Пасха", Holidays.on(LocalDate.of(2026, 4, 12)))
        assertEquals("Пасха", Holidays.on(LocalDate.of(2024, 5, 5)))
    }
    @Test fun yasno() {
        val json = """{"3.1":{"today":{"slots":[{"start":0,"end":240,"type":"NotPlanned"},{"start":480,"end":720,"type":"Definite"}],"status":"ScheduleApplies"},"tomorrow":{"slots":[{"start":1200,"end":1440,"type":"Definite"}],"status":"EmergencyShutdowns"}}}"""
        val (o, em) = Power.parseYasno(json, "3.1", LocalDate.of(2026, 11, 6))
        assertEquals(2, o.size); assertEquals("08:00–12:00", o[0].label()); assertTrue(em)
    }
}
