package com.tofu.client.session

import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
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
    /**
     * Invoked when the WebView's RENDERER PROCESS dies (crash or low-memory
     * kill) — the classic "blank page after load" cause on a memory-constrained
     * device rendering a heavy page. The host decides recovery (e.g. drop back
     * to the profile list) instead of leaving a dead blank WebView on screen.
     */
    private val onRendererGone: ((crashed: Boolean) -> Unit)? = null,
    /** Optional sink for human-readable diagnostics shown in the on-screen log. */
    private val onDiag: ((String) -> Unit)? = null,
) : WebViewClient() {

    @Volatile private var reauthInFlight = false

    /**
     * After the page settles, probe the DECISIVE layout facts and report them to
     * the on-screen log: viewport width, pointer:coarse, the tablet-drawer media
     * query, and the computed size/visibility of the sidebar + main containers.
     * This distinguishes "content never rendered" from "rendered then hidden"
     * (e.g. the responsive drawer collapsing the sidebar) without needing
     * desktop DevTools. Re-probes at +1.5s to catch a post-paint hide.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        val js = """(function(){try{
          var q=function(sel,el){el=el||document.querySelector(sel);
            if(!el)return sel+'=<none>';
            var r=el.getBoundingClientRect();var st=getComputedStyle(el);
            return sel+' h='+Math.round(r.height)+' disp='+st.display+
              ' flex='+st.flexGrow+'/'+st.flexShrink+'/'+st.flexBasis+
              ' minH='+st.minHeight+' H='+st.height+' pos='+st.position+
              ' ovf='+st.overflowY;};
          // Walk the real ancestor chain of the chat container up to <html>,
          // so we see EXACTLY which element in the flex height-chain zeroes out.
          var chain=[];var el=document.querySelector('#chatContainer');
          for(var i=0;i<7 && el;i++){
            var tag=el.id?('#'+el.id):(el.tagName.toLowerCase()+
              (el.className&&typeof el.className=='string'?'.'+el.className.trim().split(/\s+/)[0]:''));
            chain.push(q(tag,el));el=el.parentElement;}
          return 'PROBE2 iw='+window.innerWidth+' ih='+window.innerHeight+
            ' coarse='+matchMedia('(pointer:coarse)').matches+
            ' drawerMQ='+matchMedia('(max-width:1024px) and (pointer:coarse)').matches+
            ' theme='+(document.documentElement.getAttribute('data-theme'))+
            ' || '+chain.join(' || ');
        }catch(e){return 'PROBE-ERR '+e;}})();"""
        view.evaluateJavascript(js) { result ->
            onDiag?.invoke("· " + (result ?: "").trim('"').replace("\\\"", "\""))
        }
        // Re-probe after 1.5s to catch a hide that happens shortly AFTER paint.
        view.postDelayed({
            view.evaluateJavascript(js) { result ->
                onDiag?.invoke("· (+1.5s) " + (result ?: "").trim('"').replace("\\\"", "\""))
            }
        }, 1500)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame) {
            onDiag?.invoke("!! HTTP ${errorResponse.statusCode} on main frame: ${request.url}")
            if (errorResponse.statusCode == 401) trigger(view, "401 on main frame")
        }
    }

    /**
     * The renderer process died. If [detail.didCrash] is false it was killed by
     * the OS (usually low memory) — common when a WebView renders a very large
     * page on a constrained device. Returning true tells the framework we
     * HANDLED it, so the host app is NOT killed; we then hand off to recovery.
     * Without this override, a renderer death leaves a permanently blank WebView.
     */
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            detail?.didCrash() ?: false else false
        Log.e(TAG, "RENDERER GONE (${profile.alias}) didCrash=$crashed — " +
            "likely OOM/crash rendering a heavy page; recovering")
        onDiag?.invoke("!! RENDERER GONE didCrash=$crashed (likely OOM)")
        onRendererGone?.invoke(crashed)
        return true
    }

    /** Log main-frame load failures (blank-page diagnostics). */
    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                error.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                error.description?.toString() else null
            Log.e(TAG, "main-frame load error (${profile.alias}) code=$code " +
                "desc=$desc url=${request.url}")
            onDiag?.invoke("!! main-frame load error code=$code desc=$desc url=${request.url}")
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
