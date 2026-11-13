package com.pank.vsevodnom.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
        /** Экран будильника сейчас на виду (иначе служба возвращает его, если будильник полноэкранный). */
        @Volatile var visible = false
        /** Открыта камера для селфи — не возвращать экран поверх неё. */
        @Volatile var external = false
    }

    override fun onResume() { super.onResume(); visible = true; external = false }
    override fun onPause() { super.onPause(); visible = external }   // пока снимаем селфи — считаем, что на виду

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        current = WeakReference(this)
        val ring = AlarmService.ringing
        if (ring == null) { finish(); return }
        val (id, label) = ring
        val ext = AlarmService.ringExt ?: AlarmExt()
        // полноэкранный: «назад» не закрывает — только выключить или решить задание
        if (ext.full) onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        setContent {
            AppTheme {
                var now by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
                var solving by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxSize().background(Pal.Lilac).padding(24.dp).systemBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(label, style = MaterialTheme.typography.titleLarge, color = Pal.LilacInk)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        now.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = if (solving) 56.sp else 96.sp)
                    )
                    if (ext.weather && id != AlarmService.TIMER_ID) AlarmWeather()
                    Spacer(Modifier.height(if (solving) 16.dp else 40.dp))
                    if (solving) {
                        MissionPanel(ext) { send(AlarmService.ACTION_DISMISS, id, label) }
                    } else {
                        if (id != AlarmService.TIMER_ID && AlarmSettings.snoozeOn(this@AlarmActivity)) {
                            PillButton(
                                "Ещё ${AlarmSettings.snoozeMin(this@AlarmActivity)} минут", { send(AlarmService.ACTION_SNOOZE, id, label) },
                                Modifier.fillMaxWidth(), bg = Color.White, fg = Pal.Ink
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        PillButton(if (ext.mission == "none" || id == AlarmService.TIMER_ID) "Выключить" else "Выключить — " + missionTitle(ext.mission),
                            { if (ext.mission == "none" || id == AlarmService.TIMER_ID) send(AlarmService.ACTION_DISMISS, id, label) else solving = true }, Modifier.fillMaxWidth())
                    }
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

private fun missionTitle(m: String) = when (m) { "math" -> "решить примеры"; "type" -> "перепечатать текст"; "selfie" -> "сделать селфи"; else -> "" }

/** Погода на экране будильника: из кэша сразу, свежая — если есть сеть. */
@Composable
private fun AlarmWeather() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var w by remember { mutableStateOf<com.pank.vsevodnom.data.WeatherData?>(null) }
    LaunchedEffect(Unit) {
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.pank.vsevodnom.data.AppDb.get(ctx).cities().weatherOnce().firstOrNull() }.getOrNull() } ?: return@LaunchedEffect
        w = com.pank.vsevodnom.ui.weather.WeatherRepo.cached(c)
        runCatching { com.pank.vsevodnom.ui.weather.WeatherRepo.refresh(ctx, c) }.getOrNull()?.let { w = it }
    }
    val d = w ?: return
    val day = d.days.firstOrNull { it.date == java.time.LocalDate.now() } ?: d.days.firstOrNull()
    Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.6f)).padding(16.dp, 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${com.pank.vsevodnom.ui.weather.fmtT(d.temp)} · ${com.pank.vsevodnom.data.wmo(d.code).first}", style = MaterialTheme.typography.titleMedium)
        day?.let { Text("днём ${com.pank.vsevodnom.ui.weather.fmtT(it.tMax)}, ночью ${com.pank.vsevodnom.ui.weather.fmtT(it.tMin)}" + if (it.rainProb >= 30) " · дождь ${it.rainProb} %" else "", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted) }
    }
}

/** Задание, чтобы выключить: примеры (по уровню), перепечатать фразу или селфи с лицом. */
@Composable
private fun MissionPanel(ext: AlarmExt, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (ext.mission) {
            "math" -> {
                val total = Missions.count(ext.level)
                var solved by remember { mutableIntStateOf(0) }
                var p by remember { mutableStateOf(Missions.math(ext.level)) }
                var ans by remember { mutableStateOf("") }
                var wrong by remember { mutableStateOf(false) }
                Text("Пример ${solved + 1} из $total · ${Missions.LEVELS[ext.level.coerceIn(0, 4)]}", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                Text("${p.text} = ?", style = MaterialTheme.typography.headlineMedium)
                androidx.compose.material3.OutlinedTextField(ans, { v -> ans = v.filter { it.isDigit() || it == '-' }; wrong = false }, singleLine = true, isError = wrong,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                PillButton("Проверить", {
                    if (ans.toLongOrNull() == p.answer) { solved++; ans = ""; if (solved >= total) onDone() else p = Missions.math(ext.level) }
                    else { wrong = true; p = Missions.math(ext.level); ans = "" }   // ошибся — новый пример
                }, Modifier.fillMaxWidth())
                if (wrong) Text("Неверно — вот другой пример", color = Pal.RoseInk, style = MaterialTheme.typography.bodySmall)
            }
            "type" -> {
                val phrase = remember { Missions.phrase() }
                var t by remember { mutableStateOf("") }
                Text("Перепечатай фразу", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                Text(phrase, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                androidx.compose.material3.OutlinedTextField(t, { t = it; if (Missions.sameText(it, phrase)) onDone() }, modifier = Modifier.fillMaxWidth())
            }
            "selfie" -> {
                var file by remember { mutableStateOf<java.io.File?>(null) }
                var msg by remember { mutableStateOf("Сфотографируй себя — будильник выключится, когда на фото будет лицо") }
                val shot = androidx.activity.compose.rememberLauncherForActivityResult(SelfieContract()) { ok ->
                    AlarmActivity.external = false
                    val f = file
                    if (ok && f != null && f.length() > 0) {
                        if (AlarmSelfies.hasFace(f.absolutePath)) onDone() else { msg = "Лица не видно — попробуй ещё раз при свете"; f.delete() }
                    } else msg = "Фото не сделано"
                }
                Text(msg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                PillButton("Сделать селфи", {
                    val f = AlarmSelfies.newFile(ctx); file = f
                    AlarmActivity.external = true
                    shot.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                }, Modifier.fillMaxWidth())
            }
            else -> LaunchedEffect(Unit) { onDone() }
        }
    }
}

/** Снимок фронтальной камерой (подсказки для камер Google/Samsung) в наш файл. */
private class SelfieContract : androidx.activity.result.contract.ActivityResultContract<android.net.Uri, Boolean>() {
    override fun createIntent(context: Context, input: android.net.Uri) = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, input)
        .putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == android.app.Activity.RESULT_OK
}
