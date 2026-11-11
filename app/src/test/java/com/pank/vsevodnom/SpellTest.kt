package com.pank.vsevodnom

import com.goodwy.keyboard.vse.KDict
import com.goodwy.keyboard.vse.VseSpell
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Словари проверки орфографии клавиатуры (assets/kdict, tools/build_kdict.py). */
class SpellTest {
    private val dir = listOf("../keyboard/src/main/assets/kdict", "keyboard/src/main/assets/kdict").map(::File).first { it.exists() }
    private val dicts = HashMap<String, KDict>()
    private fun d(l: String): KDict = dicts.getOrPut(l) { RandomAccessFile(File(dir, "$l.kdict"), "r").channel.use { KDict(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())) } }

    @Test fun knownWords() {
        for (w in listOf("привет", "приветствую", "собака", "собаками", "ёлка", "щас")) assertTrue(d("ru").contains(w), w)
        for (w in listOf("україна", "дякую", "п'ять", "вечеря")) assertTrue(d("uk").contains(w), w)
        for (w in listOf("hello", "world", "doesn't")) assertTrue(d("en").contains(w), w)
    }

    @Test fun typosFlagged() {
        assertFalse(VseSpell.okIn(::d, "превет"))
        assertFalse(VseSpell.okIn(::d, "сабака"))
        assertFalse(VseSpell.okIn(::d, "helo"))
        assertTrue(VseSpell.okIn(::d, "Привет"))
        assertTrue(VseSpell.okIn(::d, "кто-нибудь"))
        assertTrue(VseSpell.okIn(::d, "+380501234567"))
        assertTrue(VseSpell.okIn(::d, "ЗСУ"))
    }

    @Test fun suggestions() {
        val t0 = System.currentTimeMillis()
        assertTrue("привет" in VseSpell.suggestIn(::d, "превет"), VseSpell.suggestIn(::d, "превет").toString())
        assertTrue("собака" in VseSpell.suggestIn(::d, "сабака"))
        assertTrue("Hello" in VseSpell.suggestIn(::d, "Helo"), VseSpell.suggestIn(::d, "Helo").toString())
        assertTrue("дякую" in VseSpell.suggestIn(::d, "дякую".replace('я', 'а')))
        println("подсказки за ${System.currentTimeMillis() - t0} мс")
    }
}

/** Калькулятор в строке клавиатуры: «250*3» → «750», телефоны и даты — не примеры. */
class KbCalcTest {
    private fun c(s: String) = com.goodwy.keyboard.vse.VseWatch.calcOf(s)?.first
    @Test fun math() {
        kotlin.test.assertEquals("750", c("итого 250*3"))
        kotlin.test.assertEquals("12,5", c("10+2,5"))
        kotlin.test.assertEquals("21", c("(3+4)×3="))
        kotlin.test.assertEquals("50", c("200*25%"))
        kotlin.test.assertEquals(null, c("+380 50 123 45 67"))
        kotlin.test.assertEquals(null, c("12.11.2026"))
        kotlin.test.assertEquals(null, c("просто текст 42"))
    }
}
