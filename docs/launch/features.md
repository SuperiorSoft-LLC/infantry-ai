# Feature list (DRAFT, owner review)

Every item below was checked against the repository on this branch. Paths are relative to the repo root.

## Several agent CLIs from one board
Claude Code, opencode and pi harnesses; provider and model chosen per ticket or role; other CLIs described in JSON.
plugins/official/Infantry_Claude/module.json
plugins/official/Infantry_OpenCode/module.json
plugins/official/Infantry_Pi/module.json
docs/wiki/Harnesses.md

## Ticket board, one branch and worktree per ticket
Lanes To Do, In Progress, Pending, Done (plus Backlog); role, type and weight per ticket; Pending set by the daemon while a blocking ticket is open; weight can map to a provider and model (setting `tickets.weightExecutors`).
plugins/official/Infantry_Board/module.json
docs/wiki/Settings.md
README.md

## Epics, triggers and git view
Epics group tickets; triggers start work on an event; a git tab shows the project's git state.
plugins/official/Infantry_Epics/module.json
plugins/official/Infantry_Triggers/module.json
plugins/official/Infantry_Git/module.json

## Questions that do not block the agent
An agent asks with options and a recommendation; the answer returns as mail (`infantry ask`).
README.md

## Mail, live progress, shared project knowledge
Agents message each other and you, report progress, and share decisions, issues and notes kept in the daemon.
README.md

## Plugins, and plugins inside plugins
Tabs, panels, board cards, daemon tools and harnesses are plugins with a `module.json`. A host plugin declares slots and block types that child plugins fill.
docs/wiki/Plugins.md
plugins/official/Infantry_Board/module.json
plugins/examples/Acme_DaemonHello

## Lua scripting and hooks
Sandboxed Lua 5.2 (LuaJ) scripts in `.infantry/scripts/`. Hooks run a script on lane enter/exit, incoming mail, a timer (`every <N>s`) or a named event. Scripts queue calls to any daemon tool, with multi-phase re-runs (`ih.again`). Any role may write scripts. The engine is itself a plugin behind an open extension point.
plugins/official/Infantry_Scripting/module.json
plugins/official/Infantry_Lua/module.json
docs/wiki/Scripting.md

## Pace and usage
Pace trend charts per harness (percent used, pace delta, forecast to reset), a plan usage and limits tab, and per-session statistics.
plugins/official/Infantry_Pace/module.json
plugins/official/Infantry_Usage/module.json
plugins/official/Infantry_Stats/module.json

## Clients and CLI
Windows desktop app (runs its own daemon), Android client, browser client served by the daemon, a CLI (`infantry`; `agenthq` is a deprecated alias), and a headless server (archive or Docker image you build yourself).
README.md (section "The clients")

## Local-first
No Infantry AI cloud; code, keys and agent output stay on your hardware.
README.md

## Not claimed (unsure)
- Live updates (README says "coming shortly").
- Richer weight routing rules (README: planned).
- macOS and Linux desktop apps (not packaged).
- Google Play or signed installers (not available).
- Any speed, token or cost saving figure (no measured number in the docs).
- Review tab behaviour (Infantry_Review is only described as "pod nodes waiting for review"); not verified end to end.
- Whether the free opencode models work for a given user (decided by opencode).
