"""Заборы, стены, живые изгороди и пешие маршруты (route=hiking/foot) → <id>-lines.mbtiles.
В схеме OpenMapTiles их нет. Заборы — слой «barrier» (только z14, дальше растягиваются),
маршруты — слой «route» (z11…14, склеенные линии с названием и номером).

osmium tags-filter in.pbf w/barrier=fence,wall,city_wall,hedge,retaining_wall r/route=hiking,foot,nordic_walking -o lines.osm.pbf
python3 lines.py lines.osm.pbf out.mbtiles
"""
import gzip, json, math, sys
import osmium
import mapbox_vector_tile
from shapely.geometry import LineString, MultiLineString, box
from shapely.ops import linemerge
from mbtiles import MBTiles

SRC, OUT = sys.argv[1], sys.argv[2]
R = 6378137.0; HALF = math.pi * R
BARRIERS = {"fence", "wall", "city_wall", "hedge", "retaining_wall"}
ROUTES = {"hiking", "foot", "nordic_walking"}
ZB = 14; ZR = (11, 14)


def merc(lon, lat):
    lat = max(-85.0511, min(85.0511, lat))
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def tile_bounds(z, x, y):
    s = 2 * HALF / (1 << z)
    return -HALF + x * s, HALF - (y + 1) * s, -HALF + (x + 1) * s, HALF - y * s


def tiles_for(b, z):
    s = 2 * HALF / (1 << z); n = 1 << z
    x0 = max(0, int((b[0] + HALF) // s)); x1 = min(n - 1, int((b[2] + HALF) // s))
    y0 = max(0, int((HALF - b[3]) // s)); y1 = min(n - 1, int((HALF - b[1]) // s))
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


class Rels(osmium.SimpleHandler):
    def __init__(self):
        super().__init__(); self.routes = {}; self.way_route = {}
    def relation(self, r):
        if r.tags.get("route") not in ROUTES: return
        name = r.tags.get("name:ru") or r.tags.get("name") or r.tags.get("ref") or ""
        if not name: return
        self.routes[r.id] = {"name": name, "ref": r.tags.get("ref", ""), "network": r.tags.get("network", ""),
                             "colour": r.tags.get("colour") or r.tags.get("color") or ""}
        for m in r.members:
            if m.type == "w": self.way_route.setdefault(m.ref, []).append(r.id)


class Ways(osmium.SimpleHandler):
    def __init__(self, way_route):
        super().__init__(); self.wr = way_route; self.barriers = []; self.route_lines = {}
    def way(self, w):
        b = w.tags.get("barrier")
        rs = self.wr.get(w.id)
        if b not in BARRIERS and not rs: return
        try: pts = [merc(n.lon, n.lat) for n in w.nodes]
        except osmium.InvalidLocationError: return
        if len(pts) < 2: return
        if b in BARRIERS: self.barriers.append((b, pts))
        for r in rs or []: self.route_lines.setdefault(r, []).append(pts)


rels = Rels(); rels.apply_file(SRC)
ways = Ways(rels.way_route); ways.apply_file(SRC, locations=True)
print("barriers", len(ways.barriers), "routes", len(rels.routes), flush=True)

buckets = {}   # (z, x, y) -> {layer: [(geom, props)]}

# заборы: z14, почти всегда целиком в одном тайле — режем только то, что пересекает край
for kind, pts in ways.barriers:
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    ts = tiles_for((min(xs), min(ys), max(xs), max(ys)), ZB)
    g = LineString(pts)
    for (x, y) in ts:
        gg = g
        if len(ts) > 1:
            tb = tile_bounds(ZB, x, y); pad = (tb[2] - tb[0]) * 0.01
            gg = g.intersection(box(tb[0] - pad, tb[1] - pad, tb[2] + pad, tb[3] + pad))
            if gg.is_empty: continue
        buckets.setdefault((ZB, x, y), {}).setdefault("barrier", []).append((gg, {"kind": kind}))

# маршруты: склеиваем части, упрощаем по масштабу
for rid, parts in ways.route_lines.items():
    props = {k: v for k, v in rels.routes[rid].items() if v}
    merged = linemerge(MultiLineString(parts))
    for z in range(ZR[0], ZR[1] + 1):
        tol = 2 * HALF / (1 << z) / 1024
        g = merged.simplify(tol, preserve_topology=False)
        if g.is_empty: continue
        for (x, y) in tiles_for(g.bounds, z):
            tb = tile_bounds(z, x, y); pad = (tb[2] - tb[0]) * 0.02
            c = g.intersection(box(tb[0] - pad, tb[1] - pad, tb[2] + pad, tb[3] + pad))
            if c.is_empty: continue
            buckets.setdefault((z, x, y), {}).setdefault("route", []).append((c, props))

m = MBTiles(OUT, {"name": "Заборы и маршруты", "format": "pbf", "type": "overlay", "minzoom": ZR[0], "maxzoom": ZB,
                  "attribution": "© OpenStreetMap",
                  "json": json.dumps({"vector_layers": [{"id": "barrier", "fields": {"kind": "String"}},
                                                        {"id": "route", "fields": {"name": "String", "ref": "String", "network": "String", "colour": "String"}}]})})
for (z, x, y), lays in buckets.items():
    tb = tile_bounds(z, x, y)
    out = [{"name": n, "features": [{"geometry": g.wkt, "properties": p} for g, p in items]} for n, items in lays.items()]
    data = mapbox_vector_tile.encode(out, default_options={"quantize_bounds": tb, "extents": 4096, "y_coord_down": False})
    m.put(z, x, y, gzip.compress(data))
m.close()
print("lines tiles", len(buckets))
