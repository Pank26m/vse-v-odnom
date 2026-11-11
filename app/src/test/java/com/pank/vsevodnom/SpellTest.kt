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

/** Пароли в клавиатуре: подбор записи по приложению (и кириллицей), код 2FA. */
class KbVaultTest {
    private fun e(t: String, url: String = "") = com.goodwy.keyboard.vse.VseVault.Entry(t, "", "", "", url, emptyList())
    @Test fun matching() {
        val V = com.goodwy.keyboard.vse.VseVault
        kotlin.test.assertEquals("instagram", V.keyOf("com.instagram.android"))
        kotlin.test.assertEquals("telegram", V.keyOf("org.telegram.messenger"))
        assertTrue(V.matches(e("Инстаграм"), "instagram"))
        assertTrue(V.matches(e("Телеграм"), "telegram"))
        assertTrue(V.matches(e("Почта", "https://instagram.com"), "instagram"))
        assertFalse(V.matches(e("Банк"), "instagram"))
    }
    @Test fun totpRfc() {
        // RFC 6238: секрет «12345678901234567890» (base32 GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ), t=59 c → 287082 (6 цифр)
        kotlin.test.assertEquals("287082", com.goodwy.keyboard.vse.VseVault.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59_000))
    }
}
