package com.pank.vsevodnom.ui.clock

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.*
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*

// ================= Фокус и лес =================

/** Дерево: вид 0…3, живое или засохшее (если сессию бросили). */
fun DrawScope.tree(kind: Int, alive: Boolean) {
    val w = size.width; val h = size.height
    val trunk = if (alive) Color(0xFF9A6B45) else Color(0xFF8C8478)
    val leaf = if (!alive) Color(0xFFC9C1B2) else listOf(Color(0xFF7CC59A), Color(0xFF5FB287), Color(0xFF9AD3A8), Color(0xFFB4D98A))[kind % 4]
    drawRect(trunk, Offset(w * 0.45f, h * 0.55f), Size(w * 0.1f, h * 0.35f))
    when (kind % 4) {
        0 -> drawCircle(leaf, w * 0.3f, Offset(w / 2, h * 0.4f))
        1 -> drawPath(Path().apply { moveTo(w / 2, h * 0.05f); lineTo(w * 0.85f, h * 0.65f); lineTo(w * 0.15f, h * 0.65f); close() }, leaf)
        2 -> { drawCircle(leaf, w * 0.22f, Offset(w * 0.38f, h * 0.45f)); drawCircle(leaf, w * 0.22f, Offset(w * 0.62f, h * 0.45f)); drawCircle(leaf, w * 0.24f, Offset(w / 2, h * 0.28f)) }
        else -> { drawOval(leaf, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.6f)) }
    }
    drawOval(Color(0x22000000), Offset(w * 0.25f, h * 0.88f), Size(w * 0.5f, h * 0.08f))
}

private object FocusPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE)
}

@Composable
fun FocusTab() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).todo() }
    val sessions by dao.focus().collectAsState(initial = emptyList())
    val p = remember { FocusPrefs.p(ctx) }
    var minutes by remember { mutableIntStateOf(p.getInt("len", 25)) }
    var endAt by remember { mutableLongStateOf(p.getLong("endAt", 0)) }
    var label by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endAt > 0
    LaunchedEffect(running) {
        while (endAt > 0) {
            now = System.currentTimeMillis()
            if (now >= endAt) {
                val start = endAt - p.getInt("runLen", minutes) * 60_000L
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = start, minutes = p.getInt("runLen", minutes), label = p.getString("label", "")!!, completed = true, tree = (start / 1000 % 4).toInt())) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
                EventReminder.notify(ctx, "Фокус завершён", "Новое дерево в твоём лесу", 3200)
            }
            delay(500)
        }
    }
    val done = sessions.filter { it.completed }
    val todayMin = done.filter { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }.sumOf { it.minutes }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AppCard(Modifier.fillMaxWidth(), color = Pal.Mint) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val left = if (running) ((endAt - now) / 1000).coerceAtLeast(0) else minutes * 60L
                    val total = (if (running) p.getInt("runLen", minutes) else minutes) * 60f
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(Color.White, 0f, 360f, false, style = Stroke(16f))
                            drawArc(Pal.MintInk, -90f, 360f * (1 - left / total), false, style = Stroke(16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        // дерево растёт по мере сессии
                        Canvas(Modifier.size((60 + 80 * (1 - left / total)).dp)) { tree(1, true) }
                    }
                    Text("%02d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium)
                    if (running) Text("Не уходи — иначе дерево засохнет 🙂".replace(" 🙂", ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!running) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(15, 25, 45, 60, 90).forEach { m -> Chip("$m мин", minutes == m, { minutes = m; p.edit().putInt("len", m).apply() }) } } }
            item { AppTextField(label, { label = it }, "Над чем работаешь (необязательно)") }
            item {
                PillButton("Начать", {
                    endAt = System.currentTimeMillis() + minutes * 60_000L
                    p.edit().putLong("endAt", endAt).putInt("runLen", minutes).putString("label", label).apply()
                }, Modifier.fillMaxWidth())
            }
        } else item {
            PillButton("Сдаться (дерево засохнет)", {
                val len = p.getInt("runLen", minutes)
                AppScope.io.launch { dao.addFocus(FocusSession(startedAt = endAt - len * 60_000L, minutes = len - ((endAt - System.currentTimeMillis()) / 60_000).toInt(), label = label, completed = false)) }
                endAt = 0; p.edit().putLong("endAt", 0).apply()
            }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.RoseInk)
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel("Мой лес")
                Text("Деревьев: ${done.size} · сегодня $todayMin мин · всего ${done.sumOf { it.minutes } / 60} ч", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val shown = sessions.take(60)
                if (shown.isEmpty()) Text("Здесь будет расти лес из твоих фокус-сессий.", style = MaterialTheme.typography.bodySmall)
                shown.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { s -> Canvas(Modifier.weight(1f).aspectRatio(0.8f)) { tree(s.tree, s.completed) } }; repeat(6 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
            }
        }
    }
}

