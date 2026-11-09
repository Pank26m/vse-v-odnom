package com.pank.vsevodnom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Кэш сведений и фото Google на 30 дней — по просьбе владельца, чтобы не тратить лимит повторно.
 * Лежит во внутренней папке приложения (files/gcache, недоступна другим приложениям и пользователю), зашифрован
 * ключом AES-GCM из Android Keystore — расшифровать может только это приложение на этом телефоне.
 * Не попадает в резервные копии (ключ не переносится). Выключается в «Ключи и сервисы».
 */
object GCache {
    private const val ALIAS = "gcache_key"
    const val TTL = 30L * 24 * 3600 * 1000

    fun enabled(ctx: Context) = ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE).getBoolean("gcache", true)
    fun setEnabled(ctx: Context, on: Boolean) { ctx.getSharedPreferences("api_keys", Context.MODE_PRIVATE).edit().putBoolean("gcache", on).apply(); if (!on) clear(ctx) }

    private fun dir(ctx: Context) = File(ctx.filesDir, "gcache").apply { mkdirs() }
    private fun name(key: String) = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    fun put(ctx: Context, key: String, data: ByteArray) {
        if (!enabled(ctx)) return
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(data)
            File(dir(ctx), name(key)).outputStream().use { o -> o.write(c.iv.size); o.write(c.iv); o.write(enc) }
        }
    }

    fun get(ctx: Context, key: String): ByteArray? {
        if (!enabled(ctx)) return null
        val f = File(dir(ctx), name(key))
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > TTL) { f.delete(); return null }
        return runCatching {
            val b = f.readBytes(); val n = b[0].toInt()
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, n))
            c.doFinal(b, 1 + n, b.size - 1 - n)
        }.getOrElse { f.delete(); null }
    }

    /** Когда сохранено (мс) или null. */
    fun savedAt(ctx: Context, key: String): Long? = File(dir(ctx), name(key)).takeIf { it.exists() }?.lastModified()

    /** Убрать просроченное (раз при запуске карты). */
    fun prune(ctx: Context) = runCatching { dir(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > TTL) it.delete() } }
    fun clear(ctx: Context) = runCatching { dir(ctx).deleteRecursively() }
    fun size(ctx: Context) = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
}
