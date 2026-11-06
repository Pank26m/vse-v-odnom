package com.pank.vsevodnom.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Код подтверждения, пойманный в уведомлении почты. */
data class MailCode(val code: String, val from: String, val at: Long)

/** Разбор банковского push: сумма, расход/доход, валюта, место. */
data class PushTxn(val amount: Double, val income: Boolean, val currency: String, val merchant: String)

object PushParse {
    private val AMOUNT = Regex("(?U)([+\\-−–]?)\\s?(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?\\s?(₴|грн\\.?|UAH|uah|\\$|USD|€|EUR|zł|PLN)")
    private val AMOUNT_PRE = Regex("(?U)([+\\-−–]?)\\s?(₴|\\$|€)\\s?(\\d{1,3}(?:[ ,\\u00a0]\\d{3})+|\\d+)(?:[.](\\d{1,2}))?")
    private val BALANCE = Regex("(?iU)(баланс|залишок|остаток|доступно|balance|available|кредитн)[^\\d]{0,14}$")
    private val INCOME = Regex("(?iU)(зарахування|зараховано|поповнення|надходження|зачислен|пополнен|отримали|отримано|переказ від|повернення|кешбек нараховано|refund|received)")
    private val EXPENSE = Regex("(?iU)(покупка|оплата|сплачено|списання|списано|списание|зняття|снятие|переказ на|платіж|payment|purchase|spent|paid)")
    private val PROMO = Regex("(?iU)(знижк|акці|кешбек до|до \\d+ ?%|розіграш|виграй|бонус|запрошуємо|кредит до|ліміт до)")

    private fun cur(s: String) = when (s.lowercase().trimEnd('.')) { "₴", "грн", "uah" -> "UAH"; "$", "usd" -> "USD"; "€", "eur" -> "EUR"; "zł", "pln" -> "PLN"; else -> "UAH" }

    fun parse(title: String, text: String): PushTxn? {
        val all = "$title\n$text"
        if (PROMO.containsMatchIn(all) && !EXPENSE.containsMatchIn(all)) return null
        data class M(val sign: String, val value: Double, val cur: String, val start: Int, val end: Int)
        val ms = AMOUNT.findAll(all).map { m ->
            val v = (m.groupValues[2].replace(Regex("[ \\u00a0\\u202f]"), "") + "." + m.groupValues[3].ifBlank { "0" }).toDouble()
            M(m.groupValues[1], v, cur(m.groupValues[4]), m.range.first, m.range.last)
        } + AMOUNT_PRE.findAll(all).map { m ->
            val v = (m.groupValues[3].replace(Regex("[ ,\\u00a0]"), "") + "." + m.groupValues[4].ifBlank { "0" }).toDouble()
            M(m.groupValues[1], v, cur(m.groupValues[2]), m.range.first, m.range.last)
        }
        // суммы после слова «Баланс/Залишок» — это не операция
        val m = ms.sortedBy { it.start }.firstOrNull { !BALANCE.containsMatchIn(all.substring(0, it.start).takeLast(24)) && it.value > 0 } ?: return null
        val income = when {
            m.sign == "+" -> true
            m.sign.isNotBlank() -> false
            INCOME.containsMatchIn(all) -> true
            EXPENSE.containsMatchIn(all) -> false
            else -> return null   // непонятно, что это — не выдумываем
        }
        // место: строка без сумм и служебных слов
        val merchant = all.lines().map { l -> AMOUNT.replace(AMOUNT_PRE.replace(l, ""), "").replace(BALANCE, "").trim(' ', '.', ',', ':', '·', '—', '-') }
            .firstOrNull { it.length >= 2 && !BALANCE.containsMatchIn(it) && !it.matches(Regex("(?iU)(покупка|оплата|списання|зарахування|поповнення)")) }.orEmpty()
        return PushTxn(m.value, income, m.cur, merchant.take(80))
    }
}

object Notif {
    val MAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android", "com.yandex.mail", "ru.mail.mailapp", "com.samsung.android.email.provider")
    val BANKS = mapOf(
        "com.ftband.mono" to "monobank", "ua.privatbank.ap24" to "Приват24", "com.ukrsibbank.client.android" to "UKRSIBBANK",
        "ua.com.abank" to "A-Bank", "ua.aval.dbo.client.android" to "Raiffeisen", "com.fuib.android.spot.online" to "ПУМБ",
        "ua.oschadbank.online" to "Ощадбанк", "com.izibank.app" to "izibank", "com.sensebank.mobile" to "Sense",
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notif", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ComponentName(ctx, NotifListener::class.java).flattenToString()) == true
    fun openSettings(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun openAppInfo(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    fun bankOn(ctx: Context) = prefs(ctx).getBoolean("bank", true)
    fun setBankOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bank", v).apply()
    fun mailOn(ctx: Context) = prefs(ctx).getBoolean("mail", true)
    fun setMailOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("mail", v).apply()
    fun pushAccount(ctx: Context) = prefs(ctx).getLong("account", 0)
    fun setPushAccount(ctx: Context, id: Long) = prefs(ctx).edit().putLong("account", id).apply()

    fun mailCodes(ctx: Context): List<MailCode> = runCatching {
        val a = JSONArray(prefs(ctx).getString("codes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> MailCode(o.getString("c"), o.optString("f"), o.getLong("t")) } }
    }.getOrDefault(emptyList())

    fun addMailCode(ctx: Context, c: MailCode): Boolean {
        val list = mailCodes(ctx)
        if (list.any { it.code == c.code && c.at - it.at < 15 * 60_000 }) return false
        val out = (listOf(c) + list).take(20)
        prefs(ctx).edit().putString("codes", JSONArray(out.map { JSONObject().put("c", it.code).put("f", it.from).put("t", it.at) }).toString()).apply()
        return true
    }

    /** Похожая операция уже записана за последние 3 минуты (банк и Wallet часто шлют оба). */
    private val recent = ArrayDeque<Triple<Double, Boolean, Long>>()
    @Synchronized fun isDuplicate(p: PushTxn, at: Long): Boolean {
        while (recent.isNotEmpty() && at - recent.first().third > 180_000) recent.removeFirst()
        if (recent.any { it.first == p.amount && it.second == p.income }) return true
        recent.addLast(Triple(p.amount, p.income, at)); return false
    }
}

/** Слушает уведомления: коды из почты и траты из банковских приложений. Ничего не отправляет наружу. */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val ctx = applicationContext
        when {
            sbn.packageName in Notif.MAIL && Notif.mailOn(ctx) -> mail(ctx, title, text)
            sbn.packageName in Notif.BANKS && Notif.bankOn(ctx) -> bank(ctx, sbn, title, text)
        }
    }

    private fun mail(ctx: Context, title: String, text: String) {
        val code = SmsRepo.extractCode("$title\n$text") ?: return
        if (!Notif.addMailCode(ctx, MailCode(code, title, System.currentTimeMillis()))) return
        SmsRepo.ensureChannels(ctx)
        val copy = PendingIntent.getBroadcast(ctx, code.hashCode(), Intent(ctx, CopyCodeReceiver::class.java).putExtra("code", code), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, 91, Intent(ctx, MainActivity::class.java).putExtra("route", "sms"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(("mail$code").hashCode(), NotificationCompat.Builder(ctx, SmsRepo.CH_SMS)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle("Код из почты: $code").setContentText(title)
                .setContentIntent(open).setAutoCancel(true).addAction(0, "Скопировать $code", copy).setTimeoutAfter(15 * 60_000).build())
        }
    }

    private fun bank(ctx: Context, sbn: StatusBarNotification, title: String, text: String) {
        if (sbn.packageName == "com.ftband.mono" && MoneyRepo.monoToken(ctx).isNotBlank()) return  // mono и так приходит по API
        val p = PushParse.parse(title, text) ?: return
        if (Notif.isDuplicate(p, sbn.postTime)) return
        val bankName = Notif.BANKS[sbn.packageName] ?: sbn.packageName
        AppScope.io.launch {
            val dao = AppDb.get(ctx).money()
            MoneyRepo.ensureDefaults(ctx)
            val accs = dao.accountsOnce().filter { !it.archived }
            val acc = accs.firstOrNull { it.id == Notif.pushAccount(ctx) } ?: accs.firstOrNull { it.currency == p.currency } ?: accs.firstOrNull() ?: return@launch
            val ext = "push:${sbn.packageName}:${sbn.postTime}:${p.amount}"
            if (dao.countExt(ext) > 0) return@launch
            dao.upsertTxn(Txn(at = sbn.postTime, type = if (p.income) MoneyRepo.INCOME else MoneyRepo.EXPENSE, amount = p.amount, accountId = acc.id,
                comment = listOf(p.merchant, bankName).filter { it.isNotBlank() }.joinToString(" · "), source = "push", extId = ext))
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("money", "Деньги", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(ctx, 92, Intent(ctx, MainActivity::class.java).putExtra("route", "money"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching {
                nm.notify(ext.hashCode(), NotificationCompat.Builder(ctx, "money").setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle((if (p.income) "+" else "−") + "%.2f ${p.currency} записано".format(p.amount).replace('.', ','))
                    .setContentText("${p.merchant.ifBlank { bankName }} · счёт «${acc.name}». Нажми, чтобы выбрать категорию.")
                    .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(6 * 3600_000L).build())
            }
        }
    }
}
