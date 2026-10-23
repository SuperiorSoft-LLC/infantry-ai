# Pipeline watchdog (ticket 886604d5)

The daemon tells "idle with nothing to do" from "idle but should be doing something", nudges, and escalates. No OS scheduled
task: one daemon thread (`AgentWatchdog`, `mailbox/AgentWatchdog.kt`) ticks every `AGENTHQ_WATCHDOG_POLL_SEC`.

## States (`SessionRecord.watch`, wire `watch`, derived and never persisted across a daemon start)
| State | Meaning |
|---|---|
| WORKING | a turn or background process is running and produced events within the quiet threshold |
| WAITING | idle with a pending item younger than the stall threshold, or blocked on someone else's reply (`needsInput` mail) |
| STALLED | idle, an unanswered item pending for the stall threshold |
| QUIET | running but no session event for the quiet threshold (a hung build) |
| UNREPORTED | a session whose REPORT BACK status is `unreported` (finished without its report mail) |
| IDLE | nothing pending |

Pending item = a mail to the session (id, agent id or role) since it spawned, from anyone but `system`, not ANSWERED, with no
mail from the session sent after it. Items older than 6 h are history.

## Ladder (per pending item)
1. STALLED/UNREPORTED: one `system` nudge mail per nudge interval, up to the cap (default 1). Never sent to a session with an active turn or background work (those are WORKING/QUIET).
2. After `PM_AFTER_NUDGES` nudges went unanswered for a full interval: ONE batched mail to role `pm` (wakes an idle PM through the router's pump).
3. One more interval: one `stalled` attention item (outcome `watchdog_stalled` / `watchdog_unreported`) for the owner.
A QUIET agent is only reported to the PM, once. The PM role itself skips step 2. The ladder restarts on a daemon restart.

## Thresholds (environment or JVM property)
`AGENTHQ_WATCHDOG=off` disables. `AGENTHQ_WATCHDOG_STALL_MIN` (5), `_QUIET_MIN` (15), `_NUDGE_MIN` (5), `_NUDGE_CAP` (1: one nudge per stall, then escalate), `_PM_AFTER_NUDGES` (1), `_POLL_SEC` (30).

## Known limits
- QUIET measures silence of the session's own events. Output of a background process (a long Gradle build) is not observed, so a
  silent-but-alive build and a producing one look the same after the quiet threshold.
- The Triggers tab (manual/schedule/ship/webhook; actions start_next, prompt) cannot wake an existing session, so it is not used; the PM is woken by mail.
- Ticket b86481ae owns the finish notice; UNREPORTED here only reuses the existing REPORT BACK status.
