package com.pank.vsevodnom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

object MoneyRepo {
    const val EXPENSE = "EXPENSE"; const val INCOME = "INCOME"; const val TRANSFER = "TRANSFER"; const val ADJUST = "ADJUST"

    /** Баланс счёта: начальный + доходы − расходы ± переводы ± корректировки. */
    fun balance(a: Account, txns: List<Txn>): Double {
        var b = a.initial
        for (t in txns) {
            when (t.type) {
                INCOME, ADJUST -> if (t.accountId == a.id) b += t.amount
                EXPENSE -> if (t.accountId == a.id) b -= t.amount
                TRANSFER -> { if (t.accountId == a.id) b -= t.amount; if (t.toAccountId == a.id) b += (t.toAmount ?: t.amount) }
            }
        }
        return b
    }

    suspend fun ensureDefaults(ctx: Context) {
        val dao = AppDb.get(ctx).money()
        if (dao.accountsOnce().isEmpty()) {
            dao.upsertAccount(Account(name = "Карта", currency = "UAH", tint = 1))
            dao.upsertAccount(Account(name = "Наличные", currency = "UAH", tint = 4, sort = 1))
        }
        if (dao.categoriesOnce().isEmpty()) {
            listOf("Еда", "Кафе и рестораны", "Транспорт", "Дом", "Связь и интернет", "Здоровье", "Одежда", "Развлечения", "Образование", "Подарки", "Другое")
                .forEachIndexed { i, n -> dao.upsertCategory(Category(name = n, income = false, tint = i % 6)) }
            listOf("Зарплата", "Подработка", "Подарки", "Другое").forEachIndexed { i, n -> dao.upsertCategory(Category(name = n, income = true, tint = (i + 1) % 6)) }
        }
    }

    // ---------- импорт Sumlio ----------

    data class ImportResult(val added: Int, val skipped: Int, val accounts: Int, val categories: Int)

    /** Строка Sumlio, приведённая к «счёт ± сумма» (без базы — удобно проверять). */
    data class SRec(val at: Long, val type: String, val account: String, val accCur: String, val delta: Double,
                    val toAccount: String? = null, val toCur: String? = null, val toDelta: Double? = null,
                    val cat: String = "", val sub: String = "", val income: Boolean = false, val comment: String = "", val tags: String = "", val ext: String)

    fun parseSumlio(text: String): Pair<List<SRec>, Map<String, String>> {
        val rows = parseCsv(text)
        val header = rows.firstOrNull() ?: throw IllegalArgumentException("Пустой файл")
        require(header.getOrNull(0)?.startsWith("Date") == true && header.getOrNull(1) == "Type") { "Это не экспорт Sumlio" }
        val accIdx = rows.indexOfFirst { it.getOrNull(0) == "Name" && it.getOrNull(1) == "Currency" }
        val accCur = (if (accIdx > 0) rows.drop(accIdx + 1).filter { it.size >= 2 && it[0].isNotBlank() } else emptyList()).associate { it[0] to it[1] }
        val out = ArrayList<SRec>()
        for (r in rows.subList(1, if (accIdx > 0) accIdx else rows.size)) {
            if (r.size < 10 || r[0].isBlank()) continue
            val at = runCatching { Instant.parse(r[0]).toEpochMilli() }.getOrNull() ?: continue
            val a1 = r[6].toDoubleOrNull() ?: continue; val c1 = r[7]
            val a2 = r[8].toDoubleOrNull() ?: a1; val c2 = r[9]
            val comment = r.getOrElse(14) { "" }; val tags = r.getOrElse(15) { "" }
            val ext = "sumlio:${r[0]}:${r[1]}:$a1"
            out += when (r[1]) {
                "Expense" -> SRec(at, EXPENSE, r[2], c1, -a1, cat = r[4], sub = r[5], comment = comment, tags = tags, ext = ext)
                "Income" -> SRec(at, INCOME, r[4], c2, a2, cat = r[2], sub = r[3], income = true, comment = comment, tags = tags, ext = ext)
                "Transfer", "Currency" -> SRec(at, TRANSFER, r[2], c1, -a1, r[4], c2, a2, comment = comment, tags = tags, ext = ext)
                "Adjustment" -> if (r[2] == "Корректировка") SRec(at, ADJUST, r[4], c2, a2, comment = comment.ifBlank { "Корректировка" }, ext = ext)
                                else SRec(at, ADJUST, r[2], c1, -a1, comment = comment.ifBlank { "Корректировка" }, ext = ext)
                else -> continue
            }
        }
        return out to accCur
    }

    /**
     * Формат экспорта Sumlio: Date (UTC), Type, From account/category, From subcategory, To account/category, To subcategory,
     * Amount 1, Currency 1, Amount 2, Currency 2, …, Comment, Tags. Внизу — таблица счетов (Name, Currency, Balance…).
     */
    suspend fun importSumlio(ctx: Context, text: String): ImportResult = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).money()
        val (recs, accCurrency) = parseSumlio(text)
        val accounts = dao.accountsOnce().associateBy { it.name }.toMutableMap()
        var newAcc = 0
        suspend fun acc(name: String, cur: String): Account = accounts[name] ?: run {
            val a = Account(name = name, currency = accCurrency[name] ?: cur, tint = accounts.size % 6, sort = accounts.size)
            val id = dao.upsertAccount(a); newAcc++
            a.copy(id = id).also { accounts[name] = it }
        }
        val cats = dao.categoriesOnce().toMutableList()
        var newCat = 0
        suspend fun cat(name: String, sub: String, income: Boolean): Long? {
            if (name.isBlank()) return null
            val clean = name.removePrefix("(Архив) ").trim()
            val parent = cats.firstOrNull { it.name == clean && it.income == income && it.parentId == null }
                ?: Category(name = clean, income = income, tint = cats.size % 6).let { c -> val id = dao.upsertCategory(c); newCat++; c.copy(id = id).also { cats += it } }
            if (sub.isBlank()) return parent.id
            return (cats.firstOrNull { it.name == sub.trim() && it.parentId == parent.id }
                ?: Category(name = sub.trim(), income = income, parentId = parent.id, tint = parent.tint).let { c -> val id = dao.upsertCategory(c); newCat++; c.copy(id = id).also { cats += it } }).id
        }
        val known = dao.allExtIds().toHashSet()
        val out = ArrayList<Txn>(); var skipped = 0
        for (r in recs) {
            if (r.ext in known) { skipped++; continue }
            known += r.ext
            val a = acc(r.account, r.accCur)
            out += when (r.type) {
                EXPENSE -> Txn(at = r.at, type = EXPENSE, amount = -r.delta, accountId = a.id, categoryId = cat(r.cat, r.sub, false), comment = r.comment, tags = r.tags, source = "import", extId = r.ext)
                INCOME -> Txn(at = r.at, type = INCOME, amount = r.delta, accountId = a.id, categoryId = cat(r.cat, r.sub, true), comment = r.comment, tags = r.tags, source = "import", extId = r.ext)
                TRANSFER -> Txn(at = r.at, type = TRANSFER, amount = -r.delta, accountId = a.id, toAccountId = acc(r.toAccount!!, r.toCur!!).id, toAmount = r.toDelta, comment = r.comment, tags = r.tags, source = "import", extId = r.ext)
                else -> Txn(at = r.at, type = ADJUST, amount = r.delta, accountId = a.id, comment = r.comment, source = "import", extId = r.ext)
            }
        }
        out.chunked(500).forEach { dao.insertTxns(it) }
        ImportResult(out.size, skipped, newAcc, newCat)
    }

    // ---------- monobank ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("money", Context.MODE_PRIVATE)
    fun monoToken(ctx: Context) = prefs(ctx).getString("mono", "")!!
    fun setMonoToken(ctx: Context, t: String) = prefs(ctx).edit().putString("mono", t.trim()).apply()

    private fun monoGet(token: String, path: String): String {
        val c = URL("https://api.monobank.ua$path").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Token", token); c.connectTimeout = 15_000; c.readTimeout = 20_000
        try {
            if (c.responseCode == 429) throw RuntimeException("monobank просит подождать минуту между запросами")
            if (c.responseCode !in 200..299) throw RuntimeException("monobank: HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private val ISO = mapOf(980 to "UAH", 840 to "USD", 978 to "EUR", 985 to "PLN", 826 to "GBP")

    /** Загружает выписки всех карт mono за последние 30 дней. Повторы отсекаются по id операции. */
    suspend fun syncMono(ctx: Context): Int = withContext(Dispatchers.IO) {
        val token = monoToken(ctx).ifBlank { throw IllegalStateException("Сначала вставь токен monobank") }
        val dao = AppDb.get(ctx).money()
        val info = JSONObject(monoGet(token, "/personal/client-info"))
        val accs = info.optJSONArray("accounts") ?: JSONArray()
        val known = dao.allExtIds().toHashSet()
        var added = 0
        val to = System.currentTimeMillis() / 1000; val from = to - 30L * 86400
        for (i in 0 until accs.length()) {
            val a = accs.getJSONObject(i)
            val cur = ISO[a.optInt("currencyCode")] ?: "UAH"
            val pan = a.optJSONArray("maskedPan")?.optString(0)?.takeLast(4) ?: a.optString("type")
            val name = "mono ${a.optString("type")} $pan".trim()
            val acc = dao.accountsOnce().firstOrNull { it.name == name } ?: Account(name = name, currency = cur, tint = 2).let { it.copy(id = dao.upsertAccount(it)) }
            if (i > 0) Thread.sleep(61_000) // ограничение API: 1 запрос в минуту
            val st = JSONArray(monoGet(token, "/personal/statement/${a.getString("id")}/$from/$to"))
            for (k in 0 until st.length()) {
                val o = st.getJSONObject(k)
                val ext = "mono:" + o.getString("id")
                if (ext in known) continue
                val amount = o.getLong("amount") / 100.0
                dao.upsertTxn(Txn(at = o.getLong("time") * 1000, type = if (amount < 0) EXPENSE else INCOME, amount = kotlin.math.abs(amount), accountId = acc.id,
                    comment = o.optString("description"), source = "mono", extId = ext))
                known += ext; added++
            }
        }
        added
    }

    /** Курс НБУ для USD/EUR (для обзора). */
    suspend fun nbu(cc: String): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?valcode=$cc&json").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            JSONArray(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject(0).getDouble("rate")
        }.getOrNull()
    }

    suspend fun accountsWithBalance(ctx: Context): List<Pair<Account, Double>> {
        val dao = AppDb.get(ctx).money()
        val tx = dao.allTxns().first()
        return dao.accountsOnce().map { it to balance(it, tx) }
    }
}
