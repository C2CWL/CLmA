package com.clma.app.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class AgentSecretBackupItem(
    val name: String,
    val value: String,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class WorkflowSecretBackupItem(
    val name: String,
    val value: String,
)

@Serializable
data class SkillSecretBackupItem(
    val skillName: String,
    val secretName: String,
    val value: String,
)

@Serializable
data class SecretsBackupPayload(
    val agentSecrets: List<AgentSecretBackupItem> = emptyList(),
    val workflowSecrets: List<WorkflowSecretBackupItem> = emptyList(),
    val skillSecrets: List<SkillSecretBackupItem> = emptyList(),
) {
    fun isEmpty(): Boolean = agentSecrets.isEmpty() && workflowSecrets.isEmpty() && skillSecrets.isEmpty()
}

/**
 * AES-256-GCM encryption with PBKDF2-HMAC-SHA256 key derivation for backup secrets.
 *
 * Binary format:
 * - Magic (4 bytes): `CSEC` (0x43, 0x53, 0x45, 0x43)
 * - Version (1 byte): 0x01
 * - Salt (16 bytes)
 * - IV (12 bytes)
 * - Ciphertext + GCM Tag (16 bytes tag appended)
 */
object SecretsBackupCrypto {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
    private const val VERSION: Byte = 0x01
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_BITS = 256

    fun encrypt(payload: SecretsBackupPayload, password: CharArray, json: Json): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val plainBytes = json.encodeToString(SecretsBackupPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8)

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val keySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKeyBytes = factory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plainBytes)

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.write(MAGIC)
        dos.writeByte(VERSION.toInt())
        dos.write(salt)
        dos.write(iv)
        dos.write(ciphertext)
        dos.flush()
        return out.toByteArray()
    }

    fun decrypt(encryptedBytes: ByteArray, password: CharArray, json: Json): SecretsBackupPayload {
        require(password.isNotEmpty()) { "Password must not be empty" }
        if (encryptedBytes.size < 4 + 1 + SALT_BYTES + IV_BYTES + 16) {
            throw IllegalArgumentException("Invalid encrypted secrets format: payload too short")
        }

        val dis = DataInputStream(ByteArrayInputStream(encryptedBytes))
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid magic header for encrypted secrets")
        }
        val version = dis.readByte()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported encrypted secrets version: $version")
        }
        val salt = ByteArray(SALT_BYTES)
        dis.readFully(salt)
        val iv = ByteArray(IV_BYTES)
        dis.readFully(iv)
        val ciphertext = dis.readBytes()

        val keySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKeyBytes = factory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decryptedBytes = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BadPasswordOrCorruptedSecretException("Failed to decrypt secrets: incorrect password or corrupted file", e)
        }

        val plainJson = String(decryptedBytes, StandardCharsets.UTF_8)
        return json.decodeFromString(SecretsBackupPayload.serializer(), plainJson)
    }
}

class BadPasswordOrCorruptedSecretException(message: String, cause: Throwable? = null) : Exception(message, cause)
