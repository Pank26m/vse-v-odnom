package com.pank.vsevodnom

import androidx.compose.animation.core.CubicBezierEasing
import com.pank.vsevodnom.ui.random.Sfx
import kotlin.test.Test
import kotlin.test.assertTrue

class SfxTest {
    private fun loud(pcm: ShortArray) = pcm.maxOf { kotlin.math.abs(it.toInt()) }

    @Test fun soundsAreBuilt() {
        val ease = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)
        val all = mapOf(
            "coin" to Sfx.coin(1.2), "wheel" to Sfx.wheel(0f, 360f * 6 + 100f, 5, 3600, ease),
            "bottle" to Sfx.bottle(360f * 9, 8000, CubicBezierEasing(0.08f, 0.55f, 0.02f, 1f)), "dice" to Sfx.dice(3), "ball" to Sfx.ball(),
        )
        val out = System.getenv("SFX_OUT")
        all.forEach { (k, pcm) ->
            assertTrue(loud(pcm) > 20000, "$k звучит")
            assertTrue(pcm.size > Sfx.SR / 2, "$k не короче полсекунды")
            if (out != null) writeWav(java.io.File(out, "$k.wav"), pcm)
        }
    }

    /** Звуки игр (0.43): карты, хруст, вода/взрывы, шайба, шар — все считаются, не тихие и не пустые. */
    @Test fun gameSoundsAreBuilt() {
        val all = com.pank.vsevodnom.ui.games.GameSfx.renderAll()
        assertTrue(all.size >= 12, "все звуки: ${all.keys}")
        val out = System.getenv("SFX_OUT")
        all.forEach { (k, pcm) ->
            assertTrue(loud(pcm) > (if (k.startsWith("b_")) 4000 else 10000), "$k звучит")   // звуки «Блоков» нарочно тихие
            assertTrue(pcm.size > Sfx.SR / 20, "$k не пустой")
            assertTrue(pcm.size < Sfx.SR * 4, "$k не длиннее 4 с")
            if (out != null) writeWav(java.io.File(out, "game_$k.wav"), pcm)
        }
    }

    private fun writeWav(f: java.io.File, pcm: ShortArray) {
        val bb = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(Sfx.SR).putInt(Sfx.SR * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { bb.putShort(it) }
        f.writeBytes(bb.array())
    }
}
