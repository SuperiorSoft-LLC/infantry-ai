# QA log

## LAUNCH-QA-2 (2026-10-30), dev tip d75b4f06 ("feature-agenthq"-mvp already merged; no code changed)

Gradle flags: `--no-parallel`, `-Xmx3g/-XX:MaxMetaspaceSize=2g` (Gradle + Kotlin daemon), foreground, no tail masking.

### 1. Full suites: all green, no fixes needed
| Suite | Result |
|---|---|
| infantry `test` (448 classes) | 2768 tests, 0 failed, 0 errors, 2 skipped |
| infantryUi `test` | 671 tests, 0 failed |
| infantryUiCore `jvmTest` (incl. BlankShellE2ETest 1/1) | 868 tests, 0 failed |
| infantryPlugins `jvmTest` | 77 tests, 0 failed |
| infantryAndroid `testDebugUnitTest` | 17 tests, 0 failed |
| `infantryUiCore:compileKotlinWasmJs`, infantryWeb `wasmJsBrowserDevelopmentExecutableDistribution` | BUILD SUCCESSFUL (the wasm compile tasks were FROM-CACHE: same inputs, not recompiled) |

BOARD-API-2 (migration 52 / ticket_events) is covered by the whole-suite run above.

### 2. Migration safety on a COPY of the owner's DB: pass
- Copy method: read-only SQLite backup API (`mode=ro`) of `%LOCALAPPDATA%\SuperiorSoft\InfantryAI\infantry.db`, plus sessions.json and mailbox.json (JSON-parse checked). Nothing was written to the owner's dir. Temp copy deleted afterwards.
- Daemon: installDist jars, `INFANTRY_STATE_DIR` = temp copy, `INFANTRY_PORT=8812`, other INFANTRY_* cleared. Ports 8797/8798/8787 untouched; only my PIDs killed.
- Source copy: user_version 52, integrity_check ok, no ticket_events tables (so this is a real 52 -> 53 migration).
- After first start: user_version 53, ticket_events and ticket_event_cursors created; counts identical before/after: tickets 650, session_instances 5, agents 17, ticket_files 2062, attention 90, mail 2887 (1646 unread), sessions.json 5.
- Cursors seeded at 0 (core.triggers, core.done-worker-reaper, core.ticket-files); no backfill of old tickets (events = 0 on boot).
- Moved ticket-ca709f71 todo -> backlog -> todo via the CLI on the copy: 38 events written (1 `moved` backlog, then 36 `updated` for the other todo tickets at the same millisecond, then 1 `moved` todo); all 3 cursors advanced to 38.
- Restarted the daemon on the same copy: still 38 events, cursors 38, user_version 53, same counts; no replay output, no nudge/reaper lines, no process spawned.
- Observation (not a bug, worth knowing): one todo/backlog move emits an `updated` event for every other todo ticket (O(todo count) events per move, from the position renumber). Consumers filter, but the event table grows ~37 rows per move at this board size.
- Previous-schema fixture: SchemaMigrationTest (in the infantry suite, green) covers older schemas; no separate fixture file found.

### 3. Flaky hunt: 15 x [LockAttributionTest, StallRunnerTest, ProjectGitPaneUiTest, KeepAliveSettingUiTest]
Each iteration forced a rerun of both test tasks (`--rerun`). 15/15 BUILD SUCCESSFUL, 0 failures (7 + 12 + 19 + 1 tests per iteration). No flake reproduced; nothing to fix. (Not a proof of absence; Windows desktop, one machine.)

### 4. Packaging
- `installDist` + `verifyDaemonHalvesGuard`: 7 daemon halves staged, every declared daemon.jar present: Infantry_Agents, Roles, Attention, Settings, Board, Usage, Processes.
- `dockerContext -Pdemo=true`: BUILD SUCCESSFUL, 8 halves (the 7 plus Demo_Lock).
- NOT run: `createDistributable` (installer packaging; not requested as a build per the how-we-test rules), the docker image build itself.
