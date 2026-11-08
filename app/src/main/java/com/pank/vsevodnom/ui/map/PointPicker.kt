package com.pank.vsevodnom.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.Osm
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap

/** Выбор любой точки на карте: двигаешь карту под булавку, «Выбрать» — место с адресом. */
@Composable
fun MapPointPicker(onDismiss: () -> Unit, onPick: (Triple<String, Double, Double>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("map", android.content.Context.MODE_PRIVATE) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = rememberMapView()
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        var address by remember { mutableStateOf("Двигай карту — булавка в центре") }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            view.getMapAsync { m ->
                map = m
                m.cameraPosition = camera(prefs.getFloat("lat", 50.4501f).toDouble(), prefs.getFloat("lon", 30.5234f).toDouble(), maxOf(prefs.getFloat("zoom", 13f).toDouble(), 13.0))
                m.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(MapStyle.build(ctx, MapStyle.opts(ctx)))) { s ->
                    view.addOnStyleImageMissingListener { id -> if (id.startsWith("p:")) runCatching { s.addImage(id, poiBitmap(ctx, id.removePrefix("p:"), ctx.resources.displayMetrics.density)) } }
                    if (com.pank.vsevodnom.data.TrackService.canStart(ctx)) MapKit.enableLocation(ctx, m, s, false)
                }
                m.addOnCameraIdleListener {
                    val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                    if (com.pank.vsevodnom.data.Net.online) scope.launch { Osm.reverse(t.latitude, t.longitude)?.let { address = it.name + if (it.address.isNotBlank() && it.address != it.name) " · " + it.address else "" } }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Pal.Bg)) {
            AndroidView({ view }, Modifier.fillMaxSize())
            // булавка по центру (низ иконки — точка)
            Icon(Icons.Rounded.Place, null, Modifier.align(Alignment.Center).offset(y = (-22).dp).size(46.dp), tint = Pal.RoseInk)
            Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(Pal.Ink))
            Box(Modifier.dialogBars().padding(12.dp)) { RoundIconButton(Icons.Rounded.Close, "Закрыть", onDismiss) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().dialogBars().padding(12.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(14.dp)) {
                Text(address, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                PillButton(if (busy) "…" else "Выбрать эту точку", {
                    val t = map?.cameraPosition?.target ?: return@PillButton
                    busy = true
                    scope.launch {
                        val name = runCatching { Osm.reverse(t.latitude, t.longitude)?.name }.getOrNull() ?: "%.5f, %.5f".format(t.latitude, t.longitude)
                        onPick(Triple(name, t.latitude, t.longitude))
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}
