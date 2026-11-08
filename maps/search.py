"""Офлайн-поиск страны: GeoJSONSeq из `osmium export` (stdin) → <id>-search.db (SQLite).

Таблицы: f — объекты (имя, вид «ключ:значение», точка, ближайший населённый пункт, ранг),
w — слова имён (нормализованные) → f.id, a — адреса домов (улица → f.id улицы, номер, точка).
Приложение ищет по началу слов (индекс w_word), номер дома — в a.
"""
import json, math, re, sqlite3, sys, os

OUT = sys.argv[1]
PLACE_RANK = {"city": 1, "town": 2, "village": 4, "suburb": 3, "hamlet": 6, "neighbourhood": 6, "quarter": 5, "locality": 7, "isolated_dwelling": 8}
STREETS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "pedestrian", "service", "road", "trunk_link", "primary_link"}
KEYS = ("amenity", "shop", "tourism", "leisure", "historic", "office", "craft", "healthcare", "railway", "public_transport", "aeroway", "natural", "waterway", "man_made", "sport", "building")
WORD = re.compile(r"[0-9a-zа-яёіїєґ]+")


def norm(s):
    s = s.lower().replace("ё", "е").replace("'", "").replace("’", "").replace("ʼ", "")
    return WORD.findall(s)


def center(g):
    t, c = g["type"], g["coordinates"]
    if t == "Point": return c[1], c[0]
    if t == "LineString": p = c[len(c) // 2]; return p[1], p[0]
    if t == "MultiLineString": c = max(c, key=len); p = c[len(c) // 2]; return p[1], p[0]
    ring = c[0] if t == "Polygon" else max((p[0] for p in c), key=len)
    xs = [p[0] for p in ring]; ys = [p[1] for p in ring]
    return (min(ys) + max(ys)) / 2, (min(xs) + max(xs)) / 2


places, feats, addrs = [], [], []
for line in sys.stdin:
    line = line.strip().lstrip("\x1e")
    if not line: continue
    try:
        o = json.loads(line)
    except ValueError:
        continue
    p = o.get("properties") or {}; g = o.get("geometry")
    if not g: continue
    try:
        lat, lon = center(g)
    except Exception:
        continue
    name = p.get("name") or ""
    if p.get("addr:housenumber") and p.get("addr:street"):
        addrs.append((p["addr:street"], p["addr:housenumber"], round(lat, 6), round(lon, 6)))
    if not name: continue
    alt = " ".join(dict.fromkeys(v for v in (p.get("name:ru"), p.get("name:uk"), p.get("name:en"), p.get("old_name"), p.get("alt_name")) if v and v != name))
    if "place" in p:
        r = PLACE_RANK.get(p["place"])
        if r is None: continue
        places.append((lat, lon, name, r, p.get("population")))
        feats.append([name, alt, "place:" + p["place"], lat, lon, r]); continue
    hw = p.get("highway")
    if hw in STREETS and g["type"] in ("LineString", "MultiLineString"):
        feats.append([name, alt, "highway:" + hw, lat, lon, 5]); continue
    for k in KEYS:
        if k in p and p[k] not in ("yes", "no") or (k == "building" and k in p):
            if k == "building" and not p.get("addr:housenumber") and p[k] in ("yes", "house", "residential", "apartments"): break
            feats.append([name, alt, f"{k}:{p[k]}", lat, lon, 7]); break
print("places", len(places), "features", len(feats), "addresses", len(addrs), flush=True)

# ближайший населённый пункт: сетка 0.1°, город «дотягивается» дальше деревни
grid = {}
for pl in places:
    grid.setdefault((int(pl[0] * 10), int(pl[1] * 10)), []).append(pl)
REACH = {1: 15000, 2: 6000, 3: 3000, 4: 2500, 5: 1500, 6: 1200, 7: 1000, 8: 600}


def nearest(lat, lon, skip=None):
    best, bd = "", 1e18
    k = math.cos(math.radians(lat))
    for dx in range(-2, 3):
        for dy in range(-2, 3):
            for pl in grid.get((int(lat * 10) + dx, int(lon * 10) + dy), ()):
                if pl[2] == skip or pl[3] == 3 or pl[3] >= 6: continue
                d = math.hypot((pl[0] - lat) * 111000, (pl[1] - lon) * 111000 * k)
                score = d / REACH.get(pl[3], 1000)
                if score < bd: bd, best = score, pl[2]
    return best if bd < 3 else ""


if os.path.exists(OUT): os.remove(OUT)
db = sqlite3.connect(OUT)
db.executescript("""
PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA page_size=4096;
CREATE TABLE f(id INTEGER PRIMARY KEY, name TEXT NOT NULL, alt TEXT NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, place TEXT NOT NULL, rank INTEGER NOT NULL);
CREATE TABLE w(word TEXT NOT NULL, fid INTEGER NOT NULL);
CREATE TABLE a(street INTEGER NOT NULL, num TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL);
""")
seen_street = {}
rows = []
for name, alt, kind, lat, lon, rank in feats:
    pl = "" if kind in ("place:city", "place:town") else nearest(lat, lon, name if kind.startswith("place:") else None)
    if kind.startswith("highway:"):
        key = (name, pl, int(lat * 20), int(lon * 20)) if not pl else (name, pl)
        if key in seen_street: continue
        seen_street[key] = len(rows) + 1
    rows.append((len(rows) + 1, name, alt, kind, round(lat, 6), round(lon, 6), pl, rank))
db.executemany("INSERT INTO f VALUES (?,?,?,?,?,?,?,?)", rows)
words = []
for r in rows:
    for wd in dict.fromkeys(norm(r[1] + " " + r[2])):
        if len(wd) >= 2 or wd.isdigit(): words.append((wd, r[0]))
db.executemany("INSERT INTO w VALUES (?,?)", words)
# адреса: улица ищется по имени и ближайшему пункту
by_name = {}
for r in rows:
    if r[3].startswith("highway:"): by_name.setdefault(r[1], []).append(r)
arows = []
for street, num, lat, lon in addrs:
    cands = by_name.get(street)
    if not cands: continue
    k = math.cos(math.radians(lat))
    best = min(cands, key=lambda r: (r[4] - lat) ** 2 + ((r[5] - lon) * k) ** 2)
    if math.hypot(best[4] - lat, (best[5] - lon) * k) > 0.08: continue
    arows.append((best[0], num, lat, lon))
db.executemany("INSERT INTO a VALUES (?,?,?,?)", arows)
db.executescript("CREATE INDEX w_word ON w(word); CREATE INDEX a_street ON a(street); CREATE TABLE meta(k TEXT, v TEXT);")
db.execute("INSERT INTO meta VALUES ('version','1')")
db.commit(); db.execute("VACUUM"); db.close()
print("rows", len(rows), "words", len(words), "addr", len(arows), "size", os.path.getsize(OUT), flush=True)
