package com.tofu.client.session

import com.tofu.client.data.AuthType
import com.tofu.client.data.Profile
import com.tofu.client.data.ProfileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the graceful-degradation contract for headless login: a server that
 * 302s WITHOUT the `code-server-session` cookie (bare Tofu, a different gate,
 * or a changed login form) must NOT hard-Error and strand the user on the
 * profile list. [SessionManager.login] should return [LoginResult.Success] and
 * let WebScreen load `baseUrl`, handing any real auth to the server's own login
 * page / [ReauthWebViewClient] inside the WebView.
 */
class SessionManagerLoginDegradeTest {

    // ── Fakes ─────────────────────────────────────────────────────────────
    private class FakeCookieSink : CookieSink {
        val injected = mutableListOf<String>()
        override fun inject(origin: String, cookies: List<Cookie>) { injected += origin }
        override fun purgeHost(host: String) {}
    }

    private class FakeSecrets(private val secret: String?) : SecretLookup {
        override fun secretFor(alias: String): String? = secret
    }

    private class FakeDao(private var current: Profile) : ProfileDao {
        val updates = mutableListOf<Profile>()
        override fun observeAll(): Flow<List<Profile>> = flowOf(listOf(current))
        override suspend fun getById(id: Long): Profile? = current
        override suspend fun getAllOnce(): List<Profile> = listOf(current)
        override suspend fun getByAlias(alias: String): Profile? = current
        override suspend fun insert(profile: Profile): Long = 1
        override suspend fun update(profile: Profile): Int { current = profile; updates += profile; return 1 }
        override suspend fun deleteById(id: Long) {}
    }

    /**
     * Canned-response interceptor: the GET (resolveLoginUrl form probe) returns
     * a bodyless 200 so the login URL falls back to the origin root; the POST
     * /login returns a 302 whose only Set-Cookie is NOT the code-server session
     * cookie — the exact shape a non-code-server gate produces.
     */
    private class NonCodeServerGate : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val builder = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
            return if (req.method == "POST") {
                builder.code(302).message("Found")
                    // Some other cookie, deliberately not `code-server-session`.
                    .header("Set-Cookie", "gateway_hint=abc; Path=/; SameSite=Lax")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            } else {
                builder.code(200).message("OK")
                    .body("<html></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        }
    }

    private fun profile() = Profile(
        id = 1,
        alias = "bare tofu",
        baseUrl = "https://tofu.example.com/",
        authType = AuthType.CODE_SERVER_PASSWORD,
        cookieHost = null,
    )

    @Test
    fun redirect_without_session_cookie_degrades_to_success() = runTest {
        val cookies = FakeCookieSink()
        val dao = FakeDao(profile())
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(NonCodeServerGate())
            .build()

        val mgr = SessionManager(
            dao = dao,
            secrets = FakeSecrets("pw"),
            cookies = cookies,
            http = http,
        )

        val result = mgr.login(profile())

        // NEUTER CHECK: restore the old hard-fail
        // (`return@withContext LoginResult.Error("302 without session cookie")`)
        // in SessionManager.login and this assertion fails — the user would be
        // stranded on a 302-without-cookie Error instead of loading the WebView.
        assertTrue("expected graceful Success, got $result", result is LoginResult.Success)

        // We must NOT have injected any cookie (there was no session cookie to
        // replay) nor stamped cookieHost (no cached jar to bind).
        assertTrue("must not inject a cookie: ${cookies.injected}", cookies.injected.isEmpty())
        assertTrue("must not stamp cookieHost: ${dao.updates}", dao.updates.isEmpty())
    }

    @Test
    fun successful_code_server_login_still_injects_cookie() = runTest {
        // Sanity anti-neuter: the degrade path must not swallow the REAL
        // code-server login — a 302 with `code-server-session` still injects.
        val cookies = FakeCookieSink()
        val dao = FakeDao(profile())
        val realGate = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val req = chain.request()
                val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                return if (req.method == "POST") {
                    b.code(302).message("Found")
                        .header("Set-Cookie",
                            "code-server-session=tok; Path=/; SameSite=Lax")
                        .body("".toResponseBody("text/html".toMediaType())).build()
                } else {
                    b.code(200).message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType())).build()
                }
            }
        }
        val http = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(realGate).build()

        val mgr = SessionManager(dao, FakeSecrets("pw"), cookies, http)

        val result = mgr.login(profile())

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        assertEquals(listOf("https://tofu.example.com"), cookies.injected)
    }
}
