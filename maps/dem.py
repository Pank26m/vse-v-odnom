"""Рельеф страны: тайлы Terrarium внутри границы → <id>-dem.mbtiles (raster-dem для теней и будущего 3D)
и мозаика высот (GeoTIFF через GDAL) для линий высот."""
import json, math, os, sys, concurrent.futures as cf, urllib.request
import numpy as np
from mbtiles import MBTiles
from poly import contains

meta_path, out_mbtiles, tif_dir = sys.argv[1:4]
ZMAX = int(sys.argv[4]) if len(sys.argv) > 4 else 11
CZ = int(sys.argv[5]) if len(sys.argv) > 5 else 11          # зум мозаики для линий высот
URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
meta = json.load(open(meta_path)); geom = meta["geometry"]; w, s, e, n = meta["bbox"]

def lon2x(lon, z): return int((lon + 180) / 360 * (1 << z))
def lat2y(lat, z): r = math.radians(lat); return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << z))
def x2lon(x, z): return x / (1 << z) * 360 - 180
def y2lat(y, z): return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))

def touches(z, x, y):
    lw, le, ln, ls = x2lon(x, z), x2lon(x + 1, z), y2lat(y, z), y2lat(y + 1, z)
    for fx in (0.02, 0.5, 0.98):
        for fy in (0.02, 0.5, 0.98):
            if contains(geom, lw + (le - lw) * fx, ln + (ls - ln) * fy): return True
    for poly in geom["coordinates"]:
        for lon, lat in poly[0]:
            if lw <= lon <= le and ls <= lat <= ln: return True
    return False

def tiles(z):
    return [(z, x, y) for x in range(lon2x(w, z), lon2x(e, z) + 1) for y in range(lat2y(n, z), lat2y(s, z) + 1) if touches(z, x, y)]

def fetch(t):
    z, x, y = t
    for k in range(5):
        try: return t, urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60).read()
        except Exception as err: last = err
    raise last

all_tiles = [t for z in range(5, ZMAX + 1) for t in tiles(z)]
print("dem tiles", len(all_tiles), flush=True)
m = MBTiles(out_mbtiles, {"name": meta.get("name", "dem"), "format": "png", "type": "overlay", "minzoom": 5, "maxzoom": ZMAX,
                          "bounds": f"{w},{s},{e},{n}", "encoding": "terrarium", "attribution": "Mapzen Terrain Tiles"})
os.makedirs(tif_dir, exist_ok=True)
from osgeo import gdal
import io
from PIL import Image
R = 20037508.342789244
with cf.ThreadPoolExecutor(24) as ex:
    for i, ((z, x, y), png) in enumerate(ex.map(fetch, all_tiles)):
        m.put(z, x, y, png)
        if z == CZ:
            a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"), dtype=np.float32)
            el = a[..., 0] * 256 + a[..., 1] + a[..., 2] / 256 - 32768
            size = 2 * R / (1 << z)
            ds = gdal.GetDriverByName("GTiff").Create(f"{tif_dir}/{x}_{y}.tif", 256, 256, 1, gdal.GDT_Float32, ["COMPRESS=DEFLATE"])
            ds.SetGeoTransform((-R + x * size, size / 256, 0, R - y * size, 0, -size / 256))
            ds.SetProjection('PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],PARAMETER["scale_factor",1],PARAMETER["false_easting",0],PARAMETER["false_northing",0],UNIT["metre",1],AUTHORITY["EPSG","3857"]]')
            ds.GetRasterBand(1).WriteArray(el); ds.GetRasterBand(1).SetNoDataValue(-32768); ds = None
        if i % 1000 == 0: print(f"dem {i}/{len(all_tiles)}", flush=True)
m.close()
