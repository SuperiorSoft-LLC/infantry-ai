# Pipeline audit, 2026-11 (PIPE-AUDIT-1)

Question: how much of the ticket-to-worker loop that works in AgentsRoom works in the Rust Infantry AI app
(feature-rust-port)? This is an audit: no product code changed.

## How it was checked

- Code read: `rust/crates/infantry-board/src/dispatch.rs`, `infantry-orchestrator-api/src/work.rs`,
  `infantry-daemon/src/compose/{adapters,prompter,worktree_sweep}.rs`, `infantry-daemon/src/sessions.rs`,
  `infantry-harness-claude/src/interactive.rs`, `infantry-mail`.
- Live: a scratch daemon (port 19341, scratch `--state-dir`, no `INFANTRY_*`/`AGENTHQ_*`/`CLAUDE*` inherited), the
  installed CLI 1.157 against a daemon build 1.155/rust-0.1.4 (a copy of a debug build that is an ancestor of
  feature-rust-port; **not** the branch head), a toy git repo, two one-line tickets, Claude interactive (default) with
  `haiku`. The live daemon (:18990), the owner state dir and the installed app were not touched.
  Because the binary is older than the head, a live result that differs from the code is marked "code differs".

## The six steps

| # | Step | Verdict | Evidence |
|---|---|---|---|
| 1 | Ticket is the whole brief | **WORKS** | `ticket create --desc` stores goal/files/done-when/rules; the worker's first prompt is the composed brief (`dispatch.rs` `launch`, `brief_sources`). Live: the worker read the description and did the work. The mandatory DONE report is only text in the description: nothing enforces or templates it. |
| 2 | Start spawns the worker | **WORKS** | Live: `ticket start 776427a0` cut worktree `toy/.worktrees/ticket-776427a0-add-hello-txt`, branch `ticket/776427a0-add-hello-txt` from `8a0d22d`, launched claude/haiku. `dispatch.rs:368` `start`. **Pre-condition surprise:** a fresh daemon refuses with "ticket has no role" (`dispatch.rs:396`) and a daemon without the Roles plugin has no roles at all (installed app plugins dir had none). |
| 3 | DONE mail wakes the lead | **PARTIAL (code yes, live not proven)** | `dispatch.rs` `report_done` (≈1210) sends `[AUTOMATIC] ticket done` to the ticket's `started_by` session via `notify_session` → `Daemon::deliver` (a turn into an idle session); failure goes by `alert_session`. Live: both tickets reached done by the done protocol, but the starter was the CLI/owner (no session), so no lead was woken. `started_by` is only set when the start comes from a session (`INFANTRY_SESSION_ID`). A worker's own mail to the PM is a separate path (`infantry-mail`), also untested. |
| 4 | One turn: close, clean, start next | **PARTIAL** | Start-next exists (`ticket_start_next`, `StartOrigin::StartNext`, `conn.rs:36`) and lane caps default to 1 per role (`project config set <id> <role> <n>`). Live: with the lane busy `ticket start` answered "queued … every agent of its lane is busy", and after I unassigned the first ticket the board **started the queued WORLD ticket by itself** and both ended in `done`. Gaps: (a) done workers stayed alive and idle after done (`auto-remove-done-workers` default off, so the lead must close them); (b) worktrees are removed only by a periodic sweep after the branch is merged (`worktree_sweep.rs`, `worktrees.sweepSeconds` 300 s), not as a step of the lead's turn; (c) no single lead operation "mark done, close worker, remove worktree, start next" - it is four calls; (d) the "1 work lane + 1 build lane" is role-based caps, not a named work/build pair. |
| 5 | Recovery | **PARTIAL** | Code: starting an in_progress ticket whose worker is gone restarts it (`dispatch.rs:377`, `ended()` = Exited or Failed). Live: killing the claude process by PID turned the session FAILED, the ticket stayed in_progress with an issue comment and no one was told (no starter). `ticket start` printed the same session on the same worktree but it failed again at once (`the interactive claude exited (code 1)`, resume of a session cut mid tool call - cause not confirmed), and the next `start` said "already in progress: nothing new was started" (code differs: the head treats Failed as gone). What worked: `ticket unassign --now` then `start` - new session on the **same worktree and branch**, committed work kept. So recovery exists but needs the lead to find the failure and do two steps. |
| 6 | Questions go to the owner as a card | **WORKS** | Live: `infantry ask --question … --option "label|consequence" --recommend … --reason …` with `INFANTRY_SESSION_ID` set returned `{"questionId":…,"note":"non-blocking: carry on…; the owner's answer arrives as mail"}`; code `OwnerQuestions` + `QUESTION` frame (`compose/mod.rs:226-233`). Answer-arrives-as-turn was not exercised (needs a UI client). |

## Bigger finding (not one of the six): a worker can stall silently on a tool-approval card

Default `toolApproval.mode` is `ask` (`prompter.rs:DEFAULT_MODE`). With no UI client connected, the first Bash call of
the haiku worker raised an owner approval card nobody answered. For 5+ minutes the session read `BUSY`, watch
`WORKING`, `pending: null`, `nudges: 0`; `ticket` showed "doing 'state busy' | out 3m ago". The transcript
(`~/.claude/projects/…/213c1597….jsonl`) ends on the unanswered Bash tool_use. Nothing woke the lead or the owner
(the "Worker blocked by a permission dialog" notice at `dispatch.rs:1241` fires only when the run ends). The loop
stops without any signal, which is the worst failure for an unattended pipeline. I set `toolApproval.mode allowAll` on
the scratch daemon to get past it.

## Smallest fix per gap, value order

Rule 1 of AGENTS.md applies: nothing below adds a feature to core. "Core" means a generic mechanism the board plugin
or a Lua hook uses.

| # | Gap | Smallest fix | Where |
|---|---|---|---|
| G1 | Silent stall on an unanswered approval / idle-busy worker | Stall watch: a worker with no output and no hook event for `board.stallSeconds` (setting, default 300) raises `ticket.run_stalled` and an alert to the starter and the owner; the card for an open approval is listed in `board` output as "waiting on owner". | core (watch + hook kind, generic); the notify rule is a Lua hook |
| G2 | The lead needs 4 calls to finish a ticket | One `ticket_close` (aka `ticket_finish`): done-move if needed, stop worker, remove worktree if merged or clean, `start_next` for the lane; returns the next ticket. | board (orchestration) |
| G3 | Failed worker recovery is manual and sometimes dead-ends | On `RUN_FAILED` with a resume that exits < N s, fall back to a fresh session on the same worktree (what `unassign`+`start` does) and say so in a comment; add `ticket_restart` that does the same on demand. | board |
| G4 | DONE report wake not proven for a real lead | Add a daemon test: a PM session (idle) is started, a worker is started with `started_by=PM`, the worker's done delivers `[AUTOMATIC] ticket done` as a turn; plus a CLI default so `ticket start` from a session always sets `started_by`. | tests + CLI |
| G5 | Done workers linger | Make `autoRemoveDoneWorkers` default **on** for projects whose lead started the ticket (a setting; cheapest default) or fold into G2. | board setting |
| G6 | A fresh daemon cannot start a ticket (no role, no Roles plugin installed) | `ticket start` with a missing role falls back to the project default role; the installer ships `Infantry_Roles` (the installed plugins dir had 12 of 13 official plugins). | board + packaging |
| G7 | Brief has no DONE template | The brief composer appends a standard "DONE report" block (done tool name, required fields) when the ticket text has none. | board brief |

## Proposed tickets (value order)

1. **PIPE-STALL-1 - Alert on a stalled worker.** Goal: an unattended pipeline never stops silently. Done when: a worker
   waiting on an unanswered approval card or silent past `board.stallSeconds` sets the board row to "waiting on owner" or
   "stalled", fires `ticket.run_stalled`, and alerts the starter and the owner once; a daemon test starts a worker with
   mode `ask` and no client and sees the alert. Files: `infantry-board/src/dispatch.rs`, `infantry-daemon/src/sessions.rs`,
   `infantry-daemon/src/compose/prompter.rs`, `infantry-orchestrator-api/src/work.rs` (hook name).
2. **PIPE-CLOSE-1 - One-call ticket close and next.** Goal: the lead's one turn. Done when: `ticket_close <id>` marks done,
   stops the worker, removes a clean/merged worktree and returns the started next ticket; CLI `infantry ticket close`.
   Files: `dispatch.rs`, `infantry-cli/src/cli/cmd_ticket.rs`, `worktree_sweep.rs`, `infantry-projects/src/worktree.rs`.
3. **PIPE-RESTART-1 - Restart a failed worker on its worktree.** Goal: recovery in one call. Done when: `ticket restart`
   resumes the session, falls back to a fresh one on the same worktree when the resume dies, and comments which it
   did; test kills the worker mid tool call and checks committed files carry over. Files: `dispatch.rs`,
   `sessions.rs`, `cmd_ticket.rs`.
4. **PIPE-WAKE-TEST-1 - Prove the DONE wake end to end.** Goal: step 3 verified with a real idle lead. Done when: a
   test or script shows an idle PM session receiving the done line as a turn with no polling, and that
   `ticket start` from a session sets `started_by`. Files: `infantry-daemon/tests/all/`, `cmd_ticket.rs`.
5. **PIPE-ROLE-1 - Fresh install can start a ticket.** Goal: first ticket works on a clean install. Done when: the
   installer plugin set includes `Infantry_Roles` and a ticket with no role starts with the project's default role.
   Files: `scripts/`, `app/src-tauri`, `dispatch.rs:396`.
6. **PIPE-AUTOCLEAN-1 - Done workers close themselves.** Goal: no idle done workers. Done when: `autoRemoveDoneWorkers`
   defaults on for lead-started tickets (or is dropped into PIPE-CLOSE-1). Files: `infantry-daemon/src/compose/watch.rs`,
   `sessions/lifecycle.rs`.
7. **PIPE-BRIEF-1 - Standard DONE block in the brief.** Goal: step 1's mandatory report. Done when: a brief without a
   DONE section gets the standard one appended; test on the composer. Files: `infantry-board/src/brief.rs`.

## Test later

- Step 3 with a real PM lead (idle, then busy) on the head build.
- Step 5 on the head build (the live run used an older daemon); confirm why the resumed claude exited with code 1.
- Step 6: answering the card from a UI client and the answer arriving as a turn.
- Step 4 with `autoRemoveDoneWorkers` and a merge, to watch the sweep remove the worktree.
- Lane caps with the "work + build" split; `ticket_start_next` live.
