# Official plugins

What ships with Infantry AI. Each lives in `rust/plugins/official/<Module>/` unless marked; "WASM" is a daemon half
built as a component, "sidecar" a native program, "UI" a web UI half. Truth: each module's `module.json`.

| Module | Halves | What it does |
|---|---|---|
| Infantry_Cards | WASM | The chat card directory (`cards_list`, `cards_show`) and the safe card image proxy. |
| Infantry_Chat | WASM | COMMS mail tools: inbox, read, message, announce, the project directory, and the image upload frames. |
| Infantry_Desktop | UI | A desktop layout: a wallpaper with an icon for the orchestrator and one for Apps. |
| Infantry_Docs | WASM | The user wiki inside the component; agents read a page on demand (`docs_list`, `docs_show`). |
| Infantry_Git | WASM | The Git daemon half; registers nothing today (the pipeline tools live with the board). |
| Infantry_Health | WASM | The `infantry_health` tool: daemon health and environment report (CLI: `infantry health`). |
| Infantry_Installer | WASM, UI | Installs, enables and removes plugins from the bundled folder and the sources; the plugin the daemon starts with when nothing else is installed. |
| Infantry_JobFeed | WASM | The Job Feed box: two pull routes over a session's watched jobs. |
| Infantry_Mcp | WASM | `infantry_mcp`: the agent's view of the MCP registry (list, status, my tools, grants; changes refused to agents). |
| Infantry_Notes | WASM | Project knowledge: memory notes, the decisions log and the issues log. |
| Infantry_Plugins | WASM | Agent tools over the plugin manager: list, search, show, status, list import, an owner-approved install request, `plugins_status`. |
| Infantry_Roles | data | The built-in role catalog (architect, backend, frontend, pm, qa and the rest) and each role's starting text. |
| Infantry_Runners | UI | The Runners section of Settings: every runner, its status and actions. |
| Infantry_Settings | WASM | Project config tools (per-role caps, routing rules, experiments, project data) and scoped settings tools. |
| Infantry_Sites | sidecar (infantry-plugins repository) | Infantry AI Hosting: your hosting servers and SSH for agents; a client of the WebServices API. Bundled from the infantry-plugins repository (`bundled-external.json`). |
| Infantry_Triggers | WASM, UI | Triggers that start work on an event, with the Triggers tab. |
| Infantry_Ui | UI plugin | The Infantry AI app itself (`uiPlugin`): the orchestrator interface; its plugins are managed in its Plugins tab. |

Test-only: `rust/plugins/test/Acme_UiFault` (staged only with `INFANTRY_STAGE_TEST=1`, never in a build that ships).

Plugins in the infantry-plugins catalog (installed from a source, not bundled; for example Infantry_News, the News Agent and its `card:news` card) are listed in that repository's
`official/` and `community/` folders.
