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
