package com.pank.vsevodnom.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Поиск: онлайн (OpenStreetMap / Nominatim) или офлайн по скачанным странам — сам переключается без интернета,
 * плюс свои метки и треки и координаты «50.45, 30.52». Google-поиск — второй этап (нужен ключ).
 */
@Composable
internal fun SearchScreen(center: com.pank.vsevodnom.data.GeoPt?, places: List<Place>, tracks: List<Track>, onDismiss: () -> Unit, myLoc: GeoPt? = null, forRoute: Boolean = false, onPick: (GeoHit) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val canOnline = Net.online && !MapPrefs.offlineOnly(ctx)
    val hasOffline = remember { GlFind.offlineAvailable() }
    var offline by remember { mutableStateOf(!canOnline) }
    val hasGoogle = remember { ApiKeys.google(ctx).isNotBlank() }
    var google by remember { mutableStateOf(hasGoogle && canOnline && MapPrefs.bool(ctx, "google_default", true)) }
    var results by remember { mutableStateOf<List<GeoHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(MapPrefs.history(ctx)) }
    val focus = remember { FocusRequester() }
    val lat = center?.lat ?: 50.45; val lon = center?.lon ?: 30.52

    fun own(query: String): List<GeoHit> {
        val w = query.trim().lowercase(); if (w.isEmpty()) return emptyList()
        return places.filter { it.name.lowercase().contains(w) || it.note.lowercase().contains(w) }.take(8).map { GeoHit(it.name, "Моя метка", it.lat, it.lon, "my") } +
            tracks.filter { it.name.lowercase().contains(w) }.take(4).mapNotNull { t -> Osm.decode(t.points).firstOrNull()?.let { GeoHit(t.name, "Мой трек · " + MapPrefs.dist(ctx, t.distanceM), it.lat, it.lon, "mytrack") } }
    }
    suspend fun run(final: Boolean) {
        val query = q.trim()
        if (query.length < 2) { results = emptyList(); error = null; return }
        MapPrefs.parseCoords(query)?.let { (a, b) -> results = listOf(GeoHit("Координаты", MapPrefs.coords(ctx, a, b), a, b, "coords")); return }
        busy = true; error = null
        val mine = own(query)
        val found = if (!offline && google && final) runCatching { GooglePlaces.search(ctx, query, lat, lon) }.onFailure { error = it.message }.getOrElse { emptyList() }
        else if (offline) runCatching { GlFind.search(ctx, query, lat, lon, offline = true) }.getOrElse { emptyList() }
        else if (final) runCatching { Osm.search(query, lat, lon) }.onFailure { error = "Онлайн-поиск не ответил" }.getOrElse { emptyList() } else emptyList()
        results = mine + found
        if (offline && !hasOffline) error = "Для поиска без сети скачай карту страны или области (Настройки → Загрузка карт)"
        else if (final && results.isEmpty()) error = "Ничего не нашлось"
        busy = false
        if (final) { MapPrefs.addHistory(ctx, query); history = MapPrefs.history(ctx) }
    }
    // офлайн — ищем на ходу; онлайн — по кнопке (Nominatim просит не чаще раза в секунду)
    LaunchedEffect(q, offline, google) { delay(if (offline) 250 else 700); run(final = offline && q.trim().length >= 3) }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Pal.Ink)
                }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Card).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Pal.Muted)
                    TextField(q, { q = it }, placeholder = { Text("Адрес, место, координаты") }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { scope.launch { run(final = true) } }))
                    if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Rounded.Close, "Очистить") }
                }
            }
            // источник поиска — плитки во всю ширину
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun Src(ic: androidx.compose.ui.graphics.vector.ImageVector, t: String, sub: String, sel: Boolean, enabled: Boolean, onClick: () -> Unit) =
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Sky else Pal.Card)
                        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(ic, null, tint = if (sel) Pal.SkyInk else if (enabled) Pal.Ink else Pal.Muted, modifier = Modifier.size(22.dp))
                        Text(t, style = MaterialTheme.typography.labelLarge, color = if (sel) Pal.SkyInk else if (enabled) Pal.Ink else Pal.Muted)
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = Pal.Muted, maxLines = 1)
                    }
                if (hasGoogle) Src(Icons.Rounded.TravelExplore, "Google", "заведения", !offline && google, canOnline) { offline = false; google = true }
                Src(Icons.Rounded.Public, if (hasGoogle) "OSM" else "Онлайн", "адреса", !offline && !google, canOnline) { offline = false; google = false }
                Src(Icons.Rounded.CloudOff, "Офлайн", "без сети", offline, true) { offline = true }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            if (!canOnline) Text(if (!Net.online) "Нет интернета — ищу по скачанным картам" else "Онлайн-поиск выключен в настройках", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, modifier = Modifier.padding(16.dp, 6.dp))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.RoseInk, modifier = Modifier.padding(16.dp, 6.dp)) }
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.isBlank()) {
                    // для точки маршрута — сперва «Моё местоположение»
                    if (forRoute) item {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Sky).clickable {
                            onPick(GeoHit("Моё местоположение", "по геолокации", myLoc?.lat ?: Double.NaN, myLoc?.lon ?: Double.NaN, "me"))
                        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MyLocation, null, tint = Pal.SkyInk); Spacer(Modifier.width(12.dp))
                            Column { Text("Моё местоположение", style = MaterialTheme.typography.titleSmall, color = Pal.SkyInk); Text(if (myLoc != null) "сейчас здесь" else "жду геолокацию", style = MaterialTheme.typography.bodySmall, color = Pal.Muted) }
                        }
                    }
                    // избранное плитками, как в маршруте: дом, работа и все свои
                    val favTiles = listOfNotNull(MapPrefs.home(ctx)?.let { Triple(Icons.Rounded.Home, "Дом", it) }, MapPrefs.work(ctx)?.let { Triple(Icons.Rounded.Work, "Работа", it) }) +
                        MapPrefs.favs(ctx).map { Triple(Icons.Rounded.Star, it.name, it) }
                    if (favTiles.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            favTiles.forEach { (ic, n, sp) ->
                                Column(Modifier.width(118.dp).height(84.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card).clickable { onPick(GeoHit(n, sp.sub, sp.lat, sp.lon, "fav")) }.padding(10.dp)) {
                                    Icon(ic, null, tint = Pal.SkyInk)
                                    Text(n, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(sp.sub.ifBlank { "избранное" }, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (history.isNotEmpty()) { item { SectionLabel("Искал") }; items(history) { h -> HitRow(Icons.Rounded.History, h, null) { q = h; if (!offline) scope.launch { run(true) } } } }
                    val rec = MapPrefs.recents(ctx)
                    if (rec.isNotEmpty()) { item { SectionLabel("Недавние места") }; items(rec) { r -> HitRow(Icons.Rounded.Place, r.name, r.sub) { onPick(GeoHit(r.name, r.sub, r.lat, r.lon)) } } }
                } else if (!offline && results.isEmpty() && !busy) item {
                    Text("Нажми «Найти» на клавиатуре", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                }
                items(results) { r ->
                    val icon = when { r.kind == "my" -> Icons.Rounded.Bookmark; r.kind == "mytrack" -> Icons.Rounded.Route; r.kind.startsWith("place:") -> Icons.Rounded.LocationCity
                        r.kind.startsWith("highway:") || r.kind == "addr" -> Icons.Rounded.Signpost; else -> Icons.Rounded.Place }
                    val d = Osm.distance(GeoPt(lat, lon, 0), GeoPt(r.lat, r.lon, 0))
                    HitRow(icon, r.name, listOf(r.address, MapPrefs.dist(ctx, d)).filter { it.isNotBlank() }.joinToString(" · ")) { onPick(r) }
                }
            }
        }
    }
}

@Composable
private fun HitRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pal.SkyInk); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 2) }
        }
    }
}
