package com.pank.vsevodnom.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Поиск без интернета по файлам <страна>-search.db (собираются maps/search.py из OpenStreetMap):
 * населённые пункты, улицы, заведения и адреса домов. Ищется по началу каждого слова, «Хрещатик 22» — дом на улице.
 */
object OfflineSearch {
    private val WORD = Regex("[0-9a-zа-яёіїєґ]+")
    private val dbs = HashMap<String, SQLiteDatabase>()

    fun norm(s: String): List<String> = WORD.findAll(s.lowercase().replace('ё', 'е').replace("'", "").replace("’", "").replace("ʼ", "")).map { it.value }.toList()

    private fun db(ctx: Context, id: String): SQLiteDatabase? = synchronized(dbs) {
        dbs[id]?.takeIf { it.isOpen } ?: run {
            val f = OfflineMaps.file(ctx, id, "search")
            if (!f.exists()) return null
            runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) }.getOrNull()?.also { dbs[id] = it }
        }
    }

    fun closeAll() = synchronized(dbs) { dbs.values.forEach { runCatching { it.close() } }; dbs.clear() }

    fun available(ctx: Context) = OfflineMaps.installed(ctx).filterValues { "search" in it.second }.keys

    suspend fun search(ctx: Context, q: String, nearLat: Double, nearLon: Double, limit: Int = 40): List<GeoHit> = withContext(Dispatchers.IO) {
        val words = norm(q)
        if (words.isEmpty()) return@withContext emptyList()
        val out = ArrayList<Pair<Double, GeoHit>>()
        val k = cos(Math.toRadians(nearLat))
        fun dist(lat: Double, lon: Double) = hypot((lat - nearLat) * 111.0, (lon - nearLon) * 111.0 * k)
        for (id in available(ctx)) {
            val d = db(ctx, id) ?: continue
            // номер дома — последнее слово с цифрой, если до него есть слова
            val house = words.last().takeIf { w -> words.size > 1 && w.any { it.isDigit() } }
            val nameWords = if (house != null) words.dropLast(1) else words
            var ids: Set<Long>? = null
            for (w in nameWords.sortedByDescending { it.length }) {
                val set = HashSet<Long>()
                d.rawQuery("SELECT fid FROM w WHERE word >= ? AND word < ? LIMIT 4000", arrayOf(w, w + "￿")).use { c -> while (c.moveToNext()) set += c.getLong(0) }
                ids = ids?.intersect(set) ?: set
                if (ids.isEmpty()) break
            }
            val found = ids ?: continue
            if (found.isEmpty()) continue
            val rows = ArrayList<Array<Any>>()
            found.chunked(900).forEach { chunk ->
                d.rawQuery("SELECT id, name, kind, lat, lon, place, rank FROM f WHERE id IN (${chunk.joinToString(",")})", null).use { c ->
                    while (c.moveToNext()) rows += arrayOf(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4), c.getString(5), c.getInt(6))
                }
            }
            for (r in rows) {
                val lat = r[3] as Double; val lon = r[4] as Double; val kind = r[2] as String
                val km = dist(lat, lon)
                val score = (r[6] as Int) * 3.0 + kotlin.math.ln(1 + km) * 2
                // у городов «ближайший пункт» — их же пригород, не показываем
                val near = if (kind.startsWith("place:") && (r[6] as Int) <= 3) "" else r[5] as String
                val sub = listOf(MapNames.kind(kind), near).filter { it.isNotBlank() }.joinToString(" · ")
                if (house != null && kind.startsWith("highway:")) {
                    d.rawQuery("SELECT num, lat, lon FROM a WHERE street = ? AND (num = ? OR num LIKE ?) LIMIT 5", arrayOf((r[0] as Long).toString(), house, "$house%")).use { c ->
                        while (c.moveToNext()) {
                            val num = c.getString(0)
                            out += (score - 10 + (if (num.equals(house, true)) 0.0 else 1.0)) to GeoHit("${r[1]}, $num", listOf("Дом", r[5] as String).filter { it.isNotBlank() }.joinToString(" · "), c.getDouble(1), c.getDouble(2), "addr")
                        }
                    }
                }
                out += score to GeoHit(r[1] as String, sub, lat, lon, kind)
            }
        }
        // один и тот же объект часто есть несколькими точками (дом-здание и адрес-узел) — склеиваем в пределах ~300 м
        out.sortedBy { it.first }.map { it.second }.distinctBy { "${it.name}|${it.kind}|${(it.lat * 300).toInt()}|${(it.lon * 300).toInt()}" }.take(limit)
    }
}

/** Русские названия видов объектов OpenStreetMap / OpenMapTiles. */
object MapNames {
    private val M = mapOf(
        "place:city" to "Город", "place:town" to "Город", "place:village" to "Село", "place:hamlet" to "Посёлок", "place:suburb" to "Район",
        "place:neighbourhood" to "Микрорайон", "place:quarter" to "Квартал", "place:locality" to "Местность", "place:isolated_dwelling" to "Хутор",
        "amenity:cafe" to "Кафе", "amenity:restaurant" to "Ресторан", "amenity:fast_food" to "Фастфуд", "amenity:bar" to "Бар", "amenity:pub" to "Паб",
        "amenity:pharmacy" to "Аптека", "amenity:hospital" to "Больница", "amenity:clinic" to "Клиника", "amenity:doctors" to "Врач", "amenity:dentist" to "Стоматология",
        "amenity:bank" to "Банк", "amenity:atm" to "Банкомат", "amenity:fuel" to "АЗС", "amenity:parking" to "Парковка", "amenity:school" to "Школа",
        "amenity:kindergarten" to "Детский сад", "amenity:university" to "Университет", "amenity:college" to "Колледж", "amenity:library" to "Библиотека",
        "amenity:post_office" to "Почта", "amenity:police" to "Полиция", "amenity:fire_station" to "Пожарная часть", "amenity:place_of_worship" to "Храм",
        "amenity:theatre" to "Театр", "amenity:cinema" to "Кинотеатр", "amenity:townhall" to "Администрация", "amenity:marketplace" to "Рынок",
        "amenity:bus_station" to "Автовокзал", "amenity:car_wash" to "Автомойка", "amenity:veterinary" to "Ветклиника", "amenity:toilets" to "Туалет",
        "amenity:charging_station" to "Зарядка электромобилей", "amenity:shelter" to "Укрытие", "amenity:community_centre" to "Дом культуры",
        "shop:supermarket" to "Супермаркет", "shop:convenience" to "Магазин у дома", "shop:mall" to "Торговый центр", "shop:clothes" to "Одежда",
        "shop:bakery" to "Пекарня", "shop:butcher" to "Мясной", "shop:hardware" to "Хозтовары", "shop:doityourself" to "Стройматериалы", "shop:electronics" to "Электроника",
        "shop:mobile_phone" to "Телефоны", "shop:car_repair" to "Автосервис", "shop:car" to "Автосалон", "shop:hairdresser" to "Парикмахерская", "shop:beauty" to "Салон красоты",
        "shop:florist" to "Цветы", "shop:books" to "Книги", "shop:shoes" to "Обувь", "shop:alcohol" to "Алкоголь", "shop:kiosk" to "Киоск", "shop:pet" to "Зоотовары",
        "tourism:hotel" to "Гостиница", "tourism:hostel" to "Хостел", "tourism:museum" to "Музей", "tourism:attraction" to "Достопримечательность",
        "tourism:viewpoint" to "Смотровая площадка", "tourism:camp_site" to "Кемпинг", "tourism:information" to "Информация", "tourism:artwork" to "Арт-объект",
        "tourism:guest_house" to "Гостевой дом", "tourism:apartment" to "Апартаменты", "tourism:zoo" to "Зоопарк", "tourism:picnic_site" to "Место для пикника",
        "leisure:park" to "Парк", "leisure:playground" to "Детская площадка", "leisure:pitch" to "Спортплощадка", "leisure:stadium" to "Стадион",
        "leisure:sports_centre" to "Спорткомплекс", "leisure:fitness_centre" to "Фитнес", "leisure:swimming_pool" to "Бассейн", "leisure:garden" to "Сад",
        "historic:monument" to "Памятник", "historic:memorial" to "Мемориал", "historic:castle" to "Замок", "historic:ruins" to "Руины", "historic:church" to "Церковь",
        "railway:station" to "Вокзал", "railway:halt" to "Платформа", "railway:subway_entrance" to "Вход в метро", "railway:tram_stop" to "Трамвайная остановка",
        "public_transport:station" to "Станция", "public_transport:platform" to "Остановка", "aeroway:aerodrome" to "Аэропорт",
        "natural:peak" to "Вершина", "natural:water" to "Водоём", "natural:wood" to "Лес", "natural:spring" to "Родник", "natural:beach" to "Пляж",
        "waterway:river" to "Река", "waterway:stream" to "Ручей", "office:company" to "Офис", "office:government" to "Госучреждение",
        "highway:residential" to "Улица", "highway:primary" to "Улица", "highway:secondary" to "Улица", "highway:tertiary" to "Улица", "highway:living_street" to "Улица",
        "highway:pedestrian" to "Пешеходная улица", "highway:unclassified" to "Дорога", "highway:service" to "Проезд", "highway:trunk" to "Трасса", "highway:motorway" to "Автомагистраль",
        "highway:bus_stop" to "Остановка",
    )
    private val CLASS = mapOf(
        "amenity" to "Заведение", "shop" to "Магазин", "tourism" to "Туризм", "leisure" to "Отдых", "historic" to "История", "office" to "Офис", "craft" to "Мастерская",
        "healthcare" to "Медицина", "railway" to "Железная дорога", "building" to "Здание", "natural" to "Природа", "waterway" to "Вода", "sport" to "Спорт", "man_made" to "Сооружение",
    )
    /** OpenMapTiles poi: class/subclass. */
    private val OMT = mapOf(
        "cafe" to "Кафе", "restaurant" to "Ресторан", "fast_food" to "Фастфуд", "bar" to "Бар", "beer" to "Паб", "pharmacy" to "Аптека", "hospital" to "Больница",
        "doctors" to "Врач", "dentist" to "Стоматология", "bank" to "Банк", "fuel" to "АЗС", "parking" to "Парковка", "school" to "Школа", "college" to "Колледж",
        "library" to "Библиотека", "post" to "Почта", "police" to "Полиция", "place_of_worship" to "Храм", "theatre" to "Театр", "cinema" to "Кинотеатр",
        "town_hall" to "Администрация", "grocery" to "Продукты", "shop" to "Магазин", "clothing_store" to "Одежда", "bakery" to "Пекарня", "lodging" to "Гостиница",
        "museum" to "Музей", "attraction" to "Достопримечательность", "park" to "Парк", "playground" to "Детская площадка", "stadium" to "Стадион", "monument" to "Памятник",
        "railway" to "Вокзал", "bus" to "Остановка", "airport" to "Аэропорт", "zoo" to "Зоопарк", "castle" to "Замок", "hairdresser" to "Парикмахерская",
        "car" to "Автосервис", "atm" to "Банкомат", "veterinary" to "Ветклиника", "fitness_centre" to "Фитнес", "swimming" to "Бассейн", "toilets" to "Туалет",
        "city" to "Город", "town" to "Город", "village" to "Село", "hamlet" to "Посёлок", "suburb" to "Район", "neighbourhood" to "Микрорайон",
    )
    fun kind(k: String): String = M[k] ?: CLASS[k.substringBefore(':')] ?: ""
    fun omt(cls: String?, sub: String?): String = (sub?.let { OMT[it] } ?: cls?.let { OMT[it] } ?: sub?.replace('_', ' ') ?: cls?.replace('_', ' ') ?: "").replaceFirstChar { it.uppercase() }
}
