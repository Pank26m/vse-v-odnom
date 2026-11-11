package com.pank.vsevodnom.ui.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import java.io.ByteArrayOutputStream

/**
 * Свой редактор контактов (без перехода в Google Контакты): имя, номера, почты, работа, день рождения, заметка, фото.
 * Пишет прямо в контакты Android — синхронизация с Google идёт как обычно (контакт создаётся в аккаунте Google, если он есть).
 */
object ContactEdit {
    data class Num(val type: Int, val number: String)
    data class Data(
        val rawId: Long? = null,
        val given: String = "", val family: String = "",
        val phones: List<Num> = listOf(Num(Phone.TYPE_MOBILE, "")),
        val emails: List<String> = emptyList(),
        val company: String = "", val birthday: String = "", val note: String = "",
        val photo: Bitmap? = null, val photoChanged: Boolean = false,
    )

    val PHONE_TYPES = listOf(Phone.TYPE_MOBILE to "Мобильный", Phone.TYPE_HOME to "Домашний", Phone.TYPE_WORK to "Рабочий", Phone.TYPE_OTHER to "Другой")
    fun typeName(t: Int) = PHONE_TYPES.firstOrNull { it.first == t }?.second ?: "Другой"

    fun load(ctx: Context, contactId: Long): Data {
        val cr = ctx.contentResolver
        val rawId = cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(contactId.toString()), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
        var d = Data(rawId = rawId, phones = emptyList())
        cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.MIMETYPE, "data1", "data2", "data3", "data5"),
            "${ContactsContract.Data.CONTACT_ID}=?", arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val v1 = c.getString(1).orEmpty()
                when (c.getString(0)) {
                    StructuredName.CONTENT_ITEM_TYPE -> d = d.copy(given = c.getString(2).orEmpty().ifBlank { if (d.given.isBlank()) v1 else d.given }, family = c.getString(3).orEmpty())
                    Phone.CONTENT_ITEM_TYPE -> if (d.phones.none { it.number.filter(Char::isDigit) == v1.filter(Char::isDigit) }) d = d.copy(phones = d.phones + Num(c.getInt(2), v1))
                    Email.CONTENT_ITEM_TYPE -> if (v1.isNotBlank() && v1 !in d.emails) d = d.copy(emails = d.emails + v1)
                    Organization.CONTENT_ITEM_TYPE -> d = d.copy(company = v1)
                    Note.CONTENT_ITEM_TYPE -> d = d.copy(note = v1)
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(2) == Event.TYPE_BIRTHDAY) d = d.copy(birthday = v1)
                }
            }
        }
        if (d.phones.isEmpty()) d = d.copy(phones = listOf(Num(Phone.TYPE_MOBILE, "")))
        return d
    }

    /** Аккаунт для нового контакта: Google, если на телефоне есть контакты Google, иначе — телефон. */
    private fun account(ctx: Context): Pair<String?, String?> = runCatching {
        ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=?", arrayOf("com.google"), null)?.use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
    }.getOrNull() ?: (null to null)

    /** Сохранить; вернуть id контакта (null — ошибка). */
    fun save(ctx: Context, d: Data): Boolean = runCatching {
        val ops = ArrayList<ContentProviderOperation>()
        val isNew = d.rawId == null
        fun ins(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.MIMETYPE, mime)
            return if (isNew) b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0) else b.withValue(ContactsContract.Data.RAW_CONTACT_ID, d.rawId)
        }
        if (isNew) {
            val (type, name) = account(ctx)
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, type).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, name).build()
        } else {
            // старые строки этих видов — удалить и записать заново
            fun del(mime: String, extra: String = "", args: Array<String> = emptyArray()) {
                ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?$extra", arrayOf(d.rawId.toString(), mime) + args).build()
            }
            del(StructuredName.CONTENT_ITEM_TYPE); del(Phone.CONTENT_ITEM_TYPE); del(Email.CONTENT_ITEM_TYPE); del(Organization.CONTENT_ITEM_TYPE); del(Note.CONTENT_ITEM_TYPE)
            del(Event.CONTENT_ITEM_TYPE, " AND data2=?", arrayOf(Event.TYPE_BIRTHDAY.toString()))
            if (d.photoChanged) del(Photo.CONTENT_ITEM_TYPE)
        }
        ops += ins(StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.GIVEN_NAME, d.given.trim()).withValue(StructuredName.FAMILY_NAME, d.family.trim()).build()
        d.phones.filter { it.number.isNotBlank() }.forEach { ops += ins(Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, it.number.trim()).withValue(Phone.TYPE, it.type).build() }
        d.emails.filter { it.isNotBlank() }.forEach { ops += ins(Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, it.trim()).withValue(Email.TYPE, Email.TYPE_HOME).build() }
        if (d.company.isNotBlank()) ops += ins(Organization.CONTENT_ITEM_TYPE).withValue(Organization.COMPANY, d.company.trim()).build()
        if (d.note.isNotBlank()) ops += ins(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, d.note.trim()).build()
        if (d.birthday.isNotBlank()) ops += ins(Event.CONTENT_ITEM_TYPE).withValue(Event.START_DATE, d.birthday.trim()).withValue(Event.TYPE, Event.TYPE_BIRTHDAY).build()
        if (d.photoChanged && d.photo != null) {
            val bytes = ByteArrayOutputStream().use { d.photo.compress(Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray() }
            ops += ins(Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, bytes).build()
        }
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)

    /** Фото из галереи → квадрат 720 px. */
    fun squarePhoto(ctx: Context, uri: android.net.Uri): Bitmap? = runCatching {
        val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
        val s = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        Bitmap.createScaledBitmap(sq, minOf(720, s), minOf(720, s), true)
    }.getOrNull()
}
