package com.pank.vsevodnom.ui.random

import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Звуки «Рандома» — синтез из физики удара: короткий шумовой щелчок + затухающие собственные частоты тела
 * (металл монеты, пластик колеса, стекло бутылки, кости по столу). Длинные звуки (колесо, бутылочка) считаются
 * заранее по той же кривой замедления, что и анимация, — щелчки совпадают с секторами.
 */
object Sfx {
    const val SR = 44100

    private class Buf(seconds: Double) {
        val a = FloatArray((seconds * SR).toInt().coerceAtLeast(1))
        val rnd = Random(System.nanoTime())
        /** Удар: шум [noiseMs] + моды (частота, затухание 1/с) с амплитудой [amp]. */
        fun hit(at: Double, modes: List<Pair<Double, Double>>, amp: Float, noiseMs: Double = 1.5, noiseAmp: Float = 0.6f) {
            val s0 = (at * SR).toInt(); if (s0 >= a.size || s0 < 0) return
            val maxDecay = modes.minOfOrNull { it.second } ?: 200.0
            val len = (SR * 6.0 / maxDecay).toInt().coerceAtMost(SR * 2)
            val ph = modes.map { rnd.nextDouble() * 2 * PI }
            val nLen = (noiseMs / 1000 * SR).toInt().coerceAtLeast(1)
            for (i in 0 until len) {
                val k = s0 + i; if (k >= a.size) break
                val t = i.toDouble() / SR
                var v = 0.0
                modes.forEachIndexed { m, (f, d) -> v += sin(2 * PI * f * t + ph[m]) * exp(-t * d) / (m + 1.0).let { 1.0 + 0.35 * (it - 1) } }
                if (i < nLen) v += (rnd.nextDouble() * 2 - 1) * noiseAmp * (1.0 - i.toDouble() / nLen)
                a[k] += (v * amp / modes.size.coerceAtLeast(1)).toFloat()
            }
        }
        /** Шум, пропущенный через простой фильтр нижних частот, с огибающей env(t ∈ 0…1). */
        fun noise(at: Double, dur: Double, cutoff: Double, amp: Float, env: (Double) -> Double) {
            val s0 = (at * SR).toInt(); val n = (dur * SR).toInt()
            val k1 = (2 * PI * cutoff / SR).let { it / (it + 1) }
            var y = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val k = s0 + i; if (k >= a.size || k < 0) break
                y += k1 * ((rnd.nextDouble() * 2 - 1) - y); y2 += k1 * (y - y2)
                a[k] += (y2 * 3.0 * amp * env(i.toDouble() / n)).toFloat()
            }
        }
        fun pcm(gain: Float = 0.85f): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4f)
            return ShortArray(a.size) { (a[it] / peak * gain * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }

    // собственные частоты: латунная монета ~2,5 см, пластиковый язычок колеса, стекло, кость по дереву
    private val COIN = listOf(5860.0 to 9.0, 9450.0 to 12.0, 13100.0 to 16.0, 7320.0 to 11.0)
    private val COIN_DAMPED = COIN.map { it.first to it.second * 3.2 }
    private val FLAP = listOf(2150.0 to 140.0, 3620.0 to 180.0, 5100.0 to 260.0)
    private val GLASS = listOf(1180.0 to 7.0, 2950.0 to 10.0, 4870.0 to 15.0)
    private val DIE = listOf(1750.0 to 55.0, 3120.0 to 70.0, 4630.0 to 95.0, 980.0 to 45.0)
    private val TABLE = listOf(240.0 to 35.0, 410.0 to 45.0)

    /** Монетка: щелчок большим пальцем (звенит), полёт, приземление на стол — подскоки и «юла» в конце. [land] — когда падает. */
    fun coin(land: Double = 1.2): ShortArray {
        val b = Buf(land + 1.0)
        b.hit(0.0, COIN, 0.7f, noiseMs = 2.0, noiseAmp = 0.9f)
        // подскоки: интервалы уменьшаются, каждый тише
        var t = land; var gap = 0.105; var amp = 1.0f
        repeat(5) { b.hit(t, COIN_DAMPED, amp, 1.2, 1.0f); b.hit(t, TABLE, amp * 0.35f, 0.5, 0f); t += gap; gap *= 0.62; amp *= 0.58f }
        // монета «дребезжит» на ребре, всё чаще и тише, и ложится
        var g = 0.032; var a2 = 0.28f
        while (g > 0.0035) { b.hit(t, COIN_DAMPED, a2, 0.6, 0.6f); t += g; g *= 0.86; a2 *= 0.95f }
        b.hit(t, COIN_DAMPED.map { it.first to it.second * 2 }, 0.22f, 0.8, 0.5f)
        return b.pcm()
    }

    /**
     * Колесо: язычок щёлкает о каждую границу сектора. [turns] — полный угол поворота (градусы) от [from],
     * [n] — секторов, [ms] и [easing] — как у анимации.
     */
    fun wheel(from: Float, to: Float, n: Int, ms: Int, easing: Easing): ShortArray {
        val dur = ms / 1000.0
        val b = Buf(dur + 0.3)
        val sweep = 360f / n.coerceAtLeast(1)
        var lastIdx = kotlin.math.floor(from / sweep).toInt()
        val steps = (dur * 2000).toInt()
        var prevT = 0.0
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            val ang = from + (to - from) * easing.transform(t.toFloat())
            val idx = kotlin.math.floor(ang / sweep).toInt()
            if (idx != lastIdx) {
                val at = t * dur
                // чем медленнее, тем громче и «щёлкастее» отдельный удар
                val speed = (1.0 / (at - prevT).coerceAtLeast(0.004)).coerceAtMost(250.0)
                b.hit(at, FLAP, (0.55 + 0.45 * (1 - speed / 250)).toFloat(), 1.0, 0.8f)
                lastIdx = idx; prevT = at
            }
        }
        return b.pcm(0.75f)
    }

    /** Бутылочка: шорох и постукивание стекла о стол, частота — по скорости вращения (та же кривая). */
    fun bottle(turnsDeg: Float, ms: Int, easing: Easing): ShortArray {
        val dur = ms / 1000.0
        val b = Buf(dur + 0.4)
        // старт — звонкий толчок рукой о стекло
        b.hit(0.0, GLASS, 0.5f, 2.0, 0.7f)
        var lastHalf = 0
        val steps = (dur * 1000).toInt()
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            val ang = turnsDeg * easing.transform(t.toFloat())
            val dt = 1.0 / steps
            val speed = (turnsDeg * (easing.transform(t.toFloat()) - easing.transform((t - dt).toFloat()))) / (dt * dur) / 360.0  // об/с
            val loud = (speed / 3.0).coerceIn(0.0, 1.0)
            // шорох донышка: короткими кусочками
            if (s % 10 == 0 && loud > 0.01) b.noise(t * dur, 0.012, 380.0, (0.25 * Math.pow(loud, 0.7)).toFloat()) { x -> sin(PI * x) }
            // дважды за оборот бутылка чуть подпрыгивает (неровное донышко и горлышко) — лёгкий стук стекла
            val half = (ang / 180f).toInt()
            if (half != lastHalf) { b.hit(t * dur, GLASS.map { it.first to it.second * 6 } + TABLE, (0.12 + 0.3 * (1 - loud)).toFloat() * if (loud > 0.02) 1f else 0f, 0.6, 0.4f); lastHalf = half }
        }
        return b.pcm(0.7f)
    }

    /** Кости: n кубиков падают на стол, кувыркаются — серия стуков с замедлением, иногда кубик о кубик. */
    // кость — пластик/смола: короткие сухие моды (затухание сотни 1/с), иначе звенит как струна (так было до 0.45)
    private val DIE_CLICK = listOf(2650.0 to 520.0, 4150.0 to 700.0, 5900.0 to 900.0, 1500.0 to 380.0)
    // деревянный стол: глухой «тук» низкими модами
    private val TABLE_THOCK = listOf(165.0 to 55.0, 290.0 to 75.0, 470.0 to 110.0, 820.0 to 180.0)

    /**
     * Кости, брошенные на деревянный стол: пара щелчков друг о друга в ладони, затем каждая кость падает,
     * отскакивает (интервалы и громкость уменьшаются), между ударами — короткое перекатывание по ребру,
     * в конце — дробь мелких ударов, пока кость не ляжет.
     */
    fun dice(n: Int, dur: Double = 0.95): ShortArray {
        val b = Buf(dur + 0.4)
        // в ладони: кости стукаются друг о друга
        repeat(3 + n) { b.hit(b.rnd.nextDouble() * 0.11, DIE_CLICK.map { it.first * (1.1 + b.rnd.nextDouble() * 0.3) to it.second * 1.4 }, 0.22f, 0.8, 0.5f) }
        repeat(n.coerceIn(1, 6)) { d ->
            var t = 0.16 + d * 0.025 + b.rnd.nextDouble() * 0.05
            var gap = 0.11 + b.rnd.nextDouble() * 0.05; var amp = 1.0f
            var bounce = 0
            while (t < dur && amp > 0.05f) {
                val shift = 0.92 + b.rnd.nextDouble() * 0.18
                // удар ребром/гранью: щелчок кости + глухой стол (на первых — сильнее)
                b.hit(t, DIE_CLICK.map { it.first * shift to it.second }, amp * 0.75f, 3.5, 1.0f)
                b.hit(t, TABLE_THOCK, amp * (if (bounce < 2) 0.9f else 0.5f), 1.0, 0.2f)
                // иногда двойной удар (сначала угол, потом грань)
                if (b.rnd.nextFloat() < 0.35f) b.hit(t + 0.006 + b.rnd.nextDouble() * 0.01, DIE_CLICK.map { it.first * shift * 1.07 to it.second }, amp * 0.4f, 2.0, 0.7f)
                // перекатывание между ударами: шершавый шорох
                if (bounce >= 1) b.noise(t + 0.01, gap * 0.7, 3200.0, 0.05f * amp) { x -> sin(PI * x) }
                t += gap * (0.8 + b.rnd.nextDouble() * 0.4); gap *= 0.7; amp *= 0.62f; bounce++
            }
            // кость ложится: быстрая дробь
            var tt = t; var a2 = 0.12f
            repeat(4) { b.hit(tt, DIE_CLICK.map { it.first * 0.95 to it.second * 1.3 }, a2, 1.0, 0.6f); b.hit(tt, TABLE_THOCK, a2 * 0.5f, 0.5, 0f); tt += 0.018 + b.rnd.nextDouble() * 0.01; a2 *= 0.6f }
        }
        return b.pcm()
    }

    /** Шар: жидкость плещется и внутри стучит кубик с ответами. [swings] — сколько раз качнули. */
    fun ball(swings: Int = 7, swing: Double = 0.06): ShortArray {
        val b = Buf(swings * swing + 0.5)
        for (i in 0 until swings) {
            val t = i * swing
            b.noise(t, 0.16, 520.0, 0.5f) { x -> sin(PI * x) * exp(-x * 1.5) }               // плеск
            // пузырьки — короткие свисты с поднимающимся тоном
            repeat(2) {
                val at = t + b.rnd.nextDouble() * swing; val f0 = 500 + b.rnd.nextDouble() * 500
                val s0 = (at * Sfx.SR).toInt(); val len = (0.03 * Sfx.SR).toInt()
                for (k in 0 until len) { val idx = s0 + k; if (idx >= b.a.size) break; val tt = k.toDouble() / Sfx.SR
                    b.a[idx] += (0.12 * sin(2 * PI * f0 * (1 + tt * 18) * tt) * exp(-tt * 110)).toFloat() }
            }
            if (b.rnd.nextFloat() < 0.6f) b.hit(t + 0.02, DIE.map { it.first * 0.55 to it.second * 2.4 }, 0.35f, 0.8, 0.3f)  // кубик внутри (глухо)
        }
        b.noise(swings * swing, 0.4, 300.0, 0.35f) { x -> (1 - x) * (1 - x) }                  // жидкость успокаивается
        return b.pcm(0.75f)
    }

    // ---- проигрывание ----
    private val tracks = HashMap<String, android.media.AudioTrack>()
    private val cache = HashMap<String, ShortArray>()

    /** Собрать (один раз) и проиграть звук по ключу. */
    fun play(key: String, make: () -> ShortArray) = runCatching { playPcm(key, cache.getOrPut(key, make)) }

    /** Проиграть готовый звук; предыдущий с тем же ключом останавливается. */
    @Synchronized fun playPcm(key: String, pcm: ShortArray) = runCatching {
        tracks.remove(key)?.let { runCatching { it.stop(); it.release() } }
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        t.write(pcm, 0, pcm.size); t.play(); tracks[key] = t
    }

    @Synchronized fun stop(key: String) { tracks.remove(key)?.let { runCatching { it.stop(); it.release() } } }
}
