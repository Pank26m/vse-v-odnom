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

/** Все разрешения Health Connect, которые объявлены в манифесте (иначе окно разрешений не открывается вовсе). */
val HC_PERMS: Set<String> = com.pank.vsevodnom.data.HealthData.READ + setOf(
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
        val src = com.pank.vsevodnom.data.StepSource.get(ctx)?.let { setOf(androidx.health.connect.client.records.metadata.DataOrigin(it)) } ?: emptySet()
        val todaySteps = if (src.isNotEmpty() && has(StepsRecord::class)) runCatching { c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now), dataOriginFilter = src))[StepsRecord.COUNT_TOTAL] }.getOrNull() else null
        val week = if (has(StepsRecord::class)) (6 downTo 0).map { d ->
            val day = today.minusDays(d.toLong())
            val r = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(day.atStartOfDay(z).toInstant(), day.plusDays(1).atStartOfDay(z).toInstant()), dataOriginFilter = src))
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
            todaySteps ?: agg?.get(StepsRecord.COUNT_TOTAL) ?: 0, agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0,
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
