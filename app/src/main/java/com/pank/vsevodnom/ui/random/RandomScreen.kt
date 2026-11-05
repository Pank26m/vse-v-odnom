package com.pank.vsevodnom.ui.random

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val rnd = SecureRandom()

@Composable
fun RandomScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold("Рандом", onBack) {
        Segmented(listOf("Монетка", "Число", "Кубики", "Список", "Да/Нет"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp), track = Pal.Peach)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (tab) {
                0 -> Coin()
                1 -> NumberTab()
                2 -> Dice()
                3 -> ListPick()
                else -> YesNo()
            }
        }
    }
}

@Composable
private fun BigResult(text: String, sub: String = "", bg: Color = Pal.Peach) {
    AppCard(color = bg, radius = 32.dp, padding = 24.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 172.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.displayLarge.copy(fontSize = if (text.length > 8) 40.sp else 72.sp), textAlign = TextAlign.Center)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Pal.PeachInk, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Coin() {
    var heads by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var stats by rememberSaveable { mutableStateOf(0 to 0) }
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier.size(220.dp).graphicsLayer { rotationY = rot.value; cameraDistance = 12 * density }
            .clip(CircleShape).background(Color(0xFFF6D77B)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(190.dp).clip(CircleShape).background(Color(0xFFFCE7A6)), contentAlignment = Alignment.Center) {
            Text(
                when { heads == null -> "?"; rot.isRunning -> ""; heads == true -> "Орёл"; else -> "Решка" },
                style = MaterialTheme.typography.headlineLarge, color = Pal.ButterInk
            )
        }
    }
    Spacer(Modifier.height(32.dp))
    PillButton("Подбросить", {
        scope.launch {
            val r = rnd.nextBoolean()
            heads = r
            rot.snapTo(0f)
            rot.animateTo(360f * 5, tween(1200, easing = FastOutSlowInEasing))
            rot.snapTo(0f)
            stats = if (r) stats.copy(first = stats.first + 1) else stats.copy(second = stats.second + 1)
        }
    }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Text("Орёл ${stats.first} · Решка ${stats.second}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NumberTab() {
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf("100") }
    var count by rememberSaveable { mutableStateOf("1") }
    var unique by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("—") }
    var err by rememberSaveable { mutableStateOf("") }
    BigResult(result, err)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTextField(from, { from = it }, "От", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
        AppTextField(to, { to = it }, "До", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
        AppTextField(count, { count = it }, "Сколько", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Без повторов", unique, { unique = true })
        Chip("С повторами", !unique, { unique = false })
    }
    Spacer(Modifier.height(16.dp))
    PillButton("Сгенерировать", {
        err = ""
        val a = from.trim().toLongOrNull(); val b = to.trim().toLongOrNull(); val n = count.trim().toIntOrNull() ?: 1
        if (a == null || b == null) { err = "Введи числа"; return@PillButton }
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val span = hi - lo + 1
        if (n < 1 || n > 1000) { err = "От 1 до 1000 чисел"; return@PillButton }
        if (unique && n > span) { err = "В диапазоне только $span чисел"; return@PillButton }
        val out = if (unique) {
            val set = LinkedHashSet<Long>()
            while (set.size < n) set.add(lo + (rnd.nextDouble() * span).toLong().coerceAtMost(span - 1))
            set.toList()
        } else List(n) { lo + (rnd.nextDouble() * span).toLong().coerceAtMost(span - 1) }
        result = out.joinToString(", ")
    }, Modifier.fillMaxWidth())
}

private val PIPS = mapOf(
    1 to listOf(4), 2 to listOf(0, 8), 3 to listOf(0, 4, 8),
    4 to listOf(0, 2, 6, 8), 5 to listOf(0, 2, 4, 6, 8), 6 to listOf(0, 2, 3, 5, 6, 8)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dice() {
    var n by rememberSaveable { mutableIntStateOf(2) }
    var values by rememberSaveable { mutableStateOf(listOf(1, 1)) }
    val scope = rememberCoroutineScope()
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        values.forEach { v ->
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Pal.Card).padding(14.dp)) {
                Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                    for (r in 0..2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (c in 0..2) Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .background(if ((r * 3 + c) in PIPS.getValue(v)) Pal.Ink else Color.Transparent)
                        )
                    }
                }
            }
        }
    }
    Text("Сумма: ${values.sum()}", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..6).forEach { k -> Chip("$k", n == k, { n = k; values = List(k) { 1 } }) }
    }
    Spacer(Modifier.height(16.dp))
    PillButton("Бросить", {
        scope.launch {
            repeat(8) { values = List(n) { rnd.nextInt(6) + 1 }; delay(60L + it * 15) }
        }
    }, Modifier.fillMaxWidth())
}

@Composable
private fun ListPick() {
    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("—") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    BigResult(result)
    Spacer(Modifier.height(16.dp))
    AppTextField(text, { text = it }, "Варианты — каждый с новой строки", singleLine = false, modifier = Modifier.heightIn(min = 140.dp))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Один вариант", mode == 0, { mode = 0 })
        Chip("Перемешать всё", mode == 1, { mode = 1 })
    }
    Spacer(Modifier.height(16.dp))
    PillButton("Выбрать", {
        val items = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        result = when {
            items.isEmpty() -> "Пусто"
            mode == 0 -> items[rnd.nextInt(items.size)]
            else -> items.shuffled(rnd).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        }
    }, Modifier.fillMaxWidth())
}

@Composable
private fun YesNo() {
    var r by rememberSaveable { mutableStateOf("?") }
    BigResult(r, bg = if (r == "Да") Pal.Mint else if (r == "Нет") Pal.Rose else Pal.Peach)
    Spacer(Modifier.height(24.dp))
    PillButton("Спросить", { r = if (rnd.nextBoolean()) "Да" else "Нет" }, Modifier.fillMaxWidth())
}
