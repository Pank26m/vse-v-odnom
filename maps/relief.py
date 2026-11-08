"""Рельеф карты мира (как в Guru/Maps.me): цвет по высоте и глубине + тень склонов.
Вход — тайл Terrarium (AWS elevation-tiles-prod), выход — RGB-картинка 256×256."""
import io, math
import numpy as np
from PIL import Image

# (высота м, цвет) — суша: зелень → жёлтый → охра → коричневый → серый → снег; море: светлое у берега → тёмное в глубине
LAND = [(0, (172, 208, 152)), (150, (190, 218, 160)), (400, (222, 226, 170)), (900, (232, 214, 160)),
        (1500, (214, 184, 136)), (2500, (190, 160, 128)), (3500, (176, 170, 168)), (5000, (236, 236, 238)), (8800, (255, 255, 255))]
SEA = [(0, (176, 222, 246)), (-50, (160, 212, 242)), (-200, (138, 196, 234)), (-1000, (116, 178, 224)),
       (-3000, (98, 160, 212)), (-6000, (84, 142, 198)), (-11000, (70, 120, 180))]

def ramp(v, table):
    xs = np.array([t[0] for t in table], dtype=np.float32)
    cs = np.array([t[1] for t in table], dtype=np.float32)
    order = np.argsort(xs)
    xs, cs = xs[order], cs[order]
    return np.stack([np.interp(v, xs, cs[:, k]) for k in range(3)], axis=-1)

def decode(png: bytes) -> np.ndarray:
    a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"), dtype=np.float32)
    return a[..., 0] * 256 + a[..., 1] + a[..., 2] / 256 - 32768

def render(png: bytes, z: int, y: int) -> Image.Image:
    e = decode(png)
    n = 2 ** z
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / n))))
    px = 40075016 * math.cos(math.radians(lat)) / (n * 256)          # метров на пиксель
    exag = 4.0 + max(0, 6 - z) * 3.0                                   # на мелких масштабах рельеф подчёркиваем сильнее
    land = e > 0
    rgb = np.where(land[..., None], ramp(e, LAND), ramp(e, SEA))
    # тень: свет с северо-запада
    p = np.pad(e, 1, mode="edge")
    dzdx = (p[1:-1, 2:] - p[1:-1, :-2]) / (2 * px) * exag
    dzdy = (p[2:, 1:-1] - p[:-2, 1:-1]) / (2 * px) * exag
    slope = np.arctan(np.hypot(dzdx, dzdy))
    aspect = np.arctan2(dzdy, -dzdx)
    az, alt = math.radians(315), math.radians(45)
    shade = np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - math.pi / 2 - aspect)
    shade = np.clip(shade, 0, 1)
    # полярные льды: ближе к полюсам суша белеет
    lats = np.degrees(np.arctan(np.sinh(np.pi * (1 - 2 * (y * 256 + np.arange(256) + 0.5) / (n * 256)))))
    ice = np.clip((np.abs(lats) - 62) / 10, 0, 0.8)[:, None] * land
    rgb = rgb * (1 - ice[..., None]) + np.array([242, 245, 248], dtype=np.float32) * ice[..., None]
    k = np.where(land, 0.55 + 0.6 * shade, 0.85 + 0.2 * shade)        # море — почти без тени
    out = np.clip(rgb * k[..., None], 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGB")
