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
    private val BARE = Regex("(?<![0-9+.,])([0-9]{6}|[0-9]{4})(?![0-9.,])")

    /**
     * Код из СМС. Со словами «код/пароль/…» — как раньше. Без слов — только от незнакомых отправителей (не из контактов):
     * отдельно стоящие 4 или 6 цифр, не сумма и не год.
     */
    fun extractCode(body: String, fromContact: Boolean = true): String? {
        if (!CODE_WORDS.containsMatchIn(body)) {
            if (fromContact) return null
            return BARE.findAll(body).firstOrNull { m ->
                val tail = body.substring(m.range.last + 1).trimStart().take(4).lowercase()
                val v = m.value
                !(tail.startsWith("грн") || tail.startsWith("uah") || tail.startsWith("₴") || tail.startsWith("$") || tail.startsWith("руб")) &&
                    !(v.length == 4 && (v.startsWith("19") || v.startsWith("20")))
            }?.value
        }
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

    /** Спам — только то, что ты сам отметил («Это спам?» → Да). Фильтра по словам больше нет. */
    fun isSpam(ctx: Context, address: String, @Suppress("UNUSED_PARAMETER") body: String, isContact: Boolean): Boolean {
        if (isContact) return false
        return address in spamSenders(ctx)
    }

    /** Спрашивать «Это спам?» — если отправитель не контакт и ещё не отмечен ни так, ни так. */
    fun shouldAsk(ctx: Context, address: String, isContact: Boolean) = !isContact && address !in spamSenders(ctx) && address !in hamSenders(ctx)

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
            val code = list.firstOrNull { it.inbox }?.takeIf { System.currentTimeMillis() - it.date < 24 * 3600_000L }?.let { extractCode(it.body, fromContact = name != null) }
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
        val code = SmsRepo.extractCode(body, fromContact = name != null)
        // клавиатура покажет кнопку «Вставить код» (5 минут)
        if (code != null && !spam) com.goodwy.keyboard.vse.VseCodes.put(ctx, code)
        val ask = code == null && !spam && SmsRepo.shouldAsk(ctx, address, name != null)
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
        val nid = (address + body).hashCode()
        if (ask) {
            // всплывающее «Это спам?»: Да — отправитель уходит в «Спам» (ничего не удаляется), Нет — больше не спрашиваем
            fun act(yes: Boolean) = PendingIntent.getBroadcast(ctx, nid + if (yes) 1 else 2, Intent(ctx, SpamAnswerReceiver::class.java).putExtra("address", address).putExtra("spam", yes).putExtra("nid", nid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle("${name ?: address} · это спам?").addAction(0, "Да, спам", act(true)).addAction(0, "Нет", act(false))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        if (NotifPrefs.on(ctx, "sms")) runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nid, b.build()) }
    }
}

class SpamAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.getStringExtra("address") ?: return
        val yes = intent.getBooleanExtra("spam", false)
        SmsRepo.markSpam(ctx, a, yes)
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("nid", 0)) }
        Toast.makeText(ctx, if (yes) "Отправитель в «Спаме». Сообщения не удалены." else "Ок, оставляю во входящих", Toast.LENGTH_SHORT).show()
    }
}

class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Код", code))
        Toast.makeText(ctx, "Код $code скопирован", Toast.LENGTH_SHORT).show()
    }
}
