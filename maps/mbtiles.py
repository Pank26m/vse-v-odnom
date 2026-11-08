"""Минимальная запись MBTiles (SQLite): метаданные + тайлы в схеме TMS."""
import sqlite3

class MBTiles:
    def __init__(self, path, meta: dict):
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA journal_mode=OFF"); self.db.execute("PRAGMA synchronous=OFF")
        self.db.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        self.db.execute("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
        self.db.execute("DELETE FROM metadata")
        self.db.executemany("INSERT INTO metadata VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
        self.n = 0

    def put(self, z, x, y, data: bytes):
        self.db.execute("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", (z, x, (1 << z) - 1 - y, sqlite3.Binary(data)))
        self.n += 1
        if self.n % 500 == 0: self.db.commit()

    def close(self):
        self.db.commit(); self.db.execute("VACUUM"); self.db.close()
