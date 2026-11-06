package com.pank.vsevodnom

import com.pank.vsevodnom.data.KeePassImport
import com.pank.vsevodnom.data.parseCsv
import org.junit.Test
import kotlin.test.assertEquals

class ImportTest {
    @Test fun csvQuotes() {
        val r = parseCsv("a,b,c\n\"x, y\",\"he said \"\"hi\"\"\",\"line1\nline2\"\n")
        assertEquals(listOf("x, y", "he said \"hi\"", "line1\nline2"), r[1])
    }
    @Test fun keepass2Csv() {
        val csv = "\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\n\"Gmail\",\"misha@gmail.com\",\"p,a\"\"ss\",\"https://mail.google.com\",\"\"\n\"Mono\",\"380\",\"123\",\"\",\"note\"\n"
        val e = KeePassImport.fromCsv(csv)
        assertEquals(2, e.size); assertEquals("Gmail", e[0].title); assertEquals("p,a\"ss", e[0].password); assertEquals("note", e[1].notes)
    }
    @Test fun keepassXcCsv() {
        val csv = "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\"\n\"Root\",\"Steam\",\"misha\",\"s3cr3t\",\"https://store.steampowered.com\",\"\",\"\"\n"
        val e = KeePassImport.fromCsv(csv)
        assertEquals("Steam", e[0].title); assertEquals("misha", e[0].login)
    }
}

class WeightCsvTest {
    @Test fun generic() {
        val r = com.pank.vsevodnom.data.WeightCsv.parse("Date,Weight (kg),Note\n2025-01-06,84.2,\n06.01.2025 08:00,84.0,утро\n2025-01-13,\"83,7\",\n")
        assertEquals(2, r.size); assertEquals(83.7, r[1].kg, 1e-9)
    }
}

/** Проверка на реальном экспорте владельца (файл в репозиторий не кладём): WEIGHT_CSV=путь. */
class WeightRealFileTest {
    @Test fun betterWeight() {
        val path = System.getenv("WEIGHT_CSV") ?: return
        val r = com.pank.vsevodnom.data.WeightCsv.parse(java.io.File(path).readText())
        println("rows=${r.size} first=${r.first()} last=${r.last()} min=${r.minOf { it.kg }} max=${r.maxOf { it.kg }}")
        assertEquals(java.time.LocalDate.of(2026, 11, 2), r.last().day)
        assertEquals(115.4, r.last().kg, 1e-9)
    }
}
