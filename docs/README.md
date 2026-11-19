# docs/ index: current reference, design records, history

One line per top-level file in `docs/`. Search here before you grep `docs/`: a **current reference** states how things are
now; a **design record** is dated and describes a plan or a finding at that date (the code wins; the wiki page named in
the last column replaces it, or "none" when no wiki page covers it yet); **history** is a log, measurement or one-off
report that no longer guides work. Nothing here moves or deletes a file.

For how the product works, start with the wiki: [docs/wiki/Architecture.md](wiki/Architecture.md) and
[docs/wiki/index.md](wiki/index.md).

On `main` the product is the Rust framework. The table below first lists the Rust records under `docs/rust-port/` and the
app's updater notes. The tables after it describe the Kotlin app, which lives on the `legacy-java` branch; their status
column still applies there, and they are kept as history.

## Rust framework (main)

| File | Status | What it covers |
|---|---|---|
| [docs/rust-port/PLAN.md](rust-port/PLAN.md) | design record (2026-11-18) | The plan to rebuild Infantry AI as a Rust daemon and a Tauri 2 / React app. Replaced by [Architecture](wiki/Architecture.md) for the current shape. |
| [docs/rust-port/LAYERS.md](rust-port/LAYERS.md) | current reference | The crate layer table. The guard `infantry-guards` enforces it. Replaced by [Architecture](wiki/Architecture.md), section 2. |
| [docs/rust-port/MIGRATION.md](rust-port/MIGRATION.md) | current reference | Moving a Kotlin state dir to the Rust app with `infantry-migrate`. |
| [docs/rust-port/MCP-BRIDGE.md](rust-port/MCP-BRIDGE.md) | current reference | The native `infantry mcp serve`, a drop-in for the Kotlin MCP stdio bridge. |
| [docs/rust-port/BUILD-SPEED.md](rust-port/BUILD-SPEED.md) | current reference | The `owner-test` build profile and why it is quicker than the release profile. |
| [docs/rust-port/PARITY.md](rust-port/PARITY.md) | design record (2026-11-18) | The UI checklist: Kotlin app against the Rust app, row by row. |
| [docs/rust-port/PARITY-GAPS.md](rust-port/PARITY-GAPS.md) | design record (2026-11-18/19) | The static gap audit of frames, plugins, tools and routes. |
| [docs/rust-port/MOVE-OVER.md](rust-port/MOVE-OVER.md) | history (end-to-end proof) | The move-over bar, with its evidence table. |
| [docs/rust-port/PERF.md](rust-port/PERF.md) | history (measurements) | The performance profile of the Rust port. |
| [app/src-tauri/UPDATER.md](../app/src-tauri/UPDATER.md) | current reference | The Rust installer, updater, feed, signing and Android release notes. Replaced by [Updates](wiki/Updates.md) and [Install](wiki/Install.md). |

## Legacy-java (Kotlin app): current reference

| File | Status | What it covers |
|---|---|---|
| [docs/VISION.md](VISION.md) | current reference | The product vision, in one page. |
| [docs/GATE.md](GATE.md) | current reference | The bulk QA gate and how to run it. |
| [docs/RELEASING.md](RELEASING.md) | current reference | How a release build is packaged and published. |
| [docs/STORE.md](STORE.md) | current reference | Microsoft Store: the MSIX package, its CI submission and the owner-only Partner Center steps. |
| [docs/INFANTRY-INSTALL.md](INFANTRY-INSTALL.md) | current reference | Installing a production build (desktop plus its own daemon). |
| [docs/INFANTRY-EVIDENCE-BAR.md](INFANTRY-EVIDENCE-BAR.md) | current reference | What a worker must show before a ticket is done. |
| [docs/HARNESS-SUPPORT.md](HARNESS-SUPPORT.md) | current reference | Which features each agent harness supports. |
| [docs/EFFORT.md](EFFORT.md) | current reference | The per-session reasoning effort setting. |
| [docs/CONTEXT-LIMIT.md](CONTEXT-LIMIT.md) | current reference | The per-session context limit and compaction. |
| [docs/POLICY.md](POLICY.md) | current reference | Named actions and the policy layer. Replaced by the Settings page for scopes; the policy mechanism itself is current. |
| [docs/PLUGIN-OVERRIDES.md](PLUGIN-OVERRIDES.md) | current reference | Changing a block from a plugin without editing core. |
| [docs/PLUGIN-HELLO.md](PLUGIN-HELLO.md) | current reference | End-to-end example of a config-driven block (Acme_Hello). |
| [docs/IHQ-PACKAGE.md](IHQ-PACKAGE.md) | current reference | The signed `.ihq` plugin package format (desktop JVM). |
| [docs/SCRIPT-TIMERS.md](SCRIPT-TIMERS.md) | current reference | Script timers and script-reported progress. |
| [docs/CHART-STYLE.md](CHART-STYLE.md) | current reference | The standard for every chart and stats list. |
| [docs/CHAT-MARKDOWN-ENGINE.md](CHAT-MARKDOWN-ENGINE.md) | current reference | The seam that formats assistant replies. |
| [docs/docker-deploy.md](docker-deploy.md) | current reference | Running the headless daemon in a container. |
| [docs/infantry-e2e-task.md](infantry-e2e-task.md) | current reference | The end-to-end go/no-go task for agent work. |
| [docs/LICENSE-MAP.md](LICENSE-MAP.md) | current reference (draft) | Directory-by-directory license split. Replaced by [docs/wiki/licensing.md](wiki/licensing.md). |
| [docs/TEST-TODO.md](TEST-TODO.md) | current reference (working list) | Items queued for the final QA checkpoint. |

## Legacy-java (Kotlin app): design records (dated; the code wins)

| File | Status | Replaced by (wiki) |
|---|---|---|
| [docs/AGENT-REGISTRY.md](AGENT-REGISTRY.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/AGENT-TASKS-DESIGN.md](AGENT-TASKS-DESIGN.md) | design record (dated) | none |
| [docs/AI-FRIENDLY-AUDIT.md](AI-FRIENDLY-AUDIT.md) | design record (dated) | none |
| [docs/ARCH-AUDIT-2026-11.md](ARCH-AUDIT-2026-11.md) | design record (dated 2026-11-07): duplicate and ad-hoc implementations per concern, with their consolidation tickets | none |
| [docs/CARD-REGISTRY.md](CARD-REGISTRY.md) | design record (dated 2026-11-12): plugin chat cards (fence `card:<type>`, `chat.cards` registry, on-demand agent discovery, safe card images, News Agent), with the ordered ticket list | none |
| [docs/BLOCKS-INJECTORS.md](BLOCKS-INJECTORS.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/BLOCKS-LAYOUT-DESIGN.md](BLOCKS-LAYOUT-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/CONFIG-AUDIT-2026-11.md](CONFIG-AUDIT-2026-11.md) | design record (dated 2026-11-08): audit of every setting shown in Settings, with reader, effect and verdict | none |
| [docs/CONFIG-FIELDS.md](CONFIG-FIELDS.md) | design record (dated) | [docs/wiki/Settings.md](wiki/Settings.md) |
| [docs/CONFIG-MANIFEST-DESIGN.md](CONFIG-MANIFEST-DESIGN.md) | design record (dated) | [docs/wiki/Settings.md](wiki/Settings.md) |
| [docs/CORE-CLEAN.md](CORE-CLEAN.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/DAEMON-BOARD-API.md](DAEMON-BOARD-API.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](wiki/daemon-plugin-api.md) |
| [docs/DAEMON-CORE.md](DAEMON-CORE.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/RUNNER-POOL.md](RUNNER-POOL.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/RUNNER-UNIFY.md](RUNNER-UNIFY.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/DAEMON-PLUGIN-PLAN.md](DAEMON-PLUGIN-PLAN.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](wiki/daemon-plugin-api.md) |
| [docs/DAEMON-PLUGINS.md](DAEMON-PLUGINS.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](wiki/daemon-plugin-api.md) |
| [docs/EMPTY-SHELL-DESIGN.md](EMPTY-SHELL-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/HARNESS-PLUGINS.md](HARNESS-PLUGINS.md) | design record (dated) | [docs/wiki/Harnesses.md](wiki/Harnesses.md) |
| [docs/INFANTRY-IN-APP-BUILD-DESIGN.md](INFANTRY-IN-APP-BUILD-DESIGN.md) | design record (dated) | none |
| [docs/INFANTRY-MOBILE-UI-PLAN.md](INFANTRY-MOBILE-UI-PLAN.md) | design record (dated) | none |
| [docs/INFANTRY-PHASE10-KNOWLEDGE.md](INFANTRY-PHASE10-KNOWLEDGE.md) | design record (dated) | none |
| [docs/INFANTRY-ROLE-FIRST.md](INFANTRY-ROLE-FIRST.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/INFANTRY-ROLE-TRAITS.md](INFANTRY-ROLE-TRAITS.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/NORTH-STAR-DESIGN.md](NORTH-STAR-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/PACING.md](PACING.md) | design record (dated) | none |
| [docs/PLUGIN-ENDPOINTS-DESIGN.md](PLUGIN-ENDPOINTS-DESIGN.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](wiki/daemon-plugin-api.md) |
| [docs/PLUGIN-MANAGER.md](PLUGIN-MANAGER.md) | design record (dated) | [docs/wiki/Plugins.md](wiki/Plugins.md) |
| [docs/PLUGIN-REPO-DESIGN.md](PLUGIN-REPO-DESIGN.md) | design record (dated) | [docs/wiki/make-a-plugin.md](wiki/make-a-plugin.md) |
| [docs/RUNTIME-LOAD.md](RUNTIME-LOAD.md) | design record (dated) | [docs/wiki/Plugins.md](wiki/Plugins.md) |
| [docs/RUNTIME-PLUGINS-AUDIT.md](RUNTIME-PLUGINS-AUDIT.md) | design record (dated) | [docs/wiki/Plugins.md](wiki/Plugins.md) |
| [docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md](SHELL-EMPTY-DROPIN-FEASIBILITY.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/SHELL-LEAKS.md](SHELL-LEAKS.md) | design record (dated) | [docs/wiki/Architecture.md](wiki/Architecture.md) |
| [docs/STACK-NEUTRAL.md](STACK-NEUTRAL.md) | design record (dated) | none |
| [docs/STALL.md](STALL.md) | design record (dated) | none |
| [docs/TOAST-SYSTEM-FINDINGS.md](TOAST-SYSTEM-FINDINGS.md) | design record (dated) | none |
| [docs/design/MCP-TOOLS.md](design/MCP-TOOLS.md) | design record (dated) | none (proposal, MCP-TOOLS-1 2026-11-14: MCP servers for agents on every harness) |

## Legacy-java (Kotlin app): history (findings, measurements and logs; no longer guide work)

| File | Status | What it records |
|---|---|---|
| [docs/BLOCKS-NEXT-SPLITS.md](BLOCKS-NEXT-SPLITS.md) | history | A one-time survey of what was left to split. |
| [docs/BLOCKS-PERF-CHECK.md](BLOCKS-PERF-CHECK.md) | history | A performance measurement after the block splits. |
| [docs/BUILD-SPEED.md](BUILD-SPEED.md) | history | A build-time measurement. |
| [docs/DEMO-MERGE.md](DEMO-MERGE.md) | history | Folding the demo branch into dev. |
| [docs/DEMO-QA.md](DEMO-QA.md) | history | A rebuild and retest of the public demo. |
| [docs/GAP-AUDIT.md](GAP-AUDIT.md) | history | The demo as a compass: gap findings. |
| [docs/QA-LOG.md](QA-LOG.md) | history | Dated QA log entries. |
| [docs/RENAME-INVENTORY.md](RENAME-INVENTORY.md) | history | The inventory of the old product name. |
| [docs/TOKEN-BISECT.md](TOKEN-BISECT.md) | history | A study of what added idle token burn. |
| [docs/USAGE-INVESTIGATION.md](USAGE-INVESTIGATION.md) | history | A read-only study of the weekly usage meter. |
| [docs/USAGE-POLL.md](USAGE-POLL.md) | history | The weekly-meter poll script's first run. |
| [docs/USAGE-POLL-2.md](USAGE-POLL-2.md) | history | The weekly-meter step capture. |
