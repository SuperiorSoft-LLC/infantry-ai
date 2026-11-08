-- worker-cap: how to change the number of parallel ticket workers; prints the effective cap and why, and with fill=true starts todo tickets up to it
-- Scenario: How to change the number of parallel ticket workers. Set the setting limits.workers.maxParallel ("Max parallel workers", app or project scope; `infantry settings set limits.workers.maxParallel 2`): it is the real board cap that lane-one-worker (the guard), on-ticket-done (auto-start) and usage-governor read. limits.runners.maxWorkers is per-runner concurrency only (agents on ONE runner machine), it does not change the board cap. The usage governor lowers the effective cap to 1 while the weekly pace is >= 0 and records why in project data workers.cap.status; this script prints that line. Raising the setting does not start a ticket by itself (auto-start only fires when a ticket finishes): run this script with fill=true, or hook it on="every 120s", to start todo tickets up to the cap at once.
-- Args:
--   project (string, optional): project id whose setting and data are read
--   fill (boolean, false): start todo tickets until in-progress reaches the effective cap
--   skipMarkers (list, ["[HOLD]","[PARKED]","[Q:"]): todo titles that are never started
-- Hook: on="every 120s"  args {"fill":true}
-- Gotchas: Precedence: hook arg maxInProgress (lane-one-worker) or baseWorkers (usage-governor) override the setting; otherwise cap = min(limits.workers.maxParallel, project data workers.cap.lowered). Read-only unless fill=true.
-- Example: infantry_script_run name=worker-cap args={}
local a = input.args or {}
local function res(i) return input.results and input.results[i] end
if input.phase == 1 then
  ih.call("infantry_settings_get", {key = "limits.workers.maxParallel", projectId = a.project})
  ih.call("infantry_project_config_get", {projectId = a.project})
  ih.call("infantry_board", {projectId = a.project, columns = "in_progress", limit = 50})
  if a.fill then ih.call("infantry_board", {projectId = a.project, columns = "todo", limit = 200}) end
  ih.again()
  return
end
local v = tonumber(res(1) and res(1).json and res(1).json.value) or 1
local data = (res(2) and res(2).json and res(2).json.data) or {}
local low = tonumber(data["workers.cap.lowered"])
local eff = (low and low < v) and low or v
ih.log("Max parallel workers " .. v .. "; effective " .. eff .. ". " .. tostring(data["workers.cap.status"] ~= "" and data["workers.cap.status"] or "Not lowered by the governor."))
if not a.fill then return end
-- board result: json.board = list of "  <id> | ..." lines (as on-ticket-done reads it); the text is the fallback.
local function boardLines(r)
  if r and r.json and type(r.json.board) == "table" then return r.json.board end
  local t = {}
  for line in tostring(r and r.text or ""):gmatch("[^\n]+") do t[#t + 1] = line end
  return t
end
local running = 0
for _, line in ipairs(boardLines(res(3))) do if line:match("^  %x+ |") then running = running + 1 end end
local markers = a.skipMarkers or {"[HOLD]", "[PARKED]", "[Q:"}
for _, line in ipairs(boardLines(res(4))) do
  if running >= eff then break end
  local id, rest = line:match("^  (%x+) | (.*)$")
  if id then
    local skip = false
    for _, m in ipairs(markers) do if rest:find(m, 1, true) then skip = true end end
    if not skip then ih.call("infantry_ticket_start", {ticketId = id}); running = running + 1; ih.log("started " .. id) end
  end
end
