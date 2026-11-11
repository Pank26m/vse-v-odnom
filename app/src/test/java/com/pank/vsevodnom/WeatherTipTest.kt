package com.pank.vsevodnom

import com.pank.vsevodnom.data.DayW
import com.pank.vsevodnom.data.HourW
import com.pank.vsevodnom.data.WeatherData
import com.pank.vsevodnom.ui.home.weatherTip
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTipTest {
    private val today = LocalDate.of(2026, 11, 11)
    private fun data(rainy: (LocalDateTime) -> Boolean): WeatherData {
        val hrs = (0 until 48).map { i -> val t = today.atStartOfDay().plusHours(i.toLong()); HourW(t, 8.0, if (rainy(t)) 80 else 5, if (rainy(t)) 61 else 2, true) }
        val days = listOf(DayW(today, 61, 10.0, 5.0, 80, "", "", 1.0), DayW(today.plusDays(1), 2, 10.0, 5.0, 5, "", "", 1.0))
        return WeatherData(8.0, 7.0, 80, 2, true, 1010.0, 2.0, 0, 3.0, 50, 0.0, null, hrs, days, hrs, null, null, 0L)
    }

    @Test fun pastRainNotAdvisedAtNight() {
        // дождь был днём (10–16), сейчас 22:00 и завтра сухо — совета нет
        assertNull(weatherTip(data { it.toLocalDate() == today && it.hour in 10..16 }, today.atTime(22, 0)))
    }

    @Test fun tomorrowRainAtNight() {
        val tip = weatherTip(data { it.toLocalDate() == today.plusDays(1) && it.hour in 9..12 }, today.atTime(22, 0))
        assertEquals("Завтра дождь с 9:00 — приготовь зонт", tip)
    }

    @Test fun laterTodayRain() {
        val tip = weatherTip(data { it.toLocalDate() == today && it.hour in 15..18 }, today.atTime(9, 30))
        assertEquals("Дождь с 15:00 — возьми зонт", tip)
    }

    @Test fun rainingNowUntil() {
        val tip = weatherTip(data { it.toLocalDate() == today && it.hour in 8..11 }, today.atTime(9, 10))
        assertTrue(tip!!.startsWith("Дождь до 12:00"), tip)
    }
}
