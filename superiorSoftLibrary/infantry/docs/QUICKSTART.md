# Infantry owner quickstart

Run the daemon and the desktop UI on this machine, drive a Claude agent, a pi agent and a JSON-descriptor
harness. Every command below was checked against the Gradle files and sources named in the last section.

## 1. Build and start

Run from the repository root (JDK 21; the modules use `jvmToolchain(21)`).

```
./gradlew :superiorSoftLibrary:infantry:runDaemon      # terminal 1: the daemon, ws://127.0.0.1:8787/ws
./gradlew :superiorSoftLibrary:infantryUi:runUi        # terminal 2: the desktop UI (alias of `run`)
```

- `runDaemon` runs `com.superiorsoftllc.infantry.daemon.DaemonMainKt`. It prints `listening on ws://...` and
  its access policy, then blocks. The UI connects to `ws://127.0.0.1:8787/ws` by default.
- `:superiorSoftLibrary:infantryUi:runRealDaemon` starts the daemon in-process and opens the UI on it (one terminal).
  Its provider comes from `INFANTRY_PROVIDER`, default `opencode`. It listens on 8788 unless `INFANTRY_PORT` is set.
- `:superiorSoftLibrary:infantryUi:runSmoke` opens the UI against an in-process STUB daemon with temporary state:
  use it to check the UI works before touching real CLIs.
- Other entry points: `:superiorSoftLibrary:infantry:runClient` (CLI client), `installDist` (an `infantry` binary).

### Ports: one fixed port per use

| Port | Who binds it |
|---|---|
| 8787 | The owner's daemon: `runDaemon` (`gradle run` of the daemon). |
| 8788 | The owner's UI preview: `runRealDaemon`. |
| 0 (ephemeral) | Every test, gate, QA scenario and probe. No test may bind 8787 or 8788 (`TestPortGuardTest` fails the build). |

**A taken port does not stop the daemon.** When its port is in use, the daemon binds the next free one (up to 10
above it, else one the OS assigns) and prints one line:
`[infantry] port 8788 in use (pid 1234); listening on 8789 instead`. The ready line
(`[infantry] listening on ws://127.0.0.1:8789/ws`) and the file `<state dir>/daemon.port` carry the port actually
bound; the file is deleted on clean shutdown. `runRealDaemon` points its window at that port itself, and the
standalone UI (`runUi`) reads `daemon.port` when `INFANTRY_WS_URL` is unset (no file = 8787).

Set `INFANTRY_PORT_STRICT=true` to keep the old fail-fast behaviour: the daemon exits with code 2 and one line
(`refusing to start: port 8788 in use (pid 1234) and INFANTRY_PORT_STRICT=true forbids a fallback. ...`), before it
opens its database.

Two daemons on the SAME state dir share its database, sessions and COMMS: a fallback port does not isolate them.
Give a second daemon its own `INFANTRY_STATE_DIR`.

## 2. Environment variables and config dir

Set them in the shell that starts the daemon.

| Variable | Meaning |
|---|---|
| `INFANTRY_PROVIDER` | The daemon's default provider: `opencode`, `claude` or `stub` (no CLI needed). |
| `INFANTRY_PROVIDERS` | Legacy configured-provider path only: extra providers a spawn may name (`opencode`, `claude`, `stub`), e.g. `stub`. Not needed for `claude` or `pi`, which spawn from the harness registry by default. An unknown name stops the daemon. |
| `INFANTRY_PORT`, `INFANTRY_HOST` | Listen port (default 8787; a taken one falls back to a free port, see "Ports") and host (default `127.0.0.1`). A non-loopback host warns: use a token. |
| `INFANTRY_PORT_STRICT` | `true` = a taken `INFANTRY_PORT` stops the daemon (exit 2, one readable line) instead of falling back. |
| `INFANTRY_TOKEN` / `INFANTRY_TOKEN_FILE` | Bearer token. The UI reads `INFANTRY_WS_URL` and the same token variables. Note: the daemon-side UI uses `INFANTRY_WS_URL`, while the CLI client (`runClient`) uses `INFANTRY_URL`. |
| `INFANTRY_REPO` | Git repo the daemon makes per-session worktrees in; unset = other sessions share the daemon's directory, while a ticket start still cuts its own `ticket/` branch in the main checkout of the git repository that directory is in (the boot line names it). |
| `INFANTRY_BASE_BRANCH` | The daemon-wide ref a ticket start cuts its branch from when the ticket's epic and project set no base (`infantry epic base`, `infantry project base`, or the Git tab): its commit only, so uncommitted changes never refuse a start. Unset and no epic/project base: the start is refused (BUG-BASE-087), never cut from `HEAD`. |
| `INFANTRY_STATE_DIR` | State dir, default `~/.infantry` (sessions, database). |
| `INFANTRY_DB` | Overrides the database file location (default: inside the state dir). |
| `INFANTRY_HOME` | Config dir; defaults to the state dir. Holds `commands.json` and `harnesses/`. |

**Descriptors live in `<config dir>/harnesses/*.json`** (so `~/.infantry/harnesses/` by default). A missing
directory is simply no descriptors. Files are read once at daemon start: restart the daemon after editing.

pi is a built-in harness plugin (found by ServiceLoader), so it needs no variable: it appears in the picker
beside the providers. The `pi` and `claude` executables must be on the PATH of the daemon's shell.

## 3. Spawn Claude and pi, and the slash commands

1. Start the daemon normally. `claude` and `pi` are harness-registry entries and appear in the picker by default (only `CliHarness`-based harnesses spawn); `INFANTRY_PROVIDERS` is not needed.
2. In the UI open the **New agent** box (`^N` is "spawn mode"). Pick the provider (`claude` or `pi`) and the
   model from the pickers, type the first prompt, Spawn. For claude and pi the picker shows no model list and no default model.
3. Typing `/` in the prompt box offers the harness's commands. A prompt starting with `/` is never sent as
   a prompt; it needs an existing session and starts none. There is no close frame: "kill" only stops a running turn.

**The commands below exist for claude ONLY.** pi has no slash commands yet: any `/x` there errors with `known commands: (none)`.

| Command | Does |
|---|---|
| `/model <id>` | The session's *next* run uses that model. No run starts. |
| `/effort <level>` | Level must be one the provider lists (claude: `--effort`); else an error lists them. Applies to the next run. |
| `/compact` | Hands the harness's own `/compact` to it as the next turn on the same conversation. The daemon summarises nothing. Refused while a run is in flight or before the first turn. |

## 4. Add a harness with a JSON descriptor

Create `<config dir>/harnesses/mycli.json`. Minimal entry (same shape as the `HarnessDescriptorTest` fixture):

```json
{"id": "mycli", "name": "My CLI", "command": "some-cli", "args": ["{prompt}"],
 "capabilities": [], "output": {"format": "text"}}
```

- A file holds one entry or an array of entries.
- Windows hint for a harmless test: `"command": "cmd", "args": ["/c", "echo", "{prompt}"]`.
- `args` is a template: `{prompt}` is replaced; a nested array such as `["--model", "{model}"]` is dropped as a group
  when its placeholder (`{model}`, `{sessionId}`, `{effort}`) has no value.
- `capabilities` may list `MODEL_SELECTION`, `SESSION_RESUME`, `EFFORT_SELECTION`, `USAGE`, `TOOL_CALLS`, `REASONING`.
- `output.format` is one of:
  - `text`: every stdout line is reply text.
  - `claude`: the CLI prints Claude's stream-json; `pi`: the CLI prints pi's JSON events (both decoded by the built-in translators).
  - `jsonl`: you map your own JSON lines. Each rule matches keys and emits one event:

```json
{"id": "jl", "name": "J", "command": "tool", "args": [], "capabilities": ["USAGE"],
 "output": {"format": "jsonl", "map": [
   {"match": {"t": "msg", "role": "assistant"}, "event": "text", "fields": {"text": "body.text"}},
   {"match": {"t": "end"}, "event": "completion", "fields": {"reason": "why"}}
 ]}}
```

  Events: `session`, `text`, `reasoning`, `tool`, `tool_result`, `usage`, `completion`, `error`. `fields` values are dotted paths into the line.

- `models` (optional, ROLE-FIRST S5) fills the model picker for this harness through the existing `models` frame
  (rows `provider` = the harness id, `group` = the upstream provider). Exactly one of:

```json
"models": {"list": [{"provider": "openai", "id": "openai/gpt-5", "label": "OpenAI"},
                    {"provider": "local",  "id": "local/llama"}]}
"models": {"modelsCommand": ["my-cli", "--list-models"]}
```

  - `list`: `provider` and `id` are required tokens (no spaces); `label` is optional and names the provider group
    (first one wins). A duplicate `(provider, id)` or more than 2000 entries rejects the descriptor (`INVALID_DESCRIPTOR`).
  - `modelsCommand`: an argv list, never a shell; an element holding `; & | < > $ % ^ * ? ' "`, a backtick, CR or LF
    rejects the descriptor. It runs bounded (20 s timeout, 1 MiB stdout, stdin closed, result cached 30 s); stdout is
    one `provider/model` per line, other lines are skipped, at most 2000 kept. A failure or an empty listing is ONE
    row with a note (`could not list models: ...`), never the command's output.
  - No `models` section: the harness has no catalog (free-text model field), as before.
  - No contract change: no `HARNESS_CONTRACT_VERSION` bump (the descriptor has no schema version and no test pins one).

## 5. What is NOT proven yet

- `RealCliSmokeTest` exists (run with `INFANTRY_REAL_CLI=1`). claude is proven end to end; pi's `text_delta` is proven only after the BUG-HARD-2 fix (`message_update` in `EventNormalizer`).
- OpenCode lists no slash commands (no verified flags): every `/command` there is "unknown".
- The Android git view is not compiled.
- Descriptor harnesses are tested with an echo fixture, not with a real third-party CLI.

## 6. Troubleshooting

1. **UI says reconnecting / daemon not reachable**: is `runDaemon` still printing `listening`? Check the port it printed (it may have fallen back, see "Ports"; `<state dir>/daemon.port` holds it) and that the UI's `INFANTRY_WS_URL`, if set, matches; a 401/403 means `refused` (token mismatch), which is not retried.
2. **Daemon exits with "refusing to start"**: the message names the access setting to change (non-loopback host without a token).
3. **Provider missing from the picker**: `claude`, `pi` and descriptor harnesses come from the harness registry: check the daemon's stderr `[harness]` lines and that the CLI is on PATH, then restart. A descriptor harness appears in the picker even if its command is not on PATH; a missing command shows as a FAILED session at spawn. `opencode` and `stub` need `INFANTRY_PROVIDER` or `INFANTRY_PROVIDERS`.
4. **Descriptor ignored**: stderr prints `[harness] descriptor <file>: <REASON>`. Reasons: `UNREADABLE`, `INVALID_JSON`, `INVALID_DESCRIPTOR` (detail names the broken rule), `DUPLICATE_DESCRIPTOR_ID`, `CODE_PLUGIN_WINS` (your id matches a built-in such as `claude` or `pi`: rename it), `REFUSED`.
5. **Daemon refuses to boot with "Unknown INFANTRY_PROVIDERS entry"**: only `opencode`, `claude`, `stub` are valid there; pi and descriptor harnesses are never listed in that variable.

## Files checked

`infantry/build.gradle.kts`, `infantryUi/build.gradle.kts`, `infantryUi/README.md`, `daemon/DaemonMain.kt` (`main`, `daemonConfigDir`, `loadHarnesses`),
`provider/ProviderFactory.kt`, `session/SessionManager.kt` (`lookup`, `resolveProvider`), `session/SessionStore.kt` (`stateDir`), `harness/descriptor/HarnessDescriptorLoader.kt`,
`harness/descriptor/HarnessDescriptorTest.kt`, `harness/pi/PiHarnessPlugin.kt` (exists), `CONTRACT.md` (1.38.0 / 1.39.0 sections).
