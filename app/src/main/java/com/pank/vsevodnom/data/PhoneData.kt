package com.pank.vsevodnom.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import org.json.JSONObject

/**
 * Телефон: мессенджеры контакта, журнал звонков, быстрый набор, «пробив» номера.
 *
 * Мессенджеры берутся так же, как у звонилок Google/Samsung: Telegram, Viber и WhatsApp сами кладут в контакты Android
 * свои строки (вид данных vnd.…telegram…/whatsapp…/viber…) — если у человека есть аккаунт и в мессенджере включена
 * синхронизация контактов. Открытие строки = чат или звонок в этом мессенджере.
 */
object PhoneData {
    enum class App(val title: String, val color: Long, val pkg: String) {
        TELEGRAM("Telegram", 0xFF2AABEE, "org.telegram.messenger"),
        VIBER("Viber", 0xFF7360F2, "com.viber.voip"),
        WHATSAPP("WhatsApp", 0xFF25D366, "com.whatsapp"),
    }

    /** Строка мессенджера в контакте: чат, звонок или видеозвонок. */
    data class Msgr(val app: App, val kind: Kind, val dataId: Long, val mime: String, val label: String)
    enum class Kind { CHAT, CALL, VIDEO }

    private fun appOf(mime: String): App? = when {
        mime.contains("telegram", true) -> App.TELEGRAM
        mime.contains("viber", true) && !mime.contains("viber_out", true) && !mime.contains("none_viber", true) -> App.VIBER
        mime.contains("whatsapp", true) -> App.WHATSAPP
        else -> null
    }
    private fun kindOf(mime: String): Kind = when {
        mime.contains("video", true) -> Kind.VIDEO
        mime.contains("call", true) || mime.contains("voip", true) && !mime.contains("message", true) -> Kind.CALL
        else -> Kind.CHAT
    }

    /** Мессенджеры одного контакта, по приоритету: Telegram, Viber, WhatsApp. */
    fun messengers(ctx: Context, contactId: Long): List<Msgr> = runCatching {
        val out = ArrayList<Msgr>()
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA3),
            "${ContactsContract.Data.CONTACT_ID}=? AND (${ContactsContract.Data.MIMETYPE} LIKE '%telegram%' OR ${ContactsContract.Data.MIMETYPE} LIKE '%whatsapp%' OR ${ContactsContract.Data.MIMETYPE} LIKE '%viber%')",
            arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                val app = appOf(mime) ?: continue
                out += Msgr(app, kindOf(mime), c.getLong(0), mime, c.getString(2).orEmpty())
            }
        }
        out.distinctBy { it.app to it.kind }.sortedWith(compareBy({ it.app.ordinal }, { it.kind.ordinal }))
    }.getOrDefault(emptyList())

    /** У каких контактов какие мессенджеры (для значков в списке). */
    fun messengersAll(ctx: Context): Map<Long, Set<App>> = runCatching {
        val out = HashMap<Long, MutableSet<App>>()
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.MIMETYPE} LIKE '%telegram%' OR ${ContactsContract.Data.MIMETYPE} LIKE '%whatsapp%' OR ${ContactsContract.Data.MIMETYPE} LIKE '%viber%'", null, null)?.use { c ->
            while (c.moveToNext()) appOf(c.getString(1) ?: "")?.let { out.getOrPut(c.getLong(0)) { HashSet() } += it }
        }
        out as Map<Long, Set<App>>
    }.getOrDefault(emptyMap())

    /** Открыть чат/звонок мессенджера из строки контакта. */
    fun open(ctx: Context, m: Msgr) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, m.dataId), m.mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** Международный вид без «+»: 0671234567 → 380671234567. */
    fun intl(number: String): String {
        val d = number.filter(Char::isDigit)
        return when { d.length == 10 && d.startsWith("0") -> "38$d"; d.length == 9 -> "380$d"; else -> d }
    }

    /** Чат по номеру (номер не в контактах или мессенджер не синхронизирован): откроется, если у номера есть аккаунт. */
    fun openByNumber(ctx: Context, app: App, number: String) = runCatching {
        val n = intl(number)
        val uri = when (app) {
            App.TELEGRAM -> "tg://resolve?phone=$n"
            App.VIBER -> "viber://chat?number=%2B$n"
            App.WHATSAPP -> "https://wa.me/$n"
        }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun installed(ctx: Context, app: App) = runCatching { ctx.packageManager.getPackageInfo(app.pkg, 0); true }.getOrDefault(false)

    // ---------- журнал звонков ----------
    /** Удалить записи журнала (нужно разрешение «журнал звонков: запись»). */
    fun deleteCalls(ctx: Context, ids: List<Long>): Boolean = runCatching {
        ids.chunked(200).forEach { ch -> ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ch.joinToString(",")})", null) }
        true
    }.getOrDefault(false)

    // ---------- быстрый набор: 2…9 → номер (prefs phone/speed) ----------
    private fun sp(ctx: Context) = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
    fun speedDial(ctx: Context): Map<Int, Pair<String, String>> = runCatching {
        val o = JSONObject(sp(ctx).getString("speed", "{}")!!)
        o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { n -> val v = o.getJSONObject(k); n to (v.optString("name") to v.optString("num")) } }.toMap()
    }.getOrDefault(emptyMap())
    fun setSpeedDial(ctx: Context, key: Int, name: String?, number: String?) {
        val o = runCatching { JSONObject(sp(ctx).getString("speed", "{}")!!) }.getOrDefault(JSONObject())
        if (number.isNullOrBlank()) o.remove(key.toString()) else o.put(key.toString(), JSONObject().put("name", name.orEmpty()).put("num", number))
        sp(ctx).edit().putString("speed", o.toString()).apply()
    }
    var keySounds: Boolean = true
    fun soundsOn(ctx: Context) = sp(ctx).getBoolean("key_sounds", true)
    fun setSoundsOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("key_sounds", v).apply()

    // ---------- «пробив» номера: открытые источники (своей базы, как у Getcontact, нет) ----------
    data class Lookup(val title: String, val sub: String, val url: String)
    fun lookups(number: String): List<Lookup> {
        val n = intl(number); val local = if (n.startsWith("380")) "0" + n.drop(3) else n
        val q = Uri.encode("\"$local\" OR \"+$n\"")
        return listOf(
            Lookup("Поиск Google", "отзывы, объявления, сайты с этим номером", "https://www.google.com/search?q=$q"),
            Lookup("Отзывы о номере: «кто звонил»", "жалобы на спам и мошенников", "https://www.google.com/search?q=" + Uri.encode("$local кто звонил отзывы")),
            Lookup("Telegram", "есть ли аккаунт с этим номером (если владелец не скрыл)", "tg://resolve?phone=$n"),
            Lookup("Viber", "откроется чат, если номер есть в Viber", "viber://chat?number=%2B$n"),
            Lookup("WhatsApp", "откроется чат, если номер есть в WhatsApp", "https://wa.me/$n"),
        )
    }

    /** Сохранить настройку «рингтон контакта». */
    fun setRingtone(ctx: Context, contactId: Long, uri: Uri?) = runCatching {
        ctx.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, uri?.toString()) }, null, null)
    }

    fun deleteContact(ctx: Context, id: Long, lookup: String) = runCatching {
        ctx.contentResolver.delete(ContactsContract.Contacts.getLookupUri(id, lookup), null, null) > 0
    }.getOrDefault(false)

    fun shareContact(ctx: Context, lookup: String, name: String) = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться контактом")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
