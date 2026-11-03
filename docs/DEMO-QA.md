# DEMO-QA-1: rebuild and retest of the public demo (2026-10-30)

Tip tested: `1e1cb0a5` ("feature-agenthq"-mvp, after CORE-SPLIT-A, DAEMON-PLUGINS-3, ORCH-1, STALL-5, CLI-ENV-1, CONFIG-FIELDS-1..4).
Docker was not used (Windows host, Linux image needs the engine and a 20 min image build); the local equivalent below runs
the exact `dockerContext` output, which is what the Dockerfile copies into the image.

## Steps used

1. Build the docker context with the demo plugin set compiled into the wasm bundle:
   `gradlew.bat :superiorSoftLibrary:infantry:dockerContext -Pdemo=true --no-parallel -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=2g"`
   (7m32s, BUILD SUCCESSFUL). Output `superiorSoftLibrary/infantry/build/docker/infantry/` holds `lib/`, `web/` (wasm), `policies/demo.json`,
   `modules/official/{Demo_Lock,Infantry_Board,Infantry_Processes,Infantry_Usage}`.
2. Temp state dir, a demo git repo with one commit, two token files (owner, visitor), then the daemon as the entrypoint starts it:
   `java -Xmx512m -cp "<ctx>\lib\*" com.superiorsoftllc.infantry.daemon.DaemonMainKt` with
   `INFANTRY_HOST=127.0.0.1 INFANTRY_PORT=8812 INFANTRY_STATE_DIR=INFANTRY_HOME=<tmp>/state INFANTRY_TOKEN_FILE INFANTRY_DEMO_PUBLIC_TOKEN_FILE
   INFANTRY_WEB_DIR=<ctx>/web INFANTRY_POLICY_DIRS=<ctx>/policies INFANTRY_BIN=<ctx>/bin INFANTRY_DEMO_LOCK=1 INFANTRY_POLICY=demo INFANTRY_DEMO_DIR=<tmp>/work/demo`
   plus the rest of `docker/demo-seed.env` (seats, notice, turns, `MAX_SESSIONS=2`, model allowlist).
   **Every inherited `INFANTRY_*` variable must be removed first**: an agent shell carries the owner daemon's `INFANTRY_TOKEN` / `INFANTRY_URL`.
3. Browser: Playwright against `http://127.0.0.1:8812/` (canvas UI: clicks by coordinates, screenshots in the scratch dir).
4. Lock: `DEMO_URL=http://127.0.0.1:8812 DEMO_TOKEN_FILE=<state>/access-token node docker/demo-lock-attack.mjs --scenarios`.
5. Isolation: netstat for every PID in the daemon's process tree against 8797/8798.

## Results

| Check | Result | Evidence |
|---|---|---|
| Health, seeding | PASS | `/health` ok, `"sessions":2`; backend + pm seated and warmed (pm answered "ready") |
| Page loads, wasm client connects | PASS | title "Infantry AI", "connected ws://127.0.0.1:8812/ws", Demo project selected |
| Demo banner | PASS | red strip with the `INFANTRY_DEMO_NOTICE` text above the body |
| Console errors | **FAIL (cosmetic)** | 2 x 403 on every load: `POST /node-config/seed`, `GET /update/status` (refused by the lock, UI keeps working) |
| Write actions dimmed | PASS | New agent, Switch, project add/edit, footer buttons all "Disabled in the demo"; Board opens read-only (New ticket / Start next dimmed) |
| Lock vs attacker (all frames + HTTP routes, owner token) | PASS after script fix | `RESULT: PASS (only the allow-list got through)`; spawn, project/settings writes, paid model, harness install, update apply, other-project reads all `demo: '<frame>' is disabled in this demo` |
| Secrets | PASS | owner credential + `secret_set` / `secret_status` / `run_start`: `demo: '...' is disabled in this demo`; without the owner credential: FrameGate refusal (see follow-ups) |
| Allowed reads | PASS | demo project git/epics/memory answered; Changes, Usage, Board render; another project / no project refused |
| Visitor token route | PASS | `GET /demo/visitor-token` 200 without a token; `/node-config/seed` without token 401 |
| Daemon-plugin tabs | PASS | Usage (Infantry_Usage) and Board (Infantry_Board) render from the bundled modules |
| `plugin_tools` listing | **NOT REACHABLE** | refused for owner and visitor token alike: `demo: 'plugin_tools' is disabled in this demo` |
| Settings / Plugins config cards, Acme_FieldKeys | **NOT VERIFIED** | the settings gear is dimmed under the demo policy, so no config view opens; Acme_* are not bundled in the demo image |
| Nothing reaches 8797/8798 or the owner state | PASS (host-limited) | no process of the daemon tree (daemon + 2 opencode serves + their children) has a socket on 8797/8798; state dir, home and demo dir are the temp ones |

## Defect fixed (one commit)

`docker/demo-lock-attack.mjs` (the lock's attacker test) was broken by CORE-SPLIT-A: it read `protocol/Protocol.kt` from the old path
(ENOENT) and tried to read the `daemon/` sub-directories as files (EISDIR), and it reported `secret_set` as a leak although FrameGate
refuses it first. Now it finds `Protocol.kt` in `infantryProtocol`, reads `.kt` files only, and counts the owner-credential refusal as denied.

## Follow-ups (not fixed here, not small)

1. The two 403 console errors: the web client should skip `POST /node-config/seed` (a no-op for old clients) and `GET /update/status`
   when the policy is read-only, rather than the lock opening them (`seed` returns node-config rows, which `GET /node-config` keeps refused).
2. Decide whether the demo should list plugin tools (`plugin_tools`): today no token can, so the tools the task names (usage, processes,
   ticket_files) are invisible to a visitor; they would need a read action in `demo.json` `allow`.
3. A visitor token that is not the owner gets `secret_set refused: it needs the owner credential (X-Infantry-Owner)...` instead of the neutral
   `demo:` text, and the text names the owner header. Harmless (refused), but not neutral; run the demo policy before the owner gate.
4. Settings/config cards cannot be shown in the locked demo (gear dimmed): if the demo should show them, `settings.read` needs a view that is not behind the dimmed gear.

## Not run

Docker image build and `docker/smoke.sh`, the droplet compose overlay, Landlock sandbox (Linux only), the per-IP turn limit
(10 real model turns), real visitor prompts through the page, the `-Pdemo=true` Android build, a second browser (Chromium only).
On this Windows host the seated agents ran with the operator's own opencode config and without Landlock, so the sandbox half of
"nothing reaches the owner's files" is unverified.

## DEMO-FIX-1 results (follow-ups 1 to 3)

| # | Fix | Proof |
|---|---|---|
| 1 | Under a `restricted` daemon policy the window makes no `POST /node-config/seed` and no `GET /update/status` (`NodeConfigState.daemonRowsClosed`, `ServerUpdateState.available`, set from `connection.policy.restricted` in `App.kt`). Nothing is opened in the lock. | `RestrictedPolicyCallsTest` (3 tests: no call when closed, still seeds / asks when not). Browser console not re-run. |
| 2 | `plugin_tools` is classed as a read: new catalog action `plugin.tools.list` (READ), added to `demo.json` `allow`. `plugin_tool_call` stays gated by the called tool's own action. | `FrameGateDaemonTest`: the demo lists tools; a call to an unlisted tool is `demo: 'plugin_tool_call' is disabled in this demo`. |
| 3 | The policy refusal now runs before the owner gate (`DaemonMain.kt`), so a lock's neutral text wins and never names `X-Infantry-Owner`. With no policy active the owner gate answers as before. | `FrameGateDaemonTest`: non-owner `secret_set` / `run_start` / `run_input` / `command_save` under the demo get `disabled in this demo`, no owner text. |

Attack script, same method as above (fresh `dockerContext -Pdemo=true`, port 8812, temp state dir, `demo-seed.env` values, inherited `INFANTRY_*` cleared):
`node docker/demo-lock-attack.mjs --scenarios` -> `RESULT: PASS (only the allow-list got through)`; `apply an update (HTTP)` 403, other-project reads DENIED, git path escapes REFUSED.
Daemon stopped by PID afterwards.

