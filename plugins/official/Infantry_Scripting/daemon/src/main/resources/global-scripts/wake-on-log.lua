-- wake-on-log: waits (in a process) for a log file to print a pattern, then wakes a session; for a dev UI or daemon that never ends
-- Args:
--   path (string, required): the log file
--   pattern (string, required): plain text to find
--   timeoutSec (number, 900): give up after
--   session (string, the caller): session to wake
--   label (string, log): name in the note
-- Hook: none (run in the background by hand)
-- Gotchas: Needs the process bridge (scripting.exec.enabled) and PowerShell (pwsh) on the daemon machine.
--   GOTCHAS (all scripts): never name an arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own: for a hook the event payload wins over args. Actions a run queues are readable only in the NEXT phase (ih.again()). Use ih.call(...) for an action whose refusal must not end the run.
local a = input.args or {}
local timeout = tonumber(a.timeoutSec) or 900
local label = a.label or "log"
if not input.results then
  if not a.path or not a.pattern then error("wake-on-log needs args.path and args.pattern") end
  local loop = "$d=(Get-Date).AddSeconds([int]$env:W_TIMEOUT); while((Get-Date) -lt $d){ " ..
    "if(Test-Path -LiteralPath $env:W_PATH){ $m = Select-String -LiteralPath $env:W_PATH -SimpleMatch $env:W_PATTERN -List; " ..
    "if($m){ 'found: ' + $m.Line.Substring(0,[Math]::Min(160,$m.Line.Length)); exit 0 } }; Start-Sleep -Seconds 5 }; 'timeout'; exit 2"
  ih.exec{command={"pwsh", "-NoProfile", "-Command", loop}, timeoutSec = timeout + 60,
          env = {W_PATH = a.path, W_PATTERN = a.pattern, W_TIMEOUT = tostring(timeout)}}
  ih.again()
else
  local r = input.results[1]
  local j = r and r.json or {}
  local found = j.exitCode == 0
  local tail = (j.stdoutTail or r.text or ""):gsub("%s+$", "")
  local text = found and (label .. " ready: '" .. a.pattern .. "' in " .. a.path .. " (" .. tail .. ")")
                      or (label .. " NOT ready after " .. timeout .. "s: '" .. a.pattern .. "' not in " .. a.path .. " (" .. tail .. ")")
  local note = {text = text, level = found and "info" or "warn", wake = true}
  local session = a.session or (input.caller and input.caller.id)
  if session then note.sessionId = session end
  ih.call("infantry_chat_note", note)
  ih.log(text)
end
