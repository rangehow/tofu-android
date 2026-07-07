package com.tofu.client.session

import okhttp3.Cookie

/**
 * Seam over the WebView cookie jar so session logic is unit-testable without an
 * Android runtime. Production impl is [CookieBridge]; tests supply a fake.
 */
interface CookieSink {
    /** Inject [cookies] for [origin] (scheme://host), persisting them. */
    fun inject(origin: String, cookies: List<Cookie>)

    /** Hard-invalidate every cookie pinned to [host] (Domain-pinned re-provision path). */
    fun purgeHost(host: String)
}
