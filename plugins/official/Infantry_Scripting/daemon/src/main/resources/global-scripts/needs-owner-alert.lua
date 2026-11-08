-- needs-owner-alert: raises a system notification when a ticket comment asks for the owner ("needs owner", "[Q: owner", "owner decision needed")
-- Scenario: A ticket comment asks for the owner and must raise a notification at once.
-- Args:
--   pm (string, optional): unused here, kept for hook symmetry
--   event payload: ticketId and optionally body/text/comment of the comment
-- Hook: on="ticket.commented"  args {}
-- Gotchas: Reads the latest comment when the event carries no body. Needs infantry_notify.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local function pat(s)
  s = tostring(s or ""):lower()
  return s:find("needs owner", 1, true) or s:find("[q: owner", 1, true) or s:find("needs the owner", 1, true)
    or s:find("owner decision needed", 1, true) or s:find("needs owner action", 1, true)
end
local tid = a.ticketId or a.ticket or a.id
if input.phase == 1 then
  local body = a.body or a.text or a.comment
  if body then
    if pat(body) and not tostring(body):find("OWNER DECISION", 1, true) then
      local first = tostring(body):match("[^\r\n]+") or ""
      ih.call("infantry_notify", {title = "Infantry needs you", body = (tid and (tostring(tid):gsub("^ticket%-", "") .. ": ") or "") .. first:sub(1, 160),
        level = "milestone", sound = "bell", ticketId = tid})
    end
    return
  end
  if not tid then return end
  ih.call("infantry_ticket_comments", {ticketId = tid, which = "latest", maxChars = 2000})
  ih.again()
  return
end
local t = tostring(input.results and input.results[1] and input.results[1].text or "")
if pat(t) and not t:find("OWNER DECISION", 1, true) then
  local first = t:gsub("^%x+ %S+ by [^:]+: ", ""):match("[^\r\n]+") or ""
  ih.call("infantry_notify", {title = "Infantry needs you", body = tostring(tid):gsub("^ticket%-", "") .. ": " .. first:sub(1, 160),
    level = "milestone", sound = "bell", ticketId = tid})
end
