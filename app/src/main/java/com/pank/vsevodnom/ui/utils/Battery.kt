package com.pank.vsevodnom.ui.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.common.SectionLabel
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/** Паспортная ёмкость из системного профиля питания (скрытый API, может не сработать). */
private fun designCapacityMah(ctx: Context): Double? = runCatching {
    val cls = Class.forName("com.android.internal.os.PowerProfile")
    val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
    (cls.getMethod("getBatteryCapacity").invoke(pp) as Double).takeIf { it > 500 }
}.getOrNull()

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v).replace('.', ',')

@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    var intent by remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { intent = i } }
        intent = ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }
    val currents = remember { mutableStateListOf<Double>() }
    var currentMa by remember { mutableDoubleStateOf(0.0) }
    var chargeUah by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // знак и единицы зависят от производителя: на Pixel — микроамперы, «+» при зарядке
            val ma = if (abs(raw) > 20_000) raw / 1000.0 else raw.toDouble()
            currentMa = ma
            currents.add(ma); if (currents.size > 90) currents.removeAt(0)
            chargeUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            delay(1000)
        }
    }
    val i = intent
    val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
    val voltage = (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
    val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
    val design = remember { designCapacityMah(ctx) }
    val estFull = if (chargeUah > 0 && level in 15..100) chargeUah / 1000.0 / level * 100 else null
    val powerW = abs(currentMa) * voltage / 1000

    ScreenScaffold("Батарея", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), color = Pal.Mint, radius = 28.dp) {
                    SectionLabel(
                        when (status) {
                            BatteryManager.BATTERY_STATUS_CHARGING -> "Заряжается · " + when (plugged) {
                                BatteryManager.BATTERY_PLUGGED_AC -> "от сети"; BatteryManager.BATTERY_PLUGGED_USB -> "от USB"
                                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "беспроводная"; else -> "кабель"
                            }
                            BatteryManager.BATTERY_STATUS_FULL -> "Заряжена"
                            else -> "Разряжается"
                        }, Pal.MintInk
                    )
                    Text("$level%", style = MaterialTheme.typography.displayLarge)
                    Text("${f1(abs(currentMa))} мА · ${f1(powerW)} Вт", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                        if (currents.size < 2) return@Canvas
                        val lo = minOf(0.0, currents.min()); val hi = maxOf(0.0, currents.max()); val span = (hi - lo).takeIf { it > 1 } ?: 1.0
                        fun y(v: Double) = size.height - ((v - lo) / span).toFloat() * size.height
                        drawLine(Pal.MintInk.copy(alpha = 0.25f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f)
                        val p = Path()
                        currents.forEachIndexed { k, v -> val x = k / 89f * size.width; if (k == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
                        drawPath(p, Pal.MintInk.copy(alpha = 0.7f), style = Stroke(4f))
                    }
                    Text("Ток за последние 90 секунд", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                InfoGroup("Состояние", buildList {
                    add("Температура" to "${f1(temp)} °C" + if (temp >= 40) " — горячо" else "")
                    add("Напряжение" to "${String.format(Locale.US, "%.2f", voltage).replace('.', ',')} В")
                    add("Здоровье (система)" to when (health) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "неисправна"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "перенапряжение"
                        BatteryManager.BATTERY_HEALTH_COLD -> "холодная"; else -> "неизвестно"
                    })
                    cycles?.let { add("Циклов зарядки" to "$it") }
                    i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { add("Тип" to it) }
                })
            }
            item {
                InfoGroup("Ёмкость", buildList {
                    if (chargeUah > 0) add("Сейчас в батарее" to "${(chargeUah / 1000)} мА·ч")
                    estFull?.let { add("Полная ёмкость (оценка)" to "${it.toInt()} мА·ч") }
                    design?.let { add("Паспортная" to "${it.toInt()} мА·ч") }
                    if (estFull != null && design != null) add("Износ (оценка)" to "${((1 - estFull / design) * 100).coerceIn(0.0, 100.0).toInt()}%")
                })
            }
            item {
                Text("Оценка ёмкости точнее при заряде 50–100% и после пары полных циклов. Системный счётчик заряда на некоторых телефонах округляет значения.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
