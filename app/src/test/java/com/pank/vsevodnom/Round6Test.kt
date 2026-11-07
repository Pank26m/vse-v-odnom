package com.pank.vsevodnom

import com.pank.vsevodnom.data.Card
import com.pank.vsevodnom.data.PdfConvert
import com.pank.vsevodnom.data.Srs
import com.pank.vsevodnom.ui.files.Archives
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Round6Test {
    /** Словарь: все строки «слово|перевод», после удаления повторов — не меньше 4000 слов. */
    @Test fun englishDictionary() {
        val dir = File("src/main/assets/en")
        val order = listOf("A1", "A2", "B1", "B2", "C1")
        val files = order.map { File(dir, "$it.txt") } + dir.listFiles()!!.filter { it.nameWithoutExtension !in order }.sortedBy { it.name }
        val seen = HashSet<String>()
        files.forEach { f -> f.readLines().filter { it.isNotBlank() }.forEach { l ->
            assertEquals(1, l.count { it == '|' }, "${f.name}: «$l»")
            val (w, t) = l.split('|'); assertTrue(w.isNotBlank() && t.isNotBlank(), "${f.name}: «$l»")
            assertTrue(t.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }, "перевод по-русски: «$l»")
            seen += w.trim().lowercase()
        } }
        assertTrue(seen.size >= 4000, "уникальных слов ${seen.size}")
        // все файлы из списка наборов существуют
        val packs = File("src/main/java/com/pank/vsevodnom/data/Flashcards.kt").readText().let { Regex("Pack\\(\"(\\w+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
        packs.forEach { assertTrue(File(dir, "$it.txt").exists(), "нет файла набора $it") }
        assertEquals(files.size, packs.size, "каждый файл подключён набором")
    }

    @Test fun leitner() {
        var c = Card(deckId = 1, front = "cat", back = "кошка")
        val now = 1_000_000L
        c = Srs.answer(c, true, now); assertEquals(1, c.box); assertEquals(now + 86_400_000L, c.due)
        c = Srs.answer(c, true, now); assertEquals(2, c.box)
        c = Srs.answer(c, false, now); assertEquals(0, c.box); assertEquals(now + 60_000L, c.due)
        repeat(10) { c = Srs.answer(c, true, now) }; assertEquals(5, c.box)
    }

    @Test fun zipListAndKind() {
        val f = File.createTempFile("arc", ".zip")
        ZipOutputStream(f.outputStream()).use { z -> listOf("a.txt" to "привет", "dir/b.txt" to "мир").forEach { (n, s) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        val l = Archives.list(f, Archives.kind(f.name))
        assertEquals(listOf("a.txt", "dir/b.txt"), l.map { it.name })
        assertEquals("rar", Archives.kind("x.RAR")); assertEquals("tgz", Archives.kind("x.tar.gz"))
    }

    @Test fun docxAndXlsxAreValidZips() {
        val d = ByteArrayOutputStream(); PdfConvert.textToDocx("Строка 1\nСтрока <2> & 3", d)
        val names = ZipInputStream(d.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("word/document.xml" in names)
        val x = ByteArrayOutputStream(); PdfConvert.textToXlsx("Имя\tСумма\nКофе  45,5", x)
        val xml = ZipInputStream(x.toByteArray().inputStream()).use { z -> var s = ""; while (true) { val e = z.nextEntry ?: break; if (e.name == "xl/worksheets/sheet1.xml") s = z.readBytes().toString(Charsets.UTF_8) }; s }
        assertTrue("<v>45.5</v>" in xml, xml); assertTrue("Кофе" in xml)
    }
}
