-- worker-silent-ping: asks a ticket worker for status when it is busy (or waiting on a background job) but has written nothing for N minutes
-- Scenario: Ask a worker for status when it has been busy but silent for too long.
-- Args:
--   pm (string, optional): session id told about each ping
--   silentMinutes (number, 15): silence that triggers a ping, repeated every window
--   tickSec (number, 60): the hook period
--   exemptRoles (list, [pm,assistant,version-control]): roles never pinged
--   onlySession (string, optional): only sessions whose id starts with this
--   includeNonAgents (bool, false): also ping sessions with no agent id
-- Hook: on="every 60s"  args {"pm":"<session id>","silentMinutes":15,"tickSec":60}
-- Gotchas: The mail is replaced by a chat note for the PM's own session (the app refuses mail to yourself).
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local silentMs = (tonumber(a.silentMinutes) or 15) * 60000
local tickMs = (tonumber(a.tickSec) or 60) * 1000
local exempt = {}
for _, r in ipairs(a.exemptRoles or {"pm", "assistant", "version-control"}) do exempt[tostring(r):lower()] = true end

if input.phase == 1 then
  ih.infantry_list_sessions{}
  ih.again()
  return
end

local res = input.results and input.results[1]
local list = (res and res.json and res.json.sessions) or {}
local now = os.time() * 1000
for _, s in ipairs(list) do
  local st = tostring(s.status or ""):upper()
  local role = tostring(s.role or ""):lower()
  -- the session list carries no ticketId; a worker = a session with an agentId (ticket workers) in a non-exempt role
  local ticket = s.ticketId and tostring(s.ticketId):gsub("^ticket%-", "") or (s.progress and s.progress.label) or "its ticket"
  -- owner 2026-11-06: "its been working for idle, not for running background processes": a session that is IDLE but
  -- has a background job running counts too (it is waiting on that job and may never come back on its own)
  local bgRunning = tostring(s.backgroundWork or "") ~= ""
  local isWorker = s.agentId or a.includeNonAgents
  local onlyOk = not a.onlySession or tostring(s.id):sub(1, #a.onlySession) == a.onlySession
  -- WAITING-FLAG-1: a session that flagged itself waiting (infantry waiting on) and whose flag still counts is not silent
  local flagged = s.waiting and s.waiting.active
  if isWorker and onlyOk and not flagged and not exempt[role] and (st == "BUSY" or st == "RUNNING" or st == "WORKING" or bgRunning) then
    local p = s.progress or {}
    local last = tonumber(s.lastTurnAt) or tonumber(s.lastOutputAt) or tonumber(p.lastOutputAt) or tonumber(p.updatedAt) or tonumber(p.startedAt) or tonumber(s.lastEventAt)
    if last then
      local silent = now - last
      -- fire once per silentMs window: at 15m, 30m, 45m ... of silence
      if silent >= silentMs and (silent % silentMs) < tickMs then
        local mins = math.floor(silent / 60000)
        -- 2026-11-06 self-test: the app refuses mail to the sending session itself, and one failed action ends the run
        -- (every later ping is lost). The script runs as the hook's owner (args.pm): for that session use a note
        -- (allowed on your own session, still wakes it) instead of mail.
        local self = a.pm and tostring(s.id) == tostring(a.pm)
        if self then
          ih.infantry_chat_note{sessionId = s.id, level = "info", wake = true,
            text = "STATUS CHECK (worker-silent-ping): you have written nothing for " .. mins .. " min while busy or waiting on a background job. Check it is not hung."}
        else
        ih.infantry_message{to = s.id, subject = "Status check",
          body = "STATUS CHECK (worker-silent-ping): you have not written anything for " .. mins .. " min on ticket " ..
            ticket .. ". Reply in one line: what you are doing right now, and whether you are stuck or waiting on something. " ..
            "If a tool or background job is hung, stop it and continue or report blocked."}
        end
        if a.pm and not self then
          ih.infantry_chat_note{sessionId = a.pm, level = "info", wake = true,
            text = "SILENT WORKER: " .. role .. " " .. tostring(s.id):sub(1, 8) .. " (" .. ticket .. ")" ..
              " is busy but wrote nothing for " .. mins .. " min; asked it for status."}
        end
        ih.log("pinged " .. tostring(s.id):sub(1, 8) .. " silent " .. mins .. "m")
      end
    end
  end
end
