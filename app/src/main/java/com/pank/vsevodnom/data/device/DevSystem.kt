package com.pank.vsevodnom.data.device

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Система, сборка, среда, настройки, безопасность (аппаратная аттестация), DRM — всё с телефона. */
object DevSystem {
    private val DF = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DF)

    /** Имя и дата выхода версии Android (по уровню API — это справочник, а не данные телефона). */
    fun androidName(sdk: Int): Pair<String, String?> = when (sdk) {
        29 -> "Android 10" to "3 сентября 2019"; 30 -> "Android 11 (Red Velvet Cake)" to "8 сентября 2020"; 31 -> "Android 12 (Snow Cone)" to "4 октября 2021"
        32 -> "Android 12L" to "7 марта 2022"; 33 -> "Android 13 (Tiramisu)" to "15 августа 2022"; 34 -> "Android 14 (Upside Down Cake)" to "4 октября 2023"
        35 -> "Android 15 (Vanilla Ice Cream)" to "15 октября 2024"; 36 -> "Android 16 (Baklava)" to "10 июня 2025"; 37 -> "Android 17" to null
        else -> "API $sdk" to null
    }

    fun deviceName(ctx: Context): String = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun system(ctx: Context): List<InfoSection> {
        val pm = ctx.packageManager
        val out = ArrayList<InfoSection>()
        out += section("Устройство") {
            add("Название", deviceName(ctx))
            add("Модель", Build.MODEL); add("Производитель", Build.MANUFACTURER); add("Марка", Build.BRAND)
            add("Кодовое имя (устройство)", Build.DEVICE); add("Продукт", Build.PRODUCT); add("Плата", Build.BOARD); add("Аппаратная платформа", Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= 31) { add("SKU", Build.SKU); add("ODM SKU", Build.ODM_SKU); add("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}") }
            add("Тип", when { pm.hasSystemFeature("android.hardware.type.watch") -> "часы"; pm.hasSystemFeature("android.hardware.type.automotive") -> "авто"
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> "ТВ"; ctx.resources.configuration.smallestScreenWidthDp >= 600 -> "планшет"; else -> "телефон" })
            add("Android ID", Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID), "уникален для приложения и пользователя")
            add("Загрузчик", Build.BOOTLOADER); add("Модем (baseband)", Build.getRadioVersion())
            Dev.prop("ro.boot.hardware.revision")?.let { add("Ревизия платы", it) }
        }
        val (aName, aDate) = androidName(Build.VERSION.SDK_INT)
        out += section("Android") {
            add("Версия", aName)
            add("Уровень API", if (Build.VERSION.SDK_INT >= 36) runCatching { Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null).let { "${it / 100000}.${it % 100000}" } }.getOrNull() ?: "${Build.VERSION.SDK_INT}" else "${Build.VERSION.SDK_INT}")
            add("Выпущена", aDate)
            add("Патч безопасности", Build.VERSION.SECURITY_PATCH)
            Dev.prop("ro.vendor.build.security_patch")?.let { add("Патч вендора", it) }
            add("Сборка", Build.DISPLAY); add("Номер сборки", Build.VERSION.INCREMENTAL)
            add("Дата сборки", date(Build.TIME)); add("Тип сборки", "${Build.TYPE} · ${Build.TAGS}"); add("Сборщик", "${Build.USER}@${Build.HOST}")
            add("Отпечаток", Build.FINGERPRINT)
            Dev.prop("ro.product.first_api_level")?.let { add("Вышел с Android", androidName(it.toIntOrNull() ?: 0).first) }
            if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS.takeIf { it > 0 }?.let { add("Класс производительности медиа", androidName(it).first) }
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val ext = listOf(30, 31, 33, 34, 35, 36).mapNotNull { v -> runCatching { "R$v:" + android.os.ext.SdkExtensions.getExtensionVersion(v) }.getOrNull() }
                add("Расширения SDK", ext.joinToString(" "))
            }
            pkgVer(pm, "com.google.android.modulemetadata")?.let { add("Обновление системы Google Play", it) }
        }
        out += section("Обновления и разделы") {
            add("Treble", Dev.prop("ro.treble.enabled")?.let { if (it == "true") "поддерживается" else "нет" })
            add("Бесшовные обновления (A/B)", Dev.prop("ro.build.ab_update")?.let { if (it == "true") "да" else "нет" })
            add("Активный слот", Dev.prop("ro.boot.slot_suffix")?.removePrefix("_")?.uppercase())
            add("Динамические разделы", Dev.prop("ro.boot.dynamic_partitions")?.let { if (it == "true") "да" else "нет" })
            add("Виртуальные A/B", Dev.prop("ro.virtual_ab.enabled")?.let { if (it == "true") "да" else "нет" })
            add("Сжатые снимки", Dev.prop("ro.virtual_ab.compression.enabled")?.let { if (it == "true") "да" else "нет" })
            add("Ядро GKI", Dev.prop("ro.kernel.version") ?: System.getProperty("os.version"))
            if (Build.VERSION.SDK_INT >= 29) Build.getFingerprintedPartitions().forEach { p -> add("Раздел ${p.name}", "${date(p.buildTimeMillis)}\n${p.fingerprint}") }
        }
        runCatching {
            val mods = pm.getInstalledModules(0)
            if (mods.isNotEmpty()) out += InfoSection("Модули Mainline (${mods.size})", mods.sortedBy { it.name?.toString() }.map { m ->
                InfoRow(m.name?.toString() ?: m.packageName ?: "?", listOfNotNull(m.packageName, m.packageName?.let { pkgVer(pm, it, true) }).joinToString(" · "))
            })
        }
        out += section("Среда выполнения") {
            add("Ядро Linux", Dev.read("/proc/version") ?: Dev.exec("uname", "-a") ?: System.getProperty("os.version"))
            add("Java VM", "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
            add("Архитектура", System.getProperty("os.arch"))
            add("Наборы команд", Build.SUPPORTED_ABIS.joinToString(", "))
            add("SELinux", Dev.exec("getenforce") ?: Dev.prop("ro.boot.selinux"))
            add("Toybox", Dev.exec("toybox", "--version"))
            runCatching { javax.net.ssl.SSLContext.getDefault() }.getOrNull()?.let { s -> add("SSL", s.provider.name + " " + (runCatching { s.provider.javaClass.getMethod("getVersionStr").invoke(s.provider) as String }.getOrNull() ?: "")); add("TLS", s.supportedSSLParameters.protocols.joinToString(", ")) }
            runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()?.let { add("WebView", "${it.packageName} ${it.versionName}") }
            pkgVer(pm, "com.google.android.gms")?.let { add("Сервисы Google Play", it) }
            pkgVer(pm, "com.android.vending")?.let { add("Google Play Маркет", it) }
            add("User-Agent", System.getProperty("http.agent"))
            add("Куча приложения (макс.)", Dev.bytes(Runtime.getRuntime().maxMemory()))
            if (Build.VERSION.SDK_INT >= 29) runCatching { add("Шрифтов в системе", android.graphics.fonts.SystemFonts.getAvailableFonts().size) }
        }
        val up = SystemClock.elapsedRealtime(); val awake = SystemClock.uptimeMillis()
        out += section("Время и язык") {
            add("Работает без перезагрузки", Dev.dur(up / 1000))
            add("Из них в глубоком сне", "${Dev.dur((up - awake) / 1000)} (${(up - awake) * 100 / up.coerceAtLeast(1)} %)")
            add("Включён", date(System.currentTimeMillis() - up))
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it > 0 }?.let { add("Перезагрузок всего", it) }
            val tz = TimeZone.getDefault()
            add("Часовой пояс", "${tz.getDisplayName(tz.inDaylightTime(java.util.Date()), TimeZone.LONG, Locale("ru"))} (${tz.id}, UTC${(tz.getOffset(System.currentTimeMillis()) / 3600000).let { if (it >= 0) "+$it" else "$it" }})")
            add("Язык", "${Locale.getDefault().getDisplayName(Locale("ru"))} (${Locale.getDefault().toLanguageTag()})")
            add("Языки системы", ctx.resources.configuration.locales.toLanguageTags())
            add("Время по сети", if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 0) == 1) "да" else "нет")
            add("24-часовой формат", if (android.text.format.DateFormat.is24HourFormat(ctx)) "да" else "нет")
        }
        out += settings(ctx)
        return out
    }

    fun pkgVer(pm: PackageManager, pkg: String, apex: Boolean = false): String? = runCatching {
        val pi = pm.getPackageInfo(pkg, if (apex) PackageManager.MATCH_APEX else 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrNull()

    private fun settings(ctx: Context): InfoSection {
        val cr = ctx.contentResolver; val pm = ctx.packageManager
        val power = ctx.getSystemService(PowerManager::class.java)
        fun g(k: String) = Settings.Global.getInt(cr, k, 0) == 1
        return section("Настройки и роли") {
            add("Параметры разработчика", if (g(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) "включены" else "выключены")
            add("Отладка по USB", if (g(Settings.Global.ADB_ENABLED)) "включена" else "выключена")
            add("Отладка по Wi-Fi", if (g("adb_wifi_enabled")) "включена" else "выключена")
            add("Не выключать экран при зарядке", if (Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0) "да" else "нет")
            add("Режим полёта", if (g(Settings.Global.AIRPLANE_MODE_ON)) "включён" else "выключен")
            add("Энергосбережение", if (power.isPowerSaveMode) "включено" else "выключено")
            add("Режим ожидания (Doze)", if (power.isDeviceIdleMode) "сейчас" else "нет")
            flag("Приложению разрешено работать без ограничений батареи", power.isIgnoringBatteryOptimizations(ctx.packageName))
            fun label(pkg: String?) = pkg?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() + " ($p)" }.getOrDefault(p) }
            add("Рабочий стол", label(pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName))
            add("Браузер", label(pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }))
            add("Звонилка", label(ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage))
            add("СМС", label(android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)))
            add("Клавиатура", Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { label(it) })
            add("Ассистент", Settings.Secure.getString(cr, "assistant")?.substringBefore('/')?.let { label(it) })
            add("Автозаполнение", Settings.Secure.getString(cr, "autofill_service")?.substringBefore('/')?.let { label(it) })
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.takeIf { it.isNotBlank() }?.let { s ->
                add("Спец. возможности включены", s.split(':').joinToString("\n") { label(it.substringBefore('/')) ?: it })
            }
            Settings.Secure.getString(cr, "enabled_notification_listeners")?.takeIf { it.isNotBlank() }?.let { s ->
                add("Читают уведомления", s.split(':').joinToString("\n") { label(it.substringBefore('/')) ?: it })
            }
        }
    }

    // ---------------- безопасность ----------------
    fun security(ctx: Context): List<InfoSection> {
        val pm = ctx.packageManager
        val out = ArrayList<InfoSection>()
        val km = ctx.getSystemService(KeyguardManager::class.java)
        out += section("Защита") {
            flag("Блокировка экрана установлена", km.isDeviceSecure)
            val bm = androidx.biometric.BiometricManager.from(ctx)
            flag("Надёжная биометрия (класс 3)", bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
            flag("Слабая биометрия (класс 2, лицо)", bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
            flag("Сканер отпечатков", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
            flag("Распознавание лица", pm.hasSystemFeature(PackageManager.FEATURE_FACE))
            flag("StrongBox (отдельный чип безопасности)", pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
            pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_HARDWARE_KEYSTORE }?.let { add("Аппаратное хранилище ключей", "версия ${it.version}") }
            flag("Аттестация ключей приложения", pm.hasSystemFeature("android.hardware.keystore.app_attest_key"))
            flag("Цифровые документы (Identity Credential)", pm.hasSystemFeature("android.hardware.identity_credential"))
            add("Проверенная загрузка", Dev.prop("ro.boot.verifiedbootstate")?.let { bootState(it) })
            add("Загрузчик", Dev.prop("ro.boot.flash.locked")?.let { if (it == "1") "заблокирован" else "разблокирован" } ?: Dev.prop("ro.boot.vbmeta.device_state")?.let { if (it == "locked") "заблокирован" else "разблокирован" })
            add("dm-verity", Dev.prop("ro.boot.veritymode") ?: Dev.prop("partition.system.verified"))
            add("AVB", Dev.prop("ro.boot.vbmeta.avb_version")?.let { "версия $it" })
            add("Отладочная сборка (ro.debuggable)", Dev.prop("ro.debuggable")?.let { if (it == "1") "да" else "нет" })
            Dev.prop("ro.boot.hardware.sku")?.let { add("SKU платы", it) }
        }
        out += attestation()
        out += root(ctx)
        out += drm()
        return out
    }
    private fun bootState(s: String) = when (s) { "green" -> "зелёная — система подлинная"; "yellow" -> "жёлтая — свой ключ"; "orange" -> "оранжевая — загрузчик разблокирован"; "red" -> "красная — ошибка"; else -> s }

    /** Аппаратная аттестация: ключ создаётся в чипе безопасности, и чип сам подписывает состояние загрузки — подделать его программно нельзя. */
    fun attestation(): InfoSection = section("Аппаратная аттестация ключа") {
        val alias = "vse_attest_probe"
        val r = runCatching {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge("vse-v-odnom".toByteArray()).build())
            kpg.generateKeyPair()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
            ks.deleteEntry(alias)
            chain
        }
        val chain = r.getOrNull()
        if (chain == null) { add("Результат", "не удалось: ${r.exceptionOrNull()?.message ?: "нет поддержки"}"); return@section }
        add("Цепочка сертификатов", "${chain.size} шт.")
        add("Корневой сертификат", chain.last().subjectX500Principal.name.substringAfter("CN=", "").substringBefore(',').ifEmpty { chain.last().subjectX500Principal.name })
        val ext = chain.first().getExtensionValue("1.3.6.1.4.1.11129.2.1.17")
        if (ext == null) { add("Данные аттестации", "нет (программное хранилище)"); return@section }
        val kd = Der.parse(Der.parse(ext).bytes).children
        fun lvl(i: Int) = when (kd.getOrNull(i)?.int) { 0 -> "программно"; 1 -> "TEE (доверенная среда)"; 2 -> "StrongBox (отдельный чип)"; else -> null }
        add("Версия аттестации", kd.getOrNull(0)?.int)
        add("Уровень аттестации", lvl(1))
        add("Версия KeyMint/Keymaster", kd.getOrNull(2)?.int)
        add("Где живёт ключ", lvl(3))
        val hw = kd.getOrNull(7)?.children.orEmpty() + kd.getOrNull(6)?.children.orEmpty()
        hw.firstOrNull { it.tag == 704 }?.children?.firstOrNull()?.children?.let { rot ->
            add("Загрузчик (по чипу)", rot.getOrNull(1)?.bool?.let { if (it) "заблокирован" else "разблокирован" })
            add("Состояние загрузки (по чипу)", when (rot.getOrNull(2)?.int) { 0 -> "проверена (зелёная)"; 1 -> "свой ключ (жёлтая)"; 2 -> "не проверяется (оранжевая)"; 3 -> "ошибка (красная)"; else -> null })
            rot.getOrNull(0)?.bytes?.let { add("Ключ загрузки (SHA-256)", hex(it).take(16) + "…") }
        }
        fun lvlDate(tag: Int) = hw.firstOrNull { it.tag == tag }?.children?.firstOrNull()?.int?.toString()?.let { if (it.length >= 6) "${it.take(4)}-${it.substring(4, 6)}" + if (it.length == 8) "-${it.substring(6, 8)}" else "" else it }
        hw.firstOrNull { it.tag == 705 }?.children?.firstOrNull()?.int?.let { v -> add("Версия ОС (по чипу)", "${v / 10000}.${v / 100 % 100}.${v % 100}") }
        add("Патч ОС (по чипу)", lvlDate(706)); add("Патч вендора (по чипу)", lvlDate(718)); add("Патч загрузки (по чипу)", lvlDate(719))
        note = "Эти данные подписаны самим чипом безопасности — их нельзя подделать прошивкой или root."
    }

    private fun root(ctx: Context): InfoSection = section("Root и модификации") {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap")
        val found = paths.filter { runCatching { java.io.File(it).exists() }.getOrDefault(false) }
        val pkgs = mapOf("com.topjohnwu.magisk" to "Magisk", "io.github.vvb2060.magisk" to "Magisk Alpha", "io.github.huskydg.magisk" to "Kitsune Magisk",
            "me.weishu.kernelsu" to "KernelSU", "com.rifsxd.ksunext" to "KernelSU Next", "me.bmax.apatch" to "APatch", "eu.chainfire.supersu" to "SuperSU",
            "org.lsposed.manager" to "LSPosed", "de.robv.android.xposed.installer" to "Xposed", "com.saurik.substrate" to "Substrate")
        val apps = pkgs.filter { (p, _) -> runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }.values
        val suInPath = (System.getenv("PATH") ?: "").split(':').any { runCatching { java.io.File(it, "su").exists() }.getOrDefault(false) }
        flag("Найден su", found.isNotEmpty() || suInPath)
        if (found.isNotEmpty()) add("Файлы", found.joinToString("\n"))
        add("Приложения root/модов", apps.joinToString(", ").ifEmpty { "не найдены" })
        flag("Тестовые ключи сборки", Build.TAGS?.contains("test-keys") == true)
        add("Вывод", if (found.isEmpty() && apps.isEmpty() && !suInPath) "признаков root нет" else "есть признаки root/модификаций")
        note = "Хорошо спрятанный root (Magisk с DenyList) так не найти — точнее смотреть аппаратную аттестацию выше."
    }

    private val DRMS = listOf("Widevine" to UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"), "ClearKey" to UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
        "PlayReady" to UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"))
    fun drm(): List<InfoSection> = DRMS.mapNotNull { (name, uuid) ->
        if (!runCatching { MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)) return@mapNotNull null
        val d = runCatching { MediaDrm(uuid) }.getOrNull() ?: return@mapNotNull null
        try {
            section("DRM: $name") {
                fun p(k: String) = runCatching { d.getPropertyString(k) }.getOrNull()
                add("Поставщик", p(MediaDrm.PROPERTY_VENDOR)); add("Версия", p(MediaDrm.PROPERTY_VERSION)); add("Описание", p(MediaDrm.PROPERTY_DESCRIPTION))
                add("Алгоритмы", p(MediaDrm.PROPERTY_ALGORITHMS))
                add("Уровень защиты", p("securityLevel")?.let { if (it == "L1") "L1 — HD/4K в Netflix и т.п." else if (it == "L3") "L3 — только SD" else it })
                add("System ID", p("systemId"))
                add("HDCP сейчас / макс.", listOfNotNull(p("hdcpLevel"), p("maxHdcpLevel")).joinToString(" / "))
                add("Сессий (макс.)", p("maxNumberOfSessions"))
                add("Отчёты использования", p("usageReportingSupport"))
                runCatching { d.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID) }.getOrNull()?.let { add("ID устройства (хеш)", hex(MessageDigest.getInstance("SHA-256").digest(it)).take(16) + "…") }
            }
        } finally { runCatching { d.close() } }
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Минимальный разбор DER (ASN.1) — нужен для данных аттестации. */
    class Der(val tag: Int, val constructed: Boolean, val bytes: ByteArray) {
        val children: List<Der> by lazy { if (constructed) parseAll(bytes) else emptyList() }
        val int: Int? get() = if (bytes.isEmpty() || bytes.size > 8) null else bytes.fold(if (bytes[0] < 0) -1L else 0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }.toInt()
        val bool: Boolean get() = bytes.isNotEmpty() && bytes[0] != 0.toByte()
        companion object {
            fun parse(b: ByteArray): Der = parseAll(b).first()
            fun parseAll(b: ByteArray): List<Der> {
                val out = ArrayList<Der>(); var i = 0
                while (i < b.size) {
                    val t0 = b[i++].toInt() and 0xff
                    var tag = t0 and 0x1f
                    if (tag == 0x1f) { tag = 0; while (true) { val x = b[i++].toInt() and 0xff; tag = (tag shl 7) or (x and 0x7f); if (x and 0x80 == 0) break } }
                    var len = b[i++].toInt() and 0xff
                    if (len and 0x80 != 0) { val n = len and 0x7f; len = 0; repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) } }
                    out += Der(tag, t0 and 0x20 != 0, b.copyOfRange(i, i + len)); i += len
                }
                return out
            }
        }
    }

    // ---------------- функции и библиотеки ----------------
    fun features(ctx: Context): List<InfoSection> {
        val pm = ctx.packageManager
        val f = pm.systemAvailableFeatures.filter { it.name != null }.sortedBy { it.name }
        return listOf(
            InfoSection("Функции устройства (${f.size})", f.map { InfoRow(it.name, if (it.version != 0) "версия ${it.version}" else "есть") }),
            InfoSection("Системные библиотеки", pm.systemSharedLibraryNames.orEmpty().sorted().map { InfoRow(it, "есть") }),
        )
    }
}
