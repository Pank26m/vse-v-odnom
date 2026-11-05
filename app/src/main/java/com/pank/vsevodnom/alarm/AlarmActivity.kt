package com.pank.vsevodnom.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Глобальное состояние таймера: переживает уход с экрана, звонок — через AlarmManager. */
object TimerState {
    data class S(
        val totalMs: Long = 5 * 60_000L,
        val endAtElapsed: Long = 0L,      // когда закончится (elapsedRealtime), если идёт
        val remainingPausedMs: Long = 0L, // остаток на паузе
        val running: Boolean = false,
        val paused: Boolean = false,
    )

    private val _s = MutableStateFlow(S())
    val s = _s.asStateFlow()

    fun setTotal(ms: Long) { if (!_s.value.running && !_s.value.paused) _s.value = S(totalMs = ms) }

    fun start(ctx: Context) {
        val cur = _s.value
        val left = if (cur.paused) cur.remainingPausedMs else cur.totalMs
        if (left <= 0) return
        _s.value = cur.copy(endAtElapsed = SystemClock.elapsedRealtime() + left, running = true, paused = false)
        AlarmScheduler.scheduleTimer(ctx, System.currentTimeMillis() + left)
    }

    fun pause(ctx: Context) {
        val cur = _s.value
        if (!cur.running) return
        _s.value = cur.copy(running = false, paused = true, remainingPausedMs = (cur.endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        AlarmScheduler.cancelTimer(ctx)
    }

    fun cancel(ctx: Context) {
        AlarmScheduler.cancelTimer(ctx)
        reset()
    }

    fun reset() { _s.value = S(totalMs = _s.value.totalMs) }

    fun remaining(now: Long = SystemClock.elapsedRealtime()): Long {
        val cur = _s.value
        return when {
            cur.running -> (cur.endAtElapsed - now).coerceAtLeast(0)
            cur.paused -> cur.remainingPausedMs
            else -> cur.totalMs
        }
    }
}

class AlarmActivity : ComponentActivity() {
    companion object {
        private var current: WeakReference<AlarmActivity>? = null
        fun closeAll() { current?.get()?.finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        current = WeakReference(this)
        val ring = AlarmService.ringing
        if (ring == null) { finish(); return }
        val (id, label) = ring
        setContent {
            AppTheme {
                var now by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
                Column(
                    Modifier.fillMaxSize().background(Pal.Lilac).padding(24.dp).systemBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(label, style = MaterialTheme.typography.titleLarge, color = Pal.LilacInk)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        now.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp)
                    )
                    Spacer(Modifier.height(48.dp))
                    if (id != AlarmService.TIMER_ID) {
                        PillButton(
                            "Ещё 10 минут", { send(AlarmService.ACTION_SNOOZE, id, label) },
                            Modifier.fillMaxWidth(), bg = Color.White, fg = Pal.Ink
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    PillButton("Выключить", { send(AlarmService.ACTION_DISMISS, id, label) }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    private fun send(action: String, id: Long, label: String) {
        startService(
            Intent(this, AlarmService::class.java).setAction(action)
                .putExtra(AlarmService.EXTRA_ALARM_ID, id).putExtra(AlarmService.EXTRA_LABEL, label)
        )
        finish()
    }
}
