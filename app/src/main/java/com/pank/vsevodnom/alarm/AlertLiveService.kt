package com.pank.vsevodnom.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.AlertsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Тревоги «без задержки»: служба переднего плана с тихим постоянным уведомлением проверяет Киев каждые 30 секунд
 * (фоновая задача Android — не чаще раза в 15 минут). Держит процессор от засыпания (частичная блокировка сна) —
 * это заметно, но не сильно, расходует батарею. Включается в «Тревоги → настройки» (prefs alerts/live).
 */
class AlertLiveService : Service() {
    companion object {
        @Volatile var running = false
        private const val CH = "alerts_live"
        private const val PERIOD_MS = 30_000L
        fun enabled(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE).getBoolean("live", false)
        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE).edit().putBoolean("live", on).apply()
            sync(ctx)
        }
        /** Запустить или остановить по настройке (при старте приложения, после перезагрузки, после смены настройки). */
        fun sync(ctx: Context) {
            val want = enabled(ctx) && AlertsApi.notifyEnabled(ctx)
            val i = Intent(ctx, AlertLiveService::class.java)
            if (want) runCatching { ContextCompat.startForegroundService(ctx, i) } else ctx.stopService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Слежу за тревогой", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java).putExtra("route", "alerts"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Слежу за тревогой в Киеве").setContentText("Проверка каждые 30 секунд")
            .setOngoing(true).setSilent(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
        runCatching {
            ServiceCompat.startForeground(this, 77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vse:alerts").apply { setReferenceCounted(false); acquire() }
            scope.launch {
                while (isActive) {
                    runCatching { check(applicationContext) }
                    delay(PERIOD_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { wake?.release() }
        super.onDestroy()
    }
}
