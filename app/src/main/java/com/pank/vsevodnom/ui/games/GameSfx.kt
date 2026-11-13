package com.pank.vsevodnom.ui.games

import com.pank.vsevodnom.ui.random.Sfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Звуки игр — синтез на телефоне (без чужих записей): карты (тасовка, шлепок, сгрести), хруст яблока,
 * вода/взрывы для «Морского боя», шайба и бита, таинственный звон шара. Каждый звук считается один раз и кэшируется.
 */
object GameSfx {
    private const val SR = Sfx.SR
    private val cache = HashMap<String, ShortArray>()

    private class W(sec: Double) {
        val a = FloatArray((sec * SR).toInt())
        val r = Random(7)
        fun idx(t: Double) = (t * SR).toInt()
        /** Шум через двойной фильтр нижних частот (cutoff Гц), огибающая env(0…1). hp — вычесть низы (звонче). */
        fun noise(at: Double, dur: Double, cutoff: Double, amp: Double, hp: Boolean = false, env: (Double) -> Double) {
            val s0 = idx(at); val n = (dur * SR).toInt(); val k = (2 * PI * cutoff / SR).let { it / (it + 1) }
            var y = 0.0; var y2 = 0.0; var lo = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) continue
                y += k * ((r.nextDouble() * 2 - 1) - y); y2 += k * (y - y2)
                var v = y2 * 3
                if (hp) { lo += 0.02 * (v - lo); v -= lo }
                a[j] += (v * amp * env(i.toDouble() / n)).toFloat()
            }
        }
        /** Тон с плавающей частотой f(t) и огибающей env(0…1). */
        fun tone(at: Double, dur: Double, amp: Double, f: (Double) -> Double, env: (Double) -> Double) {
            val s0 = idx(at); val n = (dur * SR).toInt(); var ph = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) continue
                val x = i.toDouble() / n
                ph += 2 * PI * f(x) / SR
                a[j] += (sin(ph) * amp * env(x)).toFloat()
            }
        }
        /** Удар твёрдого тела: затухающие моды (частота, затухание 1/с). */
        fun hit(at: Double, modes: List<Pair<Double, Double>>, amp: Double) {
            val s0 = idx(at)
            modes.forEachIndexed { m, (f, d) ->
                val len = (SR * 5.0 / d).toInt()
                for (i in 0 until len) { val j = s0 + i; if (j !in a.indices) break; val t = i.toDouble() / SR
                    a[j] += (sin(2 * PI * f * t) * exp(-t * d) * amp / (1 + m * 0.6)).toFloat() }
            }
        }
        /** Простое «эхо помещения»: несколько затухающих отражений. */
        fun room(mix: Double, delays: List<Double> = listOf(0.029, 0.037, 0.043, 0.051), fb: Double = 0.55) {
            val src = a.copyOf()
            for (d in delays) {
                val dl = (d * SR).toInt(); val buf = FloatArray(a.size)
                for (i in a.indices) { val prev = if (i >= dl) buf[i - dl] else 0f; buf[i] = src[i] + (prev * fb).toFloat() }
                for (i in a.indices) a[i] += (buf[i] - src[i]) * (mix / delays.size).toFloat()
            }
        }
        fun pcm(gain: Double = 0.85): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-4f)
            // мягкое начало/конец без щелчков
            val fade = (0.004 * SR).toInt()
            return ShortArray(a.size) { i ->
                val e = minOf(1.0, i.toDouble() / fade, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }

    /** Для тестов: только посчитать звуки, не проигрывать. */
    @Volatile private var renderOnly = false
    private fun play(key: String, make: () -> ShortArray) {
        val pcm = synchronized(cache) { cache.getOrPut(key, make) }
        if (!renderOnly) runCatching { Sfx.playPcm(key, pcm) }
    }
    /** Все звуки (для теста): ключ → PCM 44,1 кГц моно. */
    fun renderAll(): Map<String, ShortArray> {
        renderOnly = true
        try { shuffle(); place(); take(); crunch(); splash(); boom(); bigBoom(); puck(0f); puck(1f); rail(); goal(); mystic(); blkMove(); blkTurn(); blkLock(); blkLine(1); blkLine(4) } finally { renderOnly = false }
        return synchronized(cache) { HashMap(cache) }
    }

    // ---------------- карты ----------------
    /** Тасовка «ласточкой»: два десятка всё более частых щелчков бумаги и «мостик» в конце. */
    fun shuffle() = play("c_shuffle") {
        val w = W(1.15)
        var t = 0.02; var gap = 0.034
        while (t < 0.75) { w.noise(t, 0.012, 5200.0, 0.55 + w.r.nextDouble() * 0.3, hp = true) { x -> exp(-x * 5) }; t += gap * (0.8 + w.r.nextDouble() * 0.4); gap *= 0.975 }
        w.noise(0.0, 0.8, 1800.0, 0.12) { x -> sin(PI * x) }
        w.noise(0.82, 0.3, 2600.0, 0.35, hp = true) { x -> sin(PI * x) * (1 - x) }        // мостик — карты сыплются
        w.noise(1.05, 0.06, 900.0, 0.5) { x -> exp(-x * 6) }                                // колода стукнула о стол
        w.pcm(0.7)
    }
    /** Карта легла на стол: короткий шлепок бумаги. */
    fun place() = play("c_place") {
        val w = W(0.16)
        w.noise(0.0, 0.05, 3400.0, 0.9, hp = true) { x -> exp(-x * 7) }
        w.noise(0.0, 0.09, 600.0, 0.6) { x -> exp(-x * 5) }
        w.pcm(0.75)
    }
    /** Сгрести карты со стола: шуршание с несколькими шлепками. */
    fun take() = play("c_take") {
        val w = W(0.55)
        w.noise(0.0, 0.45, 2400.0, 0.45, hp = true) { x -> sin(PI * x) }
        for (i in 0 until 4) w.noise(0.05 + i * 0.09 + w.r.nextDouble() * 0.03, 0.04, 3200.0, 0.6, hp = true) { x -> exp(-x * 6) }
        w.noise(0.42, 0.1, 700.0, 0.5) { x -> exp(-x * 5) }
        w.pcm(0.7)
    }

    // ---------------- змейка ----------------
    /** Хруст яблока: серия сухих разломов + сочный низ. */
    fun crunch() = play("crunch") {
        val w = W(0.26)
        var t = 0.0
        repeat(7) { w.noise(t, 0.018 + w.r.nextDouble() * 0.012, 6500.0, 0.7 + w.r.nextDouble() * 0.4, hp = true) { x -> exp(-x * 4) }; t += 0.016 + w.r.nextDouble() * 0.022 }
        w.noise(0.0, 0.2, 900.0, 0.35) { x -> sin(PI * minOf(1.0, x * 4)) * exp(-x * 3) }
        w.pcm(0.75)
    }

    // ---------------- морской бой ----------------
    /** Промах: всплеск — удар о воду и пузыри. */
    fun splash() = play("splash") {
        val w = W(0.9)
        w.noise(0.0, 0.5, 1400.0, 0.9) { x -> (if (x < 0.04) x / 0.04 else exp(-(x - 0.04) * 5)) }
        w.noise(0.03, 0.6, 4200.0, 0.25, hp = true) { x -> exp(-x * 4) }
        repeat(9) {
            val at = 0.12 + w.r.nextDouble() * 0.6; val f0 = 380 + w.r.nextDouble() * 700
            w.tone(at, 0.05, 0.18, { x -> f0 * (1 + x * 1.6) }) { x -> sin(PI * x) }
        }
        w.room(0.25); w.pcm(0.8)
    }
    /** Попадание: взрыв. */
    fun boom() = play("boom") {
        val w = W(1.0)
        w.noise(0.0, 0.9, 420.0, 1.0) { x -> if (x < 0.02) x / 0.02 else exp(-(x - 0.02) * 4.5) }
        w.noise(0.0, 0.25, 3800.0, 0.35) { x -> exp(-x * 9) }
        w.tone(0.0, 0.5, 0.6, { x -> 90 - 55 * x }) { x -> exp(-x * 4) }
        w.room(0.3); w.pcm(0.9)
    }
    /** Потоплен: мощный взрыв — два удара, долгий гул, обломки и бульканье. */
    fun bigBoom() = play("bigboom") {
        val w = W(2.2)
        w.noise(0.0, 1.6, 300.0, 1.0) { x -> if (x < 0.015) x / 0.015 else exp(-(x - 0.015) * 2.6) }
        w.noise(0.18, 1.3, 520.0, 0.8) { x -> if (x < 0.02) x / 0.02 else exp(-(x - 0.02) * 3.2) }
        w.tone(0.0, 1.2, 0.8, { x -> 70 - 40 * x }) { x -> exp(-x * 2.5) }
        w.noise(0.0, 0.3, 5000.0, 0.3) { x -> exp(-x * 8) }
        repeat(14) { w.noise(0.3 + w.r.nextDouble() * 1.0, 0.02, 4000.0, 0.25, hp = true) { x -> exp(-x * 5) } }   // обломки
        repeat(10) { val at = 1.1 + w.r.nextDouble() * 0.9; val f0 = 200 + w.r.nextDouble() * 400; w.tone(at, 0.07, 0.15, { x -> f0 * (1 + x) }) { x -> sin(PI * x) } }
        w.room(0.4); w.pcm(0.95)
    }

    // ---------------- аэрохоккей ----------------
    private val PUCK = listOf(1850.0 to 90.0, 3300.0 to 120.0, 5200.0 to 180.0)
    /** Бита по шайбе: пластиковый «клак», громкость — от силы удара (0…1). */
    fun puck(power: Float) { val lvl = (power.coerceIn(0f, 1f) * 3).toInt(); play("puck$lvl") {
        val w = W(0.12); w.hit(0.0, PUCK, 0.6 + lvl * 0.15); w.noise(0.0, 0.006, 6000.0, 0.8) { x -> 1 - x }; w.pcm(0.45 + lvl * 0.15) } }
    /** Шайба о борт. */
    fun rail() = play("rail") { val w = W(0.14); w.hit(0.0, listOf(820.0 to 60.0, 1460.0 to 90.0), 0.8); w.noise(0.0, 0.01, 3000.0, 0.6) { x -> 1 - x }; w.pcm(0.5) }
    /** Гол: шайба падает в ворота и сирена табло. */
    fun goal() = play("goal") {
        val w = W(1.0)
        w.hit(0.0, listOf(420.0 to 30.0, 760.0 to 40.0), 0.7)
        for (k in 0..1) w.tone(0.12, 0.75, 0.35, { 330.0 * (1 + k * 0.5) }) { x -> if (x < 0.05) x / 0.05 else if (x > 0.85) (1 - x) / 0.15 else 1.0 }
        w.pcm(0.7)
    }

    // ---------------- блоки ----------------
    // тихие, мягкие звуки — чтобы не отвлекали: «тик» сдвига, «щёлк» поворота, глухое «тук» укладки, аккорд линии
    fun blkMove() = play("b_move") { val w = W(0.07); w.hit(0.006, listOf(2400.0 to 260.0, 3600.0 to 380.0), 0.35); w.pcm(0.18) }
    fun blkTurn() = play("b_turn") { val w = W(0.07); w.hit(0.006, listOf(1700.0 to 200.0, 2550.0 to 260.0), 0.4); w.hit(0.024, listOf(2300.0 to 260.0), 0.25); w.pcm(0.22) }
    fun blkLock() = play("b_lock") { val w = W(0.14); w.hit(0.006, listOf(190.0 to 40.0, 340.0 to 60.0, 900.0 to 160.0), 0.8); w.noise(0.006, 0.02, 1500.0, 0.3) { x -> 1 - x }; w.pcm(0.32) }
    /** Убраны линии: мягкий аккорд, 4 линии — выше и ярче. */
    fun blkLine(n: Int) { val k = n.coerceIn(1, 4); play("b_line$k") {
        val w = W(0.6)
        val base = listOf(523.25, 659.25, 783.99, 1046.5).take(k + 1)
        base.forEachIndexed { i, f -> w.tone(i * 0.045, 0.5, 0.25, { f }) { x -> (if (x < 0.04) x / 0.04 else 1.0) * exp(-x * 5) } }
        w.room(0.2); w.pcm(0.3)
    } }

    // ---------------- шар ----------------
    /** Таинственный звон: медленно нарастающий мерцающий аккорд, шёпот и искры при ответе. */
    fun mystic() = play("mystic") {
        val w = W(2.6)
        val chord = listOf(220.0, 261.63, 329.63, 493.88, 587.33)   // ля минор с ноной — «волшебно»
        chord.forEachIndexed { i, f ->
            for (d in listOf(-0.6, 0.6)) w.tone(0.05 * i, 2.4, 0.16, { x -> (f + d) * (1 + 0.003 * sin(2 * PI * 5 * x)) }) { x ->
                val att = minOf(1.0, x / 0.35); att * (1 - x) * (0.75 + 0.25 * sin(2 * PI * (3 + i) * x * 2.4)) }
        }
        w.noise(0.0, 1.8, 900.0, 0.12) { x -> sin(PI * x) }                                  // шёпот
        repeat(12) { val at = 1.2 + w.r.nextDouble() * 1.1; val f = 1800 + w.r.nextDouble() * 2400   // искры
            w.tone(at, 0.25, 0.05, { f }) { x -> exp(-x * 9) } }
        w.room(0.6, listOf(0.061, 0.083, 0.097, 0.113), 0.62); w.pcm(0.7)
    }
}
