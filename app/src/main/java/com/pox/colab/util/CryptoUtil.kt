package com.pox.colab.util

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val ENCRYPTION_PREFIX = "enc:v1:"

    // 256-bit AES key derived from app signing namespace
    private val keySpec: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest("com.pox.colab.secure.keystore.v1".toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext string using AES-GCM-256 with random IV.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return ENCRYPTION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts AES-GCM-256 ciphertext. If not encrypted, returns original string for backward compatibility.
     */
    fun decrypt(encryptedText: String): String {
        if (!encryptedText.startsWith(ENCRYPTION_PREFIX)) {
            return encryptedText
        }
        return try {
            val b64 = encryptedText.removePrefix(ENCRYPTION_PREFIX)
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH_BYTES) return encryptedText

            val iv = ByteArray(IV_LENGTH_BYTES)
            val ciphertext = ByteArray(combined.size - IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES)
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            encryptedText
        }
    }
}
