# TOKEN-10: what added ~0.6%/h burn (study, 2026-10-31)

Method: diffs of `git log --no-merges --since "2026-10-30 18:00"` (41 commits) read for idle burn, per-turn tokens, per-start cost.
NOT measured: per-role tokens/turns/cache-reads 12-18h vs 18h-now. The CLI (`agenthq usage`) only gives plan meters (claude week 23%, +2.3 vs pace, session 29%); no per-role/hour split is exposed, and the DB was not opened (read-only, no app launch). Verdicts below are from code, not stats.

| Change | Verdict | Evidence |
|---|---|---|
| PACE-TREND-1/2 (b4e46daf, 3fb4efb4, 406e36fe) | cannot | `PacingMeters.read()` reads the daemon's cached live read, never the provider; no process, model or session. UI refresh `PACE_REFRESH_MS`=60s is a local daemon read. |
| EFFORT-1 (18f20b2e, 24bfaa1c) | cannot by itself | Default is "off" = no `--effort` flag (`ClaudeCommand.kt:179` adds it only when set). PM/VC raise only if their role binding sets effort. |
| RUNTIME-LOAD-5/6/7, AGENT-REGISTRY-1, RUNTIME-LOAD-8 | cannot | Move code between modules; no loop, prompt or nudge added. |
| STALL-6 (ec04b8b9) | cannot | Notice text/ack handling for bg jobs; one per kind, fewer repeats. |
| TRIGGER-FOOTER-1 (61f18d0d) | minor | +~600 chars on every trigger/system mail (PM woken every 30 min => ~150 tokens x 48/day) and tells the session to run `agenthq read <id>` (one extra tool call per wake). Small but constant for the PM. |
| KNOWLEDGE-PTR-1/2 | per start only | Related-files list in the start brief; one-time per ticket, offset by TOKEN-5 trim. |
| TOKEN-1/5/6 | reduce | slimmer responses and digest. |
| TOKEN-4 | cannot | stats split only. |
| **TOKEN-7 (ddcdd49c)** | **CAN raise** | `WeightContext.DEFAULT` gives every ticket an effort when the ticket/role pins none: w1-2 low, w3 medium, **w4-5 high**. `TicketBoard` does `effort = executor.effort ?: byWeightContext?.effort`. Before, such workers had no flag (harness default). `high` = more thinking output per turn on the heaviest tickets. contextLimit 100-200k only moves compaction. |
| CONTEXT-LIMIT-2 | cannot | env `CLAUDE_CODE_AUTO_COMPACT_WINDOW` only; nothing disables caching (no DISABLE_PROMPT_CACHING in code). |
| DAEMON-PLUGIN-1..7, UI/bug commits | cannot | refactors / UI. |

Top suspect: TOKEN-7's default effort for weights 3-5 (esp. `high` on 4-5), applying per ticket start, plus the PM's per-wake footer as a small constant. Caveat: TOKEN-7 landed 01:33, so it explains the rise only if the burn climbed after that; if the +0.6%/h began earlier, re-measure per-role tokens/turn first (needs a stats query by role and hour, which no CLI offers: worth a TOKEN-11).

One-line fix: change `WeightContext.DEFAULT` so weights 3-5 carry `effort = null` (keep contextLimit); owners opt in via `tickets.weightContext`.

TEST-TODO: with default settings start a w4 ticket and confirm the launch has no `--effort`; start a w1 and confirm `--effort low`.
