package com.pank.vsevodnom.data.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.log10
import kotlin.math.pow

/** Сетевые инструменты: производитель по MAC, внешний IP, пинг, диагностика, тест скорости, устройства в локальной сети. */
object NetTools {
    // ---------------- производитель по MAC (база IEEE, офлайн) ----------------
    @Volatile private var oui: Map<String, String>? = null
    fun vendor(ctx: Context, mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val m = mac.uppercase().replace(":", "").replace("-", "")
        if (m.length < 6) return null
        // второй бит первого байта — «локально назначенный» (случайный MAC): производителя нет
        if ((m.substring(0, 2).toInt(16) and 0x02) != 0) return "случайный MAC"
        val map = oui ?: runCatching {
            GZIPInputStream(ctx.assets.open("net/oui.txt.gz")).bufferedReader().useLines { s -> s.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap() }
        }.getOrDefault(emptyMap()).also { oui = it }
        return map[m.substring(0, 6)]
    }

    /** Примерное расстояние до точки по силе сигнала (модель свободного пространства). */
    fun distanceM(rssi: Int, freqMhz: Int): Double = 10.0.pow((27.55 - 20 * log10(freqMhz.toDouble()) + kotlin.math.abs(rssi)) / 20)

    // ---------------- HTTP ----------------
    private fun get(url: String, timeout: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.setRequestProperty("User-Agent", "VseVOdnom/1.0")
        return c.inputStream.use { it.bufferedReader().readText() }.also { c.disconnect() }
    }

    fun publicIp(): List<InfoRow> = runCatching {
        val j = JSONObject(get("https://ipwho.is/"))
        if (!j.optBoolean("success", true)) error(j.optString("message"))
        val con = j.optJSONObject("connection")
        listOfNotNull(InfoRow("IP", j.optString("ip")), InfoRow("Тип", j.optString("type")),
            InfoRow("Провайдер", con?.optString("isp").orEmpty()), InfoRow("Организация", con?.optString("org").orEmpty()), InfoRow("ASN", con?.optInt("asn")?.toString().orEmpty()),
            InfoRow("Место", listOf(j.optString("city"), j.optString("region"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ")),
            InfoRow("Часовой пояс", j.optJSONObject("timezone")?.optString("id").orEmpty())).filter { it.value.isNotBlank() && it.value != "0" }
    }.getOrElse { listOf(InfoRow("Ошибка", it.message ?: "нет связи")) }

    // ---------------- пинг ----------------
    data class Ping(val host: String, val ok: Int, val sent: Int, val min: Double?, val avg: Double?, val max: Double?, val how: String)

    /** ICMP-пинг системной утилитой; если она недоступна — время TCP-соединения. */
    fun ping(host: String, count: Int = 4): Ping {
        Dev.exec("ping", "-c", "$count", "-W", "1", "-i", "0.3", host)?.let { out ->
            val times = Regex("time=([0-9.]+)").findAll(out).map { it.groupValues[1].toDouble() }.toList()
            if (times.isNotEmpty() || out.contains("packets transmitted")) return Ping(host, times.size, count, times.minOrNull(), times.takeIf { it.isNotEmpty() }?.average(), times.maxOrNull(), "ICMP")
        }
        val t = (0 until count).mapNotNull { tcpTime(host, 443) ?: tcpTime(host, 80) ?: tcpTime(host, 53) }
        return Ping(host, t.size, count, t.minOrNull(), t.takeIf { it.isNotEmpty() }?.average(), t.maxOrNull(), "TCP")
    }
    fun tcpTime(host: String, port: Int, timeout: Int = 1000): Double? = runCatching {
        Socket().use { s -> val t = SystemClock.elapsedRealtimeNanos(); s.connect(InetSocketAddress(host, port), timeout); (SystemClock.elapsedRealtimeNanos() - t) / 1e6 }
    }.getOrNull()

    fun gateway(ctx: Context): String? {
        @Suppress("DEPRECATION") val d = ctx.applicationContext.getSystemService(WifiManager::class.java).dhcpInfo ?: return null
        return d.gateway.takeIf { it != 0 }?.let { DevConnect.ipv4(it) }
    }

    /** Диагностика: роутер, DNS, интернет, HTTPS. Возвращает строки с оценкой и итоговый совет. */
    fun diagnose(ctx: Context, step: (String) -> Unit): Pair<List<InfoRow>, String> {
        val rows = ArrayList<InfoRow>(); val problems = ArrayList<String>()
        val gw = gateway(ctx)
        step("Роутер…")
        if (gw != null) { val p = ping(gw, 5); rows += InfoRow("Роутер $gw", p.avg?.let { "${Dev.fmt(it, 1)} мс · потери ${(p.sent - p.ok) * 100 / p.sent} % (${p.how})" } ?: "не отвечает", p.ok > 0)
            if (p.ok == 0) problems += "роутер не отвечает — проблема в Wi-Fi или роутере"; else if ((p.avg ?: 0.0) > 20) problems += "задержка до роутера высокая — помехи или далеко от роутера" }
        else rows += InfoRow("Роутер", "нет Wi-Fi", false)
        step("DNS…")
        val dnsT = SystemClock.elapsedRealtime(); val dnsOk = runCatching { InetAddress.getAllByName("google.com").isNotEmpty() }.getOrDefault(false)
        rows += InfoRow("DNS (google.com)", if (dnsOk) "${SystemClock.elapsedRealtime() - dnsT} мс" else "не работает", dnsOk); if (!dnsOk) problems += "DNS не отвечает — смени DNS (например 1.1.1.1) или перезагрузи роутер"
        step("Интернет…")
        listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google").forEach { (h, n) -> val p = ping(h, 4)
            rows += InfoRow("Интернет ($n)", p.avg?.let { "${Dev.fmt(it, 0)} мс · потери ${(p.sent - p.ok) * 100 / p.sent} %" } ?: "нет ответа", p.ok > 0)
            if (p.ok == 0) problems += "$n не отвечает" else if ((p.avg ?: 0.0) > 150) problems += "задержка до $n большая (${p.avg?.toInt()} мс)" }
        step("HTTPS…")
        val h = runCatching { val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection; c.connectTimeout = 5000; c.readTimeout = 5000
            val t = SystemClock.elapsedRealtime(); val code = c.responseCode; c.disconnect(); code to SystemClock.elapsedRealtime() - t }.getOrNull()
        rows += InfoRow("Проверка HTTPS", h?.let { "код ${it.first} · ${it.second} мс" } ?: "не открылось", h?.first == 204)
        if (h == null) problems += "сайты не открываются"; else if (h.first != 204) problems += "сеть перехватывает запросы (вход в гостевой Wi-Fi?)"
        DevConnect.shortNet(ctx).let { rows += InfoRow("Подключение", it) }
        @Suppress("DEPRECATION") val wi = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        if (wi != null && wi.networkId != -1 && wi.rssi < -75) problems += "слабый сигнал Wi-Fi (${wi.rssi} дБм) — подойди ближе к роутеру или перейди на 2,4 ГГц"
        return rows to (if (problems.isEmpty()) "Всё в порядке: роутер, DNS и интернет отвечают быстро." else problems.joinToString(";\n").replaceFirstChar { it.uppercase() } + ".")
    }

    // ---------------- тест скорости ----------------
    data class Speed(val pingMs: Double?, val jitterMs: Double?, val downMbps: Double?, val upMbps: Double?)

    /** Тест скорости через speed.cloudflare.com: задержка, загрузка (до 10 с), отдача (до 8 с). */
    suspend fun speedTest(onStage: (String, Double?) -> Unit): Speed = withContext(Dispatchers.IO) {
        onStage("Задержка…", null)
        val pings = (0 until 6).mapNotNull { runCatching { val t = SystemClock.elapsedRealtimeNanos(); get("https://speed.cloudflare.com/__down?bytes=0", 4000); (SystemClock.elapsedRealtimeNanos() - t) / 1e6 }.getOrNull() }
        val ping = pings.drop(1).minOrNull() ?: pings.minOrNull()
        val jitter = if (pings.size > 2) pings.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average() else null
        onStage("Загрузка…", null)
        val down = runCatching {
            val c = URL("https://speed.cloudflare.com/__down?bytes=200000000").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            val start = SystemClock.elapsedRealtime(); var total = 0L; val buf = ByteArray(64 * 1024); var shown = 0L
            c.inputStream.use { inp: InputStream ->
                while (SystemClock.elapsedRealtime() - start < 10_000) { val n = inp.read(buf); if (n < 0) break; total += n
                    val now = SystemClock.elapsedRealtime(); val sec = (now - start) / 1000.0
                    if (sec > 0.3 && now - shown >= 200) { shown = now; onStage("Загрузка…", total * 8 / 1e6 / sec) } }
            }
            c.disconnect(); total * 8 / 1e6 / ((SystemClock.elapsedRealtime() - start) / 1000.0)
        }.getOrNull()
        onStage("Отдача…", null)
        val up = runCatching {
            val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 10000; c.doOutput = true; c.requestMethod = "POST"; c.setChunkedStreamingMode(64 * 1024)
            val start = SystemClock.elapsedRealtime(); var total = 0L; val buf = ByteArray(64 * 1024); var shown = 0L
            c.outputStream.use { o -> while (SystemClock.elapsedRealtime() - start < 8_000 && total < 100_000_000) { o.write(buf); total += buf.size
                val now = SystemClock.elapsedRealtime(); val sec = (now - start) / 1000.0
                if (sec > 0.3 && now - shown >= 200) { shown = now; onStage("Отдача…", total * 8 / 1e6 / sec) } } }
            c.responseCode; c.disconnect(); total * 8 / 1e6 / ((SystemClock.elapsedRealtime() - start) / 1000.0)
        }.getOrNull()
        Speed(ping, jitter, down, up)
    }

    // ---------------- устройства в локальной сети ----------------
    data class Host(val ip: String, val name: String?, val ports: List<Int>, val services: List<String>, val kind: String, val me: Boolean, val router: Boolean, val ms: Double?)

    private val PORTS = listOf(80, 443, 22, 53, 445, 139, 554, 8080, 8008, 8009, 9100, 62078, 7000, 5000, 1900, 3389, 5555)
    private val SERVICES = listOf("_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_ipp._tcp", "_printer._tcp", "_http._tcp", "_smb._tcp", "_spotify-connect._tcp",
        "_hap._tcp", "_androidtvremote2._tcp", "_workstation._tcp", "_companion-link._tcp", "_adb-tls-connect._tcp", "_matter._tcp", "_esphomelib._tcp", "_miio._udp")

    /** Перебор /24 сети: ICMP/TCP «кто живой» + имена из mDNS и обратного DNS. Чужие MAC Android 10+ не показывает. */
    suspend fun scanLan(ctx: Context, progress: (Int, Int) -> Unit): List<Host> = coroutineScope {
        @Suppress("DEPRECATION") val d = ctx.applicationContext.getSystemService(WifiManager::class.java).dhcpInfo
        val myIp = d?.ipAddress?.takeIf { it != 0 }?.let { DevConnect.ipv4(it) } ?: return@coroutineScope emptyList()
        val gw = d.gateway.takeIf { it != 0 }?.let { DevConnect.ipv4(it) }
        val base = myIp.substringBeforeLast('.')
        val names = ConcurrentHashMap<String, MutableSet<String>>()
        val nsdJob = async(Dispatchers.IO) { mdns(ctx, names) }
        val sem = Semaphore(48); var done = 0
        val found = (1..254).map { i -> async(Dispatchers.IO) { sem.withPermit {
            val ip = "$base.$i"
            val t0 = SystemClock.elapsedRealtimeNanos()
            val reach = runCatching { InetAddress.getByName(ip).isReachable(350) }.getOrDefault(false)
            val ms = if (reach) (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 else null
            val open = if (reach || ip == gw) PORTS.filter { p -> tcpTime(ip, p, 220) != null } else PORTS.take(6).filter { p -> tcpTime(ip, p, 160) != null }
            synchronized(this@NetTools) { done++; progress(done, 254) }
            if (reach || open.isNotEmpty() || ip == myIp || ip == gw) Triple(ip, open, ms) else null
        } } }.awaitAll().filterNotNull()
        nsdJob.await()
        found.map { (ip, open, ms) ->
            val host = withTimeoutOrNull(800) { withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip } } }
            val svc = names[ip]?.toList().orEmpty()
            Host(ip, host ?: svc.firstOrNull { !it.startsWith("_") }, open, svc, kind(ip == gw, ip == myIp, open, svc), ip == myIp, ip == gw, ms)
        }.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun kind(router: Boolean, me: Boolean, p: List<Int>, s: List<String>): String = when {
        me -> "этот телефон"; router -> "роутер"
        s.any { it.contains("googlecast") || it.contains("androidtv") } -> "ТВ / Chromecast"
        s.any { it.contains("airplay") || it.contains("raop") || it.contains("companion") } || 62078 in p -> "устройство Apple"
        s.any { it.contains("ipp") || it.contains("printer") } || 9100 in p -> "принтер"
        554 in p -> "камера / видеорегистратор"; 5555 in p || s.any { it.contains("adb") } -> "Android-устройство"
        s.any { it.contains("hap") || it.contains("matter") || it.contains("esphome") || it.contains("miio") } -> "умный дом"
        3389 in p || 445 in p || 139 in p || s.any { it.contains("smb") || it.contains("workstation") } -> "компьютер"
        22 in p -> "сервер / ПК"; 80 in p || 443 in p -> "устройство с веб-панелью"; else -> "устройство"
    }

    /** mDNS: 4 секунды слушаем популярные службы, собираем «IP → имена». */
    private fun mdns(ctx: Context, out: ConcurrentHashMap<String, MutableSet<String>>) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        SERVICES.forEach { type ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION") runCatching { nsd.resolveService(si, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) { @Suppress("DEPRECATION") r.host?.hostAddress?.let { ip -> out.getOrPut(ip) { java.util.Collections.synchronizedSet(LinkedHashSet()) }.apply { add(r.serviceName); add(type) } } }
                        override fun onResolveFailed(s: NsdServiceInfo?, e: Int) {}
                    }) }
                }
                override fun onDiscoveryStarted(t: String?) {}; override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceLost(s: NsdServiceInfo?) {}; override fun onStartDiscoveryFailed(t: String?, e: Int) {}; override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
        Thread.sleep(4500)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
    }
}
