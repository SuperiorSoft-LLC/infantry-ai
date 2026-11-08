-- find-ticket: read-only: print the board lines (backlog, todo, in_progress by default) that contain a text; board output is capped over MCP
-- Scenario: You know a word from a ticket title and need its id without dumping the whole board.
-- Args:
--   q (string, required): text to find, case-insensitive
--   columns (string, backlog,todo,in_progress): board columns to search
-- Hook: none (run by hand: infantry_script_run name=find-ticket args={"q":"login"})
-- Gotchas: Output is the script log.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
if input.phase == 1 then
  ih.call("infantry_board", {columns = a.columns or "backlog,todo,in_progress", limit = 500, maxChars = 400000})
  ih.again()
  return
end
local r = input.results and input.results[1]
for _, line in ipairs((r and r.json and r.json.board) or {}) do
  if tostring(line):upper():find(tostring(a.q or ""):upper(), 1, true) then ih.log(tostring(line):sub(1, 160)) end
end
