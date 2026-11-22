-- SCENARIO gh-build-done (owner 2026-11-06: "There is no way to build a script to wake you up when the build finishes?"):
-- every N seconds, ask GitHub (public API, no token) for workflow runs of the repo that FINISHED within the last N seconds,
-- and wake the PM with one line per run: workflow, title, conclusion, and every non-success job (macOS included).
-- Stateless: the window is the hook interval, so each finished run is reported once. No model, no tokens.
-- WAKE-3: each line is mailed to the PM with kind build.success (green: attention inbox, no turn) or build.failed (wakes), key = run id,
-- so the notify.wake.kinds policy decides. args.fixture = canned exec output for an end-to-end test (skips GitHub).
-- Hook: on="every 120s", args {"pm":"<pm session>","windowSec":120}  (windowSec MUST equal the hook interval)
local a = input.args or {}
local repo = a.repo or "SuperiorSoft-LLC/infantry-ai"
local win = tonumber(a.windowSec) or 120
local ps = table.concat({
  "$ErrorActionPreference='Continue'",
  "$cut=(Get-Date).ToUniversalTime().AddSeconds(-" .. win .. ")",
  "$runs=(Invoke-RestMethod -Uri 'https://api.github.com/repos/" .. repo .. "/actions/runs?per_page=15' -Headers @{'User-Agent'='infantry-gh-build-done'}).workflow_runs",
  "foreach ($r in $runs) { if ($r.status -ne 'completed') { continue }; $t=([datetime]$r.updated_at).ToUniversalTime(); if ($t -le $cut) { continue }; " ..
    "$bad=((Invoke-RestMethod -Uri $r.jobs_url -Headers @{'User-Agent'='infantry-gh-build-done'}).jobs | Where-Object { $_.conclusion -ne 'success' -and $_.conclusion -ne 'skipped' } | ForEach-Object { $_.name + '=' + $_.conclusion }) -join ', '; " ..
    "if (-not $bad) { $bad='all jobs green' }; Write-Output ('BUILD DONE: ' + $r.name + ' | ' + $r.display_title + ' | ' + $r.conclusion + ' | ' + $bad + ' | run ' + $r.id) }",
}, "; ")
if input.phase == 1 then
  if a.fixture then
    ih.log("fixture")
    ih.again()
    return
  end
  ih.agenthq_exec{command = {"powershell", "-NoProfile", "-Command", ps}, timeoutSec = 60}
  ih.again()
  return
end
local r = input.results and input.results[#input.results]
local out = a.fixture or (r and r.json and tostring(r.json.stdoutTail or "") or tostring(r and r.text or ""))
for line in out:gmatch("BUILD DONE:[^\r\n]+") do
  ih.log(line)
  if a.pm then
    local green = line:find("| success |", 1, true) ~= nil
    local run = line:match("| run (%d+)") or line
    ih.infantry_message{to = a.pm, subject = (green and "BUILD DONE (green)" or "BUILD FAILED"), body = line:sub(1, 600),
      kind = (green and "build.success" or "build.failed"), key = run}
  end
end
