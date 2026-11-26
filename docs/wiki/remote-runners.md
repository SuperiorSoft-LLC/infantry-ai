# Remote runners

A **runner** is a machine where agents run. Your PC is the **local** runner. A remote runner is another machine, such
as a server, that your app sends agents to.

## Start a runner

The easiest way is [Docker](self-host-docker.md). When a runner starts, it prints a block like this:

```
==== INFANTRY RUNNER <name> ====
endpoint: ...
bearer: irb1....
allow: ...
```

The **endpoint** is its address. The **bearer** is its password. The bearer is printed again on every start.

## Add it to the app

1. Open **Settings > Runners**.
2. Click **Add runner**.
3. Paste the **endpoint** and the **bearer**.

The runner shows up in the list with its status. Each runner has three actions:

| Action | What it does |
|---|---|
| Check | Test the connection. |
| Rotate | Make a new bearer. The old one stops working. |
| Detach | Remove the runner from the app. |

Now pick the runner in **New agent**, or pin it on a ticket.

## Security

The link is encrypted. The bearer holds the runner's certificate fingerprint, so the app only talks to that runner.
If another app pairs with the same bearer, it takes the runner over. Use **Rotate** if a bearer leaks.

## Runner settings

| Variable | Default | Meaning |
|---|---|---|
| `INFANTRY_RUNNER_LINK_PORT` | 8790 | Port the runner listens on. |
| `INFANTRY_RUNNER_BIND` | 127.0.0.1 (0.0.0.0 in Docker) | Address the runner listens on. |
| `INFANTRY_RUNNER_HOST` | localhost | Address the app should dial. Set it to the server's address. |
| `INFANTRY_RUNNER_PUBLIC_PORT` | the link port | Port the app should dial, if you map a different one. |
| `INFANTRY_RUNNER_NAME` | | The runner's name. |
| `INFANTRY_RUNNER_ALLOW_PROJECTS` | `*` | Which projects may run here. |
| `INFANTRY_RUNNER_ALLOW_HARNESSES` | `*` | Which harnesses may run here. |
