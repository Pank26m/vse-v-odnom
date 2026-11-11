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
import androidx.compose.material.icons.rounded.LocationOn
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
    /** Версия кода клавиатуры — видна в шапке панели и при первом показе после обновления. */
    const val CODE = "0.40"

    /** Короткое сообщение в верхней панели на 3 секунды. */
    fun flash(t: String) { status.value = t; scope.launch { kotlinx.coroutines.delay(3000); if (status.value == t) status.value = null } }
    /** Действия «троеточия» — порядок как в списке. */
    val ACTIONS = listOf("passwords", "docs", "templates", "contact", "translate", "punct", "location")

    fun title(id: String) = when (id) {
        "passwords" -> "Пароли"; "passwords_" -> "Пароли"; "docs" -> "Документы"; "templates" -> "Шаблоны"; "contact" -> "Контакт"
        "translate" -> "Перевести"; "punct" -> "Проверить"; "location" -> "Где я"; else -> id
    }

    fun icon(id: String): ImageVector = when (id) {
        "passwords" -> Icons.Rounded.Key; "docs" -> Icons.Rounded.Description; "templates" -> Icons.AutoMirrored.Rounded.ShortText
        "contact" -> Icons.Rounded.Contacts; "translate" -> Icons.Rounded.Translate; "location" -> Icons.Rounded.LocationOn; else -> Icons.Rounded.Spellcheck
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
    /** Текст из окна паролей: (текст, когда, в какое приложение). Вставляется, только когда снова открыто то же приложение. */
    private var pending: Triple<String, Long, String>? = null
    /** Приложение, из которого открыли окно паролей. */
    private var askedFrom = ""
    /** Подсказки скрыты крестиком до следующего поля. */
    private var dismissed = false
    private var receiver: BroadcastReceiver? = null
    private var vaultReceiver: BroadcastReceiver? = null
    const val ACTION_VAULT = "com.pank.vsevodnom.KBD_VAULT"
    /** Панель, которую открыть, когда клавиатура снова на экране (после окна отпечатка). */
    private var pendingPanel: String? = null

    fun openPendingPanel(ctx: Context) {
        val p = pendingPanel ?: return
        if (FlorisImeService.currentInputConnection() == null) return
        if (targetPackage == ctx.packageName && askedFrom != ctx.packageName) return
        pendingPanel = null
        val km by ctx.keyboardManager()
        panel.value = p; km.activeState.imeUiMode = ImeUiMode.VSE
    }

    /** Приложение, откуда открыли пароли (для подсветки подходящих записей). */
    val askedFromPackage get() = askedFrom

    fun attach(ctx: Context) {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = i.getStringExtra("text") ?: return
                pending = Triple(t, System.currentTimeMillis(), askedFrom)
                // поле приложения получит фокус после закрытия окна паролей — пробуем несколько раз
                scope.launch { repeat(10) { kotlinx.coroutines.delay(300); if (pending == null) return@launch; flushPending(c) } }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_COMMIT), ContextCompat.RECEIVER_NOT_EXPORTED)
        // записи паролей после отпечатка — список откроется в клавиатуре
        vaultReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                VseVault.put(i.getStringExtra("json") ?: return)
                pendingPanel = "passwords"
                scope.launch { kotlinx.coroutines.delay(400); FlorisImeService.showUi(); openPendingPanel(c) }
            }
        }
        ContextCompat.registerReceiver(ctx, vaultReceiver, IntentFilter(ACTION_VAULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        VseWatch.watch(ctx)
    }

    fun detach(ctx: Context) {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null
        vaultReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }; vaultReceiver = null
    }

    /**
     * Вставить пароль. Раньше вставлялось сразу — пока на экране было окно паролей, и текст уходил «в пустоту»
     * (Telegram, Instagram). Теперь — только когда поле снова того приложения, откуда просили.
     */
    fun flushPending(ctx: Context) {
        val (t, at, pkg) = pending ?: return
        if (System.currentTimeMillis() - at > 120_000) { pending = null; return }
        if (pkg.isNotEmpty() && targetPackage != pkg) return
        if (targetPackage == ctx.packageName && pkg != ctx.packageName) return
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (ic.commitText(t, 1)) pending = null
    }

    /** Поле получило фокус (onStartInput): обновить приложение и вставить ждущий пароль. */
    fun onBind(ctx: Context, pkg: String) {
        targetPackage = pkg
        scope.launch { kotlinx.coroutines.delay(150); flushPending(ctx) }
    }

    /** Вход в поле: запоминаем приложение, ставим подсказки (код из СМС, «Пароли» в поле пароля). */
    fun onStartInput(ctx: Context, info: FlorisEditorInfo) {
        // первый показ после обновления — сказать, какая версия клавиатуры работает
        runCatching {
            val f = File(ctx.applicationContext.filesDir, "kbd_code_version")
            if (!f.exists() || f.readText() != CODE) { f.writeText(CODE); flash("Клавиатура обновлена: v$CODE") }
        }
        VseWatch.leaveField(ctx)
        targetPackage = info.packageName ?: ""
        dismissed = false
        VseWatch.enterField(info)
        openPendingPanel(ctx)
        val cls = info.base.inputType and InputType.TYPE_MASK_CLASS
        val v = info.base.inputType and InputType.TYPE_MASK_VARIATION
        isPassword = (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        flushPending(ctx)
        rebuildChips(ctx)
    }

    fun rebuildChips(ctx: Context) {
        if (dismissed) { chips.value = emptyList(); return }
        val out = ArrayList<Chip>()
        VseCodes.fresh(ctx)?.let { code -> out += Chip("Вставить код $code") { c -> commit(c, code); VseCodes.used(c) } }
        VseWatch.calc?.let { (res, withEq) -> out += Chip("= $res") { c -> commit(c, if (withEq) res else " = $res"); VseWatch.calc = null; rebuildChips(c) } }
        VseWatch.emails.forEach { (typed, e) -> out += Chip(e) { c ->
            if (typed.isNotEmpty()) FlorisImeService.currentInputConnection()?.deleteSurroundingText(typed.length, 0)
            commit(c, e); VseWatch.emails = emptyList(); rebuildChips(c)
        } }
        if (isPassword) out += Chip("🔑 Пароли") { c -> action(c, "passwords") }
        VsePhone.unknown?.let { num -> out += Chip("Сохранить контакт $num?") { c -> VsePhone.save(c, num) } }
        if (out.isNotEmpty()) out += Chip("✕") { c -> dismissed = true; rebuildChips(c) }
        chips.value = out
    }

    /** Вставить текст в поле: напрямую в соединение с полем (надёжнее, чем через редактор Inkwell с его составным словом). */
    fun commit(ctx: Context, text: String) {
        val ic = FlorisImeService.currentInputConnection()
        if (ic != null) { ic.finishComposingText(); ic.commitText(text, 1) }
        else { val ed by ctx.editorInstance(); ed.commitText(text) }
    }

    /** Действие из «троеточия». */
    fun action(ctx: Context, id: String) {
        val km by ctx.keyboardManager()
        km.activeState.isActionsOverflowVisible = false
        when (id) {
            "passwords" -> {
                askedFrom = targetPackage
                // отпечаток уже был в последние 3 минуты — сразу список
                if (VseVault.alive()) { panel.value = "passwords"; km.activeState.imeUiMode = ImeUiMode.VSE; return }
                FlorisImeService.hideUi()
                runCatching {
                    ctx.startActivity(Intent().setClassName(ctx.packageName, "com.pank.vsevodnom.ui.vault.KbVaultActivity")
                        .putExtra("mode", "unlock").putExtra("target", targetPackage)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
            "docs" -> {
                // окно документов — в основном процессе приложения: отпечаток, выбор, отправка в это же приложение
                askedFrom = targetPackage
                FlorisImeService.hideUi()
                runCatching {
                    ctx.startActivity(Intent().setClassName(ctx.packageName, "com.pank.vsevodnom.ui.vault.KbVaultActivity")
                        .putExtra("mode", "doc").putExtra("target", targetPackage)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))
                }
            }
            "templates", "contact", "translate" -> { panel.value = id; km.activeState.imeUiMode = ImeUiMode.VSE }
            "punct" -> VseText.punctuate(ctx)
            "location" -> VseWatch.location(ctx)
        }
    }

    // ---------- шаблоны ответов: файл files/kbd_templates.txt (по одному на строку, переводы строк — \n) ----------
    private fun tplFile(ctx: Context) = File(ctx.applicationContext.filesDir, "kbd_templates.txt")
    /** Примеры — только пока файла нет; удалил всё — список пустой (раньше примеры возвращались). */
    fun templates(ctx: Context): List<String> {
        val f = tplFile(ctx)
        if (!f.exists()) return listOf("Привет! Сейчас не могу говорить, напишу позже.", "Буду через 10 минут.", "Спасибо!", "Хорошо, договорились.")
        return runCatching { f.readLines().filter { it.isNotBlank() }.map { it.replace("\\n", "\n") } }.getOrDefault(emptyList())
    }
    fun saveTemplates(ctx: Context, list: List<String>) = runCatching {
        tplFile(ctx).writeText(list.joinToString("\n") { it.replace("\n", "\\n") })
    }
}
