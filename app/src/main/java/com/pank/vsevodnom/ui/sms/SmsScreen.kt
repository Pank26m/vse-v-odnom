package com.pank.vsevodnom.ui.sms

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.Sms
import com.pank.vsevodnom.data.SmsRepo
import com.pank.vsevodnom.data.SmsThread
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.vault.copySecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun whenText(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return when (t.toLocalDate()) {
        LocalDate.now() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        LocalDate.now().minusDays(1) -> "вчера"
        else -> t.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

/** Текст СМС как есть (язык оригинала), код — жирным. */
@Composable
private fun SmsText(body: String, code: String?, size: Int = 15) {
    val idx = code?.let { Regex(it.toCharArray().joinToString("[- ]?")).find(body) }
    Text(buildAnnotatedString {
        if (idx == null) append(body) else {
            append(body.substring(0, idx.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Pal.LilacInk, fontSize = (size + 3).sp)) { append(idx.value) }
            append(body.substring(idx.range.last + 1))
        }
    }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp))
}

@Composable
fun SmsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(SmsRepo.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<Sms>?>(null) }
    var threads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<SmsThread?>(null) }
    var settings by remember { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = SmsRepo.hasPermission(ctx); denied = !granted; version++
    }
    LaunchedEffect(Unit) {
        if (!granted) perm.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(granted, version) {
        if (!granted) return@LaunchedEffect
        val (a, t) = withContext(Dispatchers.IO) { val a = SmsRepo.load(ctx); a to SmsRepo.threads(ctx, a) }
        all = a; threads = t
    }

    open?.let { th ->
        BackHandler { open = null }
        ThreadView(th, all.orEmpty(), onBack = { open = null }, onSpamChanged = { version++; open = null })
        return
    }

    ScreenScaffold("СМС", onBack, actions = {
        RoundIconButton(Icons.Rounded.DoneAll, "Прочитать всё", { SmsRepo.markAllRead(ctx); version++ })
        RoundIconButton(Icons.Rounded.Settings, "Фильтр спама", { settings = true })
    }) {
        if (!granted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Peach) {
                    Text("Нужен доступ к СМС", style = MaterialTheme.typography.titleSmall, color = Pal.PeachInk)
                    Text(
                        "Приложение только читает сообщения — ничего не отправляет и не удаляет. Google Messages остаётся основным.\n\n" +
                            "Если Android пишет «Ограниченная настройка»: Настройки → Приложения → Всё в одном → ⋮ (вверху справа) → «Разрешить ограниченные настройки», затем вернись сюда.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                PillButton("Дать доступ", {
                    if (denied) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    else perm.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS))
                }, Modifier.fillMaxWidth())
                if (denied) PillButton("Проверить снова", { granted = SmsRepo.hasPermission(ctx); version++ }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
            }
            return@ScreenScaffold
        }
        val inbox = threads.filter { !it.spam }
        val codes = threads.filter { it.code != null && !it.spam }
        val spam = threads.filter { it.spam }
        Segmented(listOf("Входящие ${inbox.size}", "Коды ${codes.size}", "Спам ${spam.size}"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Sky)
        Spacer(Modifier.height(10.dp))
        val list = when (tab) { 0 -> inbox; 1 -> codes; else -> spam }
        if (all == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == 1) {
            }
            if (all != null && list.isEmpty() && tab != 1) item { EmptyHint(when (tab) { 2 -> "Спама нет"; else -> "Сообщений нет" }) }
            items(list, key = { it.address }) { t ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { SmsRepo.markRead(ctx, t.address); version++; open = t }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(if (t.spam) Pal.Line else Pal.Sky), contentAlignment = Alignment.Center) {
                            Text((t.name ?: t.address).take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Pal.SkyInk)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name ?: t.address, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (t.unread > 0) FontWeight.ExtraBold else FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
                                Text(whenText(t.last.date), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                            }
                            if (tab == 1 && t.code != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.code, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pal.LilacInk, modifier = Modifier.weight(1f))
                                    TextButton({ copySecret(ctx, "Код", t.code) }) { Text("Скопировать") }
                                }
                            } else Text(t.last.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (t.unread > 0) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(10.dp).clip(CircleShape).background(Pal.SkyInk)) }
                    }
                }
            }
        }
    }

    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Спам") },
        text = { Text("Фильтра по словам больше нет. Когда приходит СМС от незнакомого номера, сверху появляется «Это спам?»: «Да» — отправитель уходит в «Спам» (сообщения не удаляются), «Нет» — больше не спрошу. Коды из 4 или 6 цифр сами попадают в «Коды». Передумал — открой переписку и нажми кнопку вверху.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton({ settings = false }) { Text("Понятно") } })
}

@Composable
private fun ThreadView(t: SmsThread, all: List<Sms>, onBack: () -> Unit, onSpamChanged: () -> Unit) {
    val ctx = LocalContext.current
    val norm = remember(t) { t.address.filter { it.isLetterOrDigit() }.let { if (it.length >= 9 && it.all(Char::isDigit)) it.takeLast(9) else it.lowercase() } }
    val msgs = remember(t, all) {
        all.filter { it.address.filter { c -> c.isLetterOrDigit() }.let { a -> if (a.length >= 9 && a.all(Char::isDigit)) a.takeLast(9) else a.lowercase() } == norm }.reversed()
    }
    ScreenScaffold(t.name ?: t.address, onBack, actions = {
        RoundIconButton(if (t.spam) Icons.Rounded.CheckCircle else Icons.Rounded.Block, if (t.spam) "Не спам" else "Это спам", {
            SmsRepo.markSpam(ctx, t.address, !t.spam); onSpamChanged()
        })
    }) {
        val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (msgs.size - 1).coerceAtLeast(0))
        LazyColumn(state = state, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.spam) item { AppCard(Modifier.fillMaxWidth(), color = Pal.Line, padding = 10.dp) { Text("Отмечено как спам. Кнопка вверху справа — вернуть во входящие.", style = MaterialTheme.typography.bodySmall) } }
            items(msgs, key = { it.id }) { m ->
                val code = SmsRepo.extractCode(m.body)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.inbox) Alignment.Start else Alignment.End) {
                    Column(
                        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(if (m.inbox) Pal.Card else Pal.Sky).padding(12.dp)
                    ) {
                        SelectionContainer { SmsText(m.body, code) }
                        if (code != null) TextButton({ copySecret(ctx, "Код", code) }, contentPadding = PaddingValues(0.dp)) { Text("Скопировать $code") }
                    }
                    Text(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}
