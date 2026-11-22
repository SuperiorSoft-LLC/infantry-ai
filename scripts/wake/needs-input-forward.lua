-- EXAMPLE needs-input-forward (NEEDS-INPUT-1). NOT installed and NOT hooked by default.
-- Fires on the named event `session.needs_input` (a session is blocked on a question or an approval card).
-- Payload (input.args): sessionId, ticketId, projectId, promptId, kind ("question" | "tool_approval"), question, options (labels),
-- multiSelect, tool, input, route ("owner" | "pm" | "pm-then-owner").
--   1. A question whose options include one of args.auto (default {"Continue"}) is answered with that label at once, no model turn.
--   2. Anything else is forwarded to args.pm (a PM session id) as ONE mail of kind session.needs_input, key = promptId, so
--      the notify.wake.kinds policy decides whether it wakes. Leave args.pm out to only log.
-- Answering is the same tool a PM uses: `infantry prompt answer <promptId> --answer <label>`.
-- Hook: infantry script hook add "session.needs_input" needs-input-forward --payload '{"pm":"<pm session>","auto":["Continue"]}'
-- args.fixture = true: only log what would happen (end-to-end tests).
local a = input.args or {}
if a.kind ~= "question" or not a.promptId then
  return
end
local auto = a.auto or { "Continue" }
local pick
for _, opt in ipairs(a.options or {}) do
  for _, want in ipairs(auto) do
    if tostring(opt):lower() == tostring(want):lower() then pick = opt end
  end
end
if input.phase == 1 then
  if pick then
    ih.log("auto-answer " .. a.promptId .. " -> " .. pick)
    if not a.fixture then
      ih.agenthq_exec{command = {"infantry", "prompt", "answer", a.promptId, "--answer", pick}, timeoutSec = 30}
    end
    ih.again()
    return
  end
  if a.pm and not a.fixture then
    ih.infantry_message{to = a.pm, subject = "needs an answer: " .. tostring(a.sessionId):sub(1, 8),
      body = tostring(a.question or ""):sub(1, 600) .. "\nAnswer: infantry prompt answer " .. a.promptId .. " --answer \"<option>\"",
      kind = "session.needs_input", key = a.promptId}
  end
  ih.log("forwarded " .. a.promptId)
end
