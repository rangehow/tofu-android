package com.tofu.client.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Because the app runs on a phone (no easy adb/logcat), it carries an ON-SCREEN
 * diagnostic log: JS console output, main-frame load errors, and renderer death
 * are appended to an in-memory ring buffer and shown in a toggleable overlay
 * (the "Logs" chip, top-right). The overlay draws ABOVE the WebView so it is
 * reachable even when the page renders blank — turning a silent blank screen
 * into a readable error without a cable. Everything is ALSO mirrored to logcat.
 *
 * [ReauthWebViewClient] handles session-expiry re-auth and renderer-death
 * recovery (returns to the profile list instead of a dead blank surface).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    profile: Profile,
    session: SessionManager,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val webRef = remember { arrayOfNulls<WebView>(1) }

    // On-screen diagnostic ring buffer (most-recent-last, capped).
    val logs = remember { mutableStateListOf<String>() }
    var showLogs by remember { mutableStateOf(false) }
    val addLog: (String) -> Unit = remember {
        { line ->
            // WebView callbacks fire on the UI thread, so mutating the snapshot
            // list here is safe. Cap to the last 300 lines to bound memory.
            logs.add(line)
            if (logs.size > 300) logs.removeAt(0)
        }
    }

    BackHandler {
        val wv = webRef[0]
        when {
            showLogs -> showLogs = false
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webRef[0] = this
                    WebView.setWebContentsDebuggingEnabled(true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true        // Tofu uses localStorage/IndexedDB
                    settings.databaseEnabled = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true) // gateway host != Tofu host

                    // JS console → on-screen log + logcat.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            val line = "[${m.messageLevel()}] ${m.message()} " +
                                "(${m.sourceId()}:${m.lineNumber()})"
                            Log.i("TofuWebConsole", line)
                            addLog(line)
                            return true
                        }
                    }

                    val client = ReauthWebViewClient(
                        profile,
                        onReauth = { view ->
                            scope.launch(Dispatchers.Main) {
                                addLog("· re-auth triggered → logging in…")
                                val result = session.login(profile)
                                if (result is LoginResult.Success) view.reload()
                                (view.webViewClient as? ReauthWebViewClient)?.reauthSettled()
                            }
                        },
                        onRendererGone = { crashed ->
                            Log.e("TofuWebScreen", "renderer gone (crashed=$crashed)")
                            addLog("!! RENDERER GONE (crashed=$crashed) — the page " +
                                "crashed/was killed (likely out of memory). Returning to list.")
                            scope.launch(Dispatchers.Main) { onBack() }
                        },
                        onDiag = addLog,   // main-frame load errors → on-screen log
                    )
                    webViewClient = client
                    addLog("· loading ${profile.baseUrl}")
                    loadUrl(profile.baseUrl)
                }
            },
        )

        // "Logs" toggle chip — always on top, reachable even on a blank page.
        Text(
            text = if (showLogs) "✕ Logs" else "Logs (${logs.size})",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0xCC222222))
                .clickable { showLogs = !showLogs }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        if (showLogs) {
            LogOverlay(logs = logs, onClear = { logs.clear() })
        }
    }
}

/** Scrollable, monospace log panel covering the lower ~70% of the screen. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LogOverlay(
    logs: List<String>,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Auto-scroll to the newest line as logs arrive.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .background(Color(0xF2101014))
            .padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("WebView diagnostics — ${logs.size} lines",
                color = Color(0xFFBBBBBB), fontSize = 12.sp)
            Text("Clear", color = Color(0xFF7FB0FF), fontSize = 12.sp,
                modifier = Modifier.clickable { onClear() })
        }
        if (logs.isEmpty()) {
            Text("(no logs yet — reload the page to capture console output)",
                color = Color(0xFF888888), fontSize = 12.sp)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                val color = when {
                    line.startsWith("!!") || line.contains("ERROR", true) -> Color(0xFFFF8A80)
                    line.contains("WARNING", true) -> Color(0xFFFFD180)
                    line.startsWith("·") -> Color(0xFF80CBC4)
                    else -> Color(0xFFDDDDDD)
                }
                Text(line, color = color, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}
