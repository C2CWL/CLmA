package com.clma.app.data.network

import com.clma.app.data.datastore.NetworkSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class ProxyConfigTest {
    @Test
    fun `parses http proxy URL`() {
        val proxy = "http://127.0.0.1:7890".toProxyOrNull()!!
        val address = proxy.address() as InetSocketAddress

        assertEquals(Proxy.Type.HTTP, proxy.type())
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
    }

    @Test
    fun `parses socks proxy URL`() {
        val proxy = "socks5://localhost:1080".toProxyOrNull()!!

        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `maps https proxy URL to http proxy type`() {
        val proxy = "https://localhost:8443".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `defaults host and port input to http proxy`() {
        val proxy = "localhost:8080".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `rejects invalid proxy URL`() {
        assertNull("ftp://localhost:21".toProxyOrNull())
        assertNull("socks://localhost:1080".toProxyOrNull())
        assertNull("http://localhost".toProxyOrNull())
        assertNull("http://user:password@localhost:8080".toProxyOrNull())
        assertNull("http://localhost:8080/path".toProxyOrNull())
    }

    @Test
    fun `ssh proxy uses the configured app proxy`() {
        val choice = NetworkSetting(proxyUrl = "socks5://127.0.0.1:1080")
            .jschProxy("example.com", 22, selectorReturningNone())!!

        assertEquals("ProxySOCKS5", choice.proxy::class.java.simpleName)
        assertEquals("127.0.0.1", choice.dialHost)
    }

    @Test
    fun `ssh proxy uses http proxy and keeps the target hostname for the proxy`() {
        val choice = NetworkSetting(proxyUrl = "http://10.0.0.5:3128")
            .jschProxy("example.com", 22, selectorReturningNone())!!

        assertEquals("ProxyHTTP", choice.proxy::class.java.simpleName)
        assertEquals("10.0.0.5", choice.dialHost)
    }

    @Test
    fun `ssh proxy falls back to the system selector when no app proxy is set`() {
        val system = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("192.0.2.10", 8080))
        val choice = NetworkSetting().jschProxy("example.com", 22, selectorReturning(system))!!

        assertEquals("ProxyHTTP", choice.proxy::class.java.simpleName)
        assertEquals("192.0.2.10", choice.dialHost)
    }

    @Test
    fun `ssh proxy returns null for direct routing`() {
        assertNull(NetworkSetting().jschProxy("example.com", 22, selectorReturningNone()))
    }

    @Test
    fun `ssh proxy ignores an invalid app proxy URL`() {
        val system = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("192.0.2.11", 1080))
        val choice = NetworkSetting(proxyUrl = "not a proxy").jschProxy(
            "example.com",
            22,
            selectorReturning(system),
        )!!

        assertEquals("ProxySOCKS5", choice.proxy::class.java.simpleName)
        assertEquals("192.0.2.11", choice.dialHost)
    }

    @Test
    fun `loopback and localhost proxies cannot be reached through a bound socket`() {
        assertTrue(isUnbindableProxyHost("127.0.0.1"))
        assertTrue(isUnbindableProxyHost("localhost"))
        assertTrue(isUnbindableProxyHost("[::1]"))
        assertFalse(isUnbindableProxyHost("192.0.2.1"))
    }

    private fun selectorReturningNone() = FixedSelector(listOf(Proxy.NO_PROXY))

    private fun selectorReturning(proxy: Proxy) = FixedSelector(listOf(proxy))

    private class FixedSelector(private val proxies: List<Proxy>) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = proxies

        override fun connectFailed(uri: URI, sa: SocketAddress, e: IOException) {}
    }
}
