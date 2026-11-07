package com.pank.vsevodnom

import com.pank.vsevodnom.call.t9Match
import com.pank.vsevodnom.data.SmsRepo
import com.pank.vsevodnom.ui.contacts.Contact
import com.pank.vsevodnom.ui.money.PALETTE
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Round9Test {
    /** От незнакомого номера коды из 4 и 6 цифр уходят в «Коды» и без слова «код»; от контактов — нет; суммы и годы — не коды. */
    @Test fun bareCodes() {
        assertEquals("4821", SmsRepo.extractCode("4821 — ваш вход в приложение", fromContact = false))
        assertEquals("482913", SmsRepo.extractCode("Нікому не кажіть: 482913", fromContact = false))
        assertNull(SmsRepo.extractCode("Привет! Буду в 1900", fromContact = true))
        assertNull(SmsRepo.extractCode("Оплата 1500 грн", fromContact = false))
        assertNull(SmsRepo.extractCode("Акция до 2026 года", fromContact = false))
    }

    @Test fun t9() {
        val c = Contact(1, "k", "Аня Петрова", false, listOf("моб." to "+380 50 123 45 67"), emptySet())
        assertTrue(t9Match(c, "259"))         // А-Н-Я
        assertTrue(t9Match(c, "53"))          // П-е
        assertTrue(t9Match(c, "1234"))        // по цифрам номера
        assertTrue(!t9Match(c, "99"))
    }

    @Test fun iconsAndPalette() {
        val o = JSONObject(File("src/main/assets/icons/tabler.json").readText())
        val icons = o.getJSONObject("icons")
        assertTrue(icons.length() >= 250, "иконок ${icons.length()}")
        val g = o.getJSONArray("groups")
        assertEquals(16, g.length())
        for (i in 0 until g.length()) { val a = g.getJSONObject(i).getJSONArray("icons"); for (k in 0 until a.length()) assertTrue(icons.has(a.getString(k))) }
        assertTrue(File("src/main/assets/icons/TABLER_LICENSE.txt").readText().contains("MIT"))
        assertEquals(19, PALETTE.size); PALETTE.forEach { assertEquals(8, it.size) }
    }

    @Test fun giftIdeas() {
        val l = File("src/main/assets/gifts.txt").readLines().filter { it.isNotBlank() }
        l.forEach { assertEquals(2, it.split('|').size, it) }
        assertTrue(l.map { it.substringAfter('|').lowercase() }.distinct().size >= 500, "идей ${l.size}")
        assertTrue(l.map { it.substringBefore('|') }.distinct().size >= 15)
    }
}
