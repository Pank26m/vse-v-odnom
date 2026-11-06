package com.pank.vsevodnom

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.pank.vsevodnom.data.Habit
import com.pank.vsevodnom.data.HabitLog
import com.pank.vsevodnom.data.MovieHit
import com.pank.vsevodnom.data.Movies
import com.pank.vsevodnom.data.Todo
import com.pank.vsevodnom.ui.todo.Rich
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoLogicTest {
    @Test fun richWrapAndRender() {
        val v = Rich.wrap(TextFieldValue("привет мир", TextRange(7, 10)), "**")
        assertEquals("привет **мир**", v.text)
        assertEquals("привет мир", Rich.render(v.text).text)
        assertEquals("•  пункт", Rich.render("- пункт").text)
        assertEquals("☑ сделано", Rich.render("[x] сделано").text)
        assertEquals("курсив и жирный", Rich.render("*курсив* и **жирный**").text)
    }
    @Test fun richListsContinue() {
        val old = TextFieldValue("1. первое", TextRange(9))
        val typed = TextFieldValue("1. первое\n", TextRange(10))
        assertEquals("1. первое\n2. ", Rich.onEdit(old, typed).text)
        // Enter на пустом пункте — выход из списка
        val o2 = TextFieldValue("- а\n- ", TextRange(6)); val t2 = TextFieldValue("- а\n- \n", TextRange(7))
        assertEquals("- а\n", Rich.onEdit(o2, t2).text)
        assertEquals("# Заголовок", Rich.linePrefix(TextFieldValue("Заголовок", TextRange(3)), "# ").text)
        assertEquals("Заголовок", Rich.linePrefix(TextFieldValue("# Заголовок", TextRange(3)), "# ").text)
    }
    @Test fun habitDue() {
        val mon = LocalDate.of(2026, 11, 2)
        val weekdays = Habit(id = 1, title = "x", daysMask = 0b0011111)
        assertTrue(Todo.isDue(weekdays, mon)); assertFalse(Todo.isDue(weekdays, mon.plusDays(5)))
        val every3 = Habit(id = 2, title = "y", freqType = 2, freqN = 3, startDay = mon.toEpochDay())
        assertTrue(Todo.isDue(every3, mon)); assertFalse(Todo.isDue(every3, mon.plusDays(1))); assertTrue(Todo.isDue(every3, mon.plusDays(3)))
        val twice = Habit(id = 3, title = "z", freqType = 1, freqN = 2)
        val logs = listOf(HabitLog(habitId = 3, epochDay = mon.toEpochDay(), count = 1), HabitLog(habitId = 3, epochDay = mon.plusDays(1).toEpochDay(), count = 1))
        assertFalse(Todo.isDue(twice, mon.plusDays(2), logs))   // норма недели выполнена
        val limited = Habit(id = 4, title = "w", startDay = mon.toEpochDay(), goalDays = 7)
        assertTrue(Todo.isDue(limited, mon.plusDays(6))); assertFalse(Todo.isDue(limited, mon.plusDays(7)))
    }
    @Test fun movieHitsFilter() {
        val hits = listOf(MovieHit("Q1", "Во все тяжкие", "американский телесериал 2008 года"), MovieHit("Q2", "Тяжкие", "деревня"), MovieHit("Q3", "Во все тяжкие", "фильм 2019 года"))
        assertEquals(listOf("Q1", "Q3"), Movies.parseHits(hits).map { it.id })
        assertEquals(1, Movies.parseHits(listOf(MovieHit("Q9", "x", "город"))).size)
    }
}
