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
