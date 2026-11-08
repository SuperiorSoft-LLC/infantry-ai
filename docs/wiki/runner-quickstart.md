# Runner quickstart: run an agent on your own server

Proven end to end on one machine (RUNNER-E2E-1): a Docker runner paired to an orchestrator, a ticket pinned to that runner,
the session started on the runner. Design: [RUNNER-POOL](../RUNNER-POOL.md). Server setup: [Run a remote environment](run-a-remote-environment.md).

Words: the **orchestrator** is the daemon with your board (your PC); the **runner** is the daemon on the server that runs the agent CLIs.

## 1. On the server: start the runner

Use the project's runner image (or build it: `./gradlew :superiorSoftLibrary:infantry:dockerContext`, then
`docker build -t infantry-runner superiorSoftLibrary/infantry/build/docker`). Publish the link port; mount nothing of your PC.

```
docker run -d --name infantry-runner -p 8934:8934 -e INFANTRY_LIMIT_RUNNERS_LINK_PORT=8934 infantry-runner
docker exec infantry-runner infantry settings set runners.enabled true
docker exec infantry-runner infantry settings set runner.projects '["*"]'
docker exec infantry-runner infantry settings set runner.harnesses '["*"]'
docker exec infantry-runner infantry runner pair --name server-1
```

`runner.projects` / `runner.harnesses` default to `[]` (nothing may start remotely): set them or every start is refused.
`pair` prints a one-time code and the certificate fingerprint and keeps serving until the code is used (10 minutes).

## 2. On your PC: add the server

```
infantry runner add server-1 wss://<server-address>:8934 --code <code> --fingerprint <fingerprint>
infantry runner list
```

Settings > Runners > Add does the same. `runner add` needs the owner credential, so run it in your own shell, not from an agent.
Enable `runners.enabled` on the PC too.

## 3. Pick the runner when you create an agent

New agent > Advanced > Runner: a picker (Project default, local, any, and every paired server). No typing.
For a ticket: `infantry ticket update <id> --runner server-1` (or `ticket create --runner server-1`).

## 4. The project needs a git remote

A runner clones the project, so the project's repository needs an `origin` the server can reach. Without one the start is
refused with: `runner server-1 needs the project's git remote ... has no origin remote`.

## 5. What you must do yourself

- Log the agent CLI in on the server (the runner holds its own credentials): `docker exec -it infantry-runner claude login`
  (Claude is built in only with `--build-arg INSTALL_CLAUDE=true`; opencode and pi are always in the image).
  Without a login the session starts but the model cannot answer.
- Give the server access to the git remote (a deploy key or token).
- Open the link port to your PC only (firewall or tailnet).

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
No-login check with the Echo harness (not yet run live: only the in-process test below proves it) (`plugins/examples/Acme_EchoHarness`, no credentials): install it on the runner, pin an
agent or ticket with provider `echo` to the runner, prompt it, then `infantry logs <session>` shows the echo reply and the
board shows the worker live, not "gone". The two-daemon test `RunnerPoolPinnedTicketE2ETest` ("a remote session is listed on the
orchestrator and its output is read on demand") proves the same path without Docker.
