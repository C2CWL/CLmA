package com.clma.search.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SearchEgressGuardTest {

    private fun fixedDns(vararg ips: String) = Dns { _ ->
        ips.map { InetAddress.getByName(it) }
    }

    @Test
    fun `public address resolves normally`() {
        val dns = GuardedDns(fixedDns("93.184.216.34"), allowPrivate = false)

        val out = dns.lookup("example.com")

        assertEquals(1, out.size)
    }

    @Test
    fun `loopback is blocked`() {
        val dns = GuardedDns(fixedDns("127.0.0.1"), allowPrivate = false)

        try {
            dns.lookup("localhost")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("blocked_private_address"))
        }
    }

    @Test
    fun `private ranges are blocked`() {
        listOf("10.0.0.5", "192.168.1.10", "172.16.0.3", "169.254.169.254").forEach { ip ->
            val dns = GuardedDns(fixedDns(ip), allowPrivate = false)
            try {
                dns.lookup("target.example")
                fail("expected UnknownHostException for $ip")
            } catch (e: UnknownHostException) {
                assertTrue(e.message!!.contains("blocked_private_address"))
            }
        }
    }

    @Test
    fun `ipv6 loopback and unique local are blocked`() {
        listOf("::1", "fd00::1", "fc00::1").forEach { ip ->
            val dns = GuardedDns(fixedDns(ip), allowPrivate = false)
            try {
                dns.lookup("target.example")
                fail("expected UnknownHostException for $ip")
            } catch (e: UnknownHostException) {
                assertTrue(e.message!!.contains("blocked_private_address"))
            }
        }
    }

    @Test
    fun `hostIsBlockedLiteral refuses private ip literals and passes public ones`() {
        // Covers the literal-IP hosts OkHttp routes without consulting GuardedDns.
        listOf(
            "127.0.0.1", "192.168.1.1", "169.254.169.254", "::1", "fd00::1",
            "::ffff:127.0.0.1", "0.1.2.3",
        ).forEach { host ->
            assertTrue("$host should be blocked", hostIsBlockedLiteral(host))
        }
        listOf(
            "8.8.8.8", "2001:4860:4860::8888", "::ffff:8.8.8.8", "example.com",
        ).forEach { host ->
            assertFalse("$host should be allowed", hostIsBlockedLiteral(host))
        }
    }

    @Test
    fun `hostIsBlockedLiteral treats null and blank as unblocked`() {
        assertFalse(hostIsBlockedLiteral(null))
        assertFalse(hostIsBlockedLiteral(""))
        assertFalse(hostIsBlockedLiteral("  "))
    }

    @Test
    fun `ipv4-mapped ipv6 forms of private addresses are blocked`() {
        // ::ffff:127.0.0.1 reaches loopback through a dual-stack socket even though the
        // Inet6Address flag checks do not classify it as loopback/site-local.
        listOf(
            "::ffff:127.0.0.1", "::ffff:10.0.0.5", "::ffff:192.168.1.10",
            "::ffff:169.254.169.254", "::127.0.0.1", "0.1.2.3",
        ).forEach { ip ->
            val dns = GuardedDns(fixedDns(ip), allowPrivate = false)
            try {
                dns.lookup("target.example")
                fail("expected UnknownHostException for $ip")
            } catch (e: UnknownHostException) {
                assertTrue(e.message!!.contains("blocked_private_address"))
            }
        }
    }

    @Test
    fun `ipv4-mapped ipv6 form of a public address still resolves`() {
        val dns = GuardedDns(fixedDns("::ffff:93.184.216.34"), allowPrivate = false)

        assertEquals(1, dns.lookup("example.com").size)
    }
}