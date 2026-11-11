package com.pank.vsevodnom.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Импорт и экспорт меток и треков: GPX и KML/KMZ (так отдают Guru Maps, Organic Maps, Google Мои карты).
 * Папки KML (<Folder>) становятся папками коллекции, вложенность сохраняется.
 */
object GeoFiles {
    class Node(var name: String) {
        val folders = ArrayList<Node>()
        val places = ArrayList<Place>()
        val tracks = ArrayList<Pair<String, List<GeoPt>>>()
        fun count(): Int = places.size + tracks.size + folders.sumOf { it.count() }
    }

    fun read(ctx: Context, uri: Uri, fileName: String): Node {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val root = Node(fileName.substringBeforeLast('.').ifBlank { "Импорт" })
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val xml: ByteArray = if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry; var found: ByteArray? = null
                while (e != null) { if (e.name.endsWith(".kml", true)) { found = z.readBytes(); break }; e = z.nextEntry }
                found ?: error("В архиве нет KML")
            }
        } else bytes
        val head = String(xml, 0, minOf(xml.size, 2000)).lowercase()
        if (head.contains("<gpx")) gpx(ByteArrayInputStream(xml), root) else kml(ByteArrayInputStream(xml), root)
        return root
    }

    private fun time(s: String?): Long = s?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() } ?: 0L

    private fun gpx(input: InputStream, root: Node) {
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); p.setInput(input, null)
        var lat = 0.0; var lon = 0.0; var name = ""; var desc = ""; var t: String? = null; var ele = Double.NaN
        var inWpt = false; var inTrk = false; var trkName = ""; val pts = ArrayList<GeoPt>(); val segs = ArrayList<List<GeoPt>>()
        val text = StringBuilder()
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (p.name) {
                        "wpt" -> { inWpt = true; name = ""; desc = ""; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble() }
                        "trk", "rte" -> { inTrk = true; trkName = ""; pts.clear(); segs.clear() }
                        "trkseg" -> { if (pts.size >= 2) segs += pts.toList(); pts.clear() }
                        "trkpt", "rtept" -> { lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble(); t = null; ele = Double.NaN }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val s = text.toString().trim()
                    when (p.name) {
                        "name" -> if (inWpt) name = s else if (inTrk && trkName.isEmpty()) trkName = s
                        "desc", "cmt" -> if (inWpt && desc.isEmpty()) desc = s
                        "time" -> t = s
                        "ele" -> ele = s.toDoubleOrNull() ?: Double.NaN
                        "wpt" -> { if (valid(GeoPt(lat, lon, 0))) root.places += Place(name = name.ifBlank { "Метка" }, lat = lat, lon = lon, note = desc); inWpt = false }
                        "trkpt", "rtept" -> pts += GeoPt(lat, lon, time(t), ele)
                        "trkseg" -> { if (pts.size >= 2) segs += pts.toList(); pts.clear() }
                        "trk", "rte" -> {
                            if (pts.size >= 2) segs += pts.toList()
                            val line = joinParts(segs)
                            if (line.size >= 2) root.tracks += trkName.ifBlank { "Трек" } to line
                            pts.clear(); segs.clear(); inTrk = false
                        }
                    }
                    text.setLength(0)
                }
            }
            p.next()
        }
    }

    private fun coords(s: String): List<GeoPt> = s.trim().split(Regex("\\s+")).mapNotNull { c ->
        val a = c.split(','); if (a.size < 2) null else runCatching { GeoPt(a[1].toDouble(), a[0].toDouble(), 0, a.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN) }.getOrNull()
    }

    /**
     * KML (Guru, Google): у метки может быть несколько геометрий сразу — LineString и gx:Track с одним и тем же треком,
     * MultiGeometry с точками старта/финиша. Раньше всё склеивалось в одну линию: прямая от финиша назад к старту,
     * лишние точки, сбитый порядок километров. Теперь каждая геометрия отдельно: берём gx:Track (в нём время),
     * иначе LineString-и; одинаковые копии отбрасываем; точки внутри метки с линией — не часть линии.
     */
    private fun kml(input: InputStream, root: Node) {
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); p.setInput(input, null)
        val stack = ArrayList<Node>().apply { add(root) }
        var inPm = false; var pmName = ""; var pmDesc = ""; var point: GeoPt? = null
        val lines = ArrayList<List<GeoPt>>(); val gxLines = ArrayList<List<GeoPt>>()
        val gx = ArrayList<GeoPt>(); val gxWhen = ArrayList<Long>()
        var geom: String? = null
        var pendingFolder = false
        val text = StringBuilder()
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val tag = p.name?.substringAfter(':')
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (tag) {
                        "Folder" -> { val n = Node("Папка"); stack.last().folders += n; stack += n; pendingFolder = true }
                        "Placemark" -> { inPm = true; pmName = ""; pmDesc = ""; point = null; lines.clear(); gxLines.clear() }
                        "Point", "LineString", "LinearRing" -> geom = tag
                        "Track" -> { geom = "Track"; gx.clear(); gxWhen.clear() }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val s = text.toString().trim()
                    when (tag) {
                        "name" -> if (inPm) { if (pmName.isEmpty()) pmName = s } else if (pendingFolder && stack.size > 1) { stack.last().name = s.ifBlank { "Папка" }; pendingFolder = false }
                        "description" -> if (inPm && pmDesc.isEmpty()) pmDesc = s.replace(Regex("<[^>]+>"), " ").trim()
                        "coordinates" -> if (inPm) {
                            val c = coords(s)
                            if (geom == "Point" || (geom == null && c.size == 1)) { if (point == null) point = c.firstOrNull() }
                            else if (c.size >= 2) lines += c
                        }
                        "when" -> if (inPm) gxWhen += time(s)
                        "coord" -> if (inPm) s.split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { a -> runCatching { gx += GeoPt(a[1].toDouble(), a[0].toDouble(), gxWhen.getOrElse(gx.size) { 0 }, a.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN) } }
                        "Track" -> { if (gx.size >= 2) gxLines += gx.toList(); gx.clear(); geom = null }
                        "Point", "LineString", "LinearRing" -> geom = null
                        "Placemark" -> {
                            val node = stack.last()
                            val line = joinParts(if (gxLines.isNotEmpty()) gxLines else lines)
                            if (line.size >= 2) node.tracks += pmName.ifBlank { "Трек" } to line
                            else point?.let { if (valid(it)) node.places += Place(name = pmName.ifBlank { "Метка" }, lat = it.lat, lon = it.lon, note = pmDesc) }
                            inPm = false
                        }
                        "Folder" -> { if (stack.size > 1) stack.removeAt(stack.size - 1); pendingFolder = false }
                    }
                    text.setLength(0)
                }
            }
            p.next()
        }
    }

    private fun valid(g: GeoPt) = g.lat.isFinite() && g.lon.isFinite() && g.lat in -89.9..89.9 && g.lon in -180.0..180.0 && !(abs(g.lat) < 1e-6 && abs(g.lon) < 1e-6)

    /** Похожи ли две части (одна и та же линия, записанная дважды): одинаковые концы в пределах 50 м. */
    private fun same(a: List<GeoPt>, b: List<GeoPt>): Boolean =
        Osm.distance(a.first(), b.first()) < 50 && Osm.distance(a.last(), b.last()) < 50

    /**
     * Части одной метки → одна линия: дубли выкидываются, части идут друг за другом (следующая — та, чей конец ближе),
     * затем чистка: неверные координаты, одиночные «выбросы» далеко в сторону (точка у Норвегии на треке по Азорам).
     */
    internal fun joinParts(parts: List<List<GeoPt>>): List<GeoPt> {
        val ps = ArrayList<List<GeoPt>>()
        for (raw in parts) { val c = raw.filter(::valid); if (c.size >= 2 && ps.none { same(it, c) }) ps += c }
        if (ps.isEmpty()) return emptyList()
        val out = ArrayList<GeoPt>(ps.removeAt(0))
        while (ps.isNotEmpty()) {
            val end = out.last()
            var best = 0; var rev = false; var bd = Double.MAX_VALUE
            ps.forEachIndexed { i, c ->
                val d1 = Osm.distance(end, c.first()); val d2 = Osm.distance(end, c.last())
                if (d1 < bd) { bd = d1; best = i; rev = false }
                if (d2 < bd) { bd = d2; best = i; rev = true }
            }
            val c = ps.removeAt(best)
            out += if (rev) c.asReversed() else c
        }
        return clean(out)
    }

    /** Убрать выбросы: точка, от которой до обоих соседей далеко (> 5 км), а соседи рядом друг с другом. Плюс подряд одинаковые. */
    internal fun clean(src: List<GeoPt>): List<GeoPt> {
        val pts = src.filter(::valid)
        if (pts.size < 3) return pts
        val out = ArrayList<GeoPt>(pts.size)
        for (i in pts.indices) {
            val g = pts[i]
            if (i in 1 until pts.size - 1) {
                val a = pts[i - 1]; val b = pts[i + 1]
                val da = Osm.distance(a, g); val db = Osm.distance(g, b); val ab = Osm.distance(a, b)
                if (da > 5000 && db > 5000 && ab < minOf(da, db) / 4) continue
            } else if (i == 0 || i == pts.size - 1) {
                // крайняя точка далеко от единственного соседа и от всего трека — тоже выброс
                val n = if (i == 0) pts[1] else pts[pts.size - 2]
                val n2 = if (i == 0) pts[2] else pts[pts.size - 3]
                val d = Osm.distance(g, n)
                if (d > 50_000 && Osm.distance(n, n2) < d / 20) continue
            }
            if (out.isNotEmpty() && out.last().lat == g.lat && out.last().lon == g.lon) continue
            out += g
        }
        return out
    }

    /** Записать дерево в базу: папка-файл в корне (или в выбранной папке). Возвращает число объектов. */
    suspend fun save(ctx: Context, root: Node, parent: Long?): Int {
        val dao = AppDb.get(ctx).map()
        suspend fun put(n: Node, parentId: Long?) {
            val id = dao.upsertFolder(MapFolder(parentId = parentId, name = n.name))
            n.places.forEach { dao.upsertPlace(it.copy(folderId = id)) }
            n.tracks.forEach { (name, pts) ->
                var d = 0.0; for (i in 1 until pts.size) d += Osm.distance(pts[i - 1], pts[i])
                val st = pts.first().t.takeIf { it > 0 } ?: System.currentTimeMillis()
                dao.upsertTrack(Track(name = name, startedAt = st, endedAt = pts.last().t.takeIf { it > 0 } ?: st, distanceM = d, points = Osm.encode(pts), folderId = id))
            }
            n.folders.forEach { put(it, id) }
        }
        put(root, parent)
        return root.count()
    }

    // ---------- экспорт ----------
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun f6(d: Double) = String.format(Locale.US, "%.6f", d)

    fun kmlOf(name: String, folders: List<MapFolder>, places: List<Place>, tracks: List<Track>, rootId: Long?): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${esc(name)}</name>\n")
        fun body(fid: Long?) {
            places.filter { it.folderId == fid }.forEach { p ->
                sb.append("<Placemark><name>${esc(p.name)}</name>")
                if (p.note.isNotBlank()) sb.append("<description>${esc(p.note)}</description>")
                sb.append("<Point><coordinates>${f6(p.lon)},${f6(p.lat)}</coordinates></Point></Placemark>\n")
            }
            tracks.filter { it.folderId == fid }.forEach { t ->
                sb.append("<Placemark><name>${esc(t.name)}</name><LineString><coordinates>")
                Osm.decode(t.points).forEach { sb.append("${f6(it.lon)},${f6(it.lat)} ") }
                sb.append("</coordinates></LineString></Placemark>\n")
            }
            folders.filter { it.parentId == fid }.forEach { f -> sb.append("<Folder><name>${esc(f.name)}</name>\n"); body(f.id); sb.append("</Folder>\n") }
        }
        body(rootId)
        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    fun gpxOf(places: List<Place>, tracks: List<Track>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Vse v odnom\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        places.forEach { p -> sb.append("<wpt lat=\"${f6(p.lat)}\" lon=\"${f6(p.lon)}\"><name>${esc(p.name)}</name>${if (p.note.isNotBlank()) "<desc>${esc(p.note)}</desc>" else ""}</wpt>\n") }
        tracks.forEach { t ->
            sb.append("<trk><name>${esc(t.name)}</name><trkseg>\n")
            Osm.decode(t.points).forEach { pt -> sb.append("<trkpt lat=\"${f6(pt.lat)}\" lon=\"${f6(pt.lon)}\">${if (!pt.alt.isNaN()) "<ele>%.1f</ele>".format(java.util.Locale.US, pt.alt) else ""}${if (pt.t > 0) "<time>${Instant.ofEpochMilli(pt.t)}</time>" else ""}</trkpt>\n") }
            sb.append("</trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
