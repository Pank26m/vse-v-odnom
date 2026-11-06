package com.pank.vsevodnom.ui.files

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable

/** Импорты из файлов (вес, пароли) — пункты меню у файла. Заполняется по мере появления форматов. */
object FileImports {
    @Composable
    fun menuItems(ctx: Context, uri: Uri, name: String, close: () -> Unit) {}
}
