package com.pank.vsevodnom.ui.utils

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.common.AppCard
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Tint
import com.pank.vsevodnom.ui.theme.Tints

private data class Util(val key: String, val title: String, val sub: String, val icon: ImageVector, val tint: Tint)

private val UTILS = listOf(
    Util("device", "Об устройстве", "Модель, процессор, память, экран, камеры, датчики", Icons.Rounded.PhoneAndroid, Tints.Sky),
    Util("battery", "Батарея", "Ток, мощность зарядки, температура, циклы, износ", Icons.Rounded.BatteryChargingFull, Tints.Mint),
    Util("repair", "Тесты телефона", "Экран, тач, динамики, микрофон, камеры, кнопки, датчики — с отчётом", Icons.Rounded.Build, Tints.Peach),
    Util("wifi", "Wi-Fi сканер", "Сети вокруг, сигнал, каналы, защита", Icons.Rounded.Wifi, Tints.Sky),
    Util("archive", "Архивы", "Открыть и распаковать zip, rar, tar, gz; создать zip", Icons.Rounded.FolderZip, Tints.Butter),
)

@Composable
fun UtilsScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { open = null }
    if (open != null) BackHandler { close() }
    when (open) {
        "device" -> DeviceInfoScreen(close)
        "battery" -> BatteryScreen(close)
        "repair" -> RepairTestScreen(close)
        "wifi" -> WifiScreen(close)
        "archive" -> com.pank.vsevodnom.ui.files.ArchiveScreen(close)
        else -> ScreenScaffold("Утилиты", onBack) {
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(UTILS) { u ->
                    AppCard(Modifier.fillMaxWidth(), onClick = { open = u.key }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(u.tint.bg), contentAlignment = Alignment.Center) {
                                Icon(u.icon, null, tint = u.tint.ink)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(u.title, style = MaterialTheme.typography.titleMedium)
                                Text(u.sub, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Карточка «ключ — значение» для инфо-экранов. */
@Composable
fun InfoGroup(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = com.pank.vsevodnom.ui.theme.Pal.LilacInk)
        Spacer(Modifier.height(6.dp))
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = com.pank.vsevodnom.ui.theme.Pal.Muted, modifier = Modifier.weight(0.45f))
                Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
            }
        }
    }
}
