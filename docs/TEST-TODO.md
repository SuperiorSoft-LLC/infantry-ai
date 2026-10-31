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
