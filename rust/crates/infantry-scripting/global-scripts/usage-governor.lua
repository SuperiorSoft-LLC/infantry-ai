-- usage-governor: reads the weekly usage pace and steps worker capacity: normal -> one worker -> cheaper model only; restores when the pace drops
-- Scenario: Slow down workers when the weekly usage pace runs ahead of the plan, and restore when it drops.
-- Args:
--   project (string, required): the project id whose routing and data are changed
--   pm (string, optional): session id told on each change
--   baseWorkers (number, optional): OVERRIDE of the normal-mode worker count; absent = the setting limits.workers.maxParallel ("Max parallel workers")
--   sonnet (string, claude-sonnet-5-5): the cheaper model id to re-pin to
-- Hook: on="every 900s"  args {"project":"<project id>","pm":"<session id>","baseWorkers":2}
-- Gotchas: Its base is the setting limits.workers.maxParallel (script worker-cap prints it); when it lowers the cap it records why in project data workers.cap.status. limits.runners.maxWorkers is per-runner concurrency only. State is kept in project data (governor.mode, governor.lastPace, governor.savedRouting). Fails open when no weekly pace is reported.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local P = a.project or error("usage-governor needs args.project")
local base = tonumber(a.baseWorkers) -- override; else set from the setting in phase 2
local SONNET = a.sonnet or "claude-sonnet-5-5"

local function enc(v)
  local t = type(v)
  if t == "nil" then return "null" end
  if t == "boolean" or t == "number" then return tostring(v) end
  if t == "string" then return '"' .. v:gsub('\\', '\\\\'):gsub('"', '\\"') .. '"' end
  if #v > 0 or next(v) == nil then
    local o = {}
    for i = 1, #v do o[i] = enc(v[i]) end
    return "[" .. table.concat(o, ",") .. "]"
  end
  local o = {}
  for k, x in pairs(v) do o[#o + 1] = enc(tostring(k)) .. ":" .. enc(x) end
  return "{" .. table.concat(o, ",") .. "}"
end
local function tell(text, sound)
  ih.call("infantry_notify", {title = "Usage governor", body = text, level = "info", sound = sound or "chime"})
  if a.pm then ih.call("infantry_chat_note", {sessionId = a.pm, level = "info", wake = true, text = "USAGE GOVERNOR: " .. text}) end
end

-- os.date is not in the sandbox: UTC "YYYY-MM-DD HH:MM" from os.time() (civil-from-days).
local function utcNow()
  local t = os.time()
  local days, rem = math.floor(t / 86400), t % 86400
  local z = days + 719468
  local era = math.floor(z / 146097)
  local doe = z - era * 146097
  local yoe = math.floor((doe - math.floor(doe / 1460) + math.floor(doe / 36524) - math.floor(doe / 146096)) / 365)
  local doy = doe - (365 * yoe + math.floor(yoe / 4) - math.floor(yoe / 100))
  local mp = math.floor((5 * doy + 2) / 153)
  local d = doy - math.floor((153 * mp + 2) / 5) + 1
  local m = mp < 10 and mp + 3 or mp - 9
  local y = yoe + era * 400 + (m <= 2 and 1 or 0)
  return string.format("%04d-%02d-%02d %02d:%02d UTC", y, m, d, math.floor(rem / 3600), math.floor(rem % 3600 / 60))
end
local stage = a.stage
if input.phase == 1 then
  ih.call("infantry_usage", {json = true, maxChars = 20000})
  ih.call("infantry_settings_get", {key = "usage.pacing"})
  ih.call("infantry_project_config_get", {projectId = P})
  ih.call("infantry_settings_get", {key = "limits.workers.maxParallel", projectId = P})
  ih.again()
  return
end

local r = input.results or {}
-- phase 3+: the todo re-pin chain after a switch to sonnet
if input.phase >= 3 then
  local last = r[#r]
  if last and last.tool == "infantry_board" then
    local n = 0
    for id in tostring(last.text):gmatch('"%s*(%x%x%x%x%x%x%x%x) |') do
      ih.call("infantry_ticket_get", {ticketId = id, detail = "full", maxChars = 400000}); n = n + 1
    end
    if n > 0 then ih.again() end
    return
  end
  local moved = 0
  for _, x in ipairs(r) do
    if x.tool == "infantry_ticket_get" and x.json and tostring(x.json.model or ""):find("opus") and x.json.status == "todo" then
      ih.call("infantry_ticket_update", {ticketId = x.json.ticketId, provider = "claude", model = SONNET}); moved = moved + 1
    end
  end
  ih.log("re-pinned " .. moved .. " todo tickets to sonnet")
  return
end

-- phase 2: decide
local utext = tostring(r[1] and r[1].text or "")
local claude = utext:match('"meter":"claude"(.-)"meter":') or utext:match('"meter":"claude"(.*)') or ""
local pace = tonumber(claude:match('"label":"Current week".-"paceDelta":(%-?[%d%.]+)'))
if not pace then ih.log("no weekly claude pace; nothing done (fail open)"); return end
local pacing = (r[2] and r[2].json and r[2].json.value) or {}
local cfg = (r[3] and r[3].json) or {}
local data = cfg.data or {}
base = base or tonumber(r[4] and r[4].json and r[4].json.value) or 1
local mode = data["governor.mode"] or "normal"
local lastPace = tonumber(data["governor.lastPace"])

local function setWorkers(n)
  pacing.maxWorkers = n
  ih.call("infantry_settings_set", {key = "usage.pacing", scope = "app", value = enc(pacing)})
end
local function setData(k, v) ih.call("infantry_project_config_set", {projectId = P, dataKey = k, value = v}) end

local p = string.format("%+.1f", pace)
if pace < 0 then
  if mode ~= "normal" then
    setWorkers(base)
    if mode == "sonnet" and data["governor.savedRouting"] then
      ih.call("infantry_project_config_set", {projectId = P, routingRules = data["governor.savedRouting"]})
    end
    setData("governor.mode", "normal")
    setData("workers.cap.lowered", "")
    setData("workers.cap.status", "")
    tell("weekly pace back under even (" .. p .. "): " .. base .. " workers" .. (mode == "sonnet" and ", Opus routing restored" or "") .. ".")
  elseif (tonumber(pacing.maxWorkers) or 0) ~= base then
    setWorkers(base)
  end
elseif mode == "normal" then
  setWorkers(1)
  setData("governor.mode", "one")
  setData("workers.cap.lowered", "1")
  setData("workers.cap.status", "Lowered to 1 by the usage governor since " .. utcNow() .. ": weekly pace " .. p .. ". Restores to " .. base .. " (Max parallel workers) when the weekly pace drops below 0.")
  tell("weekly pace " .. p .. " (at or above even): down to 1 worker.", "bell")
elseif mode == "one" and lastPace and pace > lastPace then
  local rules = cfg.routingRules or {}
  setData("governor.savedRouting", enc(rules))
  local keep = {}
  for _, ru in ipairs(rules) do
    if not tostring(ru.model or ""):find("opus") then keep[#keep + 1] = ru end
  end
  if #keep == 0 or keep[#keep].weights or keep[#keep].ticketTypes or keep[#keep].role then
    keep[#keep + 1] = {name = "default-sonnet", provider = "claude", model = SONNET}
  end
  ih.call("infantry_project_config_set", {projectId = P, routingRules = enc(keep)})
  setData("governor.mode", "sonnet")
  tell("weekly pace still rising (" .. string.format("%+.1f", lastPace) .. " -> " .. p .. "): Sonnet only, Opus todo tickets re-pinned. Running workers untouched.", "alarm")
  setData("governor.lastPace", tostring(pace))
  ih.call("infantry_board", {projectId = P, columns = "todo", limit = 200})
  ih.again()
  return
end
setData("governor.lastPace", tostring(pace))
ih.log("pace " .. p .. " mode " .. mode)
