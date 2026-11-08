#!/usr/bin/env bash
# Сборка офлайн-карт стран для приложения «Всё в одном». Запускается в GitHub Actions (maps.yml).
# Результат — файлы в релизе maps-v1: <id>.mbtiles (векторная карта OpenMapTiles), <id>-dem.mbtiles (рельеф),
# <id>-contours.mbtiles (линии высот), <id>.json (граница), world-relief.mbtiles, catalog.json, build-report.txt.
set -uo pipefail
cd "$(dirname "$0")"
OUT=out; mkdir -p "$OUT" work
REPORT="$OUT/build-report.txt"; : > "$REPORT"
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$REPORT"; }
REL=maps-v1

gh release view "$REL" >/dev/null 2>&1 || gh release create "$REL" --title "Офлайн-карты" --notes "Файлы офлайн-карт для приложения. Данные © участники OpenStreetMap (ODbL), рельеф — Mapzen Terrain Tiles." >/dev/null
gh release download "$REL" -p catalog.json -D work --clobber 2>/dev/null || echo '{"countries":[]}' > work/catalog.json
ASSETS=$(gh release view "$REL" --json assets -q '.assets[].name' 2>/dev/null || true)

# карта мира — один раз
if ! grep -qx "world-relief.mbtiles" <<<"$ASSETS" || [ "${FORCE_WORLD:-0}" = 1 ]; then
  log "карта мира: старт"
  python3 world.py "$OUT/world-relief.mbtiles" 6 >> "$REPORT" 2>&1 && log "карта мира: $(du -h $OUT/world-relief.mbtiles | cut -f1)" || log "ОШИБКА: карта мира"
fi

PLANETILER=work/planetiler.jar
[ -f $PLANETILER ] || curl -sSL -o $PLANETILER https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar

grep -v '^#' countries.txt | grep -v '^\s*$' | while IFS='|' read -r ID NAME PATHS; do
  if grep -qx "$ID.mbtiles" <<<"$ASSETS" && [ "${FORCE:-}" != "$ID" ] && [ "${FORCE:-}" != all ]; then log "$ID: уже собрана, пропускаю"; continue; fi
  log "$ID ($NAME): старт"
  PBFS=(); POLYS=()
  IFS='+' read -ra PARTS <<<"$PATHS"
  for P in "${PARTS[@]}"; do
    F="work/$(basename "$P")"
    curl -sSL -o "$F.osm.pbf" "https://download.geofabrik.de/$P-latest.osm.pbf" || { log "ОШИБКА: скачать $P"; continue 2; }
    curl -sSL -o "$F.poly" "https://download.geofabrik.de/$P.poly"
    PBFS+=("$F.osm.pbf"); POLYS+=("$F.poly")
    log "$ID: скачано $P $(du -h "$F.osm.pbf" | cut -f1)"
  done
  # Крым: если граница Geofabrik его не включает — докачиваем и склеиваем
  if [ "$ID" = ukraine ] && ! python3 -c "import sys,json; sys.path.insert(0,'.'); from poly import to_geojson, contains; sys.exit(0 if contains(to_geojson(['work/ukraine.poly']), 34.16, 44.50) else 1)"; then
    curl -sSL -o work/crimea.osm.pbf https://download.geofabrik.de/russia/crimean-fed-district-latest.osm.pbf && curl -sSL -o work/crimea.poly https://download.geofabrik.de/russia/crimean-fed-district.poly \
      && PBFS+=(work/crimea.osm.pbf) && POLYS+=(work/crimea.poly) && log "$ID: добавлен Крым"
  fi
  if [ ${#PBFS[@]} -gt 1 ]; then osmium merge "${PBFS[@]}" -o "work/$ID-merged.osm.pbf" --overwrite && PBF="work/$ID-merged.osm.pbf"; else PBF="${PBFS[0]}"; fi
  python3 poly.py "$OUT/$ID.json" "${POLYS[@]}"
  python3 - "$OUT/$ID.json" "$NAME" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["name"] = sys.argv[2]; json.dump(d, open(sys.argv[1], "w"), separators=(",", ":"))
PY

  log "$ID: векторная карта (Planetiler)"
  java -Xmx12g -jar $PLANETILER --osm-path="$PBF" --download --output="$OUT/$ID.mbtiles" --force \
     --languages=ru,uk,en --maxzoom=14 --render_maxzoom=14 --tmpdir=work/tmp >> work/planetiler-$ID.log 2>&1 \
     && log "$ID: карта $(du -h $OUT/$ID.mbtiles | cut -f1)" || { log "ОШИБКА: Planetiler"; tail -40 work/planetiler-$ID.log >> "$REPORT"; continue; }

  log "$ID: рельеф"
  rm -rf work/tif && python3 dem.py "$OUT/$ID.json" "$OUT/$ID-dem.mbtiles" work/tif 11 11 >> "$REPORT" 2>&1 \
     && log "$ID: рельеф $(du -h $OUT/$ID-dem.mbtiles | cut -f1)" || log "ОШИБКА: рельеф"

  log "$ID: линии высот"
  gdalbuildvrt -q work/dem.vrt work/tif/*.tif && gdal_contour -q -a ele -i 20 work/dem.vrt work/contours.gpkg \
   && ogr2ogr -f GeoJSONSeq -t_srs EPSG:4326 /vsistdout/ work/contours.gpkg | python3 contours_prep.py > work/contours.geojsonl \
   && tippecanoe -q -o "$OUT/$ID-contours.mbtiles" -l contour -Z9 -z14 --force --no-tile-size-limit --simplification=4 \
        -n "Линии высот" -A "Mapzen Terrain Tiles" work/contours.geojsonl \
   && log "$ID: линии высот $(du -h $OUT/$ID-contours.mbtiles | cut -f1)" || log "ОШИБКА: линии высот"
  rm -rf work/tif work/contours.* work/*.osm.pbf work/tmp

  # большие файлы режем на части по 1.9 ГБ (лимит GitHub — 2 ГБ на файл)
  for f in "$OUT/$ID.mbtiles" "$OUT/$ID-dem.mbtiles" "$OUT/$ID-contours.mbtiles"; do
    [ -f "$f" ] || continue
    if [ $(stat -c %s "$f") -gt 1900000000 ]; then split -b 1900000000 -d -a 2 "$f" "$f.part" && rm "$f"; fi
  done
  python3 - "$ID" "$NAME" <<'PY'
import json, os, glob, sys, datetime
cid, name = sys.argv[1], sys.argv[2]
cat = json.load(open("work/catalog.json"))
def files(base):
    parts = sorted(glob.glob(f"out/{base}.part*")) or ([f"out/{base}"] if os.path.exists(f"out/{base}") else [])
    return [{"file": os.path.basename(p), "size": os.path.getsize(p)} for p in parts]
meta = json.load(open(f"out/{cid}.json"))
entry = {"id": cid, "name": name, "bbox": meta["bbox"], "built": datetime.date.today().isoformat(),
         "map": files(f"{cid}.mbtiles"), "dem": files(f"{cid}-dem.mbtiles"), "contours": files(f"{cid}-contours.mbtiles"), "shape": f"{cid}.json"}
entry["size"] = sum(f["size"] for k in ("map", "dem", "contours") for f in entry[k])
cat["countries"] = [c for c in cat["countries"] if c["id"] != cid] + [entry]
json.dump(cat, open("work/catalog.json", "w"), ensure_ascii=False, indent=1)
PY
  gh release upload "$REL" $OUT/$ID*.mbtiles* "$OUT/$ID.json" --clobber && log "$ID: загружено" || log "ОШИБКА: загрузка"
  rm -f $OUT/$ID*.mbtiles*
done

python3 - <<'PY'
import json, os
cat = json.load(open("work/catalog.json"))
if os.path.exists("out/world-relief.mbtiles"): cat["world"] = {"file": "world-relief.mbtiles", "size": os.path.getsize("out/world-relief.mbtiles")}
json.dump(cat, open("out/catalog.json", "w"), ensure_ascii=False, indent=1)
PY
[ -f $OUT/world-relief.mbtiles ] && gh release upload "$REL" $OUT/world-relief.mbtiles --clobber
gh release upload "$REL" $OUT/catalog.json --clobber
log "готово"
gh release upload "$REL" "$REPORT" --clobber
