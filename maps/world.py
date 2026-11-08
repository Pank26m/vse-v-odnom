"""Карта мира: рельеф z0…6 из Terrarium (AWS Open Data, Mapzen/Nextzen) → world-relief.mbtiles (WebP)."""
import io, sys, concurrent.futures as cf, urllib.request
from relief import render
from mbtiles import MBTiles

OUT = sys.argv[1]; ZMAX = int(sys.argv[2]) if len(sys.argv) > 2 else 6
URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

def job(t):
    z, x, y = t
    for _ in range(4):
        try:
            png = urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60).read()
            b = io.BytesIO(); render(png, z, y).save(b, "WEBP", quality=82, method=4)
            return t, b.getvalue()
        except Exception as e:
            err = e
    raise err

m = MBTiles(OUT, {"name": "Рельеф мира", "format": "webp", "type": "baselayer", "minzoom": 0, "maxzoom": ZMAX,
                  "bounds": "-180,-85.0511,180,85.0511", "attribution": "Рельеф: Mapzen Terrain Tiles (SRTM, GMTED, ETOPO1)"})
tiles = [(z, x, y) for z in range(ZMAX + 1) for x in range(1 << z) for y in range(1 << z)]
with cf.ThreadPoolExecutor(16) as ex:
    for i, ((z, x, y), data) in enumerate(ex.map(job, tiles)):
        m.put(z, x, y, data)
        if i % 500 == 0: print(f"world {i}/{len(tiles)}", flush=True)
m.close()
