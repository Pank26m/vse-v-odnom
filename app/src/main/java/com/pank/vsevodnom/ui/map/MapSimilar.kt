package com.pank.vsevodnom.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.GeoHit
import com.pank.vsevodnom.data.GeoPt
import com.pank.vsevodnom.data.MapNames
import com.pank.vsevodnom.data.Osm
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal

/** Запрос «похожие рядом»: от какого места и какой вид (kv = ключ/значение OSM; null — вид неизвестен, только по названию). */
internal data class SimAsk(val target: MapTarget, val kv: Pair<String, String>?)

/** Что выбрано в окне перед поиском. name != null — искать по названию, иначе по виду места. */
internal data class SimParams(val count: Int, val seconds: Int, val km: Int, val name: String?)

/** Окно перед поиском рядом: по виду или по названию, сколько мест, сколько ждать (до 5 мин), радиус ползунком. Выбор запоминается. */
@Composable
internal fun SimilarDialog(ask: SimAsk, onDismiss: () -> Unit, onStart: (SimParams) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { MapPrefs.p(ctx) }
    var byName by remember { mutableStateOf(ask.kv == null) }
    var name by remember { mutableStateOf(ask.target.place?.name ?: ask.target.name) }
    var count by remember { mutableFloatStateOf(p.getInt("sim_count", 30).toFloat()) }
    var secs by remember { mutableFloatStateOf(p.getInt("sim_secs", 60).toFloat()) }
    var km by remember { mutableFloatStateOf(p.getInt("sim_km", 10).toFloat()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Найти рядом") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ask.kv != null) Chip("Такие же: " + MapNames.label(ask.kv.first, ask.kv.second).lowercase(), !byName, { byName = false })
                    Chip("По названию", byName, { byName = true })
                }
                if (byName) AppTextField(name, { name = it }, "Название (например, АТБ)")
                Text("Сколько мест: ${count.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(count, { count = it }, valueRange = 5f..100f, steps = 18)
                Text("Радиус: ${km.toInt()} км", style = MaterialTheme.typography.labelLarge)
                Slider(km, { km = it }, valueRange = 1f..100f)
                Text("Искать не дольше: " + (if (secs < 60) "${secs.toInt()} с" else "${(secs / 60).toInt()} мин" + (if (secs.toInt() % 60 != 0) " ${secs.toInt() % 60} с" else "")),
                    style = MaterialTheme.typography.labelLarge)
                Slider(secs, { secs = (it / 15).toInt() * 15f }, valueRange = 15f..300f)
                Text("Ищется от ближних к дальним; что найдётся за это время — то и покажу.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
        },
        confirmButton = { TextButton({
            p.edit().putInt("sim_count", count.toInt()).putInt("sim_secs", secs.toInt()).putInt("sim_km", km.toInt()).apply()
            onStart(SimParams(count.toInt(), secs.toInt().coerceIn(15, 300), km.toInt().coerceAtLeast(1), if (byName) name.trim().takeIf { it.length >= 2 } else null))
        }, enabled = !byName || name.trim().length >= 2) { Text("Искать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

/** Найденное списком: ближние первыми — название, сколько до него, где находится. */
@Composable
internal fun SimilarList(title: String, hits: List<GeoHit>, from: GeoPt, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember(hits, from) { hits.mapIndexed { i, h -> Triple(i, h, Osm.distance(from, GeoPt(h.lat, h.lon, 0))) }.sortedBy { it.third } }
    FullScreen("$title · ${hits.size}", onDismiss) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows, key = { _, r -> r.first }) { n, (i, h, d) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { onPick(i) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${n + 1}", style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleSmall)
                        if (h.address.isNotBlank()) Text(h.address, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 2)
                    }
                    Text(MapPrefs.dist(ctx, d), style = MaterialTheme.typography.labelLarge, color = Pal.SkyInk, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
