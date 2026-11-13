package com.pank.vsevodnom.data.device

import java.io.File
import java.util.Locale

/**
 * Модель сведений об устройстве: всё, что показывает «Анализатор устройства», собирается в секции «ключ — значение».
 * Одна и та же модель идёт и на экран, и в отчёт (TXT / JSON / PDF), поэтому каждое значение — уже готовая строка.
 * ok = true/false — строка-флаг (рисуется ✓ / ✗), null — обычное значение.
 */
data class InfoRow(val key: String, val value: String, val ok: Boolean? = null, val hint: String? = null)

data class InfoSection(val title: String, val rows: List<InfoRow>, val note: String? = null)

/** Построитель секции: пустые/неизвестные значения отбрасываются — на экране только то, что телефон реально отдал. */
class SectionBuilder(val title: String) {
    val rows = ArrayList<InfoRow>()
    var note: String? = null
    fun add(k: String, v: Any?, hint: String? = null) {
        val s = v?.toString()?.trim().orEmpty()
        if (s.isEmpty() || s == "unknown" || s == "null" || s == "-1") return
        rows += InfoRow(k, s, null, hint)
    }
    fun flag(k: String, ok: Boolean?, hint: String? = null) { if (ok != null) rows += InfoRow(k, if (ok) "да" else "нет", ok, hint) }
    fun build() = InfoSection(title, rows.toList(), note)
}

/** Секция: если чтение какого-то поля упало (нет разрешения, закрыт API), показываем то, что успели, и строку об ошибке. */
fun section(title: String, b: SectionBuilder.() -> Unit): InfoSection = SectionBuilder(title).apply {
    runCatching { b() }.onFailure { rows += InfoRow("Не удалось дочитать", (it.message ?: it.javaClass.simpleName).take(120)) }
}.build()

object Dev {
    /** Чтение файла /sys или /proc; null — нет файла или Android не дал прочитать. */
    fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    fun readLong(path: String): Long? = read(path)?.trim()?.toLongOrNull()
    fun lines(path: String): List<String> = runCatching { File(path).readLines() }.getOrDefault(emptyList())

    fun bytes(b: Long?): String? {
        if (b == null || b < 0) return null
        val u = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return (if (i == 0) "${b} Б" else fmt(v, if (v >= 100) 0 else if (v >= 10) 1 else 2) + " " + u[i])
    }
    fun fmt(v: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", v).replace('.', ',')
    fun fmt(v: Float, digits: Int = 1): String = fmt(v.toDouble(), digits)
    fun mhz(khz: Long?) = khz?.let { if (it >= 1_000_000) fmt(it / 1_000_000.0, 2) + " ГГц" else "${it / 1000} МГц" }
    fun dur(sec: Long): String {
        val d = sec / 86400; val h = sec / 3600 % 24; val m = sec / 60 % 60; val s = sec % 60
        return buildString { if (d > 0) append("$d д "); if (d > 0 || h > 0) append("$h ч "); append("$m мин"); if (d == 0L && h == 0L) append(" $s с") }
    }

    // ---------- свойства системы (getprop) ----------
    @Volatile private var props: Map<String, String>? = null
    /** Все свойства, которые Android разрешает прочитать приложению (`getprop`); закрытые SELinux просто не попадут. */
    fun props(): Map<String, String> = props ?: runCatching {
        val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        val m = LinkedHashMap<String, String>()
        Regex("""^\[([^\]]+)\]: \[(.*)\]$""", RegexOption.MULTILINE).findAll(out).forEach { m[it.groupValues[1]] = it.groupValues[2] }
        m
    }.getOrDefault(emptyMap()).also { props = it }
    fun prop(k: String): String? = props()[k]?.takeIf { it.isNotBlank() } ?: reflectProp(k)
    /** Запасной путь — android.os.SystemProperties (скрытый, но читаемый API). */
    private fun reflectProp(k: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Выполнить безопасную системную команду и вернуть вывод (toybox: uname, getprop и т.п.). */
    fun exec(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText(); p.waitFor(); s.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun yes(b: Boolean) = if (b) "да" else "нет"
}
