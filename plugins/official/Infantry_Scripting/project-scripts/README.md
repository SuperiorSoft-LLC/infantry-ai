# Project scripts

## on-ticket-done.lua
When a ticket enters `done`: asks the version-control role to merge the branch tip, closes the finished worker's session, starts the next startable `todo` ticket, and comments what it did on the finished ticket. Nothing repo-specific is in the Lua; it is all hook args.

Install on a project:

    mkdir -p <project>/.agenthq/scripts && cp on-ticket-done.lua <project>/.agenthq/scripts/
    agenthq script hook add "lane enter done" on-ticket-done --payload '{"devBranch":"<dev branch>","vcRole":"<vc role id>","skipMarkers":["[HOLD]","[PARKED]"]}'

Try it first: `agenthq_script_run` name `on-ticket-done`, dryRun true, args `{"ticketId":"<id>","branch":"<b>","tipSha":"<sha>","devBranch":"..","vcRole":".."}`.


Hooks fire on a lane change: `"lane enter <lane>"` (ticket.moved `to`) or `"lane exit <lane>"` (ticket.moved `from`), any lane name (todo, in_progress, pending, done, custom), case-insensitive. Script args carry `from` and `to` for both, plus `lane` (the lane entered / left), ticketId, title, branch, tipSha, workerSession.
    agenthq script hook add "lane exit in_progress" <script>

## HOOKS.md (mail-incoming, MAIL-HOOK-1)
- `agenthq_script_hook add on="mail.incoming" script="on-mail-sent" args={"routeRoles":["pm"],"rules":[...]}` — runs BEFORE the recipient is woken (synchronously, bounded by limits.plugins.callTimeoutMs). A script that queues `ih.handled()` marks the mail read and wakes nobody; `ih.deliver_text{text="..."}` delivers that text instead of the notify line for that one mail; any other result, a script failure or a timeout delivers as usual (a mail is never dropped).
- Rules are hook args (data): vc-merged, vc-duplicate, stale-auto-failure, worker-done (see the PM's draft rules).
- Copy on-mail-sent.lua to <project>/.agenthq/scripts/ and replace the old `mail.sent` hook (a handled mail no longer needs agenthq_read or agenthq_prompt).

## Process bridge (SCRIPT-CMD-1)
A script is sandboxed and runs nothing itself; it queues `ih.exec{...}` (same as `ih.agenthq_exec{...}` or the CLI/MCP tool `agenthq_exec`) and the daemon runs the process as the script's caller. Result, in phase 2 `input.results[i].json`: `{exitCode, durationMs, stdoutTail, stderrTail, timedOut}`. A non-zero exit is a result, not a tool failure, so the script can branch.

Args: `command` (argv list: runs directly; or one string: split on spaces/quotes, no shell, no expansion), `cwd` (default the project root; a relative path is under it, an absolute one such as a ticket worktree is allowed), `env`, `stdin`, `timeoutSec` (default `scripting.exec.defaultTimeoutSec`, capped by `maxTimeoutSec`; a timeout kills the whole process tree), `script` (label echoed in `exec.done`), `background`.
`background=true` answers `{id, state:"running"}` at once; read it with `agenthq_exec_status {id}` or let a hook on the named event `exec.done` (`{id, exitCode, script, session, timedOut}`) continue the protocol.

Guard (plugin settings, data): `scripting.exec.enabled` (default **false**: the tool refuses and says how to enable it), `scripting.exec.allow` (JSON array or one per line; an entry is a command-line prefix, or `re:<regex>` that must match the whole line; empty = all), `scripting.exec.roles` (comma list; empty = any), `defaultTimeoutSec`, `maxTimeoutSec`, `tailChars`. Each run is logged once: session, role, script, command, exit code, duration.

Worked example (run a command, branch on the exit code, prompt the caller's session on failure; args `{"cmd":["prog","arg"]}`):

    if not input.results then
      ih.exec{command=input.args.cmd, timeoutSec=300, script="check"}
      ih.again()
    else
      local r = input.results[1].json
      if r.exitCode ~= 0 then
        ih.prompt{session=input.caller.id, text="command failed ("..r.exitCode.."): "..r.stderrTail}
      end
    end
