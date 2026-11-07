package com.pank.vsevodnom.ui.alerts

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.AlertGeo
import com.pank.vsevodnom.data.AlertItem
import com.pank.vsevodnom.data.AlertLevel
import com.pank.vsevodnom.data.AlertState
import com.pank.vsevodnom.data.AlertsApi
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Геоданные грузятся один раз на всё приложение. */
object GeoCache {
    var map by mutableStateOf<AlertGeo.GeoMap?>(null)
    var hromady by mutableStateOf<List<AlertGeo.Area>?>(null)
    suspend fun load(ctx: Context) = withContext(Dispatchers.Default) {
        if (map == null) map = AlertGeo.parseMap(ctx.assets.open("geo/raions.txt").bufferedReader().readText())
        if (hromady == null) hromady = AlertGeo.parseHromady(ctx.assets.open("geo/hromady.txt").bufferedReader().readText())
    }
}

fun threatName(t: String) = when (t) {
    "drones" -> "Дроны (БпЛА)"
    "ballistic_missiles" -> "Баллистика"
    "cruise_missiles" -> "Крылатые ракеты"
    "unspecified_missiles" -> "Ракеты"
    "guided_aerial_bombs" -> "Управляемые авиабомбы"
    "tactic_aircraft_activity" -> "Тактическая авиация"
    "strategic_aircraft_activity" -> "Стратегическая авиация"
    "mig31k_departure" -> "Взлёт МиГ-31К"
    "air_defense" -> "Работа ПВО"
    else -> "Угроза"
}

fun alertTypeName(t: String) = when (t) {
    "air_raid" -> "Воздушная тревога"
    "artillery_shelling" -> "Угроза артобстрела"
    "urban_fights" -> "Уличные бои"
    "chemical" -> "Химическая угроза"
    "nuclear" -> "Радиационная угроза"
    else -> "Тревога"
}

private enum class Mark { DRONE, MISSILE, AIR, OTHER }
private fun markOf(t: String) = when {
    t == "drones" -> Mark.DRONE
    "missile" in t || t == "mig31k_departure" -> Mark.MISSILE
    "aircraft" in t || t == "guided_aerial_bombs" -> Mark.AIR
    else -> Mark.OTHER
}

private fun Path.addRings(rings: List<FloatArray>) {
    fillType = PathFillType.EvenOdd
    rings.forEach { r -> moveTo(r[0], r[1]); var i = 2; while (i < r.size) { lineTo(r[i], r[i + 1]); i += 2 }; close() }
}

private fun fillOf(v: Int?) = when (v) { 2 -> FillRed; 1 -> FillYellow; else -> FillCalm }

/** Какие тревоги касаются выбранного места (ключ — область или «область|район»). */
fun itemsFor(s: AlertState?, key: String): List<AlertItem> {
    s ?: return emptyList()
    val obl = key.substringBefore('|'); val raion = key.substringAfter('|', "").ifBlank { null }
    val hr = GeoCache.hromady
    return s.items.filter { a ->
        a.oblast == obl && (raion == null || a.kind == "oblast" || (a.kind == "raion" && a.title == raion) || a.raion == raion ||
            (a.kind == "city" && hr != null && AlertGeo.findHromada(a, hr)?.raion == raion))
    }
}

@Composable
fun AlertMap(state: AlertState?, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { GeoCache.load(ctx) } }
    val geo = GeoCache.map
    val hromady = GeoCache.hromady
    val paint = remember(state, geo, hromady) { geo?.let { AlertGeo.paint(state, it, hromady) } }
    val paths = remember(geo) { geo?.let { g -> (g.raions + g.oblasts).associate { it.key to Path().apply { addRings(it.rings) } } } }
    val hPaths = remember(paint, hromady) {
        val keys = paint?.hromada?.keys.orEmpty()
        hromady.orEmpty().filter { "${it.oblast}|${it.name}" in keys }.associate { "${it.oblast}|${it.name}" to (it to Path().apply { addRings(it.rings) }) }
    }
    // значки угроз: в центре области / района / громады
    val marks = remember(state, geo, hromady) {
        val out = ArrayList<Pair<Pair<Float, Float>, Mark>>()
        if (geo != null && state != null) {
            val byKey = (geo.raions + geo.oblasts).associateBy { it.key }
            state.items.forEach { a ->
                val m = a.threats.map { markOf(it.type) }.minByOrNull { it.ordinal } ?: return@forEach
                val area = when (a.kind) {
                    "raion" -> byKey["${a.oblast}|${a.title}"]
                    "oblast" -> byKey[a.oblast]
                    else -> hromady?.let { AlertGeo.findHromada(a, it) } ?: if (a.oblast == AlertsApi.KYIV_CITY) byKey[a.oblast] else null
                } ?: return@forEach
                out += area.center to m
            }
        }
        out.distinctBy { it.first }
    }
    val drone = remember { Path().apply { moveTo(0.5f, 0.12f); lineTo(0.96f, 0.72f); lineTo(0.56f, 0.6f); lineTo(0.5f, 0.88f); lineTo(0.44f, 0.6f); lineTo(0.04f, 0.72f); close() } }
    val icons = mapOf(Mark.MISSILE to rememberVectorPainter(Icons.Rounded.RocketLaunch), Mark.AIR to rememberVectorPainter(Icons.Rounded.Flight), Mark.OTHER to rememberVectorPainter(Icons.Rounded.Warning))

    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val dens = LocalDensity.current.density
    val textPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; color = Pal.Ink.toArgb() } }

    fun clamp(o: Offset, z: Float, w: Float, h: Float) = Offset(o.x.coerceIn(w - w * z, 0f), o.y.coerceIn(h - h * z, 0f))

    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        // одним пальцем на неувеличенной карте листаем экран, а не карту
                        if (fingers >= 2 || zoom > 1.02f) {
                            val z = (zoom * ev.calculateZoom()).coerceIn(1f, 8f)
                            val c = ev.calculateCentroid(useCurrent = true)
                            val k = z / zoom
                            off = clamp((off - c) * k + c + ev.calculatePan(), z, size.width.toFloat(), size.height.toFloat())
                            zoom = z
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(geo) {
                detectTapGestures(
                    onDoubleTap = { zoom = 1f; off = Offset.Zero },
                    onTap = { t ->
                        val g = geo ?: return@detectTapGestures
                        val sc = size.width / AlertGeo.W * zoom
                        val x = (t.x - off.x) / sc; val y = (t.y - off.y) / sc
                        val city = g.oblasts.firstOrNull { (it.name == AlertsApi.KYIV_CITY || it.name == AlertsApi.SEVASTOPOL) && it.contains(x, y) }
                        val hit = city?.key ?: g.raions.firstOrNull { it.contains(x, y) }?.key
                        onSelect(if (hit == selected) null else hit)
                    },
                )
            }
    ) {
        val g = geo ?: return@Canvas
        val p = paint ?: return@Canvas
        val pp = paths ?: return@Canvas
        val sc = size.width / AlertGeo.W * zoom
        withTransform({ translate(off.x, off.y); scale(sc, sc, Offset.Zero) }) {
            g.raions.forEach { r ->
                val v = maxOf(p.oblast[r.oblast] ?: 0, p.raion[r.key] ?: 0)
                val fill = if (v == 0 && r.key in p.raionPartial) FillPartial else fillOf(v)
                drawPath(pp.getValue(r.key), fill)
            }
            g.oblasts.filter { it.name == AlertsApi.KYIV_CITY || it.name == AlertsApi.SEVASTOPOL }.forEach { drawPath(pp.getValue(it.key), fillOf(p.oblast[it.name])) }
            hPaths.forEach { (k, v) -> val h = v.first; drawPath(v.second, fillOf(maxOf(p.hromada[k] ?: 0, p.raion["${h.oblast}|${h.raion}"] ?: 0, p.oblast[h.oblast] ?: 0))) }
            val thin = 0.8f * dens / sc
            g.raions.forEach { drawPath(pp.getValue(it.key), Color.White, style = Stroke(thin)) }
            g.oblasts.forEach { drawPath(pp.getValue(it.key), Color(0xFF8E94A3), style = Stroke(1.3f * dens / sc)) }
            selected?.let { k -> pp[k]?.let { drawPath(it, Pal.Ink, style = Stroke(2.2f * dens / sc)) } }
        }
        fun screen(c: Pair<Float, Float>) = Offset(c.first * sc + off.x, c.second * sc + off.y)
        // подписи областей (на увеличенной карте) и районов (на сильно увеличенной)
        if (zoom >= 1.6f) {
            textPaint.textSize = 9.5f * dens
            g.oblasts.filter { it.name != AlertsApi.KYIV_CITY && it.name != AlertsApi.SEVASTOPOL }.forEach { o ->
                val c = screen(o.center)
                drawContext.canvas.nativeCanvas.drawText(o.name.removeSuffix(" область").replace("Автономна Республіка ", ""), c.x, c.y, textPaint)
            }
        }
        if (zoom >= 3.5f) {
            textPaint.textSize = 8f * dens
            g.raions.forEach { r ->
                val c = screen(r.center)
                if (c.x in 0f..size.width && c.y in 0f..size.height)
                    drawContext.canvas.nativeCanvas.drawText(r.name.removeSuffix(" район"), c.x, c.y + 12f * dens, textPaint)
            }
        }
        val ms = (10f + 2f * (zoom - 1).coerceAtMost(4f)) * dens
        marks.forEach { (c0, m) ->
            val c = screen(c0)
            drawCircle(Color.White.copy(alpha = 0.92f), ms * 0.75f, c)
            translate(c.x - ms / 2, c.y - ms / 2) {
                if (m == Mark.DRONE) withTransform({ scale(ms, ms, Offset.Zero) }) { drawPath(drone, Color(0xFF3A3F4D)) }
                else with(icons.getValue(m)) { draw(Size(ms, ms), colorFilter = ColorFilter.tint(Color(0xFFB23A4E))) }
            }
        }
    }
}

@Composable
fun SelectedCard(s: AlertState?, key: String, onClose: () -> Unit) {
    val obl = key.substringBefore('|'); val raion = key.substringAfter('|', "").ifBlank { null }
    val list = itemsFor(s, key)
    AppCard(Modifier.fillMaxWidth(), color = if (list.any { it.red }) FillRed.copy(alpha = 0.35f) else if (list.isNotEmpty()) FillYellow.copy(alpha = 0.35f) else FillCalm) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(raion ?: obl, style = MaterialTheme.typography.titleMedium)
                if (raion != null) Text(obl, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            RoundIconButton(Icons.Rounded.Close, "Закрыть", onClose)
        }
        Spacer(Modifier.height(6.dp))
        if (s == null) Text("Нет данных", style = MaterialTheme.typography.bodyMedium)
        else if (list.isEmpty()) Text(if (s.districtLevel) "Спокойно" else if (s.of(obl) == AlertLevel.FULL) "Тревога по области (источник без районов)" else "Спокойно", style = MaterialTheme.typography.bodyLarge)
        list.forEach { ItemRow(it) }
    }
}

@Composable
private fun ItemRow(a: AlertItem) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotSmall(if (a.red) FillRed else FillYellow)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (a.kind == "oblast") "Вся область" else a.title) + " · " + (if (a.red) alertTypeName(a.alertType) else "угроза (жёлтый уровень)"),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
            )
            Text(sinceText(a.started).substringBefore(" ·"), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
        a.threats.forEach { t ->
            Row(Modifier.padding(start = 18.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ThreatIcon(t.type)
                Spacer(Modifier.width(6.dp))
                Text(threatName(t.type) + if (t.message.isNotBlank() && t.message != threatName(t.type)) " — ${t.message}" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (a.notes.isNotBlank()) Text(a.notes, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
fun ThreatIcon(type: String) {
    val v: ImageVector? = when (markOf(type)) { Mark.MISSILE -> Icons.Rounded.RocketLaunch; Mark.AIR -> Icons.Rounded.Flight; Mark.OTHER -> Icons.Rounded.Warning; Mark.DRONE -> null }
    if (v != null) Icon(v, null, Modifier.size(16.dp), tint = Color(0xFFB23A4E))
    else Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * 0.5f, h * 0.12f); lineTo(w * 0.96f, h * 0.72f); lineTo(w * 0.56f, h * 0.6f); lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.44f, h * 0.6f); lineTo(w * 0.04f, h * 0.72f); close() }, Color(0xFF3A3F4D))
    }
}

@Composable
private fun DotSmall(c: Color) = Canvas(Modifier.size(10.dp)) { drawCircle(c) }

@OptIn(ExperimentalLayoutApi::class)
/** Карточка области в списке: вся область или перечень районов/громад с угрозами. */
@Composable
fun OblastCard(s: AlertState, n: String, onClick: () -> Unit) {
    val items = s.itemsOf(n)
    AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotSmall(if (s.of(n) == AlertLevel.FULL) FillRed else FillYellow)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n, style = MaterialTheme.typography.bodyLarge)
                val whole = items.firstOrNull { it.kind == "oblast" }
                val parts = items.filter { it.kind != "oblast" }
                when {
                    whole != null -> Text("Вся область" + if (!whole.red) " · угроза" else "", style = MaterialTheme.typography.bodySmall)
                    parts.isNotEmpty() -> Text(parts.map { it.title }.distinct().let { l -> l.take(4).joinToString(", ") + if (l.size > 4) " и ещё ${l.size - 4}" else "" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(sinceText(s.since[n]).substringBefore(" ·"), style = MaterialTheme.typography.bodySmall)
        }
        val threats = items.flatMap { it.threats }.map { it.type }.distinct()
        if (threats.isNotEmpty()) FlowRow(Modifier.padding(start = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            threats.forEach { t -> Row(verticalAlignment = Alignment.CenterVertically) { ThreatIcon(t); Spacer(Modifier.width(4.dp)); Text(threatName(t), style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

