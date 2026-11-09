package com.pank.vsevodnom.ui.todo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

private val RU = java.util.Locale("ru")
val DATE_COLORS = listOf(Color(0xFFE5485D), Color(0xFFF2A33A), Color(0xFFF2C94C), Color(0xFF3FB27F), Color(0xFF4A86E8), Color(0xFF8E5BD8), Color(0xFF9E9E9E))
private val BASE_KINDS = listOf("birthday" to "День рождения", "holiday" to "Праздник", "anniversary" to "Годовщина")
/** Свои виды дат (вместо «Другое»): хранятся в prefs «dates»/kinds, в записи — kind = «c:Название». */
private object DateKinds {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("dates", android.content.Context.MODE_PRIVATE)
    fun custom(ctx: android.content.Context): List<String> = p(ctx).getStringSet("kinds", emptySet())!!.sorted()
    fun add(ctx: android.content.Context, name: String) = p(ctx).edit().putStringSet("kinds", (custom(ctx) + name).toSet()).apply()
    fun remove(ctx: android.content.Context, name: String) = p(ctx).edit().putStringSet("kinds", (custom(ctx) - name).toSet()).apply()
}
private var customKinds by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
private val KINDS: List<Pair<String, String>> get() = BASE_KINDS + customKinds.map { "c:$it" to it } + (if (customKinds.isEmpty()) listOf("other" to "Другое") else emptyList())
private fun kindName(k: String) = BASE_KINDS.firstOrNull { it.first == k }?.second ?: if (k.startsWith("c:")) k.removePrefix("c:") else "Другое"
private fun kindIcon(k: String) = when (k) { "birthday" -> Icons.Rounded.Cake; "holiday" -> Icons.Rounded.Celebration; "anniversary" -> Icons.Rounded.Favorite; else -> Icons.Rounded.Event }

fun nextOccurrence(b: Birthday, from: LocalDate = LocalDate.now()): LocalDate =
    runCatching { MonthDay.of(b.month, b.day.coerceAtMost(Month.of(b.month).maxLength())).atYear(from.year).let { if (it.isBefore(from)) it.plusYears(1) else it } }.getOrDefault(from.plusYears(1))

private enum class DSort(val title: String) { NEXT("Ближайшие"), NAME("По имени"), COLOR("По цвету"), KIND("По типу"), MONTH("По месяцам") }

// Необычные подарки для кубика
private val GIFTS = listOf(
    "Звезда, названная в честь человека, и карта неба на дату рождения", "Мастер-класс гончарного дела на двоих", "Полёт в аэротрубе",
    "Персональный кроссворд или квест по квартире с подсказками о вас", "Фотокнига «Наш год» из общих снимков", "Ужин в темноте (ресторан «в темноте»)",
    "Набор для выращивания бонсая", "Именной подкаст: записать поздравления от друзей", "Портрет в стиле любимого мультфильма от иллюстратора",
    "Билет на необычную экскурсию: по крышам, по подземельям, в планетарий", "Ночёвка в глэмпинге или домике на дереве", "Курс бариста или сомелье на один вечер",
    "Коробка «на каждый месяц»: 12 маленьких конвертов с сюрпризами", "Песня, написанная музыкантом под заказ", "Набор для создания свечей или мыла своими руками",
    "Виниловая пластинка года рождения человека", "Ящик со вкусами страны, куда он мечтает поехать", "Игра «Как хорошо ты меня знаешь» — карточки с вопросами о вас",
    "Терраиум — маленький лес в стекле", "Сертификат на прыжок с парашютом (в тандеме)", "Каток или картинг вдвоём + горячий шоколад",
    "Генеалогическое древо, оформленное в рамке", "Набор «Пикник в коробке» и плед", "Ночь в планетарии или выезд за город смотреть звездопад",
    "3D-пазл любимого здания из дерева", "Карта мест, где вы были вместе, с отметками", "Подписка на необычное: сыры, чай, носки, книги-сюрпризы",
    "Мастер-класс по кузнечному делу", "Индивидуальный парфюм в лаборатории ароматов", "Конверт «Открыть, когда…» — набор писем на разные случаи",
    "Фотосессия в стиле 90-х", "Кулинарный вечер: готовите блюдо его детства", "Набор для каллиграфии", "Банка «365 причин» — записки на каждый день",
    "Сертификат на день «да»: целый день делаете, что он хочет", "Аренда SUP-доски или байдарки на рассвете", "Брелок или кулон с отпечатком почерка",
    "Настольная игра под его интересы и вечер, чтобы в неё сыграть", "Урок полёта на параплане", "Кофе от обжарщика с его именем на пачке",
)
private val ROMANTIC = listOf("Повторить ваше первое свидание точь-в-точь", "Письмо в будущее: открыть через 5 лет", "Ночь в отеле с видом на город и завтрак в номер",
    "Коллаж «наши первые»: первое фото, первый билет, первая открытка", "Звуковая волна вашей песни, напечатанная на постере")

@Composable
fun DatesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val dates by dao.birthdays().collectAsState(initial = emptyList())
    val prefs = remember { ctx.getSharedPreferences("dates", Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(runCatching { DSort.valueOf(prefs.getString("sort", "NEXT")!!) }.getOrDefault(DSort.NEXT)) }
    var edit by remember { mutableStateOf<Birthday?>(null) }
    var color by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { customKinds = DateKinds.custom(ctx) }
    var kind by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var gift by remember { mutableStateOf<Birthday?>(null) }
    var giftAny by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf(false) }
    var showPublic by remember { mutableStateOf(prefs.getBoolean("public", true)) }
    val today = LocalDate.now()
    val shown = dates.filter { (color == null || it.tint == color) && (kind == null || it.kind == kind) }.let { l ->
        when (sort) {
            DSort.NEXT -> l.sortedBy { nextOccurrence(it) }
            DSort.NAME -> l.sortedBy { it.name.lowercase() }
            DSort.COLOR -> l.sortedWith(compareBy({ it.tint }, { nextOccurrence(it) }))
            DSort.KIND -> l.sortedWith(compareBy({ KINDS.indexOfFirst { k -> k.first == it.kind } }, { nextOccurrence(it) }))
            DSort.MONTH -> l.sortedWith(compareBy({ it.month }, { it.day }))
        }
    }
    var holHidden by remember { mutableStateOf(Holidays.hidden(ctx)) }
    var showHidden by remember { mutableStateOf(false) }
    val holidaysAll = Holidays.between(today, today.plusYears(1))
    val holidays = holidaysAll.filter { it.name !in holHidden }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Даты", onBack, actions = {
            RoundIconButton(Icons.Rounded.Casino, "Идея подарка", { giftAny = true }, bg = Pal.Butter, tint = Pal.ButterInk)
            RoundIconButton(Icons.Rounded.Contacts, "Из контактов", { contacts = true })
            Box {
                RoundIconButton(Icons.Rounded.SwapVert, "Сортировка", { sortMenu = true })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    DSort.entries.forEach { s -> DropdownMenuItem({ Text(s.title) }, { sort = s; prefs.edit().putString("sort", s.name).apply(); sortMenu = false }, trailingIcon = { if (sort == s) Icon(Icons.Rounded.Check, null) }) }
                }
            }
        }) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Все · ${dates.size}", color == null && kind == null, { color = null; kind = null })
                KINDS.filter { k -> dates.any { it.kind == k.first } }.forEach { (k, s) -> Chip(s, kind == k, { kind = if (kind == k) null else k }) }
                DATE_COLORS.indices.filter { i -> dates.any { it.tint == i } }.forEach { i ->
                    Box(Modifier.size(if (color == i) 32.dp else 26.dp).clip(CircleShape).background(DATE_COLORS[i]).clickable { color = if (color == i) null else i })
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { EmptyHint("Добавь дни рождения, годовщины и свои праздники — повторяются каждый год и попадают в сводку. Или нажми значок контактов.") }
                var lastHeader: String? = null
                shown.forEach { b ->
                    val n = nextOccurrence(b)
                    val header = when (sort) {
                        DSort.NEXT -> n.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)).replaceFirstChar { it.uppercase() }
                        DSort.MONTH -> Month.of(b.month).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.uppercase() }
                        DSort.KIND -> kindName(b.kind)
                        else -> null
                    }
                    if (header != null && header != lastHeader) { lastHeader = header; item(key = "h$header") { SectionLabel(header) } }
                    item(key = b.id) {
                        val days = Duration.between(today.atStartOfDay(), n.atStartOfDay()).toDays()
                        val col = DATE_COLORS[b.tint % DATE_COLORS.size]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable { edit = b }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(kindIcon(b.kind), null, tint = col) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.name, style = MaterialTheme.typography.titleSmall)
                                Text(n.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)) + (b.year?.let { " · ${if (b.kind == "birthday") "исполнится" else "будет"} ${n.year - it}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(when (days) { 0L -> "сегодня!"; 1L -> "завтра"; else -> "через $days дн." }, style = MaterialTheme.typography.labelLarge, color = if (days <= 7) col else Pal.Muted)
                                if (b.contactKey.isNotBlank()) Icon(Icons.Rounded.Person, "Из контактов", Modifier.size(14.dp), tint = Pal.Muted)
                            }
                            // «поздравил» — на ближайшую дату; отметка снимается повторным нажатием
                            var doneMark by remember(b.id, n) { mutableStateOf(BdayDone.isDone(ctx, b.id, n)) }
                            CheckDivider()
                            CheckCircle(doneMark, CHECK_GREY, CHECK_SIZE) { if (doneMark) BdayDone.unmark(ctx, b.id, n) else BdayDone.mark(ctx, b.id, n); doneMark = !doneMark }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Праздники Украины · скрыть ненужные — значок справа", style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.weight(1f))
                        Switch(showPublic, { showPublic = it; prefs.edit().putBoolean("public", it).apply() })
                    }
                }
                if (showPublic) {
                    items(holidays, key = { "hol${it.date}${it.name}" }) { h ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card.copy(alpha = 0.6f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(h.date.format(DateTimeFormatter.ofPattern("d MMM", RU)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(h.name, style = MaterialTheme.typography.bodyMedium)
                                Text(h.kind, style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                            }
                            IconButton({ Holidays.setHidden(ctx, h.name, true); holHidden = Holidays.hidden(ctx) }) { Icon(Icons.Rounded.VisibilityOff, "Скрыть праздник", tint = Pal.Muted) }
                        }
                    }
                    if (holHidden.isNotEmpty()) item {
                        TextButton({ showHidden = !showHidden }) { Text(if (showHidden) "Свернуть скрытые" else "Скрытые праздники · ${holHidden.size}") }
                    }
                    if (showHidden) items(holHidden.sorted(), key = { "hid$it" }) { name ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card.copy(alpha = 0.35f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = Modifier.weight(1f))
                            TextButton({ Holidays.setHidden(ctx, name, false); holHidden = Holidays.hidden(ctx) }) { Text("Вернуть") }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Birthday(name = "", day = today.dayOfMonth, month = today.monthValue) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Добавить дату", tint = Color.White)
        }
    }
    edit?.let { DateEditor(it, onGift = { gift = it }) { edit = null } }
    if (giftAny) GiftDialog(null) { giftAny = false }
    gift?.let { b -> GiftDialog(b) { gift = null } }
    if (contacts) ContactsImport(dates) { contacts = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEditor(b0: Birthday, onGift: (Birthday) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { customKinds = DateKinds.custom(ctx) }
    var b by remember { mutableStateOf(b0) }
    var year by remember { mutableStateOf(b0.year?.toString() ?: "") }
    var pick by remember { mutableStateOf(false) }
    val col = DATE_COLORS[b.tint % DATE_COLORS.size]
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDone)
                Text(if (b0.id == 0L) "Новая дата" else "Дата", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Check, "Сохранить", {
                    if (b.name.isNotBlank()) AppScope.io.launch { AppDb.get(ctx).todo().upsertBirthday(b.copy(name = b.name.trim(), year = year.toIntOrNull(), priority = 0)) }
                    onDone()
                }, bg = Pal.Ink, tint = Color.White)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(kindIcon(b.kind), null, tint = col) }
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(b.name, { b = b.copy(name = it) }, Modifier.weight(1f), textStyle = MaterialTheme.typography.titleLarge.copy(color = Pal.Ink), cursorBrush = SolidColor(ACCENT),
                            decorationBox = { inner -> if (b.name.isEmpty()) Text("Имя или название", style = MaterialTheme.typography.titleLarge, color = Color(0xFFB8BCC6)); inner() })
                    }
                }
                FormCard("Что это") {
                    var adding by remember { mutableStateOf<String?>(null) }
                    ChoiceGrid(BASE_KINDS + customKinds.map { "c:$it" to it } + ("+" to "＋ Свой тег"), { it == b.kind }, 2) { k ->
                        if (k == "+") adding = "" else b = b.copy(kind = k)
                    }
                    if (b.kind.startsWith("c:")) TextButton({ DateKinds.remove(ctx, b.kind.removePrefix("c:")); customKinds = DateKinds.custom(ctx) }) { Text("Убрать тег «${b.kind.removePrefix("c:")}» из списка", color = Pal.RoseInk) }
                    adding?.let { a0 ->
                        var t by remember { mutableStateOf(a0) }
                        AlertDialog(onDismissRequest = { adding = null }, title = { Text("Свой тег даты") }, text = { AppTextField(t, { t = it }, "Например: Свадьба, Память, Работа") },
                            confirmButton = { TextButton({ val n = t.trim(); if (n.isNotBlank()) { DateKinds.add(ctx, n); customKinds = DateKinds.custom(ctx); b = b.copy(kind = "c:$n") }; adding = null }) { Text("Добавить") } },
                            dismissButton = { TextButton({ adding = null }) { Text("Отмена") } })
                    }
                }
                FormCard("Когда (каждый год)") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.05f)).clickable { pick = true }.padding(12.dp, 10.dp)) {
                        Text("Дата", style = MaterialTheme.typography.labelSmall)
                        Text(MonthDay.of(b.month, b.day.coerceAtMost(Month.of(b.month).maxLength())).atYear(2024).format(DateTimeFormatter.ofPattern("d MMMM", RU)) + (year.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""),
                            style = MaterialTheme.typography.titleMedium, color = ACCENT)
                    }
                    year.toIntOrNull()?.let { y -> Text("Будет ${nextOccurrence(b).year - y} — ${nextOccurrence(b).format(DateTimeFormatter.ofPattern("d MMMM yyyy", RU))}", style = MaterialTheme.typography.bodySmall) }
                }
                FormCard("Цвет") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DATE_COLORS.forEachIndexed { i, c -> Box(Modifier.size(if (b.tint == i) 38.dp else 32.dp).clip(CircleShape).background(c).clickable { b = b.copy(tint = i) }, contentAlignment = Alignment.Center) {
                            if (b.tint == i) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) } }
                    }
                }
                FormCard("Заметка") { AppTextField(b.note, { b = b.copy(note = it) }, "Идея подарка, пожелание…", singleLine = false) }
                if (b0.id != 0L) PillButton("Удалить", { AppScope.io.launch { AppDb.get(ctx).todo().deleteBirthday(b0) }; onDone() }, Modifier.fillMaxWidth(), bg = Pal.Rose, fg = Pal.RoseInk)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (pick) {
        val y0 = year.toIntOrNull()?.coerceIn(1900, 2100) ?: LocalDate.now().year
        val dp = rememberDatePickerState(initialSelectedDateMillis = LocalDate.of(y0, b.month, b.day.coerceAtMost(java.time.YearMonth.of(y0, b.month).lengthOfMonth())).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pick = false }, confirmButton = {
            TextButton({
                // год сохраняем, только если выбран не текущий (дни рождения — в прошлом); текущий = «без года»
                dp.selectedDateMillis?.let { val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); b = b.copy(day = d.dayOfMonth, month = d.monthValue); year = if (d.year != LocalDate.now().year) d.year.toString() else "" }
                pick = false
            }) { Text("Ок") }
        }, dismissButton = { TextButton({ pick = false }) { Text("Отмена") } }) {
            DatePicker(dp, showModeToggle = false, title = null)
        }
    }
}

/** «Поздравил»: отметка на конкретный день, дата пропадает из сводки. Хранится в настройках, база не меняется. */
object BdayDone {
    private fun p(ctx: Context) = ctx.getSharedPreferences("dates", Context.MODE_PRIVATE)
    private fun key(id: Long, day: LocalDate) = "${day.toEpochDay()}:$id"
    fun isDone(ctx: Context, id: Long, day: LocalDate = LocalDate.now()) = key(id, day) in (p(ctx).getStringSet("done", emptySet()) ?: emptySet())
    fun unmark(ctx: Context, id: Long, day: LocalDate) {
        p(ctx).edit().putStringSet("done", ((p(ctx).getStringSet("done", emptySet()) ?: emptySet()) - key(id, day)).toSet()).apply()
    }
    fun mark(ctx: Context, id: Long, day: LocalDate = LocalDate.now()) {
        val cutoff = day.toEpochDay() - 400
        val old = (p(ctx).getStringSet("done", emptySet()) ?: emptySet()).filter { (it.substringBefore(':').toLongOrNull() ?: 0) > cutoff }
        p(ctx).edit().putStringSet("done", (old + key(id, day)).toSet()).apply()
    }
}

/** Идеи подарков: assets/gifts.txt, строки «тема|идея» (500+). */
object GiftIdeas {
    @Volatile private var all: List<Pair<String, String>>? = null
    fun load(ctx: Context): List<Pair<String, String>> = all ?: runCatching {
        ctx.assets.open("gifts.txt").bufferedReader().readLines().mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    }.getOrDefault(emptyList()).also { all = it }
}

/** Кубик: случайная идея подарка, можно выбрать тему. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GiftDialog(b: Birthday?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ideas = remember { GiftIdeas.load(ctx).ifEmpty { GIFTS.map { "Разное" to it } + ROMANTIC.map { "Романтика" to it } } }
    val themes = remember(ideas) { ideas.map { it.first }.distinct() }
    var theme by remember { mutableStateOf<String?>(null) }
    fun pick() = ideas.filter { theme == null || it.first == theme }.random()
    var idea by remember { mutableStateOf(pick()) }
    val spin = remember { Animatable(0f) }
    var roll by remember { mutableIntStateOf(0) }
    LaunchedEffect(roll) { spin.snapTo(0f); spin.animateTo(360f, tween(450)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Идея подарка · ${ideas.size} идей") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).rotate(spin.value).clip(RoundedCornerShape(16.dp)).background(Pal.Butter).clickable { idea = pick(); roll++ }, contentAlignment = Alignment.Center) { Text("🎲", fontSize = 34.sp) }
                Spacer(Modifier.height(12.dp))
                Text(idea.second, style = MaterialTheme.typography.titleMedium)
                Text(idea.first, style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Все темы", theme == null, { theme = null; idea = pick(); roll++ })
                    themes.forEach { t -> Chip(t, theme == t, { theme = t; idea = pick(); roll++ }) }
                }
            }
        },
        confirmButton = { TextButton({ idea = pick(); roll++ }) { Text("Ещё") } },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

/** Контакт с днём рождения из телефонной книги. */
data class ContactBday(val key: String, val name: String, val day: Int, val month: Int, val year: Int?)

private fun readContactBirthdays(ctx: Context): List<ContactBday> {
    val out = ArrayList<ContactBday>()
    runCatching {
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue; val name = c.getString(1) ?: continue; val d = c.getString(2) ?: continue
                // форматы: 1990-05-21, --05-21, 21.05.1990
                val m1 = Regex("^(\\d{4}|-)-?(\\d{2})-(\\d{2})").find(d)
                val m2 = Regex("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?").find(d)
                val (y, mo, da) = when {
                    m1 != null -> Triple(m1.groupValues[1].toIntOrNull(), m1.groupValues[2].toInt(), m1.groupValues[3].toInt())
                    m2 != null -> Triple(m2.groupValues[3].toIntOrNull(), m2.groupValues[2].toInt(), m2.groupValues[1].toInt())
                    else -> continue
                }
                if (mo in 1..12 && da in 1..31) out += ContactBday(key, name, da, mo, y)
            }
        }
    }
    return out.distinctBy { it.key }.sortedBy { it.name }
}

/** Синхронизация с контактами: показывает тех, у кого указан день рождения; добавляешь только кого сам отметишь. */
@Composable
private fun ContactsImport(existing: List<Birthday>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var list by remember { mutableStateOf<List<ContactBday>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    val linked = existing.filter { it.contactKey.isNotBlank() }.associateBy { it.contactKey }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        list = withContext(Dispatchers.IO) { readContactBirthdays(ctx) }
        // у уже связанных обновляем дату, если в контакте её поменяли
        list?.forEach { c -> linked[c.key]?.let { b -> if (b.day != c.day || b.month != c.month || (c.year != null && b.year != c.year)) AppScope.io.launch { AppDb.get(ctx).todo().upsertBirthday(b.copy(day = c.day, month = c.month, year = c.year ?: b.year)) } } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Дни рождения из контактов") },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                when {
                    !granted -> {
                        Text("Нужен доступ к контактам — только чтение. Приложение покажет тех, у кого в контакте заполнен «День рождения», а добавишь ты только отмеченных.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        PillButton("Дать доступ", { perm.launch(Manifest.permission.READ_CONTACTS) }, Modifier.fillMaxWidth())
                    }
                    list == null -> CircularProgressIndicator()
                    list!!.isEmpty() -> Text("В контактах пока нет дней рождения. Открой контакт → «Изменить» → «Добавить поле» → «Значимое событие / День рождения». Потом зайди сюда снова.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list!!, key = { it.key }) { c ->
                            val already = c.key in linked
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (c.key in chosen) Pal.Lilac else Pal.Ink.copy(alpha = 0.04f))
                                .clickable(enabled = !already) { if (c.key in chosen) chosen.remove(c.key) else chosen.add(c.key) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckCircle(already || c.key in chosen, ACCENT, 20.dp) { if (!already) { if (c.key in chosen) chosen.remove(c.key) else chosen.add(c.key) } }
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${c.day} ${Month.of(c.month).getDisplayName(java.time.format.TextStyle.FULL, RU)}" + (c.year?.let { " $it" } ?: "") + if (already) " · уже в датах" else "", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val add = list.orEmpty().filter { it.key in chosen }
                AppScope.io.launch { add.forEachIndexed { i, c -> AppDb.get(ctx).todo().upsertBirthday(Birthday(name = c.name, day = c.day, month = c.month, year = c.year, kind = "birthday", tint = i % 6, contactKey = c.key)) } }
                onDismiss()
            }, enabled = chosen.isNotEmpty()) { Text(if (chosen.isEmpty()) "Добавить" else "Добавить (${chosen.size})") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } })
}
