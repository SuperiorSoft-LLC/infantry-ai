# Harnesses and providers

A **harness** is the adapter between Infantry AI and one agent CLI (Claude Code, pi, opencode, or your own). Its id
is the **provider** you pin on a ticket or role (`claude`, `pi`, `opencode`). Inside a harness, the model catalog
groups models by their **upstream provider** (for example pi lists `anthropic/claude-sonnet-5-5`). List both with
`infantry harnesses` or the MCP tool `agenthq_list_harnesses`.

There are two ways to add one:

1. **A JSON descriptor**, no code (good for a CLI that prints text or JSON lines).
2. **A harness plugin**, a jar implementing the harness API (full control: persistent sessions, approvals,
   stats, usage).

## 1. JSON descriptor (no code)

Drop a file into `<config dir>/harnesses/` (default `~/.agenthq/harnesses/`; set by `AGENTHQ_HOME`). Samples ship in
`superiorSoftLibrary/agentHq/docs/harnesses/` (`codex.json`, `gemini.json`).

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
`superiorSoftLibrary/agentHq/docs/QUICKSTART.md` section 4.

## 2. The harness API (plugin)

The only thing a harness jar compiles against is the module `agentHqHarnessApi`
(`com.superiorsoftllc.agenthq.harness.api`). It depends on the Kotlin stdlib only. The version is semver;
`HARNESS_API_VERSION` in the source is **2.12.0** (older docs stop at 2.11). A plugin states the version it was built
against in `contractVersion`; the daemon refuses a different major or a newer minor. Minor bumps only add members
with defaults, so an old plugin keeps working.

Core types:

- `HarnessPlugin` (`pluginId`, `contractVersion`, `register(registrar)`, `unload()`), registered through a
  `META-INF/services/com.superiorsoftllc.agenthq.harness.api.HarnessPlugin` file.
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
  "contract": "2.12.0",
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
| Claude Code (`claude`) | `Infantry_Claude` | watch, mcp, session, models, stats, usage | Runs `claude -p` stream-json, or the persistent `--input-format stream-json`. Maps tool approval to permission modes, routes tool calls through the watched runner, reads its session store and plan limits. |
| pi (`pi`) | `Infantry_Pi` | watch, sandbox, session, models | `--mode json` or the persistent `--mode rpc`. Carries a to-do extension; reads provider cache split. |
| opencode (`opencode`) | `Infantry_OpenCode` | watch, mcp, config, session, models, stats | `run --format json`, or the persistent `opencode serve` over HTTP + SSE (the 2.12 slots). Merges the MCP server and the watch plugin into its inline config; reads its session store with `queryDb`. |

Read their `harness/` folders under `plugins/official/` as the reference implementations.

## Add a new harness, step by step

The smallest real example is `plugins/examples/Acme_EchoHarness` (an agent process that prints the prompt back).

1. Copy it to `plugins/community/Acme_MyHarness`. Set `name`, `author`, `license`; set `harness.ids` and `harness.cli`.
2. Implement `HarnessPlugin` (`pluginId`, `contractVersion = HARNESS_API_VERSION`, `register` that calls
   `registrar.register(MyHarness(registrar.host))`).
3. Implement `Harness.process(request)` returning the argv and env of your CLI. Read declared settings through
   `host.setting("my.key")`, resolve the CLI with `host.resolveCli("my-cli")`.
4. Implement a `HarnessTranslator` that turns each output line into `HarnessEvent`s (`SessionStarted`, `TextDelta`,
   tool and usage events, and `terminal` for the exit). Start from the echo translator.
5. Add `META-INF/services/com.superiorsoftllc.agenthq.harness.api.HarnessPlugin` naming your class.
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

Added by DOCS-2. Sources: `superiorSoftLibrary/agentHqHarnessApi/src/main/kotlin/com/superiorsoftllc/agenthq/harness/api/HarnessApi.kt`
and `HarnessHost.kt`, `plugins/examples/Acme_EchoHarness/`.

### HarnessHost (what a plugin can ask the daemon)

| Member | Meaning |
|---|---|
| `moduleId`, `apiVersion` | Your module name and the harness API version the daemon implements (2.12.0). |
| `stateDir` | `<state>/plugin-data/<Module>/`, created on first use. |
| `log(line)` | One line in the daemon log, prefixed with your module name. |
| `setting(key)` | A value of a setting you declared in `harness.settings`; null when unset or undeclared. |
| `resolveCli(tool)` | Where your declared CLI (`harness.cli.id`) is installed; null if not. |
| `start(process, listener)` | Start a helper process (model listing, version probe). Not for the agent run. Throws `SecurityException` when not declared or granted. |
| `mcpLaunch(sessionEnv)` | The command that starts the agenthq MCP server for a session; null when not granted. |
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
Its `module.json` `harness` block sets `jar`, `contract` `2.12.0`, `ids` `["echo"]`, `aliases`, `cli`, `icon`,
`settings` `["echo.prefix"]` and `hooks` `[]`; a community copy needs the grants `harness.code`,
`harness.setting:echo.prefix`, `harness.cli` and `harness.cli.install` in `modules.json`.

### Open questions

- The `HarnessSession` turn protocol (persistent sessions) was not traced for this page; read `HarnessApi.kt` (`interface HarnessSession`) before relying on it.
