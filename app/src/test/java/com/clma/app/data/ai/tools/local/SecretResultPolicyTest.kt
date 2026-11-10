package com.clma.app.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretResultPolicyTest {

    @Test
    fun `plaintext credentials are rejected for password and key`() {
        val res = resolveSecretInput(refName = null, plain = "hunter2", field = "password") { Result.success(null) }
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("plaintext_credentials_rejected") == true)
    }

    @Test
    fun `plain stdin remains supported when no secret ref is supplied`() {
        val res = resolveSecretInput(refName = null, plain = "ls -la\n", field = "stdin") { Result.success(null) }
        assertTrue(res.isSuccess)
        assertEquals("ls -la\n", res.getOrNull())
    }

    @Test
    fun `secret ref takes precedence and cannot be mixed with plain input`() {
        val res = resolveSecretInput(refName = "db_pass", plain = "fallback", field = "stdin") {
            Result.success("secret_val")
        }
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("conflicting_secret_and_plain_input") == true)
    }

    @Test
    fun `capture suppresses stdout and stderr when command fails`() {
        val payload = buildJsonObject {
            put("exit_code", 1)
            put("stdout", "some_token_here")
            put("stderr", "error: command failed")
        }
        val out = captureSecretOutput(payload, name = "tok") { _, _ -> true }
        assertEquals("secret_capture_failed", out["error"]?.jsonPrimitive?.contentOrNull)
        assertNull(out["stdout"])
        assertNull(out["stderr"])
    }

    @Test
    fun `capture stores secret and returns only name when successful`() {
        var savedName: String? = null
        var savedVal: String? = null
        val payload = buildJsonObject {
            put("exit_code", 0)
            put("stdout", "super_secret_token\n")
        }
        val out = captureSecretOutput(payload, name = "new_token") { k, v ->
            savedName = k
            savedVal = v
            true
        }
        assertEquals(true, out["stored"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        assertEquals("new_token", out["secret_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("super_secret_token\n", savedVal)
        assertNull(out["stdout"])
    }
}
