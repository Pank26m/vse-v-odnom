package com.pank.vsevodnom.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Telegram через TDLib (официальная библиотека): вход в аккаунт владельца как отдельное устройство, чтение канала
 * с фильмами и потоковая загрузка видео для плеера. Ключи api_id/api_hash — только в prefs «api_keys» (tg_id, tg_hash),
 * их вводит владелец. Сессия — files/tg/db (в бэкап не идёт), скачанные куски — cache/tg (удаляются после просмотра).
 */
object Tg {
    enum class Auth { NO_KEYS, STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, LOGGING_OUT, ERROR }

    var auth by mutableStateOf(Auth.NO_KEYS); private set
    var codeHint by mutableStateOf(""); private set
    var passwordHint by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    /** Растёт при каждом изменении файла — экраны перерисовывают прогресс. */
    var fileTick by mutableIntStateOf(0); private set

    private var client: Client? = null
    @Volatile private var generation = 0
    private var app: Context? = null
    private val files = ConcurrentHashMap<Int, TdApi.File>()
    private val lock = Object()

    class TgError(val code: Int, msg: String) : Exception(msg)

    private fun p(ctx: Context) = ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE)
    fun apiId(ctx: Context) = p(ctx).getString("tg_id", "").orEmpty().trim().toIntOrNull()
    fun apiHash(ctx: Context) = p(ctx).getString("tg_hash", "").orEmpty().trim()
    fun hasKeys(ctx: Context) = apiId(ctx) != null && apiHash(ctx).length >= 16
    fun setKeys(ctx: Context, id: String, hash: String) { p(ctx).edit().putString("tg_id", id.trim()).putString("tg_hash", hash.trim()).apply() }

    fun dbDir(ctx: Context) = File(ctx.filesDir, "tg/db")
    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "tg")

    /** Ключ шифрования базы TDLib — случайный, один раз. */
    private fun dbKey(ctx: Context): ByteArray {
        val sp = ctx.getSharedPreferences("tg", Context.MODE_PRIVATE)
        sp.getString("dbkey", null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        sp.edit().putString("dbkey", android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP)).apply()
        return k
    }

    /** Запустить клиента (если есть ключи). Повторный вызов ничего не делает. */
    @Synchronized fun start(ctx: Context) {
        app = ctx.applicationContext
        if (!hasKeys(ctx)) { auth = Auth.NO_KEYS; return }
        if (client != null) return
        auth = Auth.STARTING; error = null
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        val gen = ++generation
        // обновления от старого клиента (после перезапуска) не трогают новый
        client = Client.create({ if (gen == generation) onUpdate(it) }, null, null)
    }

    /** Перезапуск после смены ключей. */
    @Synchronized fun restart(ctx: Context) {
        runCatching { client?.send(TdApi.Close(), null) }
        val had = client != null
        client = null; auth = Auth.STARTING; generation++
        // старая сессия должна отпустить базу, иначе новая не откроется
        if (had) Thread { Thread.sleep(2000); start(ctx) }.start() else start(ctx)
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateFile -> { files[o.file.id] = o.file; synchronized(lock) { lock.notifyAll() }; fileTick++ }
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        val ctx = app ?: return
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                dbDir(ctx).mkdirs(); cacheDir(ctx).mkdirs()
                val req = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = dbDir(ctx).absolutePath
                    filesDirectory = cacheDir(ctx).absolutePath
                    databaseEncryptionKey = dbKey(ctx)
                    useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = true; useSecretChats = false
                    apiId = apiId(ctx) ?: 0; apiHash = apiHash(ctx)
                    systemLanguageCode = "ru"
                    deviceModel = android.os.Build.MODEL ?: "Android"
                    systemVersion = android.os.Build.VERSION.RELEASE ?: ""
                    applicationVersion = "Vse v odnom"
                }
                client?.send(req) { r -> if (r is TdApi.Error) { error = "TDLib: ${r.message}"; auth = Auth.ERROR } }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth = Auth.WAIT_PHONE
            is TdApi.AuthorizationStateWaitCode -> {
                codeHint = when (s.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> "Код пришёл в Telegram (AyuGram) — в чат «Telegram»."
                    is TdApi.AuthenticationCodeTypeSms -> "Код пришёл по СМС."
                    else -> "Введи код подтверждения."
                }
                auth = Auth.WAIT_CODE
            }
            is TdApi.AuthorizationStateWaitPassword -> { passwordHint = s.passwordHint.orEmpty(); auth = Auth.WAIT_PASSWORD }
            is TdApi.AuthorizationStateReady -> { auth = Auth.READY; error = null }
            is TdApi.AuthorizationStateLoggingOut -> auth = Auth.LOGGING_OUT
            is TdApi.AuthorizationStateClosed -> { synchronized(this) { client = null }; if (auth == Auth.LOGGING_OUT) auth = Auth.NO_KEYS.also { app?.let { start(it) } } }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation, is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode, is TdApi.AuthorizationStateWaitRegistration -> {
                error = "Telegram просит подтвердить вход способом, которого здесь нет. Войди с номером, на котором уже есть аккаунт."; auth = Auth.ERROR
            }
            else -> {}
        }
    }

    /** Запрос к TDLib (корутина). Ошибка TDLib → [TgError]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(fn: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client ?: run { cont.resumeWithException(TgError(0, "Telegram не запущен")); return@suspendCancellableCoroutine }
        c.send(fn) { r ->
            if (!cont.isActive) return@send
            if (r is TdApi.Error) cont.resumeWithException(TgError(r.code, ruError(r.message))) else cont.resume(r as T)
        }
    }

    /** То же синхронно (для плеера, из его потока). */
    @Suppress("UNCHECKED_CAST")
    fun <T : TdApi.Object> call(fn: TdApi.Function<T>, timeoutMs: Long = 20_000): T {
        val c = client ?: throw TgError(0, "Telegram не запущен")
        val latch = CountDownLatch(1)
        var res: TdApi.Object? = null
        c.send(fn) { r -> res = r; latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TgError(0, "Telegram не отвечает")
        val r = res
        if (r is TdApi.Error) throw TgError(r.code, ruError(r.message))
        return r as T
    }

    private fun ruError(m: String?): String = when {
        m == null -> "Ошибка Telegram"
        m.contains("PHONE_CODE_INVALID") -> "Неверный код"
        m.contains("PHONE_CODE_EXPIRED") -> "Код устарел — запроси новый"
        m.contains("PASSWORD_HASH_INVALID") -> "Неверный пароль"
        m.contains("PHONE_NUMBER_INVALID") -> "Неверный номер телефона"
        m.contains("API_ID_INVALID") -> "Неверные api_id / api_hash"
        m.contains("FLOOD_WAIT") -> "Telegram просит подождать: слишком много попыток"
        m.contains("INVITE_HASH_EXPIRED") -> "Ссылка-приглашение устарела"
        else -> m
    }

    // ---------- вход ----------
    suspend fun phone(n: String) { send(TdApi.SetAuthenticationPhoneNumber(n.filter { it.isDigit() || it == '+' }, null)) }
    suspend fun code(c: String) { send(TdApi.CheckAuthenticationCode(c.filter { it.isDigit() })) }
    suspend fun password(pw: String) { send(TdApi.CheckAuthenticationPassword(pw)) }
    suspend fun logout() { send(TdApi.LogOut()) }

    // ---------- каналы ----------
    data class ChatRef(val id: Long, val title: String)

    /** Каналы и группы из списка чатов (для выбора канала с кино). */
    suspend fun channels(): List<ChatRef> {
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), 300)) }
        val ids = send(TdApi.GetChats(TdApi.ChatListMain(), 300)).chatIds.toList()
        return ids.mapNotNull { id -> runCatching { send(TdApi.GetChat(id)) }.getOrNull() }
            .filter { it.type is TdApi.ChatTypeSupergroup || it.type is TdApi.ChatTypeBasicGroup }
            .map { ChatRef(it.id, it.title) }
    }

    /** Канал по ссылке-приглашению: если уже в нём — его id, иначе вступить. */
    suspend fun byLink(link: String): ChatRef {
        val info = send(TdApi.CheckChatInviteLink(link.trim()))
        if (info.chatId != 0L) return ChatRef(info.chatId, info.title)
        val chat = send(TdApi.JoinChatByInviteLink(link.trim()))
        return ChatRef(chat.id, chat.title)
    }

    /** Все сообщения канала с видео (как видео и как файл .mp4/.mkv), от новых к старым. */
    suspend fun videos(chatId: Long, onPage: (Int) -> Unit = {}): List<TdApi.Message> {
        val out = LinkedHashMap<Long, TdApi.Message>()
        for (filter in listOf<TdApi.SearchMessagesFilter>(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterDocument())) {
            var from = 0L
            repeat(200) {
                val r = send(TdApi.SearchChatMessages(chatId, null, "", null, from, 0, 100, filter))
                r.messages.forEach { m -> if (TgFilms.fileOf(m) != null) out[m.id] = m }
                onPage(out.size)
                if (r.nextFromMessageId == 0L || r.messages.isEmpty()) return@repeat
                from = r.nextFromMessageId
            }
        }
        return out.values.toList()
    }

    suspend fun message(chatId: Long, id: Long): TdApi.Message = send(TdApi.GetMessage(chatId, id))

    // ---------- файлы ----------
    fun file(id: Int): TdApi.File? = files[id]

    /** Начать (или продолжить с [offset]) загрузку файла: TDLib качает с этого места — так работает перемотка. */
    fun download(fileId: Int, offset: Long, priority: Int = 32) {
        client?.send(TdApi.DownloadFile(fileId, priority, offset, 0, false)) { r -> if (r is TdApi.File) { files[r.id] = r; synchronized(lock) { lock.notifyAll() } } }
    }

    /** Сколько байт подряд уже скачано, начиная с [offset]. */
    fun prefix(fileId: Int, offset: Long): Long = runCatching { call(TdApi.GetFileDownloadedPrefixSize(fileId, offset), 5_000).size }.getOrDefault(0L)

    /** Ждать изменения файла (до [ms]). */
    fun waitUpdate(ms: Long) { synchronized(lock) { lock.wait(ms) } }

    /** Скачанная миниатюра (обложка) — путь, когда готова. */
    suspend fun thumb(fileId: Int): String? = runCatching {
        val f = send(TdApi.DownloadFile(fileId, 1, 0, 0, true))
        f.local.path.takeIf { f.local.isDownloadingCompleted && it.isNotBlank() }
    }.getOrNull()

    /** Удалить скачанное (после просмотра на телефоне ничего не остаётся). */
    fun forget(fileId: Int) {
        client?.send(TdApi.CancelDownloadFile(fileId, false), null)
        client?.send(TdApi.DeleteFile(fileId), null)
    }

    /** Размер кэша Telegram на телефоне. */
    fun cacheSize(ctx: Context): Long = cacheDir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearCache(ctx: Context) {
        client?.send(TdApi.OptimizeStorage(0, 0, 0, 0, null, null, null, false, 0), null)
        cacheDir(ctx).listFiles()?.filter { it.name != "thumbnails" }?.forEach { it.deleteRecursively() }
    }
}
