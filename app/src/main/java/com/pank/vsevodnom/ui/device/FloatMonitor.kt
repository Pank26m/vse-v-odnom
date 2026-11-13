package com.pank.vsevodnom.ui.device

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.pank.vsevodnom.R
import com.pank.vsevodnom.data.device.Dev
import com.pank.vsevodnom.data.device.DevCpu
import com.pank.vsevodnom.data.device.DevHardware
import com.pank.vsevodnom.data.device.DevReport
import kotlin.math.abs

/** Плавающий монитор: маленькое окно поверх всех приложений с живыми ЦП, ОЗУ, током, температурой и сетью. */
class FloatMonitorService : Service() {
    private var view: TextView? = null
    private val h = Handler(Looper.getMainLooper())
    private val cores by lazy { DevCpu.cores() }
    private var lastRx = 0L; private var lastTx = 0L; private var lastT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("float_mon", "Плавающий монитор", NotificationManager.IMPORTANCE_MIN))
        val stopPi = PendingIntent.getService(this, 95, Intent(this, FloatMonitorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "float_mon").setSmallIcon(R.drawable.ic_notify).setContentTitle("Плавающий монитор включён").setContentText("Нажми на окно, чтобы закрыть").setContentIntent(stopPi).build()
        runCatching { ServiceCompat.startForeground(this, 7095, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view == null) show()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(22, 14, 22, 14)
            background = GradientDrawable().apply { setColor(0xCC15161F.toInt()); cornerRadius = 28f }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 200 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> { if (abs(e.rawX - sx) + abs(e.rawY - sy) > 12) moved = true; lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); runCatching { wm.updateViewLayout(tv, lp) } }
                MotionEvent.ACTION_UP -> if (!moved) stopSelf()
            }
            true
        }
        runCatching { wm.addView(tv, lp) }.onFailure { stopSelf(); return }
        view = tv; lastRx = TrafficStats.getTotalRxBytes(); lastTx = TrafficStats.getTotalTxBytes(); lastT = SystemClock.elapsedRealtime()
        h.post(object : Runnable { override fun run() { update(); h.postDelayed(this, 1000) } })
    }

    private fun update() {
        val tv = view ?: return
        val f = DevCpu.curFreqs(cores.size)
        val load = f.mapIndexedNotNull { i, v -> v?.let { ((it - (cores[i].minKhz ?: 0)).toFloat() / ((cores[i].maxKhz ?: 1) - (cores[i].minKhz ?: 0)).coerceAtLeast(1)) } }.average().takeIf { !it.isNaN() } ?: 0.0
        val maxF = f.filterNotNull().maxOrNull()
        val m = DevHardware.mem(this)
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val ma = DevReport.curMa(getSystemService(BatteryManager::class.java))
        val now = SystemClock.elapsedRealtime(); val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val dt = (now - lastT).coerceAtLeast(1) / 1000.0
        val down = (rx - lastRx) * 8 / 1e6 / dt; val up = (tx - lastTx) * 8 / 1e6 / dt; lastRx = rx; lastTx = tx; lastT = now
        tv.text = buildString {
            append("ЦП  ${(load * 100).toInt()}%  ${maxF?.let { "${it / 1000} МГц" } ?: ""}\n")
            append("ОЗУ ${(m.total - m.avail) * 100 / m.total}%  ${Dev.bytes(m.total - m.avail)}\n")
            append("БАТ ${bi?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) } ?: "?"}%  ${ma?.let { "${if (it > 0) "+" else ""}${it.toInt()} мА" } ?: ""}  ${bi?.let { Dev.fmt(it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f, 1) } ?: "?"}°C\n")
            append("СЕТЬ ↓${Dev.fmt(down, 1)} ↑${Dev.fmt(up, 1)} Мбит/с\n")
            append("НАГРЕВ ${DevHardware.thermalName(DevHardware.thermalStatus(this@FloatMonitorService)).substringBefore(" —")}")
        }
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); view?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }; view = null; super.onDestroy() }
}
