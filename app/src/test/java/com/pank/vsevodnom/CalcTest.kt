package com.pank.vsevodnom

import com.pank.vsevodnom.ui.calc.Expr
import com.pank.vsevodnom.ui.calc.Solver
import com.pank.vsevodnom.ui.calc.lin
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

class MathEditorTest {
    private fun r(ed: com.pank.vsevodnom.ui.calc.MathEditor) = Expr.format(Expr.eval(ed.root.lin()))
    @Test fun fractionTakesNumber() {
        val ed = com.pank.vsevodnom.ui.calc.MathEditor()
        ed.text("12"); ed.fraction(); ed.text("4"); ed.right(); ed.text("+1")
        assertEquals("4", r(ed))
    }
    @Test fun powerAndRoot() {
        val ed = com.pank.vsevodnom.ui.calc.MathEditor()
        ed.text("2"); ed.power(); ed.text("10"); ed.right(); ed.text("+"); ed.sqrt(); ed.text("16")
        assertEquals("1 028", r(ed))
        val e2 = com.pank.vsevodnom.ui.calc.MathEditor()
        e2.nroot(); e2.text("3"); e2.right(); e2.text("27")
        assertEquals("3", r(e2))
    }
    @Test fun navigationAndBackspace() {
        val ed = com.pank.vsevodnom.ui.calc.MathEditor()
        ed.fraction(); ed.text("1"); ed.down(); ed.text("2"); ed.up(); ed.text("0")
        assertEquals("5", r(ed))               // 10/2
        ed.right(); ed.right(); ed.backspace()  // курсор в конце знаменателя → стираем «2»
        ed.backspace(); ed.text("5")           // пустой знаменатель: дробь распаковывается в «10÷»
        assertEquals("2", r(ed))               // 10/5
    }
}
