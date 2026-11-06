package com.pank.vsevodnom.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Настройки пользователя: имя, источник новостей, аватар ассистента. */
object UserPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("user", Context.MODE_PRIVATE)
    fun name(ctx: Context) = p(ctx).getString("name", "Миша")!!
    fun setName(ctx: Context, v: String) = p(ctx).edit().putString("name", v).apply()
    fun newsUrl(ctx: Context) = p(ctx).getString("news", NEWS_SOURCES.first().second)!!
    fun setNewsUrl(ctx: Context, v: String) = p(ctx).edit().putString("news", v).apply()
    fun avatar(ctx: Context) = p(ctx).getString("avatar", "")!!
    fun setAvatar(ctx: Context, v: String) = p(ctx).edit().putString("avatar", v).apply()

    val NEWS_SOURCES = listOf(
        "Українська правда" to "https://www.pravda.com.ua/rss/view_news/",
        "Суспільне" to "https://suspilne.media/rss/all.rss",
        "РБК-Україна" to "https://www.rbc.ua/static/rss/all.rus.rss.xml",
        "Liga.net" to "https://www.liga.net/news/all/rss.xml",
    )
}

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

private fun httpGet(url: String, limit: Int = 600_000): String {
    var u = url
    repeat(4) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 12_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "uk,ru;q=0.9,en;q=0.7")
        try {
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: return "").toString(); return@repeat }
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val bytes = c.inputStream.use { s -> val o = java.io.ByteArrayOutputStream(); val b = ByteArray(16384); while (o.size() < limit) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n) }; o.toByteArray() }
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
            return String(bytes, runCatching { charset(cs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    throw RuntimeException("Слишком много перенаправлений")
}

data class NewsItem(val title: String, val link: String, val time: String)

object News {
    @Volatile private var cache: Pair<Long, List<NewsItem>>? = null

    suspend fun load(ctx: Context): List<NewsItem> = withContext(Dispatchers.IO) {
        cache?.let { if (System.currentTimeMillis() - it.first < 15 * 60_000) return@withContext it.second }
        val xml = httpGet(UserPrefs.newsUrl(ctx))
        val items = Regex("<item[\\s>].*?</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(15).mapNotNull { m ->
            fun tag(t: String) = Regex("<$t[^>]*>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(m.value)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.replace(Regex("<[^>]+>"), "")
                ?.replace("&quot;", "\"")?.replace("&amp;", "&")?.replace("&#039;", "'")?.replace("&laquo;", "«")?.replace("&raquo;", "»")?.trim()
            val title = tag("title") ?: return@mapNotNull null
            val time = tag("pubDate")?.let { d -> runCatching { java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5) }.getOrNull() } ?: ""
            NewsItem(title, tag("link") ?: "", time)
        }.toList()
        if (items.isEmpty()) throw RuntimeException("Лента пустая")
        cache = System.currentTimeMillis() to items
        items
    }
}

/** Карточка товара по ссылке: название, картинка, цена из Open Graph / разметки магазина. */
object ProductInfo {
    data class Info(val title: String?, val image: String?, val price: Double?, val currency: String?, val store: String)

    suspend fun fetch(url: String): Info = withContext(Dispatchers.IO) {
        val store = runCatching { URL(url).host.removePrefix("www.").removePrefix("m.") }.getOrDefault("")
        val html = runCatching { httpGet(url, 400_000) }.getOrDefault("")
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            Regex("<meta[^>]+(?:property|name|itemprop)=[\"']${Regex.escape(k)}[\"'][^>]*content=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name|itemprop)=[\"']${Regex.escape(k)}[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        }
        val title = meta("og:title", "twitter:title") ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.trim()
        val image = meta("og:image", "twitter:image")
        val priceStr = meta("product:price:amount", "og:price:amount", "price") ?: Regex("\"price\"\\s*:\\s*\"?([0-9]+[.,]?[0-9]*)").find(html)?.groupValues?.get(1)
        val cur = meta("product:price:currency", "og:price:currency", "priceCurrency") ?: Regex("\"priceCurrency\"\\s*:\\s*\"([A-Z]{3})\"").find(html)?.groupValues?.get(1)
        Info(title?.replace("&quot;", "\"")?.replace("&amp;", "&")?.take(160), image, priceStr?.replace(',', '.')?.toDoubleOrNull(), cur, store)
    }
}

/** Напоминания о своих событиях календаря. */
object EventReminder {
    private const val CH = "events"

    fun schedule(ctx: Context, id: Long, title: String, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, (id % Int.MAX_VALUE).toInt() + 100_000,
            Intent(ctx, EventReminderReceiver::class.java).putExtra("title", title).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) }
    }

    fun notify(ctx: Context, title: String, text: String, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Напоминания о событиях", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 80, Intent(ctx, MainActivity::class.java).putExtra("route", "calendar"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { nm.notify(id, NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build()) }
    }
}

class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        EventReminder.notify(ctx, "Скоро: ${intent.getStringExtra("title") ?: "событие"}", intent.getStringExtra("text") ?: "Напоминание", (intent.getLongExtra("id", 0) % 100_000).toInt() + 2000)
    }
}
