# docs/ index: current reference, design records, history

One line per top-level file in `docs/`. Search here before you grep `docs/`: a **current reference** states how things are
now; a **design record** is dated and describes a plan or a finding at that date (the code wins; the wiki page named in
the last column replaces it, or "none" when no wiki page covers it yet); **history** is a log, measurement or one-off
report that no longer guides work. Nothing here moves or deletes a file.

For how the product works, start with the wiki: [docs/wiki/Architecture.md](docs/wiki/Architecture.md) and
[docs/wiki/index.md](docs/wiki/index.md).

## Current reference

| File | Status | What it covers |
|---|---|---|
| [docs/VISION.md](docs/VISION.md) | current reference | The product vision, in one page. |
| [docs/GATE.md](docs/GATE.md) | current reference | The bulk QA gate and how to run it. |
| [docs/RELEASING.md](docs/RELEASING.md) | current reference | How a release build is packaged and published. |
| [docs/INFANTRY-INSTALL.md](docs/INFANTRY-INSTALL.md) | current reference | Installing a production build (desktop plus its own daemon). |
| [docs/INFANTRY-EVIDENCE-BAR.md](docs/INFANTRY-EVIDENCE-BAR.md) | current reference | What a worker must show before a ticket is done. |
| [docs/HARNESS-SUPPORT.md](docs/HARNESS-SUPPORT.md) | current reference | Which features each agent harness supports. |
| [docs/EFFORT.md](docs/EFFORT.md) | current reference | The per-session reasoning effort setting. |
| [docs/CONTEXT-LIMIT.md](docs/CONTEXT-LIMIT.md) | current reference | The per-session context limit and compaction. |
| [docs/POLICY.md](docs/POLICY.md) | current reference | Named actions and the policy layer. Replaced by the Settings page for scopes; the policy mechanism itself is current. |
| [docs/PLUGIN-OVERRIDES.md](docs/PLUGIN-OVERRIDES.md) | current reference | Changing a block from a plugin without editing core. |
| [docs/PLUGIN-HELLO.md](docs/PLUGIN-HELLO.md) | current reference | End-to-end example of a config-driven block (Acme_Hello). |
| [docs/IHQ-PACKAGE.md](docs/IHQ-PACKAGE.md) | current reference | The signed `.ihq` plugin package format (desktop JVM). |
| [docs/SCRIPT-TIMERS.md](docs/SCRIPT-TIMERS.md) | current reference | Script timers and script-reported progress. |
| [docs/CHART-STYLE.md](docs/CHART-STYLE.md) | current reference | The standard for every chart and stats list. |
| [docs/CHAT-MARKDOWN-ENGINE.md](docs/CHAT-MARKDOWN-ENGINE.md) | current reference | The seam that formats assistant replies. |
| [docs/docker-deploy.md](docs/docker-deploy.md) | current reference | Running the headless daemon in a container. |
| [docs/infantry-e2e-task.md](docs/infantry-e2e-task.md) | current reference | The end-to-end go/no-go task for agent work. |
| [docs/LICENSE-MAP.md](docs/LICENSE-MAP.md) | current reference (draft) | Directory-by-directory license split. Replaced by [docs/wiki/licensing.md](docs/wiki/licensing.md). |
| [docs/TEST-TODO.md](docs/TEST-TODO.md) | current reference (working list) | Items queued for the final QA checkpoint. |

## Design records (dated; the code wins)

| File | Status | Replaced by (wiki) |
|---|---|---|
| [docs/AGENT-REGISTRY.md](docs/AGENT-REGISTRY.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/AGENT-TASKS-DESIGN.md](docs/AGENT-TASKS-DESIGN.md) | design record (dated) | none |
| [docs/AI-FRIENDLY-AUDIT.md](docs/AI-FRIENDLY-AUDIT.md) | design record (dated) | none |
| [docs/ARCH-AUDIT-2026-11.md](docs/ARCH-AUDIT-2026-11.md) | design record (dated 2026-11-07): duplicate and ad-hoc implementations per concern, with their consolidation tickets | none |
| [docs/BLOCKS-INJECTORS.md](docs/BLOCKS-INJECTORS.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/BLOCKS-LAYOUT-DESIGN.md](docs/BLOCKS-LAYOUT-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/CONFIG-AUDIT-2026-11.md](docs/CONFIG-AUDIT-2026-11.md) | design record (dated 2026-11-08): audit of every setting shown in Settings, with reader, effect and verdict | none |
| [docs/CONFIG-FIELDS.md](docs/CONFIG-FIELDS.md) | design record (dated) | [docs/wiki/Settings.md](docs/wiki/Settings.md) |
| [docs/CONFIG-MANIFEST-DESIGN.md](docs/CONFIG-MANIFEST-DESIGN.md) | design record (dated) | [docs/wiki/Settings.md](docs/wiki/Settings.md) |
| [docs/CORE-CLEAN.md](docs/CORE-CLEAN.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/DAEMON-BOARD-API.md](docs/DAEMON-BOARD-API.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](docs/wiki/daemon-plugin-api.md) |
| [docs/DAEMON-CORE.md](docs/DAEMON-CORE.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/RUNNER-POOL.md](docs/RUNNER-POOL.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/RUNNER-UNIFY.md](docs/RUNNER-UNIFY.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/DAEMON-PLUGIN-PLAN.md](docs/DAEMON-PLUGIN-PLAN.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](docs/wiki/daemon-plugin-api.md) |
| [docs/DAEMON-PLUGINS.md](docs/DAEMON-PLUGINS.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](docs/wiki/daemon-plugin-api.md) |
| [docs/EMPTY-SHELL-DESIGN.md](docs/EMPTY-SHELL-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/HARNESS-PLUGINS.md](docs/HARNESS-PLUGINS.md) | design record (dated) | [docs/wiki/Harnesses.md](docs/wiki/Harnesses.md) |
| [docs/INFANTRY-IN-APP-BUILD-DESIGN.md](docs/INFANTRY-IN-APP-BUILD-DESIGN.md) | design record (dated) | none |
| [docs/INFANTRY-MOBILE-UI-PLAN.md](docs/INFANTRY-MOBILE-UI-PLAN.md) | design record (dated) | none |
| [docs/INFANTRY-PHASE10-KNOWLEDGE.md](docs/INFANTRY-PHASE10-KNOWLEDGE.md) | design record (dated) | none |
| [docs/INFANTRY-ROLE-FIRST.md](docs/INFANTRY-ROLE-FIRST.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/INFANTRY-ROLE-TRAITS.md](docs/INFANTRY-ROLE-TRAITS.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/NORTH-STAR-DESIGN.md](docs/NORTH-STAR-DESIGN.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/PACING.md](docs/PACING.md) | design record (dated) | none |
| [docs/PLUGIN-ENDPOINTS-DESIGN.md](docs/PLUGIN-ENDPOINTS-DESIGN.md) | design record (dated) | [docs/wiki/daemon-plugin-api.md](docs/wiki/daemon-plugin-api.md) |
| [docs/PLUGIN-MANAGER.md](docs/PLUGIN-MANAGER.md) | design record (dated) | [docs/wiki/Plugins.md](docs/wiki/Plugins.md) |
| [docs/PLUGIN-REPO-DESIGN.md](docs/PLUGIN-REPO-DESIGN.md) | design record (dated) | [docs/wiki/make-a-plugin.md](docs/wiki/make-a-plugin.md) |
| [docs/RUNTIME-LOAD.md](docs/RUNTIME-LOAD.md) | design record (dated) | [docs/wiki/Plugins.md](docs/wiki/Plugins.md) |
| [docs/RUNTIME-PLUGINS-AUDIT.md](docs/RUNTIME-PLUGINS-AUDIT.md) | design record (dated) | [docs/wiki/Plugins.md](docs/wiki/Plugins.md) |
| [docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md](docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/SHELL-LEAKS.md](docs/SHELL-LEAKS.md) | design record (dated) | [docs/wiki/Architecture.md](docs/wiki/Architecture.md) |
| [docs/STACK-NEUTRAL.md](docs/STACK-NEUTRAL.md) | design record (dated) | none |
| [docs/STALL.md](docs/STALL.md) | design record (dated) | none |
| [docs/TOAST-SYSTEM-FINDINGS.md](docs/TOAST-SYSTEM-FINDINGS.md) | design record (dated) | none |

## History (findings, measurements and logs; no longer guide work)

| File | Status | What it records |
|---|---|---|
| [docs/BLOCKS-NEXT-SPLITS.md](docs/BLOCKS-NEXT-SPLITS.md) | history | A one-time survey of what was left to split. |
| [docs/BLOCKS-PERF-CHECK.md](docs/BLOCKS-PERF-CHECK.md) | history | A performance measurement after the block splits. |
| [docs/BUILD-SPEED.md](docs/BUILD-SPEED.md) | history | A build-time measurement. |
| [docs/DEMO-MERGE.md](docs/DEMO-MERGE.md) | history | Folding the demo branch into dev. |
| [docs/DEMO-QA.md](docs/DEMO-QA.md) | history | A rebuild and retest of the public demo. |
| [docs/GAP-AUDIT.md](docs/GAP-AUDIT.md) | history | The demo as a compass: gap findings. |
| [docs/QA-LOG.md](docs/QA-LOG.md) | history | Dated QA log entries. |
| [docs/RENAME-INVENTORY.md](docs/RENAME-INVENTORY.md) | history | The inventory of the old product name. |
| [docs/TOKEN-BISECT.md](docs/TOKEN-BISECT.md) | history | A study of what added idle token burn. |
| [docs/USAGE-INVESTIGATION.md](docs/USAGE-INVESTIGATION.md) | history | A read-only study of the weekly usage meter. |
| [docs/USAGE-POLL.md](docs/USAGE-POLL.md) | history | The weekly-meter poll script's first run. |
| [docs/USAGE-POLL-2.md](docs/USAGE-POLL-2.md) | history | The weekly-meter step capture. |
