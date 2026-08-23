package com.subtitleedit.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts API keys with an app-private AES key held by Android Keystore. */
object AiApiKeyStore {
    private const val VERSION = "v2"
    private const val LEGACY_VERSION = "v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "subtitle_edit_ai_api_key_v2"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    @Synchronized
    fun encrypt(apiKey: String): String {
        if (apiKey.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        return "$VERSION.${encode(cipher.iv + encrypted)}"
    }

    /** Returns legacy plain values unchanged so SettingsManager can migrate them on read. */
    @Synchronized
    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        return when {
            isKeystoreEncrypted(storedValue) -> decryptWithKeystore(storedValue)
            storedValue.startsWith("$LEGACY_VERSION.") -> decryptLegacy(storedValue)
            else -> storedValue
        }
    }

    fun isKeystoreEncrypted(value: String): Boolean = value.startsWith("$VERSION.")

    fun needsMigration(value: String): Boolean = value.isNotEmpty() && !isKeystoreEncrypted(value)

    private fun decryptWithKeystore(storedValue: String): String = runCatching {
        val encrypted = decode(storedValue.substringAfter('.'))
        require(encrypted.size > GCM_IV_BYTES)
        val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
        val payload = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** Decrypts the reversible v1 format used before Android Keystore was introduced. */
    private fun decryptLegacy(storedValue: String): String = runCatching {
        val parts = storedValue.split('.', limit = 4)
        require(parts.size == 4 && parts[0] == LEGACY_VERSION)
        val head = String(decode(parts[1]), StandardCharsets.UTF_8)
        val tail = String(decode(parts[2]), StandardCharsets.UTF_8)
        val encrypted = decode(parts[3])
        require(encrypted.size > GCM_IV_BYTES)
        val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
        val payload = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveLegacyKey(head, tail), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun deriveLegacyKey(head: String, tail: String): ByteArray =
        MessageDigest.getInstance("MD5")
            .digest((head + tail).toByteArray(StandardCharsets.UTF_8))

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
}
