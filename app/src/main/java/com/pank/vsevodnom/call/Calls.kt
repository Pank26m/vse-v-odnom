package com.pank.vsevodnom.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pank.vsevodnom.R

/**
 * Своя звонилка: когда приложение выбрано звонилкой по умолчанию, система отдаёт звонки сюда (InCallService).
 * Рингтон играет система (IN_CALL_SERVICE_RINGING = false), экран звонка — CallActivity.
 */
object CallState {
    val calls = mutableStateListOf<Call>()
    var tick by mutableIntStateOf(0)                 // любое изменение звонка → перерисовать экран
    var audio by mutableStateOf<CallAudioState?>(null)
    var service: CallService? = null
    var peerQuiet by mutableStateOf(false)
    private var savedVolume = -1

    val current: Call? get() = calls.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING || it.state == Call.STATE_RINGING } ?: calls.firstOrNull()

    fun number(c: Call): String = c.details?.handle?.schemeSpecificPart ?: ""

    /** Последний собеседник: после сброса звонок исчезает из списка, а экран ещё секунду виден —
     *  раньше в эту секунду писалось «Скрытый номер». */
    var lastNumber by mutableStateOf("")
    var lastName by mutableStateOf<String?>(null)
    private val incoming = HashMap<Call, Boolean>()
    fun remember(ctx: Context, c: Call) {
        incoming[c] = c.state == Call.STATE_RINGING || c.details?.callDirection == Call.Details.DIRECTION_INCOMING
        val n = number(c); if (n.isNotBlank()) { lastNumber = n; lastName = name(ctx, n) }
    }
    fun startRec(ctx: Context, c: Call) {
        val n = number(c)
        CallRec.start(ctx, n, name(ctx, n), incoming[c] ?: (c.details?.callDirection == Call.Details.DIRECTION_INCOMING))
    }

    fun name(ctx: Context, number: String): String? = runCatching {
        if (number.isBlank()) return null
        ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun mute(on: Boolean) { service?.setMuted(on) }
    fun speaker(on: Boolean) { service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE) }
    fun bluetooth() { service?.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH) }

    /**
     * «Без звука собеседника»: API, который глушит именно собеседника, у Android нет —
     * поэтому опускаем громкость разговора до нуля (или до минимума, если телефон не даёт ноль) и возвращаем обратно.
     */
    fun quietPeer(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (on) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
            runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL), 0) }
        } else {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            if (savedVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0) }
        }
        peerQuiet = on
    }

    fun answer(c: Call) = c.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun hangup(c: Call) { if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect() }

    /** Позвонить: если мы звонилка по умолчанию — через Telecom (экран звонка будет наш), иначе обычным способом. */
    fun place(ctx: Context, number: String) {
        val uri = Uri.fromParts("tel", number, null)
        val tm = ctx.getSystemService(TelecomManager::class.java)
        runCatching {
            if (tm.defaultDialerPackage == ctx.packageName) tm.placeCall(uri, Bundle())
            else ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun isDefault(ctx: Context) = ctx.getSystemService(TelecomManager::class.java).defaultDialerPackage == ctx.packageName
}

class CallService : InCallService() {
    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            CallState.tick++
            if (state == Call.STATE_ACTIVE) { notifyCall(); CallState.startRec(this@CallService, call) }
            if (state == Call.STATE_DISCONNECTED) notifyCall()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { CallState.tick++ }
    }

    override fun onCreate() { super.onCreate(); CallState.service = this }
    override fun onDestroy() { if (CallState.service === this) CallState.service = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        call.registerCallback(cb)
        CallState.remember(this, call)
        CallState.calls.add(call); CallState.tick++
        notifyCall()
        runCatching { startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        CallState.calls.remove(call); CallState.tick++
        if (CallState.peerQuiet) CallState.quietPeer(this, false)
        if (CallState.calls.isEmpty()) { getSystemService(NotificationManager::class.java).cancel(NID); com.pank.vsevodnom.call.CallRec.stop(this) } else notifyCall()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) { CallState.audio = audioState }

    /** Уведомление звонка — только для входящего: на весь экран (даже на заблокированном), «Ответить» / «Сбросить».
     *  Во время разговора уведомления нет (просьба владельца): вернуться к звонку — через приложение. */
    private fun notifyCall() {
        val c = CallState.current ?: return
        val nm = getSystemService(NotificationManager::class.java)
        if (c.state != Call.STATE_RINGING) { nm.cancel(NID); return }
        nm.createNotificationChannel(NotificationChannel(CH, "Звонки", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(this, 70, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(a: String, rc: Int) = PendingIntent.getBroadcast(this, rc, Intent(this, CallActionReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val num = CallState.number(c)
        val who = CallState.name(this, num) ?: num.ifBlank { "Скрытый номер" }
        val ringing = c.state == Call.STATE_RINGING
        val b = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notify).setContentTitle(who)
            .setContentText(if (ringing) "Входящий звонок" else "Идёт звонок").setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_CALL)
        if (ringing) {
            b.setFullScreenIntent(open, true)
            b.addAction(Notification.Action.Builder(null, "Сбросить", act("decline", 72)).build())
            b.addAction(Notification.Action.Builder(null, "Ответить", act("answer", 71)).build())
        } else b.addAction(Notification.Action.Builder(null, "Завершить", act("decline", 72)).build())
        nm.notify(NID, b.build())
    }

    companion object { const val CH = "calls"; const val NID = 7070 }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val c = CallState.current ?: return
        when (intent.action) {
            "answer" -> { CallState.answer(c); ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "decline" -> CallState.hangup(c)
        }
    }
}
