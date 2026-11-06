package com.pank.vsevodnom.ui.capri

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.alarm.AlarmScheduler
import com.pank.vsevodnom.alarm.TimerState
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.data.schedule.CalendarRepo
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.markets.fmtPct
import com.pank.vsevodnom.ui.markets.fmtPrice
import com.pank.vsevodnom.ui.piggy.money
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.weather.WeatherRepo
import com.pank.vsevodnom.ui.weather.fmtT
import com.pank.vsevodnom.ui.weather.tips
import com.pank.vsevodnom.ui.weight.fmtKg
import com.pank.vsevodnom.widget.ScheduleWidget
import com.pank.vsevodnom.widget.dayTitle
import com.pank.vsevodnom.widget.row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Msg(val fromUser: Boolean, val text: String, val at: Long = System.currentTimeMillis())

object Chat {
    val msgs = mutableStateListOf<Msg>()
    private var loaded = false
    private fun p(ctx: Context) = ctx.getSharedPreferences("capri", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        if (loaded) return; loaded = true
        runCatching {
            val a = JSONArray(p(ctx).getString("chat", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { msgs += Msg(it.getBoolean("u"), it.getString("t"), it.getLong("a")) }
        }
    }
    fun add(ctx: Context, m: Msg) {
        msgs += m
        while (msgs.size > 150) msgs.removeAt(0)
        val a = JSONArray(); msgs.forEach { a.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("a", it.at)) }
        p(ctx).edit().putString("chat", a.toString()).apply()
    }
    fun clear(ctx: Context) { msgs.clear(); p(ctx).edit().remove("chat").apply() }
}

private val DM = DateTimeFormatter.ofPattern("HH:mm")

/** Выполняет команду и возвращает ответ Капри. */
object CapriBrain {
    val HELP = """Я умею:
• «добавь встречу с Олегом завтра в 15:30 на 2 часа, напомни за 30 минут, важно»
• «что у меня завтра?» · «какие пары в понедельник?»
• «что по тревогам?» · «погода» · «курс доллара» / «биткоин»
• «вес 82,5» · «настроение хорошо»
• «таймер 10 минут» · «будильник на 7:30»
• «добавь в корзину» + ссылка (Prom, Rozetka, AliExpress…)
• «отложи 500 на отпуск»
• «зови меня …»"""

    suspend fun run(ctx: Context, text: String): String = withContext(Dispatchers.IO) {
        runCatching { exec(ctx, CapriParser.parse(text)) }.getOrElse { "Что-то пошло не так: ${it.message}" }
    }

    private suspend fun exec(ctx: Context, c: Cmd): String {
        val db = AppDb.get(ctx)
        val name = UserPrefs.name(ctx)
        return when (c) {
            Cmd.Hello -> "Привет, $name! " + summary(ctx)
            Cmd.Help -> HELP
            is Cmd.Name -> { UserPrefs.setName(ctx, c.name); "Хорошо, буду звать тебя ${c.name}." }
            is Cmd.Event -> {
                val z = ZoneId.systemDefault()
                val (s, e) = if (c.start == null) c.date.atStartOfDay(z) to c.date.plusDays(1).atStartOfDay(z) else c.start.atZone(z) to c.start.atZone(z).plusMinutes(c.minutes)
                val ev = CalEvent(title = c.title, startMillis = s.toInstant().toEpochMilli(), endMillis = e.toInstant().toEpochMilli(), allDay = c.start == null,
                    tint = if (c.important) 5 else 3, note = if (c.important) "Важно" else "")
                db.schedule().upsertEvent(ev)
                ScheduleWidget.refresh(ctx)
                val remind = c.remindBefore ?: if (c.important && c.start != null) 30 else null
                if (remind != null && c.start != null) EventReminder.schedule(ctx, ev.startMillis, c.title, ev.startMillis - remind * 60_000)
                buildString {
                    append("Записала: «${c.title}» — ${dayTitle(c.date).lowercase()}")
                    if (c.start != null) append(", ${c.start.toLocalTime().format(DM)}–${e.toLocalTime().format(DM)}") else append(", весь день")
                    if (c.important) append(" · важное")
                    if (remind != null && c.start != null) append(". Напомню за ${if (remind >= 60) "${remind / 60} ч" else "$remind мин"}")
                    append('.')
                }
            }
            is Cmd.Cart -> {
                val id = db.life().upsertCart(CartItem(url = c.url, title = c.url.substringAfter("//").substringBefore('/'), note = c.note))
                val info = runCatching { ProductInfo.fetch(c.url) }.getOrNull()
                if (info?.title != null) {
                    db.life().upsertCart(CartItem(id = id, url = c.url, title = info.title, price = info.price, currency = info.currency ?: "", image = info.image ?: "", store = info.store, note = c.note))
                    "Добавила в корзину: ${info.title}" + (info.price?.let { " — ${fmtPrice(it)} ${info.currency ?: ""}".trimEnd() } ?: "") + "."
                } else "Добавила ссылку в корзину. Название магазин не отдал — можно дописать вручную в «Корзине»."
            }
            Cmd.Alerts -> {
                val st = runCatching { AlertsApi.fetch(ctx) }.getOrNull() ?: return "Не получилось узнать про тревоги — нет связи."
                com.pank.vsevodnom.ui.alerts.AlertCache.state = st
                val (_, t) = com.pank.vsevodnom.ui.alerts.kyivSummary(st)
                val other = AlertsApi.OBLASTS.count { st.of(it) != AlertLevel.NONE }
                "$t. По Украине тревога в $other областях."
            }
            Cmd.Weather -> {
                WeatherRepo.ensureDefault(ctx)
                val city = db.cities().weatherOnce().firstOrNull() ?: return "Нет городов в погоде."
                val w = runCatching { WeatherRepo.refresh(ctx, city) }.getOrNull() ?: WeatherRepo.cached(city) ?: return "Погода сейчас недоступна."
                val d = w.days.firstOrNull()
                "${city.name}: ${fmtT(w.temp)}, ${wmo(w.code).first.lowercase()}, ощущается ${fmtT(w.feels)}." +
                    (d?.let { " Днём до ${fmtT(it.tMax)}, ночью ${fmtT(it.tMin)}." } ?: "") +
                    tips(w).takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " ") { "$it." }.orEmpty()
            }
            is Cmd.Rates -> {
                val l = c.what
                val syms = buildList {
                    if (Regex("доллар|долар|usd").containsMatchIn(l)) add("UAH=X")
                    if (Regex("евро|євро|eur").containsMatchIn(l)) add("EURUAH=X")
                    if (Regex("биткоин|біткоїн|bitcoin|btc|крипт").containsMatchIn(l)) add("BTC-USD")
                    if (Regex("s&p|индекс|індекс|акци").containsMatchIn(l)) { add("^GSPC"); add("^SPG1200") }
                }.ifEmpty { MarketsApi.watchlist(ctx).take(4) }
                syms.mapNotNull { s -> runCatching { MarketsApi.quote(s) }.getOrNull()?.let { q -> "${MarketsApi.nameOf(ctx, s)}: ${fmtPrice(q.price)}" + (q.changePct?.let { " (${fmtPct(it)})" } ?: "") } }
                    .ifEmpty { listOf("Котировки сейчас недоступны.") }.joinToString("\n")
            }
            is Cmd.Weight -> {
                val prev = db.weight().all().first().lastOrNull()
                val day = LocalDate.now().toEpochDay()
                db.weight().upsert(WeightEntry(id = db.weight().byDay(day)?.id ?: 0, epochDay = day, kg = c.kg))
                "Записала ${fmtKg(c.kg)} кг." + (prev?.takeIf { it.epochDay != day }?.let { val d = c.kg - it.kg; " С прошлого раза ${if (d > 0) "+" else if (d < 0) "−" else ""}${fmtKg(kotlin.math.abs(d))} кг." } ?: "")
            }
            is Cmd.Mood -> {
                if (c.level == 0) "Как оценишь: супер, хорошо, нормально, плохо или ужасно?"
                else { db.life().addMood(MoodEntry(level = c.level, note = c.note.take(200))); "Записала в дневник: ${MOOD_NAMES[c.level - 1].lowercase()}." + if (c.level <= 2) " Если хочешь — расскажи, что случилось." else "" }
            }
            is Cmd.Timer -> {
                withContext(Dispatchers.Main) { TimerState.cancel(ctx); TimerState.setTotal(c.seconds * 1000); TimerState.start(ctx) }
                "Таймер на ${if (c.seconds >= 60) "${c.seconds / 60} мин" else "${c.seconds} с"} пошёл."
            }
            is Cmd.Alarm -> {
                val a = AlarmEntity(hour = c.time.hour, minute = c.time.minute, daysMask = 0, label = "От Капри")
                val id = db.alarms().upsert(a)
                AlarmScheduler.schedule(ctx, a.copy(id = id))
                "Будильник на ${c.time.format(DM)}" + (if (c.date != null && c.date.isAfter(LocalDate.now().plusDays(1))) " — ставлю на ближайшие ${c.time.format(DM)}, на дальние даты будильник пока не умеет." else " поставила.")
            }
            is Cmd.Lessons -> {
                val l = db.schedule().lessonsSync(c.date.toEpochDay(), c.date.toEpochDay())
                val al = db.schedule().aliasesSync().associateBy { it.abbr }
                if (l.isEmpty()) "${dayTitle(c.date)}: пар нет."
                else "${dayTitle(c.date)}:\n" + l.joinToString("\n") { "${it.start} ${al[it.subject]?.fullName?.ifBlank { null } ?: it.subject} [${it.kind}] · ${it.room}" }
            }
            is Cmd.Agenda -> {
                val a = CalendarRepo.agendaSync(ctx, c.date, c.date)
                if (a.isEmpty()) "${dayTitle(c.date)}: ничего не запланировано."
                else "${dayTitle(c.date)}:\n" + a.joinToString("\n") { val r = it.row(); "${r.time.replace('\n', '–')} ${r.title}" }
            }
            is Cmd.Piggy -> {
                val goals = db.piggy().goals().first()
                val g = goals.firstOrNull { c.goal.isNotBlank() && it.goal.title.contains(c.goal.take(5), true) } ?: goals.singleOrNull()
                    ?: return "В какую копилку? У тебя: ${goals.joinToString(", ") { it.goal.title }.ifBlank { "копилок пока нет" }}."
                db.piggy().addTx(PiggyTx(goalId = g.goal.id, amount = c.amount, note = "через Капри"))
                "Добавила ${money(c.amount)} в «${g.goal.title}». Теперь там ${money(g.saved + c.amount)}" + (if (g.goal.target > 0) " из ${money(g.goal.target)}." else ".")
            }
            is Cmd.Unknown -> "Не совсем поняла. Напиши «что ты умеешь» — покажу команды."
        }
    }

    /** Короткая сводка для приветствия. */
    suspend fun summary(ctx: Context): String {
        val db = AppDb.get(ctx)
        val today = LocalDate.now()
        val lessons = db.schedule().lessonsSync(today.toEpochDay(), today.toEpochDay()).filter { LocalTime.parse(it.end) > LocalTime.now() }
        val events = CalendarRepo.agendaSync(ctx, today, today).filter { it !is com.pank.vsevodnom.data.schedule.Agenda.Pair }
        val parts = ArrayList<String>()
        if (lessons.isNotEmpty()) parts += "Сегодня ещё ${lessons.size} пар(ы), первая в ${lessons.first().start}"
        if (events.isNotEmpty()) parts += "событий: ${events.size}"
        AlertCache().let { st -> st?.let { parts += com.pank.vsevodnom.ui.alerts.kyivSummary(it).second.lowercase() } }
        return if (parts.isEmpty()) "Сегодня спокойно, планов нет." else parts.joinToString(", ") + "."
    }

    private fun AlertCache() = com.pank.vsevodnom.ui.alerts.AlertCache.state
}

val MOOD_NAMES = listOf("Ужасно", "Плохо", "Нормально", "Хорошо", "Супер")

// ================= Аватар =================

/** Собственный персонаж «Капри» (оригинальный рисунок) или картинка, выбранная пользователем. */
@Composable
fun CapriAvatar(size: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val uri = remember { mutableStateOf(UserPrefs.avatar(ctx)) }
    val bmp = remember(uri.value) {
        if (uri.value.isBlank()) null else runCatching {
            ctx.contentResolver.openInputStream(Uri.parse(uri.value))?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }.getOrNull()
    }
    Box(modifier.size(size.dp).clip(CircleShape)) {
        if (bmp != null) Image(bmp.asImageBitmap(), "Капри", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            drawRect(Brush.linearGradient(listOf(Color(0xFFE7E1F7), Color(0xFFDCEBFA))))
            val hair = Color(0xFF2E2A4F)
            // волосы сзади (каре)
            drawRoundRect(hair, Offset(w * 0.18f, h * 0.2f), Size(w * 0.64f, h * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
            // шея и плечи
            drawRect(Color(0xFFF6D9C4), Offset(w * 0.43f, h * 0.66f), Size(w * 0.14f, h * 0.12f))
            drawPath(Path().apply { moveTo(w * 0.12f, h); quadraticBezierTo(w * 0.18f, h * 0.76f, w / 2, h * 0.76f); quadraticBezierTo(w * 0.82f, h * 0.76f, w * 0.88f, h); close() }, Color(0xFF6A55A8))
            drawLine(Color(0xFF7FE3F0), Offset(w * 0.28f, h * 0.9f), Offset(w * 0.72f, h * 0.9f), w * 0.015f)
            // лицо
            drawOval(Color(0xFFFFE6D3), Offset(w * 0.3f, h * 0.3f), Size(w * 0.4f, h * 0.44f))
            // чёлка
            drawPath(Path().apply {
                moveTo(w * 0.24f, h * 0.45f); quadraticBezierTo(w * 0.22f, h * 0.18f, w / 2, h * 0.17f); quadraticBezierTo(w * 0.78f, h * 0.18f, w * 0.76f, h * 0.45f)
                lineTo(w * 0.7f, h * 0.37f); lineTo(w * 0.62f, h * 0.42f); lineTo(w * 0.55f, h * 0.36f); lineTo(w * 0.47f, h * 0.42f); lineTo(w * 0.4f, h * 0.36f); lineTo(w * 0.32f, h * 0.42f); close()
            }, hair)
            // глаза
            for (sx in listOf(0.41f, 0.59f)) {
                drawOval(Color.White, Offset(w * (sx - 0.055f), h * 0.47f), Size(w * 0.11f, h * 0.09f))
                drawCircle(Color(0xFF3FB8C9), w * 0.04f, Offset(w * sx, h * 0.515f))
                drawCircle(Color(0xFF1B3B55), w * 0.02f, Offset(w * sx, h * 0.515f))
                drawCircle(Color.White, w * 0.012f, Offset(w * (sx - 0.015f), h * 0.5f))
            }
            // улыбка и неоновая линия импланта
            drawArc(Color(0xFFB65E2E), 20f, 140f, false, Offset(w * 0.45f, h * 0.6f), Size(w * 0.1f, h * 0.05f), style = Stroke(w * 0.012f))
            drawLine(Color(0xFF7FE3F0), Offset(w * 0.62f, h * 0.6f), Offset(w * 0.69f, h * 0.56f), w * 0.012f)
            drawCircle(Color(0xFF7FE3F0), w * 0.012f, Offset(w * 0.69f, h * 0.56f))
        }
    }
}

// ================= Чат =================

@Composable
fun CapriScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var avatarVer by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        Chat.load(ctx)
        if (Chat.msgs.isEmpty()) Chat.add(ctx, Msg(false, "Привет, ${UserPrefs.name(ctx)}! Я Капри. Пиши, что сделать — добавлю событие, проверю тревоги, погоду, запишу вес или настроение. «Что ты умеешь» — список команд."))
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; UserPrefs.setAvatar(ctx, u.toString()); avatarVer++ }
    }
    fun send(t: String) {
        if (t.isBlank() || busy) return
        Chat.add(ctx, Msg(true, t.trim())); input = ""; busy = true
        scope.launch { val r = CapriBrain.run(ctx, t); Chat.add(ctx, Msg(false, r)); busy = false }
    }
    val list = rememberLazyListState()
    LaunchedEffect(Chat.msgs.size) { if (Chat.msgs.isNotEmpty()) list.animateScrollToItem(Chat.msgs.size - 1) }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { RoundIconButton(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack); Spacer(Modifier.width(10.dp)) }
            key(avatarVer) { CapriAvatar(48, Modifier.clickable { menu = true }) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Капри", style = MaterialTheme.typography.titleLarge)
                Text(if (busy) "печатает…" else "твой ассистент", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton({ menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Сменить аватар") }, { menu = false; picker.launch(arrayOf("image/*")) })
                    if (UserPrefs.avatar(ctx).isNotBlank()) DropdownMenuItem({ Text("Вернуть стандартный") }, { menu = false; UserPrefs.setAvatar(ctx, ""); avatarVer++ })
                    DropdownMenuItem({ Text("Очистить переписку") }, { menu = false; Chat.clear(ctx) })
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Chat.msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(if (m.fromUser) Pal.Lilac else Pal.Card).padding(12.dp))
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Что по тревогам?", "Какие пары завтра?", "Погода", "Что у меня сегодня?", "Курс доллара", "Что ты умеешь?")) { s -> Chip(s, false, { send(s) }) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Напиши Капри…") }, shape = RoundedCornerShape(24.dp), maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
            Spacer(Modifier.width(8.dp))
            RoundIconButton(Icons.AutoMirrored.Rounded.Send, "Отправить", { send(input) }, bg = Pal.Ink, tint = Color.White, size = 52.dp)
        }
    }
}
