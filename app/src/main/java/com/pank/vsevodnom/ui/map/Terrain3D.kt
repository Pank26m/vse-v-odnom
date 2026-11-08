package com.pank.vsevodnom.ui.map

import android.annotation.SuppressLint
import android.database.sqlite.SQLiteDatabase
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.OfflineMaps
import com.pank.vsevodnom.ui.common.dialogBars
import com.pank.vsevodnom.ui.theme.Pal
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 3D-рельеф (этап 3). MapLibre для Android не умеет рельеф в 3D, поэтому здесь — MapLibre GL JS (BSD) во встроенном WebView.
 * Всё офлайн: страница, библиотека, шрифты и значки — из assets, тайлы — прямо из скачанных .mbtiles через перехват запросов
 * к «https://app.local/…». Тот же стиль, что и на обычной карте (MapStyle), плюс terrain из рельефа страны.
 */
object Terrain3D {
    private const val HOST = "https://app.local/"

    class Bundle(val style: String, val files: Map<String, String>, val hasDem: Boolean)

    fun bundle(ctx: android.content.Context): Bundle {
        val opts = MapStyle.opts(ctx).copy(online = null, traffic = null)
        val s = JSONObject(MapStyle.build(ctx, opts))
        val files = HashMap<String, String>()
        val sources = s.getJSONObject("sources")
        var dem: String? = null
        sources.keys().asSequence().toList().forEach { k ->
            val src = sources.getJSONObject(k)
            val url = src.optString("url")
            if (url.startsWith("mbtiles://")) {
                val path = url.removePrefix("mbtiles://")
                val key = File(path).nameWithoutExtension
                files[key] = path
                src.remove("url")
                src.put("tiles", JSONArray().put("${HOST}t/$key/{z}/{x}/{y}"))
                meta(path)?.let { (mn, mx) -> src.put("minzoom", mn); src.put("maxzoom", mx) }
                if (src.optString("type") == "raster-dem" && dem == null) dem = k
            }
        }
        s.put("glyphs", "${HOST}a/map/glyphs/{fontstack}/{range}.pbf")
        s.put("sprite", "${HOST}a/map/sprite/osm-liberty")
        dem?.let { d ->
            // отдельный источник для рельефа (рекомендация MapLibre: не делить его с тенями)
            sources.put("terrain-dem", JSONObject(sources.getJSONObject(d).toString()))
            s.put("terrain", JSONObject().put("source", "terrain-dem").put("exaggeration", 1.4))
        }
        return Bundle(s.toString(), files, dem != null)
    }

    private fun meta(path: String): Pair<Int, Int>? = runCatching {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            var mn = 0; var mx = 14
            db.rawQuery("SELECT name, value FROM metadata WHERE name IN ('minzoom','maxzoom')", null).use { c ->
                while (c.moveToNext()) { if (c.getString(0) == "minzoom") mn = c.getString(1).toInt() else mx = c.getString(1).toInt() }
            }
            mn to mx
        }
    }.getOrNull()

    private val dbs = HashMap<String, SQLiteDatabase>()
    private fun tile(path: String, z: Int, x: Int, y: Int): ByteArray? = synchronized(dbs) {
        val db = dbs.getOrPut(path) { SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) }
        db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }
    fun close() = synchronized(dbs) { dbs.values.forEach { runCatching { it.close() } }; dbs.clear() }

    fun html(lat: Double, lon: Double, zoom: Double, bearing: Double) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="${HOST}a/map3d/maplibre-gl.css"><script src="${HOST}a/map3d/maplibre-gl.js"></script>
<style>html,body,#m{margin:0;height:100%;background:#e6e4de}</style></head><body><div id="m"></div><script>
var map = new maplibregl.Map({container:'m', style:'${HOST}style.json', center:[${"%.6f".format(Locale.US, lon)},${"%.6f".format(Locale.US, lat)}],
  zoom:${"%.2f".format(Locale.US, zoom)}, pitch:62, bearing:${"%.1f".format(Locale.US, bearing)}, maxPitch:85, attributionControl:false, fadeDuration:0});
map.on('error', function(e){ console.log('map error', e && e.error && e.error.message); });
function ex(v){ if (map.getTerrain()) map.setTerrain({source:'terrain-dem', exaggeration:v}); }
function pitch(d){ map.easeTo({pitch: Math.max(0, Math.min(85, map.getPitch()+d))}); }
function north(){ map.easeTo({bearing:0}); }
</script></body></html>"""

    fun intercept(ctx: android.content.Context, b: Bundle, html: String, url: String): WebResourceResponse? {
        if (!url.startsWith(HOST)) return null
        val path = url.removePrefix(HOST).substringBefore('?')
        fun ok(mime: String, data: ByteArray) = WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(data))
        fun none() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        return runCatching {
            when {
                path == "index.html" -> ok("text/html", html.toByteArray())
                path == "style.json" -> ok("application/json", b.style.toByteArray())
                path.startsWith("a/") -> {
                    val a = java.net.URLDecoder.decode(path.removePrefix("a/"), "UTF-8")
                    val mime = when { a.endsWith(".js") -> "application/javascript"; a.endsWith(".css") -> "text/css"; a.endsWith(".json") -> "application/json"; a.endsWith(".png") -> "image/png"; else -> "application/x-protobuf" }
                    ok(mime, ctx.assets.open(a).use { it.readBytes() })
                }
                path.startsWith("t/") -> {
                    val p = path.split('/')
                    val file = b.files[p[1]] ?: return none()
                    val d = tile(file, p[2].toInt(), p[3].toInt(), p[4].toInt()) ?: return none()
                    val raw = if (d.size > 2 && d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(d)).use { it.readBytes() } else d
                    val mime = when {
                        raw.size > 12 && String(raw, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
                        raw.size > 4 && raw[0] == 0x89.toByte() && raw[1] == 'P'.code.toByte() -> "image/png"
                        raw.size > 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() -> "image/jpeg"
                        else -> "application/x-protobuf"
                    }
                    ok(mime, raw)
                }
                else -> none()
            }
        }.getOrElse { none() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun Terrain3DScreen(lat: Double, lon: Double, zoom: Double, bearing: Double, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val b = remember { runCatching { Terrain3D.bundle(ctx) }.getOrNull() }
    var exag by remember { mutableFloatStateOf(1.4f) }
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { web?.destroy(); Terrain3D.close() } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Pal.Bg)) {
            if (b == null || !b.hasDem) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("3D-рельеф работает для скачанных стран с частью «Рельеф».", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Настройки карты → Загрузка карт → страна → Рельеф.", style = MaterialTheme.typography.bodyMedium, color = Pal.Muted)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onDismiss) { Text("Закрыть") }
                }
            } else {
                val html = remember { Terrain3D.html(lat, lon, zoom.coerceAtLeast(9.0), bearing) }
                AndroidView({ c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                Terrain3D.intercept(c, b, html, request.url.toString())
                        }
                        loadUrl("https://app.local/index.html")
                        web = this
                    }
                }, Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.TopStart).dialogBars().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MapBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") { onDismiss() }
                }
                Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)) {
                    IconButton({ web?.evaluateJavascript("pitch(10)", null) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Наклон больше") }
                    IconButton({ web?.evaluateJavascript("pitch(-10)", null) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Наклон меньше") }
                    IconButton({ web?.evaluateJavascript("north()", null) }) { Icon(Icons.Rounded.Explore, "На север") }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().dialogBars().padding(12.dp).shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Pal.Card).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Высота рельефа ×%.1f · два пальца вверх/вниз — наклон, поворот — вращение".format(exag), style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    Slider(exag, { exag = it; web?.evaluateJavascript("ex(${"%.2f".format(Locale.US, it)})", null) }, valueRange = 1f..3f)
                }
            }
        }
    }
}
