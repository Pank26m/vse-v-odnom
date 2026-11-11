package com.pank.vsevodnom.ui.keyboard

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pank.vsevodnom.ui.map.MenuRow
import com.pank.vsevodnom.ui.common.ScreenScaffold
import com.pank.vsevodnom.ui.theme.Pal

/** Клавиатура: её настройки + включение проверки правописания и автозаполнения паролей в Android. */
@Composable
fun KeyboardHub(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val autofillOn = remember(tick) { runCatching { ctx.getSystemService(AutofillManager::class.java).hasEnabledAutofillServices() }.getOrDefault(false) }
    val spellOn = remember(tick) {
        runCatching { ctx.getSystemService(TextServicesManager::class.java).currentSpellCheckerInfo?.packageName == ctx.packageName }.getOrDefault(false)
    }
    ScreenScaffold("Клавиатура", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuRow(Icons.Rounded.Keyboard, "Настройки клавиатуры", sub = "темы, раскладки, буфер, жесты") {
                runCatching { ctx.startActivity(Intent().setClassName(ctx, "com.goodwy.keyboard.app.FlorisAppActivity")) }
            }
            MenuRow(Icons.Rounded.Spellcheck, if (spellOn) "Проверка правописания: включена ✓" else "Включить проверку правописания",
                sub = "ошибки подчёркиваются красным, нажми — варианты; без автозамены. В списке выбери «Клавиатура · Всё в одном»") {
                val tries = listOf(
                    Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity")),
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                )
                tries.firstOrNull { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
            }
            MenuRow(Icons.Rounded.Key, if (autofillOn) "Автозаполнение паролей: включено ✓" else "Включить автозаполнение паролей",
                sub = "сайты (Chrome) и приложения: «Сохранить в Пароли?» и вставка по отпечатку") {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${ctx.packageName}"))) }
            }
            Text("Opera может не поддерживать сторонние службы автозаполнения — в ней пароли вставляй кнопкой «Пароли» в клавиатуре.",
                style = MaterialTheme.typography.bodySmall, color = Pal.Muted)
        }
    }
}
