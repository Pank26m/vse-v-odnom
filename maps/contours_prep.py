"""Линии высот: на каком зуме показывать (100 м — с 9, 50 м — с 11, 20 м — с 12) и признак «жирная»."""
import json, sys
for line in sys.stdin:
    f = json.loads(line.lstrip("\x1e"))
    ele = int(round(f["properties"].get("ele", 0)))
    if ele <= 0: continue
    f["properties"] = {"ele": ele, "major": 1 if ele % 100 == 0 else 0}
    f["tippecanoe"] = {"minzoom": 9 if ele % 100 == 0 else (11 if ele % 50 == 0 else 12)}
    sys.stdout.write(json.dumps(f, separators=(",", ":")) + "\n")
