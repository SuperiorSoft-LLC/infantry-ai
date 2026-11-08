# Memory (project knowledge notes)

The notes live in the Infantry_Notes plugin (`plugins/official/Infantry_Notes/daemon`): `MemoryStore` over the adopted
`memory_notes` table, `MemoryHandler` for the `memory_*` frames, `MemoryTool` for the MCP tool `infantry_memory`.
The CLI is `infantry memory show|search|index|save|delete`.

## Scopes

| Scope | Who sees it | Who writes it |
|---|---|---|
| `project` (default) | everyone in the project; global notes show through | anyone in the project |
| `global` | every project and every agent on this daemon install | the owner (no session) and the roles in setting `knowledge.globalWriteRoles` (default `pm`) |
| `role` | sessions of that role | that role; the pm and the owner may address another role |
| `session` | that one session; deleted when it is removed | that session |

`global` is stored as a project-scope note under the id `MemoryStore.GLOBAL_PROJECT`, which every project's `get` and `search`
already include (a project note of the same name wins). A refused global write answers `role '<role>' may not write global notes`.

## MCP: `infantry_memory`

| action | needs | does |
|---|---|---|
| `show` | `name` | prints the note (no scope: role, then project incl. global, then session) |
| `search` | `query` | matches name, description and body; returns name, description and a snippet |
| `index` | | lists names and descriptions |
| `write` | `name`, `description`, `body` (`folder`) | creates or replaces a note |
| `update` | `name`, `content` | appends; with `find` replaces that one exact passage |
| `delete` | `name` | deletes the note |

Every write is logged on the daemon log with scope, name, writer (role/session or `owner`) and time.
Example: `infantry_memory {"action":"write","scope":"global","name":"usage-override","description":"...","body":"..."}`.
