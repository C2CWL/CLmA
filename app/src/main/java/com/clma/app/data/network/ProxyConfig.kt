package com.clma.app.data.network

import com.clma.app.data.datastore.NetworkSetting
import com.clma.app.data.datastore.SettingsStore
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.OkHttpClient
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

fun String.toProxyOrNull(): Proxy? {
    val value = trim()
    if (value.isEmpty()) return null

    val normalizedValue = if ("://" in value) value else "http://$value"
    val uri = runCatching { URI(normalizedValue) }.getOrNull() ?: return null
    val type = when (uri.scheme?.lowercase()) {
        "http", "https" -> Proxy.Type.HTTP
        "socks5" -> Proxy.Type.SOCKS
        else -> return null
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it in 1..65535 } ?: return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (uri.path?.let { it.isNotEmpty() && it != "/" } == true) return null

    return Proxy(type, InetSocketAddress.createUnresolved(host, port))
}

fun OkHttpClient.Builder.applyAppProxy(settingsStore: SettingsStore): OkHttpClient.Builder =
    proxySelector(SettingsProxySelector(settingsStore))
        .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))

class SettingsProxyAuthenticator(
    private val settingsStore: SettingsStore,
) : OkHttpAuthenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val setting = settingsStore.settingsFlow.value.networkSetting
        val configuredProxy = setting.proxyUrl.toProxyOrNull() ?: return null
        if (configuredProxy.type() != Proxy.Type.HTTP || route?.proxy != configuredProxy) return null
        if (setting.proxyUsername.isEmpty()) return null
        if (response.request.header("Proxy-Authorization") != null) return null

        return response.request.newBuilder()
            .header(
                "Proxy-Authorization",
                Credentials.basic(setting.proxyUsername, setting.proxyPassword, Charsets.UTF_8),
            )
            .build()
    }
}

class SettingsSocks5Authenticator(
    private val settingsStore: SettingsStore,
) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val setting = settingsStore.settingsFlow.value.networkSetting
        val configuredProxy = setting.proxyUrl.toProxyOrNull() ?: return null
        val proxyAddress = configuredProxy.address() as? InetSocketAddress ?: return null
        if (configuredProxy.type() != Proxy.Type.SOCKS) return null
        if (setting.proxyUsername.isEmpty()) return null
        if (!requestingProtocol.equals("SOCKS5", ignoreCase = true)) return null
        if (requestingPort != proxyAddress.port) return null

        val requestedHost = requestingHost?.removeSurrounding("[", "]")
        val configuredHost = proxyAddress.hostString.removeSurrounding("[", "]")
        if (!requestedHost.equals(configuredHost, ignoreCase = true)) return null

        return PasswordAuthentication(setting.proxyUsername, setting.proxyPassword.toCharArray())
    }
}

class SettingsProxySelector(
    private val settingsStore: SettingsStore,
    private val systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull()?.let { proxy ->
            return listOf(proxy)
        }
        return runCatching { systemProxySelector?.select(uri) }
            .getOrNull()
            .orEmpty()
            .ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) {
        if (settingsStore.settingsFlow.value.networkSetting.proxyUrl.toProxyOrNull() == null) {
            systemProxySelector?.connectFailed(uri, socketAddress, exception)
        }
    }
}

/**
 * The proxy an SSH session should use so it follows the same network setting as OkHttp.
 *
 * An explicit app proxy wins. An empty proxy URL falls through to the system selector, the
 * same order [SettingsProxySelector] uses. Without an HTTP/SOCKS proxy, the target is
 * reached through Android's default route for the app (which may be a VPN).
 *
 * Credentials are attached only when the chosen proxy is the one configured in the app.
 * A system proxy must not receive the app proxy's username and password.
 *
 * The returned JSch proxy connects to the proxy host; the original SSH hostname is handed
 * to the proxy as-is, so DNS for the SSH target happens on the far side of the proxy.
 */
data class SshProxyChoice(
    val proxy: com.jcraft.jsch.Proxy,
    /** Host the socket factory dials. JSch then asks this proxy to reach the SSH target. */
    val dialHost: String,
)

internal fun NetworkSetting.jschProxy(
    targetHost: String,
    targetPort: Int,
    systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
): SshProxyChoice? {
    val configured = proxyUrl.toProxyOrNull()
    val chosen = configured ?: systemProxy(targetHost, targetPort, systemProxySelector) ?: return null
    val address = chosen.address() as? InetSocketAddress ?: return null
    val host = address.hostString?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: return null
    val port = address.port.takeIf { it in 1..65535 } ?: return null
    val proxy: com.jcraft.jsch.Proxy = when (chosen.type()) {
        Proxy.Type.HTTP -> ProxyHTTP(host, port)
        Proxy.Type.SOCKS -> ProxySOCKS5(host, port)
        else -> return null
    }
    if (configured != null && chosen == configured && proxyUsername.isNotEmpty()) {
        when (proxy) {
            is ProxyHTTP -> proxy.setUserPasswd(proxyUsername, proxyPassword)
            is ProxySOCKS5 -> proxy.setUserPasswd(proxyUsername, proxyPassword)
        }
    }
    return SshProxyChoice(proxy, host)
}

private fun systemProxy(host: String, port: Int, selector: ProxySelector?): Proxy? {
    val uri = URI("ssh", null, host, port, null, null, null)
    val selected = runCatching { selector?.select(uri) }.getOrNull().orEmpty()
    return selected.firstOrNull { proxy ->
        proxy.type() == Proxy.Type.HTTP || proxy.type() == Proxy.Type.SOCKS
    }
}
