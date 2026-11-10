package com.pank.vsevodnom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/** Дополнительное поле записи: «Старый пароль», «PIN», «Секретный вопрос»… */
data class VaultExtra(val label: String, val value: String)

/** Вложение записи (фото, ключ КЭП, любой файл) — лежит зашифрованным в files/vault_files/<id>. */
data class VaultFile(val id: String, val name: String, val mime: String, val size: Long)

data class VaultEntry(
    val id: String,
    val title: String,
    val login: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp: String = "",       // секрет base32 для кодов 2FA (поле больше не показывается, но не теряем)
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val extra: List<VaultExtra> = emptyList(),
    val avatar: String = "",                 // id зашифрованной картинки-аватарки (иконка банка и т. п.)
    val files: List<VaultFile> = emptyList(),
    /** "doc" — документ (сканы, файлы) во вкладке «Документы»; пусто — пароль. */
    val kind: String = "",
    /** Папка документа («Паспорт», «Авто»…). */
    val folder: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("login", login).put("password", password)
        .put("url", url).put("notes", notes).put("totp", totp).put("updatedAt", updatedAt)
        .put("tags", org.json.JSONArray(tags))
        .put("extra", org.json.JSONArray(extra.map { JSONObject().put("label", it.label).put("value", it.value) }))
        .put("avatar", avatar)
        .put("files", org.json.JSONArray(files.map { JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size) }))
        .put("kind", kind).put("folder", folder)

    companion object {
        fun fromJson(o: JSONObject) = VaultEntry(
            o.getString("id"), o.optString("title"), o.optString("login"), o.optString("password"),
            o.optString("url"), o.optString("notes"), o.optString("totp"), o.optLong("updatedAt"),
            o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optJSONArray("extra")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> VaultExtra(x.optString("label"), x.optString("value")) } } } ?: emptyList(),
            o.optString("avatar"),
            o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> VaultFile(x.optString("id"), x.optString("name"), x.optString("mime"), x.optLong("size")) } } } ?: emptyList(),
            o.optString("kind"), o.optString("folder"),
        )
    }
}

/**
 * Хранилище паролей.
 * Схема: случайный ключ данных (DEK, AES-256) шифрует файл с записями (AES-GCM).
 * Сам DEK зашифрован ключом из аппаратного хранилища Android (Keystore), который
 * можно использовать только сразу после разблокировки лицом/отпечатком/PIN телефона.
 * Пока хранилище открыто, DEK лежит в памяти; при уходе из приложения — стирается.
 */
object Vault {
    private const val KS_ALIAS = "vault_master_v1"
    private const val GCM_TAG = 128
    private val rnd = SecureRandom()

    private fun keyFile(ctx: Context) = File(ctx.filesDir, "vault.key")
    private fun dataFile(ctx: Context) = File(ctx.filesDir, "vault.dat")

    @Volatile private var dek: ByteArray? = null
    val isOpen get() = dek != null

    fun exists(ctx: Context) = keyFile(ctx).exists()

    private fun masterKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createMasterKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // смена отпечатков не должна уничтожать хранилище — вход и так защищён телефоном
            .setInvalidatedByBiometricEnrollment(false)
        if (android.os.Build.VERSION.SDK_INT >= 30)
            b.setUserAuthenticationParameters(15, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(15)
        kg.init(b.build())
        return kg.generateKey()
    }

    /** Вызывать сразу после успешной биометрии. Создаёт хранилище при первом запуске. */
    fun openAfterAuth(ctx: Context) {
        if (!exists(ctx)) {
            val newDek = ByteArray(32).also(rnd::nextBytes)
            val mk = masterKey() ?: createMasterKey()
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, mk)
            val wrapped = c.doFinal(newDek)
            keyFile(ctx).writeText(JSONObject().put("iv", b64(c.iv)).put("k", b64(wrapped)).toString())
            dek = newDek
            save(ctx, emptyList())
            return
        }
        val j = JSONObject(keyFile(ctx).readText())
        val mk = masterKey() ?: throw IllegalStateException("Ключ хранилища пропал из системы. Восстанови из бэкапа.")
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, mk, GCMParameterSpec(GCM_TAG, unb64(j.getString("iv"))))
        dek = c.doFinal(unb64(j.getString("k")))
    }

    fun lock() { dek?.fill(0); dek = null }

    private fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG, iv))
        return iv + c.doFinal(plain)
    }

    private fun aesDecrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun load(ctx: Context): List<VaultEntry> {
        val k = dek ?: throw IllegalStateException("Хранилище закрыто")
        val f = dataFile(ctx); if (!f.exists()) return emptyList()
        val arr = JSONArray(String(aesDecrypt(k, f.readBytes()), Charsets.UTF_8))
        return (0 until arr.length()).map { VaultEntry.fromJson(arr.getJSONObject(it)) }
    }

    fun save(ctx: Context, list: List<VaultEntry>) {
        val k = dek ?: throw IllegalStateException("Хранилище закрыто")
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "vault.dat.tmp")
        tmp.writeBytes(aesEncrypt(k, arr.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(dataFile(ctx)) // атомарная замена — не потеряем данные при сбое записи
    }

    // ---------- вложения (шифруются тем же ключом данных) ----------

    private fun filesDir(ctx: Context) = File(ctx.filesDir, "vault_files").apply { mkdirs() }

    /** Зашифровать и сохранить байты; вернуть id. */
    fun putFile(ctx: Context, bytes: ByteArray): String {
        val k = dek ?: throw IllegalStateException("Хранилище закрыто")
        val id = java.util.UUID.randomUUID().toString()
        File(filesDir(ctx), id).writeBytes(aesEncrypt(k, bytes))
        return id
    }
    fun readFile(ctx: Context, id: String): ByteArray? {
        val k = dek ?: return null
        val f = File(filesDir(ctx), id); if (!f.exists()) return null
        return runCatching { aesDecrypt(k, f.readBytes()) }.getOrNull()
    }
    fun deleteFile(ctx: Context, id: String) { if (id.isNotBlank()) File(filesDir(ctx), id).delete() }
    /** Удалить файлы, на которые больше не ссылается ни одна запись. */
    fun cleanupFiles(ctx: Context, list: List<VaultEntry>) {
        val used = list.flatMap { e -> e.files.map { it.id } + e.avatar }.filter { it.isNotBlank() }.toSet()
        filesDir(ctx).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    // ---------- бэкап с паролем ----------

    private fun derive(pass: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, 210_000, 256)).encoded

    /** Файл бэкапа: «VVO1» + соль + зашифрованный JSON. Открывается только паролем бэкапа. */
    fun exportBackup(list: List<VaultEntry>, pass: CharArray, ctx: Context? = null): ByteArray {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val arr = JSONArray(); list.forEach { e ->
            val o = e.toJson()
            if (ctx != null) {
                val blobs = JSONObject()
                (e.files.map { it.id } + e.avatar).filter { it.isNotBlank() }.forEach { id -> readFile(ctx, id)?.let { blobs.put(id, b64(it)) } }
                if (blobs.length() > 0) o.put("blobs", blobs)
            }
            arr.put(o)
        }
        return "VVO1".toByteArray() + salt + aesEncrypt(derive(pass, salt), arr.toString().toByteArray(Charsets.UTF_8))
    }

    fun importBackup(blob: ByteArray, pass: CharArray, ctx: Context? = null): List<VaultEntry> {
        require(blob.size > 36 && String(blob, 0, 4) == "VVO1") { "Это не файл бэкапа паролей" }
        val salt = blob.copyOfRange(4, 20)
        val plain = runCatching { aesDecrypt(derive(pass, salt), blob.copyOfRange(20, blob.size)) }.getOrElse { throw IllegalArgumentException("Неверный пароль бэкапа") }
        val arr = JSONArray(String(plain, Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val e = VaultEntry.fromJson(o)
            val blobs = o.optJSONObject("blobs")
            if (ctx == null || blobs == null || dek == null) e else {
                // заново шифруем вложения ключом этого телефона, id меняются
                val map = HashMap<String, String>()
                blobs.keys().forEach { id -> runCatching { map[id] = putFile(ctx, unb64(blobs.getString(id))) } }
                e.copy(avatar = map[e.avatar] ?: "", files = e.files.mapNotNull { f -> map[f.id]?.let { f.copy(id = it) } })
            }
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

/** Коды 2FA (RFC 6238, как Google Authenticator). */
object Totp {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decodeBase32(s: String): ByteArray {
        val clean = s.uppercase().filter { it in B32 }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (ch in clean) { buf = (buf shl 5) or B32.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 } }
        return out.toByteArray()
    }

    data class Params(val secret: String, val digits: Int = 6, val period: Int = 30, val algo: String = "HmacSHA1", val issuer: String = "", val account: String = "")

    /** Разбирает «otpauth://totp/...?secret=...» или просто секрет. */
    fun parse(input: String): Params? {
        val t = input.trim()
        if (t.startsWith("otpauth://", true)) {
            val u = android.net.Uri.parse(t)
            val secret = u.getQueryParameter("secret") ?: return null
            val label = java.net.URLDecoder.decode(u.path?.trimStart('/') ?: "", "UTF-8")
            val algo = when (u.getQueryParameter("algorithm")?.uppercase()) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
            return Params(secret, u.getQueryParameter("digits")?.toIntOrNull() ?: 6, u.getQueryParameter("period")?.toIntOrNull() ?: 30, algo,
                u.getQueryParameter("issuer") ?: label.substringBefore(':', ""), label.substringAfter(':'))
        }
        return if (decodeBase32(t).size >= 10) Params(t) else null
    }

    fun code(p: Params, timeMs: Long = System.currentTimeMillis()): String {
        val key = decodeBase32(p.secret)
        val counter = timeMs / 1000 / p.period
        val mac = Mac.getInstance(p.algo); mac.init(SecretKeySpec(key, p.algo))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        return (bin % 10.0.pow(p.digits).toInt()).toString().padStart(p.digits, '0')
    }
}

object PassGen {
    private val rnd = SecureRandom()
    fun generate(len: Int, upper: Boolean, digits: Boolean, symbols: Boolean): String {
        val lower = "abcdefghijkmnopqrstuvwxyz"; val up = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val dg = "23456789"; val sy = "!@#$%^&*-_=+?"
        val sets = listOfNotNull(lower, up.takeIf { upper }, dg.takeIf { digits }, sy.takeIf { symbols })
        val all = sets.joinToString("")
        // по символу из каждого выбранного набора, остальное — случайно
        val chars = sets.map { it[rnd.nextInt(it.length)] }.toMutableList()
        while (chars.size < len) chars += all[rnd.nextInt(all.length)]
        for (i in chars.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = chars[i]; chars[i] = chars[j]; chars[j] = t }
        return chars.joinToString("")
    }
}
