package com.clma.app.data.network

import com.clma.app.data.datastore.NetworkSetting
import java.net.InetSocketAddress
import java.net.Proxy

/** Explicit opt-in only: never export app credentials into child processes. */
internal fun NetworkSetting.workspaceProxyEnvironment(): Map<String, String> {
    if (!workspaceFollowAppProxy || proxyUrl.isBlank()) return emptyMap()
    require(proxyUsername.isEmpty() && proxyPassword.isEmpty()) {
        "Workspace proxy does not support authentication; disable follow-app proxy or use a proxy without authentication."
    }
    val proxy = requireNotNull(proxyUrl.toProxyOrNull()) { "Invalid application proxy for workspace" }
    val address = proxy.address() as InetSocketAddress
    val host = address.hostString.let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    // Match the app parser's HTTPS-as-HTTP semantics; SOCKS resolves DNS at the proxy.
    val scheme = if (proxy.type() == Proxy.Type.SOCKS) "socks5h" else "http"
    val url = "$scheme://$host:${address.port}"
    return listOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
        .associateWith { url }
}
