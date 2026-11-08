package com.pank.vsevodnom.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/** Статистика трека: путь, время в движении, скорости, высоты, подъём/спуск. Чистые функции — тест TrackMathTest. */
object TrackMath {
    data class Stats(
        val dist: Double, val upDist: Double, val downDist: Double,
        val totalMs: Long, val movingMs: Long,
        val avg: Double, val movingAvg: Double, val max: Double,
        val minAlt: Double, val maxAlt: Double, val ascent: Double, val descent: Double,
        val cum: DoubleArray, val alt: DoubleArray, val speed: DoubleArray,
    )

    /** Высоты сглаживаем (GPS «шумит»), подъём считаем с порогом 3 м — иначе набегают лишние метры. */
    fun stats(p: List<GeoPt>): Stats {
        val n = p.size
        val cum = DoubleArray(n); for (i in 1 until n) cum[i] = cum[i - 1] + Osm.distance(p[i - 1], p[i])
        val rawAlt = DoubleArray(n) { p[it].alt }
        val alt = DoubleArray(n) { i -> val w = (maxOf(0, i - 2)..minOf(n - 1, i + 2)).map { rawAlt[it] }.filter { !it.isNaN() }; if (w.isEmpty()) Double.NaN else w.average() }
        var asc = 0.0; var desc = 0.0; var upD = 0.0; var downD = 0.0
        var ref = alt.firstOrNull { !it.isNaN() } ?: Double.NaN
        for (i in 1 until n) {
            val a = alt[i]; if (a.isNaN() || alt[i - 1].isNaN()) continue
            val seg = cum[i] - cum[i - 1]
            if (a > alt[i - 1]) upD += seg else if (a < alt[i - 1]) downD += seg
            if (ref.isNaN()) { ref = a; continue }
            if (a - ref >= 3) { asc += a - ref; ref = a } else if (ref - a >= 3) { desc += ref - a; ref = a }
        }
        // скорость: из GPS, иначе по соседним точкам
        val speed = DoubleArray(n) { i ->
            if (p[i].spd >= 0) p[i].spd.toDouble()
            else if (i > 0 && p[i].t > p[i - 1].t && p[i - 1].t > 0) (cum[i] - cum[i - 1]) / ((p[i].t - p[i - 1].t) / 1000.0) * 3.6 else Double.NaN
        }
        var moving = 0L
        for (i in 1 until n) {
            val dt = p[i].t - p[i - 1].t
            if (p[i - 1].t <= 0 || dt <= 0 || dt > 10 * 60_000) continue
            val v = (cum[i] - cum[i - 1]) / (dt / 1000.0) * 3.6
            if (v >= 1.5) moving += dt
        }
        val total = if (n > 1 && p.first().t > 0 && p.last().t > p.first().t) p.last().t - p.first().t else 0L
        val dist = cum.lastOrNull() ?: 0.0
        val okSpeeds = speed.filter { !it.isNaN() && it < 250 }
        val sm = okSpeeds.sorted().let { if (it.size > 20) it[(it.size * 0.98).toInt().coerceAtMost(it.size - 1)] else it.maxOrNull() ?: 0.0 }
        val alts = alt.filter { !it.isNaN() }
        return Stats(dist, upD, downD, total, moving,
            if (total > 0) dist / (total / 1000.0) * 3.6 else 0.0, if (moving > 0) dist / (moving / 1000.0) * 3.6 else 0.0, sm,
            alts.minOrNull() ?: Double.NaN, alts.maxOrNull() ?: Double.NaN, asc, desc, cum, alt, speed)
    }

    /** Цвет по доле 0..1: зелёный → жёлтый → красный. */
    fun ramp(f: Double): Int {
        val x = f.coerceIn(0.0, 1.0)
        val (r, g) = if (x < 0.5) (x * 2 * 255).toInt() to 200 else 230 to ((1 - (x - 0.5) * 2) * 200).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or 60
    }

    /** Значение для раскраски каждой точки: высота, скорость или уклон (%). */
    fun values(s: Stats, mode: String): DoubleArray = when (mode) {
        "alt" -> s.alt
        "speed" -> s.speed
        else -> DoubleArray(s.alt.size) { i ->
            val j = (i + 3).coerceAtMost(s.alt.size - 1); val k = (i - 3).coerceAtLeast(0)
            val d = s.cum[j] - s.cum[k]
            if (d > 5 && !s.alt[j].isNaN() && !s.alt[k].isNaN()) abs(s.alt[j] - s.alt[k]) / d * 100 else Double.NaN
        }
    }
}

private fun hms(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }

@Composable
internal fun TrackStatsScreen(t: Track, onDismiss: () -> Unit) {
    FullScreen(t.name, onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { TrackStatsContent(t) }
    }
}

/** Вся статистика трека: числа, графики, остановки маршрута. */
@Composable
internal fun TrackStatsContent(t: Track) {
    val ctx = LocalContext.current
    val pts = remember(t.id, t.points) { Osm.decode(t.points) }
    val s = remember(t.id, t.points) { TrackMath.stats(pts) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Создан " + Instant.ofEpochMilli(t.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            AppCard(Modifier.fillMaxWidth()) {
                Group("Расстояние", listOf(MapPrefs.dist(ctx, s.dist) to "Всего", MapPrefs.dist(ctx, s.upDist) to "Вверх", MapPrefs.dist(ctx, s.downDist) to "Вниз"))
                if (s.totalMs > 0) Group("Длительность", listOf(hms(s.totalMs) to "Общее время", hms(s.movingMs) to "В движении", hms(s.totalMs - s.movingMs) to "На месте"))
                if (s.totalMs > 0) Group("Скорость, ${MapPrefs.speed(ctx, 0f).second}", listOf("%.1f".format(s.avg) to "Средняя", "%.1f".format(s.movingAvg) to "В движении", "%.1f".format(s.max) to "Максимум"))
                if (!s.minAlt.isNaN()) Group(if (Dem.fromDem(t)) "Высота (по рельефу), м" else "Высота (GPS), м", listOf("%.0f".format(s.minAlt) to "Минимум", "%.0f".format(s.maxAlt) to "Максимум", "%.0f / %.0f".format(s.ascent, s.descent) to "Подъём / спуск"))
                if (s.minAlt.isNaN()) Text("Высот нет: для этого места не скачан рельеф (Настройки → Загрузка карт → Рельеф).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                if (s.totalMs == 0L && !s.minAlt.isNaN()) Text("Построенный маршрут: высоты взяты из рельефа местности, время в пути — оценка.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            if (s.alt.any { !it.isNaN() }) Chart("Высота", s.cum, s.alt, "м", Color(0xFF2E9FB8))
            if (s.speed.any { !it.isNaN() }) Chart("Скорость", s.cum, s.speed, MapPrefs.speed(ctx, 0f).second, Color(0xFFE09A1B))
            routeWay(t)?.let { (mode, way) ->
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Маршрут · " + mapOf("car" to "авто", "bike" to "велосипед", "foot" to "пешком", "line" to "по прямой")[mode].orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    way.forEachIndexed { i, w -> Text((if (i == 0) "Старт: " else if (i == way.size - 1) "Финиш: " else "${RoutePlan.label(i - 1)}. ") + w.name, style = MaterialTheme.typography.bodyMedium) }
                }
            }
    }
}

/** Точки построенного маршрута из Track.meta: способ и список (старт, остановки, финиш). */
internal fun routeWay(t: Track): Pair<String, List<MapPrefs.Spot>>? = runCatching {
    if (t.meta.isBlank()) return null
    val o = org.json.JSONObject(t.meta); val a = o.getJSONArray("way")
    o.optString("mode", "bike") to (0 until a.length()).map { a.getJSONObject(it).let { w -> MapPrefs.Spot(w.optString("name"), w.getDouble("lat"), w.getDouble("lon")) } }
}.getOrNull()

@Composable
private fun Group(title: String, items: List<Pair<String, String>>) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        items.forEach { (v, l) -> Column(Modifier.weight(1f)) { Text(v, style = MaterialTheme.typography.titleMedium); Text(l, fontSize = 12.sp, color = Pal.Muted) } }
    }
}

/** Простой график значения по пройденному пути. */
@Composable
private fun Chart(title: String, x: DoubleArray, y: DoubleArray, unit: String, color: Color) {
    val ys = y.filter { !it.isNaN() }
    if (ys.size < 2) return
    val lo = ys.minOrNull()!!; val hi = max(ys.maxOrNull()!!, lo + 1)
    AppCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth()) {
            Canvas(Modifier.weight(1f).height(160.dp).padding(top = 8.dp)) {
                val w = size.width; val h = size.height; val xm = x.last().coerceAtLeast(1.0)
                for (k in 0..3) { val yy = h * k / 3f; drawLine(Color(0x22000000), Offset(0f, yy), Offset(w, yy), 1f) }
                val path = Path(); var started = false
                for (i in x.indices) {
                    val v = y[i]; if (v.isNaN()) continue
                    val px = (x[i] / xm * w).toFloat(); val py = (h - (v - lo) / (hi - lo) * h).toFloat()
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width = 4f))
            }
            Column(Modifier.height(168.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("%.0f %s".format(hi, unit), fontSize = 11.sp, color = Pal.Muted); Text("%.0f %s".format(lo, unit), fontSize = 11.sp, color = Pal.Muted)
            }
        }
        Text("0 … " + "%.1f км".format(x.last() / 1000).replace('.', ','), fontSize = 11.sp, color = Pal.Muted)
    }
}
