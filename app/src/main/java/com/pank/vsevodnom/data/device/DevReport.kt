package com.pank.vsevodnom.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Секции процессора, графики, температуры и батареи + сборка полного отчёта «всё о телефоне» (TXT / JSON / PDF). */
object DevReport {
    fun cpu(ctx: Context): List<InfoSection> {
        val cores = DevCpu.cores(); val cl = DevCpu.clusters(cores); val soc = DevCpu.soc()
        val blocks = DevCpu.cpuinfoBlocks()
        val feat = blocks.firstNotNullOfOrNull { it["Features"] }
        val out = ArrayList<InfoSection>()
        out += section("Процессор") {
            add("Чип", soc?.name ?: if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            add("Производитель", soc?.vendor ?: if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null)
            if (Build.VERSION.SDK_INT >= 31) add("Код платформы", Build.SOC_MODEL)
            add("Техпроцесс", soc?.process, "справочник по модели чипа")
            add("Ядер", "${cores.size} (${cl.size} кластера)")
            add("Конфигурация", cl.joinToString("\n") { "${it.cores.size} × ${it.name} · ${Dev.mhz(it.min) ?: "?"} – ${Dev.mhz(it.max) ?: "?"}" })
            add("Архитектура", DevCpu.archOf(cl.firstOrNull()?.name) ?: System.getProperty("os.arch"))
            add("Разрядность", if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64 бит" + if (Build.SUPPORTED_32_BIT_ABIS.isEmpty()) " (без 32-бит приложений)" else "" else "32 бит")
            add("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
            add("Регулятор частоты", cores.mapNotNull { it.governor }.distinct().joinToString(", "))
            add("Нейропроцессор", soc?.npu, "справочник по модели чипа")
            blocks.firstNotNullOfOrNull { it["Hardware"] }?.let { add("Hardware (cpuinfo)", it) }
            add("Ревизии ядер", cores.mapNotNull { c -> c.variant?.let { v -> "r${v.removePrefix("0x").toIntOrNull(16) ?: v}p${c.revision ?: "?"}" } }.distinct().joinToString(", "))
            if (feat != null) add("Инструкции", feat)
        }
        out += InfoSection("Ядра", cores.map { c ->
            InfoRow("Ядро ${c.id}", listOfNotNull(c.partName, if (!c.online) "выключено" else Dev.mhz(c.curKhz)?.let { "сейчас $it" },
                "${Dev.mhz(c.minKhz) ?: "?"} – ${Dev.mhz(c.maxKhz) ?: "?"}", c.capacity?.let { "ёмкость $it" }, c.governor).joinToString(" · "))
        })
        return out
    }

    fun gpu(ctx: Context): List<InfoSection> {
        val gl = DevCpu.gl(); val pm = ctx.packageManager; val soc = DevCpu.soc()
        return listOf(section("Графика") {
            add("ГП", gl.renderer ?: soc?.gpu); add("Производитель", gl.vendor)
            add("OpenGL ES", gl.version); add("GLSL", gl.glsl)
            add("Vulkan", DevCpu.vulkan(pm))
            pm.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.level" }?.let { add("Уровень Vulkan", it.version) }
            pm.systemAvailableFeatures.firstOrNull { it.name == "android.software.vulkan.deqp.level" }?.let { add("Тесты Vulkan dEQP", it.version.let { v -> "${v shr 16}-${(v shr 8) and 0xff}-${v and 0xff}" }) }
            add("Макс. текстура", gl.maxTexture?.let { "$it × $it" })
            add("Драйвер EGL", Dev.prop("ro.hardware.egl")); add("Драйвер Vulkan", Dev.prop("ro.hardware.vulkan"))
            add("ANGLE", Dev.prop("ro.gfx.angle.supported") ?: Dev.prop("persist.graphics.egl"))
            DevCpu.gpuFreqMhz()?.let { add("Частота сейчас", "$it МГц") }
            add("Конфигурация (справочник)", soc?.gpu)
        }, InfoSection("Расширения OpenGL ES (${gl.extensions.size})", gl.extensions.map { InfoRow(it, "есть") }))
    }

    fun thermal(ctx: Context): List<InfoSection> {
        val zones = DevHardware.thermalZones()
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return listOf(section("Температура") {
            add("Тепловое состояние", DevHardware.thermalName(DevHardware.thermalStatus(ctx)))
            DevHardware.headroom(ctx)?.let { add("Запас до троттлинга", "${((1 - it) * 100).toInt().coerceIn(0, 100)} %") }
            DevHardware.headroom(ctx, 10)?.let { add("Прогноз через 10 с", "${((1 - it) * 100).toInt().coerceIn(0, 100)} %") }
            b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { add("Батарея", "${Dev.fmt(it / 10f, 1)} °C") }
            add("Термодатчиков доступно", if (zones.isEmpty()) "0 — Android закрывает их от приложений" else "${zones.size}")
        }, InfoSection("Термозоны", zones.map { InfoRow(it.name, "${Dev.fmt(it.celsius, 1)} °C") }))
    }

    fun battery(ctx: Context): InfoSection {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return section("Батарея") {
            val lvl = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
            add("Заряд", lvl?.let { "$it %" })
            add("Состояние", i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { statusName(it) })
            add("Питание", i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { pluggedName(it) })
            add("Здоровье", i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)?.let { healthName(it) })
            add("Технология", i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
            add("Температура", i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { "${Dev.fmt(it / 10f, 1)} °C" })
            add("Напряжение", i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.let { "$it мВ" })
            add("Ток", curMa(bm)?.let { "${it.toInt()} мА" })
            if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }?.let { add("Циклов (система)", it) }
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }?.let { c ->
                add("Заряд в батарее", "${c / 1000} мА·ч"); if (lvl != null && lvl > 10) add("Полная ёмкость (по счётчику)", "${c / 10 / lvl} мА·ч")
            }
            designMah(ctx)?.let { add("Паспортная ёмкость", "${it.toInt()} мА·ч") }
            bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { add("До полной зарядки", Dev.dur(it / 1000)) }
            flag("Есть батарея", i?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true))
        }
    }
    /** Ток в мА: «+» — заряжается. На Pixel значение в микроамперах. */
    fun curMa(bm: BatteryManager): Double? {
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        return if (kotlin.math.abs(raw) > 20_000) raw / 1000.0 else raw.toDouble()
    }
    fun designMah(ctx: Context): Double? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
        (cls.getMethod("getBatteryCapacity").invoke(pp) as Double).takeIf { it > 500 }
    }.getOrNull()
    fun statusName(s: Int) = when (s) { BatteryManager.BATTERY_STATUS_CHARGING -> "заряжается"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "разряжается"; BatteryManager.BATTERY_STATUS_FULL -> "заряжена"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "не заряжается"; else -> "неизвестно" }
    fun pluggedName(p: Int) = when (p) { BatteryManager.BATTERY_PLUGGED_AC -> "сеть (зарядка)"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "беспроводная"; 8 -> "док"; 0 -> "от батареи"; else -> "$p" }
    fun healthName(h: Int) = when (h) { BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"; BatteryManager.BATTERY_HEALTH_DEAD -> "неисправна"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "перенапряжение"; BatteryManager.BATTERY_HEALTH_COLD -> "холодная"; else -> "неизвестно" }

    // ---------------- полный отчёт ----------------
    data class Chapter(val title: String, val sections: List<InfoSection>)

    /** Всё о телефоне: проходит все разделы. Долго (камеры, кодеки, getprop) — только в фоне. */
    fun collect(ctx: Context, progress: (String) -> Unit = {}): List<Chapter> {
        val out = ArrayList<Chapter>()
        fun ch(t: String, f: () -> List<InfoSection>) { progress(t); out += Chapter(t, runCatching(f).getOrElse { listOf(section("Ошибка") { add("Не удалось", it.message) }) }) }
        ch("Система") { DevSystem.system(ctx) }
        ch("Безопасность") { DevSystem.security(ctx) }
        ch("Процессор") { cpu(ctx) }
        ch("Графика") { gpu(ctx) }
        ch("Память") { DevHardware.memory(ctx) }
        ch("Экран") { DevHardware.display(ctx) }
        ch("Батарея") { listOf(battery(ctx)) }
        ch("Температура") { thermal(ctx) }
        ch("Сеть") { DevConnect.network(ctx) + DevConnect.wifiCaps(ctx) }
        ch("Связь") { DevConnect.bluetooth(ctx) + DevConnect.nfcUwb(ctx) + DevConnect.telephony(ctx) + DevConnect.gnss(ctx) }
        ch("Камеры") { DevMedia.cameras(ctx).flatMap { c -> c.sections.mapIndexed { i, s -> if (i == 0) s.copy(title = "${c.title}: ${c.headline}") else s.copy(title = "${c.title}: ${s.title}") } } }
        ch("Датчики") { DevMedia.sensors(ctx).map { s -> InfoSection("${DevMedia.sensorType(s)}: ${s.name}", DevMedia.sensorRows(s)) } }
        ch("Звук и вибрация") { DevHardware.audio(ctx) + DevHardware.vibrator(ctx) }
        ch("Кодеки") { DevMedia.codecs().groupBy { it.encoder }.map { (enc, l) -> InfoSection(if (enc) "Кодеры (${l.size})" else "Декодеры (${l.size})", l.map { InfoRow(it.name, it.types.joinToString(", ") + if (it.hardware) " · аппаратный" else "") }) } }
        ch("Устройства ввода") { DevHardware.inputs(ctx) }
        ch("Разделы") { listOf(InfoSection("Точки монтирования", DevHardware.mounts().map { m -> InfoRow(m.point, "${m.dev} · ${m.fs} · ${if (m.rw) "чтение-запись" else "только чтение"}" + (m.total?.let { " · ${Dev.bytes(it - (m.avail ?: 0))} из ${Dev.bytes(it)}" } ?: "")) })) }
        ch("USB") { DevHardware.usb(ctx) }
        ch("Функции") { DevSystem.features(ctx) }
        ch("Свойства системы") { listOf(InfoSection("getprop (${Dev.props().size})", Dev.props().map { InfoRow(it.key, it.value) })) }
        return out
    }

    fun txt(ctx: Context, chapters: List<Chapter>): String = buildString {
        appendLine("ОТЧЁТ ОБ УСТРОЙСТВЕ — ${DevSystem.deviceName(ctx)}")
        appendLine("Создан: ${DevSystem.date(System.currentTimeMillis())} · «Всё в одном»")
        chapters.forEach { c ->
            appendLine(); appendLine("════════ ${c.title.uppercase()} ════════")
            c.sections.forEach { s -> appendLine(); appendLine("— ${s.title} —"); s.rows.forEach { appendLine("${it.key}: ${it.value.replace("\n", "; ")}") }; s.note?.let { appendLine("(${it})") } }
        }
    }

    fun json(ctx: Context, chapters: List<Chapter>): String = JSONObject().apply {
        put("device", DevSystem.deviceName(ctx)); put("created", System.currentTimeMillis())
        put("chapters", JSONArray().apply { chapters.forEach { c -> put(JSONObject().apply {
            put("title", c.title)
            put("sections", JSONArray().apply { c.sections.forEach { s -> put(JSONObject().apply {
                put("title", s.title); put("rows", JSONObject().apply { s.rows.forEach { r -> put(r.key, r.value) } })
            }) } })
        }) } })
    }.toString(2)

    /** PDF A4: заголовки глав, секции, строки «ключ — значение» с переносом. */
    fun pdf(ctx: Context, chapters: List<Chapter>, file: File) {
        val doc = PdfDocument(); val W = 595; val H = 842; val M = 36f
        var page: PdfDocument.Page? = null; var y = 0f; var n = 0
        val title = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true; color = 0xFF2B2A33.toInt() }
        val head = Paint().apply { textSize = 11.5f; isFakeBoldText = true; isAntiAlias = true; color = 0xFF6A55A8.toInt() }
        val key = Paint().apply { textSize = 9f; isAntiAlias = true; color = 0xFF6B6A75.toInt() }
        val value = Paint().apply { textSize = 9f; isAntiAlias = true; color = 0xFF2B2A33.toInt() }
        fun newPage() { page?.let { doc.finishPage(it) }; n++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); y = M
            page!!.canvas.drawText("${DevSystem.deviceName(ctx)} · стр. $n", M, H - 18f, key) }
        fun need(h: Float) { if (page == null || y + h > H - 40) newPage() }
        fun wrap(t: String, p: Paint, w: Float): List<String> = t.split('\n').flatMap { line ->
            val out = ArrayList<String>(); var cur = ""
            line.split(' ').forEach { word -> val tryS = if (cur.isEmpty()) word else "$cur $word"; if (p.measureText(tryS) > w && cur.isNotEmpty()) { out += cur; cur = word } else cur = tryS }
            out += cur; out
        }
        newPage()
        page!!.canvas.drawText("Отчёт об устройстве: ${DevSystem.deviceName(ctx)}", M, y + 14, title); y += 22
        page!!.canvas.drawText("Создан ${DevSystem.date(System.currentTimeMillis())} в «Всё в одном»", M, y + 10, key); y += 22
        chapters.forEach { c ->
            need(40f); page!!.canvas.drawText(c.title.uppercase(), M, y + 16, title); y += 26
            c.sections.forEach { s ->
                need(30f); page!!.canvas.drawText(s.title.take(90), M, y + 12, head); y += 18
                s.rows.forEach { r ->
                    val kl = wrap(r.key, key, 170f); val vl = wrap(r.value, value, W - 2 * M - 180f)
                    val lines = maxOf(kl.size, vl.size); need(lines * 12f + 3)
                    kl.forEachIndexed { i, t -> page!!.canvas.drawText(t, M, y + 10 + i * 12, key) }
                    vl.forEachIndexed { i, t -> page!!.canvas.drawText(t, M + 180f, y + 10 + i * 12, value) }
                    y += lines * 12f + 3
                }
                y += 6
            }
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }; doc.close()
    }
}
