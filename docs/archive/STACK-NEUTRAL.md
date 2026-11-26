# STACK-NEUTRAL-1: the watch tooling is opt-in, stack knowledge is data (ticket b2dc68cf)

Owner direction (2026-10-30): the process watch, the process ledger and reaping, the build-limit env, log pattern notices
and the bg job notifier are tooling the user ENABLES; core keeps the abstraction; what a stack is (gradle, maven, npm,
cargo, go, pytest, make, dotnet...) is data, never code. With the tooling off the app runs any stack with plain exit
codes and logs. Related: STALL-1 (802c64d5), STALL-2 (a46744ec), STALL-6 (ae636d42), ticket 66153313 (build limits),
STALL-3 (d258b738, tool profiles), docs/STALL.md.

## 0. Safety check: can a kill path touch a non-Gradle process?

Yes, by design, but only inside a session's proven process closure. Every kill re-checks pid + OS start instant; the
daemon, its ancestors and the app's own `java.home` are never recorded or killed.

| path | what it kills | finding |
|---|---|---|
| `ProcessWatch.reapSession` (session close) / `reapEnded` (daemon start, shutdown) | every ledger entry of the closed session, any program, leaf first | intended. Before this ticket only Gradle/Kotlin daemons were spared as shared; a shared daemon of another stack (dotnet build server...) died with the session that started it. Now any `sharedDaemon` profile is spared. |
| `ProcessLedger.adopt` (feeds the above) | adopted a heavy process whose command line CONTAINED a live ticket worktree path | was a RISK (an owner-started `npm run dev` in a worktree died with the session). FIXED (PM): adopted only by a live chain to a ledger entry, or as an ORPHAN (parent gone) that matches a profile's `heavyCommands` and names the worktree at a path boundary. A live non-ledger parent = owner-started = never adopted. |
| `ProcessWatch.reapRogue` (auto-reap, off by default; by hand) | ledger entries only | unattributed "orphan daemon" rows are flagged rogue but never killed (not in the ledger). |
| `ProcessWatch.reapUnowned` (by hand, from the list) | MCP servers and `opencode serve` no session owns | was a RISK (could be the owner's Claude Desktop / opencode). FIXED (PM): removed; such processes are not listed, and every reap outside the ledger is refused. |
| `SessionManager.reap` (stale kept harness) | a kept harness of this state dir with no live parent | safe. |
| `bg stop <id>` | that job's wrapper tree | safe. |
| harness adapters, RunManager | their own children | safe. |

## 1. Audit: stack-specific behaviour in core (not comments, not this repo's build files, not tests)

Classes: **M** = generic mechanism (stays in core), **K** = stack knowledge (data: tool profile), **F** = stack feature
(profile hook or plugin).

| file:line (before) | behaviour / assumption | class | home | status |
|---|---|---|---|---|
| session/ProcessWatch.kt:440 `HEAVY` | java, javaw, node, opencode, docker, vmmem, wsl, kotlin.*, gradle.*, bun, pi images are worth listing | K | profiles `heavyImages` (gradle, kotlin, runtimes) | DONE |
| session/ProcessWatch.kt:443 `ORPHAN_DAEMON` | GradleDaemon / KotlinCompileDaemon are build daemons (orphan recognition) | K | profiles `daemons` | DONE |
| session/ProcessWatch.kt:444 `GRADLE_CLIENT` | gradlew / GradleWrapperMain / GradleMain are the client that uses a shared daemon | K | profiles `clients` | DONE |
| session/ProcessWatch.kt:372-376 | a shared daemon is spared for a gate-lock holder / handed to another session's client | M (rule) + K (which daemons) | core rule, profiles `sharedDaemon` | DONE |
| session/ProcessWatch.kt:266 reason text | "orphan Gradle/Kotlin daemon" | K | generic text + profile name: "orphan build daemon (gradle)" | DONE |
| session/ProcessLedger.kt:187 `HEAVY_CMD` | java, node, gradle, kotlin, npx, playwright, modelcontextprotocol command lines are adoptable | K | profiles `heavyCommands` (gradle, kotlin, runtimes, mcp) | DONE |
| session/ProcessLedger.kt `MCP_CMD`, `OPENCODE_SERVE` | harness helper processes (MCP servers, opencode serve) | harness knowledge, not stack | stays (harness plugins later) | kept |
| infantryRunner/BuildPriority.kt:57-79 `sessionEnv` | GRADLE_OPTS with `org.gradle.workers.max`, Kotlin in-process strategy, `org.gradle.project.infantry.*Forks` | K | gradle profile `env` templates (`${maxWorkers}`, `${maxParallelForks}`) | DONE |
| infantryRunner/BuildPriority.kt:82 `FORK_PROPERTIES` | this repo's own test-fork property names | project data | moved (STACK-NEUTRAL-6): resource watch/repo-profile-additions.json, migrated once into the install's `watch.toolProfiles` | F1 |
| daemon/DaemonMain.kt:847 | daemon's GRADLE_OPTS inherited | K | any template variable is appended to the daemon's own value (`inherit`) | DONE |
| settings/LimitRegistry.kt:82, 258 descriptions | "Gradle workers", "gradle/mvn/npm/yarn/pnpm" | K | generic wording; keys `limits.build.maxWorkers` / `maxParallelForks` were already stack-neutral (no alias needed) | DONE |
| daemon/WatchHooks.kt, runner/WatchRewrite.kt, runner/WatchShims.kt | which programs are long / shimmed | K | already profiles (STALL-3) | was done |
| mailbox/BgJobNotifier.kt | default notify patterns, failure markers | K | already profiles (`notifyOn`, `failureMarkers`) | was done |
| cli/BgCli.kt:603, session/BackgroundWork.kt:70 | detached daemons not waited on | K | already profiles (`daemons`) | was done |
| cli/ProgressCli.kt:373-388 | `--reader gradle` parses `> Task :x` | K | data reader profiles (regex -> status/step) | F2 |
| cli/CliMain.kt:4365 | help text names that reader | K | follows F2 | F2 |
| context/TicketContextComposer.kt:101-110 | start brief: "Gradle builds", "gradlew.bat", "`gradle`, `mvn`, `npm`... are WATCHED" | K / role data | role / project data; only when `limits.watch.enabled` = 1; tool list from the profiles | F3 |
| resources/KNOWLEDGE.md:5,11,17 | shared knowledge text: Gradle examples | role data | project knowledge, not a core resource | F3 |
| worktree/AndroidSdkProvision.kt | writes `local.properties` `sdk.dir` for a Gradle project | F | worktree-provision hook of a profile or plugin | F4 |
| daemon/WebBundle.kt:106 | dev hint naming Infantry AI's own web build | this repo's build | allowed (guard allowlist) | - |
| cli/BgCli.kt:177, cli/ProgressCli.kt:113, cli/CliMain.kt:4377 | help examples `gradlew test`, "Gradle/Kotlin daemons" | K | neutral wording | DONE |
| plugins/official/Infantry_Processes daemon tool description | "Gradle/Kotlin daemons" | K (wire text) | left: the MCP tool description is asserted on the wire (McpServeTest); change with F5 | F5 |
| scripts/infantry-gate.sh | the gate script | this repo's build | allowed | - |

## 2. The tool-profile model (one model, `watch/tool-profiles.json`)

Extends STALL-3's `ToolProfile` (infantryRunner `ToolProfiles.kt`); every new field is optional, so an old override still
parses. Merge order is unchanged: bundled defaults, then the `watch.toolProfiles` setting (and later plugins/project
data) by `name` (replace, `disabled: true` drops, a new name appends).

| field | meaning |
|---|---|
| `commands`, `shim`, `long`, `longIf`, `args`, `argsUnless` | (STALL-3) program names, PATH shims, what the hooks route through the watched runner |
| `daemons` | regexes of processes the tool leaves running on purpose: not waited on, recognised as orphan build daemons |
| `sharedDaemon` | those daemons are shared by sessions and the gate: at a session's close spared for a live gate-lock holder, else handed to another live session running one of `clients`, else killed |
| `clients` | regexes of the tool's client process command line |
| `heavyImages` | image regexes (full match, `.exe` optional) the processes list shows when no session owns them |
| `heavyCommands` | command-line regexes the ledger may adopt by a session's worktree path |
| `daemonDir` | (PROC-LEAK-2) `{paths, match}`: file templates (`${pid}`, `${home}`, `${env:NAME}`, `*` = one directory level) and a regex whose group 1 is the directory one of `daemons` last built in (the last match wins). The ledger adopts an ORPHAN daemon it never saw for the session whose worktree that is; Gradle reads its per-pid daemon log |
| `env` | `{name, value, onlyIf?, join=" ", inherit=true}` templates; `value` may use `${maxWorkers}` / `${maxParallelForks}` (settings `limits.build.*`); `onlyIf` drops the template while that limit is 0; templates of one name are joined, after the daemon's own value when `inherit` |
| `notifyOn`, `failureMarkers`, `redirects`, `logFlags` | (STALL-4) bg notice patterns |

Bundled: gradle, kotlin, maven, npm, yarn, pnpm, java-jar, cargo, go, pytest, make, dotnet, plus the non-stack entries
`runtimes` (java, node, docker, wsl, bun, pi, opencode images) and `mcp` (npx / playwright / modelcontextprotocol command
lines). The new stacks (cargo, go, pytest, make, dotnet) ship with `shim: []` and `long: false`: knowledge only (failure
markers, dotnet's shared build servers), so no program a user runs today is suddenly rewritten or shimmed.

No profile matches = generic behaviour: a process is a member of its session's tree, killed with the session, never
spared, never named; no stack env is set.

## 3. Seam and opt-in

* **Switch** `limits.watch.enabled` (0/1, a LimitDef, so it shows in Settings and takes `INFANTRY_LIMIT_WATCH_ENABLED`).
  Read live at every entry point: `ProcessWatch.tick/report/reap/reapRogue/reapSession/reapEnded` and the per-launch
  `buildEnv` (build limits env, PATH shims, `INFANTRY_WATCH` that turns the harness hooks on). Off = nothing recorded,
  nothing killed, no pressure / CPU alert, `report` answers free RAM only, a reap is refused with the reason, a session
  gets no build env and `INFANTRY_WATCH=off`. `bg run` still runs and mails its plain exit code and tail.
* **Migration** (`SettingsService.migrateWatchEnabled`, at daemon start): while the key is unset and no env names it, an
  install with stored sessions or a process ledger file is set to **1** (the owner's install keeps today's behaviour);
  a fresh one to **0**. Stored, so it runs once; logged as `limits.watch.enabled set to ...`.
  To turn it on later: Settings > `limits.watch.enabled` = 1 (the `settings_set` frame), or env `INFANTRY_LIMIT_WATCH_ENABLED=1`
  while the key is unset.
* **Core keeps** the mechanisms: ledger (pid + start instant), close-time cleanup rules, gate-lock release, CPU meter,
  priority lowering, the runner, bg job registry and exit notices. **Profiles carry** every stack name.

### Trade-off recorded

* Chosen: switch + data profiles in this ticket; the code stays in core behind the switch. Rejected for now: moving
  `ProcessWatch`/`ProcessLedger` into `Infantry_Processes`' daemon half. DaemonApi has no host service for it: a
  process snapshot, a kill that re-checks the start instant, the session roots + keep set, a session-close hook with
  the keep set, and a per-launch env contributor. Adding those is DaemonApi surface that is expensive to reverse
  (every plugin may come to rely on a process-kill service), so it is its own ticket (F5), designed with the kill-scope
  guardrails first. The switch already gives the user-visible contract (opt-in, off = nothing watched or killed).
* Expensive to reverse: the profile field names (they are user/plugin data once shipped) and the migration rule
  (an install migrated to 0 is not re-migrated).

## 4. Follow-ups (filed as tickets)

* F1: move `infantry.testForks` / `infantry.uiTestForks` out of the bundled gradle profile into this project's data.
* F2: progress readers as data (`--reader <name>` from profile `progress` rules); drop the code `gradle` reader.
* F3: start brief and KNOWLEDGE.md watch text from role/project data, shown only with the switch on, tool list from profiles.
* F4: AndroidSdkProvision as a worktree-provision hook of a profile/plugin.
* F5: DaemonApi `processes` + `launchEnv` host services, then ProcessWatch/Ledger/build env into Infantry_Processes'
  daemon half; the plugin toggle drives `limits.watch.enabled`.

## 5. Tests (scoped)

`ProcessWatchTest` (golden Gradle behaviour unchanged with the bundled profiles; watch off; switched on later; no gradle
profile = generic kill; a non-Gradle shared daemon from a data profile spared and handed over; reason names the profile),
`BuildLimitsTest` (golden GRADLE_OPTS string; no profile = no stack env; a non-Gradle profile's templates),
`BuildLimitsSettingTest` (migration once: fresh 0, existing 1, env wins), `StackNeutralGuardTest` (core code names no
stack outside the pending allowlist, which may only shrink), plus `SessionLifecycleTest`, `ToolProfilesTest`,
`BgJobNotifierTest`, `StallHookTest`, `StallRunnerTest`, `LimitSettingsTest`, `LimitsGuardTest`,
`SessionProcessIntegrationTest`. No full suite (staging gate).

## 6. STACK-NEUTRAL-2 (ticket c8e72d7f): the watch is a plugin (DaemonApi 1.8.0)

`api.processes` (snapshot/info/descendants/startInstant/memory/freeMb/totalMb/cpuNanos, `kill(pid, startedAt, tree)` = the only path to
`OsProcessTable.killTree/killOne`, re-checks start instant, refuses daemon/ancestors/java.home, logs the asker; sessions()/sessionRoots()
with worktree, state, ticket, keep set; onSessionClosed; onDaemonShutdown; gateLock; toolProfiles; serve(handler) for the `processes` frames;
publishPressure; raiseAlert), `api.launchEnv.contribute(name){LaunchInfo->Map}` (registration order, a failing contributor skipped) and
`api.settings.limit/stored` (limits.*, watch.*, processes.* only). Community: grant `daemon.processes`, never `kill`.
Moved to `Infantry_Processes/daemon`: ProcessWatch, ProcessLedger, CpuMeter/CpuSustain, ProcessFrames, build-limits env + shims env
(`WatchLaunchEnv`). Core keeps `session/ProcessHost.kt` (LocalProcesses, LaunchEnvHost, OS sources, guard). Plugin absent/disabled = no watch,
`INFANTRY_WATCH=off`. The `limits.watch.enabled` switch still gates the plugin's behaviour.
