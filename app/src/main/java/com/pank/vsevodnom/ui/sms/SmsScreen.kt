package com.pank.vsevodnom.ui.sms

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import com.pank.vsevodnom.ui.map.BottomPanel
import com.pank.vsevodnom.ui.map.MenuRow
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.Sms
import com.pank.vsevodnom.data.SmsRepo
import com.pank.vsevodnom.data.SmsThread
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.vault.copySecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun whenText(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return when (t.toLocalDate()) {
        LocalDate.now() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        LocalDate.now().minusDays(1) -> "вчера"
        else -> t.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

/** Текст СМС как есть (язык оригинала), код — жирным. */
@Composable
private fun SmsText(body: String, code: String?, size: Int = 15) {
    val idx = code?.let { Regex(it.toCharArray().joinToString("[- ]?")).find(body) }
    Text(buildAnnotatedString {
        if (idx == null) append(body) else {
            append(body.substring(0, idx.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Pal.LilacInk, fontSize = (size + 3).sp)) { append(idx.value) }
            append(body.substring(idx.range.last + 1))
        }
    }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp))
}

/** Открыть переписку с номером из других вкладок «Телефона» (кнопка «СМС»). */
object SmsNav { var open by mutableStateOf<String?>(null) }

/** Свои отметки сообщений: закреплённые и скрытые («удалить» без прав СМС-приложения по умолчанию). */
object SmsMarks {
    private fun p(ctx: android.content.Context) = ctx.getSharedPreferences("sms", android.content.Context.MODE_PRIVATE)
    fun key(a: String) = a.filter { it.isLetterOrDigit() }.let { if (it.length >= 9 && it.all(Char::isDigit)) it.takeLast(9) else it.lowercase() }
    fun pinned(ctx: android.content.Context): Set<String> = p(ctx).getStringSet("pinned", emptySet())!!
    fun setPinned(ctx: android.content.Context, a: String, on: Boolean) { val s = pinned(ctx).toMutableSet(); if (on) s += key(a) else s -= key(a); p(ctx).edit().putStringSet("pinned", s).apply() }
    private fun hiddenAll(ctx: android.content.Context) = runCatching { JSONObject(p(ctx).getString("hidden", "{}")!!) }.getOrDefault(JSONObject())
    /** Скрыто до этого момента: новые сообщения после — переписка вернётся. */
    fun hiddenAt(ctx: android.content.Context, a: String): Long = hiddenAll(ctx).optLong(key(a), 0L)
    fun hide(ctx: android.content.Context, a: String) { val o = hiddenAll(ctx); o.put(key(a), System.currentTimeMillis()); p(ctx).edit().putString("hidden", o.toString()).apply() }
}

/** Отправка СМС с телефона (нужно разрешение «Отправка СМС»); Android сам сохраняет отправленное в общий список сообщений. */
object SmsSend {
    fun can(ctx: android.content.Context) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun send(ctx: android.content.Context, to: String, text: String): Boolean = runCatching {
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        if (parts.size > 1) sm.sendMultipartTextMessage(to, null, parts, null, null) else sm.sendTextMessage(to, null, text, null, null)
        true
    }.getOrDefault(false)
}

/** Вкладка «Сообщения»: поиск по чатам и тексту, закреп, долгое нажатие — меню, кнопка «Написать». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessagesTab(onBack: () -> Unit, onNewContact: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(SmsRepo.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<Sms>?>(null) }
    var threads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<SmsThread?>(null) }
    var q by remember { mutableStateOf("") }
    var showSpam by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var threadMenu by remember { mutableStateOf<SmsThread?>(null) }
    var compose by remember { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        granted = SmsRepo.hasPermission(ctx); denied = !granted; version++
    }
    LaunchedEffect(Unit) {
        if (!granted) perm.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(granted, version) {
        if (!granted) return@LaunchedEffect
        val (a, t) = withContext(Dispatchers.IO) { val a = SmsRepo.load(ctx); a to SmsRepo.threads(ctx, a) }
        all = a; threads = t
    }
    // «СМС» из недавних/контакта — сразу переписка с этим номером
    LaunchedEffect(SmsNav.open, threads) {
        val a = SmsNav.open ?: return@LaunchedEffect
        if (all == null && granted) return@LaunchedEffect
        SmsNav.open = null
        open = threads.firstOrNull { SmsMarks.key(it.address) == SmsMarks.key(a) }
            ?: SmsThread(a, SmsRepo.contactName(ctx, a), Sms(0, a, "", System.currentTimeMillis(), true, true), 0, 0, false, null)
    }

    open?.let { th ->
        BackHandler { open = null; version++ }
        ThreadView(th, all.orEmpty(), onBack = { open = null; version++ }, onChanged = { version++ })
        return
    }

    Box(Modifier.fillMaxSize()) {
    ScreenScaffold(if (showSpam) "Спам" else "Сообщения", if (showSpam) ({ showSpam = false }) else null, actions = {
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Прочитать всё") }, { menu = false; SmsRepo.markAllRead(ctx); version++ }, leadingIcon = { Icon(Icons.Rounded.DoneAll, null) })
                DropdownMenuItem({ Text(if (showSpam) "Все сообщения" else "Спам (${threads.count { it.spam }})") }, { menu = false; showSpam = !showSpam }, leadingIcon = { Icon(Icons.Rounded.Report, null) })
                DropdownMenuItem({ Text("Как работает спам") }, { menu = false; Toast.makeText(ctx, "СМС от незнакомого номера — уведомление «Это спам?». «Да» — отправитель уходит в «Спам».", Toast.LENGTH_LONG).show() },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) })
            }
        }
    }) {
        if (!granted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    Text("Нужен доступ к СМС", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                    Text("Если Android пишет «Ограниченная настройка»: Настройки → Приложения → Всё в одном → ⋮ → «Разрешить ограниченные настройки», затем вернись сюда.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                PillButton("Дать доступ", {
                    if (denied) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    else perm.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                }, Modifier.fillMaxWidth())
                if (denied) PillButton("Проверить снова", { granted = SmsRepo.hasPermission(ctx); version++ }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
            }
            return@ScreenScaffold
        }
        val pinned = SmsMarks.pinned(ctx)
        val qq = q.trim()
        // поиск: по названию чата или по тексту любого сообщения в нём
        val hits: Map<String, Sms> = remember(qq, all) {
            if (qq.length < 2) emptyMap() else all.orEmpty().filter { it.body.contains(qq, true) }.groupBy { SmsMarks.key(it.address) }.mapValues { it.value.first() }
        }
        val list = threads.filter { t ->
            t.spam == showSpam && t.last.date > SmsMarks.hiddenAt(ctx, t.address) &&
                (qq.isEmpty() || (t.name ?: t.address).contains(qq, true) || SmsMarks.key(t.address) in hits)
        }.sortedWith(compareBy({ SmsMarks.key(it.address) !in pinned }, { -it.last.date }))
        if (all == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { AppTextField(q, { q = it }, "Поиск по чатам и сообщениям") }
            if (all != null && list.isEmpty()) item { EmptyHint(if (qq.isNotEmpty()) "Ничего не найдено" else if (showSpam) "Спама нет" else "Сообщений нет") }
            items(list, key = { it.address }) { t ->
                val hit = hits[SmsMarks.key(t.address)]
                val isPinned = SmsMarks.key(t.address) in pinned
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card)
                    .combinedClickable(onClick = { SmsRepo.markRead(ctx, t.address); open = t }, onLongClick = { threadMenu = t }).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (t.spam) Pal.Line else Pal.Sky), contentAlignment = Alignment.Center) {
                        if (t.name == null && t.address.count(Char::isDigit) >= 7) Icon(Icons.Rounded.PersonOff, null, tint = Pal.SkyInk, modifier = Modifier.size(22.dp))
                        else Text((t.name ?: t.address).take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Pal.SkyInk)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isPinned) Icon(Icons.Rounded.PushPin, "Закреплено", tint = Pal.Muted, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                            Text(t.name ?: t.address, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (t.unread > 0) FontWeight.ExtraBold else FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
                            Text(whenText((hit ?: t.last).date), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        }
                        Text((hit ?: t.last).body, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (t.unread > 0) Pal.Ink else Pal.Muted)
                        t.code?.let { Text("код $it", style = MaterialTheme.typography.labelLarge, color = Pal.LilacInk) }
                    }
                    if (t.unread > 0) { Spacer(Modifier.width(6.dp)); Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(Pal.SkyInk)) }
                }
            }
        }
    }
    // написать — круглая кнопка справа внизу
    if (granted) Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp).clip(CircleShape).background(Pal.Ink).clickable { compose = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Edit, "Написать", tint = Color.White)
    }
    }

    threadMenu?.let { t ->
        val isPinned = SmsMarks.key(t.address) in SmsMarks.pinned(ctx)
        val isNumber = t.address.count(Char::isDigit) >= 7 && t.address.none { it.isLetter() }
        BottomPanel({ threadMenu = null }) {
            Text(t.name ?: t.address, style = MaterialTheme.typography.titleMedium)
            MenuRow(Icons.Rounded.PushPin, if (isPinned) "Открепить" else "Закрепить") { SmsMarks.setPinned(ctx, t.address, !isPinned); threadMenu = null; version++ }
            MenuRow(Icons.Rounded.DoneAll, "Отметить прочитанным") { SmsRepo.markRead(ctx, t.address); threadMenu = null; version++ }
            MenuRow(Icons.Rounded.Report, if (t.spam) "Не спам" else "Это спам") { SmsRepo.markSpam(ctx, t.address, !t.spam); threadMenu = null; version++ }
            if (isNumber && t.name == null) MenuRow(Icons.Rounded.PersonAdd, "Добавить в контакты") { threadMenu = null; onNewContact(t.address) }
            MenuRow(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.RoseInk, sub = "из списка в приложении (сами СМС стереть может только приложение СМС по умолчанию); придёт новое — переписка вернётся") {
                SmsMarks.hide(ctx, t.address); threadMenu = null; version++
            }
        }
    }
    if (compose) NewMessageDialog(onDismiss = { compose = false }) { to -> compose = false; SmsNav.open = to }
}

/** Новое сообщение: кому (номер или контакт). */
@Composable
private fun NewMessageDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<com.pank.vsevodnom.ui.contacts.Contact>>(emptyList()) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { runCatching { com.pank.vsevodnom.ui.contacts.ContactsRepo.load(ctx) }.getOrDefault(emptyList()) } }
    val list = contacts.filter { it.phones.isNotEmpty() && (q.isBlank() || it.name.contains(q, true) || it.phones.any { p -> p.second.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" }) }) }.take(60)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Кому") }, text = {
        Column {
            AppTextField(q, { q = it }, "Имя или номер")
            LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 6.dp)) {
                if (q.count(Char::isDigit) >= 5) item {
                    Text("Написать на $q", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk, modifier = Modifier.fillMaxWidth().clickable { onPick(q.trim()) }.padding(10.dp))
                }
                list.forEach { c -> c.phones.forEach { (label, n) ->
                    item(key = "${c.id}:$n") {
                        Column(Modifier.fillMaxWidth().clickable { onPick(n) }.padding(10.dp)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium); Text("$n · $label", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                    }
                } }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@Composable
private fun ThreadView(t: SmsThread, all: List<Sms>, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var spam by remember { mutableStateOf(t.spam) }
    var extra by remember { mutableStateOf<List<Sms>>(emptyList()) }
    val norm = remember(t) { SmsMarks.key(t.address) }
    val msgs = remember(t, all, extra) { (all.filter { SmsMarks.key(it.address) == norm } + extra).distinctBy { it.id }.sortedBy { it.date } }
    var text by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val canReply = t.address.count(Char::isDigit) >= 3 && t.address.none { it.isLetter() }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok && p != null) { if (SmsSend.send(ctx, t.address, p)) text = "" }
        else if (p != null) runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(t.address))).putExtra("sms_body", p)) }
    }
    fun send() {
        val body = text.trim(); if (body.isEmpty()) return
        if (!SmsSend.can(ctx)) { pending = body; perm.launch(Manifest.permission.SEND_SMS); return }
        if (SmsSend.send(ctx, t.address, body)) {
            text = ""
            extra = extra + Sms(-System.currentTimeMillis(), t.address, body, System.currentTimeMillis(), true, false)
            scope.launch { kotlinx.coroutines.delay(1500); onChanged() }
        } else Toast.makeText(ctx, "Не отправилось", Toast.LENGTH_SHORT).show()
    }
    ScreenScaffold(t.name ?: t.address, onBack, actions = {
        if (t.address.count(Char::isDigit) >= 7) RoundIconButton(Icons.Rounded.Call, "Позвонить", { com.pank.vsevodnom.ui.contacts.ContactsRepo.call(ctx, t.address) })
        RoundIconButton(if (spam) Icons.Rounded.CheckCircle else Icons.Rounded.Block, if (spam) "Не спам" else "Это спам", {
            SmsRepo.markSpam(ctx, t.address, !spam); spam = !spam; onChanged()
        })
    }) {
        val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (msgs.size - 1).coerceAtLeast(0))
        LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1) }
        LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spam) item { AppCard(Modifier.fillMaxWidth(), color = Pal.Line, padding = 10.dp) { Text("Отмечено как спам. Кнопка вверху справа — вернуть во входящие.", style = MaterialTheme.typography.bodySmall) } }
            if (msgs.isEmpty()) item { EmptyHint("Сообщений ещё нет — напиши первое") }
            items(msgs, key = { it.id }) { m ->
                val code = SmsRepo.extractCode(m.body)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.inbox) Alignment.Start else Alignment.End) {
                    Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(if (m.inbox) Pal.Card else Pal.Sky).padding(12.dp)) {
                        SelectionContainer { SmsText(m.body, code) }
                        if (code != null) TextButton({ copySecret(ctx, "Код", code) }, contentPadding = PaddingValues(0.dp)) { Text("Скопировать $code") }
                    }
                    Text(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        if (canReply) Row(Modifier.fillMaxWidth().imePadding().padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Сообщение") }, shape = RoundedCornerShape(22.dp), maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (text.isBlank()) Pal.Line else Pal.Ink).clickable(enabled = text.isNotBlank()) { send() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", tint = Color.White)
            }
        }
    }
}
