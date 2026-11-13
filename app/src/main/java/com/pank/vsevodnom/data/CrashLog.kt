package com.pank.vsevodnom.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал падений: при вылете стек ошибки пишется в files/crash/last.txt, при следующем запуске
 * приложение показывает окно «Приложение упало» с кнопками «Скопировать»/«Отправить» — так владелец
 * присылает точную причину. Ошибки фоновых задач (AppScope) не роняют приложение, а пишутся в errors.txt.
 */
object CrashLog {
    private var dir: File? = null
    private var version = ""

    fun install(ctx: Context) {
        dir = File(ctx.filesDir, "crash").apply { mkdirs() }
        version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> runCatching { write("last.txt", e, "поток ${t.name}", append = false) }; prev?.uncaughtException(t, e) }
    }

    /** Нефатальная ошибка фоновой задачи — в errors.txt (последние ~50 КБ). */
    fun nonFatal(e: Throwable) { runCatching { write("errors.txt", e, "фоновая задача", append = true) } }

    private fun write(name: String, e: Throwable, where: String, append: Boolean) {
        val d = dir ?: return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val text = "=== ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))} · версия $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · $where\n$sw\n"
        val f = File(d, name)
        if (append) { f.appendText(text); if (f.length() > 50_000) f.writeText(f.readText().takeLast(40_000)) } else f.writeText(text)
    }

    /** Отчёт о падении с прошлого запуска (и сразу помечается прочитанным). */
    fun takeLast(ctx: Context): String? {
        val f = File(File(ctx.filesDir, "crash"), "last.txt")
        if (!f.exists()) return null
        val t = f.readText(); f.renameTo(File(f.parentFile, "shown.txt")); return t
    }
    fun errors(ctx: Context): String? = File(File(ctx.filesDir, "crash"), "errors.txt").takeIf { it.exists() }?.readText()
}
