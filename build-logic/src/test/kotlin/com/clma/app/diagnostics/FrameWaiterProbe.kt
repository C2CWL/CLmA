package com.clma.app.diagnostics

@Suppress("UNUSED_PARAMETER")
object FrameWaiterProbe {
    @JvmField var hits = 0
    @JvmField var siteHits = 0
    @JvmField var callback: Any? = null
    @JvmStatic fun onAwait(value: Any?) { hits++; callback = value }
    @JvmStatic fun onRequestSite(value: Any?, site: String, category: String) { siteHits++; callback = value }
}
