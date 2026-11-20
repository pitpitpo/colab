package com.pox.colab.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val ENCRYPTION_PREFIX = "enc:v1:"

    private val secureRandom = SecureRandom()

    // 256-bit AES key derived from app signing namespace
    private val keySpec: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest("com.pox.colab.secure.keystore.v1".toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext string using AES-GCM-256 with an explicit 12-byte random IV.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(IV_LENGTH_BYTES + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES)
        System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.size)

        return ENCRYPTION_PREFIX + Base64.getEncoder().encodeToString(combined)
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
            val combined = Base64.getDecoder().decode(b64)
            if (combined.size <= IV_LENGTH_BYTES) return encryptedText

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            val decryptedBytes = cipher.doFinal(combined, IV_LENGTH_BYTES, combined.size - IV_LENGTH_BYTES)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            encryptedText
        }
    }
}
