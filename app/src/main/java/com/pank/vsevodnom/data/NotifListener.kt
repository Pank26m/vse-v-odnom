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

object Notif {
    val MAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android", "com.yandex.mail", "ru.mail.mailapp", "com.samsung.android.email.provider")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notif", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ComponentName(ctx, NotifListener::class.java).flattenToString()) == true
    fun openSettings(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun openAppInfo(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    fun mailOn(ctx: Context) = prefs(ctx).getBoolean("mail", true)
    fun setMailOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("mail", v).apply()

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

}

/** Слушает уведомления почты: достаёт коды подтверждения. Ничего не отправляет наружу. */
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
}
