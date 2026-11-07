package com.pank.vsevodnom.call

import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** Экран звонка: работает поверх блокировки. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        setContent { AppTheme { CallScreen { finish() } } }
    }
}

private val CallBg = Brush.verticalGradient(listOf(Color(0xFF2E3350), Color(0xFF15161F)))
private val Green = Color(0xFF34C77B)
private val Red = Color(0xFFE5484D)

@Composable
fun CallScreen(onEnd: () -> Unit) {
    val ctx = LocalContext.current
    CallState.tick   // перерисовка при изменениях звонка
    val c = CallState.current
    LaunchedEffect(c == null) { if (c == null) { delay(600); if (CallState.current == null) onEnd() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var keypad by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }
    val num = c?.let { CallState.number(it) } ?: ""
    val who = remember(num) { CallState.name(ctx, num) }
    val st = c?.state ?: Call.STATE_DISCONNECTED
    val audio = CallState.audio
    val muted = audio?.isMuted == true
    val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
    val hasBt = (audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0
    val held = st == Call.STATE_HOLDING
    val connectTime = c?.details?.connectTimeMillis ?: 0L
    val status = when (st) {
        Call.STATE_RINGING -> "Входящий звонок"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Набираю…"
        Call.STATE_HOLDING -> "На удержании"
        Call.STATE_ACTIVE -> if (connectTime > 0) ((now - connectTime) / 1000).let { "%d:%02d".format(it / 60, it % 60) } else "Разговор"
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "Звонок завершён"
        else -> ""
    }
    Column(Modifier.fillMaxSize().background(CallBg).systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text((who ?: num).take(1).uppercase().ifBlank { "?" }, fontSize = 40.sp, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Text(who ?: num.ifBlank { "Скрытый номер" }, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
        if (who != null) Text(num, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
        Text(status, style = MaterialTheme.typography.titleMedium, color = if (held) Color(0xFFF2BE45) else Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
        // второй звонок: переключение
        CallState.calls.filter { it !== c }.forEach { o ->
            Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.1f)).clickable {
                c?.hold(); if (o.state == Call.STATE_HOLDING) o.unhold() else if (o.state == Call.STATE_RINGING) CallState.answer(o)
            }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SwapCalls, null, tint = Color.White)
                Text("  ${CallState.name(ctx, CallState.number(o)) ?: CallState.number(o)} · переключиться", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        if (keypad) {
            Text(dtmf, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
            Keypad(dark = true) { k -> c?.playDtmfTone(k); dtmf += k; c?.let { cc -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ cc.stopDtmfTone() }, 160) } }
            Spacer(Modifier.height(12.dp))
        } else if (st != Call.STATE_RINGING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Toggle(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Микрофон", muted) { CallState.mute(!muted) }
                Toggle(Icons.Rounded.VolumeUp, "Динамик", speaker) { CallState.speaker(!speaker) }
                Toggle(Icons.Rounded.Pause, "Удержать", held) { if (held) c?.unhold() else c?.hold() }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Toggle(Icons.Rounded.Dialpad, "Клавиши", false) { keypad = true }
                Toggle(if (CallState.peerQuiet) Icons.Rounded.HeadsetOff else Icons.Rounded.Headset, "Звук собеседника", CallState.peerQuiet) { CallState.quietPeer(ctx, !CallState.peerQuiet) }
                Toggle(Icons.Rounded.Bluetooth, "Bluetooth", audio?.route == CallAudioState.ROUTE_BLUETOOTH, enabled = hasBt) { if (audio?.route == CallAudioState.ROUTE_BLUETOOTH) CallState.speaker(false) else CallState.bluetooth() }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (st == Call.STATE_RINGING) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.Rounded.CallEnd, "Сбросить", Red) { c?.let(CallState::hangup) }
            RoundAction(Icons.Rounded.Call, "Ответить", Green) { c?.let(CallState::answer) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (keypad) TextButton({ keypad = false }) { Text("Скрыть", color = Color.White) } else Spacer(Modifier.width(64.dp))
            RoundAction(Icons.Rounded.CallEnd, "Завершить", Red) { c?.let(CallState::hangup) ?: onEnd() }
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = if (enabled) 0.14f else 0.05f)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (on) Color(0xFF2E3350) else Color.White.copy(alpha = if (enabled) 1f else 0.3f))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(34.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp))
    }
}

private val KEYS = listOf(listOf('1' to "", '2' to "АБВГ", '3' to "ДЕЖЗ"), listOf('4' to "ИЙКЛ", '5' to "МНОП", '6' to "РСТУ"),
    listOf('7' to "ФХЦЧ", '8' to "ШЩЪЫ", '9' to "ЬЭЮЯ"), listOf('*' to "", '0' to "+", '#' to ""))

/** Цифровая клавиатура (и для набора номера, и для тонового набора автоответчикам). */
@Composable
fun Keypad(dark: Boolean, onLong0: (() -> Unit)? = null, onKey: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KEYS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { (k, letters) ->
                    Column(Modifier.size(74.dp).clip(CircleShape).background(if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFF0EEF5))
                        .clickable { onKey(k) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("$k", fontSize = 28.sp, color = if (dark) Color.White else Color(0xFF2B2A33))
                        if (letters.isNotBlank()) Text(letters, fontSize = 10.sp, color = if (dark) Color.White.copy(alpha = 0.6f) else Color(0xFF6B6A75))
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") onLong0
}
