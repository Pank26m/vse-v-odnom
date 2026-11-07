package com.pank.vsevodnom.ui.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.Music
import com.pank.vsevodnom.data.Player
import com.pank.vsevodnom.data.Song
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun mmss(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
fun MusicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Music.granted(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var q by remember { mutableStateOf("") }
    var group by remember { mutableIntStateOf(0) }   // 0 все, 1 исполнители, 2 альбомы, 3 папки
    var filter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted) { if (granted) songs = withContext(Dispatchers.IO) { Music.scan(ctx) } }
    ScreenScaffold("Музыка", onBack) {
        if (!granted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Lilac) { Text("Нужен доступ к аудиофайлам — приложение только читает музыку из памяти телефона.", style = MaterialTheme.typography.bodyMedium) }
                PillButton("Дать доступ", { perm.launch(Music.permission) }, Modifier.fillMaxWidth())
            }
            return@ScreenScaffold
        }
        val all = songs
        if (all == null) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@ScreenScaffold }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(q, { q = it }, "Поиск: название, исполнитель, альбом")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все треки", "Исполнители", "Альбомы", "Папки").forEachIndexed { i, s -> Chip(s, group == i, { group = i; filter = null }) }
                Chip(if (sort == 0) "А→Я" else if (sort == 1) "Исполнитель" else "Длительность", false, { sort = (sort + 1) % 3 })
            }
            if (group != 0 && filter == null) {
                val keys = all.map { when (group) { 1 -> it.artist; 2 -> it.album; else -> it.folder } }.groupingBy { it }.eachCount().entries.sortedBy { it.key.lowercase() }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(keys.filter { q.isBlank() || it.key.contains(q, true) }) { _, (k, n) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable { filter = k }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (group) { 1 -> Icons.Rounded.Person; 2 -> Icons.Rounded.Album; else -> Icons.Rounded.Folder }, null, tint = Pal.LilacInk)
                            Text("  ${k.ifBlank { "—" }}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("$n", style = MaterialTheme.typography.labelLarge, color = Pal.Muted)
                        }
                    }
                }
            } else {
                if (filter != null) Row(verticalAlignment = Alignment.CenterVertically) { Text(filter!!, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton({ filter = null }) { Text("Назад к списку") } }
                val list = all.filter { s -> (filter == null || when (group) { 1 -> s.artist; 2 -> s.album; else -> s.folder } == filter) &&
                    (q.isBlank() || s.title.contains(q, true) || s.artist.contains(q, true) || s.album.contains(q, true)) }
                    .let { l -> when (sort) { 1 -> l.sortedBy { it.artist.lowercase() }; 2 -> l.sortedByDescending { it.durationMs }; else -> l.sortedBy { it.title.lowercase() } } }
                if (all.isEmpty()) EmptyHint("Музыки в памяти телефона не нашлось. Скинь mp3/flac/m4a в папку Music или Download.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${list.size} треков · ${mmss(list.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (list.isNotEmpty()) TextButton({ Player.shuffle = true; Player.play(ctx, list, list.indices.random()) }) { Icon(Icons.Rounded.Shuffle, null); Text(" Вперемешку") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                        val cur = Player.current?.id == s.id
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (cur) Pal.Lilac else Pal.Card).clickable { Player.play(ctx, list, i) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppImage(Music.art(s.albumId).toString(), Modifier.size(44.dp), maxPx = 120, radius = 10.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Text(mmss(s.durationMs), style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
                        }
                    }
                }
            }
        }
    }
}

