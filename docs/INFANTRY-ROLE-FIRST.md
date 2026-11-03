# Infantry role-first agents: role + session first, executor attached to the role, swappable

Ticket: `82c1c95a-cfdc-4bed-ae48-f838faedc523` (DESIGN: role-first agents). Design only: no production
code. Author: architect. Date: 2026-10-18.

Owner requirement (2026-10-18) and Owner addition (ticket comment, same day), restated so this note
stands alone:

1. **Role first.** Adding an agent starts with a ROLE (a role grid with a search box in the
   "Add agent" dialog). Today an agent carries the role, which is backwards.
2. **The executor is attached to the role**, and the agent filling a role can be swapped (swap the
   Project Manager). A role is filled by a default executor, or one the user defines.
3. **Picking an executor is exactly three choices, in order: HARNESS (the CLI: OpenCode, Claude Code,
   Pi...) -> PROVIDER -> MODEL.** Choosing a harness loads THAT harness's own configuration. OpenCode
   and Pi show every provider/model configured in them; Claude Code has one provider (Claude), so only
   a model is picked. **Every list is searchable.**
4. The Owner calls the current UI "a mess": this design therefore includes a cleaned-up layout for the
   Add-agent flow, the left agent list and the right panel, not only the data model.
5. Say where each harness's provider/model configuration comes from, and whether the daemon's `models`
   frame is per provider or per harness.

Already built and NOT redone here: `ux-model-picker` (the `/model` list from the daemon catalog),
`ux-open-directory`, and ROLE-1a..1d (the `role_bindings` table, `role_binding_list/get/set`, and the
resolution order in `AgentProfiles.executorFor`).

---

## Status (2026-10-18, S14)

The daemon half (S1-S5) is merged into `feature-agenthq-mvp`; the wire summary is the "What changed in role-first
(1.4x)" section of [`superiorSoftLibrary/infantry/CONTRACT.md`](../superiorSoftLibrary/infantry/CONTRACT.md). SHAs are
from `git log --oneline feature-agenthq-mvp` (merge commit / feature commit).

Where the build differs from the plan below: `NEXT` became three versions (1.43.0 for S1, 1.46.0 for S2, 1.47.0 for
S3); S4/S5/PICKER-ALL-1 needed no bump (same `models` frame shape, served by 1.45.0); the effort migration is
`25.sqm` (schema v25 -> v26), not `23.sqm` (the prompt and skills libraries took 23 and 24).

| # | State | Merge / commit | Contract |
|---|---|---|---|
| S1 `SpawnOptions.role` | done | `b1a6b77c` / `d09a400a` | 1.43.0 |
| S2 `agents.effort` | done | `6c88e60b` / `4ce0acc8` | 1.46.0, schema v26 |
| S3 `agent_swap` / `agent_swapped` | done | `34bd79cf` / `31808ed4` | 1.47.0 |
| S4 Pi catalog (`HarnessModelCatalogs`) | done | `4e77a214` / `d24b4b79` | no bump (1.45.0) |
| S5 descriptor `models` section | done | `b0a14bea` / `f74b3c8c` | no bump (1.45.0) |
| PICKER-ALL-1 (not in the plan) Claude catalog + noted row for a harness with no catalog | done | `4075dd40` / `27ac9a18` | no bump (1.45.0) |
| S6 `filterRanked` + `ExecutorPickerModel` | done | `805b6483` / `00d1fc7d` | UI only |
| S7 `SearchableSelect` + `ExecutorPicker` | done | `6ccf0391` / `b01ec4c6` | UI only |
| PICKER-ALL-2 (not in the plan) `ExecutorPicker` everywhere; `ModelCatalogPicker` / `ModelPickerField` retired | done | `59d99e71` / `d1e5b775` | UI only |
| BUG-S3 (ticket `44437057`, follow-up on S3) | in progress (not merged) | - | - |
| S8 `Controller.addAgent` / `swapAgent` / `saveAgent(effort)` (ticket `c46f56e4`) | done (merged `597e13dd`) | - | UI only |
| S9 `AddAgentDialog` | pending (`fc2e2c51` has merged as `d5ff0185`, so only S7/S8 gate it) | - | - |
| S10 left panel, one row per agent | pending | - | - |
| S11 TEAM tab + inspector header + Attention badge | pending | - | - |
| S12 cross-harness handoff (spike first) | pending | - | - |
| S13 Adopt into agent / `ProviderInfo.capabilities` / CLI `agent swap` | pending | - | - |
| S14 this status + CONTRACT summary | done (this change); `EventVocabulary.VERSION` = `1.47.0` = CONTRACT header, pinned by `StubDaemonContractTest` | - | no bump |

Decisions (section 11):

| # | State |
|---|---|
| D1 session first | Decided as recommended: S1 binds the session at creation with its role; the executor resolves before the process starts. |
| D2 persistent agent | Decided as recommended: the New agent modal's Add is `agent_save` (a named `AgentProfile`); a role-only spawn is the quick run. |
| D3 no cross-harness continuity in v1 | Decided as recommended: S3 cuts a fresh session (`new`), the old one stays resumable; handoff is S12. |
| D4 swap while running | Decided as recommended: the run finishes on the old executor, nothing is interrupted (S3). |
| D5 no seeded role defaults | Decided as recommended: no defaults in code; an unbound role falls back to the daemon default. |
| D6 layout moves | Open: (a), (b), (d) go with S10/S11; (c) the Attention relocation still needs the Owner's separate confirmation. |
| D7 vocabulary | Decided as recommended: wire `provider` / `group` / `model` unchanged, the UI says Harness / Provider / Model. |
| D8 catalog registry, no HARNESS bump | Decided as recommended: `HarnessModelCatalogs` (S4/S5); HARNESS contract stays 1.3.0. |

---

## 0. The short version

- **A role-level executor already exists** (ROLE-1). The data model needs three small additions, not a
  rebuild: a `role` on a spawn that has no profile, an `effort` on an agent, and one `agent_swap`
  command. One additive wire bump, one ALTER TABLE migration (`23.sqm`), no HARNESS contract bump.
- **The wire word "provider" means HARNESS** (`opencode`, `claude`, `pi`). The Owner's "provider" (the
  upstream vendor: anthropic, deepseek, google...) is `ProviderModels.group`, and for OpenCode and Pi it
  is also the prefix of the model id (`anthropic/claude-...`). We do NOT rename wire fields. The UI
  relabels: wire `provider` -> "Harness", wire `group` -> "Provider", wire `model` -> "Model".
- **The `models` frame is per configured provider (= per harness), one row per vendor group.** It is
  built by iterating `SessionManager.providers`, so a harness that exists only as a plugin or a JSON
  descriptor (Pi today) has NO row. That is the one real gap behind "Pi shows all providers/models
  configured in Pi", and it is closed daemon-side without touching the plugin contract (section 5).
- **Three overlapping UIs define an agent today** (the New-agent box, the Profiles tab, the Roles tab's
  binding editor). They collapse into two surfaces: one Add-agent dialog and one Team screen.
- **Nothing needs a data migration.** `agents.role` is already required, `role_bindings` stays, and a
  null executor field already means "inherit" (section 8).
- **Owner decisions before any code: section 11.** Slices: section 10.

---

## 1. What exists today (verified against this worktree, branch `design-role-first`)

| Concern | Where | Fact |
|---|---|---|
| Role catalog | `role/` | Roles are rows; `RoleIds.PATTERN` is the id shape; `role_list` / `role_save` exist. |
| Role -> executor | `role_bindings` (migration `20.sqm`), `RoleBindingHandler`, frames `role_binding_list/get/set` (1.40.0) | `RoleBinding(roleId, provider?, model?, effort?, updatedAt)`. `set` validates the role, the harness id and the effort level; it pushes `role_binding` to EVERY client. |
| "Agent" today | `AgentProfile(id?, name, role, provider?, model?, instructions?, projectId?)`, table `agents` | A per-project TEMPLATE. `role` is required. No `effort`. `agent_save` answers only the requesting client. |
| Resolution | `AgentProfiles.executorFor(profile, provider, model, effort)` | Per field, first non-null wins: the spawn's own choice, then the profile, then the profile's role binding, then the daemon default. A profile's or binding's model applies only while the session runs on the harness it was pinned for. |
| Session | `SessionRecord` | Copies provider/model/effort/agentId/role/projectId/ticketId and the harness-specific `providerSessionId` at spawn. A rebind or a profile edit therefore never reaches a running or finished session. |
| Spawn | `SpawnOptions(provider, model, agentId, projectId, effort)` | **No `role` field.** A session gets a role only through a profile (`ResolvedSpawn.role = profile.role`). |
| Live change | `/model`, `/effort` (1.39.0), `interrupt` (1.32.1) | Change the NEXT run of a live session, within the same harness. |
| Tickets | `TicketBoard.resolveAssignment` | `agentId` = a profile id, or `role:<role>` = the first profile of that role with no active session. Executor via `profiles.executorFor`. A `role:X` ticket with no profile of that role is refused ("no agent profile has role"). |
| Catalog on the wire | `providers` frame (`ProviderInfo(id, default, defaultModel, models?, modelsStale)`) and `models` frame (`ProviderModels(provider, group, label, models, note?)`) | `providerInfos` lists configured providers plus harness-only ids; harness-only ids get `ProviderInfo(id, false, null)` (no models). `modelCatalog(manager)` (`DaemonMain.kt:1805`) iterates `manager.providers` ONLY. **Capabilities (`HarnessCapability`) are not on the wire.** |
| UI: New agent | `App.kt` spawn-mode stack | `SpawnRoleRow` (role chips, `spawn-role:<id>`), then `ModelCatalogPicker`, then `PromptBox`. `Controller.spawn` sends `new` + `prompt` with `spawnOptionsData(pickedModel, pickedEffort)`. **The role chip only seeds the picker; the spawned session is never attached to the role.** |
| UI: pickers | `ModelCatalogPicker.kt` (flat scrolling column of `OutlinedButton`s), `ModelPickerField` (Profiles), `RoleBindingEditor` (provider chips + a 140dp `ModelCatalogPicker` + effort chips + "Save binding") | Three pickers, none searchable. |
| UI: shell | `App.kt` | 10 centre tabs (`CenterTab`: OUTPUT, PODS, ROLES, BOARD, PROFILES, TRIGGERS, EPICS, MEMORY, RUN, GIT), 4 right tabs (`RightTab`: WORKTREE, REVIEW, ATTENTION, USAGE), a sidebar with counters, projects, search, role chips, a roster section, a session list and "+ New agent". |

### The three real gaps

1. **A session cannot be attached to a role without a profile** (`SpawnOptions` has no `role`), and the
   New-agent flow creates no agent at all. "Role first" has nothing to attach to.
2. **Harness-only harnesses have no model catalog on the wire.** Pi is the live example: `pi
   --list-models` prints 179 models in 6 providers (measured 2026-10-18: commandcode 93, opencode-go 30,
   google 23, anthropic 17, claude-bridge 14, deepseek 2), and none of it reaches a client.
3. **Pickers are flat, unsearchable button lists**, with 308 OpenCode ids in one of them, and three
   different UIs edit "which executor does this role/agent run on".

---

## 2. Vocabulary (one table, used everywhere below)

| Term | Meaning | Wire / storage today |
|---|---|---|
| **Role** | What an agent is for (Project Manager, QA, Backend...). The key of the system. | `roles`, `RoleIds` |
| **Executor** | The triple HARNESS + PROVIDER + MODEL (+ optional EFFORT) that actually runs. | `provider` (= harness id), `model`, `effort` |
| **Harness** | The CLI that runs: OpenCode, Claude Code, Pi, or a JSON-descriptor CLI. | wire field named `provider` (kept) |
| **Provider** | The upstream vendor behind a model: anthropic, deepseek, google... | `ProviderModels.group`; prefix of the model id for OpenCode and Pi |
| **Model** | A model id as the harness spells it. | `model` |
| **Role default** | The executor a role runs on when nobody says otherwise. | `role_bindings` |
| **Agent** (user-facing) | A named, persistent occupant of a role in one project, with a persona. This IS the existing `AgentProfile`; we only change how the UI talks about it. | `agents` |
| **Session** | One conversation/run history of an agent. | `sessions` |

Decision: **no new "seat" table.** The agent profile already is "the thing that fills a role in a
project", and the swap changes its executor columns. Introducing a second concept would be complexity
without a requirement behind it.

---

## 3. Data model: Role -> executor -> session

```
 Role  (global)                 Agent  (per project)                    Session
 ------------------             -----------------------------           ---------------------
 id, name, ...                  id, name, persona                       id, agentId?, role?
 role_bindings:   ----------->  role  (required, FK by id)              provider, model, effort
   default executor             provider?/model?/effort?  (override)     (COPIED at spawn)
   (harness/model/effort)         null = inherit the role default       providerSessionId
```

**Resolution (unchanged, ROLE-1b):** spawn options > agent override > role default > daemon default,
per field. "Role first" is a matter of making the role the thing the caller names; the executor then
resolves from below. What changes:

### 3.1 Daemon changes

1. **`SpawnOptions.role: String?`** (additive). When there is no `agentId`, the role is used as if a
   profile with that role and no overrides had been named: `executorFor` reads the role's binding, the
   session record gets `role`, and no persona is injected. `ResolvedSpawn.role` becomes
   `profile?.role ?: options.role`. A role that does not exist in the catalog is refused at spawn (same
   check `RoleBindingHandler` uses), so a typo cannot create a role-less session. If both `agentId` and
   `role` are given and disagree, the spawn is refused (the profile's role is the truth).
   *Why:* quick runs and `role:X` tickets can run on a role with no agent created, and the Add-agent
   dialog can start a session in the same step it creates the agent.
2. **`AgentProfile.effort: String?`** and column `agents.effort TEXT` (migration `23.sqm`, `ALTER TABLE
   agents ADD COLUMN effort TEXT`, with the usual `.sq` parity in `SchemaMigrationTest`).
   `executorFor` becomes `effort ?: profile.effort ?: binding.effort`. Validated like
   `RoleBindingHandler` validates it (`effortLevels`). *Why:* an agent can pin a model but not an effort
   today, so a swap that changes model could not also carry effort.
3. **`agent_swap` command** (section 4). It is the only genuinely new behaviour.
4. **Catalog completeness** (section 5): the `models` frame lists every harness that can be spawned,
   including harness-only ones.
5. **No change** to `role_bindings`, `RoleBindingHandler`, `executorFor`'s order, `TicketBoard`'s
   assignment forms, or the `sessions` schema.

### 3.2 Why the session does not store a pointer to its agent's current executor

An executor is copied into the session at spawn, on purpose: a rebind must never reach a session that
already started (ROLE-1b's rule). The agent's *current session* stays derived as today (the in-flight
run, else the latest by `agentId`). A swap therefore needs no new session-pointer column.

### 3.3 "Session first"

Read literally ("a session with no executor, then attach one") this cannot exist: a session record
needs a harness to run, and `SessionManager` spawns the process at creation. The faithful reading, and
what this design implements, is: **the role and the session identity are what the caller chooses
first; the executor is resolved before the process starts and is shown, editable, in the same dialog,
pre-filled from the role's default.** Owner decision D1 (section 11) confirms this reading.

### 3.4 Wire contract

One additive minor bump (the PM allocates the number; this note calls it `NEXT`). Contents, all
additive:

- `SpawnOptions.role` (optional). `AgentProfile.effort` (optional).
- `agent_swap` (command) -> `agent_swapped` (push to every client).
- `models` frame rows for harness-only harnesses (new rows, same shape).
- Optional, slice-gated: `ProviderInfo.capabilities: List<String>?` so the UI can hide the effort row
  for a harness without `EFFORT_SELECTION`.

Command payloads are strict-decoded (`StrictJson`: unknown keys refused), so a NEW client talking to an
OLD daemon would be refused on `role` / `effort` / `agent_swap`. Clients already gate by contract
version; the role-first dialog is shown only when `NEXT` is served, and falls back to today's New-agent
flow otherwise. `CONTRACT.md` gets a "What changed in NEXT" section (every earlier bump has one).

**No HARNESS plugin contract bump** (`HARNESS_CONTRACT_VERSION` stays `1.3.0`): the version handshake
is an exact match, so a bump would invalidate every third-party plugin. See section 5 for how the
catalog gap is closed without it.

---

## 4. The "swap agent in role" operation

Two scopes, because "swap the Project Manager" can mean either:

| Scope | Meaning | Command | Affects |
|---|---|---|---|
| **This agent** | This agent now runs on a different executor. | `agent_swap` (new) | This agent's future runs. |
| **The role** | Every agent in the role that has no override now runs on a different executor. | `role_binding_set` (exists) | Future sessions of every inheriting agent. |

The Swap button on an agent offers both ("This agent only" is the default).

### 4.1 `agent_swap`

```
agent_swap   data: {"agentId":"<id>","provider":"<harness>","model":"<model>?","effort":"<level>?",
                    "session":"auto"|"keep"|"new"}
agent_swapped (push to EVERY client)
             data: {"agent":<AgentProfile as stored>,
                    "from":{"provider":..,"model":..,"effort":..},
                    "session":{"outcome":"kept"|"new"|"none","sessionId":"<id>?"}}
refusal:     an `error` frame "agent_swap refused: <reason>", NOTHING written
```

Why a dedicated command and not just `agent_save`: `agent_save` overwrites the whole profile (a stale
client can erase a concurrent edit), answers only the requester, and cannot express what happens to the
agent's live session. A swap is a state transition with a session consequence, so it is one command
with one all-or-nothing validation.

### 4.2 Algorithm (all validation first, then writes)

1. Resolve the agent (`profiles.get`); unknown id -> refuse.
2. Validate the target executor exactly as `AgentProfiles.save` does (`sessions.checkSpawn`, model
   syntax, `validateModel`, effort level). This validation is FACTORED OUT of `save` into one
   `validateExecutor(provider, model, effort)` used by both, so the two can never disagree.
3. Persist the new `provider/model/effort` on the agent (`@Synchronized`, same lock as `save`).
4. Decide the live session's fate (the agent's current session is derived: in-flight run, else latest
   by `agentId`):

| Agent's current session | Harness changes? | `session:"auto"` outcome |
|---|---|---|
| none | - | `none` (the next spawn simply resolves to the new executor) |
| idle / finished | no (model or effort only) | `kept`: the session's NEXT run uses the new model/effort via the same internal path `/model` and `/effort` use |
| idle / finished | **yes** | `new`: a fresh session is created for the agent on the new harness; the old session stays in history, still resumable on its OWN harness |
| **running** | either | The run **finishes on the old executor**; the swap is stored now and applies from the next run (D4). `outcome` is `kept` (same harness) or `none` (different harness: the new session is created lazily on the next prompt). Nothing is interrupted. |

`session:"keep"` forces `kept` and is refused for a harness change. `session:"new"` forces `new`.

5. Push `agent_swapped` to every client (the same fan-out `role_binding_set` already uses).

### 4.3 What a swap cannot do, stated plainly

**A conversation cannot cross harnesses.** `providerSessionId` is a harness-specific resume handle
(Claude's `--resume`, Pi's `--session` file...). After a cross-harness swap the new session starts
empty, with the agent's persona and role, and the UI posts a "Swapped from X to Y; previous
conversation: <link>" note. **Handoff context** (the daemon injecting the tail of the old transcript as
the new session's first prompt) is a real improvement but needs a spike first: it depends on what the
daemon durably stores per session. It is slice S12, not part of v1, and D3 asks the Owner whether v1
without it is acceptable.

### 4.4 Tickets and triggers

Nothing to change: an in-flight ticket run is untouched; the next ticket start resolves the executor
through `executorFor` and picks up the swap. A `role:X` ticket continues to need at least one agent of
role X. (Allowing a role-lane ticket to run on the bare role binding, now that `SpawnOptions.role`
exists, is a possible follow-up and is deliberately not in scope.)

---

## 5. Where each harness's provider/model configuration comes from

### 5.1 Is the `models` frame per provider or per harness?

**Per harness, in practice, but only for harnesses the daemon was configured with as an
`AgentProvider`.** The frame is `List<ProviderModels>`; each row is `(provider = harness id, group =
vendor, label, models, note?)`. `modelCatalog(manager)` flat-maps `manager.providers` (configured
providers). A harness that is registered only as a plugin or a descriptor appears in the `providers`
frame (`ProviderInfo(id, false, null)`) with `models = null` and has **no `models` row**. So for the
Owner's three choices: harness list = `providers` frame (complete); provider+model lists = `models`
frame (complete for OpenCode and Claude, **missing for Pi and any descriptor harness**).

### 5.2 Source table

| Harness | Provider/model source | Provider = | On the wire today | Needed |
|---|---|---|---|---|
| **OpenCode** | `OpenCodeConfigReader` + `ConfigFirstModels`: the providers configured in OpenCode's own config (308 ids measured), grouped by the prefix before `/` | the prefix (`anthropic`, `deepseek`, ...) | yes (`models`) | nothing |
| **Claude Code** | `ClaudeConfigReader`: `settings.json` (`availableModels`, aliases) | one group, `anthropic` | yes (`models`) | nothing. UI: the provider step is skipped (one provider, auto-selected). |
| **Pi** | `pi --list-models` prints a table (`provider model context max-out thinking images`), 179 models in 6 providers, measured live. Pi also keeps `~/.pi/agent/models.json`, `settings.json` and a per-provider cache `models-store.json` (`models[]`). **`auth.json` holds secrets and is never read.** | the first column of `--list-models` | **no** | a Pi catalog source (S4) |
| **JSON descriptor** (`harnesses/*.json`) | none | none | no | an optional `models` section in the descriptor (S5): a static list, or a `modelsCommand` argv whose stdout lines are model ids |

### 5.3 How the gap closes without a HARNESS contract bump

A daemon-side **`HarnessModelCatalogs`**: a registry keyed by harness id that yields `List<ProviderModels>`
for harnesses that have no `AgentProvider`. Built-in source: `PiModelCatalog`. Descriptor source:
the descriptor's `models` section. `modelCatalog(manager)` becomes
`manager.providers rows + HarnessModelCatalogs rows (for harness ids not already covered)`. Rules:

- A source that fails yields a noted empty row ("could not list models: ..."), exactly like
  `modelCatalog` already does for a failing provider; one bad source never hides the others.
- Pi: prefer `pi --list-models` (authoritative, includes models available through logged-in
  providers), run as a bounded child process (timeout, killed on cancel, argv list, never a shell) with
  a short in-memory cache (the same "re-read when New agent opens" refresh `refreshModelCatalog` already
  triggers). Fall back to `models-store.json` when `pi` is not on `PATH`. Parse tolerant of extra
  columns; never log file contents (the CP-1 leak rule used by the descriptor loader applies).
- The model id sent to a harness is whatever that harness expects. For Pi that is `provider/model`
  or the bare id as `--list-models` prints it; the slice's RED test pins the exact spelling against a
  recorded fixture rather than a guess.

Rejected alternative: add `Harness.models()` to the plugin interface (HARNESS `1.4.0`). It is the
"cleaner" seam, but the exact-match handshake would refuse every plugin built against `1.3.0`. Owner
decision D8 records this trade-off; the recommendation is the sidecar registry, with the interface
method deferred to the next intentional plugin-contract bump.

---

## 6. The Add-agent flow (UI, `infantryUiCore`)

### 6.1 Flow

```
 Step 1  ROLE      role grid + search           (required)
 Step 2  EXECUTOR  Harness -> Provider -> Model (pre-filled from the role default; editable)
                   [+ effort when the harness supports it]
 Step 3  AGENT     name (defaults to the role name), first task (optional)
         [ Add agent ]   -> agent_save (creates the persistent agent)
                         -> new + prompt  (starts its first session, if a task was typed)
```

- **Step 1** replaces `SpawnRoleRow`'s chip row with a grid of role cards (RoleBadge, role name, a
  one-line description, and a muted chip showing the role default: `opencode - deepseek/...` or
  `daemon default`). A search field above the grid filters by role id and name (the same ranking
  function as the executor lists, section 7). Roles come from the `roles` state that already exists.
- **Step 2** is the three-choice picker of section 7, **pre-filled** from the role binding. Untouched,
  it saves NO override on the agent (the agent inherits, and a later role rebind still reaches it).
  Touched, it saves an override. The state is always visible as a chip: `inherits role default` /
  `pinned`.
- **Step 3** is today's `PromptBox`. A blank task creates the agent and starts nothing.
- **Quick run** (no persistent agent, e.g. "just ask QA a question"): a secondary action in the dialog,
  `Run once as <role>`, sends `new` with `SpawnOptions.role` and the executor, then `prompt`. This keeps
  today's one-shot behaviour, now attached to the role.

### 6.2 Mapping to what exists

| New | Replaces / reuses |
|---|---|
| `AddAgentDialog` (role grid, executor, task) | The spawn-mode stack in `App.kt`: `SpawnRoleRow` -> `ModelCatalogPicker` -> `PromptBox`. `PromptBox` is reused as step 3. |
| `ExecutorPicker` (Harness/Provider/Model + effort) | `ModelCatalogPicker`, `ModelPickerField`, and the list half of `RoleBindingEditor`. All three call the same composable. |
| `SearchableSelect<T>` | New, generic (section 7). |
| Pure model `ExecutorPickerModel` | Built on `ModelCatalogModel.catalogSections` / `ModelPickerModel.modelGroupChoices` (UI-PROV-1), which already group by vendor. Extended, not rewritten; `PickedModel` stays the carried value. |
| `Controller.addAgent(...)`, `swapAgent(...)` | New. `spawn`, `pickModel`, `pickSpawnRole`, `saveRoleBinding`, `refreshModelCatalog` stay. `pickedModel` / `spawnRole` in `AppState` stay as the dialog's draft. |

---

## 7. The searchable Harness -> Provider -> Model picker

```
 Executor                                          inherits role default  [ Reset ]
 Harness   [ OpenCode ] [ Claude Code ] [ Pi ] [ + custom ]
 Provider  [ Search providers...                      v ]   All (14)
 Model     [ Search models... "son 4"                   ]
           anthropic   claude-sonnet-4-5                   <- 3 of 308
           anthropic   claude-sonnet-4
           openrouter  anthropic/claude-sonnet-4
 Effort    ( low ) ( medium ) ( high ) ( xhigh ) ( max )     <- only where supported
```

Rules:

1. **Harness** is a short list (cards or chips); from the `providers` frame. Choosing one reloads the
   provider and model lists from that harness's `models` rows and clears any provider/model that
   does not belong to it (a model id belongs to one harness: the same rule `executorFor` applies).
2. **Provider** is a `SearchableSelect`. Default `All (n)`; **auto-selected and collapsed to a label
   when the harness has exactly one provider** (Claude Code). Choosing one narrows the model list.
3. **Model** is a `SearchableSelect` that is always searchable. Picking a model while Provider is
   `All` sets the Provider to the model's vendor (the reverse arrow), so the three choices always end
   up consistent.
4. A harness with **no catalog** (a descriptor with no `models` section, or a failed listing) keeps
   today's free-text model field plus its `defaultModel`, and shows the row's `note` ("could not list
   models: ...") instead of an empty list. It never shows an empty, selectable-nothing list.
5. Keyboard: type to filter, Up/Down to move, Enter to choose, Esc to close, Backspace on an empty
   box reopens the previous step. Every control has a stable test tag
   (`executor-harness-<id>`, `executor-provider-<group>`, `executor-model-<id>`, `executor-search`).

### 7.1 `SearchableSelect<T>` and the filter

- A generic composable in `infantryUiCore` (no platform code: JVM + Android share it), over a
  `LazyColumn` (308 + 179 rows must not compose at once).
- The ranking is a **pure function in `commonMain`** with no Compose types, so it is unit-tested without
  a UI: `filterRanked(items, query, text = { id, label, group })`. The query is split on whitespace;
  every term must match somewhere (AND); matching is case-insensitive substring; rank is
  exact id > id-segment prefix > label prefix > substring in id/label > substring in group; ties keep
  the daemon's order. Empty query = the original order. No fuzzy/subsequence matching in v1 (it would
  need a threshold to avoid noise, and AND-substring already makes `son 4` find `claude-sonnet-4`).
- Recently used executors (kept in `AppState`, not persisted by the daemon) float to the top of an empty
  query. Optional, slice S7.

---

## 8. Migrating existing, agent-first sessions

Short answer: **nothing is rewritten.** Why each case is safe:

| Today's data | After this change | Action |
|---|---|---|
| `agents` rows (role required, provider/model maybe set) | Already "agent in a role". `provider`/`model` non-null = pinned override; null = inherits. | None. Add column `effort` (nullable). |
| `role_bindings` rows | Unchanged: they are the role defaults. | None. |
| Sessions started from a profile (`agentId`, `role` copied) | Already attached to an agent and role. | None. |
| Sessions started from the New-agent box (no `agentId`, `role` null) | They remain valid ad-hoc sessions. The left list shows them under **Quick runs**. | None. A later optional action, **Adopt into agent** (S13), creates an agent from such a session (the user picks the role; the executor is copied). The role is NEVER inferred. |
| Tickets with `agentId` = profile id or `role:X` | Unchanged. | None. |
| Clients on the old contract | Never send the new fields; strict decode only refuses unknown keys, and old clients send none. | The new dialog is gated on the contract version. |

Rollback is trivial: `agents.effort` is nullable and unread by an old daemon; dropping the new frames
leaves the old flow working unchanged.

---

## 9. Cleaned-up layout

The mess has one cause: **the UI is organised by object type (agents, sessions, profiles, roles,
bindings) instead of by the thing the Owner reasons about, "who is on my team, and what are they
doing".** The same agent appears as a roster entry, a session row, a profile card and a role chip.

### 9.1 Left panel: one row per agent

```
 +--------------------------------+
 | Project  [ KotlinProject  v ]  |
 | [ search team...  role:qa ]    |
 |                                |
 | TEAM                           |
 |  (PM) Project Manager      *   |   * = state dot (running / idle / needs you)
 |       claude - sonnet-4.5      |   <- executor chip (muted), "inherits" if unpinned
 |  (QA) qa                   !   |   ! = needs input / attention count
 |       opencode - deepseek/v4   |
 |  (BE) Backend dev          .   |
 |       pi - deepseek-chat       |
 |   v 2 more sessions            |   <- sessions nest under their agent, collapsed
 |                                |
 | QUICK RUNS                     |
 |  fix lint (opencode)       .   |   <- sessions with no agent
 |                                |
 | [ + Add agent          ^N ]    |
 +--------------------------------+
```

- **One row per agent** (role badge, name, state dot, attention count, executor chip). Selecting a row
  opens its current session in the centre. A disclosure shows its other sessions (latest 3, "more").
- **The roster section and the session list merge** into this one list; sessions with no agent go to
  **Quick runs**. The role chip filter row is removed: role filtering is the search box
  (`role:qa`, plus free text over name and executor).
- The counters strip (running / waiting / ...) stays, above the list, unchanged.
- "+ Add agent" is the single entry point (and `^N` keeps working).

### 9.2 Centre

- **Roles and Profiles tabs merge into one `TEAM` tab** (10 -> 9 tabs). It has two sections: **Roles**
  (each role, its default executor, how many agents inherit it, `Change default` = the shared
  `ExecutorPicker` -> `role_binding_set`) and **Agents** (cards: persona, executor `inherits`/`pinned`,
  `Swap`, delete). Nothing about executors is edited anywhere else.
- The remaining tabs (OUTPUT, PODS, BOARD, TRIGGERS, EPICS, MEMORY, RUN, GIT) are NOT reorganised by
  this design. A further grouping into primary tabs plus a "More" menu is a follow-up the Owner can ask
  for; it has no data-model dependency.

### 9.3 Right panel: an agent inspector

```
 +--------------------------------------+
 | (QA) qa                    * running |
 | opencode - deepseek/deepseek-v4      |
 | inherits role default      [ Swap ]  |
 | session 3 of 5      [ new session ]  |
 +--------------------------------------+
 | Worktree | Review | Usage            |
 +--------------------------------------+
 | ...tab body...                       |
```

- A **sticky header** names who the panel is about (role, name, executor, pinned/inherited, state) and
  carries the **Swap** action, so the swap is one click from wherever you are looking at the agent.
- **ATTENTION leaves the right panel's tab row** and becomes a count badge on the top bar plus a
  pop-over list (it is cross-agent by nature, so a per-agent panel is the wrong home). The Attention
  pane component is reused unchanged. This is the one part of the layout that moves an existing
  feature, so it is called out as D6 for the Owner.
- WORKTREE / REVIEW / USAGE stay as they are.

### 9.4 Overlap with in-flight work

Ticket `fc2e2c51` (worktree `.worktrees/ux-git-side-panel`) has UNCOMMITTED edits to `App.kt`,
`ProjectGitPane.kt` and `Controller.kt` plus a new `ProjectChangesPanel.kt`. The layout slices (S9, S10,
S11) edit the same `App.kt` regions, so **they are sequenced after `fc2e2c51` merges**. Slices S1-S8
touch disjoint files and can start now.

---

## 10. Sliced plan (small TDD tickets, dependency order)

Every slice follows the evidence bar (`docs/INFANTRY-EVIDENCE-BAR.md`): tests written RED first, then
the code, then a mutation check on the production line the test guards. Every new branch needs a line in
`infantry/preflight/expected-bases.properties`. The PM allocates the contract number `NEXT`; the Git
Expert merges.

| # | Slice | AREA | Depends on | Parallel with |
|---|---|---|---|---|
| **S1** | `SpawnOptions.role` + role on a profile-less session. `ResolvedSpawn.role`, role-exists check, role/agent disagreement refused, binding read for executor. Tests: spawn by role gets the binding's executor and the session's `role`; unknown role refused; mismatch refused; spawn with `agentId` unchanged. | `infantry/protocol` + `agents` (+ `CONTRACT.md` draft section) | - | S2, S4, S6 |
| **S2** | `agents.effort` (migration `23.sqm`, `.sq`, `AgentRepository`, `AgentProfile.effort`, validation, `executorFor` chain). `SchemaMigrationTest` parity. | `infantry/persistence` + `agents` | - | S1, S4, S6 |
| **S3** | `validateExecutor` factored out of `AgentProfiles.save`; `agent_swap` / `agent_swapped` frames and handler (algorithm of 4.2, session outcomes `kept`/`new`/`none`, all-or-nothing). Tests: refusals write nothing; same-harness keeps the session; cross-harness creates one; running run untouched; push reaches every client. | `infantry/agents` + `daemon` + `protocol` | S1, S2 | S4, S5, S6 |
| **S4** | `HarnessModelCatalogs` registry + `PiModelCatalog` (`pi --list-models` parser tested on a recorded fixture, bounded child, `models-store.json` fallback, notes not content). `modelCatalog` merges its rows. | `infantry/provider` + `harness/pi` + `daemon` | - | everything except S5 |
| **S5** | Descriptor `models` section (static list / `modelsCommand`) feeding the same registry. | `infantry/harness/descriptor` | S4 | S3, S6 |
| **S6** | UI-core pure model: `filterRanked` + `ExecutorPickerModel` (harness -> provider -> model state, reverse provider selection, single-provider auto-select, cleared-on-harness-change, no-catalog fallback). `commonTest` only. | `infantryUiCore/model` | - | S1-S5 |
| **S7** | `SearchableSelect<T>` composable (LazyColumn, keyboard, tags) + `ExecutorPicker` composable on top of S6; replaces `ModelCatalogPicker`, `ModelPickerField` and the list half of `RoleBindingEditor`. Compose UI tests. | `infantryUiCore/ui` | S6 | S3, S4 |
| **S8** | `Controller.addAgent` / `swapAgent` / `saveAgent(effort)` + `AppState` for the dialog + contract-version gate; `WireRequests`. Fake-daemon tests. | `infantryUiCore/net` | S1, S2, S3, S6 | S7 |
| **S9** | `AddAgentDialog` (role grid + search, executor step, task step, Quick run) replacing the spawn-mode stack. UI tests. | `infantryUiCore/ui` (`App.kt`) | S7, S8, **`fc2e2c51` merged** | S10 |
| **S10** | Left panel: one row per agent, merged roster + sessions, Quick runs, search tokens. UI tests. | `infantryUiCore/ui` (`App.kt`) | S8, **`fc2e2c51` merged** | S9 |
| **S11** | `TEAM` tab (merges Roles + Profiles), right-panel inspector header with Swap, Attention badge + pop-over. UI tests. | `infantryUiCore/ui` (`RolesAttention.kt`, `Profiles.kt`, `App.kt`) | S7, S8, S9/S10 | - |
| **S12** | (after a spike) cross-harness handoff: bounded transcript tail injected as the new session's first prompt. Spike question: what does the daemon durably store per session? | `infantry/session` | S3 | S9-S11 |
| **S13** | (optional) "Adopt into agent" for a Quick-run session; `ProviderInfo.capabilities` so the UI hides effort where unsupported; CLI parity (`infantry agent swap`). | mixed | S3, S8 | - |
| **S14** | `CONTRACT.md` final section "What changed in NEXT" + this doc's status; `EventVocabulary.VERSION = NEXT` with the version test. | `infantry` docs/contract | S1-S5 | - |

Dependency graph (arrows = "must land first"):

```
S1 --\                   S6 --> S7 --\
S2 ---+--> S3 --> S8 ------------>   +--> S9 --\
S4 --> S5                S6 --------/  S10 ----+--> S11 --> (S12, S13)
                                       (S9, S10 wait for fc2e2c51)
S14 closes the daemon half (after S1-S5).
```

Parallelisable now (disjoint files): {S1, S2, S4, S6} in four tickets at once; then {S3, S5, S7} once their
inputs land. The UI layout slices S9-S11 are the only ones that touch `App.kt`, so they serialise
among themselves and behind `fc2e2c51`.

Sizing note for free-model workers: S1, S2, S4, S5 and S6 are each one file group with a fixture-driven
test and are sized for a free model; S3 (the all-or-nothing swap with session outcomes) and S9-S11 (shared
`App.kt`) are the ones that want the most careful review.

---

## 11. Decisions needed from the Owner (before any code)

Each has a recommendation; the architect decides everything not listed here.

| # | Decision | Recommendation |
|---|---|---|
| **D1** | **"Session first" reading.** A session needs an executor to exist, so the design chooses role + session identity first and resolves the executor (pre-filled from the role default, editable) before the process starts. Accept that reading? | Accept (3.3). |
| **D2** | **Does "Add agent" create a persistent, named agent** (an `AgentProfile` in the project), with "Run once as <role>" as the secondary quick action? Or is Add agent always a one-off session? | Persistent agent by default, plus Quick run. |
| **D3** | **Cross-harness swap without conversation continuity in v1.** The new session starts empty (persona + role kept, old session linked and still resumable on its own harness). Handoff context is a later slice (S12). Acceptable for v1? | Accept; handoff after a spike. |
| **D4** | **Swap while the agent is running.** The run finishes on the old executor; the swap applies from the next run (no interrupt). The alternative interrupts the run (the `interrupt` frame exists). | Apply at next run; never interrupt silently. |
| **D5** | **Default executor per role.** Today an unbound role falls back to the daemon default. Should the daemon ship seeded defaults for the built-in roles, or should the Team screen show `daemon default` and let you bind once? | No seeded defaults in code (they would hard-code vendors and prices); the Team screen shows unbound roles and offers "Bind to..." |
| **D6** | **Layout moves:** (a) Roles + Profiles tabs become one TEAM tab; (b) the left list becomes one row per agent, sessions nested, Quick runs separate, role chips replaced by search tokens; (c) Attention leaves the right panel's tab row for a top-bar badge + pop-over; (d) the right panel gets a sticky agent header with Swap. Approve the mockups of section 9? | Approve (a), (b), (d). (c) is the only one that relocates a feature; confirm separately. |
| **D7** | **Vocabulary.** The UI says Harness / Provider / Model while the wire keeps `provider` for the harness (a rename would break every client). Accept the mismatch, documented in `CONTRACT.md`? | Accept. |
| **D8** | **Pi (and descriptor) catalogs come from a daemon-side registry that runs `pi --list-models`,** not from a new method on the plugin interface (which would be a breaking HARNESS `1.4.0`). Accept? | Accept; revisit at the next planned plugin-contract bump. |

For the PM (not the Owner): allocate the contract number `NEXT`; sequence S9-S11 behind `fc2e2c51`;
`expected-bases.properties` lines for S1-S14.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| `pi --list-models` output format changes, or `pi` is absent. | Parser tested on a recorded fixture and tolerant of extra columns; `models-store.json` fallback; a failed listing is a visible note on an empty row, never a crash. The format is a CLI surface, not a contract: this is why the registry is isolated. |
| Model-id spelling differs per harness (OpenCode `vendor/model`; Pi's own). | The slice's RED test pins the spelling from a fixture. The picker never constructs ids; it only passes through what the catalog row lists. |
| Swap races (two clients swap the same agent). | `AgentProfiles` is already `@Synchronized`; the swap is last-writer-wins, and the `agent_swapped` push tells every client the result. |
| `App.kt` conflicts with `fc2e2c51`. | Sequencing (9.4); slices S1-S8 are disjoint from it. |
| Shared-dispatch regressions (a past lesson: fixing one path broke others). | S1 and S3 keep `executorFor`'s order unchanged and add tests that the existing spawn forms (`agentId`, plain, ticket `role:X`) resolve exactly as before. |
| A bigger left-list rewrite hides sessions users rely on. | Sessions are nested, not removed; Quick runs keeps profile-less ones; the existing session-list tests are kept green or consciously rewritten in S10. |
| `ProviderInfo` carries no capabilities, so the effort row shows for every harness. | Same as today; S13 exposes capabilities when it is worth the bump. |
