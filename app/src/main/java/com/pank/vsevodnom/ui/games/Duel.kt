package com.pank.vsevodnom.ui.games

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.RoundIconButton
import com.pank.vsevodnom.ui.random.Sfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Дуэль»: ты и противник на лестнице, он стоит на несколько ступенек выше. Рука с револьвером сама ходит
 * вверх-вниз; нажатие — выстрел вдоль прицела (луч против прямоугольников тела и головы). Попал — противник
 * кувыркается вниз, ты поднимаешься на его площадку, появляется следующий (лестница иногда поворачивает —
 * зигзагом). Промахнулся — через короткую паузу стреляет он, конец игры. Каждый 10-й — босс с запасом здоровья.
 * Выстрел в голову — 2 урона, бонус и серия. Координаты — в «метрах лестницы», y вверх.
 */
class DuelGame(private val rnd: Random = Random) {
    enum class Hit { MISS, BODY, HEAD }
    enum class Phase { SPAWN, AIM, ENEMY, DEAD, KILL, CLIMB }

    class Step(val x: Float, val y: Float)
    class Fighter(var x: Float, var y: Float, var facing: Int, val look: Int, val boss: Boolean, var hp: Int) {
        val maxHp = hp
        val scale = if (boss) BOSS_SCALE else 1f
        /** Угол руки от горизонтали в сторону взгляда, градусы (вверх — плюс). */
        var arm = -70f
        var vx = 0f; var vy = 0f; var rot = 0f; var rotV = 0f
        var flinch = 0f
        var name = ""
        val shoulderY get() = y + SHOULDER * scale
        fun bodyBox() = floatArrayOf(x - HALF_W * scale, y, x + HALF_W * scale, y + BODY_H * scale)
        fun headBox() = floatArrayOf(x - HEAD_HALF * scale, y + BODY_H * scale, x + HEAD_HALF * scale, y + HEAD_TOP * scale)
        fun muzzleX() = x + facing * cos(Math.toRadians(arm.toDouble())).toFloat() * ARM_LEN * scale
        fun muzzleY() = shoulderY + sin(Math.toRadians(arm.toDouble())).toFloat() * ARM_LEN * scale
    }
    class Tracer(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val enemy: Boolean) { var age = 0f }
    class Popup(val text: String, val x: Float, val y: Float, val gold: Boolean) { var age = 0f }

    companion object {
        const val SW = 0.9f; const val SH = 0.42f
        const val MIN_A = -12f; const val MAX_A = 80f
        const val BODY_H = 0.6f; const val HEAD_TOP = 1.13f; const val HALF_W = 0.27f; const val HEAD_HALF = 0.23f
        const val SHOULDER = 0.47f; const val ARM_LEN = 0.44f; const val BOSS_SCALE = 1.35f
        const val EV_ENEMY_SHOT = 1; const val EV_NEW_ENEMY = 2; const val EV_BOSS = 3
        val BOSSES = listOf("Барон Порох", "Мадам Кобура", "Громила Бо", "Генерал Пуговица", "Доктор Дуло", "Старый Шпора")

        /** Луч (o + t·d, t ≥ 0) против прямоугольника: t входа или null. */
        fun rayBox(ox: Float, oy: Float, dx: Float, dy: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float? {
            var tmin = 0f; var tmax = Float.MAX_VALUE
            for (axis in 0..1) {
                val o = if (axis == 0) ox else oy; val d = if (axis == 0) dx else dy
                val lo = if (axis == 0) x0 else y0; val hi = if (axis == 0) x1 else y1
                if (abs(d) < 1e-7f) { if (o < lo || o > hi) return null; continue }
                var t1 = (lo - o) / d; var t2 = (hi - o) / d
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return null
            }
            return tmin
        }

        /** Куда попал луч из (ox, oy) по направлению (dx, dy): голова/тело (что ближе) и расстояние. */
        fun classify(ox: Float, oy: Float, dx: Float, dy: Float, f: Fighter): Pair<Hit, Float> {
            val h = f.headBox().let { rayBox(ox, oy, dx, dy, it[0], it[1], it[2], it[3]) }
            val b = f.bodyBox().let { rayBox(ox, oy, dx, dy, it[0], it[1], it[2], it[3]) }
            return when {
                h != null && (b == null || h <= b) -> Hit.HEAD to h
                b != null -> Hit.BODY to b
                else -> Hit.MISS to 0f
            }
        }

        /** Пиксельный человечек 12×18 лицом вправо: шляпа (4 вида), лицо, усы, рубаха, ремень, штаны, сапоги. */
        fun sprite(hat: Int, tache: Boolean): List<String> {
            val hats = listOf(
                listOf("....HHHH....", "...HHHHHH...", "...hhhhhh...", ".HHHHHHHHHH."),   // ковбойская
                listOf("...HHHHHH...", "...HHHHHH...", "...hhhhhh...", "..HHHHHHHH.."),   // цилиндр
                listOf("............", "....HHHHH...", "...HHHHHHH..", "...HHHHHHHHH"),   // кепка
                listOf("............", "....HHHH....", "...HHHHHH...", "..HhhhhhhH.."),   // котелок
            )
            val head = listOf("...RSSSSS...", "...RSSSESS..", "...SSSSSSSS.", if (tache) "...SSMMMSS.." else "...SSSSSSS..", "....SSSS....")
            val body = listOf("...BBBBBB...", "..bBBBBBBB..", "..bBBBBBBB..", "..bBBBBBBB..", "..WWWWYWWW..")
            val legs = listOf("...PPPPPP...", "...PP..PP...", "...PP..PP...", "...KKK.KKK..")
            return hats[hat.mod(hats.size)] + head + body + legs
        }
    }

    var player = Fighter(0f, 0f, 1, 0, false, 1); private set
    lateinit var enemy: Fighter; private set
    /** Сражённые, ещё летящие вниз. */
    val corpses = ArrayList<Fighter>()
    val steps = ArrayList<Step>()
    val tracers = ArrayList<Tracer>()
    val popups = ArrayList<Popup>()
    var phase = Phase.SPAWN; private set
    var over by mutableStateOf(false)
    var aim = MIN_A
    private var aimDir = 1f
    var kills = 0; private set
    var score = 0; private set
    var coins = 0; private set
    var combo = 0; private set
    var bestCombo = 0; private set
    var headshots = 0; private set
    var time = 0f; private set
    private var timer = 0f
    /** 0…1 — противник выходит на площадку. */
    var spawnK = 0f; private set
    var camX = 0f; private set
    var camY = 0f; private set
    var shake = 0f; private set
    private var spotX = 0f; private var spotY = 0f
    private var climbX = 0f; private var climbY = 0f; private var dist = 3
    private var sameRun = 0
    val level get() = kills / 10

    init {
        for (k in -12..0) steps += Step(k * SW, k * SH)
        spawnEnemy()
        camX = (player.x + spotX) / 2; camY = (player.y + spotY) / 2 + 0.55f
    }

    fun aimSpeed() = (60f + kills * 2.5f).coerceAtMost(165f) * (if (enemy.boss) 1.15f else 1f)
    fun enemyDelay() = (0.8f - kills * 0.012f).coerceAtLeast(0.42f)

    private fun spawnEnemy() {
        val boss = (kills + 1) % 10 == 0
        val d = if (boss) 3 else 2 + rnd.nextInt(3)
        val f = player.facing
        for (k in 1..d) steps += Step(player.x + f * k * SW, player.y + k * SH)
        val e = Fighter(player.x + f * d * SW, player.y + d * SH, -f, if (boss) -1 - level else 1 + rnd.nextInt(1_000_000), boss, if (boss) 3 + kills / 20 else 1)
        if (boss) e.name = BOSSES[level % BOSSES.size]
        enemy = e; dist = d; spotX = e.x; spotY = e.y
        phase = Phase.SPAWN; timer = 0f; spawnK = 0f
        aim = MIN_A; aimDir = 1f
        if (steps.size > 90) steps.subList(0, steps.size - 90).clear()
    }

    /** Угол (в сторону взгляда) от плеча игрока к центру головы/тела противника. */
    fun angleTo(f: Fighter, head: Boolean): Float {
        val b = if (head) f.headBox() else f.bodyBox()
        val tx = (b[0] + b[2]) / 2; val ty = (b[1] + b[3]) / 2
        return Math.toDegrees(atan2((ty - player.shoulderY).toDouble(), ((tx - player.x) * player.facing).toDouble())).toFloat()
    }

    /** Выстрел игрока; null — сейчас стрелять нельзя. */
    fun shoot(): Hit? {
        if (over || phase != Phase.AIM) return null
        player.arm = aim
        val r = Math.toRadians(aim.toDouble())
        val dx = player.facing * cos(r).toFloat(); val dy = sin(r).toFloat()
        val (hit, t) = classify(player.x, player.shoulderY, dx, dy, enemy)
        val len = if (hit == Hit.MISS) 16f else t
        tracers += Tracer(player.muzzleX(), player.muzzleY(), player.x + dx * len, player.shoulderY + dy * len, false)
        shake = 0.35f
        if (hit == Hit.MISS) {
            combo = 0
            phase = Phase.ENEMY; timer = 0f
            popups += Popup("мимо!", player.x, player.y + 1.4f, false)
            return hit
        }
        val head = hit == Hit.HEAD
        if (head) { combo++; headshots++; bestCombo = maxOf(bestCombo, combo) } else combo = 0
        enemy.hp -= if (head) 2 else 1
        score += if (head) 1 + combo else 1
        coins += if (head) 2 else 1
        val hy = player.shoulderY + dy * t
        popups += Popup(if (head) (if (combo > 1) "В ГОЛОВУ! ×$combo" else "В ГОЛОВУ!") else "+1", enemy.x, hy + 0.3f, head)
        if (enemy.hp <= 0) kill() else { enemy.flinch = 1f; aim = MIN_A; aimDir = 1f }
        return hit
    }

    private fun kill() {
        kills++
        val e = enemy
        e.vx = player.facing * (1.0f + rnd.nextFloat() * 0.8f); e.vy = 3.4f
        e.rotV = player.facing * (380f + rnd.nextFloat() * 200f)
        corpses += e
        if (e.boss) { coins += 10; score += 5; popups += Popup("${e.name} повержен! +10 монет", e.x, e.y + 2f, true) }
        phase = Phase.KILL; timer = 0f
    }

    private fun fall(f: Fighter, dt: Float) {
        f.vy -= 9.8f * dt; f.x += f.vx * dt; f.y += f.vy * dt; f.rot += f.rotV * dt
    }

    private fun approach(v: Float, to: Float, k: Float) = v + (to - v) * k.coerceIn(0f, 1f)

    /** Шаг; события: EV_ENEMY_SHOT — противник выстрелил (игрок убит), EV_NEW_ENEMY/EV_BOSS — вышел следующий. */
    fun step(dt: Float): Int {
        time += dt
        var ev = 0
        tracers.forEach { it.age += dt }; tracers.removeAll { it.age > 0.25f }
        popups.forEach { it.age += dt }; popups.removeAll { it.age > 1.1f }
        shake = (shake - dt * 3f).coerceAtLeast(0f)
        corpses.forEach { fall(it, dt) }; corpses.removeAll { it.y < camY - 14f }
        if (::enemy.isInitialized) enemy.flinch = (enemy.flinch - dt * 4f).coerceAtLeast(0f)
        when (phase) {
            Phase.SPAWN -> {
                timer += dt; spawnK = (timer / 0.45f).coerceAtMost(1f)
                player.arm = approach(player.arm, MIN_A, dt * 10f)
                enemy.arm = -70f
                if (timer >= 0.45f) { phase = Phase.AIM; player.arm = MIN_A }
            }
            Phase.AIM -> {
                aim += aimDir * aimSpeed() * dt
                if (aim > MAX_A) { aim = 2 * MAX_A - aim; aimDir = -1f }
                if (aim < MIN_A) { aim = 2 * MIN_A - aim; aimDir = 1f }
                player.arm = aim
            }
            Phase.ENEMY -> {
                timer += dt
                val d = enemyDelay()
                val tx = (player.x - enemy.x) * enemy.facing
                val ty = player.y + BODY_H * 0.7f - enemy.shoulderY
                val target = Math.toDegrees(atan2(ty.toDouble(), tx.toDouble())).toFloat()
                enemy.arm = -70f + (target + 70f) * (timer / (d * 0.75f)).coerceAtMost(1f)
                if (timer >= d) {
                    enemy.arm = target
                    tracers += Tracer(enemy.muzzleX(), enemy.muzzleY(), player.x, player.y + BODY_H * 0.7f, true)
                    player.vx = -player.facing * 1.3f; player.vy = 3f; player.rotV = -player.facing * 420f
                    phase = Phase.DEAD; timer = 0f; shake = 1f
                    ev = EV_ENEMY_SHOT
                }
            }
            Phase.DEAD -> {
                timer += dt
                fall(player, dt)
                if (timer > 1.3f) over = true
            }
            Phase.KILL -> {
                timer += dt
                player.arm = approach(player.arm, -55f, dt * 8f)
                if (timer > 0.45f) { phase = Phase.CLIMB; timer = 0f; climbX = player.x; climbY = player.y }
            }
            Phase.CLIMB -> {
                timer += dt
                val dur = dist * 0.17f
                val p = (timer / dur).coerceAtMost(1f)
                val s = p * dist
                val idx = floor(s).toInt().coerceAtMost(dist - 1); val fr = s - idx
                player.x = climbX + player.facing * SW * s
                player.y = climbY + SH * (idx + minOf(1f, fr * 1.6f)) + sin(PI.toFloat() * fr) * 0.1f
                player.arm = -55f
                if (p >= 1f) {
                    player.x = spotX; player.y = spotY
                    val flip = sameRun >= 2 || (sameRun >= 1 && rnd.nextFloat() < 0.4f)
                    if (flip) {
                        steps += Step(player.x + player.facing * SW, player.y)   // площадка поворота
                        player.facing = -player.facing; sameRun = 0
                    } else sameRun++
                    spawnEnemy()
                    ev = if (enemy.boss) EV_BOSS else EV_NEW_ENEMY
                }
            }
        }
        // камера — посередине между игроком и площадкой противника
        val tx = (player.x + spotX) / 2; val ty = (player.y + spotY) / 2 + 0.55f
        val k = if (phase == Phase.DEAD) 0f else (dt * 4f)
        camX = approach(camX, tx, k); camY = approach(camY, ty, k)
        return ev
    }
}

private class DuelLook(val hat: Int, val hatC: Long, val band: Long, val skin: Long, val shirt: Long, val pants: Long, val tache: Boolean, val hair: Long)

private fun lookOf(id: Int): DuelLook = when {
    id == 0 -> DuelLook(0, 0xFFF2E3C6, 0xFF7A4B2A, 0xFFF2C29B, 0xFF3D7BD9, 0xFF2E3550, false, 0xFF6B3E1E)
    id < 0 -> {
        val shirts = listOf(0xFFC0392B, 0xFF7D3C98, 0xFF1E8449, 0xFFB9770E, 0xFF2C3E50, 0xFF943126)
        DuelLook(1, 0xFF22202A, 0xFFF2C94C, 0xFFE8B48A, shirts[(-id - 1) % shirts.size], 0xFF1C1B22, true, 0xFF2A1A12)
    }
    else -> {
        val r = Random(id)
        val hats = listOf(0xFF6B3E1E, 0xFF2B2A33, 0xFF8E5BD8, 0xFFB65E2E, 0xFF3FB27F, 0xFFE5485D, 0xFF4A86E8, 0xFFDDD0B0)
        val shirts = listOf(0xFFE5485D, 0xFF3FB27F, 0xFFF2C94C, 0xFF8E5BD8, 0xFFFF8A4C, 0xFF5AA9E6, 0xFFB0B7C3, 0xFFD6559A)
        val skins = listOf(0xFFF2C29B, 0xFFE0A87C, 0xFFC68642, 0xFF8D5524, 0xFFFFDBB0)
        val pants = listOf(0xFF2E3550, 0xFF4E3B2A, 0xFF35424A, 0xFF5B4E7A)
        DuelLook(r.nextInt(4), hats.random(r), listOf(0xFF2B2A33, 0xFFF2C94C, 0xFFE5485D, 0xFFFFFFFF).random(r), skins.random(r), shirts.random(r), pants.random(r),
            r.nextBoolean(), listOf(0xFF2A1A12, 0xFF6B3E1E, 0xFFB5762B, 0xFF1C1B22).random(r))
    }
}

/** Картинка человечка 14×20: спрайт + обводка в 1 пиксель. */
private fun spriteBitmap(l: DuelLook): ImageBitmap {
    val rows = DuelGame.sprite(l.hat, l.tache)
    val w = 14; val h = 20
    val px = IntArray(w * h)
    fun shade(c: Long, k: Float): Int { val col = Color(c); return Color(col.red * k, col.green * k, col.blue * k).toArgb() }
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            val c: Int = when (ch) {
                'H' -> Color(l.hatC).toArgb(); 'h' -> Color(l.band).toArgb()
                'S' -> Color(l.skin).toArgb(); 'E' -> 0xFF15131C.toInt(); 'R', 'M' -> Color(l.hair).toArgb()
                'B' -> Color(l.shirt).toArgb(); 'b' -> shade(l.shirt, 0.72f)
                'W' -> 0xFF3A2A1E.toInt(); 'Y' -> 0xFFF2C94C.toInt()
                'P' -> Color(l.pants).toArgb(); 'K' -> 0xFF2A1A12.toInt()
                else -> 0
            }
            px[(y + 1) * w + x + 1] = c
        }
    }
    val out = px.copyOf()
    val ink = 0xFF15131C.toInt()
    for (y in 0 until h) for (x in 0 until w) {
        if (px[y * w + x] != 0) continue
        val near = (x > 0 && px[y * w + x - 1] != 0) || (x < w - 1 && px[y * w + x + 1] != 0) || (y > 0 && px[(y - 1) * w + x] != 0) || (y < h - 1 && px[(y + 1) * w + x] != 0)
        if (near) out[y * w + x] = ink
    }
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

/** Палитры уровней: небо (верх/низ), ступень (проступь/тело), дальние горы, солнце. */
private class DuelPal(val top: Long, val bottom: Long, val tread: Long, val side: Long, val far: Long, val sun: Long)
private val DUEL_PALS = listOf(
    DuelPal(0xFFFFC07A, 0xFFFF7E6B, 0xFF8A5FB0, 0xFF5B3A7A, 0xFFE0607A, 0xFFFFF0B0),   // закат
    DuelPal(0xFF7FD6E8, 0xFFC4F3DC, 0xFF48A88F, 0xFF2E7D6B, 0xFF8CCFC0, 0xFFFFFFFF),   // мята
    DuelPal(0xFF9C8CFF, 0xFFFFB3D1, 0xFF6F62C2, 0xFF4A3F8F, 0xFFC79BE0, 0xFFFFE6F2),   // сирень
    DuelPal(0xFF1B2A4E, 0xFF3E5C9A, 0xFF4E6FB0, 0xFF22345E, 0xFF2C4272, 0xFFF2F0D8),   // ночь
    DuelPal(0xFFFFE07A, 0xFFFFA94D, 0xFFD9774A, 0xFFA8502A, 0xFFF0B060, 0xFFFFFFF0),   // пустыня
    DuelPal(0xFF4DD0E1, 0xFFE0F7FA, 0xFF7C9CD0, 0xFF4F6FA6, 0xFFA8D8E8, 0xFFFFFFFF),   // лёд
)

private fun dHash(i: Int, k: Int): Float {
    var h = i * 73856093 xor k * 19349663
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
}

@Composable
fun DuelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("duel", Context.MODE_PRIVATE) }
    var g by remember { mutableStateOf(DuelGame()) }
    var started by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(Hi.get(ctx, "duel")) }
    var record by remember { mutableStateOf(false) }
    var bank by remember { mutableIntStateOf(prefs.getInt("coins", 0)) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    val sprites = remember { HashMap<Int, Pair<ImageBitmap, DuelLook>>() }
    fun sprite(id: Int) = sprites.getOrPut(id) { val l = lookOf(id); spriteBitmap(l) to l }
    fun play(k: String, pcm: () -> ShortArray) { if (sound) runCatching { Sfx.playPcm("duel_$k", pcm()) } }

    GameLoop(started && !g.over) { dt ->
        when (g.step(dt)) {
            DuelGame.EV_ENEMY_SHOT -> { play("enemy") { DuelSfx.enemyShot }; play("fall") { DuelSfx.fall } }
            DuelGame.EV_BOSS -> play("boss") { DuelSfx.boss }
        }
        tick++
        if (g.over) {
            if (Hi.submit(ctx, "duel", g.score)) { best = g.score; record = true }
            bank += g.coins; prefs.edit().putInt("coins", bank).apply()
        }
        if (sprites.size > 40) sprites.keys.filter { it > 0 && it != g.enemy.look }.forEach { sprites.remove(it) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFFF9E70))) {
        Canvas(Modifier.fillMaxSize().pointerInput(g, started) {
            detectTapGestures(onPress = {
                if (started && !g.over) when (g.shoot()) {
                    DuelGame.Hit.HEAD -> { play("shot") { DuelSfx.shot }; play("ding") { DuelSfx.ding } }
                    DuelGame.Hit.BODY -> { play("shot") { DuelSfx.shot }; play("hit") { DuelSfx.hit } }
                    DuelGame.Hit.MISS -> play("shot") { DuelSfx.shot }
                    null -> {}
                }
            })
        }) {
            @Suppress("UNUSED_EXPRESSION") tick
            val W = size.width; val H = size.height
            val pal = DUEL_PALS[g.level % DUEL_PALS.size]
            val k = W / 6.2f
            val shx = if (g.shake > 0) (Random.nextFloat() - 0.5f) * k * 0.08f * g.shake else 0f
            val shy = if (g.shake > 0) (Random.nextFloat() - 0.5f) * k * 0.08f * g.shake else 0f
            fun sx(x: Float) = W / 2 + (x - g.camX) * k + shx
            fun sy(y: Float) = H * 0.55f - (y - g.camY) * k + shy

            drawRect(Brush.verticalGradient(listOf(Color(pal.top), Color(pal.bottom))))
            // пиксельное солнце/луна
            val sunC = Offset(W * 0.74f - g.camX * k * 0.03f % W, H * 0.2f)
            val blk = k * 0.09f
            for (yy in -5..5) for (xx in -5..5) if (xx * xx + yy * yy <= 26)
                drawRect(Color(pal.sun).copy(alpha = if (xx * xx + yy * yy > 18) 0.6f else 0.95f), Offset(sunC.x + xx * blk, sunC.y + yy * blk), Size(blk + 0.5f, blk + 0.5f))
            // облака из блоков (вертикально — по кругу)
            for (j in 0 until 6) {
                val span = W * 1.6f
                val x = ((dHash(j, 1) * span - g.camX * k * 0.12f + g.time * k * 0.08f) % span + span) % span - W * 0.3f
                val vs = H * 0.75f
                val y = ((dHash(j, 2) * vs + g.camY * k * 0.25f) % vs + vs) % vs - H * 0.05f
                val c = Color.White.copy(alpha = 0.55f)
                val b = k * 0.18f
                drawRect(c, Offset(x, y), Size(b * 6, b * 2))
                drawRect(c, Offset(x + b, y - b), Size(b * 3, b))
                drawRect(c, Offset(x + b * 2, y - b * 2), Size(b * 2, b))
                drawRect(c, Offset(x - b, y + b), Size(b * 8, b))
            }
            // дальние столовые горы — параллакс по горизонтали
            val tile = W * 0.26f
            val off = -(g.camX * k * 0.18f)
            val first = floor(-off / tile).toInt() - 1
            for (j in first..first + 6) {
                val x = j * tile + off
                val hh = H * (0.12f + 0.22f * dHash(j, 5))
                val base = H * 0.86f
                drawRect(Color(pal.far), Offset(x, base - hh), Size(tile * (0.7f + 0.3f * dHash(j, 6)), H))
                drawRect(Color(pal.far).copy(alpha = 0.5f), Offset(x + tile * 0.15f, base - hh - k * 0.12f), Size(tile * 0.35f, k * 0.12f))
            }
            // лестница: каждая ступень — столб до низа экрана, новые перекрывают старые
            g.steps.forEach { s ->
                val l = sx(s.x - DuelGame.SW / 2); val r = sx(s.x + DuelGame.SW / 2); val t = sy(s.y)
                if (r < 0 || l > W || t > H) return@forEach
                drawRect(Color(pal.side), Offset(l, t), Size(r - l + 1f, H - t + k))
                drawRect(Color(pal.tread), Offset(l, t), Size(r - l + 1f, k * 0.1f))
                drawRect(Color.Black.copy(alpha = 0.18f), Offset(l, t + k * 0.1f), Size(r - l + 1f, k * 0.05f))
                drawRect(Color.White.copy(alpha = 0.12f), Offset(l + k * 0.12f, t + k * 0.24f), Size(k * 0.12f, k * 0.12f))
                drawRect(Color.Black.copy(alpha = 0.1f), Offset(r - k * 0.3f, t + k * 0.5f), Size(k * 0.12f, k * 0.12f))
            }
            // противник
            val e = g.enemy
            if (g.phase != DuelGame.Phase.KILL && g.phase != DuelGame.Phase.CLIMB) {
                val inK = g.spawnK
                val walk = if (inK < 1f) abs(sin(g.time * 18f)) * k * 0.04f else 0f
                val ox = -e.facing * (1f - inK) * k * 1.6f - e.facing * e.flinch * k * 0.12f
                fighter(e, sprite(e.look), sx(e.x) + ox, sy(e.y) - walk, k, inK)
                if (e.boss) {
                    val bw = k * 0.75f; val top = sy(e.y + DuelGame.HEAD_TOP * e.scale) - k * 0.28f
                    val seg = bw / e.maxHp
                    for (i in 0 until e.maxHp) drawRect(if (i < e.hp) Color(0xFFE5485D) else Color(0x55000000), Offset(sx(e.x) + ox - bw / 2 + i * seg + 1f, top), Size(seg - 2f, k * 0.1f))
                }
            }
            // игрок
            val p = g.player
            fighter(p, sprite(0), sx(p.x), sy(p.y), k, 1f)
            // лазерный прицел
            if (g.phase == DuelGame.Phase.AIM) {
                val r = Math.toRadians(g.aim.toDouble())
                val dx = p.facing * cos(r).toFloat(); val dy = sin(r).toFloat()
                for (i in 0 until 7) {
                    val d0 = DuelGame.ARM_LEN + 0.12f + i * 0.2f
                    drawRect(Color.White.copy(alpha = 0.75f - i * 0.09f), Offset(sx(p.x + dx * d0) - k * 0.025f, sy(p.shoulderY + dy * d0) - k * 0.025f), Size(k * 0.05f, k * 0.05f))
                }
            }
            // сражённые летят вниз
            g.corpses.forEach { c -> fighter(c, sprite(c.look), sx(c.x), sy(c.y), k, 1f) }
            // трассеры и вспышки
            g.tracers.forEach { t ->
                val a = (1f - t.age / 0.25f).coerceIn(0f, 1f)
                val c = if (t.enemy) Color(0xFFFF6B5E) else Color(0xFFFFF3B0)
                drawLine(c.copy(alpha = a), Offset(sx(t.x0), sy(t.y0)), Offset(sx(t.x1), sy(t.y1)), k * 0.05f)
                if (t.age < 0.07f) {
                    drawCircle(Color(0xFFFFE07A), k * 0.14f, Offset(sx(t.x0), sy(t.y0)))
                    drawCircle(Color.White, k * 0.07f, Offset(sx(t.x0), sy(t.y0)))
                }
                if (t.age < 0.12f && !t.enemy) drawCircle(Color.White.copy(alpha = a), k * 0.06f, Offset(sx(t.x1), sy(t.y1)))
            }
            g.popups.forEach { pp ->
                val a = (1f - pp.age / 1.1f).coerceIn(0f, 1f)
                pixelText(pp.text, sx(pp.x), sy(pp.y) - pp.age * k * 0.6f, k * (if (pp.gold) 0.3f else 0.24f), if (pp.gold) Color(0xFFFFD34D) else Color.White, a)
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp, 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack, bg = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                key(tick / 4) {
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x55000000)).padding(16.dp, 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${g.score}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("уровень ${g.level + 1} · сражено ${g.kills} · ● ${bank + if (g.over) 0 else g.coins}", color = Color(0xFFFFF0D0), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                RoundIconButton(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Звук",
                    { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }, bg = Color.White.copy(alpha = 0.85f))
            }
            key(tick / 4) {
                val e = g.enemy
                if (started && e.boss && g.phase != DuelGame.Phase.KILL && g.phase != DuelGame.Phase.CLIMB)
                    Text("БОСС: ${e.name} · ${"♥".repeat(e.hp.coerceAtLeast(0))}", color = Color(0xFFFFE07A), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x88000000)).padding(12.dp, 4.dp))
                else if (g.combo > 1) Text("серия в голову ×${g.combo}", color = Color(0xFFFFE07A), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            }
        }

        if (!started) GameOverlay("Дуэль",
            "Рука с револьвером ходит вверх-вниз сама. Нажми — выстрел по линии прицела. Промахнёшься — противник стреляет в ответ. В голову — больше очков и серия. Каждый 10-й — босс.\nРекорд: $best",
            "Старт") { started = true }
        if (g.over) GameOverlay(if (record) "Новый рекорд!" else "Подстрелили!",
            "Очки ${g.score} · сражено ${g.kills} · в голову ${g.headshots}\nЛучшая серия ×${g.bestCombo} · монет +${g.coins}\nРекорд $best",
            "Ещё раз") { g = DuelGame(); record = false; tick++ }
    }
}

/** Человечек: пиксельная картинка + рука с револьвером под углом arm; зеркалится по facing, кувыркается по rot. */
private fun DrawScope.fighter(f: DuelGame.Fighter, s: Pair<ImageBitmap, DuelLook>, fx: Float, fy: Float, k: Float, alpha: Float) {
    val p = k * (1.2f / 18f) * f.scale
    val (img, look) = s
    withTransform({
        rotate(f.rot, Offset(fx, fy - 9 * p))
        scale(f.facing.toFloat(), 1f, Offset(fx, fy))
    }) {
        drawImage(img, IntOffset.Zero, IntSize(14, 20), IntOffset((fx - 7 * p).roundToInt(), (fy - 19 * p).roundToInt()),
            IntSize((14 * p).roundToInt(), (20 * p).roundToInt()), alpha = alpha, filterQuality = FilterQuality.None)
        val sh = Offset(fx, fy - DuelGame.SHOULDER * f.scale * k)
        rotate(-f.arm, sh) {
            val ink = Color(0xFF15131C).copy(alpha = alpha)
            val parts = listOf(
                floatArrayOf(-0.5f, -0.8f, 3.2f, 0.8f) to Color(look.shirt),      // рукав
                floatArrayOf(3.0f, -0.7f, 4.2f, 0.7f) to Color(look.skin),        // кисть
                floatArrayOf(3.6f, -1.0f, 7.0f, 0.1f) to Color(0xFF3A3F4A),        // ствол
                floatArrayOf(3.8f, -0.2f, 4.9f, 1.7f) to Color(0xFF6B3E1E),        // рукоять
                floatArrayOf(4.4f, -1.4f, 5.6f, -0.9f) to Color(0xFF8A909C),       // барабан
            )
            parts.forEach { (r, _) -> drawRect(ink, Offset(sh.x + (r[0] - 0.5f) * p, sh.y + (r[1] - 0.5f) * p), Size((r[2] - r[0] + 1f) * p, (r[3] - r[1] + 1f) * p)) }
            parts.forEach { (r, c) -> drawRect(c.copy(alpha = alpha), Offset(sh.x + r[0] * p, sh.y + r[1] * p), Size((r[2] - r[0]) * p, (r[3] - r[1]) * p)) }
            drawRect(Color.White.copy(alpha = 0.35f * alpha), Offset(sh.x + 4f * p, sh.y - 0.9f * p), Size(2.6f * p, 0.3f * p))
        }
    }
}

private fun DrawScope.pixelText(s: String, x: Float, y: Float, sizePx: Float, color: Color, alpha: Float) {
    val a = (alpha * 255).toInt()
    val stroke = android.graphics.Paint().apply {
        textSize = sizePx; typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER; style = android.graphics.Paint.Style.STROKE; strokeWidth = sizePx * 0.22f
        strokeJoin = android.graphics.Paint.Join.MITER; this.color = android.graphics.Color.argb(a, 21, 19, 28)
    }
    val fill = android.graphics.Paint(stroke).apply { style = android.graphics.Paint.Style.FILL; this.color = color.copy(alpha = alpha).toArgb() }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, stroke)
    drawContext.canvas.nativeCanvas.drawText(s, x, y, fill)
}

/** Звуки дуэли — синтез: хлопок выстрела, глухой удар, звон «в голову», выстрел противника, падение, выход босса. */
private object DuelSfx {
    private class Syn(sec: Double) {
        val a = DoubleArray((sec * Sfx.SR).toInt())
        private val r = java.util.Random(5)
        fun noise(at: Double, dur: Double, amp: Double, cut: (Double) -> Double, env: (Double) -> Double) {
            val s0 = (at * Sfx.SR).toInt(); val n = (dur * Sfx.SR).toInt(); var y = 0.0; var y2 = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break
                val x = i.toDouble() / n
                val k = (2 * PI * cut(x) / Sfx.SR).let { it / (it + 1) }
                y += k * ((r.nextDouble() * 2 - 1) - y); y2 += k * (y - y2)
                a[j] += y2 * 3 * amp * env(x)
            }
        }
        fun tone(at: Double, dur: Double, amp: Double, f: (Double) -> Double, env: (Double) -> Double) {
            val s0 = (at * Sfx.SR).toInt(); val n = (dur * Sfx.SR).toInt(); var ph = 0.0
            for (i in 0 until n) {
                val j = s0 + i; if (j !in a.indices) break
                val x = i.toDouble() / n
                ph += 2 * PI * f(x) / Sfx.SR
                a[j] += sin(ph) * amp * env(x)
            }
        }
        fun pcm(gain: Double = 0.8): ShortArray {
            val peak = a.maxOf { abs(it) }.coerceAtLeast(1e-6)
            val fade = (0.003 * Sfx.SR).toInt()
            return ShortArray(a.size) { i ->
                val e = minOf(1.0, i.toDouble() / fade, (a.size - 1 - i).toDouble() / fade)
                (a[i] / peak * gain * e * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }
    val shot by lazy {
        Syn(0.35).apply {
            noise(0.0, 0.3, 1.0, { 5000 - 4200 * it }) { exp(-it * 9) }
            tone(0.0, 0.18, 0.8, { 150 - 90 * it }) { exp(-it * 6) }
        }.pcm(0.75)
    }
    val enemyShot by lazy {
        Syn(0.45).apply {
            noise(0.0, 0.4, 1.0, { 2600 - 2200 * it }) { exp(-it * 7) }
            tone(0.0, 0.25, 0.9, { 110 - 60 * it }) { exp(-it * 5) }
        }.pcm(0.8)
    }
    val hit by lazy {
        Syn(0.18).apply {
            tone(0.0, 0.16, 0.9, { 220 - 120 * it }) { exp(-it * 7) }
            noise(0.0, 0.05, 0.5, { 1200.0 }) { 1 - it }
        }.pcm(0.7)
    }
    val ding by lazy {
        Syn(0.6).apply {
            tone(0.03, 0.55, 0.6, { 1760.0 }) { exp(-it * 6) }
            tone(0.03, 0.55, 0.35, { 2637.0 }) { exp(-it * 8) }
            tone(0.03, 0.55, 0.15, { 3520.0 }) { exp(-it * 10) }
        }.pcm(0.6)
    }
    val fall by lazy {
        Syn(0.9).apply {
            tone(0.1, 0.7, 0.6, { 600 - 450 * it }) { x -> minOf(1.0, x * 12) * (1 - x) }
            noise(0.7, 0.2, 0.6, { 500.0 }) { exp(-it * 5) }
        }.pcm(0.6)
    }
    val boss by lazy {
        Syn(0.9).apply {
            tone(0.0, 0.4, 0.6, { 196.0 }) { x -> minOf(1.0, x * 20) * exp(-x * 2) }
            tone(0.0, 0.4, 0.3, { 392.0 }) { x -> minOf(1.0, x * 20) * exp(-x * 3) }
            tone(0.4, 0.5, 0.6, { 147.0 }) { x -> minOf(1.0, x * 20) * exp(-x * 3) }
            tone(0.4, 0.5, 0.3, { 294.0 }) { x -> minOf(1.0, x * 20) * exp(-x * 4) }
        }.pcm(0.6)
    }
}
