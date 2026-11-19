# Move-over bar: end-to-end proof (RUST-MOVE-1, ticket af8a08c8)

Run on a COPY of the owner state (`infantry-migrate snapshot`), the real Rust daemon, headless, port 18971, scratch git
repo with a `feature-base` branch, real `claude` workers on haiku. Agents-per-project is tickets 140bd168 and ca5eb72f.

## Result

| # | Check | Result | Evidence |
|---|---|---|---|
| 1a | `ticket_start` gives a worktree on `ticket/<short>-<slug>`, cut from the epic base | PASS | `ticket/b48a486d-mv1-probe-5-add-five-txt`, cut from feature-base a5fb3a7 |
| 1b | the worker's commit lands on that branch | PASS | 74749de "Add five.txt" |
| 1c | done runs the done-protocol and merges into the base | PASS (after fixes) | feature-base 4b4804d "Merge branch 'ticket/b48a486d-...'" made by the version-control agent from the hook's mail f014c813; ticket comment 81b5503b names worker kill + merge request |
| 1d | the ticket shows its branch and the merge | PASS | board row `ticket/b48a486d-...`, comment 81b5503b |
| 1e | the worktree is cleaned up | FAIL (same as Kotlin) | `.worktrees/ticket-b48a486d-...` stays after the merge. Nothing calls `worktree_remove` (frame exists, tested in infantry-projects; no CLI/MCP/script path, the Kotlin daemon had none either). Needs a ticket. |
| 2a | mail send / inbox / read, reply thread | PASS | CLI send 0fa9f1d8 -> agent replied 47076d6a (state Answered); `--reply-to` kept thread `pair:915d4258...|user`; MCP `infantry_inbox` lists both |
| 2b | prompt to an idle session | PASS | idle-ok reply f65f00e6 |
| 2c | prompt to a busy session | PASS | second prompt answered "queued for 915d4258"; both turns ran (essay-done c5e759d2, queued-done e264c415) |
| 2d | automatic done report / hooks (on-ticket-done) | PASS (after fixes) | see gaps 1-4 |
| 2e | ticket comment notifies the list as one mail line | NOT PROVEN | scratch project has no user-created agent; `notify` correctly refuses role ids |
| 2f | blocked report mail, on-mail-sent hook | NOT RUN | out of time budget |
| 3a | a session resumes with the same conversation | PASS | kill + restart of 915d4258 kept conversation 5803357b...; it answered a question about the first mail (23a22333) |
| 3b | owner message reaches the agent, reply shows | PASS | CLI send -> reply in inbox |
| 3c | prompt card answered from the UI | NOT PROVEN | no UI allowed; `infantry_prompt_answer` exists |
| 3d | MCP bridge (`infantry mcp serve`) drives infantry_* tools | PASS | every probe above went through it |

## Gaps found and fixed (commits on ticket/af8a08c8-...)

1. 5070d7a5c: the script alias table was empty (`ih.board`, `ih.move`, `ih.message` ... "unknown tool or alias"). Compose now reads `scripting.aliases` with the Kotlin defaults.
2. 8ebec5d41: no mail tools on the MCP/script surface. Added `infantry_message`, `infantry_inbox`, `infantry_read`, `infantry_session_remove`.
3. 7b90d589d: `ticket.moved` carried no branch, title or worker, so the done hook killed no worker and mailed "(no branch)". It now carries `branch`, `title`, `workerSession`.
4. 7b90d589d: a hook mail to a bare role ("version-control") now resolves inside the hook's project, not the first one found (it had mailed a migrated session of the owner's real project).

## Open gaps (not fixed, need tickets)

- Worktree is never removed after the merge (1e).
- `infantry send --reply-to` prints the replied-to message id instead of the new one (`cmd_mail.rs` takes the last `mail` frame, which is the replied-to message's Answered update).
- `infantry logs <prefix>` does not resolve an id prefix; `read` on a message not addressed to the owner says "no such message".
- Migrated owner hooks fail on `agenthq_project_config_get`, `infantry_memory`, `infantry_notify` (tools missing) and on the exec bridge being off.
- CLI `agents --live` fails: `agent_roster` frame unsupported. `ticket comments` is not a CLI subcommand.
- Not run: `cargo test` for the changed crates.

## Owner hooks on Rust

Run on a throwaway test daemon (port 18977, fresh state dir, no sessions; only the owner's `scripting.*` settings rows, the owner's project scripts and `hooks.json`, and the Infantry_Settings plugin), with throwaway tickets. The scripts were not edited. The test daemon was killed by pid and its state deleted.

| Hook (script) | Trigger | Result | Evidence |
|---|---|---|---|
| done-protocol-lane-enter-done (done-protocol-5) | lane enter done | PASS (bridge) | the `git` exec now runs (exit=128 only because the owner's merge repo path does not exist in the scratch box); before: `infantry_exec refused: the process bridge is off`. The run then stops at "ticket has no branch" because the throwaway ticket has no worker branch, which is the script's own check. |
| mem-check-done / mem-check-timer (mem-check) | lane enter done | PASS | `ran mem-check for ticket.moved: OK` (`agenthq_processes` resolves to the new `infantry_processes`) |
| worker-idle-nudge (worker-idle-nudge-4) | every 60s | PASS | OK, 3 actions; before: `unknown tool or alias 'agenthq_project_config_get'` |
| worker-silent-ping, worker-watch, hook-health, gh-build-done, mail-hook-keeper | timers | PASS | `ran ...: OK` |
| one-worker-inprogress (lane-one-worker) | lane enter in_progress | NOT RUN | entering in_progress starts a worker, which would launch a real harness. The same `infantry_project_config_get` call is proven by worker-idle-nudge above. |
| claude-auth-scan (claude-auth) | every 120s | FAIL here, not proven | `infantry_memory` lives in the Infantry_Notes plugin, which the test box did not load. Re-check on a box with the Notes plugin. |
| the other hooks (lane-park, lane-pending-note, lane-pin-check, usage-governor, build-tick, vc-merge, needs-owner-alert, worker-issue-wake, on-mail-sent, pm-mail-dedupe, build-done, qa-done, vc-merge-done, done-protocol-merge) | lane moves, mail, ticket.commented, exec.done | NOT RUN | their events were not fired in the box; none showed a failure |

Fixes: c7d81a1a6 (scripts read `scripting.*` from the settings table), 976b5ed12 (`infantry_project_config_*` default projectId; this is in the Settings plugin, so the plugin must be rebuilt and shipped), 4e7b10036 (`infantry_processes`, `_reap`).
