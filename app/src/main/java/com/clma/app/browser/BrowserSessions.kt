package com.clma.app.browser

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Tabs isolate DOM/history, NOT cookies: Android's default WebView profile is shared. */
object BrowserSessions {
    class Tab(val owner: String, val id: String, val view: WebView, val host: HeadlessBrowserSession?) {
        val mutex = Mutex()
        val clock = BrowserTaskClock { SystemClock.elapsedRealtime() }
        var job: kotlinx.coroutines.Job? = null
    }
    private val tabs = linkedMapOf<String, Tab>()
    private val active = mutableMapOf<String, String>()
    private val current = ThreadLocal<Tab?>()
    val legacyMutex = Mutex()
    var legacyOwner: String? = null
    fun selected(): Tab? = current.get()
    @Synchronized fun get(owner: String, id: String? = null): Tab? =
        tabs[id ?: active[owner]]?.takeIf { it.owner == owner }
    @Synchronized fun list(owner: String): List<Tab> = tabs.values.filter { it.owner == owner }
    @Synchronized fun select(tab: Tab) { check(tabs[tab.id] === tab); active[tab.owner] = tab.id }
    @Synchronized private fun add(tab: Tab): Tab {
        check(tabs.size < 12 && list(tab.owner).size < 4) { "tab_limit_reached" }
        tabs[tab.id] = tab
        active[tab.owner] = tab.id
        return tab
    }
    suspend fun create(context: Context, owner: String): Tab = withContext(Dispatchers.Main) {
        synchronized(this@BrowserSessions) {
            check(tabs.size < 12 && list(owner).size < 4) { "tab_limit_reached" }
            val host = HeadlessBrowserSession(context.applicationContext)
            val tab = Tab(owner, UUID.randomUUID().toString(), host.start(owner), host)
            add(tab)
        }
    }
    fun adopt(owner: String, view: WebView): Tab = synchronized(this) {
        tabs.values.firstOrNull { it.view === view }?.let {
            check(it.owner == owner) { "browser_owned_by_another_session" }; return it
        }
        add(Tab(owner, UUID.randomUUID().toString(), view, null))
    }
    suspend fun <T> use(tab: Tab, block: suspend () -> T): T = tab.mutex.withLock {
        withContext(current.asContextElement(tab)) {
            tab.job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            try { block() } finally { tab.job = null }
        }
    }
    suspend fun close(tab: Tab) {
        tab.clock.cancel()
        tab.job?.cancel()
        tab.mutex.withLock {
            synchronized(this) {
                tabs.remove(tab.id)
                if (active[tab.owner] == tab.id) {
                    active.remove(tab.owner)
                    list(tab.owner).lastOrNull()?.let { active[tab.owner] = it.id }
                }
            }
            withContext(Dispatchers.Main) {
                if (tab.host != null) tab.host.stop() else tab.view.stopLoading()
            }
        }
    }
}
