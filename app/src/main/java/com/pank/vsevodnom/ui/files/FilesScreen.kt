package com.pank.vsevodnom.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.AppTheme
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints

/** Недавние файлы: храним Uri с постоянным доступом. */
object Recents {
    private fun p(ctx: Context) = ctx.getSharedPreferences("files", Context.MODE_PRIVATE)
    fun list(ctx: Context): List<Uri> = p(ctx).getString("recent", "")!!.split('\n').filter { it.isNotBlank() }.map(Uri::parse)
    fun add(ctx: Context, u: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val l = (listOf(u) + list(ctx).filter { it != u }).take(30)
        p(ctx).edit().putString("recent", l.joinToString("\n")).apply()
    }
    fun remove(ctx: Context, u: Uri) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        p(ctx).edit().putString("recent", list(ctx).filter { it != u }.joinToString("\n")).apply()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var recents by remember { mutableStateOf(Recents.list(ctx)) }
    var open by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { Recents.add(ctx, u); recents = Recents.list(ctx); open = u }
    }
    open?.let { u ->
        androidx.activity.compose.BackHandler { open = null }
        FileViewer(u, { open = null }, onImportSchedule = { importAsSchedule(ctx, it) })
        return
    }
    ScreenScaffold("Файлы", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PillButton("Открыть файл", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) }
            item { Text("PDF, картинки, текст, Word (.docx), книги (.epub), Excel (.xls, .xlsx). Файлы из Telegram и проводника можно открывать через «Открыть с помощью».", style = MaterialTheme.typography.bodySmall) }
            if (recents.isNotEmpty()) item { SectionLabel("Недавние · удерживай, чтобы убрать") }
            items(recents, key = { it.toString() }) { u ->
                val name = remember(u) { displayName(ctx, u) }
                val kind = remember(u) { kindOf(name, runCatching { ctx.contentResolver.getType(u) }.getOrNull()) }
                val (icon, tint) = when (kind) {
                    Kind.PDF -> Icons.Rounded.PictureAsPdf to Tints.Rose
                    Kind.IMAGE -> Icons.Rounded.Image to Tints.Sky
                    Kind.DOCX, Kind.TEXT -> Icons.Rounded.Description to Tints.Lilac
                    Kind.EPUB -> Icons.Rounded.MenuBook to Tints.Peach
                    Kind.XLS, Kind.XLSX -> Icons.Rounded.TableChart to Tints.Mint
                    Kind.OTHER -> Icons.Rounded.InsertDriveFile to Tints.Butter
                }
                Box(Modifier.clip(RoundedCornerShape(24.dp)).combinedClickable(onClick = { open = u }, onLongClick = { Recents.remove(ctx, u); recents = Recents.list(ctx) })) {
                    AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint.ink) }
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

fun importAsSchedule(ctx: Context, uri: Uri) {
    // копируем к себе: доступ к чужому Uri может не передаться в другое окно
    val local = runCatching {
        val f = java.io.File(ctx.cacheDir, "import-schedule.xls")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }.getOrDefault(uri)
    ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(local)
        .putExtra("import_schedule", true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Просмотрщик для «Открыть с помощью» из других приложений. */
class ViewerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        setContent { AppTheme { FileViewer(uri, { finish() }, onImportSchedule = { importAsSchedule(this, it); finish() }) } }
    }
}
