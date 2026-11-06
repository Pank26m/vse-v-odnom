package com.pank.vsevodnom

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** На Android регулярки работают через ICU: флага (?U) там нет — падение при первом использовании. */
class RegexSafetyTest {
    @Test fun noUnicodeCharacterClassFlag() {
        val root = File("src/main/java")
        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (Regex("""\(\?[a-zA-Z]*U""").containsMatchIn(l) || "UNICODE_CHARACTER_CLASS" in l) "${f.name}:${i + 1}" else null }
        }.toList()
        assertTrue(bad.isEmpty(), "Флаг (?U) не поддерживается на Android: $bad")
    }
}
