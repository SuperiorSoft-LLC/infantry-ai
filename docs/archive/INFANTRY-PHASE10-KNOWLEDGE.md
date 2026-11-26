# Infantry Phase 10: project knowledge (design and slice plan)

Ticket **P10-D0** (`a1fcc4e3`), umbrella `730e519b`. Design only: no code ships with this document.
Facts were read in worktree `p10-d0-knowledge-design` at `6a2e342d`, base `5614c519` (contract **1.47.0**,
`protocol/EventVocabulary.kt:203`; last migration `25.sqm`). Code paths are relative to
`superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/` unless they say otherwise.
Every slice follows `docs/INFANTRY-EVIDENCE-BAR.md`.

## 0. Short version

- **Already built, not yet reachable by an agent:** project memory notes (`memory_save` / `memory_list` /
  `memory_search`), the prompt library (1.44.0) and the skills library (1.45.0). No first turn mentions them and
  the `infantry` CLI has no verb for them.
- **Agent surface:** new `infantry` verbs over WebSocket frames, the same road `tickets` and `epics` take
  (`cli/CliMain.kt:233-250`). No MCP (out of scope), no file mirror in v1.
- **Retrieval discipline:** every first turn that has a project gets a `knowledge` section: a pointer, live counts
  and the order to run `infantry knowledge` before exploring. It never carries note bodies or decision text. The
  order is persona → identity → traits → **knowledge** → **skills** → task, added through TRAITS T5's renderer.
- **Enforcement** is the text plus observation. A pure classifier over the normalized event stream says whether
  the first knowledge read came before the first exploring tool. The daemon cannot block a harness's tool calls,
  so nothing pretends to.
- **`memory_save`** gets three modes with named errors: `replace` (default, today's behaviour),
  `append`, and `edit` with `find` (exactly once) + `content`. Refusals are `anchor_not_found` and
  `anchor_ambiguous`. Append and edit never create a note (`note_not_found`). `memory_get` and `folder` reach
  the wire.
- **Decisions** get their own append-only table (database triggers), numbered `D<n>` per project. A new decision
  supersedes an old one by id. Nothing is ever edited or deleted (projectmem `add_decision(summary, supersedes)`).
- **Issues** get `issues` + `issue_events`, both append-only, numbered `I<n>` per project. The operations are
  open, attempt (`worked|failed|partial`, default `failed`), fix, and precheck by path (projectmem `log_issue`,
  `record_attempt`, `record_fix`, `precheck_file`). The active issue is per **session**, not per project,
  because agents run concurrently.
- **Skills** attach at three levels, using the names PHASE9-SPEC reserved (`skillIds`, `skillsMode`
  merge|replace, `includeSkillsContext`) plus a per-skill `attachedOnly`. Attached skills are injected
  in full (capped) as "always available". Every other skill gets one `name: description` line, and the agent
  fetches it with `infantry skill show`. The description is the trigger: there is no daemon-side matching.
  **P10 takes over TRAITS T10.**
- **Gate:** G1, a scripted test against a real daemon restarted over the same state. G2, a live run by QA on the
  worker harness.
- **Numbers:** this plan **assumes TRAITS T1–T6 take 1.48.0–1.51.0 and `26.sqm`–`30.sqm`**
  (`docs/INFANTRY-ROLE-TRAITS.md` §7). So P10 plans its wire bumps as **1.52.0–1.56.0** and its migrations as
  **`31.sqm`–`35.sqm`**. These are ordinals, not reservations: the PM allocates the real numbers in merge order,
  one wire change in flight at a time.
- **Out of scope:** the MCP server, UI (listed as later slices), and scopes other than project (later).

## 1. What exists (read in this worktree)

| Area | Fact | Where |
|---|---|---|
| Memory store | `MemoryNote(id, projectId, name, folder, description, body, updatedAt)`. `save(projectId, name, description, body, folder = "")` upserts by (project, name) and is `@Synchronized`. `index()` drops bodies. `get(projectId, name)`. `search()` ranks name > description > body, with a snippet. `checkName` refuses path separators, `..`, the `INDEX` stem, control characters and names over 200 chars. `export(projectId, dir)` writes a markdown mirror; production never calls it. | `memory/MemoryStore.kt` |
| Memory wire | `memory_search` → `memory_hits`. `memory_save` (`MemorySave(projectId, name, description, body)`, all required, strict decode) → `memory_note` (the whole note). `memory_list` (raw project id) → `memory_notes` (`MemoryIndexEntry(name, description)`, name order). A refusal is `ERROR "<frame> refused: <why>"`. **No folder on the wire, no get, no delete.** | `memory/MemoryHandler.kt:9-91`; CONTRACT 1.20.0, 1.21.0 |
| Memory SQL | `memory_notes(id PK, project_id, name, folder, description, body, updated_at, created_at)`. Search is `LIKE ... ESCAPE '\'`. An upsert is an insert-ignore followed by an update, because SQLDelight keeps only the first statement of a label. | `memory/SqlMemoryPersistence.kt:73-99`, `db/memory_notes.sq` |
| Lead notes | MVP 7 JSON store (`lead-notes.json`). Unrelated to memory notes; P10 does not touch it. | `memory/LeadNoteStore.kt` |
| Skills | `skills` table; `name UNIQUE` daemon-wide ("one daemon = one project"); tags stored as JSON. `SkillSave(name, description = "", body, tags)`, `SkillView(id, name, description, body, tags, createdAt, updatedAt)`. Limits: name ≤ 120, description ≤ 500, body ≤ 64 KB, ≤ 16 tags. `skill_save/list/get/delete` → `skill_item` / `skills`, pushed to every client. Nothing attaches a skill to anything. | `db/skills.sq`, `24.sqm`, `protocol/Skills.kt`, `persistence/SkillStore.kt`; CONTRACT 1.45.0 |
| Prompts | Same shape as skills (1.44.0). No agent path uses them, and P10 leaves them alone. | `db/prompts.sq`, `23.sqm` |
| Settings | Closed key set with one key, `context.includeLinkedTasksContext` (JSON boolean). No project key, so settings are daemon-global. | `settings/SettingsService.kt`, `db/settings.sq` |
| First turn | `firstTurn(instructions, role, agentId, prompt)` = persona → `identityLine` → task. Callers: `TicketBoard.startPromptUnattended` (`:224`); `DaemonMain` never-run named (`:1169`, live profile), new session via `resolved.firstPrompt` (`:1198`), never-run current (`:1217`); `MailRouter` never-run delivery (`:193`, live profile). | `agents/AgentProfiles.kt:288-311` |
| Ticket first turn | `TicketContextComposer` sources: `infantry` → `team` → `instructions` → `identity` → `ticket` → `linkedTickets`. Each source is measured in `SessionRecord.contextSources`. The `infantry` source prints `Project: ${input.projectId ?: "none"}` and says tickets, agents and mail live in the daemon, "not in project memory files". | `context/TicketContextComposer.kt:10-33` |
| Project id | `handOut` builds `ContextInput(projectId = null)` (`TicketBoard.kt:565`) but passes `row.projectId` to `startNew`. A trigger run starts with `projectId = null` (`:235`). `SessionManager.startNew` exports `INFANTRY_PROJECT_ID` when it has a project id. | `backlog/TicketBoard.kt`, `session/SessionManager.kt:271,499` |
| Agent CLI | `infantry` verbs dispatch at `cli/CliMain.kt:233-250` and talk WebSocket frames (`tickets` sends `ticket_list` with the project id, `:535`). The TEAM_BLOCK teaches agents `agents --live`, `send`, `inbox` and `read`. No verb reaches memory, skills or prompts. | `cli/CliMain.kt`, `infantry/docs/AGENT-QUICKREF.md` |
| Access | One bearer token per daemon, or loopback with no token. The identity environment (`INFANTRY_SESSION_ID`, `INFANTRY_AGENT_ID`, `INFANTRY_ROLE`, `INFANTRY_PROJECT_ID`) is declared by the caller, not authenticated. | `daemon/AccessPolicy.kt` |
| Events | `EventNormalizer` maps an opencode `{"type":"tool","part":{"tool":"bash",...}}` line to `Tool("bash")` and a claude `tool_use` block to `Tool("Bash")`. Activity text keeps `command`, `file_path`, `filePath`, `path`, `pattern`, `url` and `query`, capped at 100 chars. | `EventNormalizer.kt` |
| UI decoding | infantryUiCore decodes with `ignoreUnknownKeys = true` (`Model.kt:765`, `Controller.kt:86`, `DaemonClient.kt:38`, `WireProtocol.kt:409`), so new fields in answers are safe. The daemon decodes requests strictly. | `infantryUiCore` |
| Test rig | `DaemonFixture.launch(runner, stateDir, storePath, ...)` can restart over the same state. `ScriptedRunner(StubScript)` emits scripted stdout lines, and `ScriptedProcess.deliveredInput` holds what the daemon sent the agent. | `src/test/.../daemon/DaemonTestHarness.kt`, `runner/ScriptedRunner.kt` |
| Reservations | PHASE9-SPEC §2.2, §2.7 and §3 reserve `includeSkillsContext`, `skillsMode` (merge by default) and `skillIds` on project and ticket. Project memory is "a pointer to the index, not note bodies; decide in Phase 10". No field ships before the daemon serves it. | `infantry/docs/PHASE9-SPEC.md` |
| TRAITS | T4 freezes an immutable `session_instances` row (`29.sqm`). T5 makes every first turn persona → identity → traits → task, read from that instance, and adds a `traits` source between `identity` and `ticket`. That is no bump: it changes prompt text and adds one `contextSources` value. T10 (later) reserves skills on the instance plus `infantry skill show`. | `docs/INFANTRY-ROLE-TRAITS.md` §4, §7 |

## 2. Gaps and pitfalls found while reading

1. **Ticket first turns say `Project: none`.** `TicketBoard.kt:565` passes `projectId = null` to the composer, so
   `TicketContextComposer.kt:15` prints `Project: none` even though the session's environment carries the real id.
   `TicketContextStartTest` pins that text. Any section keyed by project would render nothing on the most-used
   path. K0 fixes it first.
2. **Trigger runs have no project** (`TicketBoard.kt:235`). They get no knowledge section, which is correct until a
   trigger carries a project.
3. **The `infantry` source contradicts the new section.** It says "not in project memory files" right before a
   section that says "read the project knowledge first". K9 rewords it and updates its pins
   (`TicketContextComposerTest`, `TicketContextStartTest`).
4. **`memory_save` cannot set a folder.** The store has a folder parameter but `MemorySave` has no folder field, so
   every note written over the wire lands in folder `""`. Also, **no frame returns one note by name**.
5. **The scopes do not line up.** Memory is per project (every frame carries `projectId`). Skills, prompts and
   settings are daemon-global. So a skill "project baseline" has to be a daemon setting, the same way
   `context.includeLinkedTasksContext` already is.
6. **Never-run first turns read the live profile** today (`DaemonMain.kt:1169`, `MailRouter.kt:193`), and T5
   fixes that. P10 must add its sections inside T5's renderer, never beside it, or both phases will edit the same
   five call sites.
7. **Identity is declared, not proven.** A write is attributed to the session named in `INFANTRY_SESSION_ID`. The
   daemon resolves that session's project and refuses a mismatch with `projectId` (`project_mismatch`). A hostile
   local process could still lie. That is the same trust model mail already uses, and it is acceptable on a
   loopback or token-guarded daemon.
8. **CRLF line endings.** A body saved from Windows can hold `\r\n`, while a multi-line `find` typed in a shell holds
   `\n`. The edit is then refused with `anchor_not_found` and nothing changes, so the failure is safe. The CLI help
   tells agents to anchor on a single line.
9. **The activity text is capped at 100 chars.** `cd <long path> && infantry knowledge` can lose its `infantry`
   part. The classifier (K10) must read the tool **input**. If the normalized event keeps only the capped text,
   K10 reads the raw line.
10. **SQLDelight keeps only the first statement of a label** (`SqlMemoryPersistence.kt:85-87`). Append-only
    triggers and partial indexes therefore go into the `.sqm` and `.sq` schema as separate statements, and a test
    must prove that a raw `UPDATE` really aborts.
11. **"Active issue" breaks under concurrency.** projectmem keeps one active issue per project, which works for a
    single user. Here several agents run at once, and a project-wide active issue would attach agent A's attempt to
    agent B's bug. K-D8 makes it per session.

## 3. Decisions (one recommendation each)

### K-D1. How agents reach knowledge
Options: (a) `infantry` CLI verbs over WebSocket frames. (b) A markdown mirror on disk, read
with the harness's own Read/Grep, with writes through a tool. (c) MCP tools. (d) Bodies in the first turn.
**Recommended: (a).**
- Every harness has a shell, and every agent already runs `infantry send` and `infantry inbox`.
- The frames stay the single source of truth.
- Each read is a visible tool call in the event stream, which is what makes the gate measurable.

(b) is cheap, since `MemoryStore.export` already writes the files. But a mirror is a second
copy that can disagree with the store, and reading the wrong artifact is the most expensive error class on this
board. The discipline carries over without the files: "read the index, walk down folders, grep a term" becomes
`infantry knowledge`, `infantry memory index --folder` and `infantry memory search`. The mirror stays a cheap later
slice (L1). (c) is out of scope (the P11 track can wrap the same frames later). (d) contradicts PHASE9-SPEC ("a
pointer to the index, not note bodies") and does not scale.

### K-D2. What the first turn carries
Options: (a) nothing; (b) a pointer, live counts and the discipline; (c) (b) plus index lines (note names, decision
summaries); (d) bodies. **Recommended: (b).**
- The size is fixed (under 1 500 chars) however much the project holds.
- The counts make "the knowledge base is not empty" concrete.
- The read stays a visible act.

Index lines in the prompt would hand the agent the decision, so "without being told" would no longer hold, and the
gate could not see whether the agent read it. Rendering rules:
- A session with a project and an **empty** base gets a two-line form that names the write verbs, so the first
  session of a project learns to record.
- With **no project** there is no section, so no-project first turns stay byte-identical to today.

### K-D3. Position, and composition with TRAITS
**Recommended:** persona → identity → traits → **knowledge** → **skills** → task on every path. A ticket start gets
`infantry` → `team` → `instructions` → `identity` → `traits` → `knowledge` → `skills` → `ticket` → `linkedTickets`.
- Both sections are added through T5's single renderer, after T5 merges. P10 never reorders a T5 section.
- The reading order is: who you are, the standing rules, what the project already knows, the tools, then the job.
- `knowledge` and `skills` are new `contextSources` values. By T5's precedent (prompt text plus a new source value)
  that is **not** a wire bump. CONTRACT.md's 1.6.0 source list is extended.
- **Freshness:** knowledge counts are read when the turn renders, so a never-run session renders them at first
  delivery. Skill references are frozen on the instance at spawn (K-D10).
- **First turn, not system prompt.** This deliberately departs from the compass. No harness adapter receives a
  system prompt from the daemon today: neither `system-prompt` nor `systemPrompt` appears anywhere in infantry's
  source or in CONTRACT.md. The first turn is the one channel that claude, opencode and pi share. A system-prompt
  channel would change the HARNESS contract (1.3.0) for each provider, so it stays a later option, outside P10.

### K-D4. How the discipline is enforced
Options: (a) text only; (b) text plus observation; (c) blocking. **Recommended: (b).**
A pure `RetrievalClassifier` reads one session's normalized events and returns
`read_first | explored_first | never_read | no_tools`. G1 runs it on scripted streams and G2 on live ones.
Putting the verdict on `SessionRecord` for the UI is a later, optional bump (K16). (c) is impossible: the daemon
sees tool events only after the harness has run them and does not mediate them, and a fake gate is worse than
none. Classification rules:
- **Knowledge read:** a shell command in which some `&&` / `;` / `|` segment runs `infantry` (path and
  `.cmd/.bat/.exe/.ps1` stripped) with the verb `knowledge`, or with `memory|decision|issue|skill` followed by
  `index|list|show|search|precheck`.
- **Neutral:** `cd`; the other `infantry` verbs (`inbox`, `read`, `agents`, `send`, `tickets`, `ticket`, `threads`);
  todo and planning tools.
- **Exploration:** everything else, reads and writes alike.

### K-D5. `memory_save` modes
**Recommended:** three modes, each with named refusal codes.

| Mode | Effect | Requires | Refusals (nothing changes) |
|---|---|---|---|
| `replace` (default) | `body` becomes the whole body. Creates the note if it is missing (today's behaviour). | `description`, `body` | today's validation |
| `append` | `content` is added after the existing body, separated by exactly one blank line. The agent never has to read the note. | `content`; the note exists | `note_not_found` |
| `edit` | The single occurrence of `find` is replaced by `content`; `content: ""` removes the passage. | `find` (non-empty) and `content` (may be empty); the note exists | `anchor_not_found` (0 matches), `anchor_ambiguous` (2+), `note_not_found` |

- `description` is optional in append and edit: the stored cue is kept unless a new one is passed. Omitting
  `folder` keeps the note where it is; a new note goes to `""`.
- `find` is matched byte for byte, with no trimming and no line-ending normalization. Occurrences are counted with
  overlap, so `aa` in `aaa` is ambiguous.
- The stored note is validated against the store's existing limits. An append that would exceed them is refused.
- `replace` takes `body`, as today. `append` and `edit` take `content`. Sending the field that
  belongs to the other mode is refused.
- Append or edit on a missing note is refused with `note_not_found`.
- The refusal text puts the code first, so tests and the CLI can match it:
  `memory_save refused: anchor_ambiguous: 'find' occurs 3 times in note 'x'; pass a longer passage`.
- The `memory_note` answer keeps returning the whole note, because the UI relies on it. Keeping a big note out of
  the agent's context is the CLI's job: it prints the metadata, `chars`, and the tail (append) or an excerpt around
  the change (edit), never the body.

### K-D6. Reading notes and walking folders
**Recommended:**
- A new `memory_get {projectId, name}` frame, answered by `memory_note` or refused with `note_not_found`.
- `folder` is added to `MemoryIndexEntry`. Walking down folders is a CLI filter on the existing `memory_list`
  answer (`--folder`), not a new frame.
- Above 60 notes, `infantry memory index` without `--folder` prints the folders with their counts plus the root
  notes, so a large memory is walked down one folder at a time.

### K-D7. Decisions
Options: (a) memory notes by naming convention; (b) their own append-only table; (c) delegate to projectmem.
**Recommended: (b).** (a) leaves notes editable, so "never edit history" becomes a convention that a free model
breaks on day one. (c) is a per-user tool outside the daemon that other agents and the UI cannot see.
- **Shape:** `decisions(project_id, number, summary, body, supersedes, author_session_id, author_agent_id,
  author_role, created_at)`, primary key `(project_id, number)`, shown as `D<number>`.
- **Append-only in the database:** `BEFORE UPDATE` and `BEFORE DELETE` triggers
  `RAISE(ABORT, 'decisions are append-only')`.
- **Supersedes** must name a decision of the same project that is not already superseded (`decision_not_found`,
  `already_superseded`). A partial `UNIQUE(project_id, supersedes) WHERE supersedes IS NOT NULL` backs the store's
  check. Chains are fine (D3 supersedes D1, then D7 supersedes D3).
- **Active** means not superseded. Lists show only active decisions unless `--all` is passed, which prints chains.
- **Sizes:** `summary` is one line of at most 200 chars and states the decision; `body` is at most 16 000 chars and
  explains why.
- **Who writes:** any agent may add a decision, and its author fields come from the declared session. A typo is
  corrected by a new decision that supersedes it. There is no edit and no delete, with no exception.
- **Frames:** `decision_add {projectId, sessionId?, summary, body?, supersedes?}` → `decision_item` (pushed to every
  client); `decision_list {projectId, includeSuperseded = false}` → `decisions`;
  `decision_get {projectId, ref}` → `decision_item`. `ref` accepts `D7` or `7`.

### K-D8. Issues, attempts and fixes
Options: (a) notes by convention; (b) backlog tickets; (c) `issues` + `issue_events`, append-only.
**Recommended: (c).** (b) mixes work tracking with knowledge, and agents may not move the board; an issue keeps an
optional `ticketId` link instead. (a) loses the structure of the attempt history.
- **Shape:** `issues(project_id, number, summary, location, ticket_id, implicit, opened_by_session,
  opened_by_agent, created_at)`, shown as `I<n>`, and
  `issue_events(project_id, issue_number, seq, kind attempt|fix, summary, outcome, location, session_id,
  agent_id, created_at)`. Both tables are append-only by trigger. Status is derived: an issue is `fixed` once it has
  a fix event.
- **`open(summary, location, ticketId?)`** returns `I<n>`, which becomes the session's active issue. `location` is
  required (projectmem `log_issue(summary, location)`).
- **`attempt(summary, outcome = failed, location?, issue?)`**: outcome is `worked`, `failed` or `partial`, and the
  default is `failed`, projectmem's safer default. Without `issue`, the attempt goes to the session's active issue.
  If there is none, an implicit issue is opened from the attempt's text and flagged `implicit` (projectmem L-008),
  so an attempt is never lost.
- **`fix(summary, issue?)`** closes the named issue or the active one. With neither, it is refused with
  `no_active_issue`. A second fix is refused with `already_fixed`; a recurrence is a new issue, and precheck brings
  up the old one.
- **The active issue** is the most recent open issue that this session opened or named in an attempt (per session;
  see §2.11).
- **`precheck(path)`** returns the issues whose location is that path or lies under it. Paths are compared by
  segment after mapping `\` to `/` and dropping any `:line` suffix, so `src/a` does not match `src/ab`. Open issues
  come first, with their attempts.
- **Frames:** `issue_open`, `issue_attempt` and `issue_fix` → `issue_item` (pushed);
  `issue_list {projectId, includeFixed = false, location?}` → `issues`; `issue_get` → `issue_item`;
  `issue_precheck {projectId, path}` → `issues`.

### K-D9. Skill attachment
**Recommended:** the names PHASE9-SPEC reserved.

| Level | Fields | Rule |
|---|---|---|
| Agent profile | `skillIds` | The "always-on toolkit". On `agent_save`, an absent or null field keeps the stored list and `[]` clears it (T3's `traitIds` rule). |
| Project baseline | settings `context.skillIds`, `context.includeSkillsContext` (default true) | Daemon settings, like `context.includeLinkedTasksContext` (§2.5). |
| Ticket | `skillIds`, `skillsMode` (`merge` default, or `replace`), `includeSkillsContext` (null inherits) | `effectiveContext` gains `includeSkillsContext` with its `source`, the same way as the linked-tasks switch. |
| Skill | `attachedOnly` (default false) | Hidden from every session the skill is not attached to: absent from the first-turn index and from `infantry skill list`. |

- **Effective set:** if `includeSkillsContext` is false, there is no skills section at all. With `replace`, only the
  ticket's ids apply. With `merge`, the agent's ids come first, then the project's, then the ticket's,
  de-duplicated so the first occurrence wins.
- **Ids:** unknown ids are refused on save. Deleting an attached skill is allowed, because tickets keep old ids
  forever and refusing would block every delete; the renderer prints `(deleted)` for that skill.
- **Naming:** skills attach by id and can be shown by name or id (names are unique daemon-wide).
- There is no project-level `skillsMode`: nothing asks for a baseline that drops agent defaults.

### K-D10. How skills reach the agent
Options: (a) daemon-side matching of the task text against descriptions; (b) an index only, with bodies on demand
(TRAITS T10's sketch); (c) attached skills in full plus an index of the rest. **Recommended: (c).**
- Attached skills are injected in full as "always available".
- Every other skill that is not `attachedOnly` gets one `name: description` line. The agent opens it with
  `infantry skill show <name>` when the description matches its work.
- The description is the trigger, so the `skill_save` documentation says to write it as "Use when ...".

Why not the others:
- (a) is non-deterministic and cannot be tested, and a false negative silently hides the right skill. The model
  reading descriptions is the matcher that Claude Code already relies on.
- (b) makes a skill a human chose depend on the agent deciding to fetch it, which is the exact failure the
  knowledge gate exists to catch.

**Budgets:** attached bodies take at most 24 000 chars in total. A skill that does not fit drops to its name, its
description and the `show` command; a body is never cut in the middle. The index is capped at 40 lines and 6 000
chars, followed by `N more: infantry skill list`.

### K-D11. Freezing skills, and TRAITS T10
**Recommended:** the effective skill ids are resolved at spawn and written to the T4 instance
(`session_instances.skill_ids`, set on insert and immutable like the rest of the row). Bodies are read when the
turn renders. A spawn without an instance (anonymous) gets no skills section, consistent with T4. **P10 takes over
T10**, so skills in the first turn have one owner; the PM should mark T10 as moved here. T10's role-level skill
lists are deferred (L4): role traits already carry role-wide standing rules, and nobody has asked for role skills.

### K-D12. Scope and authorship
**Recommended:** in v1, notes, decisions and issues are project-scoped, and skills and prompts stay daemon-global.
Folder, global and agent scopes are a later option (L2). Every decision and issue write records the
declared session, agent and role.

### K-D13. Sequencing and numbers
- **Wire chain:** the wire changes merge strictly one at a time, after TRAITS T6 (1.51.0):
  K2 1.52.0 → K4 1.53.0 → K6 1.54.0 → K11 1.55.0 → K12 1.56.0.
- **Migrations:** K4 `31.sqm`, K6 `32.sqm`, K11 `33.sqm`, K12 `34.sqm`, K13 `35.sqm`.
- **Wire-neutral slices** run beside the chain once their inputs have landed.
- **Prompt text names only what is served.** PHASE9-SPEC's rule ("no field ships before the daemon serves it")
  extends to prompt text: a section never names a verb the daemon does not serve yet. So the knowledge section
  lands after every verb it names.

### Copied semantics, at a glance

| Source | Their name | Infantry |
|---|---|---|
| projectmem | `add_decision(summary, supersedes)` | `decision_add` / `infantry decision add --supersedes D<n>` |
| projectmem | `log_issue(summary, location)` | `issue_open` / `infantry issue open` |
| projectmem | `record_attempt(summary, outcome worked/failed/partial = failed, issue_id?, location?)`, implicit issue | `issue_attempt`, the same rules |
| projectmem | `record_fix(summary, issue_id?)` | `issue_fix` |
| projectmem | `precheck_file(path)` | `issue_precheck` / `infantry issue precheck` |
| **Deviations** | active issue per project; markdown mirror read with file tools | active issue per **session** (§2.11); no mirror in v1 (K-D1) |

## 4. The texts

### 4.1 `knowledge` section

Full form, when the base holds anything:

```
PROJECT KNOWLEDGE (this applies to your whole session, including after a context compaction). Project <projectId> keeps a shared knowledge base in the Infantry daemon: <n> notes, <d> active decisions, <i> open issues.
Before you explore the code, and before you decide anything an earlier session may already have decided, run `infantry knowledge`: it lists the active decisions, the open issues and the note index. Then walk down: `infantry memory index --folder <folder>`, `infantry memory search <term>`, `infantry memory show <name>`, `infantry decision show D<n>`, and `infantry issue precheck <path>` before you change a file. An index line is a retrieval cue, not the content: open the note before you rely on it.
Record through the same CLI, never in files: `infantry decision add` (decisions are never edited; a new one replaces an old one with `--supersedes D<n>`), `infantry issue open|attempt|fix`, `infantry memory save` (`--append` adds a section without reading the note, `--edit --find "<exact passage>"` changes a passage that occurs exactly once).
If a command fails, the knowledge base is NOT empty: report the command and its answer, then go on.
```

Empty form:

```
PROJECT KNOWLEDGE. Project <projectId> has an empty knowledge base in the Infantry daemon. Record what a later session must not re-derive: `infantry decision add` for a choice, `infantry issue open|attempt|fix` for a bug, `infantry memory save` for anything else.
```

No project: no section.

### 4.2 The reworded `infantry` source

Today: "Infantry tickets, agents and mail live in the Infantry daemon, not in project memory files."
From K9: "Infantry tickets, agents, mail and project knowledge live in the Infantry daemon and are reached with the
`infantry` CLI, never through files."

### 4.3 `skills` section

```
SKILLS ALWAYS AVAILABLE TO YOU. Follow each one whenever your work matches its description.
## <name>
<description>

<body>
...
Other skills in this project's library. When a description matches what you are doing, open the skill with `infantry skill show <name>` and follow it:
- <name>: <description>
<N> more: `infantry skill list`.
```

Either half is left out when it is empty. When both are empty there is no section, and the first turn is exactly
today's text.

## 5. CLI verbs

- The project comes from `--project` or `INFANTRY_PROJECT_ID`, and the session from `INFANTRY_SESSION_ID`, the same
  pattern `tickets` uses.
- A refusal prints the frame's text, code first, and the command exits non-zero.
- Long text comes from `--file <path>` or `-` (stdin), so shell quoting never mangles a body.
- Verbs say `show`, following the TRAITS T7/T10 CLI convention. Frames say `_get`, like `skill_get` and
  `prompt_get`.

| Verb | Frames | Output |
|---|---|---|
| `infantry knowledge` | `decision_list`, `issue_list`, `memory_list` | Counts; active decisions as `D<n> summary` (≤ 50); open issues as `I<n> summary (location)` (≤ 30); the note index (folders with counts above 60 notes); then `N more` lines |
| `infantry memory index [--folder f]` | `memory_list` | One `folder/name: description` line per note, ordered by folder then name |
| `infantry memory show <name>` | `memory_get` | The note |
| `infantry memory search <term>` | `memory_search` | Ranked hits with snippets |
| `infantry memory save <name> [--description d] [--folder f] [--append \| --edit --find <passage>] (--content <text> \| --file <path> \| -)` | `memory_save` | Metadata, `chars`, and the tail (append) or an excerpt (edit). Never the body. |
| `infantry decision list [--all]` · `show D<n>` · `add --summary <line> [--body <text> \| --file <path>] [--supersedes D<n>]` | `decision_*` | The list (chains with `--all`), one decision, or the new ref |
| `infantry issue list [--all] [--location <p>]` · `show I<n>` · `open --summary <s> --location <p> [--ticket <id>]` · `attempt [I<n>] --summary <s> [--outcome worked\|failed\|partial] [--location <p>]` · `fix [I<n>] --summary <s>` · `precheck <path>` | `issue_*` | The list, one issue with its attempts, or the new ref |
| `infantry skill list` · `show <name\|id>` | `skill_list`, `skill_get` | Name and description lines (without `attachedOnly` skills), or one skill |

## 6. The phase gate

The bar: "an agent recalls a decision from a previous session without being told, and reads it before re-deriving
it."

### G1 `KnowledgeRecallGateTest` (automated: `ScriptedRunner` + a real daemon)

1. Launch `DaemonFixture` over state directory S. Through the frames the CLI uses, add D1 "Transcript archives:
   gzip", then D2 "Transcript archives: zstd level 19" with `supersedes D1`.
2. Stop that daemon and launch a new one over S. Assert that the decisions table still holds the same two rows, and
   that it is a new daemon instance, not the same object.
3. Start a ticket on the project titled "Add transcript export". Its title and description contain neither `zstd`
   nor `gzip`. The scripted agent emits opencode tool lines: `infantry knowledge`, then `infantry decision show D2`,
   then a `read` of a source file, then text.

Assertions:
- **A1, first turn** (`deliveredInput`): the `knowledge` section is present, after `identity`/`traits` and before
  `ticket`. It says `1 active decision`, and it contains neither `zstd` nor `gzip`.
- **A2, data path** (on the new daemon): `decision_list` returns D2 only. With `includeSuperseded` it returns both,
  with D1 marked as superseded by D2. `decision_get D2` carries `zstd`.
- **A3, order:** the classifier, run over the session's normalized events as the daemon recorded them (the stream
  `cli/SessionReplay.kt` reads), returns `read_first`, and the first knowledge read it reports is the
  `infantry knowledge` event.
- **A4, negative controls:**
  - first tool `grep -rn archive src`, then `infantry knowledge` → `explored_first`;
  - no knowledge command at all → `never_read`;
  - `infantry inbox`, then `infantry knowledge` → `read_first` (neutral verbs do not count).
- **Non-vacuity:** the ticket text, the first turn and the scripted prompt all lack `zstd`, so the only way to reach
  it is a knowledge read. The decision count is asserted before and after the restart, not assumed.

Mutations (each must go RED at a named `file:line`, then be restored so that `git diff` is empty):

| # | Mutation | RED at |
|---|---|---|
| M1 | the knowledge renderer returns null | A1 |
| M2 | `decision_list` ignores `supersedes` (D1 listed as active) | A2 |
| M3 | decisions kept in memory only | A2, after the restart |
| M4 | the classifier counts any first tool as a knowledge read | A4 |
| M5 | the section includes decision summaries (`zstd` appears) | A1 |

### G2 live acceptance (QA, real harness, not automated)

1. Start a fresh daemon with project P on the worker harness the team runs (opencode on its default free model).
   In session 1, give an agent the ticket "Choose the archive format for exported transcripts and record the
   choice". This step passes once a `decision_add` from that session exists.
2. QA records a superseding decision with `infantry decision add --supersedes`, then restarts the daemon.
3. In session 2, a new session of another agent in the same project, give the ticket "Implement transcript
   export". No text in that session names the format.
4. A run passes when:
   - the classifier says `read_first`;
   - the transcript shows the active decision being read (`infantry knowledge` or `decision show`) before any
     exploring tool;
   - the result uses the active decision, not the superseded one.
5. Do three independent runs; all three must pass. A failure is fixed in the section's wording (K9), never by
   lowering the bar. Runs on claude and pi are recorded for information. The evidence (session ids, the event
   lines, the decision ids) goes to the PM.

## 7. Slices

Each slice follows the evidence bar: RED first, then the code, then a mutation on the guarded line, plus its
`expected-bases.properties` line. Numbers are ordinals after TRAITS (§0), and the PM allocates them.

| # | Slice | Area | Depends on | Wire / migration | Tests |
|---|---|---|---|---|---|
| K0 | Project id on ticket first turns: `handOut` passes `row.projectId` to `ContextInput` (`TicketBoard.kt:565`). Trigger runs stay null because they have no project to pass. | `infantry/backlog` | - (coordinate with T5, which edits the same `ContextInput` call) | none | `TicketContextStartTest`'s `Project: none` pin becomes the real id (RED today); a ticket with no project still says `none` |
| K1 | `memory_save` modes in the store: `replace`/`append`/`edit`, the three refusal codes, append/edit never create, description and folder kept, limits checked on the result | `infantry/memory` | - | none | `MemoryStoreModesTest`: each mode; each refusal leaves body and `updatedAt` byte-identical; the separator is exactly one blank line; `content ""` removes the passage; an overlapping match is ambiguous; mutation `== 1` → `>= 1` |
| K2 | Memory wire: `MemorySave` gains optional `mode`/`find`/`content`/`folder` (`description`/`body` required per mode); `memory_get`; `MemoryIndexEntry.folder`; CONTRACT.md | `infantry/memory` + `protocol` | K1; 1.51.0 merged | **1.52.0** | Wire test: a 1.47.0 four-field payload still decodes and replaces; refusal text starts with the code; `memory_get` on an unknown name → `note_not_found`; the list carries the folder |
| K3 | CLI: `infantry memory index/show/search/save` with the §5 output rules | `infantry/cli` | K2 | none | `DaemonFixture`-backed CLI tests (`CliEpicStoryListTest` pattern): round trip; refusal printed with its code and a non-zero exit; `--file` and stdin bodies; folder walk above 60 notes |
| K4 | Decisions: `decisions` table (`31.sqm`), triggers, partial unique index; `DecisionStore`; `decision_add/list/get` → `decision_item`/`decisions`, pushed | `infantry/persistence` + `protocol` + `daemon` | K2 (serial wire) | **1.53.0**, `31.sqm` | Store: per-project numbering, supersede rules, cross-project refused; raw SQL `UPDATE`/`DELETE` abort; `SchemaMigrationTest` v31→v32; rows survive a restart; mutation: drop the UPDATE trigger |
| K5 | CLI: `infantry decision list/show/add` | `infantry/cli` | K3, K4 | none | CLI tests: `--supersedes D1` and `--supersedes 1`; `--all` prints the chain; refusal codes |
| K6 | Issues: `issues` + `issue_events` (`32.sqm`), triggers; `IssueStore` (per-session active issue, implicit issue, derived status, precheck); `issue_*` frames, pushed | `infantry/persistence` + `protocol` + `daemon` | K4 (serial wire) | **1.54.0**, `32.sqm` | Two sessions' attempts never cross; implicit issue flagged; `fix` with no target → `no_active_issue`; default outcome `failed`; an invalid outcome refused; precheck matches by segment; triggers abort; survives a restart. The largest P10 slice: review it carefully. |
| K7 | CLI: `infantry issue list/show/open/attempt/fix/precheck` | `infantry/cli` | K3, K6 | none | CLI tests, including active-issue resolution from `INFANTRY_SESSION_ID` |
| K8 | CLI: the `infantry knowledge` overview (three lists, caps, `N more`) | `infantry/cli` | K3, K5, K7 | none | CLI test over a seeded project: superseded decisions and fixed issues are absent; caps print `N more` |
| K9 | Knowledge section: pure `renderKnowledge(projectId, counts)`, added through T5's renderer on every first-turn path and as the `knowledge` source in the composer; `infantry` source reworded (§4.2); CONTRACT.md source list updated | `infantry/agents` + `context` + `mailbox` + `daemon` | T5, K0, K8 | none (T5 precedent) | Renderer: no project → null, empty form, full form. Order on each path (ticket, prompt, both never-run branches, mail delivery). A never-run session renders counts at delivery. Pins: `TicketContextComposerTest` and `TicketContextStartTest` updated for the reword; `AgentProfilesTest` and `RoleSpawnIdentityTest` (no project) untouched; `AgentProfileDaemonTest` mentions `projectId`, so if its pinned spawn has a project it gets the empty form deliberately; check the infantryUi `DaemonIntegrationTest` that TRAITS names. Mutation: render after the task. |
| K10 | `RetrievalClassifier` (pure), per K-D4 and §2.9 | `infantry/session` | - | none | Table-driven over real opencode and claude lines fed through `EventNormalizer` (not hand-built events): each verdict, neutral verbs, `infantry.cmd` and full-path forms, a command longer than 100 chars |
| K11 | Skill attachment, ticket and project: `tickets.skill_ids` / `skills_mode` / `include_skills_context` (`33.sqm`); settings `context.skillIds` and `context.includeSkillsContext`; `effectiveContext.includeSkillsContext` | `infantry/backlog` + `settings` + `protocol` | K6 (serial wire) | **1.55.0**, `33.sqm` | Unknown id refused; `ticket_save` keeps and clears exactly like the linked-tasks switch does; source is `ticket`, `project` or `default`; `SchemaMigrationTest` |
| K12 | Skill attachment, agent and skill: `agents.skill_ids` + `skills.attached_only` (`34.sqm`); `AgentProfile.skillIds` (null keeps, `[]` clears); `SkillSave`/`SkillView.attachedOnly`; `skill_get` by name | `infantry/agents` + `persistence` + `protocol` | K11 (serial wire) | **1.56.0**, `34.sqm` | A save without the field keeps the list; `agent_swap` keeps it; get by name and by id; `attachedOnly` round trip |
| K13 | Resolution and freeze: pure `SkillAttachment.effective(agent, project, ticket)`; `session_instances.skill_ids` (`35.sqm`), written by T4's instance builder | `infantry/session` + `agents` | T4, K11, K12 | none, `35.sqm` | Merge order and de-duplication; replace; include = false; a ticket edited after spawn leaves the instance unchanged; an anonymous spawn gets none |
| K14 | CLI: `infantry skill list/show` | `infantry/cli` | K12 | none | CLI tests: by name, by id, unknown → refusal; `attachedOnly` skills absent from the list |
| K15 | Skills section: `renderSkills(attached, library, budgets)`, as the `skills` source after `knowledge` | `infantry/agents` + `context` | K9, K13, K14 | none (T5 precedent) | Budget degradation, never mid-body; `attachedOnly` hidden unless attached; the `(deleted)` line; index cap; include = false → no section; no skills → today's text |
| G1 | `KnowledgeRecallGateTest` (§6) | `infantry` test | K5, K9, K10 | none | The test itself, plus M1–M5 |
| G2 | Live acceptance (§6) | QA | G1 | none | 3 of 3 runs; evidence to the PM |

Order:
- **Wire chain:** `T6 → K2 → K4 → K6 → K11 → K12`.
- **Knowledge track:** `K1 → K2 → K3`; `K3 + K4 → K5`; `K3 + K6 → K7`; `K5 + K7 → K8`; `T5 + K0 + K8 → K9`;
  `K5 + K9 + K10 → G1 → G2`.
- **Skills track:** `T4 + K12 → K13`; `K12 → K14`; `K9 + K13 + K14 → K15`.
- **Ready now:** K0, K1 and K10. The gate does not wait for skills.

### Later (not P10 v1)

| # | Slice | Area | Depends on | Wire |
|---|---|---|---|---|
| K16 | `SessionRecord.retrieval` from the classifier | `infantry/session` + `protocol` | K10, K12 | own bump |
| U1 | Memory pane: append/edit, folder column, show | `infantryUiCore` | K2 | none |
| U2 | Decisions pane: active list, supersede chains | `infantryUiCore` | K4 | none |
| U3 | Issues pane: open/fixed, attempt timeline, precheck | `infantryUiCore` | K6 | none |
| U4 | Skill pickers: agent profile, ticket (with mode), settings baseline; `attachedOnly` toggle | `infantryUiCore` | K11, K12 | none |
| U5 | Session retrieval badge | `infantryUiCore` | K16 | none |
| L1 | On-disk markdown mirror through `MemoryStore.export` | `infantry/memory` | K2 | none |
| L2 | Scopes beyond project: folder, global, agent | `infantry` | K2, K4, K6 | own bump |
| L3 | MCP tools over the same frames | `infantry/cli/mcp` | K2–K6 | P11 track |
| L4 | Role-level skill lists (the rest of TRAITS T10) | `infantry/role` | K13 | own bump |
| L5 | Decision writers limited by role or trait | `infantry` | K4, T3 | own bump |
| L6 | A per-project switch that turns the knowledge section off | `infantry/settings` | K9 | own bump |

## 8. Left to the slice workers, on purpose

- **K9:** the exact `AgentProfileDaemonTest` and infantryUi `DaemonIntegrationTest` text after the change. Whether
  their pinned spawn carries a project decides between "untouched" and "empty form".
- **K0:** whether a trigger should carry a project. Until it does, trigger runs get no knowledge section.
- **K10:** whether the normalized tool event keeps the full command or only the 100-char activity text (§2.9).
