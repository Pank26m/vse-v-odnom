package com.pank.vsevodnom.ui.map

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.GlMap
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import globus.glmap.GLMapInfo
import globus.glmap.GLMapManager

/** На карте GLMap: эта страна не скачана — маленькая плашка «Скачать … · размер» (карта + рельеф для 3D без сети). */
@Composable
internal fun GlDownloadHint(live: MapLive) {
    val ctx = LocalContext.current
    val c = live.center ?: return
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    val m = remember(c.lat.toInt(), c.lon.toInt(), v) { GlMap.mapAt(c.lat, c.lon) } ?: return
    val prefs = remember { MapPrefs.p(ctx) }
    var off by remember(m.mapID) { mutableStateOf(prefs.getBoolean("gl_hint_off_${m.mapID}", false)) }
    val prog = GlMap.progress(m)
    val need = GlMap.pending(m)
    if (off || (need == 0 && prog == null)) return
    Row(Modifier.shadow(4.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Pal.Card)
        .clickable { if (prog == null) { GlMap.download(m); Toast.makeText(ctx, "Скачиваю «${GlMap.name(m)}»: карта и рельеф", Toast.LENGTH_SHORT).show() } }
        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (prog == null) Icons.Rounded.Download else Icons.Rounded.Downloading, null, tint = Pal.SkyInk, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (prog != null) "${GlMap.name(m)} · ${(prog * 100).toInt()}%" else "${GlMap.name(m)} · ${MapKit.human(GlMap.sizeOnServer(m, need))}",
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (prog == null) IconButton({ off = true; prefs.edit().putBoolean("gl_hint_off_${m.mapID}", true).apply() }, Modifier.size(30.dp)) {
            Icon(Icons.Rounded.Close, "Не предлагать", tint = Pal.Muted, modifier = Modifier.size(16.dp))
        } else Spacer(Modifier.width(8.dp))
    }
}

/** Настройки → «Движок карты и 3D»: ключ GLMap, выбор движка, карты GLMap (скачать, обновить, удалить). */
@Composable
internal fun GlEnginePage(onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(GlMap.key(ctx)) }
    var engine by remember { mutableStateOf(GlMap.engine(ctx)) }
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    FullScreen("Движок карты и 3D", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GLMap — движок Guru Maps: быстрая векторная карта и 3D-рельеф всегда (наклони карту двумя пальцами вверх — горы поднимаются от уровня моря). " +
                "MapLibre — прежняя карта со своими офлайн-картами, запасной вариант.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            AppCard(Modifier.fillMaxWidth()) {
                Text("Движок", style = MaterialTheme.typography.titleMedium)
                listOf("glmap" to "GLMap · 3D-рельеф (основной)", "maplibre" to "MapLibre · прежняя карта").forEach { (k, t) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        engine = k; GlMap.setEngine(ctx, k); MapUi.engineTick++
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(engine == k, { engine = k; GlMap.setEngine(ctx, k); MapUi.engineTick++ })
                        Text(t, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val status = when {
                    engine != "glmap" -> "Сейчас рисует MapLibre."
                    key.isBlank() -> "Нет ключа GLMap — карта рисуется MapLibre."
                    GlMap.ready -> "GLMap работает." + (GlMap.server?.let { " Сервер: $it." } ?: "")
                    else -> GlMap.error ?: "GLMap запустится при открытии карты."
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (engine == "glmap" && !GlMap.ready && key.isNotBlank() && GlMap.error != null) Pal.RoseInk else Pal.Muted)
            }
            AppCard(Modifier.fillMaxWidth()) {
                Text("Ключ GLMap", style = MaterialTheme.typography.titleMedium)
                AppTextField(key, { key = it.trim() }, "Ключ API с globus.software")
                PillButton("Сохранить ключ", {
                    GlMap.setKey(ctx, key); MapUi.engineTick++; onChanged()
                    Toast.makeText(ctx, if (GlMap.ready) "Сохранено. Новый ключ заработает после перезапуска приложения" else "Сохранено", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
                Text("Ключ хранится только на телефоне. Бесплатно: 5000 скачиваний карт и 50 000 онлайн-тайлов в месяц.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            if (GlMap.ready) GlMapsList()
            Text("Карта GLMap © участники OpenStreetMap, движок © GLOBUS (globus.software).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}

@Composable
private fun GlMapsList() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    var q by remember { mutableStateOf("") }
    val all = remember(v) {
        fun walk(a: Array<GLMapInfo>?): List<GLMapInfo> = a.orEmpty().flatMap { m -> if (m.isCollection) walk(m.maps) else listOf(m) }
        runCatching { walk(GLMapManager.GetMaps()) }.getOrDefault(emptyList())
    }
    val have = all.filter { GlMap.sizeOnDisk(it) > 0 || GlMap.progress(it) != null }
    SectionLabel("Скачанные карты GLMap")
    if (have.isEmpty()) Text("Пока ничего. Карта мира работает онлайн; для 3D без интернета скачай страну (карта + рельеф).", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    have.forEach { m -> GlMapRow(m) }
    SectionLabel("Скачать страну или область")
    AppTextField(q, { q = it }, "Название (Украина, Польша, Бавария…)")
    val list = if (q.length < 2) all.filter { it.isoCode.equals("UA", true) } else all.filter { GlMap.name(it).contains(q, true) || it.isoCode?.equals(q, true) == true || GlMap.nameEn(it).contains(q, true) }.take(40)
    list.filter { it !in have }.forEach { m -> GlMapRow(m) }
    LaunchedEffect(Unit) { if (all.isEmpty()) GlMap.refreshList() }
    if (all.isEmpty()) {
        Text(when {
            GlMap.listLoading -> "Загружаю список карт…"
            GlMap.listError != null -> "Список карт не загрузился: ${GlMap.listError}"
            else -> "Список карт пуст — нажми «Обновить список»."
        }, style = MaterialTheme.typography.bodySmall, color = if (GlMap.listError != null) Pal.RoseInk else Pal.Muted)
        if (!GlMap.listLoading) PillButton("Обновить список", { GlMap.refreshList() }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
    } else if (list.isEmpty() && q.length >= 2) Text("Ничего не нашлось. Попробуй по-английски (Ukraine) или часть названия.", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
    @Suppress("UNUSED_VARIABLE") val unused = ctx
}

@Composable
private fun GlMapRow(m: GLMapInfo) {
    @Suppress("UNUSED_VARIABLE") val v = GlMap.version
    val prog = GlMap.progress(m)
    val need = GlMap.pending(m)
    val disk = GlMap.sizeOnDisk(m)
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(GlMap.name(m), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(when {
                    prog != null -> "Скачиваю… ${(prog * 100).toInt()}%"
                    disk > 0 && need != 0 -> "На телефоне ${MapKit.human(disk)} · есть обновление или рельеф (${MapKit.human(GlMap.sizeOnServer(m, need))})"
                    disk > 0 -> "На телефоне ${MapKit.human(disk)} · карта и рельеф"
                    else -> "Карта + рельеф · ${MapKit.human(GlMap.sizeOnServer(m))}"
                }, style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
            }
            when {
                prog != null -> TextButton({ GlMap.cancel(m) }) { Text("Отмена") }
                need != 0 -> TextButton({ GlMap.download(m) }) { Text(if (disk > 0) "Обновить" else "Скачать") }
            }
            if (disk > 0 && prog == null) IconButton({ GlMap.delete(m) }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = Pal.RoseInk) }
        }
        if (prog != null) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

