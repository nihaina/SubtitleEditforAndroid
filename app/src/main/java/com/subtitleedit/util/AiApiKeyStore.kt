package com.subtitleedit.util

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stores API keys without keeping the complete value in SharedPreferences as plain text. */
object AiApiKeyStore {
    private const val VERSION = "v1"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    fun encrypt(apiKey: String): String {
        if (apiKey.isEmpty()) return ""
        val head = apiKey.take(5)
        val tail = apiKey.takeLast(5)
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(head, tail), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            VERSION,
            encode(head.toByteArray(StandardCharsets.UTF_8)),
            encode(tail.toByteArray(StandardCharsets.UTF_8)),
            encode(iv + encrypted)
        ).joinToString(".")
    }

    /** Returns legacy plain values unchanged so SettingsManager can migrate them on read. */
    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty() || !isEncrypted(storedValue)) return storedValue
        return runCatching {
            val parts = storedValue.split('.', limit = 4)
            require(parts.size == 4 && parts[0] == VERSION)
            val head = String(decode(parts[1]), StandardCharsets.UTF_8)
            val tail = String(decode(parts[2]), StandardCharsets.UTF_8)
            val encrypted = decode(parts[3])
            require(encrypted.size > GCM_IV_BYTES)
            val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
            val payload = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(head, tail), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith("$VERSION.")

    private fun deriveKey(head: String, tail: String): ByteArray =
        MessageDigest.getInstance("MD5")
            .digest((head + tail).toByteArray(StandardCharsets.UTF_8))

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
}
