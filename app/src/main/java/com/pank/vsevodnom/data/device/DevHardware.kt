package com.pank.vsevodnom.data.device

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import android.view.WindowManager
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Память, накопитель, экран, звук, вибромотор, устройства ввода, USB, разделы, температура — всё с телефона. */
object DevHardware {

    // ---------------- память ----------------
    data class Mem(val total: Long, val avail: Long, val threshold: Long, val low: Boolean)
    fun mem(ctx: Context): Mem {
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return Mem(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory)
    }
    /** /proc/meminfo в байтах (kB × 1024). */
    fun meminfo(): Map<String, Long> = Dev.lines("/proc/meminfo").mapNotNull { l ->
        val k = l.substringBefore(':').trim(); val v = l.substringAfter(':').trim().split(' ').firstOrNull()?.toLongOrNull()
        v?.let { k to it * 1024 }
    }.toMap()

    /** Объём «как в магазине»: 11,3 ГиБ → 12 ГБ. */
    fun marketing(bytes: Long): String {
        val gb = bytes / 1e9
        val sizes = listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32, 64, 128, 256, 512, 1024, 2048)
        val s = sizes.firstOrNull { it >= gb * 0.98 } ?: gb.roundToInt()
        return if (s >= 1024) "${s / 1024} ТБ" else "$s ГБ"
    }

    fun memory(ctx: Context): List<InfoSection> {
        val m = mem(ctx); val mi = meminfo()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val list = ArrayList<InfoSection>()
        list += section("Оперативная память") {
            add("Объём", "${marketing(m.total)} (доступно системе ${Dev.bytes(m.total)})")
            add("Свободно", "${Dev.bytes(m.avail)} · ${(m.avail * 100 / m.total.coerceAtLeast(1))} %")
            add("Занято", Dev.bytes(m.total - m.avail))
            add("Порог нехватки", Dev.bytes(m.threshold))
            flag("Сейчас мало памяти", m.low)
            add("Класс памяти приложения", "${am.memoryClass} МБ (большая куча ${am.largeMemoryClass} МБ)")
            flag("Устройство с малым ОЗУ", am.isLowRamDevice)
            mi["Cached"]?.let { add("Кэш", Dev.bytes(it)) }
            mi["Buffers"]?.let { add("Буферы", Dev.bytes(it)) }
            mi["Shmem"]?.let { add("Общая (Shmem)", Dev.bytes(it)) }
            mi["Active"]?.let { add("Активная", Dev.bytes(it)) }
            mi["Inactive"]?.let { add("Неактивная", Dev.bytes(it)) }
            mi["Slab"]?.let { add("Ядро (Slab)", Dev.bytes(it)) }
            mi["KernelStack"]?.let { add("Стеки ядра", Dev.bytes(it)) }
            mi["PageTables"]?.let { add("Таблицы страниц", Dev.bytes(it)) }
            mi["SwapTotal"]?.takeIf { it > 0 }?.let { t -> add("Swap / zram", "${Dev.bytes(t - (mi["SwapFree"] ?: 0))} из ${Dev.bytes(t)}") }
            Dev.lines("/proc/swaps").drop(1).forEach { l -> l.split(Regex("\\s+")).takeIf { it.size >= 4 }?.let { add("Раздел подкачки", "${it[0]} · ${it[1]}") } }
            Dev.read("/sys/block/zram0/comp_algorithm")?.let { add("Сжатие zram", it.replace("[", "").replace("]", "*")) }
        }
        // производитель/тип памяти отдают не все телефоны — показываем свойства загрузчика как есть
        val ramProps = Dev.props().filterKeys { k -> Regex("ddr|dram|lpddr|ram_|memory|ufs|emmc|storage").containsMatchIn(k.lowercase()) && !k.startsWith("persist.device_config") }
        if (ramProps.isNotEmpty()) list += InfoSection("Чипы памяти (свойства загрузчика)", ramProps.map { InfoRow(it.key, it.value) }.take(30))
        list += storage(ctx)
        return list
    }

    fun storage(ctx: Context): List<InfoSection> {
        val out = ArrayList<InfoSection>()
        val st = StatFs(Environment.getDataDirectory().path)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val total = runCatching { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        val free = runCatching { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        out += section("Накопитель") {
            add("Объём", total?.let { "${marketing(it)} (${Dev.bytes(it)})" } ?: Dev.bytes(st.totalBytes))
            add("Раздел данных /data", "${Dev.bytes(st.totalBytes)}, свободно ${Dev.bytes(st.availableBytes)} (${st.availableBytes * 100 / st.totalBytes.coerceAtLeast(1)} %)")
            if (total != null && free != null) add("Занято всего", "${Dev.bytes(total - free)} (${(total - free) * 100 / total.coerceAtLeast(1)} %)")
            if (total != null) add("Система и служебное", Dev.bytes(total - st.totalBytes))
            runCatching {
                val ex = ssm.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, android.os.Process.myUserHandle())
                add("Фото", Dev.bytes(ex.imageBytes)); add("Видео", Dev.bytes(ex.videoBytes)); add("Аудио", Dev.bytes(ex.audioBytes))
                add("Приложения (общая часть)", Dev.bytes(ex.appBytes))
            }
            add("Шифрование", Dev.prop("ro.crypto.type")?.let { if (it == "file") "по файлам (FBE)" else it } ?: Dev.prop("ro.crypto.state"))
            add("Файловая система /data", mounts().firstOrNull { it.point == "/data" }?.fs)
            add("Производитель", Dev.read("/sys/block/sda/device/vendor") ?: Dev.read("/sys/block/mmcblk0/device/manfid"))
            add("Модель", Dev.read("/sys/block/sda/device/model") ?: Dev.read("/sys/block/mmcblk0/device/name"))
            add("Ревизия прошивки", Dev.read("/sys/block/sda/device/rev"))
            add("Тип", when { Dev.read("/sys/block/sda/device/vendor") != null || java.io.File("/dev/block/sda").exists() -> "UFS"; java.io.File("/sys/block/mmcblk0").exists() -> "eMMC"; else -> null })
        }
        val sm = ctx.getSystemService(StorageManager::class.java)
        val vols = sm.storageVolumes
        if (vols.size > 0) out += InfoSection("Тома", vols.map { v ->
            val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else null
            val sf = dir?.let { runCatching { StatFs(it.path) }.getOrNull() }
            InfoRow(v.getDescription(ctx), listOfNotNull(if (v.isPrimary) "основной" else null, if (v.isRemovable) "съёмный" else "встроенный",
                if (v.isEmulated) "эмулируемый" else null, v.state, sf?.let { "свободно ${Dev.bytes(it.availableBytes)} из ${Dev.bytes(it.totalBytes)}" }, dir?.path).joinToString(" · "))
        })
        return out
    }

    // ---------------- экран ----------------
    fun display(ctx: Context): List<InfoSection> {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val res = ctx.resources.displayMetrics
        val out = ArrayList<InfoSection>()
        dm.displays.forEach { d ->
            val modes = d.supportedModes.toList()
            val native = modes.maxByOrNull { it.physicalWidth * it.physicalHeight }
            val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else android.graphics.Rect(0, 0, res.widthPixels, res.heightPixels)
            val nw = native?.physicalWidth ?: bounds.width(); val nh = native?.physicalHeight ?: bounds.height()
            // xdpi/ydpi приходят для текущего режима; для «родного» масштабируем
            val k = nw.toFloat() / bounds.width().coerceAtLeast(1)
            val wIn = nw / (res.xdpi * k); val hIn = nh / (res.ydpi * k)
            val diag = sqrt(wIn * wIn + hIn * hIn)
            out += section(if (d.displayId == Display.DEFAULT_DISPLAY) "Экран" else "Дисплей ${d.displayId}: ${d.name}") {
                add("Название", d.name)
                add("Родное разрешение", "${minOf(nw, nh)} × ${maxOf(nw, nh)}")
                add("Разрешение сейчас", "${minOf(bounds.width(), bounds.height())} × ${maxOf(bounds.width(), bounds.height())}")
                add("Диагональ", "${Dev.fmt(diag, 2)}″ (${Dev.fmt(diag * 25.4f, 0)} мм)")
                add("Размер", "${Dev.fmt(minOf(wIn, hIn) * 25.4f, 0)} × ${Dev.fmt(maxOf(wIn, hIn) * 25.4f, 0)} мм")
                add("Плотность пикселей", "${Dev.fmt(sqrt((nw * nw + nh * nh).toFloat()) / diag, 0)} ppi")
                add("Плотность интерфейса", "${res.densityDpi} dpi (${densityName(res.densityDpi)}), масштаб ${Dev.fmt(res.density, 2)}")
                add("X DPI / Y DPI", "${Dev.fmt(res.xdpi, 0)} / ${Dev.fmt(res.ydpi, 0)}")
                val r = gcd(nw, nh); add("Соотношение сторон", "${maxOf(nw, nh) / r}:${minOf(nw, nh) / r}".let { s -> if (s.length > 7) Dev.fmt(maxOf(nw, nh).toFloat() / minOf(nw, nh), 2) + ":1" else s })
                add("Частота сейчас", "${Dev.fmt(d.refreshRate, 0)} Гц")
                add("Режимы", modes.joinToString("\n") { "${minOf(it.physicalWidth, it.physicalHeight)}×${maxOf(it.physicalWidth, it.physicalHeight)} @ ${Dev.fmt(it.refreshRate, 0)} Гц" })
                if (Build.VERSION.SDK_INT >= 34) d.mode.alternativeRefreshRates.takeIf { it.isNotEmpty() }?.let { add("Доп. частоты режима", it.joinToString(", ") { f -> Dev.fmt(f, 0) } + " Гц") }
                val hdr = d.hdrCapabilities?.supportedHdrTypes?.map { hdrName(it) }.orEmpty()
                add("HDR", hdr.joinToString(", ").ifEmpty { "нет" })
                d.hdrCapabilities?.let { c -> if (c.desiredMaxLuminance > 0) add("Яркость HDR (желаемая)", "до ${Dev.fmt(c.desiredMaxLuminance, 0)} нит, средняя ${Dev.fmt(c.desiredMaxAverageLuminance, 0)}, мин ${Dev.fmt(c.desiredMinLuminance, 4)}") }
                if (Build.VERSION.SDK_INT >= 34) runCatching { if (d.isHdrSdrRatioAvailable) add("HDR/SDR сейчас", Dev.fmt(d.hdrSdrRatio, 2) + "×") }
                flag("Широкая цветовая гамма", d.isWideColorGamut)
                runCatching { d.preferredWideGamutColorSpace?.name }.getOrNull()?.let { add("Цветовое пространство", it) }
                add("Состояние", when (d.state) { Display.STATE_ON -> "включён"; Display.STATE_OFF -> "выключен"; Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "дремлет (AOD)"; else -> "${d.state}" })
                add("Поворот", "${d.rotation * 90}°")
                if (Build.VERSION.SDK_INT >= 31) d.deviceProductInfo?.let { p ->
                    add("Панель (EDID)", p.name); add("Производитель (PnP)", p.manufacturerPnpId); add("Код продукта", p.productId)
                    runCatching { p.modelYear }.getOrNull()?.takeIf { it > 0 }?.let { add("Год модели", it) }
                }
                d.cutout?.let { c -> add("Вырез (камера)", c.boundingRects.joinToString { "${it.width()}×${it.height()} в (${it.left},${it.top})" }) }
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    val rc = d.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT); if (rc != null) add("Скругление углов", "${rc.radius} пикс")
                }
            }
        }
        val cr = ctx.contentResolver
        out += section("Настройки экрана") {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1).takeIf { it >= 0 }?.let { add("Яркость", "${it * 100 / 255} % ($it из 255)") }
            add("Автояркость", if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) "включена" else "выключена")
            Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1).takeIf { it > 0 }?.let { add("Отключение экрана", Dev.dur(it / 1000L)) }
            add("Масштаб шрифта", Dev.fmt(ctx.resources.configuration.fontScale, 2) + "×")
            add("Автоповорот", if (Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) "включён" else "выключен")
            Settings.System.getString(cr, "peak_refresh_rate")?.let { add("Макс. частота (настройка)", it.toFloatOrNull()?.let { f -> if (f > 1000) "без ограничения" else "${f.toInt()} Гц" } ?: it) }
            Settings.System.getString(cr, "min_refresh_rate")?.let { add("Мин. частота (настройка)", it.toFloatOrNull()?.let { f -> "${f.toInt()} Гц" } ?: it) }
            add("Тёмная тема", if ((ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) "включена" else "выключена")
            Settings.Secure.getString(cr, "doze_always_on")?.let { add("Всегда включённый экран", if (it == "1") "включён" else "выключен") }
            Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).let { add("Скорость анимаций", Dev.fmt(it, 1) + "×") }
        }
        return out
    }
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    fun densityName(d: Int) = when { d <= 120 -> "ldpi"; d <= 160 -> "mdpi"; d <= 240 -> "hdpi"; d <= 320 -> "xhdpi"; d <= 480 -> "xxhdpi"; else -> "xxxhdpi" }
    fun hdrName(t: Int) = when (t) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "тип $t" }

    // ---------------- звук ----------------
    fun audio(ctx: Context): List<InfoSection> {
        val am = ctx.getSystemService(AudioManager::class.java)
        val pm = ctx.packageManager
        val out = ArrayList<InfoSection>()
        out += section("Звук") {
            add("Частота вывода", am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let { "$it Гц" })
            add("Кадров в буфере", am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
            flag("Низкая задержка", pm.hasSystemFeature("android.hardware.audio.low_latency"))
            flag("Профессиональный звук", pm.hasSystemFeature("android.hardware.audio.pro"))
            flag("MIDI", pm.hasSystemFeature("android.software.midi"))
            if (Build.VERSION.SDK_INT >= 32) runCatching {
                val sp = am.spatializer
                add("Пространственный звук", "${if (sp.isAvailable) "есть" else "нет"}, ${if (sp.isEnabled) "включён" else "выключен"}, уровень ${sp.immersiveAudioLevel}")
                flag("Отслеживание головы", sp.isHeadTrackerAvailable)
            }
            add("Режим звонка", when (am.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> "звук"; AudioManager.RINGER_MODE_VIBRATE -> "вибрация"; else -> "без звука" })
            add("Режим аудио", when (am.mode) { AudioManager.MODE_NORMAL -> "обычный"; AudioManager.MODE_IN_CALL -> "разговор"; AudioManager.MODE_IN_COMMUNICATION -> "связь (VoIP)"; AudioManager.MODE_RINGTONE -> "звонок"; else -> "${am.mode}" })
            listOf(AudioManager.STREAM_MUSIC to "Медиа", AudioManager.STREAM_RING to "Звонок", AudioManager.STREAM_NOTIFICATION to "Уведомления",
                AudioManager.STREAM_ALARM to "Будильник", AudioManager.STREAM_VOICE_CALL to "Разговор").forEach { (s, n) ->
                add("Громкость: $n", "${am.getStreamVolume(s)} из ${am.getStreamMaxVolume(s)}")
            }
            flag("Музыка играет сейчас", am.isMusicActive)
        }
        val devs = am.getDevices(AudioManager.GET_DEVICES_ALL)
        if (devs.isNotEmpty()) out += InfoSection("Аудиоустройства", devs.map { d ->
            InfoRow((if (d.isSink) "▶ " else "● ") + audioType(d.type), listOfNotNull(d.productName?.toString()?.takeIf { it.isNotBlank() },
                d.sampleRates.takeIf { it.isNotEmpty() }?.let { "до ${it.max() / 1000} кГц" }, d.channelCounts.takeIf { it.isNotEmpty() }?.let { "каналов до ${it.max()}" },
                d.address.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { "—" })
        }, "▶ — вывод звука, ● — запись")
        runCatching {
            val mics = am.microphones
            if (mics.isNotEmpty()) out += InfoSection("Микрофоны", mics.map { m ->
                InfoRow(m.description.ifBlank { "микрофон ${m.id}" }, listOfNotNull(
                    when (m.location) { 1 -> "в корпусе"; 2 -> "на периферии"; 3 -> "внешний"; else -> null },
                    when (m.directionality) { 1 -> "всенаправленный"; 2 -> "двунаправленный"; 3 -> "кардиоида"; 4 -> "гиперкардиоида"; 5 -> "суперкардиоида"; else -> null },
                    m.sensitivity.takeIf { it > -1000 && it != 0f }?.let { "чувствит. ${Dev.fmt(it, 1)} дБФШ" },
                    m.maxSpl.takeIf { it > 0 }?.let { "макс. ${Dev.fmt(it, 0)} дБ SPL" },
                    m.position.let { p -> if (p.x != 0f || p.y != 0f) "(${Dev.fmt(p.x * 1000, 0)}; ${Dev.fmt(p.y * 1000, 0)}) мм" else null }).joinToString(" · ").ifEmpty { "—" })
            })
        }
        return out
    }
    fun audioType(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Динамик"; AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Разговорный динамик"; AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Встроенный микрофон"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Проводная гарнитура"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Проводные наушники"; AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (звонки)"; AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-аудио"; AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-гарнитура"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Телефония"; AudioDeviceInfo.TYPE_FM_TUNER -> "FM-тюнер"; AudioDeviceInfo.TYPE_HDMI -> "HDMI"; AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Внутренняя запись"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE гарнитура"; AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE колонка"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Динамик (системный)"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Слуховой аппарат"; AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Audio трансляция"
        else -> "Тип $t"
    }

    // ---------------- вибромотор ----------------
    fun vibrator(ctx: Context): InfoSection = section("Вибромотор") {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
        flag("Есть", v.hasVibrator())
        flag("Управление силой", v.hasAmplitudeControl())
        runCatching { (v.javaClass.getMethod("getResonantFrequency").invoke(v) as Float).takeIf { !it.isNaN() }?.let { add("Резонансная частота", "${Dev.fmt(it, 0)} Гц") } }
        runCatching { (v.javaClass.getMethod("getQFactor").invoke(v) as Float).takeIf { !it.isNaN() }?.let { add("Добротность (Q)", Dev.fmt(it, 1)) } }
        if (Build.VERSION.SDK_INT >= 30) {
            val prims = mapOf(1 to "щелчок", 2 to "подъём", 3 to "кручение", 4 to "быстрый подъём", 5 to "медленный подъём", 6 to "быстрое падение", 7 to "тик", 8 to "низкий тик")
            val ok = prims.filter { v.arePrimitivesSupported(it.key).firstOrNull() == true }.values
            add("Тактильные примитивы", ok.joinToString(", ").ifEmpty { "нет" })
            val eff = mapOf(0 to "щелчок", 1 to "двойной", 2 to "тик", 5 to "сильный щелчок")
            add("Готовые эффекты", eff.filter { v.areAllEffectsSupported(it.key) == android.os.Vibrator.VIBRATION_EFFECT_SUPPORT_YES }.values.joinToString(", ").ifEmpty { "нет данных" })
        }
    }

    // ---------------- устройства ввода ----------------
    fun inputs(ctx: Context): List<InfoSection> {
        val im = ctx.getSystemService(InputManager::class.java)
        val devs: List<InputDevice> = im.inputDeviceIds.toList().mapNotNull { im.getInputDevice(it) }
        return devs.map { d ->
            section(d.name) {
                add("ID", "${d.id} · vendor ${d.vendorId} · product ${d.productId}")
                add("Источники", sources(d.sources))
                add("Тип", if (d.isVirtual) "виртуальное" else if (d.isExternal) "внешнее" else "встроенное")
                if (d.keyboardType != InputDevice.KEYBOARD_TYPE_NONE) add("Клавиатура", if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) "буквенная" else "кнопки")
                d.getMotionRange(android.view.MotionEvent.AXIS_X)?.let { x -> d.getMotionRange(android.view.MotionEvent.AXIS_Y)?.let { y -> add("Область касаний", "${(x.max + 1).toInt()} × ${(y.max + 1).toInt()}") } }
                d.getMotionRange(android.view.MotionEvent.AXIS_PRESSURE)?.let { add("Давление", "до ${Dev.fmt(it.max, 2)}") }
                flag("Вибрация", d.vibrator.hasVibrator().takeIf { it })
                if (Build.VERSION.SDK_INT >= 31) runCatching { val b = d.batteryState; if (b.isPresent) add("Батарея", "${(b.capacity * 100).toInt()} %") }
                add("Дескриптор", d.descriptor)
            }
        }
    }
    private fun sources(s: Int): String = listOf(InputDevice.SOURCE_TOUCHSCREEN to "сенсорный экран", InputDevice.SOURCE_KEYBOARD to "кнопки", InputDevice.SOURCE_MOUSE to "мышь",
        InputDevice.SOURCE_STYLUS to "стилус", InputDevice.SOURCE_TOUCHPAD to "тачпад", InputDevice.SOURCE_JOYSTICK to "джойстик", InputDevice.SOURCE_GAMEPAD to "геймпад",
        InputDevice.SOURCE_DPAD to "крестовина", InputDevice.SOURCE_ROTARY_ENCODER to "колесо", InputDevice.SOURCE_SENSOR to "датчик")
        .filter { (f, _) -> s and f == f }.joinToString(", ") { it.second }.ifEmpty { "0x" + Integer.toHexString(s) }

    // ---------------- USB ----------------
    fun usb(ctx: Context): List<InfoSection> {
        val um = ctx.getSystemService(UsbManager::class.java)
        val pm = ctx.packageManager
        val out = ArrayList<InfoSection>()
        out += section("USB") {
            flag("Режим хоста (OTG)", pm.hasSystemFeature("android.hardware.usb.host"))
            flag("Режим аксессуара", pm.hasSystemFeature("android.hardware.usb.accessory"))
            add("Отладка по USB", if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) "включена" else "выключена")
            Dev.prop("sys.usb.config")?.let { add("Конфигурация", it) }
            Dev.prop("sys.usb.state")?.let { add("Состояние", it) }
            Dev.prop("ro.usb.controller")?.let { add("Контроллер", it) }
            add("Подключено устройств", um.deviceList.size)
        }
        um.deviceList.values.forEach { d ->
            out += section(d.productName ?: d.deviceName) {
                add("Производитель", d.manufacturerName); add("VID / PID", "%04X:%04X".format(d.vendorId, d.productId))
                add("Класс", d.deviceClass); add("Версия USB", runCatching { d.version }.getOrNull()); add("Интерфейсов", d.interfaceCount); add("Путь", d.deviceName)
            }
        }
        um.accessoryList?.forEach { a -> out += section("Аксессуар ${a.model}") { add("Производитель", a.manufacturer); add("Описание", a.description); add("Версия", a.version) } }
        return out
    }

    // ---------------- разделы ----------------
    data class Mount(val dev: String, val point: String, val fs: String, val rw: Boolean, val total: Long?, val avail: Long?)
    fun mounts(): List<Mount> = Dev.lines("/proc/mounts").mapNotNull { l ->
        val p = l.split(' '); if (p.size < 4) return@mapNotNull null
        val sf = if (p[2] in setOf("proc", "sysfs", "selinuxfs", "cgroup", "cgroup2", "devpts", "tracefs", "debugfs", "bpf", "pstore", "configfs", "functionfs", "binder", "fusectl"))
            null else runCatching { StatFs(p[1]) }.getOrNull()?.takeIf { it.totalBytes > 0 }
        Mount(p[0], p[1].replace("\\040", " "), p[2], p[3].split(',').contains("rw"), sf?.totalBytes, sf?.availableBytes)
    }.distinctBy { it.point }

    // ---------------- температура ----------------
    data class Zone(val name: String, val celsius: Float)
    /** Термодатчики ядра — на большинстве новых телефонов Android закрывает их от приложений; что откроется — покажем. */
    fun thermalZones(): List<Zone> = (0 until 120).mapNotNull { i ->
        val t = Dev.readLong("/sys/class/thermal/thermal_zone$i/temp") ?: return@mapNotNull null
        val name = Dev.read("/sys/class/thermal/thermal_zone$i/type") ?: "зона $i"
        val c = if (t > 1000 || t < -1000) t / 1000f else t.toFloat()
        Zone(name, c).takeIf { c > -40 && c < 150 }
    }
    fun thermalStatus(ctx: Context): Int = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
    fun thermalName(s: Int) = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "нормально"; PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий нагрев"; PowerManager.THERMAL_STATUS_MODERATE -> "средний — частоты снижены"
        PowerManager.THERMAL_STATUS_SEVERE -> "сильный — заметное снижение"; PowerManager.THERMAL_STATUS_CRITICAL -> "критический"; PowerManager.THERMAL_STATUS_EMERGENCY -> "аварийный"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "отключение"; else -> "неизвестно"
    }
    /** Запас до перегрева (1.0 = начнётся сильный троттлинг). Android не даёт спрашивать чаще раза в секунду. */
    fun headroom(ctx: Context, forecastSec: Int = 0): Float? = runCatching { ctx.getSystemService(PowerManager::class.java).getThermalHeadroom(forecastSec) }.getOrNull()?.takeIf { !it.isNaN() }
}
