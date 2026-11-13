package com.pank.vsevodnom.data.device

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Size
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Камеры (Camera2), кодеки (MediaCodec), датчики (SensorManager) — данные с телефона. */
object DevMedia {
    // ---------------- камеры ----------------
    data class Cam(val id: String, val title: String, val tags: List<String>, val mp: Double?, val headline: String, val sections: List<InfoSection>, val physical: Boolean)

    fun cameras(ctx: Context): List<Cam> {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val out = ArrayList<Cam>()
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        var back = 0; var front = 0
        ids.forEach { id ->
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val n = if (facing == CameraMetadata.LENS_FACING_FRONT) ++front else ++back
            val name = (if (facing == CameraMetadata.LENS_FACING_FRONT) "Передняя" else if (facing == CameraMetadata.LENS_FACING_BACK) "Задняя" else "Внешняя") + " $n"
            out += cam(ctx, cm, id, name, c, false); seen += id
            if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.filter { it !in seen }.forEach { pid ->
                runCatching { cm.getCameraCharacteristics(pid) }.getOrNull()?.let { pc -> out += cam(ctx, cm, pid, "$name · модуль $pid", pc, true); seen += pid }
            }
        }
        return out
    }

    private fun cam(ctx: Context, cm: CameraManager, id: String, name: String, c: CameraCharacteristics, physical: Boolean): Cam {
        val arr = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val arrMax = if (Build.VERSION.SDK_INT >= 31) c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) else null
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val diag = phys?.let { sqrt(it.width * it.width + it.height * it.height) }
        val crop = diag?.let { 43.27f / it }
        val eq = if (focal != null && crop != null) focal * crop else null
        val mp = arr?.let { it.width * it.height / 1e6 }
        val mpMax = arrMax?.let { it.width * it.height / 1e6 }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true
        val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val type = when { eq == null -> null; eq < 20 -> "сверхширокая"; eq <= 35 -> "широкоугольная"; else -> "телевик ~${Dev.fmt(eq / 24f, 1)}×" }
        val tags = listOfNotNull(type, if (ois) "OIS" else null, if (flash) "вспышка" else null, if (logical) "логическая" else null, if (physical) "физический модуль" else null)
        val headline = listOfNotNull((mpMax ?: mp)?.let { "${Dev.fmt(it, 1)} Мп" }, ap?.let { "f/${Dev.fmt(it, 2)}" }, eq?.let { "${it.roundToInt()} мм экв." }).joinToString(" · ")
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val secs = ArrayList<InfoSection>()
        secs += section("Сенсор и объектив") {
            add("ID камеры", id)
            add("Разрешение", arr?.let { "${Dev.fmt(mp!!, 1)} Мп (${it.width}×${it.height})" })
            if (mpMax != null && arrMax != null && mpMax > (mp ?: 0.0) * 1.2) add("Максимальное (без бининга)", "${Dev.fmt(mpMax, 1)} Мп (${arrMax.width}×${arrMax.height})")
            if (logical && Build.VERSION.SDK_INT >= 28) add("Состоит из модулей", c.physicalCameraIds.joinToString(", ") { pid ->
                runCatching { cm.getCameraCharacteristics(pid).get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) }.getOrNull()?.let { "$pid (${Dev.fmt(it.width * it.height / 1e6, 1)} Мп)" } ?: pid
            })
            add("Диафрагма", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(", ") { "f/${Dev.fmt(it, 2)}" })
            add("Фокусное расстояние", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(", ") { "${Dev.fmt(it, 2)} мм" })
            add("Эквивалент 35 мм", eq?.let { "${Dev.fmt(it, 1)} мм" })
            add("Кроп-фактор", crop?.let { Dev.fmt(it, 2) + "×" })
            if (phys != null && focal != null) {
                fun fov(d: Float) = Math.toDegrees(2 * atan(d / (2 * focal)).toDouble())
                add("Угол обзора", "по диагонали ${Dev.fmt(fov(diag!!), 1)}° · по горизонтали ${Dev.fmt(fov(maxOf(phys.width, phys.height)), 1)}° · по вертикали ${Dev.fmt(fov(minOf(phys.width, phys.height)), 1)}°")
            }
            phys?.let { add("Размер сенсора", "${Dev.fmt(it.width, 2)} × ${Dev.fmt(it.height, 2)} мм, диагональ ${Dev.fmt(diag!!, 2)} мм (≈1/${Dev.fmt(16f / diag, 2)}″)") }
            if (phys != null) (arrMax ?: arr)?.let { a -> add("Размер пикселя", "${Dev.fmt(phys.width * 1000 / a.width, 2)} мкм" + if (arrMax != null && arr != null && arrMax.width > arr.width) " (после бининга ${Dev.fmt(phys.width * 1000 / arr.width, 2)} мкм)" else "") }
            add("Цветовой фильтр", when (c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) { 0 -> "RGGB"; 1 -> "GRBG"; 2 -> "GBRG"; 3 -> "BGGR"; 4 -> "RGB"; 5 -> "монохром"; 6 -> "ИК"; else -> null })
            add("ISO", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { "${it.lower}–${it.upper}" })
            add("Выдержка", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { "${shutter(it.lower)} – ${shutter(it.upper)}" })
            add("Битность RAW", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { w -> "${(Math.log(w + 1.0) / Math.log(2.0)).roundToInt()} бит (белый $w)" })
            add("Мин. дистанция фокуса", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0 }?.let { "${Dev.fmt(100 / it, 1)} см" } ?: "фиксированный фокус")
            add("Калибровка фокуса", when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) { 0 -> "не откалиброван"; 1 -> "приблизительная"; 2 -> "откалиброван"; else -> null })
            add("Ориентация сенсора", c.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let { "$it°" })
            add("Источник отметок времени", when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) { 0 -> "неизвестно"; 1 -> "реальное время"; else -> null })
            c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.let { t -> add("Положение относительно основной", "${Dev.fmt(t[0] * 1000, 1)}; ${Dev.fmt(t[1] * 1000, 1)}; ${Dev.fmt(t[2] * 1000, 1)} мм") }
        }
        secs += section("Возможности") {
            add("Уровень Camera2", when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) { 2 -> "Legacy"; 0 -> "Limited"; 1 -> "Full"; 3 -> "Level 3"; 4 -> "внешняя"; else -> null })
            flag("Оптическая стабилизация (OIS)", ois)
            add("Стабилизация видео", c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString(", ") { when (it) { 0 -> "выкл"; 1 -> "вкл"; 2 -> "превью"; else -> "$it" } })
            flag("Вспышка", flash)
            if (Build.VERSION.SDK_INT >= 35) runCatching { c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL)?.takeIf { it > 1 }?.let { add("Уровней яркости фонарика", it) } }
            flag("Блокировка экспозиции (AE)", c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)); flag("Блокировка баланса белого", c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))
            add("Список", caps.mapNotNull { CAPS[it] }.joinToString("\n"))
            add("Зум", if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { "${Dev.fmt(it.lower, 1)}× – ${Dev.fmt(it.upper, 1)}×" } else null)
            add("Цифровой зум (макс.)", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { Dev.fmt(it, 1) + "×" })
            add("Обрезка при зуме", when (c.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) { 0 -> "только центр"; 1 -> "свободная"; else -> null })
            add("Компенсация экспозиции", c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r -> c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { s -> "${Dev.fmt(r.lower * s.toFloat(), 1)} … +${Dev.fmt(r.upper * s.toFloat(), 1)} EV, шаг ${s}" } })
            add("Режимы автоэкспозиции", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.joinToString(", ") { arrayOf("выкл", "авто", "автовспышка", "вспышка всегда", "от красных глаз", "внешняя вспышка", "ручная приоритет").getOrElse(it) { "$it" } })
            add("Автофокус", c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString(", ") { arrayOf("ручной", "авто", "макро", "непрерывный (видео)", "непрерывный (фото)", "EDOF").getOrElse(it) { "$it" } })
            add("Баланс белого", c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.joinToString(", ") { arrayOf("выкл", "авто", "лампа накаливания", "люминесцентная", "тёплая люминесцентная", "дневной свет", "облачно", "сумерки", "тень").getOrElse(it) { "$it" } })
            add("Сцены", c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.joinToString(", ") { SCENES[it] ?: "$it" })
            add("Эффекты", c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.joinToString(", ") { arrayOf("нет", "моно", "негатив", "соляризация", "сепия", "постеризация", "белая доска", "чёрная доска", "аква").getOrElse(it) { "$it" } })
            add("Антимерцание", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.joinToString(", ") { arrayOf("выкл", "50 Гц", "60 Гц", "авто").getOrElse(it) { "$it" } })
            add("Подавление шума", c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.joinToString(", ") { arrayOf("выкл", "быстрое", "качественное", "минимальное", "ZSL").getOrElse(it) { "$it" } })
            add("Резкость краёв", c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.joinToString(", ") { arrayOf("выкл", "быстро", "качественно", "ZSL").getOrElse(it) { "$it" } })
            add("Аберрации", c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.joinToString(", ") { arrayOf("выкл", "быстро", "качественно").getOrElse(it) { "$it" } })
            add("Горячие пиксели", c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.joinToString(", ") { arrayOf("выкл", "быстро", "качественно").getOrElse(it) { "$it" } })
            add("Распознавание лиц", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.joinToString(", ") { arrayOf("выкл", "простой", "полный").getOrElse(it) { "$it" } } +
                (c.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)?.let { " · до $it лиц" } ?: ""))
            add("Зон замера AE / AF / AWB", listOf(CameraCharacteristics.CONTROL_MAX_REGIONS_AE, CameraCharacteristics.CONTROL_MAX_REGIONS_AF, CameraCharacteristics.CONTROL_MAX_REGIONS_AWB).joinToString(" / ") { "${c.get(it) ?: 0}" })
            add("Потоков: обработанных / RAW / тяжёлых", listOf(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING).joinToString(" / ") { "${c.get(it) ?: 0}" })
            add("Частичных результатов", c.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT))
            if (Build.VERSION.SDK_INT >= 33) c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.let { p ->
                add("Профили HDR", p.joinToString(", ") { when (it) { 1L -> "стандарт"; 2L -> "HLG10"; 4L -> "HDR10"; 8L -> "HDR10+"; 16L, 32L, 64L, 128L -> "Dolby Vision"; else -> "$it" } }.split(", ").distinct().joinToString(", "))
            }
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                val ex = cm.getCameraExtensionCharacteristics(id).supportedExtensions
                add("Режимы производителя (Extensions)", ex.joinToString(", ") { when (it) { 0 -> "авто"; 1 -> "боке"; 2 -> "ретушь лица"; 3 -> "HDR"; 4 -> "ночь"; else -> "$it" } }.ifEmpty { "нет" })
            }
            add("Тестовые шаблоны", c.get(CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES)?.size)
        }
        if (map != null) secs += section("Форматы и видео") {
            add("Форматы вывода", map.outputFormats.joinToString(", ") { FORMATS[it] ?: "0x" + Integer.toHexString(it) })
            add("Размеры JPEG", map.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width * it.height }?.take(12)?.joinToString(", ") { "${it.width}×${it.height}" })
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }?.let { add("RAW", "${it.width}×${it.height}") }
            add("Частоты кадров (AE)", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString(", ") { if (it.lower == it.upper) "${it.upper}" else "${it.lower}–${it.upper}" })
            val hs = runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList())
            if (hs.isNotEmpty()) add("Замедленная съёмка", hs.sortedByDescending { it.width }.joinToString("\n") { s -> "${s.width}×${s.height}: до ${map.getHighSpeedVideoFpsRangesFor(s).maxOf { it.upper }} к/с" })
            val idInt = id.toIntOrNull()
            if (idInt != null) add("Профили записи", listOf(CamcorderProfile.QUALITY_2160P to "4K", CamcorderProfile.QUALITY_1080P to "1080p", CamcorderProfile.QUALITY_720P to "720p",
                CamcorderProfile.QUALITY_HIGH_SPEED_1080P to "1080p замедл.", CamcorderProfile.QUALITY_HIGH_SPEED_720P to "720p замедл.", CamcorderProfile.QUALITY_HIGH_SPEED_2160P to "4K замедл.")
                .filter { runCatching { CamcorderProfile.hasProfile(idInt, it.first) }.getOrDefault(false) }.joinToString(", ") { it.second })
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                CamcorderProfile.getAll(id, CamcorderProfile.QUALITY_HIGH)?.videoProfiles?.filterNotNull()?.firstOrNull()?.let { v ->
                    add("Лучший профиль", "${v.width}×${v.height} @ ${v.frameRate} к/с, ${v.bitrate / 1_000_000} Мбит/с, ${v.mediaType}")
                }
            }
        }
        return Cam(id, name, tags, mpMax ?: mp, headline, secs, physical)
    }
    private fun shutter(ns: Long): String = if (ns >= 1_000_000_000) "${Dev.fmt(ns / 1e9, 1)} с" else "1/${(1e9 / ns).roundToInt()}"
    private val CAPS = mapOf(0 to "обратная совместимость", 1 to "ручной сенсор", 2 to "ручная постобработка", 3 to "RAW", 4 to "перевыдача кадров (ZSL)", 5 to "чтение настроек",
        6 to "серийная съёмка", 7 to "глубина", 8 to "ограниченная высокоскоростная съёмка", 9 to "движение", 10 to "логическая мультикамера", 11 to "монохром", 12 to "безопасная съёмка",
        13 to "системная камера", 14 to "перевыдача YUV", 16 to "сверхвысокое разрешение", 17 to "перевыдача с RAW", 18 to "HDR 10 бит", 19 to "сценарии потоков", 20 to "цветовые пространства")
    private val SCENES = mapOf(0 to "выкл", 1 to "приоритет лица", 2 to "экшн", 3 to "портрет", 4 to "пейзаж", 5 to "ночь", 6 to "ночной портрет", 7 to "театр", 8 to "пляж", 9 to "снег",
        10 to "закат", 11 to "без дрожания", 12 to "фейерверк", 13 to "спорт", 14 to "вечеринка", 15 to "свеча", 16 to "штрихкод", 18 to "HDR")
    private val FORMATS = mapOf(ImageFormat.JPEG to "JPEG", ImageFormat.YUV_420_888 to "YUV_420_888", ImageFormat.RAW_SENSOR to "RAW_SENSOR", ImageFormat.RAW10 to "RAW10",
        ImageFormat.RAW12 to "RAW12", ImageFormat.PRIVATE to "PRIVATE", ImageFormat.DEPTH16 to "DEPTH16", ImageFormat.DEPTH_POINT_CLOUD to "DEPTH_POINT_CLOUD", ImageFormat.YCBCR_P010 to "P010 (10 бит)",
        ImageFormat.HEIC to "HEIC", ImageFormat.Y8 to "Y8", ImageFormat.NV21 to "NV21", ImageFormat.YV12 to "YV12", 0x1004 to "JPEG_R (Ultra HDR)")

    // ---------------- кодеки ----------------
    data class Codec(val name: String, val types: List<String>, val encoder: Boolean, val hardware: Boolean, val vendor: Boolean, val alias: Boolean, val audio: Boolean, val info: MediaCodecInfo)

    fun codecs(): List<Codec> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.map { ci ->
        Codec(ci.name, ci.supportedTypes.toList(), ci.isEncoder, if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated else !ci.name.startsWith("OMX.google") && !ci.name.startsWith("c2.android"),
            if (Build.VERSION.SDK_INT >= 29) ci.isVendor else false, if (Build.VERSION.SDK_INT >= 29) ci.isAlias else false, ci.supportedTypes.all { it.startsWith("audio/") }, ci)
    }

    fun codecDetails(c: Codec): List<InfoSection> = c.types.map { t ->
        val caps = runCatching { c.info.getCapabilitiesForType(t) }.getOrNull()
        section(t) {
            add("Имя", c.name); if (Build.VERSION.SDK_INT >= 29) add("Каноническое имя", c.info.canonicalName)
            add("Тип", (if (c.encoder) "кодер" else "декодер") + " · " + (if (c.hardware) "аппаратный" else "программный") + if (c.vendor) " · от производителя" else "")
            caps?.videoCapabilities?.let { v ->
                add("Разрешение", "до ${v.supportedWidths.upper}×${v.supportedHeights.upper}")
                add("Частота кадров", "до ${v.supportedFrameRates.upper} к/с")
                add("Битрейт", "${v.bitrateRange.lower / 1000} кбит/с – ${v.bitrateRange.upper / 1_000_000} Мбит/с")
                runCatching { add("4K @ 60", if (v.areSizeAndRateSupported(3840, 2160, 60.0)) "да" else "нет") }
                runCatching { add("8K @ 30", if (v.areSizeAndRateSupported(7680, 4320, 30.0)) "да" else "нет") }
                add("Выравнивание", "${v.widthAlignment}×${v.heightAlignment}")
            }
            caps?.audioCapabilities?.let { a ->
                add("Каналов", "до ${a.maxInputChannelCount}")
                add("Частоты", a.supportedSampleRates?.joinToString(", ") { "${it / 1000.0}".removeSuffix(".0") + " кГц" } ?: a.supportedSampleRateRanges.joinToString { "${it.lower}–${it.upper} Гц" })
                add("Битрейт", "${a.bitrateRange.lower / 1000}–${a.bitrateRange.upper / 1000} кбит/с")
            }
            caps?.encoderCapabilities?.let { e -> add("Сложность", "${e.complexityRange.lower}–${e.complexityRange.upper}"); runCatching { add("Режим CBR/VBR/CQ", listOf(0, 1, 2).filter { e.isBitrateModeSupported(it) }.joinToString { arrayOf("CQ", "VBR", "CBR")[it] }) } }
            caps?.let { cp ->
                add("Профили / уровни", cp.profileLevels.take(24).joinToString(", ") { "${profileName(t, it.profile)}@${it.level}" })
                add("Возможности", listOf("adaptive-playback" to "адаптивное", "secure-playback" to "защищённое (DRM)", "tunneled-playback" to "туннельное", "low-latency" to "низкая задержка",
                    "partial-frame" to "частичные кадры", "intra-refresh" to "intra-refresh", "multiple-frames" to "много кадров", "dynamic-timestamp" to "динамические метки", "hdr-editing" to "редактирование HDR")
                    .filter { (k, _) -> runCatching { cp.isFeatureSupported(k) }.getOrDefault(false) }.joinToString(", ") { it.second }.ifEmpty { "—" })
                add("Цветовые форматы", cp.colorFormats.take(10).joinToString(", ").ifEmpty { null })
                add("Экземпляров одновременно", cp.maxSupportedInstances)
            }
        }
    }
    private fun profileName(type: String, p: Int): String = when (type) {
        "video/avc" -> mapOf(1 to "Baseline", 2 to "Main", 4 to "Extended", 8 to "High", 16 to "High10", 65536 to "ConstrainedBaseline", 524288 to "ConstrainedHigh")[p]
        "video/hevc" -> mapOf(1 to "Main", 2 to "Main10", 4 to "MainStill", 4096 to "Main10HDR10", 8192 to "Main10HDR10+")[p]
        "video/x-vnd.on2.vp9" -> mapOf(1 to "P0", 2 to "P1", 4 to "P2", 8 to "P3", 4096 to "P2 HDR", 8192 to "P3 HDR", 16384 to "P2 HDR10+")[p]
        "video/av01" -> mapOf(1 to "Main8", 2 to "Main10", 4096 to "Main10HDR10", 8192 to "Main10HDR10+")[p]
        "audio/mp4a-latm" -> mapOf(2 to "LC", 5 to "HE", 29 to "HEv2", 23 to "LD", 39 to "ELD", 42 to "xHE")[p]
        else -> null
    } ?: "$p"

    // ---------------- датчики ----------------
    fun sensorType(s: Sensor): String = when (s.type) {
        Sensor.TYPE_ACCELEROMETER -> "Акселерометр"; Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Акселерометр (не откалиброван)"; Sensor.TYPE_GYROSCOPE -> "Гироскоп"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Гироскоп (не откалиброван)"; Sensor.TYPE_MAGNETIC_FIELD -> "Магнитное поле"; Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Магнитное поле (не откалибровано)"
        Sensor.TYPE_LIGHT -> "Освещённость"; Sensor.TYPE_PRESSURE -> "Давление (барометр)"; Sensor.TYPE_PROXIMITY -> "Приближение"; Sensor.TYPE_GRAVITY -> "Гравитация"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Линейное ускорение"; Sensor.TYPE_ROTATION_VECTOR -> "Вектор поворота"; Sensor.TYPE_GAME_ROTATION_VECTOR -> "Игровой вектор поворота"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Геомагнитный вектор поворота"; Sensor.TYPE_RELATIVE_HUMIDITY -> "Влажность"; Sensor.TYPE_AMBIENT_TEMPERATURE -> "Температура воздуха"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Значимое движение"; Sensor.TYPE_STEP_DETECTOR -> "Детектор шагов"; Sensor.TYPE_STEP_COUNTER -> "Шагомер"
        Sensor.TYPE_HEART_RATE -> "Пульс"; Sensor.TYPE_HEART_BEAT -> "Сердцебиение"; Sensor.TYPE_POSE_6DOF -> "Положение 6DOF"; Sensor.TYPE_STATIONARY_DETECT -> "Неподвижность"
        Sensor.TYPE_MOTION_DETECT -> "Движение"; Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "Снят с тела"; Sensor.TYPE_HINGE_ANGLE -> "Угол шарнира"
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES -> "Акселерометр (оси)"; Sensor.TYPE_GYROSCOPE_LIMITED_AXES -> "Гироскоп (оси)"; Sensor.TYPE_HEAD_TRACKER -> "Трекер головы"
        Sensor.TYPE_HEADING -> "Курс"; 3 -> "Ориентация"
        else -> s.stringType.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    fun sensorUnit(t: Int) = when (t) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "м/с²"
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "рад/с"; Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "мкТл"
        Sensor.TYPE_LIGHT -> "лк"; Sensor.TYPE_PRESSURE -> "гПа"; Sensor.TYPE_PROXIMITY -> "см"; Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"; Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_HEART_RATE -> "уд/мин"; Sensor.TYPE_STEP_COUNTER -> "шагов"; 3 -> "°"; else -> ""
    }
    fun isStandard(s: Sensor) = s.type < Sensor.TYPE_DEVICE_PRIVATE_BASE
    fun sensorRows(s: Sensor): List<InfoRow> = section("") {
        add("Производитель", s.vendor); add("Тип", "${s.stringType} (${s.type})"); add("Версия", s.version)
        add("Разрешение", "${s.resolution} ${sensorUnit(s.type)}".trim())
        add("Макс. диапазон", if (s.maximumRange > 1e9) "∞" else "${s.maximumRange} ${sensorUnit(s.type)}".trim())
        add("Потребление", "${s.power} мА")
        add("Частота", when (s.reportingMode) { Sensor.REPORTING_MODE_CONTINUOUS -> if (s.minDelay > 0) "до ${1_000_000 / s.minDelay} Гц" else "непрерывно"
            Sensor.REPORTING_MODE_ON_CHANGE -> "при изменении"; Sensor.REPORTING_MODE_ONE_SHOT -> "однократно"; else -> "особый режим" })
        if (s.maxDelay > 0) add("Мин. частота", "${Dev.fmt(1_000_000f / s.maxDelay, 1)} Гц")
        if (s.fifoMaxEventCount > 0) add("Буфер (FIFO)", "${s.fifoReservedEventCount} / ${s.fifoMaxEventCount} событий")
        flag("Будит телефон", s.isWakeUpSensor); flag("Динамический", s.isDynamicSensor)
        if (s.id > 0) add("ID", s.id)
        flag("Частный (не из Android)", s.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE)
    }.rows

    fun sensors(ctx: Context): List<Sensor> = ctx.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
}
