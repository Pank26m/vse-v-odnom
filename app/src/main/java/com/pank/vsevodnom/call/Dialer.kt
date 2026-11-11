package com.pank.vsevodnom.call

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.contacts.Contact
import com.pank.vsevodnom.ui.contacts.PhoneState
import com.pank.vsevodnom.data.PhoneData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.contacts.ContactsRepo
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Открывается по «Набрать номер» из других приложений (и как звонилка по умолчанию) — тот же «Телефон» на клавиатуре. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent?.data?.schemeSpecificPart ?: ""
        setContent { AppTheme { com.pank.vsevodnom.ui.contacts.PhoneScreen({ finish() }, start = 3, number = n) } }
    }
}

private val T9 = mapOf('2' to "абвгabc", '3' to "дежзdef", '4' to "ийклghi", '5' to "мнопjkl", '6' to "рстуmno", '7' to "фхцчpqrs", '8' to "шщъыtuv", '9' to "ьэюяwxyz", '0' to "+ ")

/** Совпадение по T9: «264» найдёт «Аня», «Боря»… — по началу любого слова в имени; или по цифрам номера. */
fun t9Match(c: Contact, digits: String): Boolean {
    if (digits.isEmpty()) return false
    if (c.phones.any { it.second.filter(Char::isDigit).contains(digits) }) return true
    if (digits.any { it == '1' || it == '*' || it == '#' }) return false
    return c.name.lowercase().replace('ё', 'е').replace('і', 'и').replace('ї', 'и').replace('є', 'е').split(' ', '-').any { w ->
        w.length >= digits.length && digits.indices.all { i -> T9[digits[i]]?.contains(w[i]) == true }
    }
}

/** Звук клавиш — классические тоны DTMF, тихо и коротко. */
private object KeyTones {
    private var gen: android.media.ToneGenerator? = null
    fun play(ctx: android.content.Context, k: Char) {
        if (!PhoneData.soundsOn(ctx)) return
        val tone = when (k) {
            in '0'..'9' -> android.media.ToneGenerator.TONE_DTMF_0 + (k - '0')
            '*' -> android.media.ToneGenerator.TONE_DTMF_S; '#' -> android.media.ToneGenerator.TONE_DTMF_P; else -> android.media.ToneGenerator.TONE_DTMF_0
        }
        runCatching { (gen ?: android.media.ToneGenerator(android.media.AudioManager.STREAM_DTMF, 70).also { gen = it }).startTone(tone, 120) }
    }
}

private val PAD = listOf(listOf('1' to "", '2' to "АБВГ", '3' to "ДЕЖЗ"), listOf('4' to "ИЙКЛ", '5' to "МНОП", '6' to "РСТУ"),
    listOf('7' to "ФХЦЧ", '8' to "ШЩЪЫ", '9' to "ЬЭЮЯ"), listOf('*' to "#", '0' to "", '+' to ""))

/** Вкладка «Клавиатура»: справа от нуля «+», «#» — удержанием «*», удержание 2–9 — быстрый набор, звук клавиш. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadTab(st: PhoneState, initial: String = "", onBack: () -> Unit, onNewContact: (String) -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    var menu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(false) }
    var sounds by remember { mutableStateOf(PhoneData.soundsOn(ctx)) }
    val contacts = st.contacts.orEmpty()
    val found = remember(number, contacts) { contacts.filter { t9Match(it, number.filter { ch -> ch.isDigit() }) }.take(30) }
    val role = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {}
    fun key(k: Char) { KeyTones.play(ctx, k); number += k }
    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Клавиатура", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Быстрый набор…") }, { menu = false; speed = true })
                    DropdownMenuItem({ Text(if (sounds) "Звук клавиш: вкл" else "Звук клавиш: выкл") }, { sounds = !sounds; PhoneData.setSoundsOn(ctx, sounds) })
                }
            }
        }
        if (!CallState.isDefault(ctx)) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Lilac).clickable {
            ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) }?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
        }.padding(12.dp)) { Text("Сделать звонилкой по умолчанию — тогда звонки будут открываться здесь", style = MaterialTheme.typography.bodySmall, color = Pal.LilacInk) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp, 4.dp)) {
            items(found, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.phones.firstOrNull()?.let { CallState.place(ctx, it.second) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.pank.vsevodnom.ui.contacts.Avatar(c.name, 38, c.photo)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        c.phones.firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    }
                    Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk)
                }
            }
        }
        // набранный номер: слева «+» — в контакты, справа — стереть (удержание — всё)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (number.isNotEmpty() && found.none { c -> c.phones.any { it.second.filter(Char::isDigit).takeLast(9) == number.filter(Char::isDigit).takeLast(9) } })
                    IconButton({ onNewContact(number) }) { Icon(Icons.Rounded.PersonAdd, "Добавить в контакты", tint = Pal.SkyInk) }
            }
            Text(number, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(enabled = number.isNotEmpty(), onClick = { number = number.dropLast(1) }, onLongClick = { number = "" }),
                contentAlignment = Alignment.Center) {
                if (number.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть", tint = Pal.Muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PAD.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { (k, sub) ->
                        Column(Modifier.size(width = 86.dp, height = 64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFFF0EEF5))
                            .combinedClickable(onClick = { key(k) }, onLongClick = {
                                when (k) {
                                    '*' -> key('#')
                                    '0' -> key('+')
                                    in '2'..'9' -> PhoneData.speedDial(ctx)[k - '0']?.let { CallState.place(ctx, it.second) } ?: run { speed = true }
                                    else -> key(k)
                                }
                            }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("$k", fontSize = 28.sp, color = Color(0xFF2B2A33))
                            if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, color = Color(0xFF6B6A75))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(width = 120.dp, height = 64.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xFF34C77B)).clickable {
            if (number.isNotBlank()) CallState.place(ctx, number)
            else st.calls.firstOrNull { it.type == android.provider.CallLog.Calls.OUTGOING_TYPE }?.let { number = it.number }   // пусто — подставить последний набранный
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Call, "Позвонить", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
    if (speed) com.pank.vsevodnom.ui.contacts.SpeedDialDialog(st) { speed = false }
}
