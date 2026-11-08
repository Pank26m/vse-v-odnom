"""Граница выгрузки Geofabrik (.poly) → GeoJSON (точный и упрощённый для серой штриховки) + рамка."""
import json, sys

def read_poly(path):
    rings, cur, name = [], None, None
    for line in open(path):
        s = line.strip()
        if not s: continue
        if name is None: name = s; continue
        if s == "END":
            if cur is not None: rings.append(cur); cur = None
            continue
        if cur is None: cur = {"hole": s.startswith("!"), "pts": []}; continue
        lon, lat = map(float, s.split()[:2]); cur["pts"].append([lon, lat])
    return rings

def simplify(pts, eps):
    if len(pts) < 4: return pts
    keep = [False] * len(pts); keep[0] = keep[-1] = True; stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop(); ax, ay = pts[a]; bx, by = pts[b]; dx, dy = bx - ax, by - ay; L = (dx * dx + dy * dy) ** 0.5 or 1e-12
        best, bi = -1, -1
        for i in range(a + 1, b):
            d = abs(dy * (pts[i][0] - ax) - dx * (pts[i][1] - ay)) / L
            if d > best: best, bi = d, i
        if best > eps: keep[bi] = True; stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]

def to_geojson(paths, eps=0.0):
    polys = []
    for path in paths:
        for r in read_poly(path):
            pts = r["pts"] if r["pts"][0] == r["pts"][-1] else r["pts"] + [r["pts"][0]]
            if eps: pts = simplify(pts, eps)
            if r["hole"] and polys: polys[-1].append(pts)
            elif not r["hole"]: polys.append([pts])
    return {"type": "MultiPolygon", "coordinates": polys}

def contains(geom, lon, lat):
    inside = False
    for poly in geom["coordinates"]:
        for ring in poly:
            j = len(ring) - 1
            for i in range(len(ring)):
                xi, yi = ring[i]; xj, yj = ring[j]
                if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi: inside = not inside
                j = i
    return inside

if __name__ == "__main__":
    out, *paths = sys.argv[1:]
    g = to_geojson(paths, 0.01)
    xs = [p[0] for poly in g["coordinates"] for r in poly for p in r]; ys = [p[1] for poly in g["coordinates"] for r in poly for p in r]
    json.dump({"geometry": g, "bbox": [min(xs), min(ys), max(xs), max(ys)]}, open(out, "w"), separators=(",", ":"))
