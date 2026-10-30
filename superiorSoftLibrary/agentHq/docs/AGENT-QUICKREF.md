# Infantry AI Quick Reference

## 0. Start here (agents: read this first)

Every command below is copy-pasteable. Replace the `<...>` parts. Run them in your shell exactly as written.
`AgentQuickRefCommandsTest` runs every line of the blocks in this section through the CLI parser, so a stale command
fails the build.

**See the board**

```
agenthq tickets
agenthq board     # one call: tickets by column + worker, live state, branch@commit (MCP agenthq_board; agenthq_get_session brief:true skips the transcript)
agenthq tickets --status todo
agenthq ticket show <ticket-id>
```

**Find the code before you grep** (TICKET-FILES; MCP `agenthq_ticket_related_files` / `agenthq_ticket_files`)

```
agenthq ticket files <ticket-id> --related
agenthq ticket files <ticket-id>
agenthq ticket files --path TicketBoard.kt
```

`--related` lists the files changed by the tickets that touched the same files, ranked. Without it, `ticket files` lists the files that ticket changed (status, +/-). `--path` (glob ok) lists the tickets that changed matching paths.

**Related tickets travel with the ticket** (KNOWLEDGE-PTR-1)

```
agenthq ticket related <ticket-id>
agenthq ticket related <ticket-id> --suggest
agenthq ticket create --title "USAGE-3 the next step" --desc "One line brief." --related-to <ticket-id>
agenthq ticket update <ticket-id> --related-to <ticket-id>
```

- A ticket save writes a `RELATED (auto, N tickets, M files)` block atop the description: its series first (same title label, `USAGE-1`, `USAGE-2`; `USAGE-UI` is another series), then the tickets linked to it (`relatedTo` / `--related-to`, stored as `relates_to` links), then the tickets that changed the same files, each with its files and `git show <sha>`. The text below the block is never changed. `--suggest` (MCP: `agenthq_ticket_update` with `relatedTo: []`) rebuilds it.
- New ticket: use that block, add `relatedTo` with the ids you already know, and reuse the series label. A title with a known label and no number (`USAGE: x`) gets the next number suggested in the answer; the title is never rewritten.
- A worker's start message carries the live `RELATED WORK` block and one line telling it to read those files first.
- Off switches: `related.brief` (start block), `related.descriptionBlock` (stored block), `related.suggestNumber`; `related.familyPattern` ("" = no series), `related.briefInstruction`; caps `limits.ticketFiles.brief*` / `limits.ticketFiles.block*`. One ticket opts out with a line `related: off` in its description.

**Check related tickets before you start** (APP-KNOWLEDGE)

Before you start, check the linkedTickets section of your brief, then the board (`agenthq board`) for tickets that touch the same work, then their changed files (`agenthq ticket files <ticket-id> --related`). Reuse their work and cite their ids in your report. If none are related, say so in one line and carry on.

**See who is available**

```
agenthq agents              # live agents (BUSY/IDLE/OFFLINE) + profiles with no session yet, KIND column tells which
agenthq agents --live       # live agents only
agenthq agents --profiles   # saved profiles only
```

A *profile* is a saved template you can create a session from; an *agent* is a running session in the project.

**Mail** (`agenthq mail` prints these again)

```
agenthq inbox
agenthq inbox --unread
agenthq read <8-char-id>
agenthq send pm "Your message, on one line."
agenthq send pm "Your answer, on one line." --reply-to <8-char-id>
agenthq threads
agenthq thread "<thread-id>"
agenthq mail
```

- `<8-char-id>` is the first column of `agenthq inbox`. `--reply-to` also takes the full id printed in the message.
- A reply goes back to the sender in the same thread. Check with `agenthq threads`: the message count goes up.
- `send` takes a role (`pm`, `qa`, `backend`...) or an agent id.
- `read` says `has not reached a console yet (state QUEUED)` when the mail waits for your turn to end. Read it now
  with `agenthq thread "<thread-id>"` (the thread id is in `agenthq threads`).
- MCP tools do the same: `agenthq_inbox`, `agenthq_read` (`messageId`), `agenthq_message` (`to`, `body`, `replyTo`).
- Never use `curl` against `/mail/...`: the CLI is the supported path.

**Tickets**

```
agenthq ticket create --title "Short title" --desc-file brief.md --role backend
agenthq ticket update <ticket-id> --desc-file brief.md
agenthq ticket start <ticket-id>
agenthq ticket move <ticket-id> done
agenthq harnesses
```

- Delete a ticket with the MCP tool `agenthq_ticket_delete` (`ticketId`). There is no CLI delete.
- Start ticket work with `ticket start`, never with a spawn.
- A start cuts the ticket's branch from its epic's base, else its project's (`agenthq project base <projectId> <branch>`),
  else the daemon's `AGENTHQ_BASE_BRANCH`. With none set the start is refused with that reason; its note names the
  base: `cut from <branch> <sha8> (project base)`.

**Attach an image**

```
agenthq ticket create --title "Short title" --image shot.png
agenthq ticket update <ticket-id> --image shot.png
```

**Memory and decisions**

```
agenthq knowledge
agenthq memory search <word>
agenthq memory show <note-name>
agenthq memory save <note-name> --description "One line summary" --file note.md
agenthq memory save <note-name> --append --file note.md
agenthq decision add "One line summary" --file decision.md
agenthq issue precheck <path>
```

**Notes have three scopes (NOTES-1, contract 1.98.0)** — add `--scope project|role|session` to `memory index|show|search|save|delete`:
- `project` (default, nothing existing changes): shared by every role and session of the project.
- `role`: notes for ONE role in this project. They persist with the role (across sessions and daemon restarts) and
  are listed in the start context of every session of that role only. You write your own role's notes; only the `pm`
  and the owner may name another role with `--role <id>`.
- `session`: yours alone and TEMPORARY. They are deleted when the session is killed or removed (`session rm`, the
  sidebar trash). Never put anything there that must outlive the session.
- `agenthq memory delete <name> --scope role` removes one note. `agenthq memory purge-role <role> <role>` deletes ALL
  of a role's notes in every project (the role id is typed twice, to confirm); a role with no sessions keeps its notes.
- The owner sees and edits all three scopes in the **Notes** tab (Project | Role | Session).

**Long-running commands (QA gates, Gradle, test suites): `agenthq bg run`**

```
agenthq bg run --name "gate" -- gradlew.bat agentHq:test     # Windows: gradlew.bat (./gradlew fails here); runs the command, tees output to a log, flags the session Running
agenthq bg list
agenthq bg status <id>
agenthq bg logs <id>
agenthq bg stop <id>
agenthq bg ack <id>      # STALL-6: "I have seen this job's result": the daemon drops its queued and future notices
```

**Never sleep or poll to wait for it: `--notify` (BG-NOTIFY).**

```
agenthq bg run --notify --name gate -- gradlew agentHq:test   # returns at once; the job runs detached
# ... END YOUR TURN. The daemon mails you "[AUTOMATIC] bg job <id> finished, exit N" with the last lines.
agenthq bg run --notify --notify-on "Tests? completed" --notify-timeout 20 -- gradlew test   # extra pattern, own timeout
```

The command's program is resolved before it starts: a bare name is looked up in the current directory (Windows) and on
PATH with PATHEXT, so `gradlew.bat`, `gradlew` and `agenthq` (a `.cmd` shim) all work from a worktree root. A command
that cannot be found or started is NOT a job: `bg run` prints `error: ... job NOT started` and exits 127, with no job
id to wait on. If a detached wrapper still fails to start it, the daemon mails you `exited 127` at once.

**Handled jobs (STALL-6).** A notice can reach you late (it is held until your turn ends). Reading a finished job's log or
status (`agenthq bg logs|status <id>`) or `agenthq bg ack <id>` marks it handled: its queued and later notices are dropped.
A newer job with the same name/command from your session also supersedes the old job's notices; a job sends at most one
"closed its output, still running" and one "finished" notice, and the first is dropped once the second is due. Off switch:
setting `limits.watch.noticeDedupe` = 0. **Exit status:** run the command itself (`bg run --notify -- gradlew test`), never
`gradlew test > out.txt; echo EXIT=$?`: the watcher reports the exit of the wrapper shell (the echo's 0), and the notice
says so when the output ends in an `EXIT=n` line that differs.

`agenthq bg wait <id>` is a fallback, ONLY for when your harness is not woken by mail: it blocks and returns the exit code. Add `--timeout <MIN>` to bound the wait.

Triggers (one mail each, to your session, from `system`, thread `bg:<id>`): exit (code + last `limits.bg.notifyTailLines`
lines, or `--notify-tail N`); the job vanishing without an exit code (stopped or died); a NEW output line matching a
pattern (defaults `BUILD SUCCESSFUL`, `BUILD FAILED`, `^e: `; replace the defaults with newline-separated regexes in
`AGENTHQ_BG_NOTIFY_PATTERNS`, drop them with `--no-default-patterns`) while the job still runs past
`limits.bg.notifyPatternGraceSeconds` (an exit inside that grace sends only the exit mail); still running after
`limits.bg.notifyTimeoutMinutes` (or `--notify-timeout MIN`, 0 = never), repeated every such period with the age of the
last output. Mail to a session at rest resumes it (claude and pi alike), so this costs no tokens while waiting.

The daemon cannot see a job that detached from your shell, so a plain background run leaves the session reading idle.
The wrapper registers the job under `AGENTHQ_SESSION_ID`, heartbeats, and deregisters on exit (a killed wrapper expires).
QA: run every gate this way.

**Ask the owner a question (non-blocking, ASK-1: use this one)**

The owner WANTS you to use this for real decisions: 2-4 real options where their preference matters (design, priority, destructive or irreversible choices), always with a recommended option. Not for trivia or anything already decided; never wait on it. `agenthq ask --test` raises a canned sample question to your own session (ASK-2) to see the popup.

```
agenthq ask --question "Ship it now?" --option "yes|ship today, QA after" --option "no|wait for the QA gate" --recommend no --reason "the gate runs tonight"
```

It prints `{"questionId":"...","note":"..."}` at once and does NOT wait. Carry on with other work. The owner sees the
question in a prompt window with your recommended option marked; their pick arrives as mail from `owner` in thread
`question:<id>` (subject `Answer to question <id>: <label>`). Rules:

- 2 to 8 options, `label|consequence` (one line: what happens if the owner picks it). Exactly one `--recommend <label>`
  with a `--reason` (at most 200 characters). `--other` lets the owner type an answer of their own.
- `--default-after <min>` (1..1440, OFF unless you set it): if the owner has not answered by then, the daemon picks your
  recommendation and mails it marked "auto-selected after N min, owner did not answer". The owner can still override
  it; an override arrives as a second mail in the same thread, marked `OVERRIDE`. Follow the newest one.
- Rate limit per agent: at most 3 open questions and 6 asks per rolling hour (settings `questions.maxOpenPerAgent`,
  `questions.maxPerHour`). A refusal exits 2: decide yourself or batch choices into one question; never stall.
- If the owner cancels, you get a "cancelled" mail: decide with your own judgment.
- The MCP form is `agenthq_ask` (same rules, see docs/MCP.md).

The older blocking form (no `--recommend`) still exists for harness use; it waits up to `--timeout` seconds for the
answer and prints it:

```
agenthq ask --question "Ship it now?" --option "yes|ship it" --option "no|wait"
```

**cmd.exe or bash: the pitfalls**

Your shell may be cmd.exe (Windows, also some agent "bash" tools) or bash. These rules work in both:

1. Put text with spaces in `"double quotes"`. Never `'single quotes'`: cmd.exe keeps them as part of the text,
   which is what broke the old curl recipes (`malformed mail_read payload`).
2. A thread id contains `|` (`pair:<a>|<b>`). Unquoted, the shell treats `|` as a pipe and cuts the id. Always
   write `agenthq thread "<thread-id>"` with the quotes.
3. Never put a line break inside an argument. cmd.exe stops at the first newline and the rest is lost without
   an error. For long text write a file first, then pass the file: `--desc-file brief.md` for tickets,
   `--file note.md` for memory and decisions. Keep a mail message on one line, or use the `agenthq_message`
   MCP tool for a long one.
4. Environment variables: bash `$AGENTHQ_SESSION_ID`, cmd.exe `%AGENTHQ_SESSION_ID%`. You rarely need them: the
   CLI reads them for you.
5. `agenthq --help` lists every command, grouped (Tickets, Agents and sessions, Mail, Memory and decisions,
   Daemon and pods).

## 1. Where state lives

- Default state directory: **`$AGENTHQ_STATE_DIR`**, or **`~/.agenthq`** if unset (see `SessionStore.kt:38-47`).
- The `stateDir()` function reads `AGENTHQ_STATE_DIR` env var; falls back to `user.home/.agenthq`.
- Creates the directory if it does not exist (`mkdirs()`).
- **Files inside the state dir:**
  - `sessions.json` — persisted session records (default store: `JsonSessionStore`).
  - `mailbox.json` — agent-to-agent messages (default store: `JsonMailStore`).
  - **`agenthq.db`** — SQLite database holding `tickets`, `agents`, `epics`, `projects`, and `ticket_links` tables.

> **Warning:** Reading `agenthq.db` directly is a debugging backdoor. It can be stale or locked.
> Never write to it or to `mailbox.json` by hand.

## 2. Protocol frames (WebSocket)

Frames are JSON; defined in `Protocol.kt`. Frame reference:

| Frame | Constant | Description |
|---|---|---|
| `mail_send` | `MAIL_SEND` | Client → daemon: carries `MailSendRequest`. Daemon answers with `[MAIL]` or `[MAIL_REFUSED]`. |
| `mail_read` | `MAIL_READ` | Acknowledge one message (`"<messageId>"` in the payload). Answer: `[MAIL]` (the updated row), or `[ERROR]` when the message has not been delivered. |
| `mail_inbox` | `MAIL_INBOX` | Request messages addressed to a session (`"<sessionId>"` in the payload; blank = this connection's active session). Answer: `[MAILS]`. |
| `mail_outbox` | `MAIL_OUTBOX` | Request messages a session sent (same payload as `mail_inbox`). Answer: `[MAILS]`. |
| `mail_thread` | `MAIL_THREAD` | Request one thread (`"<threadId>"` in the payload). Answer: `[MAILS]` — the thread's messages, in thread order. |
| `mail_threads` | `MAIL_THREADS` | Request every thread as a summary. Answer: `[MAIL_THREADS]` (a list of `ThreadSummary`). |
| `mail_waiting` | `MAIL_WAITING` | Open needs-input signals, filtered to senders at rest. Answer: `[MAIL_WAITING]`. |
| `ticket_list` | `TICKET_LIST` | Request the full ticket board. Answer: `[TICKETS]` (every ticket). |
| `agent_list` | `AGENT_LIST` | Request the list of agent profiles. Answer: `[AGENTS]` (every profile). |
| `question_ask` | `QUESTION_ASK` | ASK-1 (agent, envelope sessionId = asker): `{question, options:[{label,description}], recommended, reason, allowOther?, defaultAfterMin?}`. Answer at once: `[QUESTION_ASKED]` `{questionId, note}`; the pick arrives later as mail in thread `question:<id>`. |
| `question_list` | `QUESTION_LIST` | Every owner question, oldest first. Answer: `[QUESTIONS]` (also sent on connect). Changes are pushed as `[QUESTION]`. |
| `question_answer` / `question_cancel` | `QUESTION_ANSWER` / `QUESTION_CANCEL` | The owner's pick `{questionId, answer?, otherText?}` (no envelope sessionId: agents cannot answer) / withdraw `{questionId}` (owner, or the asking session). |

## 3. HTTP mail API (daemon HTTP routes)

These routes are for programs, not agents. A delivered message's footer prints `agenthq` CLI commands (MAIL-FOOTER-1),
not curl: curl's JSON quoting breaks in cmd.exe. Agents use section 0.

All routes under the daemon's HTTP server (see `MailHttp.kt:37-98`):

| Method | Route | Description |
|---|---|---|
| `POST` | `/mail/send` | Send (or reply to) a message. **200** + stored `Message`, or **400** + `MailRefused`. |
| `POST` | `/mail/read` | Acknowledge one message delivered → read. **200** + updated `Message`, or **404**. |
| `GET` | `/mail/inbox` | Messages addressed to a session (`?session=<id>`). Oldest first. |
| `GET` | `/mail/outbox` | Messages a session sent (`?session=<id>`). |
| `GET` | `/mail/thread/{threadId}` | One thread in order (`/<threadId>`, URL-encoded). |
| `GET` | `/mail/waiting` | Open needs-input signals, filtered to senders at rest. |

> **Security:** These routes sit behind `installAccessPolicy`. Without a bearer token, requests are refused **401** (loopback-only when no token).

## 4. Agent vs session mail delivery

- An `agents` row is a **definition**, not a running process.
- **Mail `to` is a SESSION id or an AGENT PROFILE id** (`MailSendRequest` in `mailbox/Message.kt`). An agent id resolves to that agent's session — the live one if its run is in flight, else the most recent resumable one.
- A message to a **busy** agent **QUEUES** and is delivered when that run ends (never a permanent failure). A message to an agent with **no session at all** is refused: `agent <id> has no running session — start it first`.
- Mail is delivered via the WebSocket envelope (`[MAIL_SEND]`) **or** via the HTTP API (`POST /mail/send`).
- A spawned agent gets `AGENTHQ_SESSION_ID` (its own `from`) and `AGENTHQ_MAIL_BASE` in its environment, so it can **initiate** a handoff to a colleague, not only answer a delivered one.
- It also gets **who it runs as**: `AGENTHQ_ROLE` when the session has a role (a profile's, or a role-only spawn from the New agent modal) and `AGENTHQ_AGENT_ID` when an agent profile is bound (`AGENTHQ_PROJECT_ID` when it has a project). Its first turn repeats the same facts in one line after the persona: `You are running inside Infantry AI as role '<role>', agent id '<id>'. ...` (`firstTurn` in `agents/AgentProfiles.kt`).
- The delivered prompt carries the exact commands so an unattended handoff can be **delivered AND answered** with no human relaying anything.

## 5. Daemon connection

- WebSocket URL: `ws://127.0.0.1:8787/ws` (default port 8787, `DEFAULT_PORT` in `CliMain.kt:34` / `DaemonMain.kt:84`).
- Port overridable via `AGENTHQ_PORT` env var (`DaemonMain.kt:231`).
- Host overridable via `AGENTHQ_HOST` env var (default: `127.0.0.1`).
- With a bearer token: accessible from any machine.
- Without a token: **loopback-only** (binds to `127.0.0.1`); other machines cannot reach it.

## 6. Team block in the ticket preamble (MAIL-5)

Every ticket-start preamble (`TicketContextComposer`, section `team`) carries this block, so an agent knows
its colleagues and how to read its mail without being told. Mail is read with the MCP tools `agenthq_inbox` /
`agenthq_read` (MAIL-MCP-1); the CLI `agenthq inbox` / `agenthq read` is only the fallback (BUG-MAIL-MCP-2).
`TicketContextComposerTest` pins the block below to `TEAM_BLOCK` verbatim:

```
Team (scoped to your project):
- agenthq agents --live: who is live in this project
- agenthq send <role|agentId> "msg": message a colleague (queued if they are busy)
- agenthq_inbox / agenthq_read <id> (your Infantry AI MCP tools): your mail; fallback when they are not available: agenthq inbox / agenthq read <id>
- agenthq mail: every mail command (answer one with --reply-to <id>); agenthq --help: every command, grouped. Start here: superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md, section 0
- The owner WANTS you to ask for real decisions: when a choice has 2-4 real options and their preference matters (design, priority, destructive/irreversible), run `agenthq ask --question "..." --option "label|consequence" --recommend <label> --reason "..."` (MCP agenthq_ask; --other, --default-after <min>). Always recommend one. Don't ask trivia or what is already decided. It returns an id at once and does not wait: keep working on what doesn't depend on it; the pick arrives as mail (thread question:<id>).
```

- Right after this block (and the `Report to:` line) every ticket brief carries `FINISH_LINE`: when finished, run
  `agenthq send starter "<message>"` with the commit sha, what changed, what was and was not tested, and any problem,
  then stop. Same wording for claude, opencode and pi workers; pinned by `TicketContextComposerTest`.
- AUTOMATIC FINISH NOTICE: whatever the worker does, the daemon (`FinishNoticeMonitor`) mails the ticket's starter
  when the worker's run ends: subject `[AUTOMATIC] ...` with ticket id and title, status, worker session, branch, last
  commit, exit code and the last lines of output. A crash, a non-zero exit or a kill is `[AUTOMATIC FAILURE]` (one per
  ticket per 15 min). A clean finish (ticket `done`) is suppressed when the worker already mailed the starter itself
  (its own report is richer). A clean end of turn with the ticket still open is NOT a failure: the worker is waiting
  and nothing is sent; only after `limits.watchdog.idleNoticeMinutes` (default 60, 0 = never) at rest with no mail to
  or from it does the starter get ONE calm `[AUTOMATIC] ticket ... is waiting for instructions` note per idle period.
  Such a turn is not checked for REPORT BACK either (the report is due when the ticket is done).
- The commands are scoped to the agent's own project: the CLI reads the project from its environment.
- The block is composed once per ticket start and appears exactly once in the prompt.
- ASK-1: `agenthq ask --recommend` is the non-blocking owner question (section 0, "Ask the owner a question"). Exit 0
  asked (prints `{"questionId":..,"note":..}`), 2 usage error, refusal or rate limit, 1 the daemon failed.
- PROMPT-5: `agenthq ask` WITHOUT `--recommend` asks for the agent's own session (`AGENTHQ_SESSION_ID`), blocks, and
  prints `{"answers":[...],"otherText":...}`. Exit 0 answered, 3 no answer / timeout, 2 usage error or no session. It
  works in every Tool approval mode (the `agenthq` CLI is always allowed, BUG-PERM-5).

## 7. Multi-line ticket descriptions (BUG-CLI-1)

On Windows every `agenthq` call goes through `agenthq.cmd`, and cmd.exe ends the arguments at the first
newline: a multi-line `--desc "..."` keeps only line 1. Pass long text through a file or stdin instead:

```
agenthq ticket create --title "..." --desc-file brief.md
agenthq ticket update <id> --desc-file brief.md
Get-Content brief.md -Raw | agenthq ticket create --title "..." --desc -
```

The text is kept verbatim (markdown lists starting with `-` included); the board trims only the leading and
trailing whitespace.

## 8. Which build am I running? (BUG-CLI-4)

```
agenthq --version        # or: agenthq version
agenthq 1.80.0 (daemon: 1.80.0)
```

One line, exit 0: the CLI's contract (`EventVocabulary.VERSION`), then the contract the daemon announces in its
`ready` frame. With no daemon reachable within ~2 s it prints `(daemon: not running)` and still exits 0. Two
different numbers mean an old install on one side (e.g. a daemon at `1.76.0` under a `1.80.0` CLI). It honours the
usual connection flags (`--port`, `--url`, `--endpoint`, `AGENTHQ_URL`).

**Which daemon a command talks to (CLI-ENV-1).** Precedence: `--url` / `--endpoint` / `--host` / `--port`, then
`AGENTHQ_URL`, then `AGENTHQ_PORT`, then `AGENTHQ_STATE_DIR` (the port in its `daemon.port`), then the installed
app's (`AGENTHQ_INSTALL_*`), then the default `127.0.0.1:8787`. Token: `--token-file`, then `AGENTHQ_TOKEN_FILE` /
`AGENTHQ_TOKEN`, then the stored remote-access token. If `AGENTHQ_PORT` or `AGENTHQ_STATE_DIR` is set but unusable
(not a port, no `daemon.port` in the dir, nothing listening), the CLI fails with a message naming it and NEVER falls
back to another daemon. The `mcp serve` bridge is the same CLI, so the same rule applies.

## 9. Start a ticket with its role, provider and model (BUG-ASSIGN-1)

```
agenthq harnesses                                  # harness ids (a ticket's --provider) and each one's models
agenthq ticket create --title "..." --role backend --provider claude --model haiku
agenthq ticket update <id> --model sonnet          # prints: updated <id> (saved: model sonnet)
agenthq ticket show <id>                           # AGENT:, PROVIDER: and MODEL: lines show what is pinned
agenthq ticket start <id>                          # the board's own start
```

`ticket start` is what a card moved to In Progress does: the worker runs the ticket's role, provider and model in its
own worktree on `ticket/<short-id>-<slug>`, and the ticket goes In Progress with your session as its starter. It prints
`started <id> -> in_progress`, then the worker's session, role, harness, model, branch and worktree. Already in progress
or queued: exit 0, nothing new runs; held or refused: the reason, exit 1. Over MCP: `agenthq_list_harnesses`,
`agenthq_ticket_create` / `agenthq_ticket_update`, then `agenthq_ticket_start`. Never `agenthq_spawn` for ticket work: it
runs outside the board, in the project folder, and leaves the ticket in To Do.

Delete ONE ticket over MCP with `agenthq_ticket_delete` (`ticketId`: full id or the 8-char suffix, as for
`agenthq_ticket_update`). An unknown or ambiguous id is refused and nothing is deleted. A ticket in progress is refused:
stop its worker (`agenthq_kill`) first. One ticket per call; there is no bulk delete.

`ticket move <id> in_progress` is the same start and answers the same way: a refused or held start prints the reason
and exits 1 (never `moved <id> -> todo`). A `--role` / `role` that is not a role id (a display name such as
`Backend Developer`) is refused, never guessed; the refusal lists the valid role ids.

## 10. Attach images to a ticket (IMG-AGENT-1)

```
agenthq ticket create --title "..." --image shot.png --image other.jpg   # each file uploaded, then attached
agenthq ticket update <id> --image more.png                              # ADDS to the ticket's images
agenthq ticket update <id> --remove-image <path|file name|original file> # drops one
agenthq ticket show <id>                                                 # IMAGES: each image's absolute path
```

png, jpeg, gif or webp, at most 10 MB each. A missing, oversized or non-image file is refused and nothing is saved.
Over MCP: `images` (local file paths) on `agenthq_ticket_create`; on `agenthq_ticket_update`, `addImages` /
`removeImages`, or `images` to replace the whole list. A started worker's brief lists them under "Attached images".
Details: `docs/MCP.md`, "Ticket images".

---
*Generated from code greps: SessionStore.kt, Protocol.kt, MailHttp.kt, DaemonMain.kt, CliMain.kt.*