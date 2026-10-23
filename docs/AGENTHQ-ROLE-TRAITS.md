# AgentHQ role-bound traits and per-session role instances

Ticket `33ce610e` (TRAITS-T0), the design slice of `12f20467`. Design only, no code. Author: architect. Date:
2026-10-18. Verified against branch `traits-t0-design` at `3ab2b001`: wire contract **1.47.0**, next migration
**26.sqm**. Builds on [`AGENTHQ-ROLE-FIRST.md`](AGENTHQ-ROLE-FIRST.md).

Requirement (12f20467 and the Owner clarification of 2026-10-11): a role carries bound **traits**, REPORT BACK
first (commit id, test counts, uncommitted state, actual model/provider, wall time, blockers), enforced by the
role, not by reminders in each ticket. Assigning the persistent agent to a ticket spawns a session that is a CLONE
of that agent (traits, skills, instructions, executor defaults), never of its transcript. A session that ends
without the required report is marked **unreported** and raises an attention item, after at most one re-prompt.

## 0. Short version

- A **trait** is a small library row, like a skill or a prompt: a slug id, a name, a **version**, a rule text of
  at most 2 KB, and an optional structured **check** (v1 has one kind: `report`).
- **Binding:** a role's trait list lives in a `role_traits` table keyed by role id, the `role_bindings` pattern
  (`RoleBinding.kt` already says "Future per-role definition parts (traits, skills, ticket 12f20467) join the
  same role id the same way"). An agent inherits that list and may ADD traits (`AgentProfile.traitIds`).
- **Instance:** at spawn, `SessionManager.startNew` (the one place every spawn path passes through) writes one
  immutable `session_instances` row: the frozen persona plus trait snapshots. The link back to the parent is the
  existing `SessionRecord.agentId`, and the executor is the existing copied `provider/model/effort`. No
  transcript is copied, and nothing flows back to the parent in v1.
- **Injection:** one renderer. `firstTurn` becomes persona → identity → traits → task, and the ticket composer
  gets a `traits` source between `identity` and `ticket`. A session with no traits gets exactly today's text.
- **REPORT BACK** is detected in the daemon's own mailbox: a mail in the session's outbox addressed to the
  check's `reportTo` role, with every required `label: value` line. It is checked when a run ends by itself, and
  only for unattended sessions. A miss sets `report.status = unreported` and sends ONE re-prompt (a `system`
  mail). If the report is still missing after that, an attention item `unreported` is raised.
- **Wire:** four additive bumps, strictly one at a time: 1.48.0 trait library, 1.49.0 role traits, 1.50.0
  agent traits, 1.51.0 report state + attention kind. The HARNESS contract stays 1.3.0.

## 1. What exists (read in this worktree)

| Concern | Fact |
|---|---|
| Role | `Role(id, name, color, persona, source)`. Built-ins are bundled (`roles/index.json`, which has `pm`, `qa`, `architect`); custom roles are files written by `role_save` (`RoleService`). Both are catalog data, not runtime state. |
| Per-role runtime data | `role_bindings` (20.sqm) + `role_binding_list/get/set` (1.40.0); a set is pushed to every client. |
| Agent | `AgentProfile(id, name, role, provider, model, instructions, projectId, effort)`. `agent_save` overwrites the whole profile; `agent_swap` changes only provider/model/effort. |
| Copied at spawn | `SessionRecord`: provider, model, effort, agentId, role, ticketId, projectId, contextSources, unattended. **The persona is not copied**: a bound, never-run session re-reads the live profile at its first turn (`MailRouter.deliver`, both never-run branches in `DaemonMain`). |
| Spawn paths | Five callers of `SessionManager.startNew`: `TicketBoard.handOut`, `TicketBoard.startPromptUnattended`, `DaemonMain` (`new` + `prompt`), `SpawnBinding` (bind at creation), `Orchestrator` (pods, spawned anonymous). |
| First turn | `firstTurn(instructions, role, agentId, prompt)` = persona → `identityLine` → task. Ticket starts use `TicketContextComposer` sources `agenthq`, `team`, `instructions`, `identity`, `ticket`, `linkedTickets`. |
| Libraries | `prompts` (23.sqm, 1.44.0) and `skills` (24.sqm, 1.45.0): unique name, body ≤ 64 KB, tags, `*_save/list/get/delete`. **No code attaches a skill to a role, an agent or a session**; `PHASE9-SPEC.md` only reserves project/ticket `includeSkillsContext`, `skillsMode`, `skillIds` for Phase 10. |
| Mail | `Message.from` = the sender's session id (`agenthq send` speaks as `AGENTHQ_SESSION_ID`; the daemon fills a blank `from`), or the non-session handle `user`; `Message.to` = the ORIGINAL address (session id, agent id or role name). `Mailbox.outbox(session)` lists what a session sent; `Mailbox.waiting()` lists open needs-input asks. |
| Run end | `SessionEvent.Exit(sessionId, code, killOutcome)`. `HarnessEvent.Completion` carries a reason, never the text. |
| Attention | `AttentionStore.raise(kind, triggerId, ticketId, podId, sessionId, outcome, detail)`. `attention.kind` has a SQL `CHECK (kind IN (...))` (`attention.sq`, 9.sqm). The UI already renders an unknown kind (`RolesAttentionModelTest`). |
| Ticket start | `Ticket` records no starter; `handOut` only knows a `HandOutOrigin`. |

## 2. Trait model

```
Trait            library row, table `traits` (26.sqm)
  id             slug, RoleIds.PATTERN [a-z][a-z0-9-]{0,31}, chosen by the author: "report-back"
  name           non-blank, <= 80 chars: "REPORT BACK"
  version        1 on create, +1 whenever name, body or check changes (an identical save changes nothing)
  body           the rule text rendered into the first turn, non-blank, <= 2048 UTF-8 bytes
  check          optional TraitCheck
  createdAt, updatedAt
TraitCheck       v1: kind "report" only; any other kind is refused at save
  reportTo       role id the report is mailed to, must exist in the catalog; default "pm"
  fields         1..12 required labels, unique ignoring case, each [A-Za-z][A-Za-z0-9 _-]{0,31}
  reprompt       default true: one re-prompt before the attention item
```

- **A library row, not text on the role:** one REPORT BACK is bound to many roles, the version is how a session
  says which rule it ran under, and roles are files or built-ins (section 1).
- **Trait vs skill:** a trait is an always-on rule rendered IN FULL into every instance, so it stays short; the
  2 KB cap is that boundary. A skill is an on-demand procedure (≤ 64 KB). Anything longer belongs in a skill.
- **Binding:** `role_traits(role_id PK, trait_ids JSON array, updated_at)` (27.sqm), at most 8 ids per role,
  each must exist. `AgentProfile.traitIds` + column `agents.trait_ids` (28.sqm), at most 8 additions.
  **Effective set** = the role's list in order, then the agent's additions, duplicates dropped (first wins); one
  pure function `TraitBindings.effective(profile, role)`. An agent cannot remove a role trait in v1: the Owner
  binds ALL traits to the role, and removal would let any agent opt out of REPORT BACK.
- `trait_delete` is refused while a role or an agent still references the trait, so no id dangles.

**Wire (each bullet is its own bump, in this order):**
- **1.48.0** `trait_save` (data `TraitSave{id, name, body, check?}`, strict) → push `trait_item` (`TraitView`)
  to every client; `trait_list` → `traits`; `trait_get` (id) → `trait_item`; `trait_delete` (id) → push
  `traits`. Refusals are `trait_<op> refused: <reason>` with nothing written. It mirrors 1.45.0 skills, except
  that save-as-update is by **id**, not by name.
- **1.49.0** `role_traits_set` (data `{roleId, traitIds[]}`, strict; `[]` clears) → push `role_traits`
  (`{roleId, traitIds, updatedAt}`) to every client; `role_traits_get` (role id) → `role_traits`;
  `role_traits_list` → `role_traits_all`. It mirrors 1.40.0 `role_binding_*`.
- **1.50.0** `AgentProfile.traitIds` (optional). On `agent_save`, **absent or null keeps the stored list** and
  `[]` clears it: `agent_save` overwrites the whole profile, and a 1.49.0 client that never heard of traits must
  not strip them silently. `agent`/`agents` frames always carry the stored list (`[]` when none). `agent_swap`
  keeps it unchanged.
- **1.51.0** `SessionRecord.report` and attention kind `unreported` (section 5).

## 3. Instance semantics (the clone)

Any session spawned for an agent or a role (ticket hand-out, trigger prompt, bound console, `new` naming an
agentId or a role) gets one **RoleInstance**, written once at spawn and never updated:

```
session_instances (29.sqm)
  session_id PK | agent_id | role | instructions (persona, frozen) | traits (JSON [TraitSnapshot]) | created_at
TraitSnapshot = {id, version, name, body, check?}    -- a frozen copy, not a reference
```

| Carried | How |
|---|---|
| traits | snapshots of the effective set at spawn (new) |
| instructions (persona) | frozen copy (new: today the live profile is re-read at the first turn) |
| executor defaults | already copied: `SessionRecord.provider/model/effort`, resolved by `executorFor` |
| role, link to the parent agent | already copied: `SessionRecord.role` and `SessionRecord.agentId`; no new link field |
| skills | **not in v1**: nothing attaches a skill to a role or an agent yet. They join later the same way (T10). |
| transcript | **never**. A clone starts with no conversation. |

- **One chokepoint.** `startNew` takes `instance: RoleInstance?`. When the caller passes none and the spawn names
  an agent or a role, `startNew` resolves one through an injected `instanceFor(agentId, role)`, so all five paths
  are covered even when a caller forgets (the same lesson as the env chokepoint in `startRun`). A caller that
  composes the first turn BEFORE `startNew` (ticket hand-out, trigger prompt, `new`) resolves the instance first
  and passes it in, so the stored instance is exactly what was rendered.
- **Immutable.** A later trait edit, role rebind, persona edit or agent swap never reaches a started session.
  This is the rule `executorFor` already applies to the executor. The stored versions say which rules ran.
- **No flow back in v1.** Nothing a clone learns or writes changes its parent's persona, traits or skills.
  Project memory stays shared, as today; it is not per-agent memory. A human-reviewed "promote this to the
  agent" action is possible later and is not designed here.
- An anonymous session (no agent, no role; today's pods) has no row. `session_delete` deletes the row with the
  record. Size: persona ≤ 20 000 chars plus ≤ 16 traits × 2 KB, so it stays daemon-side in v1 (it would bloat
  every `sessions` frame); the UI slice that needs it adds a read frame.

## 4. Injection

- One pure renderer, `renderTraits(snapshots): String?`, null when empty:
  `Standing rules bound to your role (they apply to this whole session):` then one block per trait,
  `## REPORT BACK (report-back v3)` followed by its body.
- `firstTurn(instance, role, agentId, prompt)` = persona → identity → traits → task, for every non-ticket first
  turn: `ResolvedSpawn.firstPrompt`, `TicketBoard.startPromptUnattended`, `MailRouter.deliver` (never-run) and
  both never-run branches of `DaemonMain`. **All of them read the persona and traits from the session's
  instance**, never from the live profile.
- `TicketContextComposer`: `ContextInput.traits` adds a `traits` source between `identity` and `ticket`, so
  `SessionRecord.contextSources` measures it like every other source.
- No traits = the same first turn as today. The existing exact-text pins (`RoleSpawnIdentityTest`,
  `AgentProfilesTest`, `AgentProfileDaemonTest`, agentHqUi `DaemonIntegrationTest`) stay green untouched; that
  is the regression guard for the shared first-turn paths.
- No frame or field changes (prompt text plus one new value in `contextSources`), so no bump. CONTRACT.md's
  1.6.0 source list gets corrected; it already omits `agenthq`, `team` and `identity`.

## 5. REPORT BACK enforcement

**Who is checked:** an `unattended` session (ticket hand-out, trigger) whose instance has a trait with a `report`
check. Never an interactive console: a human chatting with an agent would collect an "unreported" flag every turn.

**When:** on `SessionEvent.Exit` with `killOutcome == null`; an operator kill, or a session found ORPHANED at
restart, is never checked. At most once per run (`checkedRun < runCount`, so a restart cannot raise twice), and only while
the status is not `reported`. A run that ends with an open needs-input ask from this session
(`Mailbox.waiting()`) is skipped: the session declared it is blocked, which is communication, not silence.

**Detection: a mail, not a marker.** The session has reported once `Mailbox.outbox(session.id)` holds a message
that meets all three conditions:
1. `sentAt >= session.spawnedAt`;
2. it is addressed to the `reportTo` role: `to` is the role name, or an agent id whose profile has that role, or a
   session id whose record has it;
3. the body has every required field as a line `label: value`. The label match ignores case and allows a
   leading `-` or `*`. The value must be non-blank and not an unfilled `<placeholder>`. `NOT RUN`, `none` and
   `unknown` are valid values.

The delivery state is not checked: a sent report counts even if delivery later fails, which the mailbox already
shows. Why a mail and not a marker in the console output: the observed failure is that the PM never HEARS (workers
commit, print a summary, go idle), and a marker parsed by the daemon still reaches nobody. A marker would also
need the run's final text, which `Completion` does not carry and whose `TextDelta` granularity differs per harness.

**State** (wire 1.51.0, persisted with the record in `sessions.json`): `SessionRecord.report =
{status: pending|reported|unreported, traitId, reprompted, checkedRun, reportMessageId?}`. It is null when the
session has no obligation, and `pending` from spawn.

**On a miss:** the status becomes `unreported` (the Board flag). If the check allows a re-prompt, it is unused,
and the run exited 0, the daemon sends ONE mail to the session from `system` (a non-session handle, like the
CLI's `user`), subject `REPORT BACK missing`. It names the missing fields and the exact command (`agenthq send
<reportTo>`, "do not reply to this message"); the mailbox queues it while the session is busy and delivers it at
rest, like any other mail. No attention item yet.
One attention item `unreported` is raised (session, ticket, role, agent, run number, and the missing fields of
the closest mail, if any) in three cases: the next run still leaves no report, the re-prompt is off, or the run
failed. A later qualifying report flips the status to `reported`. The attention item stays until the operator
acks it, and an ack never changes the status.

**False-positive risk, stated plainly:**

| Error | Cause | Mitigation / residual |
|---|---|---|
| Report missed | Sent through a channel the daemon cannot see (another tool's mailbox, a ticket comment, a console summary). The likeliest case on this board. | The trait names the exact command and the re-prompt repeats it. Residual: one attention item a human dismisses. |
| Report missed | Prose without the labels ("committed abc, tests green"). | The re-prompt asks for the labelled lines. |
| Report missed | Sent to a colleague, or as a reply to the `system` re-prompt. | Flagged by design: the requester did not hear. |
| Non-report counted | A question or status mail that happens to carry every label. | Unlikely with six labels; accepted. |
| Non-report counted | The trait's own template echoed into a mail. | Its `<...>` placeholders are rejected as values. |
| Non-report counted | Fabricated values ("tests: 42 passed" without running them). | No presence check can catch this; truth stays with QA gates and the evidence bar. |
| Not re-checked | Follow-up runs after the report (the PM asks for a fix) are not checked: one obligation per instance in v1. | Accepted; a later rule can re-arm the obligation on each delivered task mail. |

**Migration pitfall:** `attention.kind` is limited by `CHECK (kind IN (...))`, and SQLite cannot alter a CHECK.
Admitting `unreported` therefore needs a table rebuild in 30.sqm (create `attention_new` with the extended list,
copy the rows, drop the old table, rename, re-create the index), plus a `SchemaMigrationTest` case that keeps
the existing rows.

## 6. UI touchpoints (LATER slices, client only, no wire change)

- **Roles / TEAM tab trait editor** (T8): the trait library (list, plus an editor with a check sub-form), an
  ordered per-role multi-select (`role_traits_set`), per-agent additions on the agent card (`agent_save` with
  `traitIds`), and the effective set shown with `role`/`agent` source chips.
- **Board unreported flag** (T9): ticket cards and session rows show `unreported` (attention colour), `reported`
  (muted) or nothing, from `SessionRecord.report`. The `unreported` attention row links to the session.

## 7. Slices

Every slice follows `docs/AGENTHQ-EVIDENCE-BAR.md` (RED first, then the code, then a mutation check on the
guarded line) and needs its `expected-bases.properties` line. The PM allocates migration and contract numbers in
merge order; the numbers here assume this order. **The wire-changing slices (T1, T2, T3, T6) merge strictly one
after another**; the wire-neutral ones run beside them once their inputs have landed.

| # | Slice | AREA | Depends on | Wire / contract | Test approach |
|---|---|---|---|---|---|
| T1 | Trait library: `traits` (26.sqm), `TraitStore` (limits, versioning, `report` check validation), `trait_*` frames and handler | `agentHq/persistence` + `protocol` + `daemon` | - | **1.48.0** | Store tests (each limit; version +1 only on a change; unknown check kind refused); wire test mirroring `SkillWireTest`; `SchemaMigrationTest` v26→v27 |
| T2 | Role traits: `role_traits` (27.sqm), handler mirroring `RoleBindingHandler` (role exists, traits exist, ≤ 8, no duplicates), `role_traits_*` frames; `trait_delete` refused while bound | `agentHq/role` + `persistence` + `daemon` | T1 | **1.49.0** | Mirrors `RoleBindingWireTest`; the push reaches every client; delete-while-bound refused |
| T3 | Agent traits: `agents.trait_ids` (28.sqm), `AgentProfile.traitIds` (null keeps, `[]` clears), validation in `save`, `TraitBindings.effective`; `trait_delete` also refused while an agent adds the trait | `agentHq/agents` + `persistence` | T2 | **1.50.0** | A save without the field keeps the list; a swap keeps it; effective order and de-duplication |
| T4 | Instance: `session_instances` (29.sqm), `RoleInstance`/`TraitSnapshot`, `startNew(instance)` + injected `instanceFor`; deleted with `session_delete` | `agentHq/session` + `agents` + `persistence` | T3 | none (daemon-side) | One parity test per spawn path; immutability (edit the trait and persona after spawn, the row is unchanged); anonymous spawn has no row |
| T5 | Injection: `renderTraits`, `firstTurn(instance, ...)`, `ContextInput.traits` + `traits` source; all composition sites read the instance; CONTRACT.md source list | `agentHq/agents` + `context` + `mailbox` + `daemon` | T4 | none | Existing exact-text pins unchanged (no-trait path); render order per path; a never-run session uses the FROZEN persona after a profile edit |
| T6 | REPORT BACK: `ReportBackMonitor` on `SessionEvent.Exit`, outbox detection, `SessionRecord.report`, one `system` re-prompt, attention `unreported` (+ 30.sqm CHECK rebuild) | `agentHq/session` + `mailbox` + `attention` + `daemon` | T5 | **1.51.0** | `DaemonFixture` + `ScriptedRunner`: reported; re-prompt then reported; re-prompt then exactly one attention item; kill skipped; interactive skipped; wrong role; placeholder value; open needs-input; restart idempotent. Mutation: drop the recipient check or the kill guard → RED |
| T7 | CLI: new verbs `agenthq trait save/list/show` and `agenthq role traits <role> [ids...]`; section 8 as the worked example. The CLI has no agent-edit verb today, so agent additions stay on the `agent_save` frame (UI, T8) | `agentHq/cli` | T2 | none (uses the T1-T2 frames) | `DaemonFixture`-backed CLI tests (the `CliEpicStoryListTest` pattern) |
| T8 | (later) Roles/TEAM tab trait editor (section 6) | `agenthqUiCore` | T3 | none | `commonTest` reducer tests (the `RolesAttentionModelTest` pattern) |
| T9 | (later) Board and session `unreported` flag | `agenthqUiCore` | T6 | none | `commonTest` reducer tests on `report`, incl. an old daemon that sends none |
| T10 | (later) Skills on the instance: role/agent skill lists merged with the project/ticket `skillIds` + `skillsMode` that `PHASE9-SPEC.md` reserves (no new names), snapshot as references, an index in the first turn, bodies fetched on demand by a new `agenthq skill show` | `agentHq` + `cli` | T4 | its own bump | The T2-T5 pattern |

Order: `T1 → T2 → T3 → T4 → T5 → T6 → T9`; `T2 → T7`; `T3 → T8`; `T4 → T10`. Each slice is sized for one free-model
worker. T4 (five spawn paths) and T6 (the state machine) need the most careful review.

## 8. The canonical REPORT BACK trait (data, created through T7 or the UI, never seeded in code)

```json
{"id":"report-back","name":"REPORT BACK",
 "body":"When your work on this task is done, or you are blocked, send ONE mail to the PM before you end your turn:\nagenthq send pm \"<one-line verdict>\ncommit: <sha or none>\ntests: <passed/failed/total, or NOT RUN>\nuncommitted: <paths, or none>\nmodel: <harness/provider/model you actually ran on>\nwall time: <minutes>\nblockers: <what is in the way, or none>\"\nA console summary, a session file or ending your turn is not a report. NOT RUN and unknown are valid values; silence is not.",
 "check":{"kind":"report","reportTo":"pm","fields":["commit","tests","uncommitted","model","wall time","blockers"],"reprompt":true}}
```

## 9. Decisions (taken here, smallest option each; no Owner gate)

| # | Decision | Why |
|---|---|---|
| D1 | A trait is a versioned library row (slug id, ≤ 2 KB body, optional `report` check), not a skill and not role text. | Shared across roles; versions make the clone auditable; the size cap keeps always-on text cheap. |
| D2 | Role traits live in a `role_traits` table keyed by role id. | The `role_bindings` precedent; built-in and file roles cannot carry runtime fields. |
| D3 | Agents inherit and may only ADD traits; `traitIds` null on save keeps the stored list. | "ALL traits are bound to the role"; a stale whole-profile save must not strip traits. |
| D4 | The instance is an immutable daemon-side row written in `startNew`; the parent link is the existing `agentId`. | One chokepoint covers every spawn path; nothing new on the wire. |
| D5 | Skills join the instance later (T10). | Nothing attaches a skill to a role or an agent today. |
| D6 | No transcript in a clone, and no learning flows back in v1. | Owner clarification; flow-back needs a human review step that is not designed yet. |
| D7 | A report is a mail in the session's outbox to the `reportTo` role with every labelled field. | It is what the requester actually receives, and it is already persisted; no transcript parsing. |
| D8 | The requester is the check's `reportTo` role (default `pm`), not the ticket's starter. | Tickets do not record who started them, and on this board the requester is the PM. |
| D9 | Only unattended sessions are enforced; one obligation per instance; kills and open needs-input asks are skipped. | Keeps interactive consoles silent and the signal trustworthy. |
| D10 | The re-prompt is one `system` mail; the attention item waits for its outcome (immediate when re-prompt is off or the run failed). | Reuses mailbox delivery and ordering; a self-healed miss never wakes a human. |
| D11 | Four additive bumps (1.48.0 to 1.51.0), strictly serial; no HARNESS bump. | One wire change in flight at a time, as the board requires. |
