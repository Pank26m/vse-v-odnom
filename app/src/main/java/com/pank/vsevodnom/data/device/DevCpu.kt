package com.pank.vsevodnom.data.device

import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build

/** Процессор и графика: всё читается с телефона (/proc/cpuinfo, /sys/devices/system/cpu, EGL/OpenGL, системные функции). */
object DevCpu {
    data class Core(val id: Int, val online: Boolean, val minKhz: Long?, val maxKhz: Long?, val curKhz: Long?, val governor: String?,
                    val part: String?, val partName: String?, val variant: String?, val revision: String?, val capacity: Long?, val policy: Int?)

    data class Soc(val name: String, val vendor: String, val process: String?, val gpu: String?, val npu: String?)

    /** Код ядра (CPU implementer + part из /proc/cpuinfo) → название. */
    fun coreName(impl: String?, part: String?): String? {
        val i = impl?.removePrefix("0x")?.lowercase(); val p = part?.removePrefix("0x")?.lowercase() ?: return null
        return when (i) {
            "41" -> mapOf("d03" to "Cortex-A53", "d04" to "Cortex-A35", "d05" to "Cortex-A55", "d07" to "Cortex-A57", "d08" to "Cortex-A72", "d09" to "Cortex-A73",
                "d0a" to "Cortex-A75", "d0b" to "Cortex-A76", "d0d" to "Cortex-A77", "d41" to "Cortex-A78", "d44" to "Cortex-X1", "d46" to "Cortex-A510",
                "d47" to "Cortex-A710", "d48" to "Cortex-X2", "d4d" to "Cortex-A715", "d4e" to "Cortex-X3", "d80" to "Cortex-A520", "d81" to "Cortex-A720",
                "d82" to "Cortex-X4", "d85" to "Cortex-X925", "d87" to "Cortex-A725", "d8a" to "C1-Nano", "d8b" to "C1-Pro", "d8c" to "C1-Ultra", "d8e" to "C1-Premium",
                "d0c" to "Neoverse N1", "d40" to "Neoverse V1", "d49" to "Neoverse N2")[p]
            "51" -> mapOf("800" to "Kryo 2xx Gold", "801" to "Kryo 2xx Silver", "802" to "Kryo 3xx Gold", "803" to "Kryo 3xx Silver", "804" to "Kryo 4xx Gold",
                "805" to "Kryo 4xx Silver", "001" to "Oryon", "002" to "Oryon")[p]
            "53" -> "Samsung Mongoose ($p)"
            "61" -> "Apple ($p)"
            else -> null
        }
    }

    fun archOf(core: String?): String? = when {
        core == null -> null
        core.contains(Regex("A510|A710|A715|X2|X3")) -> "ARMv9.0-A"
        core.contains(Regex("A520|A720|A725|X4|X925|C1-")) -> "ARMv9.2-A"
        core.contains(Regex("A55|A75|A76|A77|A78|X1|Kryo|Neoverse N1")) -> "ARMv8.2-A"
        core.contains("Oryon") -> "ARMv8.7-A"
        core.contains(Regex("A53|A57|A72|A73|A35")) -> "ARMv8-A"
        else -> null
    }

    /** Маркетинговое имя чипа по коду платформы (Build.SOC_MODEL / ro.board.platform / Hardware). */
    fun soc(): Soc? {
        val keys = listOfNotNull(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null, Dev.prop("ro.soc.model"), Dev.prop("ro.board.platform"),
            Dev.prop("ro.hardware.chipname"), Build.HARDWARE, Build.BOARD).map { it.lowercase().replace(" ", "") }
        for (k in keys) SOCS.entries.firstOrNull { (code, _) -> k == code || k.startsWith(code) }?.let { return it.value }
        return null
    }

    private val SOCS: Map<String, Soc> = linkedMapOf(
        "gs101" to Soc("Google Tensor", "Google", "5 нм Samsung", "Mali-G78 MP20", "Google TPU"),
        "gs201" to Soc("Google Tensor G2", "Google", "5 нм Samsung", "Mali-G710 MP7", "Google TPU"),
        "zumapro" to Soc("Google Tensor G4", "Google", "4 нм Samsung", "Mali-G715 MP7", "Google TPU"),
        "zuma" to Soc("Google Tensor G3", "Google", "4 нм Samsung", "Mali-G715 MP7", "Google TPU"),
        "laguna" to Soc("Google Tensor G5", "Google", "3 нм TSMC", "PowerVR DXT-48-1536", "Google TPU"),
        "sm8850" to Soc("Snapdragon 8 Elite Gen 5", "Qualcomm", "3 нм TSMC", "Adreno 840", "Hexagon NPU"),
        "sm8750" to Soc("Snapdragon 8 Elite", "Qualcomm", "3 нм TSMC", "Adreno 830", "Hexagon NPU"),
        "sm8650" to Soc("Snapdragon 8 Gen 3", "Qualcomm", "4 нм TSMC", "Adreno 750", "Hexagon NPU"),
        "sm8635" to Soc("Snapdragon 8s Gen 3", "Qualcomm", "4 нм TSMC", "Adreno 735", "Hexagon NPU"),
        "sm8550" to Soc("Snapdragon 8 Gen 2", "Qualcomm", "4 нм TSMC", "Adreno 740", "Hexagon"),
        "sm8475" to Soc("Snapdragon 8+ Gen 1", "Qualcomm", "4 нм TSMC", "Adreno 730", "Hexagon"),
        "sm8450" to Soc("Snapdragon 8 Gen 1", "Qualcomm", "4 нм Samsung", "Adreno 730", "Hexagon"),
        "sm8350" to Soc("Snapdragon 888", "Qualcomm", "5 нм Samsung", "Adreno 660", "Hexagon 780"),
        "sm8250" to Soc("Snapdragon 865", "Qualcomm", "7 нм TSMC", "Adreno 650", "Hexagon 698"),
        "sm8150" to Soc("Snapdragon 855", "Qualcomm", "7 нм TSMC", "Adreno 640", "Hexagon 690"),
        "sm7675" to Soc("Snapdragon 7+ Gen 3", "Qualcomm", "4 нм TSMC", "Adreno 732", "Hexagon"),
        "sm7550" to Soc("Snapdragon 7 Gen 3", "Qualcomm", "4 нм TSMC", "Adreno 720", "Hexagon"),
        "sm7475" to Soc("Snapdragon 7+ Gen 2", "Qualcomm", "4 нм TSMC", "Adreno 725", "Hexagon"),
        "sm7450" to Soc("Snapdragon 7 Gen 1", "Qualcomm", "4 нм Samsung", "Adreno 644", "Hexagon"),
        "sm7325" to Soc("Snapdragon 778G", "Qualcomm", "6 нм TSMC", "Adreno 642L", "Hexagon 770"),
        "sm7225" to Soc("Snapdragon 750G", "Qualcomm", "8 нм Samsung", "Adreno 619", "Hexagon 694"),
        "sm6450" to Soc("Snapdragon 6 Gen 1", "Qualcomm", "4 нм Samsung", "Adreno 710", "Hexagon"),
        "sm6375" to Soc("Snapdragon 695", "Qualcomm", "6 нм TSMC", "Adreno 619", "Hexagon 686"),
        "sm6225" to Soc("Snapdragon 680", "Qualcomm", "6 нм TSMC", "Adreno 610", "Hexagon 686"),
        "sm4350" to Soc("Snapdragon 480", "Qualcomm", "8 нм Samsung", "Adreno 619", "Hexagon 686"),
        "mt6991" to Soc("Dimensity 9400", "MediaTek", "3 нм TSMC", "Immortalis-G925", "MediaTek APU 890"),
        "mt6989" to Soc("Dimensity 9300", "MediaTek", "4 нм TSMC", "Immortalis-G720", "MediaTek APU 790"),
        "mt6985" to Soc("Dimensity 9200", "MediaTek", "4 нм TSMC", "Immortalis-G715", "MediaTek APU 690"),
        "mt6983" to Soc("Dimensity 9000", "MediaTek", "4 нм TSMC", "Mali-G710 MC10", "MediaTek APU 590"),
        "mt6897" to Soc("Dimensity 8300", "MediaTek", "4 нм TSMC", "Mali-G615 MC6", "MediaTek APU 780"),
        "mt6896" to Soc("Dimensity 8200", "MediaTek", "4 нм TSMC", "Mali-G610 MC6", "MediaTek APU 580"),
        "mt6895" to Soc("Dimensity 8100", "MediaTek", "5 нм TSMC", "Mali-G610 MC6", "MediaTek APU 580"),
        "mt6886" to Soc("Dimensity 7200", "MediaTek", "4 нм TSMC", "Mali-G610 MC4", "MediaTek APU 650"),
        "mt6877" to Soc("Dimensity 900/1080", "MediaTek", "6 нм TSMC", "Mali-G68 MC4", "MediaTek APU"),
        "mt6855" to Soc("Dimensity 7020/930", "MediaTek", "6 нм TSMC", "IMG BXM-8-256", "MediaTek APU"),
        "mt6853" to Soc("Dimensity 720", "MediaTek", "7 нм TSMC", "Mali-G57 MC3", "MediaTek APU"),
        "mt6833" to Soc("Dimensity 700", "MediaTek", "7 нм TSMC", "Mali-G57 MC2", null),
        "mt6789" to Soc("Helio G99", "MediaTek", "6 нм TSMC", "Mali-G57 MC2", null),
        "mt6785" to Soc("Helio G90T/G95", "MediaTek", "12 нм TSMC", "Mali-G76 MC4", null),
        "mt6769" to Soc("Helio G85/G88", "MediaTek", "12 нм TSMC", "Mali-G52 MC2", null),
        "mt6765" to Soc("Helio P35/G37", "MediaTek", "12 нм TSMC", "PowerVR GE8320", null),
        "s5e9955" to Soc("Exynos 2500", "Samsung", "3 нм Samsung", "Xclipse 950", "Samsung NPU"),
        "s5e9945" to Soc("Exynos 2400", "Samsung", "4 нм Samsung", "Xclipse 940", "Samsung NPU"),
        "s5e9925" to Soc("Exynos 2200", "Samsung", "4 нм Samsung", "Xclipse 920", "Samsung NPU"),
        "s5e8835" to Soc("Exynos 1380", "Samsung", "5 нм Samsung", "Mali-G68 MP5", "Samsung NPU"),
        "s5e8825" to Soc("Exynos 1280", "Samsung", "5 нм Samsung", "Mali-G68 MP4", "Samsung NPU"),
        "exynos990" to Soc("Exynos 990", "Samsung", "7 нм Samsung", "Mali-G77 MP11", "Samsung NPU"),
    )

    /** Ядра: частоты, регулятор, тип ядра, кластер (общая политика частот). */
    fun cores(): List<Core> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(possible())
        val info = cpuinfoBlocks()
        return (0 until n).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i"
            val blk = info.firstOrNull { it["processor"] == "$i" } ?: info.getOrNull(i)
            val impl = blk?.get("CPU implementer"); val part = blk?.get("CPU part")
            Core(i, Dev.read("$base/online")?.let { it == "1" } ?: true,
                Dev.readLong("$base/cpufreq/cpuinfo_min_freq"), Dev.readLong("$base/cpufreq/cpuinfo_max_freq"),
                Dev.readLong("$base/cpufreq/scaling_cur_freq"), Dev.read("$base/cpufreq/scaling_governor"),
                part, coreName(impl, part), blk?.get("CPU variant"), blk?.get("CPU revision"), Dev.readLong("$base/cpu_capacity"),
                Dev.read("$base/cpufreq/related_cpus")?.split(" ")?.firstOrNull()?.toIntOrNull())
        }
    }
    private fun possible(): Int = Dev.read("/sys/devices/system/cpu/possible")?.substringAfter('-')?.toIntOrNull()?.plus(1) ?: 0

    /** Текущие частоты ядер (кГц) — для живых графиков; null — ядро выключено или файл закрыт. */
    fun curFreqs(n: Int): List<Long?> = (0 until n).map { Dev.readLong("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }

    /** /proc/cpuinfo блоками «ключ: значение». */
    fun cpuinfoBlocks(): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>(); var cur = LinkedHashMap<String, String>()
        Dev.lines("/proc/cpuinfo").forEach { l ->
            if (l.isBlank()) { if (cur.isNotEmpty()) out += cur; cur = LinkedHashMap() }
            else { val k = l.substringBefore(':').trim(); val v = l.substringAfter(':', "").trim(); cur[k] = v }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
    fun cpuinfoRaw(): String = Dev.read("/proc/cpuinfo") ?: "—"

    /** Кластеры: ядра с одинаковой политикой частот. */
    data class Cluster(val cores: List<Core>) {
        val name get() = cores.first().partName ?: "ядро"
        val max get() = cores.mapNotNull { it.maxKhz }.maxOrNull()
        val min get() = cores.mapNotNull { it.minKhz }.minOrNull()
    }
    fun clusters(cs: List<Core>): List<Cluster> =
        cs.groupBy { it.policy ?: (it.maxKhz?.toInt() ?: it.id) }.values.map { Cluster(it) }.sortedByDescending { it.max ?: 0 }

    /** Время на каждой частоте с загрузки (time_in_state, единицы 10 мс): кластер → [(кГц, мс)]. */
    fun timeInState(): List<Pair<String, List<Pair<Long, Long>>>> {
        val seen = HashSet<String>()
        return cores().mapNotNull { c ->
            val path = "/sys/devices/system/cpu/cpu${c.id}/cpufreq/stats/time_in_state"
            val key = c.policy?.toString() ?: c.id.toString()
            if (!seen.add(key)) return@mapNotNull null
            val l = Dev.lines(path).mapNotNull { s -> s.trim().split(Regex("\\s+")).takeIf { it.size == 2 }?.let { (f, t) -> f.toLongOrNull()?.let { it to (t.toLongOrNull() ?: 0) * 10 } } }
            if (l.isEmpty()) null else "${c.partName ?: "Кластер"} (ядра ${cores().filter { (it.policy ?: it.id) == (c.policy ?: c.id) }.joinToString(",") { "${it.id}" }})" to l
        }
    }

    // ---------- ГП ----------
    data class Gl(val renderer: String?, val vendor: String?, val version: String?, val glsl: String?, val extensions: List<String>, val maxTexture: Int?)

    @Volatile private var gl: Gl? = null
    /** Создаём невидимый контекст OpenGL ES и спрашиваем драйвер. Только с фонового потока. */
    fun gl(): Gl = gl ?: runCatching {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(d, ver, 0, ver, 1)
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /*ES3*/, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, num, 0)
        val ctx = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(d, surf, surf, ctx)
        val tex = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, tex, 0)
        val r = Gl(GLES20.glGetString(GLES20.GL_RENDERER), GLES20.glGetString(GLES20.GL_VENDOR), GLES20.glGetString(GLES20.GL_VERSION),
            GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION), GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.filter { it.isNotBlank() }?.sorted().orEmpty(), tex[0].takeIf { it > 0 })
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(d, surf); EGL14.eglDestroyContext(d, ctx); EGL14.eglTerminate(d)
        r
    }.getOrDefault(Gl(null, null, null, null, emptyList(), null)).also { gl = it }

    /** Частота и загрузка ГП — только если ядро разрешает читать (на многих Android 14+ закрыто). */
    private val GPU_FREQ = listOf("/sys/class/misc/mali0/device/cur_freq", "/sys/kernel/gpu/gpu_clock", "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/devfreq/gpufreq/cur_freq", "/sys/devices/platform/1f000000.mali/cur_freq", "/sys/devices/platform/28000000.mali/cur_freq")
    private val GPU_LOAD = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy", "/sys/class/misc/mali0/device/utilization",
        "/sys/devices/platform/1f000000.mali/utilization")
    fun gpuFreqMhz(): Long? = GPU_FREQ.firstNotNullOfOrNull { Dev.readLong(it) }?.let { if (it > 10_000_000) it / 1_000_000 else if (it > 10_000) it / 1000 else it }
    fun gpuLoad(): Int? = GPU_LOAD.firstNotNullOfOrNull { Dev.read(it)?.filter { c -> c.isDigit() }?.toIntOrNull() }?.coerceIn(0, 100)

    fun vulkan(pm: android.content.pm.PackageManager): String? = pm.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.version" }?.version?.let {
        "${it ushr 22}.${(it ushr 12) and 0x3ff}.${it and 0xfff}"
    }
    fun glesVersion(pm: android.content.pm.PackageManager): String? = pm.systemAvailableFeatures.firstOrNull { it.name == null }?.reqGlEsVersion?.let { "${it ushr 16}.${it and 0xffff}" }
}
