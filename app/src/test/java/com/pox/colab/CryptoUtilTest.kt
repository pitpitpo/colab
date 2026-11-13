package com.pox.colab

import com.pox.colab.util.CryptoUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoUtilTest {

    @Test
    fun testEncryptionAndDecryptionRoundtrip() {
        val plaintext = "ya29.a0AfH6SMD_sensitive_oauth_token_xyz"
        val encrypted = CryptoUtil.encrypt(plaintext)

        assertTrue(encrypted.startsWith("enc:v1:"))
        assertNotEquals(plaintext, encrypted)

        val decrypted = CryptoUtil.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testBackwardCompatibilityWithUnencryptedString() {
        val legacy = "{\"token\": \"ya29.plain_token\"}"
        val decrypted = CryptoUtil.decrypt(legacy)
        assertEquals(legacy, decrypted)
    }

    @Test
    fun testEmptyStringHandling() {
        assertEquals("", CryptoUtil.encrypt(""))
        assertEquals("", CryptoUtil.decrypt(""))
    }
}
