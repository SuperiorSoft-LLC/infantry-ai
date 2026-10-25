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
./gradlew :superiorSoftLibrary:agentHq:dockerContext          # daemon + web UI + Dockerfile + entrypoint -> build/docker
docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/agentHq/build/docker
docker save infantryai/infantry-ai:dev | gzip > infantry-ai.tar.gz   # ~420 MB compressed content
```

Build options (`--build-arg`): `OPENCODE_VERSION=1.19.4`, `PI_VERSION=1.4.0` pin the CLIs (default: latest at build
time); `INSTALL_CLAUDE=true` adds Claude Code (off by default: it draws on a paid subscription).

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
tailscale ip -4                    # e.g. 100.x.y.z -> use it as AGENTHQ_BIND below; no Cloud Firewall rule needed
```

Plain HTTP carries the token in clear text. For a public demo on a domain, use the `tls` profile of
`docker/compose.yaml` (Caddy, automatic HTTPS) or Tailscale, not a bare port.

## 6. Start it

```bash
cd /root
docker load < infantry-ai.tar.gz
cat > .env <<'EOF'
AGENTHQ_BIND=0.0.0.0               # Option B: the Tailscale IP instead
AGENTHQ_HOST_PORT=8787
AGENTHQ_WEB_HOSTS=<droplet-ip>     # or your domain / Tailscale name; the browser is refused on any other host name
EOF
docker compose -f docker-compose.yml --env-file .env up -d
docker compose -f docker-compose.yml ps        # STATUS: Up ... (healthy) after ~40 s
curl -fsS http://127.0.0.1:8787/health          # {"status":"ok",...}
```

Then the two settings that matter on 2 GB (stored in the volume, so once is enough):

```bash
docker compose -f docker-compose.yml exec infantry-ai agenthq settings set session.keepAliveMinutes 0
docker compose -f docker-compose.yml exec infantry-ai agenthq settings set processes.floorMb 256
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
`agenthq settings set <key> <n>` (the stored setting wins over the environment). From `settings/LimitRegistry.kt`:

| Env | Setting | Demo value | Meaning |
|---|---|---|---|
| `AGENTHQ_LIMIT_CONCURRENCY_MAX_PER_POD` | `limits.concurrency.maxPerPod` | 2 | sessions one session / MCP connection may add (legacy `AGENTHQ_POD_SPAWN_CAP`) |
| `AGENTHQ_LIMIT_CONCURRENCY_MCP_MAX_SPAWNS` | `limits.concurrency.mcpMaxSpawns` | 2 | sessions one MCP connection may hold open |
| `AGENTHQ_LIMIT_CONCURRENCY_MAX_DEPTH` | `limits.concurrency.maxDepth` | 1 | spawn nesting (legacy `AGENTHQ_POD_SPAWN_DEPTH`) |
| `AGENTHQ_LIMIT_CONCURRENCY_LANE_DEFAULT` | `limits.concurrency.laneDefault` | 1 | agents of one role at once per project |
| `AGENTHQ_LIMIT_PACING_MAX_WORKERS_CEILING` | `limits.pacing.maxWorkersCeiling` | 2 | highest worker count the usage pacing rule accepts |
| `AGENTHQ_LIMIT_BUILD_MAX_WORKERS` / `..._MAX_PARALLEL_FORKS` | `limits.build.*` | 1 / 1 | Gradle workers / test forks of a worker's build |
| `AGENTHQ_LIMIT_WATCHDOG_TICKET_DEADLINE_MINUTES` | `limits.watchdog.ticketDeadlineMinutes` | 60 | unattended ticket worker deadline (legacy `AGENTHQ_TICKET_DEADLINE_MIN`) |
| `AGENTHQ_LIMIT_WATCHDOG_DEADLINE_MINUTES` | `limits.watchdog.deadlineMinutes` | 30 | other unattended runs |
| `AGENTHQ_LIMIT_WATCHDOG_STALL_MINUTES` | `limits.watchdog.stallMinutes` | 15 | silent run stopped as stalled |
| (also) `..._KILL_RETRY_MINUTES`, `..._SILENT_MINUTES`, `AGENTHQ_LIMIT_CPU_ALERT_PERCENT/_MINUTES`, `AGENTHQ_LIMIT_PROCESSES_WATCH_INTERVAL_SECONDS` | | defaults | see `LimitRegistry.kt` |
| `JAVA_OPTS` | | `-Xms32m -Xmx512m` | daemon heap |
| `AGENTHQ_MEM_LIMIT` | | 1600m | whole container (daemon + CLIs) |

**Gap:** there is no daemon-wide cap on the number of live sessions. `maxPerPod` / `mcpMaxSpawns` bound spawns made by
sessions and MCP connections ("session-originated spawns only", daemon log), and `laneDefault` bounds one role per
project; a person in the browser can still start more sessions by hand. On the demo, `mem_limit` is the backstop (the
kernel kills inside the container, not the droplet).

## 11. Linux / container notes (what was found making this work)

1. **Native libraries vs a noexec `/tmp`** (fixed in `docker/entrypoint.sh`). sqlite-jdbc (and JNA) extract their `.so`
   into `java.io.tmpdir` and load it; compose `tmpfs: /tmp` is mounted noexec, so the daemon died at
   `db/AgentHQDatabase.kt:38` (`JdbcSqliteDriver`) with "failed to map segment from shared object" and
   `NativeLibraryNotFoundException ... Linux/x86_64`. The entrypoint passes `-Dorg.sqlite.tmpdir` and `-Djna.tmpdir` =
   `/data/native-libs` (`AGENTHQ_NATIVE_DIR`). This also affected the existing `docker/compose.yaml` (read-only root +
   tmpfs `/tmp`); `docker/smoke.sh` now passes against it.
2. **How each CLI is found**: `executables/ExecutableResolver.kt:31-62`, in order: Settings override > env
   (`AGENTHQ_CLAUDE_EXE` / `AGENTHQ_OPENCODE_EXE` / `AGENTHQ_PI_EXE`, `executables/CliTools.kt:44,76,116`) > the app's own
   npm prefix `<AGENTHQ_HOME>/harness-tools/bin/<id>` (`executables/HarnessPrefix.kt:20-24`) > the Linux default
   locations (`CliTools.kt:63` claude, `:99` opencode, `:129` pi: `~/.local/bin`, `/usr/local/bin`, ...) > `PATH`. The
   image installs with `npm -g` into `/usr/local/bin`, which is both a default location and on PATH: no setting needed.
3. **Home directory / credentials**: the container user is `agenthq` (uid 10001), `HOME=/home/agenthq`, kept in the
   `infantry-home` volume. opencode, pi and claude keep their logins and config under it (`~/.local/share/opencode`,
   `~/.config/opencode`, `~/.pi`, `~/.claude`); nothing is read from the host.
4. **Windows-only jars**: none in the image. The skiko runtime (`skiko-awt-runtime-windows-x64`) and the Compose jars
   are on the DESKTOP app's classpath only (`agentHqUi`); the image uses `:agentHq:installDist`, whose `lib/` has 36 jars,
   no skiko, no Compose. JNA and sqlite-jdbc carry their Linux natives.
5. **PTY vs piped runner**: Linux uses the same piped runner as Windows (`runner/RunnerFactory.kt:9-13`, default
   `piped`); `AGENTHQ_RUNNER=pty` throws by design (`runner/PtyProcessRunner.kt`). Nothing to change.
6. **Kept opencode server**: with `session.keepAliveMinutes` > 0 each opencode session keeps an `opencode serve` child
   (~420 MB) between turns (`session/host/HarnessSessionAdapter.kt:28` default 30, `opencode/OpenCodeServeAdapter.kt`).
   Settings-only, no env name.
7. **Memory-pressure floor**: `session/ProcessWatch.kt:23` default `floorMb = 4096`; a warning only (auto-reap is off by
   default), but it fires on any box under ~4 GB free. Settings-only (`processes.floorMb`).
8. **Browser host check**: inside a container the daemon cannot see the droplet's public address, so `/` answers 403
   to any Host header but localhost until `AGENTHQ_WEB_HOSTS` names it (`daemon/AccessPolicy.kt:326`). Measured: 403
   without, 200 with.
9. **Token**: the image sets `AGENTHQ_TOKEN_FILE=/data/access-token`; the daemon refuses both a file AND a non-blank
   `AGENTHQ_TOKEN` (`daemon/AccessPolicy.kt:337`), so the entrypoint drops the file when a token is given explicitly.
   `AGENTHQ_TOKEN_AUTO=false` restores the strict "refuse to start without a token" rule (`docker/smoke.sh` step 1).
10. **`agenthq` CLI inside the container** is a JVM per call (Node is only for the harness CLIs); agents that call it
    often pay a JVM start each time.
