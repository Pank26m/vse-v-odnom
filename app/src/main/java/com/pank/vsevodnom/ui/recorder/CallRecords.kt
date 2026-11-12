package com.pank.vsevodnom.ui.recorder

import android.content.Intent
import android.media.MediaPlayer
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pank.vsevodnom.call.CallRec
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.utils.findActivity
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Вход в записи разговоров: отпечаток (или код экрана). Закрывается при уходе из приложения. */
object CallRecLock { var open by mutableStateOf(false) }

/** Вкладка «Разговоры» Диктофона: зашифрованные записи звонков, слушать только здесь, выгрузить нужное. */
@Composable
fun CallRecordsTab() {
    val ctx = LocalContext.current
    val act = ctx.findActivity() as? FragmentActivity
    var err by remember { mutableStateOf<String?>(null) }
    // без скриншотов и превью в недавних
    DisposableEffect(Unit) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); CallRec.clearPlay(ctx) }
    }
    val owner = LocalLifecycleOwner.current
    var exporting by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && !exporting) CallRecLock.open = false }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    fun unlock() {
        val a = act ?: return
        err = null
        BiometricPrompt(a, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { CallRecLock.open = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { err = errString.toString() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Записи разговоров").setSubtitle("Подтверди, что это ты")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
    }
    LaunchedEffect(Unit) { if (!CallRecLock.open) unlock() }
    if (!CallRecLock.open) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Fingerprint, null, tint = Pal.RoseInk, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("Записи разговоров закрыты", style = MaterialTheme.typography.titleMedium)
            err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
            Spacer(Modifier.height(12.dp))
            PillButton("Открыть", { unlock() })
        }
        return
    }

    // микрофон нужен заранее: во время звонка окно разрешения не покажешь
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) mic.launch(android.Manifest.permission.RECORD_AUDIO) }
    var list by remember { mutableStateOf<List<CallRec.Meta>?>(null) }
    LaunchedEffect(CallRec.version) { list = withContext(Dispatchers.IO) { CallRec.list(ctx) } }
    var accessOn by remember { mutableStateOf(CallRec.accessOn(ctx)) }
    LaunchedEffect(Unit) { while (true) { accessOn = CallRec.accessOn(ctx); delay(1500) } }
    var enabled by remember { mutableStateOf(CallRec.enabled(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<Long?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var askDel by remember { mutableStateOf<CallRec.Meta?>(null) }
    var exportId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player) { while (player != null) { pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(200) } }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    fun stopPlay() { player?.release(); player = null; playing = null; pos = 0; CallRec.clearPlay(ctx) }
    fun play(m: CallRec.Meta) {
        stopPlay()
        AppScope.io.launch {
            val f = runCatching { CallRec.openForPlay(ctx, m.id) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (f == null) { Toast.makeText(ctx, "Не удалось расшифровать запись", Toast.LENGTH_SHORT).show(); return@withContext }
                runCatching { player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start(); setOnCompletionListener { stopPlay() } }; playing = m.id }
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val id = exportId; exportId = null; exporting = false
        if (uri != null && id != null) AppScope.io.launch {
            val ok = runCatching { CallRec.export(ctx, id, uri) }.isSuccess
            withContext(Dispatchers.Main) { Toast.makeText(ctx, if (ok) "Сохранено" else "Не удалось сохранить", Toast.LENGTH_SHORT).show() }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth(), color = if (enabled && accessOn) Pal.Mint else Pal.Peach, radius = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (!enabled) "Запись звонков выключена" else if (accessOn) "Звонки записываются" else "Нужно включить службу", style = MaterialTheme.typography.titleSmall)
                        Text("Хранится ${CallRec.KEEP_DAYS} дней (закреплённые — пока не удалишь), зашифровано, слушать — только здесь.", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ settings = true }) { Icon(Icons.Rounded.Tune, "Настройки записи") }
                }
                if (enabled && !accessOn) {
                    Text("Без службы «Всё в одном: запись разговоров» Android во время звонка отдаёт тишину. Она ничего не читает с экрана. " +
                        "Если переключатель серый: Настройки → Приложения → Всё в одном → ⋮ → «Разрешить настройки с ограниченным доступом».",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    PillButton("Включить службу", { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (!com.pank.vsevodnom.call.CallState.isDefault(ctx)) Text("Записываются звонки, когда «Всё в одном» — звонилка по умолчанию.", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk, modifier = Modifier.padding(top = 6.dp))
            }
        }
        val l = list
        if (l == null) item { CircularProgressIndicator() }
        else if (l.isEmpty()) item { EmptyHint("Записей разговоров пока нет") }
        else items(l, key = { it.id }) { m ->
            val isPlaying = playing == m.id
            AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Пауза" else "Слушать",
                        { if (isPlaying) stopPlay() else play(m) }, bg = Pal.Rose, tint = Pal.RoseInk)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (m.incoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade, null, Modifier.size(14.dp), tint = Pal.Muted)
                            Text(" " + (m.name ?: m.number.ifBlank { "Неизвестный" }), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (m.pinned) Icon(Icons.Rounded.PushPin, "Закреплено", Modifier.padding(start = 4.dp).size(14.dp), tint = Pal.RoseInk)
                        }
                        val left = CallRec.KEEP_DAYS - (System.currentTimeMillis() - m.id) / 86_400_000L
                        Text(Instant.ofEpochMilli(m.id).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + " · " + fmtDur(m.durMs) +
                            (if (m.name != null) " · ${m.number}" else "") + if (!m.pinned) " · удалится через $left дн." else "",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Скачать на устройство") }, { menu = false; exportId = m.id; exporting = true
                                saver.launch("Разговор " + Instant.ofEpochMilli(m.id).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")) + " " + (m.name ?: m.number) + ".m4a") },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) })
                            DropdownMenuItem({ Text(if (m.pinned) "Открепить (удалится по сроку)" else "Закрепить — не удалять") }, { menu = false; CallRec.setPinned(ctx, m.id, !m.pinned) },
                                leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                            DropdownMenuItem({ Text("Удалить", color = Pal.RoseInk) }, { menu = false; askDel = m }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Pal.RoseInk) })
                        }
                    }
                }
                if (isPlaying && m.durMs > 0) {
                    Slider(pos.toFloat() / m.durMs, { player?.seekTo((it * m.durMs).toInt()); pos = (it * m.durMs).toInt() },
                        colors = SliderDefaults.colors(thumbColor = Pal.RoseInk, activeTrackColor = Pal.RoseInk))
                    Text("${fmtDur(pos.toLong())} / ${fmtDur(m.durMs)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    askDel?.let { m ->
        AlertDialog(onDismissRequest = { askDel = null }, title = { Text("Удалить запись?") }, text = { Text(m.name ?: m.number) },
            confirmButton = { TextButton({ if (playing == m.id) stopPlay(); CallRec.delete(ctx, m.id); askDel = null }) { Text("Удалить", color = Pal.RoseInk) } },
            dismissButton = { TextButton({ askDel = null }) { Text("Отмена") } })
    }
    if (settings) {
        var src by remember { mutableIntStateOf(CallRec.source(ctx)) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Запись разговоров") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Записывать все звонки", modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it; CallRec.setEnabled(ctx, it) })
                }
                Text("Источник звука", style = MaterialTheme.typography.labelLarge)
                CallRec.SOURCES.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { src = i; CallRec.setSource(ctx, i) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(src == i, { src = i; CallRec.setSource(ctx, i) }); Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Android не даёт сторонним приложениям звук линии: пишется микрофон. Твой голос — чётко, собеседник — хорошо на громкой связи, у уха — тихо. " +
                    "Если выбранный источник телефон не даст — запись пойдёт с микрофона.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }, confirmButton = { TextButton({ settings = false }) { Text("Готово") } })
    }
}
