-- lane-one-worker: keeps at most N ticket workers in progress, bounces the extra one back to todo, and prompts a freshly started idle worker
-- Args:
--   maxInProgress (number, 1)
--   exemptRoles (list, []): roles that do not count and are never bounced
--   kickPrompt (string, optional): prompt for a new idle worker; {ticket} is replaced
--   nowMarker (string, optional): title prefix that is outside the cap
--   event payload: ticketId, workerSession
-- Hook: on="lane enter in_progress"  args {"maxInProgress":1,"exemptRoles":["version-control"],"kickPrompt":"Start ticket {ticket}."}
-- Gotchas: A worker that already ran two turns is not kicked again.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args
local ticket = a.ticketId or error("lane-one-worker: no ticketId")
local max = tonumber(a.maxInProgress or 1)
local exempt = {}
for _, r in ipairs(a.exemptRoles or {}) do exempt[r:lower()] = true end
local function res(i) return input.results and input.results[i] end
local function roleOf(line) local r = line:match("| (%w[%w%-_]*) %x+ ") ; return r and r:lower() end

if input.phase == 1 then
  ih.infantry_ticket_get{ticketId = ticket, detail = "summary"}
  ih.infantry_board{columns = "in_progress", limit = 50}
  ih.again()
elseif input.phase == 2 then
  local t = (res(1) and res(1).json) or {}
  local role = tostring(t.role or t.agent or ""):gsub("^role:", ""):lower()
  if exempt[role] then ih.log("exempt role " .. role); return end
  -- nowMarker (text, optional, e.g. "[NOW]"): a ticket whose title starts with it is an owner-ordered exception to the cap.
  -- (Replaces bumping maxInProgress around a start: the hook ran after the revert and killed the new worker.)
  local title = tostring(t.title or "")
  if a.nowMarker and title:sub(1, #a.nowMarker) == a.nowMarker then ih.log("now-marker ticket: outside the cap"); return end
  local lines = (res(2) and res(2).json and res(2).json.board) or {}
  local others = 0
  for _, line in ipairs(lines) do
    local id = line:match("^  (%x+) |")
    if id and not ticket:find(id, 1, true) and not exempt[roleOf(line) or ""] then others = others + 1 end
  end
  if others >= max then
    if a.workerSession then ih.infantry_session_remove{sessionId = a.workerSession, force = true} end
    -- the bounce only moves the lane; queuedAgent (per-agent queue, AGENT-TASKS-3) is left untouched, so the [Q:] marker and queue slot survive.
    ih.call("infantry_ticket_move", {ticketId = ticket, status = "todo", top = true})
    ih.infantry_ticket_comment{ticketId = ticket, body = "lane-one-worker: " .. others .. " other ticket(s) in progress (max " .. max .. "); moved back to todo top."}
  elseif a.workerSession and a.kickPrompt then
    ih.call("infantry_get_session", {sessionId = a.workerSession, brief = true})
    ih.again()
  end
elseif input.phase == 3 then
  -- only an IDLE worker is kicked; a busy one is already working (a prompt would be refused as run_in_flight).
  local s = res(#input.results)
  local status = s and s.json and tostring(s.json.status or "") or ""
  -- WORKER-COST-1: this hook also fires when an in-progress ticket is re-saved (ticket_update); a worker that already
  -- ran turns on it (runCount >= 2) is not a fresh start, so no "start work" kick (each cost a full-context turn).
  local runs = s and s.json and tonumber(s.json.runCount or 0) or 0
  if runs >= 2 then
    ih.log("worker " .. a.workerSession .. " already ran " .. runs .. " turns; no start kick")
  elseif status:upper() == "IDLE" then
    ih.call("infantry_prompt", {sessionId = a.workerSession, prompt = (a.kickPrompt:gsub("{ticket}", ticket))})
    ih.log("kicked idle worker " .. a.workerSession)
  else
    ih.log("worker " .. a.workerSession .. " is " .. status .. "; no kick")
  end
end
