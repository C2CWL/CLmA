package com.clma.app.data.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 值级脱敏: 保险箱明文出现在命令回显里时必须被抠掉。 */
class SecretValueRedactorTest {

    private val secrets = mapOf("gh_pat" to "ghp_AbCdEf1234567890XyZ", "db_pass" to "hunter2hunter2")

    @Test
    fun `raw value is replaced with a named marker`() {
        val out = SecretValueRedactor.redact("remote: token=ghp_AbCdEf1234567890XyZ authenticated", secrets)
        assertFalse(out.contains("ghp_AbCdEf1234567890XyZ"))
        assertTrue(out.contains("[REDACTED:gh_pat]"))
        assertTrue(out.contains("authenticated"))
    }

    @Test
    fun `base64 form is replaced too`() {
        val encoded = SecretValueRedactor.variantsOf("ghp_AbCdEf1234567890XyZ")
            .first { it != "ghp_AbCdEf1234567890XyZ" && !it.startsWith("ghp") }
        val out = SecretValueRedactor.redact("Authorization: Basic $encoded", secrets)
        assertFalse(out.contains(encoded))
        assertTrue(out.contains("[REDACTED:gh_pat]"))
    }

    @Test
    fun `url encoded form is replaced`() {
        val encoded = SecretValueRedactor.variantsOf("hunter2hunter2").first { it.contains('%') }
        val out = SecretValueRedactor.redact("GET /login?pw=$encoded", secrets)
        assertFalse(out.contains(encoded))
        assertTrue(out.contains("[REDACTED:db_pass]"))
    }

    @Test
    fun `multiple secrets in one blob are all redacted`() {
        val out = SecretValueRedactor.redact(
            "ghp_AbCdEf1234567890XyZ ... hunter2hunter2",
            secrets,
        )
        assertFalse(out.contains("ghp_AbCdEf1234567890XyZ"))
        assertFalse(out.contains("hunter2hunter2"))
    }

    @Test
    fun `short values are left alone to avoid blanking prose`() {
        val out = SecretValueRedactor.redact("the answer is 42", mapOf("pin" to "42"))
        assertEquals("the answer is 42", out)
    }

    @Test
    fun `empty vault and empty text are no-ops`() {
        assertEquals("anything", SecretValueRedactor.redact("anything", emptyMap()))
        assertEquals("", SecretValueRedactor.redact("", secrets))
    }
}