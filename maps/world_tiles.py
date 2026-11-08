"""Векторные тайлы подписей мира z0…7 без прореживания (tippecanoe выкидывал большинство городов и стран).

python3 world_tiles.py dir_with_geojsonl out.mbtiles
Каждый объект попадает во все тайлы со своего minzoom (поле tippecanoe.minzoom); линии режутся по тайлу и упрощаются.
"""
import gzip, json, math, sys
import mapbox_vector_tile
from shapely.geometry import shape, mapping, box
from shapely.ops import transform
from mbtiles import MBTiles

SRC, OUT = sys.argv[1], sys.argv[2]
ZMAX = 7
R = 6378137.0; HALF = math.pi * R


def merc(lon, lat, z=None):
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


layers = {}
for name in ("country", "city", "boundary"):
    feats = []
    for line in open(f"{SRC}/{name}.geojsonl"):
        f = json.loads(line)
        g = transform(lambda x, y, z=None: merc(x, y), shape(f["geometry"]))
        props = {k: v for k, v in f["properties"].items() if v is not None}
        feats.append((f.get("tippecanoe", {}).get("minzoom", 0), g, props))
    layers[name] = feats
    print(name, len(feats))

m = MBTiles(OUT, {"name": "Подписи мира", "format": "pbf", "type": "overlay", "minzoom": 0, "maxzoom": ZMAX,
                  "bounds": "-180,-85.0511,180,85.0511", "attribution": "Natural Earth",
                  "json": json.dumps({"vector_layers": [{"id": k, "fields": {}} for k in layers]})})
for z in range(ZMAX + 1):
    tol = 2 * HALF / (1 << z) / 512          # упрощение линий: ~полпикселя тайла 256
    buckets = {}
    for lname, feats in layers.items():
        for mz, g, props in feats:
            if mz > z: continue
            gg = g if g.geom_type == "Point" else g.simplify(tol, preserve_topology=False)
            if gg.is_empty: continue
            for xy in tiles_for(gg.bounds, z):
                buckets.setdefault(xy, {}).setdefault(lname, []).append((gg, props))
    n = 0
    for (x, y), lays in buckets.items():
        tb = tile_bounds(z, x, y)
        pad = (tb[2] - tb[0]) * 0.02
        clip = box(tb[0] - pad, tb[1] - pad, tb[2] + pad, tb[3] + pad)
        out = []
        for lname, items in lays.items():
            fs = []
            for gg, props in items:
                c = gg if gg.geom_type == "Point" else gg.intersection(clip)
                if c.is_empty: continue
                if gg.geom_type == "Point" and not clip.contains(c): continue
                fs.append({"geometry": c.wkt, "properties": props})
            if fs: out.append({"name": lname, "features": fs})
        if not out: continue
        data = mapbox_vector_tile.encode(out, default_options={"quantize_bounds": tb, "extents": 4096, "y_coord_down": False})
        m.put(z, x, y, gzip.compress(data)); n += 1
    print("z", z, "tiles", n, flush=True)
m.close()
