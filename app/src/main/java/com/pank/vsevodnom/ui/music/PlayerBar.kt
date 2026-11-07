package com.pank.vsevodnom.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.Music
import com.pank.vsevodnom.data.Player
import com.pank.vsevodnom.ui.common.AppImage
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay

private val BarBg = Color(0xFF1E1D24)
private fun mmss(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

object PlayerUi { var expanded by mutableStateOf(false) }

@Composable
private fun rememberProgress(id: Long?): Pair<Int, Int> {
    var pos by remember { mutableIntStateOf(0) }; var dur by remember { mutableIntStateOf(1) }
    LaunchedEffect(id) { while (true) { pos = Player.position(); dur = Player.duration().coerceAtLeast(1); delay(400) } }
    return pos to dur
}

/**
 * Плеер над нижним баром — на всех экранах, пока что-то играет или на паузе.
 * Нажатие или свайп вверх — раскрывает на весь экран.
 */
@Composable
fun PlayerBar(modifier: Modifier = Modifier) {
    val s = Player.current ?: return
    val (pos, dur) = rememberProgress(s.id)
    Column(
        modifier.fillMaxWidth().background(BarBg)
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12) PlayerUi.expanded = true } }
            .clickable { PlayerUi.expanded = true }
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(pos.toFloat() / dur).fillMaxHeight().background(Color.White))
        }
        Row(Modifier.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppImage(Music.art(s.albumId).toString(), Modifier.size(46.dp), maxPx = 140, radius = 6.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
            IconButton({ Player.prev() }) { Icon(Icons.Rounded.SkipPrevious, "Назад", tint = Color.White) }
            IconButton({ Player.toggle() }) { Icon(if (Player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (Player.playing) "Пауза" else "Играть", tint = Color.White, modifier = Modifier.size(32.dp)) }
            IconButton({ Player.next() }) { Icon(Icons.Rounded.SkipNext, "Дальше", tint = Color.White) }
        }
    }
}

/** Полноэкранный плеер: обложка, перемотка, все кнопки и очередь. Свайп вниз или «назад» — свернуть. */
@Composable
fun FullPlayer() {
    val s = Player.current
    if (s == null) { PlayerUi.expanded = false }
    AnimatedVisibility(PlayerUi.expanded && s != null, enter = slideInVertically(tween(320)) { it } + fadeIn(), exit = slideOutVertically(tween(280)) { it } + fadeOut()) {
        val song = Player.current ?: return@AnimatedVisibility
        BackHandler { PlayerUi.expanded = false }
        val (pos, dur) = rememberProgress(song.id)
        var dragging by remember { mutableStateOf<Float?>(null) }
        var dragY by remember { mutableFloatStateOf(0f) }
        var queueOpen by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { translationY = dragY.coerceAtLeast(0f) }
                .background(Brush.verticalGradient(listOf(Color(0xFF3A3646), Color(0xFF15141A))))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = { if (dragY > 220) PlayerUi.expanded = false; dragY = 0f }) { _, dy -> dragY += dy }
                }
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ PlayerUi.expanded = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Text(if (Player.queue.size > 1) "${Player.index + 1} из ${Player.queue.size}" else "", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ queueOpen = !queueOpen }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", tint = if (queueOpen) Pal.Lilac else Color.White) }
            }
            if (queueOpen) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(Player.queue, key = { i, q -> "$i-${q.id}" }) { i, q ->
                        val cur = i == Player.index
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cur) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { Player.jump(i) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppImage(Music.art(q.albumId).toString(), Modifier.size(40.dp), maxPx = 120, radius = 6.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(q.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                                Text(q.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                            }
                            Text(mmss(q.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(0.4f))
                AppImage(Music.art(song.albumId).toString(), Modifier.fillMaxWidth().aspectRatio(1f), maxPx = 1000, radius = 14.dp)
                Spacer(Modifier.weight(0.5f))
            }
            Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, modifier = Modifier.padding(top = 16.dp))
            Text(song.artist + if (song.album.isNotBlank()) " · ${song.album}" else "", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Slider(dragging ?: pos.toFloat() / dur, { dragging = it }, onValueChangeFinished = { dragging?.let { Player.seek((it * dur).toInt()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
            Row {
                Text(mmss(((dragging?.times(dur))?.toLong() ?: pos.toLong())), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text(mmss(dur.toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ Player.shuffle = !Player.shuffle }) { Icon(Icons.Rounded.Shuffle, "Вперемешку", tint = if (Player.shuffle) Pal.Lilac else Color.White.copy(alpha = 0.8f)) }
                IconButton({ Player.prev() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Назад", tint = Color.White, modifier = Modifier.size(40.dp)) }
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable { Player.toggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (Player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза", tint = BarBg, modifier = Modifier.size(42.dp))
                }
                IconButton({ Player.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Дальше", tint = Color.White, modifier = Modifier.size(40.dp)) }
                IconButton({ Player.repeat = (Player.repeat + 1) % 3 }) { Icon(if (Player.repeat == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор", tint = if (Player.repeat > 0) Pal.Lilac else Color.White.copy(alpha = 0.8f)) }
            }
            TextButton({ PlayerUi.expanded = false; Player.stop() }, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                Text("Закрыть плеер", color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}
