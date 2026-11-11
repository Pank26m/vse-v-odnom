package com.pank.vsevodnom.ui.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.Vault
import com.pank.vsevodnom.data.VaultEntry
import com.pank.vsevodnom.data.VaultFile
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.files.Archives
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Переключатель «Пароли | Документы» сверху хранилища. */
@Composable
internal fun VaultTabs(tab: Int, pwds: Int, docs: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(4.dp)) {
        listOf("Пароли · $pwds" to Icons.Rounded.Key, "Документы · $docs" to Icons.Rounded.Description).forEachIndexed { i, (t, ic) ->
            Row(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (tab == i) Pal.Lilac else Color.Transparent).clickable { onTab(i) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, tint = if (tab == i) Pal.LilacInk else Pal.Muted, modifier = Modifier.size(18.dp))
                Text(" $t", style = MaterialTheme.typography.labelLarge, color = if (tab == i) Pal.LilacInk else Pal.Ink)
            }
        }
    }
}

/**
 * Раскладка документов: папка по ключевым словам в пути и имени файла (паспорт, ИНН, права, диплом…), иначе — папка из архива.
 * Страницы одного документа («паспорт_1.jpg», «паспорт (2).jpg», «page 3») собираются в один документ.
 */
object DocSort {
    private val RULES = listOf(
        "Паспорт и ID" to listOf("паспорт", "passport", "id-карт", "id карт", "idcard", "id_card", "закордон"),
        "ИНН и налоги" to listOf("інн", "ипн", "іпн", "инн", "рнокпп", "податк", "налог", "tax", "декларац"),
        "Авто и права" to listOf("права", "водійськ", "водител", "техпаспорт", "техпасп", "реєстрац тз", "автоцив", "осаго", "осцпв", "мопед", "yamaha", "авто", "car"),
        "Учёба" to listOf("диплом", "атестат", "аттестат", "студент", "залік", "зачет", "кнуба", "knuba", "сертиф", "certificate", "довідка з місця навчання"),
        "Медицина" to listOf("мед", "вакцин", "щеплен", "прививк", "covid", "аналіз", "анализ", "лікар", "врач", "086", "063", "полис", "страхов"),
        "Военное" to listOf("військов", "военн", "приписн", "резерв", "тцк", "оберіг", "вобл"),
        "Банк и финансы" to listOf("банк", "iban", "рахун", "счет", "счёт", "кредит", "виписк", "выписк", "monobank", "privat"),
        "Работа" to listOf("трудов", "контракт", "договір", "договор", "ppbud", "наказ", "приказ", "резюме", "cv"),
        "Жильё" to listOf("квартир", "оренд", "аренд", "власн", "собствен", "прописк", "реєстрац місця", "комунал", "дом", "будин"),
        "Семья" to listOf("свідоцтво про народж", "свидетельство о рожд", "шлюб", "брак", "народжен", "рожден"),
    )
    fun folderFor(path: String): String? {
        val low = path.lowercase()
        return RULES.firstOrNull { (_, keys) -> keys.any { low.contains(it) } }?.first
    }
    /** Имя документа без номера страницы и расширения. */
    fun stem(name: String): String = name.substringBeforeLast('.').replace('_', ' ')
        .replace(Regex("\\s*[(\\[]?\\s*(?:стр|стор|page|p)?\\.?\\s*(?<![0-9])\\d{1,2}\\s*[)\\]]?\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[\\s-]+$"), "").trim().ifBlank { name.substringBeforeLast('.') }
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "gif" -> "image/gif"
        "pdf" -> "application/pdf"; "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls", "xlsx" -> "application/vnd.ms-excel"; "txt" -> "text/plain"; "p7s", "jks", "pfx", "zs2", "dat" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
    private val SKIP = setOf(".ds_store", "thumbs.db", "desktop.ini")

    /**
     * Архив → документы: файлы с одинаковым «стволом» имени в одной папке — один документ.
     * Возвращает новые записи (уже с зашифрованными файлами).
     */
    fun fromArchive(ctx: Context, f: java.io.File, pass: String?): List<VaultEntry> {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<VaultFile>>()
        Archives.walk(f, Archives.kind(f.name), pass) { e, ins ->
            if (e.dir || ins == null) return@walk
            val name = e.name.substringAfterLast('/')
            if (name.isBlank() || name.lowercase() in SKIP || name.startsWith("._")) return@walk
            val bytes = ins.readBytes()
            if (bytes.isEmpty()) return@walk
            val dirPath = e.name.substringBeforeLast('/', "")
            val folder = folderFor(e.name) ?: dirPath.substringAfterLast('/').ifBlank { "Разное" }
            val id = Vault.putFile(ctx, bytes)
            groups.getOrPut(folder to stem(name).lowercase()) { ArrayList() } += VaultFile(id, name, mime(name), bytes.size.toLong())
        }
        return groups.map { (k, files) ->
            VaultEntry(UUID.randomUUID().toString(), stem(files.first().name).replaceFirstChar { it.uppercase() }, kind = "doc", folder = k.first,
                files = files.sortedBy { it.name.lowercase() })
        }
    }
}

/** Миниатюра первой картинки документа (расшифровка в памяти, уменьшенная). */
private object DocThumbs {
    val mem = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60 }
    fun get(ctx: Context, f: VaultFile): ImageBitmap? {
        synchronized(mem) { mem[f.id] }?.let { return it }
        if (!f.mime.startsWith("image")) return null
        val b = Vault.readFile(ctx, f.id) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / s > 400) s *= 2
        val bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap() ?: return null
        synchronized(mem) { mem[f.id] = bm }
        return bm
    }
}

@Composable
internal fun DocsContent(entries: List<VaultEntry>, onChange: (List<VaultEntry>) -> Unit, onBack: () -> Unit, onLock: () -> Unit, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs = entries.filter { it.kind == "doc" }
    val folders = docs.map { it.folder.ifBlank { "Разное" } }.distinct().sorted()
    var folder by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<VaultEntry?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var arcFile by remember { mutableStateOf<java.io.File?>(null) }
    var askPass by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun importArchive(f: java.io.File, pass: String?) {
        busy = "Распаковываю и шифрую…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { DocSort.fromArchive(ctx, f, pass) } }
            busy = null
            r.onSuccess { l ->
                if (l.isEmpty()) { msg = "В архиве не нашлось файлов"; return@onSuccess }
                onChange(entries + l); f.delete(); arcFile = null
                msg = "Добавлено документов: ${l.size} (файлов ${l.sumOf { it.files.size }}), папки: " + l.map { it.folder }.distinct().joinToString(", ") +
                    "\n\nИсходный архив с телефона лучше удалить — он не зашифрован."
            }.onFailure { e ->
                val m = e.message.orEmpty()
                if (pass == null && (m.contains("assphrase", true) || m.contains("ncrypt", true) || m.contains("password", true))) askPass = true
                else {
                    msg = if (m.contains("ncrypt", true)) "Архив зашифрован так, что расшифровать его здесь нельзя (RAR5 с паролем libarchive не читает). Распакуй его на компьютере или перепакуй в ZIP с паролем — тогда загрузится."
                    else "Не получилось: $m"
                    f.delete(); arcFile = null
                }
            }
        }
    }
    val arcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        LockGuard.suppress = false
        u ?: return@rememberLauncherForActivityResult
        val name = com.pank.vsevodnom.ui.files.displayName(ctx, u)
        runCatching { Archives.copyToCache(ctx, u, name) }.onSuccess { f -> arcFile = f; importArchive(f, null) }.onFailure { msg = "Не открылся файл: ${it.message}" }
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        LockGuard.suppress = false
        if (us.isEmpty()) return@rememberLauncherForActivityResult
        busy = "Шифрую…"
        scope.launch {
            val files = withContext(Dispatchers.IO) { us.mapNotNull { u -> addFile(ctx, u) } }
            busy = null
            if (files.isEmpty()) return@launch
            val first = files.first().name
            val e = VaultEntry(UUID.randomUUID().toString(), DocSort.stem(first).replaceFirstChar { it.uppercase() }, kind = "doc",
                folder = folder ?: DocSort.folderFor(first) ?: "Разное", files = files)
            onChange(entries + e); open = e
        }
    }

    val shown = docs.filter { (folder == null || it.folder.ifBlank { "Разное" } == folder) && (q.isBlank() || it.title.contains(q, true) || it.folder.contains(q, true) || it.files.any { f -> f.name.contains(q, true) }) }
        .sortedBy { it.title.lowercase() }
    ScreenScaffold("Пароли", onBack, actions = {
        Box {
            RoundIconButton(Icons.Rounded.Add, "Добавить", { addMenu = true }, bg = Pal.Ink, tint = Color.White)
            DropdownMenu(addMenu, { addMenu = false }) {
                DropdownMenuItem({ Text("Документ: фото, сканы, PDF…") }, { addMenu = false; LockGuard.suppress = true; filesPicker.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) })
                DropdownMenuItem({ Text("Архив (zip, rar, 7z) — разложить по папкам") }, { addMenu = false; LockGuard.suppress = true; arcPicker.launch(arrayOf("*/*")) }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
            }
        }
        RoundIconButton(Icons.Rounded.Lock, "Закрыть хранилище", onLock)
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultTabs(1, entries.count { it.kind != "doc" }, docs.size, onTab)
            AppTextField(q, { q = it }, "Поиск документа")
            if (folders.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Все · ${docs.size}", folder == null, { folder = null })
                folders.forEach { f -> Chip("$f · ${docs.count { it.folder.ifBlank { "Разное" } == f }}", folder == f, { folder = if (folder == f) null else f }) }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  $it", style = MaterialTheme.typography.bodySmall) } }
        }
        if (docs.isEmpty()) AppCard(Modifier.fillMaxWidth().padding(16.dp), color = Pal.Lilac) {
            Text("Документов пока нет", style = MaterialTheme.typography.titleSmall, color = Pal.LilacInk)
            Text("«+» — добавить сканы и PDF или целый архив: файлы разложатся по папкам (паспорт, ИНН, права, учёба…). Всё шифруется тем же ключом, что и пароли.", style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { d ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { open = d }) {
                    val img by produceState<ImageBitmap?>(null, d.id) { value = withContext(Dispatchers.IO) { d.files.firstOrNull { it.mime.startsWith("image") }?.let { DocThumbs.get(ctx, it) } } }
                    Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(Pal.Lilac), contentAlignment = Alignment.Center) {
                        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Icon(if (d.files.any { it.mime == "application/pdf" }) Icons.Rounded.PictureAsPdf else Icons.Rounded.Description, null, tint = Pal.LilacInk, modifier = Modifier.size(36.dp))
                    }
                    Column(Modifier.padding(10.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(d.folder.ifBlank { "Разное" } + " · " + d.files.size + " файл.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                    }
                }
            }
        }
    }
    open?.let { d ->
        DocEditor(entries.firstOrNull { it.id == d.id } ?: d, folders, onSave = { nd -> onChange(entries.map { if (it.id == nd.id) nd else it }) },
            onDelete = { onChange(entries.filter { it.id != d.id }); open = null }) { open = null }
    }
    if (askPass) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askPass = false; arcFile?.delete(); arcFile = null }, title = { Text("Архив с паролем") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Пароль архива") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton({ askPass = false; arcFile?.let { importArchive(it, pw) } }) { Text("Открыть") } },
            dismissButton = { TextButton({ askPass = false; arcFile?.delete(); arcFile = null }) { Text("Отмена") } })
    }
    msg?.let { m -> AlertDialog(onDismissRequest = { msg = null }, title = { Text("Документы") }, text = { Text(m) }, confirmButton = { TextButton({ msg = null }) { Text("Ок") } }) }
}

private fun addFile(ctx: Context, u: Uri): VaultFile? = runCatching {
    val name = com.pank.vsevodnom.ui.files.displayName(ctx, u)
    val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
    VaultFile(Vault.putFile(ctx, bytes), name, ctx.contentResolver.getType(u) ?: DocSort.mime(name), bytes.size.toLong())
}.getOrNull()

/** Документ: название, папка, файлы (открыть, отправить, удалить), добавить ещё страницы. */
@Composable
private fun DocEditor(d: VaultEntry, folders: List<String>, onSave: (VaultEntry) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(d.id) { mutableStateOf(d.title) }
    var folder by remember(d.id) { mutableStateOf(d.folder.ifBlank { "Разное" }) }
    var notes by remember(d.id) { mutableStateOf(d.notes) }
    var fMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var askDel by remember { mutableStateOf(false) }
    fun save(files: List<VaultFile> = d.files) = onSave(d.copy(title = title.trim().ifBlank { d.title }, folder = folder, notes = notes, files = files, updatedAt = System.currentTimeMillis()))
    val more = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        LockGuard.suppress = false
        scope.launch { val add = withContext(Dispatchers.IO) { us.mapNotNull { addFile(ctx, it) } }; if (add.isNotEmpty()) save(d.files + add) }
    }
    Dialog({ save(); onDismiss() }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ save(); onDismiss() }) { Icon(Icons.Rounded.Close, "Закрыть") }
                Text("Документ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ askDel = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.RoseInk) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(title, { title = it }, "Название")
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { fMenu = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = Pal.ButterInk); Text("  $folder", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowDropDown, null, tint = Pal.Muted)
                    }
                    DropdownMenu(fMenu, { fMenu = false }) {
                        (folders + listOf("Паспорт и ID", "ИНН и налоги", "Авто и права", "Учёба", "Медицина", "Банк и финансы", "Работа", "Жильё", "Разное")).distinct().forEach { f ->
                            DropdownMenuItem({ Text(f) }, { folder = f; fMenu = false })
                        }
                        DropdownMenuItem({ Text("+ Новая папка…", color = Pal.SkyInk) }, { fMenu = false; newFolder = true })
                    }
                }
                AppTextField(notes, { notes = it }, "Заметка (номер, срок действия…)", singleLine = false)
                Text("Файлы", style = MaterialTheme.typography.titleSmall)
                d.files.forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { LockGuard.suppress = true; openVaultFile(ctx, f, false, inApp = true) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val img by produceState<ImageBitmap?>(null, f.id) { value = withContext(Dispatchers.IO) { DocThumbs.get(ctx, f) } }
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Pal.Lilac), contentAlignment = Alignment.Center) {
                            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                ?: Icon(if (f.mime == "application/pdf") Icons.Rounded.PictureAsPdf else Icons.Rounded.InsertDriveFile, null, tint = Pal.LilacInk)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text("${f.size / 1024} КБ", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                        }
                        IconButton({ LockGuard.suppress = true; openVaultFile(ctx, f, false, inApp = true) }) { Icon(Icons.Rounded.Visibility, "Открыть", tint = Pal.SkyInk) }
                        IconButton({ LockGuard.suppress = true; openVaultFile(ctx, f, false) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть как", tint = Pal.Muted) }
                        IconButton({ LockGuard.suppress = true; openVaultFile(ctx, f, true) }) { Icon(Icons.Rounded.Share, "Отправить", tint = Pal.Muted) }
                        IconButton({ save(d.files.filter { it.id != f.id }) }) { Icon(Icons.Rounded.Close, "Убрать файл", tint = Pal.Muted) }
                    }
                }
                PillButton("Добавить страницы / файлы", { LockGuard.suppress = true; more.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                Text("Файл открывается во временной расшифрованной копии; после закрытия хранилища она удаляется.", style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (newFolder) {
        var n by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("Новая папка") }, text = { AppTextField(n, { n = it }, "Название") },
            confirmButton = { TextButton({ if (n.isNotBlank()) folder = n.trim(); newFolder = false }) { Text("Готово") } })
    }
    if (askDel) AlertDialog(onDismissRequest = { askDel = false }, title = { Text("Удалить документ «${d.title}»?") },
        confirmButton = { TextButton({ askDel = false; onDelete() }) { Text("Удалить", color = Pal.RoseInk) } }, dismissButton = { TextButton({ askDel = false }) { Text("Отмена") } })
}
