-- mem-check: checks free memory and reaps leftover processes the daemon attributes to ended or idle sessions; notes the PM when memory is low
-- Scenario: The machine runs low on memory because ended sessions leave processes behind.
-- Args:
--   floorMb (number, 8000): free MB below which the PM is noted
--   reap (bool, true): reap rogue processes
--   wake (string, optional): session id noted when memory is low
-- Hook: on="every 600s"  or  on="lane enter done"
-- Gotchas: Never touches unattributed processes. Needs infantry_processes and infantry_processes_reap.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local rs = input.results or {}

if input.phase == 1 or not input.phase then
  ih.infantry_processes{all = "true"}
  ih.again()
  return
end

if input.phase == 2 then
  local txt = tostring((rs[1] and rs[1].text) or "")
  local free = tonumber(txt:match("memory: (%d+) MB free")) or -1
  local rogue = tonumber(txt:match("rogue %((%d+)%)")) or 0
  if rogue > 0 and a.reap ~= false then
    -- 2026-11-07: the tool now needs exactly one of pid or all=true (failed 3 ticks in a row, caught by hook-health).
    -- ih.call: a refusal must not end the run (the low-memory note below still has to go out).
    ih.call("infantry_processes_reap", {all = "true"})
    ih.log("reaped " .. rogue .. " rogue process(es)")
  end
  local floor = tonumber(a.floorMb) or 8000
  if free >= 0 and free < floor and a.wake then
    -- the three biggest lines of the "not ours" block: unattributed leftovers the reaper cannot touch
    local big = {}
    for mb, line in txt:gmatch("pid %d+ | (%d+) MB | ([^\n]+)") do
      big[#big + 1] = {tonumber(mb), line}
    end
    table.sort(big, function(x, y) return x[1] > y[1] end)
    local top = {}
    for i = 1, math.min(3, #big) do top[#top + 1] = big[i][1] .. " MB " .. big[i][2]:sub(1, 70) end
    ih.infantry_chat_note{sessionId = a.wake, level = "info", wake = false,
      text = "memory low: " .. free .. " MB free (floor " .. floor .. "). Biggest: " .. table.concat(top, " ; ")}
  end
  ih.log("free " .. free .. " MB, rogue " .. rogue)
end
