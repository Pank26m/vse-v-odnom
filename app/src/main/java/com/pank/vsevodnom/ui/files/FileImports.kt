package com.pank.vsevodnom.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.ui.vault.PendingVaultImport
import kotlinx.coroutines.launch

/** Импорты из файлов — пункты меню ⋮ у файла. */
object FileImports {
    @Composable
    fun menuItems(ctx: Context, uri: Uri, name: String, close: () -> Unit) {
        val low = name.lowercase()
        if (low.endsWith(".csv") || low.endsWith(".xml")) DropdownMenuItem({ Text("Импорт в пароли (KeePass)") }, {
            close()
            PendingVaultImport.uri = uri
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "passwords").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        if (low.endsWith(".csv")) DropdownMenuItem({ Text("Импорт в «Деньги» (Sumlio)") }, {
            close()
            com.pank.vsevodnom.data.AppScope.io.launch {
                val msg = runCatching { com.pank.vsevodnom.data.MoneyRepo.importSumlio(ctx, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
                    .fold({ "Импортировано операций: ${it.added} (повторов пропущено: ${it.skipped})" }, { "Не получилось: ${it.message}" })
                android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
            }
        })
        if (low.endsWith(".csv")) DropdownMenuItem({ Text("Импорт веса") }, {
            close()
            WeightImport.pending = uri
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "weight").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
}

/** Файл с весом из другого приложения — разбирается на экране веса. */
object WeightImport { var pending: Uri? = null }
