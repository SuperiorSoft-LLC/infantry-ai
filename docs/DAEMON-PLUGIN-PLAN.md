# DAEMON-PLUGIN-PLAN: DP-5 breakdown (ticket 8837a4df)

Goal: move the board family (backlog, epics, stories, mvp, context, brief, ledger, ticketfiles) out of core into
`plugins/official/Infantry_Board/daemon`, against `DaemonApi` only, with parity (existing board/ticket/story/epic daemon
tests pass unchanged). Start state: dev tip 24ed8b90 (DP-4c merged); `BoardImportGuardTest` lists the 14 remaining importers.
Every ticket keeps the build green and the wire unchanged. Test rule for all: compile and run the existing scoped guard tests
only; no new suites, no full suite; the worker appends a `TEST-TODO:` section (behaviour, class/function, inputs, expected) to
its report and ticket; QA turns those into cases in one batch.

## Tickets, in order

1. **DAEMON-PLUGIN-3 / d59300af (DP-5a, w3)**: settings (WeightExecutors), db (TicketTypeMigration), TicketStartCharge, ledger stop importing board packages.
2. **DAEMON-PLUGIN-4 / 56e73894 (DP-5b, w4)**: triggers and ticketfiles use `Tickets`/`TicketEvents` (additive API members if needed).
3. **DAEMON-PLUGIN-5 / 65094bfb (DP-5c, w5)**: `BoardHost` port in agentHqDaemonApi (storage tx, worktree/git, sessions, settings, clock) + `LocalBoardHost`; nothing consumes it yet.
4. **DAEMON-PLUGIN-6 / 6f045cbf (DP-5d, w4)**: DaemonMain board frame switch becomes a registered handler table with a "no board" refusal fallback.
5. **DAEMON-PLUGIN-7 / 59edf02f (DP-5e, w4)**: new Gradle module `agentHqBoard`; epics, brief, ledger, context, ticketfiles move into it.
6. **DAEMON-PLUGIN-8 / a1c88bcc (DP-5f, w5)**: backlog (TicketBoard) moves into agentHqBoard, via BoardHost.
7. **DAEMON-PLUGIN-9 / 62370fc0 (DP-5g, w4)**: board/story handlers and stories move; project/workspace registry stays core (worker confirms).
8. **DAEMON-PLUGIN-10 / 405bb59e (DP-5h, w5)**: module becomes Infantry_Board's loaded daemon plugin; DaemonMain constructs no board; ratchet list empty.

Order logic: 1-2 remove the small importers (cheap, shrinks the ratchet); 3 is the design that decides whether the move is
possible at all; 4 removes the DaemonMain frame coupling; 5-7 move code inside the build first (module boundary enforced by
Gradle, still one jar), 8 flips it to runtime plugin loading. Each of 5-7 is revertible alone.

## Risks

* **Storage coupling (biggest).** TicketBoard needs SQL transactions over `tickets*` tables, and `ticket_events` is written inside the commit transaction (DP-4b). The BoardHost port must preserve that atomicity; if a raw transaction handle is the only way, the plugin is "official, trusted" only (not safe for community plugins). Plugin-owned tables stay a later step.
* **Lock order.** Board monitor vs TriggerService lock (never call the board under the trigger lock) must survive DP-5b/5f.
* **TicketBoard is 1999 lines** and was touched by 9 tickets: expect merge conflicts if other tickets edit it while DP-5f runs. Freeze edits to it during 5f.
* **Startup order / missing plugin (DP-5h).** The board must load before the first frame and before session restore; the daemon must run with refusals when absent. Worker mid-run on plugin reload is undefined until 5h states it.
* **Project registry** (mvp/Project*, Workspace*) is arguably core; DP-5g defaults to leaving it in core.
* **Version bumps.** DP-5b/5c add API members (additive, minor). Anything that changes a refusal text or frame is NOT additive: the worker stops and mails the PM.

## Owner decisions (not decided here)

1. Whether any contract bump that changes visible behaviour (refusal wording, frame shape) is acceptable; default is none.
2. Whether projects/workspaces are core or board-plugin (default: core).
3. Whether users may remove the Infantry_Board plugin (D3 says officials can be removed) and what the daemon should show then.
4. Licence headers / MPL vs Apache placement of the moved code (parked, D13).
5. Whether the plugin may use a raw DB transaction handle (trusted-official only) or must wait for plugin-owned tables via `DaemonApi.storage`.
