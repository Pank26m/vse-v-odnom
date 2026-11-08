package com.pank.vsevodnom.ui.map

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * План маршрута (как в Guru): точки «Откуда» (null — моё место), остановки и «Куда»; способ — авто/вело/пешком/прямая.
 * Маршрут строится сам, когда есть начало и конец; ничего не рисуется заранее.
 */
object RoutePlan {
    var active by mutableStateOf(false)
    val points = mutableStateListOf<MapPrefs.Spot?>(null, null)      // [0] откуда (null — я), [last] куда, между — остановки
    var mode by mutableStateOf("car")
    var route by mutableStateOf<Routing.Route?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var rev by mutableIntStateOf(0)      // растёт при изменении точек — повод перестроить
    var editTrack by mutableStateOf<Track?>(null)   // правим сохранённый маршрут — «Сохранить» обновит его

    /** Открыть сохранённый маршрут/трек для правки: точки из meta или начало и конец трека. */
    fun load(t: Track) {
        val rw = routeWay(t); val pts = Osm.decode(t.points)
        points.clear()
        if (rw != null) { points.addAll(rw.second); mode = rw.first }
        else if (pts.size >= 2) { points.add(MapPrefs.Spot("Начало трека", pts.first().lat, pts.first().lon)); points.add(MapPrefs.Spot("Конец трека", pts.last().lat, pts.last().lon)); mode = "bike" }
        while (points.size < 2) points.add(null)
        editTrack = t; route = null; error = null; active = true; rev++
    }

    fun open(ctx: android.content.Context) { if (!active) { points.clear(); points.add(null); points.add(null); route = null; error = null; mode = MapPrefs.navMode(ctx) }; active = true }
    fun close() { active = false; editTrack = null; points.clear(); points.add(null); points.add(null); route = null; error = null; rev++ }
    fun setTo(s: MapPrefs.Spot) { points[points.size - 1] = s; rev++ }
    fun setFrom(s: MapPrefs.Spot?) { points[0] = s; rev++ }
    fun addStop(s: MapPrefs.Spot) { if (points.last() == null) points[points.size - 1] = s else points.add(points.size - 1, s); rev++ }
    fun remove(i: Int) { if (i == 0 || i == points.size - 1) points[i] = null else points.removeAt(i); rev++ }
    val dest get() = points.lastOrNull()

    /** Метки остановок: 1…99, дальше А, Б, …, Я, АА, АБ… */
    fun label(i: Int): String {
        if (i < 99) return (i + 1).toString()
        val abc = "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЭЮЯ"
        var n = i - 99; val sb = StringBuilder()
        do { sb.insert(0, abc[n % abc.length]); n = n / abc.length - 1 } while (n >= 0)
        return sb.toString()
    }

    fun sourceName(r: Routing.Route?) = when (r?.source) {
        "офлайн" -> "офлайн на телефоне (BRouter)"
        "OSRM" -> "OSRM онлайн (без пробок)"
        "line" -> "по прямой"
        else -> r?.source.orEmpty()
    }
}

@Composable
internal fun RoutePlannerTop(onClose: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Pal.Card).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Закрыть маршрут") }
        listOf(Triple("car", "Авто", Icons.Rounded.DirectionsCar), Triple("bike", "Вело", Icons.AutoMirrored.Rounded.DirectionsBike),
            Triple("foot", "Пешком", Icons.AutoMirrored.Rounded.DirectionsWalk), Triple("line", "Прямая", Icons.Rounded.Straighten)).forEach { (k, n, ic) ->
            val sel = RoutePlan.mode == k
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) Pal.Sky else Color.Transparent)
                .clickable { RoutePlan.mode = k; MapPrefs.setStr(ctx, "nav_mode", k); RoutePlan.rev++ }, contentAlignment = Alignment.Center) {
                Icon(ic, n, tint = if (sel) Pal.SkyInk else Pal.Ink)
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
            DropdownMenu(menu, { menu = false }) {
                if (RoutePlan.editTrack != null) DropdownMenuItem({ Text("Сохранить изменения в «${RoutePlan.editTrack!!.name.take(30)}»") }, { menu = false; saveRouteAsTrack(ctx, RoutePlan.editTrack) }, enabled = RoutePlan.route != null)
                DropdownMenuItem({ Text(if (RoutePlan.editTrack != null) "Сохранить как новый трек" else "Сохранить как трек") }, { menu = false; saveRouteAsTrack(ctx, null) }, enabled = RoutePlan.route != null)
                DropdownMenuItem({ Text("Настройки навигации") }, { menu = false; onSettings() })
            }
        }
    }
}

private fun saveRouteAsTrack(ctx: android.content.Context, into: Track?) {
    val r = RoutePlan.route ?: return
    val way = RoutePlan.points.mapIndexedNotNull { i, s -> s ?: if (i == 0) r.points.firstOrNull()?.let { MapPrefs.Spot("Моё место", it.lat, it.lon) } else null }
    val meta = org.json.JSONObject().put("mode", RoutePlan.mode).put("way", org.json.JSONArray(way.map { org.json.JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })).toString()
    val name = "Маршрут: " + RoutePlan.points.filterNotNull().joinToString(" → ") { it.name }.ifBlank { "без названия" }
    AppScope.io.launch {
        val dao = AppDb.get(ctx).map()
        val folder = dao.foldersNow().firstOrNull { it.name == "Маршруты" && it.parentId == null }?.id ?: dao.upsertFolder(MapFolder(name = "Маршруты"))
        val now = System.currentTimeMillis()
        if (into != null) dao.upsertTrack(into.copy(distanceM = r.meters, points = Osm.encode(r.points), meta = meta))
        else dao.upsertTrack(Track(name = name.take(120), startedAt = now, endedAt = now, distanceM = r.meters, points = Osm.encode(r.points), folderId = folder, meta = meta))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(ctx, if (into != null) "Трек обновлён" else "Сохранено в коллекции «Маршруты»", Toast.LENGTH_SHORT).show() }
    }
}

/** Нижняя панель плана: итог (км · время · прибытие · ▶) и список точек с остановками. */
@Composable
internal fun RoutePlannerBottom(me: GeoPt?, places: List<Place>, onGo: () -> Unit, onPickMap: (Int) -> Unit, onSearch: (Int) -> Unit) {
    val ctx = LocalContext.current
    val r = RoutePlan.route
    var expanded by remember { mutableStateOf(true) }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp).shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Pal.Card)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).width(40.dp).height(4.dp).clip(CircleShape).background(Pal.Line).clickable { expanded = !expanded })
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                RoutePlan.busy -> { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("  Строю маршрут…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)) }
                r != null -> {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(MapPrefs.dist(ctx, r.meters), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (r.source != "line") {
                                Text("   " + fmtDur((r.seconds * 1000).toLong()), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F7D57))
                                Text("   " + LocalTime.now().plusSeconds(r.seconds.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 18.sp, color = Pal.Muted)
                            }
                        }
                        Text(RoutePlan.sourceName(r) + if (r.delay >= 60) " · пробки +" + fmtDur((r.delay * 1000).toLong()) else "", style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
                    }
                    val canGo = RoutePlan.mode == "car" || (RoutePlan.points.first() == null && r.source != "line" && r.steps.isNotEmpty())
                    Box(Modifier.size(52.dp).clip(CircleShape).background(if (canGo) Color(0xFF3E6FA8) else Pal.Line).clickable {
                        if (RoutePlan.mode == "car") RoutePlan.dest?.let { d -> Toast.makeText(ctx, "На машине ведёт Waze", Toast.LENGTH_SHORT).show(); waze(ctx, d.lat, d.lon) }
                        else if (canGo) onGo() else Toast.makeText(ctx, if (r.source == "line") "Навигация — по дорогам: выбери авто, вело или пешком" else "Навигация ведёт от моего места — убери «Откуда»", Toast.LENGTH_LONG).show()
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Поехали", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
                RoutePlan.error != null -> Text(RoutePlan.error!!, style = MaterialTheme.typography.bodyMedium, color = Pal.RoseInk, modifier = Modifier.weight(1f))
                else -> Text("Маршрут", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.42f).dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            RoutePlan.points.forEachIndexed { i, sp ->
                val last = i == RoutePlan.points.size - 1
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable { onSearch(i) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    when {
                        i == 0 -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF3E6FA8)))
                        last -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE5484D)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SportsScore, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        else -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE09A1B)), contentAlignment = Alignment.Center) {
                            Text(RoutePlan.label(i - 1), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(when { i == 0 -> "Откуда"; last -> "Куда"; else -> "Остановка ${RoutePlan.label(i - 1)}" }, fontSize = 11.sp, color = Pal.Muted)
                        Text(sp?.name ?: if (i == 0) "Моё положение" else "Найти место…", style = MaterialTheme.typography.bodyLarge, color = if (sp == null && i != 0) Pal.Muted else Pal.Ink, maxLines = 1)
                    }
                    IconButton({ onPickMap(i) }) { Icon(Icons.Rounded.AddLocationAlt, "Выбрать на карте", tint = Pal.SkyInk) }
                    if (sp != null) IconButton({ RoutePlan.remove(i) }) { Icon(Icons.Rounded.Cancel, "Убрать", tint = Pal.Muted, modifier = Modifier.size(20.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Bg).clickable { onSearch(-1) }.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = Pal.SkyInk, modifier = Modifier.size(22.dp))
                Text("Добавить остановку", style = MaterialTheme.typography.bodyLarge, color = Pal.SkyInk, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                IconButton({ onPickMap(-1) }) { Icon(Icons.Rounded.AddLocationAlt, "Остановка на карте", tint = Pal.SkyInk) }
            }
            if (RoutePlan.dest == null) RouteQuickPicks(me, places)
        }
    }
}

/** Быстрый выбор «Куда»: избранное (дом, работа, свои), недавние, мои метки. */
@Composable
private fun RouteQuickPicks(me: GeoPt?, places: List<Place>) {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf(MapPrefs.favs(ctx)) }
    var home by remember { mutableStateOf(MapPrefs.home(ctx)) }
    var work by remember { mutableStateOf(MapPrefs.work(ctx)) }
    var filter by remember { mutableStateOf("") }
    var pickFav by remember { mutableStateOf(false) }
    var moveFav by remember { mutableStateOf<((MapPrefs.Spot) -> Unit)?>(null) }
    var renameFav by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFav by remember { mutableStateOf<MapPrefs.Spot?>(null) }
    SectionLabel("Избранное")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun Tile(ic: androidx.compose.ui.graphics.vector.ImageVector, n: String, s: MapPrefs.Spot?, onRename: ((String) -> Unit)?, onMove: (MapPrefs.Spot) -> Unit, onDelete: () -> Unit) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.width(110.dp).height(80.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Bg).clickable {
                if (s != null) RoutePlan.setTo(s.copy(name = n)) else { moveFav = { sp -> onMove(sp) } }
            }) {
                Column(Modifier.padding(10.dp)) {
                    Icon(ic, null, tint = Pal.SkyInk); Text(n, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(s?.sub?.ifBlank { null } ?: if (s != null) "задано" else "нажми — задать", style = MaterialTheme.typography.bodySmall, color = Pal.Muted, maxLines = 1)
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.MoreVert, "Ещё", tint = Pal.Muted, modifier = Modifier.padding(4.dp).size(18.dp).clickable { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        if (onRename != null) DropdownMenuItem({ Text("Переименовать") }, { menu = false; renameFav = onRename; renameText = n })
                        DropdownMenuItem({ Text("Изменить адрес") }, { menu = false; moveFav = onMove })
                        DropdownMenuItem({ Text("Удалить", color = Pal.RoseInk) }, { menu = false; onDelete() })
                    }
                }
            }
        }
        Tile(Icons.Rounded.Home, "Дом", home, null, { sp -> val h = sp.copy(name = "Дом"); MapPrefs.setHome(ctx, h); home = h }) { MapPrefs.setHome(ctx, null); home = null }
        Tile(Icons.Rounded.Work, "Работа", work, null, { sp -> val w = sp.copy(name = "Работа"); MapPrefs.setWork(ctx, w); work = w }) { MapPrefs.setWork(ctx, null); work = null }
        favs.forEachIndexed { idx, f ->
            Tile(Icons.Rounded.Star, f.name, f, { nn -> favs = favs.toMutableList().also { it[idx] = f.copy(name = nn) }; MapPrefs.setFavs(ctx, favs) },
                { sp -> favs = favs.toMutableList().also { it[idx] = sp.copy(name = f.name) }; MapPrefs.setFavs(ctx, favs) }) { favs = favs - f; MapPrefs.setFavs(ctx, favs) }
        }
        Column(Modifier.width(110.dp).height(80.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Bg).clickable { pickFav = true }.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Add, "Добавить", tint = Pal.SkyInk); Text("Добавить", style = MaterialTheme.typography.labelMedium, color = Pal.Muted)
        }
    }
    moveFav?.let { cb -> MapPointPicker(onDismiss = { moveFav = null }) { (n, la, lo) -> cb(MapPrefs.Spot(n, la, lo, n)); moveFav = null } }
    renameFav?.let { cb ->
        AlertDialog(onDismissRequest = { renameFav = null }, title = { Text("Название") }, text = { AppTextField(renameText, { renameText = it }, "Название") },
            confirmButton = { TextButton({ if (renameText.isNotBlank()) cb(renameText.trim()); renameFav = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ renameFav = null }) { Text("Отмена") } })
    }
    if (pickFav) MapPointPicker(onDismiss = { pickFav = false }) { (n, la, lo) -> pickFav = false; newFav = MapPrefs.Spot(n, la, lo, n) }
    newFav?.let { sp ->
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFav = null }, title = { Text("Новое избранное") },
            text = { Column { Text(sp.sub, style = MaterialTheme.typography.bodySmall); AppTextField(name, { name = it }, "Название (Учёба, Спорт…)") } },
            confirmButton = { TextButton({ if (name.isNotBlank()) { favs = favs + sp.copy(name = name.trim()); MapPrefs.setFavs(ctx, favs) }; newFav = null }) { Text("Добавить") } },
            dismissButton = { TextButton({ newFav = null }) { Text("Отмена") } })
    }
    val rec = remember { MapPrefs.recents(ctx) }
    if (rec.isNotEmpty()) { SectionLabel("Недавние"); rec.take(5).forEach { r -> MenuRow(Icons.Rounded.History, r.name, sub = r.sub.ifBlank { null }) { RoutePlan.setTo(r) } } }
    if (places.isNotEmpty()) {
        SectionLabel("Мои метки")
        AppTextField(filter, { filter = it }, "Найти метку")
        places.filter { filter.isBlank() || it.name.contains(filter, true) }.sortedBy { p -> me?.let { Osm.distance(it, GeoPt(p.lat, p.lon, 0)) } ?: 0.0 }.take(10).forEach { pl ->
            MenuRow(Icons.Rounded.Place, pl.name, sub = me?.let { MapPrefs.dist(ctx, Osm.distance(it, GeoPt(pl.lat, pl.lon, 0))) }) { RoutePlan.setTo(MapPrefs.Spot(pl.name, pl.lat, pl.lon, "Моя метка")) }
        }
    }
}

/** Точки «линейки» для прямой: 0, середина, конец и итог у каждой остановки. */
internal fun rulerMarks(ctx: android.content.Context, pts: List<GeoPt>): List<Pair<GeoPt, String>> {
    if (pts.size < 2) return emptyList()
    val cum = DoubleArray(pts.size); for (i in 1 until pts.size) cum[i] = cum[i - 1] + Osm.distance(pts[i - 1], pts[i])
    val total = cum.last()
    fun at(d: Double): GeoPt {
        val i = (1 until pts.size).firstOrNull { cum[it] >= d } ?: (pts.size - 1)
        val f = if (cum[i] > cum[i - 1]) (d - cum[i - 1]) / (cum[i] - cum[i - 1]) else 0.0
        return GeoPt(pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * f, pts[i - 1].lon + (pts[i].lon - pts[i - 1].lon) * f, 0)
    }
    val out = arrayListOf(pts.first() to "0 км", at(total / 2) to MapPrefs.dist(ctx, total / 2), pts.last() to MapPrefs.dist(ctx, total))
    for (i in 1 until pts.size - 1) out += pts[i] to MapPrefs.dist(ctx, cum[i])
    return out
}
