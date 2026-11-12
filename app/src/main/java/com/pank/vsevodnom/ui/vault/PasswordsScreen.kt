package com.pank.vsevodnom.ui.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pank.vsevodnom.data.PassGen
import com.pank.vsevodnom.data.Totp
import com.pank.vsevodnom.data.Vault
import com.pank.vsevodnom.data.VaultEntry
import com.pank.vsevodnom.data.VaultExtra
import com.pank.vsevodnom.data.VaultFile
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.utils.findActivity
import kotlinx.coroutines.delay
import java.util.UUID

/** Копирование секрета: помечаем как чувствительное и очищаем буфер через 45 секунд. */
fun copySecret(ctx: Context, label: String, value: String, sensitive: Boolean = true) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, value)
    // звёздочками (скрытым) в буфере показываем только пароли и коды; логин — обычным текстом и без автоочистки
    if (!sensitive) { cm.setPrimaryClip(clip); if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Логин скопирован", Toast.LENGTH_SHORT).show(); return }
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching { if (cm.primaryClip?.getItemAt(0)?.text == value) cm.clearPrimaryClip() }
    }, 45_000)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Скопировано · очистится через 45 с", Toast.LENGTH_SHORT).show()
}

/** Файл KeePass, выбранный в «Файлах» — импортируется после разблокировки. */
object PendingVaultImport { var uri by mutableStateOf<android.net.Uri?>(null) }

/** Пока открыт выбор файла для бэкапа, не блокируем хранилище (это другое окно, но наше действие). */
internal object LockGuard { var suppress = false }

@Composable
fun PasswordsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx.findActivity() as? FragmentActivity
    var open by remember { mutableStateOf(Vault.isOpen) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    // запрет скриншотов и показа в списке недавних
    DisposableEffect(Unit) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // автоблокировка при уходе из приложения
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && !LockGuard.suppress) { Vault.lock(); open = false; entries = emptyList() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun unlock() {
        val a = act ?: return
        err = null
        BiometricPrompt(a, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching { Vault.openAfterAuth(ctx); entries = Vault.load(ctx) }
                    .onSuccess { open = true }
                    .onFailure { err = it.message ?: "Не удалось открыть хранилище" }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { err = errString.toString() }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Пароли").setSubtitle("Подтверди, что это ты")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        )
    }
    LaunchedEffect(Unit) { if (Vault.isOpen) entries = runCatching { Vault.load(ctx) }.getOrDefault(emptyList()) else unlock() }

    if (!open) {
        ScreenScaffold("Пароли", onBack) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Lilac), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = Pal.LilacInk, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Хранилище закрыто", style = MaterialTheme.typography.titleLarge)
                Text("Лицо, отпечаток или PIN телефона", style = MaterialTheme.typography.bodySmall)
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
                Spacer(Modifier.height(20.dp))
                PillButton("Открыть", { unlock() }, Modifier.fillMaxWidth())
            }
        }
        return
    }
    VaultContent(entries, onChange = { l -> Vault.save(ctx, l); runCatching { Vault.cleanupFiles(ctx, l) }; entries = l }, onBack = onBack, onLock = { Vault.lock(); open = false; entries = emptyList() })
}

@Composable
private fun VaultContent(entries: List<VaultEntry>, onChange: (List<VaultEntry>) -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val ctx = LocalContext.current
    // вкладки: пароли | документы (сканы, файлы — тем же шифрованием)
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    if (tab == 1) { DocsContent(entries, onChange, onBack, onLock) { tab = it }; return }
    if (tab == 2) { AutoContent(entries, onChange, onBack, onLock) { tab = it }; return }
    val docsN = entries.count { it.kind == "doc" }
    val pwds = entries.filter { it.kind == "" }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<VaultEntry?>(null) }
    var menu by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<String?>(null) } // "export" | "import"
    var pendingBlob by remember { mutableStateOf<ByteArray?>(null) }
    var exportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> LockGuard.suppress = false; if (u != null) { exportUri = u; backup = "export" } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        LockGuard.suppress = false
        if (u != null) { pendingBlob = runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull(); backup = "import" }
    }

    var keepassMsg by remember { mutableStateOf<String?>(null) }
    fun importKeepass(u: android.net.Uri) {
        runCatching {
            val name = com.pank.vsevodnom.ui.files.displayName(ctx, u)
            val text = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val imp = com.pank.vsevodnom.data.KeePassImport.parse(name, text)
            val have = entries.map { it.title.lowercase() to it.login.lowercase() }.toSet()
            val fresh = imp.filter { (it.title.lowercase() to it.login.lowercase()) !in have }
            onChange(entries + fresh)
            "Импортировано: ${fresh.size}" + (if (imp.size > fresh.size) " (повторов пропущено: ${imp.size - fresh.size})" else "") +
                "\n\nТеперь удали файл экспорта с телефона — он не зашифрован."
        }.onSuccess { keepassMsg = it }.onFailure { keepassMsg = "Не получилось: ${it.message}" }
    }
    val keepassPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> LockGuard.suppress = false; u?.let(::importKeepass) }
    LaunchedEffect(PendingVaultImport.uri) { PendingVaultImport.uri?.let { PendingVaultImport.uri = null; importKeepass(it) } }

    val shown = pwds.filter { q.isBlank() || it.title.contains(q, true) || it.login.contains(q, true) || it.url.contains(q, true) }
        .filter { tagFilter == null || (tagFilter == "" && it.tags.isEmpty()) || tagFilter in it.tags }.sortedBy { it.title.lowercase() }
    val allTags = pwds.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    ScreenScaffold("Пароли", onBack, actions = {
        RoundIconButton(Icons.Rounded.Add, "Добавить", { edit = VaultEntry(UUID.randomUUID().toString(), "") }, bg = Pal.Ink, tint = androidx.compose.ui.graphics.Color.White)
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Сохранить бэкап…") }, { menu = false; LockGuard.suppress = true; exporter.launch("пароли-бэкап.vvo") })
                DropdownMenuItem({ Text("Восстановить из бэкапа…") }, { menu = false; LockGuard.suppress = true; importer.launch(arrayOf("*/*")) })
                DropdownMenuItem({ Text("Импорт из KeePass (CSV / XML)…") }, { menu = false; LockGuard.suppress = true; keepassPicker.launch(arrayOf("text/*", "application/xml", "text/xml", "text/csv", "*/*")) })
                DropdownMenuItem({ Text("Закрыть хранилище") }, { menu = false; onLock() })
            }
        }
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultTabs(0, pwds.size, docsN, { tab = it }, entries.count { it.kind == "auto" })
            AppTextField(q, { q = it }, "Поиск")
            if (allTags.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Все · ${pwds.size}", tagFilter == null, { tagFilter = null })
                allTags.forEach { t -> Chip(t, tagFilter == t, { tagFilter = if (tagFilter == t) null else t }) }
                if (pwds.any { it.tags.isEmpty() }) Chip("Без тега", tagFilter == "", { tagFilter = if (tagFilter == "") null else "" })
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pwds.isEmpty()) item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac) {
                    Text("Хранилище пустое", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
                    Text("Записи шифруются ключом, который хранится в защищённом чипе телефона. Обязательно сохрани бэкап (меню ⋮) — без него при потере телефона пароли не восстановить.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(shown, key = { it.id }) { e ->
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { edit = e }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VaultAvatar(e, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (e.login.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.login, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Rounded.ContentCopy, "Скопировать логин", Modifier.padding(start = 6.dp).size(26.dp).clip(RoundedCornerShape(8.dp)).clickable { copySecret(ctx, "Логин", e.login, sensitive = false) }.padding(5.dp), tint = Pal.Muted)
                            }
                            val rest = listOf(if (e.login.isBlank()) e.url else "", e.tags.joinToString(" · ") { "#$it" }).filter { it.isNotBlank() }.joinToString("  ")
                            if (rest.isNotBlank()) Text(rest, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                        }
                        if (e.password.isNotBlank()) IconButton({ copySecret(ctx, "Пароль", e.password) }) { Icon(Icons.Rounded.Key, "Скопировать пароль", tint = Pal.LilacInk) }
                    }
                }
            }
        }
    }

    keepassMsg?.let { m -> AlertDialog(onDismissRequest = { keepassMsg = null }, title = { Text("Импорт KeePass") }, text = { Text(m) }, confirmButton = { TextButton({ keepassMsg = null }) { Text("Ок") } }) }
    edit?.let { e ->
        EntryEditor(e, allTags, onDismiss = { edit = null }, onSave = { n ->
            onChange(entries.filter { it.id != n.id } + n.copy(updatedAt = System.currentTimeMillis())); edit = null
        }, onDelete = if (entries.any { it.id == e.id }) ({ onChange(entries.filter { it.id != e.id }); edit = null }) else null)
    }
    if (backup != null) {
        var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        val exp = backup == "export"
        AlertDialog(
            onDismissRequest = { backup = null },
            title = { Text(if (exp) "Пароль для бэкапа" else "Пароль бэкапа") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (exp) Text("Запомни его: без пароля бэкап не открыть. Хранить файл можно где угодно (Google Диск, флешка).", style = MaterialTheme.typography.bodySmall)
                    AppTextField(p1, { p1 = it }, "Пароль", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (exp) AppTextField(p2, { p2 = it }, "Ещё раз", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk) }
                }
            },
            confirmButton = {
                TextButton({
                    if (exp) {
                        if (p1.length < 8) { msg = "Минимум 8 символов"; return@TextButton }
                        if (p1 != p2) { msg = "Пароли не совпадают"; return@TextButton }
                        runCatching { ctx.contentResolver.openOutputStream(exportUri!!)!!.use { it.write(Vault.exportBackup(entries, p1.toCharArray(), ctx)) } }
                            .onSuccess { Toast.makeText(ctx, "Бэкап сохранён (${entries.size})", Toast.LENGTH_SHORT).show(); backup = null }
                            .onFailure { msg = "Не удалось сохранить: ${it.message}" }
                    } else {
                        val blob = pendingBlob ?: run { msg = "Не удалось прочитать файл"; return@TextButton }
                        runCatching { Vault.importBackup(blob, p1.toCharArray(), ctx) }
                            .onSuccess { imp ->
                                val map = entries.associateBy { it.id }.toMutableMap()
                                imp.forEach { n -> val old = map[n.id]; if (old == null || n.updatedAt >= old.updatedAt) map[n.id] = n }
                                onChange(map.values.toList()); Toast.makeText(ctx, "Восстановлено записей: ${imp.size}", Toast.LENGTH_SHORT).show(); backup = null
                            }
                            .onFailure { msg = it.message }
                    }
                }) { Text(if (exp) "Сохранить" else "Восстановить") }
            },
            dismissButton = { TextButton({ backup = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun TotpTicker(content: @Composable (Long) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    content(now)
}

@Composable
private fun TotpRow(e: VaultEntry, now: Long) {
    val ctx = LocalContext.current
    val p = remember(e.totp) { Totp.parse(e.totp) }
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { p?.let { copySecret(ctx, "Код", Totp.code(it, now)) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall)
                if (p == null) Text("Неверный ключ 2FA", style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk)
                else Text(Totp.code(p, now).chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pal.LilacInk)
            }
            if (p != null) {
                val left = p.period - (now / 1000 % p.period)
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(Pal.Line, 0f, 360f, false, style = Stroke(5f))
                        drawArc(if (left <= 5) Pal.RoseInk else Pal.LilacInk, -90f, 360f * left / p.period, false, style = Stroke(5f, cap = StrokeCap.Round))
                    }
                    Text("$left", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryEditor(e: VaultEntry, knownTags: List<String>, onDismiss: () -> Unit, onSave: (VaultEntry) -> Unit, onDelete: (() -> Unit)?) {
    var title by remember { mutableStateOf(e.title) }
    var login by remember { mutableStateOf(e.login) }
    var pass by remember { mutableStateOf(e.password) }
    var url by remember { mutableStateOf(e.url) }
    var notes by remember { mutableStateOf(e.notes) }
    val extras = remember { mutableStateListOf<VaultExtra>().apply { addAll(e.extra) } }
    val tags = remember { mutableStateListOf<String>().apply { addAll(e.tags) } }
    var newTag by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var avatar by remember { mutableStateOf(e.avatar) }
    val files = remember { mutableStateListOf<VaultFile>().apply { addAll(e.files) } }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching {
            // аватарка — квадрат 256 px, PNG
            val src = ctx.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            val side = minOf(src.width, src.height)
            val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = java.io.ByteArrayOutputStream(); android.graphics.Bitmap.createScaledBitmap(sq, 256, 256, true).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            avatar = Vault.putFile(ctx, out.toByteArray())
        }.onFailure { Toast.makeText(ctx, "Не получилось: ${it.message}", Toast.LENGTH_SHORT).show() }
    }
    fun addFile(u: android.net.Uri) = runCatching {
        val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
        val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "файл"
        files += VaultFile(Vault.putFile(ctx, bytes), name, ctx.contentResolver.getType(u) ?: "application/octet-stream", bytes.size.toLong())
    }.onFailure { Toast.makeText(ctx, "Не получилось: ${it.message}", Toast.LENGTH_SHORT).show() }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { l -> l.forEach { addFile(it) } }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> l.forEach { addFile(it) } }
    val secure = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false)

    fun save() {
        if (title.isBlank()) { Toast.makeText(ctx, "Нужно название", Toast.LENGTH_SHORT).show(); return }
        val ex = extras.filter { it.value.isNotBlank() }.toMutableList()
        // сменили пароль — старый не теряем
        if (e.password.isNotBlank() && pass != e.password && ex.none { it.value == e.password })
            ex += VaultExtra("Старый пароль · " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), e.password)
        val t = (tags + newTag.trim()).filter { it.isNotBlank() }.distinct()
        onSave(e.copy(title = title.trim(), login = login.trim(), password = pass, url = url.trim(), notes = notes, tags = t, extra = ex, avatar = avatar, files = files.toList()))
    }

    Dialog(onDismiss, secure) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss)
                Text(if (e.title.isBlank()) "Новая запись" else e.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
                RoundIconButton(Icons.Rounded.Check, "Сохранить", { save() }, bg = Pal.Ink, tint = androidx.compose.ui.graphics.Color.White)
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { pickAvatar.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                VaultAvatar(e.copy(title = title.ifBlank { "?" }, avatar = avatar), 56.dp)
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("Аватарка", style = MaterialTheme.typography.labelLarge)
                                Row {
                                    TextButton({ pickAvatar.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (avatar.isBlank()) "Из галереи" else "Другая") }
                                    if (avatar.isNotBlank()) TextButton({ avatar = "" }) { Text("Убрать", color = Pal.RoseInk) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AppTextField(title, { title = it }, "Название (сайт, приложение)")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(login, { login = it }, label = { Text("Логин / почта / телефон") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = { if (login.isNotBlank()) IconButton({ copySecret(ctx, "Логин", login, sensitive = false) }) { Icon(Icons.Rounded.ContentCopy, "Скопировать") } })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                Row {
                                    IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать") }
                                    IconButton({ copySecret(ctx, "Пароль", pass) }) { Icon(Icons.Rounded.ContentCopy, "Скопировать") }
                                }
                            }
                        )
                        Row {
                            TextButton({ gen = true }) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сгенерировать") }
                            Spacer(Modifier.weight(1f))
                            TextButton({ extras += VaultExtra("Пароль ${extras.size + 2}", "") }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ещё пароль") }
                        }
                    }
                }
                if (extras.isNotEmpty()) item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Другие и старые пароли")
                        extras.forEachIndexed { i, x ->
                            var vis by remember(i) { mutableStateOf(false) }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicLabelField(x.label, { extras[i] = x.copy(label = it) }, Modifier.weight(1f))
                                IconButton({ extras.removeAt(i) }) { Icon(Icons.Rounded.DeleteOutline, "Убрать", tint = Pal.Muted) }
                            }
                            OutlinedTextField(x.value, { extras[i] = x.copy(value = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                                visualTransformation = if (vis) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Row {
                                        IconButton({ vis = !vis }) { Icon(if (vis) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать") }
                                        IconButton({ copySecret(ctx, x.label, x.value) }) { Icon(Icons.Rounded.ContentCopy, "Скопировать") }
                                    }
                                })
                        }
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        OutlinedTextField(url, { url = it }, label = { Text("Ссылка") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                if (url.isNotBlank()) IconButton({
                                    val u = if (url.startsWith("http")) url else "https://$url"
                                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
                                }) { Icon(Icons.Rounded.OpenInNew, "Открыть") }
                            })
                        Spacer(Modifier.height(8.dp))
                        AppTextField(notes, { notes = it }, "Заметка", singleLine = false)
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Файлы и фото")
                        Text("Хранятся зашифрованными вместе с паролями (КЭП, ключи, сканы документов…). Попадают в бэкап паролей.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                        files.forEachIndexed { i, f ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Pal.Bg).clickable { openVaultFile(ctx, f, share = false) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (f.mime.startsWith("image")) Icons.Rounded.Image else Icons.Rounded.Description, null, tint = Pal.LilacInk)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(com.pank.vsevodnom.ui.map.MapKit.human(f.size), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                                }
                                IconButton({ openVaultFile(ctx, f, share = true) }) { Icon(Icons.Rounded.Share, "Отправить", tint = Pal.Muted) }
                                IconButton({ files.removeAt(i) }) { Icon(Icons.Rounded.DeleteOutline, "Убрать", tint = Pal.Muted) }
                            }
                        }
                        Row {
                            TextButton({ pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Фото") }
                            TextButton({ pickDoc.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Файл") }
                        }
                    }
                }
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Теги")
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (knownTags + tags + listOf("Почта", "Соцсети", "Банки", "Учёба", "Игры", "Работа", "Покупки")).distinct().forEach { t ->
                                Chip(t, t in tags, { if (t in tags) tags.remove(t) else tags.add(t) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { AppTextField(newTag, { newTag = it }, "Новый тег") }
                            IconButton({ newTag.trim().takeIf { it.isNotBlank() }?.let { if (it !in tags) tags.add(it) }; newTag = "" }) { Icon(Icons.Rounded.Add, "Добавить тег") }
                        }
                    }
                }
                if (e.updatedAt > 0 && e.title.isNotBlank()) item {
                    Text("Изменено " + java.time.Instant.ofEpochMilli(e.updatedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                }
                if (onDelete != null) item { TextButton({ confirmDel = true }) { Text("Удалить запись", color = Pal.RoseInk) } }
            }
        }
    }
    if (gen) {
        var len by remember { mutableFloatStateOf(18f) }
        var up by remember { mutableStateOf(true) }; var dg by remember { mutableStateOf(true) }; var sy by remember { mutableStateOf(true) }
        var cand by remember { mutableStateOf(PassGen.generate(18, true, true, true)) }
        LaunchedEffect(len, up, dg, sy) { cand = PassGen.generate(len.toInt(), up, dg, sy) }
        AlertDialog(onDismissRequest = { gen = false }, title = { Text("Генератор") }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(cand, fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.clickable { cand = PassGen.generate(len.toInt(), up, dg, sy) })
                    Text("Длина: ${len.toInt()}", style = MaterialTheme.typography.labelLarge)
                    Slider(len, { len = it }, valueRange = 8f..40f)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("A–Z", up, { up = !up }); Chip("0–9", dg, { dg = !dg }); Chip("!@#", sy, { sy = !sy }) }
                }
            },
            confirmButton = { TextButton({ pass = cand; show = true; gen = false }) { Text("Использовать") } },
            dismissButton = { TextButton({ cand = PassGen.generate(len.toInt(), up, dg, sy) }) { Text("Ещё") } })
    }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Удалить «${e.title}»?") },
        confirmButton = { TextButton({ confirmDel = false; onDelete?.invoke() }) { Text("Удалить", color = Pal.RoseInk) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Отмена") } })
}

/** Подпись поля, которую можно переименовать прямо на месте. */
@Composable
private fun BasicLabelField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, modifier, singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(color = Pal.LilacInk))
}


/** Аватарка записи: своя картинка (расшифровывается в памяти) или первая буква. */
@Composable
private fun VaultAvatar(e: VaultEntry, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, e.avatar) {
        value = if (e.avatar.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Vault.readFile(ctx, e.avatar)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Pal.Lilac), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text(e.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Pal.LilacInk)
    }
}

/** Открыть или отправить вложение: расшифровка во временную папку (чистится при следующем открытии хранилища). */
/**
 * Открыть файл хранилища: расшифрованная временная копия в cache/vault_view (стирается при следующем открытии).
 * inApp — нашим просмотрщиком (картинки, PDF, документы); иначе — «открыть как» (выбор приложения) или отправить.
 */
internal fun openVaultFile(ctx: Context, f: VaultFile, share: Boolean, inApp: Boolean = false) {
    runCatching {
        val bytes = Vault.readFile(ctx, f.id) ?: throw IllegalStateException("Файл не найден")
        val dir = java.io.File(ctx.cacheDir, "vault_view").apply { deleteRecursively(); mkdirs() }
        val out = java.io.File(dir, f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")); out.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        if (inApp && !share) {
            ctx.startActivity(android.content.Intent(ctx, com.pank.vsevodnom.ui.files.ViewerActivity::class.java).setDataAndType(uri, f.mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return@runCatching
        }
        val i = if (share) android.content.Intent(android.content.Intent.ACTION_SEND).setType(f.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
            else android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, f.mime)
        ctx.startActivity(android.content.Intent.createChooser(i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
    }.onFailure { Toast.makeText(ctx, "Не открылось: ${it.message}", Toast.LENGTH_LONG).show() }
}
