package com.pank.vsevodnom.ui.vault

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pank.vsevodnom.data.Totp
import com.pank.vsevodnom.data.Vault
import com.pank.vsevodnom.data.VaultEntry
import com.pank.vsevodnom.ui.common.AppTextField
import com.pank.vsevodnom.ui.common.PillButton
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.theme.Pal

/**
 * Окно для клавиатуры: отпечаток → пароль (логин/пароль/код 2FA/другие поля) или документы.
 * Пароль уходит в клавиатуру сообщением только внутри приложения (setPackage) и вставляется в поле, где был курсор.
 * Документы (можно несколько) — отправляются в то приложение, где была клавиатура (Telegram и т. п.).
 */
class KbVaultActivity : FragmentActivity() {
    private var entries by mutableStateOf<List<VaultEntry>?>(null)
    private var err by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val docs = intent.getStringExtra("mode") == "doc"
        val target = intent.getStringExtra("target").orEmpty()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                        Text(if (docs) "Документы" else "Пароли", style = MaterialTheme.typography.headlineSmall)
                        val list = entries
                        when {
                            err != null -> { Text(err!!, color = Pal.RoseInk, modifier = Modifier.padding(top = 12.dp)); PillButton("Ещё раз", { unlock() }, Modifier.padding(top = 8.dp)) }
                            list == null -> Text("Подтверди, что это ты…", color = Pal.Muted, modifier = Modifier.padding(top = 12.dp))
                            docs -> DocsPicker(list.filter { it.kind == "doc" }, target)
                            else -> PassPicker(list.filter { it.kind != "doc" })
                        }
                    }
                }
            }
        }
        unlock()
    }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) { Vault.lock(); finish() } }

    private fun unlock() {
        err = null
        if (Vault.isOpen) { entries = runCatching { Vault.load(this) }.getOrNull(); return }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching { Vault.openAfterAuth(this@KbVaultActivity); entries = Vault.load(this@KbVaultActivity) }
                    .onFailure { err = it.message ?: "Не удалось открыть хранилище" }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) finish() else err = errString.toString()
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Клавиатура: пароли").setSubtitle("Подтверди, что это ты")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
    }

    /** Отдать текст клавиатуре и закрыться (поле снова получит фокус — клавиатура вставит). */
    private fun send(text: String) {
        sendBroadcast(Intent("com.pank.vsevodnom.KBD_COMMIT").setPackage(packageName).putExtra("text", text))
        finish()
    }

    @Composable
    private fun PassPicker(list: List<VaultEntry>) {
        var q by remember { mutableStateOf("") }
        var pick by remember { mutableStateOf<VaultEntry?>(null) }
        AppTextField(q, { q = it }, "Найти", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        val shown = list.filter { q.isBlank() || it.title.contains(q, true) || it.login.contains(q, true) || it.url.contains(q, true) }.sortedBy { it.title.lowercase() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shown.isEmpty()) item { Text("Ничего не найдено", color = Pal.Muted) }
            items(shown, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { pick = e }.padding(14.dp, 10.dp)) {
                    Text(e.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleSmall)
                    if (e.login.isNotBlank()) Text(e.login, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
            }
        }
        pick?.let { e ->
            // что вставить: логин, пароль, код 2FA, дополнительные поля
            val opts = buildList {
                if (e.login.isNotBlank()) add("Логин" to e.login)
                if (e.password.isNotBlank()) add("Пароль" to e.password)
                if (e.totp.isNotBlank()) Totp.parse(e.totp)?.let { add("Код 2FA (сейчас)" to Totp.code(it)) }
                e.extra.filter { it.value.isNotBlank() }.forEach { add(it.label.ifBlank { "Поле" } to it.value) }
            }
            AlertDialog(onDismissRequest = { pick = null }, title = { Text(e.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (opts.isEmpty()) Text("В записи нет данных для вставки")
                        opts.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Sky).clickable { send(value) }.padding(12.dp)) {
                                Text(label, color = Pal.SkyInk, modifier = Modifier.weight(1f))
                                Text(if (label.startsWith("Пароль")) "••••••" else value.take(24), color = Pal.Ink)
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ pick = null }) { Text("Отмена") } })
        }
    }

    @Composable
    private fun ColumnScope.DocsPicker(list: List<VaultEntry>, target: String) {
        var sel by remember { mutableStateOf(setOf<String>()) }
        Text("Отметь нужные (паспорт, ИНН, прописку…) — файлы уйдут в приложение, где ты печатал.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted,
            modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (list.isEmpty()) item { Text("Документов нет: «Пароли» → вкладка «Документы»", color = Pal.Muted) }
            list.groupBy { it.folder.ifBlank { "Без папки" } }.toSortedMap().forEach { (folder, docs) ->
                item { Text(folder, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 6.dp)) }
                items(docs, key = { it.id }) { d ->
                    val on = d.id in sel
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Pal.Sky else Pal.Card)
                        .clickable { sel = if (on) sel - d.id else sel + d.id }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title.ifBlank { "Документ" }, style = MaterialTheme.typography.titleSmall)
                            Text("${d.files.size} файл(ов)", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        }
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) Pal.SkyInk else Pal.Muted)
                    }
                }
            }
        }
        PillButton(if (sel.isEmpty()) "Выбери документы" else "Отправить (${sel.size})", { if (sel.isNotEmpty()) shareDocs(list.filter { it.id in sel }, target) },
            Modifier.fillMaxWidth().padding(top = 8.dp))
    }

    private fun shareDocs(docs: List<VaultEntry>, target: String) {
        runCatching {
            val dir = java.io.File(cacheDir, "kbd_share").apply { deleteRecursively(); mkdirs() }
            val uris = ArrayList<android.net.Uri>(); var mime = ""
            docs.flatMap { it.files }.forEach { f ->
                val bytes = Vault.readFile(this, f.id) ?: return@forEach
                val out = java.io.File(dir, f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { f.id })
                out.writeBytes(bytes)
                uris += androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
                mime = if (mime.isEmpty() || mime == f.mime) f.mime else "*/*"
            }
            if (uris.isEmpty()) throw IllegalStateException("в выбранных документах нет файлов")
            val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType(mime.ifBlank { "*/*" })
            if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // сразу в то приложение, где была клавиатура (Telegram покажет выбор чата); если не примет — общий выбор
            val direct = Intent(i).setPackage(target)
            if (target.isNotBlank() && direct.resolveActivity(packageManager) != null) startActivity(direct)
            else startActivity(Intent.createChooser(i, "Отправить документы"))
            finish()
        }.onFailure { Toast.makeText(this, "Не получилось: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
