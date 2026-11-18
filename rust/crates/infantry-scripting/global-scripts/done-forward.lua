-- done-forward: when a watched ticket enters done, mails its latest done comment to another project's PM, push-notifies the owner, optionally starts the next ticket; fires once
-- Scenario: One project finishing a ticket should hand its report to another project's PM and notify the owner.
-- Args:
--   ticket (string, required): the 8-char ticket id to watch
--   recipient (string, required): e.g. pm@<project id>; never name it `to`
--   subject (string): mail subject
--   intro (string): text above the report
--   pushTitle, pushBody (string): owner notification text
--   pm (string, optional): session id told of every outcome
--   hookId (string, optional): this hook's id, removed after it fires
--   startNext (string, optional): ticket id started after the hand-off
-- Hook: on="lane enter done"  args {"ticket":"<8 chars>","recipient":"pm@<project>","hookId":"<id>"}
-- Gotchas: Compares the payload's ticketId with `ticket`; the owner is notified on every outcome, failure included.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local rs = input.results or {}
local tid = tostring(a.ticketId or ""):gsub("^ticket%-", ""):sub(1, 8)
if not a.ticket or tid ~= a.ticket then return end

if input.phase == 1 or not input.phase then
  ih.call("infantry_ticket_comments", {ticketId = a.ticket, which = "latest", kind = "done", maxChars = 200000})
  ih.again()
  return
end

if input.phase == 2 then
  local report = tostring((rs[1] and rs[1].text) or "")
  if report == "" or report:match("^%s*$") then
    -- no done comment: do not forward an empty mail; the PM handles it (hook stays for the next done entry)
    if a.pm then ih.call("infantry_chat_note", {sessionId = a.pm, level = "fail", wake = true,
      text = "done-forward: " .. a.ticket .. " entered done with no done comment; nothing forwarded to " .. tostring(a.recipient)}) end
    ih.call("infantry_notify", {title = "Hand-off FAILED", body = "Ticket " .. a.ticket .. " finished without a report; nothing sent to the site team yet. PM woken to fix it.",
      level = "info", sound = "chime"})
    return
  end
  ih.call("infantry_message", {to = a.recipient, allowCrossProject = true,
    subject = a.subject or ("Report: ticket " .. a.ticket), body = (a.intro and (a.intro .. string.rep(string.char(10), 2)) or "") .. report})
  if a.startNext then ih.call("infantry_ticket_start", {ticketId = a.startNext}) end
  ih.again()
  return
end

-- phase 3: results of the mail (and the start); actions queued in a run cannot be read in that same run.
if input.phase == 3 then
  local sent, st = rs[1], rs[2]
  if sent and sent.ok then
    ih.call("infantry_notify", {title = a.pushTitle or "Passed on", body = a.pushBody or ("Ticket " .. a.ticket .. " report sent to " .. tostring(a.recipient)),
      level = "info", sound = "chime"})
    if a.pm then ih.call("infantry_chat_note", {sessionId = a.pm, level = "ok", wake = true,
      text = "done-forward: " .. a.ticket .. " report mailed to " .. tostring(a.recipient) .. " and owner push-notified."}) end
  else
    ih.call("infantry_notify", {title = "Hand-off FAILED", body = "Mail to " .. tostring(a.recipient) .. " failed: " .. tostring(sent and sent.text or "?"):sub(1, 160),
      level = "info", sound = "chime"})
    if a.pm then ih.call("infantry_chat_note", {sessionId = a.pm, level = "fail", wake = true, text = "done-forward: mail to " .. tostring(a.recipient) .. " failed; resend by hand."}) end
  end
  if a.startNext then
    if st and st.ok then
      ih.call("infantry_notify", {title = "Next ticket started", body = a.startNext .. ": " .. tostring(st.text):sub(1, 120), level = "info"})
    else
      ih.call("infantry_notify", {title = "Next ticket did NOT start", body = a.startNext .. ": " .. tostring(st and st.text or "?"):sub(1, 160), level = "info", sound = "chime"})
      if a.pm then ih.call("infantry_chat_note", {sessionId = a.pm, level = "fail", wake = true, text = "done-forward: start of " .. a.startNext .. " failed; start a ticket now."}) end
    end
  end
  if a.hookId then ih.call("infantry_script_hook", {action = "remove", id = a.hookId}) end
  ih.log("forwarded " .. a.ticket .. " to " .. tostring(a.recipient))
end
