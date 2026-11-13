package com.pank.vsevodnom.data.device

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/** Приложения: список, подробности, анализ APK, разрешения, трафик и экранное время — всё из системы телефона. */
object AppScan {
    data class App(val pkg: String, val label: String, val version: String?, val code: Long, val system: Boolean, val updatedSystem: Boolean, val enabled: Boolean,
                   val target: Int, val min: Int, val installer: String?, val installed: Long, val updated: Long, val apkBytes: Long, val splits: Int,
                   val uid: Int, val category: Int, val location: Int, val source: String, val splitDirs: List<String>)

    fun list(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS).mapNotNull { pi -> runCatching { app(pm, pi) }.getOrNull() }.sortedBy { it.label.lowercase() }
    }

    private fun app(pm: PackageManager, pi: PackageInfo): App {
        val ai = pi.applicationInfo!!
        val split = ai.splitSourceDirs?.toList().orEmpty()
        return App(pi.packageName, ai.loadLabel(pm).toString(), pi.versionName, pi.longVersionCode, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0, ai.enabled, ai.targetSdkVersion, ai.minSdkVersion, installer(pm, pi.packageName),
            pi.firstInstallTime, pi.lastUpdateTime, (listOf(ai.sourceDir) + split).sumOf { runCatching { File(it).length() }.getOrDefault(0L) }, split.size,
            ai.uid, ai.category, pi.installLocation, ai.sourceDir, split)
    }

    fun installer(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).let { it.installingPackageName ?: it.initiatingPackageName } else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    fun installerName(pkg: String?): String = when (pkg) {
        null -> "предустановлено"; "com.android.vending" -> "Google Play"; "com.google.android.packageinstaller", "com.android.packageinstaller" -> "установщик пакетов (APK)"
        "com.android.shell" -> "ADB (компьютер)"; "com.huawei.appmarket" -> "AppGallery"; "com.sec.android.app.samsungapps" -> "Galaxy Store"; "ru.vk.store" -> "RuStore"
        "com.amazon.venezia" -> "Amazon"; "org.fdroid.fdroid" -> "F-Droid"; "com.aurora.store" -> "Aurora Store"; "app.revanced.manager.flutter" -> "ReVanced"
        "com.google.android.apps.nbu.files" -> "Files by Google"; else -> pkg
    }

    fun categoryName(c: Int) = when (c) { ApplicationInfo.CATEGORY_GAME -> "игра"; ApplicationInfo.CATEGORY_AUDIO -> "аудио"; ApplicationInfo.CATEGORY_VIDEO -> "видео"
        ApplicationInfo.CATEGORY_IMAGE -> "фото"; ApplicationInfo.CATEGORY_SOCIAL -> "общение"; ApplicationInfo.CATEGORY_NEWS -> "новости"; ApplicationInfo.CATEGORY_MAPS -> "карты"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "работа"; ApplicationInfo.CATEGORY_ACCESSIBILITY -> "спец. возможности"; else -> null }

    private val icons = LruCache<String, Bitmap>(300)
    fun icon(ctx: Context, pkg: String): Bitmap? = icons.get(pkg) ?: runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()?.also { icons.put(pkg, it) }

    // ---------------- подробности ----------------
    data class Details(val pi: PackageInfo, val perms: List<Pair<String, Boolean>>, val sig: List<X509Certificate>, val sizes: Triple<Long, Long, Long>?, val libs: List<String>)

    fun details(ctx: Context, pkg: String): Details? = runCatching {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_DISABLED_COMPONENTS
        val pi = pm.getPackageInfo(pkg, flags)
        val perms = pi.requestedPermissions.orEmpty().mapIndexed { i, p -> p to ((pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) }
        val sig = runCatching { (pi.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }).orEmpty()
            .map { CertificateFactory.getInstance("X.509").generateCertificate(it.toByteArray().inputStream()) as X509Certificate } }.getOrDefault(emptyList())
        val sizes = runCatching {
            val s = ctx.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(pi.applicationInfo!!.storageUuid, pkg, Process.myUserHandle())
            Triple(s.appBytes, s.dataBytes, s.cacheBytes)
        }.getOrNull()
        val libs = File(pi.applicationInfo!!.nativeLibraryDir ?: "").listFiles()?.map { it.name }.orEmpty().ifEmpty { apkLibs(pi.applicationInfo!!.sourceDir) }
        Details(pi, perms, sig, sizes, libs)
    }.getOrNull()

    private fun apkLibs(src: String): List<String> = runCatching { ZipFile(src).use { z -> z.entries().toList().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.substringAfterLast('/') }.distinct() } }.getOrDefault(emptyList())

    fun permLabel(ctx: Context, p: String): Pair<String, Int> = runCatching {
        val pi = ctx.packageManager.getPermissionInfo(p, 0)
        val label = pi.loadLabel(ctx.packageManager).toString().takeIf { it != p } ?: p.substringAfterLast('.')
        label to (pi.protection)
    }.getOrDefault(p.substringAfterLast('.') to -1)
    fun isDangerous(prot: Int) = prot == PermissionInfo.PROTECTION_DANGEROUS

    // ---------------- анализатор ----------------
    data class Analysis(val app: App, val platform: String, val libs: List<String>, val sigAlg: String?)

    /** Платформа по файлам внутри APK, библиотеки — по именам компонентов и нативных библиотек. */
    fun analyze(ctx: Context, a: App): Analysis {
        val entries = runCatching { ZipFile(a.source).use { z -> z.entries().toList().map { it.name } } }.getOrDefault(emptyList()) +
            a.splitDirs.flatMap { s -> runCatching { ZipFile(s).use { z -> z.entries().toList().map { it.name } } }.getOrDefault(emptyList()) }
        fun has(s: String) = entries.any { it.contains(s) }
        val platform = when {
            has("libunity.so") -> "Unity"; has("libflutter.so") -> "Flutter"; has("libreactnativejni.so") || has("index.android.bundle") || has("libhermes.so") -> "React Native"
            has("libmonodroid.so") || has("assemblies/") || has("libxamarin") -> "Xamarin / .NET"; has("libgodot_android.so") -> "Godot"; has("libUE4.so") || has("libUnreal.so") -> "Unreal Engine"
            has("libcocos2d") || has("libcocos") -> "Cocos"; has("libgdx.so") -> "libGDX"; has("assets/www/") || has("capacitor") -> "Cordova / Capacitor"
            has("libapp.so") && has("flutter_assets") -> "Flutter"; has("kotlin/") || has("META-INF/androidx.compose") -> "Kotlin (Android)"; else -> "Java (Android)"
        }
        val pi = runCatching { ctx.packageManager.getPackageInfo(a.pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        val comps = (pi?.activities.orEmpty().map { it.name } + pi?.services.orEmpty().map { it.name } + pi?.receivers.orEmpty().map { it.name } + pi?.providers.orEmpty().map { it.name } +
            pi?.applicationInfo?.metaData?.keySet().orEmpty())
        fun c(s: String) = comps.any { it.contains(s) } || has(s.replace('.', '/'))
        val libs = LIBS.filter { (_, keys) -> keys.any { c(it) } }.map { it.first }
        val alg = runCatching { pi?.signingInfo?.apkContentsSigners?.firstOrNull()?.let { (CertificateFactory.getInstance("X.509").generateCertificate(it.toByteArray().inputStream()) as X509Certificate).sigAlgName } }.getOrNull()
        return Analysis(a, platform, libs, alg)
    }
    private val LIBS = listOf("Firebase" to listOf("com.google.firebase"), "Google Analytics" to listOf("com.google.android.gms.analytics", "measurement"),
        "AdMob (реклама Google)" to listOf("com.google.android.gms.ads"), "Facebook SDK" to listOf("com.facebook.FacebookActivity", "com.facebook.internal", "com.facebook.sdk"),
        "Meta Audience Network" to listOf("com.facebook.ads"), "Play Billing (покупки)" to listOf("com.android.billingclient", "com.android.vending.billing"),
        "ML Kit" to listOf("com.google.mlkit"), "ARCore" to listOf("com.google.ar.core"), "Android Auto" to listOf("com.google.android.gms.car", "androidx.car.app"),
        "WorkManager" to listOf("androidx.work"), "Jetpack Compose" to listOf("androidx.compose"), "AppsFlyer" to listOf("com.appsflyer"), "Adjust" to listOf("com.adjust.sdk"),
        "Yandex Mobile Ads" to listOf("com.yandex.mobile.ads"), "Yandex AppMetrica" to listOf("io.appmetrica", "com.yandex.metrica"), "Unity Ads" to listOf("com.unity3d.ads", "com.unity3d.services"),
        "AppLovin" to listOf("com.applovin"), "IronSource" to listOf("com.ironsource"), "Mintegral" to listOf("com.mbridge"), "Pangle (TikTok Ads)" to listOf("com.bytedance.sdk"),
        "Sentry" to listOf("io.sentry"), "Crashlytics" to listOf("com.google.firebase.crashlytics", "crashlytics"), "OneSignal" to listOf("com.onesignal"),
        "Google Maps" to listOf("com.google.android.geo", "com.google.android.gms.maps"), "Huawei HMS" to listOf("com.huawei.hms"), "Stripe" to listOf("com.stripe"), "Branch" to listOf("io.branch"))

    // ---------------- доступ к статистике ----------------
    fun hasUsage(ctx: Context): Boolean = runCatching {
        val ao = ctx.getSystemService(AppOpsManager::class.java)
        ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Трафик по приложениям за период: uid → (Wi-Fi байт, мобильные байт). */
    fun traffic(ctx: Context, from: Long, to: Long = System.currentTimeMillis()): Map<Int, Pair<Long, Long>> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val out = HashMap<Int, LongArray>()
        @Suppress("DEPRECATION")
        listOf(ConnectivityManager.TYPE_WIFI to 0, ConnectivityManager.TYPE_MOBILE to 1).forEach { (type, idx) ->
            runCatching {
                val st = nsm.querySummary(type, null, from, to); val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) { st.getNextBucket(b); out.getOrPut(b.uid) { LongArray(2) }[idx] += b.rxBytes + b.txBytes }
                st.close()
            }
        }
        return out.mapValues { it.value[0] to it.value[1] }
    }

    data class Usage(val pkg: String, val fgMs: Long, val launches: Int)
    /** Экранное время: по событиям «на экране / ушло с экрана» (точнее агрегатов). */
    fun screenTime(ctx: Context, from: Long, to: Long = System.currentTimeMillis()): Pair<List<Usage>, Int> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val ev = usm.queryEvents(from, to); val e = UsageEvents.Event()
        val started = HashMap<String, Long>(); val total = HashMap<String, Long>(); val launches = HashMap<String, Int>(); var unlocks = 0
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { if (started[e.packageName] == null) { started[e.packageName] = e.timeStamp; launches[e.packageName] = (launches[e.packageName] ?: 0) + 1 } }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> started.remove(e.packageName)?.let { s -> total[e.packageName] = (total[e.packageName] ?: 0) + (e.timeStamp - s) }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
            }
        }
        started.forEach { (p, s) -> total[p] = (total[p] ?: 0) + (to - s) }
        return total.map { Usage(it.key, it.value, launches[it.key] ?: 0) }.filter { it.fgMs > 1000 }.sortedByDescending { it.fgMs } to unlocks
    }
}
