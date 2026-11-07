package com.pank.vsevodnom.ui.commands

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pank.vsevodnom.data.AppScope
import com.pank.vsevodnom.data.CmdAction
import com.pank.vsevodnom.data.Command
import com.pank.vsevodnom.data.Commands
import com.pank.vsevodnom.ui.common.*
import com.pank.vsevodnom.ui.theme.Pal
import com.pank.vsevodnom.ui.theme.Tints
import kotlinx.coroutines.launch
import java.util.UUID

private val ICONS = listOf("⚡", "☀️", "🌙", "🏠", "🚗", "🎧", "🍵", "🔦", "⏰", "📚", "💼", "🏃", "🛒", "📞", "💬", "🎮", "🧘", "🔕", "📍", "🎬")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Commands.all(ctx)) }
    var edit by remember { mutableStateOf<Command?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    fun run(c: Command) {
        running = c.id; log = emptyList()
        AppScope.io.launch { Commands.run(ctx, c) { line -> log = log + line }; running = null }
    }
    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Команды", onBack) {
            LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(2) }) {
                    Text("Нажми — выполнится вся цепочка действий. Долгое нажатие — изменить. Любую команду можно вынести ярлыком на рабочий стол телефона.", style = MaterialTheme.typography.bodySmall)
                }
                items(list, key = { it.id }) { c ->
                    val t = Tints.all[c.tint % 6]
                    Column(Modifier.height(120.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).combinedClickable(onClick = { run(c) }, onLongClick = { edit = c }).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.icon, fontSize = 28.sp)
                            Spacer(Modifier.weight(1f))
                            if (running == c.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = t.ink)
                            else Icon(Icons.Rounded.MoreHoriz, "Изменить", tint = t.ink, modifier = Modifier.clip(CircleShape).clickable { edit = c })
                        }
                        Spacer(Modifier.weight(1f))
                        Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${c.actions.size} " + if (c.actions.size in 2..4) "действия" else if (c.actions.size == 1) "действие" else "действий", style = MaterialTheme.typography.labelSmall, color = t.ink)
                    }
                }
                if (log.isNotEmpty()) item(span = { GridItemSpan(2) }) {
                    AppCard(Modifier.fillMaxWidth()) { log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Ink)
            .clickable { edit = Command(UUID.randomUUID().toString(), "", "⚡", list.size % 6, emptyList()) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Новая команда", tint = Color.White) }
    }
    edit?.let { c ->
        CommandEditor(c, onDelete = if (list.any { it.id == c.id }) ({ list = list.filter { it.id != c.id }; Commands.save(ctx, list); edit = null }) else null,
            onRun = { run(it) }) { saved ->
            if (saved != null) { list = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved; Commands.save(ctx, list) }
            edit = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandEditor(c0: Command, onDelete: (() -> Unit)?, onRun: (Command) -> Unit, onDone: (Command?) -> Unit) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(c0) }
    var adding by remember { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf<Int?>(null) }
    fun upd(i: Int, a: CmdAction) { c = c.copy(actions = c.actions.toMutableList().also { it[i] = a }) }
    Dialog({ onDone(null) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIconButton(Icons.Rounded.Close, "Закрыть", { onDone(null) })
                Text(if (c0.name.isBlank()) "Новая команда" else "Команда", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.PlayArrow, "Проверить", { onRun(c) })
                RoundIconButton(Icons.Rounded.Check, "Сохранить", { if (c.name.isNotBlank()) onDone(c) else Toast.makeText(ctx, "Дай команде название", Toast.LENGTH_SHORT).show() }, bg = Pal.Ink, tint = Color.White)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        AppTextField(c.name, { c = c.copy(name = it) }, "Название, например «Утро»")
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ICONS.forEach { e -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (c.icon == e) Tints.all[c.tint % 6].bg else Color.Transparent).clickable { c = c.copy(icon = e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) } }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tints.all.forEachIndexed { i, t -> Box(Modifier.size(if (c.tint % 6 == i) 32.dp else 26.dp).clip(CircleShape).background(t.ink.copy(alpha = if (c.tint % 6 == i) 0.85f else 0.35f)).clickable { c = c.copy(tint = i) }) } }
                    }
                }
                item { SectionLabel("Действия — по порядку") }
                if (c.actions.isEmpty()) item { EmptyHint("Пока пусто. Добавь первое действие.") }
                c.actions.forEachIndexed { i, a ->
                    item(key = "a$i${a.type}") {
                        val t = Commands.type(a.type)
                        AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(Pal.Lilac), contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Pal.LilacInk) }
                                Text("  ${t?.title ?: a.type}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton({ if (i > 0) c = c.copy(actions = c.actions.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Выше") }
                                IconButton({ if (i < c.actions.lastIndex) c = c.copy(actions = c.actions.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Ниже") }
                                IconButton({ c = c.copy(actions = c.actions.filterIndexed { j, _ -> j != i }) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Убрать", tint = Pal.Muted) }
                            }
                            when (a.type) {
                                "section" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Commands.SECTIONS.forEach { (k, s) -> Chip(s, a.a == k, { upd(i, a.copy(a = k)) }) }
                                }
                                "app" -> PillButton(if (a.a.isBlank()) "Выбрать приложение" else appLabel(ctx, a.a), { pickApp = i }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                                "torch" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("вкл", "выкл", "переключить").forEach { v -> Chip(v, a.a.ifBlank { "переключить" } == v, { upd(i, a.copy(a = v)) }) } }
                                "ringer" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("звук", "вибрация", "тихо").forEach { v -> Chip(v, a.a.ifBlank { "звук" } == v, { upd(i, a.copy(a = v)) }) } }
                                else -> {
                                    t?.hintA?.let { h -> Spacer(Modifier.height(6.dp)); AppTextField(a.a, { upd(i, a.copy(a = it)) }, h) }
                                    t?.hintB?.let { h -> Spacer(Modifier.height(6.dp)); AppTextField(a.b, { upd(i, a.copy(b = it)) }, h) }
                                }
                            }
                        }
                    }
                }
                item { PillButton("+ Добавить действие", { adding = true }, Modifier.fillMaxWidth(), bg = Pal.Lilac, fg = Pal.LilacInk) }
                if (c0.name.isNotBlank()) item {
                    PillButton("Ярлык на рабочий стол", { if (!Commands.pin(ctx, c)) Toast.makeText(ctx, "Лаунчер не поддерживает ярлыки", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth(), bg = Pal.Card, fg = Pal.Ink)
                }
                onDelete?.let { d -> item { TextButton(d) { Text("Удалить команду", color = Pal.RoseInk) } } }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, confirmButton = {}, title = { Text("Действие") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Commands.TYPES.groupBy { it.group }.forEach { (g, l) ->
                    Text(g, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    l.forEach { t -> Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { c = c.copy(actions = c.actions + CmdAction(t.key)); adding = false }.padding(10.dp)) }
                }
            }
        })
    pickApp?.let { i ->
        val apps = remember {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(ctx.packageManager).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        }
        AlertDialog(onDismissRequest = { pickApp = null }, confirmButton = {}, title = { Text("Приложение") },
            text = { LazyColumn(Modifier.heightIn(max = 480.dp)) { items(apps) { (n, p) -> Text(n, Modifier.fillMaxWidth().clickable { upd(i, c.actions[i].copy(a = p)); pickApp = null }.padding(12.dp)) } } })
    }
}

private fun appLabel(ctx: android.content.Context, pkg: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
