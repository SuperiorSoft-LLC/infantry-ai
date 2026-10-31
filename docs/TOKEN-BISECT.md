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

## TIMERS (TOKEN-12, 2026-10-31)

Read-only, from code. NOT measured: per-turn gaps of pm/version-control (no CLI exposes turn timestamps; `trigger list` was not reachable from this worktree). Sessions live now: pm (sonnet), version-control (haiku), both at rest.

| Timer | Interval | Does with NO ticket running | Model cost? | Evidence |
|---|---|---|---|---|
| **Trigger pump + `prompt` triggers (trg-3ecbb271, 30 min)** | pump 1 s; trigger fires every 30 min | delivers the prompt as MAIL to the PM; wakes an idle PM = a full turn (+ footer, + `agenthq read` call) | **YES, ~48 turns/day** | TriggerService.kt:68,117, TriggerNudge/PromptDelivery :21-32 |
| FinishNoticeMonitor idle sweep | 1 min sweep; note after 60 min at rest (`idleNoticeMinutes`) | one `[AUTOMATIC]` mail to the starter per idle period | yes, once per period, only if a worker rests with ticket open | FinishNoticeMonitor.kt:59-63, LimitRegistry.kt IDLE_NOTICE_MINUTES=60 |
| StallDetector | 30 s tick; stall 5 min, foreground 10 min | nudge mail to a worker + a line to the starter, once per stretch | yes, only for In Progress tickets | StallDetector.kt:54, :8-20 |
| AgentWatchdog | pollMs 30 s; nudge ladder, then ONE batched mail to PM | nudges, then wakes PM | yes, only with unanswered mail | AgentWatch.kt:52, AgentWatchdog.kt:58,88-100 |
| SessionManager watchdog / silent sweep / bg scan | 1 s / 60 s / 2 s | kill stalled unattended runs, flag silent, scan child pids | no (local, flag only) | SessionManager.kt:462-470, :1950-1961 |
| Ticket worker sweep | 60 s | attention item for unbriefed workers | no | DaemonMain.kt:1321-1333 |
| Question sweeper | 15 s | expire owner questions | no | DaemonMain.kt:517, :4384 |
| HealthGate tick | intervalMs | health check | no | HealthGate.kt:102 |
| UsageMeter prune | 6 h | SQLite prune | no | UsageMeter.kt:200-205 |
| SessionHost idle sweep | idleSweepMs | stops idle warm harness processes | no (ends processes) | SessionHost.kt:200-227 |
| Pace/usage reads (UI 60 s) | 60 s | reads cached daemon value | no | TOKEN-10 table |
| MailStore retries | ms backoff, 2 s budget | file replace retry | no | MailStore.kt:168-173 |
| Update check | ServerUpdateService (on demand) | GitHub release poll | no | ServerUpdateService.kt |

No timer spawns a CLI or prompts a model by itself except the three mail paths above (trigger, idle note, stall/watchdog nudges). Plugin daemons (Pace, Processes) add no model-facing timer.

### 1h prompt-cache TTL
A session idle >60 min rewrites its whole context on its next turn (cache WRITE, ~12x the price of a read). The 30-min trigger keeps the PM's cache warm (each wake = a cache READ of the whole PM context, big but 10x cheaper), so the PM is NOT hit by TTL expiry while the trigger runs; it IS hit whenever a gap exceeds 60 min (trigger paused, daemon restarted). version-control (haiku) is woken only by mail: any gap >60 min is a full rewrite, but on haiku. The 60-min idle note of FinishNoticeMonitor lands exactly on the TTL edge: a worker at rest 60 min sends its starter a mail, and the worker's own next turn after it pays a rewrite.

Jump period ~100 min: no timer has a 100 min period. 30 min x 3 = 90, 60 + 30 + 10 = 100: a plausible beat is the 30-min PM wake combined with the 60-min idle note and 5/10-min stall nudges, i.e. PM turns that each re-read a large (100k+) context. Not proven.

Top suspect: the 30-min trigger waking the PM with no work to do (48 full-context turns/day, each a large cache read plus tool calls). One-line fix: make the `prompt` trigger skip delivery when the PM has no unread mail and no ticket is In Progress or To Do-ready (or lengthen to 60 min+ only if skipping is unwanted; avoid exactly 60, the TTL edge).

TEST-TODO: with no ticket running and no mail, let a 30-min prompt trigger fire; confirm no mail is delivered and the event is recorded as skipped; with an In Progress ticket confirm it still delivers.

## PER-COMMIT (TOKEN-13, 2026-10-31)

Method (static, deterministic, no model calls, no Gradle, no app launch): for each of the 49 first-parent merges on feature-agenthq-mvp from 2026-10-30 12:00 (54070dd3 .. 8cabc40b) read the tree with `git show <c>:<path>` and measured (script in /tmp, not committed):
- **tools**: `agenthq_*` tools declared in `plugins/official/*/module.json` (core `McpWatchTools` is the rest).
- **schema chars**: characters of the string literals on name/description/schema lines (`description`, `type`, `enum`, `putJsonObject`, tool names) in tool-defining Kotlin (`plugins/official/*/daemon/**`, `cli/mcp`). A proxy for the text the model receives each turn; it is not the serialised `tools/list`.
- **brief chars**: NOT measured per commit (rendering needs the runtime knowledge store and a fake ticket, i.e. Gradle test per commit). Proxy: size of `TicketBoard.brief()` = 586 chars at every commit (constant); `TicketBoard.kt` grew 107.4k -> 112.5k chars (brief inputs such as KNOWLEDGE-PTR-2 related tickets).
- **response chars**: NOT measured (needs a running board). Read from diffs instead: TOKEN-1 (91413e24) cut them (board compact: done 5, backlog/todo 10 lines; session tail 50 -> 5 lines, line cap 300).

| commit | tools (module.json) | schema chars (proxy) | brief fn chars | note |
|---|---|---|---|---|
| 54070dd3 (first) | 23 | 8023 | 586 | |
| 32ebaa3d | 23 | 8246 (+223) | 586 | |
| 5e2715f2 | 27 | 8777 (+531) | 586 | tools moving core -> plugins (same total surface) |
| dc9f39b6 | 31 | 8995 (+218) | 586 | |
| 17adcd5f | 37 | 8352 (-643) | 586 | |
| 30ed6fbf | 38 | 8625 (+273) | 586 | |
| ba830e0e | 38 | 8761 (+136) | 586 | |
| 5d13d537 (RUNTIME-LOAD-8) | 43 | 8548 (-213) | 586 | last tool move |
| **b55952c3 (TOKEN-1)** | 43 | **9643 (+1095)** | 586 | new `limit`/`columns` params + longer descriptions on board and list_sessions |
| 8cabc40b (tip) | 43 | 9643 | 586 | unchanged to the tip |

All other merges: unchanged in every column.

### Result
- **Largest jump: b55952c3 (TOKEN-1), schema +1095 chars (+14%, ~270 tokens per turn, prompt-cached).** The diff lines: `putJsonObject("columns")`, `putJsonObject("limit")` (board) and `put("description", "At most this many sessions ...")` (list_sessions), plus longer `agenthq_board` description. It buys smaller responses, so the net is expected to be a saving; it is the only tool-surface growth in the window.
- Tool count 23 -> 43 in module.json is migration, not growth: the same tools used to be declared in core Kotlin.
- Nothing in the static surface explains a +0.6%/h burn (270 cached tokens per turn is negligible beside a 50k+ context).
- No first-turn brief or system-prompt jump visible in code (brief function constant). Not proven: brief CONTENT (knowledge digest, related files, role persona) is data-dependent and was not rendered.

### What is left (live behaviour, needs measurement not code reading)
1. Turns per ticket and tokens per turn by role, 12-18h vs 18h-now (TOKEN-9 logging).
2. Retries/loops: stall nudges, trigger wakes (TRIGGER-FOOTER-1 adds a `read` call per wake), bg-job notices.
3. Rendered brief size per role with real knowledge store (one Gradle test, current tip only).
4. Cache misses: anything that changes the prompt prefix each turn.

TEST-TODO: render the start brief for pm / version-control / backend at the tip with a fixed fake ticket and record chars; compare `tools/list` serialised length at 54070dd3 vs tip.
