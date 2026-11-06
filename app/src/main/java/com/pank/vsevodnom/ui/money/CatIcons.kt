package com.pank.vsevodnom.ui.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.pank.vsevodnom.data.Category

/** Яркие цвета категорий (как в финансовых приложениях) — индекс хранится в Category.tint. */
val CAT_COLORS = listOf(
    Color(0xFFAD1457), Color(0xFF43A047), Color(0xFF9E9E9E), Color(0xFFBA68C8), Color(0xFFEF6C00), Color(0xFF1E88E5),
    Color(0xFFE53935), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFFF9A825), Color(0xFF8E24AA),
)

fun catColor(c: Category?) = CAT_COLORS[((c?.tint ?: 2) % CAT_COLORS.size + CAT_COLORS.size) % CAT_COLORS.size]

/** Набор значков категорий: ключ хранится в Category.icon. */
val CAT_ICONS: List<Pair<String, ImageVector>> = listOf(
    "food" to Icons.Rounded.SoupKitchen, "restaurant" to Icons.Rounded.Storefront, "fastfood" to Icons.Rounded.Fastfood,
    "coffee" to Icons.Rounded.LocalCafe, "grocery" to Icons.Rounded.LocalGroceryStore, "bar" to Icons.Rounded.LocalBar,
    "transport" to Icons.Rounded.DirectionsBus, "taxi" to Icons.Rounded.LocalTaxi, "car" to Icons.Rounded.DirectionsCar,
    "fuel" to Icons.Rounded.LocalGasStation, "train" to Icons.Rounded.Train, "bike" to Icons.Rounded.PedalBike,
    "flight" to Icons.Rounded.Flight, "home" to Icons.Rounded.Home, "bills" to Icons.Rounded.Bolt,
    "phone" to Icons.Rounded.PhoneAndroid, "internet" to Icons.Rounded.Wifi, "subs" to Icons.Rounded.Subscriptions,
    "shopping" to Icons.Rounded.ShoppingBag, "clothes" to Icons.Rounded.Checkroom, "beauty" to Icons.Rounded.Spa,
    "health" to Icons.Rounded.LocalHospital, "pharmacy" to Icons.Rounded.LocalPharmacy, "sport" to Icons.Rounded.FitnessCenter,
    "education" to Icons.Rounded.School, "books" to Icons.Rounded.MenuBook, "work" to Icons.Rounded.Work,
    "fun" to Icons.Rounded.Celebration, "games" to Icons.Rounded.SportsEsports, "movie" to Icons.Rounded.Movie,
    "music" to Icons.Rounded.MusicNote, "gift" to Icons.Rounded.CardGiftcard, "charity" to Icons.Rounded.Favorite,
    "pets" to Icons.Rounded.Pets, "kids" to Icons.Rounded.ChildCare, "tech" to Icons.Rounded.Devices,
    "repair" to Icons.Rounded.Build, "misc" to Icons.Rounded.Settings, "salary" to Icons.Rounded.Payments,
    "bank" to Icons.Rounded.AccountBalance, "savings" to Icons.Rounded.Savings, "cashback" to Icons.Rounded.Redeem,
    "other" to Icons.Rounded.Category,
)

private val ICON_MAP = CAT_ICONS.toMap()

/** Подбор значка по названию, если пользователь не выбрал свой. */
fun guessIcon(name: String): String {
    val n = name.lowercase()
    val rules = listOf(
        "rest" to listOf("рест", "кафе", "пицц", "мак", "суши", "ресторан"), "fastfood" to listOf("вредн", "фастфуд", "бургер", "шаурм"),
        "coffee" to listOf("кофе", "кава"), "grocery" to listOf("продукт", "супермаркет", "сільпо", "атб"), "food" to listOf("еда", "їжа", "обед", "обід", "питан"),
        "taxi" to listOf("такси", "таксі", "uber", "bolt"), "fuel" to listOf("бенз", "топлив", "пальн"), "car" to listOf("авто", "машин"),
        "bike" to listOf("велик", "велос", "самокат"), "transport" to listOf("транспорт", "метро", "проезд", "проїзд", "автобус"), "train" to listOf("поезд", "потяг", "укрзал"),
        "flight" to listOf("путеш", "подорож", "отпуск", "відпуст", "авиа"), "bills" to listOf("коммун", "комун", "свет", "світл", "газ", "вода"),
        "home" to listOf("дом", "квартир", "аренд", "оренд", "житл"), "phone" to listOf("связь", "зв'яз", "моб", "телефон"), "internet" to listOf("интернет", "інтернет"),
        "subs" to listOf("подписк", "підписк", "web", "веб", "netflix", "spotify", "youtube"), "clothes" to listOf("одежд", "одяг", "обув", "взут"),
        "beauty" to listOf("красот", "краса", "уход", "догляд", "барбер", "стриж"), "pharmacy" to listOf("аптек", "лекар", "ліки"),
        "health" to listOf("здоров", "врач", "лікар", "медиц"), "sport" to listOf("спорт", "зал", "фитнес", "фітнес"),
        "education" to listOf("образов", "освіт", "учеб", "навчан", "курс", "универ", "універ"), "books" to listOf("книг"),
        "salary" to listOf("зарпл", "зарплат", "аванс"), "work" to listOf("работ", "робот", "подработ", "фриланс"),
        "fun" to listOf("развлеч", "розваг", "отдых", "відпоч"), "games" to listOf("игр", "ігр", "steam"), "movie" to listOf("кино", "кіно", "фильм"),
        "gift" to listOf("подар"), "charity" to listOf("добро", "благо", "донат", "зсу"), "pets" to listOf("кот", "кіт", "собак", "живот", "твар"),
        "kids" to listOf("дет", "діт"), "tech" to listOf("техник", "техні", "гаджет", "электрон"), "repair" to listOf("ремонт"),
        "savings" to listOf("сбереж", "заощад", "копил", "депозит"), "cashback" to listOf("кешбек", "кэшбек", "cashback"), "bank" to listOf("банк", "процент", "відсот"),
        "shopping" to listOf("покуп", "шопинг", "маркетплейс", "rozetka", "розетка"), "misc" to listOf("разное", "різне", "прочее", "інше", "другое"),
    )
    return rules.firstOrNull { (_, keys) -> keys.any { it in n } }?.first?.let { if (it == "rest") "restaurant" else it } ?: "other"
}

fun catIcon(c: Category?): ImageVector = ICON_MAP[c?.icon?.ifBlank { null } ?: c?.name?.let(::guessIcon) ?: "other"] ?: Icons.Rounded.Category
