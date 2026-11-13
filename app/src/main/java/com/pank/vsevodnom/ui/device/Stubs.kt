package com.pank.vsevodnom.ui.device
import androidx.compose.runtime.Composable
@Composable fun BatteryHub(onBack: () -> Unit) = DevPage("Батарея", onBack) {}
@Composable fun WifiAnalyzer(onBack: () -> Unit) = DevPage("Wi-Fi", onBack) {}
@Composable fun AppsHub(onBack: () -> Unit) = DevPage("Приложения", onBack) {}
@Composable fun TestsHub(onBack: () -> Unit) = DevPage("Тесты", onBack) {}
@Composable fun ToolsHub(onBack: () -> Unit) = DevPage("Инструменты", onBack) {}
object NetTools { fun publicIp(): List<com.pank.vsevodnom.data.device.InfoRow> = emptyList() }
