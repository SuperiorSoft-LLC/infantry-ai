[← Wiki index](index.md)

# Settings

Settings are durable key/value pairs kept by the daemon. Read and write them in the Settings tab, with the CLI, or
with the MCP tools `infantry_settings_get` / `infantry_settings_set`.

```
infantry settings                                  # every setting, key = value
infantry settings get <key> [--project p] [--role r] [--session s]
infantry settings set <key> <value> [--scope app|project|role|session] [--project p] [--role r] [--session s]
infantry settings explain <key> [--project p] [--role r] [--session s]
infantry settings limits                           # every cap/threshold: value, default, range, env name
```

## Scopes

A value can be stored at four scopes. The most specific one that holds a value wins:

**session > role (in a project) > project > app > built-in default**

| Scope | Target | Use |
|---|---|---|
| app | the whole installation | the normal case; flat keys |
| project | a project id | one project spends differently |
| role | a role inside a project | e.g. a cheap role keeps less context |
| session | one running session | a one-off experiment |

`settings explain <key>` prints the winner and every layer that holds a value. Not every key allows every scope: a
key that is not listed below is **app-only**, and setting it at another scope is refused with the allowed list. The
app scope cannot be cleared, only set.

## Cost-saving defaults and what each opt-in costs

The default is the cheapest reasonable value. A costlier value is an opt-in. These keys allow all four scopes.

| Key | Default | What it does | What the costlier value costs |
|---|---|---|---|
| `related.brief` | `true` | Puts the related-tickets block in a started ticket's brief | Adds to every worker's first turn |
| `brief.related.mode` | `files-command` | `files-command` (one line naming the command), `files-inline`, `tickets` | `files-inline` puts every related path in each brief, re-read every turn; `tickets` adds ids, titles, statuses and snippets: the most tokens |
| `related.files.format` | `paths` | What `ticket files <id> --related` prints: `paths` or `detailed` | `detailed` adds ids, status and counts per path: several times the output |
| `related.descriptionBlock` | `true` | Pastes the related-files block into a new ticket's description | Longer descriptions |
| `related.briefInstruction` | built-in line | The instruction line above the list | none |
| `related.suggestNumber` | `true` | Suggests the next number of a ticket family | none |
| `mail.wakeMode` | `real` | Which mail wakes a resting session: `real`, `never`, `all` | `all` wakes on every automation mail; each wake re-reads the whole context |
| `mail.deliveryMode` | `notify` | What a wake injects: `notify`, `truncate`, `full` | `full` injects every body, re-read on every later turn |
| `limits.session.contextCapTokens` | `100000` | Past this context size, a resting session is restarted with a short handoff when mail wakes it; `0` = off | A higher cap or `0` keeps longer context; each turn costs more |
| `session.keepAliveMinutes` | `30` | Minutes a resting harness process is kept alive; `0` = one-shot | A longer keep-alive holds more RAM |
| `context.includeLinkedTasksContext` | `true` | Linked-tickets section in the brief | Longer brief when on |
| `context.includeSkillsContext` | `true` | Skills section in the brief | Longer brief when on |
| `context.includeFinishInstructions` | `true` | Report-instruction lines in the brief | Turn off when a script or role note owns the finish step |

`mail.wakeMode` and `mail.deliveryMode` also take per-role and per-session variants as keys with a suffix
(`mail.wakeMode.<role>`).

## Other settings worth knowing

| Key | Default | Meaning |
|---|---|---|
| `tickets.weightExecutors` | (unset) | Provider and model per ticket weight 1 to 5 |
| `scripting.*` | see [Scripting](Scripting.md) | Run limits, hook guards, the process bridge (off by default) |
| `processes.autoReap` | off | Reap rogue background processes (Gradle/Kotlin daemons, node) left by ended sessions |
| `processes.reapKinds` | `[]` (every kind) | The kinds a session's close reaps: tool profile names (`gradle`, `kotlin`, ...) or `other`; a process of an unlisted kind is spared |
| `limits.processes.stopGraceSeconds` | 10 | How long a build daemon gets after its profile's graceful `stop` (run in the session's worktree) before it is killed by pid; 0 = no graceful step |
| `limits.processes.stuckMinutes` | 15 | A profile's `stuck` client (a CLI call) with no CPU for this long is reported stuck in its session; 0 = off |
| `nudges.deferToHooks` | `[]` | Built-in notices a script replaces (for example `notice.ticket_finished`) |
| `harness.claude.mode` | `interactive` | How Claude agents run. `interactive`: the real `claude` CLI in its own terminal, as you run Claude Code yourself, so usage behaves like normal Claude Code use. `headless`: `claude -p` stream-json (SDK), a machine-readable stream that may be metered differently. Switch in Settings (Plugins tab, Harnesses > Claude Code, field "Mode") or `infantry settings set harness.claude.mode headless`; it applies at the next session launch. See [Harnesses](Harnesses.md#claude-code-the-real-cli-by-default) |

Plugins add their own keys through `config` cards in their `module.json` (see [Plugins](Plugins.md)). The full list
for your build: `infantry settings`, and the Settings tab (searchable). Environment variables (`INFANTRY_*`, also read
as `INFANTRY_*`) set machine-level values such as the port, state dir and token; see the README.
