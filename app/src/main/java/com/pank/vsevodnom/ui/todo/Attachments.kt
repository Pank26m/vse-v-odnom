package com.pank.vsevodnom.ui.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pank.vsevodnom.ui.theme.Pal
import java.io.File

private fun displayName(ctx: Context, u: Uri): String = runCatching {
    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: (u.lastPathSegment ?: "файл")

/** Выбор любого файла (PDF, аудио, документ…): копия кладётся внутрь приложения. Возвращает «путь\tимя». */
@Composable
fun rememberFilePicker(dir: String, types: Array<String> = arrayOf("*/*"), onPicked: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult
        runCatching {
            val name = displayName(ctx, u).replace('\t', ' ')
            val d = File(ctx.filesDir, "$dir/files").apply { mkdirs() }
            val f = File(d, "${System.currentTimeMillis()}_" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            ctx.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            onPicked(f.absolutePath + "\t" + name)
        }
    }
    return { l.launch(types) }
}

/** Выбор контакта из телефонной книги. Возвращает «имя\tтелефон». */
@Composable
fun rememberContactPicker(onPicked: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(u, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) onPicked((c.getString(0) ?: "Контакт").replace('\t', ' ') + "\t" + (c.getString(1) ?: ""))
            }
        }
    }
    // выбор телефона из контактов не требует разрешения на чтение всей книги
    return { l.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
}

fun openFile(ctx: Context, path: String) = runCatching {
    val f = File(path)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть"))
}

/** Файлы и контакты из вложений: нажатие — открыть / позвонить, крестик — убрать. */
@Composable
fun FilesAndContacts(att: NoteAtt, onChange: (NoteAtt) -> Unit) {
    val ctx = LocalContext.current
    att.files.forEach { f ->
        val path = f.substringBefore('\t'); val name = f.substringAfter('\t', File(path).name)
        AttRow(if (name.endsWith(".pdf", true)) Icons.Rounded.PictureAsPdf else if (Regex("(?i).*\\.(mp3|m4a|ogg|wav|flac|aac)$").matches(name)) Icons.Rounded.AudioFile else Icons.Rounded.Description,
            name, "файл", { openFile(ctx, path) }) { File(path).delete(); onChange(att.copy(files = att.files - f)) }
    }
    att.contacts.forEach { c ->
        val name = c.substringBefore('\t'); val phone = c.substringAfter('\t', "")
        AttRow(Icons.Rounded.Person, name, phone.ifBlank { "контакт" }, { if (phone.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) } }) {
            onChange(att.copy(contacts = att.contacts - c))
        }
    }
}

@Composable
private fun AttRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.75f)).clickable(onClick = onClick).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ACCENT)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
        }
        IconButton(onRemove, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted, modifier = Modifier.size(16.dp)) }
    }
}
