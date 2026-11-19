-- hook-health: watches every script's run history; alerts when a script fails N runs in a row (the watcher of the watchers)
-- Scenario: Alert when a script keeps failing, so a silently broken hook is noticed (watcher of the watchers).
-- Args:
--   pm (string, optional): session id woken with the alert
--   failRun (number, 3): failures in a row that raise the alert
--   repeatEvery (number, 30): alert again every this many further failures
--   windowSec (number, 120): only failures newer than this count as new; keep it equal to the hook period
--   logOnly (bool, false): log what would be alerted, send nothing
-- Hook: on="every 120s"  args {"pm":"<session id>","failRun":3,"repeatEvery":30}
-- Gotchas: Needs infantry_script_stats and infantry_notify. Ignores its own failures.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local need = tonumber(a.failRun) or 3
local rep = tonumber(a.repeatEvery) or 30

if input.phase == 1 then
  ih.call("infantry_script_stats", {limit = 150, detail = "full", maxChars = 400000})
  ih.again()
  return
end

local r = input.results and input.results[1]
local runs = (r and r.json and r.json.runs) or {}
-- runs are newest first: count the leading streak of failures per script
-- 2026-11-07 fix: alert only when the newest failure is new since the last tick (windowSec, default 130). Without it a
-- script that stopped running (deleted probe) kept its streak of 3 and re-alerted every tick forever.
local windowMs = (tonumber(a.windowSec) or 120) * 1000 -- = the hook period, so no run is counted in two ticks
local now = os.time() * 1000
-- k = failures of the streak that happened inside the window (new since the last tick). The streak was n-k at the last
-- tick; alert when it CROSSED need (or a further multiple of rep) since then. A 60s hook adds ~2 per 120s tick, so an
-- exact "n == need" test could skip 3 (2 -> 4) and never fire.
local streak, done, lastErr, fresh = {}, {}, {}, {}
for _, run in ipairs(runs) do
  local name = tostring(run.script or "?")
  if name ~= "hook-health" and not done[name] then
    if run.ok == false or tostring(run.outcome) == "failed" then
      streak[name] = (streak[name] or 0) + 1
      lastErr[name] = lastErr[name] or tostring(run.error or run.summary or "")
      local at = tonumber(run.at)
      if at and (now - at) < windowMs then fresh[name] = (fresh[name] or 0) + 1 end
    else
      done[name] = true
    end
  end
end
local function band(x) if x < need then return -1 end return math.floor((x - need) / rep) end
for name, n in pairs(streak) do
  local before = n - (fresh[name] or 0)
  if band(n) > band(before) then
    -- BOARD-COOLDOWN-STUCK-1: a daemon plugin's breaker refusing the script's calls is not a script bug: say which plugin
    -- is cooling down, since when and at which strike, and do not ask anyone to fix the script.
    local err = lastErr[name]
    local plugin, since = err:match("plugin (%S+) is cooling down since (%d%d:%d%d %(strike %d+%))")
    local msg, tail, title
    if plugin then
      msg = "HOOK BLOCKED: script '" .. name .. "' failed " .. n .. " runs in a row: plugin " .. plugin .. " cooling down since " .. since
      tail = " The script is fine; it runs again when the plugin's breaker closes (hook-health)."
      title = "Infantry plugin cooling down"
    else
      msg = "HOOK BROKEN: script '" .. name .. "' failed " .. n .. " runs in a row: " .. err:sub(1, 160)
      tail = " Fix the script now (hook-health)."
      title = "Infantry automation broken"
    end
    if a.logOnly then ih.log("WOULD ALERT: " .. msg) else
    ih.call("infantry_notify", {title = title, body = msg:sub(1, 200), level = "milestone", sound = "bell"})
    if a.pm then
      ih.call("infantry_chat_note", {sessionId = a.pm, level = "fail", wake = true, text = msg .. tail})
    end
    ih.log(msg)
    end
  end
end
if not r or r.ok == false then
  ih.call("infantry_notify", {title = "Infantry automation broken", body = "hook-health cannot read script_stats: " .. tostring(r and r.text or "no result"):sub(1, 150), level = "milestone", sound = "bell"})
end
