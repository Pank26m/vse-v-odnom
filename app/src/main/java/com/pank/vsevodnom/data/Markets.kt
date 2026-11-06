package com.pank.vsevodnom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Инструмент в списке: тикер Yahoo Finance + человеческое название. */
data class Instrument(val symbol: String, val name: String, val group: String, val unit: String = "")

val CATALOG = listOf(
    Instrument("UAH=X", "Доллар / гривна", "Валюты", "₴"),
    Instrument("EURUAH=X", "Евро / гривна", "Валюты", "₴"),
    Instrument("PLNUAH=X", "Злотый / гривна", "Валюты", "₴"),
    Instrument("EURUSD=X", "Евро / доллар", "Валюты", "$"),
    Instrument("GBPUSD=X", "Фунт / доллар", "Валюты", "$"),
    Instrument("BTC-USD", "Биткоин", "Крипта", "$"),
    Instrument("ETH-USD", "Эфир", "Крипта", "$"),
    Instrument("SOL-USD", "Solana", "Крипта", "$"),
    Instrument("USDT-USD", "Tether", "Крипта", "$"),
    Instrument("^GSPC", "S&P 500", "Индексы", ""),
    Instrument("^SPG1200", "S&P Global 1200", "Индексы", ""),
    Instrument("URTH", "MSCI World (ETF)", "Индексы", "$"),
    Instrument("^IXIC", "Nasdaq", "Индексы", ""),
    Instrument("^DJI", "Dow Jones", "Индексы", ""),
    Instrument("^STOXX50E", "Euro Stoxx 50", "Индексы", ""),
    Instrument("GC=F", "Золото", "Сырьё", "$"),
    Instrument("BZ=F", "Нефть Brent", "Сырьё", "$"),
    Instrument("AAPL", "Apple", "Акции", "$"),
    Instrument("NVDA", "Nvidia", "Акции", "$"),
    Instrument("TSLA", "Tesla", "Акции", "$"),
)

data class Quote(
    val symbol: String,
    val price: Double,
    val prevClose: Double?,
    val currency: String,
    val times: List<Long>,   // секунды
    val closes: List<Double>,
    val delayedNote: String = "",
) {
    val change get() = prevClose?.let { price - it }
    val changePct get() = prevClose?.takeIf { it != 0.0 }?.let { (price - it) / it * 100 }
}

object MarketsApi {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** range: 1d,5d,1mo,6mo,1y,5y */
    suspend fun quote(symbol: String, range: String = "1mo"): Quote = withContext(Dispatchers.IO) {
        val interval = when (range) { "1d" -> "5m"; "5d" -> "30m"; "1mo" -> "1d"; "6mo" -> "1d"; "1y" -> "1d"; else -> "1wk" }
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val err = ArrayList<Throwable>()
        for (host in listOf("query1", "query2")) {
            try {
                val js = JSONObject(get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?range=$range&interval=$interval"))
                val r = js.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = r.getJSONObject("meta")
                val ts = r.optJSONArray("timestamp") ?: JSONArray()
                val cl = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close") ?: JSONArray()
                val times = ArrayList<Long>(); val closes = ArrayList<Double>()
                for (i in 0 until minOf(ts.length(), cl.length())) if (!cl.isNull(i)) { times += ts.getLong(i); closes += cl.getDouble(i) }
                val price = meta.optDouble("regularMarketPrice", closes.lastOrNull() ?: Double.NaN)
                val prev = if (meta.has("previousClose")) meta.optDouble("previousClose") else if (meta.has("chartPreviousClose") && range == "1d") meta.optDouble("chartPreviousClose") else null
                return@withContext Quote(symbol, price, prev ?: dayBefore(times, closes), meta.optString("currency"), times, closes,
                    if (symbol.startsWith("^")) "Индексы — с задержкой до 15 минут" else "")
            } catch (t: Throwable) { err += t }
        }
        fallback(symbol) ?: throw err.firstOrNull() ?: RuntimeException("Нет данных")
    }

    /** Цена закрытия предыдущего дня по ряду, если биржа не прислала её явно. */
    private fun dayBefore(times: List<Long>, closes: List<Double>): Double? {
        if (closes.size < 2) return null
        return closes[closes.size - 2]
    }

    /** Если Yahoo недоступен: НБУ для гривны, CoinGecko для крипты (без истории). */
    private fun fallback(symbol: String): Quote? = runCatching {
        when (symbol) {
            "UAH=X", "EURUAH=X", "PLNUAH=X" -> {
                val cc = mapOf("UAH=X" to "USD", "EURUAH=X" to "EUR", "PLNUAH=X" to "PLN").getValue(symbol)
                val arr = JSONArray(get("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?valcode=$cc&json"))
                val rate = arr.getJSONObject(0).getDouble("rate")
                Quote(symbol, rate, null, "UAH", emptyList(), emptyList(), "Официальный курс НБУ")
            }
            "BTC-USD", "ETH-USD", "SOL-USD", "USDT-USD" -> {
                val id = mapOf("BTC-USD" to "bitcoin", "ETH-USD" to "ethereum", "SOL-USD" to "solana", "USDT-USD" to "tether").getValue(symbol)
                val o = JSONObject(get("https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=usd&include_24hr_change=true")).getJSONObject(id)
                val p = o.getDouble("usd"); val ch = o.optDouble("usd_24h_change", 0.0)
                Quote(symbol, p, p / (1 + ch / 100), "USD", emptyList(), emptyList(), "CoinGecko")
            }
            else -> null
        }
    }.getOrNull()

    // ---- список наблюдения ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("markets", Context.MODE_PRIVATE)
    private val DEFAULT = listOf("UAH=X", "EURUAH=X", "BTC-USD", "^GSPC", "^SPG1200")

    fun watchlist(ctx: Context): List<String> =
        prefs(ctx).getString("list", null)?.split('|')?.filter { it.isNotBlank() } ?: DEFAULT

    fun setWatchlist(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("list", l.joinToString("|")).apply()

    fun customName(ctx: Context, symbol: String) = prefs(ctx).getString("name_$symbol", null)
    fun setCustomName(ctx: Context, symbol: String, name: String) = prefs(ctx).edit().putString("name_$symbol", name).apply()

    fun nameOf(ctx: Context, symbol: String) = CATALOG.firstOrNull { it.symbol == symbol }?.name ?: customName(ctx, symbol) ?: symbol
}
