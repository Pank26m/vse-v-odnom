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
    Util("device", "Анализатор устройства", "Всё о телефоне вживую: процессор, память, экран, камеры, датчики, сеть, безопасность, приложения + полный отчёт", Icons.Rounded.PhoneAndroid, Tints.Sky),
    Util("battery", "Батарея", "Зарядка и разрядка с историей, ёмкость и износ, уведомление о заряде", Icons.Rounded.BatteryChargingFull, Tints.Mint),
    Util("repair", "Тесты телефона", "Экран, тач, звук, камеры, GPS, кнопки, датчики, автотест — с отчётом", Icons.Rounded.Build, Tints.Peach),
    Util("wifi", "Wi-Fi анализатор", "Каналы, сети вокруг, лучший канал, устройства в сети, диагностика", Icons.Rounded.Wifi, Tints.Sky),
    Util("files", "Вспомогательные файлы", "Всё, с чем работает приложение: расписание, импорты, вложения задач и заметок, записи", Icons.Rounded.FolderOpen, Tints.Lilac),
    Util("pdf", "PDF", "Просмотр, правка страниц, рисунок поверх, конвертация", Icons.Rounded.PictureAsPdf, Tints.Rose),
    Util("codes", "Коды", "Сканер QR и штрихкодов, создание и печать", Icons.Rounded.QrCode2, Tints.Sky),
    Util("downloads", "Загрузки", "Видео и музыка по ссылке (YouTube, Instagram, TikTok…)", Icons.Rounded.Download, Tints.Peach),
    Util("keyboard", "Клавиатура", "Своя клавиатура: настройки, проверка правописания, автозаполнение", Icons.Rounded.Keyboard, Tints.Lilac),
    Util("archive", "Архивы", "Открыть и распаковать zip, rar, tar, gz; создать zip", Icons.Rounded.FolderZip, Tints.Butter),
)

@Composable
fun UtilsScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { open = null }
    if (open != null) BackHandler { close() }
    when (open) {
        "device" -> com.pank.vsevodnom.ui.device.DeviceHub(close)
        "battery" -> com.pank.vsevodnom.ui.device.BatteryHub(close)
        "repair" -> com.pank.vsevodnom.ui.device.TestsHub(close)
        "wifi" -> com.pank.vsevodnom.ui.device.WifiAnalyzer(close)
        "archive" -> com.pank.vsevodnom.ui.files.ArchiveScreen(close)
        "files" -> com.pank.vsevodnom.ui.files.FilesScreen(close)
        "pdf" -> com.pank.vsevodnom.ui.files.PdfScreen(close)
        "codes" -> com.pank.vsevodnom.ui.codes.CodesScreen(close)
        "downloads" -> com.pank.vsevodnom.ui.downloads.DownloadsScreen(close)
        "keyboard" -> com.pank.vsevodnom.ui.keyboard.KeyboardHub(close)
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
