# Harness support matrix (HARNESS-AUDIT-1, 2026-11-05)

This page shows which recent features (scripts and hooks, board automation, background processes, tools, usage)
work on each harness, with the evidence for every cell. It is an audit: nothing here was fixed. Each real gap is a
`HARNESS-GAP:` ticket on the board (see [Gaps](#gaps)).

- **Code** was read at `ff9e1177` (this ticket's base). Where `feature-agenthq-mvp` has moved on (SECRETS-2b), the
  cell says so.
- **Live** runs used a scrubbed test daemon (port 8812, temp state dir) on dist build `20261105-120733-b9c6373e`,
  with cheap models: claude `claude-haiku-5-5`, pi `anthropic/claude-haiku-5-5`, opencode
  `opencode/deepseek-v4.1-flash-free`. The probe scripts are in `scripts/harness-audit/` (see [Probes](#probes)).

Cell values: **yes** = supported, **partial** = works with a limit (said how), **no** = unsupported,
**untested** = code says yes but no live run, **n/a** = does not apply. *(live)* marks a cell seen on the test
daemon; every other cell is from the code.

Path shorthands (all under the repo root):

| Short | Path |
|---|---|
| `SM` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/SessionManager.kt` |
| `SH` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/host/SessionHost.kt` |
| `MR` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/mailbox/MailRouter.kt` |
| `BJN` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/mailbox/BgJobNotifier.kt` |
| `FNM` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/mailbox/FinishNoticeMonitor.kt` |
| `SU` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/usagemeter/StreamUsage.kt` |
| `DM` | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/DaemonMain.kt` |
| `BG` | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/BgCli.kt` |
| `TA` | `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/ToolApprovals.kt` |
| `Claude/` | `plugins/official/Infantry_Claude/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/claude/harness/` |
| `Tty` | `Claude/ClaudeTtySession.kt` (claude interactive) |
| `CS` | `Claude/ClaudeSession.kt` (claude headless) |
| `CC` | `Claude/ClaudeCommand.kt` |
| `Pi/` | `plugins/official/Infantry_Pi/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/pi/harness/` |
| `OC` | `plugins/official/Infantry_OpenCode/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/opencode/harness/OpenCodeServeSession.kt` |
| `Note` | `plugins/official/Infantry_Scripting/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/ChatNoteTools.kt` |

## Summary

Most features are harness-agnostic. Mail, bg jobs, hooks, auto-merge, pins and resume run through generic daemon
code, so they behave the same on every harness. The differences that matter:

1. **Claude interactive (the default) fails on a folder it has never trusted** *(live, 3 of 3)*. The auto-answer
   to the trust question ends on "No, exit", so the CLI quits with code 1. Then the session stays wedged: the
   provider session id was recorded before the CLI wrote a transcript, so every later resume fails with "No
   conversation found", even after switching to headless *(live)*. A new user's first project hits this.
2. **Tool approval only fully works on opencode.** The fresh-install default is `DENY` (`TA:54`). On claude, DENY
   and ASK pass no `--permission-mode` (`CC:60`), so the CLI runs in its own `default` mode. Headless refuses
   risky commands *(live)*. Interactive would show the CLI's permission dialog in the PTY, and nothing answers it
   (from the code, not seen live). ASK = DENY on claude headless, and pi ignores every mode.
3. **A wake to a busy session is lost (every harness).** `chat_note wake=true` to a busy session answers "it will
   see the note when its turn ends", and the note never arrives *(live, pi and claude headless)*. The fix
   NOTE-WAKE-PTY-1 (`96975c45`) is **not merged**: it is on neither HEAD nor `feature-agenthq-mvp`.
   `infantry_prompt` to a busy session is refused with `run_in_flight` *(live)*, not queued.
4. **pi has no MCP** *(live: "I don't have MCP tools available here")*. Infantry tools are reachable only through
   the `infantry` CLI.
5. **Usage**: interactive claude records tokens but no reported cost and no compaction events. Headless gets both
   *(live: cost on every run row)*.

## 1. Scripts and hooks driving a session

| Feature | claude interactive | claude headless | pi | opencode |
|---|---|---|---|---|
| `infantry_prompt` into an idle session | yes: the turn is pasted and Enter pressed (`Tty:197-201,288-294`) | yes *(live)*: a stream-json user line (`CS:117-126`) | yes *(live)*: rpc `prompt` (`Pi/PiSession.kt:227-234`) | yes: `prompt_async` (`OC:138-139`) |
| `infantry_prompt` into a busy session | **no**: refused with `run_in_flight` (`DM:2856-2859`), not queued. Only the UI and `session_act queue_add` queue (`SM:1747-1758`) | **no** *(live: "already has a run in flight")* | **no** (same code) | **no** (same code) |
| "Send now" into a running turn | yes (`Tty:218-225`) | yes (`CS:138-142`) | **no**: no `injectInput`, force refused (`SM:1787`) | **no** (same) |
| `chat_note wake=true`, idle session | yes (`api.sessions.resume`) | yes | yes | yes |
| `chat_note wake=true`, busy session | **no**: wake dropped (`Note:79-83`); fix `96975c45` unmerged | **no** *(live: note never arrived)* | **no** *(live: note never arrived)* | **no** (same code) |
| `chat_note` session id | full id only *(live: an 8-char prefix is "unknown session")*; `infantry_prompt` takes a prefix | same | same | same |
| mail / `mail.incoming` hook | yes: the hook runs before delivery; delivery is a new turn at rest (`MR:412-413,464-469`) | yes | yes *(live: the bg mail woke pi)* | yes |
| worker-watch kicks (`.agenthq/scripts/worker-watch.lua`) | yes: kicks an IDLE session | yes | yes | **partial**: a turn with no reply ends FAILED (`OC:325-328`), so the session is never IDLE and is never kicked |
| on-ticket-done close / remove | yes: `/exit`, else a tree kill (`Tty:227-234`, `SH:399-420`) | yes: closes stdin (`CS:144-147`) | yes: closes stdin (`Pi/PiSession.kt:244-247`) | **partial**: `close()` = false, always a tree kill (`OC:206`) |

## 2. Automation and board flow

| Feature | claude interactive | claude headless | pi | opencode |
|---|---|---|---|---|
| `ticket_start` model pin | yes `--model` (`CC:190-191`) | yes *(live)* | yes *(live)* `--model` (`Pi/PiCommand.kt:51`) | yes *(live)*: per turn in `prompt_async` (`OC:454-457`) |
| effort pin | yes `--effort` | yes *(live: "effort low: supported")* | yes `--thinking` (`Pi/PiCommand.kt:52`) | **no**, recorded *(live: "recorded, not applied by opencode")* |
| contextLimit pin | yes: env `CLAUDE_CODE_AUTO_COMPACT_WINDOW` (`Tty:109`, `CC:265-274`) | yes (`CS:62,69`) | partial: compacts after a turn that ends over it (`Pi/PiSession.kt:174-191`) | **no**, recorded (`SM:1451-1454`) |
| role / provider / agent identity | yes: env (`SM:1463-1469`) | yes | yes | yes |
| done report reaches the PM | yes: `ticket move done` → on-ticket-done (generic) | yes *(live: moved itself to done)* | yes *(live: moved itself to done)* | untested (free tier quota) |
| "went idle without a report" | yes on a clean exit (`FNM:108-143`); turn end = the Stop hook (`Tty:257-262`) | yes: `result` record (`CS:102-114`) | yes: `agent_settled` | yes: `session.idle` |
| ... after a FAILED turn with no exit code | **no** (every harness): `SM:1679` → `failRun` (`SM:1869-1880`) emits no `SessionEvent.Exit` and never drains the queue | **no** | **no** | **no** |
| auto-merge after done | yes: scripted (`vc-merge.lua` on the VC's `mail.incoming`), harness-agnostic | yes | yes | yes |
| queue ("Agent is busy. Enter to queue") | yes: UI + `session_act queue_add`, drained at rest (`SM:1727,1805-1816`) | yes | yes | yes |
| resume after daemon restart | yes: `--resume` (`CC:188-189`) *(live: "session resumed")*, **but** see gap G2 | yes: `--resume` | yes *(live)*: `--session` (`Pi/PiCommand.kt:53`) | yes *(live: "resuming provider session ses_...")* (`OC:148-153`) |
| first start in a never-trusted folder | **no** *(live, 3/3)*: the trust auto-answer ends on "No, exit", CLI exits 1 (`Tty:383-402`) | yes *(live)*: `-p` has no trust question | yes | yes |
| one-shot fallback (keep-alive 0) | headless `-p` even in interactive mode (`Claude/ClaudeHarnessPlugin.kt:72-87`); MID_TURN_INPUT declared but `injectInput` = false (`HarnessSessionAdapter.kt:128`) | same | `--mode json` | `opencode run` |

## 3. Background processes

| Feature | claude interactive | claude headless | pi | opencode |
|---|---|---|---|---|
| `infantry bg run --notify` from the agent's shell | yes: keyed by `INFANTRY_SESSION_ID` (`BG:20,421-423`) | yes *(live)* | yes *(live)* | yes (same CLI) |
| end-of-job wake | yes: mail, a new turn at rest (`MR:447-465`, `BJN:166-169`) | yes *(live: woke and read its inbox)* | yes *(live: "bg job e0335588 finished" became a turn)* | untested (quota) |
| CHAT-BG-1 chat row | yes: UI reads `bgJobs` / `backgroundWork` (process tree under the harness process) | yes | yes | yes (root = the `serve` process) |
| process attribution / reaping (PROC-LEAK-2) | yes: Windows Job Object per session (`SM:151,2107,2121`) plus the ledger; harness-agnostic | yes | yes | yes *(live: "reaped stale opencode process" on restart)* |
| `bg stop` | partial: the CLI kills the wrapper and its `descendants()` only (`BG:1006-1013`), not the Job Object, no start-time check | partial | partial | partial |
| the CLI's own background tool | partial: routed through the watched runner only under ALLOW_ALL (`Tty:105`); otherwise native, seen only by the tree watch (`SM:771-793`); may wake twice (`BJN:265-267`) | partial (same, `CS:55,95-100`) | n/a (pi has none) | n/a |

## 4. Tools

| Feature | claude interactive | claude headless | pi | opencode |
|---|---|---|---|---|
| infantry MCP server attached | yes: `--mcp-config` + `--strict-mcp-config` (`CC:104-111,197-199`) | yes *(live: used `infantry_inbox`)* | **no** *(live)*: only the todo/watch extensions load (`Pi/PiSession.kt:66`) | yes: `mcp.infantry` in the serve config (`OpenCodePermissions.kt:86-95`, `OC:79`) |
| MCP state reported (`session.mcp`) | yes: pid-file poll (`Tty:361-373`) | **no** | n/a | **no** (no `mcpState` call) |
| `mcp_reconnect` in place | **no**: restart + resume fallback (`SM:1374-1396`) | no (fallback) | n/a | no (fallback) |
| ToolApproval DENY | partial: no flag, so the CLI's `default` mode; the PTY dialog is unanswered (`CC:60`) | partial *(live: "A variable in this command can't be checked before it runs" refused)* | **no**: ignored, every tool runs (`Pi/PiCommand.kt:24-25`) | yes: reject (`OpenCodePermissions.kt:58-62`) |
| ToolApproval ASK | **no** (as DENY) | **no**: relay not ported (`CS:29-30`) | **no** | yes: `permission.asked` → `requestApproval` (`OC:279-281,505-506`) |
| ALLOW_EDITS / ALLOW_ALL | yes: `acceptEdits` / `bypassPermissions` | yes | **no** (ignored) | yes |
| watched tool calls (`watch()`) | partial: hooks only under ALLOW_ALL (`Tty:105-106`), by design ("a hook must never widen", `WatchHooks.kt:34-37`) | partial (same) | yes: every mode | partial: ALLOW_ALL only |
| secrets as env + masking (SECRETS-2/2b, on `feature-agenthq-mvp`) | partial: secrets go only into saved run commands' env; masked on run output (`Infantry_Run/.../RunManager.kt:317`). Session output and the PTY screen are **not** masked | same | same | same |
| run commands (RUN-SCOPE-1) | yes: MCP tools scoped to the caller's project | yes | partial: CLI `run-cmd` only (no MCP) | yes |

## 5. Usage and stats

| Feature | claude interactive | claude headless | pi | opencode |
|---|---|---|---|---|
| tokens per request/run | yes: the transcript tail is re-emitted as stream-json (`Claude/ClaudeTranscript.kt:62-75`) | yes *(live: 10 requests, 2 runs)* | yes *(live: 8 requests, 3 runs)* | yes: `step_finish` (`SU:137-143`) |
| cost | **no**: the made-up `result` line has no `total_cost_usd` (`Claude/ClaudeTranscript.kt:107-116`) | yes *(live: run cost 0.0058 / 0.0107)* | yes *(live)* | yes; free models report 0 |
| turns | API requests (message ids, `SU:100,149`) | same (+ `num_turns` fallback) | assistant `message_end` | `step_finish` (meter) vs assistant messages (`stats()`): not comparable |
| compaction events | **no**: `system` records dropped (`Claude/ClaudeTranscript.kt:55-58`) | yes: `compact_boundary` (`SU:85-88`) | partial: auto-compaction counted, the daemon's own `compact` not | **no** |
| session stats (`stats()`) | partial: first record per message id only, likely undercounts output (`Claude/ClaudeStatsUsage.kt:68-75`); ignores `CLAUDE_CONFIG_DIR` (`Claude/ClaudeHarnessPlugin.kt:126`) | partial (same) | yes (core reader) | yes (`opencode.db`) |
| context size / `contextCapTokens` restart | yes | yes | yes | yes |
| plan quota / pace meter | yes: claude OAuth | yes | partial: only `anthropic/*` models (charged to the claude meter) | partial: only `opencode-go/*` models; Zen/free and other providers have no source |

## Gaps

Each row is one board ticket `HARNESS-GAP: <feature> on <harness>`, in todo with a weight. **Owner** marks a
choice only the owner can make.

Tickets: G1 f0b7173f, G2 d472e3f6, G3 11754e3b, G4 c2f05317, G5 46ca4afd, G6 25a68b9d, G7 d0503cc8, G8 3fd235bb,
G9 b908f8fa, G10 fa6ef6ed, G11 591f7bd7, G12 882108cc, G13 b5fe0722, G14 2566bb86, G15 100fbefd, G16 d84c460b,
G17 6f4c36b1, G18 5a69ec0f, G19 5ccc0b8c, G20 ba7f1956, G21 d4945c15.

| # | Gap | Harness | Evidence | Owner? |
|---|---|---|---|---|
| G1 | Folder trust auto-answer ends on "No, exit"; first start in a fresh folder fails | claude interactive | live 3/3; `Tty:383-402` (Down re-sent every 1.5 s toggles the 2-item menu) | |
| G2 | Provider session id recorded before the CLI is up: a launch that writes no transcript wedges the session ("No conversation found" on every resume); the "no provider session" health check cannot fire | claude interactive | live; `Tty:264-266` | |
| G3 | DENY / ASK / ALLOW_EDITS show the CLI's own permission dialog in the PTY and nothing answers it; no per-turn timeout | claude interactive | `CC:53-66`, `Tty` has no dialog handling; `TA:54` default DENY | FIXED 11754e3b (owner: dontAsk + hook): DENY = `--permission-mode dontAsk`; a `PermissionRequest` http hook to the plugin's loopback endpoint denies (ASK relays to the owner); a dialog no hook answered is answered No after 3 s, turn ends INTERRUPTED; default stays DENY |
| G4 | ToolApproval ASK not relayed (acts as DENY) | claude headless + interactive | `CS:29-30` | FIXED c2f05317: headless ASK launches with `--permission-prompt-tool stdio`; each `can_use_tool` control request goes to the owner via `SessionOutput.requestApproval` and is answered with a `control_response` (allow = original input, deny/timeout/error = deny with a message); other control requests get an error reply. Interactive ASK is the G3 hook relay. Live check on claude 2.1.318: allow ran the Write, deny refused it |
| G5 | Every approval mode is ignored (DENY runs every tool) | pi | `Pi/PiCommand.kt:24-25` | yes: gate pi through its extension API, or refuse non-ALLOW_ALL |
| G6 | No infantry MCP; tools only via CLI | pi | live; `Pi/PiSession.kt:66` | yes: MCP bridge extension vs CLI-only |
| G7 | `chat_note wake=true` to a busy session is dropped: merge NOTE-WAKE-PTY-1 (`96975c45`, ticket 54f8bab9) | all | live; `Note:79-83` | |
| G8 | `infantry_prompt` to a busy session refused, not queued | all | live; `DM:2856-2859` | |
| G9 | A FAILED turn with no exit code: no finish/idle notice, queue never drains, worker-watch never kicks | all (opencode "no reply" most) | `SM:1679,1869-1880`; `OC:325-328` | |
| G10 | No mid-turn input ("Send now") | pi, opencode | no `injectInput` override; `SM:1787` | |
| G11 | MID_TURN_INPUT declared on the one-shot path, which cannot inject | claude (keep-alive 0) | `HarnessSessionAdapter.kt:128` | |
| G12 | MCP state not reported; no in-place `mcp_reconnect` | claude headless, opencode (reconnect: all) | `CS:44-56`; `SM:1374-1396` | |
| G13 | No reported cost and no compaction events | claude interactive | `Claude/ClaudeTranscript.kt:55-58,107-116` | |
| G14 | `stats()` likely undercounts output tokens; ignores `CLAUDE_CONFIG_DIR` | claude (both) | `Claude/ClaudeStatsUsage.kt:68-75`; `Claude/ClaudeHarnessPlugin.kt:126` | |
| G15 | No plan-quota / pace source | pi non-anthropic, opencode non-Go (free) | `Limits.kt:184,232` | yes: should free/pay-per-token models count toward pace at all |
| G16 | `bg stop` kills only the descendants tree, not the session Job Object; no start-time check | all | `BG:1006-1013` | |
| G17 | `infantry_run_stop` is not project-scoped (every other run tool is) | all | `Infantry_Run/.../RunCommandTools.kt:99-104` | |
| G18 | Secrets are masked in run output only, not in session output / the PTY screen | all | `RunManager.kt:317`; `Tty:187-195` | yes: mask harness output too, or document that agents can see secrets they read |
| G19 | The CLI's own background tool can wake the session twice (its task-notification turn + the daemon's tree-job mail) | claude (both) | `Tty:355`, `CS:95-100`, `BJN:265-267` | |
| G20 | No graceful close (always a tree kill) | opencode | `OC:206` | |
| G21 | `chat_note` refuses an 8-char session prefix that `infantry_prompt` accepts | all | live | |

By design, not tickets: the watch hooks run only under ALLOW_ALL on claude and opencode (`WatchHooks.kt:34-37`). The
LIVE_SESSION capability is declared but no daemon code reads it. The opencode free-tier quota error is handled
(`OC:332-342` gives up after `maxRetries`), but it counts attempts, not time, so the session sat BUSY for 5+ minutes
on one "Free usage exceeded, retry 1" line *(live)*.

Not tested live: everything on claude interactive after the trust question (blocked by G1; pre-trusting the folder
would have meant editing the owner's real `~/.claude.json`), and everything on opencode (the free tier answered
"Free usage exceeded").

## Probes

`scripts/harness-audit/`:

- `test-daemon.sh <dist build dir> <state dir>`: a daemon on port 8812 with every `INFANTRY_*` / `AGENTHQ_*` /
  `CLAUDE*` variable removed, the dist's bundled modules, and `INFANTRY_BIN` set to the dist launcher.
- `cli.sh <dist> <args...>`: the CLI pinned to that daemon (`INFANTRY_URL`); needs `STATE` set.
- `mcp.sh <dist> <tool> '<json>'`: one MCP tool call over `infantry mcp serve` (set `MCP_PROJECT` for project
  tools such as `infantry_chat_note`).
- `ws-frame.ps1`: sends one owner frame (used for `project_save`, since the CLI cannot create a project).
- `probe-brief.md`: the ticket brief every probe worker ran (bg run, wake, inbox, env echo, move done).

Run: create a git repo, `project_save` it, set `project base <id> main`, create one ticket per harness with
`probe-brief.md` and a cheap model, `ticket start`, then read `<state>/logs/infantry.log` and
`<state>/usage-meter.db`.
