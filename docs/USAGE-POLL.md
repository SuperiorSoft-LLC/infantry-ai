# Usage poll: weekly-meter steps (ticket b0fb747d)

Read-only poll: `scratch/usage-poll/poll.py`. It samples every 30 s and appends one JSON line per sample to `poll.jsonl`. It reads scratch copies of `usage-meter.db` and `infantry.db` (plus `-wal`/`-shm`) and `plugin-data/Infantry_Pace/samples.jsonl`. It never writes live data.

## Runs

1. **12-record crash** (`poll-part1-crashed.jsonl`, 16:15:40Z–16:21:10Z). The run died after 12 records with `FileNotFoundError` on copy of `infantry.db-wal`. The daemon checkpoints and removes the WAL during the copy. Fix: a missing `-wal`/`-shm` is tolerated, and each poll is wrapped in try/except.
2. **40-minute run, stopped** (`poll-part2-stopped.jsonl`, 4 records). Stopped by PID on the owner's instruction, because the window was too short for the expected next spike. Exit 127 in its job log is the expected result of stopping it, not a poll failure.
3. **90-minute run** (`poll.jsonl`, 181 samples plus a `done` line, 16:25:50Z–17:55:23Z, exit 0). This is the main sample.

## Weekly-pace timeline

| Run | Time (Z) | Current week | Change |
|---|---|---|---|
| crash run | 16:15:40–16:18:40 | 27.0 | — |
| crash run | 16:19:10 (first sample at 28.0) | 28.0 | **+1.0** |
| 40-min run | all 4 samples | 28.0 | none |
| 90-min run | 16:25:50–17:55:23 | 28.0 (flat, 181 samples) | **none** |

There is one weekly step in all captured data: 27.0 → 28.0, first seen in the sample at 16:19:10Z. The 90-minute run recorded no weekly step.

## Three minutes before the 16:19Z step (crash run)

The window covers the samples from 16:16:10Z to 16:18:40Z, which is 19 requests:

| Sample (Z) | Requests | cache_write | cache_read | Roles | Session events |
|---|---|---|---|---|---|
| 16:16:10 | 4 | 3,112 | 275,053 | pm, version-control | — |
| 16:16:40 | 2 | 447 | 190,443 | pm | **session 2cddeb0a started** (16:16:23Z) |
| 16:17:10 | 5 | 45,618 | 234,114 | backend | — |
| 16:17:40 | 4 | 8,003 | 270,725 | backend | — |
| 16:18:10 | 4 | 2,103 | 291,137 | backend | — |
| 16:18:40 | 0 | 0 | 0 | — | — |
| **Total** | **19** | **59,283** | **1,261,472** | pm, backend, version-control | 1 start |

## What happened at session starts (90-minute run)

Six sessions started in the 90-minute run. The weekly meter did not move at any of them.

| Start (Z, approx.) | Ticket | Requests in the next 30 s sample | Weekly change |
|---|---|---|---|
| 16:35:27 | ticket-18370e (22c0112b) | 8 | none |
| 16:56:16 | ticket-46ee61 (3bcd1d94) | 6 (mixed with PM) | none |
| 17:09 | ticket-d742b6 (57bd78f4) | 5 | none |
| 17:19:22 | ticket-9d0a58 (3360a7a1) | 6 | none |
| 17:36:53 | ticket-6df0d6 (d685aaea) | 1 | none |
| 17:55:23 | ticket-1580ce (8eb4e58f) | not sampled after start (run ended) | none |

Request bursts in this run: 17:08:52–17:10:22 (up to 13 per 30 s), 17:24:52 (9 requests), and 17:25:52 (8 requests). None of them moved the weekly meter.

## Conclusion

- **The one weekly step we captured (27 → 28) coincides with a session start,** about 2.5 minutes after session 2cddeb0a began. It is also preceded by a backend request burst (19 requests, 1.26M cache-read tokens). Only one step is in the data, so this is a single observation, not a pattern.
- **The 90-minute run does not reproduce the step.** Six session starts and several bursts produced no weekly change. The earlier claim that the step "usually happens after a session start" is not supported by this run. The owner's chart shows steps about every 1.3–3 h, and this run's 90-minute window was shorter than that cadence.
- **Neither the crash run nor the 90-minute run is enough to say whether the step is caused by a session start, a request burst, or neither.** The next capture needs a window of at least 3 h, or a run started just before the expected step.

## Limits

- The 40-minute run and the crash run are partial. Their data is not used for the timeline except for the single step above.
- The pace file has a 1-point resolution (whole percent). Sample timing is 30 s, so the step time is only known to a 30 s bucket.
- Token counts come from `usage_meter` request rows. They are not the same as the pace percent.
- One sample in the 90-minute run (line 178) logged `infantry_error` (`PermissionError` on the copy of `infantry.db`, locked by another process). It did not affect the weekly data.
