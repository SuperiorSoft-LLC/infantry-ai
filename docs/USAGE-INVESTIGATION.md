# USAGE-INVESTIGATION (ticket 244fd210): what drives the Claude weekly meter

Read-only. Data: copies of the live `usage-meter.db` and `infantry.db` (state dir `%LOCALAPPDATA%\SuperiorSoft\InfantryAI`) and `plugin-data/Infantry_Pace/samples.jsonl`, taken 2026-10-31 ~15:40 UTC. The live meter holds 7311 requests (2026-10-30 00:12 to 2026-10-31 15:36 UTC), not 600; 600 is only the report's query limit. Nothing was changed.

## Totals (all harnesses, 7311 requests)
input 587,072 / output 266,708 / cache-read 895,519,842 / cache-write 18,810,735.
Output is recorded only for pi and some other rows (claude-harness rows show no output), so output is not usable for ranking. Cache-read is 98% of all tokens and cache-write about 2%.

## By role
| role | requests | input | output | cache-read | cache-write | share of cache-write |
|---|---|---|---|---|---|---|
| backend | 2879 | 5,790 | 95,836 | 306.8M | 8.23M | 43.7% |
| architect | 1675 | 496,851 | 45,429 | 244.9M | 4.40M | 23.4% |
| frontend | 683 | 1,369 | 124,787 | 77.6M | 2.45M | 13.0% |
| pm (28fdda96, one session) | 1284 | 3,506 | - | 193.1M | 2.04M | 10.8% |
| automation | 259 | 565 | - | 17.8M | 0.58M | 3.1% |
| version-control | 299 | 676 | - | 39.7M | 0.57M | 3.1% |
| devops / qa / fullstack / assistant | 232 | 78,315 | 656 | 15.7M | 0.54M | 2.9% |

By harness/source: claude worker 4135 req / 12.40M cw (66%); claude mail 2119 / 3.22M; claude pm 628 / 0.94M; claude trigger 123 / 0.19M; pi mail 46 / 1.41M; pi worker 199 / 0.66M; opencode and ollama 0 cw.
By model (cache-write): sonnet 12.03M (5037 req), opus 3.85M (1588), haiku 0.82M (379), pi anthropic sonnet-5-5 1.89M (189), pi opus-5-5 0.18M (56).

## Largest sessions (cache-write)
| session / role | requests | cache-read | cache-write | share |
|---|---|---|---|---|
| 28fdda96 pm | 1284 | 193.1M | 2.04M | 10.8% |
| 5c8b1bc3 frontend (pi) | 120 | 19.7M | 0.99M | 5.3% |
| 8529d69a backend (pi) | 69 | 10.9M | 0.90M | 4.8% |
| df5185af architect | 217 | 32.7M | 0.43M | 2.3% |
| b872e7ad backend | 210 | 21.6M | 0.42M | 2.2% |
| fb289f34 version-control | 261 | 37.8M | 0.42M | 2.2% |

Per hour: 60 to 290 requests and 150k to 840k cache-write nearly every hour, around the clock (peaks 10-30 21:00 at 1.52M, 10-31 03:00 at 0.84M). The load is continuous, not spiky.

## Top 10 single requests (cache-write)
| time UTC | session / role | harness / model | cache-write | context |
|---|---|---|---|---|
| 10-30 23:01 | 5c8b1bc3 frontend | pi anthropic sonnet-5-5 (mail) | 251,104 | 255,662 |
| 10-30 22:58 | 5c8b1bc3 frontend | pi sonnet | 238,890 | 243,448 |
| 10-30 22:56 | 5c8b1bc3 frontend | pi sonnet | 235,576 | 240,134 |
| 10-30 21:43 | 8529d69a backend | pi sonnet | 220,108 | 224,664 |
| 10-30 21:41 | 8529d69a backend | pi sonnet | 215,643 | 220,201 |
| 10-30 21:41 | 8529d69a backend | pi sonnet | 213,566 | 218,124 |
| 10-31 14:28 | fb289f34 version-control | claude haiku (worker) | 195,773 | 214,888 |
| 10-31 00:15 | 5bab899e backend | claude sonnet (mail) | 47,899 | 66,234 |
| 10-30 13:11 | bb4ba6a8 architect | claude opus (worker) | 45,437 | 63,647 |
| 10-30 14:40 | df5185af architect | claude opus (worker) | 44,935 | 63,145 |

The six biggest are pi runs on anthropic/claude-sonnet-5-5 (they spend the Claude plan); they all fall before the weekly window starts (10-30 23:15). Pi rows show cache-read of only 4,554 against 200k+ written, so pi on anthropic is not reusing the prompt cache. The seventh (haiku, 195k) is the only big one inside the sampled weekly window.

## Idle gaps and compactions
- Requests that came more than 5 min after the same session's previous request: 258 requests, 572k cache-write (3.0% of all). Cache rebuild after idle is NOT a significant driver.
- Compactions (meter_event): 21, all claude. The 3 largest claude requests after a compaction are 47.9k (5bab899e, 00:15:27, 4 s after its compaction at 00:15:23), 45.4k and 44.9k (architect, seconds after their compactions at 13:10:55 and 14:40:25). A compaction costs about 45k cache-write per event, so about 1M for all 21 (about 5%; estimated from those three, not measured for every event). The PM session compacted 8 times in 36 h.

## Weekly meter steps vs requests
The Current week meter went 19 -> 27 between 10-30 23:15 and 10-31 14:58 (8 whole-percent steps; Pace samples every 5 min). Window = previous step to this step, claude plus pi requests:

| window UTC | step | requests | sessions (new) | cache-write | cache-read | opus req |
|---|---|---|---|---|---|---|
| 10-30 23:15 - 10-31 00:57 | 19->20 | 299 | 6 (5) | 903k | 41.9M | 27 |
| 00:57 - 02:22 | 20->21 | 319 | 15 (14) | 937k | 28.0M | 0 |
| 02:22 - 04:03 | 21->22 | 407 | 16 (15) | 1,211k | 34.3M | 21 |
| 04:03 - 05:41 | 22->23 | 369 | 12 (9) | 820k | 42.8M | 0 |
| 05:41 - 07:52 | 23->24 | 463 | 19 (16) | 1,118k | 40.4M | 0 |
| 07:52 - 10:23 | 24->25 | 349 | 12 (9) | 854k | 40.0M | 0 |
| 10:23 - 12:03 | 25->26 | 323 | 10 (7) | 724k | 36.5M | 0 |
| 12:03 - 14:58 | 26->27 | 350 | 6 (4) | 872k | 39.5M | 24 |

Each step is a steady 300 to 460 requests, 0.7 to 1.2M cache-write and 28 to 43M cache-read over 1.4 to 2.9 hours. No step is explained by one big request: the largest single request in any window is under 50k cache-write (6%), except the 195k haiku request in the last window (22% of that window). Sessions per step: 6 to 19, with 4 to 16 new sessions (median about 9), so the owner's "about 5 sessions per step" is the right order but low. About 330 requests per 1%, consistent with many small requests, each re-reading a 60 to 270k context.

Caveat: the meter percent weights opus, sonnet and haiku and cache read/write in a way not exposed here, and `cost` is empty in meter_request, so tokens per 1% cannot be converted to a plan formula.

## Effort and settings
- Stored settings (infantry.db `settings`): only `toolApproval.mode=allowAll`, `usage.overrides={"claude":{"workers":5,...}}`, `limits.watch.enabled=1`. No `tickets.weightExecutors` and no weight-context/effort value is stored, so the code defaults apply (`WeightExecutors.DEFAULT`, `WeightContext.DEFAULT`; resolution order in CliMain: ticket pin > profile/role binding > weightExecutors > default). I did not read the default values' contents.
- Tickets: 792 rows, `effort` is empty on all 792 (no ticket pins an effort). Models pinned: sonnet-5-5 162, opus-5-5 105, haiku-5-5 75 (claude); pi: opus-5-5 97, sonnet-5-5 43, haiku 2, opus-5 1; plus alias pins opus 27, haiku 6. Weights: 1:46, 2:128, 3:158, 4:88, 5:42, none 330. There is no `effectiveEffort` column in the DB; I did not find where it is computed at runtime.
- Spawn command line (ClaudeCommand.kt:174-188): `claude -p --input-format stream-json --output-format stream-json --verbose --thinking adaptive --thinking-display summarized [--model <m>] [--effort <level> only if set] [--permission-mode <m>] --allowedTools ... [--permission-prompt-tool ...] [--settings <watch hook>] [--mcp-config ...] [--resume <id>]`. Env: `ClaudeContextLimit.env(contextLimit)` only (null = the CLI's own window). `--effort` is not passed unless set, so workers run at the CLI default effort. `--thinking adaptive` is always passed.
- CLI: `claude --version` 2.1.316; `claude --help` lists `--effort <level> (low, medium, high, xhigh, max)` and `--autocompact <auto|tokens>` (100k to 1M) but does not state the default effort for Sonnet 5.5 or Haiku. Not found; I did not run a model call to probe it (that would spend plan).

## CLI-level behaviours that could explain unmatched steps
- Cache TTL / idle rebuild: not supported by the data (3% of cache-write).
- Auto compact: real (21 events, about 45k each by the three sampled), about 5% of cache-write; the PM session compacts every few hours at about 267k context. Contexts of 250k+ make every cache-read large.
- Extended/adaptive thinking: always on (`--thinking adaptive`); thinking tokens bill as output, which the meter does not record for claude-harness rows, so a share of plan use is invisible here. Cannot be quantified from this data.
- Background subagents: not visible in meter_request (no source for it); not testable read-only.
- pi on anthropic: 245 requests, 2.07M cache-write (11%), with almost no cache-read; all before 10-30 23:15 here. No pi request in any of the 8 sampled weekly steps.

## Conclusion
Likely cause (not a single confirmed one): sustained request volume. Every 1% step is about 300 to 460 requests across 6 to 19 sessions, not one big request, not idle-cache rebuild, and not compactions alone. The dominant consumers are backend (44% of cache-write), architect (23%), frontend (13%) and the one long PM session (11%), 66% from worker requests; Opus (21% of cache-write) is probably weighted heavier than its share. Not found: the effort default and how thinking output is billed (unobservable here). No step lacked daemon activity in the 8 windows, so no unexplained CLI-only activity was seen.
