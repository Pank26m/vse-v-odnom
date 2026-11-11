package com.goodwy.keyboard.vse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Набрал номер телефона (+380…, 0…) — ищем его в контактах; если такого нет — кнопка «Сохранить контакт?».
 */
object VsePhone {
    /** Номер, которого нет в контактах (или null). */
    @Volatile var unknown: String? = null
        private set
    private var last = ""
    private val RX = Regex("(\\+?\\d[\\d ()\\-]{8,}\\d)$")

    /** Проверить текст перед курсором: набран номер, которого нет в контактах? true — что-то поменялось. */
    suspend fun update(ctx: Context, before: String): Boolean {
        val m = RX.find(before.takeLast(30).trimEnd())?.value
        val digits = m?.filter { it.isDigit() || it == '+' }.orEmpty()
        val ok = digits.count { it.isDigit() } in 10..13 && (digits.startsWith("+") || digits.startsWith("0"))
        val num = if (ok) digits else ""
        if (num == last) return false
        last = num
        unknown = if (num.isEmpty()) null else withContext(Dispatchers.IO) { if (known(ctx, num)) null else num }
        return true
    }

    private fun known(ctx: Context, num: String): Boolean = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: true
    }.getOrDefault(true)   // нет доступа к контактам — не предлагаем

    fun save(ctx: Context, num: String) {
        runCatching {
            ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, num).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        unknown = null; VseIme.rebuildChips(ctx)
    }

    /** Карточка контакта для отправки: имя и все номера (без лишних подробностей). */
    data class Card(val id: Long, val name: String, val phones: List<String>)

    fun search(ctx: Context, q: String, limit: Int = 300): List<Card> = runCatching {
        val sel = if (q.isBlank()) null else "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val args = if (q.isBlank()) null else arrayOf("%$q%")
        val out = LinkedHashMap<Long, Card>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER),
            sel?.replace(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY), args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext() && out.size <= limit) {
                val id = c.getLong(0); val name = c.getString(1) ?: continue; val num = c.getString(2) ?: continue
                val prev = out[id]
                val norm = num.filter { it.isDigit() || it == '+' }
                if (prev == null) out[id] = Card(id, name, listOf(num))
                else if (prev.phones.none { it.filter { ch -> ch.isDigit() || ch == '+' }.takeLast(9) == norm.takeLast(9) }) out[id] = prev.copy(phones = prev.phones + num)
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())

    fun text(c: Card) = (listOf(c.name) + c.phones).joinToString("\n")
}
