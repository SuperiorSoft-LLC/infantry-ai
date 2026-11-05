# AI-friendly audit (AI-FRIENDLY-1, 2026-11-05)

How easy is this repository for a fresh AI coding agent (Claude Code, Cursor, Copilot, opencode) that clones it to add a
feature or a plugin? Every finding below was measured on commit `4509da31` (branch `feature-agenthq-mvp`); the commands
are given so the numbers can be re-run. Scores are 1 (hostile) to 5 (excellent).

| Area | Score | One-line verdict |
|---|---|---|
| 1. Entry docs | 4 | `AGENTS.md` + `llms.txt` + `docs/wiki` answer every first question in 2-3 reads; a few gaps fixed in this ticket. |
| 2. Code navigability | 2 | 8 main files over 2,000 lines; two functions over 590 lines; an agent cannot hold the CLI or the daemon entry in context. |
| 3. Build/test ergonomics | 3 | One-line scoped test commands are documented; the known-failure baseline is not in the repo, so agents chase old failures. |
| 4. Plugin authoring | 4 | A building template, seven recipes, two API references; no check that the API docs match the code. |
| 5. Guard tests | 5 | 29 guard tests turn the architecture rules into build failures; now all listed in `AGENTS.md`. |
| 6. Kotlin specifics | 2 | Versions were nowhere in the docs; no page on expect/actual, Compose Multiplatform or wasm limits. |

## 1. Entry docs (score 4)

Measured: as a fresh agent, the path `AGENTS.md` -> `docs/wiki/Architecture.md` -> one recipe gives the three layers,
core vs plugins, the MPL-2.0 / Apache-2.0 split, build commands, a single-test-class command, the plugin layout and the
"where each change goes" table. That is 3 reads, under the 5-read target. `llms.txt` exists and mirrors the map.
`DocsFreshnessGuardTest` keeps every path and type named in `AGENTS.md` and `docs/wiki/**` real, so the entry docs
cannot rot silently.

Stale or missing (all fixed directly in this ticket):

- No `CLAUDE.md`: Claude Code reads `CLAUDE.md`, not `AGENTS.md`. Added a one-line `CLAUDE.md` that imports `AGENTS.md`.
- `AGENTS.md` and `CONTRIBUTING.md` said "the infantry / infantry CLI" (a leftover of the agenthq -> infantry rename).
- `AGENTS.md` repo table missed `infantrySdkBom` and `infantrySdkCatalog` (two real Gradle modules).
- `AGENTS.md` never mentioned `templates/plugin-repo` (the out-of-tree plugin template that `scripts/template-check.sh`
  builds); an agent making a plugin in its own repo would copy the wrong starter.
- `AGENTS.md` listed 13 guard tests; the repo has 29 (`git ls-files '*GuardTest.kt' | wc -l`). All are now listed.
- `README.md` named the owner's personal path `C:/Users/antho/agenthq-dist/...`; now `<dist>/...`.
- No toolchain versions anywhere in the entry docs; added to `AGENTS.md` section 3.

Still open:

- `docs/` holds 60 top-level design records, several over 40 KB (`BLOCKS-LAYOUT-DESIGN.md` 104.6 KB,
  `INFANTRY-MOBILE-UI-PLAN.md` 51.6 KB, `DAEMON-CORE.md` 50.6 KB). Search hits land in them first and many are dated.
  There is no index that says which are current and which are history (fix 6).
- The old name: `git grep -i agenthq -- '*.kt'` gives 151 lines in 52 files (`AGENTHQ_PORT`, `AGENTHQ_TOKEN`,
  `AGENTHQ_RUNNER`, `agenthq_` tool aliases, `-Dagenthq.*`), plus `.agenthq/scripts`, the branch name
  `feature-agenthq-mvp` and the role prompts' `agenthq_ticket_related_files`. A search for one name misses the other.
  Backlog ticket a6d7ba09 (SCRUB-AGENTHQ-1) covers the user-visible part; an alias table for agents is fix 8.

## 2. Code navigability (score 2)

2,193 Kotlin files, 303,231 lines. Main (non-test) files over 1,000 lines: 16; over 2,000 lines: 8.

The 30 largest Kotlin files (lines; `git ls-files '*.kt'` then `wc -l`):

| Lines | File |
|---|---|
| 6511 | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/CliMain.kt` |
| 4306 | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/DaemonMain.kt` |
| 2653 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/protocol/WireProtocol.kt` |
| 2523 | `superiorSoftLibrary/infantryBoard/src/main/kotlin/com/superiorsoftllc/infantry/backlog/TicketBoard.kt` |
| 2403 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/model/Model.kt` |
| 2387 | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/SessionManager.kt` |
| 2246 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/cli/CliAcceptanceTest.kt` (test) |
| 2194 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/net/Controller.kt` |
| 1547 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/ConfigDialog.kt` |
| 1168 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/db/SchemaMigrationTest.kt` (test) |
| 1164 | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/DaemonPluginHost.kt` |
| 1146 | `plugins/official/Infantry_Board/src/Board.kt` |
| 1065 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/TabFactory.kt` |
| 1044 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/shell/ShellContext.kt` |
| 1033 | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/persistence/InfantryStore.kt` |
| 1031 | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/BgCli.kt` |
| 1010 | `plugins/official/Infantry_Chat/src/ChatSurface.kt` |
| 1003 | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/SettingsService.kt` |
| 892 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/cli/mcp/McpServeTest.kt` (test) |
| 859 | `plugins/official/Infantry_Processes/daemon/src/test/kotlin/com/superiorsoftllc/infantry/plugins/official/processes/daemon/ProcessWatchTest.kt` (test) |
| 836 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/cli/AgentTicketStartE2eTest.kt` (test) |
| 795 | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/LimitRegistry.kt` |
| 786 | `plugins/official/Infantry_ChatFeed/src/ChatFeedSurface.kt` |
| 779 | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/projectgit/ProjectGitService.kt` |
| 757 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/daemon/TicketStartDaemonTest.kt` (test) |
| 746 | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/AhqRoleIcons.kt` |
| 726 | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/triggers/TriggerServiceTest.kt` (test) |
| 722 | `superiorSoftLibrary/infantryProtocol/src/main/kotlin/com/superiorsoftllc/infantry/protocol/Protocol.kt` |
| 718 | `plugins/official/Infantry_Changes/src/ProjectChangesPanel.kt` |
| 716 | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/manager/PluginManager.kt` |

Functions over 150 lines in main code (brace-counting scan over `fun` declarations; a rough measure):

| Lines | Function |
|---|---|
| 636 | `Model.kt:1375` `private fun apply(s: AppState, env: Envelope, now: Long)`: the whole client reducer, one `when` over every frame type |
| 598 | `CliMain.kt:4987` `private fun parseArgs(...)`: every CLI command's argument parsing in one function |
| 230 | `TicketBoard.kt:1122` `private fun handOut(...)`: ticket start |
| 194 | `ClaudeCommand.kt:98` `fun definesInfantry(...)` |
| 158 | `ClientMain.kt:331` `private fun render(...)` |
| 155 | `MemoryHandler.kt:37` `fun answer(...)` |

Why this matters for agents: a 6,500-line file is ~80K tokens; an agent reads it in ranges and loses the shape, then
edits the wrong branch of a `when`. `CliMain.kt` and `DaemonMain.kt` are also the hottest merge-conflict files
(every CLI or daemon feature touches them). `AGENTS.md` already warns about `DaemonMain.kt`; the fix is structural.

Misleading names: the `Ahq` prefix (`AhqRoleIcons.kt` and 4 more files, 5 `Ahq*` identifiers) and the `agenthq` env/tool
names (section 1) survive the rename; `TicketBoard.kt` lives in package `backlog`.

## 3. Build and test ergonomics (score 3)

Good:

- `AGENTS.md` section 3 gives the one-line single-class command, the per-plugin `:jar` / `:test` commands, and the rule
  "one Gradle build at a time". Recipes repeat the scoped test line for their own change.
- Gradle memory is set in `gradle.properties` (`org.gradle.jvmargs=-Xmx6g`, `kotlin.daemon.jvmargs=-Xmx3072M`,
  `kotlin.js.node.args=--max-old-space-size=12288` for wasm); now named in `AGENTS.md`.
- `TestPortGuardTest` stops a test from hitting the owner's daemon.

Gaps:

- The QA base list (59 known failures on dev) is not in the repository. `git grep -nE 'known fail'` finds only scattered
  `TEST-TODO` lines in `docs/QA-LOG.md`; the project knowledge base has no note for it either
  (`infantry memory search "known failures"` returns nothing). An agent whose scoped run hits one of them will try to
  "fix" it. Fix 4.
- 1,069 test files; no per-module timing is documented, so an agent cannot tell a slow module from a hung build.
  Measured here: `./gradlew :superiorSoftLibrary:infantry:test --tests DocsFreshnessGuardTest --tests SharedDocsGuardTest`
  in a fresh worktree: BUILD SUCCESSFUL in 1m 51s (70 tasks, 40 executed, 30 from the build cache). That is the
  realistic "time to first green test" for an agent.
- `gradle/libs.versions.toml` is inconsistent for Ktor: `ktor-server-cio`, `ktor-server-websockets`,
  `ktor-server-core` and `ktor-client-websockets` point at `ktorClientCio = "3.2.3"`, the rest at `ktor = "3.4.1"`.
  Mixed, `webSocket("/ws")` never fires (handshake then 1006). Only `superiorSoftLibrary/infantry/build.gradle.kts`
  (line ~123) pins every `io.ktor` module back to 3.4.1; a new module that uses those catalog entries without that
  block gets the skew. A trap for an agent adding a module or bumping Ktor (fix 10).

## 4. Plugin authoring (score 4)

- `plugins/_template` builds in the repo build (`:plugins:_template` and `:daemon-plugin-_template`), has a unit test,
  and `docs/wiki/make-a-plugin.md` walks from copy to built jars with every `module.json` field.
- `templates/plugin-repo` is the out-of-tree template; `scripts/template-check.sh` proves it builds against the
  published SDK. Not linked from `AGENTS.md` until this ticket.
- Five reference plugins in `plugins/examples/` (daemon tool, policy hook, field keys, news agent, echo harness).
- Seven recipes in `docs/wiki/recipes/`.
- Gap: nothing checks that `docs/wiki/plugin-api.md` and `docs/wiki/daemon-plugin-api.md` cover the public API.
  `DocsFreshnessGuardTest` catches a type that was removed, not a type that was added and never documented. Fix 7.
- `docs/wiki/make-a-plugin.md` line ~502 itself notes that `plugins/_template/README.md` omits `slots`, `parents`,
  `config`; the template README lags the wiki page.

## 5. Guard tests (score 5)

29 guard tests (`git ls-files '*GuardTest.kt'`) enforce the layer line, the SDK surface, no presets / demo / build stack
/ script engine in core, named limits, test ports, license headers, docs freshness and the harness contract. For an AI
agent this is the strongest feature of the repo: a rule broken by mistake fails fast with a message naming the file.
`AGENTS.md` section 6 now lists all 29. Five of them (`BoardApiImportGuardTest`, `HookKindsGuardTest`, `PerfGuardTest`,
`TriggerGuardTest`, `TriggerPortsGuardTest`) carry no KDoc stating the rule they enforce, so the failure is harder to
read. Fix 10.

## 6. Kotlin specifics (score 2)

Versions (`gradle/libs.versions.toml`): Kotlin 2.3.10, Compose Multiplatform 1.10.3, Ktor 3.4.1, kotlinx.serialization
1.10.0, coroutines 1.10.2, SQLDelight 2.2.1, AGP 9.0.1, kotlinx-datetime 0.7.1. These are newer than most models'
training data; the usual mistakes are old Compose APIs, `kotlinx.datetime` 0.6 names, and Android Gradle Plugin 8 DSL.

- 23 files use `expect` / `actual` (`infantryUiCore` common / jvm / android / wasmJs); no page explains which source set
  an agent edits for which client, or that wasm and Android compile plugins in (D6, D9) while desktop loads them at run
  time.
- `gradle.properties` disables Kotlin incremental compilation (`kotlin.incremental=false`, JS/wasm too) and sets
  `android.builtInKotlin=false`; the reason is not written down, so an agent "optimising" the build may flip them.
- Fix 5 adds a Kotlin notes page with examples.

## Top 10 fixes by impact

Each is a ticket in todo: d6904191, b86fdcc8, 1c00d684, 76a30346, 23a46621, 96063f02, beb7750e, 23039ad2, 51e58aa0,
7a8f3904 (fixes 1-10 in order).

| # | Fix | Area | Weight |
|---|---|---|---|
| 1 | Split `CliMain.kt` (6,511 lines) into one file per command group; `parseArgs` (598 lines) into per-command parsers | 2 | 3 |
| 2 | Split `Model.kt` `apply` (636 lines) into per-frame-family reducers | 2 | 3 |
| 3 | Split `DaemonMain.kt` (4,306 lines) further: frame dispatch per area into its own files | 2 | 4 |
| 4 | Put the known-failure baseline in the repo (`docs/KNOWN-FAILURES.md`, class + reason + ticket) and link it from `AGENTS.md` | 3 | 2 |
| 5 | Kotlin notes page in the wiki: versions, expect/actual source sets per client, Compose MP and wasm pitfalls, why incremental is off | 6 | 2 |
| 6 | Index `docs/` design records: current vs history, so search lands on the right page | 1 | 1 |
| 7 | Guard that every public type of `infantryPlugins` / `infantryDaemonApi` / `infantryHarnessApi` is named in its API page | 4 | 3 |
| 8 | Old-name alias table in `AGENTS.md` (agenthq env vars, tool aliases, folders, branch) so search finds both names | 1 | 1 |
| 9 | Split `TicketBoard.kt` (2,523) and `SessionManager.kt` (2,387); `handOut` (230 lines) into steps | 2 | 4 |
| 10 | KDoc the rule on the five undocumented guard tests; bring `plugins/_template/README.md` up to the wiki; make the Ktor catalog entries one version (drop `ktorClientCio`) | 5, 4, 3 | 1 |

Out of scope here (project data, not repo): the role prompts still name `agenthq_ticket_related_files`; that is for the PM
to update in the role text.
