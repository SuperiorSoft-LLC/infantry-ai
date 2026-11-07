# Job Feed (optional plugin)

`Infantry_JobFeed` shows the live output of a watched command (gradle, tests, builds) in a collapsible box under the chat.
It is OFF until you enable it in Settings > Plugins (`"defaultEnabled": false` in its `module.json`). Core and the shell
name nothing of it (`JobFeedGuardTest`).

## How it works (pull, no stream)

- Core adds only a generic seam: `DaemonApi` 1.49.0 `Sessions.jobs(id)` and `Sessions.jobLog(id, jobId, fromOffset, maxBytes)`
  (a bounded tail read of a background job's log). No second streaming path, no timer, no subscriber list.
- The plugin's daemon half answers two routes, `POST /plugins/Infantry_JobFeed/jobs` and `/read`.
- The UI half fills the slot `chat.jobfeed` (declared by `Infantry_Chat`). The box asks for new lines only while it is open,
  not paused, and the window is focused. Closed box or plugin off = no requests, nothing rendered.
- Title: command, status (running / done exit 0 / failed exit N), elapsed time. Failure-marker lines are highlighted.
  A neutral notice appears after 5 minutes of silence. Several jobs: the newest is shown, the others are a small list.

## Settings

| Setting | Default | Off / change |
|---|---|---|
| `jobfeed.maxUpdatesPerSecond` | 4 | 1..20 |
| `jobfeed.maxLines` | 300 | 10..5000 |
| `jobfeed.lowPower` (1 update/s, 100 lines) | off | checkbox |
| `jobfeed.autoCollapseUnfocused` | on | uncheck to keep reading when unfocused |
| `jobfeed.openByDefault` | off | checkbox |
| `jobfeed.failureMarkers` | `FAILED\|error:\|Exception\|BUILD FAILED\|FAILURE` | empty = no highlight |
| `jobfeed.quietNoticeSeconds` | 300 | 0 = no notice |
| `jobfeed.minIntervalMs` (daemon rate cap per job) | 200 | 0 = no cap |
| `jobfeed.maxLineChars` | 300 | 0 = never cut |
| `jobfeed.stripAnsi` | on | uncheck |
| `jobfeed.maxTracked` | 200 | rate-table size |
| `limits.jobs.remembered` (core) | 5 | ended jobs kept per session |
| `limits.jobs.maxReadBytes` (core) | 65536 | most bytes per read |

## Measured cost

Not measured yet: the noisy 20k-lines/minute run (open vs closed vs plugin off) was not executed in this ticket.
By construction the closed and off cases make no requests, and an open box costs at most one bounded read per poll interval.

## Supplying your own box

Another plugin can fill the same slot: declare `panels: [{ "id": "<yours>", "panel": "chat.jobfeed" }]` in its `module.json`
and register a `PanelProvider` for it. The daemon seam (`Sessions.jobs` / `jobLog`) is available to any daemon plugin.
