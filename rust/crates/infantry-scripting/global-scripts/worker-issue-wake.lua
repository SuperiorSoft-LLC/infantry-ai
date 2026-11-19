-- worker-issue-wake: wakes the PM at once when a ticket worker stops with an open ISSUE comment, instead of waiting for the idle kick
-- Scenario: Wake the PM at once when a worker stops with an open issue comment.
-- Args:
--   wake (string, required): session id of the PM to wake
--   authorPrefix (string, agent-): comment authors that count as workers
--   event payload: ticketId
-- Hook: on="ticket.commented"  args {"wake":"<pm session id>"}
-- Gotchas: Does nothing unless wake is given.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local rs = input.results or {}
local tid = tostring(a.ticketId or ""):gsub("^ticket%-", "")
if tid == "" or not a.wake then return end

if input.phase == 1 then
  ih.infantry_ticket_comments{ticketId = tid, which = "latest"}
  ih.again()
  return
end

local txt = tostring((rs[1] and rs[1].text) or "")
local prefix = a.authorPrefix or "agent-"
if txt:find("issue/open by " .. prefix, 1, true) then
  ih.infantry_chat_note{sessionId = a.wake, level = "fail", wake = true,
    text = "WORKER ISSUE on " .. tid:sub(1, 8) .. " (worker stopped; answer it or kick it): " .. txt:sub(1, 600)}
  ih.log("woke PM for worker issue on " .. tid:sub(1, 8))
end
