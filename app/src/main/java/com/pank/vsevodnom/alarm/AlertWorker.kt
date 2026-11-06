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

/** Фоновая проверка тревоги по Киеву и области (Android не даёт чаще, чем раз в 15 минут). */
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
        val st = runCatching { AlertsApi.fetch(applicationContext) }.getOrElse { return Result.retry() }
        val prefs = applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        listOf(AlertsApi.KYIV_CITY to "Киев", AlertsApi.KYIV_OBL to "Киевская область").forEachIndexed { i, (key, title) ->
            val now = st.active[key] ?: return@forEachIndexed
            val was = prefs.getBoolean("last_$key", false)
            if (now != was) {
                prefs.edit().putBoolean("last_$key", now).apply()
                notify(100 + i, if (now) "Тревога: $title" else "Отбой: $title", if (now) "Пройди в укрытие" else "Тревога закончилась")
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
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
