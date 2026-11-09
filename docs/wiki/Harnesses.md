[← Wiki index](index.md)

# Harnesses and providers

A **harness** is the adapter between Infantry AI and one agent CLI (Claude Code, pi, opencode, or your own). Its id
is the **provider** you pin on a ticket or role (`claude`, `pi`, `opencode`). Inside a harness, the model catalog
groups models by their **upstream provider** (for example pi lists `anthropic/claude-sonnet-5-5`). List both with
`infantry harnesses` or the MCP tool `infantry_list_harnesses`.

There are two ways to add one:

1. **A JSON descriptor**, no code (good for a CLI that prints text or JSON lines).
2. **A harness plugin**, a jar implementing the harness API (full control: persistent sessions, approvals,
   stats, usage).

## How the model catalog is answered

A harness lists its own models (`claude` is a fixed list; pi shells out to its `--list-models`, opencode reads its config
files and falls back to `opencode models`), and a cold CLI can take tens of seconds -- longer than a caller waits. So the
daemon keeps every harness's last good list for its whole life (`ModelCatalogCache` in
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/provider/ModelCatalogCache.kt`): a
request is answered from the cache at once and an old list is refreshed in the background, a harness that has never
listed its models is waited for at most `limits.models.catalogWaitMs` (default 4 s) before the answer says it is still
listing, and one slow harness never empties another's list. The note on such a harness says what happened and how to pin
a model id by hand; `limits.models.catalogTtlMs` (default 5 min) says how old a served list may be.

## 1. JSON descriptor (no code)

Drop a file into `<config dir>/harnesses/` (default `~/.infantry/harnesses/`; set by `INFANTRY_HOME`). Samples ship in
`superiorSoftLibrary/infantry/docs/harnesses/` (`codex.json`, `gemini.json`).

```json
{
  "id": "codex", "name": "Codex CLI", "command": "codex",
  "args": ["exec", "--json", "--skip-git-repo-check", "{toolApproval}", ["--model", "{model}"], "-"],
  "capabilities": ["MODEL_SELECTION", "TOOL_CALLS", "USAGE"],
  "output": { "format": "jsonl", "map": [
    {"match": {"type": "thread.started"}, "event": "session", "fields": {"id": "thread_id"}},
    {"match": {"type": "item.completed", "item.type": "agent_message"}, "event": "text", "fields": {"text": "item.text"}},
    {"match": {"type": "turn.failed"}, "event": "error", "fields": {"message": "error.message"}}
  ]},
  "toolApproval": { "deny": [], "allowEdits": ["--sandbox", "workspace-write"], "allowAll": ["--dangerously-bypass-approvals-and-sandbox"] }
}
```

- `args` is a template. `{prompt}`, `{model}`, `{sessionId}`, `{effort}`, `{toolApproval}` are replaced; a nested
  array like `["--model", "{model}"]` is dropped when its placeholder has no value.
- `capabilities`: `MODEL_SELECTION`, `SESSION_RESUME`, `EFFORT_SELECTION`, `USAGE`, `TOOL_CALLS`, `REASONING`.
- `output.format`: `text`, `claude`, `pi` (built-in decoders) or `jsonl` with `map` rules. Events:
  `session`, `text`, `reasoning`, `tool`, `tool_result`, `usage`, `completion`, `error`. `fields` values are dotted
  paths into the line.
- `models` (optional) fills the model picker: `{"list": [{"provider": "openai", "id": "openai/gpt-5"}]}` or
  `{"modelsCommand": ["my-cli", "--list-models"]}` (argv only, no shell; one `provider/model` per line, 20 s timeout).
- `toolApproval` maps each approval mode (`deny`, `allowEdits`, `allowAll`) to the CLI's flags. Audit it: it is how
  the owner's approval choice reaches the CLI.

Quick test: `"command": "cmd", "args": ["/c", "echo", "{prompt}"]` on Windows. See
`superiorSoftLibrary/infantry/docs/QUICKSTART.md` section 4.

## 2. The harness API (plugin)

The only thing a harness jar compiles against is the module `infantryHarnessApi`
(`com.superiorsoftllc.infantry.harness.api`). It depends on the Kotlin stdlib only. The version is semver;
`HARNESS_API_VERSION` in the source is **2.15.0** (`HarnessContractGuardTest` fails the build when a repo harness module declares another). A plugin states the version it was built
against in `contractVersion`; the daemon refuses a different major or a newer minor. Minor bumps only add members
with defaults, so an old plugin keeps working.

Core types:

- `HarnessPlugin` (`pluginId`, `contractVersion`, `register(registrar)`, `unload()`), registered through a
  `META-INF/services/com.superiorsoftllc.infantry.harness.api.HarnessPlugin` file.
- `Harness`: `id`, `capabilities`, `process(request): HarnessProcess` (argv, env, working dir; the daemon launches,
  sandboxes, gates and kills it), `newTranslator()` (a `HarnessTranslator`: `translate(line)` to `HarnessEvent`s,
  `terminal(exitCode, stderrTail)`).
- `HarnessRequest`: prompt, model, session id, effort, `toolApproval`, `contextLimit`, project.
- `HarnessHost` (from `registrar.host`, never a static): `stateDir` (your own data folder), `setting(key)` (declared
  keys only), `resolveCli(tool)`, `start(process, listener)`, `mcpLaunch`, `watchLauncher`, `queryDb`, `ask`, `log`.
  Nothing else of the daemon is reachable.

### Contributions (all optional, default no-op)

| Contribution | Since | Member | module.json `harness.hooks` |
|---|---|---|---|
| Context limit / reasoning effort | 2.1 / 2.2 | `contextLimit`, `effort` | none |
| Tool denial event | 2.3 | `HarnessEvent.Denial` | none |
| Usage (plan/quota meters) | 2.4 | `usageSource()` | `usage` |
| Stats from the CLI's own store | 2.5 | `stats()` | `stats` |
| Watched tool calls | 2.6 | `watch(env, launcher)` | `watch` |
| Sandbox dirs and env names | 2.7 | `sandbox(dirs)` (daemon still checks every path) | `sandbox` |
| Persistent session adapter | 2.8 | `session()` (one process kept between turns) | `session` |
| Model catalog | 2.8 | `models()` (picker groups) | `models` |
| Prompt-cache usage buckets | 2.9 | `HarnessEvent.Usage.cacheReadTokens/cacheWriteTokens` | none |
| Approvals (permission mode argv) | 2.10 | `approvalArgs(ToolApproval)` | none |
| Slash commands | 2.10 | `commands()` | none |
| MCP tool allow rule | 2.10 | `mcpToolRule(serverName)` | `mcp` |
| Config merge | 2.11 | `mergeConfig(existing, ConfigParts(mcp, watchPlugin))` | `config` |
| Non-stdio sessions (HTTP/SSE) | 2.12 | `SessionOutput.output/diagnostic/requestApproval`, `HarnessSession.connect(out, launched)` | `session` |

A community plugin must be granted each hook in `modules.json` (`harness.hook.<name>`, plus `harness.code`,
`harness.cli`, `harness.cli.install`, `harness.setting:<key>` as needed).

### The `harness` block in module.json

From `Infantry_Pi`:

```json
"harness": {
  "jar": "harness/build/libs/Infantry_Pi-harness.jar",
  "contract": "2.15.0",
  "ids": ["pi"],
  "cli": { "id": "pi", "command": "pi", "label": "Pi" },
  "settings": [],
  "hooks": ["watch", "sandbox", "session", "models"]
}
```

`ids` are the provider ids it registers; `aliases` maps alternate ids; `cli` names the executable (and, with
`install: { npm, bin }`, how `infantry harness-install` gets it); `icon`; `settings` are the keys the harness may
read through `host.setting`.

## How the three official harnesses do it

| Harness | Plugin | Hooks | Notes |
|---|---|---|---|
| Claude Code (`claude`) | `Infantry_Claude` | watch, mcp, session, models, stats, usage | By default runs the interactive `claude` CLI in its own terminal (see below); headless mode runs `claude -p` stream-json, or the persistent `--input-format stream-json`. Maps tool approval to permission modes, routes tool calls through the watched runner, reads its session store and plan limits. |
| pi (`pi`) | `Infantry_Pi` | watch, sandbox, session, models | `--mode json` or the persistent `--mode rpc`. Carries a to-do extension; reads provider cache split. |
| opencode (`opencode`) | `Infantry_OpenCode` | watch, mcp, config, session, models, stats | `run --format json`, or the persistent `opencode serve` over HTTP + SSE (the 2.12 slots). Merges the MCP server and the watch plugin into its inline config; reads its session store with `queryDb`. |

Read their `harness/` folders under `plugins/official/` as the reference implementations.

### Claude Code: the real CLI by default

By default each Claude agent runs the real interactive `claude` CLI in its own terminal (a pseudo-terminal, ConPTY on
Windows), exactly as you would run Claude Code yourself. The daemon types into that terminal and reads the session
transcript the CLI writes; nothing about the CLI is patched or spoofed. Its requests are the CLI's own, so usage
behaves like normal Claude Code use. You get this without changing anything.

The setting is `harness.claude.mode`:

| Value | What runs | Trade-off |
|---|---|---|
| `interactive` (default; also used when unset or unrecognised) | The interactive `claude` in a terminal | Parity with using Claude Code yourself |
| `headless` | `claude -p --output-format stream-json` (the SDK path; the persistent form adds `--input-format stream-json`) | A machine-readable event stream; may be metered differently from interactive use |

To switch to headless (SDK):

- **Settings UI:** Settings, Plugins tab, Harnesses > Claude Code (or search "claude"), field "Mode", type `headless`.
- **CLI:** `infantry settings set harness.claude.mode headless` (MCP: `infantry_settings_set` with the same key and value).

The mode is read at every session launch: a running agent keeps its mode, and its next launch (a new session, or a
restart) uses the new one. To go back, set `interactive` or clear the key. A value you saved yourself is never
changed by an update.

## Add a new harness, step by step

The smallest real example is `plugins/examples/Acme_EchoHarness` (an agent process that prints the prompt back).

1. Copy it to `plugins/community/Acme_MyHarness`. Set `name`, `author`, `license`; set `harness.ids` and `harness.cli`.
2. Implement `HarnessPlugin` (`pluginId`, `contractVersion = HARNESS_API_VERSION`, `register` that calls
   `registrar.register(MyHarness(registrar.host))`).
3. Implement `Harness.process(request)` returning the argv and env of your CLI. Read declared settings through
   `host.setting("my.key")`, resolve the CLI with `host.resolveCli("my-cli")`.
4. Implement a `HarnessTranslator` that turns each output line into `HarnessEvent`s (`SessionStarted`, `TextDelta`,
   tool and usage events, and `terminal` for the exit). Start from the echo translator.
5. Add `META-INF/services/com.superiorsoftllc.infantry.harness.api.HarnessPlugin` naming your class.
6. Add optional contributions only as you need them (approvals first, so the owner's tool approval reaches the CLI;
   then models, then a persistent session). Add each used one to `harness.hooks`.
7. Build the harness jar, install the folder under the plugins root `community/`, grant it in `modules.json`, restart.
   Check with `infantry harness-status` and `infantry harnesses`.
8. Pin it on a ticket or role (`--provider <id>`) and start the ticket.

## Add a new provider (a model group)

A model provider is a group in a harness's picker. Three routes, from simplest:

- **Descriptor harness**: add a `models` section (`list` or `modelsCommand`) as above.
- **Plugin harness**: return `HarnessModels(group, label, models, note, unavailable)` rows from `models()`.
- **An existing CLI that already knows the provider** (pi, opencode): configure it in that CLI; its `models` listing
  appears in the picker with no change to Infantry AI.

Routing by ticket weight and per-role pins are data, not code: `infantry project routing <projectId> --file rules.json`
(rules `{name, weights?, ticketTypes?, role?, provider, model, effort?}`, first match wins; a provider or model the
harness does not list is refused).

## Reference: HarnessHost, translator, a worked example

Added by DOCS-2. Sources: `superiorSoftLibrary/infantryHarnessApi/src/main/kotlin/com/superiorsoftllc/infantry/harness/api/HarnessApi.kt`
and `HarnessHost.kt`, `plugins/examples/Acme_EchoHarness/`.

### HarnessHost (what a plugin can ask the daemon)

| Member | Meaning |
|---|---|
| `moduleId`, `apiVersion` | Your module name and the harness API version the daemon implements (2.15.0). |
| `stateDir` | `<state>/plugin-data/<Module>/`, created on first use. |
| `log(line)` | One line in the daemon log, prefixed with your module name. |
| `setting(key)` | A value of a setting you declared in `harness.settings`; null when unset or undeclared. |
| `resolveCli(tool)` | Where your declared CLI (`harness.cli.id`) is installed; null if not. |
| `start(process, listener)` | Start a helper process (model listing, version probe). Not for the agent run. Throws `SecurityException` when not declared or granted. |
| `mcpLaunch(sessionEnv)` | The command that starts the infantry MCP server for a session; null when not granted. |
| `watchLauncher(sessionEnv)` | The watched-runner launcher, when watch is on; else null. |
| `queryDb(file, sql, params)` | Read-only sqlite query (2.11.0, grant `harness.hook.stats`); null when not granted or it fails. |
| `ask(sessionId, question)` | Suspends until the owner answers, it times out, or the session ends. |

### The run contract

The daemon calls `Harness.process(request)` to get a `HarnessProcess` (command, env), launches it under the session's
policy, and feeds each stdout line to a fresh `HarnessTranslator` from `newTranslator()`:

```kotlin
interface HarnessTranslator {
    fun translate(line: String): List<HarnessEvent>
    fun terminal(exitCode: Int, stderrTail: String): HarnessEvent
}
```

Events include `SessionStarted(id)`, `TextDelta(text)`, `Denial(tool, reason)`, `Completion(reason)` and `Error(message)`.
`terminal` runs when the process exits and returns `Completion` or `Error`. A harness that keeps one process for many
turns implements `HarnessSession` instead of a one-shot `process`.

### Worked example: `Acme_EchoHarness`

`EchoHarnessPlugin` (a `HarnessPlugin` with `pluginId` and `contractVersion = HARNESS_API_VERSION`) registers one
harness, `echo`, in `register(registrar)`. `EchoHarness.process` returns the daemon's own Java running a small
`EchoCli` class, with `ECHO_PREFIX` taken from `host.setting("echo.prefix")`. `EchoTranslator` turns a line starting
`session: ` into `SessionStarted`, any other line into `TextDelta`, and the exit code into `Completion` or `Error`.
Its `module.json` `harness` block sets `jar`, `contract` `2.15.0`, `ids` `["echo"]`, `aliases`, `cli`, `icon`,
`settings` `["echo.prefix"]` and `hooks` `[]`; a community copy needs the grants `harness.code`,
`harness.setting:echo.prefix`, `harness.cli` and `harness.cli.install` in `modules.json`.

### Open questions

- The `HarnessSession` turn protocol (persistent sessions) was not traced for this page; read `HarnessApi.kt` (`interface HarnessSession`) before relying on it.

## Feature support per harness

Not every feature works the same on every harness. The full matrix (scripts and hooks, board automation, background
processes, tools, usage), with the evidence for each cell and the open gaps, is in
[HARNESS-SUPPORT.md](../HARNESS-SUPPORT.md) (HARNESS-AUDIT-1, 2026-11-05). In short:

- **Same everywhere** (generic daemon code): mail and `mail.incoming` hooks, `infantry bg run --notify` and its
  end-of-job wake, process attribution and reaping, model pins, resume after a daemon restart, scripted auto-merge.
- **claude**: the interactive terminal (the default) and headless stream-json both carry MCP, effort and context
  limit. Only headless reports cost and compaction. Tool approval ASK relays each call to the owner (interactive:
  the `ClaudePermissionHook` relay, HARNESS-GAP G3; headless: `--permission-prompt-tool stdio`, G4). ASK is the fresh-install
  default (`ToolApprovals.DEFAULT`, FIRSTRUN-PERM-1).
- **pi**: no MCP server (agents use the `infantry` CLI); approval modes are not enforced; context limit is best
  effort; no mid-turn input.
- **opencode**: an ASK approval relay like claude's; effort and context limit are recorded but not
  applied; no mid-turn input.

## Type index (every other public type)

Generated for the `infantryHarnessApi` guard `SdkTypesDocumentedGuardTest`: every public top-level type of the module is named on this page. Types above are explained in full; the rest are listed by source file with the first line of their KDoc. Read the file for the members.

### `HarnessApi.kt`

- `ApprovalReply`: 2.12.0: the owner's answer to [SessionOutput.requestApproval].
- `ContextLimitCapability`: [support] plus, in words, HOW the harness applies the limit (or why it cannot), shown next to the option to the user.
- `ContextLimitSupport`: 2.1.0 (CONTEXT-LIMIT-1): how a harness honours a context limit.
- `EffortCapability`: [support] plus, in words, HOW the harness applies the level (or why it cannot), shown next to the option to the user.
- `EffortSupport`: 2.2.0 (EFFORT-1): how a harness honours a reasoning effort.
- `HarnessCapability`: [LIVE_SESSION] (additive, CLI-LIVE-1): the harness's [Harness.session] keeps ONE process per session and every wake (owner message, mail, note, script prompt) is a message into it, like a live CLI; `-
- `HarnessCommandAction`: 2.10.0: what the daemon does with a [HarnessCommandSpec]: set the next run's model, set its effort, or hand [HarnessCommandSpec.native] to the harness as the next turn.
- `HarnessCommandSpec`: 2.10.0: one `/` command: [name] is a bare word, [argument] a hint, [values] the accepted (or suggested) values, [native] the text for [HarnessCommandAction.NATIVE] only.
- `HarnessRegistrar`: Only valid during [HarnessPlugin.register]; a later call fails.
- `HarnessStatEvent`: One step of a session's record, in order.
- `HarnessStats`: 2.5.0: reads one session of this harness from its own store.
- `HarnessStatsRow`: Every field optional; null = the store does not say (never guessed).
- `HarnessUsageReading`: [windows] empty = no signal (the host shows the harness as unknown, never 0%).
- `HarnessUsageSource`: 2.4.0: one harness's plan limits.
- `HarnessUsageWindow`: One limit window: [percent] used (0-100, null = unknown), [resetsAt] epoch millis (null = unknown).
- `McpState`: 2.14.0: the session's MCP server link, as the harness sees it.
- `SandboxContribution`: 2.7.0: dirs added to the sandbox (read-only / read-write) and names of environment variables to let through.
- `SandboxRoots`: 2.7.0: the only roots a contributed sandbox dir may sit under: the module's own data folder and the session worktree.
- `SessionConnection`: 2.8.0: one launched process's protocol state.
- `SessionTurnEnd`: 2.8.0: how a persistent turn ended.
- `WatchContribution`: 2.6.0: [extraArgs] are appended to the harness argv, [env] is added to its environment, [files] are written by the host under the module's own [HarnessHost.stateDir] (relative path -> text; a path tha

### `HarnessHost.kt`

- `HarnessAnswer`
- `HarnessProcessListener`
- `HarnessQuestion`
- `HarnessRunningProcess`
