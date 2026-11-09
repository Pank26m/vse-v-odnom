package com.pank.vsevodnom.ui.contacts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Contact(val id: Long, val lookup: String, val name: String, val starred: Boolean, val phones: List<Pair<String, String>>, val groups: Set<Long>)
data class Group(val id: Long, val title: String)
data class CallEntry(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long)

object ContactsRepo {
    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun load(ctx: Context): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val cr = ctx.contentResolver
        val phones = HashMap<Long, MutableList<Pair<String, String>>>()
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                phones.getOrPut(c.getLong(0)) { ArrayList() }.let { l -> val n = c.getString(1) ?: ""; if (l.none { it.second.filter(Char::isDigit) == n.filter(Char::isDigit) }) l += label to n }
            }
        }
        val groups = HashMap<Long, MutableSet<Long>>()
        cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE}=?", arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) groups.getOrPut(c.getLong(0)) { HashSet() } += c.getLong(1)
        }
        val out = ArrayList<Contact>()
        cr.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Contact(id, c.getString(1) ?: "", c.getString(2) ?: "(без имени)", c.getInt(3) == 1, phones[id].orEmpty(), groups[id].orEmpty())
            }
        }
        return out
    }

    fun groups(ctx: Context): List<Group> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Group>()
        ctx.contentResolver.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
            "${ContactsContract.Groups.DELETED}=0", null, null)?.use { c -> while (c.moveToNext()) out += Group(c.getLong(0), c.getString(1) ?: "") }
        // системные группы Google дублируются по аккаунтам и называются по-английски — переводим и схлопываем
        return out.map { it.copy(title = when (it.title) { "My Contacts", "Starred in Android" -> ""; "Friends" -> "Друзья"; "Family" -> "Семья"; "Coworkers" -> "Коллеги"; else -> it.title }) }
            .filter { it.title.isNotBlank() }.distinctBy { it.title }
    }

    fun setStarred(ctx: Context, c: Contact, star: Boolean) {
        ctx.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.id), ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (star) 1 else 0) }, null, null)
    }

    /** Создать группу на первом аккаунте контакта. */
    fun createGroup(ctx: Context, title: String) {
        ctx.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, ContentValues().apply { put(ContactsContract.Groups.TITLE, title); put(ContactsContract.Groups.GROUP_VISIBLE, 1) })
    }

    fun setInGroup(ctx: Context, c: Contact, groupId: Long, inGroup: Boolean) {
        val cr = ctx.contentResolver
        // нужен raw-контакт
        val raw = cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID), "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(c.id.toString()), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return
        if (inGroup) cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, raw); put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
        }) else cr.delete(ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(c.id.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()))
    }

    fun calls(ctx: Context, limit: Int = 200): List<CallEntry> {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<CallEntry>()
        runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit")?.use { c ->
                while (c.moveToNext()) out += CallEntry(c.getString(0) ?: "", c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    fun call(ctx: Context, number: String) {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        com.pank.vsevodnom.call.CallState.place(ctx, number)
    }

    // ---- чёрный список и определитель ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("calls", Context.MODE_PRIVATE)
    fun norm(n: String) = n.filter(Char::isDigit).takeLast(9)
    fun blocked(ctx: Context): Set<String> = prefs(ctx).getStringSet("blocked", emptySet())!!
    fun setBlocked(ctx: Context, number: String, b: Boolean) {
        val s = blocked(ctx).toMutableSet(); if (b) s += norm(number) else s -= norm(number)
        prefs(ctx).edit().putStringSet("blocked", s).apply()
    }
    fun blockHidden(ctx: Context) = prefs(ctx).getBoolean("block_hidden", false)
    fun setBlockHidden(ctx: Context, b: Boolean) = prefs(ctx).edit().putBoolean("block_hidden", b).apply()
}

/** Определитель: показывает, кто звонит, блокирует номера из чёрного списка. */
class CallScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart ?: ""
        val name = com.pank.vsevodnom.data.SmsRepo.contactName(this, number)
        val blocked = number.isNotBlank() && ContactsRepo.norm(number) in ContactsRepo.blocked(this)
        val hidden = number.isBlank()
        val reject = blocked || (hidden && ContactsRepo.blockHidden(this))
        val r = CallResponse.Builder()
        if (reject) r.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false)
        respondToCall(details, r.build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        if (name == null) notifyUnknown(number, reject)
    }

    private fun notifyUnknown(number: String, rejected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("calls", "Определитель номера", NotificationManager.IMPORTANCE_HIGH))
        val intl = number.startsWith("+") && !number.startsWith("+380")
        val text = when {
            rejected -> "Заблокирован (чёрный список)"
            number.isBlank() -> "Скрытый номер"
            intl -> "Иностранный номер — частый признак мошенников"
            else -> "Номера нет в контактах"
        }
        val block = PendingIntent.getBroadcast(this, number.hashCode(), Intent(this, BlockNumberReceiver::class.java).putExtra("n", number), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java).putExtra("route", "contacts"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "calls").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (number.isBlank()) "Звонок со скрытого номера" else "Звонит $number").setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(open).setAutoCancel(true).setTimeoutAfter(120_000)
        if (number.isNotBlank() && !rejected) n.addAction(0, "В чёрный список", block)
        if (com.pank.vsevodnom.data.NotifPrefs.on(this, "callerid")) runCatching { nm.notify(60, n.build()) }
    }
}

class BlockNumberReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        intent.getStringExtra("n")?.let { ContactsRepo.setBlocked(ctx, it, true) }
        ctx.getSystemService(NotificationManager::class.java).cancel(60)
        android.widget.Toast.makeText(ctx, "Номер в чёрном списке", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ================= Экран =================

@Composable
fun ContactsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var calls by remember { mutableStateOf<List<CallEntry>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<Long?>(null) }
    var open by remember { mutableStateOf<Contact?>(null) }
    var settings by remember { mutableStateOf(false) }
    var dial by remember { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    LaunchedEffect(Unit) {
        perm.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE))
    }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) { Triple(runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList()), runCatching { ContactsRepo.groups(ctx) }.getOrDefault(emptyList()), ContactsRepo.calls(ctx)) }
            .let { (c, g, l) -> contacts = c; groups = g; calls = l }
    }
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }

    if (dial) { BackHandler { dial = false }; Box(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) { com.pank.vsevodnom.call.DialPad { dial = false } }; return }
    open?.let { c ->
        BackHandler { open = null }
        ContactDetail(contacts?.firstOrNull { it.id == c.id } ?: c, groups, onBack = { open = null }, onChanged = { version++ },
            onEdit = { editLauncher.launch(Intent(Intent.ACTION_EDIT, ContactsContract.Contacts.getLookupUri(c.id, c.lookup)).putExtra("finishActivityOnSaveCompleted", true)) })
        return
    }

    Box(Modifier.fillMaxSize()) {
    ScreenScaffold("Контакты", onBack, actions = {
        RoundIconButton(Icons.Rounded.PersonAdd, "Новый контакт", { editLauncher.launch(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).putExtra("finishActivityOnSaveCompleted", true)) })
        RoundIconButton(Icons.Rounded.Shield, "Определитель", { settings = true })
    }) {
        if (!ContactsRepo.has(ctx, Manifest.permission.READ_CONTACTS)) {
            Column(Modifier.padding(16.dp)) {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) { Text("Нужен доступ к контактам и журналу звонков.", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(10.dp))
                PillButton("Дать доступ", { perm.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)) }, Modifier.fillMaxWidth())
            }
            return@ScreenScaffold
        }
        Segmented(listOf("Контакты", "Недавние"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Sky)
        Spacer(Modifier.height(10.dp))
        if (tab == 1) { RecentCalls(calls, contacts.orEmpty()) { version++ }; return@ScreenScaffold }
        val all = contacts.orEmpty()
        val fav = all.filter { it.starred && it.phones.isNotEmpty() }
        val list = all.filter { (q.isBlank() || it.name.contains(q, true) || it.phones.any { p -> p.second.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifBlank { "\u0000" }) }) && (group == null || group in it.groups) }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AppTextField(q, { q = it }, "Поиск: имя или номер") }
            // избранные — плитками: нажатие на плитку — детали, кнопка снизу — сразу звонок
            if (fav.isNotEmpty() && q.isBlank() && group == null) item {
                Column {
                    SectionLabel("Избранные")
                    Spacer(Modifier.height(6.dp))
                    fav.chunked(3).forEach { row ->
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { c ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Pal.Card).clickable { open = c }.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Avatar(c.name, 54)
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
            if (groups.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Chip("Все", group == null, { group = null }) }
                    items(groups, key = { it.id }) { g -> Chip(g.title, group == g.id, { group = if (group == g.id) null else g.id }) }
                }
            }
            if (contacts == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            items(list, key = { it.id }) { c ->
                AppCard(Modifier.fillMaxWidth(), padding = 10.dp, onClick = { open = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            c.phones.firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (c.starred) Icon(Icons.Rounded.Star, null, tint = Pal.ButterInk, modifier = Modifier.size(18.dp))
                        c.phones.firstOrNull()?.let { p -> IconButton({ ContactsRepo.call(ctx, p.second) }) { Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk) } }
                    }
                }
            }
        }
    }

    if (ContactsRepo.has(ctx, Manifest.permission.READ_CONTACTS)) Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(62.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF34C77B)).clickable { dial = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Dialpad, "Набрать номер", tint = Color.White)
    }
    }

    if (settings) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        val held = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        var hidden by remember { mutableStateOf(ContactsRepo.blockHidden(ctx)) }
        var blocked by remember { mutableStateOf(ContactsRepo.blocked(ctx).toList()) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Определитель номера") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(if (held) "Включён: при звонке с незнакомого номера придёт уведомление с подсказкой, номера из чёрного списка сбрасываются." else
                            "Выключен. Включи — Android спросит, разрешить ли приложению проверять звонки. Основная звонилка остаётся прежней.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!held) item { PillButton("Включить", { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }, Modifier.fillMaxWidth()) }
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
                    item { Text("«Как я подписан у других» (как в Getcontact) сделать нельзя: эти данные есть только в их закрытой базе.", style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = { TextButton({ settings = false }) { Text("Готово") } })
    }
}

@Composable
private fun Avatar(name: String, size: Int) {
    val t = Tints.all[(name.hashCode() and 0x7fffffff) % 6]
    Box(Modifier.size(size.dp).clip(CircleShape).background(t.bg), contentAlignment = Alignment.Center) {
        Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), color = t.ink,
            style = if (size > 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ContactDetail(c: Contact, groups: List<Group>, onBack: () -> Unit, onChanged: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newGroup by remember { mutableStateOf(false) }
    ScreenScaffold(c.name, onBack, actions = {
        RoundIconButton(if (c.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Избранное", {
            scope.launch(Dispatchers.IO) { runCatching { ContactsRepo.setStarred(ctx, c, !c.starred) }; withContext(Dispatchers.Main) { onChanged() } }
        }, tint = if (c.starred) Pal.ButterInk else Pal.Ink)
        RoundIconButton(Icons.Rounded.Edit, "Изменить", onEdit)
    }) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(c.name, 96) } }
            items(c.phones) { (label, num) ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(num, style = MaterialTheme.typography.titleMedium); Text(label, style = MaterialTheme.typography.bodySmall) }
                        IconButton({ ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))) }) { Icon(Icons.Rounded.Sms, "СМС", tint = Pal.SkyInk) }
                        IconButton({ ContactsRepo.call(ctx, num) }) { Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk) }
                    }
                }
            }
            item { SectionLabel("Группы") }
            item {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        Chip(g.title, g.id in c.groups, {
                            scope.launch(Dispatchers.IO) { runCatching { ContactsRepo.setInGroup(ctx, c, g.id, g.id !in c.groups) }; withContext(Dispatchers.Main) { onChanged() } }
                        })
                    }
                    Chip("+ группа", false, { newGroup = true })
                }
            }
        }
    }
    if (newGroup) {
        var t by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newGroup = false }, title = { Text("Новая группа") }, text = { AppTextField(t, { t = it }, "Название") },
            confirmButton = { TextButton({ if (t.isNotBlank()) scope.launch(Dispatchers.IO) { runCatching { ContactsRepo.createGroup(ctx, t.trim()) }; withContext(Dispatchers.Main) { onChanged() } }; newGroup = false }) { Text("Создать") } },
            dismissButton = { TextButton({ newGroup = false }) { Text("Отмена") } })
    }
}

@Composable
private fun RecentCalls(calls: List<CallEntry>, contacts: List<Contact>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val blocked = ContactsRepo.blocked(ctx)
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (calls.isEmpty()) item { EmptyHint("Журнал звонков пуст или нет доступа") }
        items(calls) { c ->
            val (icon, tint) = when (c.type) {
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed to Pal.RoseInk
                CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade to Pal.SkyInk
                CallLog.Calls.BLOCKED_TYPE -> Icons.Rounded.Block to Pal.Muted
                else -> Icons.AutoMirrored.Rounded.CallReceived to Pal.MintInk
            }
            var menu by remember { mutableStateOf(false) }
            AppCard(Modifier.fillMaxWidth(), padding = 10.dp, onClick = { ContactsRepo.call(ctx, c.number) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name ?: c.number.ifBlank { "Скрытый номер" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        val t = Instant.ofEpochMilli(c.date).atZone(ZoneId.systemDefault())
                        Text(
                            (if (t.toLocalDate() == LocalDate.now()) t.format(DateTimeFormatter.ofPattern("HH:mm")) else t.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))) +
                                (if (c.duration > 0) " · ${c.duration / 60}:%02d".format(c.duration % 60) else "") +
                                (if (ContactsRepo.norm(c.number) in blocked) " · в чёрном списке" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Написать СМС") }, { menu = false; ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))) })
                            if (c.name == null && c.number.isNotBlank()) DropdownMenuItem({ Text("Сохранить контакт") }, {
                                menu = false
                                ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, c.number))
                            })
                            val isB = ContactsRepo.norm(c.number) in blocked
                            if (c.number.isNotBlank()) DropdownMenuItem({ Text(if (isB) "Разблокировать" else "В чёрный список") }, { menu = false; ContactsRepo.setBlocked(ctx, c.number, !isB); onChanged() })
                        }
                    }
                }
            }
        }
    }
}
