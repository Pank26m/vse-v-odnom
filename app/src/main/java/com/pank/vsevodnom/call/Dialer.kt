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
import com.pank.vsevodnom.ui.contacts.ContactsRepo
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Открывается по «Набрать номер» из других приложений — нужно, чтобы систему можно было сделать звонилкой по умолчанию. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent?.data?.schemeSpecificPart ?: ""
        setContent { AppTheme { Box(Modifier.fillMaxSize().background(Pal.Bg).systemBarsPadding()) { DialPad(n) { finish() } } } }
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

@Composable
fun DialPad(initial: String = "", onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { runCatching { ContactsRepo.load(ctx) }.getOrDefault(emptyList()) } }
    val found = remember(number, contacts) { contacts.filter { t9Match(it, number.filter { ch -> ch.isDigit() }) }.take(30) }
    val role = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {}
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!CallState.isDefault(ctx)) Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Lilac).clickable {
            ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) }?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
        }.padding(12.dp)) { Text("Сделать звонилкой по умолчанию — тогда звонки будут открываться здесь", style = MaterialTheme.typography.bodySmall, color = Pal.LilacInk) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp, 4.dp)) {
            items(found, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.phones.firstOrNull()?.let { CallState.place(ctx, it.second) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Pal.Sky), contentAlignment = Alignment.Center) { Text(c.name.take(1).uppercase(), color = Pal.SkyInk) }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        c.phones.firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                    }
                    Icon(Icons.Rounded.Call, "Позвонить", tint = Pal.MintInk)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            if (number.isNotEmpty()) IconButton({ number = number.dropLast(1) }) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть", tint = Pal.Muted) }
        }
        Spacer(Modifier.height(8.dp))
        Keypad(dark = false) { k -> number += k }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (onClose != null) TextButton(onClose) { Text("Закрыть") } else Spacer(Modifier.width(64.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF34C77B)).clickable { if (number.isNotBlank()) CallState.place(ctx, number) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, "Позвонить", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
    @Suppress("unused") val unused = Intent()
}
