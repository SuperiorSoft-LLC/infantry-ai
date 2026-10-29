# Usage stats (STATS-ACC-1)

## Definitions
- **Turn** = one model request and its response, i.e. one provider usage report. Claude: one `assistant` message id (deduped). Pi: one `message_end` of an assistant message. OpenCode: one `step_finish`. A single prompt can cause many turns (every tool round trip is another request).
- **Run** = one agent process, from a prompt to its exit. A run holds many turns. `meter_run` is one row per run; `meter_request` is one row per turn.
- **Context per turn** = input + cache read + cache write tokens: what the model re-read for that request.
- **Cache hit rate** = cache read / context, over requests that reported a cache-read figure.

## Push, not poll
The Stats pane used to ask for `usage_meter` every 15 s, and a run was only written when its process exited, so a long run showed nothing until it ended (the lag the owner saw). Now the meter records each turn the moment its usage line arrives and the daemon pushes a fresh `usage_meter` frame to every client (debounced to at most 4 per second: leading edge immediate, trailing edge guaranteed). The pane requests once when it opens or reconnects and has no timer. Runs still in flight are included in the day rows. The frame is the existing `usage_meter` with additive fields `requests`, `events`, `prices`.

## What each harness reports (n/a = stored NULL, shown "n/a")
| field | claude | pi | opencode |
|---|---|---|---|
| input | yes | yes | yes |
| output per request | n/a (assistant lines carry streaming placeholders; run total from the closing `result` line) | yes | yes |
| cache read / write | yes | yes | if present |
| cost per request | n/a (run `total_cost_usd` only) | yes | yes |
| compaction event | `system/compact_boundary` | `auto_compaction_end` (best effort, shape unverified) | n/a |

Each request row also carries model, harness, role, ticket id, session id, source.

## Cost estimate
`usage-prices.json` in the state dir (seeded on first start; edit it): USD per million tokens, first model-id substring match wins. The UI never hard-codes prices; every cost is shown as an estimate (`~$`), `n/a` when no price matches. Provider-reported cost stays separate in the day rows.

## Retention
`meter_request` and `meter_event` are pruned with the per-run rows (current plus previous week). No history is rewritten.
