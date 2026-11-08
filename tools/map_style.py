"""Готовит слои офлайн-стиля из OSM Liberty (BSD, © Maputnik/Mapbox) для приложения.

python3 tools/map_style.py path/to/osm-liberty/style.json app/src/main/assets/map
Пишет car.json / foot.json / bike.json — списки слоёв со источником «omt» и плейсхолдером имени «__NAME__».
Приложение размножает слои для каждой скачанной страны (MapStyle.kt).
После генерации фильтры переводятся в выражения (convertFilter из @maplibre/maplibre-gl-style-spec) — иначе их нельзя
дополнять выражениями (within, match).
"""
import copy, json, sys

FONTS = {"Roboto Regular": "Noto Sans Regular", "Roboto Medium": "Noto Sans Bold", "Roboto Condensed Italic": "Noto Sans Italic"}
DROP = {"natural_earth", "background"}


def base(style):
    out = []
    for l in style["layers"]:
        if l["id"] in DROP:
            continue
        l = copy.deepcopy(l)
        l["source"] = "omt"
        lay = l.get("layout", {})
        if "text-font" in lay:
            lay["text-font"] = [FONTS.get(f, "Noto Sans Regular") for f in lay["text-font"]]
        tf = lay.get("text-field")
        if isinstance(tf, str) and tf.startswith("{name"):
            lay["text-field"] = "__NAME__"
        out.append(l)
    return out


def find(layers, i):
    return next(l for l in layers if l["id"] == i)


def path_filter(brunnel, classes=("path", "pedestrian")):
    f = ["all", ["==", "$type", "LineString"], ["in", "class", *classes]]
    f.append(["==", "brunnel", brunnel] if brunnel else ["!in", "brunnel", "bridge", "tunnel"])
    return f


def foot(layers):
    """Пешком: тропы и грунтовки заметные (коричневый пунктир с z12), магистрали приглушены."""
    for i in ("road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian"):
        l = find(layers, i); l["minzoom"] = 12
        l["paint"]["line-color"] = "#b5502c"; l["paint"]["line-dasharray"] = [2, 1.2]
        l["paint"]["line-width"] = {"base": 1.2, "stops": [[12, 1], [14, 1.6], [20, 6]]}
    t = find(layers, "road_service_track")
    t["paint"]["line-color"] = ["match", ["get", "class"], "track", "#a0794c", "#fff"]
    t["paint"]["line-width"] = {"base": 1.2, "stops": [[12, 0.8], [14, 1.8], [20, 7.5]]}
    t["paint"]["line-dasharray"] = [3, 1]
    for i in ("road_motorway", "road_trunk_primary", "road_motorway_link"):
        find(layers, i)["paint"]["line-color"] = "#f2dfb8"
    return layers


def bike(layers):
    """Велосипед: велодорожки синие сплошные с z11, прочие тропы коричневым пунктиром."""
    for i in ("road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian"):
        l = find(layers, i); l["minzoom"] = 12
        l["paint"]["line-color"] = "#a0794c"; l["paint"]["line-dasharray"] = [2, 1.2]
        l["paint"]["line-width"] = {"base": 1.2, "stops": [[12, 0.8], [20, 5]]}
    idx = layers.index(find(layers, "road_path_pedestrian")) + 1
    layers.insert(idx, {
        "id": "road_cycleway", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 11,
        "filter": ["all", ["==", "$type", "LineString"], ["==", "subclass", "cycleway"]],
        "layout": {"line-cap": "round", "line-join": "round"},
        "paint": {"line-color": "#2f7fd8", "line-width": {"base": 1.3, "stops": [[11, 1.2], [14, 2.4], [20, 8]]}}})
    t = find(layers, "road_service_track")
    t["paint"]["line-color"] = ["match", ["get", "class"], "track", "#b08a5a", "#fff"]
    t["paint"]["line-width"] = {"base": 1.2, "stops": [[13, 0.8], [16, 2], [20, 7.5]]}
    return layers


def car(layers):
    """На машине: как Liberty, тропы тоньше и серее (не отвлекают)."""
    for i in ("road_path_pedestrian", "bridge_path_pedestrian", "tunnel_path_pedestrian"):
        l = find(layers, i); l["minzoom"] = 15
        l["paint"]["line-color"] = "#c9c3b8"
        l["paint"]["line-width"] = {"base": 1.2, "stops": [[15, 0.8], [20, 5]]}
    return layers


if __name__ == "__main__":
    style = json.load(open(sys.argv[1])); out = sys.argv[2]
    for name, fn in (("car", car), ("foot", foot), ("bike", bike)):
        json.dump(fn(base(style)), open(f"{out}/{name}.json", "w"), ensure_ascii=False, separators=(",", ":"))
        print(name, "ok")
