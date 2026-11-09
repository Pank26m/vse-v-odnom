package com.pank.vsevodnom.ui.todo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val NOTE_COLORS = listOf(Color(0xFFFFFFFF), Color(0xFFFFF4C7), Color(0xFFDDF2E6), Color(0xFFDCEBFA), Color(0xFFF8DDE6), Color(0xFFE7E1F7), Color(0xFFFCE5D6), Color(0xFFE3E7EC))
private const val TRASH_DAYS = 60

/** Вложения заметки или задачи: фото, голос, рисунки, место, файлы («путь\tимя»), контакты («имя\tтелефон»). */
data class NoteAtt(
    val photos: List<String> = emptyList(), val audio: List<String> = emptyList(), val drawings: List<String> = emptyList(),
    val place: Triple<String, Double, Double>? = null, val files: List<String> = emptyList(), val contacts: List<String> = emptyList(),
    val track: Long? = null,        // запланированный маршрут на «Карте» (id трека)
) {
    fun isEmpty() = photos.isEmpty() && audio.isEmpty() && drawings.isEmpty() && place == null && files.isEmpty() && contacts.isEmpty() && track == null
    fun toJson(): String = if (isEmpty()) "" else JSONObject().put("photos", JSONArray(photos)).put("audio", JSONArray(audio)).put("drawings", JSONArray(drawings))
        .put("files", JSONArray(files)).put("contacts", JSONArray(contacts))
        .apply { place?.let { put("place", JSONObject().put("name", it.first).put("lat", it.second).put("lon", it.third)) }; track?.let { put("track", it) } }.toString()
    companion object {
        private fun l(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        fun of(s: String): NoteAtt = runCatching {
            val o = JSONObject(s)
            NoteAtt(l(o, "photos"), l(o, "audio"), l(o, "drawings"), o.optJSONObject("place")?.let { Triple(it.optString("name"), it.getDouble("lat"), it.getDouble("lon")) },
                l(o, "files"), l(o, "contacts"), if (o.has("track")) o.optLong("track") else null)
        }.getOrDefault(NoteAtt())
    }
}

fun Note.tagList() = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }
fun Note.plain() = (title + "\n" + Rich.render(NoteInline.stripped(body)).text).trim()

private fun copyNote(ctx: Context, n: Note) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Заметка", n.plain()))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}
private fun shareNote(ctx: Context, n: Note) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.plain()), n.title.ifBlank { "Заметка" }))
private fun daysLeft(n: Note) = n.deletedAt?.let { TRASH_DAYS - ((System.currentTimeMillis() - it) / 86_400_000L).toInt() }?.coerceAtLeast(0) ?: TRASH_DAYS

private enum class NSort(val title: String) { UPDATED("Изменённые"), CREATED("Созданные"), TITLE("По названию"), COLOR("По цвету") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val all by dao.notes().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { dao.purgeNotes(System.currentTimeMillis() - TRASH_DAYS * 86_400_000L) }
    val prefs = remember { ctx.getSharedPreferences("notes", Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(runCatching { NSort.valueOf(prefs.getString("sort", "UPDATED")!!) }.getOrDefault(NSort.UPDATED)) }
    var view by remember { mutableIntStateOf(0) }   // 0 заметки, 1 архив, 2 корзина
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Note?>(null) }
    var actions by remember { mutableStateOf<Note?>(null) }
    val base = all.filter { when (view) { 1 -> it.archived && it.deletedAt == null; 2 -> it.deletedAt != null; else -> !it.archived && it.deletedAt == null } }
    val tags = base.flatMap { it.tagList() }.distinct().sorted()
    val shown = base.filter { (tag == null || tag in it.tagList()) && (q.isBlank() || it.title.contains(q, true) || it.body.contains(q, true) || it.tags.contains(q, true)) }
        .let { l -> when (sort) { NSort.UPDATED -> l.sortedByDescending { it.updatedAt }; NSort.CREATED -> l.sortedByDescending { it.createdAt }; NSort.TITLE -> l.sortedBy { it.title.lowercase().ifBlank { it.body.lowercase() } }; NSort.COLOR -> l.sortedBy { it.tint } } }
        .sortedByDescending { it.pinned && view == 0 }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(listOf("Заметки", "Архив", "Корзина")[view], { if (view != 0) view = 0 else onBack() }, actions = {
            RoundIconButton(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "Поиск", { searching = !searching; if (!searching) q = "" })
            Box {
                RoundIconButton(Icons.Rounded.SwapVert, "Сортировка", { sortMenu = true })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    NSort.entries.forEach { s -> DropdownMenuItem({ Text(s.title) }, { sort = s; prefs.edit().putString("sort", s.name).apply(); sortMenu = false }, trailingIcon = { if (sort == s) Icon(Icons.Rounded.Check, null) }) }
                }
            }
            Box {
                RoundIconButton(Icons.Rounded.MoreVert, "Ещё", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Заметки") }, { view = 0; menu = false }, leadingIcon = { Icon(Icons.Rounded.StickyNote2, null) })
                    DropdownMenuItem({ Text("Открыть архив · ${all.count { it.archived && it.deletedAt == null }}") }, { view = 1; menu = false }, leadingIcon = { Icon(Icons.Rounded.Archive, null) })
                    DropdownMenuItem({ Text("Корзина · ${all.count { it.deletedAt != null }}") }, { view = 2; menu = false }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                }
            }
        }) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searching) AppTextField(q, { q = it }, "Поиск по словам")
                if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Все", tag == null, { tag = null }); tags.forEach { t -> Chip("#$t", tag == t, { tag = if (tag == t) null else t }) }
                }
                if (view == 2) Text("Заметки в корзине удаляются насовсем через $TRASH_DAYS дней.", style = MaterialTheme.typography.bodySmall)
            }
            LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalItemSpacing = 10.dp) {
                if (shown.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) { EmptyHint(when (view) { 1 -> "Архив пуст"; 2 -> "Корзина пуста"; else -> "Заметок нет. Нажми «+»." }) }
                items(shown, key = { it.id }) { n ->
                    val att = remember(n.attachments) { NoteAtt.of(n.attachments) }
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(NOTE_COLORS[n.tint % NOTE_COLORS.size])
                        .border(1.dp, Pal.Ink.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { if (view == 2) actions = n else edit = n }, onLongClick = { actions = n }).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (NoteInline.images(n.body).firstOrNull() ?: att.photos.firstOrNull() ?: att.drawings.firstOrNull())?.let { AppImage(it, Modifier.fillMaxWidth().height(110.dp), maxPx = 500) }
                        Row(verticalAlignment = Alignment.Top) {
                            if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
                            else Spacer(Modifier.weight(1f))
                            if (n.pinned && view == 0) Icon(Icons.Rounded.PushPin, "Закреплена", Modifier.size(16.dp), tint = ACCENT)
                        }
                        if (n.body.isNotBlank()) Text(Rich.render(NoteInline.stripped(n.body)), style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (att.audio.isNotEmpty()) Icon(Icons.Rounded.Mic, null, Modifier.size(14.dp), tint = Pal.Muted)
                            if (att.place != null) Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = Pal.Muted)
                            if (att.photos.size + att.drawings.size > 1) Text("+${att.photos.size + att.drawings.size - 1} фото", style = MaterialTheme.typography.labelSmall)
                            if (n.tagList().isNotEmpty()) Text(n.tagList().joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = ACCENT, maxLines = 1)
                        }
                        if (view == 2) Text("Удалится через ${daysLeft(n)} дн.", style = MaterialTheme.typography.labelSmall, color = Pal.RoseInk)
                    }
                }
            }
        }
        if (view == 0) Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Note(title = "", tint = 0) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новая заметка", tint = Color.White) }
    }
    edit?.let { NoteEditor(it) { edit = null } }
    actions?.let { n -> NoteActions(n, onOpen = { edit = n; actions = null }) { actions = null } }
}

/** Меню заметки: закрепить, копировать, отправить, архив, удалить / восстановить. */
@Composable
private fun NoteActions(n: Note, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    fun up(x: Note) { AppScope.io.launch { dao.upsertNote(x) }; onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, title = { Text(n.title.ifBlank { "Заметка" }, maxLines = 1) },
        text = {
            Column {
                @Composable fun A(ic: androidx.compose.ui.graphics.vector.ImageVector, t: String, c: Color = Pal.Ink, f: () -> Unit) =
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = f).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(ic, null, tint = c); Text("   $t", color = c, style = MaterialTheme.typography.bodyLarge) }
                if (n.deletedAt != null) {
                    A(Icons.AutoMirrored.Rounded.Undo, "Восстановить") { up(n.copy(deletedAt = null)) }
                    A(Icons.Rounded.DeleteForever, "Удалить насовсем", Pal.RoseInk) { AppScope.io.launch { dao.deleteNote(n) }; onDismiss() }
                } else {
                    A(Icons.Rounded.Edit, "Открыть") { onOpen() }
                    A(Icons.Rounded.PushPin, if (n.pinned) "Открепить" else "Закрепить") { up(n.copy(pinned = !n.pinned)) }
                    A(Icons.Rounded.ContentCopy, "Скопировать") { copyNote(ctx, n); onDismiss() }
                    A(Icons.Rounded.Share, "Отправить") { shareNote(ctx, n); onDismiss() }
                    A(Icons.Rounded.Archive, if (n.archived) "Вернуть из архива" else "В архив") { up(n.copy(archived = !n.archived)) }
                    A(Icons.Rounded.DeleteOutline, "Удалить (в корзину на $TRASH_DAYS дней)", Pal.RoseInk) { up(n.copy(deletedAt = System.currentTimeMillis())) }
                }
            }
        })
}

/**
 * Вложения прямо в тексте заметки: строка-метка «⟦att:вид:значение⟧» в том месте, где стоял курсор.
 * Виды: photo, draw, audio (путь), file («путь\tимя»), contact («имя\tтелефон»), place («имя|lat|lon»).
 */
object NoteInline {
    val RE = Regex("⟦att:([a-z]+):(.*?)⟧")
    fun marker(kind: String, value: String) = "⟦att:$kind:${value.replace("⟧", "")}⟧"
    sealed class Seg(val key: Long) {
        class Text(key: Long, val v: TextFieldValue) : Seg(key)
        class Att(key: Long, val kind: String, val value: String) : Seg(key)
    }
    private var seq = 0L
    fun nextKey() = ++seq
    fun parse(body: String): List<Seg> {
        val out = ArrayList<Seg>(); var pos = 0
        RE.findAll(body).forEach { m ->
            out += Seg.Text(nextKey(), TextFieldValue(body.substring(pos, m.range.first).removeSuffix("\n")))
            out += Seg.Att(nextKey(), m.groupValues[1], m.groupValues[2])
            pos = m.range.last + 1
            if (pos < body.length && body[pos] == '\n') pos++
        }
        out += Seg.Text(nextKey(), TextFieldValue(body.substring(pos)))
        return out
    }
    fun join(segs: List<Seg>): String = buildString {
        segs.forEachIndexed { i, sg ->
            when (sg) {
                is Seg.Text -> append(sg.v.text)
                is Seg.Att -> { if (isNotEmpty() && !endsWith("\n")) append('\n'); append(marker(sg.kind, sg.value)); append('\n') }
            }
        }
    }.trimEnd()
    /** Текст для плиток и копирования: метки — значками. */
    fun stripped(body: String): String = RE.replace(body) { m ->
        val v = m.groupValues[2]
        when (m.groupValues[1]) { "photo", "draw" -> "📷"; "audio" -> "🎙 голосовое"; "file" -> "📎 " + v.substringAfter('\t'); "contact" -> "👤 " + v.substringBefore('\t'); "place" -> "📍 " + v.substringBefore('|'); else -> "" }
    }
    fun images(body: String): List<String> = RE.findAll(body).filter { it.groupValues[1] == "photo" || it.groupValues[1] == "draw" }.map { it.groupValues[2] }.toList()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditor(n0: Note, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    var title by remember { mutableStateOf(n0.title) }
    val segs = remember { mutableStateListOf<NoteInline.Seg>().apply { addAll(NoteInline.parse(n0.body)) } }
    var focused by remember { mutableIntStateOf(segs.lastIndex) }
    var tint by remember { mutableIntStateOf(n0.tint) }
    var pinned by remember { mutableStateOf(n0.pinned) }
    var tags by remember { mutableStateOf(n0.tagList()) }
    var att by remember { mutableStateOf(NoteAtt.of(n0.attachments)) }   // вложения старых заметок (внизу)
    var panel by remember { mutableStateOf<String?>(null) }   // colors | tags | place | draw
    var menu by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    val voice = remember { VoiceNote(ctx, "notes") }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    /** Вставить вложение туда, где стоит курсор: текст делится, после вложения — новый абзац. */
    fun insert(kind: String, value: String) {
        val i = focused.coerceIn(0, segs.lastIndex).let { if (segs[it] is NoteInline.Seg.Text) it else segs.indexOfLast { s -> s is NoteInline.Seg.Text } }
        val t = segs[i] as NoteInline.Seg.Text
        val cut = t.v.selection.start.coerceIn(0, t.v.text.length)
        segs[i] = NoteInline.Seg.Text(t.key, TextFieldValue(t.v.text.substring(0, cut).trimEnd('\n')))
        segs.add(i + 1, NoteInline.Seg.Att(NoteInline.nextKey(), kind, value))
        segs.add(i + 2, NoteInline.Seg.Text(NoteInline.nextKey(), TextFieldValue(t.v.text.substring(cut).trimStart('\n'), androidx.compose.ui.text.TextRange(0))))
        focused = i + 2
    }
    fun removeAt(i: Int) {
        val sg = segs[i] as? NoteInline.Seg.Att ?: return
        if (sg.kind in setOf("photo", "draw", "audio")) File(sg.value).delete()
        if (sg.kind == "file") File(sg.value.substringBefore('\t')).delete()
        segs.removeAt(i)
        // склеить соседние абзацы
        val a = segs.getOrNull(i - 1) as? NoteInline.Seg.Text; val b = segs.getOrNull(i) as? NoteInline.Seg.Text
        if (a != null && b != null) {
            val txt = listOf(a.v.text, b.v.text).filter { it.isNotEmpty() }.joinToString("\n")
            segs[i - 1] = NoteInline.Seg.Text(a.key, TextFieldValue(txt, androidx.compose.ui.text.TextRange(a.v.text.length))); segs.removeAt(i)
        }
        focused = (i - 1).coerceAtLeast(0)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { l -> l.mapNotNull { copyIntoApp(ctx, it, "notes") }.forEach { insert("photo", it) } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) voice.start() }
    val pickFile = rememberFilePicker("notes") { f -> insert("file", f) }
    val pickContact = rememberContactPicker { c -> insert("contact", c) }
    val pickAudio = rememberFilePicker("notes", arrayOf("audio/*")) { f -> insert("file", f) }
    fun stopVoice() { voice.stop()?.let { insert("audio", it) } }
    fun current() = n0.copy(title = title.trim(), body = NoteInline.join(segs), tint = tint, pinned = pinned, tags = tags.joinToString(","), attachments = att.toJson(), updatedAt = System.currentTimeMillis(),
        createdAt = if (n0.createdAt == 0L) System.currentTimeMillis() else n0.createdAt)
    fun save() {
        if (voice.recording) stopVoice()
        val n = current()
        if (n.title.isBlank() && n.body.isBlank() && att.isEmpty()) { if (n0.id != 0L) AppScope.io.launch { dao.deleteNote(n0) }; onDone(); return }
        AppScope.io.launch { dao.upsertNote(n) }
        onDone()
    }
    val focusedText = segs.getOrNull(focused) as? NoteInline.Seg.Text
    Dialog({ save() }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(NOTE_COLORS[tint % NOTE_COLORS.size]).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ save() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Сохранить и выйти") }
                Spacer(Modifier.weight(1f))
                IconButton({ pinned = !pinned }) { Icon(Icons.Rounded.PushPin, "Закрепить", tint = if (pinned) ACCENT else Pal.Muted) }
                IconButton({ panel = if (panel == "colors") null else "colors" }) { Icon(Icons.Rounded.Palette, "Цвет") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Скопировать") }, { copyNote(ctx, current()); menu = false }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        DropdownMenuItem({ Text("Отправить") }, { shareNote(ctx, current()); menu = false }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                        DropdownMenuItem({ Text(if (n0.archived) "Из архива" else "В архив") }, { AppScope.io.launch { dao.upsertNote(current().copy(archived = !n0.archived)) }; menu = false; onDone() }, leadingIcon = { Icon(Icons.Rounded.Archive, null) })
                        if (n0.id != 0L) DropdownMenuItem({ Text("Удалить", color = Pal.RoseInk) }, { AppScope.io.launch { dao.upsertNote(current().copy(deletedAt = System.currentTimeMillis())) }; menu = false; onDone() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Pal.RoseInk) })
                    }
                }
            }
            if (panel == "colors") Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NOTE_COLORS.forEachIndexed { i, c -> Box(Modifier.size(if (i == tint) 36.dp else 30.dp).clip(CircleShape).background(c).border(if (i == tint) 2.dp else 1.dp, if (i == tint) ACCENT else Pal.Ink.copy(alpha = 0.15f), CircleShape).clickable { tint = i }) }
            }
            // оформление — для абзаца, где курсор
            focusedText?.let { ft -> Box(Modifier.padding(horizontal = 14.dp)) { RichToolbar(ft.v) { nv -> segs[focused] = NoteInline.Seg.Text(ft.key, nv) } } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(title, { title = it }, Modifier.fillMaxWidth().padding(top = 4.dp), textStyle = MaterialTheme.typography.headlineMedium.copy(color = Pal.Ink), cursorBrush = SolidColor(ACCENT),
                    decorationBox = { inner -> if (title.isEmpty()) Text("Заголовок", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFB8BCC6)); inner() })
                segs.forEachIndexed { i, sg ->
                    key(sg.key) {
                        when (sg) {
                            is NoteInline.Seg.Text -> BasicTextField(sg.v, { nv -> segs[i] = NoteInline.Seg.Text(sg.key, Rich.onEdit(sg.v, nv)); focused = i },
                                Modifier.fillMaxWidth().heightIn(min = if (i == segs.lastIndex) 160.dp else 28.dp).onFocusChanged { if (it.isFocused) focused = i },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pal.Ink, lineHeight = 24.sp), cursorBrush = SolidColor(ACCENT), visualTransformation = Rich.transformation,
                                decorationBox = { inner -> if (sg.v.text.isEmpty() && segs.size == 1) Text("Текст заметки", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB8BCC6)); inner() })
                            is NoteInline.Seg.Att -> InlineAtt(sg, voice) { removeAt(i) }
                        }
                    }
                }
                if (voice.recording) Row(verticalAlignment = Alignment.CenterVertically) { RecordingTimer(); TextButton({ stopVoice() }) { Text("Стоп — вставить сюда") } }
                // вложения старых заметок (до 0.28 шли отдельным списком внизу)
                (att.photos + att.drawings).takeIf { it.isNotEmpty() }?.let { imgs ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        imgs.forEach { p ->
                            Box {
                                AppImage(p, Modifier.size(104.dp), maxPx = 400)
                                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0x99000000)).clickable {
                                    att = att.copy(photos = att.photos - p, drawings = att.drawings - p)
                                }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Убрать", tint = Color.White, modifier = Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
                att.audio.forEach { a -> if (File(a).exists()) VoiceChip(a, voice, onDelete = { voice.stopPlay(); att = att.copy(audio = att.audio - a); File(a).delete() }) }
                FilesAndContacts(att) { att = it }
                att.place?.let { (name, lat, lon) ->
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.7f)).clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"))) }
                    }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Place, null, tint = Pal.RoseInk); Text(" $name", style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Rounded.Close, "Убрать", Modifier.padding(start = 8.dp).size(16.dp).clickable { att = att.copy(place = null) }, tint = Pal.Muted)
                    }
                }
                if (tags.isNotEmpty() || panel == "tags") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { t -> Text("#$t  ✕", style = MaterialTheme.typography.labelLarge, color = ACCENT, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Lilac).clickable { tags = tags - t }.padding(10.dp, 6.dp)) }
                }
                if (panel == "tags") Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { AppTextField(newTag, { newTag = it.replace(",", "") }, "Новый тег") }
                    IconButton({ if (newTag.isNotBlank()) { tags = (tags + newTag.trim()).distinct(); newTag = "" } }) { Icon(Icons.Rounded.Add, "Добавить") }
                }
                // всё, что можно добавить, — плитками (вставляется туда, где курсор)
                Text("Добавить в заметку · встанет там, где курсор", style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    @Composable fun A(ic: androidx.compose.ui.graphics.vector.ImageVector, t: String, on: Boolean = false, f: () -> Unit) =
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) ACCENT else Pal.Lilac).clickable(onClick = f).padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, null, Modifier.size(18.dp), tint = if (on) Color.White else Pal.LilacInk); Text(" $t", style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Pal.LilacInk)
                        }
                    A(Icons.Rounded.AddPhotoAlternate, "Фото") { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    A(if (voice.recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (voice.recording) "Стоп" else "Голосовое", voice.recording) {
                        if (voice.recording) stopVoice()
                        else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) voice.start()
                        else mic.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                    A(Icons.Rounded.AudioFile, "Аудио") { pickAudio() }
                    A(Icons.Rounded.AttachFile, "Файл") { pickFile() }
                    A(Icons.Rounded.AddLocationAlt, "Место") { panel = "place" }
                    A(Icons.Rounded.PersonAddAlt, "Контакт") { pickContact() }
                    A(Icons.Rounded.Draw, "Рисунок") { panel = "draw" }
                    A(Icons.Rounded.Checklist, "Список дел") { focusedText?.let { ft -> segs[focused] = NoteInline.Seg.Text(ft.key, Rich.toggleCheckAtCursor(ft.v)) } }
                    A(Icons.Rounded.Sell, "Теги", panel == "tags") { panel = if (panel == "tags") null else "tags" }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (panel == "draw") DrawingDialog(onDismiss = { panel = null }) { p -> insert("draw", p); panel = null }
    if (panel == "place") PlacePicker(onDismiss = { panel = null }) { pl -> insert("place", "${pl.first}|${pl.second}|${pl.third}"); panel = null }
}

/** Вложение внутри текста: фото во всю ширину, голосовое, файл, контакт, место — с крестиком. */
@Composable
private fun InlineAtt(a: NoteInline.Seg.Att, voice: VoiceNote, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    when (a.kind) {
        "photo", "draw" -> Box(Modifier.fillMaxWidth()) {
            AppImage(a.value, Modifier.fillMaxWidth().heightIn(max = 260.dp), maxPx = 1000)
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Убрать", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        "audio" -> if (File(a.value).exists()) VoiceChip(a.value, voice, onDelete = { voice.stopPlay(); onRemove() })
        else -> {
            val (ic, t, sub, open) = when (a.kind) {
                "file" -> { val path = a.value.substringBefore('\t'); listOf(Icons.Rounded.Description, a.value.substringAfter('\t', File(path).name), "файл", { openFile(ctx, path); Unit }) }
                "contact" -> { val ph = a.value.substringAfter('\t', ""); listOf(Icons.Rounded.Person, a.value.substringBefore('\t'), ph.ifBlank { "контакт" },
                    { if (ph.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ph.filter { it.isDigit() || it == '+' }))) }; Unit }) }
                else -> { val p = a.value.split('|'); listOf(Icons.Rounded.Place, p.getOrElse(0) { "Место" }, "место",
                    { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.getOrNull(1)},${p.getOrNull(2)}?q=${p.getOrNull(1)},${p.getOrNull(2)}(${Uri.encode(p.getOrElse(0) { "" })})"))) }; Unit }) }
            }
            @Suppress("UNCHECKED_CAST")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Lilac).clickable { (open as () -> Unit)() }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic as androidx.compose.ui.graphics.vector.ImageVector, null, tint = Pal.LilacInk)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(t as String, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(sub as String, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
                }
                IconButton(onRemove, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

/** Место из «Карты»: сохранённые места или «где я сейчас». */
@Composable
internal fun PlacePicker(onDismiss: () -> Unit, onPick: (Triple<String, Double, Double>) -> Unit) {
    val ctx = LocalContext.current
    var onMap by remember { mutableStateOf(false) }
    if (onMap) { com.pank.vsevodnom.ui.map.MapPointPicker(onDismiss = { onMap = false }) { onPick(it) }; return }
    val places by remember { AppDb.get(ctx).map().places() }.collectAsState(initial = emptyList())
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Отмена") } }, title = { Text("Прикрепить место") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Pal.Lilac).clickable { onMap = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Map, null, tint = Pal.LilacInk); Text("   Выбрать точку на карте", color = Pal.LilacInk)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                        val loc = runCatching { listOf("fused", "gps", "network").firstNotNullOfOrNull { @Suppress("MissingPermission") lm.getLastKnownLocation(it) } }.getOrNull()
                        if (loc != null) onPick(Triple("Моё место", loc.latitude, loc.longitude)) else Toast.makeText(ctx, "Нет доступа к местоположению — открой «Карту» один раз", Toast.LENGTH_SHORT).show()
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.MyLocation, null, tint = ACCENT); Text("   Где я сейчас") }
                }
                if (places.isEmpty()) item { Text("Сохранённых мест нет. На «Карте» нажми на точку → «Сохранить».", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
                items(places) { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(Triple(p.name, p.lat, p.lon)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Place, null, tint = Pal.RoseInk)
                        Column { Text("   ${p.name}"); if (p.folder.isNotBlank()) Text("   ${p.folder}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        })
}

/** Рисование пальцем: цвета, толщина, отмена; сохраняется картинкой в заметку. */
@Composable
internal fun DrawingDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    data class Stroke1(val pts: List<Offset>, val color: Color, val w: Float)
    val strokes = remember { mutableStateListOf<Stroke1>() }
    var cur by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color(0xFF2B2A33)) }
    var width by remember { mutableFloatStateOf(8f) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val colors = listOf(Color(0xFF2B2A33), Color(0xFFE5485D), Color(0xFFF2A33A), Color(0xFF3FB27F), Color(0xFF4772F5), Color(0xFF8E5BD8), Color.White)
    fun path(p: List<Offset>) = Path().apply { p.firstOrNull()?.let { moveTo(it.x, it.y) }; p.drop(1).forEach { lineTo(it.x, it.y) } }
    fun save() {
        if (strokes.isEmpty() || size.width <= 0) { onDismiss(); return }
        val bmp = android.graphics.Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
        strokes.forEach { s ->
            val p = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeWidth = s.w; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; this.color = s.color.toArgb() }
            val ap = android.graphics.Path(); s.pts.firstOrNull()?.let { ap.moveTo(it.x, it.y) }; s.pts.drop(1).forEach { ap.lineTo(it.x, it.y) }
            if (s.pts.size == 1) ap.lineTo(s.pts[0].x + 0.1f, s.pts[0].y)
            c.drawPath(ap, p)
        }
        val f = File(File(ctx.filesDir, "notes").apply { mkdirs() }, "draw_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        onSave(f.absolutePath)
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
                Text("Рисунок", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Отменить") }
                IconButton({ strokes.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Очистить") }
                IconButton({ save() }) { Icon(Icons.Rounded.Check, "Сохранить", tint = ACCENT) }
            }
            Canvas(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { cur = listOf(it) }, onDragEnd = { if (cur.isNotEmpty()) strokes.add(Stroke1(cur, color, width)); cur = emptyList() }) { ch, _ -> cur = cur + ch.position }
                }) {
                size = this.size
                strokes.forEach { s -> drawPath(path(s.pts), s.color, style = Stroke(s.w, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                if (cur.isNotEmpty()) drawPath(path(cur), color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                colors.forEach { c -> Box(Modifier.size(if (c == color) 34.dp else 28.dp).clip(CircleShape).background(c).border(1.dp, Pal.Ink.copy(alpha = 0.2f), CircleShape).clickable { color = c }) }
                Spacer(Modifier.weight(1f))
                listOf(4f, 8f, 16f).forEach { w -> Box(Modifier.size(34.dp).clip(CircleShape).background(if (width == w) Pal.Lilac else Color.Transparent).clickable { width = w }, contentAlignment = Alignment.Center) { Box(Modifier.size((w / 1.5f + 3).dp).clip(CircleShape).background(Pal.Ink)) } }
            }
        }
    }
}
