package com.pank.vsevodnom.ui.device
import androidx.compose.runtime.Composable
@Composable fun AppsHub(onBack: () -> Unit) = DevPage("Приложения", onBack) {}
@Composable fun TestsHub(onBack: () -> Unit) = DevPage("Тесты", onBack) {}
@Composable fun ToolsHub(onBack: () -> Unit) = DevPage("Инструменты", onBack) {}
