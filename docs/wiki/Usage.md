# Usage and worker limits

> **legacy-java:** this section describes the Kotlin app. It lives on the `legacy-java` branch. The Rust framework on main is described in [Architecture](Architecture.md).

## Rust (current)

The `infantry-usage` crate has live plan meters, a refresher with backoff, a pacing judge and forecast (`pace.rs`, `pacing.rs`), overrides and the governor's workers-allowed answer. CLI: `infantry usage`. Limits: `limits.usage.*` and `limits.pacing.maxWorkersCeiling`. The numbers below are Kotlin-era and not verified in Rust.

### When a provider rate-limits a meter

Claude's plan endpoint (`/api/oauth/usage`) rate-limits the **token**, and every process that reads with it counts: the
daemon, the dev and test daemons, other tools signed in as the same account. When a read fails:

- The wait before the next read is the **later** of the provider's `Retry-After` and our backoff (`usage.refreshMinutes`,
  doubling up to `limits.usage.backoffMaxMinutes`). That endpoint answers every 429 with `Retry-After: 0`; a hint can only
  lengthen the wait, so a `0` never turns the backoff into "ask again now".
- The **last good reading stays on the card**, marked old, for up to `limits.usage.staleMaxHours` hours. The card says its
  age ("updated 12m ago"), the reason once, and when the daemon asks again ("next try in 4m"). With no reading at all it says
  so, and that pacing is off for the meter.
- The meter **recovers by itself** at the first read the provider allows; there is nothing to click. Refresh asks only outside
  the manual floor (`limits.usage.manualRefreshFloorSeconds`) and never inside a backoff.
- The daemons of one machine **share what they read**: the last good reading, the failures in a row and the next time to ask,
  in one small JSON file per provider and sign-in under `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Cache\limits` (else
  `$XDG_CACHE_HOME/infantry-ai/limits`, else `~/.cache/infantry-ai/limits`; `INFANTRY_LIMITS_CACHE_DIR` moves it). A restarted
  daemon shows the last good reading at once, and a second daemon does not ask while the first one waits or right after it
  read. The files hold readings only, never the credential; a sign-in is told apart by a hash of the folder it lives in.
  If the folder cannot be written the daemon logs it once and reads on its own.

## Legacy (Kotlin) reference


Why did my ticket not start? Why does only 1 worker run? This page explains the usage system in plain words. Any agent can read it
and explain it to a user.

## The meters

A **meter** is one provider's plan usage (for example `claude`, `opencode-go`). Each meter has **windows**: a short **session**
window and a longer **week** window (some plans have a monthly one). For each window the daemon shows:

- **used**: percent of the window's allowance spent.
- **resets**: when the window starts again at zero.
- **pace vs even**: used minus the share of the window that has gone by. `+5` = 5 points ahead of an even spend, `-5` = behind.
- **projected at reset**: where you would finish if the average spend so far continued. Over 100% means you would run out early.

The daemon re-reads the meters about every `usage.refreshMinutes` minutes (default 5). A read that fails keeps the last good reading
for up to `limits.usage.staleMaxHours` hours; with no reading at all pacing is off for that meter (it fails open).
See it with `infantry usage` (MCP `infantry_usage`) or the Usage panel.

## The pacing rule: how many workers may run

The **allowance** is the number of ticket workers one meter may run at the same time. It is judged on the tightest window of the
meter (the one that allows the fewest workers). Setting `usage.pacing` holds the rule; the default mode is `pace`:

- Projected at reset up to about 100% (`okProjected`): up to `maxWorkers` (default 5) may run.
- Projected 20 points or more over (`okProjected` + 20): only 1.
- In between: the allowance steps down smoothly from `maxWorkers` to 1.
- At or above `hardStopPercent` (default 95) used, or at 90% and ahead of pace: only 1.

The other mode, `bands`, uses the raw percent used (default: below 60% three workers, below 80% two, then one). Change the rule with
`infantry usage rule <60:3,80:2,1|on|off|default>` or `infantry settings set usage.pacing <json>`. The highest accepted number is
`limits.pacing.maxWorkersCeiling`. A separate board cap, `limits.workers.maxParallel`, also limits parallel workers; the pacing
allowance does not change it.

## "FULL: agent ticket starts refused"

When running workers on a meter reach its allowance, `infantry usage` shows **FULL: agent ticket starts refused** and a new ticket
start is refused with a line like `usage pacing: claude at 82% week (+6 vs even pace, projected 104% at reset), allowance 2 (rule),
running 2`. Running workers are **never killed**: only new starts wait. When a worker finishes, or the reading drops, the next start
goes through.

## What a user can do

1. **Wait.** The allowance rises as the window moves on (the projection falls) and fully resets at the window reset.
2. **Ask for an override.** An override replaces the rule's number for a while: `infantry usage override <meter> <n|none> [<hours>]`
   (MCP `infantry_usage_override`), and `infantry usage override <meter> clear` returns to the rule. `none` means no limit. With no
   `hours` it lasts until the meter's window resets, else 2 hours; the answer shows when it expires. Only the roles in setting
   `usage.overrideRoles` (default `owner,pm`) may set it; everyone may read. Each change is logged with who and why.
3. **Use another provider.** Pin the ticket to a provider with spare meter (`infantry_list_harnesses` lists them).
4. **Type a reading** for a meter the daemon cannot read: `infantry usage set <meter> <pct>`, undone with `infantry usage clear <meter>`.

Related: `usage.mock` shows fake readings for demos and does not affect pacing unless `usage.mock.affectsPacing` is on. Trend charts
and forecasts are on the Pace tab ([PACING](../PACING.md)); override details in [Settings](Settings.md).
