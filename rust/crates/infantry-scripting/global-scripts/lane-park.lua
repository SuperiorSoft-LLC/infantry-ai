-- lane-park: a ticket leaving in_progress for anything but the done lane, or entering backlog, gives up its worker
-- Scenario: A ticket leaving in progress without finishing must release its worker.
-- Args:
--   doneLane (string, required): name of the done lane
--   event payload: ticketId, from, to, workerSession
-- Hook: on="lane enter backlog" and on="lane exit in_progress"  args {"doneLane":"done"}
-- Gotchas: Comments on the ticket.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args
local ticket = a.ticketId or error("lane-park: no ticketId")
local done = a.doneLane or error("lane-park needs doneLane")
if a.to and a.to:lower() == done:lower() then return end
if input.phase == 1 and a.workerSession then
  ih.infantry_session_remove{sessionId = a.workerSession, force = true}
  ih.again()
else
  ih.infantry_ticket_comment{ticketId = ticket, body = "lane-park: moved " .. tostring(a.from) .. " -> " .. tostring(a.to) ..
    (a.workerSession and ("; closed worker " .. a.workerSession) or "; no worker attached") .. "."}
end
