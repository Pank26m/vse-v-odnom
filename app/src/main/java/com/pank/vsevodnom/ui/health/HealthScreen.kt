package com.pank.vsevodnom.ui.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.pank.vsevodnom.data.AppDb
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

val HC_PERMS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(HeightRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(WeightRecord::class),
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
)

data class HealthSummary(
    val steps: Long, val distanceKm: Double, val kcal: Double,
    val avgHr: Long?, val restHr: Long?, val sleep: Duration?,
    val weekSteps: List<Pair<LocalDate, Long>>,
    val weightKg: Double?, val heightM: Double?,
    val workouts: List<ExerciseSessionRecord>,
)

object HealthRepo {
    fun status(ctx: Context) = HealthConnectClient.getSdkStatus(ctx)
    fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    suspend fun granted(ctx: Context) = client(ctx).permissionController.getGrantedPermissions()

    suspend fun summary(ctx: Context): HealthSummary {
        val c = client(ctx)
        val z = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.atStartOfDay(z).toInstant()
        val now = Instant.now()
        val g = granted(ctx)
        fun has(r: kotlin.reflect.KClass<out Record>) = HealthPermission.getReadPermission(r) in g

        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(HeartRateRecord::class)) add(HeartRateRecord.BPM_AVG)
            if (has(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
        }
        val agg = if (metrics.isNotEmpty()) c.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, now))) else null
        val week = if (has(StepsRecord::class)) (6 downTo 0).map { d ->
            val day = today.minusDays(d.toLong())
            val r = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(day.atStartOfDay(z).toInstant(), day.plusDays(1).atStartOfDay(z).toInstant())))
            day to (r[StepsRecord.COUNT_TOTAL] ?: 0L)
        } else emptyList()
        val sleep = if (has(SleepSessionRecord::class)) c.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
            TimeRangeFilter.between(today.minusDays(1).atTime(12, 0).atZone(z).toInstant(), now))).records
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.takeIf { it > 0 }?.let { Duration.ofMinutes(it) } else null
        val weight = if (has(WeightRecord::class)) c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(now.minus(Duration.ofDays(365))), ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.weight?.inKilograms else null
        val height = if (has(HeightRecord::class)) c.readRecords(ReadRecordsRequest(HeightRecord::class, TimeRangeFilter.after(now.minus(Duration.ofDays(3650))), ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.height?.inMeters else null
        val workouts = if (has(ExerciseSessionRecord::class)) c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.after(now.minus(Duration.ofDays(30))), ascendingOrder = false, pageSize = 10)).records else emptyList()
        return HealthSummary(
            agg?.get(StepsRecord.COUNT_TOTAL) ?: 0, agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0,
            agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories ?: 0.0,
            agg?.get(HeartRateRecord.BPM_AVG), agg?.get(RestingHeartRateRecord.BPM_AVG), sleep, week, weight, height, workouts
        )
    }

    /** Записи веса из приложения → Health Connect (без дублей по дням). */
    suspend fun pushWeights(ctx: Context): Int {
        val c = client(ctx)
        val z = ZoneId.systemDefault()
        val ours = AppDb.get(ctx).weight().all().first()
        if (ours.isEmpty()) return 0
        val from = LocalDate.ofEpochDay(ours.first().epochDay).atStartOfDay(z).toInstant()
        val existing = c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(from))).records
            .map { it.time.atZone(z).toLocalDate().toEpochDay() }.toSet()
        val recs = ours.filter { it.epochDay !in existing }.map {
            val t = LocalDate.ofEpochDay(it.epochDay).atTime(9, 0).atZone(z)
            WeightRecord(time = t.toInstant(), zoneOffset = t.offset, weight = Mass.kilograms(it.kg))
        }
        if (recs.isNotEmpty()) c.insertRecords(recs)
        return recs.size
    }
}

@Composable
fun HealthScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var workout by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<HealthSummary?>(null) }
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val status = remember { HealthRepo.status(ctx) }

    val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { version++ }
    LaunchedEffect(version) {
        if (status != HealthConnectClient.SDK_AVAILABLE) return@LaunchedEffect
        runCatching {
            granted = HealthRepo.granted(ctx)
            if (granted!!.isNotEmpty()) summary = HealthRepo.summary(ctx)
        }.onFailure { err = it.message }
    }

    if (workout) { BackHandler { workout = false }; CyclingWorkout { workout = false; version++ }; return }

    ScreenScaffold("Здоровье", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                item {
                    AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                        Text("Health Connect недоступен", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                        Text("На Pixel он встроен в систему: Настройки → Безопасность и конфиденциальность → Health Connect. Если просит обновление — обнови в Play Маркете.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    PillButton("Открыть Play Маркет", {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                    }, Modifier.fillMaxWidth())
                }
            } else if (granted?.isEmpty() == true || (granted != null && !granted!!.containsAll(HC_PERMS.filter { it.contains("READ") }))) {
                item {
                    AppCard(Modifier.fillMaxWidth(), color = Pal.Rose) {
                        Text("Подключи Health Connect", style = MaterialTheme.typography.titleSmall, color = Pal.RoseInk)
                        Text("Туда стекаются данные из Google Fit, Fitbit, Mi Fitness / Zepp Life (если в их настройках включена синхронизация с Health Connect). Отсюда — шаги, сон, пульс, вес, рост, тренировки.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { PillButton("Разрешить доступ", { permLauncher.launch(HC_PERMS) }, Modifier.fillMaxWidth()) }
            }
            val s = summary
            if (s != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Шаги", "%,d".format(s.steps).replace(',', ' '), "сегодня", Modifier.weight(1f), Pal.Mint, Pal.MintInk)
                        Stat("Дистанция", "%.1f км".format(s.distanceKm).replace('.', ','), "${s.kcal.roundToInt()} ккал", Modifier.weight(1f), Pal.Sky, Pal.SkyInk)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Пульс", s.avgHr?.let { "$it" } ?: "—", s.restHr?.let { "в покое $it" } ?: "средний", Modifier.weight(1f), Pal.Rose, Pal.RoseInk)
                        Stat("Сон", s.sleep?.let { "${it.toHours()} ч ${it.toMinutes() % 60} м" } ?: "—", "прошлой ночью", Modifier.weight(1f), Pal.Lilac, Pal.LilacInk)
                    }
                }
                if (s.weekSteps.isNotEmpty()) item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Шаги за неделю")
                        Spacer(Modifier.height(8.dp))
                        val max = (s.weekSteps.maxOf { it.second }.coerceAtLeast(1000)).toFloat()
                        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                            val n = s.weekSteps.size; val bw = size.width / n
                            s.weekSteps.forEachIndexed { i, (_, v) ->
                                val h = v / max * (size.height - 4)
                                drawRoundRect(if (i == n - 1) Pal.MintInk.copy(alpha = 0.7f) else Pal.Mint, Offset(i * bw + bw * 0.18f, size.height - h), Size(bw * 0.64f, h), CornerRadius(10f))
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            s.weekSteps.forEach { (d, _) -> Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru")), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        }
                    }
                }
                item {
                    InfoGroupLocal("Тело", buildList {
                        s.weightKg?.let { add("Вес" to "%.1f кг".format(it).replace('.', ',')) }
                        s.heightM?.let { add("Рост" to "${(it * 100).roundToInt()} см") }
                        if (s.weightKg != null && s.heightM != null) add("ИМТ" to "%.1f".format(s.weightKg / (s.heightM * s.heightM)).replace('.', ','))
                    })
                }
                item {
                    PillButton("Отправить мой вес в Health Connect", {
                        scope.launch { runCatching { HealthRepo.pushWeights(ctx) }.onSuccess { err = "Отправлено записей: $it"; version++ }.onFailure { err = "Не получилось: ${it.message}" } }
                    }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                }
                if (s.workouts.isNotEmpty()) item {
                    InfoGroupLocal("Тренировки за 30 дней", s.workouts.map { w ->
                        val d = Duration.between(w.startTime, w.endTime)
                        (w.title ?: workoutName(w.exerciseType)) to "${w.startTime.atZone(ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth}.${"%02d".format(it.monthValue)}" }} · ${d.toMinutes()} мин"
                    })
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach, onClick = { workout = true }) {
                    Text("Велотренировка с пульсом", style = MaterialTheme.typography.titleMedium, color = Pal.PeachInk)
                    Text("Живой пульс с Mi Band 8 (нужна «Трансляция пульса» на браслете), зоны, средний и максимальный пульс. Сохраняется в Health Connect.", style = MaterialTheme.typography.bodySmall)
                }
            }
            err?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

private fun workoutName(t: Int) = when (t) {
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Велосипед"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Бег"; ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Ходьба"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Плавание"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Силовая"
    else -> "Тренировка"
}

@Composable
private fun Stat(title: String, value: String, sub: String, modifier: Modifier, bg: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color) {
    AppCard(modifier, color = bg) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = ink)
        Text(value, style = MaterialTheme.typography.headlineLarge)
        Text(sub, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
    }
}

@Composable
private fun InfoGroupLocal(title: String, rows: List<Pair<String, String>>) = com.pank.vsevodnom.ui.utils.InfoGroup(title, rows)
