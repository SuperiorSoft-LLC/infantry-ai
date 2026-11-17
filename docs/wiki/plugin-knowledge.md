# Plugin knowledge

A plugin tells agents what its tools are for. Any role, any plugin (community tier included), no host edit per plugin.
The plan is CAL-PLAN-2 section 2 (ticket cc8207de); this page describes what is built.

A built example: the Calendar plugin ([Calendar](Calendar.md)).

## Status

| Part | Ticket | State |
|---|---|---|
| module.json `knowledge`, the SDK ports, the briefing section in every role session's first turn, the budget settings, `modules.changed` on a version change | PK-1 | built |
| The provider (Infantry_Notes): `plugin:<Module>/<id>` notes, the `AgentBriefing` answer, live notices to running sessions | PK-2 | built (real-window proof pending) |
| MCP `tools/list_changed` so a live session's tool list catches up (the stdio bridge compares the tool list every `limits.mcp.toolsWatchSeconds` and sends `notifications/tools/list_changed` when it changes) | PK-3 | built |

Until a provider is loaded, no session gets a PLUGIN TOOLS section: no provider = no section, the same rule as the project
knowledge digest.

## Declaring knowledge (module.json)

```json
"knowledge": [
  { "id": "calendar",
    "summary": "Calendar: add tasks and reminders for the user, a role or a session (infantry_calendar_add); list with infantry_calendar_list.",
    "file": "knowledge/calendar.md",
    "tools": ["infantry_calendar_add", "infantry_calendar_list"],
    "roles": ["*"], "skipRoles": [], "projects": [] }
],
"files": ["knowledge"]
```

| Field | Meaning |
|---|---|
| `id` | Unique within the module: `[a-z0-9][a-z0-9._-]{0,63}`. |
| `summary` | One line, briefed into the first turn. Cut to the setting `limits.knowledge.plugins.summaryMaxChars` (default 200). Required. |
| `file` | Optional: a `.md` or `.txt` path inside the module folder with the details an agent reads on demand. It may use the `<!-- skip: roleA,roleB -->` section markers of the core KNOWLEDGE.md. List its folder in `files` so a packaged install carries it. |
| `tools` | The tool names the entry is about (named in the notices of PK-2). |
| `roles` | The roles that see it. Empty or `*` = every role; `assistant` is just a role. |
| `skipRoles` | Roles that never see it; wins over `roles`. |
| `projects` | Project ids it applies to. Empty = every project, and sessions with no project. |

A bad entry (a rule above broken, a duplicate id) is skipped, never the module. Types: `KnowledgeDeclaration`,
`KnowledgeDeclarations` (the rules: `problems`, `valid`, `inScope`).

The knowledge is versioned with the plugin: a new version ships its new text, and the daemon's named event
`modules.changed` fires on a version change as well as on install, enable, disable and uninstall. Its payload is
`{"modules":[ids], "versions":[{"id":..,"version":..}]}`.

## Knowledge computed at run time

A daemon half whose knowledge depends on config or state contributes it through the `PluginKnowledge` service:

```kotlin
val handle = api.services.find(PluginKnowledge::class)?.contribute(
    KnowledgeEntry(id = "servers", summary = "You can reach these servers: ...", roles = listOf("*"))
)
// handle?.close() removes it; unloading the plugin removes it too.
```

The provider publishes the service with `services.provideScoped`, so the entry is stamped with the contributing module.

## What agents see

At spawn, every role session (ticket workers, spawned roles, the Assistant) asks the `AgentBriefing` service once and
appends the answer after the shared knowledge. The section is frozen with the persona, so a running session does not drift:

```
PLUGIN TOOLS (from installed plugins; details: infantry memory show plugin:<Module>/<id>)
- Calendar: add tasks and reminders ... (plugin:Infantry_Calendar/calendar)
```

The whole section stays within the setting `limits.knowledge.plugins.maxChars` (default 1500). When the lines do not fit,
entries that name the session's role come first and the rest are named in one `+N more` line. Every provider renders with
`KnowledgeBriefing.render`, so they all brief alike; the core holds the answer to the budget even if a provider overshoots.

The skip-marker parser is `KnowledgeSections.textFor`, shared by the core KNOWLEDGE.md and plugin knowledge files.

## The provider (Infantry_Notes, PK-2)

Infantry_Notes' daemon half (`PluginKnowledgeProvider`) reconciles at start and on every `modules.changed`:

- One global note `plugin:<Module>/<id>` (folder `plugins`) per valid declared entry of a loaded module, holding the summary, the
  tool names and the entry's `file` text. A changed version or text refreshes it; a note whose module is no longer loaded (also one
  left by an earlier run) is deleted. Any session reads it, the Assistant (no project) too: `infantry memory show plugin:<Module>/<id>`.
- It answers `AgentBriefing` and accepts `PluginKnowledge.contribute` (dynamic entries, removed on close or when the module unloads).
- Live notices: when an entry is added, upgraded or removed, each live session in its scope (role, project; a session that has
  not run yet gets it in its first turn instead) hears ONE coalesced notice: "New tools available: ... If your tool list doesn't show
  them yet, call them with `infantry tool <name>`", or "These tools were removed; do not call: ...".
  An idle session is not woken: the notice is held and arrives with its next turn.

| Setting | Default | Meaning |
|---|---|---|
| `knowledge.plugins.notifyLive` | on | Send the live notices at all. |
| `knowledge.plugins.noticeWakes` | off | Deliver at once, waking an idle session. |
| `knowledge.plugins.noticeCoalesceMs` | 2000 | Merge changes within this window into one notice. |
