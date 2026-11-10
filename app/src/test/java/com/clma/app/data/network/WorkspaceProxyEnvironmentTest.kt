package com.clma.app.data.network

import com.clma.app.data.datastore.NetworkSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProxyEnvironmentTest {
    @Test
    fun `default and blank proxy export nothing`() {
        assertTrue(NetworkSetting(proxyUrl = "http://localhost:7890").workspaceProxyEnvironment().isEmpty())
        assertTrue(NetworkSetting(workspaceFollowAppProxy = true).workspaceProxyEnvironment().isEmpty())
    }

    @Test
    fun `http proxy supplies standard variables without changing app semantics`() {
        val env =
            NetworkSetting(
                workspaceFollowAppProxy = true,
                proxyUrl = "https://localhost:8443",
            ).workspaceProxyEnvironment()
        assertEquals(setOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"), env.keys)
        assertEquals(setOf("http://localhost:8443"), env.values.toSet())
    }

    @Test
    fun `socks uses remote dns and preserves ipv6 address`() {
        val env =
            NetworkSetting(
                workspaceFollowAppProxy = true,
                proxyUrl = "socks5://[::1]:1080",
            ).workspaceProxyEnvironment()
        assertEquals("socks5h://[::1]:1080", env["all_proxy"])
    }

    @Test
    fun `invalid or authenticated proxies fail without disclosing secrets`() {
        for (setting in listOf(
            NetworkSetting(proxyUrl = "http://private:secret@localhost:7890"),
            NetworkSetting(proxyUrl = "http://localhost:7890", proxyUsername = "private", proxyPassword = "secret"),
            NetworkSetting(proxyUrl = "http://localhost:7890", proxyPassword = "secret"),
        )) {
            val error = runCatching { setting.copy(workspaceFollowAppProxy = true).workspaceProxyEnvironment() }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
    }
}
