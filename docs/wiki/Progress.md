# Progress and downloads

CORE-PROGRESS-DOWNLOAD-1. The app has ONE progress/loader system and ONE downloader. Every feature and plugin uses them;
a plugin may replace how progress is drawn and where the bytes come from, never the mechanism. Do not write a progress
bar or a download loop of your own: `DownloadProgressGuardTest` fails the build on a new one.

| You need | Use |
|---|---|
| Show that something is running (steps, %, bytes, ETA, Cancel, Retry) | `api.progress` on the daemon (`DaemonApi`), `controller.progressUi` / `LocalProgress` in the UI, `progress.start` / `progress.update` from Lua |
| Draw a bar | `ProgressBar(fraction)`; a whole task: `ProgressView(task)`; a task by id: `ProgressIndicator(taskId)` |
| Download a file | `api.downloads.download(DownloadRequest(...))` on the daemon, `http.download` from Lua |
| Call a JSON API | not the downloader: `http.fetch` from Lua, `Services.ENDPOINTS` in the UI |

## The progress system

Source: `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/Progress.kt`
(the types) and `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/host/ProgressCenter.kt`
(the one implementation; the daemon and the UI each run one).

A task (`ProgressTask`) has an `id`, the `source` module that started it, a `title`, a `state` (`ProgressState`: queued,
running, done, failed, cancelled), an amount (`done` of `total` in `unit`; `total = -1` is an indeterminate bar), a `step`
label with its place in the `steps` list, an `etaMs`, whether it can be cancelled or retried, an `error`, and an optional
`link` (where a client takes the user when it is clicked).

```kotlin
val task = api.progress.start(ProgressSpec("Indexing docs", steps = listOf("Scanning", "Indexing"), cancellable = true,
    retry = { reindex() }))
task.step("Scanning")
for ((i, f) in files.withIndex()) {
    if (task.cancelRequested) { task.cancelled(); return }
    task.amount(i.toLong(), files.size.toLong(), "files")
}
task.step("Indexing")
task.done()                                 // or task.fail("index is corrupt"), which offers Retry
```

- `ProgressHandle` is the starter's side: `step`, `amount`, `eta`, `done`, `fail(reason, retryable)`, `cancelled`,
  `cancelRequested`, `onCancel { }` (runs when the user presses Cancel). One live task per id: starting an id that is
  still running returns the same task.
- `Progress` is everyone's side: `tasks()`, `task(id)`, `observe { }`, `cancel(id)`, `retry(id)` (runs the `retry` the
  starter gave in its `ProgressSpec`; only for a failed or cancelled task), `present(id, presenter)`.
- Finished tasks are kept for `limits.progress.keepFinished`; amount changes are published at most once per
  `limits.progress.minIntervalMs` (steps and the end always at once).
- A failed task is reported: the daemon publishes the named event `progress.failed` (payload: the task) and logs it; a
  client shows an error toast that points at Retry in the running-tasks list. Moving this onto the notification bus is a
  follow-up of NOTIFY-CORE-1.

### On the wire

The daemon pushes every change to every client as the frame `progress_task` (the task as JSON, `ProgressWire.PUSH`),
and replays the live and kept tasks on connect. A client sends `progress_cancel` / `progress_retry` with `{"id": ...}`
(`ProgressWire.CANCEL`, `ProgressWire.RETRY`; owner connections only). The client mirrors the daemon's tasks into its own
`ProgressCenter` (`mirror`), so the UI reads one list for daemon-side and UI-side tasks.

### Drawing it

The core default presenter is in
`superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/progress/ProgressViews.kt`:

- `ProgressIndicator(taskId)`: an inline indicator a screen binds to a task id (nothing until the task exists).
- `RunningTasks`: the global running-tasks list, drawn by the shell over the window (bottom left). Clear forgets the
  finished tasks; Hide folds it.
- `ProgressView(task)`, `CoreProgressView`, `ProgressBar(fraction)`: one task, the core drawing, THE bar.

**A plugin's drawing.** Implement `ProgressRenderer` and register it with
`implementation(PROGRESS_RENDERER_REGISTRY, "<your module>", renderer)` (declare it under `implementations` in
`module.json`). The last one registered draws every task, inline and in the list. It runs inside `GuardedBlock`: a
renderer that throws while composing is reported as that module's fault and the core drawing takes its place.

**A daemon-side presenter.** `api.progress.present("", presenter)` registers a `ProgressPresenter` under your module id
(a tray icon, a status LED, a remote dashboard). The setting `progress.presenter` names the one to use (blank = the last
registered). It replaces the core default (the broadcast to clients); one that throws is reported and the default is
used for that change. Unloading the plugin removes it.

## The downloader

Source: `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/downloads/Downloads.kt`
(the API) and `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/downloads/CoreDownloads.kt`
(the implementation, with `HttpDownloadTransport` as the core transport).

```kotlin
val r = api.downloads.download(DownloadRequest(
    url = "https://example.com/model.bin", dest = File(dir, "model.bin"), title = "Downloading the model",
    secretHeaders = mapOf("Authorization" to "Bearer $token"), sha256 = expectedSha,
))
if (!r.ok) log("${r.error} (retryable: ${r.retryable})")
```

What every download gets:

- HTTP(S) GET with your `headers`; `secretHeaders` are never logged, never put in an error text, and sent only to the
  first host (not after a redirect to another host). An error text cuts the URL before its query.
- The proxy from the setting `downloads.proxy` (`host:port`), else the system proxy.
- A temp file next to the target (`<name>.part`), then an atomic move over the target; while Windows holds the target
  open, the move is retried (`limits.downloads.moveRetries`).
- Retries on a broken connection, HTTP 408, 429 or a 5xx: `limits.downloads.retries` times, waiting
  `limits.downloads.backoffMs` doubling each time (a server's `Retry-After` wins), resuming from the bytes already there
  with `Range` (a server that refuses the range starts over).
- Timeouts: `limits.downloads.connectTimeoutSeconds`, `limits.downloads.responseTimeoutSeconds`, and
  `limits.downloads.stallSeconds` (no byte for that long closes the connection and retries).
- A size cap: the request's `maxBytes`, else `limits.downloads.maxMb`; a sha256 check (`sha256 mismatch for <file>:
  expected ..., got ...`, retryable).
- Progress: a task with the steps Downloading and Verifying, bytes against the size, Cancel (closes the connection and
  deletes the temp file) and Retry. Pass your own `progress` handle to fold the download into a larger task.

`DownloadResult` says `ok`, the `file`, `bytes`, its `sha256`, the `attempts`, the last HTTP `status`, and on failure the
`error`, whether it is `retryable`, and whether it was `cancelled`.

**A plugin's transport.** `api.downloads.transport("", transport)` registers a `DownloadTransport` under your module id
(a mirror, a cache, a CDN, an offline bundle). It only opens bytes (`DownloadResponse`); retries, resume, the cap, the
hash, the temp file and progress stay in core around it. The setting `downloads.transport` names the one to use (blank
= the core transport). Throw an IOException for a failure worth a retry; anything else is your module's fault and the
download falls back to the core transport.

## From Lua

The Scripting plugin declares three capabilities (see [Scripting](Scripting.md)):

```lua
-- ih.call only queues; each answer is in input.results[i].json on the next phase (after ih.again()).
if not input.results then
  ih.call("progress.start", {title = "Mirroring docs", steps = {"Fetching", "Unpacking"}})
  ih.again()
elseif #input.results == 1 then
  local t = input.results[1].json
  ih.call("progress.update", {id = t.id, step = "Fetching"})
  ih.call("http.download", {url = "https://example.com/docs.zip", dest = "mirror/docs.zip",
    sha256 = "...", secretHeaders = {Authorization = "docs-token"}, progressId = t.id})
  ih.again()
else
  local t, r = input.results[1].json, input.results[3].json
  ih.call("progress.update", {id = t.id, state = r.ok and "done" or "failed", error = r.error})
end
```

## Where it is used

- Plugin install, update, rollback and remove (`PluginManager`): one task each; the catalog and jar downloads go
  through the core downloader.
- The updater's release downloads (`Infantry_Updater`, through the update engine's release source).
- The Settings plugins screen draws its strip with `ProgressBar`.

Not moved yet (each a backlog ticket; `DownloadProgressGuardTest` names them with their reason): the harness installer,
the voice plugin store, the Android self-update, the CLI update path, the update prompt and server update bars, and a few
spinners.
