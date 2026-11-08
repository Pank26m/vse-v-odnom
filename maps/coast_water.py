"""Полигоны моря для Planetiler из береговой линии OSM внутри границы страны (когда osmdata.openstreetmap.de недоступен).

osmium export coast.osm.pbf -f geojsonseq | python3 coast_water.py <страна>.json out_dir
Береговая линия в OSM идёт так, что суша слева, вода справа. Граница страны + береговые линии режут область на куски;
кусок — вода, если ближайший к нему участок берега оставляет его справа. Вода режется сеткой 0.5° и пишется
shapefile'ом в EPSG:3857 (out_dir/water_polygons.shp).
"""
import json, math, sys
from shapely.geometry import shape, LineString, Polygon, MultiPolygon, box, mapping
from shapely.ops import unary_union, polygonize, linemerge
from shapely.strtree import STRtree
from shapely.validation import make_valid

region = make_valid(shape(json.load(open(sys.argv[1]))["geometry"])).buffer(0)
out_dir = sys.argv[2]

lines = []
for raw in sys.stdin:
    raw = raw.strip().lstrip("\x1e")
    if not raw: continue
    try: g = shape(json.loads(raw)["geometry"])
    except Exception: continue
    if g.geom_type == "LineString": lines.append(g)
    elif g.geom_type == "MultiLineString": lines.extend(g.geoms)
    elif g.geom_type == "Polygon": lines.append(LineString(g.exterior.coords))   # остров целиком
print("coast lines", len(lines), flush=True)
if not lines: sys.exit(1)

tree = STRtree(lines)
noded = unary_union(lines + [region.boundary])
faces = [f for f in polygonize(noded) if f.representative_point().within(region)]
print("faces", len(faces), flush=True)


def is_water(face):
    p = face.representative_point()
    i = tree.nearest(p); ln = lines[i]
    d = ln.project(p); a = ln.interpolate(max(0.0, d - 1e-6)); b = ln.interpolate(min(ln.length, d + 1e-6))
    if a.equals(b): a = ln.interpolate(max(0.0, d - 1e-4)); b = ln.interpolate(min(ln.length, d + 1e-4))
    cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
    return cross < 0          # точка справа от берега — вода


water = unary_union([f for f in faces if is_water(f)])
print("water area deg2", round(water.area, 3), flush=True)

R = 6378137.0
def merc(x, y):
    y = max(-85.0511, min(85.0511, y))
    return R * math.radians(x), R * math.log(math.tan(math.pi / 4 + math.radians(y) / 2))

polys = []
minx, miny, maxx, maxy = water.bounds
x = math.floor(minx * 2) / 2
while x < maxx:
    y = math.floor(miny * 2) / 2
    while y < maxy:
        part = water.intersection(box(x, y, x + 0.5, y + 0.5))
        for poly in (part.geoms if hasattr(part, "geoms") else [part]):
            if poly.geom_type != "Polygon" or poly.is_empty or poly.area < 1e-9: continue
            polys.append(Polygon([merc(*c) for c in poly.exterior.coords], [[merc(*c) for c in r.coords] for r in poly.interiors]))
        y += 0.5
    x += 0.5
print("water polygons", len(polys), flush=True)
try:
    from osgeo import ogr, osr
    ds = ogr.GetDriverByName("ESRI Shapefile").CreateDataSource(f"{out_dir}/water_polygons.shp")
    srs = osr.SpatialReference(); srs.ImportFromEPSG(3857)
    lay = ds.CreateLayer("water_polygons", srs, ogr.wkbPolygon)
    for poly in polys:
        f = ogr.Feature(lay.GetLayerDefn()); f.SetGeometry(ogr.CreateGeometryFromWkb(poly.wkb)); lay.CreateFeature(f)
    ds = None
except ImportError:      # проверка без GDAL
    json.dump({"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {}, "geometry": mapping(p)} for p in polys]}, open(f"{out_dir}/water_polygons.geojson", "w"))
