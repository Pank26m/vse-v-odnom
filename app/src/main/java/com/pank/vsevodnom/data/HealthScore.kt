package com.pank.vsevodnom.data

import kotlin.math.abs

/**
 * Оценка здоровья 0…10 — по показателям тела, с весами (коэффициентами):
 * шаги 3 · тренировки 2 · питание 2 · состав тела/вес 2 · сон 1 · экранное время 1 · пульс в покое 0,7 · кислород 0,5 · давление 0,5 · настроение 0,3.
 * Нет данных по стороне — она не считается (нет сна — не штраф). Перебор тренировок (> 10 ч в неделю) — минус, не плюс.
 * Чистая функция — проверяется тестом HealthScoreTest.
 */
object HealthScore {
    data class Part(val key: String, val name: String, val score: Double, val weight: Double, val value: String, val note: String)

    private fun c(v: Double) = v.coerceIn(0.0, 10.0)
    private fun f1(v: Double) = "%.1f".format(v).replace('.', ',')

    fun compute(
        days: List<DayHealth>, stepGoal: Long, plan: Nutrition.Plan?, heightCm: Double?, check: BodyCheck.Rec?, checkAgeDays: Long?,
        moodAvg: Double?,
        screenMin: List<Long> = emptyList(),
    ): List<Part> {
        val w7 = days.takeLast(7)
        val out = ArrayList<Part>()
        // шаги — главное
        val steps = w7.mapNotNull { it.steps }.filter { it > 0 }
        if (steps.isNotEmpty()) {
            val avg = steps.average()
            out += Part("steps", "Шаги", c(avg / stepGoal * 10), 3.0, "в среднем за неделю ${"%,d".format(avg.toLong()).replace(',', ' ')} в день", "цель ${stepGoal.toLong()}")
        }
        // тренировки и активность: 150 минут в неделю — норма ВОЗ; > 600 — перегруз
        val ex = w7.mapNotNull { it.exerciseMin }
        if (ex.isNotEmpty() || steps.isNotEmpty()) {
            val m = ex.sum().toDouble()
            val s = if (m <= 600) c(m / 150 * 10) else c(10 - (m - 600) / 60)
            out += Part("activity", "Тренировки", s, 2.0, "${m.toLong()} мин за неделю", if (m > 600) "перегруз — организму нужен отдых" else "норма 150 мин в неделю")
        }
        // питание: отклонение от плана (−20% или +20% → 5 баллов; 5000 ккал при 2100 → 0)
        if (plan != null) {
            val intake = w7.mapNotNull { it.intakeKcal }.filter { it > 300 }
            val avg = if (intake.isNotEmpty()) intake.average() else plan.kcal.toDouble()
            val r = abs(avg - plan.kcal) / plan.kcal
            out += Part("food", "Питание", c(10 - r * 25), 2.0, "${avg.toInt()} ккал в день", if (intake.isEmpty()) "по плану специалиста" else "план ${plan.kcal}")
        }
        // состав тела: ИМТ, а при свежем анализе (до полугода) — ещё процент жира и висцеральный жир
        val kg = days.lastOrNull { it.weightKg != null }?.weightKg ?: check?.v("weight")
        val h = heightCm ?: check?.v("height")
        if (kg != null && h != null && h > 100) {
            val bmi = kg / (h / 100 * h / 100)
            var s = if (bmi <= 25) 10.0 else c(10 - (bmi - 25))
            var note = "ИМТ ${f1(bmi)}"
            if (check != null && (checkAgeDays ?: 999) <= 180) {
                check.v("fat_pct")?.let { fp -> s = (s + c(10 - (fp - 19.5).coerceAtLeast(0.0) * 0.5)) / 2; note += ", жир ${f1(fp)}%" }
                check.v("visceral")?.let { v -> if (v > 10) s = c(s - 1) }
            }
            // вес идёт вниз за месяц — плюс
            val month = days.takeLast(31).mapNotNull { it.weightKg }
            if (month.size >= 2 && month.last() < month.first() - 0.3 && bmi > 25) s = c(s + 1)
            out += Part("body", "Вес и состав тела", s, 2.0, "${f1(kg)} кг", note)
        }
        // сон — не главное: нет записей — не учитываем
        val sleep = w7.mapNotNull { it.sleepMin }.filter { it > 60 }
        if (sleep.isNotEmpty()) {
            val hrs = sleep.average() / 60
            val s = c(when { hrs in 7.0..9.0 -> 10.0; hrs > 9 -> 10 - (hrs - 9) * 2; else -> 10 - (7 - hrs) * 2.5 })
            out += Part("sleep", "Сон", s, 1.0, "${f1(hrs)} ч", "норма 7–9 ч")
        }
        val rest = w7.mapNotNull { it.restHr }.filter { it in 30..150 }
        if (rest.isNotEmpty()) {
            val r = rest.average()
            out += Part("hr", "Пульс в покое", c(when { r in 50.0..70.0 -> 10.0; r > 70 -> 10 - (r - 70) * 0.4; else -> 8.0 }), 0.7, "${r.toInt()} уд/мин", "норма 50–70")
        }
        val sp = w7.mapNotNull { it.spo2 }
        if (sp.isNotEmpty()) { val v = sp.average(); out += Part("spo2", "Кислород в крови", c(if (v >= 95) 10.0 else 10 - (95 - v) * 2), 0.5, "${v.toInt()}%", "норма 95–100%") }
        val sys = w7.mapNotNull { it.bpSys }; val dia = w7.mapNotNull { it.bpDia }
        if (sys.isNotEmpty() && dia.isNotEmpty()) {
            val s1 = sys.average(); val d1 = dia.average()
            out += Part("bp", "Давление", c(10 - maxOf(s1 - 120, (d1 - 80) * 1.5, 0.0) * 0.25), 0.5, "${s1.toInt()}/${d1.toInt()}", "норма до 120/80")
        }
        // экранное время (0.45): до 2 ч в день — отлично, 8 ч и больше — 0
        val scr = screenMin.takeLast(7).filter { it > 0 }
        if (scr.isNotEmpty()) {
            val avg = scr.average()
            out += Part("screen", "Экранное время", ScreenTime.score(avg), 1.0, "${f1(avg / 60)} ч в день за неделю", "лучше до 2–3 ч")
        }
        if (moodAvg != null) out += Part("mood", "Настроение", c((moodAvg - 1) / 4 * 10), 0.3, "${f1(moodAvg)} из 5", "")
        return out
    }

    fun total(parts: List<Part>): Double? = parts.takeIf { it.isNotEmpty() }?.let { p -> p.sumOf { it.score * it.weight } / p.sumOf { it.weight } }

    fun verdict(v: Double) = when {
        v >= 8.5 -> "Отличная форма"
        v >= 7 -> "Хорошо — есть что подтянуть"
        v >= 5 -> "Средне: часть показателей проседает"
        v >= 3 -> "Слабо — начни с шагов и питания"
        else -> "Тревожно — шаги, питание и сон в первую очередь"
    }
}
