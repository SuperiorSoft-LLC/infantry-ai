-- goal-handoff: when every goal ticket is done, hands the machine to another project: mails its PM and tells our PM to start nothing until handed back; fires once
-- Args:
--   goals (list of strings, required): 8-char ticket ids
--   recipient (string): e.g. pm@<project id>; never name it `to`
--   body (string): mail text
--   pm (string, optional): our PM session id
--   hookId (string, optional): removed after it fires
-- Hook: on="lane enter done"  args {"goals":["<8 chars>"],"recipient":"pm@<project>","pm":"<session id>","hookId":"<id>"}
-- Gotchas: Does nothing for a ticket that is not a goal.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local rs = input.results or {}
local goals = a.goals or {}
if #goals == 0 then return end
local tid = tostring(a.ticketId or ""):gsub("^ticket%-", ""):sub(1, 8)
local isGoal = false
for _, g in ipairs(goals) do if g == tid then isGoal = true end end
if not isGoal then return end

if input.phase == 1 or not input.phase then
  for _, g in ipairs(goals) do ih.call("infantry_ticket_get", {ticketId = g}) end
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
  if a.recipient then
    ih.call("infantry_message", {to = a.recipient, allowCrossProject = true, subject = "Resources free: your turn",
      body = a.body or ("Our tickets " .. table.concat(goals, ", ") .. " are done. The machine is yours; tell us when you are finished.")})
  end
  if a.pm then
    local msg = "HANDOFF: goals " .. table.concat(goals, ", ") .. " done; mailed " .. tostring(a.recipient) ..
      " that resources are theirs (script goal-handoff). Start NO ticket until they hand back."
    ih.call("infantry_chat_note", {sessionId = a.pm, level = "ok", wake = true, text = msg})
  end
  if a.hookId then ih.call("infantry_script_hook", {action = "remove", id = a.hookId}) end
  ih.log("handoff sent to " .. tostring(a.recipient))
end
