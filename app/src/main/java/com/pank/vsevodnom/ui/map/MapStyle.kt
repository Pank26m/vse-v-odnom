package com.pank.vsevodnom.ui.map

import android.content.Context
import com.pank.vsevodnom.data.OnlineMaps

/**
 * Настройки вида карты GLMap: способ передвижения (City/Outdoor + Car/Hike/Bike), слои рельефа, онлайн-подложка,
 * группы объектов «Объекты на карте» (опции стандартного стиля GLMap + наши: пешие/вело маршруты).
 */
object MapStyle {
    enum class Variant(val key: String, val title: String) { CAR("car", "Город"), FOOT("foot", "Пешком"), BIKE("bike", "Велосипед") }

    data class Opts(
        val variant: Variant = Variant.CAR,
        val hillshade: Boolean = true,
        val contours: Boolean = true,
        val lang: String = "local",           // local | ru | en
        val online: OnlineMaps.Source? = null,
        val hidden: Set<String> = DEFAULT_HIDDEN,
        val worldRelief: Boolean = true,
    )

    /** Группа «Объектов на карте»: название, иконка Tabler, цвет кружка; option — опция стиля GLMap (показывать, если включена). */
    class Group(val title: String, val icon: String, val color: Int, val option: String)

    /** Группы значков стандартного стиля GLMap. */
    val GROUPS = linkedMapOf(
        "health" to Group("Больницы, клиники, аптеки", "medical-cross", 0xFFE53935.toInt(), "Medical"),
        "food" to Group("Рестораны, кафе, фастфуд", "tools-kitchen-2", 0xFFFB8C00.toInt(), "Food"),
        "bars" to Group("Бары и пабы", "glass-cocktail", 0xFFFB8C00.toInt(), "Bar"),
        "mall" to Group("Супермаркеты, ТЦ, рынки", "shopping-cart", 0xFF42A5F5.toInt(), "Shop"),
        "shop" to Group("Все остальные магазины", "shopping-bag", 0xFF42A5F5.toInt(), "AllShops"),
        "beauty" to Group("Салоны красоты, парикмахерские", "brush", 0xFFEC407A.toInt(), "Beauty"),
        "bank" to Group("Банки и банкоматы", "building-bank", 0xFF43A047.toInt(), "Financial"),
        "post" to Group("Почта и почтоматы", "package", 0xFF43A047.toInt(), "Postal"),
        "city" to Group("Госучреждения, полиция, посольства", "shield", 0xFF5C6BC0.toInt(), "CityService"),
        "edu" to Group("Школы, вузы, детсады", "school", 0xFF43A047.toInt(), "Education"),
        "culture" to Group("Музеи, памятники, храмы", "building-castle", 0xFF26A69A.toInt(), "Culture"),
        "fun" to Group("Кино, театры, развлечения", "movie", 0xFFFB8C00.toInt(), "Entertaiment"),
        "hotel" to Group("Гостиницы, хостелы, кемпинги", "bed", 0xFF7E57C2.toInt(), "Hotel"),
        "sport" to Group("Спорт и бассейны", "barbell", 0xFFFF7043.toInt(), "Sport"),
        "transit" to Group("Общественный транспорт", "bus", 0xFF78909C.toInt(), "PublicTransport"),
        "rail" to Group("Железная дорога и метро", "train", 0xFF78909C.toInt(), "Railway"),
        "bnames" to Group("Названия зданий", "home", 0xFF6D4C41.toInt(), "BuildingName"),
    )
    /** Наши группы: маршруты из OSM (пешие, вело) — в стиле пешком/вело рисуются поверх дорог. Опция — «скрыть». */
    val ROUTE_GROUPS = linkedMapOf(
        "hike" to Group("Пешие маршруты (туристические тропы)", "trekking", 0xFFD9534F.toInt(), "HideHiking"),
        "bike" to Group("Веломаршруты", "bike", 0xFF2E7D32.toInt(), "HideBike"),
    )
    /** Подписи и метро: наши опции «скрыть» (правило в hide_user.mapcss поверх стандартного стиля). */
    val LABEL_GROUPS = linkedMapOf(
        "l_districts" to Group("Названия районов и микрорайонов", "map", 0xFF8D6E63.toInt(), "HideDistricts"),
        "l_streets" to Group("Названия улиц и дорог", "road", 0xFF78909C.toInt(), "HideStreets"),
        "l_water" to Group("Названия рек, озёр, морей", "droplet", 0xFF42A5F5.toInt(), "HideWaterNames"),
        "l_countries" to Group("Названия стран и областей", "world", 0xFF5C6BC0.toInt(), "HideCountries"),
        "metro" to Group("Метро: линии, станции, входы", "train", 0xFFE53935.toInt(), "HideMetro"),
        "l_nature" to Group("Названия парков, заповедников, островов", "trees", 0xFF43A047.toInt(), "HideNature"),
        "l_shields" to Group("Номера трасс и дорог", "road-sign", 0xFF1E88E5.toInt(), "HideShields"),
    )
    /**
     * Служебное, чего нет в опциях стиля GLMap (рисуется всегда) — скрывается только нашими правилами.
     * Ключи — в том же наборе hidden.
     */
    val SERVICE_GROUPS = linkedMapOf(
        "x_car" to Group("Для машины: АЗС, зарядки, мойки, парковки, автосервисы", "gas-station", 0xFF546E7A.toInt(), ""),
        "x_peaks" to Group("Вершины гор и холмов, перевалы", "mountain", 0xFF8D6E63.toInt(), ""),
        "x_rest" to Group("Отдых: пикники, мангалы, смотровые, достопримечательности", "camera", 0xFF26A69A.toInt(), ""),
        "x_power" to Group("Линии электропередач и опоры", "bolt", 0xFF9E9E9E.toInt(), ""),
        "x_barrier" to Group("Заборы, калитки, шлагбаумы, входы", "fence", 0xFFE6A176.toInt(), ""),
    )
    /** Правила скрытия по группам: в стиль попадают только правила выключенных групп (последними — перекрывают стандартный). */
    private val HIDE_RULES = mapOf(
        "l_districts" to "node, area { [place=suburb], [place=quarter], [place=neighbourhood], [place=city_block], [place=locality], [place=borough], [landuse=residential] { text: none; } }",
        "l_streets" to "line { [highway] { text: none; } }\narea { [highway=pedestrian], [highway=footway], [place=square] { text: none; } }",
        "l_water" to "node, area { [natural=water], [water], [waterway], [place=sea], [place=ocean], [place=bay], [natural=bay], [natural=strait] { text: none; } }\nline { [waterway] { text: none; } }",
        "l_countries" to "node, area { [place=country], [place=state], [place=region], [place=province] { text: none; } }",
        "l_nature" to "node, area { [boundary=protected_area], [boundary=national_park], [leisure=nature_reserve], [leisure=park], [leisure=garden], [place=island], [place=islet] { text: none; } }",
        "metro" to "node, area { [station=subway], [railway=subway_entrance] { icon-image: none; text: none; } }\nline { [railway=subway] { width: none; dashes-width: none; } }",
    )
    /**
     * Значки групп «Объектов на карте» — те же теги, что в points.mapcss внутри «@if Опция». Скрытая группа выключается
     * опцией стиля, а ещё и явным правилом в конце стиля: если опция почему-то не сработала, значок всё равно пропадёт.
     * «Все магазины» ([shop]) и «Названия зданий» явным правилом не трогаем — они шире своих групп.
     */
    private val POI_TAGS = mapOf(
        "health" to "amenity=hospital building=hospital amenity=clinic amenity=doctors amenity=veterinary amenity=pharmacy amenity=dentist healthcare=laboratory",
        "food" to "amenity=fast_food amenity=cafe amenity=restaurant",
        "bars" to "amenity=bar amenity=pub",
        "mall" to "shop=supermarket shop=mall amenity=marketplace shop=department_store shop=convenience",
        "beauty" to "shop=hairdresser shop=beauty shop=tattoo shop=massage amenity=public_bath leisure=sauna",
        "bank" to "amenity=atm amenity=bank amenity=bureau_de_change",
        "post" to "amenity=post_office amenity=parcel_locker",
        "city" to "amenity=townhall office=government office=diplomatic amenity=police",
        "edu" to "amenity=university amenity=college amenity=school amenity=kindergarten amenity=library",
        "culture" to "amenity=arts_centre amenity=community_centre tourism=museum historic=monument historic=memorial amenity=place_of_worship amenity=fountain",
        "fun" to "amenity=nightclub amenity=cinema amenity=theatre tourism=zoo tourism=theme_park leisure=water_park",
        "hotel" to "tourism=hotel tourism=hostel tourism=motel tourism=guest_house tourism=chalet tourism=alpine_hut tourism=camp_site tourism=caravan_site",
        "sport" to "leisure=stadium amenity=swimming_pool leisure=swimming_pool leisure=sports_centre leisure=fitness_centre",
        "transit" to "amenity=bus_station highway=bus_stop railway=tram_stop",
        "rail" to "railway=station railway=halt building=train_station",
    )
    /** Правила «служебных» групп и номеров трасс — отдельный уровень: если движок их не примет, остальное скрытие работает. */
    private val EXTRA_RULES = mapOf(
        "x_car" to poiRule("amenity=fuel amenity=charging_station amenity=car_wash amenity=parking amenity=parking_entrance amenity=car_rental amenity=car_sharing shop=car_repair shop=car shop=car_parts shop=motorcycle shop=tyres"),
        "x_peaks" to poiRule("natural=peak natural=volcano natural=saddle mountain_pass=yes natural=hill"),
        "x_rest" to poiRule("tourism=picnic_site leisure=picnic_table leisure=firepit amenity=bbq tourism=viewpoint tourism=attraction"),
        "x_power" to "line { [power=line], [power=minor_line] { width: none; } }\n" + poiRule("power=tower power=pole"),
        "x_barrier" to "area, line { [barrier] { width: none; } }\n" + "node, area { [barrier], [entrance] { icon-image: none; text: none; } }",
        "l_shields" to "line { [highway] { shield-image: none; shield-text: none; } }",
    )
    private fun poiRule(tags: String) = "node, area { " + tags.split(' ').joinToString(", ") { "[$it]" } + " { icon-image: none; text: none; } }"

    /** Правила скрытия: подписи/метро + значки скрытых групп. pois=false — без значков (запасной вариант, если стиль не разобрался). */
    fun hideCss(hidden: Set<String>, pois: Boolean = true, extra: Boolean = true): String {
        val out = HIDE_RULES.filterKeys { it in hidden }.values.toMutableList()
        if (extra) EXTRA_RULES.forEach { (k, r) -> if (k in hidden) out += r }
        if (pois) POI_TAGS.forEach { (k, t) -> if (k in hidden) out += poiRule(t) }
        if (pois && "shop" in hidden && "mall" in hidden) out += poiRule("shop")
        return out.joinToString("\n", postfix = "\n")
    }

    /** По умолчанию маршруты из OSM скрыты (владельцу почти всегда не нужны). */
    val DEFAULT_HIDDEN = setOf("hike", "bike")

    fun hidden(ctx: Context): Set<String> = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getStringSet("gl_hidden", DEFAULT_HIDDEN)?.toSet() ?: DEFAULT_HIDDEN
    fun setHidden(ctx: Context, h: Set<String>) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putStringSet("gl_hidden", h).apply()

    fun opts(ctx: Context): Opts {
        val p = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
        val online = p.getString("online_id", null)?.let { id -> OnlineMaps.all(ctx).firstOrNull { it.id == id } }
        return Opts(
            Variant.entries.firstOrNull { it.key == p.getString("variant", "car") } ?: Variant.CAR,
            p.getBoolean("hillshade", true), p.getBoolean("contours", true),
            p.getString("lang", "local") ?: "local", if (p.getBoolean("use_online", false)) online else null,
            hidden(ctx), p.getBoolean("world_relief", true),
        )
    }
}
