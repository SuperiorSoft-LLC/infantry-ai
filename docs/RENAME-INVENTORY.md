# Rename inventory: the old product name "AgentHQ"

The product is Infantry AI. RENAME-1 cleaned everything a user or an agent SEES. RENAME-2 (ticket 5e8b4e17) renamed
the contract identifiers too. The old spelling survives only as a working legacy fallback, listed here.

## Done (RENAME-2)
| Item | Now | Legacy still accepted |
|---|---|---|
| CLI | `infantry` is the command; installDist, the installed app and the Docker image carry it | `agenthq` is a launcher alias (same program), deprecated |
| MCP tool names | every tool is registered and listed as `infantry_*` | `agenthq_*` is accepted at the MCP call, the daemon plugin host and the script dispatcher (`LegacyToolNames`); never listed |
| Env vars | `INFANTRY_*` | `AGENTHQ_*` is read when only it is set; `InfantryEnv` plus a process mirror (needs `--add-opens java.base/java.util`, which every launcher passes); children see both spellings |
| System properties | `-Dinfantry.*` | `-Dagenthq.*` is copied to the new name at start |
| Kotlin packages | `com.superiorsoftllc.infantry.*` (all modules, plugins, tests, `module.json` entries, service files) | none |
| Gradle modules | `infantry*` (folders and project paths), `rootProject.name = "InfantryAI"` | none |
| State dir | `SuperiorSoft\InfantryAI` (macOS `InfantryAI`, Linux `infantry`), dev `~/.infantry`, UI data `~/.infantry-ui` | `StateDirMigration`: only the old dir exists -> it is copied (temp sibling, then renamed), names with the old word get the new word (`infantry.db`); both exist -> the new one; the old one is never changed or deleted |
| Project folder | `.infantry/` (scripts, hooks, uploads, `mcp.json`) | `.agenthq/` is read per file when the file is not under `.infantry/`; writes go to `.infantry/` |
| DB | `infantry.db`, class `InfantryDatabase` | `agenthq.db` is copied to `infantry.db` by the layout upgrade |
| Wire headers | `X-Infantry-Owner/Session/Signature/Timestamp/Delivery` sent | the `X-AgentHQ-*` names are accepted |
| Console markers | `[infantry:done ...]`, `note`, `truncated`, `start`, `eof`, `running` emitted | the notifier ignores `[agenthq:` runner lines as it does `[infantry:` |
| Installer / archive names | `InfantryAI-<v>.msi`, `InfantryAI-server-...`; `User-Agent: Infantry-updater` | `AgentHQ-<v>.msi`, `AgentHQ-server-...` still recognised by the updater |
| Comments, KDoc, docs, site | new names; commands use `infantry` | this file, history notes |

## Kept on purpose
- Git branch names (`feature-agenthq-mvp`, `staging-agenthq`, ticket branches), the GitHub repo and `refs/agenthq/tickets/*`
  (a ref namespace already present in existing repos), local checkout folder names: not part of this ticket.
- The project's own `.agenthq/scripts` (still runs untouched through the fallback above).

## Data for the PM (DB, not code)
Custom roles and role notes stored in the daemon DB may still say AgentHQ or call `agenthq_*`; the aliases keep them
working. Run `infantry role list` and look for "AgentHQ" to tidy them.
