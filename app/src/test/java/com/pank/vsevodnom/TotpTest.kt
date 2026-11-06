package com.pank.vsevodnom

import com.pank.vsevodnom.data.PassGen
import com.pank.vsevodnom.data.Totp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Векторы из RFC 6238 (SHA1, ключ «12345678901234567890»). */
class TotpTest {
    private val p = Totp.Params("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8)
    @Test fun rfcVectors() {
        assertEquals("94287082", Totp.code(p, 59_000L))
        assertEquals("07081804", Totp.code(p, 1111111109_000L))
        assertEquals("14050471", Totp.code(p, 1111111111_000L))
        assertEquals("89005924", Totp.code(p, 1234567890_000L))
        assertEquals("69279037", Totp.code(p, 2000000000_000L))
    }
    @Test fun generator() {
        repeat(100) { val s = PassGen.generate(16, true, true, true); assertEquals(16, s.length); assertTrue(s.any { it.isDigit() } && s.any { it.isUpperCase() }) }
    }
}
