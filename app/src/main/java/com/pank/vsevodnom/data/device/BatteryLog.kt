package com.pank.vsevodnom.data.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Журнал батареи (как AccuBattery, но свой): служба в фоне делит время на сессии зарядки и разрядки
 * и копит в каждой: проценты, мА·ч (по счётчику заряда), время с экраном вкл/выкл, глубокий сон, ток, температуру.
 * Сессии — files/battery/sessions.jsonl (одна строка JSON на сессию), текущая — current.json.
 */
object BatteryLog {
    data class Session(
        val charge: Boolean, val start: Long, var end: Long, val startPct: Int, var endPct: Int,
        var mah: Double = 0.0, var onMs: Long = 0, var offMs: Long = 0, var deepMs: Long = 0,
        var onMah: Double = 0.0, var offMah: Double = 0.0, var deepMah: Double = 0.0,
        var maxMa: Double = 0.0, var tempSum: Double = 0.0, var tempN: Int = 0, var maxTemp: Float = 0f, var plug: Int = 0,
        var counterCap: Double? = null, var alarmed: Boolean = false,
    ) {
        val pct get() = abs(endPct - startPct)
        val durMs get() = end - start
        val avgTemp get() = if (tempN > 0) tempSum / tempN else null
        /** Износ сессии в циклах: 100 % заряда = 1 цикл. */
        val cycles get() = if (charge) pct / 100.0 else 0.0
        /** Оценка полной ёмкости по зарядке: сколько мА·ч влезло на N процентов. */
        val capacity: Double? get() = if (charge && pct >= 15 && mah > 50) mah / (pct / 100.0) else null
        fun json() = JSONObject().apply {
            put("c", charge); put("s", start); put("e", end); put("sp", startPct); put("ep", endPct); put("m", mah); put("on", onMs); put("off", offMs); put("deep", deepMs)
            put("onm", onMah); put("offm", offMah); put("dm", deepMah); put("max", maxMa); put("ts", tempSum); put("tn", tempN); put("tm", maxTemp.toDouble()); put("p", plug)
            counterCap?.let { put("cc", it) }; put("al", alarmed)
        }
        companion object {
            fun of(j: JSONObject) = Session(j.getBoolean("c"), j.getLong("s"), j.getLong("e"), j.getInt("sp"), j.getInt("ep"), j.optDouble("m"), j.optLong("on"), j.optLong("off"), j.optLong("deep"),
                j.optDouble("onm"), j.optDouble("offm"), j.optDouble("dm"), j.optDouble("max"), j.optDouble("ts"), j.optInt("tn"), j.optDouble("tm").toFloat(), j.optInt("p"),
                if (j.has("cc")) j.getDouble("cc") else null, j.optBoolean("al"))
        }
    }

    /** Меняется при каждой записи — экраны перечитывают данные. */
    var version by mutableIntStateOf(0)
    @Volatile var current: Session? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "battery").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("battery_log", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("on", v).apply(); sync(ctx) }
    fun alarmPct(ctx: Context) = prefs(ctx).getInt("alarm", 80)
    fun setAlarmPct(ctx: Context, v: Int) = prefs(ctx).edit().putInt("alarm", v).apply()
    fun alarmOn(ctx: Context) = prefs(ctx).getBoolean("alarm_on", true)
    fun setAlarmOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("alarm_on", v).apply()
    /** Номинальная ёмкость: из системного профиля питания или своя (можно поправить). */
    fun design(ctx: Context): Double = prefs(ctx).getFloat("design", 0f).toDouble().takeIf { it > 0 } ?: DevReport.designMah(ctx) ?: 4000.0
    fun setDesign(ctx: Context, v: Double) = prefs(ctx).edit().putFloat("design", v.toFloat()).apply()
    fun firstSeen(ctx: Context): Long = prefs(ctx).getLong("first", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs(ctx).edit().putLong("first", it).apply() }

    fun sessions(ctx: Context): List<Session> = runCatching {
        File(dir(ctx), "sessions.jsonl").takeIf { it.exists() }?.readLines()?.mapNotNull { l -> runCatching { Session.of(JSONObject(l)) }.getOrNull() }
    }.getOrNull().orEmpty()

    @Synchronized fun append(ctx: Context, s: Session) {
        val f = File(dir(ctx), "sessions.jsonl")
        f.appendText(s.json().toString() + "\n")
        // не раздуваем файл: храним последние 3000 сессий
        if (f.length() > 3_000_000) f.writeText(f.readLines().takeLast(3000).joinToString("\n", postfix = "\n"))
        version++
    }
    fun saveCurrent(ctx: Context) { current?.let { runCatching { File(dir(ctx), "current.json").writeText(it.json().toString()) } } }
    fun loadCurrent(ctx: Context): Session? = runCatching { Session.of(JSONObject(File(dir(ctx), "current.json").readText())) }.getOrNull()

    fun sync(ctx: Context) {
        val i = Intent(ctx, BatteryLogService::class.java)
        if (enabled(ctx)) runCatching { ContextCompat.startForegroundService(ctx, i) } else ctx.stopService(i)
    }

    // ---------------- расчёты для экранов ----------------
    data class Rates(val mixedPctH: Double?, val onPctH: Double?, val offPctH: Double?, val mixedMa: Double?, val onMa: Double?, val offMa: Double?,
                     val awakePctH: Double?, val deepPctH: Double?, val sessions: Int, val pctTotal: Double, val hours: Double, val onH: Double, val offH: Double, val onPct: Double, val offPct: Double)

    /** Средний расход по сессиям разрядки за последние [days] дней. */
    fun rates(list: List<Session>, cap: Double, days: Int = 7): Rates {
        val from = System.currentTimeMillis() - days * 86_400_000L
        val d = list.filter { !it.charge && it.end >= from && it.durMs > 60_000 }
        val onMs = d.sumOf { it.onMs }.toDouble(); val offMs = d.sumOf { it.offMs }.toDouble(); val deepMs = d.sumOf { it.deepMs }.toDouble()
        val onMah = d.sumOf { it.onMah }; val offMah = d.sumOf { it.offMah }; val deepMah = d.sumOf { it.deepMah }
        fun pctH(mah: Double, ms: Double) = if (ms > 600_000) mah / cap * 100 / (ms / 3_600_000) else null
        fun ma(mah: Double, ms: Double) = if (ms > 600_000) mah / (ms / 3_600_000) else null
        val totalPct = d.sumOf { it.pct.toDouble() }
        return Rates(pctH(onMah + offMah, onMs + offMs), pctH(onMah, onMs), pctH(offMah, offMs), ma(onMah + offMah, onMs + offMs), ma(onMah, onMs), ma(offMah, offMs),
            pctH(offMah - deepMah, offMs - deepMs), pctH(deepMah, deepMs), d.size, totalPct, (onMs + offMs) / 3_600_000, onMs / 3_600_000, offMs / 3_600_000,
            onMah / cap * 100, offMah / cap * 100)
    }

    /** Оценки ёмкости по зарядкам (дата, мА·ч). */
    fun capacityPoints(list: List<Session>): List<Pair<Long, Double>> = list.mapNotNull { s -> s.capacity?.takeIf { it in 500.0..20000.0 }?.let { s.end to it } }

    /** Линейная регрессия y = a + b·x; возвращает (a, b, R²). */
    fun regression(p: List<Pair<Double, Double>>): Triple<Double, Double, Double>? {
        if (p.size < 3) return null
        val n = p.size; val mx = p.sumOf { it.first } / n; val my = p.sumOf { it.second } / n
        val sxx = p.sumOf { (it.first - mx) * (it.first - mx) }; if (sxx == 0.0) return null
        val b = p.sumOf { (it.first - mx) * (it.second - my) } / sxx; val a = my - b * mx
        val ssTot = p.sumOf { (it.second - my) * (it.second - my) }; val ssRes = p.sumOf { val f = a + b * it.first; (it.second - f) * (it.second - f) }
        return Triple(a, b, if (ssTot == 0.0) 1.0 else 1 - ssRes / ssTot)
    }

    /** Циклы износа за период. */
    fun cycles(list: List<Session>, fromMs: Long) = list.filter { it.charge && it.end >= fromMs }.sumOf { it.cycles }
}

/** Служба журнала: живёт в фоне с тихим уведомлением; опрос — 5 с при зарядке/включённом экране, 60 с при выключенном. */
class BatteryLogService : Service() {
    companion object { const val CH = "battery_log"; const val NID = 7090; const val ALARM_CH = "battery_alarm"; const val ALARM_NID = 7091 }
    private val h = Handler(Looper.getMainLooper())
    private var lastT = 0L; private var lastUp = 0L; private var lastCounter = 0L
    private var screenOn = true
    private val bm by lazy { getSystemService(BatteryManager::class.java) }
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> { tick(); screenOn = true }
                Intent.ACTION_SCREEN_OFF -> { tick(); screenOn = false }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> tick()
                Intent.ACTION_BATTERY_CHANGED -> tick(i)
            }
            reschedule()
        }
    }
    private val loop = Runnable { tick(); reschedule() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Журнал батареи", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALARM_CH, "Батарея: заряд достиг цели", NotificationManager.IMPORTANCE_HIGH))
        runCatching { ServiceCompat.startForeground(this, NID, notif("Журнал батареи"), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        if (lastT == 0L) {
            screenOn = getSystemService(PowerManager::class.java).isInteractive
            BatteryLog.current = BatteryLog.current ?: BatteryLog.loadCurrent(this)
            lastT = SystemClock.elapsedRealtime(); lastUp = SystemClock.uptimeMillis(); lastCounter = counter()
            val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) }
            ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            tick(); reschedule()
        }
        return START_STICKY
    }

    private fun counter(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
    private fun reschedule() { h.removeCallbacks(loop); h.postDelayed(loop, if (BatteryLog.current?.charge == true || screenOn) 5_000 else 60_000) }

    private var lastBat: Intent? = null
    @Synchronized private fun tick(i0: Intent? = null) {
        val i = i0 ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: lastBat ?: return
        lastBat = i
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plug != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val now = System.currentTimeMillis(); val t = SystemClock.elapsedRealtime(); val up = SystemClock.uptimeMillis()
        val dt = (t - lastT).coerceAtLeast(0); val dUp = (up - lastUp).coerceIn(0, dt)
        val cnt = counter(); val ma = DevReport.curMa(bm)
        var s = BatteryLog.current
        if (s == null || s.charge != charging) {
            s?.let { closeSession(it, lvl) }
            s = BatteryLog.Session(charging, now, now, lvl, lvl, plug = plug); BatteryLog.current = s
        } else if (dt > 0) {
            // мА·ч за интервал: по счётчику заряда (точно), иначе — ток × время
            val dMah = if (cnt > 0 && lastCounter > 0 && cnt != lastCounter) abs(cnt - lastCounter) / 1000.0 else (ma?.let { abs(it) * dt / 3_600_000.0 } ?: 0.0)
            s.mah += dMah
            if (screenOn) { s.onMs += dt; s.onMah += dMah } else {
                s.offMs += dt; s.offMah += dMah
                val deep = dt - dUp; s.deepMs += deep
                // в глубоком сне ток не измерить напрямую: всё, что сверх тока «бодрствования», — сон
                val awakeMah = (ma?.let { abs(it) } ?: 0.0) * dUp / 3_600_000.0
                s.deepMah += (dMah - awakeMah).coerceIn(0.0, dMah)
            }
            ma?.let { s.maxMa = maxOf(s.maxMa, abs(it)) }
            s.tempSum += temp; s.tempN++; s.maxTemp = maxOf(s.maxTemp, temp)
            s.end = now; s.endPct = lvl; s.plug = if (charging) plug else s.plug
            if (charging && lvl > 10 && cnt > 0) s.counterCap = cnt / 10.0 / lvl
            // уведомление «отключи зарядку»
            if (charging && !s.alarmed && BatteryLog.alarmOn(this) && lvl >= BatteryLog.alarmPct(this) && s.startPct < BatteryLog.alarmPct(this)) { s.alarmed = true; alarm(lvl) }
        }
        lastT = t; lastUp = up; if (cnt > 0) lastCounter = cnt
        BatteryLog.saveCurrent(this); BatteryLog.version++
    }

    private fun closeSession(s: BatteryLog.Session, lvl: Int) {
        s.end = System.currentTimeMillis(); s.endPct = if (s.durMs > 0) s.endPct else lvl
        // слишком короткие и без изменений — не засоряют историю
        if (s.durMs >= 120_000 || s.pct >= 1) BatteryLog.append(this, s)
    }

    private fun alarm(lvl: Int) {
        val n = Notification.Builder(this, ALARM_CH).setSmallIcon(R.drawable.ic_notify).setContentTitle("Заряжено до $lvl %")
            .setContentText("Можно отключить зарядку — так батарея изнашивается медленнее").setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 91, Intent(this, MainActivity::class.java).putExtra("route", "utils"), PendingIntent.FLAG_IMMUTABLE)).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALARM_NID, n) }
    }

    private fun notif(text: String): Notification = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notify).setContentTitle(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 90, Intent(this, MainActivity::class.java).putExtra("route", "utils"), PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onDestroy() { h.removeCallbacks(loop); runCatching { unregisterReceiver(rx) }; BatteryLog.saveCurrent(this); super.onDestroy() }
}
