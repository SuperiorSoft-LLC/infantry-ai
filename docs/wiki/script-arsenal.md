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

## Global hooks

Hooks stay per project (`hooks.json`); a project hook may name a global script. A hook stored at global scope, running for every project's events, is a follow-up.
