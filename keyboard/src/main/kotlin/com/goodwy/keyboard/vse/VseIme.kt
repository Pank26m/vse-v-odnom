package com.goodwy.keyboard.vse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.goodwy.keyboard.FlorisImeService
import com.goodwy.keyboard.editorInstance
import com.goodwy.keyboard.ime.ImeUiMode
import com.goodwy.keyboard.ime.editor.FlorisEditorInfo
import com.goodwy.keyboard.keyboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Всё в одном» поверх Inkwell: подсказки-кнопки над клавиатурой (код из СМС, сохранить номер, пароли в поле пароля),
 * свои действия в «троеточии» (пароли, документы, шаблоны, контакт, перевод, проверка пунктуации), приём текста
 * из окна паролей (оно в основном процессе приложения — пароль приходит сюда широковещательным сообщением только
 * внутри приложения и вставляется, когда поле снова в фокусе).
 */
object VseIme {
    /** Действия «троеточия» — порядок как в списке. */
    val ACTIONS = listOf("passwords", "docs", "templates", "contact", "translate", "punct")

    fun title(id: String) = when (id) {
        "passwords" -> "Пароли"; "docs" -> "Документы"; "templates" -> "Шаблоны"; "contact" -> "Контакт"
        "translate" -> "Перевести"; "punct" -> "Проверить"; else -> id
    }

    fun icon(id: String): ImageVector = when (id) {
        "passwords" -> Icons.Rounded.Key; "docs" -> Icons.Rounded.Description; "templates" -> Icons.AutoMirrored.Rounded.ShortText
        "contact" -> Icons.Rounded.Contacts; "translate" -> Icons.Rounded.Translate; else -> Icons.Rounded.Spellcheck
    }

    /** Подсказка над клавиатурой: текст кнопки и что делать. */
    data class Chip(val label: String, val run: (Context) -> Unit)

    val chips = MutableStateFlow<List<Chip>>(emptyList())
    /** Что открыто в панели «Всё в одном» (шаблоны, контакт, перевод) — когда imeUiMode == VSE. */
    val panel = MutableStateFlow("templates")
    /** Строка состояния панели/подсказок (перевод скачивается, проверка идёт…). */
    val status = MutableStateFlow<String?>(null)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile var targetPackage: String = ""
        private set
    private var isPassword = false

    const val ACTION_COMMIT = "com.pank.vsevodnom.KBD_COMMIT"
    private var pending: Pair<String, Long>? = null
    private var receiver: BroadcastReceiver? = null

    fun attach(ctx: Context) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = i.getStringExtra("text") ?: return
                pending = t to System.currentTimeMillis()
                // если поле уже снова в фокусе — вставляем сразу
                scope.launch { kotlinx.coroutines.delay(400); flushPending(c) }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_COMMIT), ContextCompat.RECEIVER_NOT_EXPORTED)
        VsePhone.watch(ctx)
    }

    fun detach(ctx: Context) { receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null }

    private fun flushPending(ctx: Context) {
        val (t, at) = pending ?: return
        if (System.currentTimeMillis() - at > 120_000) { pending = null; return }
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (ic.commitText(t, 1)) pending = null
    }

    /** Вход в поле: запоминаем приложение, ставим подсказки (код из СМС, «Пароли» в поле пароля). */
    fun onStartInput(ctx: Context, info: FlorisEditorInfo) {
        targetPackage = info.packageName ?: ""
        val cls = info.base.inputType and InputType.TYPE_MASK_CLASS
        val v = info.base.inputType and InputType.TYPE_MASK_VARIATION
        isPassword = (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        flushPending(ctx)
        rebuildChips(ctx)
    }

    fun rebuildChips(ctx: Context) {
        val out = ArrayList<Chip>()
        VseCodes.fresh(ctx)?.let { code -> out += Chip("Вставить код $code") { c -> commit(c, code); VseCodes.used(c) } }
        if (isPassword) out += Chip("🔑 Пароли") { c -> action(c, "passwords") }
        VsePhone.unknown?.let { num -> out += Chip("Сохранить контакт $num?") { c -> VsePhone.save(c, num) } }
        chips.value = out
    }

    fun commit(ctx: Context, text: String) {
        val ed by ctx.editorInstance()
        ed.commitText(text)
    }

    /** Действие из «троеточия». */
    fun action(ctx: Context, id: String) {
        val km by ctx.keyboardManager()
        km.activeState.isActionsOverflowVisible = false
        when (id) {
            "passwords", "docs" -> {
                // окно паролей — в основном процессе приложения: отпечаток, выбор, текст вернётся сюда
                FlorisImeService.hideUi()
                runCatching {
                    ctx.startActivity(Intent().setClassName(ctx.packageName, "com.pank.vsevodnom.ui.vault.KbVaultActivity")
                        .putExtra("mode", if (id == "docs") "doc" else "pass").putExtra("target", targetPackage)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))
                }
            }
            "templates", "contact", "translate" -> { panel.value = id; km.activeState.imeUiMode = ImeUiMode.VSE }
            "punct" -> VseText.punctuate(ctx)
        }
    }

    // ---------- шаблоны ответов: файл files/kbd_templates.txt (по одному на строку, переводы строк — \n) ----------
    private fun tplFile(ctx: Context) = File(ctx.applicationContext.filesDir, "kbd_templates.txt")
    fun templates(ctx: Context): List<String> = runCatching {
        tplFile(ctx).readLines().filter { it.isNotBlank() }.map { it.replace("\\n", "\n") }
    }.getOrDefault(emptyList()).ifEmpty { listOf("Привет! Сейчас не могу говорить, напишу позже.", "Буду через 10 минут.", "Спасибо!", "Хорошо, договорились.") }
    fun saveTemplates(ctx: Context, list: List<String>) = runCatching {
        tplFile(ctx).writeText(list.joinToString("\n") { it.replace("\n", "\\n") })
    }
}
