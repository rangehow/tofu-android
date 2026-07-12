package com.tofu.client.session

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Pure (Android-free) derivation of the supervisor's base URL from a profile's
 * Tofu server URL, and endpoint building. Unit-testable without a device.
 *
 * The supervisor is proxied by the SAME code-server as Tofu, one port up
 * (Tofu 15000 → supervisor 15001). So a server URL like
 * `https://<host>/proxy/15000/` maps to `https://<host>/proxy/15001` and the
 * control endpoints hang off that (`…/proxy/15001/status`, `/start`, `/stop`).
 *
 * When the Tofu port cannot be found in the path (a non-proxy deployment), we
 * fall back to the origin root + the default supervisor port path
 * `/proxy/<TOFU_SUPERVISOR_PORT>` so the same shape still works behind a proxy.
 */
data class SupervisorUrl(
    /** e.g. `https://<host>/proxy/15001` (no trailing slash). */
    val base: String,
    /** origin root `https://<host>` — used to look up the session cookie. */
    val origin: String,
) {
    companion object {
        const val TOFU_PORT = "15000"
        const val SUPERVISOR_PORT = "15001"

        const val STATUS = "status"
        const val START = "start"
        const val STOP = "stop"

        /**
         * Derive the supervisor base from a Tofu server URL, or null if the URL
         * is not a valid absolute http(s) URL.
         */
        fun fromServerUrl(serverUrl: String): SupervisorUrl? {
            val u = serverUrl.trim().toHttpUrlOrNull() ?: return null
            val origin = "${u.scheme}://${u.host}" +
                if (u.port != HttpUrlDefaultPort(u.scheme)) ":${u.port}" else ""
            // The supervisor is proxied one port up from Tofu off the origin
            // root: `…/proxy/15000/` (or any deployment) → `…/proxy/15001`.
            return SupervisorUrl(base = "$origin/proxy/$SUPERVISOR_PORT", origin = origin)
        }

        /**
         * Build a full endpoint URL. For GET /status the projectPath is passed
         * as a query param; for POST it is sent in the body (pass null here).
         */
        fun endpoint(su: SupervisorUrl, name: String, projectPathForQuery: String?): String {
            val root = "${su.base}/$name"
            return if (projectPathForQuery != null) {
                val enc = URLEncoder.encode(projectPathForQuery, "UTF-8")
                "$root?projectPath=$enc"
            } else {
                root
            }
        }

        private fun HttpUrlDefaultPort(scheme: String): Int =
            if (scheme == "https") 443 else 80
    }
}
