"""Подъезды и входы (entrance=* с номером ref или названием) → <id>-entrances.mbtiles (векторные тайлы z15, дальше растягиваются).
osmium export entr.osm.pbf -f geojsonseq | python3 entrances.py out.mbtiles"""
import gzip, json, math, sys
import mapbox_vector_tile
from mbtiles import MBTiles
Z = 15; EXT = 4096
tiles = {}
n = 0
for line in sys.stdin:
    line = line.strip().lstrip("\x1e")
    if not line: continue
    try: f = json.loads(line)
    except ValueError: continue
    g = f.get("geometry") or {}
    if g.get("type") != "Point": continue
    p = f.get("properties") or {}
    ref = p.get("ref") or p.get("addr:flats") and ("кв. " + p["addr:flats"]) or ""
    kind = p.get("entrance", "yes")
    if not ref and kind not in ("main", "staircase"): continue
    lon, lat = g["coordinates"]
    k = 1 << Z
    fx = (lon + 180) / 360 * k
    fy = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * k
    x, y = int(fx), int(fy)
    px, py = (fx - x) * EXT, (fy - y) * EXT
    tiles.setdefault((x, y), []).append({"geometry": f"POINT({px:.1f} {py:.1f})", "properties": {"ref": str(ref)[:12], "kind": kind}})
    n += 1
m = MBTiles(sys.argv[1], {"name": "Подъезды", "format": "pbf", "type": "overlay", "minzoom": Z, "maxzoom": Z,
                          "json": json.dumps({"vector_layers": [{"id": "entrance", "fields": {"ref": "String", "kind": "String"}}]})})
for (x, y), fs in tiles.items():
    data = mapbox_vector_tile.encode([{"name": "entrance", "features": fs}], default_options={"y_coord_down": True, "extents": EXT})
    m.put(Z, x, y, gzip.compress(data))
m.close()
print("entrances", n, "tiles", len(tiles))
