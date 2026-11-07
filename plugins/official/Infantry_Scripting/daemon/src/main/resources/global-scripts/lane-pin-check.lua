-- lane-pin-check: makes sure a ticket's provider/model pin is allowed before it runs; repins (or sends it back to todo) when not
-- Args:
--   provider (string, required): pin to set
--   allowedProviders (list, required)
--   defaultModel (string, required)
--   modelByWeight (map "1".."5" -> model, optional)
--   defaultRole (string, optional): role set on a ticket that has none
--   onBad (string, repin): repin | back_to_todo
--   event payload: ticketId, to, workerSession
-- Hook: on="lane enter todo" and on="lane enter in_progress"  args {"provider":"...","allowedProviders":["..."],"defaultModel":"..."}
-- Gotchas: A field missing from the ticket answer counts as unknown, never as bad: no worker is killed on it.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args
local ticket = a.ticketId or error("lane-pin-check: no ticketId")
if not a.provider or not a.allowedProviders or not a.defaultModel then error("lane-pin-check needs provider, allowedProviders, defaultModel") end
local function res(i) return input.results and input.results[i] end

if input.phase == 1 then
  -- detail "full": since build 5ec8ec63 the "summary" shape has no provider field, so every start read nil and was killed (2026-11-02).
  ih.call("infantry_ticket_get", {ticketId = ticket, detail = "full"})
  ih.again()
elseif input.phase == 2 then
  local t = (res(1) and res(1).json) or {}
  -- defaultRole (role id, optional): a ticket with no role cannot start, and the failed start aborted on-ticket-done before
  -- it closed the finished worker (2026-11-02, d0b9bdcf). Give it the default role on entry; the PM can change it.
  if a.defaultRole and (t.role == nil or t.role == "") and t.ticketId then
    ih.call("infantry_ticket_update", {ticketId = ticket, role = a.defaultRole})
    ih.infantry_ticket_comment{ticketId = ticket, body = "lane-pin-check: ticket had no role; set to " .. a.defaultRole .. "."}
  end
  -- a field missing from the answer is "unknown", not "bad": never kill a worker on a missing field
  if t.provider == nil and t.effectiveProvider == nil then ih.log("pin check: ticket answer has no provider field; skipped"); return end
  t.provider = t.provider or t.effectiveProvider
  -- a model chosen by a project rule (e.g. light tickets -> haiku) has no stored pin but is valid: use effectiveModel
  t.model = (t.model ~= nil and t.model ~= "") and t.model or t.effectiveModel
  local ok = false
  for _, p in ipairs(a.allowedProviders) do if t.provider == p then ok = true end end
  if ok and t.model and t.model ~= "" then ih.log("pin ok " .. t.provider .. "/" .. t.model); return end
  local model = (a.modelByWeight and t.weight and a.modelByWeight[tostring(t.weight)]) or a.defaultModel
  local was = tostring(t.provider) .. "/" .. tostring(t.model)
  if a.onBad == "back_to_todo" and a.to == "in_progress" then
    if a.workerSession then ih.infantry_session_remove{sessionId = a.workerSession, force = true} end
    ih.call("infantry_ticket_update", {ticketId = ticket, provider = a.provider, model = model})
    ih.call("infantry_ticket_move", {ticketId = ticket, status = "todo", top = true})
    ih.infantry_ticket_comment{ticketId = ticket, body = "lane-pin-check: started on disallowed pin " .. was .. "; worker closed, repinned to " .. a.provider .. "/" .. model .. ", back to todo top."}
  else
    ih.call("infantry_ticket_update", {ticketId = ticket, provider = a.provider, model = model})
    ih.infantry_ticket_comment{ticketId = ticket, body = "lane-pin-check: pin " .. was .. " not allowed; repinned to " .. a.provider .. "/" .. model .. "."}
  end
end
