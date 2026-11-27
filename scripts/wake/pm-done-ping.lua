-- pm-done-ping: when a ticket enters done, tell the PM in one line so it keeps the queue moving.
-- WAKE-3: sent with kind ticket.done.auto + key = ticket id, the same kind and key as the daemon's own done notice, so the two are one
-- event: notify.wake.kinds decides (default: attention inbox, no turn) and the same ticket never wakes twice.
-- Hook: "lane enter done". Args: pm (session id to notify).
local a = input.args
local pm = a.pm or error("pm-done-ping needs pm")
-- DONE-ONCE-1: lane hooks fire once per completion (a later done comment or move changes nothing); a close the lead
-- made itself (via = "close") needs no ping.
if a.via == "close" then return end
local t = a.ticketId or "?"
ih.infantry_message{to = pm, subject = "[DONE-PING] ticket " .. tostring(t) .. " entered done",
  body = "[DONE-PING] ticket " .. tostring(t) .. " entered done. Check its merge, make sure the next worker started, keep the todo queue full of Rust tickets.",
  kind = "ticket.done.auto", key = tostring(t)}
