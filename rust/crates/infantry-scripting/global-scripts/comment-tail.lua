-- comment-tail: read-only: print the last N chars of a ticket's latest comment of a kind (tool results are cut at the MCP limit)
-- Scenario: You need the end of a long ticket comment that a tool result cut off.
-- Args:
--   ticket (string, required): ticket id
--   kind (string, done): comment kind
--   chars (number, 3500): how many trailing chars to print
-- Hook: none (run by hand)
-- Gotchas: Output is the script log.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
if input.phase == 1 then
  ih.call("infantry_ticket_comments", {ticketId = a.ticket, which = "latest", kind = a.kind or "done", maxChars = 200000})
  ih.again()
  return
end
local t = tostring(input.results[1].text)
local n = tonumber(a.chars) or 3500
local tail = t:sub(-n)
for line in tail:gmatch("[^\n]+") do ih.log(line) end
