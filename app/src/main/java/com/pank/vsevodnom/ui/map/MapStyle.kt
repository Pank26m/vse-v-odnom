package com.pank.vsevodnom.ui.map

import android.content.Context
import com.pank.vsevodnom.data.OnlineMaps

/**
 * Настройки вида карты GLMap: способ передвижения (City/Outdoor + Car/Hike/Bike), слои рельефа, онлайн-подложка,
 * группы объектов «Объекты на карте» (опции стандартного стиля GLMap + наши: пешие/вело маршруты).
 */
object MapStyle {
    enum class Variant(val key: String, val title: String) { CAR("car", "На машине"), FOOT("foot", "Пешком"), BIKE("bike", "Велосипед") }

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
    )
    /** Правила скрытия по группам: в стиль попадают только правила выключенных групп (последними — перекрывают стандартный). */
    private val HIDE_RULES = mapOf(
        "l_districts" to "node, area { [place=suburb], [place=quarter], [place=neighbourhood], [place=city_block], [place=locality], [place=borough] { text: none; } }",
        "l_streets" to "line { [highway] { text: none; } }\narea { [highway=pedestrian], [highway=footway], [place=square] { text: none; } }",
        "l_water" to "node, area { [natural=water], [water], [waterway], [place=sea], [place=ocean], [place=bay], [natural=bay], [natural=strait] { text: none; } }\nline { [waterway] { text: none; } }",
        "l_countries" to "node, area { [place=country], [place=state], [place=region], [place=province] { text: none; } }",
        "metro" to "node, area { [station=subway], [railway=subway_entrance] { icon-image: none; text: none; } }\nline { [railway=subway] { width: none; dashes-width: none; } }",
    )
    fun hideCss(hidden: Set<String>): String = HIDE_RULES.filterKeys { it in hidden }.values.joinToString("\n", postfix = "\n")

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
