package com.pank.vsevodnom.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.map.FullScreen
import com.pank.vsevodnom.ui.map.MenuRow
import com.pank.vsevodnom.ui.map.SwitchRow
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch

/** «Мой профиль»: здоровье, транспорт. Хранится в настройках «profile» (ключ → строка), уходит в резервную копию. */
object Profile {
    private fun p(ctx: Context) = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
    fun get(ctx: Context, k: String, def: String = "") = p(ctx).getString(k, def) ?: def
    fun set(ctx: Context, k: String, v: String) = p(ctx).edit().putString(k, v.trim()).apply()

    class Field(val key: String, val label: String, val numeric: Boolean = false, val def: String = "", val choices: List<String> = emptyList())
    class Section(val id: String, val title: String, val icon: ImageVector, val fields: List<Field>, val model: List<String> = emptyList(), val parts: String = "")

    val SECTIONS = listOf(
        Section("me", "Обо мне", Icons.Rounded.Person, listOf(
            Field("surname", "Фамилия"), Field("birthday", "День рождения (ДД.ММ.ГГГГ)"), Field("sex", "Пол", choices = listOf("мужской", "женский")),
            Field("phone", "Телефон"), Field("email", "Почта"), Field("city", "Город"),
        )),
        Section("health", "Здоровье", Icons.Rounded.Favorite, listOf(
            Field("height", "Рост, см", true), Field("blood", "Группа крови", choices = listOf("O(I) Rh+", "O(I) Rh−", "A(II) Rh+", "A(II) Rh−", "B(III) Rh+", "B(III) Rh−", "AB(IV) Rh+", "AB(IV) Rh−")),
            Field("allergy", "Аллергии"), Field("chronic", "Хронические болезни"), Field("meds", "Постоянные лекарства"),
            Field("emergency", "Экстренный контакт (имя, телефон)"), Field("goal_weight", "Цель по весу, кг", true),
        )),
        Section("bike", "Мой велосипед", Icons.AutoMirrored.Rounded.DirectionsBike, listOf(
            Field("bike_type", "Тип", choices = listOf("шоссейный", "гравийный", "горный (MTB)", "городской", "туристический", "BMX", "складной", "электро")),
            Field("bike_brand", "Марка"), Field("bike_model", "Модель"), Field("bike_year", "Год", true),
            Field("bike_frame", "Размер рамы (S/M/L или см)"), Field("bike_frame_mat", "Материал рамы", choices = listOf("алюминий", "сталь", "карбон", "титан")),
            Field("bike_wheels", "Колёса", choices = listOf("20\"", "24\"", "26\"", "27.5\"", "28\" / 700c", "29\"")), Field("bike_tires", "Покрышки (размер)"),
            Field("bike_front", "Звёзды спереди"), Field("bike_rear", "Звёзды сзади (кассета)"), Field("bike_shifters", "Переключатели (группа)"),
            Field("bike_brakes", "Тормоза", choices = listOf("ободные (V-brake)", "механические дисковые", "гидравлические дисковые", "клещевые")),
            Field("bike_fork", "Вилка (амортизация, ход)"), Field("bike_weight", "Вес, кг", true), Field("bike_serial", "Серийный номер рамы"), Field("bike_notes", "Заметки (обслуживание, цепь, покрышки)"),
        ), model = listOf("bike_brand", "bike_model", "bike_year"), parts = "запчасти"),
        Section("moped", "Мой мопед", Icons.Rounded.TwoWheeler, listOf(
            Field("moped_brand", "Марка", def = "Yamaha"), Field("moped_model", "Модель", def = "Gear 4T"), Field("moped_year", "Год", true),
            Field("moped_engine", "Двигатель", def = "49 см³, 4-тактный"), Field("moped_plate", "Номер"), Field("moped_vin", "VIN / номер рамы"),
            Field("moped_km", "Пробег, км", true), Field("moped_oil", "Замена масла (дата, км)"), Field("moped_notes", "Заметки"),
        ), model = listOf("moped_brand", "moped_model"), parts = "запчасти"),
        Section("car", "Моё авто", Icons.Rounded.DirectionsCar, listOf(
            Field("car_brand", "Марка"), Field("car_model", "Модель"), Field("car_year", "Год", true), Field("car_plate", "Номер"), Field("car_vin", "VIN"),
            Field("car_engine", "Двигатель (объём, мощность)"), Field("car_fuel", "Топливо", choices = listOf("бензин", "дизель", "газ/бензин", "гибрид", "электро")),
            Field("car_km", "Пробег, км", true), Field("car_insurance", "Страховка до"), Field("car_service", "ТО / замена масла"), Field("car_notes", "Заметки"),
        ), model = listOf("car_brand", "car_model", "car_year"), parts = "запчасти"),
    )
}

/** Настройки приложения на весь экран: профиль, уведомления, обращение. */
@Composable
fun AppSettingsScreen(onDismiss: () -> Unit, onName: (String) -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<String?>(null) }
    when (page) {
        null -> FullScreen("Настройки", onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var n by remember { mutableStateOf(UserPrefs.name(ctx)) }
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Как к тебе обращаться", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                    AppTextField(n, { n = it; UserPrefs.setName(ctx, it.trim().ifBlank { "Миша" }); onName(it.trim().ifBlank { "Миша" }) }, "Имя")
                }
                SectionLabel("Мой профиль")
                Profile.SECTIONS.forEach { s ->
                    val filled = s.fields.count { Profile.get(ctx, it.key, it.def).isNotBlank() }
                    MenuRow(s.icon, s.title, sub = "заполнено $filled из ${s.fields.size}") { page = s.id }
                }
                SectionLabel("Приложение")
                MenuRow(Icons.Rounded.Key, "Ключи и сервисы", sub = "GLMap, Google, Telegram (кино), EasyWay, Waze") { page = "keys" }
                MenuRow(Icons.Rounded.Backup, "Резервная копия", sub = "всё приложение в один файл и обратно") { page = "backup" }
                MenuRow(Icons.Rounded.Notifications, "Уведомления", sub = "ToDo, привычки, тревоги, свет, фокус, СМС, звонки…") { page = "notif" }
                MenuRow(Icons.Rounded.Settings, "Системные настройки приложения", sub = "разрешения, батарея, хранилище") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                }
            }
        }
        "notif" -> NotificationsPage { page = null }
        "keys" -> com.pank.vsevodnom.ui.map.KeysPage({ com.pank.vsevodnom.ui.map.MapUi.engineTick++ }) { page = null }
        "backup" -> FullScreen("Резервная копия", { page = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp)) { BackupCard() }
        }
        else -> Profile.SECTIONS.firstOrNull { it.id == page }?.let { ProfilePage(it) { page = null } } ?: run { page = null }
    }
}

@Composable
private fun ProfilePage(s: Profile.Section, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vals = remember(s.id) { mutableStateMapOf<String, String>().apply { s.fields.forEach { put(it.key, Profile.get(ctx, it.key, it.def)) } } }
    val db = remember { AppDb.get(ctx) }
    val weights by db.weight().all().collectAsState(initial = emptyList())
    FullScreen(s.title, onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.id == "health") AppCard(Modifier.fillMaxWidth()) {
                // взвешивания — по понедельникам, из сводки; здесь только последнее
                Text("Последнее взвешивание: " + (weights.lastOrNull()?.let { "${com.pank.vsevodnom.ui.weight.fmtKg(it.kg)} кг, " +
                    java.time.LocalDate.ofEpochDay(it.epochDay).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.forLanguageTag("ru"))) } ?: "ещё не было"),
                    style = MaterialTheme.typography.titleMedium)
                Text("Взвешивание по понедельникам — напоминание приходит в сводку.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                val h = vals["height"]?.toDoubleOrNull(); val w = weights.lastOrNull()?.kg
                if (h != null && h > 50 && w != null) Text("Индекс массы тела: %.1f".format(w / ((h / 100) * (h / 100))), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            if (s.id == "health") {
                var goalEdit by remember { mutableStateOf(false) }
                var gTick by remember { mutableIntStateOf(0) }
                val kg = weights.lastOrNull()?.kg
                val manual = remember(gTick) { StepGoal.manual(ctx) }
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Цель шагов: " + fmtSteps(remember(gTick, kg, vals["height"], vals["birthday"]) { StepGoal.value(ctx, kg) }), style = MaterialTheme.typography.titleMedium)
                            Text(if (manual != null) "своя (автоматически было бы ${fmtSteps(StepGoal.auto(ctx, kg))})" else "автоматически — по росту, весу, возрасту; меняется по мере похудения",
                                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        IconButton({ goalEdit = true }) { Icon(Icons.Rounded.Edit, "Изменить цель шагов") }
                    }
                }
                if (goalEdit) StepGoalDialog(kg) { goalEdit = false; gTick++ }
            }
            s.fields.forEach { f ->
                if (f.choices.isNotEmpty()) {
                    Text(f.label, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.choices.forEach { c ->
                            val sel = vals[f.key] == c
                            Text(c, style = MaterialTheme.typography.labelLarge, color = if (sel) Pal.SkyInk else Pal.Ink,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) Pal.Sky else Pal.Card)
                                    .clickable { val v = if (sel) "" else c; vals[f.key] = v; Profile.set(ctx, f.key, v) }.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                } else AppTextField(vals[f.key].orEmpty(), { vals[f.key] = it; Profile.set(ctx, f.key, it) }, f.label,
                    keyboardOptions = if (f.numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
            }
            if (s.model.isNotEmpty()) {
                val model = s.model.mapNotNull { vals[it]?.takeIf { v -> v.isNotBlank() } }.joinToString(" ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PillButton("Найти модель", { search(ctx, "$model характеристики") }, Modifier.weight(1f), bg = Pal.Sky, fg = Pal.SkyInk)
                    PillButton("Найти ${s.parts}", { search(ctx, "$model ${s.parts} купить") }, Modifier.weight(1f), bg = Pal.Mint, fg = Pal.MintInk)
                }
                Text("Эти данные потом пригодятся: обслуживание по пробегу, подбор деталей, маршруты под транспорт.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }
    }
}

private fun search(ctx: Context, q: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q.trim())))) }

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun NotificationsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    FullScreen("Уведомления", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                NotifPrefs.TYPES.forEach { t ->
                    var on by remember(t.key) { mutableStateOf(NotifPrefs.on(ctx, t.key)) }
                    SwitchRow(t.title, t.sub, if (t.canOff) on else true, enabled = t.canOff) { on = it; NotifPrefs.set(ctx, t.key, it) }
                }
            }
            PillButton("Звук, вибрация, важность — в Android", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
            }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
            Text("Выключенные здесь уведомления приложение просто не показывает. Свет: время напоминания и «не беспокоить 23–08» — в разделе «Свет».",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}
