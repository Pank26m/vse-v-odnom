package com.pank.vsevodnom.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.pank.vsevodnom.MainActivity
import com.pank.vsevodnom.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume

/** Действие команды: тип и параметры (a, b — смысл зависит от типа). */
data class CmdAction(val type: String, val a: String = "", val b: String = "")

/** Команда — набор действий, которые выполняются по очереди одним нажатием (как «Быстрые команды» на iPhone). */
data class Command(val id: String, val name: String, val icon: String, val tint: Int, val actions: List<CmdAction>)

/** Описание типа действия для редактора: название, подписи полей. */
data class CmdType(val key: String, val title: String, val hintA: String? = null, val hintB: String? = null, val group: String)

object Commands {
    val TYPES = listOf(
        CmdType("section", "Открыть раздел приложения", "Раздел", null, "Приложение"),
        CmdType("task", "Добавить задачу на сегодня", "Текст задачи", null, "Приложение"),
        CmdType("url", "Открыть ссылку", "https://…", null, "Интернет"),
        CmdType("app", "Запустить другое приложение", "Приложение", null, "Телефон"),
        CmdType("torch", "Фонарик", "вкл / выкл / переключить", null, "Телефон"),
        CmdType("volume", "Громкость музыки", "0–100 %", null, "Телефон"),
        CmdType("ringer", "Режим звонка", "звук / вибрация / тихо", null, "Телефон"),
        CmdType("wifi", "Панель Wi-Fi / интернета", null, null, "Телефон"),
        CmdType("timer", "Таймер (часы Google)", "Минут", "Подпись", "Время"),
        CmdType("alarm", "Будильник (часы Google)", "Время ЧЧ:ММ", "Подпись", "Время"),
        CmdType("wait", "Подождать", "Секунд", null, "Время"),
        CmdType("speak", "Произнести вслух", "Текст", null, "Общение"),
        CmdType("notify", "Показать уведомление", "Заголовок", "Текст", "Общение"),
        CmdType("copy", "Скопировать текст", "Текст", null, "Общение"),
        CmdType("share", "Поделиться текстом", "Текст", null, "Общение"),
        CmdType("call", "Позвонить", "Номер", null, "Общение"),
        CmdType("sms", "Написать СМС", "Номер", "Текст", "Общение"),
        CmdType("map", "Найти на карте / маршрут", "Адрес или место", null, "Интернет"),
    )
    fun type(k: String) = TYPES.firstOrNull { it.key == k }

    /** Разделы, которые можно открыть командой. */
    val SECTIONS = listOf("home" to "Главная", "todo" to "ToDo", "money" to "Финансы", "calendar" to "Календарь", "weather" to "Погода", "map" to "Карта",
        "power" to "Свет", "alerts" to "Тревоги", "clock/0" to "Часы", "health" to "Здоровье", "music" to "Музыка", "codes" to "Коды", "pdf" to "PDF",
        "english" to "Английский", "cards" to "Карточки", "passwords" to "Пароли", "sms" to "СМС", "calc" to "Калькулятор", "recorder" to "Диктофон", "games" to "Игры")

    private fun p(ctx: Context) = ctx.getSharedPreferences("commands", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<Command> = runCatching {
        val a = JSONArray(p(ctx).getString("list", null) ?: return defaults())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val acts = o.getJSONArray("actions")
            Command(o.getString("id"), o.getString("name"), o.optString("icon", "⚡"), o.optInt("tint"), (0 until acts.length()).map { j -> acts.getJSONObject(j).let { x -> CmdAction(x.getString("t"), x.optString("a"), x.optString("b")) } })
        } }
    }.getOrDefault(defaults())
    fun save(ctx: Context, list: List<Command>) = p(ctx).edit().putString("list", JSONArray(list.map { c ->
        JSONObject().put("id", c.id).put("name", c.name).put("icon", c.icon).put("tint", c.tint)
            .put("actions", JSONArray(c.actions.map { JSONObject().put("t", it.type).put("a", it.a).put("b", it.b) }))
    }).toString()).apply()

    fun defaults() = listOf(
        Command("tea", "Чай", "🍵", 1, listOf(CmdAction("timer", "4", "Чай готов"), CmdAction("notify", "Таймер на 4 минуты", "Чай заварится — напомню"))),
        Command("dark", "Фонарик", "🔦", 4, listOf(CmdAction("torch", "переключить"))),
        Command("morning", "Утро", "☀️", 2, listOf(CmdAction("volume", "40"), CmdAction("speak", "Доброе утро! Смотрим, что на сегодня."), CmdAction("section", "todo"))),
        Command("home", "Домой", "🏠", 3, listOf(CmdAction("map", "Дом"), CmdAction("speak", "Строю маршрут домой"))),
    )

    // ---------- выполнение ----------
    suspend fun run(ctx: Context, c: Command, log: (String) -> Unit = {}) {
        for (a in c.actions) {
            runCatching { exec(ctx, a, log) }.onFailure { log("✕ ${type(a.type)?.title}: ${it.message}") }
        }
    }

    private fun start(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private suspend fun exec(ctx: Context, a: CmdAction, log: (String) -> Unit) {
        when (a.type) {
            "section" -> start(ctx, Intent(ctx, MainActivity::class.java).putExtra("route", a.a).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            "task" -> AppDb.get(ctx).todo().upsertTask(TaskItem(title = a.a.ifBlank { "Задача" }, dueDay = LocalDate.now().toEpochDay()))
            "url" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (a.a.startsWith("http")) a.a else "https://${a.a}")))
            "app" -> ctx.packageManager.getLaunchIntentForPackage(a.a)?.let { start(ctx, it) } ?: error("приложение не найдено")
            "torch" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: error("нет вспышки")
                val on = when (a.a.lowercase()) { "вкл", "on" -> true; "выкл", "off" -> false; else -> !TorchState.on }
                cm.setTorchMode(id, on); TorchState.on = on
            }
            "volume" -> { val am = ctx.getSystemService(AudioManager::class.java); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * (a.a.toIntOrNull() ?: 50).coerceIn(0, 100) / 100f).toInt(), AudioManager.FLAG_SHOW_UI) }
            "ringer" -> { val am = ctx.getSystemService(AudioManager::class.java); am.ringerMode = when (a.a.lowercase()) { "вибрация" -> AudioManager.RINGER_MODE_VIBRATE; "тихо" -> AudioManager.RINGER_MODE_SILENT; else -> AudioManager.RINGER_MODE_NORMAL } }
            "wifi" -> start(ctx, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS))
            "timer" -> start(ctx, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, ((a.a.replace(',', '.').toDoubleOrNull() ?: 1.0) * 60).toInt()).putExtra(AlarmClock.EXTRA_MESSAGE, a.b).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "alarm" -> { val (h, m) = a.a.split(':').map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 7 }) to (it.getOrElse(1) { 0 }) }
                start(ctx, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_MESSAGE, a.b).putExtra(AlarmClock.EXTRA_SKIP_UI, true)) }
            "wait" -> delay(((a.a.replace(',', '.').toDoubleOrNull() ?: 1.0) * 1000).toLong())
            "speak" -> speak(ctx, a.a)
            "notify" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("commands", "Команды", NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify((a.a + a.b).hashCode(), NotificationCompat.Builder(ctx, "commands").setSmallIcon(R.drawable.ic_notify).setContentTitle(a.a).setContentText(a.b).setAutoCancel(true).build())
            }
            "copy" -> ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Команда", a.a))
            "share" -> start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.a), "Поделиться"))
            "call" -> start(ctx, Intent(if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + a.a)))
            "sms" -> start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.a)).putExtra("sms_body", a.b))
            "map" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.a))))
        }
        log("✓ ${type(a.type)?.title}")
    }

    private suspend fun speak(ctx: Context, text: String) = suspendCancellableCoroutine<Unit> { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru")
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { tts?.shutdown(); if (cont.isActive) cont.resume(Unit) }
                    @Deprecated("старый API") override fun onError(id: String?) { tts?.shutdown(); if (cont.isActive) cont.resume(Unit) }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cmd")
            } else if (cont.isActive) cont.resume(Unit)
        }
    }

    /** Ярлык команды на рабочем столе телефона. */
    fun pin(ctx: Context, c: Command): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("command", c.id)
        val info = ShortcutInfoCompat.Builder(ctx, "cmd_${c.id}").setShortLabel(c.name.take(20)).setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)).setIntent(intent).build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }
}

object TorchState { var on = false }
