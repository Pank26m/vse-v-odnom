package com.pank.vsevodnom.data

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Навигация: маршрут (GLMap офлайн/онлайн, OSRM), ведение по шагам, пересчёт при съезде, голос.
 * Коды команд — как в BRouter (VoiceHint).
 */
object Nav {
    const val C = 1; const val TL = 2; const val TSLL = 3; const val TSHL = 4; const val TR = 5; const val TSLR = 6; const val TSHR = 7
    const val KL = 8; const val KR = 9; const val TLU = 10; const val TRU = 11; const val RNDB = 13; const val RNLB = 14; const val TU = 15
    const val EL = 17; const val ER = 18; const val END = 100

    // ---------------- маршрут ----------------

    fun offlineAvailable(@Suppress("UNUSED_PARAMETER") ctx: Context) = GlRoute.offlineAvailable()

    /**
     * Маршрут: сначала офлайн (движок GLMap/Valhalla по навигационным данным на телефоне), потом онлайн GLMap,
     * в последнюю очередь OSRM. Выбор «онлайн» в планировщике (пешком/вело) меняет порядок. Пустой результат — ошибка.
     */
    suspend fun best(ctx: Context, mode: String, way: List<GeoPt>): Routing.Route {
        val toll = MapPrefsBridge.bool(ctx, "avoid_toll", false); val ferry = MapPrefsBridge.bool(ctx, "avoid_ferry", false)
        val canOnline = Net.online && !MapPrefsBridge.offlineOnly(ctx)
        val wantOnline = mode != "car" && MapPrefsBridge.bool(ctx, "route_online", false) && canOnline
        fun ok(r: Routing.Route) = r.points.size >= 2 && r.meters >= 1
        var err: Throwable? = null
        suspend fun off(): Routing.Route? = runCatching { GlRoute.route(ctx, mode, way, true, toll, ferry) }.onFailure { err = it }.getOrNull()?.takeIf(::ok)
        suspend fun glOn(): Routing.Route? = if (!canOnline) null else runCatching { GlRoute.route(ctx, mode, way, false, toll, ferry) }.onFailure { if (err == null) err = it }.getOrNull()?.takeIf(::ok)
        suspend fun osrm(): Routing.Route? = if (!canOnline) null else runCatching { Routing.route(mode, way, toll, ferry) }.onFailure { if (err == null) err = it }.getOrNull()?.takeIf(::ok)
        val r = if (wantOnline) glOn() ?: osrm() ?: off() else off() ?: glOn() ?: osrm()
        return r ?: throw Exception(err?.message ?: "Маршрут не построен")
    }

    // ---------------- геометрия ----------------

    /** Расстояние в метрах от точки до отрезка и доля по отрезку. */
    private fun segDist(p: GeoPt, a: GeoPt, b: GeoPt): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = a.lon * k; val ay = a.lat * 110_540.0; val bx = b.lon * k; val by = b.lat * 110_540.0; val px = p.lon * k; val py = p.lat * 110_540.0
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        val x = ax + t * dx - px; val y = ay + t * dy - py
        return sqrt(x * x + y * y) to t
    }

    // ---------------- ведение ----------------

    class Session(val route: Routing.Route, val mode: String, val destName: String, val way: List<GeoPt> = emptyList()) {
        val cum = DoubleArray(route.points.size).also { c -> for (i in 1 until c.size) c[i] = c[i - 1] + Osm.distance(route.points[i - 1], route.points[i]) }
        var seg = 0                 // текущий отрезок
        var along = 0.0             // пройдено по маршруту, м
        var offCount = 0
        val spoken = HashSet<String>()
        val total get() = cum.lastOrNull() ?: 0.0
    }

    data class Status(val step: Routing.Step?, val toStep: Double, val left: Double, val leftSec: Double, val off: Boolean, val arrived: Boolean, val next: Routing.Step?)

    var session by mutableStateOf<Session?>(null)
    var status by mutableStateOf<Status?>(null)

    /** Обновить положение. Возвращает true, если пора пересчитать маршрут. */
    fun update(p: GeoPt, accuracy: Float): Boolean {
        val s = session ?: return false
        val pts = s.route.points
        if (pts.size < 2) return false
        // ищем ближайший отрезок вокруг текущего (вперёд дальше, назад чуть-чуть)
        var best = s.seg; var bd = Double.MAX_VALUE; var bt = 0.0
        for (i in maxOf(0, s.seg - 5) until minOf(pts.size - 1, s.seg + 60)) {
            val (d, t) = segDist(p, pts[i], pts[i + 1]); if (d < bd) { bd = d; best = i; bt = t }
        }
        if (bd > 80) for (i in 0 until pts.size - 1) { val (d, t) = segDist(p, pts[i], pts[i + 1]); if (d < bd) { bd = d; best = i; bt = t } }
        val limit = maxOf(45.0, accuracy * 1.5)
        val off = bd > limit
        if (!off) { s.seg = best; s.along = s.cum[best] + (s.cum[best + 1] - s.cum[best]) * bt; s.offCount = 0 } else s.offCount++
        val left = (s.total - s.along).coerceAtLeast(0.0)
        val nextSteps = s.route.steps.filter { s.cum[it.index] > s.along + 3 }
        val step = nextSteps.firstOrNull()
        val toStep = step?.let { s.cum[it.index] - s.along } ?: left
        val leftSec = if (s.total > 0) s.route.seconds * left / s.total else 0.0
        val arrived = left < 25
        status = Status(step, toStep, left, leftSec, off, arrived, nextSteps.getOrNull(1))
        return s.offCount >= 3
    }

    // ---------------- фразы и голос ----------------

    fun phrase(cmd: Int, exit: Int): String = when (cmd) {
        TL -> "поверните налево"; TR -> "поверните направо"
        TSLL -> "плавно налево"; TSLR -> "плавно направо"
        TSHL -> "резко налево"; TSHR -> "резко направо"
        KL, EL -> "держитесь левее"; KR, ER -> "держитесь правее"
        TU, TLU, TRU -> "развернитесь"
        RNDB, RNLB -> if (exit > 0) "на круге — ${ordinal(exit)} съезд" else "въезд на круг"
        END -> "вы на месте"
        else -> "прямо"
    }
    private fun ordinal(n: Int) = listOf("", "первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой").getOrElse(n) { "$n-й" }

    fun distText(m: Double): String = when {
        m >= 1000 -> "%.1f км".format(m / 1000).replace(".0 ", " ").replace('.', ',')
        m >= 100 -> "${(m / 50).toInt() * 50} м"
        else -> "${(m / 10).toInt() * 10} м"
    }
    private fun sayDist(m: Double): String = when {
        m >= 1000 -> { val km = Math.round(m / 100) / 10.0; if (km == km.toInt().toDouble()) "${km.toInt()} километр${if (km.toInt() == 1) "" else if (km.toInt() in 2..4) "а" else "ов"}" else "$km километра".replace('.', ',') }
        else -> "${(m / 50).toInt().coerceAtLeast(1) * 50} метров"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var muted by mutableStateOf(false)

    fun initVoice(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("ru", "RU").takeIf { tts?.isLanguageAvailable(it) ?: -1 >= TextToSpeech.LANG_AVAILABLE } ?: Locale("uk", "UA")
        }
    }
    fun say(text: String) { if (!muted && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav") }
    fun shutdownVoice() { tts?.shutdown(); tts = null; ttsReady = false }

    /** Когда что говорить: заранее (зависит от скорости), «скоро» и «сейчас». */
    fun voice(speedKmh: Float) {
        val s = session ?: return; val st = status ?: return
        if (st.arrived) { if (s.spoken.add("arrive")) say("Вы прибыли. ${s.destName}") ; return }
        val step = st.step ?: return
        val far = if (s.mode == "car") (if (speedKmh > 70) 1500.0 else 600.0) else 200.0
        val near = if (s.mode == "car") (if (speedKmh > 70) 400.0 else 150.0) else 40.0
        val now = if (s.mode == "car") 40.0 else 12.0
        val key = step.index.toString()
        val what = phrase(step.cmd, step.exit)
        when {
            st.toStep <= now && s.spoken.add("$key-now") -> say(what.replaceFirstChar { it.uppercase() } + (st.next?.let { n -> if (s.cum[n.index] - s.cum[step.index] < 150) ", затем ${phrase(n.cmd, n.exit)}" else "" } ?: ""))
            st.toStep in now..near && s.spoken.add("$key-near") -> say("Через ${sayDist(st.toStep)} $what")
            st.toStep in near..far && s.spoken.add("$key-far") -> say("Через ${sayDist(st.toStep)} $what")
        }
    }
}

/** Доступ к настройкам карты из data-слоя (prefs «map»). */
object MapPrefsBridge {
    fun bool(ctx: Context, k: String, def: Boolean) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean(k, def)
    fun offlineOnly(ctx: Context) = bool(ctx, "offline_only", false)
}
