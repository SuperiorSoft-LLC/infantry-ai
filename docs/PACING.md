# PACING: the pacing trend charts (Infantry_Pace)

PACE-TREND-1 (ticket-537050e7). Answers one question for every harness: **am I still going over pace, or slowly coming back?** Each provider window gets a chart card built from that provider's own meter, with a forecast of where you land at reset and an up / flat / down momentum.

## What it is made of

| Piece | Where | What it does |
|---|---|---|
| `api.meters` (DaemonApi 1.10.0) | `agentHqDaemonApi/.../usage/MetersApi.kt`, `agentHq/.../usage/PacingMeters.kt` | Read-only host service: the CURRENT reading of every provider window (percent, reset time, pace delta, projected landing). Community plugins need the grant `usage.read`. |
| Infantry_Pace daemon half | `plugins/official/Infantry_Pace/daemon` | Samples `api.meters` every minute, keeps history, computes momentum and forecast, answers `pace_get`, tool `agenthq_pace`, CLI `agenthq usage history`. |
| Infantry_Pace UI half | `plugins/official/Infantry_Pace/src` | The **Pace** tab: one card per provider window. |

Optional plugin (class `optional` in `plugins/build-presets.json`, like Stats). Disabled or removed: nothing is recorded, core is unchanged, the tab is gone.

## Where the data comes from, and the Stats question (the dependency design)

The plugin depends on the **source of the data** (the provider meters, through the host's `api.meters`), **not on Stats**. It records its own history from that source, so it works with Stats absent or present and has no `requires: Stats` and no new manifest field.

What Stats stores today: `Infantry_Stats` shows the selected session's statistics and the daemon's token meter (`usage_meter`: tokens and cost by day, from `run_usage`). It keeps **no history of the plan meter percent** (the `agenthq_usage` reading is current only), and it has no contribution point other plugins can add a panel to. Choice: the simplest path that works in both cases is **its own tab**, so nothing in Stats changed. Reusing Stats' data where it exists: the tab already reads the same `usage_meter` report Stats asks for (one request, no polling of its own) for the token totals below. If Stats later grows a contribution point, the cards can move onto the Stats screen without touching the recorder.

Upload / download: the history is one file in the plugin's own folder, `<state dir>/plugin-data/Infantry_Pace/samples.jsonl`. Whatever export or sync already carries plugin data folders carries it. **No such path exists today** that I found, and no sync system was built here: the history stays on the daemon host.

## What is recorded

Every minute the recorder reads `api.meters`. Each readable window becomes one sample: `provider, window label, observedAt, percent, paceDelta, projectedPercent, resetsAt (and the window length)`. The daemon refreshes the provider's live reading every ~5 minutes (setting `usage.refreshMinutes`), so in practice a window gains a sample about every 5 minutes. A reading identical to the newest sample (same `observedAt`, percent and reset time) is **not** stored twice; an unreadable percent is never stored.

Retention: `pace.retentionDays` (default 60, `0` = keep all), pruned at startup and hourly. History round-trips through restarts (one JSON line per sample; a torn line is skipped).

Read it: `agenthq usage history [claude] ["5h limit"] [24h]`, MCP tool `agenthq_pace`, or the `pace_get` frame the tab uses.

## The numbers, in plain words

Words used: **window** = one plan period (e.g. 7 days). **Used** = percent of the window's allowance spent. **Even pace** = spending the same share every hour, so that you hit exactly 100% at the reset. **Pace delta** = used minus the share of the window that has gone by: `+5` means 5 points ahead of even pace, `-5` behind. All settings below are in Settings > Plugins > Pacing trend and have defaults.

### 1. Windows and resets

A **new window** starts when the reset time moves by more than 30 minutes, or the percent falls by more than 5 points. The history is split there. A drop at a reset is a boundary, never a "fall in momentum", and the chart does not join the old line to the new one. Momentum and the forecast look at the **current window only**.

### 2. Momentum (rising / flat / falling)

Take the samples of the current window from the last *L* hours (**short look-back** `pace.lookbackShortHours`, default 6; **long** `pace.lookbackLongHours`, default 24). Fit a straight line (least squares) to the **projected landing** (`projectedPercent`: where you would finish at reset if the average burn so far continued) against time. The slope is in **points per hour**.

* slope above `pace.deadBand` (default 0.25 points/hour) -> **RISING** (the landing is moving up: you are going further over pace);
* slope below minus the band -> **FALLING** (coming back);
* otherwise **FLAT**.

The same fit is also done on the pace delta and reported. The card header shows the short look-back, with an arrow **and** a word (▲ Rising, ▬ Flat, ▼ Falling), never colour alone. With too few samples it says "Not enough data".

### 2b. Burn rate (the rate of change)

**Burn rate** = points of percent used gained per hour over a rolling window (`pace.burnWindowHours`, default 1): at each sample, `(used now - used one window ago) / hours between`. It is computed inside one window segment, so a reset drop is never a burn. It is plotted in a second small panel under the pace line, with a heavy zero line labelled "0 idle". **When nothing is being done it falls to 0**, while the pace delta above visibly drifts **down** (the even-pace line keeps advancing, so the same used percent is further and further behind it), the projected landing falls, and the momentum label reads **FALLING**. Test: `PaceTest."idle usage"` (8 flat hourly samples: burn 0 throughout, pace delta strictly decreasing, FALLING).

### 3. Forecast: two lines to the reset

* **Current rate** = `used / elapsed share of the window` (the average burn since the window began; the first 10% of a window is floored so the very start does not divide by almost zero). This is exactly the pacing rule's own projection.
* **Recent rate** = `used + max(slope, 0) x hours left`, where the slope is the fitted burn (points of *used* per hour) over the short look-back. It answers "if the last few hours are what happens from now".
* **Hours until you cross 100%** = `(100 - used) / rate` when that line lands above 100, else "stays under 100%". Already at or over 100 = 0 hours.

On the chart the current-rate line is **dashed**, the recent-rate line **dotted** (a different style, not only a different colour), with a shaded band between the two landings. They are drawn in the 7d and Window ranges, where the reset fits on the chart; the sentence under the chart is always shown.

A forecast needs at least `pace.minSamples` (default 3) samples that span at least `pace.minSpanHours` (default 2 hours) inside the short look-back; otherwise the card says **"not enough data yet"** and draws no forecast.

### Worked example

A weekly window (168 h). Now it is 84 h in, so half the window has elapsed. You have used **53.5%**. Six hours ago you had used 50.5% and since then you burned 0.5 points every hour.

* Current rate: `53.5 / 0.5 = 107%`. You land at **107%**.
* Recent rate: `53.5 + 0.5 x 84 h left = 95.5`, shown as **96%**.
* Hours to cross 100% at the current rate: the average burn is `53.5 / 84 h = 0.637 points/h`, so `(100 - 53.5) / 0.637 = 73 h`. At the recent rate you never cross.
* Momentum: the projected landing six hours ago was `50.5 / 0.464 = 108.8`; now it is 107. A straight line through the seven hourly points slopes at -0.29 points/hour, which is below -0.25, so **FALLING**.

The card then reads: *at the current rate you land at 107%; at the last 6h rate, 96% (falling); at the current rate you cross 100% in 73.0h.* (This example is a unit test: `PaceTest."worked example"`.)

## Which numbers drive pacing, and which are only informational

* **The meter percent** (the provider's own number: `percent`, `resetsAt`, and the `paceDelta` / projection derived from them) is the **authority**. The worker allowance rule (`usage.pacing`, USAGE-PACE) reads only it. **This ticket does not change that rule.**
* **Everything here is informational**: the history, momentum, the two forecast lines and the token totals. They never start, stop or limit a worker.
* **Token totals** (per card, "last 7 days"): input including cache read and write, output, and turns, summed from the daemon's `usage_meter` rows (the per-run `run_usage` the Stats tab already shows), mapped to the meter the run spent (an `anthropic/*` model on pi spends the Claude plan). They sit beside the trend as context; the percent above them is what counts.

## The chart (docs/CHART-STYLE.md)

One card per provider window, any provider, no per-provider code (a new harness that reports a meter gets a card on its first sample). Header: title, caption (`54% of limit · +4 vs even pace · lands at 94%`), momentum label, **Pace delta / Used** toggle top right. Buttons: **Range** 6h / 24h / 7d / Window, **Scale** presets, **Fit** (default), Custom, Reset to default, the active one highlighted. Labelled y ticks with faint gridlines; the even-pace line (pace delta 0) is heavy and labelled "0 even"; Used shows the 100% cap when it is in range. First and last time under the axis. A value past a fixed scale is clipped and an explicit line says "over scale: line clipped at N%". Hover or tap shows the exact value and time. All colours are `Palette` tokens, checked for contrast in both themes by `PaceContrastTest`.

## Settings (`pace.*`, all with defaults)

`pace.retentionDays` 60 (0 = keep all) · `pace.lookbackShortHours` 6 · `pace.lookbackLongHours` 24 · `pace.deadBand` 0.25 points/hour · `pace.minSamples` 3 · `pace.minSpanHours` 2 · `pace.burnWindowHours` 1.

## Tests

`plugins/official/Infantry_Pace/daemon/src/test/.../PaceTest.kt` (recording, dedupe, retention, round trip, reset splitting, momentum and forecast with fixed series incl. the worked example, insufficient data, report for any provider), `plugins/official/Infantry_Pace/test/PaceChartModelTest.kt` and `PaceContrastTest.kt` (series, empty state, scales, clipping, token totals, tab states, contrast in both themes), `agentHqUi/.../PaceCardUiTest.kt` (the real card, tooltip, clip warning, screenshots in both themes), `agentHq/.../GrantedMetersTest.kt` (`api.meters`, the community grant denial).
