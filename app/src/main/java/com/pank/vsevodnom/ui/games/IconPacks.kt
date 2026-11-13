package com.pank.vsevodnom.ui.games

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Наборы иконок (0.45): любые установленные паки в формате ADW/Nova (Cleandroid UI и т. п.) —
 * список значков из res/xml/drawable.xml (или assets/drawable.xml), картинка — ресурс drawable пака.
 * Выбранный значок сохраняется как обложка игры (PNG в files/game_covers).
 */
object IconPacks {
    data class Pack(val pkg: String, val label: String)

    fun installed(ctx: Context): List<Pack> {
        val pm = ctx.packageManager
        val actions = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme", "com.teslacoilsw.launcher.THEME")
        return actions.flatMap { a -> runCatching { pm.queryIntentActivities(Intent(a), 0) }.getOrDefault(emptyList()) }
            .map { it.activityInfo.packageName }.distinct()
            .map { Pack(it, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Имена значков пака (без повторов, по порядку файла). */
    fun names(ctx: Context, pkg: String): List<String> {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        val out = LinkedHashSet<String>()
        fun read(p: XmlPullParser) {
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let { out += it }
                e = p.next()
            }
        }
        @Suppress("DiscouragedApi") val id = res.getIdentifier("drawable", "xml", pkg)
        if (id != 0) runCatching { res.getXml(id).use { read(it) } }
        if (out.isEmpty()) runCatching {
            res.assets.open("drawable.xml").use { s -> val p = android.util.Xml.newPullParser(); p.setInput(s, "UTF-8"); read(p) }
        }
        if (out.isEmpty()) runCatching {   // запасной путь — appfilter.xml (значки приложений)
            @Suppress("DiscouragedApi") val af = res.getIdentifier("appfilter", "xml", pkg)
            if (af != 0) res.getXml(af).use { p -> var e = p.eventType
                while (e != XmlPullParser.END_DOCUMENT) { if (e == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.let { out += it }; e = p.next() } }
        }
        return out.toList()
    }

    fun bitmap(ctx: Context, pkg: String, name: String, size: Int = 192): Bitmap? = runCatching {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        @Suppress("DiscouragedApi") val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: res.getIdentifier(name, "mipmap", pkg)
        if (id == 0) return null
        val d = res.getDrawable(id, null)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b)); b
    }.getOrNull()
}

/** Окно выбора: сначала набор иконок, потом значок (с поиском по имени). */
@Composable
fun IconPackPicker(onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var packs by remember { mutableStateOf<List<IconPacks.Pack>?>(null) }
    var pack by remember { mutableStateOf<IconPacks.Pack?>(null) }
    var names by remember { mutableStateOf<List<String>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { IconPacks.installed(ctx) } }
    LaunchedEffect(pack) { names = null; pack?.let { p -> names = withContext(Dispatchers.IO) { runCatching { IconPacks.names(ctx, p.pkg) }.getOrDefault(emptyList()) } } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).dialogBars()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", { if (pack != null) pack = null else onDismiss() })
                Text(pack?.label ?: "Набор иконок", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
            }
            val p = pack
            if (p == null) {
                val l = packs
                when {
                    l == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    l.isEmpty() -> Text("Наборов иконок не найдено. Установи, например, Cleandroid UI из Google Play — он появится здесь.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(l, key = { it.pkg }) { pk ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Card).clickable { pack = pk }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val icon = remember(pk.pkg) { runCatching { ctx.packageManager.getApplicationIcon(pk.pkg) }.getOrNull() }
                                icon?.let { d -> Image(remember(d) { android.graphics.Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(Canvas(b)) }.asImageBitmap() }, null, Modifier.size(40.dp)) }
                                Text(pk.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            } else {
                AppTextField(q, { q = it }, "Поиск значка (латиницей: game, dice, chess…)", Modifier.padding(horizontal = 16.dp))
                val all = names
                if (all == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else {
                    val shown = remember(all, q) { if (q.isBlank()) all else all.filter { it.contains(q.trim().replace(' ', '_'), true) } }
                    Text("${shown.size} значков", style = MaterialTheme.typography.labelMedium, color = Pal.Muted, modifier = Modifier.padding(16.dp, 6.dp))
                    LazyVerticalGrid(GridCells.Adaptive(64.dp), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it }) { n ->
                            val bmp by produceState<Bitmap?>(null, n) { value = withContext(Dispatchers.IO) { IconPacks.bitmap(ctx, p.pkg, n, 144) } }
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Pal.Card).clickable {
                                IconPacks.bitmap(ctx, p.pkg, n, 512)?.let(onPick)
                            }, contentAlignment = Alignment.Center) {
                                bmp?.let { Image(it.asImageBitmap(), n, Modifier.fillMaxSize().padding(6.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
