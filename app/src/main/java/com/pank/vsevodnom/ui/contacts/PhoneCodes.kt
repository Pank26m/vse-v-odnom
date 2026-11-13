package com.pank.vsevodnom.ui.contacts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal

/**
 * Экстренные службы Украины и служебные коды (0.45, «Телефон» → ⋮).
 * Коды GSM (*#21#, *#43#, **04*…#) — стандарт 3GPP, работают у всех операторов; коды операторов
 * помечены «проверь у оператора» — их меняют. «arg» — что вписать перед вызовом (номер, PIN).
 */
object PhoneCodes {
    data class Code(val code: String, val title: String, val note: String = "", val arg: String? = null, val warn: Boolean = false)
    data class Group(val title: String, val items: List<Code>)

    val GROUPS = listOf(
        Group("Экстренные службы", listOf(
            Code("112", "Единый номер экстренной помощи", "работает без SIM и без денег на счёте"),
            Code("101", "Пожарная и спасательная служба (ДСНС)"),
            Code("102", "Полиция"),
            Code("103", "Скорая помощь"),
            Code("104", "Аварийная газовая служба"),
            Code("1545", "Правительственная горячая линия"),
            Code("1547", "Линия против домашнего насилия и торговли людьми"),
            Code("116123", "Национальная линия против домашнего насилия (La Strada)"),
            Code("116111", "Детская линия (La Strada)"),
            Code("7333", "Психологическая поддержка (Lifeline Ukraine)"),
        )),
        Group("Телефон и SIM", listOf(
            Code("*#06#", "IMEI телефона"),
            Code("**04*{}#", "Сменить PIN SIM", "впиши: старый PIN*новый*новый", "старыйPIN*новыйPIN*новыйPIN"),
            Code("**042*{}#", "Сменить PIN2", "старый PIN2*новый*новый", "старыйPIN2*новый*новый"),
            Code("**05*{}#", "Разблокировать SIM по PUK", "PUK*новый PIN*новый PIN — 10 неверных PUK блокируют SIM навсегда", "PUK*новыйPIN*новыйPIN", warn = true),
        )),
        Group("Переадресация", listOf(
            Code("*#21#", "Проверить: всё переадресуется"),
            Code("**21*{}#", "Переадресовывать все звонки", arg = "номер, например +380…"),
            Code("**61*{}#", "Если не отвечаю", arg = "номер"),
            Code("**62*{}#", "Если недоступен", arg = "номер"),
            Code("**67*{}#", "Если занято", arg = "номер"),
            Code("*#61#", "Проверить «не отвечаю»"),
            Code("*#62#", "Проверить «недоступен»"),
            Code("*#67#", "Проверить «занято»"),
            Code("##002#", "Отменить все переадресации"),
        )),
        Group("Ожидание и номер", listOf(
            Code("*43#", "Включить ожидание вызова"),
            Code("#43#", "Выключить ожидание вызова"),
            Code("*#43#", "Проверить ожидание вызова"),
            Code("*#30#", "Показывается ли мне номер звонящего (CLIP)"),
            Code("*#31#", "Скрыт ли мой номер (CLIR)"),
            Code("#31#{}", "Позвонить со скрытым номером (один раз)", arg = "номер"),
            Code("*31#{}", "Позвонить с показом номера (один раз)", arg = "номер"),
        )),
        Group("Операторы (проверь у оператора)", listOf(
            Code("*111#", "Киевстар: меню и баланс"),
            Code("*161#", "Киевстар: мой номер"),
            Code("*101#", "Vodafone: баланс"),
            Code("*111#", "lifecell: меню и баланс"),
        )),
        Group("Служебные меню Android", listOf(
            Code("*#*#4636#*#*", "Сведения о телефоне, батарее и сети (тест)"),
            Code("*#*#225#*#*", "Сведения о календарях (на некоторых сборках)"),
        )),
    )

    /** Набрать код: секретные *#*#…#*#* — через звонилку, USSD — запросом с ответом, остальное — как звонок/MMI. */
    fun dial(ctx: Context, code: String, onAnswer: (String) -> Unit) {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        Regex("^\\*#\\*#(\\d+)#\\*#\\*$").find(code)?.let { m ->
            runCatching { ctx.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(m.groupValues[1]) }.onFailure { Toast.makeText(ctx, "Служебный код открывает только звонилка по умолчанию", Toast.LENGTH_LONG).show() }
            return
        }
        val isUssd = code.startsWith("*") && code.endsWith("#") && !code.startsWith("*#") && !code.startsWith("**") && Regex("^\\*\\d+(\\*\\d+)*#$").matches(code)
        if (isUssd && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val tel = ctx.getSystemService(TelephonyManager::class.java)
            val ok = runCatching {
                tel.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, req: String, resp: CharSequence) = onAnswer(resp.toString())
                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, req: String, failureCode: Int) {
                        // меню с вводом (Киевстар *111#) по запросу не открывается — набираем как обычно
                        com.pank.vsevodnom.call.CallState.place(ctx, code)
                    }
                }, Handler(Looper.getMainLooper()))
            }.isSuccess
            if (ok) return
        }
        // MMI (*#21#, **21*…#, *#06#): звонилка по умолчанию может обработать сама
        if (code.startsWith("*") || code.startsWith("#")) {
            val handled = runCatching { tm.defaultDialerPackage == ctx.packageName && tm.handleMmi(code) }.getOrDefault(false)
            if (handled) return
        }
        ContactsRepo.call(ctx, code)
    }
}

@Composable
fun PhoneCodesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var arg by remember { mutableStateOf<PhoneCodes.Code?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss)
                Text("Службы и коды", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AppTextField(q, { q = it }, "Поиск") }
                PhoneCodes.GROUPS.forEach { g ->
                    val list = g.items.filter { q.isBlank() || it.title.contains(q, true) || it.code.contains(q) }
                    if (list.isEmpty()) return@forEach
                    item(key = "g" + g.title) { Text(g.title, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                    items(list, key = { g.title + it.code + it.title }) { c ->
                        val emergency = g.title.startsWith("Экстрен")
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (emergency) Pal.Rose else Pal.Card)
                            .clickable { if (c.arg != null) arg = c else PhoneCodes.dial(ctx, c.code) { answer = it } }.padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge)
                                if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall, color = if (c.warn) Pal.RoseInk else Pal.Muted)
                            }
                            Text(c.code.replace("{}", "…"), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = if (emergency) Pal.RoseInk else Pal.SkyInk, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
                item { Text("Коды переадресации, ожидания и PIN — стандарт GSM, работают у всех операторов. Коды операторов меняются — если не сработал, проверь на сайте оператора. Ответ USSD (баланс) появится здесь же.",
                    style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
    arg?.let { c ->
        var v by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { arg = null }, title = { Text(c.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(v, { v = it.filter { ch -> ch.isDigit() || ch == '*' || ch == '+' } }, c.arg ?: "",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                Text("Будет набрано: " + c.code.replace("{}", v.ifBlank { "…" }), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        }, confirmButton = { TextButton({ if (v.isNotBlank()) { PhoneCodes.dial(ctx, c.code.replace("{}", v)) { answer = it }; arg = null } }) { Text("Набрать") } },
            dismissButton = { TextButton({ arg = null }) { Text("Отмена") } })
    }
    answer?.let { a -> AlertDialog(onDismissRequest = { answer = null }, title = { Text("Ответ оператора") }, text = { Text(a) }, confirmButton = { TextButton({ answer = null }) { Text("Ок") } }) }
}
