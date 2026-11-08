"""Точная граница страны из OSM (relation admin_level=2, экспорт osmium) → <id>.json для приложения (упрощённая)
и GeoJSON для osmium extract (полная). python3 border.py border.geojson out.json name"""
import json, sys
from shapely.geometry import shape, mapping
from shapely.ops import unary_union
src, out, name = sys.argv[1:4]
d = json.load(open(src))
feats = d["features"] if d.get("type") == "FeatureCollection" else [d]
g = unary_union([shape(f["geometry"]) for f in feats if f.get("geometry") and f["geometry"]["type"] in ("Polygon", "MultiPolygon")])
if g.is_empty: sys.exit("пустая граница")
# чистый полигон для osmium extract (один объект, без лишних кусков)
full = g.buffer(0)
json.dump({"type": "Feature", "properties": {}, "geometry": mapping(full)}, open(out.replace(".json", "-clip.geojson"), "w"))
s = g.simplify(0.003, preserve_topology=True)
m = mapping(s)
if m["type"] == "Polygon": m = {"type": "MultiPolygon", "coordinates": [m["coordinates"]]}
coords = json.loads(json.dumps(m))
json.dump({"geometry": coords, "bbox": list(s.bounds), "name": name}, open(out, "w"), separators=(",", ":"))
print("border parts", len(coords["coordinates"]), "bbox", [round(v, 3) for v in s.bounds])
