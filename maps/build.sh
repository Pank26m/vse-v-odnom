#!/usr/bin/env bash
# Сборка офлайн-карт стран для приложения «Всё в одном». Запускается в GitHub Actions (maps.yml).
# Файлы в релизе maps-v1:
#   <id>.mbtiles — векторная карта (схема OpenMapTiles), <id>-dem.mbtiles — рельеф (тени, будущее 3D),
#   <id>-contours.mbtiles — линии высот, <id>-search.db — офлайн-поиск, <id>.json — граница страны,
#   world-relief.mbtiles — рельеф мира z0…6, world-labels.mbtiles — подписи и границы мира,
#   catalog.json — список, build-report.txt — отчёт.
# Каждая часть собирается, только если её ещё нет (или FORCE=<id>|all, FORCE_WORLD=1).
set -uo pipefail
cd "$(dirname "$0")"
OUT=out; mkdir -p "$OUT" work
REPORT="$OUT/build-report.txt"; : > "$REPORT"
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$REPORT"; }
REL=maps-v1
up() { gh release upload "$REL" "$@" --clobber >/dev/null && log "загружено: $(basename -a "$@" | tr '\n' ' ')" || log "ОШИБКА загрузки: $*"; }

gh release view "$REL" >/dev/null 2>&1 || gh release create "$REL" --title "Офлайн-карты" --notes "Файлы офлайн-карт для приложения. Данные © участники OpenStreetMap (ODbL), рельеф — Mapzen Terrain Tiles, подписи мира — Natural Earth." >/dev/null
gh release download "$REL" -p catalog.json -D work --clobber 2>/dev/null || echo '{"countries":[]}' > work/catalog.json
ASSETS=$(gh release view "$REL" --json assets -q '.assets[].name' 2>/dev/null || true)
have() { grep -qx "$1" <<<"$ASSETS" || grep -q "^$1\.part" <<<"$ASSETS"; }
# версия способа сборки частей: если поднять — часть пересоберётся (рельеф v1 был испорчен багом границы)
declare -A PART_VER=([map]=3 [dem]=2 [contours]=3 [search]=3)   # map v3 / search v2 — обрезка строго по границе страны   # contours v2 — tippecanoe терял линии   # map v1 — без моря на крупных масштабах
outdated() { python3 -c "import json,sys; c=next((c for c in json.load(open('work/catalog.json'))['countries'] if c['id']==sys.argv[1]),{}); sys.exit(0 if c.get(sys.argv[2]+'_ver',1) < int(sys.argv[3]) else 1)" "$1" "$2" "${PART_VER[$2]}"; }
forced() { [ "${FORCE:-}" = "$1" ] || [ "${FORCE:-}" = all ]; }

# ---------- мир ----------
WORLD_VER=2   # v2 — до z7 (подробнее, ~60 МБ)
world_old() { python3 -c "import json,sys; sys.exit(0 if json.load(open('work/catalog.json')).get('world_ver',1) < $WORLD_VER else 1)"; }
if ! have world-relief.mbtiles || world_old || [ "${FORCE_WORLD:-0}" = 1 ]; then
  log "рельеф мира: старт"
  python3 world.py "$OUT/world-relief.mbtiles" 7 > work/world.log 2>&1 && log "рельеф мира: $(du -h $OUT/world-relief.mbtiles | cut -f1)" && up "$OUT/world-relief.mbtiles" || { log "ОШИБКА: рельеф мира"; tail -5 work/world.log >> "$REPORT"; }
fi

# источники Planetiler качаем сами — с повторами (osmdata.openstreetmap.de бывает медленным)
mkdir -p data/sources
fetch() { [ -s "$2" ] || curl -sSL --retry 8 --retry-delay 30 --retry-all-errors --connect-timeout 60 -o "$2.tmp" "$1" && mv "$2.tmp" "$2" 2>/dev/null; [ -s "$2" ]; }
WATER=data/sources/water-polygons-split-3857.zip
fetch_water() { [ -s "$WATER" ] || curl -sSL --retry 1 --retry-delay 20 --connect-timeout 40 --max-time 900 -o "$WATER.tmp" https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip && mv "$WATER.tmp" "$WATER" 2>/dev/null; [ -s "$WATER" ]; }
fetch_water || log "osmdata.openstreetmap.de недоступен — море строится из береговой линии каждой страны"
fetch https://naciscdn.org/naturalearth/packages/natural_earth_vector.sqlite.zip data/sources/natural_earth_vector.sqlite.zip || log "ОШИБКА: natural earth"
fetch https://github.com/acalcutt/osm-lakelines/releases/download/v12/lake_centerline.shp.zip data/sources/lake_centerline.shp.zip || log "ОШИБКА: lake centerlines"
# если мировых полигонов моря нет — строим море для каждой страны из её береговой линии (coast_water.py)
COAST_WATER=0; [ -s "$WATER" ] || COAST_WATER=1
log "источники: $(du -sh data/sources | cut -f1)"

NE=work/natural_earth_vector.sqlite
get_ne() {
  [ -f $NE ] && return 0
  local z=data/sources/natural_earth_vector.sqlite.zip
  [ -f $z ] || { mkdir -p data/sources; curl -sSL --retry 3 -o $z https://naciscdn.org/naturalearth/packages/natural_earth_vector.sqlite.zip; }
  unzip -o -q -j $z '*.sqlite' -d work; [ -f $NE ]
}
LABELS_VER=2   # v1 (tippecanoe) терял большинство подписей на мелких масштабах
labels_old() { python3 -c "import json,sys; sys.exit(0 if json.load(open('work/catalog.json')).get('labels_ver',1) < $LABELS_VER else 1)"; }
if ! have world-labels.mbtiles || labels_old || [ "${FORCE_WORLD:-0}" = 1 ]; then
  log "подписи мира: старт"
  mkdir -p work/wl
  if get_ne && python3 world_labels.py $NE work/wl >> "$REPORT" 2>&1 \
     && python3 world_tiles.py work/wl "$OUT/world-labels.mbtiles" >> "$REPORT" 2>&1; then
    log "подписи мира: $(du -h $OUT/world-labels.mbtiles | cut -f1)"; up "$OUT/world-labels.mbtiles"
  else log "ОШИБКА: подписи мира"; fi
fi

PLANETILER=work/planetiler.jar
for k in 1 2 3; do
  unzip -tq $PLANETILER >/dev/null 2>&1 && break
  curl -fsSL --retry 3 -o $PLANETILER https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar || sleep 20
done
unzip -tq $PLANETILER >/dev/null 2>&1 || log "ОШИБКА: planetiler.jar не скачался"

routing_part() {
  local ID=$1
  [ -f "$OUT/$ID.json" ] || gh release download "$REL" -p "$ID.json" -D "$OUT" --clobber || return 0
  # маршруты офлайн: готовые сегменты BRouter (квадраты 5°, лицензия данных ODbL) — зеркалим в релиз
  python3 - "$ID" > work/rd5.txt <<'PY'
import json, sys, math
sys.path.insert(0, ".")
from poly import contains
c = json.load(open(f"out/{sys.argv[1]}.json")); w, s, e, n = c["bbox"]; g = c["geometry"]
for lon in range(int(math.floor(w / 5) * 5), int(math.floor(e / 5) * 5) + 1, 5):
    for lat in range(int(math.floor(s / 5) * 5), int(math.floor(n / 5) * 5) + 1, 5):
        # квадрат нужен, только если в нём есть часть страны (проверка по сетке 0.1°)
        if any(contains(g, lon + i / 10, lat + j / 10) for i in range(51) for j in range(51)):
            print(("E%d" % lon if lon >= 0 else "W%d" % -lon) + "_" + ("N%d" % lat if lat >= 0 else "S%d" % -lat) + ".rd5")
PY
  RD=()
  while read -r R; do
    if have "$R" && [ "${FORCE_ROUTING:-0}" != 1 ]; then RD+=("$R"); continue; fi
    if curl -fsSL --retry 3 --connect-timeout 30 -o "$OUT/$R" "https://brouter.de/brouter/segments4/$R"; then up "$OUT/$R"; RD+=("$R"); rm -f "$OUT/$R"
    else log "$ID: нет сегмента $R (море или ошибка)"; fi
  done < work/rd5.txt
  log "$ID: сегменты маршрутов: ${RD[*]:-нет}"
  ASSETS=$(gh release view "$REL" --json assets -q '.assets[].name' 2>/dev/null || true)
  gh release view "$REL" --json assets > work/assets.json
  python3 - "$ID" "${RD[*]:-}" <<'PY'
import json, sys
cid, names = sys.argv[1], sys.argv[2].split()
sizes = {a["name"]: a["size"] for a in json.load(open("work/assets.json"))["assets"]}
cat = json.load(open("work/catalog.json"))
for c in cat["countries"]:
    if c["id"] == cid:
        c["routing"] = [{"file": n, "size": sizes.get(n, 0)} for n in names if n in sizes]
        c["size"] = sum(f["size"] for k in ("map", "dem", "contours", "search", "routing") for f in c.get(k, []))
json.dump(cat, open("work/catalog.json", "w"), ensure_ascii=False, indent=1)
PY
}

while IFS='|' read -r ID NAME PATHS RELID <&3; do
  NEED=()
  for part in map dem contours search; do
    case $part in map) f="$ID.mbtiles";; dem) f="$ID-dem.mbtiles";; contours) f="$ID-contours.mbtiles";; search) f="$ID-search.db";; esac
    if ! have "$f" || forced "$ID" || outdated "$ID" "$part"; then NEED+=("$part"); fi
  done
  # подъезды и метро собираются из выгрузки — если их ещё нет в каталоге, выгрузку тоже качаем
  python3 -c "import json,sys; c=next((c for c in json.load(open('work/catalog.json'))['countries'] if c['id']==sys.argv[1]),{}); sys.exit(0 if c.get('entrances') else 1)" "$ID" || NEED+=(extra)
  if [ ${#NEED[@]} -eq 0 ]; then log "$ID: всё собрано"; routing_part "$ID"; continue; fi
  log "$ID ($NAME): нужно ${NEED[*]}"
  needs() { [[ " ${NEED[*]} " == *" $1 "* ]]; }

  # граница и (если нужно) выгрузка OSM
  PBFS=(); POLYS=()
  IFS='+' read -ra PARTS <<<"$PATHS"
  for P in "${PARTS[@]}"; do
    F="work/$(basename "$P")"
    curl -sSL --retry 3 -o "$F.poly" "https://download.geofabrik.de/$P.poly"; POLYS+=("$F.poly")
    if needs map || needs search || needs extra; then
      curl -sSL --retry 3 -o "$F.osm.pbf" "https://download.geofabrik.de/$P-latest.osm.pbf" || { log "ОШИБКА: скачать $P"; continue 2; }
      PBFS+=("$F.osm.pbf"); log "$ID: скачано $P $(du -h "$F.osm.pbf" | cut -f1)"
    fi
  done
  # Крым: если граница Geofabrik его не включает — докачиваем и склеиваем
  if [ "$ID" = ukraine ] && ! python3 -c "import sys; sys.path.insert(0,'.'); from poly import to_geojson, contains; sys.exit(0 if contains(to_geojson(['work/ukraine.poly']), 34.16, 44.50) else 1)"; then
    curl -sSL -o work/crimea.poly https://download.geofabrik.de/russia/crimean-fed-district.poly && POLYS+=(work/crimea.poly) && log "$ID: Крым в границе"
    if needs map || needs search || needs extra; then
      curl -sSL -o work/crimea.osm.pbf https://download.geofabrik.de/russia/crimean-fed-district-latest.osm.pbf && PBFS+=(work/crimea.osm.pbf) && log "$ID: добавлен Крым"
    fi
  fi
  python3 poly.py "$OUT/$ID.json" "${POLYS[@]}"
  python3 - "$OUT/$ID.json" "$NAME" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["name"] = sys.argv[2]; json.dump(d, open(sys.argv[1], "w"), separators=(",", ":"))
PY
  PBF=""
  if [ ${#PBFS[@]} -gt 1 ]; then osmium merge "${PBFS[@]}" -o "work/$ID-merged.osm.pbf" --overwrite && PBF="work/$ID-merged.osm.pbf"; elif [ ${#PBFS[@]} -eq 1 ]; then PBF="${PBFS[0]}"; fi
  # точная граница страны (relation admin_level=2): по ней режем данные — за границей карта онлайн
  if [ -n "${RELID:-}" ] && [ -n "$PBF" ]; then
    if osmium getid -r "$PBF" "r$RELID" -o work/border.osm.pbf --overwrite 2>>work/osmium.log \
       && osmium export work/border.osm.pbf -f geojson --geometry-types=polygon -o work/border.geojson --overwrite 2>>work/osmium.log \
       && python3 border.py work/border.geojson "$OUT/$ID.json" "$NAME" >> "$REPORT" 2>&1 \
       && mv "$OUT/$ID-clip.geojson" work/border-clean.geojson \
       && osmium extract -p work/border-clean.geojson -s smart "$PBF" -o "work/$ID-clip.osm.pbf" --overwrite 2>>work/osmium.log \
       && [ $(stat -c %s "work/$ID-clip.osm.pbf") -gt 50000000 ]; then
      PBF="work/$ID-clip.osm.pbf"; log "$ID: обрезано по границе $(du -h "$PBF" | cut -f1)"
    else log "ОШИБКА: граница r$RELID — оставляю выгрузку Geofabrik"; tail -5 work/osmium.log >> "$REPORT"; fi
  fi
  # подъезды: входы с номерами (в схеме OpenMapTiles их нет) — отдельный маленький слой
  if [ -n "$PBF" ]; then
    osmium tags-filter "$PBF" n/entrance -o work/entr.osm.pbf --overwrite 2>>work/osmium.log \
      && osmium export work/entr.osm.pbf -f geojsonseq -o - 2>>work/osmium.log | python3 entrances.py "$OUT/$ID-entrances.mbtiles" >> "$REPORT" 2>&1 \
      && log "$ID: подъезды $(du -h "$OUT/$ID-entrances.mbtiles" | cut -f1)" || log "ОШИБКА: подъезды"
  fi
  # метро: линии с цветами и станции
  if [ -n "$PBF" ]; then
    osmium tags-filter "$PBF" r/route=subway,light_rail n/station=subway -o work/metro.osm.pbf --overwrite 2>>work/osmium.log \
      && python3 metro.py work/metro.osm.pbf "$OUT/$ID-metro.json" >> "$REPORT" 2>&1 && log "$ID: метро $(du -h "$OUT/$ID-metro.json" | cut -f1)" || log "ОШИБКА: метро"
  fi

  BUILT=()
  if needs map && [ $COAST_WATER = 1 ]; then
    log "$ID: море из береговой линии OSM"
    rm -rf work/cw && mkdir -p work/cw
    if osmium tags-filter "$PBF" w/natural=coastline -o work/coast.osm.pbf --overwrite \
       && osmium export work/coast.osm.pbf -f geojsonseq -o - 2>>work/osmium.log | python3 coast_water.py "$OUT/$ID.json" work/cw >> "$REPORT" 2>&1 \
       && (cd work/cw && zip -q -r ../water-$ID.zip .); then WATER=work/water-$ID.zip
    else log "ОШИБКА: море из берега — карта будет без моря на крупных масштабах"; WATER=work/none.zip; fi
  fi
  if needs map; then
    log "$ID: векторная карта (Planetiler)"
    if java -Xmx12g -jar $PLANETILER --osm-path="$PBF" --water_polygons_path="$WATER" --output="$OUT/$ID.mbtiles" --force \
       --languages=ru,uk,en --maxzoom=14 --render_maxzoom=14 --tmpdir=work/tmp >> work/planetiler-$ID.log 2>&1; then
      log "$ID: карта $(du -h $OUT/$ID.mbtiles | cut -f1)"; BUILT+=(map)
    else log "ОШИБКА: Planetiler"; tail -40 work/planetiler-$ID.log >> "$REPORT"; fi
  fi

  if needs search; then
    log "$ID: офлайн-поиск"
    if osmium tags-filter "$PBF" nwr/name nwr/addr:housenumber -o work/named.osm.pbf --overwrite \
       && osmium export work/named.osm.pbf -f geojsonseq --geometry-types=point,linestring,polygon -o - 2>>work/osmium.log \
          | python3 search.py "$OUT/$ID-search.db" >> "$REPORT" 2>&1; then
      log "$ID: поиск $(du -h $OUT/$ID-search.db | cut -f1)"; BUILT+=(search)
    else log "ОШИБКА: поиск"; tail -5 work/osmium.log >> "$REPORT"; fi
    rm -f work/named.osm.pbf
  fi

  if needs dem; then
    log "$ID: рельеф"
    rm -rf work/tif
    if python3 dem.py "$OUT/$ID.json" "$OUT/$ID-dem.mbtiles" work/tif 11 99 >> "$REPORT" 2>&1; then
      log "$ID: рельеф $(du -h $OUT/$ID-dem.mbtiles | cut -f1)"; BUILT+=(dem)
    else log "ОШИБКА: рельеф"; fi
    rm -rf work/tif
  fi
  if needs contours; then
    log "$ID: линии высот"
    DEMF="$OUT/$ID-dem.mbtiles"
    [ -f "$DEMF" ] || { gh release download "$REL" -p "$ID-dem.mbtiles" -D work --clobber && DEMF="work/$ID-dem.mbtiles"; }
    if python3 contours_tiles.py "$DEMF" "$OUT/$ID-contours.mbtiles" >> work/contours.log 2>&1; then
      log "$ID: линии высот $(du -h $OUT/$ID-contours.mbtiles | cut -f1)"; BUILT+=(contours)
    else log "ОШИБКА: линии высот"; tail -20 work/contours.log >> "$REPORT"; fi
    rm -f "work/$ID-dem.mbtiles"
  fi
  rm -rf work/*.osm.pbf work/tmp

  # большие файлы режем на части по 1.9 ГБ (лимит GitHub — 2 ГБ на файл)
  for f in "$OUT/$ID.mbtiles" "$OUT/$ID-dem.mbtiles" "$OUT/$ID-contours.mbtiles" "$OUT/$ID-search.db"; do
    [ -f "$f" ] || continue
    if [ $(stat -c %s "$f") -gt 1900000000 ]; then split -b 1900000000 -d -a 2 "$f" "$f.part" && rm "$f"; fi
  done
  python3 - "$ID" "$NAME" "${BUILT[*]:-}" "map=${PART_VER[map]} dem=${PART_VER[dem]} contours=${PART_VER[contours]} search=${PART_VER[search]}" <<'PY'
import json, os, glob, sys, datetime
cid, name, built = sys.argv[1], sys.argv[2], sys.argv[3].split()
VER = dict(kv.split("=") for kv in sys.argv[4].split())
VER = {k: int(v) for k, v in VER.items()}
cat = json.load(open("work/catalog.json"))
old = next((c for c in cat["countries"] if c["id"] == cid), {})
names = {"map": f"{cid}.mbtiles", "dem": f"{cid}-dem.mbtiles", "contours": f"{cid}-contours.mbtiles", "search": f"{cid}-search.db"}
def files(base):
    parts = sorted(glob.glob(f"out/{base}.part*")) or ([f"out/{base}"] if os.path.exists(f"out/{base}") else [])
    return [{"file": os.path.basename(p), "size": os.path.getsize(p)} for p in parts]
meta = json.load(open(f"out/{cid}.json"))
entry = dict(old); entry.update({"id": cid, "name": name, "bbox": meta["bbox"], "shape": f"{cid}.json"})
if os.path.exists(f"out/{cid}-metro.json"): entry["metro"] = f"{cid}-metro.json"
if os.path.exists(f"out/{cid}-entrances.mbtiles"): entry["entrances"] = f"{cid}-entrances.mbtiles"
for k in names:
    if k in built: entry[k] = files(names[k]); entry[k + "_built"] = datetime.date.today().isoformat(); entry[k + "_ver"] = VER[k]
    else: entry.setdefault(k, [])
entry["built"] = max([entry.get(k + "_built", "") for k in names] + [old.get("built", "")])
entry["size"] = sum(f["size"] for k in names for f in entry.get(k, []))
cat["countries"] = [c for c in cat["countries"] if c["id"] != cid] + [entry]
json.dump(cat, open("work/catalog.json", "w"), ensure_ascii=False, indent=1)
PY
  shopt -s nullglob; FILES=("$OUT/$ID.json" $OUT/$ID-metro.json $OUT/$ID-entrances.mbtiles $OUT/$ID.mbtiles* $OUT/$ID-*.mbtiles* $OUT/$ID-search.db*); shopt -u nullglob
  up "${FILES[@]}"
  rm -f $OUT/$ID.mbtiles* $OUT/$ID-*.mbtiles* $OUT/$ID-search.db*

  routing_part "$ID"
done 3< <(grep -v '^#' countries.txt | grep -v '^\s*$')

python3 - <<'PY'
import json, os, subprocess
cat = json.load(open("work/catalog.json"))
assets = {a["name"]: a["size"] for a in json.loads(subprocess.run(["gh", "release", "view", "maps-v1", "--json", "assets"], capture_output=True, text=True).stdout or '{"assets":[]}')["assets"]}
for k, f in (("world", "world-relief.mbtiles"), ("labels", "world-labels.mbtiles")):
    if os.path.exists(f"out/{f}"):
        cat[k] = {"file": f, "size": os.path.getsize(f"out/{f}")}
        if k == "world": cat["world_ver"] = 2
        if k == "labels": cat["labels_ver"] = 2
    elif f in assets: cat[k] = {"file": f, "size": assets[f]}
json.dump(cat, open("out/catalog.json", "w"), ensure_ascii=False, indent=1)
PY
up $OUT/catalog.json
log "готово"
gh release upload "$REL" "$REPORT" --clobber
