package com.superiorsoftllc.agenthq.protocol

import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * The **public contract**: the versioned, provider-neutral event vocabulary a client renders.
 *
 * WHY THIS EXISTS. Phase 1 forwarded the provider's own stream lines verbatim (`opencode
 * --format json` emits `step_start` / `text` / `step_finish`; `claude --output-format
 * stream-json` emits `system` / `assistant` / `result`), so a rendering client had to know which
 * CLI was behind the daemon and parse that CLI's schema. The owner's decision (2026-10-08) is
 * that the UI is a separate layer that must be buildable against a stable boundary, so the raw
 * frames are translated HERE, once, into four neutral events -- and a client never sees a
 * provider-specific frame again.
 *
 * THE VERSION RULE. [VERSION] names the wire surface this daemon serves. Additive frames and fields
 * receive a minor bump; breaking changes receive a major bump. Every payload decodes with
 * `ignoreUnknownKeys`, so an older client can ignore fields it does not know.
 */
object EventVocabulary {
    /**
     * Semantic version of the boundary described in `CONTRACT.md`.
     *
     * **1.29.0 (EPIC-6) is BREAKING** (it was `1.28.0`, AGENT-UX-11). `Project` no longer carries
     * `storyIds`: the field duplicated nothing a reader used (`story_list`/`BoardHandler` orders a
     * listing from the story store's own creation order; `LeadBriefComposer` never read it), and a
     * story's order now lives entirely on `Epic.storyIds` -- the daemon's integration wiring no
     * longer appends a created story's id onto its project, only onto its epic when the create named
     * one. A client reading `project`/`projects`/`project_save`'s answer and expecting a `storyIds`
     * key must drop that expectation; `ignoreUnknownKeys` means an OLDER client parsing a NEWER
     * daemon's answer is unaffected either way, since the field is simply absent now, not renamed.
     * **1.28.0 (AGENT-UX-11) is ADDITIVE** (it was `1.27.0`, AGENT-UX-10, merged as `4d34ae68`). A new
     * client request frame, `session_delete` (payload: a plain `sessionId` string), permanently removes
     * a session's durable record. The daemon refuses, without touching anything, an unknown id or a
     * session whose `live` field is true (a run in flight) -- answered by `error` with
     * `"session_delete refused: <reason>"`. On success there is no direct reply to the requester: the
     * daemon pushes a freshly computed `sessions` frame to every connected client, exactly like any
     * other session-list mutation, and that push is what tells every client the row is gone. Nothing
     * older moved; a pre-1.28.0 client that never sends `session_delete` sees no change.
     * **1.27.0 (AGENT-UX-10) is ADDITIVE.** The `providers` answer's `ProviderInfo` gains an optional
     * `models` list -- the model ids the provider itself reports, read through AGENT-UX-7's canonical
     * `knownModels()` seam (`opencode models` for the `opencode` provider), `null` when it cannot list
     * any -- and an optional `modelsStale` boolean, currently always `false` (the canonical seam exposes
     * no staleness signal yet). A 1.26.0 client ignores both (`ignoreUnknownKeys`), so its frames are
     * unchanged.
     *
     * **1.25.0 (AGENT-UX-9) is ADDITIVE.** The `status` frame's `StatusPayload` gains an optional
     * `tool` field: the name of the tool the run is currently calling, or null when the status is not
     * about a tool call. It is produced by the normaliser from a provider tool call (opencode's `tool`
     * part, claude's `tool_use` block) and is **omitted from the JSON when null**, so a 1.24.0 client
     * sees every existing status frame byte-for-byte unchanged and ignores the new field when present.
     *
     * **1.24.0 (AGENT-UX-4) is ADDITIVE.** `SessionRecord` gains `isProcessAlive` (boolean, default false)
     * and `live` (computed boolean) fields for accurate liveness reporting. `live` is true when the
     * session is in a running state (STARTING/BUSY/WAITING_INPUT) OR when `childPid != null &&
     * isProcessAlive == true`. A 1.23.0 client works unchanged: it ignores unknown fields.
     *
     * **1.23.0 (THINK-1) is ADDITIVE** (it was `1.22.0`). A new session-stream frame `reasoning`
     * carries the model's own reasoning, kept apart from `text_delta` so a UI can render thinking
     * separately from the answer. It is produced by the normaliser from opencode's `reasoning` part
     * (`text`, top-level or under `part`) and from claude's `assistant` content block of type
     * `thinking` (`thinking`); `text` blocks are unchanged. Nothing older moved.
     *
     * **1.22.0 (STORY-EPIC-1) is ADDITIVE** (it was `1.21.0`). `story_save` gains an optional
     * `epicId`: on a create it joins that epic, whose ordered `storyIds` grow by the new story, and
     * the changed epic is pushed as an `epic` frame to every client. An unknown epic id is a no-op.
     * An edit ignores `epicId`. Nothing older moved.
     *
     * **1.21.0 (MEM-8 memory notes) is ADDITIVE** (it was `1.20.0`). `memory_save`
     * (`{"projectId","name","description","body"}`) is answered by a `memory_note` frame carrying the
     * stored note, and `memory_list` (raw project id as payload, like `epic_list`) by a `memory_notes`
     * frame: the project's notes as `{name, description}`, in name order. Nothing older moved.
     *
     * **1.20.0 (MEM-5 memory search) is ADDITIVE** (it was `1.19.0`). `memory_search`
     * (`{"projectId","query"}`) is answered by a `memory_hits` frame: the project's ranked hits, each
     * a note's `name`, `description` and a `snippet` of its body around the match. Nothing older moved.
     *
     * **1.19.0 (EPIC-4) is ADDITIVE.** A project's epics are now available over the wire:
     * `epic_save` (`{"projectId","name","goal"?}`) is answered by an `epic` frame and `epic_list`
     * (raw project id as payload, like `project_get` / `workspace_list`) by an `epics` frame.
     * Nothing older moved.
     *
     * **1.8.0 (MVP Sprint-Zero I0) is ADDITIVE.** Projects and stories are now available over
     * `project_save` / `project_get` / `project_list` and `story_save` / `story_get` /
     * `story_move` / `story_list`; tickets also carry their optional `projectId`. Nothing older moved.
     *
     * **1.10.0 (Story 9.5a triggers) is ADDITIVE.** Manual trigger events coalesce into a bounded
     * run stream with at most one in-flight run and one follow-up slot.
     *
     * **1.11.0 (talk to an agent) is ADDITIVE.** A new sequenced session-stream frame, `turn`, carries
     * the prompt each run was started with, ahead of the reply it caused, so replay rebuilds both sides
     * of a conversation. `ready` gains `instance=<id>` and `since=<epoch millis>` so a client can tell a
     * reconnect from a daemon restart (sequences restart with the process). Nothing older moved.
     *
     * **1.14.0 (story 9.5 wiring) is ADDITIVE.** Manual triggers with a `start_next` action are stored and
     * served: `trigger_save` / `trigger_arm` / `trigger_disable` push `trigger`, `trigger_fire` answers
     * `trigger`, `trigger_list` answers and `trigger_delete` pushes `triggers`, `trigger_events` answers
     * `trigger_events`. `/health` adds `triggersTripped`.
     *
     * **1.13.0 (story 9.7b wiring) is ADDITIVE.** A run whose stream reported usage ends with a sequenced
     * `usage` frame (`finished` is unchanged); `provider_state` is pushed when a provider's observed limit
     * state changes, and `provider_states` lists every provider's latest state (`unknown` until observed).
     * Unattended starts pass a launch gate (budget, exhausted provider); human starts are only warned.
     *
     * **1.12.0 (role catalog) is ADDITIVE.** `role_list` answers `roles` from the daemon's catalog;
     * `role_save` writes a custom role definition and pushes the stored `role` to every client.
     *
     * **1.9.0 (Story 9.4a attention) is ADDITIVE.** The daemon stores attention history, broadcasts
     * `attention` updates, answers `attention_list` with `attention_items`, and exposes `GET /attention`.
     * `/health` reports the open attention count. No existing frame or field changes meaning.
     *
     * **1.7.0 (AgentHQ F2 replay) is ADDITIVE.** Session-stream frames carry a per-session increasing
     * `sequence`; clients can request retained frames after an exclusive cursor with `replay_request`.
     * The daemon retains at most 256 frames per session and reports an expired cursor with `replay_gap`
     * before returning the retained range. The CLI surfaces that gap instead of silently skipping it.
     * The four rendered events and their payloads are unchanged.
     *
     * **1.6.0 (Story 9.3) is ADDITIVE.** Ticket starts, role capacity queues, executor/context views,
     * durable `ticket_runs`, and the `ticket_start_next` / settings request surfaces are new. Ticket
     * rows and sessions also gain optional fields. Nothing older moved.
     *
     * **1.5.0 (Phase 9, the backlog) is ADDITIVE.** New `ticket_save` / `ticket_get` / `ticket_list` /
     * `ticket_move` / `ticket_link` / `ticket_unlink` / `ticket_reorder` / `ticket_delete` requests,
     * answered by `ticket` / `tickets`, which are also pushed to every client when the board changes, a
     * 1.4.0 client included when another client edits the backlog. Nothing older moved; a client that
     * ignores frame types it does not know (every client this project ships does) sees no change.
     *
     * **1.4.0 (Phase 9, agent profiles) is ADDITIVE.** New `agent_save` / `agent_list` /
     * `agent_delete` requests answered by `agent` / `agents`; `SpawnOptions.agentId` spawns from a
     * profile; `SessionRecord.agentId` and `.role` are new fields. A 1.3.0 client sees no change.
     *
     * **1.3.0 (Phase 9, per-spawn provider) is ADDITIVE.** A `new` frame may carry `SpawnOptions`
     * (provider and model) for the sessions a connection creates; a new `providers` request lists what
     * the daemon can spawn; `SessionRecord.model` and `/health.providers` are new fields. A 1.2.0
     * client that sends `new` with empty data and never asks for `providers` sees no change.
     *
     * **1.2.0 (Phase 7) is ADDITIVE on the frames.** Client frames (`prompt`, `mail_send`, `mail_inbox`,
     * `mail_outbox`) now honour an optional `sessionId`: a client that names its session continues or
     * addresses that session, so a reconnecting client resumes without holding state of its own. A 1.1.0
     * client that never sets `sessionId` on those frames sees no change. Remote use adds a bearer-token
     * requirement on the daemon -- a configuration, not a frame -- see `CONTRACT.md` sections 3 and 5.
     *
     * **1.64.0 (BUG-M2-11b-1) changes BEHAVIOUR, no frame or shape**: every session runs through one session host. `exit`
     * now reports a hosted process that ended unasked (a crash); one-shot turns stop sending it. One `stderr` boot line
     * `[agenthq] * session …` per launch; the per-turn plumbing lines moved to the Activity box (`status`). See `CONTRACT.md`.
     * **1.65.0 (BRANCH-VIEW-1) is ADDITIVE**: `project_git_branches` -> `project_git_branch_list` (local branches),
     * `project_git_branch_changes` -> `project_git_branch_diffstat` (what a branch changed, `mergeBase..branch`) and
     * `project_git_branch_diff` -> `project_git_patch` (now carrying `branch`). Read-only. See `CONTRACT.md`.
     * **1.66.0 (E1-b) is ADDITIVE**: `ticket_list` also takes a JSON object `{projectId?, epicId?}`; `epicId` lists only
     * the tickets filed under that epic, in board order, and an unknown epic answers an empty `tickets`. Empty data and
     * a bare project id answer as before.
     * **1.68.0 (BUG-M2-11b-2) is ADDITIVE, no new frame**: the setting `session.keepAliveMinutes` (int 0..1440, default
     * 30) through `settings_get` / `settings_set`; 0 runs every harness one-shot. The `AGENTHQ_OPENCODE_SERVE` env toggle
     * is gone. See `CONTRACT.md`.
     * **1.73.0 (CLIPATH-1) is ADDITIVE**: `cli_locations` -> `cli_locations` `{tools:[CliLocation]}` and `cli_location_set
     * {id, path?}` (null/blank clears; a non-executable path is refused as an `error`). One resolver launches every CLI:
     * Settings override, `AGENTHQ_*_EXE`, known install locations, PATH. `ProviderInfo` gains `available` /
     * `unavailableReason`. See `CONTRACT.md`.
     * **1.72.0 (BUG-M2-11f) is ADDITIVE, no new frame**: `SessionRecord.keptProcess` `{pid, startedAt?, label}` (omitted
     * when null). A kept process stops after `session.keepAliveMinutes` idle with no `exit` frame. A `settings_set` of
     * that key applies at once. A daemon start reaps a leftover kept process only when pid AND start instant match. See
     * `CONTRACT.md`.
     * **1.74.0 (BRANCH-SWITCH-1) is ADDITIVE**: `project_git_checkout {projectId, branch, create?}` ->
     * `project_git_checkout_result` switches (or creates from HEAD and switches) the project's checked-out branch. Never
     * forces, stashes or discards: a dirty tracked tree, a branch held by another worktree, a bad or unknown name is a
     * result with `switched = false`, a `code` and a `reason`. A switch that happened also pushes `project_git` to every
     * client. See `CONTRACT.md`.
     * **1.76.0 (BUG-PERM-1) is ADDITIVE**: `tool_approval` -> `tool_approval` `{mode?, effective, source, choices[]}` and
     * `tool_approval_set {mode?}` (null/blank clears; an unknown mode is an `error`); `new` gains `toolApproval`. A run
     * resolves session override, setting, `AGENTHQ_TOOL_APPROVAL`, deny. A harness denial adds one `stderr` notice per
     * denied tool. See `CONTRACT.md`.
     * **1.78.0 (PROMPT-6) is ADDITIVE**: `prompt_response` takes the decision `allow_always`, which stores an
     * `ApprovalRule` {tool, prefix?} for the asking session's project (settings key `toolApproval.rules`); a later
     * matching approval in that project is allowed with no `prompt_request` and one `[agenthq] auto-allowed by rule ...`
     * stderr line. New frames `approval_rules {projectId?}` and `approval_rule_delete {ruleId}`, both answered by
     * `approval_rules {projectId?, rules[]}`. See `CONTRACT.md`.
     * **1.77.0 (PROMPT-1) is ADDITIVE**: Tool approval gains the mode `ask` (not the default; a harness that cannot
     * prompt treats it as `deny`, its notice adds "(this harness can't prompt)"). New frames `prompt_request`
     * (daemon -> clients, replayed on connect while open), `prompt_response` (client -> daemon; unknown or duplicate
     * ids refused) and `prompt_resolved` (daemon -> clients). Setting `prompts.timeoutSec` (1..86400, default 600).
     * See `CONTRACT.md`.
     * **1.80.0 (UX-WRITE-1) is ADDITIVE, no new frame**: a `status` frame for a file-writing tool call gains `write`
     * `{path, content? | edits[{oldText, newText}]? | patch?, truncated?}`, what the call wrote, so a UI can show the
     * change in the chat. Omitted on every other status. See `CONTRACT.md`.
     * **1.79.0 (PROMPT-5) is ADDITIVE**: new frames `prompt_ask {question, options[], multiSelect?, allowOther?,
     * timeoutSec?}` (client -> daemon; envelope `sessionId` = the asking session, which must exist) and `prompt_answer
     * {answered, answers[], otherText?}` (daemon -> the asking connection only, once the prompt resolves).
     * The prompt itself is an ordinary 1.77.0 `question` (`prompt_request` / `prompt_response` / `prompt_resolved`);
     * `timeoutSec` (1..86400) overrides `prompts.timeoutSec` for it; a closed connection withdraws it (`cancelled`).
     * Refusals are `error` "prompt_ask refused: ...". 1.78.0 is PROMPT-6's. See `CONTRACT.md`.
     * **1.75.0 (CLIPATH-2) is ADDITIVE, no new frame**: `cli_locations` lists one more built-in tool, `node` (the
     * runtime Pi's `cli.js` runs on), after `git`; `cli_location_set` accepts `"node"`. See `CONTRACT.md`.
     * **1.62.0 (E1-c) DEPRECATES the story frames**: `story_save` / `story_get` / `story_move` / `story_list` are shims over
     * the ticket board (a story IS a ticket; its id is the ticket id; an old story id resolves through `story_migration`).
     * `Epic.storyIds` is derived from the tickets filed under the epic, in board order. No frame was added or removed.
     * **1.61.0 (BUG-M2-15b) is ADDITIVE**: new client frame `session_project {sessionId, projectId}` binds a session with
     * no project to a known one; answered by the `session` push, refused as an `error`. See `CONTRACT.md`.
     * **1.58.0 (BUG-G2d) is ADDITIVE**: `pod_submit` and `new` read the envelope's `sessionId` as the session the spawn
     * comes from. Such a spawn is charged to that session's pod and refused (`error` `spawn refused: pod cap ...` /
     * `spawn refused: depth limit ...`, nothing written) past the cap or the depth limit. A frame with no `sessionId`
     * (the user, the UI) is unchanged. No frame was added or removed. See `CONTRACT.md` "What changed in 1.58.0".
     * **1.57.0 (BUG-G2b) is ADDITIVE**: `PodSubmitRequest` gains an optional `projectId`. A pod's sessions carry it
     * (absent or blank = the daemon's default project), and each node's first attempt renders that project's knowledge
     * and skills sections. No frame was added or removed. See `CONTRACT.md` "What changed in 1.57.0".
     * **1.56.0 (P10-K12) is ADDITIVE**: `AgentProfile` and `agent_save` gain `skillIds` (absent or null keeps, `[]` clears,
     * unknown or repeated ids refused; `agent_swap` keeps it); `SkillSave` gains `attachedOnly` (absent keeps, a new skill
     * gets false) and `SkillView` carries it; `skill_get` accepts an exact skill name as well as an id. Schema v35
     * (`34.sqm`: `agents.skill_ids`, `skills.attached_only`). See `CONTRACT.md` "What changed in 1.56.0".
     * **1.55.0 (P10-K11) is ADDITIVE**: `Ticket` and `ticket_save` gain `skillIds` (absent keeps, `[]` clears, unknown or
     * repeated ids refused), `skillsMode` (`merge` | `replace`, default `merge`) and `includeSkillsContext` (null
     * inherits); `effectiveContext` gains `includeSkillsContext {value, source}`; settings gain `context.skillIds` and
     * `context.includeSkillsContext`. Schema v34 (`33.sqm`, three `tickets` columns). See `CONTRACT.md` "What changed
     * in 1.55.0".
     * **1.54.0 (P10-K6) is ADDITIVE**: new frames `issue_open {projectId, sessionId?, summary, location, ticketId?}`,
     * `issue_attempt {projectId, sessionId?, summary, outcome? = failed, location?, issue?}` and `issue_fix {projectId,
     * sessionId?, summary, issue?}` -> `issue_item` (pushed to every client); `issue_list {projectId, includeFixed,
     * location?}` and `issue_precheck {projectId, path}` -> `issues`; `issue_get {projectId, ref}` -> `issue_item`.
     * Without `issue`, an attempt or a fix goes to the declared session's ACTIVE issue (per session); an attempt with
     * none opens an `implicit` issue. Refusals `issue_not_found`, `no_active_issue`, `already_fixed`,
     * `session_not_found` put the code first. Schema v33 (`32.sqm`, the append-only `issues` and `issue_events`
     * tables). See `CONTRACT.md` "What changed in 1.54.0".
     * **1.53.0 (P10-K4) is ADDITIVE**: new frames `decision_add {projectId, sessionId?, summary, body?, supersedes?}` ->
     * `decision_item` (pushed to every client), `decision_list {projectId, includeSuperseded}` -> `decisions`,
     * `decision_get {projectId, ref}` -> `decision_item`; refusals `decision_not_found`, `already_superseded`,
     * `session_not_found` put the code first. Schema v32 (`31.sqm`, the append-only `decisions` table). See `CONTRACT.md`
     * "What changed in 1.53.0".
     * **1.52.0 (TRAITS-T6) is ADDITIVE**: `SessionRecord` gains an optional `report` (`{status: pending|reported|unreported,
     * traitId, reprompted, checkedRun, reportMessageId?, attentionId?}`, null when the session owes no REPORT BACK), and
     * attention gains the kind `unreported`. One `system` mail (subject `REPORT BACK missing`) may be sent to a session.
     * Schema v31 (`30.sqm`, the attention CHECK rebuilt). See `CONTRACT.md` "What changed in 1.52.0".
     * **1.51.0 (P10-K2) is ADDITIVE**: `memory_save` gains optional `mode` (`replace` default / `append` / `edit`),
     * `find`, `content` and `folder` (a 1.50.0 four-field payload still replaces); refusals `note_not_found`,
     * `anchor_not_found`, `anchor_ambiguous` put the code first. New `memory_get {projectId, name}` answered by
     * `memory_note`. `memory_notes` rows gain `folder`. No schema change. See `CONTRACT.md` "What changed in 1.51.0".
     * **1.50.0 (TRAITS-T3) is ADDITIVE**: `AgentProfile` gains an optional `traitIds` (the traits an agent ADDS to its role's
     * list). On `agent_save` absent/null KEEPS the stored list and `[]` clears it; `agent` / `agents` always carry it; `agent_swap`
     * keeps it; `trait_delete` is refused while an agent binds the trait. Schema v29 (`agents.trait_ids`). See `CONTRACT.md`
     * "What changed in 1.50.0".
     * **1.49.0 (TRAITS-T2) is ADDITIVE**: a role's ordered trait list -- `role_traits_set` / `role_traits_get` / `role_traits_list`
     * and the `role_traits` / `role_traits_all` frames; `trait_delete` is refused while a role binds the trait. Schema v28
     * (`role_traits`). See `CONTRACT.md` "What changed in 1.49.0".
     * **1.48.0 (TRAITS-T1) is ADDITIVE**: the trait library -- `trait_save` / `trait_list` / `trait_get` / `trait_delete` and the
     * `trait_item` / `traits` frames. Schema v27 (`traits`). See `CONTRACT.md` "What changed in 1.48.0".
     * **1.47.0 (ROLE-FIRST S3) is ADDITIVE**: `agent_swap` (`AgentSwap`) moves one agent onto another executor, answered by the
     * `agent_swapped` push (`AgentSwapped`: the stored profile, the executor it left, the session outcome) to EVERY client. No schema
     * change. See `CONTRACT.md` "What changed in 1.47.0".
     * **1.46.0 (ROLE-FIRST S2) is ADDITIVE**: `AgentProfile` gains an optional `effort` (null = inherit), carried by `agent_save` /
     * `agent` / `agents`. Schema v26 (`agents.effort`). See `CONTRACT.md` "What changed in 1.46.0".
     * **1.45.0 (P10-B) is ADDITIVE**: the skills library -- `skill_save` / `skill_list` / `skill_get` / `skill_delete` and the `skill_item` /
     * `skills` frames. Schema v25 (`skills`). See `CONTRACT.md` "What changed in 1.45.0".
     * **1.44.0 (P10-A) is ADDITIVE**: the prompt library -- `prompt_save` / `prompt_list` / `prompt_get` / `prompt_delete` and the `prompt_item` /
     * `prompts` frames. Schema v24 (`prompts`). See `CONTRACT.md` "What changed in 1.44.0".
     * **1.43.0 (ROLE-FIRST S1) is ADDITIVE**: `SpawnOptions` gains an optional `role`; a spawn naming it attaches the session to that
     * role and runs on the role's binding. See `CONTRACT.md` "What changed in 1.43.0".
     * **1.42.0 (P9.6) is ADDITIVE**: `TriggerSave` gains a write-only `webhookSecret`, trigger kind `webhook` is served, and
     * `POST /hooks/{id}` is admitted without the bearer but only with a valid per-hook signature. See `CONTRACT.md` "What changed in 1.42.0".
     * **1.41.0 (P9.5d) is ADDITIVE**: `TriggerAction` gains an optional `prompt`, and the trigger action `prompt` (a ticketless unattended session
     * for an agent, first message = the prompt) is served. See `CONTRACT.md` "What changed in 1.41.0".
     * **1.40.0 (ROLE-1c) is ADDITIVE**: `role_binding_list` / `role_binding_get` / `role_binding_set` read and write what a role
     * runs on (provider, model, effort); answers are `role_bindings` / `role_binding`, a set is pushed to every client.
     * `AgentProfile` is untouched. See `CONTRACT.md` "What changed in 1.40.0".
     * **1.39.0 (CP-4 slice d) is ADDITIVE** (it was `1.38.0`): a known `/` command now runs against its session and is answered with a
     * `command_result` frame; `SessionRecord` gains `effort`. See `CONTRACT.md` "What changed in 1.39.0".
     * **1.38.0 (CP-4) is ADDITIVE** (it was `1.37.0`): one frame, `command_catalog`, lists a harness's `/` commands filtered by a prefix, and a
     * `prompt` starting with `/` is a command, never a prompt. See `CONTRACT.md` "What changed in 1.38.0".
     * **1.37.0 (CP-3) is ADDITIVE** (it was `1.36.0`): `AgentProfile` gains `projectId`, `agent_list` may name a project. See `CONTRACT.md` "What changed in 1.37.0".
     * **1.36.0 (CP-2) is ADDITIVE** (it was `1.35.0`): `Project` / `ProjectSave` gain `directory` (the absolute
     * directory a project lives in; empty = none), and three request frames, `project_git_status`,
     * `project_git_diff` and `project_git_log`, read that directory's git working tree, one file's diff and its
     * history (answered by `project_git`, `project_git_patch`, `project_git_history`). See `CONTRACT.md`
     * "What changed in 1.36.0".
     *
     * **1.35.0 (GIT VIEW) is ADDITIVE** (it was `1.34.0`): `WorktreeStatus` gains `changes` and `changesTruncated`, and a
     * `worktrees` request may name one session. See `CONTRACT.md` "What changed in 1.35.0".
     *
     * **1.31.0 is ADDITIVE and is a MINOR bump** (it was `1.30.0`). The number is allocated at the acceptance
     * of the ROSTER / MAIL / AGENT-CLI / CLI-T series, whose slices carry no bump of their own. Nothing a
     * 1.30.0 client sends or reads changes meaning. It adds: [Ticket.epicId] and [TicketSave.epicId], an
     * optional epic a ticket is filed under (a blank `epicId` on an edit keeps the current one); and the
     * `agent_roster` request answered by a `roster` frame, the live "who's here" list of one project's
     * agents ([RosterRow]). The `agenthq` CLI surfaces of the series (`epic show` / `epic create`, the
     * `EPIC:` line of `ticket show`, `ticket create` / `move` / `link`) are client-side and add no frame.
     *
     * **1.30.0 (BIND) is ADDITIVE** (it was `1.29.0`, EPIC-6). [SpawnOptions] gains `projectId`, the
     * project the console belongs to, recorded on the session so a roster or role-based mail can scope
     * by project; absent or null keeps the old behaviour, and `ignoreUnknownKeys` leaves a 1.29.0 client
     * unaffected. The behavioural part is NOT additive and is the reason this is documented rather than
     * merged quietly: a `new` that names an agent profile now creates THE session at that moment,
     * bound to the profile's `agentId`, `role`, `projectId` and persona, instead of creating one at the
     * first prompt. "The next prompt starts a fresh session" is therefore no longer true for a
     * profile-bound `new` (it still holds for one naming no profile), and "a `new` that names a profile
     * spawns sessions from it" is now singular. `SessionRecord.projectId` sits beside the `agentId` and
     * `role` it gained in 1.4.0; all three are null on any record an earlier daemon wrote.
     *
     * **1.1.0 (Phase 5b) is ADDITIVE and changed nothing that 1.0.0 froze.** It adds the pod command
     * frames ([POD_SUBMIT], [POD_QUERY], [POD_CANCEL]) and their single answer frame ([POD]), and
     * two counts on `/health`. The four event frames below are byte-for-byte what 1.0.0 published:
     * same names, same payload types, same meaning. A minor bump is the *only* kind of bump that
     * leaves a 1.0.0 client working, so if a future change needs a major one it is because one of
     * those four moved -- not because more was added.
     */
    // **1.86.0 (SWAP-AGENT-1) is ADDITIVE**: `session_swap` (SessionSwap) gains an optional `role` (absent = keep,
    // blank = none) that swaps the role the session runs as, in place; `session_swapped` gains `role` / `fromRole`.
    // **1.87.0 (BUG-MCP-RACE-1) is ADDITIVE**: the envelope gains an optional `requestId`. A backlog request carrying
    // one (`Envelope.CORRELATED_REQUESTS`) is always answered DIRECTLY with that id echoed, after the push to every
    // client; pushes never carry it. A request without one is answered exactly as before.
    // **1.88.0 (UX-CHANGES-1) is ADDITIVE**: `project_git_worktrees` -> `project_git_worktree_list` (the repository's
    // worktrees, the session's marked) and `project_git_worktree_status` -> `project_git_worktree` (one worktree's branch,
    // ahead/behind its base, uncommitted changes and history); `project_git_diff` gains an optional `worktree`.
    // **1.89.0 (ATTACH-INLINE-1) is ADDITIVE**: `image_upload`'s `purpose` gains `file`, a chat attachment of any type
    // (no magic-byte check, 20 MB, stored under `uploads/file/`, swept like `chat`). `chat` and `ticket` are unchanged.
    // **1.90.0 (ORIGIN-SCRUB-1a)**: five role ids renamed (git, i18n, brainstormer, bot, chief-of-staff -> version-control,
    // localization, ideation, automation, assistant); a retired id is still accepted on input and stored rows migrate on open.
    // **1.91.0 (ORIGIN-SCRUB-1b)**: the ticket type set is AgentHQ's own (feature, bug, refactor, chore, docs, style,
    // perf, test, security -> enhancement, defect, restructure, upkeep, documentation, cosmetic, performance, testing,
    // hardening); a retired type is still accepted on input and stored tickets migrate on open.
    // **1.92.0 (STATS-1) is ADDITIVE**: `session_stats_get` {sessionId} -> `session_stats` (a SessionStats), one session's
    // statistics read from its harness's own local record; fields the harness does not expose are null.
    // **1.93.0 (ROLE-ICON-1) is ADDITIVE**: `Role` gains `icon` (a client icon key, "" = none, the client paints its
    // default for the role) and `RoleSave` an optional `icon` (absent = keep, "" = clear). Stored in the role file.
    // **1.94.0 (RUN-CMD-1) is ADDITIVE**: `command_save` {id, name?, argv, cwd?, graceMillis?} (upsert) and `command_delete`
    // {id} write `<AGENTHQ_HOME>/commands.json`; the updated `commands` list goes to every client. `CommandSpec` gains
    // `argv` and `graceMillis` (owner decision: the editor shows the argv; it still never reaches a log line).
    // **1.95.0 (1c24fdc4) is ADDITIVE**: `status.runActivity` {state, label?, startedAt, turnStartedAt, lastEventAt}, the
    // run's harness-neutral activity (idle | thinking | tool_running | waiting_for_input | finished | error).
    // **1.96.0 (ticket-b5aa01bc) is ADDITIVE**: a `prompt` naming a session that already has a run in flight is refused
    // with a TYPED `error`: data `run_in_flight <sessionId>: <reason>` (`RUN_IN_FLIGHT`), with the prompt's `requestId`
    // echoed when it carried one, so a client can put the message it sent back in its queue instead of losing it.
    // **1.97.0 (ASK-1) is ADDITIVE**: `question_ask` -> `question_asked`, `question` / `questions` pushes, `question_list`,
    // `question_answer`, `question_cancel`: NON-BLOCKING owner questions; the pick reaches the asker as mail (thread `question:<id>`).
    // **1.98.0 (NOTES-1) is ADDITIVE**: a memory note has a `scope` (project | role | session) and an `ownerId`; `memory_*`
    // payloads take optional `scope` / `sessionId` / `role`; new `memory_delete`, `memory_purge_role` (typed confirmation).
    // **1.99.0 (PIPE-2) is ADDITIVE**: the daemon PUSHES `pipeline_state` to every client after each `pipeline_config` /
    // `pipeline_record` / `pipeline_check` change (the PIPE-1 frames are otherwise unchanged); the UI draws it in the Git tab.
    // **1.104.0 (VOICE-1) is ADDITIVE**: `notify` ({say, level?, projectId?, ticketId?}) -> `spoken` pushed to every client
    // (a SpokenNotice: id, at, say, level, projectId?, projectName?, ticketId?). Ephemeral: no table, no replay.
    // **1.100.0 (HARNESS-INSTALL-1) is ADDITIVE**: `harness_install_status` {checkLatest?} -> `harness_install_status`
    // (`HarnessInstallReport`: per-harness installed / missing / outdated + versions) and `harness_install` {ids?, update?}
    // -> `harness_install_result` (`HarnessInstallResult`: outcomes + npm log + the report after). npm into the app prefix.
    // **1.101.0 (ISO-1) is ADDITIVE**: `mail_send` takes optional `allowCrossProject`; without it a session sender mailing a
    // session/agent of ANOTHER project is `mail_refused` naming both projects. Roles still resolve in the sender's project only.
    // **1.102.0 (SECRETS-1) is ADDITIVE**: the daemon-owned keystore. `secret_set` / `secret_clear` / `secret_import` / `secret_status`
    // -> `secret_state` (set / not set and a masked tail; never a value). See CONTRACT.md.
    // **1.103.0 (TICKET-WEIGHT) is ADDITIVE**: `ticket` / `ticket_save` carry an optional `weight` (1..5; 0 clears on save); `effectiveExecutor`
    // sources may be `weight`; the `tickets.weightExecutors` setting maps a weight to {provider, model} for a ticket that pins none.
    // **1.105.0 (TRIGGER-NUDGE) is ADDITIVE**: `TriggerSave.projectId`; `TriggerView` gains projectId / lastFiredAt / nextFireAt; a
    // `prompt` action is mail to the target in the trigger's project (queued while busy, one pending per trigger, never a
    // trip); dispositions `mailed` / `coalesced_pending` / `deferred_busy`; `trigger_arm` enables a disabled trigger; a
    // schedule slot missed while down fires once on boot (45.sqm, schema v46).
    // **1.106.0 (SCOPE-1) is ADDITIVE**: MCP board / ticket_get / ticket_update / ticket_move / ticket_delete / get_session / pod_state default to the
    // CALLER'S project; `projectId=all` (pm / owner) is explicit; a foreign project is refused for other roles. Answers carry projectId + scope. No frame changes.
    // **1.107.0 (USER-PORTRAIT) is ADDITIVE**: the setting `user.portrait` (base64 PNG, <= 256 KB, absent = the bundled default).
    // **1.113.0 (STATS-2) is ADDITIVE**: one harness-neutral usage definition for `session_stats`; `StatsPoint.cumulativeCost` and `SessionStats.costTotal` (reported cost, null = none). See CONTRACT.md.
    // **1.114.0 (KILL-SCOPE-1) is ADDITIVE**: kill scope by role + project, `session rm --force`, `agenthq kill`, `SessionRecord.spawnerSessionId`. See CONTRACT.md.
    // **1.115.0 (ASSISTANT-1) is ADDITIVE**: the `assistant` frame, `SessionRecord.global`, role `assistant` is privileged across projects. See CONTRACT.md.
    // **1.117.0 (PROGRESS-1, ticket-1371d44b) is ADDITIVE**: `SessionRecord.progress`, the `progress` request frame, `agenthq progress` / `agenthq run` / `agenthq_progress`. See CONTRACT.md.
    // **1.118.0 (BASICS-KILL-1) is ADDITIVE**: `Project.autoRemoveDoneWorkers` + `project_config` key; stale BUSY removable; one session per ticket. See CONTRACT.md.
    // **1.120.0 (ROGUE-1, ticket 8a40b481) is ADDITIVE**: the `processes` request frame (list / reap / reap_rogue) and the `process_pressure`
    // push; settings `processes.autoReap` (default false) / `processes.idleMinutes` / `processes.heavyMb` / `processes.floorMb`. See CONTRACT.md.
    // **1.119.0 (USAGE-PACE) is ADDITIVE**: `usage_get` / `usage_report` (read-only plan meters + pacing allowance), `agenthq usage` / `agenthq_usage`. See CONTRACT.md.
    // **1.121.0 (USAGE-PACE S2+S3) is ADDITIVE + BEHAVIOURAL**: `usage_set`, settings `usage.pacing|manual|overrides`, `agenthq usage set|clear|override|rule`; an agent's / the daemon's ticket start beyond the plan allowance is refused (1.120.0 is ROGUE-1). See CONTRACT.md.
    // **1.122.0 (WEB-1, ticket 6cb0d59e) is ADDITIVE**: the daemon serves the browser client bundle at `/` (no token; Host and
    // Origin still checked); a SAME-ORIGIN browser is admitted on `/ws` only with the bearer, which it may send as the
    // `agenthq.bearer.<base64url token>` subprotocol (the daemon answers `agenthq`); setting `AGENTHQ_WEB_HOSTS`. See CONTRACT.md.
    // **1.123.0 (PROGRESS-COUNTDOWN, ticket 564d0e01) is ADDITIVE**: `SessionProgress` labelStartedAt / estimateSource / lastOutputAt, `ProgressReport` estimateMs and op `output`; the meter always counts down. See CONTRACT.md.
    // **1.128.0 (ROUTING-1, ticket-a5969342) is ADDITIVE**: `Project.routingRules` + `project_config.routingRules`, `EffectiveExecutor.rule`, source `rule`. See CONTRACT.md.
    // **1.130.0 (TODO-PROGRESS-1, ticket-aba6cdca) is ADDITIVE**: `SessionProgress.origin` (`todo`), a `count` bar derived from the agent's to-do list. See CONTRACT.md.
    // **1.131.0 (BACKLOG, ticket-39eab724) is ADDITIVE**: ticket column `backlog` (first in board order, never started), `TicketSave.status` on a create. See CONTRACT.md.
    // **1.132.0 (BUILD-LIMITS, ticket-66153313) is ADDITIVE + BEHAVIOURAL**: settings `limits.build.priority|maxWorkers|maxParallelForks` and `limits.cpu.alertPercent|alertMinutes`; worker sessions run at the build priority (Windows Job object / POSIX nice) with Gradle caps in their env; `TrackedProcess.cpuPercent`, `ProcessReport.agentCpuPercent|cpuAlertPercent|cpuAlertMinutes|cpuHighSince`; a `ceiling` attention item (outcome `cpu_sustained`). See CONTRACT.md.
    // **1.133.0 (NO-HARD-CODED-LIMITS, ticket-23471801) is ADDITIVE + BEHAVIOURAL**: settings `limits.concurrency.*`, `limits.pacing.maxWorkersCeiling`, `limits.watchdog.*` (and `limits.build|cpu.*` now share the registry); env `AGENTHQ_LIMIT_*`; frame `limit_catalog`; refusals name the setting. See CONTRACT.md.
    // **1.134.0 (RUN-CONSOLE, ticket-67f117b6) is ADDITIVE + BEHAVIOURAL**: runs have a pty; frames `run_bytes`, `run_list`/`runs`, `run_attach`, `run_resize`, `run_input`; `run` name/terminal/interactive; settings `limits.run.scrollbackBytes|history`. See CONTRACT.md.
    // **1.135.0 (SECRETS-2, ticket-e5d1b57f) is ADDITIVE + BEHAVIOURAL**: key/value secrets: frames `secret_field_set`, `secret_field_delete`, `secret_delete`; `secret_state` entries/locked; `CommandSpec.secrets` and `run_start` secrets inject a secret's fields as environment variables, scrubbed from the run's output. See CONTRACT.md.
    // **1.136.0 (SEC-FIX-1, ticket-61e8f4ac) is BEHAVIOURAL**: under a seated lockdown every client frame not on the read-only allowlist is refused, a bare text prompt included; command_save/delete, secret_set/clear/import and run_start/stop/attach/resize/input need the owner credential (owner.token, X-AgentHQ-Owner header or agenthq.owner.<b64> subprotocol); run_input limits and pty size clamp (limits.run.*). See CONTRACT.md.
    // **1.137.0 (TICKET-FILES, ticket-55476f3c) is ADDITIVE**: `ticket_files` (TicketFilesQuery) -> `ticket_files_report` (capped text): the files a ticket changed, the tickets a path glob was changed by, the files of its related tickets, a backfill from git. See CONTRACT.md.
    // **1.138.0 (PLUGIN-ENDPOINTS-2, ticket-779d5845) is ADDITIVE**: `plugin_secret_get` ({module, field}) -> `plugin_secret` ({module, field, value}): one field of the secret `plugin.<module>` for that module's endpoint credential. Owner-only, refused under a seated lockdown, `AGENTHQ_*` fields refused. See CONTRACT.md.
    // **1.139.0 (ASK-TIMER, ticket-c62e72a9) is ADDITIVE + BEHAVIOURAL**: `prompt_request.expiresAt|onTimeout`; settings `prompts.timeoutSec` 0 = no timer, `prompts.onTimeout` (deny | approve); `new` `promptTimeoutSec|promptOnTimeout`; expiry logged in the transcript; owner questions default `defaultAfterMin` to the timer. See CONTRACT.md.
    // **1.140.0 (ASK-TIMER-2, ticket-cc73295b) is ADDITIVE + BEHAVIOURAL**: `prompt_request.recommended`; `prompt_ask.recommended`; an expired prompt auto-selects its recommended option (fallback `prompts.onTimeout` / no answer). See CONTRACT.md.
    // **1.141.0 (USAGE-1, ticket-98828ad8) is ADDITIVE**: `usage_meter_get` -> `usage_meter` (daily tokens/cost by role, model, source). See CONTRACT.md.
    // **1.142.0 (CONFIG-8) is ADDITIVE**: `config_get`, `config_schema`, `config_values`, `config_set` (expectRev), `config_changed`; `GET /config/schema`. See CONTRACT.md.
    // **1.143.0 (POLICY-1, ticket-b6446a82) is ADDITIVE + BEHAVIOURAL**: `policy` (PolicyWire, after `ready` and on every switch), `policy_get`, `policy_set` (owner, refused while pinned); `processes_reap` (the killing half of `processes`; a legacy `processes` reap is gated as it); under a non-production policy every client frame is gated by its named action (ActionCatalog). See CONTRACT.md.
    // **1.144.0 (DAEMON-PLUGINS-1, ticket-fb588e94) is ADDITIVE + BEHAVIOURAL**: `plugin_tools` -> `plugin_tools` (the daemon plugins' tool descriptors), `plugin_tool_call` ({name, arguments}) -> `plugin_tool_result` ({name, text, isError}); plugin frames and routes; `GET /plugins` modules gain `daemon`, `tools`. The seated lockdown (1.136.0's allowlist included) is a daemon plugin's: a seated preset no plugin implements refuses every frame but the switch. See CONTRACT.md.
    // **1.145.0 (HARNESS-PLUGIN-4 H5a, ticket-24a2706c) is ADDITIVE**: `GET /plugins` modules gain `harness` (the harness block's state) and `harnessIds`; `harness_install_status` also lists the CLIs harness modules declare in `harness.cli.install`. See CONTRACT.md.
    // **1.146.0 (CONTEXT-LIMIT-1, ticket-1a983d71) is ADDITIVE**: a per-session context limit. `new` (SpawnOptions) and `ticket_save` take `contextLimit` (tokens; 0 clears on a ticket), tickets carry `contextLimit` + `effectiveContextLimit`, sessions `contextLimit` + `contextLimitSupport`, `providers` entries `contextLimit` (supported / best_effort / unsupported) + `contextLimitHow`. See CONTRACT.md and docs/CONTEXT-LIMIT.md.
    // **1.147.0 (RUNTIME-LOAD-4, ticket-61177938) is ADDITIVE**: a `plugin_tools` descriptor may list `localFiles` (argument keys naming files on the caller's machine); `plugin_tool_call` then carries those files inline (`{path, size, base64?}`) and `client.projectId` (the MCP server's default project). See CONTRACT.md.
    // **1.148.0 (EFFORT-1, ticket-b80b8582) is ADDITIVE**: a reasoning effort on tickets. `ticket_save` takes `effort` (low..max; "" / off clears), tickets carry `effort` + `effectiveEffort` {value, source, support, how}, sessions `effortSupport`, `providers` entries `effort` (supported / best_effort / unsupported) + `effortHow`. `new` (SpawnOptions) and agent profiles already carried `effort`. See CONTRACT.md and docs/EFFORT.md.
    // **1.149.0 (ARCHIVE-1, ticket-719fcaf4) is ADDITIVE**: the ticket archive. `archive_query` (list / show / search / count) and `archive_apply` (archive / unarchive a ticket, an epic or a lane filter) -> `archive_report` (text + items). Setting `board.archive.doneAfterDays` (default off) archives done tickets after N days. See CONTRACT.md.
    const val VERSION = "1.149.0"

    /** Frame type carrying incremental assistant text (payload: a plain String, the delta). */
    const val TEXT_DELTA = Envelope.TEXT_DELTA

    /**
     * Contract 1.23.0: frame type carrying the model's own reasoning (payload: a plain String, the
     * thought text), distinct from [TEXT_DELTA] so a UI can render thinking apart from the answer.
     */
    const val REASONING = Envelope.REASONING

    /**
     * Contract 1.11.0: frame type carrying the prompt a run was started with (payload: a plain String,
     * exactly what the provider received). Sequenced on the session stream before that run's output.
     */
    const val TURN = Envelope.TURN

    /** Frame type carrying a lifecycle transition (payload: a `StatusPayload`). */
    const val STATUS = Envelope.STATUS

    /** Frame type carrying a daemon-side failure (payload: a plain String, the message). */
    const val ERROR = Envelope.ERROR

    /** Frame type carrying how the run ended (payload: a `FinishedPayload`). */
    const val FINISHED = Envelope.FINISHED

    // --- Phase 5b: the pod command surface. NEW frames; none of the four above moved. ---

    /**
     * Client command: submit a pod (payload: a `PodSubmitRequest` as JSON). The daemon answers [POD].
     *
     * A pod is a DAG of nodes the daemon schedules itself; it is the orchestration entry point that
     * Phase 5 built but, until this version, nothing outside the daemon could reach.
     */
    const val POD_SUBMIT = Envelope.POD_SUBMIT

    /** Client command: read one pod's state (payload: `"<podId>"`). The daemon answers [POD]. */
    const val POD_QUERY = Envelope.POD_QUERY

    /** Client command: cancel one pod (payload: `"<podId> [force]"`). The daemon answers [POD]. */
    const val POD_CANCEL = Envelope.POD_CANCEL

    /**
     * The answer to all three (payload: a `PodStatus` as JSON -- the pod, its nodes, and its runs).
     *
     * One frame for submit, query and cancel, because all three answer the same question: what is
     * this pod's state NOW. A client never has to wait for a later push to learn the id of a pod it
     * just submitted.
     */
    const val POD = Envelope.POD

    /** Push and acknowledgement answer carrying one durable [com.superiorsoftllc.agenthq.attention.AttentionItem]. */
    const val ATTENTION = Envelope.ATTENTION

    /** Client command requesting the durable attention history; answered by [ATTENTION_ITEMS]. */
    const val ATTENTION_LIST = Envelope.ATTENTION_LIST

    /** Answer to [ATTENTION_LIST], carrying the ordered attention list as JSON. */
    const val ATTENTION_ITEMS = Envelope.ATTENTION_ITEMS

    /** Client command acknowledging the item id in its plain-string data. */
    const val ATTENTION_ACK = Envelope.ATTENTION_ACK
}

/**
 * One lifecycle transition, in the client's own terms.
 *
 * [state] is the same [SessionState] the `session` frame carries, so a client has ONE state
 * vocabulary to learn, not two. [detail] is a human-readable gloss ("provisioning worktree",
 * "killed by request") that a client may show but never has to parse.
 */
@Serializable
data class StatusPayload(
    val state: SessionState,
    val detail: String = "",
    /**
     * Contract 1.25.0: the name of the tool the run is currently calling (e.g. `Read`, `Bash`), or
     * `null` when this status is not about a tool call. This is the field a UI reads to show
     * "Running read" instead of a plain spinner.
     *
     * It is optional AND omitted when null (`@EncodeDefault(NEVER)`), so the JSON a lifecycle-status
     * frame puts on the wire is byte-for-byte what 1.24.0 published: a 1.24.0 client decodes a
     * 1.25.0 frame unchanged (`ignoreUnknownKeys`), and a no-tool status is unchanged for everyone.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tool: String? = null,
    /**
     * Contract 1.26.0 (version PM-allocated after AGENT-UX-9): the tool call's salient ARGUMENT, the
     * "what is it doing right now" half that a tool NAME does not carry -- the path being searched, the
     * command being run, the file being read. It is a single short, REDACTED line produced by
     * [EventNormalizer] from a narrow allow-list of argument keys; it never carries file CONTENTS or
     * the environment.
     *
     * Optional and omitted when null (`@EncodeDefault(NEVER)`), exactly like [tool]: a tool call with
     * no salient argument (or an old daemon) simply has no `activity`, so the frame stays additive.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val activity: String? = null,
    /**
     * Contract 1.80.0 (UX-WRITE-1): set only on a FILE-WRITING tool call (claude `Write` / `Edit` / `MultiEdit`, opencode
     * `write` / `edit` / `multiedit` / `patch` / `apply_patch`), so a UI can show the change inline in the chat. It is the
     * one place file contents reach the wire, and only the contents the agent itself is writing: what a run READS is
     * never sent. Omitted when null, so every other status frame is byte-for-byte what 1.79.0 published.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val write: FileWrite? = null,
    /**
     * Contract 1.127.0 (TODO-OVERLAY-1): the agent's own to-do list, set on the status frame of a to-do tool call (claude
     * `TodoWrite`, opencode `todowrite`). It is the FULL list, so a client REPLACES its list; an empty list clears it. Only
     * the items (text, status, activeText) are sent, never other tool arguments. Omitted when null, so every other status
     * frame is byte-for-byte what 1.126.0 published.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val todos: List<com.superiorsoftllc.agenthq.protocol.todo.TodoItemPayload>? = null,
    /**
     * Contract 1.95.0 (1c24fdc4): the run's harness-neutral activity ([RunActivity]: `thinking`, `tool_running`, ...),
     * sent whenever its phase or label changes. A UI's working indicator reads this and nothing harness-specific.
     * Omitted when null, so every other status frame is byte-for-byte what 1.94.0 published.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val runActivity: RunActivity? = null,
)

/**
 * Contract 1.80.0 (UX-WRITE-1): what one file-writing tool call wrote. Exactly one of the three shapes is set:
 *  - [content]: the whole file, as written (a `Write`): a UI shows it as a created file;
 *  - [edits]: string replacements (`Edit` / `MultiEdit`): a UI shows each old text as removed and the new as added;
 *  - [patch]: the patch text the agent applied (opencode `patch` / `apply_patch`), drawn as a diff.
 * [path] is the file written (for a patch, the first file it names). Each text is capped at
 * [EventNormalizer.WRITE_MAX_CHARS] characters; [truncated] is true when any was cut.
 */
@Serializable
data class FileWrite(
    val path: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val content: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val edits: List<FileEdit>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val patch: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val truncated: Boolean = false,
)

/** Contract 1.80.0: one string replacement of an edit: [oldText] was replaced by [newText]. */
@Serializable
data class FileEdit(val oldText: String, val newText: String)

/**
 * How a run ended, in the client's own terms.
 *
 * [exitCode] is the provider process's exit code, or `null` when the run never produced one (a
 * transport that could not spawn, or a forced kill with no exit report). [outcome] is the daemon's
 * own judgement -- it never leaves the client to infer "failed" from an exit code alone, because a
 * deliberate kill and a crash both end a process and mean opposite things to a user.
 */
@Serializable
data class FinishedPayload(
    val exitCode: Int?,
    val outcome: Outcome,
    val detail: String = "",
) {
    /** Why the run is over. Ordered from "nothing wrong" to "we stopped it". */
    @Serializable
    enum class Outcome {
        /** The run completed and the provider exited 0. */
        SUCCEEDED,

        /** The provider exited non-zero, or its run could not be started at all. */
        FAILED,

        /** A client asked for `kill` and it resolved gracefully (stdin EOF inside the grace window). */
        KILLED_GRACEFUL,

        /** A client asked for `kill` and the process tree had to be killed by force. */
        KILLED_FORCED,

        /** A daemon restart found this run's child still alive; it was not adopted. */
        ORPHANED,
    }
}
