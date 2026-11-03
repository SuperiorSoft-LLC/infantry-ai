-- on-mail-sent: the incoming-mail handler (MAIL-HOOK-1). It runs BEFORE the recipient is woken, on the pre-delivery event `mail.incoming`.
-- Hook:  infantry_script_hook add on="mail.incoming" script="on-mail-sent" args=<see HOOKS.md>
-- Event payload (input.args): id, from, to, role (recipient role), sessionId (resolved recipient), projectId, subject, body, threadId.
-- Result: ih.handled() = the mail is marked read and nobody is woken. Anything else (no ih.handled, an error, a timeout) = delivered as usual.
-- Args (data, never literals here):
--   routeRoles  recipient roles whose mail is screened (e.g. ["pm"]); mail to any other role is delivered untouched.
--   rules       list of {name, from?, fromRole?, find (Lua pattern), unless? [plain strings],
--               onlyIfTicketNotIn? lane, comment? template}. First matching rule handles the mail:
--               optionally commented on its ticket, then handled. Templates: {id} {from} {body} {1} (first capture of find).
-- Phase 1 reads the board and sessions (only when a rule needs them); phase 2 matches the rules.
local a = input.args
local routed = false
for _, x in ipairs(a.routeRoles or {}) do if x == a.role then routed = true end end
if not routed or not a.body or a.body == "" then return end

local function last(tool)
  local rs = input.results or {}
  for i = #rs, 1, -1 do if rs[i].tool == tool then return rs[i] end end
end
local function fill(tpl, vals) return (tpl:gsub("{(%w+)}", function(k) return vals[k] or "" end)) end

if input.phase == 1 then
  ih.infantry_list_sessions{limit = 200}
  ih.infantry_board{columns = "in_progress,done", limit = 20}
  ih.again()
  return
end

local body = a.body
local fromRole = a.from
local sl = last("infantry_list_sessions")
for _, s in ipairs(sl and sl.json and sl.json.sessions or {}) do if (s.id or s.sessionId) == a.from then fromRole = s.role end end
-- ticket: named in the body, else the board line of the sender's worker session
local ticket = body:match("ticket%-(%x%x%x%x%x%x%x%x)") or body:match("ticket (%x%x%x%x%x%x%x%x)")
local board = last("infantry_board")
local lane, laneOf = nil, {}
for _, line in ipairs(board and board.json and board.json.board or {}) do
  local col = line:match("^(%a[%w_]*) %(")
  if col then lane = col else
    local id = line:match("^  (%x+) |")
    if id then
      laneOf[id] = lane
      if not ticket and a.from and #a.from >= 8 and line:find(a.from:sub(1, 8), 1, true) then ticket = id end
    end
  end
end
for _, r in ipairs(a.rules or {}) do
  local ok = (not r.from or r.from == a.from) and (not r.fromRole or r.fromRole == fromRole)
  local cap
  if ok then cap = body:match(r.find); ok = cap ~= nil end
  if ok then for _, u in ipairs(r.unless or {}) do if body:find(u, 1, true) then ok = false end end end
  if ok and r.onlyIfTicketNotIn then ok = ticket ~= nil and laneOf[ticket] ~= r.onlyIfTicketNotIn end
  if ok then
    if r.comment and ticket then
      ih.infantry_ticket_comment{ticketId = ticket, body = fill(r.comment, {id = a.id, from = a.from, body = body, ["1"] = (type(cap) == "string") and cap or ""})}
    end
    ih.log("mail " .. (a.id or "?") .. " handled by rule " .. (r.name or "?") .. (ticket and (" ticket " .. ticket) or ""))
    ih.handled()
    return
  end
end
