# DECOUPLE-PLAN: agents stand alone, the orchestrator is removable, the layout is a plugin

Design record, 2026-11-20. Ticket #45 DECOUPLE-1, under EPIC #41 DECOUPLE. Status: plan. The RED tests and the guards
have landed; no move has.

The owner's target (2026-11-20): Infantry boots like an operating system. The architecture page is #83 OS-DOC-1.
1. **Core** is the kernel: frames, events and hooks, slots, settings, the vault, processes, files and the plugin host.
   It holds no feature. It also owns the splash screen and the boot progress (#89 BOOT-SPLASH-1).
2. **The installer plugin** is the bootstrap: core loads it when nothing is installed (#90 INSTALLER-1).
3. **The UI plugin** is the shell and its layout provider (#87 WEBUI-5, #91 BOOT-CHAIN-1). The installer loads it.
4. **The component plugins** plug into the UI: agents, board, mail, notes and the rest.

With plugins installed, the app starts like a normal application. A bundled build is the same chain with the plugins
pre-installed, and it takes no special code path. The shell-only build is core plus the installer (#92 SHELL-ONLY-1).

Within that target, EPIC #41 has three goals:
- Core holds generic mechanisms only.
- Agents run and talk with no orchestrator installed.
- The orchestrator is a set of plugins that cooperate only through core seams.

**No user-visible change.** The owner: "The behaviors should be exactly the same." Every move keeps the screens, the
flows and the look, and bundled and installed plugins behave alike. The one new screen is the installer's bootstrap
screen, shown only when nothing is installed. In this page, **D-N** is a daemon move, **UI-N** an app move, and
**LIC-N** one of the Sonnet-sized moves that remove the licence crossings (section 7a).

1. [Where we stand](#1-where-we-stand)
2. [The target](#2-the-target)
3. [The RED lists](#3-the-red-lists)
4. [The UI map](#4-the-ui-map)
5. [Plugin management as a plugin](#5-plugin-management-as-a-plugin)
6. [Plugin ABI gaps](#6-plugin-abi-gaps)
7. [The licence line](#7-the-licence-line)
8. [AIFY design targets](#8-aify-design-targets)
9. [Building an app on Infantry](#9-building-an-app-on-infantry)
10. [The shell-only proof](#10-the-shell-only-proof)
11. [The moves, in order](#11-the-moves-in-order)
12. [What it breaks, and the owner's calls](#12-what-it-breaks-and-the-owners-calls)

## 1. Where we stand

The daemon builds everything in one composition root, `rust/crates/infantry-daemon/src/compose/mod.rs:505-560`. Its
unit list, in order:

1. projects, git, worktrees;
2. board (with the dispatcher), usage, mail;
3. attention, notifications, notify, prompts, questions;
4. tools, Run, settings, vault, MCP;
5. pods, account, timeline, scripts;
6. the knowledge units, the plugins' frame units, health;
7. agent frames, harness frames, harness install.

The connection burst (`hello`, `compose/mod.rs:743-750`) sends `worktrees` first, then every open prompt card.

- **Run modes.** The mode (`mode.rs`, `--mode both|runner|orchestrator`) hides the board side on a runner with a
  refusal (`ModeGated`). But nothing builds or runs without the orchestrator crates.
- **Core names features:** 11 crate edges and 145 lines in 20 files (section 3c). `infantry-daemon` links the board,
  mail, usage, knowledge, scripting, projects, the orchestrator API and the four concrete harnesses.
- **Plugins** come in two forms, WASM components and native sidecars (`infantry-plugin-host`). There are 14 official
  plugin folders. Most daemon halves are thin (`Infantry_Git` 11 lines, `Infantry_Health` 48, `Infantry_Docs` 64;
  AUDIT-1a A14), so the WIT seams are mostly unproven.
- **The plugin manager is core.** `compose/plugins.rs` (364 lines) holds discovery, the disabled list, plugin settings
  and `plugin_admin`. Install, update, remove, rollback and grants answer exit code 1: "this daemon serves only bundled
  modules" (`plugins.rs:120`).
- **The app compiles every screen in** (`app/shell/src/plugins/catalog.tsx`), and plugin UI is declarative only
  (section 4). #70 WEBUI-1, in progress, adds runtime web UI plugins, a slot API and a host seam.
- **The Tauri host carries a second session manager**, `app/src-tauri/src/local.rs` (729 lines; AUDIT-1a A1, #36).

AUDIT-1a section 5 is the input. It comes from ticket #2; the report is `docs/audit/AUDIT-1a-feature-gaps.md` on branch
`audit-1a-feature-gaps`, not merged.

| Finding | What it says | Where this plan takes it |
|---|---|---|
| A1 | The Tauri host runs its own session manager | #36; section 4e |
| A6 | The git view, the stale report and the worktree sweep are in core; `Infantry_Git` registers nothing | D-10 (the git view to `Infantry_Git`, the sweep to the orchestrator), D-17 |
| A10 | The agent and usage tools are "fallbacks" for plugins that don't exist | D-4 (explicit precedence), D-9, D-11 |
| A11 | Feature crates sit in L1 core (scripting, mail, usage) | D-8, D-9, D-11 |
| A12 | The board is a core crate and names a plugin id outside the guard's reach | D-10, D-16; the new guard covers every core crate (section 3c) |
| A14 | Most official plugins are shells | D-15 and D-19 prove the seams with fixture plugins; D-16 and D-17 with real ones |

Section 2a places A2 (Run), A5 (MCP), A7 (Health), A8 (Pods) and A9 (Account). A3, A4 and A13 are host and bridge
matters outside this plan.

What landed with #45:

| File | What it is | Run it with |
|---|---|---|
| `rust/crates/infantry-daemon/tests/decouple_red.rs` | The daemon-level RED test (section 3a), ignored | `cargo test -p infantry-daemon --test decouple_red -- --ignored` |
| `rust/crates/infantry-guards/src/features.rs` | What core may not name, today's baseline and the ratchet (sections 3c, 3d) | `cargo test -p infantry-guards` |
| `rust/crates/infantry-guards/src/licences.rs` | The licence line and the SPDX header check (section 7) | `cargo test -p infantry-guards` |
| `rust/crates/infantry-guards/tests/workspace.rs` | Four new tests: `licence_line_holds`, `every_rust_file_carries_its_crates_spdx_header`, `core_names_no_new_feature` (green, the ratchet) and `core_names_no_feature` (RED, ignored) | `cargo test -p infantry-guards -- --ignored core_names_no_feature` |

## 2. The target

```
boot     core shows the splash and reports each step (#89): core, installer, UI plugin, component plugins N/M
app      thin host: connection, plugin loader (#70, #86), host seam, theme, @infantry/ui, the registries
         ├─ UI plugin (#91): the shell and its LayoutProvider (#87); the default layout is today's look
         └─ component plugins' UI halves: agents, board, worktrees, git, usage, knowledge, installer, ...
                    │  v1 WebSocket, /ui-plugins/<id>/ (#85), plugin routes
daemon   core (MPL-2.0): the infantry-daemon library, infantry-core, infantry-protocol, the runners
           protocol envelope, FrameUnit registry, event bus, db, settings, vault, notification bus,
           ask-the-user (prompts, questions), tool catalog, project registry, runners, harness API,
           health probe, plugin host (loader + contracts), the DaemonModule seam
         builtin modules (native, Apache-2.0, listed as plugin folders)
           agents: sessions, harness registry, agent mail, agent tools        <- needs only core
           knowledge, usage, scripting
         loaded plugins (WASM or sidecar, Apache-2.0)
           installer (#90, the plugin manager); orchestrator: board, worktrees + merge + dispatch, pods;
           Git, Chat, Notes, ...
         distribution (infantry-distribution, Apache-2.0; it builds the infantry-daemon binary)
           holds main.rs, links the builtin modules and the harnesses, names the default plugin folder;
           keeps Run, MCP and Account until they become plugins
```

Rules of the target:

1. Core names no feature, no plugin id, no concrete harness and no role: `core_names_no_feature` is green.
2. Agents need only core. The orchestrator needs core and the agents' API. Agents never need the orchestrator (the
   guard "L3 never depends on L4" already holds this for crates).
3. Plugins cooperate only through core seams. The seams are frames (`FrameUnit`), events and hooks, the tool catalog,
   the daemon API (WIT imports), UI slots and web UI plugins (#70). No plugin imports another.
4. Bundled and installed plugins take one path. A bundled plugin is a folder in the default plugin directory, and no
   code asks whether a plugin is bundled.
5. Flavours are data. The default distribution and the shell-only flavour run the same binaries with different bundled
   folders.

### 2a. Every core unit and crate: what it becomes, its seam, its move

| Today | Lines | Becomes | Seam it uses | Move |
|---|---|---|---|---|
| `infantry-protocol`: the board, pod and worktree frame names (`frames.rs:61-63`, `133-184`, `147-149`, `254-256`) | | Owned by the orchestrator crates. The protocol keeps the envelope and the core frames. | Frame names declared by the unit that owns them | D-10 |
| `infantry-core` | 492 | Core. Gains `DaemonModule`, the tool catalog contract and the notification bus. | | D-3, D-4, D-8 |
| `infantry-plugin-host`, `-wit`, `-sdk` | 2,552 / 464 / 485 | The core loader (MPL, an owner call) and the Apache contracts | WIT | D-2 (LIC-2), D-15, D-19 |
| `infantry-runner-api`, `-runner`, `-runner-local`, `-runner-link`, `infantry-harness` | 711 / 1,730 / 1,393 / 2,478 / 1,741 | Core. `runner-api` becomes an Apache API leaf (an owner call). | | D-2 (LIC-1) |
| `infantry-vault` | 3,348 | Core | | |
| Sessions (`sessions.rs`, `sessions/`) and the harness registry (`harness/`) | 1,406 + 726 + 1,193 | The `infantry-agents` builtin module. The distribution builds the harness registry (LIC-4, LIC-5). | `DaemonModule` | D-6, D-9 |
| `compose/harness_frames.rs`, `compose/harness_install.rs` | | The agents module. The distribution registers the concrete harnesses. | Harness registration | D-6 |
| `compose/agent_frames.rs` | | Split. The agent registry goes to agents; queue, switch and start go to the board. | Frames | D-9, D-10 |
| `compose/agent_registry.rs` | | Agents | Role lookup through the role seam | D-7, D-9 |
| `compose/agent_tools.rs` | | Split. Agent and mail tools go to agents, board tools to the board, usage tools to usage. | Tool catalog | D-4, D-9, D-10, D-11 |
| `infantry-mail` agent mail (`mail` module, `MailUnit`) | part of 5,560 | Agents (agent mail) | | D-9 |
| `infantry-mail` notification bus (`notify`), the attention, notifications and notify units | | Core | | D-8 |
| The prompts and questions units | | Core (ask the user) | | stays |
| `infantry-board`, the board unit and its dispatcher | 5,510 | Orchestrator: the board plugin (a native module at D-10, WASM at D-16) | Frames, storage, events, hooks, timers | D-10, D-16 |
| `pods.rs`, the pods unit (A8) | 790 | Orchestrator: pods | Frames, events | D-10 |
| The worktrees unit, `compose/worktree_sweep.rs`, `infantry-projects::{worktree, ticket_branches, stale}`, `infantry-orchestrator-api` | | Orchestrator: worktrees, merge and dispatch (a sidecar at D-17) | Frames, timers, processes | D-10, D-17 |
| The git unit, `infantry-projects::{git, service, changes}` (A6) | | `Infantry_Git` | Frames, processes | D-10 |
| The projects unit, `infantry-projects::{store, wire, paths}` | | Core: the project registry | | D-10 (crate split) |
| `compose/adapters.rs` | | Splits with what it adapts. The board-to-agents adapters go to the orchestrator. | Agents API | D-7, D-9, D-10, D-11 |
| `compose/watch.rs` | | Orchestrator | Events | D-9, D-10 |
| `infantry-knowledge`, the knowledge units | 3,124 | The knowledge module (roles, skills, briefs). The PM role moves to the orchestrator's role pack. | Role capability data | D-7, D-11 |
| `infantry-usage`, the usage unit | 5,240 | The usage module (pacing, the governor, usage tools) | Model-call seam | D-11, D-21 |
| `infantry-scripting`, the scripts and timeline units | 5,995 | The scripting module (Lua, hooks) | Hook delivery | D-4, D-11, D-15 |
| `compose/tools.rs` (the tools unit) | | Core: the tool catalog | Tool catalog contract | D-4 |
| `compose/scope.rs` | | Core. The list of spawning frames and the `"pm"` checks become declared data. | Role capabilities; spawning frames declared by their unit | D-7 |
| `fresh_schema.rs`, `build.rs` (the Kotlin SQLDelight tree) | | Core creates only its own tables; each module brings its schema | `DaemonModule::schema` | D-5 |
| `health.rs` (A7) | 847 | The core health probe, with a generic working folder; `Infantry_Health` later | | D-7 |
| `run/`, the Run unit (A2) | 1,574 | The distribution at D-12, then `Infantry_Run` | | D-12, later |
| `mcp.rs`, the MCP unit (A5) | 814 | The distribution at D-12, then `Infantry_Mcp` | | D-12, later |
| `account.rs`, the account unit (A9) | 587 | The distribution at D-12, then an account plugin | | D-12, later |
| `compose/plugins.rs`, `compose/host_frames.rs` | 364 + | The loader and the contracts stay core; the policy and the admin move to the installer plugin (#90) | Plugin admin contract | D-14 |
| `held.rs`, `gateway/legacy.rs`, `gateway/routes.rs` (A13) | | The Kotlin bridge, removed after cutover | | outside this plan |
| `app/src-tauri/src/local.rs` (A1) | 729 | Removed, or replaced by the daemon | | #36 |

### 2b. The seams the moves rest on

**DaemonModule (D-3).** A native module sits behind a trait in `infantry-core`. The proposed shape (owner OK needed):

```rust
pub trait DaemonModule: Send + Sync {
    fn id(&self) -> &str;                                   // its module.json name
    fn schema(&self) -> &[Migration] { &[] }                // D-5
    fn units(&self, cx: &ModuleCtx) -> Vec<Box<dyn FrameUnit>>;
    fn tools(&self, cx: &ModuleCtx) -> Vec<Arc<dyn ToolProvider>> { Vec::new() } // D-4
    fn hello(&self, cx: &ModuleCtx) -> Vec<Envelope> { Vec::new() }              // the connection burst
    fn start(&self, cx: &ModuleCtx) -> Result<(), String> { Ok(()) }
    fn stop(&self) {}
}
```

- `ModuleCtx` hands a module the db, the event bus, settings, the vault, the notification bus, the project registry and
  a typed service registry.
- The orchestrator finds the agents' API through that registry (`cx.services.get::<dyn AgentsApi>()`), so core never
  names it.
- Modules start in a declared order (the `sequence` field of `module.json`), which reproduces today's unit order.

**Two plugin forms.**
- A **builtin module** is a native crate linked into the distribution. A `module.json` lists it with
  `"daemon": {"builtin": "<id>"}` (D-13), so it is enabled, disabled and shown like any plugin. Agents stay builtin:
  they run native sessions, PTYs and child processes, and the shell-only flavour always has them.
- A **loaded plugin** is a WASM component or a sidecar. The orchestrator must become loadable (D-16, D-17): the
  shell-only proof installs it from a source, and a builtin module can't be installed without a rebuild.

**The tool catalog (D-4).** `ToolInfo`, `ToolInvoker` and `ToolResult` move from `infantry_scripting::host` into core.
A module contributes providers. The catalog orders them by a declared precedence instead of their place in a list:
today `compose/agent_tools.rs:1-6` puts board tools first, plugin tools next, and agent and usage tools last as
fallbacks.

**The work context (D-7).** A session carries an opaque `context` owned by whoever started it. The board fills it with
the ticket and the worktree; core shows the working folder and reads nothing else.

**Role capabilities (D-7).** A role declares what it may do, as `capabilities` data in its role pack. Core and modules
check a capability, never a role id. The PM role leaves the built-in roles and comes with the orchestrator's role pack.

**Module profiles (D-3).** `Mode` becomes a profile: `runner` loads no orchestration module, and those frames answer
the same refusal as today. Mail on a runner stays gated until #53 RUNNER-1 decides otherwise.

## 3. The RED lists

### 3a. RED test: a daemon with no orchestrator

`agents_run_and_talk_with_no_orchestrator_installed` composes today's daemon with an empty plugin folder and a harness
test double (`Recorder`, no real CLI). It checks two halves:

- **The orchestrator is absent.** None of its 26 frames has an owner, the connection burst sends none of them, the
  agents' tool catalog holds none of its 12 tools, a fresh `infantry.db` holds none of its 17 tables, and the role
  list has no `pm`.
- **Agents work.** Two sessions start through the double. A sends B "DECOUPLE ping from A" and B answers "DECOUPLE pong
  from B", over `mail_send`, and each reads its message with `mail_inbox`.

Today the agents half passes and the test lists 57 breaks:

| Kind | Count | Breaks |
|---|---|---|
| Frames served by a core unit | 26 | **board (18):** `ticket_list`, `ticket_get`, `ticket_save`, `ticket_move`, `ticket_delete`, `ticket_link`, `ticket_unlink`, `ticket_reorder`, `epic_save`, `epic_list`, `epic_base`, `ticket_start`, `ticket_start_next`, `ticket_assign`, `ticket_unassign`, `agent_queue`, `agent_switch`, `ticket_done`. **Pods (5):** `pod_submit`, `pod_query`, `pod_cancel`, `pod_list`, `pod_review`. **Worktrees (3):** `worktrees`, `worktree_remove`, `repo` |
| The connection burst | 1 | `worktrees` |
| Tools in the agents' catalog | 12 | `infantry_ticket_create`, `infantry_agent_switch`, `infantry_agent_queue`, `infantry_epic_base`, `infantry_ticket_get`, `infantry_ticket_update`, `infantry_ticket_move`, `infantry_ticket_start`, `infantry_ticket_comment`, `infantry_ticket_comments`, `infantry_board`, `infantry_pod_state` |
| Tables in a fresh db | 17 | `archived_ticket_links`, `archived_tickets`, `epics`, `node_attempts`, `node_dependencies`, `pipeline_checks`, `pipeline_config`, `pipeline_entries`, `pod_nodes`, `pods`, `ticket_event_cursors`, `ticket_events`, `ticket_files`, `ticket_files_read`, `ticket_links`, `ticket_runs`, `tickets` |
| Roles | 1 | `pm` |

Which moves clear them:
- the frames, the burst and the tools: D-10 (with D-4 for the tools);
- the tables: D-5;
- the role: D-7.

The test turns green at D-13. That move points the test's plugin folder at a folder that holds only the agents module.
That is the setup the test means ("agents installed, no orchestrator"), not a change made to get a pass. D-13 also
removes the `#[ignore]`, and the test then stays as the EPIC #41 guard.

Two servers answer `infantry_message` today: core's fallback in `compose/agent_tools.rs`, and the daemon half of
`Infantry_Chat`, which wins when it is loaded. D-9 keeps one of them (section 11).

### 3b. Compile break: the daemon without the orchestrator crates

Removing `infantry-board` and `infantry-orchestrator-api` from `infantry-daemon/Cargo.toml` gives 16 errors (E0432 and
E0433) in 7 files. I measured this on 2026-11-20, then restored both dependencies; none of it is committed.

| File | Lines |
|---|---|
| `compose/adapters.rs` | 9, 10, 13 |
| `compose/agent_frames.rs` | 17 |
| `compose/agent_tools.rs` | 8, 10, 57, 99, 153, 165, 166 |
| `compose/watch.rs` | 10 |
| `compose/worktree_sweep.rs` | 11, 12 |
| `compose/mod.rs` | 33 |
| `fresh_schema.rs` | 19 |

### 3c. Core still names features

`core_names_no_feature` (RED, ignored) scans the shipped code of the core crates: `src/` and `build.rs`, skipping
comment lines and the trailing `#[cfg(test)] mod`.

**What counts as a feature:**
- the feature crates: board, orchestrator API, mail, scripting, usage, knowledge, projects, and the four concrete
  harnesses;
- the feature terms:
  - the paths of those crates, and `MailUnit`;
  - the git view, worktree, ticket-branch and sweep modules of `infantry-projects`;
  - the harness names `"claude"`, `"codex"` and `"opencode"`;
  - any plugin id (`Infantry_`) and the PM role (`"pm"`);
  - the orchestrator's wire names as string literals (`"ticket_`, `"epic_`, `"pod_`, `"worktree`).

**What does not count:** the notification bus (`infantry_mail::notify`) and the project registry
(`infantry_projects::{store, wire, paths}`). They stay core.

Today the scan finds 11 crate edges (from `infantry-daemon` to every feature crate) and 145 lines in 20 files. Paths
below are under `rust/crates/`.

| File | Terms (lines) | Cleared by |
|---|---|---|
| `infantry-protocol/src/frames.rs` | `"worktree` (61-63); `"ticket_` (133, 134, 170-174, 176, 178-181, 184); `"pod_` (147-149); `"epic_` (254-256) | D-10 |
| `infantry-daemon/src/compose/adapters.rs` | `"pm"` (37); board (9, 10); knowledge (560); agent mail (11); orchestrator API (13); ticket branches (16); worktree (335); scripting (77, 96); usage (21-23) | D-7, D-9, D-10, D-11 |
| `.../compose/agent_frames.rs` | board (17); agent mail (19) | D-9, D-10 |
| `.../compose/agent_registry.rs` | knowledge (7) | D-9 |
| `.../compose/agent_tools.rs` | `"pm"` (95); board (8, 57, 99, 153, 165, 166); agent mail (33, 300, 305, 321, 329); orchestrator API (10); scripting (12); usage (13, 404, 433) | D-4, D-7, D-9, D-10, D-11 |
| `.../compose/harness_install.rs` | `"claude"` (36); `"codex"` (37); `"opencode"` (38) | D-6 |
| `.../compose/mod.rs` | `MailUnit` (46, 217, 523); board (33); knowledge (100, 300, 306, 768); agent mail (37-39, 199, 768); git view (47, 48); scripting (50-56, 381, 393, 399, 405, 412, 425, 444, 456, 466); usage (57, 58, 190, 194) | D-3 to D-12 |
| `.../compose/scope.rs` | `"pm"` (23); `"ticket_` (27) | D-7 |
| `.../compose/timeline.rs` | scripting (11) | D-11 |
| `.../compose/tools.rs` | knowledge (26, 40, 103, 161, 162); scripting (9, 10) | D-4 |
| `.../compose/watch.rs` | agent mail (8, 53); orchestrator API (10) | D-9, D-10 |
| `.../compose/worktree_sweep.rs` | `"worktree` (21-23, 27, 30); board (11); orchestrator API (12); the sweep (13) | D-10 |
| `infantry-daemon/src/fresh_schema.rs` | board (19) | D-5 |
| `infantry-daemon/src/harness/mod.rs` | the four harness crates (176, 181-184, 200) | D-6 |
| `infantry-daemon/src/health.rs` | `"worktree` (569, 641, 647) | D-7 |
| `infantry-daemon/src/pods.rs` | `"pod_` (22-26, 692, 700, 713, 714) | D-10 |
| `infantry-daemon/src/run/mod.rs` | `"worktree` (807) | D-12 (Run leaves core) |
| `infantry-daemon/src/run/prep.rs` | `"worktree` (161, 180, 191); git view (12, 144) | D-12 |
| `infantry-daemon/src/run/tools.rs` | scripting (7) | D-4 |
| `infantry-daemon/src/wire.rs` | `"worktree` (13); `"ticket_` (36) | D-3, D-10 |

The guard turns green at D-12, when the composition root's feature wiring moves into `infantry-distribution` and the
`infantry-daemon` library keeps only core. D-12 removes its `#[ignore]` and empties the baselines.

### 3d. The ratchet: no new feature enters core

`core_names_no_new_feature` is green and runs in every `cargo test -p infantry-guards`. It holds core to the baseline in
`features.rs`: `BASELINE` per file, `EDGE_BASELINE` per crate edge.

- **Fails:** a new file that names a feature, a new term in a listed file, or a new core-to-feature edge.
- **Passes:** more lines of a listed term in the same file. The ratchet is per (file, term), not per line.
- **Fails when stale:** an entry that no longer applies. The move that clears a file, a term or an edge deletes its
  entry in the same change.
- **Splits:** a move that splits a core file may carry the entry to the new file, with the same terms or fewer, and
  says so in its commit. No move adds a term.

This is what enforces "no new feature is built into core after #45 and #70". UI-2 brings the same rule to the app, as
a ratchet on the built-in screens.

Effect on open tickets: #5 PM-ENFORCE-1 cannot add a `"pm"` check to a new core file. It must check a role capability
(D-7) or live in a module.

## 4. The UI map

The roots and their sizes:
- `app/shell/src`: 13,403 lines;
- `app/packages/ui/src`: 2,219 lines;
- `app/packages/client/src`: 7,681 lines, plus 2,017 lines of tests.

Every part gets one of five classes:
- **host** stays in the thin host;
- **layout** moves to the layout plugin;
- **agents** moves to the agents UI half;
- **orch** moves to the orchestrator plugins' UI halves;
- **other** moves to its own plugin's UI half.

### 4a. Classification

`app/shell/src`. A folder with no prefix is under `app/shell/src/components/`, except `plugins/`, `state/`, `daemon/` and
`mock/`, which sit in `app/shell/src/` beside `main.tsx` and `App.tsx`.

| Group | Lines | Class | Goes to |
|---|---|---|---|
| `main.tsx`, `App.tsx` (startup, splash, pairing gate) | 70 | host | host. The splash and the boot progress stay core (#89 BOOT-SPLASH-1). |
| `daemon/` (finding the daemon, transports, perf scenario) | 296 | host | host |
| `daemon/agents.ts`, `daemon/use-timeline.ts`, `mock/types.ts` (the real `Agent` types, despite the name) | 320 | agents | UI-8 |
| `state/ui-state.tsx` (navigation and agent selection in one context) | 143 | layout + agents | split at UI-3 |
| `plugins/` (catalog, use-plugins, plugin-fallback) | 235 | layout | The registry stays host (UI-2). The fallback stays host. |
| `layout/` (frame, title bar, status bar, shortcuts, window controls) | 689 | layout. Exceptions: `toast-host`, `connection-badge` and `app-version` are host; `project-picker` is orch until UI-4. | UI-6 |
| `tabs/` (centre outlet, `TabPage` chrome) | 211 | layout. Exceptions: `board-tab` is orch; `list-tab.tsx` has no importer. | `TabPage` to `@infantry/ui` (UI-1), the rest UI-6 |
| `session/` (20 files: chat, transcript, composer, prompt cards, popouts) | 2,268 | agents. Exceptions: `session-view.tsx` and `session-tab-bar.tsx` are layout. | UI-8, UI-6 |
| `agents/` (sidebar, cards, avatars, global workers) | 921 | agents | UI-8 |
| `dialogs/` | 586 | agents (new-agent, executor, resume). Exceptions: `dialog-host` is layout; `settings-body` is other. | UI-3, UI-8 |
| `mail/` (the mail tab, the mail and attention store setup) | 245 | agents | UI-8 |
| `roles/` (persona, traits, model pin; a `"pm"` id at `agents/role-icon.tsx:14`) | 408 | agents | UI-8 |
| `board/` (lanes, tickets, the ticket dialog) | 461 | orch | UI-7 |
| `git/` (git tab, diff, worktrees, the Changes panel, the projects store) | 678 | orch | UI-7; the current project moves to a scope store at UI-4 |
| `panels/` | 698 | orch (epics, pods, review). Profiles, MCP, Services, Sites and the Settings page are other. | UI-7, UI-10 |
| `usage/` | 555 | other (usage). Exceptions: `right-panel.tsx` is layout; `attention-panel` is agents. | UI-10 |
| `run/`, `scripts/`, `triggers/`, `health/` | 473 / 382 / 278 / 138 | other | UI-10; Triggers moves with #70 |
| `knowledge/`, `library/` | 546 / 373 | other. `knowledge/parts.tsx` is generic host code. | UI-10; `parts.tsx` to `@infantry/ui` (UI-1) |
| `components/plugins/`, `settings/`, `stats/`, `setup/` | 190 / 770 / 946 / 311 | other. `settings/desktop-install.tsx` is the host updater. | UI-9 (the installer, #90), host, UI-10 |
| `connect/` (phone pairing) | 209 | host | host |

`app/packages/ui` is all host: 18 shadcn primitives, the blocks and the theme tokens. Its one domain leak is the
agent status vocabulary in `types.ts:5`.

`app/packages/client` splits like this:

| Group | Lines | Class |
|---|---|---|
| wire, connection, index, markdown, toasts, pairing, update, tools, stress | 1,015 | host |
| `layout.ts`, `shortcuts.ts` | 142 | layout |
| store, transcript, cards, images, popout, chat-view, worker-lanes | 1,016 | agents |
| `setup-wizard.ts` | 156 | other (setup) |
| `stores/` (25 files) | 5,352 | per store; see 4c |

### 4b. What blocks a layout swap today

1. **A fixed screen table.** There is no router: the "route" is the `ui.sessionTab` string.
   - `shell/src/plugins/catalog.tsx` mounts every screen from fixed arrays: CENTRE at `:81-102` (20 entries), RIGHT at
     `:105-113` (7), and BOARD at `:126`, outside both. It statically imports 25 screens from 15 feature folders
     (`:39-59`), and none of them has another importer.
   - A plugin can only show, hide or reorder entries by their declared ids (`tabsFor`, `:137-149`).
   - `prompts` and `skills` appear in both containers; `entryFor` (`:154`) picks the centre one.
   - The CLI entry is a placeholder (`render: () => null`, `:82`), and `session/session-view.tsx:30-34` hard-codes `cli`
     to `FullChatPanel`.
2. **A fixed frame.** It is one JSX tree, `layout/app-shell.tsx:55-80`.
3. **Closed dialogs and shortcuts.**
   - Dialogs are a closed union (`state/ui-state.tsx:9`) and a switch (`dialogs/dialog-host.tsx:32-77`).
   - Ctrl+digit shortcuts match the catalog's `hint` strings (`layout/use-shortcuts.ts:75-91`), and Ctrl+8 opens the
     Board in hard-coded code (`:76-78`).
4. **The layout imports features.**
   - `app-shell.tsx:5,9-12,18-19` imports the global workers host, the agent sidebar, the session view, the right
     panel, the dialog host, the popout host and auto-setup.
   - `title-bar.tsx`: the attention store (`:7`), the avatar strip (`:12`), the project picker (`:14`, which reads the
     git store at `project-picker.tsx:16`), and the new-ticket and new-agent dialogs (`:31`, `:34`).
   - `status-bar.tsx:6,12` (attention, runs) and `use-shortcuts.ts:6,10,46` (the agent list, the session dialogs, and a
     raw `kill` frame).
   - `session-view.tsx:6,11,12`, and `right-panel.tsx:6`, which special-cases the `attention` id at `:25`.
   - `dialog-host.tsx:9-15` (board, agents, plugins, settings, stats, setup).
5. **Features import the layout.**
   - `agents/agent-sidebar.tsx:19` takes the search id from use-shortcuts, and `dialogs/settings-body.tsx:12` imports
     app-version.
   - 16 feature files import `TabPage` from `tabs/tab-page.tsx`.
   - Features write navigation state with raw `ui.set` calls: `agent-sidebar.tsx:45,83`, `agent-menu.tsx:68`,
     `sidebar-counters.tsx:39`, `global-workers.tsx:57-58`, `composer.tsx:233`, `session-header.tsx:58`,
     `settings-body.tsx:98`, `declared-settings.tsx:50`, `setup-wizard.tsx:91`, `status-bar.tsx:23`.
6. **Features import each other.**
   - agents → board: `agent-menu.tsx:27,29`, `sidebar-counters.tsx:12`;
   - agents and session → the git store: `agent-menu.tsx:31`, `global-workers.tsx:11`, `composer.tsx:42`,
     `daemon/agents.ts:10`;
   - board → session, dialogs and panels: `ticket-comments.tsx:10`, `ticket-dialog.tsx:11-12,16`;
   - roles → usage: `roles-tab.tsx:14`;
   - 13 files import `knowledge/parts.tsx`.
7. **Package directions.**
   - `packages/ui` imports only react, radix-ui, cn, cva and lucide.
   - `packages/client` imports only `zustand/vanilla`, with two problems. `wire.ts:72-77` imports types from
     `../../../src/generated/*`, outside its package. And `index.ts:4-45` does not export `stores/link.ts`, so the
     `FrameLink` type that every store factory takes has no public name.

### 4c. Shared state

- `state/ui-state.tsx:14-34` mixes two kinds of key in one context, which 22 files read through `useUi()`:
  - navigation: `leftOpen`, `rightOpen`, `sessionTab`, `rightTab`, `dialog`, `theme`;
  - agent selection: `selectedAgentId`, `roleFilter`, `search`, `chatMax`, `assistantOpen`, `popouts`, `workersOpen`,
    `globalWorkers`.
- Every client store is a vanilla zustand factory. The shell creates each one as a module-level singleton in a feature
  setup file:

| Store | Lines | Created at (`app/shell/src`) | Read by |
|---|---|---|---|
| attention | 201 | `mail/use-mail.ts:25` | layout (title, status), usage, right panel, `desktop-install.tsx:41,56` |
| board | 224 | `board/use-board.ts:11` | board, tabs, agents |
| harness-install | 31 | `setup-wizard.tsx:43` | setup |
| health | 114 | `plugins/use-plugins.ts:30` | health |
| knowledge | 302 | `knowledge/use-knowledge.ts:29` | knowledge |
| launcher | 139 | `dialogs/new-agent-dialog.tsx:30` | dialogs, session (`session-actions:14`), setup |
| library | 240 | `use-knowledge.ts:37` | library |
| link | 39 | (a helper, not exported) | every store |
| mail | 174 | `use-mail.ts:21` | mail |
| pace | 490 | `stats/use-pace.ts:13` | stats |
| panels | 239 | `panels/use-panels.ts:27-31` | panels, board |
| plugins | 237 | `use-plugins.ts:29` | plugins, layout, tabs, settings, health, triggers |
| projects | 479 | `git/use-projects.ts:12` | git, layout, agents, session, daemon, knowledge, library, run, scripts, board |
| prompts | 100 | `session/use-prompts.ts:12` | session, `main.tsx:39` |
| query | 95 | (generic) | panels, setup |
| roles | 270 | `use-knowledge.ts:33` | agents, dialogs, roles, library, panels |
| runs | 301 | `run/use-runs.ts:12` | run, status bar |
| scripts | 270 | `scripts/use-scripts.ts:13` | scripts |
| secrets | 207 | `settings/secrets-panel.tsx:14` | settings |
| session-actions | 149 | `session/session-actions.tsx:28` | session, agents, use-shortcuts, stats |
| settings | 190 | `settings/use-settings.ts:11` | settings, panels, plugins |
| stats | 339 | `stats/stats-views.tsx:25` | stats |
| timeline | 70 | `daemon/use-timeline.ts:12` | session header |
| triggers | 150 | `use-plugins.ts:31` | triggers |
| usage | 302 | `usage/use-usage.ts:13` | usage, stats, roles |

Four places mix agent data with orchestrator data:
- `client/src/store.ts:80-89`: the daemon state holds tickets and projects beside sessions and agents. Its `ready`
  handler asks for the agent, project and ticket lists (`:217-219`), and it reduces ticket and project frames
  (`:260-274`).
- `shell/src/daemon/agents.ts:13-21,74`: an agent's "review" status comes from its ticket's board lane, and `:122`
  filters agents by the git store's current project.
- `client/src/stores/panels.ts` bundles epics (`:34`), the agent registry (`:68`), MCP (`:125`), pods (`:184`) and
  scoped settings (`:220`) in one file.
- `client/src/stores/projects.ts:169`: the global "current project" (`currentId`) lives inside the git and worktree
  store.

### 4d. What #70 brings, and what it does not

Today's plugin UI seam is declarative only. The daemon's plugin list goes through `parsePlugin`, which reads the
`module.json` `ui` block (tabs, panels, fills, slots, config, configNodes; `client/src/stores/plugins.ts:23-33,92-115`),
then `slotRegistry` (`:140-160`), then `useRegistry`, then `tabsFor`. The limits:
- A plugin tab can only switch on a renderer already compiled into the shell. Anything else gets `PluginFallback`,
  which says the view "has no view in this app yet" (`plugin-fallback.tsx:18`).
- Panels are parsed but never rendered (`plugin-manager.tsx:21`).
- Fills have one consumer, `status-bar.tsx:19`.
- `registerChatCard` (`session/chat-cards.tsx:12-20`) has no caller.

#70 WEBUI-1 (design comment on #70, 2026-11-20) brings:
- the manifest's `ui` half and `/ui-plugins/<id>/<file>` serving, plus a `plugins_changed` push;
- one host-agnostic loader in `@infantry/client`: it imports a blob, checks the API version and calls `activate(api)`;
- `@infantry/ui-plugin-api` (`UI_API_VERSION = 1`):
  - the slots `tab`, `panel`, `settings`, `config`, `badge` and `chatCard`;
  - the daemon link, the theme tokens, and the host's `TabPage`, `Field` and `RefusalBanner`;
  - a declared list of `@infantry/ui` components, and a build helper that maps React and `@infantry/ui` to the
    host's copies;
- error boundaries and fault cards;
- the host seam: `client/src/host.ts`, with `shell/src/host/` as the only place that imports `@tauri-apps`, enforced
  by a guard;
- the Triggers tab as the reference plugin screen.

It brings no layout seam. #82 LAYOUT-1 assumes #70 adds a `LayoutProvider`, and #70's design does not. UI-6 adds it,
and #82 builds on UI-6.

### 4e. The Tauri host and `local.rs` (#36)

The Tauri host embeds its own session manager, through `infantry-harness-claude` and `infantry-runner-local`:
- it runs sessions, turns and replay frames, with no mail, no board and no vault;
- the shell reaches it with the Tauri commands `local_connect`, `local_send`, `local_set_project` and
  `local_resumable` (`shell/src/daemon/local-transport.ts:19,26,49,50`, used by `dialogs/resume-body.tsx:22,30`).

For the decoupled app it is a second engine, and a host that names a concrete harness: it breaks rule 1 at the host.
#36 removes it or runs the daemon as the one engine. Until then it stays behind #70's host seam (`shell/src/host/`), and
no move here builds on it.

The other Tauri commands are host features and stay in the host: `endpoint`, `perf_report`, `remote_save` and
`remote_forget`, `ui_runner_get` and `ui_runner_set`, and the updater, CLI-path and pairing commands in
`settings/desktop-install.tsx`.

### 4f. The UI moves

| Move | What it does |
|---|---|
| UI-1 | Shared parts move into `@infantry/ui`, and the package directions are fixed. |
| UI-2 | Built-in screens register like plugin components, with no position and no order (#84 WEBUI-2), and a ratchet blocks new built-in screens. |
| UI-3 | Navigation, dialogs and shortcuts become registries, and `ui-state` splits. |
| UI-4 | One store hub, the mixed stores split, and the current project leaves the git store. |
| UI-5 | Feature-to-feature and layout-to-feature imports become slots. |
| UI-6 | The `LayoutProvider` seam and the default layout (#87 WEBUI-5), loaded as the UI plugin by the boot chain (#91 BOOT-CHAIN-1). With no UI plugin, the installer's bootstrap screen shows; there is no fallback strip. |
| UI-7 to UI-10 | The screens move into their plugins' UI halves (orchestrator, agents, the installer, the rest). |

The PM filed the boot and UI tickets from the owner's boot model: #83 OS-DOC-1 (the architecture page), #84 to #88
WEBUI-2 to WEBUI-6, #89 BOOT-SPLASH-1, #90 INSTALLER-1, #91 BOOT-CHAIN-1 and #92 SHELL-ONLY-1. Details are in
section 11.

## 5. Plugin management as a plugin

This plugin is the installer (#90 INSTALLER-1): the bootstrap that core loads when nothing is installed, and the plugin
manager after that. It is one plugin, grown from `Infantry_Plugins`, not a second one beside it.

What sits in core today, in `compose/plugins.rs` and `compose/host_frames.rs`:
- **Discovery:** the bundled folder and `INFANTRY_PLUGINS_DIR`.
- **State files** in the state dir: `plugins-disabled.json`, `plugin-settings.json`, and `agent-overrides.json`
  (`host_frames.rs:42`).
- **Frames:**
  - `plugins_op` (the list) and `modules.changed`;
  - `plugin_admin` (`{"args":[op,id?]}` answers `{code,out,err}`). Enable and disable work; install, update, remove,
    rollback and grants answer exit code 1.
- **Limits:** the host is built with default limits (`plugins.rs:295`).
- **UI:** `shell/src/components/plugins/` and parts of the settings screens.

The split:

| Stays core: the loader and the contracts | Moves to the installer plugin |
|---|---|
| Reading and checking `module.json`, tiers and grants | Which folders to scan, and in what order |
| Loading WASM, sidecar and builtin modules; faults and limits | Enable and disable (the state it writes) |
| The plugin contracts: `plugin_tools`, `plugin_tool_call`, `plugin_secret_get`, routes | Install, update, remove and rollback from a source (#69) |
| `modules.changed` and the reload | Overriding an official plugin (#46) |
| Storing plugin settings (core settings, read through the `settings` import) | The Plugins screen, the marketplace UI, and the settings cards (#46) |

The contract between the two (owner OK needed, D-14):
- The installer writes one state file: the enabled set, the folder chosen for each id, and pinned versions. The
  loader reads it at boot and on `modules.changed`.
- With no state file, the loader loads every folder in its directories. That is what a bundled install does today,
  so there is no bundled special case.
- Only the installer holds the `plugins.admin` grant, which gives it the reload call.
- With nothing installed, core loads the installer, and the app shows its bootstrap screen: the one new screen in this
  plan (#90, #91). The shell-only build is core plus the installer (#92).

The installer grows from `Infantry_Plugins` (101 lines today). Its code leaves core, so it is Apache-2.0.

**Status (INSTALLER-1 #90, built).** `Infantry_Installer` is a new plugin beside `Infantry_Plugins` (which keeps the agent
tools); the two can merge later. Two things differ from the split above, and both are for the owner to confirm:
- A WASM plugin cannot copy files or load another plugin, so the *mechanism* is a generic core seam, `compose/installer.rs`:
  sources, the `requires` order, copy into `<plugins>/<tier>/<id>`, late frame registration, and the `plugin_admin` ops
  `available`, `install`, `uninstall`. The *policy and the screen* are the plugin's. Core names no plugin id: the bootstrap
  plugin is the `plugins.bootstrap` setting, `INFANTRY_BOOTSTRAP_PLUGIN`, or a manifest `"bootstrap": true`.
- The installer does not hold a `plugins.admin` grant yet. Its web half drives the owner's own `plugin_admin` frames; its
  daemon half only reads. The grant belongs with the fix for the security note below.

See [Plugins](../wiki/Plugins.md#the-installer-and-the-bootstrap-plugin).

**Security note (filed as a reproduce-first ticket, section 11).** A plugin that holds the `frames` import can read
another plugin's secrets:
- Plugin frame requests run as the owner: `compose/plugins.rs:192` ignores the calling module, and `:210` dispatches
  with `owner: true` (`compose/mod.rs:723-725`).
- `plugin_secret_get` is owner-only (`infantry-vault/src/unit.rs:57-75,163`), so the plugin passes that check. The
  frame then takes the module name from the request body (`infantry-vault/src/handler.rs:418-432`).
- Ten official plugins declare `frames`; a community plugin needs the owner's `daemon.import.frames` grant.

D-14 and D-19 depend on the fix: a frame request carries the calling module, and module-scoped frames answer only that
module.

## 6. Plugin ABI gaps

The WIT world today (`rust/crates/infantry-plugin-wit/wit/daemon-plugin.wit:166-190`):
- **Imports:**
  - `log`, `config`, `settings`;
  - `storage`: key-value and SQL, with a per-module table prefix enforced by the authorizer at
    `infantry-plugin-host/src/imports.rs:160-163`;
  - `events` (publish, subscribe, broadcast), `http` (send) and `frames` (request).
- **Exports:**
  - `start`, `stop`, `call-tool`, `handle-frame`;
  - `handle-route` (the daemon serves plugin routes, `infantry-daemon/src/server.rs:252`);
  - `on-hook`, `on-event`.

| Gap | Evidence | Needed by | Move |
|---|---|---|---|
| Hooks never reach a plugin | Only the health probe calls `on-hook` (`infantry-plugin-host/src/host.rs:71`), so the `daemon.hooks` in `module.json` are dead | the orchestrator, #43 AUTO-1 | D-15 |
| No timers or schedules | The dispatcher and the worktree sweep run on daemon timers | board, worktrees, Home, Guard, Shield | D-15 |
| No frame-name ownership | Nothing stops a plugin from claiming a core frame name | every plugin | D-15 |
| The limit settings are ignored | `PluginHost::new(services.clone(), Default::default())` (`compose/plugins.rs:295`), while `docs/wiki/daemon-plugin-api.md:66` promises `limits.plugins.*` | every plugin | bug ticket; fixed in D-15 |
| A tool's `action` and `kind` are not enforced per call | `kind` only sets `read_only` and the MCP `readOnlyHint` (`compose/tools.rs:138`, `:67`); `action` maps a capability to a tool (`infantry-plugin-host/src/manifest.rs:48`) and is shown (`compose/tools.rs:91`); no call checks either | approvals, Shield | D-15 |
| Secrets are not bound to the module | Section 5, security note | every plugin that keeps a secret | security ticket, D-19 |
| No `secrets`, `notify`, `ask`, `process`, `files` or `tools.call` import | The WIT world above | Home, Guard, Games, Shield, app builders | D-19 |
| No background work, and the sidecar SDK serves one call at a time | `infantry-plugin-sdk/src/sidecar.rs:3-4` | Guard, Home | D-20 |
| A plugin half cannot say where it runs | No placement field in `module.json` | runners (#53), device plugins | D-20 |
| No model-call seam | A plugin cannot call a model through the usage governor | Guard, Shield, any AI plugin | D-21 |
| No Rust template and no SDK test host | `plugins/_template` and `templates/plugin-repo` are Kotlin/Gradle; `infantry-plugin-sdk/src` holds only `host.rs`, `lib.rs` and `sidecar.rs` | app builders | D-22 |
| Core embeds the feature tables | `infantry-daemon/build.rs:10` embeds the Kotlin SQLDelight tree | a removable orchestrator | D-5 |

## 7. The licence line

The rule comes from `docs/LICENSE-MAP.md` section 1: core is MPL-2.0; the SDK, the APIs, the features and the plugins
are Apache-2.0. On normal and build dependencies:
1. An MPL crate depends on no Apache crate except the API crates: `infantry-plugin-wit`, `infantry-plugin-sdk`,
   `infantry-harness` and `infantry-orchestrator-api` (`API_CRATES` in `licences.rs`).
2. An API crate depends on no MPL crate, so whoever builds against the SDK is never pulled onto MPL terms.

An Apache feature may use MPL core, because MPL is file-level.

The guards are the Rust port of the Kotlin `DaemonCoreGuardTest`, `HarnessCoreGuardTest` and `LicenseMapGuardTest`:
- `licence_line_holds` fails on a new crossing;
- `every_rust_file_carries_its_crates_spdx_header` fails on a file whose SPDX header differs from its crate's
  `license`.

Today's exceptions, each with the move that removes it. Both lists only shrink:

| Exception | Rule | Removed by |
|---|---|---|
| `infantry-daemon` → `infantry-board` | 1 | LIC-6 to LIC-10: the board wiring becomes a module in its own Apache crate, which the distribution registers |
| `infantry-daemon` → `infantry-harness-claude`, `-codex`, `-opencode`, `-pi` (4 crossings) | 1 | LIC-4 and LIC-5: the distribution builds the harness registry |
| `infantry-daemon` → `infantry-plugin-host` | 1 | LIC-2. The plugin host is part of the kernel in the owner's boot model, but its crate is Apache. Proposed: MPL-2.0. |

Edges the line allows, so no move removes them:
- **Apache to MPL:** `infantry-board` → `infantry-core` and `infantry-protocol`; `infantry-conformance` →
  `infantry-protocol`; `infantry-plugin-host` → `infantry-core` and `infantry-proc`. LICENSE-MAP section 1 says "Apache
  code may depend on MPL code"; only the SDK and the API crates must import nothing MPL. The owner's S3 and S13 keep the
  protocol MPL and let Apache code use it unmodified. The board leaves core for good when it becomes a WASM component
  (D-16).
- **MPL to an API crate:** `infantry-daemon` → `infantry-harness`, `infantry-orchestrator-api` and `infantry-plugin-wit`.
  Core implements the API crates; that is rule 1.
- The guard is stricter than S13: no API crate uses the protocol today. If one needs it, S13 says the owner accepts it,
  and the guard gets an entry.

The rest of D-2, after LIC-1 and LIC-2:
- maps the Rust tree in `scripts/license-paths.txt`, which today lists only `infantry-runner` as MPL;
- adds a guard that `license-paths.txt` agrees with every `Cargo.toml` `license`;
- makes `scripts/license-headers.sh` cover `.rs` files;
- adds a Rust section to `LICENSE-MAP.md`.

Every file that leaves core in D-9 to D-14 changes its SPDX header from MPL to Apache and updates its
`license-paths.txt` line in the same move (EPIC #41 bar).

### 7a. The moves that remove the crossings (LIC-1 to LIC-10)

Each move is one Sonnet-sized ticket (30 to 90 minutes) that a worker with no context can pick up. For every move,
"done when" also means: `cargo test -p infantry-guards` is green, the daemon builds and starts with `/health` green,
and nothing changes for the user. Each move deletes or trims the entries it clears in `CROSSINGS`, `HEADERLESS`
(`rust/crates/infantry-guards/src/licences.rs`), `BASELINE` and `EDGE_BASELINE` (`features.rs`), in the same commit.
Build with `scripts/rust-build-env.ps1` dot-sourced, one build at a time. Paths below are under
`rust/crates/infantry-daemon/src/` unless they say otherwise.

**LIC-1: `infantry-runner-api` becomes an Apache API crate** (30 min; owner sign-off on the licence)
- Goal: `infantry-harness`, an Apache API crate, depends on the MPL `infantry-runner-api`, so whoever builds a harness
  pulls in MPL files. `runner-api` is the runner contract and has no workspace dependency: it becomes Apache-2.0 and
  joins `API_CRATES`. The same commit adds the SPDX header to the 5 test files that lack one.
- Files: `rust/crates/infantry-runner-api/Cargo.toml` (`license`); the SPDX line of every `.rs` file in that crate;
  `licences.rs` (`API_CRATES`, the `harness → runner-api` crossing, the 5 `HEADERLESS` entries); `tests/all/main.rs`
  in `infantry-board`, `infantry-daemon`, `infantry-harness-claude`, `infantry-harness-codex` and `infantry-scripting`
  (each gets the header of its crate's own `license`); section 7 of this page; the Rust note at the top of
  `docs/wiki/licensing.md`. That note calls every crate except the SDK MPL, but the board, the plugin host, the WIT
  crate, the orchestrator API and the harness crates are Apache: list each crate's licence from its `Cargo.toml`.
- Done when: the guards are green with 6 crossings and no file without a header.

**LIC-2: `infantry-plugin-host` becomes MPL** (30 min; owner sign-off on the licence)
- Goal: the plugin host is part of the kernel in the owner's boot model, but its crate is Apache, so core links an
  Apache crate that is not an API. It becomes MPL-2.0. Plugin authors are not affected: they build against
  `infantry-plugin-sdk` and `infantry-plugin-wit`, which stay Apache.
- Files: `rust/crates/infantry-plugin-host/Cargo.toml`; the SPDX line of every `.rs` file under its `src/` and `tests/`;
  `licences.rs` (the `daemon → plugin-host` crossing); `docs/wiki/licensing.md`. `plugins/official/Infantry_News` uses
  the crate as a dev-dependency only, which never ships.
- Done when: the guards are green with one crossing fewer, and `plugins_op` lists the same plugins as before.
- If the owner keeps it Apache, two moves replace this one. (a) `plugin-host` joins `API_CRATES` and drops
  `infantry-core`: it defines its own frame trait, and `compose/plugins.rs` adapts it to `FrameUnit`. (b) It drops
  `infantry-proc`: the daemon passes in a spawner built on `infantry_proc::command`.

**LIC-3: the SQL splitter moves to core** (30 min)
- Goal: `fresh_schema.rs` calls the board for a generic helper, `infantry_board::schema::schema_statements`, which
  splits a SQL text into statements. The helper moves to the db module of `infantry-core`, and the board re-exports it
  so its own callers don't change.
- Files: `rust/crates/infantry-board/src/schema.rs`; the `infantry-core` module that holds `Db`; `fresh_schema.rs:19`;
  `features.rs` (delete the `fresh_schema.rs` entry).
- Done when: a fresh state dir gets the same `sqlite_master` as before (names and SQL).

**LIC-4: the harness registry comes from `main.rs`** (60 to 90 min)
- Goal: the daemon library names the 4 concrete harness crates in `harness/mod.rs` (`DEFAULT_PROVIDER`, `neutral`,
  `by_name`, `REGISTERED`, `all_except`). `main.rs` builds the list instead and passes it in, so that LIC-5 can move
  `main.rs` out of core.
- Shape: a `HarnessRegistry` in `harness/mod.rs` holds the ordered `Arc<dyn infantry_harness::Harness>` list and the
  default id. It offers `neutral(name)`, `by_name(name)` (which keeps today's `stub` case and the `Driven` wrapper),
  `names()`, `all_except(skip)` and `default_id()`. The session manager holds it, through `sessions::Config` or
  `Daemon::new`. `main.rs` fills it in today's order: claude, codex, opencode, pi, with claude the default.
- Call sites: `harness/mod.rs`; `compose/agent_tools.rs:389`; `compose/harness_frames.rs:60`; `compose/mod.rs:158`;
  `main.rs:69-70`. The tests build the same registry through one helper.
- Files: those, and `features.rs`. The `harness/mod.rs` entry moves to `main.rs` with the same 4 terms (a split may
  carry its entry).
- Done when: `--provider` claude, codex, opencode and pi each start; `--provider nope` fails with the same message; the
  `providers` and `models` frames and the `infantry_list_harnesses` tool answer the same names, order and default.

**LIC-5: the distribution crate** (60 to 90 min; after LIC-4)
- Goal: a new Apache crate takes `main.rs` and links the concrete harnesses, so the `infantry-daemon` library (core,
  MPL) stops depending on them. The binary keeps its name, so the installer, the updater and the Tauri sidecar don't
  change.
- Shape: `rust/crates/infantry-distribution`: Apache-2.0, with `[[bin]] name = "infantry-daemon"` and `src/main.rs`
  moved as it is (it uses only the library's public API). `infantry-daemon` becomes a library only, and it keeps the 4
  harness crates as dev-dependencies for its tests. The workspace lists `crates/*`, so `rust/Cargo.toml` doesn't
  change. Two packages can't build the same binary name, so remove the daemon's `[[bin]]` in the same commit.
- Files:
  - the new crate and `infantry-daemon/Cargo.toml`;
  - `layers.rs` (`infantry-distribution` is `Layer::Top`) and `docs/rust-port/LAYERS.md`;
  - `licences.rs` (the 4 harness crossings) and `features.rs` (the `main.rs` entry and the 4 harness edges);
  - the `-p infantry-daemon` lines in `app/src-tauri/scripts/stage-bundle.sh:15`, `scripts/rust-owner-build.ps1:20`,
    `scripts/ui-live-preview.ps1:53` and `scripts/rust-build-bench.ps1:6`;
  - the build lines in `AGENTS.md` and in `docs/wiki/`: `Build-speed.md`, `Install.md`, `runner-quickstart.md`,
    `recipes/add-cli-harness.md` and `recipes/test-daemon-for-runner-repros.md`.
- Done when: `cargo build -p infantry-distribution` produces `infantry-daemon.exe`, and it starts with `/health` green;
  `stage-bundle.sh` stages the same three files; the guards are green with 4 crossings fewer.

**LIC-6: the `DaemonModule` seam, first cut** (60 to 90 min; owner OK on the shape, section 2b)
- Goal: the composition root takes feature modules from its caller instead of naming them.
- Shape: the section 2b trait without `schema` and `tools`, in a new `module.rs` (it moves to `infantry-core` with
  D-3). Its `ModuleCtx` carries what the board's construction takes today (the session manager, projects, usage, the
  db, the bus and the state dir) and a typed service registry; D-3 narrows it. A new
  `compose::build_with_modules(daemon, state_dir, mode, modules)` slots in each module's units and hello frames where
  the board's are today, and it starts the modules after the units are registered. `build_with_mode` calls it with an
  empty list, so the 8 test files that call `build_with_mode` don't change.
- Files: `module.rs` (new), `lib.rs`, `compose/mod.rs`.
- Done when: the frame-owner table, the `plugin_tools` list, the hello burst and `sqlite_master` are unchanged (dump
  them before and after).

**LIC-7: the agent units stop holding the `Dispatcher`** (60 to 90 min; owner OK on the shape, with LIC-6)
- Goal: agent frames, agent tools and the session observer reach ticket work through a small sync trait, so they name
  no board type.
- Shape: a `WorkHooks` trait in a new `compose/work.rs`, with plain types only, because the ratchet counts
  `infantry_orchestrator_api` as a feature term:
  - `titles() -> Result<HashMap<String, String>, String>`: the title of the work each session holds, by session id,
    for the roster;
  - `start(ticket, by) -> Result<String, String>`: `infantry_spawn` with a `ticketId`;
  - `turn_end(ticket, session, outcome, detail)`: the done protocol.
- The units hold `Option<Arc<dyn WorkHooks>>`, where `None` means no orchestrator. The board wiring implements the
  trait over the `Dispatcher`, in `compose/adapters.rs`.
- Pitfalls:
  - Don't call the async `Orchestrator` trait from a frame handler: `FrameUnit::handle` is sync and runs on the
    runtime.
  - `WorkObserver` keeps one blocking task per turn end, with the done protocol before `mail.session_idle`, as today.
- Call sites: `compose/agent_frames.rs:17,39,73,306`; `compose/agent_tools.rs:8,30,248-252`;
  `compose/adapters.rs:462,540`; `compose/mod.rs:361,552,589`.
- Files: those, and `features.rs` (trim `infantry_board` from `agent_frames.rs`).
- Done when: the roster shows the same titles; `infantry_spawn` with a ticket id starts it; and a ticket session's turn
  end runs the done protocol as before.

**LIC-8: the board tools behind a tool source** (60 to 90 min; owner OK on the shape, with LIC-6)
- Goal: `compose/agent_tools.rs` and `compose/tools.rs` call the board tools (`infantry_board::tools`) directly. Instead,
  a tool source that the board wiring builds answers them.
- Shape:
  - A `ToolSource` trait in `compose/tools.rs`, which already names the `infantry_scripting::host` types. It has
    `catalog() -> Vec<ToolInfo>` and `call(name, args, inv) -> Option<ToolResult>`, where `None` means the tool is not
    its own.
  - The board source wraps `infantry_board::tools::{descriptors, call, Ctx, Caller}`, and it builds the caller as
    `board_call` does today.
  - The catalog and the dispatch keep today's places: board tools beside the agent tools in the catalog, and board
    calls after the run tools and before the plugin tools.
- Call sites: `compose/agent_tools.rs:56` (`board_catalog`), `:98` (`short`) and `:152-170` (`board_call`);
  `compose/tools.rs:124,172`.
- Files: those; `compose/adapters.rs` or `compose/mod.rs` (the board source); `features.rs` (trim `infantry_board`
  from `agent_tools.rs`).
- Done when: the MCP tools list (names, order and descriptions) and `plugin_tools` are unchanged, and a board tool such
  as the ticket list answers as before.

**LIC-9: the board wiring becomes one module** (60 to 90 min; after LIC-6, LIC-7 and LIC-8)
- Goal: everything that builds the board moves into `compose/board_module.rs`, which implements `DaemonModule`.
  `build_with_mode` passes it by default, so neither behaviour nor callers change.
- It holds:
  - the construction at `compose/mod.rs:203-209`: `DaemonBoardEnv`, `TicketBoard::new`, `DaemonWorkspaces`,
    `DaemonLauncher`, `Dispatcher::new` and `LocalOrchestrator::with_dispatcher`;
  - `BoardUnit` (`:521`);
  - the `WorkHooks` and `ToolSource` implementations, which it hands to compose through the service registry;
  - the worktree sweep (`compose/worktree_sweep.rs`, which lists tickets and comments on them).
- Compose builds the modules before the agent units that read them. The `board`, `dispatcher` and `orchestrator` fields
  of `Composed` go behind the module: no code outside `compose/mod.rs` reads them.
- Files: `compose/board_module.rs` (new), `compose/mod.rs`, `compose/adapters.rs`, `compose/worktree_sweep.rs`,
  `features.rs` (the `infantry_board` terms move to the new file).
- Done when: the LIC-6 goldens are unchanged, and a ticket can be created, started and taken to done.

**LIC-10: the board module leaves the daemon** (60 min; after LIC-5 and LIC-9)
- Goal: the `infantry-daemon` library no longer depends on `infantry-board`. The distribution registers the board
  module.
- Shape:
  - A new Apache crate, `rust/crates/infantry-board-module`, takes `compose/board_module.rs`, the board half of
    `adapters.rs` and `worktree_sweep.rs`. It is `Layer::Top`, because it depends on the daemon library.
  - The daemon items those files use become `pub`.
  - `build_with_mode` stops adding the board, and `infantry-distribution/src/main.rs` passes it.
  - The daemon tests that need the board move to the new crate. A dev-dependency back onto it would build the daemon
    twice, with mismatched types.
- Files:
  - the new crate, `infantry-daemon/Cargo.toml` (drop `infantry-board`), `compose/mod.rs` and `infantry-distribution`;
  - `layers.rs` and `LAYERS.md`;
  - `licences.rs` (`CROSSINGS` ends empty) and `features.rs` (the board entries and the `daemon → board` edge);
  - the moved tests.
- Done when:
  - `CROSSINGS` is empty;
  - the distribution starts with `/health` green;
  - a ticket goes from created to done;
  - the RED test (`cargo test -p infantry-daemon --test decouple_red -- --ignored`) reports fewer breaks, because it
    now builds with no modules.

Order: LIC-1, LIC-2 and LIC-3 can run at any time. LIC-4 comes before LIC-5. LIC-6, LIC-7 and LIC-8 are independent of
one another, and LIC-9 needs all three. LIC-10 needs LIC-5 and LIC-9.

How they map onto the daemon track (section 11a):

| LIC moves | Daemon move |
|---|---|
| LIC-1, LIC-2 | D-2 |
| LIC-3 | starts D-5 |
| LIC-4 | D-6's registration step |
| LIC-5 | starts D-12 |
| LIC-6 | starts D-3 |
| LIC-8 | starts D-4 |
| LIC-7, LIC-9, LIC-10 | start D-10 |

## 8. AIFY design targets

The four AIFY plugins (EPIC #71) are design targets, not orders. Each check below asks whether the plugin could be
written later with zero core changes, and names the generic seam that closes each gap.

### Home (#72 HOME-1): AI and scripts control the lights, through Home Assistant first

- **What it does.** It lists devices from a Home Assistant server and switches lights and scenes. Agents and Lua
  scripts do the same by tool, the user gets a Home tab, and automations run ("lights off when I leave").
- **Seams it uses today:**
  - a WASM daemon half;
  - `http` for the Home Assistant REST API, `settings` for the server URL, `storage` for the device cache;
  - tools in the catalog (`home_devices`, `home_lights_set`) and events (`home.changed`);
  - a UI half (#70: a tab and a badge).
- **What core lacks:**
  - a `secrets` import bound to the module, for the Home Assistant token (section 5);
  - timers for polling and schedules (D-15);
  - hooks delivered, so a script reacts to `home.changed` (D-15);
  - a long-lived connection for Home Assistant's event stream (background work, D-20, or a sidecar);
  - a `notify` import (D-19).
- **Verdict:** zero core changes once D-15, D-19, D-20 and the secret fix land.

### Guard (#73 GUARD-1): AI watches the home cameras and alerts

- **What it does.** It pulls frames and clips from cameras (RTSP or ONVIF, or a recorder's HTTP API) and runs detection
  (a local model, or a vision model through the governor). It raises alerts to the phone and keeps an event log with
  snapshots.
- **Seams it uses:**
  - a sidecar, because video decoding is native;
  - `storage`; the notification bus; a UI half (a camera grid tab, and event cards through the `chatCard` slot);
  - plugin routes (`handle-route`), to serve snapshots to the UI.
- **What core lacks:**
  - a model-call seam with a cost setting whose default is the cheapest (local detection, a model only on motion; D-21);
  - a `notify` import (D-19);
  - background work with cancellation (D-20);
  - a `files` import so a WASM half can keep snapshots in its own folder (D-19). A sidecar can already do it.
- **Verdict:** zero core changes once D-19, D-20 and D-21 land.

### Games (#74 GAMES-1): AI organizes the game library across launchers

- **What it does.** It reads the installed games of Steam, Epic, GOG and Xbox (launcher manifests and databases) and
  merges them into one library, with metadata and covers over HTTP. The user launches a game from it, and an agent tags
  and sorts.
- **Seams it uses:** `http`, `storage`, tools (`games_list`, `games_launch`), `settings` (library paths), a UI half (a
  library grid) and plugin routes (covers).
- **What core lacks:**
  - a `files` import with declared read-only path grants (D-19);
  - a `process` import with declared commands, to launch through the launcher, going through `infantry-proc` (D-19).
    Registry reads stay in a sidecar; no WIT import is planned for them.
  - placement: the plugin must run on the user's desktop daemon, not on a remote runner (D-20).
- **Verdict:** zero core changes once D-19 and D-20 land.

### Shield (#75 SHIELD-1): clean the drive, scan for malware, and an AI looks for signs of a hack

- **What it does.**
  - It finds large and temporary files and offers to clean them.
  - It runs the Windows Defender command line, or reads its results.
  - It collects signs of compromise (autoruns, new services, scheduled tasks, unusual connections, sign-in events), and
    an agent reviews them and writes a report.
- **Seams it uses:** a sidecar (native system calls), `storage`, tools, a UI half (a report tab) and the
  prompt-card units that already exist for asking the user.
- **What core lacks:**
  - an `ask` import, because every destructive step needs the user's approval (D-19);
  - `process` and `files` imports (D-19), and scheduled scans (D-15);
  - a model-call seam, to summarize findings (D-21);
  - per-call enforcement of a tool's `kind`, so a `write` tool needs an approval rule (D-15).
  - Elevation is outside the plugin model. Proposal: none in v1, read-only checks only (an owner call, section 12).
- **Verdict:** zero core changes for the read-only version once D-15, D-19 and D-21 land.

### The common gaps, as generic seams

| Gap | Generic seam | Move |
|---|---|---|
| Secrets bound to a module | A `secrets` import that answers only the calling module | security ticket, D-19 |
| Timers and schedules | A `timers` import, or `daemon.schedules` in `module.json` | D-15 |
| Hooks that reach plugins and scripts | `on-hook` delivered; events reach Lua | D-15 |
| Notices to the user and the phone | A `notify` import over the notification bus | D-19 |
| Asking the user | An `ask` import over prompt cards | D-19 |
| Running a program | A `process` import with declared commands, through `infantry-proc` | D-19 |
| Files | A `files` import with declared path grants | D-19 |
| Calling another tool | A `tools.call` import | D-19 |
| Long-running work | Background tasks with cancellation; concurrent calls in the sidecar SDK | D-20 |
| Where a plugin runs | A placement field in `module.json`; the orchestrator routes calls | D-20 (with #53) |
| Model calls | A `model.call` import through the usage governor, with a cost setting whose default is the cheapest | D-21 |

## 9. Building an app on Infantry

An app is a set of plugins, shared through the marketplace (#69). A plugin has up to six parts:
- a daemon half (WASM, or a sidecar);
- a web UI half (#70);
- tools, declared in `module.json` and served to agents and scripts through the tool catalog;
- Lua scripts and hooks;
- optional role and skill packs (knowledge data);
- settings, declared in `module.json`, which come with their own card (#46).

The path from a template to a shared app:

1. **Start from the template** (D-22): `module.json`, `daemon/` (Rust built to a WASM component against
   `infantry-plugin-sdk`), `ui/` (React against `@infantry/ui-plugin-api`), `scripts/`, the tools block, and a test.
   `infantry plugin new <name>` scaffolds it.
2. **Build.** `rust/plugins/stage.sh` builds the daemon half; the UI half builds with the API's build helper (#70).
3. **Run it locally.** Point a dev daemon at the folder with `INFANTRY_PLUGINS_DIR`, and open the live preview
   (`scripts/ui-live-preview.ps1`).
4. **Test it.** The SDK test host (D-22) runs the daemon half without a daemon.
5. **Share it.** Push the folder to a git repository. Users add it as a source in the marketplace (#69), install and
   enable it. Only signed or approved plugins are served (the #70 trust model).

What the SDK must expose:
- the WIT world, with today's imports plus `timers` and hooks (D-15), `secrets`, `notify`, `ask`, `process`, `files` and
  `tools.call` (D-19), and `model.call` (D-21);
- typed registration of frames and tools, a test host, and versioning (an API version in `module.json`, refused on a
  mismatch);
- the UI API (#70).

What blocks it today:
- the templates are Kotlin-era (`plugins/_template`, `templates/plugin-repo`);
- the ABI gaps in section 6, and the UI half, which lands with #70;
- installing from a source: `plugin_admin` install, update and remove answer exit code 1 (#69, D-14);
- the Rust section of `docs/wiki/daemon-plugin-api.md` is short, and it promises limit settings that the daemon does
  not read;
- the SDK has no test host.

## 10. The shell-only proof

**The build (#92 SHELL-ONLY-1).** The shell-only build is core plus the installer plugin. It runs the same binaries
with a bundled folder that holds only the installer. Core boots and shows the splash (#89). It finds no UI plugin, so
it loads the installer, which shows its bootstrap screen (#90, #91). Because flavours are data (section 2, rule 5), the
check runs the release binaries, not a special build.

**The check (#92, D-18).** It runs on Windows (builds are Windows-only for now):
1. Start the daemon with the shell-only folder. Health is green, and the app opens on the installer's bootstrap
   screen.
2. Add a local source: a clone of the infantry-plugins repository (a fixture folder in CI).
3. From it, install the UI plugin, the agents module and the orchestration plugins (board, worktrees, pods). A builtin
   module installs as its folder (D-13).
4. Check that the board works. Create a ticket and start it; an agent session starts in a worktree through the harness
   double, and the ticket reaches done.
5. Compare against the bundled build: the frame owners, the tool catalog, the tables, the UI contributions and the
   screens are identical. This shows that both paths give the same app.

A grep guard fails on any `bundled` branch in the loader.

It depends on #69 (marketplace sources), #90, #91, D-13, D-16, D-17, UI-7 and UI-9. The orchestrator must be loadable
(D-16, D-17) before the check can install it from a repository.

## 11. The moves, in order

Rules that every move follows:
- **Behaviour stays identical.** No styling change, nothing removed. Frames, tools, tables, the hello burst and the UI
  stay the same unless the move says otherwise; D-7 changes the wire shape at both ends. Bundled and installed plugins
  behave alike. The one new screen is the installer's bootstrap screen, shown only when nothing is installed (#90).
- **A golden proves it.** Each move names its golden: the frame-to-owner table, the `plugin_tools` list,
  `sqlite_master`, the hello burst, or the conformance suite.
- **The ratchet shrinks.** The move trims the `BASELINE`, `EDGE_BASELINE`, `CROSSINGS` or `HEADERLESS` entries it
  clears, in the same change.
- **Seams first get the owner's OK on their shape**, before any code (owner rule: abstract tooling needs owner OK).
- **UI moves are checked live.** They are verified with the Playwright MCP on the live preview
  (`scripts/ui-live-preview.ps1`), and the desktop stays identical.
- **Licence headers follow the code.** Files that leave core change their SPDX header and their `license-paths.txt`
  line in the same move.
- **Tests stay scoped:** the changed crates and packages plus the guards. The full suite runs only on staging (owner
  rule).

### 11a. Daemon track

| Move | What | Risk | After | Ticket |
|---|---|---|---|---|
| D-2 | Licence hygiene: `plugin-host` becomes MPL, `runner-api` an Apache API crate; headers on 5 files; `license-paths.txt` maps the Rust tree, with a guard; `license-headers.sh` covers `.rs`; a Rust section in LICENSE-MAP | low (owner sign-off) | | LIC-1, LIC-2 |
| D-3 | The `DaemonModule` seam: the composition root builds from modules in today's order; the hello burst is per module; `Mode` becomes a profile | medium (owner OK) | | LIC-6 first |
| D-4 | The tool catalog contract in core, with explicit precedence; the knowledge filter is contributed | low-medium (owner OK) | D-3 | LIC-8 first |
| D-5 | Schema per module: drop the Kotlin `build.rs` tree; a fresh db gets only the installed modules' tables; existing dbs are untouched | medium-high | D-3 | LIC-3 first |
| D-6 | Harness registration by the composition root; install recipes from each harness; the default provider becomes a setting with today's value | low-medium | | LIC-4 |
| D-7 | The work context in the session DTO (both ends), a generic working folder in health, spawning frames declared by their unit, role capabilities and the PM role pack | medium | | |
| D-8 | The notification bus moves into core; `infantry-mail` keeps agent mail only | low-medium | | |
| D-9 | The `infantry-agents` crate: sessions, the harness registry, agent mail and tools, the agent registry, `AgentsApi`; one `infantry_message` server | high | D-4, D-6, D-7, D-8 | |
| D-10 | The orchestrator crates: board, dispatcher, pods, worktrees, sweep, merge, adapters, watch, board tools; frame names leave the protocol; the git view goes to `Infantry_Git`; `infantry-projects` splits | high | D-5, D-9 | LIC-7, LIC-9, LIC-10 first |
| D-11 | Knowledge, usage and scripting become modules | medium | D-4 | |
| D-12 | The split: the composition root's feature wiring, Run, MCP and Account move into `infantry-distribution` (Apache); the `infantry-daemon` library keeps core; `core_names_no_feature` turns green | medium | D-10, D-11 | LIC-5 first |
| D-13 | Builtin modules load like plugins (`"daemon": {"builtin": ...}`); the RED test turns green and loses its `#[ignore]` | medium | D-12 | |
| D-14 | Plugin management becomes the installer plugin (the loader and the contracts stay core) | medium (owner OK) | D-13, #46, #69, the security fix | #90 |
| D-15 | The orchestrator ABI: timers and schedules, hooks delivered, frame-name ownership, the `limits.plugins.*` settings read, tool `kind` enforced | medium (owner OK) | D-3 | |
| D-16 | The board as a WASM component | high | D-13, D-15 | |
| D-17 | Worktrees, merge and dispatch as a sidecar; `stage.sh` stages sidecars | high | D-13, D-15 | |
| D-18 | The shell-only build (core plus the installer) and its check (section 10) | medium | #90, #91, D-13, D-16, D-17, UI-7, UI-9, #69 | #92 |
| D-19 | The device-plugin ABI: `secrets`, `notify`, `ask`, `process`, `files` and `tools.call` imports | medium-high (owner OK) | D-15, the security fix | |
| D-20 | Background work, concurrent sidecar calls, and plugin placement (with #53) | medium-high | D-19 | |
| D-21 | The model-call seam through the usage governor, with a cost setting whose default is the cheapest | medium (owner OK) | D-11 | |
| D-22 | The Rust app template, the SDK test host, `infantry plugin new`, and a wiki page "Building an app on Infantry" | low-medium | #70, D-15 | |

### 11b. App track

| Move | What | Risk | After | Ticket |
|---|---|---|---|---|
| UI-1 | `TabPage` and `knowledge/parts.tsx` move into `@infantry/ui`; the agent status vocabulary leaves `ui/types.ts:5`; `client/wire.ts:72-77` imports from inside its package; `stores/link.ts` is exported | low | #70 | |
| UI-2 | Built-in screens register like plugin components, with no position and no order (a `preferredRegion` hint at most; the layout decides); the CLI becomes an ordinary screen; a ratchet blocks new built-in screens | medium | #70, #56 | #84 |
| UI-3 | A navigation API, a dialog registry and a shortcut registry; `ui-state` splits into a layout context and an agent-selection store | medium | UI-2 | |
| UI-4 | One store hub; `panels.ts` splits; the current project leaves the git store; tickets and projects leave the daemon store | medium-high | #70 | |
| UI-5 | Feature-to-feature and layout-to-feature imports become slots, with an import guard | medium | UI-2, UI-3, UI-4 | |
| UI-6 | The `LayoutProvider` seam; today's frame becomes the default layout of the UI plugin, which the boot chain loads; with no UI plugin the installer shows; #82 builds on it | medium-high | UI-5 | #87, #91 |
| UI-7 | The orchestrator screens (board, git, worktrees, epics, pods, review) move into the orchestrator's UI halves | medium | UI-6, D-13 | |
| UI-8 | The agents screens (session, agents, agent dialogs, mail, roles) move into the agents UI half | medium | UI-6, D-13 | |
| UI-9 | The Plugins screens move into the installer's UI half | low-medium | UI-6, D-14 | #90 |
| UI-10 | The remaining screens (usage, knowledge, library, scripts, run, stats, setup, health) move into their plugins | medium | UI-6, D-13 | |

The boot chain, filed by the PM from the owner's model:
- #89 BOOT-SPLASH-1: core owns the splash and the boot progress. A plugin reports its own progress through core's seam
  and never draws the splash.
- #90 INSTALLER-1, #91 BOOT-CHAIN-1 and #92 SHELL-ONLY-1.
- #83 OS-DOC-1: the architecture page.
- #88 WEBUI-6: the Triggers tab becomes the first real UI plugin.

### 11c. Bugs found on the way

| Bug | Kind | Ticket |
|---|---|---|
| Plugin frame requests run as the owner and can read any plugin's secrets (section 5) | security, reproduce first | |
| The plugin host ignores the `limits.plugins.*` settings that the plugin API page promises (section 6) | bug, reproduce first | |

### 11d. Order

| Phase | Moves | Notes |
|---|---|---|
| 0. The licence line | LIC-1 to LIC-10 (section 7a), D-8; UI-1 after #70 | Small Sonnet moves. LIC-1, LIC-2 and LIC-6 to LIC-8 need the owner's sign-off first |
| 1. Core seams | D-3, D-4, D-5, D-7, D-15 | Each needs the owner's OK on its shape first |
| 2. Extract | D-9, D-10, D-11; UI-2 to UI-5 (#84 to #86) | |
| 3. Split and prove | D-12 (the core guard goes green), D-13 (the RED test goes green); UI-6 (#87); the boot chain (#89, #91) | |
| 4. The installer and the screens | #90 (D-14); UI-7 to UI-10 | |
| 5. A loadable orchestrator | D-16, D-17, #92 (D-18, the shell-only proof) | |
| 6. The app platform | D-19, D-20, D-21, D-22 | |

The critical path is D-3 → D-4 → D-9 → D-10 → D-12 → D-13 → #90 → D-16 and D-17 → #92.

The board runs at most two workers. The order above is a sequence, and the PM picks the next move from it.

## 12. What it breaks, and the owner's calls

### What it breaks

- **The wire (D-7).** The session DTO's `worktree_path`, `worktree_branch`, `worktree_base_ref` and `ticket_id`
  (`infantry-protocol/src/session.rs:224-235`) move into `context`. Clients that read the old fields (the Android
  build of 0.2.0) break unless the daemon sends both for a while.
- **Frame constants (D-10).** Code that uses `infantry_protocol::frames::TICKET_*` and the like moves to the
  orchestrator's constants. The conformance suite and the `CONTRACT.md` golden (#80) follow.
- **The plugin list (D-13).** Builtin modules appear as plugin folders, so the Plugins screen lists agents, knowledge,
  usage, scripting and the orchestrator modules.
- **Databases (D-5).** An existing db keeps every table and row. A fresh db without the board has no board tables, and
  uninstalling the board leaves its tables in place: nothing is removed.
- **Open tickets that touch moved code:**
  - #9 PERF-SWEEP-1, #13 BUG-SWEEP-2 and #16 GAP-WT-DELETE-1 change `compose/worktree_sweep.rs`, which D-10 moves.
    They land first, or rebase onto D-10.
  - #7 PERF-MAIL-1 and #8 PERF-MAIL-2 change `infantry-mail`, which D-8 and D-9 split. They land first, or follow the
    split.
  - #24 GAP-ARCHIVE-1 adds board frames: in the board crate, not in a new core file (the ratchet fails otherwise).
  - #23 GAP-AGENT-FRAMES-1 adds agent frames: in `compose/agent_frames.rs`, which D-9 moves into `infantry-agents`.
  - #5 PM-ENFORCE-1 checks a role capability (D-7), not `"pm"` in core.
  - #29 GAP-HARNESS-INSTALL-1 overlaps D-6.
  - #43 AUTO-1 needs hook delivery (D-15). #44 AUTO-2 and #15 GAP-MERGE-1 meet D-17.
  - #56 UI-TABS-ZERO-PLUGINS-1 lands before UI-2, or UI-2 keeps today's behaviour.
  - #82 LAYOUT-1 builds on UI-6, not on #70.
  - #46 PCFG-1 and #69 MARKET-1 come before D-14. #76 PLANNER-1 is the first real install from the infantry-plugins
    repository (D-18).
  - #53 RUNNER-1 decides mail on runners and plugin placement (D-20). #36 ARCH-TAURI-LOCAL-1 removes `local.rs`.
- **The build.** The workspace gains crates (`infantry-distribution`, `infantry-board-module`, `infantry-agents`, the
  orchestrator crates). Each move adds its crates to `layers.rs` and `docs/rust-port/LAYERS.md`. After LIC-5,
  `cargo build -p infantry-daemon` builds the library only: the binary comes from `-p infantry-distribution`, under
  the same name.
- **Unchanged:** the Kotlin bridge (`--legacy-backend`, `held.rs`, `gateway/legacy.rs`).

What #45 itself breaks: nothing. The two RED tests are ignored. The ratchet fails only on a new feature name in core,
and the licence guard only on a new crossing.

### The owner's calls

| # | Call | Proposal | Needed before |
|---|---|---|---|
| 1 | The seam shapes: `DaemonModule` (first cut in LIC-6), `WorkHooks` (LIC-7), `ToolSource` and tool precedence (LIC-8, D-4), the plugin admin contract, the orchestrator ABI, the device ABI, the model-call seam | As in sections 2b, 5, 6, 7a and 8 | LIC-6 to LIC-8, D-3, D-4, D-14, D-15, D-19, D-21 |
| 2 | Licences | `plugin-host` becomes MPL (kernel). `runner-api` becomes an Apache API crate. `infantry-agents`, the orchestrator crates, `infantry-board-module` and the installer are Apache. The distribution crate is `infantry-distribution` (Apache) and builds the `infantry-daemon` binary. The `infantry-daemon` library stays core (MPL), which keeps every import, path and baseline that names it. | LIC-1, LIC-2, LIC-5, D-9, D-12 **Approved 2026-11-20: `runner-api` is Apache-2.0 and an API crate (LIC-1 done). `plugin-host` is MPL-2.0 core (LIC-2, approved 2026-11-20), so call 2 is fully approved.** |
| 3 | The old session fields | Send them beside `context` for one minor version | D-7 |
| 4 | Builtin modules in the Plugins screen | Show them with a "built in" badge. Agents cannot be disabled in the default flavour. | D-13 |
| 5 | Board storage under WASM | Grant the board its existing table names rather than rename tables | D-16 |
| 6 | The shell-only build | Core plus the installer: the same binaries with a different bundled folder, not a cargo feature | #92 (D-18) |
| 7 | Elevation for system plugins (Shield) | None in v1: read-only checks only | D-19 |
