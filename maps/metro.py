"""Линии метро (route=subway/light_rail) с цветами и станции → <id>-metro.json (GeoJSON) для слоя «Метро».
python3 metro.py metro_src.osm.pbf out.json"""
import json, sys
import osmium
src, out = sys.argv[1], sys.argv[2]

class Rels(osmium.SimpleHandler):
    def __init__(self):
        super().__init__(); self.routes = []; self.ways = {}; self.stops = {}
    def relation(self, r):
        if r.tags.get("route") not in ("subway", "light_rail"): return
        t = {k: v for k, v in r.tags}
        col = t.get("colour") or t.get("color") or "#888888"
        ws = [m.ref for m in r.members if m.type == "w" and m.role in ("", "forward", "backward", "main")]
        for w in ws: self.ways.setdefault(w, (col, t.get("ref", ""), t.get("name", "")))
        for m in r.members:
            if m.type == "n" and m.role.startswith("stop"): self.stops.setdefault(m.ref, col)

class Geo(osmium.SimpleHandler):
    def __init__(self, ways, stops):
        super().__init__(); self.w = ways; self.s = stops; self.feats = []; self.seen = set()
    def way(self, w):
        if w.id not in self.w: return
        col, ref, name = self.w[w.id]
        try: pts = [[round(n.lon, 6), round(n.lat, 6)] for n in w.nodes]
        except osmium.InvalidLocationError: return
        if len(pts) >= 2: self.feats.append({"type": "Feature", "geometry": {"type": "LineString", "coordinates": pts}, "properties": {"kind": "line", "colour": col, "ref": ref}})
    def node(self, n):
        t = n.tags
        is_station = (t.get("railway") == "station" and (t.get("station") == "subway" or t.get("subway") == "yes")) or n.id in self.s
        if not is_station or not t.get("name"): return
        key = (t.get("name"), round(n.location.lat, 3), round(n.location.lon, 3))
        if key in self.seen: return
        self.seen.add(key)
        self.feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(n.location.lon, 6), round(n.location.lat, 6)]},
                           "properties": {"kind": "station", "name": t.get("name"), "colour": t.get("colour") or self.s.get(n.id, "#555555")}})

r = Rels(); r.apply_file(src)
g = Geo(r.ways, r.stops); g.apply_file(src, locations=True)
# у станции в OSM есть сама станция и точки остановки по направлениям — склеиваем одноимённые ближе 700 м в одну точку
import math
st = [f for f in g.feats if f["properties"]["kind"] == "station"]; rest = [f for f in g.feats if f["properties"]["kind"] != "station"]
used = [False] * len(st)
for i, f in enumerate(st):
    if used[i]: continue
    x0, y0 = f["geometry"]["coordinates"]; xs = []; ys = []
    for j in range(i, len(st)):
        if used[j] or st[j]["properties"]["name"] != f["properties"]["name"]: continue
        x, y = st[j]["geometry"]["coordinates"]
        if math.hypot((x - x0) * 111320 * math.cos(math.radians(y0)), (y - y0) * 110540) > 700: continue
        used[j] = True; xs.append(x); ys.append(y)
    f["geometry"]["coordinates"] = [round(sum(xs) / len(xs), 6), round(sum(ys) / len(ys), 6)]; rest.append(f)
g.feats = rest
json.dump({"type": "FeatureCollection", "features": g.feats}, open(out, "w"), ensure_ascii=False, separators=(",", ":"))
print("metro routes", len(r.ways), "features", len(g.feats))
