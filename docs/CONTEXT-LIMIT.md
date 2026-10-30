# Context limit (CONTEXT-LIMIT-1, ticket 1a983d71)

A per-session **context limit**: the most context (in tokens) an agent's conversation may hold before its harness compacts
or caps it. Set it per agent (spawn) or per ticket (the ticket's worker), so a PM gives a hard, long ticket a large window
and a small job a small one. Default: no limit (the harness's own window); nothing changes for a session that sets none.

Core and the shell know only the abstract value (an `Int` of tokens) and a three-state capability. How a limit is applied
is each harness's own, in its translator / adapter. No harness name appears in core.

## Contract

| Layer | What was added (all additive) |
|---|---|
| Harness API 2.1.0 (`agentHqHarnessApi`) | `HarnessRequest.contextLimit: Int?`, `Harness.contextLimit: ContextLimitCapability` (default `UNSUPPORTED`), `ContextLimitSupport { SUPPORTED, BEST_EFFORT, UNSUPPORTED }`, `ContextLimitCapability(support, how)` |
| Daemon harness contract 1.7.0 | the same on the 1.x `Harness` / `HarnessRequest`; `AgentProvider.contextLimit()` + `RunRequest.contextLimit` |
| Wire 1.146.0 | `new.contextLimit`, `ticket_save.contextLimit` (0 clears), `ticket.contextLimit` + `effectiveContextLimit {value, source, support, how}`, `session.contextLimit` + `contextLimitSupport`, `providers[].contextLimit` + `contextLimitHow` |
| Board API 1.2.0 (`agentHqDaemonApi`) | `TicketInfo.contextLimit`, `effectiveContextLimit`, `effectiveContextLimitSource`, `contextLimitSupport` (Infantry_Board's `agenthq_ticket_get`) |
| DB v54 (`53.sqm`) | `tickets.context_limit`, `run_usage.context_limit` |

Support levels:

- `supported`: the CLI itself keeps the conversation at or under the limit.
- `best_effort`: approximated (e.g. compacted between turns, so one turn can pass it).
- `unsupported`: the limit is **recorded and reported, not applied**: never passed to the harness, never silently dropped.
  The session carries `contextLimitSupport: unsupported`, a ticket start says `(recorded, not applied by <harness>)`.

A 2.0.0 harness plugin keeps working unchanged and is `unsupported` (the interface default). Values: 10000..10000000
tokens; input accepts `200000`, `200k`, `1m`, `off` (`ContextLimits.parse`).

## Per-harness mapping (verified 2026-10-30 against the installed CLIs)

| Harness | Support | How | Verified |
|---|---|---|---|
| claude (Claude Code 2.1.312) | supported | env `CLAUDE_CODE_AUTO_COMPACT_WINDOW=<tokens>` on the process (one-shot spec and persistent session; in the session fingerprint, so a change restarts + resumes). Claude Code auto-compacts at that window; a value below its minimum is raised to the minimum. | The binary's own strings: `CLAUDE_CODE_AUTO_COMPACT_WINDOW="..." Set it to a size ... such as 200k or 200000`, and "To enforce it, set CLAUDE_CODE_AUTO_COMPACT_WINDOW". Not run end to end against the API. |
| pi (pi 1.3.0, persistent `--mode rpc`) | best_effort | After a turn settles cleanly the adapter sends `get_session_stats`; if `contextUsage.tokens` > limit it sends `compact` and ends the turn on the compact's answer, so the next turn starts compacted. One-shot pi (`--mode json`, keep-alive 0) is `unsupported`. | pi docs `rpc-commands.md` (get_session_stats, compact), `compaction.md`, `settings.md`: the threshold is `contextWindow - compaction.reserveTokens` and is set only in settings files (no flag or env), so the daemon cannot set it. Adapter unit-tested with a fake process; not run against a live pi. |
| opencode (1.18.35) | unsupported | Not mapped. The compaction threshold is the model's `limit.input - compaction.reserved` (or `limit.context - output`). An inline `OPENCODE_CONFIG_CONTENT` override of a model's `limit.context` is refused unless it also names `limit.output` (ran it: `Missing key provider.opencode.models.beam-free.limit.output`), which the daemon does not know per model. | Ran `opencode models --verbose` with and without the override. Follow-up: read each model's output limit from `opencode models --verbose` and send the full limit object, or call `POST /session/:id/summarize` between turns (best effort, like pi). |
| plugin harness (API 2.1) | as declared | `Harness.contextLimit` + `HarnessRequest.contextLimit` (only set when not unsupported). Reference: `Acme_EchoHarness` declares `supported` and prints `context limit: <n>` from `ECHO_CONTEXT_LIMIT`. | `EchoHarnessModuleDaemonTest`. |

## Where it is set

- **New agent (UI)**: the New agent dialog's *Context limit* field (blank = harness default); the chosen harness's support
  is shown next to it.
- **Spawn**: `new` frame `contextLimit`; MCP `agenthq_spawn` `contextLimit` (`200k`, `1m`, `off`). The CLI has no spawn command.
- **Ticket**: `ticket_save.contextLimit`; MCP `agenthq_ticket_create` / `agenthq_ticket_update` `contextLimit`; CLI
  `agenthq ticket create|update --context-limit 200k|off`. `ticket show` prints `CONTEXT LIMIT: 200k (ticket) / harness: supported`.
  A start passes it to the worker session and names it in `start.detail`.
- Role defaults are out of scope (source is `ticket` or `default` only).

## Usage

Each run's `run_usage` row records the session's `context_limit` (null = none), next to its tokens and cost, so the stats
product can compare runs with and without a limit. Data only for now; no screen.

## Relation to CONTEXT-SWITCH-1 (7eef2cb9)

CONTEXT-SWITCH-1 asks for a `contextSize` choice (250k / 500k / 1M) and a fresh-context reset. It should reuse this
value and capability rather than add a second one: its size picker writes `contextLimit`, its "change mid-session"
falls back to restart-with-handoff at the new limit, and its per-harness support table starts from the one above.
