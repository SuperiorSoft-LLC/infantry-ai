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

1. **Start the runner.** From the image: `docker run -d --name infantry-runner -e INFANTRY_MODE=runner -v infantry-data:/data <image>`
   (the entrypoint passes its arguments on, so `... <image> --mode runner` works too). From an install: `infantry daemon --mode runner`.
2. **Log the agent CLI in on the server** (for example `claude`) over SSH or `docker exec`. Logins and secrets never travel from your PC.
3. **Allow what it may run** on the runner: the settings `runner.projects` and `runner.harnesses` (empty means none, `*` means any).
4. **Pair.** On the server: `infantry runner pair --name server-1` prints a one-time code and the certificate fingerprint.
   On your PC: `infantry runner add server-1 wss://<host>:<port> --code <code> --fingerprint <fingerprint>` (compare the fingerprint first).
5. **Use it.** Turn on `runners.enabled`, then pin a ticket to `runner: server-1`, or set `runners.default` to `server-1` or `any`.
   Caps: `runners.<name>.maxWorkers`. Pause a runner with `runners.<name>.paused` = true (running workers go on, no new start).
   Revoke with `infantry runner detach server-1`; rotate the credential with `infantry runner rotate server-1`.

The usage allowance (`usage.pacing`) counts running workers across every runner for the same provider account, so one shared login is
one meter.

Docker-only test setup: use ports of your own, never the owner's daemon ports, and remove the container when done.
