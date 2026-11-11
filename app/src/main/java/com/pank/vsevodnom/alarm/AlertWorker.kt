package com.pank.vsevodnom.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.AlertsApi
import java.util.concurrent.TimeUnit

/** Фоновая проверка тревоги только по Киеву (Android не даёт чаще, чем раз в 15 минут). */
class AlertWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    companion object {
        private const val NAME = "alerts-check"
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!AlertsApi.notifyEnabled(ctx)) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        if (!AlertsApi.notifyEnabled(applicationContext)) return Result.success()
        // включена служба «без задержки» — она и проверяет; здесь только запасной путь, если служба не запущена
        if (AlertLiveService.running) return Result.success()
        return if (check(applicationContext)) Result.success() else Result.retry()
    }
}

/** Проверка тревоги в Киеве и уведомление при смене уровня (общая для фоновой задачи и службы «без задержки»). false — нет связи. */
internal suspend fun check(ctx: Context): Boolean = AlertCheck(ctx).run()

private class AlertCheck(val applicationContext: Context) {
    suspend fun run(): Boolean {
        val st = runCatching { AlertsApi.fetch(applicationContext) }.getOrElse { return false }
        val prefs = applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        // уровень по alert_level: 2 — красная, 1 — жёлтая (угроза), 0 — нет
        val now = com.pank.vsevodnom.ui.home.kyivAlertLevel(st) ?: return true
        val was = prefs.getString("lvl2_kyiv", null)?.toIntOrNull() ?: 0
        if (now != was) {
            prefs.edit().putString("lvl2_kyiv", now.toString()).apply()
            val threats = st.items.filter { it.oblast == AlertsApi.KYIV_CITY }.flatMap { it.threats }.map { com.pank.vsevodnom.ui.alerts.threatName(it.type) }.distinct().joinToString(", ")
            when (now) {
                2 -> notify(100, "Тревога: Киев", threats.ifBlank { "Пройди в укрытие" })
                1 -> notify(100, "Угроза в Киеве (жёлтая)", threats.ifBlank { "Будь внимателен" })
                else -> notify(100, "Отбой: Киев", "Тревога закончилась")
            }
        }
        return true
    }

    private fun notify(id: Int, title: String, text: String) {
        if (!com.pank.vsevodnom.data.NotifPrefs.on(applicationContext, "alerts")) return
        Channels.ensure(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 30, Intent(applicationContext, MainActivity::class.java).putExtra("route", "alerts"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, Channels.REMINDER)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setAutoCancel(true).build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
