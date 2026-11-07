-- lane-pending-note: says on the ticket that it waits on prerequisites; when it leaves pending for the start lane, starts it
-- Args:
--   startLane (string, optional): name of the lane that means start (e.g. todo)
--   event payload: ticketId, from, to
-- Hook: on="lane enter pending" and on="lane exit pending"  args {"startLane":"todo"}
-- Gotchas: Without startLane it only writes the note.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args
local ticket = a.ticketId or error("lane-pending-note: no ticketId")
if a.from and a.from:lower() == "pending" then
  if a.startLane and a.to and a.to:lower() == a.startLane:lower() then ih.infantry_ticket_start{ticketId = ticket} end
else
  ih.infantry_ticket_comment{ticketId = ticket, body = "lane-pending-note: waiting on open prerequisites; it starts when they are done."}
end
