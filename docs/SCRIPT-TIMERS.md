# Script timers and progress (SCRIPT-TICK-1)

Hooks can fire on a clock, and a script can report progress for the session it acts for. The product only provides the interface; what is measured is your script.

- `agenthq script hook add on="every 30s" script=<name> [to=<session>]`: runs every N seconds (floor: setting `scripting.hooks.minEverySec`, default 10). `input.args` = hook args + `{tick, at, hookId}`. With `to`, the hook is removed when that session ends.
- `on="exec.tick"`: for each live background `exec` run, every `scripting.exec.tickSec` (default 10) seconds: `{id, script, session, projectId, elapsedMs, stdoutTail, stderrTail, bytesSinceLast}`; stops at `exec.done`.
- A tick whose previous run is still going is skipped (one log line), never stacked; the usual hook loop guard and rate limit apply.
- `ih.progress{mode=..., ...}` (= `agenthq_progress`) updates the card of the hook's `to` session, or of the `sessionId` the call names. Only sessions of the same project; otherwise refused.

Example: watch a background command, show steps from its output, report failure lines. Hook args: `{"stepPattern":"^STEP (%w+)","failPattern":"^FAILED"}`

```lua
local a = input.args
local out = a.stdoutTail or ""
local sid = a.session
for step in out:gmatch(a.stepPattern or "") do
  ih.progress{ mode = "status", label = "step " .. step, sessionId = sid }
end
if a.failPattern and out:find(a.failPattern) then
  ih.progress{ mode = "fail", label = "failure line seen", sessionId = sid }
end
```
