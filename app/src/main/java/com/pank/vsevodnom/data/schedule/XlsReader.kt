package com.pank.vsevodnom.data.schedule

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальный читатель старого Excel (.xls, BIFF8), терпимый к «битым» файлам.
 * Портал университета сохраняет файлы с незакрытой цепочкой секторов — обычные
 * библиотеки на них падают, а мы просто читаем ровно столько байт, сколько заявлено.
 * Достаёт только текст и числа ячеек — для расписания больше не нужно.
 */
object XlsReader {
    class Sheet(val name: String, val cells: Map<Pair<Int, Int>, String>) {
        val rows: Int get() = (cells.keys.maxOfOrNull { it.first } ?: -1) + 1
        val cols: Int get() = (cells.keys.maxOfOrNull { it.second } ?: -1) + 1
        operator fun get(r: Int, c: Int): String = cells[r to c] ?: ""
    }

    class XlsException(msg: String) : Exception(msg)

    fun read(bytes: ByteArray): List<Sheet> {
        val wb = workbookStream(bytes)
        return parseBiff(wb)
    }

    // ---------------- Контейнер OLE (Compound File) ----------------

    private const val END = 0xFFFFFFFE.toInt()
    private const val FREE = 0xFFFFFFFF.toInt()

    private fun workbookStream(d: ByteArray): ByteArray {
        if (d.size < 512 || ByteBuffer.wrap(d, 0, 8).long != 0xD0CF11E0A1B11AE1uL.toLong())
            throw XlsException("Это не файл Excel .xls")
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val secSize = 1 shl bb.getShort(30).toInt()
        val miniSize = 1 shl bb.getShort(32).toInt()
        val nFat = bb.getInt(44)
        val dirStart = bb.getInt(48)
        val miniCutoff = bb.getInt(56)
        val miniFatStart = bb.getInt(60)
        var difatNext = bb.getInt(68)
        val nDifat = bb.getInt(72)

        fun secOff(s: Int) = 512 + s.toLong() * secSize

        // список секторов FAT
        val fatSecs = ArrayList<Int>()
        for (i in 0 until 109) bb.getInt(76 + i * 4).let { if (it >= 0) fatSecs += it }
        var guard = 0
        while (nDifat > 0 && difatNext >= 0 && guard++ < nDifat + 4) {
            val off = secOff(difatNext).toInt()
            if (off + secSize > d.size) break
            for (i in 0 until secSize / 4 - 1) bb.getInt(off + i * 4).let { if (it >= 0) fatSecs += it }
            difatNext = bb.getInt(off + secSize - 4)
        }
        val fat = IntArray(fatSecs.size * secSize / 4)
        fatSecs.forEachIndexed { k, s ->
            val off = secOff(s).toInt()
            for (i in 0 until secSize / 4) if (off + i * 4 + 4 <= d.size) fat[k * secSize / 4 + i] = bb.getInt(off + i * 4)
        }

        /** Читает `size` байт по цепочке; на кривых цепочках не падает. */
        fun readChain(start: Int, size: Long, unit: Int, next: (Int) -> Int, offset: (Int) -> Long, src: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var left = size
            var steps = 0
            while (s >= 0 && left > 0 && steps++ < 1_000_000) {
                val off = offset(s)
                if (off < 0 || off >= src.size) break
                val n = minOf(unit.toLong(), left, src.size - off).toInt()
                out.write(src, off.toInt(), n)
                left -= n
                s = next(s)
            }
            return out.toByteArray()
        }
        fun fatNext(s: Int) = if (s in fat.indices) fat[s] else END

        // каталог
        val dir = readChain(dirStart, Long.MAX_VALUE / 2, secSize, ::fatNext, ::secOff, d)
        data class Entry(val name: String, val type: Int, val start: Int, val size: Long)
        val entries = (0 until dir.size / 128).map { i ->
            val e = ByteBuffer.wrap(dir, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nl = e.getShort(i * 128 + 64).toInt().coerceIn(0, 64)
            val name = String(dir, i * 128, (nl - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            Entry(name, dir[i * 128 + 66].toInt(), e.getInt(i * 128 + 116), e.getInt(i * 128 + 120).toLong() and 0xFFFFFFFFL)
        }
        val root = entries.firstOrNull { it.type == 5 } ?: throw XlsException("Повреждённый файл")
        val wb = entries.firstOrNull { it.type == 2 && (it.name == "Workbook" || it.name == "Book") }
            ?: throw XlsException("В файле нет листов Excel")

        if (wb.size >= miniCutoff) return readChain(wb.start, wb.size, secSize, ::fatNext, ::secOff, d)

        // маленький поток лежит в мини-потоке корня
        val miniStream = readChain(root.start, root.size, secSize, ::fatNext, ::secOff, d)
        val miniFatBytes = readChain(miniFatStart, Long.MAX_VALUE / 2, secSize, ::fatNext, ::secOff, d)
        val mb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
        val miniFat = IntArray(miniFatBytes.size / 4) { mb.getInt(it * 4) }
        return readChain(
            wb.start, wb.size, miniSize,
            { if (it in miniFat.indices) miniFat[it] else END },
            { it.toLong() * miniSize }, miniStream
        )
    }

    // ---------------- Записи BIFF8 ----------------

    private class Rec(val id: Int, val data: ByteArray, val pos: Int)

    /** Последовательное чтение строки SST, которая может переходить в записи CONTINUE. */
    private class Chunks(val parts: List<ByteArray>) {
        var p = 0; var i = 0
        fun ensure() { while (p < parts.size && i >= parts[p].size) { p++; i = 0 } }
        fun u8(): Int { ensure(); if (p >= parts.size) throw IndexOutOfBoundsException(); return parts[p][i++].toInt() and 0xFF }
        fun u16() = u8() or (u8() shl 8)
        fun u32() = u16() or (u16() shl 16)
        fun skip(n: Int) { repeat(n) { u8() } }
        fun str(): String {
            val cch = u16()
            val flags = u8()
            var high = flags and 1 != 0
            val rich = if (flags and 8 != 0) u16() else 0
            val ext = if (flags and 4 != 0) u32() else 0
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0) {
                if (p < parts.size && i >= parts[p].size) {
                    // строка продолжается в следующей записи — там новый байт флагов
                    p++; i = 0
                    if (p >= parts.size) break
                    high = (parts[p][i++].toInt() and 1) != 0
                }
                if (p >= parts.size) break
                if (high) sb.append((u8() or (u8() shl 8)).toChar()) else sb.append(u8().toChar())
                left--
            }
            skip(rich * 4); skip(ext)
            return sb.toString()
        }
    }

    private fun shortStr(b: ByteArray, off: Int, twoByteLen: Boolean): String {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val cch = if (twoByteLen) bb.getShort(off).toInt() and 0xFFFF else b[off].toInt() and 0xFF
        val fo = off + if (twoByteLen) 2 else 1
        val high = b[fo].toInt() and 1 != 0
        val start = fo + 1
        return if (high) String(b, start, minOf(cch * 2, b.size - start), Charsets.UTF_16LE)
        else String(b, start, minOf(cch, b.size - start), Charsets.ISO_8859_1)
    }

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100 else d
    }

    private fun num(d: Double): String =
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun parseBiff(wb: ByteArray): List<Sheet> {
        val recs = ArrayList<Rec>()
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 4 <= wb.size) {
            val id = bb.getShort(pos).toInt() and 0xFFFF
            val len = bb.getShort(pos + 2).toInt() and 0xFFFF
            val end = minOf(pos + 4 + len, wb.size)
            recs += Rec(id, wb.copyOfRange(pos + 4, end), pos)
            pos = end
        }

        val sheetNames = LinkedHashMap<Int, String>() // смещение BOF листа → имя
        var sst: List<String> = emptyList()
        var k = 0
        while (k < recs.size) {
            val r = recs[k]
            when (r.id) {
                0x0085 -> {
                    val off = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                    sheetNames[off] = runCatching { shortStr(r.data, 6, false) }.getOrDefault("Лист")
                }
                0x00FC -> {
                    val parts = arrayListOf(r.data)
                    while (k + 1 < recs.size && recs[k + 1].id == 0x003C) { parts += recs[k + 1].data; k++ }
                    val c = Chunks(parts)
                    c.u32()
                    val unique = c.u32()
                    val list = ArrayList<String>(unique)
                    try { repeat(unique) { list += c.str() } } catch (_: IndexOutOfBoundsException) {}
                    sst = list
                }
            }
            k++
        }

        val sheets = ArrayList<Sheet>()
        var cur: MutableMap<Pair<Int, Int>, String>? = null
        var curName = ""
        var depth = 0
        for (r in recs) {
            val d = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
            when (r.id) {
                0x0809 -> {
                    depth++
                    if (sheetNames.containsKey(r.pos)) { cur = HashMap(); curName = sheetNames.getValue(r.pos) }
                }
                0x000A -> {
                    depth--
                    cur?.let { sheets += Sheet(curName, it) }
                    cur = null
                }
            }
            val cells = cur ?: continue
            if (r.data.size < 6) continue
            val row = d.getShort(0).toInt() and 0xFFFF
            val col = d.getShort(2).toInt() and 0xFFFF
            when (r.id) {
                0x00FD -> if (r.data.size >= 10) sst.getOrNull(d.getInt(6))?.let { cells[row to col] = it }
                0x0204 -> runCatching { cells[row to col] = shortStr(r.data, 6, true) }
                0x0203 -> if (r.data.size >= 14) cells[row to col] = num(d.getDouble(6))
                0x027E -> if (r.data.size >= 10) cells[row to col] = num(rk(d.getInt(6)))
                0x00BD -> {
                    val n = (r.data.size - 6) / 6
                    for (j in 0 until n) cells[row to (col + j)] = num(rk(d.getInt(4 + j * 6 + 2)))
                }
            }
        }
        if (sheets.isEmpty() && sheetNames.isEmpty()) throw XlsException("Не нашёл листов в файле")
        return sheets
    }
}
