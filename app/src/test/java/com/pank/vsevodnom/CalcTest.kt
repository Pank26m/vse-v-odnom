package com.pank.vsevodnom

import com.pank.vsevodnom.ui.calc.Expr
import com.pank.vsevodnom.ui.calc.Solver
import org.junit.Test
import kotlin.test.assertEquals

class CalcTest {
    private fun e(s: String) = Expr.format(Expr.eval(s))
    @Test fun basics() {
        assertEquals("0,3", e("0,1+0,2"))
        assertEquals("176", e("120+56"))
        assertEquals("220", e("200+10%"))
        assertEquals("20", e("200×10%"))
        assertEquals("14", e("2+3×4"))
        assertEquals("1 024", e("2^10"))
        assertEquals("120", e("5!"))
        assertEquals("−6", e("−2×3"))
        assertEquals("4", e("√(16)"))
        Expr.degrees = true; assertEquals("0,5", e("sin(30)"))
        assertEquals("6,28318530718", e("2π"))
        assertEquals("10", e("2(3+2)"))
    }
    @Test fun solver() {
        assertEquals(listOf(3.0, 2.0), Solver.solve("x^2−5x+6=0").roots.map { Math.round(it * 1e9) / 1e9 })
        assertEquals(listOf(4.0), Solver.solve("2x+3=11").roots)
        assertEquals(0, Solver.solve("x^2+1=0").roots.size)
        val r = Solver.solve("x^3=8").roots; assertEquals(1, r.size); assertEquals(2.0, r[0], 1e-6)
    }
}
