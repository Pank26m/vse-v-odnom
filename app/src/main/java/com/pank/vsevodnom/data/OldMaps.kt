package com.pank.vsevodnom.data

import android.app.DownloadManager
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Данные прежней карты (MapLibre, версии 0.16–0.26): страны .mbtiles, рельеф, поиск, сегменты BRouter, кэш онлайн-тайлов.
 * Сама карта вырезана в 0.28 — здесь только подсчёт места и кнопка «Стереть старые данные» (потом уберём как рудимент).
 */
object OldMaps {
    private fun dirs(ctx: Context): List<File> =
        ctx.getExternalFilesDirs(null).filterNotNull().map { File(it, "maps") } +
            listOf(File(ctx.filesDir, "map"), File(ctx.filesDir, "brouter"), File(ctx.filesDir, "maps"))

    private fun mbglFiles(ctx: Context): List<File> = (listOf(ctx.filesDir, ctx.cacheDir) + ctx.getExternalFilesDirs(null).filterNotNull())
        .flatMap { d -> d.listFiles().orEmpty().filter { it.name.startsWith("mbgl") || it.name == ".mbgl" } }

    private fun size(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    fun bytes(ctx: Context): Long = (dirs(ctx).filter { it.exists() } + mbglFiles(ctx)).sumOf { size(it) }

    /** Стереть всё старое: файлы, недокачанные загрузки и их записи в настройках. */
    fun wipe(ctx: Context): Long {
        val before = bytes(ctx)
        val p = ctx.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)
        runCatching {
            val pend = JSONObject(p.getString("pending", "{}") ?: "{}")
            val dm = ctx.getSystemService(DownloadManager::class.java)
            pend.keys().forEach { k -> pend.optJSONObject(k)?.optJSONArray("ids")?.let { a -> for (i in 0 until a.length()) runCatching { dm.remove(a.getLong(i)) } } }
        }
        dirs(ctx).forEach { runCatching { it.deleteRecursively() } }
        mbglFiles(ctx).forEach { runCatching { it.deleteRecursively() } }
        p.edit().clear().apply()
        return before
    }
}
