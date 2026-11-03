# Recipe: add a Lua hook

Goal: when a ticket enters a lane, a script runs and does something with the tools an agent has (here: comment on
the ticket). No Kotlin and no rebuild: a script and a hook are data in the daemon and the project.

Scripting is a plugin, not core: `plugins/official/Infantry_Scripting` owns the hook dispatcher
(`plugins/official/Infantry_Scripting/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/ScriptHooks.kt`)
and `plugins/official/Infantry_Lua` provides the language
(`plugins/official/Infantry_Lua/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/lua/daemon/LuaScriptEngine.kt`).
The full API is in [Scripting](../Scripting.md).

## Files to touch

```
my-scripts/on-ticket-done.lua                      the script (any folder; you save it into the daemon)
<project>/.infantry/scripts/hooks.json               written for you by "script hook add"
```

You do not edit repository code. The hook store (`HookStore.kt`, same folder as `ScriptHooks.kt`) owns `hooks.json`.

## Steps

1. Write the script. It does not act itself: it **queues actions**; after it ends the daemon runs them in order as
   the caller, and the first failure stops the run. `input.args` holds the hook's fixed args merged under the event
   payload (for `lane enter` the payload has `ticketId`, `title`, `branch`, `tipSha`, `from`, `to`, `lane`):

```lua
-- my-scripts/on-ticket-done.lua
if not input.results then
  ih.comment{ticket = input.args.ticketId, text = "entered " .. input.args.lane .. ": " .. input.args.title}
  ih.again()
else
  ih.log("comment ok=" .. tostring(input.results[1].ok))
end
```

   Phase 1 queues the action and calls `ih.again()`; phase 2 runs again with `input.results`, one entry per executed
   action (`{name, tool, ok, text, json}`). Globals are base, string, table, math, `os.time`, `os.clock`, `ih` and
   `input`; there is no `io`, `require`, `load` or `debug`.
2. Save it (parse-checked, runs at once):

```
infantry script save on-ticket-done --file my-scripts/on-ticket-done.lua
```

3. Attach it to an event:

```
infantry script hook add "lane enter done" on-ticket-done
infantry script hook list
```

   `on` forms: `lane enter <lane>`, `lane exit <lane>`, a named event (`mail.incoming`, `exec.done`, `exec.tick`,
   `ticket.*`, or your own sent with `infantry_event_emit`), and `every <N>s`. Fixed args go in with
   `--payload '{"key":"value"}'`. Remove a hook with `infantry script hook remove <id>`.
4. Test without a ticket: run the script with `dryRun` through the MCP tool `infantry_script_run`
   (`{name, args, dryRun}`), or move a scratch ticket into the lane.

## Notes

- A hook that moves a ticket into the lane it fires on is guarded against loops (see "Loop guards" in
  [Scripting](../Scripting.md)).
- `mail.incoming` runs before a mail is delivered, synchronously, and attaches to one session; use it to screen
  routine mail by rules.
- Scripts are not role-restricted: any agent or role can author and run them.

## Check

`infantry script list` shows the script, `infantry script hook list` shows the hook, and `infantry_script_stats` (MCP)
shows the latest runs. The engine's own tests: `plugins/official/Infantry_Scripting/daemon/src/test/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/ScriptHooksTest.kt`.
