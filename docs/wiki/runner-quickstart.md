# Runner quickstart: run an agent on your own server

Proven end to end on one machine (RUNNER-E2E-1): a Docker runner paired to an orchestrator, a ticket pinned to that runner,
the session started on the runner. Design: [RUNNER-POOL](../RUNNER-POOL.md). Server setup: [Run a remote environment](run-a-remote-environment.md).

Words: the **orchestrator** is the daemon with your board (your PC); the **runner** is the daemon on the server that runs the agent CLIs.

## 1. On the server: start the runner

One command, then copy the link it prints.

**Docker.** Use the project's runner image (or build it: `./gradlew :superiorSoftLibrary:infantry:dockerContext`, then
`docker build -t infantry-runner superiorSoftLibrary/infantry/build/docker`). Mount nothing of your PC.

```
docker run -d --name infantry-runner -e INFANTRY_MODE=runner \
  -e INFANTRY_RUNNER_NAME=server-1 -e INFANTRY_RUNNER_HOST=<public or tailnet address> \
  -e INFANTRY_RUNNER_ALLOW_PROJECTS='<project-id>' -e INFANTRY_RUNNER_ALLOW_HARNESSES=claude \
  -p 8790:8790 -v infantry-data:/data infantry-runner
docker logs infantry-runner        # the link is between the ==== PAIRING LINK ==== lines
```

`INFANTRY_RUNNER_NAME` makes the container print a pairing link once the daemon is up and the runner is not paired yet. Inside a
container the detected hosts are container addresses, so set `INFANTRY_RUNNER_HOST` (the log warns when it is missing). If the
code expires unpaired the log says so; `docker exec -it infantry-runner infantry runner pair --name server-1` issues a new one (the
container never loops on issuing codes: each issue is a fresh 10-minute window for guessing).

**An install** (no Docker): `infantry runner up --name server-1 --host <address> --allow-projects '<project-id>' --allow-harnesses claude`.
`--allow-projects` / `--allow-harnesses` take comma-separated ids, or `*` for every one; without them a console prompt asks (nothing
granted silently).
If no daemon answers it starts one in runner mode in the background (waits `limits.runners.upStartSec`, default 60, log in
`<state dir>/runner-up.log`), then prints the link. Run it again and it says "already paired" or "waiting" and starts nothing new.
A daemon running in another mode is refused with that mode.

On Windows a bare `*` only reaches the CLI when nothing in the current folder matches it: `java.exe` expands any argument
containing `*` against the files there (a JVM quirk; shell quoting does not stop it), so `--allow-projects '*'` typed in a
folder full of files fails with the usage line. Run it from an empty folder, or list the ids explicitly.

**The link is a secret for 10 minutes** (it carries the one-time code) and sits in the container log; it is useless once redeemed
or expired. The link port is `limits.runners.linkPort`, default **8790** (env `INFANTRY_LIMIT_RUNNERS_LINK_PORT`); publish it.

### By hand (fallback)

```
docker run -d --name infantry-runner -p 8790:8790 -e INFANTRY_MODE=runner infantry-runner
docker exec infantry-runner infantry runner pair --name server-1 --allow-projects '<project-id>' --allow-harnesses claude
```

Pairing is where you grant access (RUNNER-DEFAULTS-1). `runner.projects` / `runner.harnesses` default to `[]` (nothing may
start remotely, the least access) and nothing grants them silently. `--allow-projects` / `--allow-harnesses` take
comma-separated ids, or `*` for every one. Run `pair` in a terminal (`docker exec -it`) without the flags and it asks, with the
harnesses installed on the runner pre-selected (Enter confirms). `pair` writes the two settings, so the lines
`infantry settings set runner.projects '["<id>"]'` and `runner.harnesses` do the same by hand. A start the lists refuse says
which setting is missing and prints that command. A start also needs the project to have a git `origin` the runner can reach; without one
it is refused with `git remote add origin <url>` as the fix.
`pair` prints one pairing link (`infantry://runner?v=1&name=...&host=...&port=...&code=...&fp=...`; hosts come from `--host` flags, then the `runners.advertiseHosts` setting, else the detected addresses, tailnet first; at most `limits.runners.advertiseHostsMax`), then the one-time code and the certificate fingerprint "by hand", and keeps serving until the code is used (10 minutes).

### Grant the project at pairing, from the PC (RUNNER-GRANT-AT-PAIR-1, owner decision D24)

Project ids live on the PC, so typing one on the server is the step worth removing. Issue the code with `--grant-on-pair` and
the first pairing may add ONE project (chosen on the PC, `limits.runners.grantProjectsMax`) and the harnesses installed on the
runner (or only the ones in `--grant-harnesses-max`) to `runner.projects` / `runner.harnesses`:

```
server:  infantry runner pair --name server-1 --grant-on-pair [--grant-harnesses-max claude,echo]   # no --allow-projects
PC:      infantry runner add '<link>' --project <project-id>   # asks once: Grant runner server-1 access to project <name> (<id>) and harnesses ...? [y/N]
```

The link then carries `grant=1`, and `infantry runner add '<link>' --grant` answers that question for you. The runner adds, never
replaces; a `*` in a request is refused; a used or expired code grants nothing; and no link frame changes grants after pairing.
The grant is printed by `add` and logged on the runner as `runner_link_pair_grant` (`infantry runner audit`). Without the grant
flag the requested ids are ignored and the answer says so, so grants stay the runner's own business.

## 2. On your PC: add the server

Paste the link `runner up` (or the container log) printed. One paste, the whole handshake (RUNNER-ADD-LINK-1):

```
infantry runner add 'infantry://runner?v=1&name=server-1&host=...&port=8790&code=...&fp=...'
infantry runner list
```

Quote the link: an unquoted `&` cuts it short in most shells. `--name other` pairs it under another name, and `--link -`
reads the link from stdin so it need not land in your shell history. The link's hosts are tried in order; a host that does not
answer (`limits.runners.addHostTimeoutSec`, 5 s each) or whose certificate is not the link's fingerprint is skipped — the
code is only sent after the pin matched — and the first host that answers pairs. A PAIR refusal (wrong, expired or burned
code) stops at once. If no host answers, the error lists each host with its reason.

The first successful add on a machine turns `runners.enabled` on (adding is already the owner's consent); detaching a runner
leaves it on.

The card is the same flow with no terminal (RUNNER-ADD-UI-1): Settings > Runners > Add runner, paste the link into the
(masked) link field, give a name only if you want one other than the link's, and press Connect. When the link's code was issued
with `--grant-on-pair` the dialog also shows one tick, ticked, naming the project this window is on: connecting with it grants
that project and the installed harnesses at pairing (RUNNER-GRANT-AT-PAIR-1). The card draws its heading
and Add runner even while runners are off, so it is always findable. A successful add runs the health check
(RUNNER-CHECK-1) in the same dialog and lists its rows: a tick or a cross per row, and under a failure the exact `fix:`
step with a Copy button; Check again runs it again, Close leaves the runner paired, and each runner row's Check opens
those rows again. The CLI face of the rows: `infantry runner check <name> [--project <id>] [--harness <id>]`.

By hand, the same pairing (older installs):

```
infantry runner add server-1 wss://<host from the link>:8790 --code <code from the link> --fingerprint <fp from the link>
infantry runner list
```

The link (`host=`, `port=`, `code=`, `fp=`) holds those values.

Settings > Runners > Add does the same. `runner add` needs the owner credential, so run it in your own shell, not from an agent.

## 3. Pick the runner when you create an agent

New agent > Advanced > Runner: a picker (Project default, local, any, and every paired server). No typing.
For a ticket: `infantry ticket update <id> --runner server-1` (or `ticket create --runner server-1`).

## 4. The project needs a git remote

A runner clones the project, so the project's repository needs an `origin` the server can reach. Without one the start is
refused with: `runner server-1 needs the project's git remote ... has no origin remote`.

## 5. What you must do yourself

- Log the agent CLI in on the server (the runner holds its own credentials): `docker exec -it infantry-runner claude login`
  (claude, opencode and pi are all in the image, on `/usr/local/bin`; `--build-arg INSTALL_CLAUDE=false` leaves claude out, and then `runner check` reports `harness 'claude' is not installed on this runner`).
  Without a login the session starts but the model cannot answer.
- Give the server access to the git remote (a deploy key or token).
- Open the link port to your PC only (firewall or tailnet).

Then check it from your PC: `infantry runner check <name> [--project <id>] [--harness <id>]` (RUNNER-CHECK-1) runs the
check over the link and prints one line per row with the fix under a failing one, and exits 1 when any row fails:

```
contract         ok      this runner speaks contract 1.4.0
harness:claude   FAIL    harness 'claude' is installed but not signed in; a session would start and the model could not answer
                         fix: log claude in on this runner: run `claude` there once and sign in
grant:project    FAIL    my-project is not in this runner's runner.projects (empty = none)
                         fix: on this runner: infantry settings set runner.projects '["my-project"]'
link             ok      reached wss://100.64.0.5:8790 in 42 ms
```

Run each `fix:` line on the runner and check again. A full disk, an unreachable remote and a harness the runner does not
have show up here too, instead of at the first ticket start.

Who a worker on the runner commits as (RUNNER-GIT-IDENT-1, row `git:identity`): every remote start carries the orchestrator's git
identity (the project checkout's `user.name` / `user.email`) and the runner writes it into its clone, so a worker commits as a
local one would and never has to guess a name. To make a runner commit under its own identity instead, set both on the runner:
`infantry settings set runner.gitName '"<name>"'` and `infantry settings set runner.gitEmail '"<email>"'` (blank = use the
orchestrator's). The row fails only when none of these, nor the runner user's own git config, gives an identity.

Green rows say the CLI is installed and signed in; they do not say it ANSWERS. For that, run one throwaway turn on the runner
(RUNNER-TEST-1) — the Runners card's Check view has the same "Test" button:

```
infantry runner test server-1 [--harness <id>] [--model <id>]
```

It starts a session there with no ticket and no project, sends "Reply with the single word: ready", prints the harness, how long
it took and the answer, then stops and removes the session (nothing is left on the runner's session list). Exit code 0 means an
answer came back; otherwise the error and the last transcript lines say why. A test with a real model harness spends one small
turn of that account; `echo` spends nothing.

## 6. Check it ran there

```
infantry ticket start <id>                      # note: "on runner server-1"
docker exec infantry-runner infantry runner audit   # runner_link_start with the ticket id
docker exec infantry-runner infantry pods           # the session id, BUSY
```

## 7. Output of a remote session (RUNNER-E2E-2)

The orchestrator lists a remote session (`infantry sessions`, the board's worker check) and reads its output from the runner when
you ask: `infantry logs <session>` prints the lines the runner kept (STDOUT and STDERR). Nothing streams live
(`runners.transcripts = on-demand`; `stream` is not built yet), so run `logs` after the agent has answered. A new remote session
reads `STARTING` until the runner reports its first status.
No-login check with the Echo harness (run live in RUNNER-E2E-3, see the result below) (`plugins/examples/Acme_EchoHarness`, no credentials): install it on the runner, pin an
agent or ticket with provider `echo` to the runner, prompt it, then `infantry logs <session>` shows the echo reply and the
board shows the worker live, not "gone". The two-daemon test `RunnerPoolPinnedTicketE2ETest` ("a remote session is listed on the
orchestrator and its output is read on demand") proves the same path without Docker.

Live result (RUNNER-E2E-3, Docker runner + `Acme_EchoHarness`, no login). Install the plugin on the runner under
`/data/plugins/community/Acme_EchoHarness` (`module.json`, `icons/`, `harness/build/libs/Acme_EchoHarness-harness.jar`) with
`/data/plugins/modules.json` granting `harness.code`, `harness.setting:echo.prefix`, `harness.cli`, `harness.cli.install`, restart the
runner, `runner pair --allow-projects '*' --allow-harnesses echo`; install the same plugin on the orchestrator (else
`ticket_save refused: harness 'echo' is not installed`). Seen: `ticket start` says `on runner test-runner`; the runner audit has
`runner_link_start`; the orchestrator lists the session (`provider remote`, IDLE) and the board shows it `idle`, not "gone". NOT seen:
the echo output on the orchestrator. The echo ran inside the container (runner `get_session` transcript: `session started (echo)`,
`finished SUCCEEDED`), but orchestrator `infantry logs <session>` printed "no output within 30000ms" and its `get_session` says
"no replay history"; the start did not run a turn on its own (the session stayed IDLE) and `infantry_prompt` on the orchestrator
answers `unknown session` for a remote session. Open: ticket RUNNER-E2E-3 comment.

RUNNER-REMOTE-IO-1 (what changed after that run): the runner's transcript read now serves the session's turns (`[turn] ...`), raw
process lines, errors, and, for a harness that printed no raw line, its answer text (`RunnerTranscript`), so `infantry logs <session>`
on the orchestrator is not empty for any harness. A `prompt` frame (`infantry_prompt`) for a session on a remote runner is forwarded
to that runner like mail (a busy or unreachable runner answers an error to send again, not "unknown session"), and the runner
serves the transcript and mail of a session started without a git remote too (`isLinked`). `session_act` (queue, interrupt) is not
forwarded yet. Not yet proven live: the Docker re-run (needs the board plugins on the orchestrator).

VERIFY-RUNNER-IO-1 (live, a second LOCAL runner process, not Docker; the Docker packaging stayed unverified, the engine was down): orchestrator
(`--mode both`, Board/Attention/Roles/Agents/RemoteRunner plugins) and a `--mode runner` daemon, each on its own 189xx port and temp state dir, both
with `Acme_EchoHarness`, paired with `runner pair` / `runner add`. A ticket pinned `--runner r2 --provider echo` started on the runner; the
orchestrator and the runner both listed the session; `infantry logs <full session id>` on the orchestrator printed the turn and the
`echo:` lines read from the runner; `infantry_prompt` on the orchestrator reached the runner (its transcript has `[turn from owner] ...`) and a
second `logs` printed the `echo: <prompt>` reply. `logs` now sends one `replay_request` up front (before, it was a live tail only and printed
nothing for a remote session). Notes: the full session id is needed (a prefix gets "no replay history"), the reply is read on demand, not streamed.

## Docker Desktop on Windows (RUNNER-LINK-HOST-1)

A container's port is reached from the PC on the port Docker **published**, not on 8790, and `host.docker.internal` only
resolves inside a container. Publish a port, then tell the runner which host and port the PC should use:

```
docker run -d --name efd-runner2 -e INFANTRY_MODE=runner \
  -e INFANTRY_RUNNER_NAME=efd2 -e INFANTRY_RUNNER_HOST=localhost -e INFANTRY_RUNNER_PUBLIC_PORT=18921 \
  -e INFANTRY_RUNNER_ALLOW_PROJECTS='<project-id>' -e INFANTRY_RUNNER_ALLOW_HARNESSES=claude \
  -p 18921:8790 -v infantry-data:/data infantry-runner
docker logs efd-runner2            # the link must read host=localhost&port=18921
docker ps                          # PORTS: 0.0.0.0:18921->8790/tcp
```

`INFANTRY_RUNNER_PUBLIC_PORT` is the `--port` of `infantry runner pair|up` (a new pairing:
`docker exec -it efd-runner2 infantry runner pair --name efd2 --host localhost --port 18921`). Paste the link into Settings >
Runners > Add runner (or `infantry runner add '<link>'`). The Name field takes 1-32 of a-z, 0-9 and `-` (e.g. `docker-runner`;
no spaces or capitals) and shows the rule as you type.

If the add says "no host of the link answered", it lists what to try: the host:port pair of the link, `docker ps` for the
published port, and `localhost` + that port. The container log warns when `INFANTRY_RUNNER_HOST` is `host.docker.internal`, or
when a host is set without `INFANTRY_RUNNER_PUBLIC_PORT`.
