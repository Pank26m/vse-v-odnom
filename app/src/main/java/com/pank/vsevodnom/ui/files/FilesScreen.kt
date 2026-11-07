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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    var appTick by remember { mutableIntStateOf(0) }
    val appFiles = remember(appTick) { AppFiles.groups(ctx) }
    ScreenScaffold("Вспомогательные файлы", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PillButton("Открыть файл", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) }
            item { Text("Сюда — файлы, нужные приложению: расписание пар (.xls), выгрузки Sumlio и веса (.csv), KeePass, PDF и документы. Открываются PDF, картинки, текст, Word, книги, Excel. Из Telegram и проводника — через «Открыть с помощью».", style = MaterialTheme.typography.bodySmall) }
            if (recents.isNotEmpty()) item { SectionLabel("Недавние") }
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
                var menu by remember { mutableStateOf(false) }
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { open = u }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint.ink) }
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.weight(1f))
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted) }
                            DropdownMenu(menu, { menu = false }) {
                                val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: "*/*"
                                DropdownMenuItem({ Text("Открыть") }, { menu = false; open = u })
                                DropdownMenuItem({ Text("Поделиться") }, {
                                    menu = false
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
                                })
                                DropdownMenuItem({ Text("Открыть в другом приложении") }, {
                                    menu = false
                                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть в…")) }
                                })
                                if (kind == Kind.XLS) DropdownMenuItem({ Text("Загрузить как расписание пар") }, { menu = false; importAsSchedule(ctx, u) })
                                FileImports.menuItems(ctx, u, name) { menu = false }
                                DropdownMenuItem({ Text("Убрать из списка", color = Pal.RoseInk) }, { menu = false; Recents.remove(ctx, u); recents = Recents.list(ctx) })
                            }
                        }
                    }
                }
            }
            if (appFiles.isNotEmpty()) item { SectionLabel("Файлы приложения · ${AppFiles.size(appFiles.sumOf { it.bytes })}") }
            appFiles.forEach { g ->
                item(key = "g" + g.title) {
                    var openG by remember { mutableStateOf(false) }
                    AppCard(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { openG = !openG }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, tint = Pal.LilacInk)
                            Text("  ${g.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${g.files.size} · ${AppFiles.size(g.bytes)}", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        }
                        if (openG) g.files.take(100).forEach { f ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name.substringAfter('_', f.name), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f).clickable { com.pank.vsevodnom.ui.todo.openFile(ctx, f.absolutePath) })
                                Text(AppFiles.size(f.length()), style = MaterialTheme.typography.labelSmall, color = Pal.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Файлы внутри приложения (вложения, записи, фото): по папкам, с размером. */
object AppFiles {
    private val NAMES = mapOf("notes" to "Заметки", "tasks" to "Задачи", "mood" to "Настроение", "recordings" to "Диктофон", "rec" to "Диктофон", "cart" to "Корзина", "pdf" to "PDF")
    data class Group(val title: String, val files: List<java.io.File>) { val bytes get() = files.sumOf { it.length() } }
    fun groups(ctx: Context): List<Group> = (ctx.filesDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("vault") && !it.name.startsWith("datastore") }).mapNotNull { d ->
        val fs = d.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }.toList()
        if (fs.isEmpty()) null else Group(NAMES[d.name] ?: d.name, fs)
    }.sortedByDescending { it.bytes }
    fun size(b: Long) = when { b >= 1 shl 20 -> "%.1f МБ".format(b / 1048576.0); b >= 1024 -> "${b / 1024} КБ"; else -> "$b Б" }
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
