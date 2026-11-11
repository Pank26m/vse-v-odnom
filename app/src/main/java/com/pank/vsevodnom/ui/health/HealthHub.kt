package com.pank.vsevodnom.ui.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.home.StepGoal
import com.pank.vsevodnom.ui.home.scoreColor
import com.pank.vsevodnom.ui.map.FullScreen
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")
private fun f1(v: Double) = "%.1f".format(v).replace('.', ',')
private fun int(v: Double) = "%,d".format(v.toLong()).replace(',', ' ')

/** Показатель здоровья: откуда брать значение за день, норма, как показывать. */
internal class Metric(
    val key: String, val title: String, val icon: ImageVector, val tint: Tint, val unit: String,
    val lo: Double?, val hi: Double?, val about: String, val get: (DayHealth) -> Double?, val fmt: (Double) -> String = ::int,
    val sum: Boolean = false,
)

/** Все данные раздела: дни, тренировки, настроение, оценка. Грузится раз и держится, пока экран открыт. */
private class HealthState {
    var days by mutableStateOf<List<DayHealth>>(emptyList())
    var workouts by mutableStateOf<List<WorkoutRec>>(emptyList())
    var moodByDay by mutableStateOf<Map<LocalDate, Double>>(emptyMap())
    var parts by mutableStateOf<List<HealthScore.Part>>(emptyList())
    var granted by mutableStateOf<Set<String>?>(null)
    var loading by mutableStateOf(true)
}

private fun metrics(ctx: android.content.Context, st: HealthState): List<Metric> {
    val kg = st.days.lastOrNull { it.weightKg != null }?.weightKg
    val goal = StepGoal.value(ctx, kg).toDouble()
    val h = com.pank.vsevodnom.ui.home.Profile.get(ctx, "height").replace(',', '.').toDoubleOrNull() ?: BodyCheck.latest(ctx)?.v("height")
    val plan = Nutrition.plan(ctx)
    return listOf(
        Metric("steps", "Шаги", Icons.AutoMirrored.Rounded.DirectionsWalk, Tints.Mint, "шагов", goal, null,
            "Цель $goal — считается по росту, весу и возрасту (Профиль → Здоровье). Шаги — самый весомый показатель оценки.", { it.steps?.toDouble() }, sum = true),
        Metric("kcal", "Калории", Icons.Rounded.LocalFireDepartment, Tints.Peach, "ккал", null, null,
            "Сколько сожжено за день (покой + движение). Для похудения расход должен быть больше, чем съедено. Базальный обмен по анализу — ${BodyCheck.latest(ctx)?.v("bmr")?.toInt() ?: "—"} ккал.",
            { it.totalKcal ?: it.activeKcal }, sum = true),
        Metric("activity", "Время активности", Icons.Rounded.Timer, Tints.Sky, "мин", 30.0, null,
            "Тренировки за день. Норма ВОЗ — 150 минут в неделю (≈30 в день). Больше 10 часов в неделю — перегруз, оценка снижается.", { it.exerciseMin?.toDouble() }, sum = true),
        Metric("sleep", "Сон", Icons.Rounded.Bedtime, Tints.Lilac, "ч", 7.0, 9.0,
            "Ночь считается по дню пробуждения. Норма для взрослого — 7–9 часов. Браслет считает сон, только если ты в нём спишь.", { it.sleepMin?.let { m -> m / 60.0 } }, ::f1),
        Metric("hr", "Пульс", Icons.Rounded.Favorite, Tints.Rose, "уд/мин", 50.0, 70.0,
            "Пульс в покое (если браслет не отдаёт — средний за день). Норма в покое 50–70; ниже — тренированное сердце, выше 80 — повод снизить стресс и вес.",
            { (it.restHr ?: it.hrAvg)?.toDouble() }),
        Metric("bp", "Давление", Icons.Rounded.MonitorHeart, Tints.Rose, "мм рт. ст.", 90.0, 120.0,
            "Верхнее (систолическое). Норма до 120/80. Mi Band 8 давление не меряет — нужны данные тонометра в Health Connect.", { it.bpSys }),
        Metric("spo2", "Кислород в крови", Icons.Rounded.Air, Tints.Sky, "%", 95.0, 100.0,
            "Сатурация (SpO₂). Норма 95–100%. Ночные замеры с браслета точнее дневных.", { it.spo2 }),
        Metric("weight", "Вес", Icons.Rounded.MonitorWeight, Tints.Sky, "кг",
            h?.let { 18.5 * (it / 100) * (it / 100) }, h?.let { 25.0 * (it / 100) * (it / 100) },
            "Норма — вес при ИМТ 18,5–25 для твоего роста. Взвешивание по понедельникам.", { it.weightKg }, ::f1),
        Metric("food", "Питание", Icons.Rounded.Restaurant, Tints.Butter, "ккал", plan.kcal * 0.9, plan.kcal * 1.1,
            "Если записей питания нет — считаю по плану специалиста: ${plan.kcal} ккал, белки ${plan.protein} г, жиры ${plan.fat} г, углеводы ${plan.carbs} г (меняется на вкладке «Тело»).",
            { it.intakeKcal ?: plan.kcal.toDouble() }),
        Metric("mood", "Настроение", Icons.Rounded.Mood, Tints.Butter, "из 5", 3.5, 5.0, "Среднее по отметкам настроения за день (сводка трижды в день).",
            { st.moodByDay[it.day] }, ::f1),
    )
}

@Composable
fun HealthScreen(onBack: () -> Unit, startWeight: Boolean = false, onOpenMap: () -> Unit = {}) {
    val ctx = LocalContext.current
    val st = remember { HealthState() }
    var tab by remember { mutableIntStateOf(if (startWeight) 3 else 0) }
    var weightOpen by remember { mutableStateOf(startWeight) }
    var detail by remember { mutableStateOf<Metric?>(null) }
    var page by remember { mutableStateOf<String?>(null) }   // bodycheck | photos | mood
    var workout by remember { mutableStateOf<WorkoutKind?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val available = remember { HealthData.available(ctx) }
    val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { version++ }

    LaunchedEffect(version) {
        st.loading = true
        if (available) st.granted = runCatching { HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions() }.getOrNull()
        st.days = HealthData.days(ctx, 35, force = version > 0)
        st.workouts = HealthData.workouts(ctx, force = version > 0)
        val z = ZoneId.systemDefault()
        val moods = runCatching { AppDb.get(ctx).life().moods().first() }.getOrDefault(emptyList())
        st.moodByDay = moods.groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(z).toLocalDate() }.mapValues { (_, l) -> l.map { it.level }.average() }
        val kg = st.days.lastOrNull { it.weightKg != null }?.weightKg
        val check = BodyCheck.latest(ctx)
        val h = com.pank.vsevodnom.ui.home.Profile.get(ctx, "height").replace(',', '.').toDoubleOrNull()
        val weekMood = st.moodByDay.filterKeys { !it.isBefore(LocalDate.now().minusDays(6)) }.values.takeIf { it.isNotEmpty() }?.average()
        st.parts = HealthScore.compute(st.days, StepGoal.value(ctx, kg), Nutrition.plan(ctx), h, check,
            check?.let { java.time.temporal.ChronoUnit.DAYS.between(it.date, LocalDate.now()) }, weekMood)
        st.loading = false
    }

    workout?.let { k -> BackHandler { workout = null; version++ }; WorkoutSession(k) { workout = null; version++ }; return }
    if (weightOpen) { BackHandler { weightOpen = false }; com.pank.vsevodnom.ui.weight.WeightScreen({ weightOpen = false; version++ }); return }
    when (page) {
        "bodycheck" -> { BackHandler { page = null }; BodyCheckScreen { page = null; version++ }; return }
        "photos" -> { BackHandler { page = null }; BodyPhotosScreen { page = null }; return }
        "mood" -> { BackHandler { page = null }; com.pank.vsevodnom.ui.life.MoodScreen { page = null }; return }
    }
    val ms = remember(st.days, st.moodByDay) { metrics(ctx, st) }

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(listOf("Здоровье", "Показатели", "Тренировки", "Тело")[tab], onBack, actions = {
            RoundIconButton(Icons.Rounded.Refresh, "Обновить", { version++ })
        }) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> SummaryTab(st, ms, available, onConnect = { permLauncher.launch(HC_PERMS) }, onMetric = { detail = it }, onBody = { page = "bodycheck" })
                    1 -> MetricsTab(st, ms, onMetric = { m -> if (m.key == "mood") page = "mood" else detail = m }, onWorkouts = { tab = 2 })
                    2 -> WorkoutsTab(st) { workout = it }
                    else -> BodyTab(st, onWeight = { weightOpen = true }, onCheck = { page = "bodycheck" }, onPhotos = { page = "photos" })
                }
            }
            // нижний бар разделов здоровья
            Row(Modifier.fillMaxWidth().background(Pal.Card).padding(vertical = 6.dp)) {
                listOf(Icons.Rounded.Favorite to "Здоровье", Icons.Rounded.Insights to "Показатели", Icons.Rounded.FitnessCenter to "Тренировки", Icons.Rounded.Accessibility to "Тело")
                    .forEachIndexed { i, (ic, t) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { tab = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (tab == i) Pal.Rose else Color.Transparent).padding(horizontal = 14.dp, vertical = 3.dp)) {
                                Icon(ic, null, tint = if (tab == i) Pal.RoseInk else Pal.Muted)
                            }
                            Text(t, style = MaterialTheme.typography.labelSmall, color = if (tab == i) Pal.Ink else Pal.Muted)
                        }
                    }
            }
        }
    }
    detail?.let { m -> MetricDetail(m, st) { detail = null } }
}

// ---------------- главная: оценка и ползунки ----------------

@Composable
private fun SummaryTab(st: HealthState, ms: List<Metric>, available: Boolean, onConnect: () -> Unit, onMetric: (Metric) -> Unit, onBody: () -> Unit) {
    val ctx = LocalContext.current
    val total = HealthScore.total(st.parts)
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScoreRing(total, 170.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Оценка здоровья", style = MaterialTheme.typography.titleMedium)
                    Text(total?.let { HealthScore.verdict(it) } ?: if (st.loading) "Считаю…" else "Мало данных — подключи Health Connect", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, textAlign = TextAlign.Center)
                    Text("по показателям тела за неделю · оценка жизни — в «Мои успехи»", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            }
        }
        if (st.parts.isNotEmpty()) item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Главное")
                st.parts.sortedByDescending { it.weight }.forEach { p -> PartSlider(p) { ms.firstOrNull { it.key == mapKey(p.key) }?.let(onMetric) } }
                Text("Вес показателя: шаги ×3, тренировки ×2, питание ×2, вес ×2, сон ×1, пульс ×0,7, кислород и давление ×0,5, настроение ×0,3. Нет данных — показатель не считается.",
                    style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // шаги за неделю — столбики с подсказкой
        val week = st.days.takeLast(7)
        if (week.any { (it.steps ?: 0) > 0 }) item {
            AppCard(Modifier.fillMaxWidth(), onClick = { ms.firstOrNull { it.key == "steps" }?.let(onMetric) }) {
                SectionLabel("Шаги за неделю")
                BarsChart(week.map { it.day to it.steps?.toDouble() }, ms.first().lo, null, Pal.MintInk, ::int, Modifier.fillMaxWidth().height(140.dp))
            }
        }
        if (available && st.granted != null && !st.granted!!.containsAll(HealthData.READ)) item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Rose) {
                Text("Подключи Health Connect", style = MaterialTheme.typography.titleSmall, color = Pal.RoseInk)
                Text("Mi Band 8 → Mi Fitness → Профиль → Настройки → Сторонние данные / Health Connect → включить синхронизацию. Потом здесь «Разрешить».", style = MaterialTheme.typography.bodySmall)
                PillButton("Разрешить доступ", onConnect, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (!available) item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                Text("Health Connect недоступен", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                Text("На Pixel он встроен: Настройки → Безопасность и конфиденциальность → Health Connect. Если просит обновление — обнови в Play Маркете.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { BodyCheckStatusCard(onBody) }
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Sky) {
                Text("Как мерить, если браслет не всегда на руке", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk)
                Text("• Спи в браслете — это сон, пульс в покое и кислород за ночь (самые ценные данные).\n• Днём — шаги с телефона тоже считаются.\n• Раз в неделю, в понедельник утром вместе со взвешиванием — замер пульса и кислорода на браслете в покое.\n• Анализ состава тела — раз в 3 месяца.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun mapKey(partKey: String) = when (partKey) { "body" -> "weight"; "activity" -> "activity"; else -> partKey }

@Composable
internal fun ScoreRing(score: Double?, size: androidx.compose.ui.unit.Dp) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(score) { a.animateTo(((score ?: 0.0) / 10).toFloat(), tween(1200, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 0.09f
            drawArc(Pal.Line, 135f, 270f, false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(scoreColor(a.value * 10.0), 135f, 270f * a.value, false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.let { f1(a.value * 10.0) } ?: "—", fontSize = (size.value * 0.26f).sp, fontWeight = FontWeight.ExtraBold, color = score?.let { scoreColor(it) } ?: Pal.Muted)
            Text("из 10", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        }
    }
}

@Composable
private fun PartSlider(p: HealthScore.Part, onClick: () -> Unit) {
    val v by animateFloatAsState((p.score / 10).toFloat(), tween(900), label = "p")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(p.value, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            Text("  " + f1(p.score), style = MaterialTheme.typography.titleSmall, color = scoreColor(p.score), fontWeight = FontWeight.Bold)
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Pal.Line)) {
            Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(scoreColor(p.score)))
        }
        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
    }
}

// ---------------- показатели ----------------

@Composable
private fun MetricsTab(st: HealthState, ms: List<Metric>, onMetric: (Metric) -> Unit, onWorkouts: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ms.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> MetricTile(m, st, Modifier.weight(1f)) { onMetric(m) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StubTile("Стресс", Icons.Rounded.Psychology, "Mi Fitness не передаёт стресс в Health Connect — только в своём приложении.", Modifier.weight(1f))
                StubTile("Витальность", Icons.Rounded.Bolt, "Оценка витальности (PAI) Xiaomi тоже не выгружается — её место занимает оценка здоровья.", Modifier.weight(1f))
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Peach, onClick = onWorkouts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FitnessCenter, null, tint = Pal.PeachInk)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Тренировки", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                        Text("за месяц: ${st.workouts.count { it.start.isAfter(java.time.Instant.now().minusSeconds(30 * 86400L)) }} · история и запуск", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Pal.PeachInk)
                }
            }
        }
    }
}

@Composable
private fun MetricTile(m: Metric, st: HealthState, modifier: Modifier, onClick: () -> Unit) {
    val week = st.days.takeLast(7).map { m.get(it) }
    val last = st.days.lastOrNull { m.get(it) != null }?.let { m.get(it) }
    val anim = remember(m.key) { Animatable(0f) }
    LaunchedEffect(m.key, week) { anim.snapTo(0f); anim.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    AppCard(modifier.height(150.dp), color = m.tint.bg, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(m.icon, null, tint = m.tint.ink, modifier = Modifier.size(18.dp))
            Text("  " + m.title, style = MaterialTheme.typography.labelLarge, color = m.tint.ink, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(last?.let { m.fmt(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(if (last == null) "нет данных" else m.unit, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        Spacer(Modifier.weight(1f))
        // мини-график недели (растёт при открытии)
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val vals = week.map { it ?: 0.0 }; val max = (vals.maxOrNull() ?: 0.0).coerceAtLeast(m.lo ?: 1.0).coerceAtLeast(0.0001)
            val bw = size.width / 7f
            vals.forEachIndexed { i, v ->
                val hh = (v / max).toFloat() * size.height * anim.value
                drawRoundRect(m.tint.ink.copy(alpha = if (i == 6) 0.9f else 0.35f), Offset(i * bw + bw * 0.2f, size.height - hh), Size(bw * 0.6f, hh.coerceAtLeast(2f)), CornerRadius(6f))
            }
        }
    }
}

@Composable
private fun StubTile(title: String, icon: ImageVector, text: String, modifier: Modifier) {
    AppCard(modifier.height(150.dp), color = Pal.Card) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Pal.Muted, modifier = Modifier.size(18.dp)); Text("  $title", style = MaterialTheme.typography.labelLarge, color = Pal.Muted) }
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
    }
}

/** Подробно о показателе: значение, график 7/30/90 дней с нормой и нажатием, среднее, отклонение. */
@Composable
private fun MetricDetail(m: Metric, st: HealthState, onDismiss: () -> Unit) {
    var range by remember { mutableIntStateOf(7) }
    FullScreen(m.title, onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val pts = st.days.takeLast(range).map { it.day to m.get(it) }
            val vals = pts.mapNotNull { it.second }
            val last = vals.lastOrNull()
            AppCard(Modifier.fillMaxWidth(), color = m.tint.bg) {
                Text(last?.let { m.fmt(it) + " " + m.unit } ?: "Нет данных", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("последнее значение", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                if (vals.isNotEmpty()) {
                    val avg = vals.average()
                    Text("Среднее за $range дн.: ${m.fmt(avg)} · мин ${m.fmt(vals.min())} · макс ${m.fmt(vals.max())}" + if (m.sum) " · всего ${m.fmt(vals.sum())}" else "",
                        style = MaterialTheme.typography.bodyMedium)
                    val dev = when { m.lo != null && avg < m.lo -> "ниже нормы на ${((1 - avg / m.lo) * 100).toInt()}%"; m.hi != null && avg > m.hi -> "выше нормы на ${((avg / m.hi - 1) * 100).toInt()}%"; m.lo != null || m.hi != null -> "в норме"; else -> null }
                    dev?.let { Text("Отклонение: $it", style = MaterialTheme.typography.titleSmall, color = if (it == "в норме") Pal.MintInk else Pal.RoseInk) }
                }
                Text("Норма: " + when { m.lo != null && m.hi != null -> "${m.fmt(m.lo)}–${m.fmt(m.hi)} ${m.unit}"; m.lo != null -> "от ${m.fmt(m.lo)} ${m.unit}"; m.hi != null -> "до ${m.fmt(m.hi)} ${m.unit}"; else -> "индивидуально" },
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7 to "Неделя", 30 to "Месяц").forEach { (d, t) -> Chip(t, range == d, { range = d }) } }
            AppCard(Modifier.fillMaxWidth()) {
                Text("Нажми на столбик — значение дня", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                BarsChart(pts, m.lo, m.hi, m.tint.ink, m.fmt, Modifier.fillMaxWidth().height(200.dp))
            }
            AppCard(Modifier.fillMaxWidth()) { Text(m.about, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Столбики по дням с полосой нормы (пунктир) и подсказкой по нажатию. */
@Composable
internal fun BarsChart(pts: List<Pair<LocalDate, Double?>>, lo: Double?, hi: Double?, color: Color, fmt: (Double) -> String, modifier: Modifier) {
    var sel by remember(pts.size) { mutableIntStateOf(pts.lastIndex) }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pts) { anim.snapTo(0f); anim.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Column(modifier) {
        pts.getOrNull(sel)?.let { (d, v) ->
            Text(d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)).replaceFirstChar { it.uppercase() } + ": " + (v?.let(fmt) ?: "нет данных"),
                style = MaterialTheme.typography.titleSmall, color = color)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp).pointerInput(pts) {
            detectTapGestures { o -> sel = (o.x / (size.width / pts.size.coerceAtLeast(1))).toInt().coerceIn(0, pts.lastIndex) }
        }) {
            val vals = pts.mapNotNull { it.second }
            val max = (listOfNotNull(vals.maxOrNull(), hi, lo).maxOrNull() ?: 1.0) * 1.1
            val minV = if (hi != null && lo != null && lo > 0 && (vals.minOrNull() ?: 0.0) > lo * 0.7) lo * 0.8 else 0.0
            fun y(v: Double) = size.height - ((v - minV) / (max - minV)).toFloat().coerceIn(0f, 1f) * size.height
            if (lo != null && hi != null) drawRect(Color(0x1A2DB37A), Offset(0f, y(hi)), Size(size.width, y(lo) - y(hi)))
            listOfNotNull(lo, hi).forEach { n -> drawLine(Color(0xFF2DB37A), Offset(0f, y(n)), Offset(size.width, y(n)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))) }
            val bw = size.width / pts.size.coerceAtLeast(1)
            pts.forEachIndexed { i, (_, v) ->
                if (v == null) { drawCircle(Pal.Line, 4f, Offset(i * bw + bw / 2, size.height - 4f)); return@forEachIndexed }
                val top = size.height - (size.height - y(v)) * anim.value
                drawRoundRect(color.copy(alpha = if (i == sel) 1f else 0.45f), Offset(i * bw + bw * 0.18f, top), Size(bw * 0.64f, size.height - top), CornerRadius(8f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val step = if (pts.size > 10) pts.size / 6 else 1
            pts.forEachIndexed { i, (d, _) ->
                Text(if (i % step == 0) (if (pts.size <= 10) d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU) else "${d.dayOfMonth}") else "",
                    style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

// ---------------- тренировки ----------------

@Composable
private fun WorkoutsTab(st: HealthState, onStart: (WorkoutKind) -> Unit) {
    var open by remember { mutableStateOf<WorkoutRec?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionLabel("Начать тренировку") }
        items(WORKOUT_KINDS.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Pal.Peach).clickable { onStart(k) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(k.emoji, fontSize = 28.sp)
                        Text(k.title, style = MaterialTheme.typography.labelLarge, color = Pal.PeachInk, textAlign = TextAlign.Center, maxLines = 2)
                        Text(if (k.outdoor) "с треком" else "в зале", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Text("Тренировка, запущенная на самом браслете, попадает сюда после синхронизации Mi Fitness → Health Connect (обычно через несколько минут). Треки, импортированные на карту, в здоровье не считаются.",
                style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
        item { SectionLabel("История (${st.workouts.size})") }
        if (st.workouts.isEmpty()) item { EmptyHint(if (st.loading) "Загружаю…" else "Тренировок пока нет. Запусти здесь или на браслете.") }
        items(st.workouts) { w ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { open = w }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Pal.Peach), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FitnessCenter, null, tint = Pal.PeachInk) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(w.title, style = MaterialTheme.typography.titleSmall)
                    Text(w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)) + " · ${w.minutes} мин" +
                        (w.distKm?.takeIf { it > 0.05 }?.let { " · ${f1(it)} км" } ?: "") + (w.kcal?.let { " · ${it.toInt()} ккал" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                w.hrAvg?.let { Text("♥ $it", style = MaterialTheme.typography.labelLarge, color = Pal.RoseInk) }
            }
        }
    }
    open?.let { w ->
        AlertDialog(onDismissRequest = { open = null }, confirmButton = { TextButton({ open = null }) { Text("Закрыть") } }, title = { Text(w.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(w.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", RU)))
                    Text("Длительность: ${w.minutes / 60} ч ${w.minutes % 60} мин")
                    w.distKm?.takeIf { it > 0.05 }?.let { d -> Text("Дистанция: ${f1(d)} км · темп ${if (d > 0) "%.1f".format(w.minutes / d).replace('.', ',') else "—"} мин/км · скорость ${f1(d / (w.minutes / 60.0).coerceAtLeast(0.01))} км/ч") }
                    w.kcal?.let { Text("Калории: ${it.toInt()} ккал") }
                    w.hrAvg?.let { Text("Пульс: средний $it" + (w.hrMax?.let { m -> ", максимальный $m" } ?: "")) }
                    Text("Источник: Health Connect (браслет / Mi Fitness или запись здесь)", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                }
            })
    }
}

// ---------------- тело ----------------

@Composable
private fun BodyTab(st: HealthState, onWeight: () -> Unit, onCheck: () -> Unit, onPhotos: () -> Unit) {
    val ctx = LocalContext.current
    var planEdit by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(Nutrition.plan(ctx)) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val w = st.days.mapNotNull { d -> d.weightKg?.let { d.day to it } }
            AppCard(Modifier.fillMaxWidth(), color = Pal.Sky, onClick = onWeight) {
                Text("Вес", style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk)
                Text(w.lastOrNull()?.let { "${f1(it.second)} кг" } ?: "— кг", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("графики, цель, ИМТ, взвешивание по понедельникам", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }
        item { BodyCheckStatusCard(onCheck) }
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac, onClick = onPhotos) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = Pal.LilacInk)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Фото до / после", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
                        Text("спереди, сбоку, сзади — сравнение первой и последней серии", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Pal.LilacInk)
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Butter, onClick = { planEdit = true }) {
                Text("План питания", style = MaterialTheme.typography.labelLarge, color = Pal.ButterInk)
                Text("${plan.kcal} ккал в день", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Белки" to plan.protein, "Жиры" to plan.fat, "Углеводы" to plan.carbs).forEach { (n, g) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.6f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$g г", style = MaterialTheme.typography.titleSmall); Text(n, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                        }
                    }
                }
                Text("Пока нет записей питания, оценка считает, что ты ешь по плану. Нажми, чтобы изменить.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (planEdit) {
        var k by remember { mutableStateOf(plan.kcal.toString()) }; var pr by remember { mutableStateOf(plan.protein.toString()) }
        var fa by remember { mutableStateOf(plan.fat.toString()) }; var ca by remember { mutableStateOf(plan.carbs.toString()) }
        AlertDialog(onDismissRequest = { planEdit = false }, title = { Text("План питания") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Triple("Калории, ккал", k) { v: String -> k = v }, Triple("Белки, г", pr) { v: String -> pr = v }, Triple("Жиры, г", fa) { v: String -> fa = v }, Triple("Углеводы, г", ca) { v: String -> ca = v })
                        .forEach { (l, v, set) -> AppTextField(v, { set(it.filter(Char::isDigit).take(5)) }, l, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) }
                }
            },
            confirmButton = { TextButton({
                val np = Nutrition.Plan(k.toIntOrNull() ?: plan.kcal, pr.toIntOrNull() ?: plan.protein, fa.toIntOrNull() ?: plan.fat, ca.toIntOrNull() ?: plan.carbs)
                Nutrition.setPlan(ctx, np); plan = np; planEdit = false
            }) { Text("Сохранить") } },
            dismissButton = { TextButton({ planEdit = false }) { Text("Отмена") } })
    }
}

/** Статус анализа состава тела: актуален / обновить через N дней / пора. */
@Composable
internal fun BodyCheckStatusCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val last = remember { BodyCheck.latest(ctx) }
    val left = remember { BodyCheck.daysLeft(ctx) }
    val due = left != null && left <= 0
    AppCard(Modifier.fillMaxWidth(), color = if (due) Pal.Rose else Pal.Mint, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Biotech, null, tint = if (due) Pal.RoseInk else Pal.MintInk)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(BodyCheck.TITLE, style = MaterialTheme.typography.titleSmall, color = if (due) Pal.RoseInk else Pal.MintInk)
                Text(when {
                    last == null -> "нет записей"
                    due -> "Обновите данные — прошло ${-left!! + BodyCheck.EVERY_DAYS} дн. (раз в 3 месяца)"
                    left!! <= 14 -> "Стоит обновить данные через $left дн."
                    else -> "Данные актуальны · следующий анализ через $left дн."
                } + (last?.let { " · от " + it.date.format(DateTimeFormatter.ofPattern("d.MM.yyyy")) } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Pal.Muted)
        }
    }
}
