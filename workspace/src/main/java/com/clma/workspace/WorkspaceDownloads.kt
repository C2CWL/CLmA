package com.clma.workspace

import java.net.Proxy
import java.net.URL
import java.net.URLConnection

/**
 * Proxy hook for workspace downloads (rootfs archives).
 *
 * :workspace sits below :app and has no access to the app's network setting, so the app
 * process installs a resolver at startup that answers with the configured app proxy, falling
 * back to the system selector. `URL.openConnection()` on its own only ever consults the
 * system selector, which is why a configured app proxy used to be bypassed here.
 *
 * A null resolver, or a resolver returning null, means "leave routing to the platform".
 */
object WorkspaceDownloads {
    @Volatile
    var proxyFor: ((URL) -> Proxy?)? = null

    fun open(url: URL): URLConnection {
        val proxy = runCatching { proxyFor?.invoke(url) }.getOrNull()
        return if (proxy != null) url.openConnection(proxy) else url.openConnection()
    }
}