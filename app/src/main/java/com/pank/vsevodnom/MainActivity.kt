package com.pank.vsevodnom

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
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
import com.pank.vsevodnom.ui.games.GamesScreen
import com.pank.vsevodnom.ui.vault.PasswordsScreen
import com.pank.vsevodnom.ui.sms.SmsScreen
import com.pank.vsevodnom.ui.contacts.ContactsScreen
import com.pank.vsevodnom.ui.health.HealthScreen
import com.pank.vsevodnom.ui.downloads.DownloadsScreen
import com.pank.vsevodnom.ui.downloads.SharedLink
import com.pank.vsevodnom.ui.downloads.extractUrl
import com.pank.vsevodnom.alarm.AlertWorker
import com.pank.vsevodnom.ui.calendar.CalendarScreen
import com.pank.vsevodnom.ui.calendar.PendingImport
import com.pank.vsevodnom.ui.clock.ClockScreen
import com.pank.vsevodnom.ui.home.HomeScreen
import com.pank.vsevodnom.ui.home.AppsScreen
import androidx.compose.material3.NavigationBarItem
import com.pank.vsevodnom.ui.todo.TodoScreen
import com.pank.vsevodnom.ui.money.MoneyScreen
import com.pank.vsevodnom.ui.power.PowerScreen
import com.pank.vsevodnom.ui.life.MoodScreen
import com.pank.vsevodnom.ui.life.CartScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Apps
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
        com.pank.vsevodnom.data.Net.init(this)
        AlarmScheduler.rescheduleAll(this)
        AlertWorker.schedule(this)
        com.pank.vsevodnom.data.AppScope.io.launch { com.pank.vsevodnom.data.HabitReminders.rescheduleAll(this@MainActivity) }
        pendingRoute.value = routeFrom(intent)

        setContent {
            AppTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute.value) {
                    pendingRoute.value?.let { nav.navigate(it) { launchSingleTop = true }; pendingRoute.value = null }
                }
                val back: () -> Unit = { if (!nav.popBackStack()) finish() }
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: "home"
                val tabs = listOf(
                    Triple("home", "Главная", androidx.compose.material.icons.Icons.Rounded.Home),
                    Triple("todo", "Дела", androidx.compose.material.icons.Icons.Rounded.Checklist),
                    Triple("money", "Финансы", androidx.compose.material.icons.Icons.Rounded.AccountBalanceWallet),
                    Triple("calendar?new={new}", "Календарь", androidx.compose.material.icons.Icons.Rounded.CalendarMonth),
                    Triple("apps", "Все", androidx.compose.material.icons.Icons.Rounded.Apps),
                )
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(com.pank.vsevodnom.ui.theme.Pal.Bg)) {
                val barVisible = tabs.any { it.first == route } && route != "money"  // у «Финансов» свой нижний бар
                val offline = !com.pank.vsevodnom.data.Net.online
                if (offline) com.pank.vsevodnom.ui.common.OfflineBanner()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).then(
                    // плашка уже заняла место под строкой состояния
                    if (offline) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else androidx.compose.ui.Modifier
                ).then(
                    // нижний бар сам учитывает системную панель — внутри экранов её отступ не нужен
                    if (barVisible) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars) else androidx.compose.ui.Modifier
                )) {
                NavHost(
                    nav, startDestination = "home",
                    enterTransition = { fadeIn() }, exitTransition = { fadeOut() }
                ) {
                    composable("home") { HomeScreen(open = { nav.navigate(it) }) }
                    composable("todo") { TodoScreen() }
                    composable("money") { MoneyScreen(back) }
                    composable("power") { PowerScreen(back) }
                    composable("apps") { AppsScreen(open = { nav.navigate(it) }) }
                    composable("mood") { MoodScreen(back) }
                    composable("cart?new={new}", arguments = listOf(navArgument("new") { type = NavType.IntType; defaultValue = 0 })) { CartScreen(back, it.arguments?.getInt("new") == 1) }
                    composable("clock/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) {
                        ClockScreen(back, it.arguments?.getInt("tab") ?: 0)
                    }
                    composable("weather") { WeatherScreen(back) }
                    composable("calc") { CalcScreen(back) }
                    composable("random") { RandomScreen(back) }
                    composable("weight?new={new}", arguments = listOf(navArgument("new") { type = NavType.IntType; defaultValue = 0 })) { WeightScreen(back, it.arguments?.getInt("new") == 1) }
                    composable("piggy") { PiggyScreen(back) }
                    composable("calendar?new={new}", arguments = listOf(navArgument("new") { type = NavType.IntType; defaultValue = 0 })) { CalendarScreen(back, it.arguments?.getInt("new") == 1) }
                    composable("alerts") { AlertsScreen(back) }
                    composable("markets") { MarketsScreen(back) }
                    composable("recorder") { RecorderScreen(back) }
                    composable("utils") { UtilsScreen(back) }
                    composable("files") { FilesScreen(back) }
                    composable("games") { GamesScreen(back) }
                    composable("passwords") { PasswordsScreen(back) }
                    composable("sms") { SmsScreen(back) }
                    composable("contacts") { ContactsScreen(back) }
                    composable("health") { HealthScreen(back, onOpenMap = { nav.navigate("map") }) }
                    composable("downloads") { DownloadsScreen(back) }
                    composable("map") { com.pank.vsevodnom.ui.map.MapScreen(back) }
                }
                }
                if (barVisible) androidx.compose.material3.NavigationBar(containerColor = com.pank.vsevodnom.ui.theme.Pal.Card) {
                    tabs.forEach { (r, title, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                val target = if (r.startsWith("calendar")) "calendar" else r
                                nav.navigate(target) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                            },
                            icon = { androidx.compose.material3.Icon(icon, title) },
                            label = { androidx.compose.material3.Text(title) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(indicatorColor = com.pank.vsevodnom.ui.theme.Pal.Lilac)
                        )
                    }
                }
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
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let(::extractUrl)?.let { SharedLink.url = it; return "downloads" }
        }
        return when (i?.getStringExtra("route")) {
            "downloads" -> "downloads"
            "passwords" -> "passwords"
            "clock" -> "clock/1"
            "weight" -> "weight"
            "calendar" -> "calendar"
            "alerts" -> "alerts"
            "recorder" -> "recorder"
            "sms" -> "sms"
            "todo" -> "todo"
            "power" -> "power"
            "map" -> "map"
            "money" -> "money"
            "contacts" -> "contacts"
            else -> if (i?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" || i?.action == "android.intent.action.VIEW_PERMISSION_USAGE") "health" else null
        }
    }
}
