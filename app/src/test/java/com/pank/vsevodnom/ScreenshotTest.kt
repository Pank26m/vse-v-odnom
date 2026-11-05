package com.pank.vsevodnom

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.calc.CalcScreen
import com.pank.vsevodnom.ui.clock.ClockScreen
import com.pank.vsevodnom.ui.home.HomeScreen
import com.pank.vsevodnom.ui.piggy.PiggyScreen
import com.pank.vsevodnom.ui.random.RandomScreen
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.weather.WeatherScreen
import com.pank.vsevodnom.ui.weight.WeightScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Рендерит экраны в PNG (app/build/outputs/roborazzi) — проверка вёрстки без телефона. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { content() } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun seed(code: Int = 61, isDay: Int = 1) = runBlocking {
        val db = AppDb.get(ctx)
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val hTime = JSONArray(); val hT = JSONArray(); val hP = JSONArray(); val hC = JSONArray(); val hD = JSONArray(); val hV = JSONArray()
        for (i in 0 until 30) {
            hTime.put(now.plusHours(i.toLong()).toString()); hT.put(7.0 + kotlin.math.sin(i / 4.0) * 3)
            hP.put(listOf(60, 70, 40, 20, 10, 0)[i % 6]); hC.put(if (i < 4) code else 3); hD.put(isDay); hV.put(12000)
        }
        val dTime = JSONArray(); val dC = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray(); val dP = JSONArray(); val sr = JSONArray(); val ss = JSONArray(); val uv = JSONArray()
        for (i in 0 until 10) {
            val d = LocalDate.now().plusDays(i.toLong())
            dTime.put(d.toString()); dC.put(listOf(code, 2, 0, 3, 71, 63, 1, 0, 45, 95)[i]); dMax.put(9.0 - i * 0.7); dMin.put(3.0 - i * 0.6)
            dP.put(listOf(70, 30, 0, 10, 60, 80, 0, 0, 20, 50)[i]); sr.put("${d}T06:58"); ss.put("${d}T16:35"); uv.put(1.2)
        }
        val f = JSONObject()
            .put("current", JSONObject().put("time", now.toString()).put("temperature_2m", 7.2).put("relative_humidity_2m", 87)
                .put("apparent_temperature", 4.1).put("is_day", isDay).put("precipitation", 0.4).put("weather_code", code)
                .put("cloud_cover", 90).put("pressure_msl", 1003.0).put("wind_speed_10m", 5.1).put("wind_direction_10m", 310).put("wind_gusts_10m", 11.0))
            .put("hourly", JSONObject().put("time", hTime).put("temperature_2m", hT).put("precipitation_probability", hP)
                .put("weather_code", hC).put("is_day", hD).put("visibility", hV))
            .put("daily", JSONObject().put("time", dTime).put("weather_code", dC).put("temperature_2m_max", dMax).put("temperature_2m_min", dMin)
                .put("precipitation_probability_max", dP).put("sunrise", sr).put("sunset", ss).put("uv_index_max", uv))
        val raw = JSONObject().put("f", f).put("a", JSONObject().put("current", JSONObject().put("european_aqi", 32).put("pm2_5", 8.0)))
            .put("t", System.currentTimeMillis()).toString()
        val cities = db.cities()
        if (cities.weatherOnce().isEmpty()) {
            cities.upsertWeather(WeatherCity(name = "Киев", region = "Украина", lat = 50.45, lon = 30.52, cachedJson = raw, cachedAt = System.currentTimeMillis()))
            cities.upsertWeather(WeatherCity(name = "Львов", region = "Украина", lat = 49.8, lon = 24.0, sort = 1))
        }
        val w = db.weight()
        val start = LocalDate.now().minusWeeks(8)
        listOf(84.2, 84.0, 83.7, 83.9, 83.4, 83.1, 83.2, 82.8).forEachIndexed { i, kg ->
            w.upsert(WeightEntry(epochDay = start.plusWeeks(i.toLong()).toEpochDay(), kg = kg))
        }
        val p = db.piggy()
        val g1 = p.upsertGoal(PiggyGoal(title = "Отпуск", target = 40000.0, tint = 0))
        val g2 = p.upsertGoal(PiggyGoal(title = "Перфоратор в аренде", kind = PiggyGoal.KIND_PAYBACK, target = 6500.0, tint = 2))
        p.upsertGoal(PiggyGoal(title = "Резерв", tint = 4))
        p.addTx(PiggyTx(goalId = g1, amount = 12000.0, at = System.currentTimeMillis() - 60L * 86_400_000))
        p.addTx(PiggyTx(goalId = g1, amount = 6400.0))
        repeat(14) { p.addTx(PiggyTx(goalId = g2, amount = 300.0, at = System.currentTimeMillis() - (40 - it * 3) * 86_400_000L)) }
        db.alarms().upsert(AlarmEntity(hour = 7, minute = 30, daysMask = 0b0011111, label = "Пары"))
        db.alarms().upsert(AlarmEntity(hour = 10, minute = 0, daysMask = 0b1100000, enabled = false))
        db.cities().addWorld(WorldCity(zoneId = "Europe/Warsaw", name = "Варшава"))
        db.cities().addWorld(WorldCity(zoneId = "America/New_York", name = "Нью-Йорк"))
        db.cities().addWorld(WorldCity(zoneId = "Asia/Tokyo", name = "Токио"))
    }

    @Test fun home() { seed(); shot("01_home") { HomeScreen {} } }
    @Test fun weatherRain() { seed(61); shot("02_weather_rain") { WeatherScreen {} } }
    @Test fun clockWorld() { seed(); shot("03_clock_world") { ClockScreen({}, 0) } }
    @Test fun clockAlarms() { seed(); shot("04_clock_alarms") { ClockScreen({}, 1) } }
    @Test fun clockTimer() { seed(); shot("05_clock_timer") { ClockScreen({}, 3) } }
    @Test fun calc() { shot("06_calc") { CalcScreen {} } }
    @Test fun random() { shot("07_random") { RandomScreen {} } }
    @Test fun piggy() { seed(); shot("08_piggy") { PiggyScreen {} } }
    @Test fun weight() { seed(); shot("09_weight") { WeightScreen {} } }
}
