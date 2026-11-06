package com.pank.vsevodnom.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R

data class Sms(val id: Long, val address: String, val body: String, val date: Long, val read: Boolean, val inbox: Boolean)

data class SmsThread(val address: String, val name: String?, val last: Sms, val unread: Int, val count: Int, val spam: Boolean, val code: String?)

object SmsRepo {
    // код подтверждения: 4–8 цифр рядом со словами «код», «code», «пароль», «OTP»… (ru/uk/en)
    private val CODE_WORDS = Regex("(код|kod|code|пароль|парол|password|otp|pin|підтвердж|подтвержд|verification|одноразов)", RegexOption.IGNORE_CASE)
    private val CODE = Regex("(?<![\\d+])(\\d{3}[- ]?\\d{3}|\\d{4,8})(?!\\d)")

    /** Достаёт код подтверждения из текста, если он там есть. */
    fun extractCode(body: String): String? {
        if (!CODE_WORDS.containsMatchIn(body)) return null
        // предпочитаем число, стоящее после ключевого слова
        val kw = CODE_WORDS.find(body)!!
        val after = CODE.find(body, kw.range.first)
        val m = after ?: CODE.find(body) ?: return null
        // отсекаем похожее на суммы/даты: «1500 грн», «2026»
        val tail = body.substring(m.range.last + 1).trimStart().take(4).lowercase()
        if (tail.startsWith("грн") || tail.startsWith("uah") || tail.startsWith("₴") || tail.startsWith("руб")) return CODE.findAll(body).map { it.value }.firstOrNull { it != m.value }?.replace(Regex("[- ]"), "")
        return m.value.replace(Regex("[- ]"), "")
    }

    // ---- спам ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sms", Context.MODE_PRIVATE)
    private val DEFAULT_SPAM_WORDS = listOf("казино", "casino", "выигр", "виграл", "кредит онлайн", "займ", "позик", "ставк", "бонус до", "розыгрыш", "розіграш", "приз", "акция!", "знижка до", "скидка до")

    /** Отметки «прочитано» храним у себя: системный флаг может менять только основное СМС-приложение. */
    fun readAllAt(ctx: Context) = prefs(ctx).getLong("read_all", 0)
    fun markAllRead(ctx: Context) = prefs(ctx).edit().putLong("read_all", System.currentTimeMillis()).apply()
    fun readAt(ctx: Context, address: String) = prefs(ctx).getLong("read_" + address.filter { it.isLetterOrDigit() }, 0)
    fun markRead(ctx: Context, address: String) = prefs(ctx).edit().putLong("read_" + address.filter { it.isLetterOrDigit() }, System.currentTimeMillis()).apply()

    fun spamSenders(ctx: Context): Set<String> = prefs(ctx).getStringSet("spam", emptySet())!!
    fun hamSenders(ctx: Context): Set<String> = prefs(ctx).getStringSet("ham", emptySet())!!
    fun spamWords(ctx: Context): List<String> = prefs(ctx).getString("words", null)?.split('\n')?.filter { it.isNotBlank() } ?: DEFAULT_SPAM_WORDS
    fun setSpamWords(ctx: Context, w: List<String>) = prefs(ctx).edit().putString("words", w.joinToString("\n")).apply()

    fun markSpam(ctx: Context, address: String, spam: Boolean) {
        val s = spamSenders(ctx).toMutableSet(); val h = hamSenders(ctx).toMutableSet()
        if (spam) { s += address; h -= address } else { s -= address; h += address }
        prefs(ctx).edit().putStringSet("spam", s).putStringSet("ham", h).apply()
    }

    fun isSpam(ctx: Context, address: String, body: String, isContact: Boolean): Boolean {
        if (address in hamSenders(ctx) || isContact) return false
        if (address in spamSenders(ctx)) return true
        if (extractCode(body) != null) return false
        val low = body.lowercase()
        return spamWords(ctx).any { it.isNotBlank() && low.contains(it.lowercase()) }
    }

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun contactName(ctx: Context, address: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (address.none { it.isDigit() }) return null
        return runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun load(ctx: Context, limit: Int = 3000): List<Sms> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<Sms>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
                while (c.moveToNext()) out += Sms(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getLong(3), c.getInt(4) == 1, c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        return out
    }

    fun threads(ctx: Context, all: List<Sms>): List<SmsThread> {
        val names = HashMap<String, String?>()
        return all.groupBy { normalize(it.address) }.map { (_, list) ->
            val last = list.first()
            val name = names.getOrPut(last.address) { contactName(ctx, last.address) }
            val code = list.firstOrNull { it.inbox }?.takeIf { System.currentTimeMillis() - it.date < 24 * 3600_000L }?.let { extractCode(it.body) }
            val seen = maxOf(readAllAt(ctx), readAt(ctx, last.address))
            SmsThread(last.address, name, last, list.count { it.inbox && !it.read && it.date > seen }, list.size, isSpam(ctx, last.address, last.body, name != null), code)
        }
    }

    private fun normalize(a: String) = a.filter { it.isLetterOrDigit() }.let { if (it.length >= 9 && it.all(Char::isDigit)) it.takeLast(9) else it.lowercase() }

    // ---- уведомления ----
    const val CH_SMS = "sms"
    const val CH_SPAM = "sms_spam"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SMS, "СМС", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SPAM, "СМС-спам", NotificationManager.IMPORTANCE_MIN))
    }

    fun boldCode(body: String, code: String?): CharSequence {
        if (code == null) return body
        val s = SpannableString(body)
        val raw = Regex(code.toCharArray().joinToString("[- ]?")).find(body) ?: return body
        s.setSpan(StyleSpan(Typeface.BOLD), raw.range.first, raw.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}

/** Новое СМС: своё уведомление с жирным кодом и кнопкой «Скопировать». Спам — тихо. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val address = parts.firstOrNull()?.displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        SmsRepo.ensureChannels(ctx)
        val name = SmsRepo.contactName(ctx, address)
        val spam = SmsRepo.isSpam(ctx, address, body, name != null)
        val code = SmsRepo.extractCode(body)
        val open = PendingIntent.getActivity(ctx, address.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("route", "sms"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, if (spam) SmsRepo.CH_SPAM else SmsRepo.CH_SMS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (code != null) "Код: $code · ${name ?: address}" else (name ?: address) + if (spam) " · спам" else "")
            .setContentText(SmsRepo.boldCode(body, code))
            .setStyle(NotificationCompat.BigTextStyle().bigText(SmsRepo.boldCode(body, code)))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (code != null) {
            val copy = PendingIntent.getBroadcast(ctx, code.hashCode(), Intent(ctx, CopyCodeReceiver::class.java).putExtra("code", code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Скопировать $code", copy)
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify((address + body).hashCode(), b.build()) }
    }
}

class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Код", code))
        Toast.makeText(ctx, "Код $code скопирован", Toast.LENGTH_SHORT).show()
    }
}
