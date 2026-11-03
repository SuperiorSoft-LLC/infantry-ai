# USAGE-POLL-2: weekly-meter step capture (read-only)

Ticket: c89907ae. Branch: ticket/c89907ae-usage-poll-2-4-hour-script-poll-to-catch.
Data: scratch/usage-poll/poll2.jsonl (114 lines, 18:02:20 to 19:03:43 UTC). Meter reads: scratch/usage-poll/copy/usage-meter.db (read-only copy).
Times are UTC. The machine is on EDT (UTC-4).

## Result

- One weekly step captured: **28% -> 29% at 18:45:12 UTC.** The previous sample (18:44:42) read 28%.
- No second step through the last sample at 19:03:43 (still 29%).
- Session % also moved during the run: 7 -> 8 (18:20:21), 8 -> 10 (18:25:21), 10 -> 11 (18:30:21).
- The poller (PID 40508) was stopped by PID at about 19:06 UTC, on the PM's wrap-up order. The window was captured; the run was not the planned 4 hours (see Problems).

## Requests before the step

Windows end at the step (18:45:12). Columns: requests, input, cache read, cache write.

| Window | Role / model | Req | Input | Cache read | Cache write |
|---|---|---|---|---|---|
| 15 min (since 18:30) | backend haiku | 4 | 10 | 238,853 | 2,521 |
| | backend sonnet | 9 | 23 | 1,333,216 | 16,127 |
| | pm sonnet | 8 | 21 | 1,226,011 | 6,184 |
| 30 min (since 18:15) | backend haiku | 4 | 10 | 238,853 | 2,521 |
| | backend sonnet | 97 | 198 | 10,643,210 | 281,172 |
| | pm sonnet | 14 | 36 | 2,117,442 | 10,674 |
| | version-control haiku | 1 | 4 | 152,023 | 597 |
| 60 min (since 17:45) | backend haiku | 31 | 64 | 1,652,179 | 135,523 |
| | backend sonnet | 131 | 265 | 13,112,592 | 362,625 |
| | pm sonnet | 31 | 81 | 4,469,791 | 23,924 |
| | version-control haiku | 39 | 86 | 5,306,206 | 32,471 |
| 120 min (since 16:45) | backend haiku | 32 | 68 | 1,715,910 | 136,448 |
| | backend sonnet | 261 | 518 | 24,636,894 | 720,501 |
| | pm sonnet | 60 | 158 | 7,922,588 | 45,811 |
| | version-control haiku | 97 | 221 | 11,031,059 | 74,241 |

Output tokens are NULL in the meter table, so no output totals exist.

## Between steps

Only one step was captured, so there are no between-step totals and no tokens-per-percent-point figure.

## Lag and cause

- The heaviest cache-read activity was 18:39:23 to 18:42:48 UTC: PM sonnet (28fdda96) from 18:39:23, backend haiku (04932298) 18:39:33 to 18:40:03, and backend sonnet (c5acc2b7, ticket-f810f7) 18:41:04 to 18:42:03 with nine requests at 138k to 155k cache read each.
- The step came 2 to 6 minutes after that burst. The PM's note says meter data lags, so the step may belong to activity earlier than 18:39. **Not proven.** One step cannot separate roles or models, and timing alone does not establish cause.
- Cache reads are context re-read on each request. Large totals mean long sessions, not extra work.

## Gap in the poll file

The restart baseline left 18:33:21 to 18:40:42 out of poll2.jsonl. The meter table covers it: 19 requests from 18:30:08 to 18:42, 2,481,676 cache read, all in the windows above.

## Problems

- The first run ran 31 minutes, not 4 hours. The poller stopped writing, was relaunched detached as PID 40508, and was stopped after about 2.5 more hours of combined runtime.
- The background-job wrapper was killed at its 30-minute limit (task bql5613pl) and restarted under instruction.
- `infantry bg run --notify` is rejected by CLI 1.127 (daemon 1.148).
- Output tokens are NULL in the meter table.
- The pace timestamp is the meter's own clock, not the poller's.

## Conclusion

Neither a session start, a burst, nor a specific role or model is proven to cause the 28 -> 29 step. The heaviest activity (18:39 to 18:42 UTC) came just before the step, consistent with a cache-read-weighted meter with lag. One step is not enough to attribute it. More steps, a fixed baseline with no restart, and output tokens are needed.
