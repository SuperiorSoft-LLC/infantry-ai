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
