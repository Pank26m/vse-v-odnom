package com.pank.vsevodnom

import com.pank.vsevodnom.data.PushParse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PushParseTest {
    @Test fun expenseWithBalance() {
        val p = assertNotNull(PushParse.parse("−253.40₴", "Сільпо\nБаланс 1 234.56₴"))
        assertEquals(253.40, p.amount, 0.001); assertFalse(p.income); assertEquals("UAH", p.currency); assertEquals("Сільпо", p.merchant)
    }
    @Test fun privatStyle() {
        val p = assertNotNull(PushParse.parse("Приват24", "Покупка 150.00 UAH. Аптека Доброго дня. Бал. 3200.10UAH"))
        assertEquals(150.0, p.amount, 0.001); assertFalse(p.income)
    }
    @Test fun income() {
        val p = assertNotNull(PushParse.parse("Зарахування", "+1 500,00 грн від Іван І."))
        assertEquals(1500.0, p.amount, 0.001); assertTrue(p.income)
    }
    @Test fun walletDollarFirst() {
        val p = assertNotNull(PushParse.parse("Rozetka", "₴1,299.00 оплачено карткою •• 1234 — payment"))
        assertEquals(1299.0, p.amount, 0.001); assertFalse(p.income)
    }
    @Test fun promoIgnored() {
        assertNull(PushParse.parse("monobank", "Кешбек до 500 грн на кафе цього тижня!"))
        assertNull(PushParse.parse("Банк", "Ваш баланс 2000 грн"))
    }
}
