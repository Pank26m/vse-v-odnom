"""Линии высот из рельефа (<id>-dem.mbtiles, тайлы z11 Terrarium) → <id>-contours.mbtiles (векторные тайлы z11…14).

python3 contours_tiles.py dem.mbtiles out.mbtiles
z11 — через 100 м, z12 — +50 м, z13–14 — через 20 м. Рельеф слегка сглаживается, к тайлу добавляется край соседей — без швов.
"""
import gzip, io, math, sys, sqlite3
from multiprocessing import Pool
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
from skimage.measure import find_contours
from shapely import clip_by_rect
from shapely.geometry import LineString
import mapbox_vector_tile
from mbtiles import MBTiles

DEM, OUT = sys.argv[1], sys.argv[2]
Z = 11
EXT = 4096


def load(db, x, y):
    r = db.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?", (Z, x, (1 << Z) - 1 - y)).fetchone()
    if not r: return None
    a = np.asarray(Image.open(io.BytesIO(r[0])).convert("RGB"), dtype=np.float32)
    return a[..., 0] * 256 + a[..., 1] + a[..., 2] / 256 - 32768


_db = None
def work(xy):
    global _db
    if _db is None: _db = sqlite3.connect(DEM)
    x, y = xy
    c = load(_db, x, y)
    if c is None: return []
    # 3×3 соседей по краям (по 8 пикселей), отсутствующих — повторяем край
    P = 8
    big = np.pad(c, P, mode="edge")
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if dx == dy == 0: continue
            nb = load(_db, x + dx, y + dy)
            if nb is None: continue
            ys = slice(0, P) if dy < 0 else slice(P + 256, P * 2 + 256) if dy > 0 else slice(P, P + 256)
            xs = slice(0, P) if dx < 0 else slice(P + 256, P * 2 + 256) if dx > 0 else slice(P, P + 256)
            sy = slice(256 - P, 256) if dy < 0 else slice(0, P) if dy > 0 else slice(0, 256)
            sx = slice(256 - P, 256) if dx < 0 else slice(0, P) if dx > 0 else slice(0, 256)
            big[ys, xs] = nb[sy, sx]
    big = gaussian_filter(big, 1.0)
    lo, hi = big.min(), big.max()
    levels = [l for l in range(int(math.ceil(max(lo, 1) / 20) * 20), int(hi) + 1, 20)]
    if not levels: return []
    lines = []   # (ele, [(col,row) в пикселях тайла z11])
    for lv in levels:
        for cnt in find_contours(big, lv):
            if len(cnt) < 3: continue
            lines.append((lv, [(p[1] - P + 0.5, p[0] - P + 0.5) for p in cnt]))
    out = []
    for zt in range(Z, 15):
        k = 1 << (zt - Z); span = 256 / k; scale = EXT / span
        step = 100 if zt == 11 else 50 if zt == 12 else 20
        tol = 1.5 if zt >= 13 else 2.5
        for i in range(k):
            for j in range(k):
                feats = []
                x0, y0 = j * span, i * span
                for lv, pts in lines:
                    if lv % step: continue
                    g = LineString([((px - x0) * scale, (py - y0) * scale) for px, py in pts])
                    g = clip_by_rect(g, -64, -64, EXT + 64, EXT + 64)
                    if g.is_empty: continue
                    g = g.simplify(tol)
                    if g.is_empty or g.length < 8: continue
                    feats.append({"geometry": g, "properties": {"ele": lv, "major": 1 if lv % 100 == 0 else 0}})
                if feats:
                    data = mapbox_vector_tile.encode([{"name": "contour", "features": feats}], default_options={"y_coord_down": True, "extents": EXT})
                    out.append((zt, x * k + j, y * k + i, gzip.compress(data)))
    return out


if __name__ == "__main__":
    src = sqlite3.connect(DEM)
    tiles = [(x, (1 << Z) - 1 - r) for x, r in src.execute("select tile_column, tile_row from tiles where zoom_level=?", (Z,))]
    print("z11 dem tiles", len(tiles), flush=True)
    m = MBTiles(OUT, {"name": "Линии высот", "format": "pbf", "type": "overlay", "minzoom": Z, "maxzoom": 14,
                      "attribution": "Mapzen Terrain Tiles", "json": '{"vector_layers":[{"id":"contour","fields":{"ele":"Number","major":"Number"}}]}'})
    n = 0
    with Pool() as pool:
        for i, res in enumerate(pool.imap_unordered(work, tiles, chunksize=8)):
            for z, x, y, d in res: m.put(z, x, y, d); n += 1
            if i % 200 == 0: print(f"contours {i}/{len(tiles)} tiles {n}", flush=True)
    m.close()
    print("contour tiles", n)
