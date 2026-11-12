-- runner-green (GREEN-GAP-1): the check the PM runs BEFORE telling the owner a runner/harness is green.
-- Runs on a daemon paired with a runner (gradle run build, ports 18900-18999, never the owner's runner).
-- args: {runner="hm", rows={{harness="claude",model="haiku"},{harness="pi",model="..."},{harness="opencode",model="commandcode/..."}}}
-- Each row = one real throwaway turn ON THE RUNNER through the link (`infantry runner test`): start, answer, end, seconds, error text.
-- Columns approval / ping-pong are NOT covered by `runner test`; they print NOT-TESTED, never PASS (a row is green only if all are PASS).
-- Never prints a bearer: the runner is already paired; this script only reads `runner test` output.
local a = input.args or {}
local runner = a.runner or "hm"
local rows = a.rows or {
  {harness = "claude", model = "haiku"},
  {harness = "pi", model = a.piModel},
  {harness = "opencode", model = a.opencodeModel},
}
if input.phase == 1 then
  for _, r in ipairs(rows) do
    local cmd = {"infantry", "runner", "test", runner, "--harness", r.harness}
    if r.model then cmd[#cmd + 1] = "--model"; cmd[#cmd + 1] = r.model end
    ih.exec{command = cmd, timeoutSec = 150}
  end
  ih.again()
else
  ih.log("row | placement | start | answer | approval | ping-pong | end | seconds | error")
  for i, r in ipairs(rows) do
    local res = input.results[i] or {}
    local j = res.json or {}
    local out = (j.stdoutTail or "") .. " " .. (j.stderrTail or "")
    local ok = (j.exitCode == 0)
    local err = ok and "-" or out:gsub("%s+", " "):sub(1, 220)
    if not ok and err == "" then err = "NO ERROR TEXT (that is the bug: file it with the session id)" end
    ih.log(string.format("%s/%s | runner:%s | %s | %s | NOT-TESTED | NOT-TESTED | %s | %.0f | %s",
      r.harness, tostring(r.model or "default"), runner, ok and "PASS" or "FAIL", ok and "PASS" or "FAIL",
      ok and "PASS" or "FAIL", (j.durationMs or 0) / 1000, err))
  end
end
