# Tofu Android Client

A thin native Kotlin **WebView shell** for the Tofu self-hosted assistant. It does
**not** re-implement the SPA — the existing vanilla-JS frontend renders inside a
WebView (it already derives `BASE_PATH` from `location.pathname` and carries the
SSE-through-proxy recovery nets). The native layer owns only what a browser
can't: **credential/session management** and **multi-server profiles**.

## Why this exists

Connecting to a cloud-IDE-hosted Tofu (`https://<uuid>-vscode-<idc>.mlp.…/proxy/15000/`)
means re-typing a long code-server password and copy-pasting UUID-scoped URLs.
This app remembers servers and authenticates once per profile.

## Feasibility spike findings (verified end-to-end through the public gateway)

1. **Layer 1 (gateway) enforces no interactive SSO on this path.** An unauth
   `GET …/proxy/15000/` 302s to a **relative** `./../../login` — code-server's
   own login, not an SSO IdP redirect. Confirmed by replaying the full loop
   through the public host.
2. **Layer 2 (code-server `--auth password`) is the sole gate and is fully
   replayable**: `POST /login` (`password`, `base=.`) → `302` +
   `Set-Cookie: code-server-session=…; Domain=<uuid-host>; Path=/; SameSite=Lax`;
   replaying that cookie on `GET /proxy/15000/` → `200`.
3. **The session cookie has NO `Max-Age`/`Expires`** → it's a session cookie a
   plain WebView drops on cold start. We author the injected cookie, so we
   **upgrade it to persistent** (`CookieBridge`), and keep the stored credential
   as the ultimate fallback.
4. **The cookie is `Domain`-pinned to the full UUID host.** On a re-provision the
   URL host changes and any cached jar is bound to the dead host → it MUST be
   hard-invalidated. Baked into `SessionManager.updateUrlAndReauth`:
   **URL change ⇒ purge old host jar ⇒ re-login from stored credential.**

## Architecture

```
MainActivity (single Activity, Compose)     routes on ProfilesViewModel.screen:
  ├─ ProfileListScreen   list / switcher — tap to activate, edit/delete, add FAB
  ├─ AddEditScreen       add/edit form (validated by ProfileForm)
  └─ WebScreen           WebView hosting the Tofu SPA + ReauthWebViewClient wiring
ui/
  ProfilesViewModel.kt   reactive profile list + screen/status state; delegates
                         every mutation to SessionController (no session logic here)
  ProfileListScreen / AddEditScreen / WebScreen   thin Compose surfaces
data/
  Profile.kt          Room entity + DAO + DB
                      { alias (stable identity), instanceUuid?, baseUrl (editable),
                        authType, cookieHost }
session/
  SecretStore.kt      EncryptedSharedPreferences, secret keyed by ALIAS (not URL)
                      (impl of SecretVault: read+write; SecretLookup: read-only)
  ServerUrl.kt        host / origin / loginUrl / MLP-UUID parsing
  LoginForm.kt        pure <form action> resolver (Gap-1)
  ProfileForm.kt      pure add/edit validation (alias/URL/secret rules)
  CookieBridge.kt     OkHttp cookie → WebView jar; Max-Age upgrade + flush; purgeHost
  SessionManager.kt   headless login, URL-change purge+relogin, profile update path
  SessionController.kt orchestrates add/edit/delete/activate over DAO+vault+manager
                       (rename moves the alias-keyed secret; delete removes it;
                        host-change routes through updateUrlAndReauth)
  ReauthWebViewClient 302→/login or 401 ⇒ silent re-auth; latch clears on outcome
```

**Design invariants**
- Secret binds to `alias`, never to `baseUrl` → a re-provisioned sandbox reuses
  the credential after a one-tap URL edit.
- Cookie injection always `flush()`es (else a cold kill loses it).
- `SessionManager` uses `followRedirects(false)` — we need the 302 + Set-Cookie,
  not the redirect target.

## Distribution
Release APKs ship via **GitHub Releases** — NOT self-hosted from Tofu, NOT a
browser-extension bundle (an Android app has no host to embed in). The Tofu web
UI surfaces a download link in the Settings footer from `GET /api/health` →
`mobile_client_url`, which **defaults to a DIRECT APK deep link**:

```
https://github.com/rangehow/ToFu/releases/latest/download/tofu-android.apk
```

GitHub's `/releases/latest/download/<asset>` is a stable redirect that always
serves the newest release's asset and triggers a real download on tap — exactly
what a phone needs. `TOFU_MOBILE_CLIENT_URL` overrides it (e.g. to pin a
specific version's asset).

**Graceful-degradation tradeoff (deliberate):** before the first tagged release
this deep link **404s** — an honest "not published yet". We chose that over the
releases *page* (`/releases/latest`), which never 404s but on a phone dumps the
user into a list of wrong-platform desktop installers with no APK. Since the
user's need is an **on-device install**, a clean 404-until-published beats a
misleading wrong-platform page. The frontend still hides the link defensively if
the URL is blanked, so emptying `TOFU_MOBILE_CLIENT_URL` never yields a dead
button.

The URL's filename (`tofu-android.apk`) and the CI-published asset name are the
**same string**, kept in lockstep by `chatui/tests/test_mobile_client_apk_url.py`
(backend `MOBILE_CLIENT_APK_ASSET` ⇔ workflow publish list) so the deep link
can't silently rot into a 404.

## Building the APK & CI
`.github/workflows/build-apk.yml`:
- **every push/PR** → runs `./gradlew test` + `assembleDebug` and uploads the
  debug APK as a build artifact (so the build can't silently rot);
- **on a `v*` tag** → `assembleRelease`, **renames the output to
  `tofu-android.apk`** (Gradle emits `app-release[-unsigned].apk`, which would
  NOT match the deep link), and publishes exactly that asset
  (`fail_on_unmatched_files: true`, so a missing/misnamed APK fails the release
  loudly instead of silently shipping a 404 link).

### Release & signing
A release APK must be **signed** to install on a normal device. Before the first
real release: create a keystore, add it (base64) + passwords as repo secrets,
wire a `signingConfigs.release` block in `app/build.gradle.kts`, and reference it
from the `release` build type. The workflow's release step publishes whatever
`assembleRelease` produces; an **unsigned** APK is inspection-only and will be
rejected by Android's installer. This signing step + the on-device
cookie-persistence test are the parts that require a real Android SDK / device.

## Open items
- **Cellular layer-1** (needs a phone): confirm a phone on cellular lands on the
  password page (fast path) vs an SSO screen (`AuthType.INTERACTIVE_SSO` handles
  it — WebView completes SSO once, jar persisted).
- **URL stability** (needs a re-provision): confirm whether stop→start reuses the UUID.
- **Login retry/backoff (gap 3, deferred)**: `SessionManager.login` has no
  retry/backoff for a flaky-tunnel drop mid-login — a cellular blip returns
  `Error` with no auto-recovery. Lower priority than gaps 1/2/4; add a bounded
  backoff (mirroring the web boot-reconnect ladder) when the E2E path is exercised.
- **Next increment**: the profile-list / add-server / switcher Compose UI, and a
  signed release APK built on an Android-SDK machine (the one step that can't run here).

## Build
Standard Gradle/AGP 8.5.2 project (Gradle 8.9 wrapper committed), `minSdk 26`,
`targetSdk 34`, JDK 17. Open in Android Studio or `./gradlew :app:assembleDebug`.

## Running the unit tests
The canonical target is `./gradlew test` (needs the Android SDK). For a fast
proof without the SDK, `./test-local.sh` runs two tiers on a plain JDK 17 +
`kotlinc`:

- **Pure-JVM tier** (28 tests: `ServerUrl`, `LoginForm`, `CookieHeaders`,
  `ProfileForm`, `SessionManager` + `SessionController` via the `CookieSink` /
  `SecretVault` seams) — no Android runtime.
- **Robolectric tier** (3 tests: `CookieBridge` against a shadow `CookieManager`;
  `ReauthWebViewClient` latch) — runs headless on the JVM, no device/emulator.
  Needs the Robolectric jars + an instrumented `android-all` in `LIBS`.

The jars are fetched reproducibly by the committed `fetch-test-deps.sh` (pinned
versions, sankuai mirror → Maven Central fallback; it also extracts the
`classes.jar` from the androidx.test `.aar`s Robolectric needs). From a fresh
clone:

```
export JAVA_HOME=/path/to/jdk17            # e.g. Temurin 17
export KOTLINC=/path/to/kotlinc/bin/kotlinc  # kotlinc 1.9.24

./fetch-test-deps.sh /tmp/tofu-libs        # populate a LIBS dir from Maven
LIBS=/tmp/tofu-libs ./test-local.sh        # → 28 pure-JVM + 3 Robolectric green
```

(A JDK 17 + `kotlinc` on PATH are the only prerequisites the script does not
fetch. Verified end-to-end against a cold, empty `LIBS` dir.)

**Guarded invariants (each has a verified neuter check — the test fails if the
mechanism is removed):**
- `LoginForm.resolveAction` derives the login POST target from the served
  `<form action>` (resolved against the page URL), NOT the assumed origin-root —
  so a code-server behind a path prefix still authenticates (neuter: return
  origin-root → `LoginFormTest` subpath case fails). Gap-1.
- `CookieHeaders.toPersistentHeader` appends `Max-Age` to an expiry-less session
  cookie (neuter: drop the upgrade branch → `CookieHeadersTest` fails).
- `ReauthWebViewClient` clears its in-flight latch on the observed OUTCOME
  (`reauthSettled()`), not a timer (neuter: make `reauthSettled` a no-op →
  the Robolectric latch test fails). Gap-2.
- `CookieBridge.purgeHost` clears both cookies AND per-host web storage
  (`WebStorage.deleteOrigin`) for the dead host. Gap-4.
- `SessionManager.updateUrlAndReauth` calls `purgeHost(oldHost)` on a host change
  and not on a same-host edit (neuter: remove the purge call →
  `SessionManagerReauthTest` fails).
- `SessionController.editProfile` routes a URL host change through
  `updateUrlAndReauth` (neuter: bypass it → `SessionControllerTest` host-change
  purge case fails), and a rename MOVES the alias-keyed secret to the new alias
  (neuter: drop the move → the credential is orphaned and the rename test fails).
- `SessionController.deleteProfile` removes both the secret and the row (never
  orphans a credential).
