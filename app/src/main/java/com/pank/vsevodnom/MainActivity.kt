package com.pank.vsevodnom

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pank.vsevodnom.alarm.AlarmScheduler
import com.pank.vsevodnom.alarm.Channels
import com.pank.vsevodnom.ui.calc.CalcScreen
import com.pank.vsevodnom.ui.alerts.AlertsScreen
import com.pank.vsevodnom.ui.markets.MarketsScreen
import com.pank.vsevodnom.ui.recorder.RecorderScreen
import com.pank.vsevodnom.ui.utils.UtilsScreen
import com.pank.vsevodnom.ui.files.FilesScreen
import com.pank.vsevodnom.alarm.AlertWorker
import com.pank.vsevodnom.ui.calendar.CalendarScreen
import com.pank.vsevodnom.ui.calendar.PendingImport
import com.pank.vsevodnom.ui.clock.ClockScreen
import com.pank.vsevodnom.ui.home.HomeScreen
import com.pank.vsevodnom.ui.piggy.PiggyScreen
import com.pank.vsevodnom.ui.random.RandomScreen
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.weather.WeatherScreen
import com.pank.vsevodnom.ui.weight.WeightScreen

class MainActivity : FragmentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        Channels.ensure(this)
        AlarmScheduler.rescheduleAll(this)
        AlertWorker.schedule(this)
        pendingRoute.value = routeFrom(intent)

        setContent {
            AppTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute.value) {
                    pendingRoute.value?.let { nav.navigate(it) { launchSingleTop = true }; pendingRoute.value = null }
                }
                val back: () -> Unit = { if (!nav.popBackStack()) finish() }
                NavHost(
                    nav, startDestination = "home",
                    enterTransition = { fadeIn() }, exitTransition = { fadeOut() }
                ) {
                    composable("home") { HomeScreen(open = { nav.navigate(it) }) }
                    composable("clock/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) {
                        ClockScreen(back, it.arguments?.getInt("tab") ?: 0)
                    }
                    composable("weather") { WeatherScreen(back) }
                    composable("calc") { CalcScreen(back) }
                    composable("random") { RandomScreen(back) }
                    composable("weight") { WeightScreen(back) }
                    composable("piggy") { PiggyScreen(back) }
                    composable("calendar") { CalendarScreen(back) }
                    composable("alerts") { AlertsScreen(back) }
                    composable("markets") { MarketsScreen(back) }
                    composable("recorder") { RecorderScreen(back) }
                    composable("utils") { UtilsScreen(back) }
                    composable("files") { FilesScreen(back) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = routeFrom(intent)
    }

    private fun routeFrom(i: Intent?): String? {
        // файл расписания, открытый через «Открыть с помощью»
        if (i?.action == Intent.ACTION_VIEW && i.data != null && i.getBooleanExtra("import_schedule", false)) {
            PendingImport.uri = i.data
            return "calendar"
        }
        return when (i?.getStringExtra("route")) {
            "clock" -> "clock/1"
            "weight" -> "weight"
            "calendar" -> "calendar"
            "alerts" -> "alerts"
            "recorder" -> "recorder"
            else -> null
        }
    }
}
