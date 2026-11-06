package com.pank.vsevodnom.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.theme.Pal

/** Каркас экрана раздела: кнопка назад, заголовок, действия справа. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            actions()
        }
        Column(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    bg: Color = Pal.Card,
    tint: Color = Pal.Ink,
    size: Dp = 44.dp,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = Pal.Card,
    radius: Dp = 16.dp,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier.clip(RoundedCornerShape(radius)).background(color)
    Column(
        (if (onClick != null) m.clickable(onClick = onClick) else m).padding(padding),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, color: Color = Pal.Muted) {
    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = color)
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = Pal.Ink,
    fg: Color = Color.White,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pal.Ink else Pal.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else Pal.Ink
        )
    }
}

/** Сегментированный переключатель вкладок, как в макете часов. */
@Composable
fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, track: Color = Pal.Lilac) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(track).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { i, s ->
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (i == selected) Pal.Card else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = if (i == selected) Pal.Ink else Color(0xFF5A5966),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Pal.Card,
            unfocusedContainerColor = Pal.Card,
            focusedBorderColor = Pal.LilacInk,
            unfocusedBorderColor = Color(0xFFE3E0DA),
        )
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Pal.Muted, modifier = modifier.padding(16.dp))
}
