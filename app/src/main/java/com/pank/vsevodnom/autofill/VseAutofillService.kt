package com.pank.vsevodnom.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.*
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.pank.vsevodnom.R

/**
 * Автозаполнение «Всё в одном» (включается в настройках Android: Пароли и аккаунты → Служба автозаполнения).
 * Видит поля логина/пароля и адрес сайта (в браузерах, которые поддерживают сторонние службы, — Chrome) или приложение.
 * Заполнение — кнопка «Пароли «Всё в одном»» (в клавиатуре — прямо в верхней строке), по отпечатку → выбор записи.
 * После входа Android спросит «Сохранить в «Всё в одном»?» → отпечаток → запись в паролях.
 */
class VseAutofillService : AutofillService() {

    /** Найденные поля формы. */
    class Form(val user: AutofillId?, val pass: AutofillId?, val domain: String, val pkg: String, val userValue: String?, val passValue: String?)

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val st = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val f = parse(st)
        if (f.pass == null && f.user == null) return callback.onSuccess(null)
        if (f.pkg == packageName) return callback.onSuccess(null)   // в своём же приложении не мешаем
        val ids = listOfNotNull(f.user, f.pass)
        val auth = Intent(this, com.pank.vsevodnom.ui.vault.KbVaultActivity::class.java)
            .putExtra("mode", "autofill").putExtra("domain", f.domain).putExtra("target", f.pkg)
            .putExtra("user_id", f.user).putExtra("pass_id", f.pass)
        val pi = PendingIntent.getActivity(this, (f.domain + f.pkg).hashCode(), auth, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val label = "🔑 Пароли «Всё в одном»" + if (f.domain.isNotBlank()) " · ${f.domain}" else ""
        val pres = RemoteViews(packageName, R.layout.autofill_item).apply { setTextViewText(R.id.autofill_text, label) }
        val ds = Dataset.Builder(pres)
        // встроенная подсказка в клавиатуре (Android 11+): та же кнопка в верхней строке клавиатуры
        val inline = if (android.os.Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs?.firstOrNull()?.let { spec ->
            runCatching {
                val slice = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(
                    PendingIntent.getActivity(this, 1, Intent(), PendingIntent.FLAG_IMMUTABLE)).setTitle(label).build().slice
                android.service.autofill.InlinePresentation(slice, spec, false)
            }.getOrNull()
        } else null
        for (id in ids) {
            if (inline != null && android.os.Build.VERSION.SDK_INT >= 30) @Suppress("DEPRECATION") ds.setValue(id, null, pres, inline)
            else @Suppress("DEPRECATION") ds.setValue(id, null, pres)
        }
        ds.setAuthentication(pi.intentSender)
        val resp = FillResponse.Builder().addDataset(ds.build())
        if (f.pass != null) {
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or if (f.user != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0
            resp.setSaveInfo(SaveInfo.Builder(type, arrayOf(f.pass)).apply { f.user?.let { setOptionalIds(arrayOf(it)) } }.build())
        }
        callback.onSuccess(resp.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val st = request.fillContexts.lastOrNull()?.structure ?: return callback.onFailure("нет данных")
        val f = parse(st)
        if (f.passValue.isNullOrEmpty()) return callback.onFailure("пароль не найден")
        // сохранение — в окне с отпечатком (хранилище открывается только после разблокировки)
        val i = Intent(this, com.pank.vsevodnom.ui.vault.KbVaultActivity::class.java)
            .putExtra("mode", "save").putExtra("domain", f.domain).putExtra("target", f.pkg)
            .putExtra("login", f.userValue.orEmpty()).putExtra("password", f.passValue)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            callback.onSuccess(pi.intentSender)
        } else { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); callback.onSuccess() }
    }

    companion object {
        private val USER_WORDS = listOf("user", "login", "email", "e-mail", "mail", "phone", "логин", "почт", "телефон", "username", "account", "ім'я", "имя")
        private val PASS_WORDS = listOf("pass", "pwd", "пароль", "парол")

        fun parse(st: AssistStructure): Form {
            var user: AutofillId? = null; var pass: AutofillId? = null; var domain = ""
            var userV: String? = null; var passV: String? = null
            val texts = ArrayList<Pair<AutofillId, String?>>()   // текстовые поля по порядку — логин = поле перед паролем
            fun walk(n: AssistStructure.ViewNode) {
                n.webDomain?.takeIf { it.isNotBlank() && domain.isEmpty() }?.let { domain = it.removePrefix("www.") }
                val id = n.autofillId
                if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
                    val hints = n.autofillHints?.joinToString(" ")?.lowercase().orEmpty()
                    val html = n.htmlInfo?.attributes?.joinToString(" ") { "${it.first}=${it.second}" }?.lowercase().orEmpty()
                    val words = (hints + " " + html + " " + (n.idEntry ?: "") + " " + (n.hint ?: "")).lowercase()
                    val v = n.inputType and InputType.TYPE_MASK_VARIATION
                    val isPass = (n.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                        (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                        "type=password" in html || hints.contains("password") || PASS_WORDS.any { it in words }
                    val value = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                    if (isPass) {
                        if (pass == null) {
                            pass = id; passV = value
                            // логин не опознан по подсказкам — это текстовое поле прямо перед паролем (так в Instagram и большинстве форм)
                            if (user == null) texts.lastOrNull()?.let { (uid, uv) -> user = uid; userV = uv }
                        }
                    }
                    else {
                        texts += id to value
                        if (user == null && (hints.contains("username") || hints.contains("email") || hints.contains("phone") ||
                                v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                                USER_WORDS.any { it in words })) { user = id; userV = value }
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChildAt(i))
            }
            for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode)
            // логин не опознан, но есть пароль — берём текстовое поле прямо перед ним
            if (user == null && pass != null && texts.isNotEmpty()) { val (id, v) = texts.last(); user = id; userV = v }
            return Form(user, pass, domain, st.activityComponent?.packageName.orEmpty(), userV, passV)
        }
    }
}
