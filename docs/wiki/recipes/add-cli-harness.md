# Recipe: add a CLI harness

Goal: Infantry AI can run another agent CLI. A **harness** turns "run this prompt in this session" into the argv and
environment of that CLI and turns the CLI's output lines into events. The daemon launches, sandboxes, gates and
kills the process. Core carries no concrete harness (`HarnessCoreGuardTest`); every harness is a descriptor or a
plugin.

There are two routes. Use the first when the CLI prints JSON lines and takes flags; use the second when you need code.

## Route 1: a JSON descriptor (no code)

### Files to touch

```
~/.agenthq/harnesses/mycli.json      (the config dir; AGENTHQ_HOME changes it)
```

Samples that ship: `superiorSoftLibrary/agentHq/docs/harnesses/codex.json` and
`superiorSoftLibrary/agentHq/docs/harnesses/gemini.json`.

### Steps

1. Copy `codex.json` to `mycli.json`. Set `id`, `name`, `command`.
2. `args` is a template: `{prompt}`, `{model}`, `{sessionId}`, `{effort}`, `{toolApproval}` are replaced; a nested
   array such as `["--model", "{model}"]` is dropped when its placeholder has no value.
3. Map the CLI's output lines in `output.map` (`match` on fields, `event` one of `session`, `text`, `reasoning`,
   `tool`, `tool_result`, `usage`, `completion`, `error`, `fields` as dotted paths into the line).
4. Map `toolApproval` (`deny`, `allowEdits`, `allowAll`) to the CLI's flags. Audit it: it is how the owner's approval
   choice reaches the CLI.
5. Restart the daemon. Check with `infantry harnesses`, then pin the harness on a ticket or role with
   `--provider <id>`.

## Route 2: a harness plugin (code)

The reference is `plugins/examples/Acme_EchoHarness`, an agent process that prints the prompt back. It compiles
against `agentHqHarnessApi` only (a leaf with no project dependency).

### Files to touch

```
plugins/community/Acme_MyHarness/module.json
plugins/community/Acme_MyHarness/harness/build.gradle.kts
plugins/community/Acme_MyHarness/harness/src/main/kotlin/com/acme/myharness/MyHarnessPlugin.kt
plugins/community/Acme_MyHarness/harness/src/main/resources/META-INF/services/com.superiorsoftllc.agenthq.harness.api.HarnessPlugin
plugins/modules.json                 (existing file: enable and grant)
```

Their models in the repository: `plugins/examples/Acme_EchoHarness/module.json`,
`plugins/examples/Acme_EchoHarness/harness/build.gradle.kts`,
`plugins/examples/Acme_EchoHarness/harness/src/main/kotlin/com/acme/echoharness/EchoHarnessPlugin.kt`, and the services
file `plugins/examples/Acme_EchoHarness/harness/src/main/resources/META-INF/services/com.superiorsoftllc.agenthq.harness.api.HarnessPlugin`.

### Steps

1. Copy `plugins/examples/Acme_EchoHarness` to `plugins/community/Acme_MyHarness`. Rename the package. `settings.gradle.kts`
   includes `plugins/<folder>/<Module>/harness` as `:harness-plugin-<folder>-<Module>`.
2. `module.json`: set `name`, `author`, `license`, and the `harness` object: `jar`, `contract` (must equal
   `HARNESS_API_VERSION`, in `superiorSoftLibrary/agentHqHarnessApi/src/main/kotlin/com/superiorsoftllc/agenthq/harness/api/HarnessApi.kt`),
   `ids`, `cli`, `settings` (every key the harness reads), `hooks` (optional contributions it uses).
3. Implement `HarnessPlugin`: `pluginId`, `contractVersion = HARNESS_API_VERSION`, and
   `register(registrar)` calling `registrar.register(MyHarness(registrar.host))`.
4. Implement `Harness`: `id`, `capabilities`, `process(request)` returning the argv, env and working dir of your CLI
   (read declared settings with `host.setting("my.key")`, resolve the binary with `host.resolveCli("my-cli")`), and
   `newTranslator()` returning a `HarnessTranslator` whose `translate(line)` yields `HarnessEvent`s
   (`SessionStarted`, `TextDelta`, tool and usage events) and whose `terminal(exitCode, stderrTail)` ends the run.
   `HarnessHost` (`HarnessHost.kt`, same folder as `HarnessApi.kt`) is the only part of the daemon you can reach.
5. Name your class in the services file (one line, the fully qualified class name).
6. Build: `./gradlew :harness-plugin-community-Acme_MyHarness:jar --console=plain`. Grant it in `plugins/modules.json`
   with `harness.code`, `harness.setting:<key>` and, if it ships a `cli` block, `harness.cli` (see
   [Harnesses](../Harnesses.md), "Worked example").
7. Restart; check with `infantry harness-status` and `infantry harnesses`; pin with `--provider <id>`.

Add optional contributions only as you need them: approvals first (so the owner's tool approval reaches the CLI),
then models, then a persistent session.

## Related

[Harnesses](../Harnesses.md) (the API, the contributions, the run contract),
[Add a provider](add-provider.md).
