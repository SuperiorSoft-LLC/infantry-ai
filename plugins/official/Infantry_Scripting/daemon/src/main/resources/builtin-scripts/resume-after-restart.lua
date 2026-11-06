-- resume-after-restart (shipped by Infantry_Scripting, BRIDGE-EVENTS-1; was RESUME-NOTIFY-1's core code).
-- On daemon.started: every session whose turn the stop cut is prompted to continue where it left off; whoever waits on it
-- (its ticket, else its starter session) is told, and the owner gets ONE attention item with the summary. A session whose
-- process outlived the daemon (orphaned) is still running: it is named, never prompted.
-- Args (settings of the Scripting plugin): resume (scripting.restart.resume, default true; false = tell only, prompt nobody),
-- prompt (scripting.restart.prompt; "" = the default below). Turn the whole hook off with scripting.builtins.off = resume-after-restart.
-- Tools are called by their registry names, so the run never depends on the alias table.
-- Payload: cutTurns / orphaned = [{sessionId, role, agentId, ticketId, projectId, lastAction, lastOutputAt, starter}].
local a = input.args or {}
local cut = a.cutTurns or {}
local orphaned = a.orphaned or {}
if #cut == 0 and #orphaned == 0 then return end
local resume = a.resume ~= false
local prompt = (type(a.prompt) == "string" and a.prompt ~= "") and a.prompt
  or "The daemon restarted and your last turn was cut. Continue where you left off."

local function label(s)
  return (s.role or "-") .. "/" .. (s.agentId or tostring(s.sessionId):sub(1, 8)) .. " (session " .. tostring(s.sessionId):sub(1, 8)
    .. (s.ticketId and (", ticket " .. s.ticketId) or "") .. ")"
end

-- Phase 1: prompt the cut sessions (a failure is recorded, not fatal), then come back for the results.
if (input.phase or 1) == 1 then
  if resume then
    for _, s in ipairs(cut) do ih.infantry_prompt{sessionId = s.sessionId, prompt = prompt, _optional = true} end
    if #cut > 0 then ih.again() return end
  end
end

-- Phase 2 (or phase 1 with resume off / nothing cut): what each prompt did.
-- Results line up with cut, in order (one prompt per cut session).
local failed = {}
local i = 0
for _, r in ipairs(input.results or {}) do
  if r.tool == "infantry_prompt" then
    i = i + 1
    local s = cut[i]
    if s and r.ok == false then failed[s.sessionId] = tostring(r.text or "refused"):sub(1, 200) end
  end
end

local function tell(s, line)
  if s.ticketId then
    ih.infantry_ticket_comment{ticketId = s.ticketId, kind = "issue", body = line, _optional = true}
  elseif s.starter then
    ih.infantry_message{to = s.starter, subject = "[AUTOMATIC] daemon restart cut a turn", body = line, _optional = true}
  end
end

local lines, ids, resumed = {}, {}, 0
for _, s in ipairs(cut) do
  ids[#ids + 1] = s.sessionId
  if not resume then
    tell(s, label(s) .. ": the daemon restarted and cut its turn; auto-resume is off (scripting.restart.resume), so it is idle until prompted.")
    lines[#lines + 1] = "- " .. label(s) .. ": not resumed (auto-resume off)"
  elseif failed[s.sessionId] then
    tell(s, label(s) .. ": the daemon restarted and cut its turn; it could NOT be resumed (" .. failed[s.sessionId] .. "). It needs a prompt.")
    lines[#lines + 1] = "- " .. label(s) .. ": NOT resumed (" .. failed[s.sessionId] .. ")"
  else
    resumed = resumed + 1
    tell(s, label(s) .. ": the daemon restarted and cut its turn; it was prompted to continue where it left off.")
    lines[#lines + 1] = "- " .. label(s) .. ": resumed"
  end
end
for _, s in ipairs(orphaned) do
  ids[#ids + 1] = s.sessionId
  tell(s, label(s) .. ": the daemon restarted while its turn ran; its process is still alive, so it was not prompted.")
  lines[#lines + 1] = "- " .. label(s) .. ": process still running, not prompted"
end

local total = #cut + #orphaned
local one = (total == 1) and (cut[1] or orphaned[1]) or nil
ih.infantry_attention_raise{
  kind = "session_resumed", outcome = "resumed_after_restart",
  sessionId = one and one.sessionId or nil, ticketId = one and one.ticketId or nil,
  detail = (total .. " session(s) had their turn cut by a daemon restart; " .. resumed .. " resumed.\n" .. table.concat(lines, "\n")):sub(1, 2000),
  _optional = true,
}
ih.log("restart: cut=" .. #cut .. " resumed=" .. resumed .. " orphaned=" .. #orphaned)
