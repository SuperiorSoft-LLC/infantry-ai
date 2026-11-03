# RENAME-1 inventory: the old product name "AgentHQ"

The product is Infantry AI. Counts come from `git grep -I -i "agenthq\|agent hq"` on 2026-11-02 (about 2,400 files
carry the lowercase identifier; almost all of them are contracts).

## Changed (A, B, C)
- (B) agent-visible: all 16 built-in role personas under `plugins/official/Infantry_Roles/daemon/src/main/resources/roles/`
  (first line "You are AgentHQ's ..." is now "You are Infantry AI's ..."); the pi to-do tool header comment. The session
  preamble (`FirstTurnPreamble.kt`, `TicketContextComposer.kt`) already said Infantry AI. Test: `PersonaNameScrubTest`.
- (A) user-visible: the incoming-sound setting help text, the web demo fixture turn, `superiorSoftLibrary/agentHq/README.md`,
  `agentHqWeb/README.md`, `docs/MCP.md`. Root `README.md` already explains the old name on purpose.
- (C) console lines: none changed (see D, the `[agenthq:...]` markers are parsed).

## Kept (D): contracts, with a backward-compatible rename proposal
| Item | Proposal |
|---|---|
| CLI command `agenthq` | `infantry` already exists as an alias; keep both |
| MCP tool names `agenthq_*` | register `infantry_*` aliases next to them, deprecate later |
| Env vars `AGENTHQ_*` | read `INFANTRY_*` first, fall back to `AGENTHQ_*` |
| Kotlin packages `com.superiorsoftllc.agenthq.*`, Gradle modules `agentHq*`, `agenthq*` | rename only with a plugin-API major version |
| State/config dirs `SuperiorSoft\AgentHQ`, `~/.agenthq`, `.agenthq/`, `agenthq.db`, `AgentHQDatabase` | new dir name, migrate on first start, keep reading the old one |
| Wire headers `X-AgentHQ-Owner/Signature/Timestamp/Delivery` | accept both names, send the new one after a protocol bump |
| Console markers `[agenthq:done ...]`, `[agenthq:note ...]`, `[agenthq:truncated ...]` | parser accepts both prefixes, then emit `[infantry:...]` |
| Legacy installer names `AgentHQ-<v>.msi`, `AgentHQ-server-` | already accepted by the updater; new releases use InfantryAI |
| HTTP `User-Agent: AgentHQ-updater`, `rootProject.name = "AgentHQ"`, branch `feature-agenthq-mvp` | rename with the repo/branch move |
| Internal comments and KDoc that say AgentHQ (about 150, not shown to users or agents) | opportunistic cleanup |
| Docs under `docs/` and `superiorSoftLibrary/agentHq/docs/` (design records, AGENTHQ-*.md file names) | dated records; rename files with redirects |

## Data for the PM (DB, not code)
Custom roles and role notes stored in the daemon DB were not readable from the worktree. The project's `fullstack` role
note text in this session did not contain the old name, but the persona line "You are AgentHQ's full-stack engineer"
was loaded from the old built-in; existing DB copies of personas (if any were saved from the old built-ins) need the
same one-line edit. Run `agenthq role list` and look for "AgentHQ".
