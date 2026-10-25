package com.clma.app.data.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsBackupCryptoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val samplePayload = SecretsBackupPayload(
        agentSecrets = listOf(
            AgentSecretBackupItem(
                name = "test_key",
                value = "sk-secret-123",
                createdAt = 1000L,
                updatedAt = 2000L,
            )
        ),
        workflowSecrets = listOf(
            WorkflowSecretBackupItem(
                name = "wf_token",
                value = "ghp_abc",
            )
        ),
        skillSecrets = listOf(
            SkillSecretBackupItem(
                skillName = "skill_1",
                secretName = "api_token",
                value = "tok_xyz",
            )
        )
    )

    @Test
    fun `encrypt and decrypt round trip succeeds with correct password`() {
        val password = "StrongPassword123!".toCharArray()
        val encrypted = SecretsBackupCrypto.encrypt(samplePayload, password, json)

        // Verify magic header "CSEC"
        assertEquals('C'.code.toByte(), encrypted[0])
        assertEquals('S'.code.toByte(), encrypted[1])
        assertEquals('E'.code.toByte(), encrypted[2])
        assertEquals('C'.code.toByte(), encrypted[3])
        // Version 1
        assertEquals(1.toByte(), encrypted[4])

        val decrypted = SecretsBackupCrypto.decrypt(encrypted, password, json)
        assertEquals(samplePayload.agentSecrets.size, decrypted.agentSecrets.size)
        assertEquals(samplePayload.agentSecrets[0].name, decrypted.agentSecrets[0].name)
        assertEquals(samplePayload.agentSecrets[0].value, decrypted.agentSecrets[0].value)
        assertEquals(samplePayload.workflowSecrets.size, decrypted.workflowSecrets.size)
        assertEquals(samplePayload.workflowSecrets[0].name, decrypted.workflowSecrets[0].name)
        assertEquals(samplePayload.workflowSecrets[0].value, decrypted.workflowSecrets[0].value)
        assertEquals(samplePayload.skillSecrets.size, decrypted.skillSecrets.size)
        assertEquals(samplePayload.skillSecrets[0].skillName, decrypted.skillSecrets[0].skillName)
        assertEquals(samplePayload.skillSecrets[0].secretName, decrypted.skillSecrets[0].secretName)
        assertEquals(samplePayload.skillSecrets[0].value, decrypted.skillSecrets[0].value)
    }

    @Test
    fun `decrypt fails with incorrect password`() {
        val password = "StrongPassword123!".toCharArray()
        val wrongPassword = "WrongPassword456?".toCharArray()
        val encrypted = SecretsBackupCrypto.encrypt(samplePayload, password, json)

        assertThrows(BadPasswordOrCorruptedSecretException::class.java) {
            SecretsBackupCrypto.decrypt(encrypted, wrongPassword, json)
        }
    }

    @Test
    fun `decrypt fails with corrupted ciphertext`() {
        val password = "StrongPassword123!".toCharArray()
        val encrypted = SecretsBackupCrypto.encrypt(samplePayload, password, json)

        // Corrupt the ciphertext
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0xFF).toByte()

        assertThrows(BadPasswordOrCorruptedSecretException::class.java) {
            SecretsBackupCrypto.decrypt(encrypted, password, json)
        }
    }

    @Test
    fun `decrypt fails with invalid magic header`() {
        val password = "StrongPassword123!".toCharArray()
        val encrypted = SecretsBackupCrypto.encrypt(samplePayload, password, json)

        // Corrupt magic
        encrypted[0] = 'X'.code.toByte()

        assertThrows(IllegalArgumentException::class.java) {
            SecretsBackupCrypto.decrypt(encrypted, password, json)
        }
    }
}
