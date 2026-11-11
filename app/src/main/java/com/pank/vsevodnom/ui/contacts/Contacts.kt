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

data class Contact(val id: Long, val lookup: String, val name: String, val starred: Boolean, val phones: List<Pair<String, String>>, val groups: Set<Long>, val photo: String? = null, val ringtone: String? = null)
data class Group(val id: Long, val title: String)
data class CallEntry(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long, val id: Long = 0)

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
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.CUSTOM_RINGTONE),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Contact(id, c.getString(1) ?: "", c.getString(2) ?: "(без имени)", c.getInt(3) == 1, phones[id].orEmpty(), groups[id].orEmpty(), c.getString(4), c.getString(5))
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
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls._ID),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit")?.use { c ->
                while (c.moveToNext()) out += CallEntry(c.getString(0) ?: "", c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getLong(5))
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
        // системный чёрный список (работает, когда приложение — звонилка по умолчанию): блокирует и звонки, и СМС
        runCatching {
            if (android.provider.BlockedNumberContract.canCurrentUserBlockNumbers(ctx)) {
                val cr = ctx.contentResolver
                if (b) cr.insert(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, ContentValues().apply {
                    put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                }) else cr.delete(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    "${android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}=?", arrayOf(number))
            }
        }
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
