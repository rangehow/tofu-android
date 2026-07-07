package com.tofu.client.ui

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tofu.client.data.Profile
import com.tofu.client.session.LoginResult
import com.tofu.client.session.ReauthWebViewClient
import com.tofu.client.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hosts the Tofu SPA in a WebView for one active [profile]. The
 * [ReauthWebViewClient] detects a session-expiry redirect / 401 and drives a
 * silent re-auth via [SessionManager.login], reloading on success and clearing
 * the in-flight latch on the observed outcome (Gap-2).
 *
 * System-back returns to the profile list (via [onBack]) when the WebView can't
 * navigate back any further.
 */
@Composable
fun WebScreen(
    profile: Profile,
    session: SessionManager,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    // Hold the WebView across recompositions. A plain local `var` would be
    // re-initialized to null on every recomposition and the BackHandler would
    // re-register capturing that fresh null — losing the reference (a Compose
    // bug that compiles clean and only fails at runtime). The single-element
    // array is a stable mutable box the factory writes once and the BackHandler
    // reads live, without triggering recomposition (unlike mutableStateOf).
    val webRef = remember { arrayOfNulls<WebView>(1) }

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
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true        // Tofu uses localStorage/IndexedDB
                    settings.databaseEnabled = true
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true) // gateway host != Tofu host

                    val client = ReauthWebViewClient(profile) { view ->
                        // Off-UI re-auth; reload on success, always settle the latch.
                        scope.launch(Dispatchers.Main) {
                            val result = session.login(profile)
                            if (result is LoginResult.Success) view.reload()
                            (view.webViewClient as? ReauthWebViewClient)?.reauthSettled()
                        }
                    }
                    webViewClient = client
                    loadUrl(profile.baseUrl)
                }
            },
        )
    }
}
