package com.pank.vsevodnom

import com.pank.vsevodnom.ui.games.Chess
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChessTest {
    @Test fun perftStart() {
        val c = Chess()
        assertEquals(20, c.perft(1)); assertEquals(400, c.perft(2)); assertEquals(8902, c.perft(3)); assertEquals(197281, c.perft(4))
        val h = c.hash; assertEquals(h, c.computeHash())
    }
    @Test fun perftKiwipete() {
        val c = Chess(); c.loadFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, c.perft(1)); assertEquals(2039, c.perft(2)); assertEquals(97862, c.perft(3))
    }
    @Test fun perftPos3() {
        val c = Chess(); c.loadFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, c.perft(1)); assertEquals(191, c.perft(2)); assertEquals(2812, c.perft(3)); assertEquals(43238, c.perft(4))
    }
    @Test fun perftPos4() {
        val c = Chess(); c.loadFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, c.perft(1)); assertEquals(264, c.perft(2)); assertEquals(9467, c.perft(3))
    }
    @Test fun findsMateInOne() {
        val c = Chess(); c.loadFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
        val m = c.bestMove(4); assertNotNull(m)
        assertEquals("Лa8#", c.san(m!!))
    }
    @Test fun searchSpeed() {
        val c = Chess()
        val t = System.currentTimeMillis(); val m = c.bestMove(5); println("best=${c.san(m!!)} in ${System.currentTimeMillis() - t} ms")
    }
}
