-- on-ticket-done: the "a ticket reached done" flow as a script (SCRIPT-PROC-1; D21).
-- Hook:  infantry script hook add "lane enter done" on-ticket-done --payload '{"devBranch":"<dev branch>","vcRole":"<vc role id>","skipMarkers":["[HOLD]","[PARKED]"]}'
-- Install: copy this file to <project>/.infantry/scripts/on-ticket-done.lua.
-- Config is hook args (data), never literals here: devBranch, vcRole (both required), skipMarkers (list, optional).
-- Event payload (enriched by the daemon): ticketId, title, branch, tipSha, workerSession.
-- Phase 1: message VC, close the worker session, read the todo lane.  Phase 2: start the next ticket.  Phase 3: comment what was done.
local a = input.args
if not a.devBranch or not a.vcRole then error("on-ticket-done needs hook args devBranch and vcRole") end
local ticket = a.ticketId
if not ticket then error("on-ticket-done: no ticketId in the event payload") end

local function result(i) return input.results and input.results[i] end
local function ok(r) return r and r.ok end

if input.phase == 1 then
  local branch = a.branch or "(no branch)"
  local tip = a.tipSha or "(unknown tip)"
  ih.message{
    to = a.vcRole,
    subject = "merge " .. branch,
    body = "NEW request: merge " .. branch .. " tip " .. tip .. " into " .. a.devBranch ..
      " --no-ff. No gate, no build. Reply only with the new dev head sha, or the conflicts. (ticket " .. ticket ..
      (a.title and (": " .. a.title) or "") .. ")",
  }
  if a.workerSession then ih.close_session{sessionId = a.workerSession, force = true, _optional = true} end
  ih.board{columns = "todo", limit = 200}
  ih.again()

elseif input.phase == 2 then
  -- the board is the last action of phase 1; pending (open prerequisites) is its own lane, so todo has none.
  local board = result(#input.results)
  local lines = board and board.json and board.json.board or {}
  local markers = a.skipMarkers or {}
  local next_id
  for _, line in ipairs(lines) do
    local id, rest = line:match("^  (%x+) | (.*)$")
    if id then
      local skip = false
      for _, m in ipairs(markers) do if rest:find(m, 1, true) then skip = true end end
      if not skip then next_id = id; break end
    end
  end
  if next_id then
    ih.start{ticketId = next_id}
    ih.log("next ticket " .. next_id)
  else
    ih.log("no startable todo ticket")
  end
  ih.again()

else
  local n = #input.results
  local started = result(n)
  local didStart = started and started.tool == "infantry_ticket_start"
  local parts = {
    "on-ticket-done: asked '" .. a.vcRole .. "' to merge " .. (a.branch or "?") .. " tip " .. (a.tipSha or "?") .. " into " .. a.devBranch ..
      (ok(result(1)) and " (queued)" or " (FAILED)") .. ".",
    a.workerSession and ("Closed worker session " .. a.workerSession .. (ok(result(2)) and "." or " (failed).")) or "No worker session named; none closed.",
  }
  if didStart then parts[#parts + 1] = "Started the next todo ticket: " .. (started.text or ""):sub(1, 200)
  else parts[#parts + 1] = "Board is empty: no startable todo ticket (skipped " .. table.concat(a.skipMarkers or {}, ", ") .. ")." end
  ih.comment{ticketId = ticket, body = table.concat(parts, " ")}
end
