-- worker-waiting-nudge: one plain nudge to a ticket worker that went idle with no report and no "waiting" flag; one "still waiting?" nudge when its flag ran out
-- Scenario: Catch a worker that quit its turn without reporting or flagging itself waiting, and ask the worker for an update.
-- Args:
--   enabled (bool, true): KILL SWITCH. false = the script does nothing at all
--   idleSeconds (number, 60): idle time (turn ended) before the nudge
--   tickSec (number, 20): the hook period; each idle period is nudged in the one tick that crosses idleSeconds
--   exemptRoles (list, [pm,assistant,version-control]): roles never nudged
--   onlySession (string, optional): only sessions whose id starts with this
-- Hook: on="every 20s"  args {"idleSeconds":60,"tickSec":20}
-- Gotchas: Never nudges a session with an active waiting flag (infantry waiting on), a session running a background job, a session
--   whose ticket is not In Progress, or a session twice for the same idle period (the nudge lands once, in the tick that crosses the
--   threshold). A flag past its expiry gets one "still waiting?" nudge, in a window three ticks wide: the first nudge starts a turn
--   and that turn start clears the flag, so it cannot repeat, and a drifting tick cadence (a run takes ~1 s) cannot make it fall
--   between two ticks. The nudge is queue_add: a held message the worker reads at its next turn (sent at once when idle).
--   Edit the text in this file, not the hook.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
if a.enabled == false or tostring(a.enabled) == "false" then return end
local idleMs = (tonumber(a.idleSeconds) or 60) * 1000
local tickMs = (tonumber(a.tickSec) or 20) * 1000
local exempt = {}
for _, r in ipairs(a.exemptRoles or {"pm", "assistant", "version-control"}) do exempt[tostring(r):lower()] = true end

local NUDGE = "Update the ticket, report done or blocked with the report script, or set waiting with `infantry waiting on --reason \"...\"` if you are waiting on a process."
local EXPIRED = "Your waiting flag ran out. Still waiting? Update the ticket or report, or set it again with `infantry waiting on --reason \"...\"`; turn it off with `infantry waiting off` when you are working again."

-- the candidates of this tick: [{id, ticket, text}]
local function candidates(list, now)
  local out = {}
  for _, s in ipairs(list) do
    local role = tostring(s.role or ""):lower()
    local st = tostring(s.status or ""):upper()
    local onlyOk = not a.onlySession or tostring(s.id):sub(1, #a.onlySession) == a.onlySession
    local idleStatus = st:find("IDLE", 1, true) ~= nil
    local bg = tostring(s.backgroundWork or "") ~= ""
    local last = tonumber(s.lastTurnAt)
    if s.agentId and s.ticketId and onlyOk and not exempt[role] and idleStatus and not bg and last then
      local idle = now - last
      local w = s.waiting
      if w and w.active then
        -- flagged and in force: leave it alone
      elseif w then
        -- flag ran out: one "still waiting?" nudge. The window is three ticks wide so neither a drifting cadence (each run
        -- takes about a second) nor a busy daemon can make the expiry fall between two ticks; a repeat is impossible because
        -- the first nudge starts a turn and the next turn start clears the flag.
        local since = now - (tonumber(w.expiresAt) or now)
        if since >= 0 and since < tickMs * 3 then table.insert(out, {id = s.id, ticket = s.ticketId, text = EXPIRED}) end
      elseif idle >= idleMs and idle < idleMs + tickMs then
        table.insert(out, {id = s.id, ticket = s.ticketId, text = NUDGE})
      end
    end
  end
  return out
end

if input.phase == 1 then
  ih.infantry_list_sessions{}
  ih.infantry_board{columns = "in_progress"}
  ih.again()
  return
end

local rs = input.results or {}
local list = (rs[1] and rs[1].json and rs[1].json.sessions) or {}
local board = (rs[2] and rs[2].json and rs[2].json.board) or {}
-- the In Progress column of the board names each worker by the first 8 characters of its session id
local inProgress = ""
for _, line in ipairs(board) do inProgress = inProgress .. tostring(line) .. "\n" end
local c = candidates(list, os.time() * 1000)
for _, x in ipairs(c) do
  if inProgress:find(tostring(x.id):sub(1, 8), 1, true) then
    ih.call("infantry_session_act", {sessionId = x.id, action = "queue_add", text = x.text})
    ih.log("nudged " .. tostring(x.id):sub(1, 8) .. " on " .. tostring(x.ticket):sub(1, 14))
  end
end
