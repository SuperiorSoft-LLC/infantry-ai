[← Wiki index](index.md)

# Run a remote environment

A remote environment is a second machine (a server, a container) that runs your agents while the board stays on your PC.
One build and one image serve every role; a startup **mode** picks what a daemon does. Design record:
[RUNNER-POOL.md](../RUNNER-POOL.md).

## The mode

| mode | what runs | use it for |
|---|---|---|
| `both` (default) | board and runner `local`, exactly as before | your PC |
| `runner` | sessions, harnesses, worktrees, the runner link. No board: a client frame that reads or writes board state (tickets, mail, knowledge, roles) is refused with `refused: this daemon runs in runner mode` | a remote server |
| `orchestrator` | the board only. No session runs on this machine: a local start is refused and placement skips `local` | an always-on board (optional) |

Choose it, first source wins: `infantry daemon --mode runner` (or `--mode=runner`), the environment variable `INFANTRY_MODE`, the setting
`daemon.mode`, else `both`. A value that is no mode stops the start with the three valid names. `[infantry] mode: <mode>` is printed at start-up.

## Steps

1. **Start the runner.** From the image on GitHub Container Registry (tags `:<version>`, `:latest` = newest stable, `:nightly`):
   ```
   docker pull ghcr.io/superiorsoft-llc/infantry-ai:latest
   docker run -d --name infantry-runner --restart unless-stopped \
     -e INFANTRY_MODE=runner -v infantry-data:/data -v infantry-work:/work \
     -p 8790:8790 ghcr.io/superiorsoft-llc/infantry-ai:latest
   ```
   `/data` holds the state (token, link keystore, agent logins), `/work` the repositories and worktrees. Port 8790 is the runner
   link (`limits.runners.linkPort`, TLS). The daemon's own port 8787 stays closed. The image bakes in `opencode` and `pi`; build
   with `--build-arg INSTALL_CLAUDE=true` to add `claude`. Add `-e INFANTRY_RUNNER_NAME=server-1` (plus `INFANTRY_RUNNER_HOST`,
   `INFANTRY_RUNNER_ALLOW_PROJECTS`, `INFANTRY_RUNNER_ALLOW_HARNESSES`) and the container prints its pairing link in `docker logs`
   once it is up and not paired yet (see [Runner quickstart](runner-quickstart.md)). From an install instead: `infantry runner up
   --name server-1 --host <address>`, which starts a runner-mode daemon when none answers and prints the link.
2. **Log the agent CLI in on the server**, for example `docker exec -it infantry-runner claude login` (the login lands in `/data`).
   Logins and secrets never travel from your PC.
3. **Allow what it may run** on the runner: the settings `runner.projects` and `runner.harnesses` (empty means none, `*` means any).
4. **Pair.** On the server (`docker exec -it infantry-runner infantry runner pair --name server-1`) it prints one pairing link
   (`infantry://runner?...`, carrying hosts, port, code and fingerprint; add `--host <address>` for an address the runner cannot detect,
   e.g. behind Docker NAT), then the one-time code and fingerprint by hand.
   On your PC, paste that link: `infantry runner add 'infantry://runner?v=1&name=server-1&host=...&port=8790&code=...&fp=...'`
   (quote it). The link's hosts are tried in order; a host that does not answer or whose certificate is not the link's is skipped
   without sending the code, and the first that answers pairs. The by-hand form still works:
   `infantry runner add server-1 wss://<host>:<port> --code <code> --fingerprint <fingerprint>`.
5. **Use it.** The first successful add turns `runners.enabled` on; then pin a ticket to `runner: server-1`, or set `runners.default` to `server-1` or `any`.
   Caps: `runners.<name>.maxWorkers`. Pause a runner with `runners.<name>.paused` = true (running workers go on, no new start).
   Revoke with `infantry runner detach server-1`; rotate the credential with `infantry runner rotate server-1`.

The usage allowance (`usage.pacing`) counts running workers across every runner for the same provider account, so one shared login is
one meter.

Docker-only test setup: use ports of your own, never the owner's daemon ports, and remove the container when done.

## Deploying the runner image (for the website team)

The image is one container; nothing else is needed.

| item | value |
|---|---|
| image | `ghcr.io/superiorsoft-llc/infantry-ai:latest`, or a pinned `:<version>` |
| port to open | **8790/tcp**, the runner link (TLS); restrict it to the orchestrator's address when you can |
| volume to back up | `/data` (state, link keystore, agent logins). `/work` holds clones that can be rebuilt |
| healthcheck | built in: `GET http://127.0.0.1:8787/health` every 30 s; the daemon exits after 3 failed self-checks, so `--restart unless-stopped` brings it back |
| update | `docker pull <image>`, then recreate the container with the same volumes; `/data` survives |
| user | non-root (uid 10001); the volumes must be writable by it |

Verification: `docker/runner-compare.sh` runs the same pinned ticket against a container runner and a native runner and prints the
differences; the `docker-image` workflow runs it in CI and publishes the image.
