# Effort (EFFORT-1, ticket b80b8582)

A per-session **reasoning effort** (the "thinking level"): how hard the model thinks before it answers. Set it per agent
(profile / role binding / routing rule / spawn) or per ticket (the ticket's worker), so a PM gives a hard ticket a high
effort and a mechanical one a low one. Default: **unset = the harness's own default**; unset is the off switch, and a session
that sets none behaves exactly as before.

It copies the pattern of the context limit ([CONTEXT-LIMIT.md](CONTEXT-LIMIT.md)): one abstract value, a three-state harness
capability, the mapping inside each harness, the value recorded on the session and in `run_usage`.

## The levels are data

`session.EffortLevels.ALL` (and its shared copy `daemon.api.board.EffortLevels` for plugins) is the ONE ordered list:
`low`, `medium`, `high`, `xhigh`, `max`, each with a label. Spawn, agent profiles, role bindings, routing rules and tickets all
validate against it; a harness maps a level to its own flag. Add or reorder levels there only. Input accepts any case; blank,
`off`, `none`, `default` = unset.

## Contract

| Layer | What was added (all additive) |
|---|---|
| Harness API 2.2.0 (`infantryHarnessApi`) | `Harness.effort: EffortCapability` (default `UNSUPPORTED`), `EffortSupport { SUPPORTED, BEST_EFFORT, UNSUPPORTED }`, `EffortCapability(support, how)`. `HarnessRequest.effort` already existed in 2.0.0. |
| Daemon harness contract 1.8.0 | the same on the 1.x `Harness`; default = supported when the harness declares `EFFORT_SELECTION`, else unsupported. `AgentProvider.effort()`. |
| Wire 1.148.0 | `ticket_save.effort`, `ticket.effort` + `effectiveEffort {value, source, support, how}`, `session.effortSupport`, `providers[].effort` + `effortHow` |
| Board API 1.4.0 (`infantryDaemonApi`) | `TicketInfo.effort` / `effectiveEffort*` / `effortSupport`, `TicketDraft` / `TicketPatch.effort`, `SpawnRequest.effort`, `SessionInfo.effort` / `effortSupport`, catalog `effort` / `effortHow` |
| DB v56 (`55.sqm`) | `tickets.effort`, `run_usage.effort` (next to `context_limit`) |
| Registry | `AgentDefinition.effort` (additive, validated against the levels; a profile fills it like `contextLimit`) |

Support levels are the same as for the context limit: `supported` (the CLI applies the level), `best_effort` (mapped, the CLI may
clamp or ignore it), `unsupported` (**recorded and reported, not applied**: never passed to the harness, never silently
dropped; the session carries `effortSupport: unsupported` and a ticket start says `(recorded, not applied by <harness>)`).
A 2.0 / 2.1 harness plugin keeps working unchanged: it is `unsupported` unless it declares `EFFORT_SELECTION`.

## Per-harness mapping (verified 2026-10-30 against the installed CLIs)

| Harness | Support | How | Verified |
|---|---|---|---|
| claude (Claude Code 2.1.312) | supported | `--effort <level>` on the one-shot argv and on the persistent session (in the session fingerprint, so a change restarts + resumes). | `claude --help` lists `--effort <level>  (low, medium, high, xhigh, max)`; **ran** `claude -p "say ok" --effort low` (answered); `--effort bogus` warns `Unknown --effort value ... Valid values: low, medium, high, xhigh, max` and falls back to the default. Not compared for quality. |
| pi (pi 1.3.0) | supported | `--thinking <level>` (pi's name for it), one-shot and `--mode rpc`. pi also has `off` and `minimal`, which the shared list does not offer. | `pi --help` lists `--thinking <level> ... off, minimal, low, medium, high, xhigh, max`; **ran** `pi --thinking low -p "say ok"` (answered). pi clamps a level the chosen model lacks; not tested per model. |
| opencode (1.18.35) | unsupported | Not mapped. `opencode run --help` lists `--variant` ("provider-specific reasoning effort, e.g. high, max, minimal"), but its values are per provider/model, and a live `opencode run --variant high -m opencode/beam-free` did not finish within 60 s here, so it is **not verified**; the persistent (serve) mode has no variant input in the daemon either. | Ran the help; the live run timed out. Follow-up: read each model's variants from `opencode models --verbose`, pass `--variant` only when the model lists it (best_effort). |
| plugin harness (API 2.2) | as declared | `Harness.effort`; the request carries `effort` only when not unsupported. A plugin below 2.2 is supported only if it declares `EFFORT_SELECTION`. Reference: `Acme_EchoHarness` declares `supported` and prints `effort: <level>` from `ECHO_EFFORT`. | `EchoHarnessModuleDaemonTest`. |

## Where it is set

- **Spawn**: `new` frame `effort` (existing); MCP `infantry_spawn` `effort` (`low`..`max`, `off`).
- **Agent profile / role binding / routing rule**: existing `effort` fields; registry `AgentDefinition.effort`.
- **Ticket**: `ticket_save.effort`; MCP `infantry_ticket_create` / `infantry_ticket_update` `effort`; CLI
  `infantry ticket create|update --effort high|off`. `ticket show` prints `EFFORT: high (ticket) / harness: supported`;
  `infantry_ticket_get` returns `effort`, `effectiveEffort`, `effectiveEffortSource`, `effortSupport`; `infantry_list_harnesses`
  and `infantry harnesses` show `effort` per harness.
- **Effective value and source**: the ticket's own pin, else the matched routing rule (`rule`), the agent profile (`agent`), the
  role binding (`role`), else `default` (no effort). A start passes the resolved level to the worker session.

## Usage

Each run's `run_usage` row records the session's `effort` (null = harness default) next to `context_limit`, tokens and cost, so
the stats product can compare turns and tokens by effort (the owner's pi vs claude measurement). Data only for now; no screen.

## UI (second slice)

`ui/model/EffortModel.kt` and `ui/EffortViews.kt` (infantryUiCore) mirror ContextLimitModel / ContextLimitViews: the shared
`EffortField` (checkbox = off/harness default, one button per level of `EffortLevels.ALL`, picked one ticked, a per-harness
support line) in the New agent dialog and ticket create/edit; the `effort high` / `(best effort)` / `(not applied)` chip on
the session header (`CenterHeader`) and the agent card (`RolesAttention`); and the board card line `effort high (ticket)`.
Theme tokens only (Danger for "not applied", Info otherwise), always with the words. The UI's level list mirrors the
daemon's; add a level in both.
