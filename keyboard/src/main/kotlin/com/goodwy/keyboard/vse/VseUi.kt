package com.goodwy.keyboard.vse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodwy.keyboard.FlorisImeService
import com.goodwy.keyboard.ime.ImeUiMode
import com.goodwy.keyboard.ime.keyboard.FlorisImeSizing
import com.goodwy.keyboard.ime.theme.FlorisImeTheme
import com.goodwy.keyboard.ime.theme.FlorisImeUi
import com.goodwy.keyboard.keyboardManager
import com.goodwy.keyboard.lib.compose.FlorisIconButtonWithInnerPadding
import com.goodwy.lib.snygg.ui.snyggBackground
import com.goodwy.lib.snygg.ui.solidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Строка подсказок над клавиатурой: если есть свои подсказки или статус — они, иначе обычные варианты слов. */
@Composable
fun VseCandidatesOr(content: @Composable () -> Unit) {
    val chips by VseIme.chips.collectAsState()
    val status by VseIme.status.collectAsState()
    val ctx = LocalContext.current
    val style = FlorisImeTheme.style.get(FlorisImeUi.ClipboardItem)
    val fg = style.foreground.solidColor(ctx)
    val bg = style.background.solidColor(ctx)
    when {
        status != null -> Box(Modifier.fillMaxSize().clickable { if (VseVoice.listening) VseVoice.stop() else VseIme.status.value = null }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(status!!, color = fg, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        chips.isNotEmpty() -> LazyRow(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips) { c ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { c.run(ctx) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(c.label, color = fg, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        else -> content()
    }
}

/** Панель «Всё в одном» вместо клавиш: шаблоны ответов, контакт, перевод. */
@Composable
fun VsePanel() {
    val ctx = LocalContext.current
    val km by ctx.keyboardManager()
    val panel by VseIme.panel.collectAsState()
    val header = FlorisImeTheme.style.get(FlorisImeUi.ClipboardHeader)
    val item = FlorisImeTheme.style.get(FlorisImeUi.ClipboardItem)
    val hfg = header.foreground.solidColor(ctx)
    val ifg = item.foreground.solidColor(ctx)
    val ibg = item.background.solidColor(ctx)
    val h = FlorisImeSizing.imeUiHeight()
    Column(Modifier.fillMaxWidth().height(h)) {
        Row(Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight).snyggBackground(ctx, header), verticalAlignment = Alignment.CenterVertically) {
            FlorisIconButtonWithInnerPadding(onClick = { km.activeState.imeUiMode = ImeUiMode.TEXT }, icon = Icons.AutoMirrored.Rounded.ArrowBackIos, iconColor = hfg)
            Text(VseIme.title(panel), color = hfg, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (panel == "templates") FlorisIconButtonWithInnerPadding(onClick = {
                // новый шаблон — из выделенного текста или всего текста поля
                val ic = FlorisImeService.currentInputConnection()
                val t = ic?.getSelectedText(0)?.toString()?.ifBlank { null }
                    ?: ((ic?.getTextBeforeCursor(5000, 0)?.toString() ?: "") + (ic?.getTextAfterCursor(5000, 0)?.toString() ?: "")).trim()
                if (t.isNotBlank()) VseIme.saveTemplates(ctx, listOf(t) + VseIme.templates(ctx).filter { it != t })
                VseIme.panel.value = "templates_"; VseIme.panel.value = "templates"
            }, icon = Icons.Rounded.Add, iconColor = hfg)
        }
        @Composable
        fun Row1(text: String, sub: String? = null, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(ibg).clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text, color = ifg, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (sub != null) Text(sub, color = ifg.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2)
                }
                if (onDelete != null) Icon(Icons.Rounded.Close, "Удалить", tint = ifg.copy(alpha = 0.6f), modifier = Modifier.clickable(onClick = onDelete).padding(6.dp).size(18.dp))
            }
        }
        when (panel) {
            "templates" -> {
                var list by remember { mutableStateOf(VseIme.templates(ctx)) }
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Text("Нажми — вставить. «+» сверху — сохранить выделенный текст (или весь текст поля) как шаблон.", color = ifg.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(10.dp, 4.dp)) }
                    items(list) { t ->
                        Row1(t, onDelete = { list = list - t; VseIme.saveTemplates(ctx, list) }) { VseIme.commit(ctx, t); km.activeState.imeUiMode = ImeUiMode.TEXT }
                    }
                }
            }
            "contact" -> {
                // поиск — по слову перед курсором: написал «Мама» → «Контакт» → нашлась Мама, слово заменится карточкой
                val ic = FlorisImeService.currentInputConnection()
                val word = remember { Regex("([\\p{L}]{2,})$").find(ic?.getTextBeforeCursor(40, 0)?.toString().orEmpty())?.value.orEmpty() }
                var cards by remember { mutableStateOf<List<VsePhone.Card>?>(null) }
                LaunchedEffect(word) { cards = withContext(Dispatchers.IO) { VsePhone.search(ctx, word).ifEmpty { if (word.isNotEmpty()) VsePhone.search(ctx, "") else emptyList() } } }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(if (word.isNotEmpty()) "Ищу «$word». Нажми контакт — отправится имя и номера." else "Совет: напиши в поле начало имени и открой «Контакт» снова — будет поиск.",
                            color = ifg.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(10.dp, 4.dp))
                    }
                    if (cards == null) item { Text("Загружаю…", color = ifg, modifier = Modifier.padding(12.dp)) }
                    else if (cards!!.isEmpty()) item { Text("Нет доступа к контактам или ничего не найдено", color = ifg, modifier = Modifier.padding(12.dp)) }
                    items(cards.orEmpty()) { c ->
                        Row1(c.name, c.phones.joinToString(" · ")) {
                            val icn = FlorisImeService.currentInputConnection()
                            if (word.isNotEmpty() && icn?.getTextBeforeCursor(word.length, 0)?.toString() == word) icn.deleteSurroundingText(word.length, 0)
                            VseIme.commit(ctx, VsePhone.text(c)); km.activeState.imeUiMode = ImeUiMode.TEXT
                        }
                    }
                }
            }
            "translate" -> {
                Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Весь текст поля переведётся на выбранный язык. Оригинал — в буфере обмена.", color = ifg.copy(alpha = 0.7f), fontSize = 13.sp)
                    listOf("en" to "На английский", "ru" to "На русский", "uk" to "На украинский").forEach { (code, t) ->
                        Row1(t) { km.activeState.imeUiMode = ImeUiMode.TEXT; VseText.translate(ctx, code) }
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize().background(Color.Transparent))
        }
    }
}
