package com.pank.vsevodnom.alarm

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.AlarmEntity
import com.pank.vsevodnom.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object Channels {
    const val ALARM = "alarm"
    const val REMINDER = "reminder"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALARM, "Будильник и таймер", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // звук играет сервис, чтобы его нельзя было случайно заглушить настройкой канала
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(REMINDER, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Считает следующее срабатывание будильника. */
fun nextTrigger(a: AlarmEntity, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
    val t = LocalTime.of(a.hour, a.minute)
    if (a.daysMask == 0) {
        val today = now.toLocalDate().atTime(t)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }
    for (d in 0..7) {
        val date = now.toLocalDate().plusDays(d.toLong())
        val bit = date.dayOfWeek.value - 1 // пн = 0
        if (a.daysMask and (1 shl bit) != 0) {
            val dt = date.atTime(t)
            if (dt.isAfter(now)) return dt
        }
    }
    return now.plusDays(1)
}

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

object AlarmScheduler {
    private const val REQ_TIMER = 900_001
    private const val REQ_WEIGHT = 900_002

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    private fun ringIntent(ctx: Context, alarmId: Long, label: String, req: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmService.EXTRA_LABEL, label)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("route", "clock"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setClock(ctx: Context, atMillis: Long, op: PendingIntent) {
        if (!canExact(ctx)) {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
            return
        }
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent(ctx)), op)
    }

    fun schedule(ctx: Context, a: AlarmEntity) {
        val op = ringIntent(ctx, a.id, a.label.ifBlank { "Будильник" }, a.id.toInt())
        if (!a.enabled) { am(ctx).cancel(op); return }
        setClock(ctx, nextTrigger(a).toMillis(), op)
    }

    fun cancel(ctx: Context, a: AlarmEntity) {
        am(ctx).cancel(ringIntent(ctx, a.id, "", a.id.toInt()))
    }

    fun snooze(ctx: Context, alarmId: Long, label: String, minutes: Int = 10) {
        setClock(ctx, System.currentTimeMillis() + minutes * 60_000L, ringIntent(ctx, alarmId, label, alarmId.toInt()))
    }

    fun scheduleTimer(ctx: Context, atMillis: Long) =
        setClock(ctx, atMillis, ringIntent(ctx, AlarmService.TIMER_ID, "Таймер", REQ_TIMER))

    fun cancelTimer(ctx: Context) = am(ctx).cancel(ringIntent(ctx, AlarmService.TIMER_ID, "Таймер", REQ_TIMER))

    /** Напоминание о взвешивании: каждый понедельник в 9:00. */
    fun scheduleWeightReminder(ctx: Context) {
        val now = LocalDateTime.now()
        var next = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(8, 0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val op = PendingIntent.getBroadcast(
            ctx, REQ_WEIGHT, Intent(ctx, WeightReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toMillis(), op)
    }

    fun rescheduleAll(ctx: Context) {
        bgScope.launch {
            AppDb.get(ctx).alarms().allOnce().filter { it.enabled }.forEach { schedule(ctx, it) }
            scheduleWeightReminder(ctx)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = Intent(ctx, AlarmService::class.java).putExtras(intent)
        try {
            ctx.startForegroundService(svc)
        } catch (e: Exception) {
            // система не дала запустить звонок из фона — показываем громкое уведомление со звуком
            Channels.ensure(ctx)
            val open = PendingIntent.getActivity(
                ctx, 4, Intent(ctx, MainActivity::class.java).putExtra("route", "clock"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, Channels.REMINDER)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(intent.getStringExtra(AlarmService.EXTRA_LABEL) ?: "Будильник")
                .setContentText("Пора!")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(43, n) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
        com.pank.vsevodnom.data.AppScope.io.launch { com.pank.vsevodnom.data.HabitReminders.rescheduleAll(ctx) }
    }
}

class WeightReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!com.pank.vsevodnom.data.NotifPrefs.on(ctx, "weight")) { AlarmScheduler.scheduleWeightReminder(ctx); return }
        Channels.ensure(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("route", "weight"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Channels.REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Понедельник — время взвеситься")
            .setContentText("Запиши вес, пока не забыл")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(42, n) }
        AlarmScheduler.scheduleWeightReminder(ctx)
    }
}

/** Играет звук будильника/таймера и показывает полноэкранный экран. */
class AlarmService : Service() {
    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_LABEL = "label"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SNOOZE = "snooze"
        const val TIMER_ID = -1L
        private const val NOTIF_ID = 7
        private const val AUTO_STOP_MS = 10 * 60_000L

        @Volatile var ringing: Pair<Long, String>? = null
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ALARM_ID, 0L) ?: 0L
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Будильник"
        when (intent?.action) {
            ACTION_DISMISS -> { finishRing(id); return START_NOT_STICKY }
            ACTION_SNOOZE -> {
                AlarmScheduler.snooze(this, id, label)
                finishRing(id, snoozed = true)
                return START_NOT_STICKY
            }
        }
        Channels.ensure(this)
        ringing = id to label
        val n = buildNotification(id, label)
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        startSound()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finishRing(id) }, AUTO_STOP_MS)
        // попытка открыть экран будильника сразу (сработает, если разрешено)
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        }
        return START_NOT_STICKY
    }

    private fun action(act: String, id: Long, label: String, req: Int) = PendingIntent.getService(
        this, req,
        Intent(this, AlarmService::class.java).setAction(act).putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_LABEL, label),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(id: Long, label: String): Notification {
        val full = PendingIntent.getActivity(
            this, 3, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, Channels.ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(label)
            .setContentText(if (id == TIMER_ID) "Время вышло" else "Пора вставать")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Выключить", action(ACTION_DISMISS, id, label, 11))
        if (id != TIMER_ID) b.addAction(0, "Ещё 10 мин", action(ACTION_SNOOZE, id, label, 12))
        return b.build()
    }

    private fun startSound() {
        stopSound()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0))
    }

    private fun stopSound() {
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
    }

    private fun finishRing(id: Long, snoozed: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        ringing = null
        AlarmActivity.closeAll()
        if (id > 0 && !snoozed) {
            val ctx = applicationContext
            bgScope.launch {
                val dao = AppDb.get(ctx).alarms()
                val a = dao.byId(id) ?: return@launch
                if (a.daysMask == 0) dao.upsert(a.copy(enabled = false))
                else AlarmScheduler.schedule(ctx, a)
            }
        }
        if (id == TIMER_ID) TimerState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}
