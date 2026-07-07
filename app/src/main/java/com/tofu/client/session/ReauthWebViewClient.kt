package com.tofu.client.session

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tofu.client.data.Profile

/**
 * Detects session expiry inside the WebView and silently re-establishes it.
 *
 * The spike showed an unauthenticated request 302s to `…/login` (relative) and
 * sub-resources 401. We treat either as the re-auth trigger: run the headless
 * login again from the stored credential, re-inject the cookie, and reload.
 *
 * [onReauth] is invoked off the UI thread by the host; the host is responsible
 * for calling [SessionManager.login] and then [WebView.reload] on success, and
 * for calling [reauthSettled] when that attempt finishes (success OR failure).
 *
 * Gap-2: the in-flight latch clears on the observed OUTCOME ([reauthSettled]),
 * NOT on a fixed timer. A slow or failed re-auth must not silently re-open the
 * trigger and resume a redirect storm — the same observable-outcome rule the
 * frontend boot-reconnect path follows.
 */
class ReauthWebViewClient(
    private val profile: Profile,
    private val onReauth: (WebView) -> Unit,
) : WebViewClient() {

    @Volatile private var reauthInFlight = false

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode == 401) {
            trigger(view, "401 on main frame")
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url.toString()
        if (request.isForMainFrame && looksLikeLogin(url)) {
            trigger(view, "redirect to login: $url")
            return true   // swallow the navigation; re-auth will reload
        }
        return false
    }

    private fun looksLikeLogin(url: String): Boolean =
        url.endsWith("/login") || url.contains("/login?") || url.contains("/login#")

    private fun trigger(view: WebView, reason: String) {
        if (reauthInFlight) return
        reauthInFlight = true
        Log.i(TAG, "re-auth trigger (${profile.alias}): $reason")
        // NOTE: we do NOT clear the latch here. The host clears it via
        // reauthSettled() once the login attempt resolves (success or failure),
        // so a slow/failed re-auth cannot re-open the trigger mid-flight.
        onReauth(view)
    }

    /**
     * Host signals that the re-auth attempt has finished (success OR failure).
     * Only then is the trigger re-armed. Called from the host after
     * [SessionManager.login] resolves and any reload is issued.
     */
    fun reauthSettled() {
        reauthInFlight = false
    }

    /** Test/inspection hook: whether a re-auth is currently latched in-flight. */
    fun isReauthInFlight(): Boolean = reauthInFlight

    private companion object {
        const val TAG = "ReauthWebViewClient"
    }
}
