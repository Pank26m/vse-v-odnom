package com.pank.vsevodnom.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

/** Показатели одного дня (null — нет данных). */
data class DayHealth(
    val day: LocalDate, val steps: Long? = null, val activeKcal: Double? = null, val totalKcal: Double? = null, val distKm: Double? = null,
    val exerciseMin: Long? = null, val sleepMin: Long? = null, val hrAvg: Long? = null, val hrMin: Long? = null, val hrMax: Long? = null,
    val restHr: Long? = null, val spo2: Double? = null, val bpSys: Double? = null, val bpDia: Double? = null,
    val intakeKcal: Double? = null, val weightKg: Double? = null,
)

/** Тренировка (с браслета через Mi Fitness → Health Connect или своя). */
data class WorkoutRec(val title: String, val type: Int, val start: Instant, val end: Instant, val kcal: Double?, val distKm: Double?, val hrAvg: Long?, val hrMax: Long?) {
    val minutes get() = Duration.between(start, end).toMinutes()
}

/**
 * Здоровье из Health Connect (туда пишет Mi Fitness с Mi Band 8, Google Fit и др.) + своё (вес, настроение).
 * По дням за [n] дней. Читается раз в 5 минут — экраны берут из памяти (меньше фризов).
 */
object HealthData {
    val READ = setOf(
        StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
        HeartRateRecord::class, RestingHeartRateRecord::class, SleepSessionRecord::class, WeightRecord::class, HeightRecord::class,
        ExerciseSessionRecord::class, OxygenSaturationRecord::class, BloodPressureRecord::class, NutritionRecord::class,
    ).map { HealthPermission.getReadPermission(it) }.toSet()

    @Volatile private var cache: Triple<Long, Int, List<DayHealth>>? = null
    @Volatile private var wCache: Pair<Long, List<WorkoutRec>>? = null

    fun invalidate() { cache = null }

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun days(ctx: Context, n: Int = 35, force: Boolean = false): List<DayHealth> = withContext(Dispatchers.IO) {
        cache?.takeIf { !force && it.second >= n && System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return@withContext it.third.takeLast(n) }
        val today = LocalDate.now()
        val from = today.minusDays(n - 1L)
        val z = ZoneId.systemDefault()
        val map = LinkedHashMap<LocalDate, DayHealth>()
        (0 until n).forEach { map[from.plusDays(it.toLong())] = DayHealth(from.plusDays(it.toLong())) }
        if (available(ctx)) runCatching {
            val c = HealthConnectClient.getOrCreate(ctx)
            val g = c.permissionController.getGrantedPermissions()
            fun has(r: kotlin.reflect.KClass<out Record>) = HealthPermission.getReadPermission(r) in g
            val range = TimeRangeFilter.between(from.atStartOfDay(), today.plusDays(1).atStartOfDay())
            val metrics = buildSet {
                if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
                if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
                if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                if (has(HeartRateRecord::class)) { add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX) }
                if (has(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
                if (has(ExerciseSessionRecord::class)) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
                if (has(BloodPressureRecord::class)) { add(BloodPressureRecord.SYSTOLIC_AVG); add(BloodPressureRecord.DIASTOLIC_AVG) }
                if (has(NutritionRecord::class)) add(NutritionRecord.ENERGY_TOTAL)
                if (has(WeightRecord::class)) add(WeightRecord.WEIGHT_AVG)
            }
            if (metrics.isNotEmpty()) c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1))).forEach { grp ->
                val d = grp.startTime.toLocalDate(); val r = grp.result
                map[d] = (map[d] ?: DayHealth(d)).copy(
                    steps = r[StepsRecord.COUNT_TOTAL], distKm = r[DistanceRecord.DISTANCE_TOTAL]?.inKilometers,
                    activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories, totalKcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                    hrAvg = r[HeartRateRecord.BPM_AVG], hrMin = r[HeartRateRecord.BPM_MIN], hrMax = r[HeartRateRecord.BPM_MAX], restHr = r[RestingHeartRateRecord.BPM_AVG],
                    exerciseMin = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes(),
                    bpSys = r[BloodPressureRecord.SYSTOLIC_AVG]?.inMillimetersOfMercury, bpDia = r[BloodPressureRecord.DIASTOLIC_AVG]?.inMillimetersOfMercury,
                    intakeKcal = r[NutritionRecord.ENERGY_TOTAL]?.inKilocalories, weightKg = r[WeightRecord.WEIGHT_AVG]?.inKilograms,
                )
            }
            // шаги из выбранного приложения (Health Connect по умолчанию берёт по своему приоритету источников)
            StepSource.get(ctx)?.let { src -> if (has(StepsRecord::class)) runCatching {
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), range, Period.ofDays(1),
                    dataOriginFilter = setOf(androidx.health.connect.client.records.metadata.DataOrigin(src)))).forEach { grp ->
                    val d = grp.startTime.toLocalDate(); map[d]?.let { map[d] = it.copy(steps = grp.result[StepsRecord.COUNT_TOTAL]) }
                }
            } }
            // сон — по дню пробуждения (ночь с понедельника на вторник = вторник)
            if (has(SleepSessionRecord::class)) c.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                TimeRangeFilter.between(from.minusDays(1).atTime(12, 0).atZone(z).toInstant(), Instant.now()), pageSize = 500)).records
                .groupBy { it.endTime.atZone(z).toLocalDate() }.forEach { (d, l) ->
                    map[d]?.let { map[d] = it.copy(sleepMin = l.sumOf { s -> Duration.between(s.startTime, s.endTime).toMinutes() }.coerceAtMost(16 * 60)) }
                }
            if (has(OxygenSaturationRecord::class)) c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range, pageSize = 2000)).records
                .groupBy { it.time.atZone(z).toLocalDate() }.forEach { (d, l) -> map[d]?.let { map[d] = it.copy(spo2 = l.map { r -> r.percentage.value }.average()) } }
        }
        // свой вес (взвешивания в приложении) — точнее, чем из Health Connect
        runCatching { AppDb.get(ctx).weight().all().first() }.getOrDefault(emptyList()).forEach { w ->
            val d = LocalDate.ofEpochDay(w.epochDay); map[d]?.let { map[d] = it.copy(weightKg = w.kg) }
        }
        val l = map.values.toList()
        cache = Triple(System.currentTimeMillis(), n, l)
        l
    }

    suspend fun workouts(ctx: Context, force: Boolean = false): List<WorkoutRec> = withContext(Dispatchers.IO) {
        wCache?.takeIf { !force && System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return@withContext it.second }
        val out = ArrayList<WorkoutRec>()
        if (available(ctx)) runCatching {
            val c = HealthConnectClient.getOrCreate(ctx)
            val g = c.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in g) return@runCatching
            val recs = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(180))), ascendingOrder = false, pageSize = 60)).records
            recs.forEachIndexed { i, s ->
                // сведения по тренировке — только для последних 25 (каждая — отдельный запрос)
                val agg = if (i < 25) runCatching {
                    val m = buildSet {
                        if (HealthPermission.getReadPermission(DistanceRecord::class) in g) add(DistanceRecord.DISTANCE_TOTAL)
                        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in g) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                        if (HealthPermission.getReadPermission(HeartRateRecord::class) in g) { add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX) }
                    }
                    if (m.isEmpty()) null else c.aggregate(AggregateRequest(m, TimeRangeFilter.between(s.startTime, s.endTime)))
                }.getOrNull() else null
                out += WorkoutRec(s.title ?: workoutName(s.exerciseType), s.exerciseType, s.startTime, s.endTime,
                    agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories, agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers,
                    agg?.get(HeartRateRecord.BPM_AVG), agg?.get(HeartRateRecord.BPM_MAX))
            }
        }
        wCache = System.currentTimeMillis() to out
        out
    }

    fun workoutName(t: Int) = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Велотренировка"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Велотренажёр"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Бег на улице"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Беговая дорожка"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Ходьба"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Плавание"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Силовая"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Поход"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Йога"
        else -> "Тренировка"
    }
}

/** План питания (собирает специалист): калории и БЖУ в день; если в Health Connect нет записей питания — считаем по плану. */
object Nutrition {
    data class Plan(val kcal: Int, val protein: Int, val fat: Int, val carbs: Int)
    private fun p(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    fun plan(ctx: Context) = Plan(p(ctx).getInt("kcal_plan", 2100), p(ctx).getInt("prot_plan", 140), p(ctx).getInt("fat_plan", 70), p(ctx).getInt("carb_plan", 225))
    fun setPlan(ctx: Context, pl: Plan) = p(ctx).edit().putInt("kcal_plan", pl.kcal).putInt("prot_plan", pl.protein).putInt("fat_plan", pl.fat).putInt("carb_plan", pl.carbs).apply()
}

/**
 * Биоимпедансный анализ состава тела (БИА, «InBody»): точные данные о жире, мышцах, воде, висцеральном жире.
 * Делается редко — рекомендуем раз в 3 месяца, пока идёт похудение. Хранится в prefs bodycheck (JSON).
 */
object BodyCheck {
    const val TITLE = "Биоимпедансный анализ состава тела"
    const val EVERY_DAYS = 90L
    data class P(val key: String, val name: String, val value: Double?, val lo: Double?, val hi: Double?, val unit: String, val text: String = "", val group: String = "")
    data class Rec(val id: String, val date: LocalDate, val params: List<P>, val note: String = "", val files: List<String> = emptyList()) {
        fun v(k: String) = params.firstOrNull { it.key == k }?.value
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bodycheck", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<Rec> {
        val raw = sp(ctx).getString("list", null) ?: return listOf(FIRST).also { save(ctx, it) }
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList()).sortedBy { it.date }
    }
    fun save(ctx: Context, l: List<Rec>) = sp(ctx).edit().putString("list", JSONArray().apply { l.forEach { put(toJson(it)) } }.toString()).apply()
    fun latest(ctx: Context) = all(ctx).lastOrNull()
    /** Сколько дней до следующего анализа (отрицательное — просрочено). */
    fun daysLeft(ctx: Context): Long? = latest(ctx)?.let { EVERY_DAYS - Duration.between(it.date.atStartOfDay(), LocalDate.now().atStartOfDay()).toDays() }

    private fun toJson(r: Rec) = JSONObject().put("id", r.id).put("date", r.date.toString()).put("note", r.note).put("files", JSONArray(r.files))
        .put("params", JSONArray().apply { r.params.forEach { p -> put(JSONObject().put("k", p.key).put("n", p.name).put("v", p.value ?: JSONObject.NULL)
            .put("lo", p.lo ?: JSONObject.NULL).put("hi", p.hi ?: JSONObject.NULL).put("u", p.unit).put("t", p.text).put("g", p.group)) } })
    private fun fromJson(o: JSONObject): Rec {
        fun d(x: JSONObject, k: String) = if (x.isNull(k)) null else x.optDouble(k).takeIf { !it.isNaN() }
        val ps = o.optJSONArray("params") ?: JSONArray()
        return Rec(o.getString("id"), LocalDate.parse(o.getString("date")), (0 until ps.length()).map { i -> ps.getJSONObject(i).let { x ->
            P(x.optString("k"), x.optString("n"), d(x, "v"), d(x, "lo"), d(x, "hi"), x.optString("u"), x.optString("t"), x.optString("g")) } },
            o.optString("note"), o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
    }

    private fun p(k: String, n: String, v: Double?, lo: Double?, hi: Double?, u: String, g: String, t: String = "") = P(k, n, v, lo, hi, u, t, g)
    /** Шаблон параметров (для нового замера). */
    val TEMPLATE: List<P> get() = FIRST.params.map { it.copy(value = null, text = if (it.key in setOf("body_type", "muscle_fix")) "" else it.text) }

    /** Запись из бланка 17.10.2025 (фото, присланное владельцем). */
    val FIRST = Rec("2025-10-17", LocalDate.of(2025, 10, 17), listOf(
        p("height", "Рост", 173.8, null, null, "см", "Общее"),
        p("age", "Возраст", 20.0, null, null, "лет", "Общее"),
        p("weight", "Вес", 98.0, 56.5, 76.4, "кг", "Основные компоненты"),
        p("water", "Общая вода", 45.6, 36.3, 44.3, "л", "Основные компоненты"),
        p("protein", "Белки", 12.4, 9.9, 12.1, "кг", "Основные компоненты"),
        p("minerals", "Минералы", 4.5, 3.4, 4.2, "кг", "Основные компоненты"),
        p("fat", "Жир", 35.4, 9.0, 13.6, "кг", "Основные компоненты"),
        p("bmi", "Индекс массы тела", 32.4, 18.5, 25.0, "кг/м²", "Мышцы и жир"),
        p("muscle", "Скелетные мышцы", 34.8, 27.8, 33.9, "кг", "Мышцы и жир"),
        p("fat_pct", "Процент жира", 36.2, 14.5, 19.5, "%", "Мышцы и жир"),
        p("visceral", "Висцеральный (внутренний) жир, уровень", 12.0, 4.0, 8.0, "", "Мышцы и жир", "норма 4–8, пограничный 8–10, превышение 10–15"),
        p("visceral_kg", "Висцеральный жир, масса", 6.1, null, null, "кг", "Мышцы и жир"),
        p("visceral_area", "Висцеральный жир, площадь", 153.0, 50.0, 100.0, "см²", "Мышцы и жир"),
        p("ideal", "Идеальный вес", 75.4, null, null, "кг", "Рекомендации"),
        p("task", "Задача по весу", -22.26, null, null, "кг", "Рекомендации"),
        p("muscle_fix", "Коррекция мышц", null, null, null, "кг", "Рекомендации", "в норме"),
        p("fat_fix", "Коррекция жира", -22.26, null, null, "кг", "Рекомендации"),
        p("icw", "Вода внутриклеточная", 28.8, null, null, "л", "Вода"),
        p("ecw", "Вода внеклеточная", 16.8, null, null, "л", "Вода"),
        p("ecw_ratio", "Соотношение жидкостей", 0.369, null, null, "", "Вода"),
        p("body_type", "Тип тела", null, null, null, "", "Дополнительно", "Ожирение 3 степени"),
        p("bmr", "Базальный метаболизм (в покое)", 1721.0, null, null, "ккал", "Дополнительно"),
        p("acm", "Активная клеточная масса", 41.2, null, null, "кг", "Дополнительно"),
        p("bio_age", "Биологический возраст", 25.0, null, null, "лет", "Дополнительно"),
        p("rec_kcal", "Рекомендуемые калории (активный образ жизни)", 2650.0, null, null, "ккал", "Дополнительно"),
        p("score", "Общая оценка", 60.0, null, null, "из 100", "Дополнительно"),
        p("m_trunk", "Мышцы — туловище", 27.43, 21.27, 26.0, "кг", "Сегменты: мышцы"),
        p("m_rarm", "Мышцы — правая рука", 3.48, 2.83, 3.46, "кг", "Сегменты: мышцы"),
        p("m_larm", "Мышцы — левая рука", 3.42, 2.83, 3.46, "кг", "Сегменты: мышцы"),
        p("m_rleg", "Мышцы — правая нога", 9.51, 7.81, 9.54, "кг", "Сегменты: мышцы"),
        p("m_lleg", "Мышцы — левая нога", 9.54, 7.81, 9.54, "кг", "Сегменты: мышцы"),
        p("f_trunk", "Жир — туловище", 18.32, 4.79, 7.19, "кг", "Сегменты: жир"),
        p("f_rarm", "Жир — правая рука", 2.44, 0.61, 0.92, "кг", "Сегменты: жир"),
        p("f_larm", "Жир — левая рука", 2.42, 0.61, 0.92, "кг", "Сегменты: жир"),
        p("f_rleg", "Жир — правая нога", 4.71, 1.5, 2.25, "кг", "Сегменты: жир"),
        p("f_lleg", "Жир — левая нога", 4.72, 1.5, 2.25, "кг", "Сегменты: жир"),
    ), note = "Бланк от 17.10.2025 13:50")
}

/**
 * Чьи шаги считать. Health Connect сам сводит данные нескольких приложений (браслет, Google Fit, телефон) по своему
 * списку приоритетов — поэтому число может не совпадать с тем, что показывает одно из приложений.
 * null = как решил Health Connect; иначе — пакет приложения (prefs health/steps_src).
 */
object StepSource {
    private fun p(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    fun get(ctx: Context): String? = p(ctx).getString("steps_src", "")!!.ifBlank { null }
    fun set(ctx: Context, pkg: String?) { p(ctx).edit().putString("steps_src", pkg ?: "").apply() }

    data class Src(val pkg: String, val name: String, val steps: Long)

    /** Шаги за день по каждому приложению-источнику + итог Health Connect (pkg = ""). */
    suspend fun breakdown(ctx: Context, day: LocalDate = LocalDate.now()): List<Src> = withContext(Dispatchers.IO) {
        runCatching {
            val c = HealthConnectClient.getOrCreate(ctx)
            val z = ZoneId.systemDefault()
            val range = TimeRangeFilter.between(day.atStartOfDay(z).toInstant(), day.plusDays(1).atStartOfDay(z).toInstant())
            val recs = ArrayList<StepsRecord>()
            var token: String? = null
            do {
                val r = c.readRecords(ReadRecordsRequest(StepsRecord::class, range, pageSize = 1000, pageToken = token))
                recs += r.records; token = r.pageToken
            } while (token != null && recs.size < 20000)
            val pkgs = recs.map { it.metadata.dataOrigin.packageName }.distinct()
            val total = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL] ?: 0L
            listOf(Src("", "Health Connect (сводит всех)", total)) + pkgs.map { pkg ->
                val n = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range,
                    dataOriginFilter = setOf(androidx.health.connect.client.records.metadata.DataOrigin(pkg))))[StepsRecord.COUNT_TOTAL] ?: 0L
                Src(pkg, appName(ctx, pkg), n)
            }.sortedByDescending { it.steps }
        }.getOrDefault(emptyList())
    }

    fun appName(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
