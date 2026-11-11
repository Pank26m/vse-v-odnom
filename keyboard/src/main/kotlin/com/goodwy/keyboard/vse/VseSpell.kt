package com.goodwy.keyboard.vse

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Проверка орфографии без интернета: словари Hunspell (LibreOffice: ru — A. Lebedev, BSD; uk — dict_uk, MPL;
 * en — SCOWL) развёрнуты во все словоформы + частоты слов (FrequencyWords, CC BY-SA) — tools/build_kdict.py.
 * Файлы assets/kdict/<язык>.kdict читаются прямо из APK (без сжатия, через память-отображение), почти без ОЗУ.
 * Слово с ошибкой подчёркивается красным (это делает Android), по нажатию — варианты. Автозамены нет.
 */
class KDict(buf: ByteBuffer) {
    private val b = buf.order(ByteOrder.LITTLE_ENDIAN)
    private val alphabet: CharArray
    private val code = HashMap<Char, Int>()
    private val count: Int
    private val offsets: IntArray
    private val data: Int

    init {
        require(b.get(0) == 'K'.code.toByte() && b.get(3) == '1'.code.toByte()) { "не словарь" }
        val n = b.get(4).toInt() and 0xFF
        alphabet = CharArray(n) { b.getShort(5 + it * 2).toInt().toChar() }
        alphabet.forEachIndexed { i, c -> code[c] = i + 1 }
        var p = 5 + n * 2
        count = b.getInt(p); val blocks = b.getInt(p + 4); p += 8
        offsets = IntArray(blocks) { b.getInt(p + it * 4) }
        data = p + blocks * 4
    }

    val letters: CharArray get() = alphabet

    fun encode(w: String): ByteArray? {
        val out = ByteArray(w.length)
        for (i in w.indices) out[i] = (code[w[i]] ?: return null).toByte()
        return out
    }

    private fun cmp(a: ByteArray, aLen: Int, t: ByteArray): Int {
        val n = minOf(aLen, t.size)
        for (i in 0 until n) { val d = (a[i].toInt() and 0xFF) - (t[i].toInt() and 0xFF); if (d != 0) return d }
        return aLen - t.size
    }

    /** Частота слова 0..255 или −1, если слова нет. */
    fun freq(word: String): Int {
        val t = encode(word) ?: return -1
        // последний блок, чьё первое слово ≤ t
        var lo = 0; var hi = offsets.size - 1; var blk = -1
        val first = ByteArray(255)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val o = data + offsets[mid]; val len = b.get(o).toInt() and 0xFF
            for (i in 0 until len) first[i] = b.get(o + 1 + i)
            if (cmp(first, len, t) <= 0) { blk = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (blk < 0) return -1
        val cur = ByteArray(255)
        var o = data + offsets[blk]
        var len = b.get(o).toInt() and 0xFF
        for (i in 0 until len) cur[i] = b.get(o + 1 + i)
        o += 1 + len
        var f = b.get(o).toInt() and 0xFF; o++
        val end = if (blk + 1 < offsets.size) data + offsets[blk + 1] else b.limit()
        while (true) {
            val c = cmp(cur, len, t)
            if (c == 0) return f
            if (c > 0 || o >= end) return -1
            val pre = b.get(o).toInt() and 0xFF; val tl = b.get(o + 1).toInt() and 0xFF
            for (i in 0 until tl) cur[pre + i] = b.get(o + 2 + i)
            len = pre + tl; o += 2 + tl
            f = b.get(o).toInt() and 0xFF; o++
        }
    }

    fun contains(w: String) = freq(w) >= 0
}

object VseSpell {
    private val dicts = HashMap<String, KDict?>()

    @Synchronized fun dict(ctx: Context, lang: String): KDict? = dicts.getOrPut(lang) {
        runCatching {
            val fd = ctx.assets.openFd("kdict/$lang.kdict")
            FileInputStream(fd.fileDescriptor).channel.use { ch -> KDict(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)) }
        }.getOrNull()
    }

    private fun norm(w: String) = w.replace('’', '\'').replace('ʼ', '\'').lowercase()

    /** Языки, в которых проверять слово: по буквам. */
    fun langsOf(w: String): List<String> = when {
        w.any { it in "іїєґІЇЄҐ" } -> listOf("uk")
        w.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' } -> if (w.any { it in "ыэъёЫЭЪЁ" }) listOf("ru") else listOf("ru", "uk")
        w.any { it in 'a'..'z' || it in 'A'..'Z' } -> listOf("en")
        else -> emptyList()
    }

    /** Не проверяем: числа, ссылки, почты, одиночные буквы, аббревиатуры КАПСОМ, смесь алфавитов. */
    fun skip(w: String): Boolean = w.length <= 1 || w.any { it.isDigit() || it == '@' || it == '/' || it == '_' || it == '.' } ||
        (w.length <= 5 && w.all { !it.isLetter() || it.isUpperCase() }) || langsOf(w).isEmpty() ||
        (w.any { it in 'a'..'z' || it in 'A'..'Z' } && w.any { it in 'а'..'я' || it in 'А'..'Я' })

    /** Слово написано правильно? (через дефис — каждая часть). */
    fun ok(ctx: Context, raw: String): Boolean = okIn({ dict(ctx, it) }, raw)
    fun okIn(dict: (String) -> KDict?, raw: String): Boolean {
        val w = raw.trim('\'', '-', '«', '»', '"')
        if (skip(w)) return true
        val langs = langsOf(w)
        val n = norm(w)
        if (langs.any { dict(it)?.contains(n) ?: true }) return true
        if (n.contains('-')) return n.split('-').filter { it.isNotEmpty() }.all { p -> langs.any { dict(it)?.contains(p) ?: true } }
        return false
    }

    /** Варианты исправления: одна правка (замена, вставка, пропуск, перестановка), частые слова первыми. */
    fun suggest(ctx: Context, raw: String, limit: Int = 5): List<String> = suggestIn({ dict(ctx, it) }, raw, limit)
    fun suggestIn(dictOf: (String) -> KDict?, raw: String, limit: Int = 5): List<String> {
        val w = norm(raw.trim('\'', '-'))
        val out = HashMap<String, Int>()
        for (lang in langsOf(raw)) {
            val d = dictOf(lang) ?: continue
            val letters = d.letters.filter { it.isLetter() || it == '\'' || it == '-' }
            fun tryW(c: String) { if (c.isNotEmpty() && !out.containsKey(c)) { val f = d.freq(c); if (f >= 0) out[c] = f } }
            for (i in w.indices) {
                tryW(w.removeRange(i, i + 1))
                if (i + 1 < w.length) tryW(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
                for (ch in letters) if (ch != w[i]) tryW(w.substring(0, i) + ch + w.substring(i + 1))
            }
            for (i in 0..w.length) for (ch in letters) tryW(w.substring(0, i) + ch + w.substring(i))
        }
        val cap = raw.firstOrNull()?.isUpperCase() == true
        return out.entries.sortedByDescending { it.value }.take(limit).map { s -> if (cap) s.key.replaceFirstChar { it.uppercase() } else s.key }
    }
}
