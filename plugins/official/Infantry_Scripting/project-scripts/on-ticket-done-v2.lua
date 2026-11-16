-- on-ticket-done-v2 (DONE-KILL-FIRST-1): kill the worker FIRST, then merge the branch tip it left.
-- Same hook and args as on-ticket-done.lua (devBranch, vcRole required; skipMarkers optional; the board starts a queued agent ticket):
--   infantry script hook add "lane enter done" on-ticket-done-v2 --payload '{"devBranch":"<dev>","vcRole":"<vc role id>","skipMarkers":["[HOLD]","[PARKED]","[Q:"]}'
-- Why: the old flow mailed the merge request with the tip read when the hook started, while the worker was still running.
-- A worker that kept committing after it said "done" was merged at a stale tip (ticket 4c22b8b3).
-- Order (three phases, the scripting maxPhases):
--   P1 kill the named worker with force; read the done and todo lanes.
--   P2 if the event named no worker, find it on the board line and kill it; re-read the done lane.
--   P3 verify the worker reads "gone" (else comment the failure and STOP: no merge request, no next ticket); then the merge
--      request naming the branch: its HEAD is final once the worker is gone, so VC merges the head it reads then
--      (a script cannot read a git tip; the board line shows "@no commit" for a gone worker), the hook-start tip is only informational; start the next todo ticket; comment.
-- Event payload (enriched by the daemon): ticketId, title, branch, tipSha, workerSession (can be missing on an explicit move; the board line names the worker then).
local a = input.args
if not a.devBranch or not a.vcRole then error("on-ticket-done-v2 needs hook args devBranch and vcRole") end
local ticket = a.ticketId
if not ticket then error("on-ticket-done-v2: no ticketId in the event payload") end

local function ok(r) return r and r.ok end
local function same(short) return ticket == short or ticket:sub(-#short) == short end

-- The board line of this ticket in a board result: its worker (8 hex chars) and whether that worker is gone.
local function lineOf(r)
  local lines = r and r.json and r.json.board or {}
  for _, line in ipairs(lines) do
    local id = line:match("^  (%x+) | ")
    if id and same(id) then
      local gone = line:match("worker (%x+) gone")
      local w = gone or line:match("|%s*%S+%s+(%x%x%x%x%x%x%x%x)%s+%a+")
      return {worker = w, gone = gone ~= nil, tip = line:match("@(%x+)%s*$"), line = line}
    end
  end
  return {}
end
local function boardsOf(results)
  local t = {}
  for _, r in ipairs(results or {}) do if r.tool == "infantry_board" then t[#t + 1] = r end end
  return t
end
local function killOf(results)
  for _, r in ipairs(results or {}) do if r.tool == "infantry_session_remove" then return r end end
end
-- the latest board result that lists this ticket (the done lane); the todo lane never does
local function doneLine(results)
  local bs = boardsOf(results)
  for i = #bs, 1, -1 do local l = lineOf(bs[i]); if l.line then return l end end
  return {}
end

if input.phase == 1 then
  if a.workerSession then ih.close_session{sessionId = a.workerSession, force = true, _optional = true} end
  ih.board{columns = "done", limit = 200}
  ih.board{columns = "todo", limit = 200}
  ih.again()

elseif input.phase == 2 then
  local seen = doneLine(input.results)
  if not a.workerSession and seen.worker and not seen.gone then
    ih.close_session{sessionId = seen.worker, force = true, _optional = true}
    ih.board{columns = "done", limit = 200}
  else
    ih.log("worker named by the event or none on the board; nothing to kill in phase 2")
  end
  ih.again()

else
  local k = killOf(input.results)
  local after = doneLine(input.results)
  local worker = a.workerSession or (k and after.worker) or nil
  local why
  if k and not ok(k) then why = "close_session force failed: " .. tostring(k.text or "no answer"):sub(1, 200)
  elseif after.worker and not after.gone then why = "board still shows worker " .. after.worker .. " live after the kill attempt" end
  if why then
    ih.comment{ticketId = ticket, body = "on-ticket-done-v2: MERGE NOT REQUESTED. The worker " .. tostring(worker or after.worker) ..
      " could not be killed (" .. why .. "). Kill it by hand (infantry session rm <id> --force), then ask '" ..
      a.vcRole .. "' to merge " .. (a.branch or "?") .. " into " .. a.devBranch .. ". The next ticket was not started."}
    return
  end
  local branch = a.branch or "(no branch)"
  local tip = a.tipSha or "(unknown)"
  ih.message{
    to = a.vcRole,
    subject = "merge " .. branch,
    body = "NEW request: merge " .. branch .. " into " .. a.devBranch .. " at the CURRENT HEAD of that branch (read it now; the hook-start tip " ..
      tip .. " may be older) --no-ff. No gate, no build. The worker is gone, so the branch head is final. Reply only with the new dev head sha, or the conflicts. (ticket " .. ticket ..
      (a.title and (": " .. a.title) or "") .. ")",
  }
  local parts = {"on-ticket-done-v2: worker killed first" .. (worker and (" (" .. worker .. ")") or " (none was running)") ..
    ", then asked '" .. a.vcRole .. "' to merge " .. branch .. " at its final head (hook-start tip " .. tip .. ") into " .. a.devBranch .. "."}
  if a.agentQueueNext then
    parts[#parts + 1] = "No todo ticket started: the agent's queue head " .. tostring(a.agentQueueNext) .. " starts when the worker exits."
  else
    local todo = boardsOf(input.results)[2]
    local lines = todo and todo.json and todo.json.board or {}
    local markers = a.skipMarkers or {}
    local next_id
    for _, line in ipairs(lines) do
      local id, rest = line:match("^  (%x+) | (.*)$")
      if id then
        local skip = same(id)
        for _, m in ipairs(markers) do if rest:find(m, 1, true) then skip = true end end
        if not skip then next_id = id; break end
      end
    end
    if next_id then
      ih.start{ticketId = next_id}
      parts[#parts + 1] = "Started the next todo ticket " .. next_id .. "."
    else
      parts[#parts + 1] = "Board is empty: no startable todo ticket (skipped " .. table.concat(markers, ", ") .. ")."
    end
  end
  ih.comment{ticketId = ticket, body = table.concat(parts, " ")}
end
