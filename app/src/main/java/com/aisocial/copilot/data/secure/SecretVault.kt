package com.aisocial.copilot.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256/GCM encryption backed by the Android Keystore.
 *
 * API keys are never stored in plaintext and never logged. The keystore key itself is
 * non-exportable and can only be used through this process.
 */
class SecretVault {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey? {
        return try {
            val existing = (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns a Base64 blob, or an empty string when the value is empty / encryption fails. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = secretKey() ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteBuffer.allocate(1 + iv.size + cipherText.size)
                .put(iv.size.toByte())
                .put(iv)
                .put(cipherText)
                .array()
            Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    /** Returns an empty string when the blob cannot be decrypted (e.g. after a keystore reset). */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val key = secretKey() ?: return ""
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = packed[0].toInt()
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val cipherText = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ai_social_copilot_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
