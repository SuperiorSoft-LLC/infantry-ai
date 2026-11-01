- DP-5f-3b3: with projectFolders on, start a ticket in a project with no directory (expect refusal), and check agent lane queueing / clone creation (LANE-CAPS) end to end.
- DP-5f-3c: event delivery order — kill a ticket worker mid-hand-out and with queued tickets: run row written once, lane drains once, in arrival order. Lock hook parity: an Exit emitted under the sessions lock is queued (not inline), one on the board's own thread applies inline.
- DP-5f-3d: kill/restart a phantom ticket worker (sweepWorkers) and a hand-out that the launch gate refuses; check the ticket-start detail line (context limit / effort notes) still reads the same; daemon with no session manager: start and sweep answer 'unavailable'

## DP-5h-1 (ticket-405bb59e): BoardWiring extraction
- TEST-TODO: plugin missing: a daemon with `boardWiring == null` starts and every board-family frame answers "this daemon has no board" (today the Daemon always builds one; the null path is untested).
- TEST-TODO: plugin reload mid-worker: workers stay with core's SessionManager; the new board must re-adopt via sweepWorkers + clearQueueMarks; session events during the swap must be queued, not lost.
- TEST-TODO: startup order: the board plugin loaded before the first frame and before session restore.

## DP-5h-2 (ticket-ac0f234f): plugin API for board frames
- TEST-TODO: a real board plugin registering the DP-5d table through `api.frames.table` and starting a worker through `api.spawns.charge`: depth/pod-cap refusal text identical to the core path.
- TEST-TODO: no-board daemon answers project_*/workspace_* frames from core (new behaviour) and ticket_* with the no-board refusal.
- TEST-TODO: community plugin without `sessions.spawn` gets NoSpawnCharge.

## DP-5h-3 (ticket-498dfe11): BoardDaemonPlugin shading + swap feed
- TEST-TODO: plugin missing: remove Infantry_Board-daemon.jar from the plugins dir; daemon starts, every ticket_* frame answers '<type> refused: this daemon has no board', session events queue (SwapFeed.pending grows to its limit, then drops oldest).
- TEST-TODO: reload mid-worker: start a ticket worker, reload Infantry_Board; worker keeps running in core, the new board re-adopts it (sweepWorkers) and clearQueueMarks runs; the status/exit events of the gap arrive in order.
- TEST-TODO: startup order: with the plugin installed, the first client frame and the session restore both see a built board (needs DaemonMain to build the plugin host before restore; not done in this ticket).
- TEST-TODO: the shaded Infantry_Board-daemon.jar contains com/superiorsoftllc/agenthq/backlog/TicketBoard.class and no daemon-api classes.

## DP-5h-3a (ticket-498dfe11): board frame registration on the ports
- TEST-TODO: a session-originated ticket_move through the board-side `registerBoardFrames` (FrameBinding + SpawnCharge) hits the depth / pod-cap refusal with the same text as the old core TicketStartCharge path; the worker joins the starter's pod and records its spawner.

## DP-5h-3a part 2 (board runtime port)
- With `coreBuiltBoard` false (after DP-5h-3b), the plugin-built board answers ticket frames and a session-originated start is refused at depth / pod cap exactly as the core path did (no scoped test drives the plugin-built board end to end yet).
- Epic and story frames are unregistered when the plugin builds the board until 5h-3b supplies their handlers.

## DP-5h-3a2 (ticket-d760d11c): story / epic inputs
- TEST-TODO: with DP-5h-3b flipping `coreBuiltBoard`, the plugin-built board answers story_* shims, epic_save/epic_list/epic_base, and the RELATED block on ticket_save like core (an unknown project's refusal text may differ from ProjectStore.get).
- DP-5h-3b (partial): with the board present, a worker's turn end still records its ticket outcome (events now pass through SwapFeed -> LocalBoardHost.publish). Not yet: early plugin host / board-less daemon (see report).
- DP-5h-3c: boot the real daemon with the board plugin; confirm the host loads before session restore (log order), events during a plugin reload are delivered in order after re-attach, and a reload re-adopts running workers (sweep + queue marks). Detach/attach on reload is only unit-tested (BoardSeamTest); no reload entry point exists in DaemonPluginHost yet.
- DP-5h-3d-1: 6 E2E tests (BacklogLane, McpTicketMove/Delete/Images/AnswerRace, TicketImageAttach) fail on dev too: they pass `mcp serve --lineage-dir`, a flag RUNTIME-LOAD-7 removed ('unknown flag'); fix the tests' args. TicketStartDaemonTest 'gated failure #1' flake fixed in TicketStartSupport (run ERROR frames carry a sessionId).

- DP-5h-3e-a: in the built app, a ticket worker's first turn still carries its brief, the 60 s worker sweep still flags an unbriefed worker, a harness switch (handover) still resumes with the brief, the roster shows ticket titles, a review report's `Files read:` line is still saved, and a start by an agent is still charged to its pod.

- DP-5h-3e-b (the flip): not run, no runnable Gradle project for the plugin's own tests (`BoardBuildTest`, `PluginCoreAccessTest`); no `invoke` case yet in `PluginCoreAccessTest`. Not covered: a real-daemon start-charge refusal test (none exists in the tree). The full suite was not run (staging only).

- DP-5h-4: in the built app (plugin present) create, move, start and finish a ticket and open the board/epic panes: identical to before; with the Infantry_Board plugin removed the app starts and every board frame answers the no-board refusal. Not run: the full suite and the E2E set (scoped tests only).
## DP-5h-3e-c (ticket-33c67672): plugin-present start charge
- TEST-TODO: PluginBoardStartChargeDaemonTest -- session-originated ticket_move to in_progress through the plugin-built board: podSpawnDepth=0 -> 'spawn refused: depth limit', podSpawnCap=0 -> 'spawn refused: pod cap' (ticket stays todo); defaults -> started and the worker's spawnerSessionId is the starter; beforeStart sees boardPorts.ops() != null and the first ticket_list answers `tickets`.
## DP-5f-6 (ticket-8b50393b): TicketBoard tests over the fake host (partial)
- TEST-TODO: RoutingRulesTest, BacklogLaneTest, TicketPacingGateTest (agentHqBoard, over InMemoryBoardHost) -- start precedence (rule beats role binding, ticket pin beats rule), a rule naming an unknown provider parks the ticket in todo with "rule 'heavy'" in the detail and reports once, per-lane cap and pacing gate refusals. Inputs: weighted tickets with rules/pins/lane caps; expected: same assertions as before the move, started model read from host.starts.
- TEST-TODO: still in agentHq, not yet moved: RoleBindingSpawnTest, ContextLimitTest, EffortTest, TicketWorkerInvariantTest, TicketSkillAttachmentTest, TicketWeightTest, TicketBoardTest, KnowledgePtrTest; TicketEventLogTest/TicketTypeMigrationTest/LocalBoardServicesTest/StoryMigrationTest not yet rewired to BoardOps.

## CORE-SPLIT-4a1 (ticket 6dad821a)
TEST-TODO: daemon wiring builders (`daemon/wiring/*Wiring.kt`) are a pure move of DaemonMain construction. Behaviour: a daemon started as before has the same stores, frames, trigger service (autoStart=false), owner-question sink, attention eventSink, run manager and chat-image retention. Check: start the daemon, save a memory note/decision/issue, fire a trigger, ask an owner question; all behave as before.
TEST-TODO (CORE-SPLIT-4a2): behaviour: daemon start order unchanged after moving agents/prompts/board-host construction to daemon/wiring. Class: a daemon boot smoke over AgentsWiring/PromptsWiring/BoardWiring. Inputs: start with and without a board (withBoard=false). Expected: pending prompts reach clients, role-bound agents resolve, no board => no ticket services and no core host.

- CORE-SPLIT-4a3: at QA, start the daemon and confirm harness modules still load/sync and /plugins lists them (HarnessModulesWiring); BoardPortRef/OrchestratorHub and the PluginBoardPorts attach stay in DaemonMain.
## STACK-NEUTRAL-6 (ticket-89466254)
TEST-TODO: SettingsService.migrateRepoProfileAdditions - existing install with no stored `gradle` override: stored watch.toolProfiles gains the gradle profile + the two fork templates (resource watch/repo-profile-additions.json), second call returns false; fresh install or existing gradle override: untouched. Expect owner's worker GRADLE_OPTS identical to before.

TEST-TODO (STACK-NEUTRAL-3, ticket cd3d95bc): behaviour: `--reader <name>` resolves from the tool profiles' `progress` list. Class: ProgressCliTest `shipped task reader...`. Inputs: line `> Task :app:compileKotlin` with --reader gradle; unknown reader name. Expected: status "gradle :app:compileKotlin" (only while the job is indeterminate); unknown name = error "--reader expects one of junit, gate, gradle". Also at QA: a custom profile with a `progress` entry in watch.toolProfiles works with `agenthq run --reader`.
## STACK-NEUTRAL-4 (ticket-bfe9e7d0)
TEST-TODO: WorktreeProvisionHook seam (worktree/WorktreeProvisionHook.kt). Behaviour: WorktreeProvisions runs each hook on worktree creation, joins warnings with "; ", none -> null. Class AndroidSdkProvisionTest: gradle-file repo + no SDK -> same warning; SDK found -> same local.properties; non-matching repo -> no file, no warning. Detect files now come from resources worktree/sdk-provision.json. Not done: DaemonApi hooks.worktreeProvision (plugin registration) left for a follow-up.

TEST-TODO: STACK-NEUTRAL-5 (ticket-18370e32)
- Start brief watch line: BoardRuntime.watchedPrograms() empty unless limits.watch.enabled=1 and limits.watch.forced=1; then the shimmed programs of the profiles in force. Check on a live project: watch on -> brief names the profile programs; watch off -> no WATCHED line.
- BG_RUN_LINE no longer carries the Windows wrapper hint: put it in this project's knowledge/role notes (`agenthq memory save`): "On Windows run Gradle as gradlew.bat (./gradlew fails under agenthq bg run)".
## DAEMON-STORAGE-1
- Plugin storage (PluginStorageTest): prefix refusal (core table, join, comma list, schema-qualified, ATTACH/PRAGMA, two statements), adopt list official-only, `migrate` idempotent + failed migrate rolls back, `transaction` rollback, `storage` grant. Not tested: a real plugin jar calling api.storage through DaemonPluginHost, community adopt refusal at load, concurrent writers.

## AR-5 (ticket-d742b63c)
TEST-TODO: typed service registry. Class ServiceRegistryTest (done, unit): provide/find, missing = null, duplicate provider refused, community without `services.provide` refused, unload closes handles, stale handle safe. Not tested: a real plugin jar calling api.services through DaemonPluginHost (QA: a plugin publishes an interface, a second plugin finds it, disable the first, find returns null); community grant path through module settings.
## DP-6 (ticket-9d0a581b)
TEST-TODO: pipeline in Infantry_Board. Done (PipelineStoreTest, real SQLite, 11 tests): store rules, handler answers/refusals/push. Not tested (QA): (1) a real daemon with Infantry_Board loaded answering `pipeline_show`/`pipeline_record` through DaemonPluginHost, author label "role (agent)" from `sessionReads.find`, `pipeline_state` push reaching a second WebSocket client via `boardRuntime.push`; (2) with Infantry_Board absent, `pipeline_show` answers `pipeline_show refused: this daemon has no pipeline`; (3) `agenthq pipeline record` + MCP `agenthq_pipeline_record` end to end; (4) existing McpServeTest has 15 golden-drift failures and BuiltinToolsListGuardTest 2 (`agenthq_read` in McpPluginTools) that predate DP-6.

## DP-7a (ticket-6df0d6c2)
TEST-TODO: knowledge wire DTOs moved to agentHqProtocol (same packages). Covered: OrchestratorBoundaryGuardTest (cli>memory/issues/decisions 0), DaemonCoreGuardTest (protocol leaf holds memory/issues/decisions), existing Memory/Issue/Decision tests. QA: `agenthq memory save/get/list/search`, `agenthq issue open/attempt/fix/list`, `agenthq decision add/list/show` against a running daemon return the same output as before the move.

## DP-7b (ticket-1580ce99)
TEST-TODO: Knowledge port. LocalKnowledgeTest: digest/section/search/noteCount over real stores; ServiceKnowledgeCounter renders identical to renderKnowledge and gives null section with no provider. Not tested (QA): a real daemon first turn after the plugin host unloads/never provides (expect no KNOWLEDGE section, no error); KnowledgeFirstTurnTest known-failing case compared to base.

- DP-7c TEST-TODO: CliKnowledgeTest (capped lists with decisions/issues; under-caps output) needs the real Infantry_Notes plugin loaded in a daemon; core now refuses decision_list/issue_list. Re-add as a cross-module test at the QA checkpoint (the CLI knowledge command is unchanged).

## DP-7d (ticket-092125b5)
TEST-TODO: session-ended hook. MemoryScopeTest.sessionEndedListenersHearAKillAndARemovalAndCanBeClosed: SessionManager.addSessionEndedListener hears kill + delete, a closed handle hears nothing. Not tested (QA): a real plugin loaded in a daemon receiving events.onSessionRemoved (DaemonPluginHost wrapper, disabled-plugin skip).

## DP-7e (ticket-0839e57f)
TEST-TODO: mail port. SessionNotifierTest (unit): notifyRole reaches only live sessions of the role in the project, nothing for none, failing send not counted, grant refused for a community module, default port delivers nothing. Not tested (QA): a real community plugin with and without `sessions.notify` in modules.json receiving a real mail in a live daemon; the ROLE-NOTES-1 role-note announcement end to end after the DaemonMain rewire.

- DP-7f: with the real Infantry_Notes plugin loaded in a daemon, run `agenthq memory save/show/index/search/append/edit/delete` (project, role and session scope) end to end; this replaces the deleted core CliMemoryTest, MemoryDaemonTest and MemoryModesWireTest (daemon-socket tests that needed the in-core store). Also check a role note change mails a live session of that role, a removed session's notes vanish, and notes survive a daemon restart.
- USAGE-FIT (ticket-665db9e0) TEST-TODO: with a real daemon and the usage-meter db holding >= 8 weekly steps, open Stats, expand "Usage fit" (collapsed by default, nothing around it moves) and check headline/confidence/table/3 charts read right in dark and light, and the 5-hour toggle; with < 8 steps expect "not enough data yet". Check a plugin-less Stats pane still draws exactly as before. agentHqUi test sources do not compile on the base (TicketImagesUiTest/StartSessionShotsMain), so InjectorUiTest and a Stats tab-tree render test could not run; no screenshot taken.

## DP-10a (ticket-6aa4b885)
TEST-TODO: project files + chat images in the plugin. With a real daemon and the Infantry_Chat plugin: (1) attach a PNG in chat and a ticket image; expect files under `<project>/.agenthq/uploads/chat|ticket/<sha>.png`, `uploads/.gitignore` = "*", same answer JSON as before. (2) Remove the plugin: `image_upload` answers "image_upload refused: this daemon stores no uploads". (3) Set `images.chatImageRetentionDays` to 1, backdate a chat upload 2 days, restart: swept at start; ticket upload kept. (4) A community plugin without the `files` grant calling `api.files(...)` is refused "missing grant 'files'". ProjectFilesTest covers scope refusal; ImageUploadsTest the retention clock.
- TOKEN-9: with the app running a few hours, call agenthq_pace with samples:"true" and confirm rawSamples rows carry raw percent, prev, delta and per-session tokens; check whole-percent steps vs token burn.

## DP-10b (ticket-1b61bfef)
TEST-TODO: approval rules in Infantry_Approvals. With a real daemon: (1) answer a Bash approval card "Always allow" in a project; a NEW session of that project calling `git ...` is auto-allowed (one `auto-allowed by rule Bash git` line, no card) and `approval_rules` lists the rule (Settings > Tool approval); delete it and the next call asks again. (2) Disable or remove Infantry_Approvals, restart: every prompt asks, "Always allow" lasts only the session (same tool again in that session has no card, a new session asks), no error. Rules stored before DP-10b (settings row `toolApproval.rules`) are NOT migrated: the owner is asked again once. Classes: ApprovalRulesPortTest (core), ApprovalRulesTest (plugin), DaemonCoreGuardTest.
TEST-TODO (ATTN-1): AttentionServiceTest: local provider raise/list/resolve keeps kinds and texts; FoundAttention with no provider returns null/false/empty without throwing; provider appearing later is picked up. Manual: stall a session / trip a trigger, the attention item still shows in the UI.

## ATTN-2a (33c4894f)
TEST-TODO: `events.broadcast` / `hooks.connected` / `QuestionsOpen` (DaemonPluginHost, DaemonMain). Inputs: an official plugin calls `api.events.broadcast("x","{}")`, registers `hooks.connected { listOf(Frame("x","{}")) }`; a community plugin does the same. Expected: a connected client gets the frame in both cases for official; community throws IllegalStateException (plugin disabled). Stall detector: a session with an open question published via `QuestionsOpen` is not nudged.

## ATTN-2c (77434467)
- [ ] ask E2E on a real daemon: `agenthq ask --recommend ...` from a session -> `question` push and `questions` replay on connect reach a client -> `question_answer` mails the pick to the asking session (body carries `(thread question:<id>)`) -> an unanswered question with `--default-after` auto-picks via the 15 s `api.schedule` sweep -> killing the session orphans it. No daemon-level test covers the plugin path (only OwnerQuestionsTest on the service and the store).
- [ ] per-session prompt-timer override no longer feeds the default auto-pick of a question (core state); only global `prompts.timeoutSec` does. Decide if a seam is wanted.

## TRIG-1
- TriggerPortsGuardTest `triggers imports nothing outside protocol and daemon api`: scans triggers/*.kt imports; expected only protocol, daemon.api, kotlin/java.
- TriggerServiceTest `startNext is not called under the trigger lock`: startNext blocks; events() from another thread returns within 2 s.
- Pre-existing (also on base): TriggerDaemonTest aPromptTriggerForAnUnknownAgentTripsAndStartsNothing, aFailedRunTripsTheTriggerRaisesAttentionAndSuppressesUntilArmed, aTriggerRunningWhenTheDaemonStopsComesBackTripped fail; unrelated to this change.

## TRIG-2a
- TEST-TODO: POST a template public route (`/hooks/{id}`) from a real plugin without a bearer token: expect the handler to see pathParams; with the plugin disabled expect 503.
- TEST-TODO: a plugin `hooks.status` field appears under `pluginStatus` in GET /health.

- TEST-TODO (TRIG-2b): run TriggerStoreContractTest (Infantry_Triggers/daemon) at the QA checkpoint; once TRIG-2c switches core over, check triggers survive a daemon restart through the plugin store (create, fire, restart, list, event pruning to 500).

## MAIL-HOOK-1 (pre-delivery mail hook)
TEST-TODO: install `on-mail-sent.lua` (plugins/official/Infantry_Scripting/project-scripts) with hook `on="mail.incoming"`, routeRoles ["pm"] and the vc-merged / vc-duplicate / stale-auto-failure / worker-done rules; send a routine VC mail to an idle PM: expect it READ, no PM turn, ticket comment written; send an unmatched mail: PM woken once (no second delivery). Break the script (syntax error): mail still delivered. Check why agenthq_prompt to an idle session waits 30 s (see report).

## MAIL-HOOK-1b (860c0da2)
TEST-TODO: on a real daemon (port 8812, temp state dir): session A `agenthq script hook add --on mail.incoming --to <B> --script pong`, A mails B "PING n"; confirm B's reply and no wake. The in-process ping-pong (ScriptHooksTest) fakes the mailbox, not MailRouter. Also confirm a hook disappears from `script hook list` after its session is removed.

## DISCONNECT-1 (0af901f2)
TEST-TODO: on a TEST daemon only (port 8812, temp AGENTHQ_STATE_DIR; never the owner's 8797/8798/8787), connect a real app/client, then kill only the client's TCP connection (not the daemon): expect "reconnecting..." with a countdown, then back to connected with the same sessions and no second daemon; a relaunch of the app is still refused by the state-dir lock.
TEST-TODO: with a long-running agenthq_session_remove / agenthq_prompt in flight, send a malformed frame on the same ws: expect an `error` frame for that frame (log "ws frame failed, connection kept") and the ws stays open.
TEST-TODO: on a test daemon, close a session whose tree holds a `java.exe --add-opens ...` process from the app's own runtime: expect it untouched (TreeKillRule). Not exercised end to end: the owner's original trigger was never reproduced.

TEST-TODO (SCRIPT-VIS-1): in a live daemon with a mail.incoming hook that handles a mail: agenthq_script_stats shows the run (AUTOMATION line, handled) and turnsSaved +1 for the recipient; an ordinary mail shows wakesByReason.mail +1; an owner message shows owner +1. UI (automation line in the session transcript, session panel counts, project summary) is NOT built yet.

## TEST-TODO: SCRIPT-VIS-1b (e65bfcf2)
- Live ping-pong on a TEST daemon (own state dir + port, kill by PID): PM <-> VC mail hooks live; send a ping. Expect: both chats show an "AUTOMATION · pong · handled ..." line (gear mark, expandable to actions and ih.log lines), no session woke, `agenthq_script_stats` shows 2 turns saved.
- While a hook runs, the session list carries `automation {script, hookId, since}`; the session sits under Running with BUSY/IDLE unchanged; it moves back when the run ends.
- Restart the daemon: the run log (newest `limits.scripting.runLogMax`) is still there and the chat lines come back via `timeline_list`.
- Disable/uninstall Infantry_Scripting: chat shows no automation lines and works as before.
- Lost verdict: make a mail hook exceed its time limit; the run record reads "verdict-lost", FAILED, and the mail is delivered.
- NOT DONE in 1b: automation badge in the Sidebar group, wake reasons on the activity line, turns-saved/wakes panel and project summary, UI sending `timeline_list` on connect.

## TEST-TODO: SCRIPT-VIS-1c (7d548a77)
- Ping-pong on a TEST daemon (own state dir + port, kill by PID): PM <-> VC mail hooks live, send a ping. Both chats show their AUTOMATION row, nobody woke, the session panel line reads "turns saved N" (N total 2 across both), the project part of the line adds the project totals.
- While a hook runs, the session's sidebar row shows the "AUTO" chip and sits under Running; the chip goes when the run ends. The row's activity text ends with "woken by <reason>" after a real wake (owner / agent / mail).
- Restart the UI against a running daemon (reconnect): the AUTOMATION rows and the stats line from before are back (UI sends `timeline_list` on every connect).
- Disable Infantry_Scripting: no rows, no chip, no stats line; chat works as before. NOT run by hand: the throwing-plugin case is covered by unit tests only.

## SCRIPT-CMD-1
- TEST-TODO: set scripting.exec.enabled=true, run a script with `ih.exec{command={...}}` from a real agent session: exit code and stdoutTail arrive in phase 2; with the setting off the tool refuses and names the setting.
- TEST-TODO: background=true run + a hook on `exec.done` continues the protocol; the Infantry_Scripting settings page shows the six scripting.exec.* fields.
- TEST-TODO: the CLI parity command `agenthq exec` was NOT added (see report); call the tool via `agenthq_exec` MCP / `script_run`.

## SCRIPT-TICK-1
- TEST-TODO: add a hook `on="every 30s" to=<your session>` for a script that calls `ih.progress{mode="status", label="..."}`: the card of that session shows the label and updates every 30 s; end the session and confirm the hook is gone from `agenthq script hook list`.
- TEST-TODO: start `ih.exec{command=..., background=true}`, add an `exec.tick` hook (see docs/SCRIPT-TIMERS.md): the card shows steps parsed from the output and `fail` on a failing line; the hook stops being called after exec.done.
- TEST-TODO: a deliberately slow tick script (longer than the period): one "skipped (never stacked)" log line, no pile-up. Not run by hand; unit-tested with a nested tick only.
- TEST-TODO: progress with a `sessionId` of another project is refused (unit-tested; try it from a real second project).

## SCRIPT-SAVE-1
- TEST-TODO: from a real worker session run `agenthq script save probe --body -` (pipe `ih.log("hi")`), then `agenthq script list` (shows size, saver, hooks) and `agenthq_script_run name=probe` with no restart; a source with a syntax error must be refused with the Lua parse error and leave no file.
- TEST-TODO: add a hook on `probe`, then `agenthq script delete probe` must be refused naming the hook; remove the hook and the delete works. Not tried by hand with the real Lua engine over the wire `check` op (unit-tested with a fake engine; Lua check compiled but only built, not exercised).
