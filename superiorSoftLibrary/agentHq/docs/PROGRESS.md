# Progress meter (contract 1.117.0)

An agent reports how far along its long job is; the app shows it as a live bar on the agent's card, its session
header and its running ticket's board card, and the PM reads it in `agenthq_list_sessions` / `agenthq_get_session`
without reading the transcript. The number is the agent's MEASUREMENT. Nothing is estimated unless the agent opts in
(`--estimate`, last section), and then the bar says so.

## Pick a mode that fits the job

| Mode | CLI | When |
|---|---|---|
| Checkpoints | `agenthq progress plan "compile" "unit tests" "ui tests"` (or `plan 4`), then `agenthq progress step "compile"` as each completes | a job with known stages: prefer this |
| Counter | `agenthq progress count 37 120 "tests"` | items done of a total (`?` = unknown total) |
| Transfer | `agenthq progress bytes 1048576 8388608 "model"` | a download/upload; the speed is derived |
| Percent | `agenthq progress 40 "label"` | the tool already prints a percentage |
| Indeterminate | `agenthq progress status "waiting for CI"` | nothing can be measured: label + elapsed time |

`agenthq progress done` / `fail "reason"` end it; `clear` removes it; `show` prints the current value.
`step` names the step that just COMPLETED (no name = the current one); a name the plan did not have is added where the
job stands, already complete. The bar shows `step k/n <name of the step now running>`.

## From inside a pipe, in any language

Print a marker line; wrap the command with `agenthq run` (or pipe it through `agenthq progress --watch`):

```
agenthq run --label gate -- ./scripts/agenthq-gate.sh HEAD
some-build 2>&1 | agenthq progress --watch --reader gradle
```

Grammar (one line, optional leading whitespace, case-sensitive):

```
##infantry progress <0-100>[%] [label...]
##infantry plan <N> [label...]          |  ##infantry plan <name>|<name>|...
##infantry step [name...]
##infantry bytes <done> <total|?> [label...]
##infantry count <done> <total|?> [label...]
##infantry status [--estimate] <label...>      (##infantry estimate <label...> is the same as status --estimate)
##infantry done [label...]  |  ##infantry fail [reason...]  |  ##infantry clear
```

A line that does not parse is ordinary output. Marker lines are passed through like every other line. Only the parsed
fields are sent to the daemon; no other output ever is.

`run` copies stdout and stderr through byte for byte, forwards stdin, and exits with the command's own exit code; it
reports `done` on 0 and `failed` otherwise. `--watch` copies stdin to stdout byte for byte and exits 0 at EOF.

Readers apply ONLY when asked (`--reader`, repeatable), never by guessing:

- `gradle`: the running `> Task :x` as the label, while the job is not measured another way (use `--console=plain`).
- `junit`: counts `Class > test PASSED|FAILED|SKIPPED` lines; `N tests completed` sets the count.
- `gate`: `stage k of n [name]` becomes a plan of n checkpoints with k-1 complete.

## Harmless outside Infantry AI

With no `AGENTHQ_SESSION_ID`, no reachable daemon, or a refused report: every `progress` command does nothing, prints
nothing and exits 0; `run` and `--watch` still pass everything through and keep the exit code. Reports go out on a
background thread and the CLI waits at most 3 s at the end, so a slow daemon never stalls the pipe. Set
`AGENTHQ_PROGRESS_DEBUG=1` to see why a report was dropped (on stderr).

Each `agenthq progress` call starts a JVM: for frequent updates print markers through `run` / `--watch`, which send
at most one numeric update every 250 ms (a plan, step, done or fail always goes out, in order).

## Lifetime

Stored on the session in the daemon's memory. Cleared when the session ends, when a turn ends with no report made
during it (unless an `agenthq bg run` job is still registered), and on a daemon restart. A client shows the bar amber
with "no update for N min" when `updatedAt` is older than its stale setting.

## Estimated mode (opt-in, last resort)

When nothing can be measured but the same job has run before, `--estimate` turns an indeterminate bar into a guess
from history: `agenthq run --estimate --label "gate" -- ./scripts/agenthq-gate.sh HEAD`, or
`agenthq progress status "gate" --estimate` (or `agenthq progress estimate "gate"`); MCP `agenthq_progress` mode
`status` with `estimate: true`; with `progress --watch`, `--estimate` needs `--label`. The daemon keeps, per project and label, the durations of the last 5 runs
that ended `done` (never a failed one) and predicts their median. The bar is drawn HATCHED and reads
"~12 min left (estimated)" (past the prediction: "over the usual 12 min (estimated)"); it never fills completely on a
guess. The first run of a label has no history and shows as plain indeterminate. Any measured report (plan/step,
count, bytes, percent) or a plain `status` replaces the estimate, so `--estimate` with `--reader gradle` loses it at the
first task line. Prefer checkpoints or a counter: estimate only when nothing else works.

## The gate script

`scripts/agenthq-gate.sh` declares its four stages as `##infantry plan` / `##infantry step` lines and, after the last
stage, reports the test totals (`##infantry count <passed> <total> tests passed (N failed)`) then `done` / `fail`.
Under `agenthq run` (which sets `AGENTHQ_PROGRESS_PIPE=1` on the command it wraps) those lines are the report; run on
its own with `agenthq` on PATH, the script sends the same reports through `agenthq progress`; with neither, they are
plain lines. If you pipe a script like this through `progress --watch` instead, set `AGENTHQ_PROGRESS_PIPE=1` on it.
