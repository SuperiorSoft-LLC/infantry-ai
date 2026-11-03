# AGENT-TASKS: unassign, assign, switch and queue tickets for an existing agent

Ticket AGENT-TASKS-1 (4b9efb9a), design only, 2026-11-03. The owner asked: "we also need a means to remove a task, or
add or switch a task, for the same agents. I am not sure if this is possible."

Short answer: yes. Most of what it needs is already in the board: a re-start reuses the ticket's branch, an agent
profile is already a lane with a capacity queue, and a session exit already drains that lane. What is missing is four
board operations, one persistent queue field, a derived "has work" view, and small changes to three project hooks.

## 0. What exists today (read from the code, 2026-11-03)

| Fact | Where |
|---|---|
| An "agent" is an **agent profile** (id, name, role, project, pins). A **session** is one run of a profile. A ticket names its agent in `StoredTicket.agentId` (a profile id or `role:<role>`). | `infantryDaemonApi/.../board/BoardHost.kt` `StoredTicket`; `TicketBoard.resolveAssignment` |
| Every start goes through `TicketBoard.handOut`: assignment, executor, launch gate, pacing, then the ticket's own branch and worktree, then `host.sessions.start(SessionStart(...))` with `ticketId` set. | `infantryBoard/.../backlog/TicketBoard.kt` `handOut` |
| A **re-start reuses the ticket's branch**: `BranchRequest(recordedBranch = row.branch, recordedBase = row.base)`. A ticket that already has a branch needs no base. | `TicketBoard.handOut` (BRANCH-PER-TASK 1/3) |
| A session's `ticketId` and working directory are fixed when it is created (`SessionStart.ticketId`, `SessionStart.worktree`). | `BoardHost.kt` `SessionStart`; `infantryCore/.../session/Session.kt` |
| A busy profile **queues** the ticket: `queuedFor = <lane>`, `queuedAt`. The queue is volatile: `clearQueueMarks()` wipes it at boot ("a restart never launches work automatically"). A session exit runs `drainProfileLane`, which starts the first queued todo ticket of that lane (`HandOutOrigin.QUEUE_DRAIN`, an unattended launch). | `TicketBoard.clearQueueMarks`, `drainProfileLane`, `applySessionEvent` |
| Removing a session does not remove its worktree. `branch-cleanup.lua` removes only branches merged into dev whose ticket is done. | `WorktreeManager.remove` (only called by cleanup); `.agenthq/scripts/branch-cleanup.lua` |
| DEADLOCK-1 (f3389c81): a turn end held the SessionManager lock and read the board while a start held the board lock and called `startNew`. The fix delivers session events after the manager lock is released and the board defers events that arrive under it to its single `sessionEvents` thread. Rule kept here: **no board operation calls into sessions while a session listener holds the manager lock; follow-up starts run on the board's `sessionEvents` thread, never inline in a listener.** | `SessionBoardDeadlockTest`, `SwapFeed.kt`, `TicketBoard.onSessionEvent` |
| Project hooks: `on-ticket-done` (lane enter done: VC merge mail, start the next todo ticket, close the worker), `worker-watch` (every 180 s: sweep idle/ended sessions not on an in-progress ticket, start a ticket if the board stalled, kick idle workers), `lane-one-worker` (lane enter in_progress: bounce a start over `maxInProgress`, kick a fresh worker). | `.agenthq/scripts/*.lua`, samples in `plugins/official/Infantry_Scripting/project-scripts/` |

So "remove a task from an agent" today = move to todo + remove the session, by hand or by script; nothing records
that work exists. "Give an agent another task" today = a new ticket start with `agentId` = that profile, which already
spawns a new session of the **same agent profile**. The missing parts are naming those operations, making them safe
(ordering, locks, hooks) and adding a queue that survives a restart.

## 1. The key decision: what moves when an agent changes ticket

The ticket asks: switch the agent's working directory to the ticket's worktree, or keep one worktree per ticket and
move the session. Three real options:

| Option | How | For | Against |
|---|---|---|---|
| **A. One worktree per ticket; same agent profile, NEW session per ticket** (chosen) | Assign = end the agent's idle session (if any) and `handOut` the ticket with `agentId` = that profile. The new session runs in the ticket's worktree, on its branch. | No new core mechanism. Branch-per-task, VC merge, `branch-cleanup`, `useWorktree` and the worktree guard keep working unchanged. Clean context = cheapest per turn (section 7). Uncommitted work of A stays in A's worktree. | The conversation of the previous ticket is not carried (only a handoff note is). |
| B. One worktree per agent; `git switch` the ticket branch inside it | The agent's session keeps its cwd and provider session; the board commits/stashes and switches branch. | Full context reuse. | Breaks branch-per-task invariants: a branch can be checked out in one worktree only, so the ticket's recorded worktree must be torn down; a dirty tree leaks A's edits into B or needs an auto-WIP commit on someone's branch; the VC merge and cleanup scripts assume a ticket worktree. Costlier per turn (section 7). Hard to reverse once tickets stop owning worktrees. |
| C. One worktree per ticket; MOVE the provider session (resume it in the new worktree) | Start a new process in B's worktree that resumes A's provider conversation. | Context reuse without a shared tree. | Harness-dependent: Claude Code keys stored conversations by project directory, so `--resume` from another cwd does not find them; other harnesses differ. Needs a harness capability flag and per-harness work. Same per-turn cost penalty as B. |

**Chosen: A.** It is the simplest structure, it reuses `handOut` as is, and per the owner's efficiency rule it is the
cheaper default. C is kept as an **opt-in setting for later** (`tickets.agentTasks.contextMode = continue`), only on a
harness that declares it can resume in another directory; it is a backlog ticket, not part of this build. B is
rejected: expensive to reverse (it changes what owns a worktree, which every merge and cleanup flow relies on).

What "the same agent" means after this: the same **profile** (name, role, pins, role notes, mailbox address, card on
the Agents tab, run history). Mail addressed to the profile id reaches whichever session it runs now.

## 2. Contracts first

All four operations are **board operations** (the board plugin owns tickets, lanes and queues). Each is one method on
`TicketBoard`, one MCP tool in the board's daemon half (`plugins/official/Infantry_Board/daemon/`, next to
`TicketStartTool.kt` / `TicketMoveTools.kt`), one CLI verb, and therefore one Lua call (`ih.<tool>`: D21, scripts reach
every agent tool). The UI calls the same tools over the wire.

```
unassign(ticketId, now = false, note = null)            -> TicketAnswer   // ticket -> todo, work kept
assign(ticketId, agentId, mode = start|queue, at = null) -> TicketAnswer   // give it to an existing agent profile
switch(agentId, toTicketId, now = false)                -> SwitchAnswer   // unassign current + assign target
queue(agentId, action = list|add|remove|move|clear, ticketId?, at?) -> AgentQueue
```

| Op | MCP tool | CLI | Lua |
|---|---|---|---|
| Unassign | `infantry_ticket_unassign {ticketId, now?, note?}` | `infantry ticket unassign <id> [--now] [--note <text>]` | `ih.infantry_ticket_unassign{...}` |
| Assign | `infantry_ticket_assign {ticketId, agentId, queue?, at?}` | `infantry ticket assign <id> <agent> [--queue] [--at <n>]` | `ih.infantry_ticket_assign{...}` |
| Switch | `infantry_agent_switch {agentId, ticketId, now?}` | `infantry agent switch <agent> <ticketId> [--now]` | `ih.infantry_agent_switch{...}` |
| Queue | `infantry_agent_queue {agentId, action, ticketId?, at?}` | `infantry agent queue <agent> [add <id> [--at n] \| rm <id> \| mv <id> <n> \| clear]` | `ih.infantry_agent_queue{...}` |

`<agent>` accepts a profile id or a profile name (unique in the project), like `ticket start` does today.

### 2.1 Unassign

Stop work on a ticket without losing it.

1. Refused when the ticket is not `in_progress` (nothing to unassign) or is `done`.
2. **Board commit first** (board lock, one write transaction): status `todo`, placed at the top of todo,
   `sessionId = null`; `branch`, `base`, `agentId` and the worktree record are kept. A `handoff` ticket comment is
   written (2.5). The commit happens before any session call, so a refused or slow kill never leaves the ticket
   half-moved.
3. **Then the worker ends**, outside the board lock, on the board's `sessionEvents` thread:
   - IDLE worker: `sessions.kill(force = true, by = "board", reason = "unassigned")`, then `remove`.
   - BUSY worker: by default the board waits for the turn to end (the session's next IDLE status event, which already
     arrives on `sessionEvents`) and ends it then, so a commit in flight lands. A wait longer than
     `limits.agentTasks.unassignWaitSeconds` (default 120) kills it. `now = true` kills at once.
   - The session's exit is a normal Exit event: `finishTicketRun` records the run as `KILLED` with detail
     `unassigned`; the exit drains the profile's lane (2.4) as any exit does.
4. Next start of the ticket (anyone, any agent) goes through `handOut`, which already reuses the recorded branch and
   worktree, so the new worker resumes from the last commit and finds uncommitted files where they were. Its start
   brief carries the latest handoff comment (2.5).

"Has work" is **derived**, not stored: a todo ticket with a recorded `branch` whose branch is ahead of its base.
The view gets `hasWork: {branch, tip, ahead, dirty?}`; the board line gets the marker `[WORK <tip7> +<ahead>]`.
New port method `BoardGit.ahead(repo, branch, base): Int?` (default null = unknown), read only when the view is built.

### 2.2 Assign to an existing agent

Give a ticket to a named agent profile instead of letting routing pick one.

1. Sets `agentId` = that profile (what `ticket update --agent` does today), then:
2. `mode = start` (default):
   - Agent has **no live session**: `handOut(ticketId, MOVE)`. Same gates as any start.
   - Agent has a live **IDLE session on no in-progress ticket** (a chat, or a worker whose ticket is done): the board
     ends that session first (2.1 step 3 rules), then hands out on the Exit event, on `sessionEvents`. Never inside the
     listener that delivered the event, never under the sessions lock.
   - Agent is **working on another in-progress ticket**: refused with "agent <name> is on <ticket>; use switch, or
     assign --queue". Assign never silently drops current work.
   - Agent is BUSY with no ticket (a chat turn): refused, "agent busy; assign --queue to take it after this turn".
3. `mode = queue`: add to the agent's queue (2.4) instead.
4. Refused if the profile's role is not allowed to run tickets, or the profile is in another project.

### 2.3 Switch

Move an agent from ticket A to ticket B in one call: `unassign(A)` + `assign(B, agent)`, in this order:

1. One board commit: A to todo with a handoff (2.1 step 2), B's `agentId` = the agent, B stays todo and is put
   at the head of the agent's queue (2.4). No extra "switch pending" state: the queue head is that state.
2. The worker on A ends (2.1 step 3).
3. Its Exit event drains the agent's queue, whose head is B: `handOut(B, QUEUE_DRAIN)`.

So switch is not a new start path: it is "unassign + queue at the head", and the existing exit drain does the start.
That is why it is safe from DEADLOCK-1: the start happens on the board's own thread after the exit event, exactly
where `drainProfileLane` already starts queued tickets today. With no worker on A (already unassigned), switch is
assign.

### 2.4 Agent queue

A short ordered list of todo tickets per agent profile.

- Storage: two nullable ticket columns, `queuedAgent` (profile id) and `queueRank` (int). A ticket is in at most one
  agent's queue. They are **persistent** and distinct from the volatile capacity mark `queuedFor`/`queuedAt` (which
  `clearQueueMarks()` wipes at boot and which only means "this lane was busy when you tried"). Merging the two would
  either lose the queue on every restart or make a restart start work; neither is acceptable.
- Length cap: `limits.agentTasks.queueMax` (default 5): a named limit (`LimitsGuardTest`).
- Moving a queued ticket out of todo (start by hand, backlog, delete) removes it from the queue. Moving it back to
  todo (for example `lane-one-worker` bouncing it) keeps `queuedAgent`, so it stays at its place.
- **Drain**: in `drainProfileLane` (already run on every session Exit of a profile, and on a FAILED first status),
  the agent's own queue goes first (lowest `queueRank`), then the existing `queuedFor` capacity lane. Origin
  `QUEUE_DRAIN`: the launch gate, pacing, lane caps and `checkSpawn` all apply; a refusal leaves the queue as it is
  and writes a ticket comment once.
- **When it drains**: the agent's worker ends. In the default flow that is `on-ticket-done` closing the worker after
  the report moved the ticket to done, so "when it reports done, it takes the next one" holds without a new trigger.
  If nothing closes the worker (closeWorker off), the board ends a done ticket's IDLE worker itself when its agent has a
  queued ticket (setting `tickets.agentTasks.closeDoneWorkerWhenQueued`, default true).
- **Never at boot**: a restart keeps the queue but starts nothing; the first exit of that agent after the restart
  drains it, or the owner/script starts the head. "A restart never launches work automatically" stays true.
- **One-worker rule**: the drained ticket replaces the one that just ended, so in-progress count does not grow. The
  drain is still a normal start, so `lane-one-worker` still bounces it if something else took the slot; the bounce
  keeps it queued (above).
- `startNext`, `startNextUnattended` and `laneCapsChanged` skip a ticket queued for an agent: it runs only from its
  agent's queue, or when started by hand (a hand start always wins and pulls it out of the queue).
- Board line marker: `[Q:<agent name>#<n>]`. The `done` lane event payload gains `agentQueueNext` (ticket id, or
  absent) so a hook can see the board will start that ticket itself.

### 2.5 Handoff note

A note on the ticket so the next worker (or the owner) knows where it stands. Comment kind `handoff` (new kind next
to done / issue / note), shown in the ticket and put in the next start brief (only the latest one).

Setting `tickets.agentTasks.handoffNote`:

- `auto` (**default**, zero model tokens): the board writes it: branch, tip sha, commits ahead of base with their
  subjects (up to `limits.agentTasks.handoffCommits`, default 10), `git status --short` count, and the worker's last
  assistant message (cut at `limits.agentTasks.handoffChars`, default 1500). `note` from the caller is appended.
- `worker`: before ending an IDLE worker, the board prompts it once ("write a handoff note via the report script with
  status info, then stop"), waits for that turn (bounded by `unassignWaitSeconds`), then ends it. Costs one
  full-context turn (section 7), so it is opt-in.
- `off`: no comment.

New port method `BoardSessions.lastAssistantText(sessionId): String?` (default null) for `auto`.

## 3. Layers

| Piece | Layer | Why |
|---|---|---|
| `unassign / assign / switch / queue` on `TicketBoard`, queue columns, `hasWork` view, `handoff` comment kind, drain order, board-line markers, the four MCP tools and CLI verbs | **Board plugin** (`infantryBoard` + `plugins/official/Infantry_Board`) | Tickets, lanes and queues are the board's concepts. Core must not know what a ticket queue is (D20). |
| `BoardGit.ahead`, `BoardSessions.lastAssistantText` | **Daemon API port** (`infantryDaemonApi`), implemented in `LocalBoardHost` | A generic read (git ahead count, a session's last output) the board asks the host for. Both have null defaults, so a host without them still runs. |
| Session kill / remove / start | **Core** (unchanged) | Already generic. No new core mechanism is needed for option A. |
| A UI drop target that accepts a dragged ticket on an agent card | **UI core seam** only if missing (a typed drag payload + drop target in `infantryUiCore`); the board and agents plugins use it | The board tab and the Agents cards are two plugins; neither may import the other. |
| Which agent should take which ticket automatically, rebalancing queues, pods | **Orchestration** (later, not in this build) | Orchestration may call the queue tools; the board and agents never depend on orchestration. |
| `on-ticket-done`, `worker-watch` skip rules | **Project scripts** (data) and the official samples | Our flow, not product (product-vs-this-project line). |
| `contextMode = continue` and a harness capability `resumeInOtherDirectory` | Harness API + harness plugins (backlog) | Per-harness behaviour. |

## 4. Surfaces

- **MCP / CLI / Lua**: the table in section 2. Each tool returns the ticket line(s) it changed and, for a deferred
  end/start, says so ("worker ends after its turn; B starts on exit"). Scripts get the same: a Lua flow can unassign,
  queue and switch with no model turn.
- **Board UI** (Infantry_Board UI half):
  - Ticket card menu (in progress): **Unassign** (keeps work), **Switch to…** (a list of todo tickets, puts the agent
    on the picked one).
  - Ticket card menu (todo): **Assign to…** (agent list: start now / add to queue).
  - Todo card shows the `has work` badge (branch tip, +N commits) and the latest handoff in the detail view.
  - Queued card shows `queued: <agent> #n`.
- **Agents UI** (Infantry_Agents card): the agent's current ticket, its queue (reorder by drag, remove), and drop a
  ticket card onto the agent card = assign (start if free, else queue).

## 5. Hooks: what changes so a queued or reassigned agent is not swept or killed

| Hook | Today | Change |
|---|---|---|
| `on-ticket-done` (lane enter done) | Mails VC, starts the first startable todo ticket (phase 2), closes the worker (phase 3). | Phase 2: when the payload has `agentQueueNext`, do **not** start a todo ticket (the board starts the queue head when the worker exits); log it. Closing the worker stays: that close is the exit that drains the queue. Add `"[Q:"` to `skipMarkers` (hook args) so it never picks another agent's queued ticket. |
| `worker-watch` (every 180 s) | Sweep removes sessions not on an in-progress ticket that are ended or idle > 15 min; stall-start the first todo ticket; kick idle in-progress workers. | Stall-start: `"[Q:"` in `skipMarkers` (args), so it never starts another agent's queued ticket. Sweep: unchanged in the default mode (between tickets the agent has no session, so nothing to sweep). With `contextMode = continue` (later) the session list gets `queued: n` per session and the sweep skips a session whose agent has a queue. Kick: an unassigned ticket is in todo, so it is never kicked; no change. |
| `lane-one-worker` (lane enter in_progress) | Bounces a start over `maxInProgress` back to todo top and removes the new worker. | No change. A drained queue start replaces the ticket that just finished, so it is under the cap. A bounce keeps `queuedAgent`, so the ticket stays queued. Assign/switch refuse up front when the profile's lane cap (the board's own `laneCap`) is full, so the common case never reaches the bounce. |
| `lane-pin-check` (todo / in_progress) | Checks provider/model pins. | No change: unassign moves to todo and fires it like any move. |

The board's own steps never run inside a hook's turn or under a session lock: the deferred end and start run on the
board's `sessionEvents` thread, after the Exit event, the path DEADLOCK-1 made safe.

## 6. Settings and limits (named; default = the cheapest)

| Key | Default | Other values | Cost of the non-default |
|---|---|---|---|
| `tickets.agentTasks.contextMode` | `fresh` | `continue` (backlog; only on a harness that can resume in another directory) | Every turn of the next ticket re-reads the previous ticket's context (section 7: about +50-60% tokens). |
| `tickets.agentTasks.handoffNote` | `auto` | `worker`, `off` | `worker`: one extra full-context turn per unassign or switch. |
| `tickets.agentTasks.closeDoneWorkerWhenQueued` | `true` | `false` | `false`: the queue waits until something else ends the worker. |
| `limits.agentTasks.queueMax` | 5 | any | none (a longer list, more to go stale). |
| `limits.agentTasks.unassignWaitSeconds` | 120 | any | a longer wait keeps a busy worker's tokens running. |
| `limits.agentTasks.handoffCommits` / `handoffChars` | 10 / 1500 | any | a longer handoff costs tokens in the next brief on every turn. |

## 7. Cost: reuse the agent's conversation vs. a fresh session per ticket

Inputs (measured or set in this project): a ticket's target is about 20 turns and 1.6M tokens (week plan 2026-11-02);
the context cap is 100K tokens (`contextCapTokens = 100000`); a fresh worker's first turn carries the system prompt,
role notes, the knowledge digest and the brief, about 30K. Every turn re-reads the whole context (prompt cache makes
it cheaper per token, not fewer tokens; the ratio below holds either way).

| Case | Context per turn | Input over 20 turns of ticket B |
|---|---|---|
| **Fresh session per ticket** (default) | starts ~30K, grows to ~100K, average ~65-70K | ~1.3-1.4M |
| Reuse (continue A's conversation) | starts where A ended, at the 100K cap, so a compaction turn (~100K in) comes first and then ~100-110K every turn | ~2.1-2.3M |
| Fresh + `auto` handoff note | +~1-2K per turn | +~30-40K (~3%) |
| `handoffNote = worker` | one extra turn at A's full context | +~100K once |

Reuse wins only when B is very short (a few turns) and reads the same files A already read (saving maybe 3-5 file-read
turns, ~150-250K). For a normal ticket it costs about 50-60% more. So the **default is fresh** (`contextMode = fresh`);
`continue` is opt-in and its cost is stated in the setting's description.

## 8. Decisions that are expensive to reverse

1. **A ticket owns its worktree; a session never changes directory.** Everything (merge mail, cleanup, guards,
   `useWorktree`) assumes it. Option B would undo that across every flow.
2. **The agent queue is persistent and separate from `queuedFor`.** Once scripts and the UI read `queuedAgent`,
   folding it into the volatile mark would change restart behaviour for every user.
3. **"Has work" is derived from git, not stored.** Cheap to keep right; a stored flag would drift from the branch.
4. **Comment kind `handoff` on the wire.** Adding a kind is a protocol change clients must render; it is additive, so
   old clients show it as a note.

## 9. Build tickets (implementation order)

Created in todo, Sonnet, each small with its own acceptance and scoped tests. Ids below.

1. **AGENT-TASKS-2 55d9515d (backend, weight 4)**: board `unassign` + `hasWork` view + `handoff` comment kind (`auto` note) +
   `BoardGit.ahead` / `BoardSessions.lastAssistantText` ports + MCP tool + CLI.
2. **AGENT-TASKS-3 ace812e8 (backend, weight 4)**: persistent agent queue (`queuedAgent`, `queueRank`, migration), drain order in
   `drainProfileLane`, skip in `startNext*`, board-line marker, `agentQueueNext` in the done payload,
   `closeDoneWorkerWhenQueued`, `infantry_agent_queue` tool + CLI.
3. **AGENT-TASKS-4 f5ff8206 (backend, weight 4)**: `assign` (start / queue) and `switch` on top of 2 and 3, tools + CLI.
4. **AGENT-TASKS-5 748eccd4 (backend, weight 2)**: hook updates: `on-ticket-done` honours `agentQueueNext`; `[Q:` skip markers in
   the official samples and in this project's hook args; a Lua smoke script that unassigns, queues and switches.
5. **AGENT-TASKS-6 5f3069b0 (frontend, weight 3)**: board UI: Unassign, Switch to…, Assign to…, has-work badge, queued badge,
   handoff in the detail view.
6. **AGENT-TASKS-7 4504ed7c (frontend, weight 4)**: Agents card: current ticket, queue list (reorder, remove), drop a ticket on the
   card to assign; the generic drag/drop seam in `infantryUiCore` if one is missing.
7. Backlog, **AGENT-TASKS-8 b6bd616d (backend, weight 5)**: `contextMode = continue` with a harness capability
   `resumeInOtherDirectory`; opt-in, costlier (section 7).
