package com.pank.vsevodnom

import com.pank.vsevodnom.ui.vault.DocSort
import kotlin.test.Test
import kotlin.test.assertEquals

class DocSortTest {
    @Test fun sortAndStem() {
        assertEquals("Паспорт и ID", DocSort.folderFor("Документы/Паспорт/скан_1.jpg"))
        assertEquals("ИНН и налоги", DocSort.folderFor("ІПН.pdf"))
        assertEquals("Учёба", DocSort.folderFor("Диплом бакалавра.pdf"))
        assertEquals("паспорт", DocSort.stem("паспорт_1.jpg"))
        assertEquals("паспорт", DocSort.stem("паспорт (2).jpg"))
        assertEquals("диплом 2024", DocSort.stem("диплом_2024.pdf"))
        assertEquals("права", DocSort.stem("права page 3.png"))
    }
}
