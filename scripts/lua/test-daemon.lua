-- TEST-DAEMON-1 / TESTDAEMON-RUST-1: clean Rust test daemon in one step (copy of the project script `test-daemon`).
-- args: {action="start"|"stop"|"status", worktree=<dir, optional>, dist=<daemon exe or its folder, optional>, plugins=<dir, optional>, name=<n>, pid=<pid for stop>, repo=<dir holding scripts/test-daemon.sh>}
-- Runs through powershell (the exec allow-list has no "bash") and calls Git Bash by full path (plain "bash" is WSL there).
local a = input.args or {}
local act = a.action or "status"
local repo = a.repo or a.worktree or "C:/Users/antho/agenthq-standalone"
local s = "& 'C:/Program Files/Git/bin/bash.exe' '" .. repo .. "/scripts/test-daemon.sh' " .. act
if act == "start" then
  if a.worktree then s = s .. " --worktree '" .. a.worktree .. "'" end
  if a.dist then s = s .. " --bin '" .. a.dist .. "'" end
  if a.plugins then s = s .. " --plugins '" .. a.plugins .. "'" end
end
if a.name then s = s .. " --name '" .. a.name .. "'" end
if a.pid then s = s .. " --pid " .. tostring(tonumber(a.pid) or 0) end
if (input.phase or 1) == 1 then
  ih.exec{command = {"powershell", "-NoProfile", "-Command", s}, timeoutSec = 900}
  ih.again()
  return
end
local r = (input.results or {})[1] or {}
ih.log((r.ok and "OK " or "FAILED ") .. tostring(r.text or ""):sub(1, 1500))
