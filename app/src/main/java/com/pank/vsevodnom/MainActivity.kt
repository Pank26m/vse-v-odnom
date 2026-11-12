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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Map
import androidx.compose.foundation.layout.navigationBarsPadding
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
        com.pank.vsevodnom.alarm.AlertLiveService.sync(this)
        com.pank.vsevodnom.data.AppScope.io.launch { com.pank.vsevodnom.data.HabitReminders.rescheduleAll(this@MainActivity) }
        pendingRoute.value = routeFrom(intent)

        setContent {
            // размеры системных панелей с окна приложения — для полноэкранных окон (там система отдаёт нули)
            val rootInsets = androidx.compose.runtime.remember { com.pank.vsevodnom.ui.common.RootInsets() }
            val iTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val iBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val iIme = androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            androidx.compose.runtime.SideEffect { rootInsets.top = iTop; rootInsets.bottom = iBottom; rootInsets.ime = iIme }
            androidx.compose.runtime.CompositionLocalProvider(com.pank.vsevodnom.ui.common.LocalRootInsets provides rootInsets) {
            AppTheme {
                val nav = rememberNavController()
                val back: () -> Unit = { if (!nav.popBackStack()) finish() }
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: "home"
                val tabs = listOf(
                    Triple("home", "Главная", androidx.compose.material.icons.Icons.Rounded.Home),
                    Triple("todo", "ToDo", androidx.compose.material.icons.Icons.Rounded.Checklist),
                    Triple("money", "Финансы", androidx.compose.material.icons.Icons.Rounded.AccountBalanceWallet),
                    Triple("map", "Карта", androidx.compose.material.icons.Icons.Rounded.Map),
                    Triple("apps", "Все", androidx.compose.material.icons.Icons.Rounded.Apps),
                )
                val tabRoutes = tabs.map { it.first }.toSet()
                // Переход: вкладки нижнего бара всегда открываются «с главной» (назад — сразу на главную),
                // поэтому из сводки или «Всех» можно уйти в ToDo и потом нажать любую другую вкладку.
                val go: (String) -> Unit = { r0 ->
                    val r = if (r0 == "calendar") "todo" else r0
                    when {
                        // уже на карте: не пересоздаём экран (раньше камера сбрасывалась на север) — только вернуть кнопки после «Рамки»
                        r == "map" && route == "map" -> com.pank.vsevodnom.ui.map.MapUi.reselect++
                        r == "home" -> nav.popBackStack("home", false)
                        r in tabRoutes -> nav.navigate(r) { popUpTo("home"); launchSingleTop = true }
                        else -> nav.navigate(r)
                    }
                }
                LaunchedEffect(com.pank.vsevodnom.ui.map.MapUi.openTodo) {
                    com.pank.vsevodnom.ui.map.MapUi.openTodo?.let { com.pank.vsevodnom.ui.todo.TodoNav.target = it; com.pank.vsevodnom.ui.map.MapUi.openTodo = null; go("todo") }
                }
                LaunchedEffect(com.pank.vsevodnom.ui.map.MapUi.request) { if (com.pank.vsevodnom.ui.map.MapUi.request != null && route != "map") go("map") }
                LaunchedEffect(com.pank.vsevodnom.ui.common.AppNav.route) { com.pank.vsevodnom.ui.common.AppNav.route?.let { com.pank.vsevodnom.ui.common.AppNav.route = null; go(it) } }
                LaunchedEffect(pendingRoute.value) {
                    pendingRoute.value?.let { go(it); pendingRoute.value = null }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(com.pank.vsevodnom.ui.theme.Pal.Bg)) {
                val barVisible = route in tabRoutes
                val playerVisible = com.pank.vsevodnom.data.Player.current != null
                val offline = !com.pank.vsevodnom.data.Net.online
                if (offline) com.pank.vsevodnom.ui.common.OfflineBanner()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).then(
                    // плашка уже заняла место под строкой состояния
                    if (offline) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else androidx.compose.ui.Modifier
                ).then(
                    // нижний бар или плеер сами учитывают системную панель — внутри экранов её отступ не нужен
                    if (barVisible || playerVisible) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars) else androidx.compose.ui.Modifier
                )) {
                NavHost(
                    nav, startDestination = "home",
                    enterTransition = { fadeIn() }, exitTransition = { fadeOut() }
                ) {
                    composable("home") { HomeScreen(open = go) }
                    composable("todo") { TodoScreen() }
                    composable("money") { MoneyScreen(back) }
                    composable("power") { PowerScreen(back) }
                    composable("apps") { AppsScreen(open = go) }
                    composable("mood") { MoodScreen(back) }
                    composable("cart?new={new}", arguments = listOf(navArgument("new") { type = NavType.IntType; defaultValue = 0 })) { CartScreen(back, it.arguments?.getInt("new") == 1) }
                    composable("clock/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) {
                        ClockScreen(back, it.arguments?.getInt("tab") ?: 0)
                    }
                    composable("weather") { WeatherScreen(back) }
                    composable("calc") { CalcScreen(back) }
                    // клавиатура (Inkwell/FlorisBoard) — свои настройки в отдельном окне и процессе :ime
                    composable("keyboard") { com.pank.vsevodnom.ui.keyboard.KeyboardHub(back) }
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
                    composable("sms") { com.pank.vsevodnom.ui.contacts.PhoneScreen(back, 1) }
                    composable("phone?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { com.pank.vsevodnom.ui.contacts.PhoneScreen(back, it.arguments?.getInt("tab") ?: 0) }
                    composable("contacts") { com.pank.vsevodnom.ui.contacts.PhoneScreen(back, 2) }
                    composable("health") { HealthScreen(back, onOpenMap = { nav.navigate("map") }) }
                    composable("downloads") { DownloadsScreen(back) }
                    composable("map") { com.pank.vsevodnom.ui.map.MapScreen(back) }
                    composable("commands") { com.pank.vsevodnom.ui.commands.CommandsScreen(back) }
                    composable("music") { com.pank.vsevodnom.ui.music.MusicScreen(back) }
                    composable("codes") { com.pank.vsevodnom.ui.codes.CodesScreen(back) }
                    composable("pdf") { com.pank.vsevodnom.ui.files.PdfScreen(back) }
                    composable("archive") { com.pank.vsevodnom.ui.files.ArchiveScreen(back) }
                    composable("cards") { com.pank.vsevodnom.ui.learn.CardsScreen(back) }
                    composable("english") { com.pank.vsevodnom.ui.learn.EnglishScreen(back) }
                }
                }
                if (playerVisible) com.pank.vsevodnom.ui.music.PlayerBar(if (barVisible) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.navigationBarsPadding())
                if (barVisible) androidx.compose.material3.NavigationBar(containerColor = com.pank.vsevodnom.ui.theme.Pal.Card) {
                    tabs.forEach { (r, title, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = { go(r) },
                            icon = { androidx.compose.material3.Icon(icon, title) },
                            label = { androidx.compose.material3.Text(title) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(indicatorColor = com.pank.vsevodnom.ui.theme.Pal.Lilac)
                        )
                    }
                }
                }
                com.pank.vsevodnom.ui.music.FullPlayer()
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
            return "todo"
        }
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (com.pank.vsevodnom.data.IncomingGeo.isMapLink(t)) { com.pank.vsevodnom.data.IncomingGeo.handleLink(applicationContext, t); return "map" }
            extractUrl(t)?.let { SharedLink.url = it; return "downloads" }
        }
        if (i?.action == Intent.ACTION_VIEW && i.data != null && i.getStringExtra("route") == null) {
            val d = i.data!!
            if (d.scheme == "geo" || d.host?.contains("google") == true || d.host == "maps.app.goo.gl") { com.pank.vsevodnom.data.IncomingGeo.handleLink(applicationContext, d.toString()); return "map" }
            if (d.scheme == "content" || d.scheme == "file") { com.pank.vsevodnom.data.IncomingGeo.handleFile(applicationContext, d); return "map" }
        }
        // ярлык команды с рабочего стола: выполняем и остаёмся на главной
        i?.getStringExtra("command")?.let { id ->
            com.pank.vsevodnom.data.Commands.all(this).firstOrNull { it.id == id }?.let { c -> com.pank.vsevodnom.data.AppScope.io.launch { com.pank.vsevodnom.data.Commands.run(applicationContext, c) } }
            i.removeExtra("command")
            return null
        }
        i?.getStringExtra("route")?.let { r -> if (r in setOf("music", "codes", "pdf", "cards", "english", "commands", "games", "calc", "recorder", "home", "weather", "health", "clock/0")) return r }
        return when (i?.getStringExtra("route")) {
            "downloads" -> "downloads"
            "passwords" -> "passwords"
            "clock" -> "clock/1"
            "weight" -> "weight"
            "calendar" -> "todo"
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
