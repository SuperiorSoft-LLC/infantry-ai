# Script Arsenal (global scripts)

The Script Arsenal is the app-wide library of Lua scripts: stored once, usable from every project, refined per project by copying. It belongs to the Scripting plugin (`plugins/official/Infantry_Scripting`); core knows nothing about it. See [Scripting](Scripting.md) for the language, hooks and tools.

## Scopes and resolution order

| Scope | Stored in | Who sees it |
|---|---|---|
| project | `<project>/.infantry/scripts/<name>.lua` | that project |
| global (the Script Arsenal) | the Scripting plugin's state dir, `global-scripts/<name>.lua` (+ a `.meta.json` sidecar) | every project |

- `infantry_script_run name=X` in any project finds the project's X first, then the global X.
- A hook (`infantry_script_hook`) can name a global script the same way.
- A global script is only source. It runs AS the calling project and agent, with that project's context, fence and permissions. A global script run from project A cannot touch project B.
- `infantry_script_list` shows both scopes (`scope=project|global`) and marks a global script `SHADOWED by the project copy` and the project copy `shadows the global`.

## Refine by copy

To change a global script for one project, save a project script of the same name (`infantry_script_save`, scope `project`, the default). It wins; the global stays untouched. Delete the project copy and the global is used again.

`infantry_script_save` and `infantry_script_delete` take `scope = project` (default) or `global`. Who may write the global scope is the setting `scripting.global.writeRoles` (default `pm,owner`; empty = any role; the owner always may). Deleting a global script is refused while a hook in any project names it and has no project copy.

## The bundled set

The plugin jar carries a starter set as resources (`daemon/src/main/resources/global-scripts/`, listed in `index.json`). At plugin start each one missing from the Arsenal is copied in. An app update refreshes a bundled script only while nobody edited it (the hash last seeded is kept in a `bundled.json` beside the scripts); an edited global and every project copy are never overwritten.

Each script's first lines document its purpose, args (name, type, default, meaning), a suggested hook line and the gotchas. Current set: `hook-health`, `worker-silent-ping`, `mem-check`, `done-forward`, `goal-handoff`, `goal-autostart-off`, `find-ticket`, `comment-tail`, `usage-governor`, `needs-owner-alert`, `wake-on-log`, `worker-issue-wake`, `lane-park`, `lane-pending-note`, `lane-pin-check`, `lane-one-worker`.

Gotchas that apply to all of them: never name a script arg `to`, `from`, `ticketId` or `status` unless it is meant to be the event payload's own value (for a hook the payload wins); actions a run queues are readable only in the next phase.

## Tests

`BundledGlobalScriptsTest` (in `Infantry_Lua`) parses every bundled script and runs it through all phases against a fake tool registry; a script without a scenario fails the build. `GlobalScriptsTest` (in `Infantry_Scripting`) covers resolution, the write setting, the project fence and the seeding rules.

## Index of the bundled scripts

Generated from the script headers; `ArsenalKnowledgeTest` (in `Infantry_Scripting`) fails when it is stale (run it with `ARSENAL_INDEX_WRITE=1` to rewrite the table).

<!-- arsenal-index:start -->
| Script | Purpose | Hook | Scenario |
|---|---|---|---|
| `comment-tail` | read-only: print the last N chars of a ticket's latest comment of a kind (tool results are cut at the MCP limit) | none (run by hand) | You need the end of a long ticket comment that a tool result cut off. |
| `done-forward` | when a watched ticket enters done, mails its latest done comment to another project's PM, push-notifies the owner, optionally starts the next ticket; fires once | on="lane enter done"  args {"ticket":"<8 chars>","recipient":"pm@<project>","hookId":"<id>"} | One project finishing a ticket should hand its report to another project's PM and notify the owner. |
| `find-ticket` | read-only: print the board lines (backlog, todo, in_progress by default) that contain a text; board output is capped over MCP | none (run by hand: infantry_script_run name=find-ticket args={"q":"login"}) | You know a word from a ticket title and need its id without dumping the whole board. |
| `goal-autostart-off` | when every goal ticket is done, re-adds the done-enter hook with auto-start off and tells the PM | on="lane enter done"  args {"goals":["<8 chars>"],"pm":"<session id>","offArgsJson":"{...}"} | A batch of goal tickets is running with auto-start on; stop auto-starting once the last one is done. |
| `goal-handoff` | when every goal ticket is done, hands the machine to another project: mails its PM and tells our PM to start nothing until handed back; fires once | on="lane enter done"  args {"goals":["<8 chars>"],"recipient":"pm@<project>","pm":"<session id>","hookId":"<id>"} | The machine is lent to another project until a set of goal tickets finishes, then handed on. |
| `hook-health` | watches every script's run history; alerts when a script fails N runs in a row (the watcher of the watchers) | on="every 120s"  args {"pm":"<session id>","failRun":3,"repeatEvery":30} | Alert when a script keeps failing, so a silently broken hook is noticed (watcher of the watchers). |
| `lane-one-worker` | keeps at most N ticket workers in progress, bounces the extra one back to todo, and prompts a freshly started idle worker | on="lane enter in_progress"  args {"maxInProgress":1,"exemptRoles":["version-control"],"kickPrompt":"Start ticket {ticket}."} | Allow only N ticket workers at a time and bounce the extra one back to todo. |
| `lane-park` | a ticket leaving in_progress for anything but the done lane, or entering backlog, gives up its worker | on="lane enter backlog" and on="lane exit in_progress"  args {"doneLane":"done"} | A ticket leaving in progress without finishing must release its worker. |
| `lane-pending-note` | says on the ticket that it waits on prerequisites; when it leaves pending for the start lane, starts it | on="lane enter pending" and on="lane exit pending"  args {"startLane":"todo"} | Tickets that wait on prerequisites should say so and start when released. |
| `lane-pin-check` | makes sure a ticket's provider/model pin is allowed before it runs; repins (or sends it back to todo) when not | on="lane enter todo" and on="lane enter in_progress"  args {"provider":"...","allowedProviders":["..."],"defaultModel":"..."} | Make sure every ticket runs on an allowed provider and model before a worker starts. |
| `mem-check` | checks free memory and reaps leftover processes the daemon attributes to ended or idle sessions; notes the PM when memory is low | on="every 600s"  or  on="lane enter done" | The machine runs low on memory because ended sessions leave processes behind. |
| `needs-owner-alert` | raises a system notification when a ticket comment asks for the owner ("needs owner", "[Q: owner", "owner decision needed") | on="ticket.commented"  args {} | A ticket comment asks for the owner and must raise a notification at once. |
| `usage-governor` | reads the weekly usage pace and steps worker capacity: normal -> one worker -> cheaper model only; restores when the pace drops | on="every 900s"  args {"project":"<project id>","pm":"<session id>","baseWorkers":2} | Slow down workers when the weekly usage pace runs ahead of the plan, and restore when it drops. |
| `wake-on-log` | waits (in a process) for a log file to print a pattern, then wakes a session; for a dev UI or daemon that never ends | none (run in the background by hand) | Wake an agent when a long-running dev server or daemon prints a ready line. |
| `worker-issue-wake` | wakes the PM at once when a ticket worker stops with an open ISSUE comment, instead of waiting for the idle kick | on="ticket.commented"  args {"wake":"<pm session id>"} | Wake the PM at once when a worker stops with an open issue comment. |
| `worker-silent-ping` | asks a ticket worker for status when it is busy (or waiting on a background job) but has written nothing for N minutes | on="every 60s"  args {"pm":"<session id>","silentMinutes":15,"tickSec":60} | Ask a worker for status when it has been busy but silent for too long. |
<!-- arsenal-index:end -->

## Knowledge base

Every global script has one generated note in the app's knowledge base (the store behind `infantry_memory`), named `script-arsenal-<name>`, in the global scope that every project's `infantry_memory search` and `show` can reach. A project script saved with a header gets a note in its own project. The note carries the purpose, scenario (when to use it), args, suggested hook, gotchas, an example run and the project copies that shadow it, and the words "Script Arsenal". Entries are never hand-written: they are regenerated when a global script is saved, seeded, refreshed or deleted, when a project copy is saved or deleted, and at daemon start. The Scripting plugin writes them through the daemon API's `KnowledgeWriter` seam (published by Infantry_Notes). Example: `infantry memory search "alert when a script keeps failing"` finds `script-arsenal-hook-health`.

`infantry_script_list q=<text>` searches script names, purposes and scenarios across both scopes.

A script header is the comment block at the top: line 1 `-- <name>: <purpose>`, then `Scenario:`, `Args:`, `Hook:`, `Gotchas:` and optionally `Example:`.

## Global hooks

`infantry_script_hook action=add scope=global on=... script=<global script>` stores a hook in the plugin's `global-hooks.json`. It runs for every project's events (timers fire per project), as that project, with the project's context. A project hook with the same id shadows it (the project's way to opt out). Writing or removing one uses the same setting `scripting.global.writeRoles` as global scripts. `infantry_script_hook action=list` shows them marked `[arsenal: every project]`. Deleting a global script is refused while a global hook names it. Example: one `hook-health` global hook on `every 120s` watches every project.
