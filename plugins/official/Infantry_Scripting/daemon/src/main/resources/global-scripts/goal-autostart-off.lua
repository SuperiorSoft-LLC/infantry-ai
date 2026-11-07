-- goal-autostart-off: when every goal ticket is done, re-adds the done-enter hook with auto-start off and tells the PM
-- Args:
--   goals (list of strings, required): 8-char ticket ids
--   pm (string, optional): PM session id
--   hookId (string, done-enter): hook to replace
--   hookScript (string, on-ticket-done): its script
--   offArgsJson (string): that hook's args as JSON text with autoStart=false
-- Hook: on="lane enter done"  args {"goals":["<8 chars>"],"pm":"<session id>","offArgsJson":"{...}"}
-- Gotchas: Prompts the PM session; the hook swap is skipped without offArgsJson.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local rs = input.results or {}
local goals = a.goals or {}
if #goals == 0 then return end
local tid = tostring(a.ticketId or ""):gsub("^ticket%-", ""):sub(1, 8)
local isGoal = false
for _, g in ipairs(goals) do if g == tid then isGoal = true end end
if not isGoal then return end

if input.phase == 1 then
  for _, g in ipairs(goals) do ih.infantry_ticket_get{ticketId = g} end
  ih.again()
  return
end

if input.phase == 2 then
  local open = {}
  for i, g in ipairs(goals) do
    local st = rs[i] and rs[i].json and rs[i].json.status or "?"
    if st ~= "done" then open[#open + 1] = g .. "=" .. st end
  end
  if #open > 0 then ih.log("goals not all done: " .. table.concat(open, ", ")); return end
  -- PM first: "the first failure stops the run", so the wake must not depend on the hook swap succeeding.
  if a.pm then
    local msg = "GOALS REACHED (" .. table.concat(goals, ", ") .. " done): auto-start switched off by script goal-autostart-off " ..
      "(owner: 'turn it off when you reach the goals'). Verify done-enter autoStart=false, set worker-watch stallStart=false, then infantry_ask the owner that the updater is ready to test."
    ih.infantry_chat_note{sessionId = a.pm, level = "ok", wake = true, text = msg}
    ih.call("infantry_prompt", {sessionId = a.pm, prompt = msg})
  end
  -- offArgsJson: the done-enter args as JSON text with autoStart=false (the hook tool takes args as a JSON string).
  if a.offArgsJson then
    local id = a.hookId or "done-enter"
    ih.call("infantry_script_hook", {action = "remove", id = id})
    ih.call("infantry_script_hook", {action = "add", id = id, on = "lane enter done", script = a.hookScript or "on-ticket-done", args = a.offArgsJson})
  end
  ih.log("goals reached; auto-start off requested")
end
