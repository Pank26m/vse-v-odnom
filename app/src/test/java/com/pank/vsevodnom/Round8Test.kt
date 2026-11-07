package com.pank.vsevodnom

import com.pank.vsevodnom.data.AlertsApi
import com.pank.vsevodnom.data.MoodEntry
import com.pank.vsevodnom.data.Power
import com.pank.vsevodnom.ui.home.kyivAlertLevel
import com.pank.vsevodnom.ui.life.MoodMath
import com.pank.vsevodnom.ui.todo.NoteAtt
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Round8Test {
    private fun kyiv(level: String) = AlertsApi.parseOfficial(JSONObject().put("alerts", JSONArray().put(JSONObject()
        .put("location_type", "city").put("location_title", "м. Київ").put("location_oblast", "м. Київ").put("alert_level", level)
        .put("started_at", "2026-11-07T08:00:00.000Z").put("alert_type", "air_raid"))).toString(), 0)

    /** Жёлтая тревога в Киеве — жёлтый дрон, а не красная ракета (так было в 0.13: плитка красная, карта жёлтая). */
    @Test fun kyivLevelFollowsAlertLevel() {
        assertEquals(1, kyivAlertLevel(kyiv("yellow")))
        assertEquals(2, kyivAlertLevel(kyiv("red")))
        assertEquals(0, kyivAlertLevel(AlertsApi.parseOfficial("{\"alerts\":[]}", 0)))
    }

    private fun mood(day: Int, level: Int) = MoodEntry(level = level, at = LocalDate.of(2026, 10, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    @Test fun moodStability() {
        assertEquals(100, MoodMath.stability((1..10).map { mood(it, 4) }))
        assertEquals(0, MoodMath.stability((1..10).map { mood(it, if (it % 2 == 0) 1 else 5) }))
        assertEquals(null, MoodMath.stability(listOf(mood(1, 3), mood(1, 5))))   // один день — не считаем
        assertEquals(listOf(0, 0, 1, 0, 2), MoodMath.counts(listOf(mood(1, 3), mood(2, 5), mood(3, 5))).toList())
        assertEquals(100, MoodMath.score(listOf(mood(1, 5))))
    }

    @Test fun attachmentsRoundTrip() {
        val a = NoteAtt(photos = listOf("/p.jpg"), files = listOf("/f/1_doc.pdf\tdoc.pdf"), contacts = listOf("Мама\t+380501112233"), place = Triple("Дом", 50.45, 30.52))
        val b = NoteAtt.of(a.toJson())
        assertEquals(a, b)
        assertTrue(NoteAtt.of("").isEmpty())
        // старые заметки без новых полей читаются
        assertEquals(listOf("/a.m4a"), NoteAtt.of("{\"photos\":[],\"audio\":[\"/a.m4a\"],\"drawings\":[]}").audio)
    }

    @Test fun yasnoPossibleSlots() {
        val json = """{"3.1":{"today":{"slots":[{"start":480,"end":720,"type":"Definite"},{"start":900,"end":960,"type":"Possible"}],"status":"ScheduleApplies"},"tomorrow":{"slots":[]}}}"""
        val (off, em, maybe) = Power.parseYasnoFull(json, "3.1", LocalDate.of(2026, 11, 7))
        assertEquals(1, off.size); assertEquals(480, off[0].fromMin); assertEquals(false, em)
        assertEquals(1, maybe.size); assertEquals(900, maybe[0].fromMin)
    }
}
