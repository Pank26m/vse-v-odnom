package com.pank.vsevodnom.data

import java.util.UUID

/** Разбор CSV с кавычками и переносами строк внутри полей. */
fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>(); val f = StringBuilder()
    var q = false; var i = 0
    val t = text.removePrefix("﻿")
    // разделитель: запятая или точка с запятой — смотрим первую строку
    val first = t.lineSequence().firstOrNull().orEmpty()
    val sep = if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
    while (i < t.length) {
        val c = t[i]
        if (q) {
            if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c)
        } else when (c) {
            '"' -> q = true
            sep -> { row += f.toString(); f.clear() }
            '\n' -> { row += f.toString(); f.clear(); rows += row; row = ArrayList() }
            '\r' -> {}
            else -> f.append(c)
        }
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); rows += row }
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

object KeePassImport {
    /** KeePass 2 CSV («Account, Login Name, Password, Web Site, Comments») и KeePassXC CSV («Title, Username, …»). */
    fun fromCsv(text: String): List<VaultEntry> {
        val rows = parseCsv(text)
        require(rows.size >= 2) { "В файле нет записей" }
        val h = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } }
        val title = col("account", "title", "название", "имя записи")
        val user = col("login name", "username", "user name", "логин", "имя пользователя")
        val pass = col("password", "пароль")
        val url = col("web site", "url", "website", "сайт")
        val notes = col("comments", "notes", "заметки", "примечания")
        val totp = col("totp", "otp")
        require(pass != null && (title != null || url != null)) { "Не узнаю формат CSV: нет столбцов «Password» и «Account/Title»" }
        return rows.drop(1).mapNotNull { r ->
            fun g(i: Int?) = i?.let { r.getOrNull(it) }?.trim().orEmpty()
            val t = g(title).ifBlank { g(url).removePrefix("https://").removePrefix("http://").substringBefore('/') }
            if (t.isBlank() && g(pass).isBlank()) null
            else VaultEntry(UUID.randomUUID().toString(), t.ifBlank { "Без названия" }, g(user), g(pass), g(url), g(notes), g(totp))
        }
    }

    /** KeePass 2 XML (незашифрованный экспорт). История версий записей пропускается. */
    fun fromXml(text: String): List<VaultEntry> {
        val p = android.util.Xml.newPullParser()
        p.setInput(text.reader())
        val out = ArrayList<VaultEntry>()
        var depthHistory = 0
        var cur: MutableMap<String, String>? = null
        var key: String? = null
        var tag = ""
        val sb = StringBuilder()
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = p.name; sb.clear()
                    when (p.name) {
                        "History" -> depthHistory++
                        "Entry" -> if (depthHistory == 0) cur = HashMap()
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> sb.append(p.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (p.name) {
                        "History" -> depthHistory--
                        "Key" -> key = sb.toString()
                        "Value" -> if (depthHistory == 0) key?.let { k -> cur?.put(k, sb.toString()) }
                        "Entry" -> if (depthHistory == 0) {
                            cur?.let { m ->
                                if ((m["Title"] ?: "").isNotBlank() || (m["Password"] ?: "").isNotBlank())
                                    out += VaultEntry(UUID.randomUUID().toString(), m["Title"].orEmpty().ifBlank { "Без названия" }, m["UserName"].orEmpty(),
                                        m["Password"].orEmpty(), m["URL"].orEmpty(), m["Notes"].orEmpty(), m["otp"] ?: m["TOTP Seed"] ?: "")
                            }
                            cur = null
                        }
                    }
                    sb.clear()
                }
            }
        }
        require(out.isNotEmpty()) { "В XML не нашлось записей KeePass" }
        return out
    }

    fun parse(name: String, text: String): List<VaultEntry> =
        if (name.endsWith(".xml", true) || text.trimStart().startsWith("<")) fromXml(text) else fromCsv(text)
}

/** Импорт веса из CSV другого приложения: сам находит столбцы даты и веса. */
object WeightCsv {
    data class Row(val day: java.time.LocalDate, val kg: Double)

    private val fmts = listOf("yyyy-MM-dd", "dd.MM.yyyy", "d.M.yyyy", "MM/dd/yyyy", "M/d/yyyy", "dd/MM/yyyy", "yyyy/MM/dd", "dd-MM-yyyy", "yyyyMMdd")
        .map { java.time.format.DateTimeFormatter.ofPattern(it) }

    fun date(s0: String): java.time.LocalDate? {
        val s = s0.trim().trim('"')
        if (s.isEmpty()) return null
        // unix-время в секундах или миллисекундах
        s.toLongOrNull()?.let { n ->
            val ms = when { n > 100_000_000_000L -> n; n > 1_000_000_000L -> n * 1000; else -> return null }
            return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        val d = s.substringBefore('T').substringBefore(' ')
        for (f in fmts) runCatching { return java.time.LocalDate.parse(d, f) }
        return null
    }

    private fun num(s: String) = s.trim().trim('"').replace(" ", "").replace(',', '.').removeSuffix("kg").removeSuffix("кг").toDoubleOrNull()

    fun parse(text: String): List<Row> {
        val rows = parseCsv(text)
        require(rows.isNotEmpty()) { "Пустой файл" }
        val header = rows[0].map { it.lowercase() }
        val hasHeader = header.any { it.any(Char::isLetter) }
        val data = if (hasHeader) rows.drop(1) else rows
        val n = data.maxOf { it.size }
        // столбец даты — где больше всего распознанных дат; веса — числа 20–400 (предпочитаем заголовок со словом вес/weight)
        val dateCol = (0 until n).maxByOrNull { c -> data.count { date(it.getOrElse(c) { "" }) != null } } ?: 0
        val weightCandidates = (0 until n).filter { c -> c != dateCol && data.count { r -> num(r.getOrElse(c) { "" })?.let { it in 20.0..400.0 } == true } > data.size / 2 }
        val weightCol = weightCandidates.firstOrNull { hasHeader && (header.getOrNull(it)?.contains("weight") == true || header.getOrNull(it)?.contains("вес") == true || header.getOrNull(it)?.contains("ваг") == true) }
            ?: weightCandidates.firstOrNull() ?: throw IllegalArgumentException("Не нашёл столбец с весом")
        val lbs = hasHeader && (header.getOrNull(weightCol)?.contains("lb") == true)
        return data.mapNotNull { r ->
            val d = date(r.getOrElse(dateCol) { "" }) ?: return@mapNotNull null
            val w = num(r.getOrElse(weightCol) { "" }) ?: return@mapNotNull null
            Row(d, if (lbs) w * 0.45359237 else w)
        }.groupBy { it.day }.map { (_, v) -> v.last() }.sortedBy { it.day }
    }
}
