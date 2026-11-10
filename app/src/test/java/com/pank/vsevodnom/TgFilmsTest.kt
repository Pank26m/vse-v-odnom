package com.pank.vsevodnom

import com.pank.vsevodnom.data.TgFilms
import kotlin.test.Test
import kotlin.test.assertEquals

class TgFilmsTest {
    @Test fun parseNames() {
        val a = TgFilms.parse("", "Джон Уик 4 (Пифагор)[q720].mp4")
        assertEquals("Джон Уик 4", a.title); assertEquals(null, a.episode); assertEquals("720p", a.quality)
        val b = TgFilms.parse("", "Силиконовая_долина_Кубик_в_Кубе_|_Kubik³_Провайдер_2s6s1q720.mp4")
        assertEquals("Силиконовая долина", b.title); assertEquals(2, b.season); assertEquals(6, b.episode)
        val c = TgFilms.parse("Силиконовая долина\n2 сезон 7 серия", "video.mp4")
        assertEquals("Силиконовая долина", c.title); assertEquals(2, c.season); assertEquals(7, c.episode)
        val d = TgFilms.parse("Во все тяжкие S03E10", "x.mkv")
        assertEquals("Во все тяжкие", d.title); assertEquals(3, d.season); assertEquals(10, d.episode)
        val e = TgFilms.parse("Сериал «Чернобыль». Серия 3", "")
        assertEquals(1, e.season); assertEquals(3, e.episode)
        assertEquals(TgFilms.key(b.title), TgFilms.key(c.title))
    }
}
