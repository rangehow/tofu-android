package com.tofu.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tofu.client.data.Profile
import com.tofu.client.session.LoginResult
import com.tofu.client.session.ReauthWebViewClient
import com.tofu.client.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hosts the Tofu SPA in a WebView for one active [profile].
 *
 * [ReauthWebViewClient] handles session-expiry re-auth (silent re-login on a
 * redirect-to-login / 401) and renderer-death recovery (returns to the profile
 * list instead of stranding a dead blank WebView). JS console output and load
 * errors are mirrored to logcat (remote-debuggable via chrome://inspect) — the
 * on-screen diagnostic overlay used during the blank-screen investigation has
 * been removed now that the viewport-height root cause is fixed server-side.
 *
 * A small floating Refresh button provides the reload affordance a WebView
 * shell otherwise lacks (unlike Chrome, there is no address bar / menu). It
 * replaces the removed pull-to-refresh, which was unusable here: the SPA keeps
 * html/body overflow:hidden and scrolls an INNER div, so SwipeRefreshLayout's
 * scrollY-based "am I at the top?" check always read 0 and every pull-down
 * reloaded mid-chat. A button is always available regardless of the SPA's inner
 * scroll position and hijacks no touch gesture.
 *
 * Voice input: the SPA's mic button calls getUserMedia(). A WebView denies that
 * by default, so [WebChromeClient.onPermissionRequest] must explicitly grant the
 * web-origin audio capture — AND the app must hold the runtime RECORD_AUDIO
 * permission (dangerous, so requested on first use via micLauncher). The
 * manifest permission alone is insufficient for either gate.
 */
/**
 * JS→native bridge for the one-click diagnostics FAB. The web collector
 * (static/js/diag_collect.js → window.__tofuCollectDiagnostics) is async (it
 * runs a live GET probe), so evaluateJavascript's synchronous return can't
 * capture its result. Instead the FAB invokes the collector and pipes its
 * resolved JSON string back through [onResult] via this @JavascriptInterface.
 * Copying to the clipboard + the Toast happen on the native side so they work
 * even when the SPA is wedged on the loading skeleton (the failure we diagnose).
 */
private class DiagBridge(val onResult: (String) -> Unit) {
    @JavascriptInterface
    fun deliver(json: String) { onResult(json) }
}

/** Copy [text] to the system clipboard and show a short confirmation Toast. */
private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Tofu diagnostics", text))
    Toast.makeText(ctx, "Diagnostics copied — paste to the maintainer", Toast.LENGTH_LONG).show()
}

/**
 * Fire the web collector and route its async result to the native clipboard.
 * The collector returns a Promise<string>; we resolve it in-page and hand the
 * string to the injected `TofuDiag.deliver(...)` bridge. If the collector is
 * missing (old web build) or errors, we still copy a helpful marker so the
 * user's tap is never a silent no-op.
 */
private fun collectAndCopyDiagnostics(wv: WebView?) {
    if (wv == null) return
    val js = """
        (function(){
          try {
            if (typeof window.__tofuCollectDiagnostics !== 'function') {
              TofuDiag.deliver('{"error":"diagnostics collector missing — web build predates diag_collect.js; Refresh once on a newer server build"}');
              return;
            }
            Promise.resolve(window.__tofuCollectDiagnostics()).then(
              function(s){ TofuDiag.deliver(String(s)); },
              function(e){ TofuDiag.deliver('{"error":"collector rejected: '+(e&&e.message||e)+'"}'); }
            );
          } catch (e) {
            TofuDiag.deliver('{"error":"collector threw: '+(e&&e.message||e)+'"}');
          }
        })();
    """.trimIndent()
    wv.evaluateJavascript(js, null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    profile: Profile,
    session: SessionManager,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val webRef = remember { arrayOfNulls<WebView>(1) }

    // A getUserMedia() request that arrived before the runtime RECORD_AUDIO
    // permission was granted, parked here until micLauncher returns a result.
    val pendingMicRequest = remember { arrayOfNulls<PermissionRequest>(1) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val req = pendingMicRequest[0]
        pendingMicRequest[0] = null
        if (req != null) {
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else req.deny()
        }
    }

    BackHandler {
        val wv = webRef[0]
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webRef[0] = this
                    // Remote-debuggable via chrome://inspect on a connected
                    // desktop — safe to leave on for a self-hosted tool.
                    WebView.setWebContentsDebuggingEnabled(true)
                    // JS→native bridge for the diagnostics FAB. The collected
                    // JSON is copied to the clipboard natively so it works even
                    // when the SPA is wedged. Exposed as window.TofuDiag.
                    addJavascriptInterface(
                        DiagBridge { json ->
                            scope.launch(Dispatchers.Main) { copyToClipboard(ctx, json) }
                        },
                        "TofuDiag",
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true        // Tofu uses localStorage/IndexedDB
                    settings.databaseEnabled = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    // Honor the SPA's <meta viewport width=device-width> exactly
                    // like Chrome. Without useWideViewPort the WebView ignores
                    // the meta and lays out at the raw control width, so the
                    // computed innerWidth lands on the wrong side of the SPA's
                    // 768/1024 responsive breakpoints (core.js TOFU_BP) and the
                    // tablet renders a different layout than Chrome on the same
                    // device.
                    //
                    // NOTE: loadWithOverviewMode was tried alongside this in
                    // v0.1.3 but is deliberately NOT set — it forces a
                    // zoom-to-fit initial layout that can collapse the page to
                    // ~0 height (the "flash-then-blank" / black-line regression).
                    // useWideViewPort alone delivers the Chrome-parity width.
                    settings.useWideViewPort = true
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true) // gateway host != Tofu host

                    // Mirror JS console output to logcat (tag TofuWebConsole)
                    // and bridge the SPA's mic (getUserMedia) request to the
                    // app's runtime RECORD_AUDIO permission.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.i(
                                "TofuWebConsole",
                                "[${m.messageLevel()}] ${m.message()} " +
                                    "(${m.sourceId()}:${m.lineNumber()})",
                            )
                            return true
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            val wantsAudio = request.resources.any {
                                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            }
                            // Only the mic is bridged; deny anything else
                            // (camera, protected media) the shell doesn't need.
                            if (!wantsAudio) {
                                request.deny()
                                return
                            }
                            val held = ctx.checkSelfPermission(
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (held) {
                                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            } else {
                                // Park the request and prompt; micLauncher's
                                // callback grants or denies once the user decides.
                                pendingMicRequest[0] = request
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }

                    val client = ReauthWebViewClient(
                        profile,
                        onReauth = { view ->
                            scope.launch(Dispatchers.Main) {
                                val result = session.login(profile)
                                if (result is LoginResult.Success) view.reload()
                                (view.webViewClient as? ReauthWebViewClient)?.reauthSettled()
                            }
                        },
                        onRendererGone = {
                            // Renderer died (crash / OOM) → the page is a dead
                            // blank surface; return to the profile list.
                            scope.launch(Dispatchers.Main) { onBack() }
                        },
                        onPageDone = { view, _ ->
                            // Viewport-parity probe: log the WebView's computed
                            // layout width + DPR so breakpoint agreement with
                            // Chrome (SPA TOFU_BP 768/1024, core.js) can be
                            // VERIFIED from logcat on a real device rather than
                            // assumed from useWideViewPort alone. Tag: TofuViewport.
                            view.evaluateJavascript(
                                "(function(){try{return JSON.stringify({" +
                                    "innerWidth:window.innerWidth," +
                                    "dpr:window.devicePixelRatio," +
                                    "screenW:window.screen&&window.screen.width," +
                                    "band:(window.innerWidth<=768?'mobile':" +
                                    "(window.innerWidth<=1024?'tablet':'desktop'))" +
                                    "});}catch(e){return 'probe-error:'+e;}})()",
                            ) { r -> Log.i("TofuViewport", "viewport=$r") }
                        },
                    )
                    webViewClient = client
                    loadUrl(profile.baseUrl)
                }
            },
        )

        // Top-end affordance stack the WebView shell otherwise lacks (no
        // address bar / menu). Status-bar inset + low alpha keep it clear of
        // the SPA and unobtrusive over the chat.
        //   • Refresh — reload the page (replaces the unusable pull-to-refresh).
        //   • Copy diagnostics — collect client state + a live GET probe and
        //     copy the JSON to the native clipboard so the user can paste it to
        //     the maintainer in one tap. Native clipboard write means it works
        //     even when the SPA is stuck on the "Fetching messages…" skeleton.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { webRef[0]?.reload() },
                modifier = Modifier.alpha(0.6f),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
            SmallFloatingActionButton(
                onClick = { collectAndCopyDiagnostics(webRef[0]) },
                modifier = Modifier.alpha(0.6f),
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = "Copy diagnostics")
            }
        }

    }
}
