package com.pank.vsevodnom.ui.device

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.device.BatteryLog
import com.pank.vsevodnom.data.device.Dev
import com.pank.vsevodnom.data.device.DevReport
import com.pank.vsevodnom.data.device.DevSystem
import com.pank.vsevodnom.ui.common.AppTextField
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.common.Segmented
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs

private val RU = Locale("ru")
private fun hm(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }
private fun hours(h: Double?) = h?.takeIf { it.isFinite() && it > 0 }?.let { hm((it * 3_600_000).toLong()) } ?: "—"
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun whenText(ms: Long): String { val d = day(ms); val t = LocalDate.now()
    return (if (d == t) "Сегодня" else if (d == t.minusDays(1)) "Вчера" else d.format(DateTimeFormatter.ofPattern("d MMM", RU))) + ", " + clock(ms) }

/** Живое состояние батареи (общая для всех вкладок). */
private class BatLive {
    var level by mutableIntStateOf(0); var plug by mutableIntStateOf(0); var status by mutableIntStateOf(0)
    var mv by mutableIntStateOf(0); var temp by mutableFloatStateOf(0f); var ma by mutableStateOf<Double?>(null)
    var toFullMs by mutableLongStateOf(-1); var counter by mutableLongStateOf(0); var cycles by mutableIntStateOf(-1); var health by mutableIntStateOf(0)
    val cur = Series("Ток", DevColors.series[1])
    val charging get() = plug != 0
}

@Composable
fun BatteryHub(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BatteryLog.sync(ctx) }
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    val live = remember { BatLive() }
    LiveLoop(1000, Unit) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@LiveLoop
        live.level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        live.plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0); live.status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        live.mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0); live.temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        live.health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
        if (Build.VERSION.SDK_INT >= 34) live.cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
        live.ma = DevReport.curMa(bm); live.ma?.let { live.cur.push(it.toFloat()) }
        live.toFullMs = bm.computeChargeTimeRemaining(); live.counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    }
    val ver = BatteryLog.version
    val list = rememberLoad(ver / 5) { BatteryLog.sessions(ctx) } ?: emptyList()
    var tab by remember { mutableIntStateOf(if (live.charging) 0 else 1) }
    var settings by remember { mutableStateOf(false) }
    ScreenScaffold("Батарея", onBack, actions = { RoundIconButton(Icons.Rounded.Tune, "Настройки", { settings = true }) }) {
        Box(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) { 0 -> chargeTab(live, list); 1 -> dischargeTab(live, list); 2 -> capacityTab(live, list); else -> historyTab(list) }
            }
        }
        // свой нижний бар
        Row(Modifier.fillMaxWidth().background(Pal.Card).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("Зарядка", Icons.Rounded.BatteryChargingFull, 0), Triple("Разрядка", Icons.Rounded.BatteryStd, 1), Triple("Ёмкость", Icons.Rounded.Favorite, 2), Triple("История", Icons.Rounded.History, 3))
                .forEach { (t, ic, i) -> BarItem(t, ic, tab == i) { tab = i } }
        }
    }
    if (settings) BatterySettings { settings = false }
}

@Composable
private fun RowScope.BarItem(t: String, ic: ImageVector, sel: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) Pal.Mint else Color.Transparent).padding(14.dp, 4.dp)) { Icon(ic, t, tint = if (sel) Pal.MintInk else Pal.Muted) }
        Text(t, style = MaterialTheme.typography.labelMedium, color = if (sel) Pal.Ink else Pal.Muted)
    }
}

@Composable
private fun Hero(live: BatLive, sub: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Ring(live.level / 100f, 110.dp, if (live.level < 20) DevColors.bad else DevColors.good, 11.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${live.level} %", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                if (live.charging) Icon(Icons.Rounded.Bolt, null, tint = DevColors.good, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(DevReport.statusName(live.status).replaceFirstChar { it.uppercase() } + if (live.charging) " · " + DevReport.pluggedName(live.plug) else "", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            live.ma?.let { KV("Ток", "${if (it > 0) "+" else ""}${it.toInt()} мА", if (it > 0) DevColors.good else DevColors.warn) }
            KV("Мощность", "${Dev.fmt(abs(live.ma ?: 0.0) * live.mv / 1e6, 2)} Вт")
            KV("Температура", "${Dev.fmt(live.temp, 1)} °C", if (live.temp >= 40) DevColors.bad else Pal.Ink)
        }
    }
}

// ---------------- зарядка ----------------
private fun LazyListScope.chargeTab(live: BatLive, list: List<BatteryLog.Session>) {
    item {
        val ctx = LocalContext.current
        val s = BatteryLog.current?.takeIf { it.charge }
        val target = BatteryLog.alarmPct(ctx)
        val rate = s?.let { if (it.durMs > 120_000 && it.pct > 0) it.pct / (it.durMs / 3_600_000.0) else null }
        val toTarget = if (live.charging && rate != null && live.level < target) (target - live.level) / rate else null
        Hero(live, when {
            !live.charging -> "Не заряжается — подключи зарядку"
            toTarget != null -> "До $target % — ${hours(toTarget)} (≈ в ${clock(System.currentTimeMillis() + (toTarget * 3_600_000).toLong())}) при ${Dev.fmt(rate!!, 1)} %/ч"
            else -> "Считаю скорость зарядки…"
        })
    }
    item {
        Card {
            Text("Ток вживую", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            LiveChart(listOf(live.cur), height = 120.dp, yMin = minOf(0f, live.cur.values.minOrNull() ?: 0f), unit = "мА")
            Text("«+» — ток в батарею, «−» — из неё.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
    item {
        val ctx = LocalContext.current
        var target by remember { mutableFloatStateOf(BatteryLog.alarmPct(ctx).toFloat()) }
        var on by remember { mutableStateOf(BatteryLog.alarmOn(ctx)) }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сказать, когда заряд дойдёт до ${target.toInt()} %", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(on, { on = it; BatteryLog.setAlarmOn(ctx, it) })
            }
            Slider(target, { target = it }, valueRange = 50f..100f, steps = 9, onValueChangeFinished = { BatteryLog.setAlarmPct(ctx, target.toInt()) })
            val wearT = (target - live.level).coerceAtLeast(0f) / 100f; val wearF = (100 - live.level).coerceAtLeast(0) / 100f
            KV("Износ при зарядке до ${target.toInt()} %", "${Dev.fmt(wearT, 2)} цикла")
            KV("Износ при зарядке до 100 %", "${Dev.fmt(wearF, 2)} цикла")
            Text("Остановить зарядку приложение не может (так устроен Android) — пришлёт уведомление. В Pixel есть своя «Защита зарядки 80 %» в настройках батареи.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
    item {
        val ctx = LocalContext.current
        val r = BatteryLog.rates(list, BatteryLog.design(ctx))
        Card {
            Text("${live.level} % хватит на", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Row { listOf("Смешанно" to r.mixedPctH, "Экран вкл" to r.onPctH, "Экран выкл" to r.offPctH).forEach { (t, v) ->
                Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.labelSmall, color = Pal.Muted); Text(hours(v?.let { live.level / it }), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) } } }
            if (r.sessions == 0) Text("Оценка появится после первых разрядок (журнал ведётся с момента установки).", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }
    val s = BatteryLog.current?.takeIf { it.charge }
    if (s != null) item {
        SectionCard(com.pank.vsevodnom.data.device.section("Эта зарядка (с ${clock(s.start)})") {
            add("Заряжено", "${s.pct} % · ${s.mah.toInt()} мА·ч за ${hm(s.durMs)}")
            add("Скорость", if (s.durMs > 60_000) "${Dev.fmt(s.pct / (s.durMs / 3_600_000.0), 1)} %/ч · средний ток ${(s.mah / (s.durMs / 3_600_000.0)).toInt()} мА" else "…")
            add("С включённым экраном", "${hm(s.onMs)} · ${s.onMah.toInt()} мА·ч"); add("С выключенным", "${hm(s.offMs)} · ${s.offMah.toInt()} мА·ч")
            add("Макс. ток", "${s.maxMa.toInt()} мА"); add("Температура", s.avgTemp?.let { "в среднем ${Dev.fmt(it, 1)} °C, макс. ${Dev.fmt(s.maxTemp, 1)} °C" })
            add("Износ", "${Dev.fmt(s.cycles, 2)} цикла")
            add("Ёмкость по этой зарядке", s.capacity?.let { "${it.toInt()} мА·ч" } ?: "нужно зарядить хотя бы на 15 %")
            add("Ёмкость по счётчику Android", s.counterCap?.let { "${it.toInt()} мА·ч" })
            add("Осталось до 100 % (Android)", if (live.toFullMs > 0) hm(live.toFullMs) else null)
        }, Tints.Mint)
    }
}

// ---------------- разрядка ----------------
private fun LazyListScope.dischargeTab(live: BatLive, list: List<BatteryLog.Session>) {
    item {
        Hero(live, if (live.charging) "Сейчас заряжается — ниже последняя разрядка" else "Разряжается с ${BatteryLog.current?.let { clock(it.start) } ?: "…"}")
    }
    val s = (if (live.charging) list.lastOrNull { !it.charge } else BatteryLog.current?.takeIf { !it.charge }) ?: list.lastOrNull { !it.charge }
    if (s != null) item {
        val ctx = LocalContext.current
        val cap = BatteryLog.design(ctx)
        fun pct(m: Double) = m / cap * 100
        SectionCard(com.pank.vsevodnom.data.device.section("Сессия ${whenText(s.start)} – ${clock(s.end)}") {
            add("Израсходовано", "${s.pct} % · ${s.mah.toInt()} мА·ч за ${hm(s.durMs)}")
            add("Экран включён", "${hm(s.onMs)} · ${Dev.fmt(pct(s.onMah), 1)} % · ${s.onMah.toInt()} мА·ч")
            add("Экран выключен", "${hm(s.offMs)} · ${Dev.fmt(pct(s.offMah), 1)} % · ${s.offMah.toInt()} мА·ч")
            add("  из них не спал", "${hm(s.offMs - s.deepMs)} · ${Dev.fmt(pct(s.offMah - s.deepMah), 1)} %")
            add("  в глубоком сне", "${hm(s.deepMs)} (${if (s.offMs > 0) s.deepMs * 100 / s.offMs else 0} % времени) · ${Dev.fmt(pct(s.deepMah), 1)} %")
            fun rate(mah: Double, ms: Long) = if (ms > 300_000) "${Dev.fmt(pct(mah) / (ms / 3_600_000.0), 1)} %/ч · ${(mah / (ms / 3_600_000.0)).toInt()} мА" else "мало данных"
            add("Скорость: смешанно", rate(s.mah, s.durMs)); add("Скорость: экран вкл", rate(s.onMah, s.onMs)); add("Скорость: экран выкл", rate(s.offMah, s.offMs))
            add("Скорость: в глубоком сне", rate(s.deepMah, s.deepMs))
            add("Температура", s.avgTemp?.let { "в среднем ${Dev.fmt(it, 1)} °C, макс. ${Dev.fmt(s.maxTemp, 1)} °C" })
        }, Tints.Peach)
    }
    item {
        val ctx = LocalContext.current
        val r = BatteryLog.rates(list, BatteryLog.design(ctx))
        SectionCard(com.pank.vsevodnom.data.device.section("Средний расход за 7 дней") {
            add("Сессий разрядки", r.sessions)
            add("Смешанно", r.mixedPctH?.let { "${Dev.fmt(it, 1)} %/ч · ${r.mixedMa?.toInt()} мА · ${r.pctTotal.toInt()} % за ${Dev.fmt(r.hours, 1)} ч" })
            add("Экран включён", r.onPctH?.let { "${Dev.fmt(it, 1)} %/ч · ${r.onMa?.toInt()} мА · ${Dev.fmt(r.onH, 1)} ч" })
            add("Экран выключен", r.offPctH?.let { "${Dev.fmt(it, 1)} %/ч · ${r.offMa?.toInt()} мА · ${Dev.fmt(r.offH, 1)} ч" })
            add("Не спал (экран выкл)", r.awakePctH?.let { "${Dev.fmt(it, 1)} %/ч" }); add("Глубокий сон", r.deepPctH?.let { "${Dev.fmt(it, 2)} %/ч" })
            add("От 100 % хватит: экран вкл", hours(r.onPctH?.let { 100 / it })); add("От 100 % хватит: экран выкл", hours(r.offPctH?.let { 100 / it })); add("От 100 % хватит: смешанно", hours(r.mixedPctH?.let { 100 / it }))
            if (r.sessions == 0) note = "Журнал ведётся с момента установки 0.44 — цифры появятся после первых разрядок."
            else note = "Расход по приложениям — в «Приложения → Экранное время»: Android не отдаёт сторонним программам точную статистику энергии по приложениям."
        }, Tints.Peach)
    }
}

// ---------------- ёмкость ----------------
private fun LazyListScope.capacityTab(live: BatLive, list: List<BatteryLog.Session>) {
    item {
        val ctx = LocalContext.current
        val design = BatteryLog.design(ctx)
        val pts = BatteryLog.capacityPoints(list)
        val last5 = pts.takeLast(5).map { it.second }.takeIf { it.isNotEmpty() }?.average()
        val fullCharge = list.lastOrNull { it.charge && it.endPct >= 99 }
        var edit by remember { mutableStateOf(false) }
        Card {
            Text("Ёмкость аккумулятора", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            val pct = last5?.let { it / design }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring((pct ?: 0.0).toFloat(), 92.dp, if ((pct ?: 1.0) > 0.8) DevColors.good else DevColors.warn) { Text(pct?.let { "${(it * 100).toInt()} %" } ?: "—", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                Column(Modifier.padding(start = 14.dp)) {
                    KV("Вычисленная", last5?.let { "${it.toInt()} мА·ч" } ?: "нужны зарядки")
                    KV("Номинальная", "${design.toInt()} мА·ч")
                    if (live.level > 10 && live.counter > 0) KV("По счётчику Android", "${live.counter / 10 / live.level} мА·ч")
                    if (live.cycles >= 0) KV("Циклов (система)", "${live.cycles}")
                    KV("Здоровье (система)", DevReport.healthName(live.health))
                }
            }
            Text(if (pts.isEmpty()) "Ёмкость считается по зарядкам на 15 % и больше: сколько мА·ч вошло на каждый процент. Чем шире зарядка, тем точнее."
                else "По ${pts.takeLast(5).size} последним зарядкам из ${pts.size}.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            Text("Последняя полная зарядка: " + (fullCharge?.let { whenText(it.end) } ?: "ещё не было"), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
            TextButton({ edit = true }) { Text("Изменить номинальную ёмкость") }
        }
        if (edit) {
            var t by remember { mutableStateOf(design.toInt().toString()) }
            AlertDialog(onDismissRequest = { edit = false }, title = { Text("Номинальная ёмкость, мА·ч") }, text = { AppTextField(t, { t = it.filter(Char::isDigit) }, "например 5050") },
                confirmButton = { TextButton({ t.toDoubleOrNull()?.takeIf { it > 500 }?.let { BatteryLog.setDesign(ctx, it) }; edit = false }) { Text("Сохранить") } })
        }
    }
    item {
        val ctx = LocalContext.current
        val design = BatteryLog.design(ctx)
        val pts = BatteryLog.capacityPoints(list).map { it.first to it.second / design * 100 }
        CapacityChart(pts)
    }
    item {
        val now = System.currentTimeMillis(); val first = list.firstOrNull()?.start ?: now
        val days = ((now - first) / 86_400_000L).coerceAtLeast(1)
        SectionCard(com.pank.vsevodnom.data.device.section("Износ (циклы)") {
            listOf(7L to "7 дней", 30L to "30 дней", 365L to "год").forEach { (d, t) -> val c = BatteryLog.cycles(list, now - d * 86_400_000L); add("За $t", "${Dev.fmt(c, 2)} цикла · ${Dev.fmt(c / minOf(d, days), 2)} в день") }
            val all = BatteryLog.cycles(list, 0); add("С ${whenText(first)}", "${Dev.fmt(all, 2)} цикла за $days дн. · ${Dev.fmt(all / days, 2)} в день")
            note = "Цикл = 100 % заряда суммарно (две зарядки по 50 % — один цикл)."
        }, Tints.Mint)
    }
    item { WearChart(list) }
}

/** График ёмкости: точки по зарядкам, среднее по 5, тренд (линейная регрессия). Нажатие на точку — подробности. */
@Composable
private fun CapacityChart(pts: List<Pair<Long, Double>>) {
    var sel by remember(pts) { mutableStateOf<Int?>(null) }
    val reg = remember(pts) { BatteryLog.regression(pts.map { it.first / 86_400_000.0 to it.second }) }
    Card {
        Text("График ёмкости", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        if (pts.size < 2) { Text("Точки появятся после зарядок на 15 % и больше.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted); return@Card }
        val x0 = pts.first().first.toDouble(); val x1 = pts.last().first.toDouble().coerceAtLeast(x0 + 1)
        val yMin = (pts.minOf { it.second } - 5).coerceAtMost(80.0); val yMax = (pts.maxOf { it.second } + 5).coerceAtLeast(100.0)
        Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(pts) {
            detectTapGestures { o -> val w = size.width - 60f
                sel = pts.indices.minByOrNull { i -> abs(((pts[i].first - x0) / (x1 - x0) * w).toFloat() - o.x) } }
        }) {
            val w = size.width - 60f; val h = size.height
            fun X(t: Double) = ((t - x0) / (x1 - x0) * w).toFloat()
            fun Y(v: Double) = (h * (1 - (v - yMin) / (yMax - yMin))).toFloat()
            val p = android.graphics.Paint().apply { color = Pal.Muted.toArgb(); textSize = 24f; isAntiAlias = true }
            listOf(yMax, (yMin + yMax) / 2, yMin).forEach { v -> drawLine(Pal.Line, Offset(0f, Y(v)), Offset(w, Y(v)), 2f); drawContext.canvas.nativeCanvas.drawText("${v.toInt()} %", w + 6, Y(v) + 8, p) }
            pts.forEach { (t, v) -> val c = Offset(X(t.toDouble()), Y(v)); drawLine(DevColors.series[1], c - Offset(6f, 6f), c + Offset(6f, 6f), 3f); drawLine(DevColors.series[1], c - Offset(-6f, 6f), c + Offset(-6f, 6f), 3f) }
            // скользящее среднее по 5
            val avg = pts.indices.map { i -> pts[i].first to pts.subList(maxOf(0, i - 4), i + 1).map { it.second }.average() }
            val path = Path(); avg.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(X(t.toDouble()), Y(v)) else path.lineTo(X(t.toDouble()), Y(v)) }
            drawPath(path, DevColors.series[1].copy(alpha = 0.8f), style = Stroke(4f))
            reg?.let { (a, b, _) -> val d0 = x0 / 86_400_000.0; val d1 = x1 / 86_400_000.0
                drawLine(DevColors.series[0], Offset(0f, Y(a + b * d0)), Offset(w, Y(a + b * d1)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))) }
            sel?.let { i -> drawLine(Pal.Muted, Offset(X(pts[i].first.toDouble()), 0f), Offset(X(pts[i].first.toDouble()), h), 2f) }
        }
        Legend(listOf("зарядка" to DevColors.series[1], "среднее по 5" to DevColors.series[1].copy(alpha = 0.8f), "тренд" to DevColors.series[0]))
        sel?.let { i -> KV("Выбрано", "${whenText(pts[i].first)} · ${Dev.fmt(pts[i].second, 1)} %") }
        reg?.let { (_, b, r2) -> KV("Тренд: потеря в год", "${Dev.fmt(b * 365, 1)} %"); KV("R² (насколько точен тренд)", Dev.fmt(r2, 2)) }
    }
}

/** Накопленный износ (циклы) по дням. */
@Composable
private fun WearChart(list: List<BatteryLog.Session>) {
    val pts = remember(list) { var acc = 0.0; list.filter { it.charge }.map { acc += it.cycles; it.end to acc } }
    Card {
        Text("График износа", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        if (pts.size < 2) { Text("Появится после нескольких зарядок.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted); return@Card }
        val s = remember(pts) { Series("Циклы", DevColors.series[5], 400).apply { pts.takeLast(400).forEach { push(it.second.toFloat()) } } }
        LiveChart(listOf(s), height = 130.dp, unit = "цикл.")
        KV("Всего с начала журнала", "${Dev.fmt(pts.last().second, 2)} цикла")
    }
}

// ---------------- история ----------------
private fun LazyListScope.historyTab(list: List<BatteryLog.Session>) {
    item {
        var mode by remember { mutableIntStateOf(1) }
        var sel by remember(mode) { mutableStateOf<Int?>(null) }
        val groups = remember(list, mode) {
            val key: (Long) -> Pair<String, LocalDate> = { ms -> val d = day(ms)
                when (mode) { 0 -> d.format(DateTimeFormatter.ofPattern("d.MM")) to d
                    1 -> "${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" to d.with(java.time.DayOfWeek.MONDAY)
                    else -> d.format(DateTimeFormatter.ofPattern("LLL", RU)) to d.withDayOfMonth(1) } }
            list.groupBy { key(it.end) }.entries.sortedBy { it.key.second }.takeLast(14).map { (k, v) -> k to v }
        }
        Card {
            Segmented(listOf("По дням", "По неделям", "По месяцам"), mode, { mode = it })
            if (groups.isEmpty()) { Text("История появится после первых сессий.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp)); return@Card }
            val maxC = groups.maxOf { (_, v) -> v.filter { it.charge }.sumOf { it.pct } }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEachIndexed { i, (k, v) ->
                    val c = v.filter { it.charge }.sumOf { it.pct }
                    Column(Modifier.weight(1f).clickable { sel = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height((120 * c / maxC).coerceAtLeast(2).dp).clip(RoundedCornerShape(5.dp)).background(if (sel == i) Pal.SkyInk else DevColors.series[0]))
                        Text(k.first, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
                    }
                }
            }
            val g = groups.getOrNull(sel ?: groups.lastIndex)
            g?.let { (k, v) ->
                val first = v.minOf { it.start }; val last = v.maxOf { it.end }
                Text("${day(first).format(DateTimeFormatter.ofPattern("d.MM.yyyy"))} – ${day(last).format(DateTimeFormatter.ofPattern("d.MM.yyyy"))}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                KV("Заряжено", "${v.filter { it.charge }.sumOf { it.pct }} % · ${v.filter { it.charge }.sumOf { it.mah }.toInt()} мА·ч")
                KV("Израсходовано", "${v.filter { !it.charge }.sumOf { it.pct }} %")
                KV("Износ", "${Dev.fmt(v.sumOf { it.cycles }, 2)} цикла")
                KV("Экран включён", hm(v.filter { !it.charge }.sumOf { it.onMs }))
                KV("Сессий", "${v.size}")
            }
        }
    }
    item { GroupTitle("Последние сессии") }
    val recent = list.takeLast(100).reversed()
    if (recent.isEmpty()) item { Card { Text("Пока пусто. Журнал пишется сам в фоне — уведомление «Журнал батареи» можно свернуть.", style = MaterialTheme.typography.bodySmall) } }
    items(recent, key = { "${it.start}" }) { s -> SessionCard(s) }
}

@Composable
private fun SessionCard(s: BatteryLog.Session) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(14.dp)) {
        Row {
            Text((if (s.charge) "Заряжался " else "Разряжался ") + hm(s.durMs), style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.weight(1f))
            Text(whenText(s.start), style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.startPct} % → ${s.endPct} %", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text((if (s.charge) "+" else "−") + "${s.pct} %", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (s.charge) DevColors.good else DevColors.bad)
                Text((if (s.charge) "+" else "−") + "${s.mah.toInt()} мА·ч", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
            }
        }
        Text(if (s.charge) "износ ${Dev.fmt(s.cycles, 2)} цикла" + (s.capacity?.let { " · ёмкость ~${it.toInt()} мА·ч" } ?: "") + if (s.maxTemp > 0) " · до ${Dev.fmt(s.maxTemp, 1)} °C" else ""
            else "экран был включён ${hm(s.onMs)} (${if (s.durMs > 0) s.onMs * 100 / s.durMs else 0} %) · сон ${hm(s.deepMs)}", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Line)) {
            val a = minOf(s.startPct, s.endPct) / 100f; val b = maxOf(s.startPct, s.endPct) / 100f
            Row(Modifier.fillMaxSize()) {
                if (a > 0) Spacer(Modifier.weight(a))
                Box(Modifier.weight((b - a).coerceAtLeast(0.01f)).fillMaxHeight().background(if (s.charge) DevColors.good else DevColors.bad))
                if (1 - b > 0) Spacer(Modifier.weight(1 - b))
            }
        }
    }
}

@Composable
private fun BatterySettings(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(BatteryLog.enabled(ctx)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Журнал батареи") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Вести журнал в фоне", modifier = Modifier.weight(1f)); Switch(on, { on = it; BatteryLog.setEnabled(ctx, it) }) }
            Text("Нужен для истории, ёмкости, расхода и уведомления о заряде. Служба почти не тратит батарею: при выключенном экране просыпается раз в минуту, а в глубоком сне спит вместе с телефоном. Android не даёт фоновой службе работать совсем без уведомления, но его можно выключить: кнопка ниже → выключи «Журнал батареи». Служба продолжит работать, в шторке ничего не будет. Проценты и ток в уведомлении больше не обновляются.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            com.pank.vsevodnom.ui.common.PillButton("Скрыть уведомление", {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.pank.vsevodnom.data.device.BatteryLogService.CH)) }
            }, Modifier.fillMaxWidth())
            Text("Журнал ведётся с ${whenText(BatteryLog.firstSeen(ctx))}. Историю других приложений (AccuBattery) перенести нельзя — их данные закрыты.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Готово") } })
}
