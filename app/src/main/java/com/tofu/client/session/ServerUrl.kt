package com.tofu.client.session

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parsed view of a server base URL, e.g.
 * `https://<uuid>-vscode-zw05.mlp.sankuai.com/proxy/15000/`.
 *
 * The feasibility spike established two facts this class encodes:
 *  - the session cookie is `Domain`-pinned to the FULL host, so [host] is the
 *    identity that a cached jar is bound to;
 *  - the code-server login lives at the code-server ROOT (`…/login`), which for
 *    a `/proxy/PORT/` deploy is the origin root, NOT under the proxy subpath —
 *    the observed unauth redirect was `./../../login` climbing out of the
 *    subpath. [loginUrl] therefore posts to `<scheme>://<host>/login`.
 */
data class ServerUrl(
    val raw: String,
    val httpUrl: HttpUrl,
) {
    val host: String get() = httpUrl.host

    val scheme: String get() = httpUrl.scheme

    /** Origin root, e.g. `https://<host>`. */
    val origin: String get() = "$scheme://$host"

    /** code-server login endpoint (origin root + /login). */
    val loginUrl: String get() = "$origin/login"

    /**
     * MLP codelab instance id parsed from a `<uuid>-vscode-<idc>.…` host, or
     * null for non-MLP hosts. Used to recognise "same logical server, new URL".
     */
    val instanceUuid: String?
        get() = UUID_HOST.find(host)?.groupValues?.getOrNull(1)

    companion object {
        // <uuid>-vscode-<idc>.<domain>  →  capture the uuid
        private val UUID_HOST =
            Regex("""^([0-9a-fA-F-]{8,})-vscode-[^.]+\.""")

        /** Parse, returning null if the string is not a valid absolute http(s) URL. */
        fun parse(raw: String): ServerUrl? {
            val u = raw.trim().toHttpUrlOrNull() ?: return null
            return ServerUrl(raw = raw, httpUrl = u)
        }
    }
}
