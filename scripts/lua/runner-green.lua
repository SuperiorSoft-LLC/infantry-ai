-- runner-green (GREEN-GAP-1, SPAWN-PARAMS-1): the check the PM runs BEFORE telling the owner a runner/harness is green.
-- Each row starts a session THROUGH THE APP'S START PATH (ih.spawn = infantry_spawn = the `new` frame's options and checks, then
-- LocalRunner.start: placement on the runner, the lent harness key, Tool approval `ask` so the card relays to this app), then:
--   ping-pong: the task asks for one shell call, then the single word "pong";
--   approval:  that shell call opens ONE approval card here, answered allow_once (ih.prompt_answer; owner or a pm only);
--   end:       the session is killed; seconds = start to end; error = the spawn refusal or the session's own [error] line.
-- A column that did not run prints NOT-TESTED, never PASS; a row is green only when every column is PASS.
-- Run it on a daemon paired with a throwaway runner (test ports 18900-18999, never the owner's runner). It never prints a bearer.
-- args: {runner="hm", role="fullstack", projectId=..., waitSec=40, waitCmd={...},
--        rows={{harness="pi", model="commandcode/..."}, {harness="opencode", model="commandcode/..."}}}
-- Needs scripting.maxPhases >= 6 and scripting.exec.enabled (the waits are ih.exec of waitCmd).
-- The whole run is ONE plugin call: keep 2 * waitSec well under its hang ceiling (120 s seen; limits.plugin.guard.timeoutMs).
local a = input.args or {}
local runner = a.runner or "hm"
local role = a.role or "fullstack"
local waitSec = tonumber(a.waitSec) or 40
local waitCmd = a.waitCmd or {"powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds " .. waitSec}
local rows = a.rows or {
  {harness = "pi", model = a.piModel},
  {harness = "opencode", model = a.opencodeModel},
}
local TASK = "Run exactly one shell command with your shell/bash tool: echo infantry-approval-check . " ..
  "After it ran (or was denied), reply with exactly one word: pong"
local N = #rows
local res = input.results or {}
local phase = input.phase or 1

local function json(i) return (res[i] or {}).json or {} end
local function text(i) return (res[i] or {}).text or "" end
local function oneLine(s) return (tostring(s or "")):gsub("%s+", " "):sub(1, 220) end

-- input.results is cumulative, in order: phase 1 = N spawns + 1 wait; phase 2 = one card listing per started row;
-- phase 3 = one answer per row with a card + 1 wait; phase 4 = one session_read per started row; phase 5 = one kill each;
-- phase 6 prints the table.
local sessions = {}
local spawnErr = {}
for i = 1, N do
  local j = json(i)
  if (res[i] or {}).ok and j.sessionId then sessions[i] = j.sessionId else spawnErr[i] = oneLine(text(i)) end
end

if phase == 1 then
  for _, r in ipairs(rows) do
    ih.spawn{role = role, projectId = a.projectId, provider = r.harness, model = r.model, runner = runner,
      toolApproval = "ask", task = TASK, _optional = true}
  end
  ih.exec{command = waitCmd, timeoutSec = waitSec + 30, _optional = true}
  ih.log("started at " .. os.time())
  ih.again()
  return
end

local base3 = N + 2
if phase == 2 then
  for i = 1, N do
    if sessions[i] then ih.prompt_answer{sessionId = sessions[i], _optional = true} end
  end
  ih.again()
  return
end

-- the card listing of each started row (phase 2's actions, in row order)
local cards, listErr, k = {}, {}, base3
for i = 1, N do
  if sessions[i] then
    if not (res[k] or {}).ok then listErr[i] = "card listing refused: " .. oneLine(text(k)) end
    local open = (json(k).open or {})
    local first = open[1]
    if first and first.kind == "tool_approval" then cards[i] = first.promptId end
    k = k + 1
  end
end
local afterList = k

if phase == 3 then
  for i = 1, N do
    if cards[i] then ih.prompt_answer{sessionId = sessions[i], promptId = cards[i], decision = "allow_once", _optional = true} end
  end
  ih.exec{command = waitCmd, timeoutSec = waitSec + 30, _optional = true}
  ih.again()
  return
end

local answered, k2 = {}, afterList
for i = 1, N do
  if cards[i] then answered[i] = (res[k2] or {}).ok == true and json(k2).answered == true; if not answered[i] then answered[i] = oneLine(text(k2)) end; k2 = k2 + 1 end
end
k2 = k2 + 1 -- the wait
local afterAnswer = k2

if phase == 4 then
  for i = 1, N do
    if sessions[i] then ih.session_read{sessionId = sessions[i], lines = 60, _optional = true} end
  end
  ih.again()
  return
end

local transcript, k3 = {}, afterAnswer
for i = 1, N do
  if sessions[i] then transcript[i] = json(k3); k3 = k3 + 1 end
end
local afterRead = k3

if phase == 5 then
  for i = 1, N do
    if sessions[i] then ih.kill{sessionId = sessions[i], force = true, _optional = true} end
  end
  ih.again()
  return
end

-- phase 6: the table
local ended, k4 = {}, afterRead
for i = 1, N do
  if sessions[i] then ended[i] = (res[k4] or {}).ok == true; k4 = k4 + 1 end
end
-- seconds: scripts keep no clock between phases, so a row's time is the run's measured waits (start to the end request)
local secs = 0
for _, r in ipairs(res) do if r.name == "exec" and r.json and r.json.durationMs then secs = secs + r.json.durationMs / 1000 end end

ih.log("row | placement | start | approval | ping-pong | end | seconds | error")
local green = 0
for i, r in ipairs(rows) do
  local label = r.harness .. "/" .. tostring(r.model or "default")
  if not sessions[i] then
    ih.log(string.format("%s | runner:%s | FAIL | NOT-TESTED | NOT-TESTED | NOT-TESTED | 0 | %s", label, runner,
      spawnErr[i] ~= "" and spawnErr[i] or "NO ERROR TEXT (that is the bug: file it)"))
  else
    local t = transcript[i] or {}
    local pong, err = false, nil
    for _, line in ipairs(t.lines or {}) do
      local l = tostring(line)
      if l:sub(1, 1) ~= "[" and l:lower():match("^%s*%**pong%**[%.!]?%s*$") then pong = true end
      -- a harness whose raw lines are JSON events (opencode --format json) carries the answer as a "text" field
      if l:match('"type"%s*:%s*"text"') and l:lower():match('"text"%s*:%s*"%s*%**pong%**[%.!]?%s*"') then pong = true end
      -- a local transcript marks errors "[error] ..."; a remote one (read from the runner) carries the harness's own stderr
      if not err and (l:sub(1, 7) == "[error]" or l:match("^%s*[Ee]rror:") or l:match("turn failed:")) then err = oneLine(l) end
    end
    if not err and (t.state == "FAILED" or t.state == "failed") then err = "session FAILED: " .. oneLine(t.lastError or "no error line in the transcript") end
    local approval = cards[i] and (answered[i] == true and "PASS" or "FAIL") or "FAIL"
    if cards[i] and answered[i] ~= true and not err then err = "answer refused: " .. tostring(answered[i]) end
    if not cards[i] and not err and listErr[i] then err = listErr[i] end
    if not cards[i] and not err then err = "no approval card opened (state " .. tostring(t.state) .. ")" end
    local row = {"PASS", approval, pong and "PASS" or "FAIL", ended[i] and "PASS" or "FAIL"}
    local all = true
    for _, c in ipairs(row) do if c ~= "PASS" then all = false end end
    if all then green = green + 1 end
    ih.log(string.format("%s | runner:%s | %s | %s | %s | %s | %s | %s", label, runner, row[1], row[2], row[3], row[4], tostring(math.floor(secs + 0.5)),
      err or (all and "-" or "see session " .. sessions[i])))
  end
end
ih.log(string.format("GREEN %d of %d rows", green, N))
-- the tail of each row's transcript, so a FAIL can be checked without opening the session
for i, r in ipairs(rows) do
  local lines = (transcript[i] or {}).lines or {}
  for j = math.max(1, #lines - 5), #lines do ih.log(string.format("  %s> %s", r.harness, oneLine(lines[j]))) end
end
