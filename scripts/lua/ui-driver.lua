-- UI-DRIVER-1: drive a TEST instance of the desktop app (offscreen, no OS input) from a script (docs/wiki/UI-driver.md).
-- args: {action="up"|"down"|"list"|"health"|"tree"|"query"|"click"|"type"|"key"|"scroll"|"wait"|"shot"|"quit",
--        instance=<port>, json=<JSON object string: the selector and options>, port=<port for up>, size="1400x860",
--        repo=<dir holding scripts/ui-driver.sh>}
-- e.g. {action="click", instance=18950, json='{"tag":"run-start"}'}
--      {action="key", instance=18950, json='{"key":"Escape"}'}
-- Runs through powershell (the exec allow-list has no "bash") and calls Git Bash by full path (plain "bash" is WSL there),
-- as scripts/lua/test-daemon.lua does.
local a = input.args or {}
local act = a.action or "list"
local repo = a.repo or "C:/Users/antho/agenthq-standalone"
local function q(s) return "'" .. tostring(s):gsub("'", "''") .. "'" end
local s = "& 'C:/Program Files/Git/bin/bash.exe' " .. q(repo .. "/scripts/ui-driver.sh") .. " " .. act
if a.instance then s = s .. " --instance " .. tostring(tonumber(a.instance) or 0) end
if act == "up" then
  if a.port then s = s .. " --port " .. tostring(tonumber(a.port) or 0) end
  if a.size then s = s .. " --size " .. q(a.size) end
end
if a.json then s = s .. " --json " .. q(a.json) end
if (input.phase or 1) == 1 then
  ih.exec{command = {"powershell", "-NoProfile", "-Command", s}, timeoutSec = (act == "up") and 900 or 660}
  ih.again()
  return
end
local r = (input.results or {})[1] or {}
ih.log((r.ok and "OK " or "FAILED ") .. tostring(r.text or ""):sub(1, 4000))
