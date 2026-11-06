package com.pank.vsevodnom

import com.pank.vsevodnom.data.SmsRepo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmsCodeTest {
    @Test fun codes() {
        assertEquals("482913", SmsRepo.extractCode("Ваш код підтвердження: 482913. Нікому не повідомляйте"))
        assertEquals("1234", SmsRepo.extractCode("Код для входа 1234"))
        assertEquals("573291", SmsRepo.extractCode("Your verification code is 573-291"))
        assertEquals("5531", SmsRepo.extractCode("Пароль: 5531 для оплати 250 грн"))
        assertEquals("7744", SmsRepo.extractCode("Код 7744. Сума 1500 грн"))
        assertEquals("90817", SmsRepo.extractCode("Nova Poshta: kod 90817 dlya otrymannya"))
        assertNull(SmsRepo.extractCode("Списано 1500 грн. Баланс 3200 грн"))
        assertNull(SmsRepo.extractCode("Привет! Буду в 1900"))
    }
}
