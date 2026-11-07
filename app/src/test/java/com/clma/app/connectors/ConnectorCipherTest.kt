package com.clma.app.connectors

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectorCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val name = "github.account.test"
    private val text = "Local draft: 中文 😀\nCredential: test-not-a-real-token"

    @Test
    fun `the actual store envelope round trips with a fresh nonce each time`() {
        val first = ConnectorCipher.encrypt(name, text, key)
        val second = ConnectorCipher.encrypt(name, text, key)
        assertEquals(text, ConnectorCipher.decrypt(name, first, key))
        assertEquals(text, ConnectorCipher.decrypt(name, second, key))
        assertNotEquals(first, second)
        assertFalse(first.contains("test-not-a-real-token"))
    }

    @Test
    fun `ciphertext cannot move between credential and draft slots`() {
        val encrypted = ConnectorCipher.encrypt(name, text, key)
        assertThrows(GeneralSecurityException::class.java) {
            ConnectorCipher.decrypt("github.account", encrypted, key)
        }
    }

    @Test
    fun `a wrong key cannot decrypt the envelope`() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = ConnectorCipher.encrypt(name, text, key)
        assertThrows(GeneralSecurityException::class.java) { ConnectorCipher.decrypt(name, encrypted, other) }
    }

    @Test
    fun `tampering with either nonce or ciphertext fails authentication`() {
        val parts = ConnectorCipher.encrypt(name, text, key).split(':')
        parts.indices.forEach { index ->
            val bytes = Base64.getDecoder().decode(parts[index])
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            val changed = parts.toMutableList().apply { set(index, Base64.getEncoder().encodeToString(bytes)) }.joinToString(":")
            assertThrows(GeneralSecurityException::class.java) { ConnectorCipher.decrypt(name, changed, key) }
        }
    }

    @Test
    fun `malformed or plaintext values are never returned as plaintext`() {
        listOf("plaintext-token", "", "not-base64:invalid", "AA==:AA==").forEach { value ->
            assertThrows(Exception::class.java) { ConnectorCipher.decrypt(name, value, key) }
        }
    }
}
