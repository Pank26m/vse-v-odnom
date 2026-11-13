package com.pank.vsevodnom.ui.contacts

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.PhoneData
import com.pank.vsevodnom.call.KeypadTab
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.map.BottomPanel
import com.pank.vsevodnom.ui.map.MenuRow
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Общие данные «Телефона»: контакты, группы, журнал, мессенджеры. Перечитываются по [version]. */
class PhoneState {
    var version by mutableIntStateOf(0)
    var contacts by mutableStateOf<List<Contact>?>(null)
    var groups by mutableStateOf<List<Group>>(emptyList())
    var calls by mutableStateOf<List<CallEntry>>(emptyList())
    var msgrs by mutableStateOf<Map<Long, Set<PhoneData.App>>>(emptyMap())
    fun byNumber(n: String): Contact? { val k = ContactsRepo.norm(n); if (k.length < 6) return null; return contacts?.firstOrNull { c -> c.phones.any { ContactsRepo.norm(it.second) == k } } }
}

private val PERMS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
    Manifest.permission.WRITE_CALL_LOG, Manifest.permission.CALL_PHONE)

/**
 * «Телефон»: свой нижний бар — Клавиатура · Недавние (главная) · Контакты · Сообщения.
 * [start]: 0 недавние, 1 сообщения, 2 контакты, 3 клавиатура.
 */
@Composable
fun PhoneScreen(onBack: () -> Unit, start: Int = 0, number: String = "") {
    val ctx = LocalContext.current
    // порядок в баре: 0 клавиатура, 1 недавние, 2 контакты, 3 сообщения
    var tab by rememberSaveable { mutableIntStateOf(when (start) { 1 -> 3; 2 -> 2; 3 -> 0; else -> 1 }) }
    val st = remember { PhoneState() }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { st.version++ }
    LaunchedEffect(Unit) { if (!ContactsRepo.has(ctx, Manifest.permission.READ_CONTACTS) || !ContactsRepo.has(ctx, Manifest.permission.READ_CALL_LOG)) perm.launch(PERMS) }
    LaunchedEffect(st.version) {
        withContext(Dispatchers.IO) {
            val c = runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList())
            val g = runCatching { ContactsRepo.groups(ctx) }.getOrDefault(emptyList())
            val l = ContactsRepo.calls(ctx, 500)
            val m = PhoneData.messengersAll(ctx)
            withContext(Dispatchers.Main) { st.contacts = c; st.groups = g; st.calls = l; st.msgrs = m }
        }
    }
    // «СМС» из недавних/контакта — на вкладку сообщений (переписку откроет она сама)
    LaunchedEffect(com.pank.vsevodnom.ui.sms.SmsNav.open) { if (com.pank.vsevodnom.ui.sms.SmsNav.open != null) tab = 3 }
    var openContact by remember { mutableStateOf<Contact?>(null) }
    var edit by remember { mutableStateOf<Pair<Long?, String>?>(null) }   // (id контакта или null — новый, номер для нового)
    edit?.let { (id, num) ->
        BackHandler { edit = null }
        ContactEditor(id, num, onDone = { edit = null; st.version++ }); return
    }
    openContact?.let { c ->
        BackHandler { openContact = null }
        ContactCard(st.contacts?.firstOrNull { it.id == c.id } ?: c, st, onBack = { openContact = null }, onEdit = { edit = c.id to "" }); return
    }
    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Box(Modifier.weight(1f)) {
            if (!ContactsRepo.has(ctx, Manifest.permission.READ_CONTACTS) && tab != 3) {
                ScreenScaffold("Телефон", onBack) {
                    Column(Modifier.padding(16.dp)) {
                        AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) { Text("Нужен доступ к контактам и журналу звонков.", style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(10.dp))
                        PillButton("Дать доступ", { perm.launch(PERMS) }, Modifier.fillMaxWidth())
                    }
                }
            } else when (tab) {
                0 -> KeypadTab(st, number, onBack, onNewContact = { edit = null to it })
                1 -> RecentsTab(st, onBack, onOpen = { openContact = it }, onNewContact = { edit = null to it })
                2 -> ContactsTab(st, onBack, onOpen = { openContact = it }, onNew = { edit = null to "" })
                else -> com.pank.vsevodnom.ui.sms.MessagesTab(onBack, onNewContact = { edit = null to it })
            }
        }
        // свой нижний бар
        Row(Modifier.fillMaxWidth().background(Pal.Card).navigationBarsPadding().padding(vertical = 6.dp)) {
            listOf(Icons.Rounded.Dialpad to "Клавиатура", Icons.Rounded.History to "Недавние", Icons.Rounded.Contacts to "Контакты", Icons.AutoMirrored.Rounded.Chat to "Сообщения")
                .forEachIndexed { i, (ic, t) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { tab = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (tab == i) Pal.Mint else Color.Transparent).padding(horizontal = 14.dp, vertical = 3.dp)) {
                            Icon(ic, null, tint = if (tab == i) Pal.MintInk else Pal.Muted)
                        }
                        Text(t, style = MaterialTheme.typography.labelSmall, color = if (tab == i) Pal.Ink else Pal.Muted)
                    }
                }
        }
    }
}

// ---------------- общие кусочки ----------------

@Composable
fun Avatar(name: String, size: Int, photo: String? = null) {
    val t = Tints.all[(name.hashCode() and 0x7fffffff) % 6]
    if (photo != null) AppImage(photo, Modifier.size(size.dp).clip(CircleShape), maxPx = size * 3, radius = (size / 2).dp)
    else Box(Modifier.size(size.dp).clip(CircleShape).background(t.bg), contentAlignment = Alignment.Center) {
        Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), color = t.ink,
            style = when { size > 100 -> MaterialTheme.typography.displaySmall; size > 48 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleSmall })
    }
}

/** Незнакомый номер: человечек зачёркнут. */
@Composable
fun UnknownAvatar(size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Pal.Line), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PersonOff, "Нет в контактах", tint = Pal.Muted, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Значок мессенджера. */
@Composable
fun MsgrIcon(app: PhoneData.App, size: Int) {
    if (app == PhoneData.App.TELEGRAM) com.pank.vsevodnom.ui.todo.TelegramBadge(size.dp)
    else Box(Modifier.size(size.dp).clip(CircleShape).background(Color(app.color)), contentAlignment = Alignment.Center) {
        Icon(if (app == PhoneData.App.VIBER) Icons.Rounded.Call else Icons.AutoMirrored.Rounded.Chat, null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

fun fmtWhen(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val d = t.toLocalDate(); val today = LocalDate.now()
    return when {
        d == today -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        d == today.minusDays(1) -> "вчера " + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        d.year == today.year -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale("ru")))
        else -> t.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

fun callTypeIcon(type: Int): Pair<ImageVector, Color> = when (type) {
    CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed to Pal.RoseInk
    CallLog.Calls.REJECTED_TYPE -> Icons.Rounded.CallEnd to Pal.RoseInk
    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade to Pal.SkyInk
    CallLog.Calls.BLOCKED_TYPE -> Icons.Rounded.Block to Pal.Muted
    else -> Icons.AutoMirrored.Rounded.CallReceived to Pal.MintInk
}

private fun copyText(ctx: android.content.Context, t: String) {
    ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Номер", t))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun sms(ctx: android.content.Context, number: String) = runCatching {
    com.pank.vsevodnom.ui.sms.SmsNav.open = number
}

// ---------------- Недавние ----------------

/** Звонки подряд с одного номера — одной строкой (как в звонилке Google). */
private data class CallGroup(val first: CallEntry, val all: List<CallEntry>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentsTab(st: PhoneState, onBack: () -> Unit, onOpen: (Contact) -> Unit, onNewContact: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }   // 0 все, 1 пропущенные, 2 отклонённые, 3 входящие, 4 исходящие
    var group by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var numberMenu by remember { mutableStateOf<CallGroup?>(null) }
    var settings by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }
    val contacts = st.contacts.orEmpty()
    val groups = remember(st.calls, filter, q, group, contacts) {
        val l = st.calls.filter { c ->
            (when (filter) {
                1 -> c.type == CallLog.Calls.MISSED_TYPE
                2 -> c.type == CallLog.Calls.REJECTED_TYPE || c.type == CallLog.Calls.BLOCKED_TYPE
                3 -> c.type == CallLog.Calls.INCOMING_TYPE
                4 -> c.type == CallLog.Calls.OUTGOING_TYPE
                else -> true
            }) && (q.isBlank() || (st.byNumber(c.number)?.name ?: c.name ?: "").contains(q, true) || c.number.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" })) &&
                (group == null || st.byNumber(c.number)?.groups?.contains(group) == true)
        }
        val out = ArrayList<CallGroup>()
        l.forEach { c ->
            val last = out.lastOrNull()
            val sameDay = last != null && Instant.ofEpochMilli(last.first.date).atZone(ZoneId.systemDefault()).toLocalDate() == Instant.ofEpochMilli(c.date).atZone(ZoneId.systemDefault()).toLocalDate()
            if (last != null && sameDay && ContactsRepo.norm(last.first.number) == ContactsRepo.norm(c.number) && c.number.isNotBlank()) out[out.lastIndex] = last.copy(all = last.all + c)
            else out += CallGroup(c, listOf(c))
        }
        out
    }
    val fav = contacts.filter { it.starred && it.phones.isNotEmpty() }
    var callRecs by remember { mutableStateOf(false) }
    var codes by remember { mutableStateOf(false) }
    if (callRecs) com.pank.vsevodnom.ui.recorder.CallRecordsDialog { callRecs = false }
    if (codes) PhoneCodesDialog { codes = false }
    ScreenScaffold("Недавние", null, actions = {
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Записи разговоров") }, { menu = false; callRecs = true }, leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null) })
                DropdownMenuItem({ Text("Экстренные службы и коды") }, { menu = false; codes = true }, leadingIcon = { Icon(Icons.Rounded.Sos, null, tint = Pal.RoseInk) })
                DropdownMenuItem({ Text("Определитель и чёрный список") }, { menu = false; settings = true }, leadingIcon = { Icon(Icons.Rounded.Shield, null) })
                DropdownMenuItem({ Text("Быстрый набор") }, { menu = false; speed = true }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                DropdownMenuItem({ Text("Уведомления о звонках") }, { menu = false
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) } },
                    leadingIcon = { Icon(Icons.Rounded.Notifications, null) })
                DropdownMenuItem({ Text("Звонилка по умолчанию") }, { menu = false
                    ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) }?.let {
                        runCatching { (ctx as? android.app.Activity)?.startActivityForResult(it.createRequestRoleIntent(RoleManager.ROLE_DIALER), 7) }
                    } }, leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) })
                DropdownMenuItem({ Text("Настройки вызовов Android") }, { menu = false
                    runCatching { ctx.startActivity(Intent(android.telecom.TelecomManager.ACTION_SHOW_CALL_SETTINGS)) } }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                DropdownMenuItem({ Text("Очистить журнал звонков") }, { menu = false
                    AppScope.io.launch { PhoneData.deleteCalls(ctx, st.calls.map { it.id }); withContext(Dispatchers.Main) { st.version++ } } },
                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = Pal.RoseInk) })
            }
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { AppTextField(q, { q = it }, "Поиск: имя или номер") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed2(listOf("Все", "Пропущенные", "Отклонённые", "Входящие", "Исходящие")) { i, t -> Chip(t, filter == i, { filter = i }) }
                    items(st.groups, key = { "g" + it.id }) { g -> Chip("#" + g.title, group == g.id, { group = if (group == g.id) null else g.id }) }
                }
            }
            // избранные — три в ряд, как раньше: плитка — карточка, низ — звонок
            if (fav.isNotEmpty() && q.isBlank() && filter == 0 && group == null) item {
                Column(Modifier.padding(top = 4.dp)) {
                    fav.chunked(3).forEach { row ->
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { onOpen(c) }.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Avatar(c.name, 54, c.photo)
                                    Text(c.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp, 6.dp, 6.dp, 8.dp))
                                    Row(Modifier.fillMaxWidth().height(40.dp).background(Pal.Mint).clickable { ContactsRepo.call(ctx, c.phones.first().second) },
                                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (groups.isEmpty()) item { EmptyHint(if (st.calls.isEmpty()) "Журнал звонков пуст или нет доступа" else "Ничего не найдено") }
            items(groups, key = { it.first.id }) { g ->
                val c = g.first
                val who = st.byNumber(c.number)
                val (icon, tint) = callTypeIcon(c.type)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card)
                    // нажатие — сведения (карточка контакта или меню номера); звонок — только значком справа
                    .combinedClickable(onClick = { if (who != null) onOpen(who) else numberMenu = g }, onLongClick = { numberMenu = g })
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (who != null) Box(Modifier.clickable { onOpen(who) }) { Avatar(who.name, 42, who.photo) } else UnknownAvatar(42)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val title = who?.name ?: c.name ?: c.number.ifBlank { "Скрытый номер" }
                        Text(title + if (g.all.size > 1) " (${g.all.size})" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            color = if (c.type == CallLog.Calls.MISSED_TYPE) Pal.RoseInk else Pal.Ink)
                        if (who != null || c.name != null) Text(c.number, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                            Text(" " + fmtWhen(c.date) + (if (c.duration > 0) " · ${c.duration / 60}:%02d".format(c.duration % 60) else "") +
                                (if (ContactsRepo.norm(c.number) in ContactsRepo.blocked(ctx)) " · заблокирован" else ""),
                                style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                        }
                    }
                    who?.let { w -> st.msgrs[w.id]?.minByOrNull { it.ordinal }?.let { MsgrIcon(it, 18); Spacer(Modifier.width(4.dp)) } }
                    if (c.number.isNotBlank()) Box(Modifier.size(44.dp).clip(CircleShape).background(Pal.Mint).clickable { callOnce(ctx, c.number) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk)
                    }
                }
            }
        }
    }
    numberMenu?.let { g -> NumberMenu(g.first.number, st, g.all.map { it.id }, onDismiss = { numberMenu = null }, onOpen = onOpen, onNewContact = onNewContact) }
    if (settings) ScreenerDialog { settings = false }
    if (speed) SpeedDialDialog(st) { speed = false }
}

/** Звонок с защитой от повторного нажатия: пока идёт (или только что начался) звонок — второй не начинаем. */
private var lastCallAt = 0L
fun callOnce(ctx: android.content.Context, number: String) {
    val now = System.currentTimeMillis()
    if (com.pank.vsevodnom.call.CallState.calls.isNotEmpty() || now - lastCallAt < 3000) return
    lastCallAt = now
    ContactsRepo.call(ctx, number)
}

/** itemsIndexed без лишнего импорта. */
private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed2(list: List<T>, crossinline content: @Composable (Int, T) -> Unit) {
    list.forEachIndexed { i, t -> item(key = "f$i") { content(i, t) } }
}

/** Меню номера (долгое нажатие): копировать, удалить из списка, в контакты, блок, история, пробив, мессенджеры. */
@Composable
fun NumberMenu(number: String, st: PhoneState, logIds: List<Long> = emptyList(), onDismiss: () -> Unit, onOpen: (Contact) -> Unit, onNewContact: (String) -> Unit) {
    val ctx = LocalContext.current
    val who = st.byNumber(number)
    var history by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf(false) }
    var msgrs by remember { mutableStateOf<List<PhoneData.Msgr>>(emptyList()) }
    LaunchedEffect(who) { msgrs = who?.let { withContext(Dispatchers.IO) { PhoneData.messengers(ctx, it.id) } }.orEmpty() }
    val blocked = ContactsRepo.norm(number) in ContactsRepo.blocked(ctx)
    BottomPanel(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (who != null) Avatar(who.name, 44, who.photo) else UnknownAvatar(44)
            Column(Modifier.padding(start = 12.dp)) {
                Text(who?.name ?: number.ifBlank { "Скрытый номер" }, style = MaterialTheme.typography.titleMedium)
                if (who != null) Text(number, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }
        if (number.isBlank()) { Text("Номер скрыт — действия недоступны", color = Pal.Muted); return@BottomPanel }
        MenuRow(Icons.Rounded.Call, "Позвонить") { onDismiss(); ContactsRepo.call(ctx, number) }
        MenuRow(Icons.Rounded.Sms, "Написать СМС") { onDismiss(); sms(ctx, number) }
        if (msgrs.isNotEmpty()) msgrs.forEach { m ->
            MenuRow(when (m.kind) { PhoneData.Kind.CALL -> Icons.Rounded.Call; PhoneData.Kind.VIDEO -> Icons.Rounded.Videocam; else -> Icons.AutoMirrored.Rounded.Chat },
                when (m.kind) { PhoneData.Kind.CALL -> "Позвонить в ${m.app.title}"; PhoneData.Kind.VIDEO -> "Видеозвонок в ${m.app.title}"; else -> "Написать в ${m.app.title}" }, tint = Color(m.app.color)) {
                onDismiss(); PhoneData.open(ctx, m)
            }
        } else PhoneData.App.entries.filter { PhoneData.installed(ctx, it) }.forEach { a ->
            MenuRow(Icons.AutoMirrored.Rounded.Chat, "Открыть в ${a.title}", tint = Color(a.color), sub = "сработает, если у номера есть ${a.title}") { onDismiss(); PhoneData.openByNumber(ctx, a, number) }
        }
        MenuRow(Icons.Rounded.ContentCopy, "Скопировать номер") { copyText(ctx, number); onDismiss() }
        if (who == null) MenuRow(Icons.Rounded.PersonAdd, "Добавить в контакты") { onDismiss(); onNewContact(number) }
        else MenuRow(Icons.Rounded.Person, "Открыть контакт") { onDismiss(); onOpen(who) }
        MenuRow(Icons.Rounded.History, "История звонков") { history = true }
        MenuRow(Icons.Rounded.TravelExplore, "Пробить номер", sub = "кто это, отзывы о спаме и мошенниках") { lookup = true }
        if (logIds.isNotEmpty()) MenuRow(Icons.Rounded.DeleteOutline, "Удалить из списка") {
            AppScope.io.launch {
                val ok = PhoneData.deleteCalls(ctx, logIds)
                withContext(Dispatchers.Main) { if (!ok) Toast.makeText(ctx, "Нет разрешения на изменение журнала звонков", Toast.LENGTH_SHORT).show(); st.version++ }
            }
            onDismiss()
        }
        MenuRow(Icons.Rounded.Block, if (blocked) "Разблокировать" else "Заблокировать", tint = Pal.RoseInk) {
            ContactsRepo.setBlocked(ctx, number, !blocked); st.version++; onDismiss()
            Toast.makeText(ctx, if (blocked) "Разблокирован" else "Заблокирован: звонки с номера будут сбрасываться", Toast.LENGTH_SHORT).show()
        }
    }
    if (history) CallHistoryDialog(listOf(number), st) { history = false }
    if (lookup) LookupDialog(number) { lookup = false }
}

@Composable
fun CallHistoryDialog(numbers: List<String>, st: PhoneState, onDismiss: () -> Unit) {
    val keys = numbers.map { ContactsRepo.norm(it) }.toSet()
    val list = st.calls.filter { ContactsRepo.norm(it.number) in keys }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("История звонков") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 420.dp)) {
            if (list.isEmpty()) item { Text("Звонков нет", color = Pal.Muted) }
            items(list, key = { it.id }) { c ->
                val (ic, t) = callTypeIcon(c.type)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = t, modifier = Modifier.size(18.dp))
                    Text("  " + fmtWhen(c.date), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(if (c.duration > 0) "${c.duration / 60}:%02d".format(c.duration % 60) else when (c.type) { CallLog.Calls.MISSED_TYPE -> "пропущен"; CallLog.Calls.REJECTED_TYPE -> "отклонён"; else -> "—" },
                        style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
            if (list.isNotEmpty()) item {
                Text("Всего: ${list.size} · разговоров ${list.sumOf { it.duration } / 60} мин", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

/**
 * «Пробить номер». Своей базы «как подписан у других» нет: Getcontact и похожие держат её на своих серверах и не дают к ней
 * доступа (их API закрытый, только для их приложения). Поэтому — открытые источники: поиск, отзывы, есть ли аккаунты в мессенджерах.
 */
@Composable
fun LookupDialog(number: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val getcontact = remember { runCatching { ctx.packageManager.getLaunchIntentForPackage("app.source.getcontact") }.getOrNull() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Пробить $number") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PhoneData.lookups(number).forEach { l ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) }
                }.padding(12.dp)) {
                    Text(l.title, style = MaterialTheme.typography.titleSmall)
                    Text(l.sub, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
            if (getcontact != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable {
                copyText(ctx, number); runCatching { ctx.startActivity(getcontact) }
            }.padding(12.dp)) {
                Text("Getcontact", style = MaterialTheme.typography.titleSmall)
                Text("номер скопирован — вставь в поиск Getcontact", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            Text("Базы вроде Getcontact закрыты — прочитать их из своего приложения нельзя. Здесь — открытые источники.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

/** Определитель и чёрный список (перенесено из старых «Контактов»). */
@Composable
private fun ScreenerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rm = ctx.getSystemService(RoleManager::class.java)
    val held = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) || com.pank.vsevodnom.call.CallState.isDefault(ctx)
    var hidden by remember { mutableStateOf(ContactsRepo.blockHidden(ctx)) }
    var blocked by remember { mutableStateOf(ContactsRepo.blocked(ctx).toList()) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Определитель номера") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(if (held) "Включён: незнакомые номера подписываются уведомлением, номера из чёрного списка сбрасываются." else
                        "Выключен. Включи — Android спросит, разрешить ли приложению проверять звонки.", style = MaterialTheme.typography.bodyMedium)
                }
                if (!held) item { PillButton("Включить", { runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) } }, Modifier.fillMaxWidth()) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сбрасывать скрытые номера", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(hidden, { hidden = it; ContactsRepo.setBlockHidden(ctx, it) })
                    }
                }
                item { Text("Чёрный список (${blocked.size})", style = MaterialTheme.typography.labelLarge) }
                items(blocked) { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("…$n", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton({ ContactsRepo.setBlocked(ctx, n, false); blocked = ContactsRepo.blocked(ctx).toList() }) { Text("Убрать") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Готово") } })
}

/** Быстрый набор: долгое нажатие на цифру 2–9 на клавиатуре — звонок. */
@Composable
fun SpeedDialDialog(st: PhoneState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var map by remember { mutableStateOf(PhoneData.speedDial(ctx)) }
    var pick by remember { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Быстрый набор") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Удерживай цифру на клавиатуре — сразу звонок.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            (2..9).forEach { k ->
                val v = map[k]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { pick = k }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$k", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v?.first?.ifBlank { null } ?: v?.second ?: "не задано", style = MaterialTheme.typography.bodyMedium, color = if (v == null) Pal.Muted else Pal.Ink)
                        if (v != null && v.first.isNotBlank()) Text(v.second, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                    if (v != null) IconButton({ PhoneData.setSpeedDial(ctx, k, null, null); map = PhoneData.speedDial(ctx) }) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted) }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Готово") } })
    pick?.let { k ->
        ContactPicker(st, title = "Цифра $k", onDismiss = { pick = null }) { name, num -> PhoneData.setSpeedDial(ctx, k, name, num); map = PhoneData.speedDial(ctx); pick = null }
    }
}

/** Выбор контакта и номера (или ввод номера вручную). */
@Composable
fun ContactPicker(st: PhoneState, title: String, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = st.contacts.orEmpty().filter { it.phones.isNotEmpty() && (q.isBlank() || it.name.contains(q, true) || it.phones.any { p -> p.second.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" }) }) }.take(80)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            AppTextField(q, { q = it }, "Имя или номер")
            LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 6.dp)) {
                if (q.count(Char::isDigit) >= 5) item {
                    Text("Номер: $q", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk, modifier = Modifier.fillMaxWidth().clickable { onPick("", q.trim()) }.padding(10.dp))
                }
                list.forEach { c -> c.phones.forEach { (label, n) ->
                    item(key = "${c.id}:$n") {
                        Row(Modifier.fillMaxWidth().clickable { onPick(c.name, n) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.name, 34, c.photo)
                            Column(Modifier.padding(start = 10.dp)) { Text(c.name, style = MaterialTheme.typography.bodyMedium); Text("$n · $label", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                        }
                    }
                } }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Отмена") } })
}

// ---------------- Контакты ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsTab(st: PhoneState, onBack: () -> Unit, onOpen: (Contact) -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<Long?>(null) }
    var numberMenu by remember { mutableStateOf<String?>(null) }
    val all = st.contacts.orEmpty()
    val list = all.filter { (q.isBlank() || it.name.contains(q, true) || it.phones.any { p -> p.second.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" }) }) && (group == null || group in it.groups) }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Контакты", onBack, actions = { RoundIconButton(Icons.Rounded.PersonAdd, "Новый контакт", onNew) }) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AppTextField(q, { q = it }, "Поиск: имя или номер") }
                if (st.groups.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        item { Chip("Все · ${all.size}", group == null, { group = null }) }
                        items(st.groups, key = { it.id }) { g -> Chip("#" + g.title, group == g.id, { group = if (group == g.id) null else g.id }) }
                    }
                }
                if (st.contacts == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                var letter = ' '
                list.forEach { c ->
                    val l = c.name.firstOrNull()?.uppercaseChar() ?: '#'
                    if (l != letter && q.isBlank()) { letter = l; item(key = "L$l${c.id}") { Text("$l", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) } }
                    item(key = c.id) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card)
                            .combinedClickable(onClick = { onOpen(c) }, onLongClick = { c.phones.firstOrNull()?.let { numberMenu = it.second } }).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.name, 42, c.photo)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                c.phones.firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                            }
                            st.msgrs[c.id]?.sortedBy { it.ordinal }?.forEach { MsgrIcon(it, 16); Spacer(Modifier.width(3.dp)) }
                            if (c.starred) Icon(Icons.Rounded.Star, null, tint = Pal.ButterInk, modifier = Modifier.size(18.dp))
                            c.phones.firstOrNull()?.let { p -> IconButton({ ContactsRepo.call(ctx, p.second) }) { Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk) } }
                        }
                    }
                }
            }
        }
    }
    numberMenu?.let { n -> NumberMenu(n, st, onDismiss = { numberMenu = null }, onOpen = onOpen, onNewContact = {}) }
}

// ---------------- Карточка контакта ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactCard(c: Contact, st: PhoneState, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    var msgrs by remember(c.id) { mutableStateOf<List<PhoneData.Msgr>?>(null) }
    LaunchedEffect(c.id, st.version) { msgrs = withContext(Dispatchers.IO) { PhoneData.messengers(ctx, c.id) } }
    var more by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var numberMenu by remember { mutableStateOf<String?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var photoFull by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val ringPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val u = r.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AppScope.io.launch { PhoneData.setRingtone(ctx, c.id, u); withContext(Dispatchers.Main) { st.version++ } }
        }
    }
    val main = c.phones.firstOrNull()?.second
    val chats = msgrs.orEmpty().filter { it.kind == PhoneData.Kind.CHAT }.ifEmpty { msgrs.orEmpty().filter { it.kind != PhoneData.Kind.VIDEO } }.distinctBy { it.app }
    val blocked = c.phones.any { ContactsRepo.norm(it.second) in ContactsRepo.blocked(ctx) }
    ScreenScaffold("", onBack, actions = {
        RoundIconButton(if (c.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Избранный", {
            AppScope.io.launch { runCatching { ContactsRepo.setStarred(ctx, c, !c.starred) }; withContext(Dispatchers.Main) { st.version++ } }
        }, tint = if (c.starred) Pal.ButterInk else Pal.Ink)
        RoundIconButton(Icons.Rounded.Edit, "Изменить", onEdit)
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { more = true })
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem({ Text("Поделиться") }, { more = false; PhoneData.shareContact(ctx, c.lookup, c.name) }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                DropdownMenuItem({ Text("Рингтон" + if (c.ringtone != null) " · свой" else "") }, { more = false
                    ringPicker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, c.ringtone?.let { Uri.parse(it) }))
                }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) })
                DropdownMenuItem({ Text(if (blocked) "Разблокировать" else "Заблокировать") }, { more = false
                    c.phones.forEach { ContactsRepo.setBlocked(ctx, it.second, !blocked) }; st.version++ }, leadingIcon = { Icon(Icons.Rounded.Block, null) })
                DropdownMenuItem({ Text("Удалить контакт", color = Pal.RoseInk) }, { more = false; askDelete = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Pal.RoseInk) })
            }
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clickable(enabled = c.photo != null) { scope.launch { photoFull = c.photo?.let { loadImage(ctx, it, 1600) } } }) { Avatar(c.name, 150, c.photo) }
                    Text(c.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
                    if (blocked) Text("заблокирован", style = MaterialTheme.typography.labelMedium, color = Pal.RoseInk)
                }
            }
            // кнопки под авой: вызов, СМС, главный мессенджер (Telegram > Viber > WhatsApp)
            if (main != null) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigAction(Icons.Rounded.Call, "Вызов", Pal.Mint, Pal.MintInk, Modifier.weight(1f)) { ContactsRepo.call(ctx, main) }
                    BigAction(Icons.Rounded.Sms, "СМС", Pal.Sky, Pal.SkyInk, Modifier.weight(1f)) { sms(ctx, main) }
                    chats.firstOrNull()?.let { m ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(m.app.color).copy(alpha = 0.15f)).clickable { PhoneData.open(ctx, m) }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            MsgrIcon(m.app, 26); Text(m.app.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            // остальные мессенджеры и звонки через них — списком сразу ниже
            val rest = msgrs.orEmpty().filterNot { it == chats.firstOrNull() }
            if (rest.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card)) {
                    rest.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { PhoneData.open(ctx, m) }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            MsgrIcon(m.app, 22)
                            Text(when (m.kind) { PhoneData.Kind.CALL -> "  Позвонить в ${m.app.title}"; PhoneData.Kind.VIDEO -> "  Видеозвонок в ${m.app.title}"; else -> "  Написать в ${m.app.title}" },
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (msgrs != null && msgrs!!.isEmpty() && main != null) item {
                // мессенджеры не синхронизировали контакт — можно попробовать открыть чат по номеру
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(12.dp)) {
                    Text("Мессенджеры не нашлись в контакте", style = MaterialTheme.typography.titleSmall)
                    Text("Telegram, Viber и WhatsApp сами добавляют себя в контакты, если в них включена синхронизация контактов. Можно попробовать открыть по номеру:",
                        style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhoneData.App.entries.filter { PhoneData.installed(ctx, it) }.forEach { a ->
                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { PhoneData.openByNumber(ctx, a, main) }.padding(10.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                MsgrIcon(a, 18); Text(" " + a.title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            items(c.phones) { (label, num) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable { ContactsRepo.call(ctx, num) }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(num, style = MaterialTheme.typography.titleMedium); Text(label, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    IconButton({ numberMenu = num }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                }
            }
            // история звонков
            val keys = c.phones.map { ContactsRepo.norm(it.second) }.toSet()
            val calls = st.calls.filter { ContactsRepo.norm(it.number) in keys }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("История звонков · ${calls.size}")
                    Spacer(Modifier.weight(1f))
                    if (calls.size > 5) TextButton({ history = true }) { Text("Все") }
                }
            }
            items(calls.take(5), key = { "c" + it.id }) { e ->
                val (ic, t) = callTypeIcon(e.type)
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = t, modifier = Modifier.size(18.dp))
                    Text("  " + fmtWhen(e.date), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(if (e.duration > 0) "${e.duration / 60}:%02d".format(e.duration % 60) else "—", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
            item { SectionLabel("Теги") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.groups.forEach { g ->
                        Chip("#" + g.title, g.id in c.groups, {
                            AppScope.io.launch { runCatching { ContactsRepo.setInGroup(ctx, c, g.id, g.id !in c.groups) }; withContext(Dispatchers.Main) { st.version++ } }
                        })
                    }
                    Chip("+ тег", false, { newGroup = true })
                }
            }
        }
    }
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Удалить «${c.name}»?") },
        text = { Text("Контакт удалится и из Google Контактов.") },
        confirmButton = { TextButton({ askDelete = false; AppScope.io.launch { PhoneData.deleteContact(ctx, c.id, c.lookup); withContext(Dispatchers.Main) { st.version++; onBack() } } }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Отмена") } })
    if (history) CallHistoryDialog(c.phones.map { it.second }, st) { history = false }
    numberMenu?.let { n -> NumberMenu(n, st, onDismiss = { numberMenu = null }, onOpen = {}, onNewContact = {}) }
    photoFull?.let { b -> com.pank.vsevodnom.ui.map.PhotoViewer(listOf(b), 0, c.name) { photoFull = null } }
    if (newGroup) {
        var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newGroup = false }, title = { Text("Новый тег") }, text = { AppTextField(t, { t = it }, "Название") },
            confirmButton = { TextButton({ if (t.isNotBlank()) AppScope.io.launch { runCatching { ContactsRepo.createGroup(ctx, t.trim()) }; withContext(Dispatchers.Main) { st.version++ } }; newGroup = false }) { Text("Создать") } },
            dismissButton = { TextButton({ newGroup = false }) { Text("Отмена") } })
    }
}

@Composable
private fun BigAction(icon: ImageVector, text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp)); Text(text, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------- Редактор ----------------

@Composable
private fun ContactEditor(id: Long?, number: String, name: String = "", onSaved: (String) -> Unit = {}, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf<ContactEdit.Data?>(null) }
    LaunchedEffect(id) {
        d = if (id == null) ContactEdit.Data(given = name.substringBefore(' ').trim(), family = name.substringAfter(' ', "").trim(),
                phones = listOf(ContactEdit.Num(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, number)))
            else withContext(Dispatchers.IO) { ContactEdit.load(ctx, id) }
    }
    var saving by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        u?.let { uri -> AppScope.io.launch { val b = ContactEdit.squarePhoto(ctx, uri); withContext(Dispatchers.Main) { d = d?.copy(photo = b, photoChanged = b != null) } } }
    }
    val cur = d
    ScreenScaffold(if (id == null) "Новый контакт" else "Изменить", onDone, actions = {
        RoundIconButton(Icons.Rounded.Check, "Сохранить", {
            val x = d ?: return@RoundIconButton
            if (x.given.isBlank() && x.family.isBlank()) { Toast.makeText(ctx, "Впиши имя", Toast.LENGTH_SHORT).show(); return@RoundIconButton }
            saving = true
            AppScope.io.launch {
                val ok = ContactEdit.save(ctx, x)
                if (ok) onSaved(listOf(x.given, x.family).filter { it.isNotBlank() }.joinToString(" "))
                withContext(Dispatchers.Main) { saving = false; if (ok) onDone() else Toast.makeText(ctx, "Не удалось сохранить (нет доступа к контактам?)", Toast.LENGTH_LONG).show() }
            }
        }, bg = Pal.Ink, tint = Color.White)
    }) {
        if (cur == null || saving) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@ScreenScaffold }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(120.dp).clip(CircleShape).background(Pal.Line).clickable {
                pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, contentAlignment = Alignment.Center) {
                if (cur.photo != null) androidx.compose.foundation.Image(cur.photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Icon(Icons.Rounded.AddAPhoto, "Фото", tint = Pal.Muted, modifier = Modifier.size(36.dp))
            }
            AppTextField(cur.given, { d = cur.copy(given = it) }, "Имя")
            AppTextField(cur.family, { d = cur.copy(family = it) }, "Фамилия")
            SectionLabel("Номера")
            cur.phones.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var typeMenu by remember { mutableStateOf(false) }
                    Box {
                        Text(ContactEdit.typeName(p.type), style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk,
                            modifier = Modifier.width(96.dp).clip(RoundedCornerShape(10.dp)).clickable { typeMenu = true }.padding(8.dp))
                        DropdownMenu(typeMenu, { typeMenu = false }) {
                            ContactEdit.PHONE_TYPES.forEach { (t, n) -> DropdownMenuItem({ Text(n) }, { typeMenu = false; d = cur.copy(phones = cur.phones.toMutableList().also { it[i] = p.copy(type = t) }) }) }
                        }
                    }
                    AppTextField(p.number, { v -> d = cur.copy(phones = cur.phones.toMutableList().also { it[i] = p.copy(number = v) }) }, "Номер", Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                    if (cur.phones.size > 1) IconButton({ d = cur.copy(phones = cur.phones.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted) }
                }
            }
            TextButton({ d = cur.copy(phones = cur.phones + ContactEdit.Num(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "")) }) { Text("+ номер") }
            SectionLabel("Почта")
            cur.emails.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(e, { v -> d = cur.copy(emails = cur.emails.toMutableList().also { it[i] = v }) }, "Почта", Modifier.weight(1f))
                    IconButton({ d = cur.copy(emails = cur.emails.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted) }
                }
            }
            TextButton({ d = cur.copy(emails = cur.emails + "") }) { Text("+ почта") }
            AppTextField(cur.company, { d = cur.copy(company = it) }, "Работа / компания")
            AppTextField(cur.birthday, { d = cur.copy(birthday = it.filter { c -> c.isDigit() || c == '-' }.take(10)) }, "День рождения (ГГГГ-ММ-ДД)")
            AppTextField(cur.note, { d = cur.copy(note = it) }, "Заметка", singleLine = false)
        }
    }
}


// ---------------- Связь дат с контактами ----------------

/** Связи даты с контактами: строки «lookupKey#id» через перевод строки (старые записи — просто lookupKey).
 *  Ключ поиска Android меняется после синхронизации, поэтому ищем через lookupContact (он умеет старые ключи), а id — запасной путь. */
object ContactLinks {
    fun parse(s: String): List<String> = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun join(l: List<String>) = l.distinct().joinToString("\n")
    fun of(c: Contact) = "${c.lookup}#${c.id}"
    fun resolveId(ctx: android.content.Context, link: String): Long? {
        val key = link.substringBefore('#'); val id = link.substringAfter('#', "").toLongOrNull()
        val uri = if (id != null && key.isNotBlank()) ContactsContract.Contacts.getLookupUri(id, key) else Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
        return runCatching { ContactsContract.Contacts.lookupContact(ctx.contentResolver, uri)?.let { android.content.ContentUris.parseId(it) } }.getOrNull()
    }
    /** Новый контакт по имени (последний созданный с таким именем). */
    fun findByName(ctx: android.content.Context, name: String): Contact? =
        runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList()).filter { it.name.equals(name.trim(), true) }.maxByOrNull { it.id }
}

/** Карточка контакта поверх любого экрана (из «Дат»): позвонить, написать, изменить. */
@Composable
fun ContactLinkDialog(link: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val st = remember { PhoneState() }
    var id by remember { mutableStateOf<Long?>(null) }
    var edit by remember { mutableStateOf(false) }
    LaunchedEffect(st.version) {
        withContext(Dispatchers.IO) {
            val c = runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList())
            val i = ContactLinks.resolveId(ctx, link)
            val m = PhoneData.messengersAll(ctx)
            withContext(Dispatchers.Main) { st.contacts = c; st.msgrs = m; id = i }
        }
    }
    // «СМС» в карточке ведёт во вкладку сообщений — закрываем окно и переходим туда
    LaunchedEffect(com.pank.vsevodnom.ui.sms.SmsNav.open) { if (com.pank.vsevodnom.ui.sms.SmsNav.open != null) { com.pank.vsevodnom.ui.common.AppNav.route = "sms"; onDismiss() } }
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            val c = st.contacts?.firstOrNull { it.id == id }
            when {
                c != null && edit -> { BackHandler { edit = false }; ContactEditor(c.id, "", onDone = { edit = false; st.version++ }) }
                c != null -> ContactCard(c, st, onBack = onDismiss, onEdit = { edit = true })
                st.contacts != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Контакт не найден — возможно, удалён из телефонной книги.", style = MaterialTheme.typography.bodyLarge)
                    PillButton("Закрыть", onDismiss, Modifier.fillMaxWidth())
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Новый контакт с готовым именем (для даты, у которой человека нет в контактах). onLinked — связь нового контакта. */
@Composable
fun NewContactDialog(name: String, onLinked: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            ContactEditor(null, "", name = name, onSaved = { n ->
                // вызывается в фоне сразу после сохранения
                ContactLinks.findByName(ctx, n)?.let { c -> onLinked(ContactLinks.of(c)) }   // состояние Compose можно менять из фона
            }, onDone = onDismiss)
        }
    }
}

/** Выбор нескольких контактов (связать дату — свадьбу, годовщину — с людьми). */
@Composable
fun ContactsChooser(title: String, chosen0: Set<Long>, onDismiss: () -> Unit, onDone: (List<Contact>) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<Contact>?>(null) }
    var granted by remember { mutableStateOf(ContactsRepo.has(ctx, Manifest.permission.READ_CONTACTS)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (granted) all = withContext(Dispatchers.IO) { runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList()) } else perm.launch(Manifest.permission.READ_CONTACTS) }
    var q by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Long>().apply { addAll(chosen0) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            AppTextField(q, { q = it }, "Имя или номер")
            val list = all.orEmpty().filter { q.isBlank() || it.name.contains(q, true) || it.phones.any { p -> p.second.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" }) } }
                .sortedByDescending { it.id in chosen }.take(120)
            if (all == null) CircularProgressIndicator(Modifier.padding(16.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 6.dp)) {
                items(list, key = { it.id }) { c ->
                    val on = c.id in chosen
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Pal.Lilac else Color.Transparent)
                        .clickable { if (on) chosen.remove(c.id) else chosen.add(c.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 36, c.photo)
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        if (on) Icon(Icons.Rounded.Check, null, tint = Pal.LilacInk)
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ onDone(all.orEmpty().filter { it.id in chosen }) }) { Text(if (chosen.isEmpty()) "Готово" else "Готово (${chosen.size})") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

/** Подпись связанного контакта (имя + фото) по связи. */
@Composable
fun LinkedContactRow(link: String, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var c by remember(link) { mutableStateOf<Contact?>(null) }
    var missing by remember(link) { mutableStateOf(false) }
    LaunchedEffect(link) {
        withContext(Dispatchers.IO) {
            val id = ContactLinks.resolveId(ctx, link)
            val found = id?.let { i -> runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList()).firstOrNull { it.id == i } }
            withContext(Dispatchers.Main) { c = found; missing = found == null }
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Ink.copy(alpha = 0.04f)).clickable(enabled = c != null, onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(c?.name ?: "?", 36, c?.photo)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(c?.name ?: if (missing) "Контакт не найден" else "…", style = MaterialTheme.typography.bodyLarge)
            c?.phones?.firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
        }
        c?.phones?.firstOrNull()?.let { p -> IconButton({ ContactsRepo.call(ctx, p.second) }) { Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk) } }
        if (onRemove != null) IconButton(onRemove) { Icon(Icons.Rounded.LinkOff, "Отвязать", tint = Pal.Muted) }
    }
}
