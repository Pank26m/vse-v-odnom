"""Подписи и границы для карты мира из Natural Earth (public domain) → GeoJSONSeq для tippecanoe.

python3 world_labels.py natural_earth_vector.sqlite outdir
Слои: country (точки подписей стран), city (города), boundary (границы стран и областей).
Имена: name (местное/английское), name_ru, name_uk, name_en.
"""
import json, sys
from osgeo import ogr

src = ogr.Open(sys.argv[1]); out = sys.argv[2]


def props(f, keys):
    d = {}
    for k in keys:
        i = f.GetFieldIndex(k)
        if i < 0: i = f.GetFieldIndex(k.upper())
        if i >= 0: d[k] = f.GetField(i)
    return d


def write(name, gen):
    n = 0
    with open(f"{out}/{name}.geojsonl", "w") as fh:
        for feat in gen:
            fh.write(json.dumps(feat, ensure_ascii=False) + "\n"); n += 1
    print(name, n)


def countries():
    for f in src.GetLayerByName("ne_10m_admin_0_countries"):
        p = props(f, ["name", "name_ru", "name_uk", "name_en", "label_x", "label_y", "min_label", "labelrank"])
        if p.get("label_x") is None: continue
        mz = max(0, min(6, int(round(p.get("min_label") or 3)) - 1))
        yield {"type": "Feature", "tippecanoe": {"minzoom": mz}, "geometry": {"type": "Point", "coordinates": [p["label_x"], p["label_y"]]},
               "properties": {"name": p.get("name"), "name_ru": p.get("name_ru"), "name_uk": p.get("name_uk"), "name_en": p.get("name_en"), "rank": p.get("labelrank") or 5}}


def cities():
    for f in src.GetLayerByName("ne_10m_populated_places"):
        p = props(f, ["name", "name_ru", "name_uk", "name_en", "min_zoom", "scalerank", "featurecla", "pop_max"])
        mz = p.get("min_zoom") or 7
        if mz > 7: continue
        g = f.GetGeometryRef()
        cap = 1 if "capital" in (p.get("featurecla") or "").lower() and "Admin-0" in (p.get("featurecla") or "") else 0
        yield {"type": "Feature", "tippecanoe": {"minzoom": max(1, int(mz) - 1)}, "geometry": {"type": "Point", "coordinates": [g.GetX(), g.GetY()]},
               "properties": {"name": p.get("name"), "name_ru": p.get("name_ru"), "name_uk": p.get("name_uk"), "name_en": p.get("name_en"),
                              "capital": cap, "rank": p.get("scalerank") or 10, "pop": p.get("pop_max") or 0}}


def boundaries():
    for lname, level, mz in (("ne_10m_admin_0_boundary_lines_land", 2, 0), ("ne_10m_admin_1_states_provinces_lines", 4, 4)):
        lay = src.GetLayerByName(lname)
        if lay is None: continue
        for f in lay:
            g = json.loads(f.GetGeometryRef().ExportToJson())
            yield {"type": "Feature", "tippecanoe": {"minzoom": mz}, "geometry": g, "properties": {"admin_level": level}}


write("country", countries()); write("city", cities()); write("boundary", boundaries())
