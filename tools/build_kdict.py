#!/usr/bin/env python3
"""
Словари проверки орфографии клавиатуры (assets/kdict/<lang>.kdict).

Источники (не в репозитории, скачать заново):
  - Hunspell из LibreOffice: github.com/LibreOffice/dictionaries — ru_RU (BSD, A. Lebedev), uk_UA (MPL 1.1, dict_uk),
    en/en_US (SCOWL) → развернуть все словоформы: `unmunch xx.dic xx.aff > xx.forms` (пакет hunspell-tools)
  - частоты слов: github.com/hermitdave/FrequencyWords (content/2018/<lang>/<lang>_full.txt, CC BY-SA 4.0)
Запуск: python3 -I tools/build_kdict.py <папка_с_исходниками> keyboard/src/main/assets/kdict

Формат .kdict (всё little-endian):
  "KDC1" | u8 N | N × u16 символов алфавита (байт b>0 → символ alphabet[b-1]) | u32 слов | u32 блоков |
  u32[блоков] смещения блоков от начала данных | данные.
  Блок (до 64 слов, отсортированы по байтам): первое слово: u8 len, байты, u8 freq; дальше: u8 общий префикс,
  u8 длина хвоста, хвост, u8 freq. freq — 0..255 по логарифму частоты (0 — нет в частотном списке).
"""
import math, os, struct, sys, collections

APOS = {"’": "'", "ʼ": "'", "`": "'"}

def norm(w):
    w = w.strip()
    if w.startswith("+") and "=" in w:
        w = w.split("=", 1)[1]
    for a, b in APOS.items():
        w = w.replace(a, b)
    return w.lower()

def build(lang, forms_path, freq_path, out_path, extra_min):
    words = set()
    with open(forms_path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            w = norm(line)
            if 1 <= len(w) <= 40 and any(ch.isalpha() for ch in w) and " " not in w:
                words.add(w)
    freq = {}
    with open(freq_path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            p = line.split()
            if len(p) != 2: continue
            w = norm(p[0]); c = int(p[1])
            if not w or not all(ch.isalpha() or ch in "'-" for ch in w): continue
            freq[w] = freq.get(w, 0) + c
            # разговорные слова, которых нет в Hunspell («щас», имена) — если встречаются часто
            if c >= extra_min and len(w) >= 2:
                words.add(w)
    cnt = collections.Counter()
    for w in words: cnt.update(w)
    alphabet = [ch for ch, _ in cnt.most_common(250)]
    idx = {ch: i + 1 for i, ch in enumerate(alphabet)}
    enc = []
    for w in words:
        if all(ch in idx for ch in w):
            enc.append(bytes(idx[ch] for ch in w))
    # порядок: по байтам; алфавит отсортирован так, чтобы байтовый порядок был стабилен (не обязательно алфавитный)
    enc.sort()
    mx = max(freq.values()) if freq else 1
    def fb(b):
        w = "".join(alphabet[x - 1] for x in b)
        c = freq.get(w, 0)
        return 0 if c <= 0 else max(1, min(255, int(255 * math.log(1 + c) / math.log(1 + mx))))
    blocks = []; data = bytearray(); B = 64
    for i in range(0, len(enc), B):
        blocks.append(len(data))
        chunk = enc[i:i + B]
        first = chunk[0]
        data += bytes([len(first)]) + first + bytes([fb(first)])
        prev = first
        for w in chunk[1:]:
            p = 0
            while p < min(len(prev), len(w), 255) and prev[p] == w[p]: p += 1
            tail = w[p:]
            data += bytes([p, len(tail)]) + tail + bytes([fb(w)])
            prev = w
    with open(out_path, "wb") as o:
        o.write(b"KDC1"); o.write(bytes([len(alphabet)]))
        for ch in alphabet: o.write(struct.pack("<H", ord(ch)))
        o.write(struct.pack("<II", len(enc), len(blocks)))
        for b in blocks: o.write(struct.pack("<I", b))
        o.write(data)
    print(lang, len(enc), "слов,", os.path.getsize(out_path) // 1024, "КБ")

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    # порог для разговорных слов не из Hunspell: ниже — в словарь попадают частые опечатки («helo» 580 раз в субтитрах)
    for lang, forms, extra in [("ru", "ru_RU", 300), ("uk", "uk_UA", 100), ("en", "en_US", 3000)]:
        build(lang, os.path.join(src, forms + ".forms"), os.path.join(src, lang + "_full.txt"), os.path.join(out, lang + ".kdict"), extra)
