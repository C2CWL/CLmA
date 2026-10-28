package com.clma.ai.provider

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BalanceQueryTest {
    @Test
    fun `buildBalanceRequest builds GET request with relative path and merged headers`() {
        val request = buildBalanceRequest(
            baseUrl = "https://api.example.com/v1/",
            apiKey = "sk-test",
            providerHeaders = listOf(
                CustomHeader("X-Provider", "one"),
                CustomHeader("X-Override", "from-provider"),
            ),
            option = BalanceOption(
                enabled = true,
                apiPath = "/credits",
                headers = listOf(CustomHeader("X-Override", "from-balance")),
            ),
        )

        assertEquals("GET", request.method)
        assertEquals("https://api.example.com/v1/credits", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("one", request.header("X-Provider"))
        assertEquals("from-balance", request.header("X-Override"))
    }

    @Test
    fun `buildBalanceRequest builds POST request when body is JSON object`() {
        val request = buildBalanceRequest(
            baseUrl = "https://api.example.com/v1",
            apiKey = "",
            providerHeaders = emptyList(),
            option = BalanceOption(
                enabled = true,
                apiPath = "https://api.example.com/user/balance",
                method = BalanceMethod.POST,
                requestBody = "{\"currency\":\"USD\"}",
            ),
        )

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/user/balance", request.url.toString())
        assertEquals("{\"currency\":\"USD\"}", buffer.readUtf8())
    }

    @Test
    fun `buildBalanceRequest rejects cross-origin full URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildBalanceRequest(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-secret",
                providerHeaders = emptyList(),
                option = BalanceOption(
                    enabled = true,
                    apiPath = "https://evil.example.org/collect",
                ),
            )
        }
    }

    @Test
    fun `buildBalanceRequest rejects non-object POST body`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildBalanceRequest(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-secret",
                providerHeaders = emptyList(),
                option = BalanceOption(
                    enabled = true,
                    apiPath = "/credits",
                    method = BalanceMethod.POST,
                    requestBody = "[1, 2, 3]",
                ),
            )
        }
    }

    @Test
    fun `formatBalanceResponse formats numeric expression with decimals prefix and suffix`() {
        val text = formatBalanceResponse(
            body = """{"data":{"total_credits":10.0,"total_usage":3.456}}""",
            option = BalanceOption(
                enabled = true,
                resultPath = "data.total_credits - data.total_usage",
                decimals = 2,
                prefix = "$",
                suffix = " USD",
            ),
        )

        assertEquals("$6.54 USD", text)
    }

    @Test
    fun `formatBalanceResponse preserves non-numeric text and wraps prefix suffix`() {
        val text = formatBalanceResponse(
            body = """{"data":{"plan":"Pro Unlimited"}}""",
            option = BalanceOption(
                enabled = true,
                resultPath = "data.plan",
                prefix = "[",
                suffix = "]",
            ),
        )

        assertEquals("[Pro Unlimited]", text)
    }
}
