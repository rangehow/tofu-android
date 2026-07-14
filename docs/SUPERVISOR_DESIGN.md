# Supervisor — remote start/stop of the Tofu project from the App

> **Status:** DESIGN (awaiting sign-off). No code written yet.
> **Goal (owner):** the App configures a *project path* and can **start AND stop**
> the Tofu server (`python server.py` / `bash stop.sh`) on the host, in addition
> to the existing "open" (WebView) function.

---

## 1. Why a separate supervisor (recap of the ratified decision)

The App is a WebView shell talking HTTP to a Tofu server. It has **no shell
access** to the host, and `server.py` / `stop.sh` are host shell commands.

The killer constraint: **start cannot be served by Tofu itself** — a stopped
server can't answer the "start me" request. So control must live in a process
whose lifetime is *independent* of `server.py`. That is the supervisor: a tiny,
always-on daemon whose only job is to spawn/kill the Tofu server for a
configured project path.

Ratified answers (owner, this thread):
1. Tofu is always reached via code-server `/proxy/15000/`. **The supervisor sits
   behind the same code-server and reuses the same `--auth password` gate.**
2. **Both start and stop** — stop-only is rejected.
3. Project path is stored per-server on the App's `Profile` (Room, new column),
   sibling to `baseUrl`.

---

## 2. Grounded facts (verified in source, not assumed)

| Fact | Source | Consequence for design |
|---|---|---|
| Lock file `data/.server.lock`, one line `<pid>@<host>` | `server.py:1680`, `stop.sh:39-40` | supervisor reads it to report `running` state + owning host |
| `stop.sh` is host-scoped, SIGTERM→(12s)→SIGKILL, refuses wrong-host / PID-reuse | `stop.sh` whole | **reuse `stop.sh` verbatim** for stop; do not reimplement kill logic |
| server.py graceful_timeout = 10s | `server.py:1927` | stop poll window ≥ 12s (stop.sh already uses this) |
| Launch = `python server.py`, host/port from `--host/--port` env `BIND_HOST`/`PORT` (default 127.0.0.1:15000) | `server.py:1660-1661` | supervisor start = `python server.py` in the project cwd, inherit env |
| server.py auto-reclaims a stale instance lock on startup | `server.py:1682` | double-start is safe-ish, but supervisor still guards it (idempotent) |
| App `Profile` is Room v1, columns via `@ColumnInfo`; DAO has `update()` | `Profile.kt`, `ProfileDatabase.kt` | add `project_path` column → Room schema **v1→v2 + migration** |

---

## 3. Component: `supervisor.py` (new, lives in the Tofu repo)

A standalone Quart (or stdlib `http.server`) micro-service. **Not** part of
`server.py` — separate process, separate port, never exits with the Tofu server.

### 3.1 Placement behind code-server
- Runs on a **fixed port** on the host, e.g. `15001`.
- Exposed the same way Tofu is: code-server proxies `…/proxy/15001/`.
- So the App reaches it at `<baseUrl-host>/proxy/15001/` with the **same
  `code-server-session` cookie** already established by the profile login. No
  new auth mechanism — the code-server password gate is the single gate.

### 3.2 Endpoints (all under the proxied prefix)

| Method | Path | Body | Returns |
|---|---|---|---|
| Method | Path | Auth | Returns |
|---|---|---|---|
| `GET`  | `/health`      | none | `{ok:true, version}` (liveness) |
| `GET`  | `/status`      | **none (read-only)** | `{running, pid, host, projectPath, …}` |
| `POST` | `/start`       | **Bearer token** | `{ok, running, pid, alreadyRunning}` |
| `POST` | `/stop`        | **Bearer token** | `{ok, wasRunning, exitCode}` |

**Least-privilege:** only the STATE-CHANGING endpoints require the token.
`/status` reports running/pid and mutates nothing, so it is gated by the
code-server cookie alone (same door as the proxied Tofu UI) — not the token.
All three still enforce the `projectPath` allow-list (403 on a non-listed path).

- **Start** = `subprocess.Popen(['python','server.py'], cwd=projectPath,
  env=inherited, start_new_session=True)`, stdout/stderr → a log file under the
  project's `data/`. Detached (`start_new_session`) so it survives the request.
- **Stop** = `subprocess.run(['bash','stop.sh'], cwd=projectPath)`, surface the
  exit code (0 clean / 1 refused / 2 SIGKILL — the codes stop.sh already
  defines).
- **Status** = read `projectPath/data/.server.lock`; `kill -0` the pid to
  confirm liveness (same defensive check stop.sh does).

### 3.3 Idempotency (owner requirement)
- **start when already running** → detect live lock → return
  `{ok:true, alreadyRunning:true}`, do NOT spawn a second process.
- **stop when not running** → stop.sh already exits 0 on "nothing running" →
  return `{ok:true, wasRunning:false}`.

### 3.4 projectPath whitelist (owner requirement — security)
A remote "run `python server.py` in an arbitrary cwd" endpoint is RCE-adjacent.
Guard it:
- The supervisor reads an **allow-list** from its own config
  (`TOFU_SUPERVISOR_PROJECTS` env = `:`-separated abs paths, or a config file).
- `/start` / `/stop` **reject** any `projectPath` not exactly in the allow-list
  (403). No globbing, no path-prefix matching — exact match after
  `os.path.realpath` to defeat `..` traversal.
- Each allowed path must contain a `server.py` and a `stop.sh` (sanity check).
- **Never** derive the runnable path from the request alone.

### 3.5 Auth
- Behind code-server: the proxy only forwards requests carrying a valid
  `code-server-session` cookie, so the App's existing session is sufficient.
- **Defence in depth (SHIPPED):** supervisor checks a shared token
  (`TOFU_SUPERVISOR_TOKEN`) in an `Authorization: Bearer` header on the
  STATE-CHANGING endpoints (`/start`, `/stop`) only — least-privilege leaves the
  read-only `/status` on the cookie gate. Fail-closed: with no token configured,
  `/start` / `/stop` return 503 while `/status` stays reachable. App stores the token
  alongside the profile secret. (Open question Q3 below.)

### 3.6 Logging (CLAUDE.md §2 discipline)
Standard `get_logger(__name__)`; every start/stop is an `audit_log('supervisor_start'/'supervisor_stop', project=…, pid=…, exit=…)`. Zero silent catches.

---

## 4. App changes (Kotlin/Compose)

### 4.1 `Profile` — new column (Room v1→v2)
```kotlin
@ColumnInfo(name = "project_path") val projectPath: String? = null
```
- `ProfileDatabase` version 1→2 + a `Migration(1,2)`:
  `ALTER TABLE profiles ADD COLUMN project_path TEXT` (nullable, no default →
  existing rows get NULL, App shows controls only when set). Matches the
  existing nullable-column style (`instance_uuid`, `cookie_host`).
- **Do NOT** use `fallbackToDestructiveMigration` — it would wipe saved
  servers/secrets. Explicit migration only.

### 4.2 `AddEditScreen` — new field
- One text field "Project path (for start/stop)", optional, sibling to Base URL.
- Persist via existing `ProfileDao.update()`.

### 4.3 `WebScreen` (or a small control surface) — Start/Stop controls
- Only shown when `profile.projectPath != null`.
- Two actions calling the supervisor:
  - a tiny status poll (`GET …/proxy/15001/status`) to show running/stopped,
  - Start / Stop buttons → POST, then re-poll.
- Reuse the existing session cookie (the `SessionManager` / `CookieBridge`
  path) — same host, so the code-server cookie already applies.
- After a successful **start**, offer "reload" (the WebView can now load Tofu).

### 4.4 Supervisor base URL derivation
From the profile's `baseUrl` (`…/proxy/15000/…`) swap the proxied port segment
`15000`→`15001`. Keep it a single helper (mirror of `ServerUrl` parsing) so the
rule lives in one place.

---

## 5. Ratified decisions (owner sign-off — build to these)

- **Q1 — port `15001`. APPROVED.**
- **Q2 — launch via a systemd USER UNIT with `Restart=always`.** It is the only
  option that makes the supervisor genuinely always-on and crash-surviving; a
  code-server task dies with the IDE session. **Fallback:** `supervisor.sh` +
  `nohup` only where systemd user-lingering is unavailable.
- **Q3 — YES, add the Bearer token IN ADDITION to the code-server cookie.**
  Mandatory, not optional: a remote process-spawner in front of
  `TOFU_REQUIRE_PG=1` production hosts must not rely on a single gate. Defence
  in depth. App stores the token in the secret store alongside the profile
  secret.
- **Q4 — NO `/restart`.** Keep the supervisor minimal; the App composes
  stop→poll→start.
- **Q5 — `/start` returns IMMEDIATELY; the App polls `/status`** until the port
  is listening. Never block the request thread on the multi-second bind.

---

## 6. Build / rollout order (once answers are in)

1. `supervisor.py` + allow-list config + tests (start/stop/status idempotency,
   whitelist rejection, stale-lock handling) — Tofu repo, `pytest -p no:napari`.
2. `supervisor.sh` launcher + README section (how to run it behind code-server).
3. App: Room v1→v2 migration + migration test (Robolectric) — **this is the
   riskiest App change; test the migration preserves existing rows**.
4. App: `AddEditScreen` field + `ProfileForm` validation.
5. App: Start/Stop control surface + supervisor client (through `Api`-style
   single seam / `SessionManager`).
6. Ship as **v0.1.4** through the same CI gate → tag → APK-asset verification.

Each layer is independently landable; the App half is inert until `projectPath`
is set, so it ships safely even before the supervisor is deployed.
