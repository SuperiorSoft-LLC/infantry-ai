# Infantry AI on a DigitalOcean droplet (Docker)

The headless Infantry AI daemon in one container, with the browser UI, `git`, Node 22 and the **opencode** and **pi**
CLIs installed inside it (claude only on request). Written for a **2 GB Ubuntu droplet** used as a public demo. Every
step can be copied as is; replace the values in `<angle brackets>`.

Files: `docker/Dockerfile`, `docker/entrypoint.sh`, `docker/docker-compose.yml` (this demo). The hardened variant
(secrets as files, read-only root, optional Caddy TLS) is `docker/compose.yaml`, see `docker/README.md`.

---

## 1. Build the image (on your PC, not on the droplet)

A 2 GB droplet cannot comfortably run Gradle and the WebAssembly build. Build on your PC (Docker Desktop running), then
copy the image over.

```bash
./gradlew :superiorSoftLibrary:infantry:dockerContext          # daemon + web UI + Dockerfile + entrypoint -> build/docker
docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/infantry/build/docker
docker save infantryai/infantry-ai:dev | gzip > infantry-ai.tar.gz   # ~420 MB compressed content
```

Build options (`--build-arg`): `OPENCODE_VERSION=1.19.4`, `PI_VERSION=1.4.0` pin the CLIs (default: latest at build
time); `INSTALL_CLAUDE=false` leaves Claude Code out (in by default; it needs a login).

## 2. Create the droplet

DigitalOcean > Create > Droplets: **Ubuntu 24.04 LTS**, Basic, Regular, **2 GB / 1 CPU**, your SSH key. Note its IP.

```bash
scp infantry-ai.tar.gz docker/docker-compose.yml root@<droplet-ip>:/root/
ssh root@<droplet-ip>
```

## 3. Install Docker (on the droplet)

```bash
curl -fsSL https://get.docker.com | sh
docker version                     # client AND server must print
```

## 4. Add 2 GB of swap (on the droplet)

The daemon and two agent sessions peak around 0.9 GB (section 9); swap keeps a spike from killing the box.

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl vm.swappiness=10 && echo 'vm.swappiness=10' >> /etc/sysctl.conf
free -h                            # Swap: 2.0Gi
```

## 5. Firewall: allow only SSH and the daemon port (or nothing, with Tailscale)

**Docker publishes ports around `ufw`**: a `ufw deny` does NOT block a port published by a container. Use the
DigitalOcean **Cloud Firewall** (it sits outside the droplet), or keep the port on loopback and use Tailscale.

Option A, public demo port: DigitalOcean > Networking > Firewalls > Create:
- Inbound: SSH TCP 22 (your IP if you can), Custom TCP `<port>` (e.g. 8787; "All IPv4/IPv6" for a public demo, or your IP).
- Outbound: leave the defaults (the CLIs need the internet to reach their models).
- Apply it to the droplet.

Option B, private (recommended when the demo is not public): install Tailscale and bind to its address only.

```bash
curl -fsSL https://tailscale.com/install.sh | sh && tailscale up
tailscale ip -4                    # e.g. 100.x.y.z -> use it as INFANTRY_BIND below; no Cloud Firewall rule needed
```

Plain HTTP carries the token in clear text. For a public demo on a domain, use the `tls` profile of
`docker/compose.yaml` (Caddy, automatic HTTPS) or Tailscale, not a bare port.

## 6. Start it

```bash
cd /root
docker load < infantry-ai.tar.gz
cat > .env <<'EOF'
INFANTRY_BIND=0.0.0.0               # Option B: the Tailscale IP instead
INFANTRY_HOST_PORT=8787
INFANTRY_WEB_HOSTS=<droplet-ip>     # or your domain / Tailscale name; the browser is refused on any other host name
EOF
docker compose -f docker-compose.yml --env-file .env up -d
docker compose -f docker-compose.yml ps        # STATUS: Up ... (healthy) after ~40 s
curl -fsS http://127.0.0.1:8787/health          # {"status":"ok",...,"database":"ok"}
```

Moving the demo to another domain, or the release files to another repository, is an env change in the same `.env`:
`INFANTRY_WEB_HOSTS` and `INFANTRY_DOMAIN` (the `tls` profile) name the host, and `INFANTRY_SITE_URL`,
`INFANTRY_DOCS_URL`, `INFANTRY_DOWNLOAD_URL`, `INFANTRY_UPDATE_REPO`, `INFANTRY_UPDATE_API` and `INFANTRY_DOCKER_IMAGE`
point the product's links and updates (empty = the built-in default; the list and defaults are in
[Settings](wiki/Settings.md#domains-and-urls) and `docker/README.md`). Then `docker compose ... up -d` again; the
image and the volumes stay as they are.

If the database stops answering, `/health` says so at once (HTTP 503, `"database":"stuck"`) and the daemon's health
watchdog halts it after 3 failed self-checks 10 s apart (exit 3), so `restart: unless-stopped` restarts it: Docker on its
own never restarts a container that is only *unhealthy*. Tune or turn it off with `INFANTRY_LIMIT_HEALTH_WATCHDOG_*`
(`FAILURES=0` = off) in `.env`; `docker inspect -f '{{.RestartCount}}'` counts the restarts.

Then the two settings that matter on 2 GB (stored in the volume, so once is enough):

```bash
docker compose -f docker-compose.yml exec infantry-ai infantry settings set session.keepAliveMinutes 0
docker compose -f docker-compose.yml exec infantry-ai infantry settings set processes.floorMb 256
```

- `session.keepAliveMinutes` (default 30): an opencode session keeps an `opencode serve` process of ~420 MB alive
  between turns. `0` runs each turn one-shot and frees it at once (measured: 800 MB -> 142 MB after two sessions); a
  small number (e.g. 5) keeps follow-up turns fast at the cost of that memory.
- `processes.floorMb` (default 4096): below this much free RAM the daemon warns "memory pressure" every 10 minutes. On a
  2 GB box the default always fires.

## 7. The access token

The first start generates it into the state volume; the logs show only its path:

```bash
docker compose -f docker-compose.yml logs infantry-ai | grep entrypoint
#   [entrypoint] generated a new access token at /data/access-token
docker compose -f docker-compose.yml exec infantry-ai cat /data/access-token
```

Open `http://<droplet-ip>:8787/` and paste the token; the browser keeps it. To choose your own token, write it into
`/data/access-token` (mode 600) and restart. To rotate it: delete the file and restart (a new one is generated; every
browser must be given the new one).

Model credentials never go in the image: put `OPENROUTER_API_KEY=...` (pi), `OPENCODE_API_KEY=...` etc. in `.env`, or
log in once inside the container (`docker compose exec infantry-ai opencode auth login`, `... pi`), which stores the
login in the `infantry-home` volume. opencode's free models (`opencode/*-free`, e.g. `opencode/deepseek-v4.1-flash-free`)
work with no credential at all.

## 8. Stop, update, back up

```bash
docker compose -f docker-compose.yml stop                 # stop (state kept)
docker compose -f docker-compose.yml up -d                # start again
docker compose -f docker-compose.yml down                 # remove the container; the volumes (state) stay
# update: build + save a new image on your PC (step 1), scp it, then
docker load < infantry-ai.tar.gz && docker compose -f docker-compose.yml up -d    # re-creates with the new image
# back up the state (database, token, settings):
docker run --rm -v infantry-demo_infantry-data:/data -v "$PWD":/backup busybox tar czf /backup/infantry-data.tgz -C /data .
```

`down -v` deletes the volumes: the database, the token and every project. Only use it to start over.

## 9. Measurements (real run, 2026-10-25)

Image built from this branch (base db250c9) with opencode 1.19.4, pi 1.4.0, Node 22.23.3, Temurin 21.0.12. Run with
`docker/docker-compose.yml` on Docker Desktop (WSL2 capped at 3 GB / 2 CPUs), `mem_limit` 1600m, `JAVA_OPTS=-Xms32m
-Xmx512m`. Sessions: opencode, model `opencode/deepseek-v4.1-flash-free` (free, no key), prompt "Reply with exactly the
word OK and nothing else." (answer: `OK`), started over the daemon's WebSocket protocol. "Container" = `docker stats`
(memory.current minus inactive file cache), peaks sampled every 0.5 s from the container's cgroup.

| State | Container memory | Main processes (RSS) |
|---|---|---|
| Idle, 45-60 s after start, no session | **115.1 MiB** (repeats: 108.9, 121.5) | java 139 MB |
| 1 opencode session, during the turn (peak) | **550 MiB** | opencode serve 437 MB, java 158 MB |
| 1 session, after the turn (process kept, keepAlive 30) | **500 MiB** | opencode serve 437 MB |
| 2 concurrent sessions, during (peak) | **850 MiB** | 2 x opencode serve 421 / 409 MB, java 146 MB |
| 2 sessions, after (both kept, keepAlive 30) | **800.6 MiB** | as above |
| 2 concurrent sessions, one-shot (keepAlive 0), peak | **912 MiB** | `opencode run` x 2 |
| 2 sessions one-shot, after | **141.8 MiB** | java only |

The very first opencode start in a fresh `infantry-home` volume peaked at 1.24 GiB raw cgroup memory (file cache
included: opencode unpacks and indexes on its first run); later starts stayed in the numbers above. pi was installed and
resolved (`pi --version` 1.4.0) but no pi session was measured: pi lists no models without a credential and none was
used for the test.

What this means for 2 GB: the daemon alone is ~115 MiB; each live opencode session costs ~400-450 MB; two at once peak
~0.9 GB. With `mem_limit` 1600m, 2 GB swap and `session.keepAliveMinutes 0`, two concurrent workers fit.

## 10. Settings that bound the demo

All in `docker/docker-compose.yml`, each overridable in `.env`, and changeable live in Settings > Limits or with
`infantry settings set <key> <n>` (the stored setting wins over the environment). From `settings/LimitRegistry.kt`:

| Env | Setting | Demo value | Meaning |
|---|---|---|---|
| `INFANTRY_LIMIT_CONCURRENCY_MAX_SESSIONS` | `limits.concurrency.maxSessions` | 2 | sessions of the whole daemon OPEN at once (working, or idle with their CLI process kept alive), any path; at the cap a start is refused with "Demo busy: 2 agents are open" until one is stopped (Stop agent button in its chat) or times out (0 = unlimited) |
| `INFANTRY_SESSION_KEEP_ALIVE_MINUTES` | `session.keepAliveMinutes` | 2 | minutes an idle agent keeps its CLI process, and so its slot above (normal app default 30; 0 = one-shot, a slot is held only during a turn) |
| `INFANTRY_ALLOWED_MODELS` | `limits.models.allowed` | `commandcode/stealth/glyph-cluster:free` | the only models that may be picked or run (comma list, `*` = any characters); the daemon refuses every other one and the pickers hide them; a harness with no allowed model is shown dimmed. Empty = every model |
| `INFANTRY_ALLOWED_ROLES` | `limits.roles.allowed` | (empty = all roles; optional, off in the demo) | when set, the only roles that may be started, assigned to a ticket or picked in the New agent dialog; the daemon refuses every other one. Sessions with no role are not affected. Empty = every role |
| `INFANTRY_LIMIT_CONCURRENCY_MAX_PER_POD` | `limits.concurrency.maxPerPod` | 2 | sessions one session / MCP connection may add (legacy `INFANTRY_POD_SPAWN_CAP`) |
| `INFANTRY_LIMIT_CONCURRENCY_MCP_MAX_SPAWNS` | `limits.concurrency.mcpMaxSpawns` | 2 | sessions one MCP connection may hold open |
| `INFANTRY_LIMIT_CONCURRENCY_MAX_DEPTH` | `limits.concurrency.maxDepth` | 1 | spawn nesting (legacy `INFANTRY_POD_SPAWN_DEPTH`) |
| `INFANTRY_LIMIT_CONCURRENCY_LANE_DEFAULT` | `limits.concurrency.laneDefault` | 1 | agents of one role at once per project |
| `INFANTRY_DEMO_USAGE_SESSION_PERCENT` / `..._WEEK_PERCENT` | (Demo_Lock) | 10 / 25 | the FIXED plan meters a demo visitor sees on the `opencode-go` plan (never the real ones) |
| `INFANTRY_PROCESSES_FLOOR_MB` | `processes.floorMb` | 256 (2 GB box; default 4096) | free RAM (MB) below which the daemon warns "memory pressure"; a stored setting still wins |
| `INFANTRY_LIMIT_PACING_MAX_WORKERS_CEILING` | `limits.pacing.maxWorkersCeiling` | 2 | highest worker count the usage pacing rule accepts |
| `INFANTRY_USAGE_MOCK` | `usage.mock` | off | `1` shows mock usage data (Infantry_Usage); see `INFANTRY_USAGE_MOCK_DATA` (JSON) and `INFANTRY_USAGE_MOCK_AFFECTS_PACING` (default off: pacing keeps the real usage) |
| `INFANTRY_LIMIT_BUILD_MAX_WORKERS` / `..._MAX_PARALLEL_FORKS` | `limits.build.*` | 1 / 1 | Gradle workers / test forks of a worker's build |
| `INFANTRY_LIMIT_WATCHDOG_TICKET_DEADLINE_MINUTES` | `limits.watchdog.ticketDeadlineMinutes` | 60 | unattended ticket worker deadline (legacy `INFANTRY_TICKET_DEADLINE_MIN`) |
| `INFANTRY_LIMIT_WATCHDOG_DEADLINE_MINUTES` | `limits.watchdog.deadlineMinutes` | 30 | other unattended runs |
| `INFANTRY_LIMIT_WATCHDOG_STALL_MINUTES` | `limits.watchdog.stallMinutes` | 15 | silent run stopped as stalled |
| (also) `..._KILL_RETRY_MINUTES`, `..._SILENT_MINUTES`, `INFANTRY_LIMIT_CPU_ALERT_PERCENT/_MINUTES`, `INFANTRY_LIMIT_PROCESSES_WATCH_INTERVAL_SECONDS` | | defaults | see `LimitRegistry.kt` |
| `JAVA_OPTS` | | `-Xms32m -Xmx512m` | daemon heap |
| `INFANTRY_MEM_LIMIT` | | 1600m | whole container (daemon + CLIs) |

`limits.concurrency.maxSessions` (ticket f9e181af) closes the old gap: a person in the browser can no longer open more
sessions than that by hand. It counts OPEN sessions: working on a turn, or idle with their CLI process still kept alive
(`session.keepAliveMinutes`, 2 min in the demo), so an idle agent frees its slot when it is stopped or times out.

The model allowlist is stored-setting > env: anyone holding the owner token can lift it in Settings
(`infantry settings set limits.models.allowed '[]'`).

## 11. Linux / container notes (what was found making this work)

1. **Native libraries vs a noexec `/tmp`** (fixed in `docker/entrypoint.sh`). sqlite-jdbc (and JNA) extract their `.so`
   into `java.io.tmpdir` and load it; compose `tmpfs: /tmp` is mounted noexec, so the daemon died at
   `db/InfantryDatabase.kt:38` (`JdbcSqliteDriver`) with "failed to map segment from shared object" and
   `NativeLibraryNotFoundException ... Linux/x86_64`. The entrypoint passes `-Dorg.sqlite.tmpdir` and `-Djna.tmpdir` =
   `/data/native-libs` (`INFANTRY_NATIVE_DIR`). This also affected the existing `docker/compose.yaml` (read-only root +
   tmpfs `/tmp`); `docker/smoke.sh` now passes against it.
2. **How each CLI is found**: `executables/ExecutableResolver.kt:31-62`, in order: Settings override > env
   (`INFANTRY_CLAUDE_EXE` / `INFANTRY_OPENCODE_EXE` / `INFANTRY_PI_EXE`, `executables/CliTools.kt:44,76,116`) > the app's own
   npm prefix `<INFANTRY_HOME>/harness-tools/bin/<id>` (`executables/HarnessPrefix.kt:20-24`) > the Linux default
   locations (`CliTools.kt:63` claude, `:99` opencode, `:129` pi: `~/.local/bin`, `/usr/local/bin`, ...) > `PATH`. The
   image installs with `npm -g` into `/usr/local/bin`, which is both a default location and on PATH: no setting needed.
3. **Home directory / credentials**: the container user is `infantry` (uid 10001), `HOME=/home/infantry`, kept in the
   `infantry-home` volume. opencode, pi and claude keep their logins and config under it (`~/.local/share/opencode`,
   `~/.config/opencode`, `~/.pi`); claude keeps its login and config in `CLAUDE_CONFIG_DIR=/data/claude`, the state volume,
   so a recreated container stays signed in (RUNNER-LOGIN-1; the entrypoint copies an older `~/.claude` login there once).
   Nothing is read from the host.
4. **Windows-only jars**: none in the image. The skiko runtime (`skiko-awt-runtime-windows-x64`) and the Compose jars
   are on the DESKTOP app's classpath only (`infantryUi`); the image uses `:infantry:installDist`, whose `lib/` has 36 jars,
   no skiko, no Compose. JNA and sqlite-jdbc carry their Linux natives.
5. **PTY vs piped runner**: Linux uses the same piped runner as Windows (`runner/RunnerFactory.kt:9-13`, default
   `piped`); `INFANTRY_RUNNER=pty` throws by design (`runner/PtyProcessRunner.kt`). Nothing to change.
6. **Kept opencode server**: with `session.keepAliveMinutes` > 0 each opencode session keeps an `opencode serve` child
   (~420 MB) between turns (`session/host/HarnessSessionAdapter.kt:28` default 30, `opencode/OpenCodeServeAdapter.kt`).
   Settings-only, no env name.
7. **Memory-pressure floor**: `session/ProcessWatch.kt:23` default `floorMb = 4096`; a warning only (auto-reap is off by
   default), but it fires on any box under ~4 GB free. Settings-only (`processes.floorMb`).
8. **Browser host check**: inside a container the daemon cannot see the droplet's public address, so `/` answers 403
   to any Host header but localhost until `INFANTRY_WEB_HOSTS` names it (`daemon/AccessPolicy.kt:326`). Measured: 403
   without, 200 with.
9. **Token**: the image sets `INFANTRY_TOKEN_FILE=/data/access-token`; the daemon refuses both a file AND a non-blank
   `INFANTRY_TOKEN` (`daemon/AccessPolicy.kt:337`), so the entrypoint drops the file when a token is given explicitly.
   `INFANTRY_TOKEN_AUTO=false` restores the strict "refuse to start without a token" rule (`docker/smoke.sh` step 1).
10. **`infantry` CLI inside the container** is a JVM per call (Node is only for the harness CLIs); agents that call it
    often pay a JVM start each time.

## 12. Sandbox: agents confined to their directory (SANDBOX-1, ticket d4c1dbd9)

`docker/docker-compose.yml` turns it on (`INFANTRY_LIMIT_SANDBOX_MODE=1`). It is a daemon setting, default **off**
everywhere else: `limits.sandbox.mode` (0/1) and `limits.sandbox.allowedDirs` (JSON array of absolute paths; env
`INFANTRY_LIMIT_SANDBOX_ALLOWED_DIRS`, comma separated), both in Settings > Limits / `infantry settings`. The Limits tab and
the startup line `[infantry] sandbox: ...` say what is really enforced on that machine. Code: `runner/Sandbox.kt`.

Every agent CLI launch (all three runner seams of the session manager) goes through `SandboxRunner`. With the mode on:

1. **Scoped token.** Agents get a token the daemon generates at each start (memory only) instead of the owner token.
   That tier (`daemon/AgentTier.kt`) may read, mail, report progress/bg jobs, ask questions and work its tickets and
   knowledge; it is refused `settings_set`, CLI locations, harness installs, role/agent/skill/trait/prompt edits, tool
   approval, secrets, projects, commands, triggers, spawns (`new`, pods), prompting or killing sessions, and every
   HTTP route except `/health`, `/ws`, `/mail/*`, `GET /attention`. `INFANTRY_TOKEN_FILE` is blanked for agents.
   This fixes QA 950f4251's finding (the owner token in `/proc/<pid>/environ` of `opencode serve`).
2. **Harness permissions (first layer), per CLI:**
   - **opencode 1.19.4**: inline `OPENCODE_CONFIG_CONTENT` `permission` merged over the daemon's own (the `infantry_*`
     MCP allow and the MCP server entry are kept): `bash: deny`, `webfetch: deny`, `websearch: deny`,
     `external_directory: {"*": deny, "<workdir>/**": allow, "<allowedDir>/**": allow}`, `edit` denied on
     `**/opencode.json(c)`, `**/.opencode/**`, `**/.config/opencode/**`, `**/.pi/**`, `**/.claude/**`; plus
     `OPENCODE_DISABLE_PROJECT_CONFIG=1` (an `opencode.json` an agent writes is ignored), `OPENCODE_DISABLE_AUTOUPDATE`,
     `OPENCODE_DISABLE_CLAUDE_CODE`.
   - **pi 1.4.0**: has no permission system (only which tools exist): `--exclude-tools bash,powershell`. read/edit/write
     stay and are path-confined by the OS layer ONLY. Its mail path is the infantry MCP server (`.pi/mcp.json`, the pi
     tool bridge, switched on for every pi session while the sandbox is on).
   - **claude**: `--disallowedTools Bash WebFetch WebSearch`, `--settings` with `permissions.deny` on its own config
     (`~/.claude/**`, `~/.claude.json`, `**/.claude/settings*.json`), `--add-dir` for each allowed dir. Not tested in
     the container (no claude credential).
   Not enforceable at the harness layer: pi file paths (no setting), opencode's `read`/`glob`/`grep` inside the allowed
   dirs reading files an agent should not see there (they are the agent's own files by definition).
3. **OS confinement (backstop), Linux only**: the CLI runs under `/usr/local/bin/landlock-exec` (`docker/landlock-exec.c`,
   compiled static in the image). Read + execute: `/usr /bin /lib /lib64 /etc /opt /proc /sys/fs/cgroup`, the user's
   home (CLI config and logins, READ-ONLY), `/data/pi`, `/data/harness-tools`. Read + write: the session's working dir,
   `limits.sandbox.allowedDirs`, the CLI's own state dirs (opencode `~/.local/share|state/opencode`, `~/.cache/opencode`;
   pi sessions; claude `~/.claude/projects|todos|...`) and the scratch `TMPDIR=/data/sandbox/tmp`. Everything else,
   `/data` (database, `access-token`, keystore) and `/proc/1/environ` included, is out of reach. Fails closed: mode on +
   no helper = the launch is refused, never run unconfined. Landlock needs kernel >= 5.13 (Ubuntu 22.04/24.04 droplets
   and Docker Desktop's WSL2 kernel 6.6 have it; Docker's default seccomp profile allows it, no capability needed).
   **Windows / macOS: NOT ENFORCED YET** (the status says so); only layers 1 and 2 apply there.

Proven inside the running container (2026-10-25, Docker Desktop WSL2 kernel 6.6.114, Landlock ABI 3):

| Attack | Result |
|---|---|
| agent shell call (`cat /data/access-token`) | no shell tool exists for the agent (bash denied) |
| owner token in the agent's env | agent `INFANTRY_TOKEN=378632...`, owner `/data/access-token` = `3609ae...`; `INFANTRY_TOKEN_FILE=` |
| agent token: `settings set limits.sandbox.mode 0` | `sandbox: 'settings_set' is not available to a sandboxed agent` |
| agent token: `harness-install claude` / `project config set` | refused the same way; `POST /update/apply` = 403 |
| Landlock policy: `cat /data/access-token`, `/data/infantry.db`, `ls /data` | `Permission denied` |
| Landlock policy: write `~/.config/opencode/opencode.json` (add a model) | `Permission denied` |
| Landlock policy: write `/tmp/pwned`, `/work/pwned` | `Permission denied` |
| Landlock policy: read `/proc/1/environ` | `Permission denied` |
| allowed: write `/work/sbx/allowed.txt`; read CLI config | works |
| pm -> backend mail via the `infantry_message` MCP tool, shell off | `state: DELIVERED` |
| two turns at once (pm + backend, opencode free model) | both IDLE in 4.9 s / 2.6 s, overlapping |

Memory with the sandbox on, 2 kept opencode sessions, during and after two concurrent turns: **853-862 MiB**
(`docker stats`, 6 samples). QA 950f4251 measured 1.03-1.05 GiB on a longer run: budget **~1.05 GiB** for two
sessions, not the 800 MiB of section 9; `mem_limit` 1600m + 2 GB swap still fits a 2 GB droplet.

## 13. Locked public demo (DEMO-LOCK, ticket 2f154f5f; the `Demo_Lock` plugin, any branch)

`INFANTRY_DEMO_LOCK=1` makes the daemon default-deny for EVERY token, the owner's included: only reading the demo project,
prompting / stopping its two seeded agents and mail between those two get through; everything else is refused
(`demo: ...` frame error / HTTP 403). The flag is read once at start-up and is not a stored setting, so no token can turn
it off; to change it, redeploy. Without the overlay below the same image runs the normal app.

The seed (`docker/demo-seed.env`): on the first locked start the entrypoint makes `/work/demo` a git repository and the
daemon creates "Demo project" on it plus one session per seat (`backend` and `pm`, opencode, `commandcode/stealth/glyph-cluster:free`),
persisted in `/data`; later starts find the same ones. The notice shown above each agent's composer is
`INFANTRY_DEMO_NOTICE`. To re-seed: `docker compose ... down -v` (removes the volumes, the token too).

Build and ship (on your PC, from any checkout of the dev branch; the `demo-build` branch is retired, D16).
`-Pdemo=true` compiles the demo-only UI modules (`plugins/demo/`) into the web client:

```bash
./gradlew :superiorSoftLibrary:infantry:dockerContext -Pdemo=true --max-workers=2
docker build -t infantryai/infantry-ai:demolock superiorSoftLibrary/infantry/build/docker
docker save infantryai/infantry-ai:demolock | gzip > infantry-ai-demolock.tar.gz
scp infantry-ai-demolock.tar.gz docker/docker-compose.yml docker/docker-compose.demo.yml docker/demo-seed.env root@<droplet>:/root/
```

On the droplet:

```bash
cd /root && docker load < infantry-ai-demolock.tar.gz
cat > .env <<'ENV'
INFANTRY_IMAGE=infantryai/infantry-ai:demolock
INFANTRY_BIND=127.0.0.1             # only Caddy reaches it
INFANTRY_HOST_PORT=8787
INFANTRY_WEB_HOSTS=demo.example.com # your domain; the browser is refused on any other host name
OPENCODE_API_KEY=<the opencode-go key>
CMD_API_KEY=<the Command Code key>
ENV
chmod 600 .env
docker compose -f docker-compose.yml -f docker-compose.demo.yml --env-file .env up -d
curl -fsS http://127.0.0.1:8787/health      # healthy after ~40 s; "sessions":2
docker compose -f docker-compose.yml exec infantry-ai cat /data/access-token
```

Key copy: the opencode-go key goes ONLY in `/root/.env` on the droplet (mode 600), never in the image or the repo; it
reaches the container as `OPENCODE_API_KEY`. The agents run under Landlock (`limits.sandbox.mode=1`): `/data`
(database, token) is out of their reach.

The tokens: visitors type nothing. The entrypoint writes a public visitor token to `/data/demo-public-token` (or set
`INFANTRY_DEMO_PUBLIC_TOKEN`), the page fetches it from `GET /demo/visitor-token` and lands connected. The daemon admits
that token only while the demo preset is active, and under the lock it can do only what the allow-list permits (proved
by `docker/demo-lock-attack.mjs`, below). Each client IP gets `INFANTRY_DEMO_TURNS_PER_IP` turns per
`INFANTRY_DEMO_TURN_WINDOW_HOURS` (behind Caddy, the client IP comes from `X-Forwarded-For` of a peer in
`INFANTRY_DEMO_TRUSTED_PROXIES`, default loopback). Rotate the visitor token (delete `/data/demo-public-token`, restart)
to cut everyone off. `/data/access-token` is the owner's token: keep it private.

Caddy (TLS) on the droplet host, `/etc/caddy/Caddyfile`:

```
demo.example.com {
	reverse_proxy 127.0.0.1:8787
}
```

(`apt install caddy`, then `systemctl reload caddy`; Caddy gets the certificate itself. The WebSocket `/ws` passes
through `reverse_proxy` unchanged.) Firewall (DigitalOcean Cloud Firewall; `ufw` does not cover Docker-published ports):
inbound TCP 22 (your IP), 80 and 443 (all); nothing for 8787, which is bound to loopback. Outbound: defaults.

Proof against a locked container (Node >= 22, the owner token in a file):

```bash
DEMO_URL=http://127.0.0.1:8787 DEMO_TOKEN_FILE=token.txt node docker/demo-lock-attack.mjs              # every frame + route
DEMO_URL=http://127.0.0.1:8787 DEMO_TOKEN_FILE=token.txt node docker/demo-lock-attack.mjs --scenarios  # named attacks
```
