package com.pank.vsevodnom.data

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
        "gate" to "Ворота / калитка", "lift_gate" to "Шлагбаум", "bollard" to "Столбики", "cycle_barrier" to "Барьер", "stile" to "Перелаз", "kissing_gate" to "Калитка",
        "sally_port" to "Арка-проезд", "toll_booth" to "Пункт оплаты", "border_control" to "Пограничный пункт", "waste_basket" to "Урна", "recycling" to "Сбор вторсырья",
        "shelter" to "Навес / беседка", "drinking_water" to "Питьевая вода", "water_point" to "Вода", "fountain" to "Фонтан", "information" to "Информация",
        "map" to "Карта-схема", "board" to "Инфостенд", "guidepost" to "Указатель", "bench" to "Лавочка", "bbq" to "Мангал", "picnic_table" to "Столик для пикника",
        "picnic_site" to "Место для пикника", "chess" to "Шахматы", "telephone" to "Телефон", "post_box" to "Почтовый ящик", "vending_machine" to "Автомат",
        "clock" to "Часы", "bicycle_parking" to "Велопарковка", "viewpoint" to "Смотровая площадка", "parcel_locker" to "Почтомат", "post_office" to "Почта",
        "subway_entrance" to "Вход в метро", "subway" to "Станция метро", "pitch" to "Спортплощадка", "soccer" to "Футбольное поле", "tennis" to "Теннисный корт",
        "basketball" to "Баскетбольная площадка", "multi" to "Спортплощадка", "garden" to "Сад", "artwork" to "Арт-объект", "company" to "Офис",
        "government" to "Госучреждение", "diplomatic" to "Посольство", "clinic" to "Клиника", "supermarket" to "Супермаркет", "convenience" to "Магазин у дома",
        "kindergarten" to "Детский сад", "university" to "Университет", "swimming_pool" to "Бассейн", "camp_site" to "Кемпинг", "bus_stop" to "Остановка",
        "tram_stop" to "Трамвайная остановка", "memorial" to "Мемориал", "kiosk" to "Киоск", "office" to "Офис", "pub" to "Паб", "hotel" to "Гостиница",
        "hostel" to "Хостел", "guest_house" to "Гостевой дом", "dormitory" to "Общежитие", "community_centre" to "Дом культуры", "courthouse" to "Суд",
        "christian" to "Храм", "muslim" to "Мечеть", "jewish" to "Синагога", "charging_station" to "Зарядка электромобилей", "car_wash" to "Автомойка",
        "car_repair" to "Автосервис", "car_parts" to "Автозапчасти", "clothes" to "Одежда", "beauty" to "Салон красоты", "florist" to "Цветы",
    )
    fun kind(k: String): String = M[k] ?: CLASS[k.substringBefore(':')] ?: ""
    fun omt(cls: String?, sub: String?): String = (sub?.let { OMT[it] } ?: cls?.let { OMT[it] } ?: sub?.replace('_', ' ') ?: cls?.replace('_', ' ') ?: "").replaceFirstChar { it.uppercase() }
}
