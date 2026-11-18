<!-- SPDX-License-Identifier: Apache-2.0 -->
# UI parity: Kotlin app vs Rust app (RUST-PARITY-1, 2026-11-18)

This is the owner's checklist. Each row is one user-facing feature of the Kotlin desktop app, checked against the Rust
app (`app/`, React in Tauri). It was checked by reading the code on both sides, not by clicking through a running app.

- **done**: the Rust app has it and it talks to the daemon.
- **partial**: it exists but lacks part of what Kotlin does. Notes say what is missing.
- **missing**: the Rust app does not have it.

Each partial or missing row names what it needs:

- **"UI only"**: the Rust daemon already has the frames, and the work is in `app/`.
- **"daemon: X"**: the Rust daemon has no frame X yet.

Rows marked *(fixed today)* were gaps closed by this sweep.

MCP, Pods, Account and Sites are listed at the end without a grade, because backend 9c2fcbb2 is doing their daemon frames now.

## Window and title bar

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Custom title bar, drag to move, min/max/close | yes | done | |
| Left / right panel toggles | yes | done | |
| Project picker, add project | yes | done | |
| Edit project dialog (folder, lane caps, project skills, project secrets) | yes | partial | Add only. Edit dialog: UI only (`project_save`, `project_config`). |
| Agent avatar strip | yes | done | |
| Assistant portrait and popup | yes | missing | Needs an Assistant chat panel. UI only (a session with the assistant role). The dead pill was removed today. |
| "N need attention" chip | yes | done | *(fixed today)* It showed a fixed 14. It is now the real count and hides at 0. |
| Server/host label | yes | partial | *(fixed today)* It now shows "This computer" or the daemon's host (was fixed text). No server-switch dropdown with saved servers: UI only. |
| Connection chip, connect dialog | yes | done | Phone pairing is in Settings > Daemon. |
| Theme toggle | yes | done | |
| New ticket button | yes | done | *(fixed today)* It opened a form of sample lists and created nothing. It now opens the board's real ticket dialog. |
| Single instance (second launch raises the window) | yes | missing | Tauri `single-instance` plugin. App only. |
| Splash while loading | yes | missing | Cosmetic. |
| Crash strip instead of a native dialog | yes | missing | UI only. |
| Resizable side panels | yes | missing | UI only. |
| Narrow window: drawers, one pane at a time | yes | done | |

## Sidebar and agent cards

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Counters: Needs input, To review, Working | yes | done | *(fixed today)* "To review" was a fixed 0. It now counts the Pending lane. |
| Board button with count | yes | done | *(fixed today)* The count was a fixed 3. It now counts To Do + In Progress. |
| Search agents | yes | done | Ctrl+K works *(fixed today)*. |
| Role filter chips | yes | done | *(fixed today)* They listed sample roles. They now list the daemon's roles. |
| Groups by status (Waiting on you / Ready to check / Running / Quiet) | yes | done | *(RUST-UI-9)* Ready to check = a quiet session whose ticket is in Pending. |
| Card: avatar, state dot, role, last line, age, ticket | yes | done | |
| Card badges: AUTO, report-back, watchdog, progress bar, runner | yes | partial | Progress line and ticket only. The rest is UI only (fields are on the session/agent frames). |
| Pulsing border while busy, selected stripes | yes | partial | Live ring only. Cosmetic. |
| Card "⋯" menu: Open, Resume, Assign to project, Remove | yes | partial | *(RUST-UI-8)* "⋯" or right-click: Open, Resume (ended sessions), ticket actions, Remove with confirm. No "Assign to project": daemon (`session_project` has no handler). |
| Card ticket block: change / unassign ticket, ticket picker | yes | done | *(RUST-UI-8)* In the card menu: a searchable To Do picker (Assign / Queue, held tickets behind a toggle) and Unassign. Needs an agent profile on the session. |
| Card queue (running + queued tickets, move up/down, remove) | yes | partial | *(RUST-UI-8)* The picker can queue a ticket. No queue list with move / remove: UI only (`agent_queue`). |
| Drag a ticket onto an agent card to assign it | yes | missing | UI only (`ticket_assign`). |
| Empty states | yes | done | |
| "Working in every project" dialog (experimental) | yes | missing | UI only. |

## Session view

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Header: role, id, harness/provider/model chips | yes | done | |
| Context-limit and effort chips | yes | done | *(RUST-UI-8)* From the session record. The duplicated provider chips were removed. |
| Switch agent (provider/model) | yes | done | |
| Interrupt, restart (with force) | yes | done | |
| Session stats | yes | done | |
| Automation stats line ("turns saved") | yes | missing | *(fixed today)* A sample line was removed. Needs a per-session automation count: daemon work. |
| Status line: agent count, session state | yes | done | *(fixed today)* It was sample text ("allowAll · 14 agents"). It now shows real counts and "not connected". The permission mode is not shown: UI only. |
| Centre tab bar from the plugin list | yes | done | |
| Ended-session bar with Resume | yes | partial | Composer Resume opens the resume dialog. No inline bar: UI only. |
| Failed-session card with Retry | yes | missing | UI only (`session_restart`). |
| Start-session pane (role / provider / model / branch / worktree) | yes | partial | The New agent dialog covers role / harness / model. No branch or worktree pick: UI only. |

## Chat transcript

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Owner, comms and assistant messages | yes | done | |
| Markdown: headings, lists, quotes, tables, inline code/bold/italic/links | yes | done | *(fixed today)* It was plain paragraphs. A new parser (`packages/client/src/markdown.ts`) never produces HTML. |
| Code blocks with a language label and Copy | yes | done | *(fixed today)* |
| Thinking blocks, collapsible | yes | done | |
| Reasoning side panel (setting) | yes | missing | UI only. |
| Tool activity | yes | partial | One summary line per tool burst. No collapsible Activity box: UI only. |
| File-edit diff boxes | yes | done | |
| To-do box ("To do 3/7") | yes | missing | UI only, if the harness sends todo events. |
| Images in the transcript, full-size viewer | yes | missing | UI only (`image`). |
| Chat cards (`card:<type>` fences, plugin renderers, folded updates) | yes | partial | *(RUST-UI-8)* Card fences parse like Kotlin (`id`, `op=set/append/replace`), draw through a renderer registry (`registerChatCard`) or a generic card (title, text, link, fields, lists, JSON toggle), and later fences of one id fold into the first. Folding works within one message only (Kotlin: the whole chat). No plugin ships a renderer yet. Card images need daemon `card_image`. |
| Background-jobs row | yes | done | *(RUST-UI-8)* Label, pid and age from the record's `bgJobs`, above the composer. Stopping them is in the Run tab. |
| Pending send: sending / sent / retry | yes | done | No "Discard this message": UI only. |
| Jump to latest, stick to bottom | yes | done | |
| Load older pages on scroll up | yes | partial | History loads on open. No paging: UI only. |
| Search this chat (Ctrl+F) | yes | missing | *(fixed today)* A dead button was removed. UI only. |
| Expand / collapse all, history buttons | yes | missing | *(fixed today)* These were dead buttons and were removed. UI only. |
| Read comms aloud | yes | missing | *(fixed today)* A dead button was removed. Needs a TTS plugin (`spoken` frame exists). |

## Composer

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Enter sends, Shift+Enter newline, no session = spawn | yes | done | |
| Busy: queue; queued list with Send now / Remove | yes | done | |
| Stop turn, Send | yes | done | |
| Resume, Swap agent, Restart, Cancel turn | yes | done | |
| Delete / remove session with confirm | yes | done | *(fixed today)* It was a dead button. It now confirms, then sends `session_delete`. |
| Attachments: file picker, paste image, drag and drop | yes | missing | *(fixed today)* A dead button was removed. Daemon: an `image_upload` handler (RUST-UI-8 found the frame name only; the Chat plugin notes it as a host need). |
| Slash commands with autocomplete | yes | missing | Daemon: a `command_catalog` handler (frame name only, no handler). |
| Notice strip for command results | yes | missing | Daemon: `command_result` (frame name only). |
| Stop background processes (Ctrl+Shift+K) | yes | missing | UI only (`processes_reap`). |
| End session (Ctrl+Alt+K) | yes | done | *(RUST-UI-8)* Graceful `kill`, so the session can be resumed. |
| Minimize / expand composer | yes | missing | UI only. |
| Composer action slot (voice etc.) | yes | missing | *(fixed today)* The dead mic button was removed. Needs a plugin slot: UI only. |
| Disconnected: "commands are disabled" | yes | done | *(fixed today)* Now on the status line above the box. |

## Prompts and questions

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Tool approval card (allow once / session / always / deny, expiry) | yes | done | |
| Approval card keys (arrows, Enter, Esc) | yes | missing | UI only. |
| Question card: options, Recommended, multi-select, Other | yes | done | |
| Question keys (digits, arrows, Space) and auto-select timer line | yes | missing | UI only. |
| Questions pill for questions in other sessions | yes | missing | UI only. The Attention tab lists them. |
| Cancel question / Later | yes | missing | UI only (`question_cancel`). |

## Board

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Lanes Backlog / To Do / In Progress / Pending / Done with counts | yes | done | |
| Drag between lanes and reorder (mouse, touch, keyboard) | yes | done | |
| Filters (epic, role, assignee, text) | yes | done | |
| New / edit ticket: title, description, type | yes | done | |
| Ticket form: epic, assignee, executor, effort, context limit, runner, images | yes | missing | UI only for the fields (`ticket_save` carries `epicId`). Images need the daemon `image_upload` handler. |
| Delete ticket | yes | done | |
| Collapse a lane | yes | done | *(RUST-UI-8)* The lane header folds it to title and count, remembered per lane; a folded lane still takes drops. |
| "All projects" toggle | yes | missing | UI only. |
| Start next | yes | done | *(RUST-UI-8)* Button in the filter bar sends `ticket_start_next` for the current project; a refusal toasts *(RUST-UI-9)*. |
| Card buttons: Start, Done, Back, Reopen, Park, Archive | yes | partial | Drag does the moves. No buttons: UI only (`ticket_move`). |
| Assign / switch / queue / unassign from a card | yes | missing | UI only (`ticket_assign`, `agent_switch`, `agent_queue`, `ticket_unassign`). |
| Multi-select and move to epic | yes | missing | UI only (`ticket_save`). |
| Card badges: epic, runner, waiting, has work, queued | yes | partial | Role, assignee and branch only. UI only. |
| Ticket comments, resolve, notify list, handoff | yes | missing | Daemon: ticket comment frames (none in the Rust protocol). |
| Related tickets | yes | missing | UI only (`ticket_link`). |
| Archive view: search, restore | yes | missing | UI only (`archive_query`, `archive_apply`). |

## Attention and notifications

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Attention tab: open questions, items with Acknowledge | yes | done | |
| Notification feed with actions and dismiss | yes | done | |
| Question history, "Change to" an auto-picked answer | yes | missing | UI only (`question_list`). |
| Clear stale, Open agent per item | yes | missing | UI only. |
| Watchdog list (stalled / quiet / unreported agents) | yes | missing | UI only. |
| Toast stack (bottom right), prompt toasts | yes | partial | *(RUST-UI-9)* Toast stack bottom right (newest first, repeats counted, auto-expire, dismiss): refusals of UI requests with no inline view (card menu, Start next) and pushed notifications. No prompt toasts (approve from a toast). |
| Desktop OS notifications, sounds | yes | missing | Daemon: `notify_push` / `notify_sounds` (the Rust protocol has only `notify`). Tauri notification plugin. |
| Spoken alerts | yes | missing | `spoken` frame exists. Needs a TTS presenter. |
| "Update available" notice | yes | done | Added by RUST-UPD-1. *(fixed today)* The status-bar pill was fixed text; it now shows only for a real update. |

## Status bar

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Connection line | yes | done | |
| Processes badge | yes | done | |
| RAM free / agents CPU | yes | missing | *(fixed today)* Sample text was removed. UI only (`processes` report). |
| Build / version stamp | yes | done | *(fixed today)* It was a fixed build string. It now shows the app's real version. |
| Client / daemon version mismatch warning | yes | missing | UI only (`ready` carries the contract). |
| Shortcut hints | yes | done | *(fixed today)* They now name only keys that work. |

## Keyboard shortcuts

All *(fixed today)*: before this sweep no window-wide key worked, and the hints were labels only.

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Ctrl+K agent search | yes | done | |
| Alt+Up / Alt+Down next / previous agent | yes | done | |
| Ctrl+J next agent that needs input | yes | done | |
| Ctrl+N new agent | yes | done | Opens the New agent dialog. |
| Ctrl+R resume | yes | done | Opens the Resume dialog. |
| Ctrl+, settings | yes | done | |
| Ctrl+/ focus the composer | yes | done | |
| Ctrl+0..9 tabs, Ctrl+8 board | yes | done | ^3 Changes and ^4 Review hints added. |
| Ctrl+Shift+N switch agent, Ctrl+Alt+K end session | yes | done | *(RUST-UI-8)* |
| Ctrl+Shift+K stop background processes | yes | missing | UI only (`processes_reap`, per session). |
| Ctrl+L connect dialog | yes | missing | UI only. |
| Ctrl+F chat search | yes | missing | See transcript. |

## Settings and appearance

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Settings dialog with sections and search | yes | done | |
| Plugin-declared settings cards | yes | done | |
| Dark theme | yes | done | |
| Accent colour | yes | missing | *(fixed today)* A dead picker was removed. UI only (`accent` in Infantry_Appearance config). |
| Blur behind dialogs | yes | missing | UI only. |
| Agent avatar style, your portrait, mascot | yes | missing | UI only plus the Mascot renderer. |
| Compact agent cards | no | n/a | *(fixed today)* A dead switch was removed. Kotlin has no such setting. |
| Daemon picker, "Start daemon with the app" | n/a | n/a | *(fixed today)* Sample rows were removed. Remote daemon, embedded runner and phone pairing rows are real. |
| Secrets vault (reveal behind Windows Hello) | yes | done | |
| Phone pairing, `infantry` on PATH, updater | yes | done | RUST-UPD-1. |
| Tool approval settings (timer, expiry, rules) | yes | missing | Rules: UI only (`approval_rules`). Timer and expiry: daemon (`tool_approval_set` has no handler). |
| Setup wizard (install Node, providers) | yes | missing | Daemon: `harness_install` / `cli_locations` handlers (frame names only). |
| Plugin drop-in install, consent dialog | yes | missing | UI only (`plugin_admin`). |
| About: version | yes | done | *(fixed today)* It was a fixed string. |

## Centre tabs

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| CLI (transcript) | yes | done | |
| Mail | yes | done | |
| Roles: list, edit, traits, bindings, delete | yes | done | |
| Profiles | yes | done | No "start as profile": UI only (`new`). |
| Triggers: list, fire, arm, disable, delete, events | yes | done | |
| Trigger editor: at / webhook / ship kinds, action, coalesce, max per hour | yes | partial | Name, kind and interval only. UI only (`trigger_save`). |
| Epics: list, create | yes | partial | No add-task-to-epic or move tasks: UI only (`ticket_save`, `ticket_move`). |
| Notes / memory: scopes, search, edit, delete, decisions, issues | yes | done | |
| Run: commands, console, stop | yes | done | |
| Run: start on branch | yes | missing | Daemon: `run_branches` (not in the Rust protocol). |
| Git: working tree, diff, history, branches | yes | done | |
| Git: base branch picker, project folder, verification pipeline | yes | missing | Base branch and folder: UI only (`project_base`, `project_save`). Pipeline: daemon (`pipeline_config` / `pipeline_show` have no handler). |
| Processes | yes | done | No auto-reap toggle: UI only (`settings_set`). |
| Health | yes | done | |
| Plugins: list, on/off, settings | yes | partial | No Browse / Sources / install-from-file / roll back: UI only (`plugin_admin`). |
| Pace | yes | done | |
| Stats | yes | partial | *(fixed today)* It was all sample tiles and bars. It now shows the selected session's real numbers. Tokens by day/week, cost ranking and harness comparison need a client store for `usage_meter`: UI only. |
| Skills, Prompts | yes | done | No "Insert into the composer": UI only. |
| Calendar | branch only | missing | *(fixed today)* Sample data was removed. The tab shows only when a plugin declares it. Daemon: calendar frames (none in the Rust protocol). |
| Settings page (all scopes) | yes | done | |

## Right panel

| Feature | Kotlin | Rust | Notes |
|---|---|---|---|
| Attention (^6) | yes | done | |
| Usage (^7): meters, pacing, override | yes | done | |
| Changes (^3): staged / unstaged, worktree, branch diff, history | yes | done | |
| Diff modal with previous / next file | yes | partial | Inline diff view. No modal stepping: UI only. |
| Review (^4) | yes | done | Read-only queue. No Open agent: UI only. |
| Prompts | yes | done | *(fixed today)* It listed sample prompts. It now shows the real prompt library. |
| Skills | yes | done | *(fixed today)* It listed sample skills. It now shows the real skill library. |

## Out of scope today (backend 9c2fcbb2 is doing the daemon frames)

| Feature | Kotlin | Rust today |
|---|---|---|
| MCP: servers, enable, test, revoke | yes | enable, Test and Revoke wired. No add / edit / grant dialogs. |
| Pods: submit, watch, cancel | yes | registry on/off only. No pod list frame. |
| Account: sign in, plan | yes | read-only status. |
| Sites: server list | yes | read-only list. |

## Counts

Graded rows, excluding the 2 n/a rows and the out-of-scope table: **87 done, 20 partial, 52 missing** (159 rows; RUST-UI-8 split one row and changed 11). RUST-PARITY-1 had 81 / 16 / 61.
This sweep changed 38 rows:

- 27 now work: markdown and code Copy, keyboard shortcuts, real counts, the real New ticket dialog, delete session,
  Stats, the Prompts and Skills panels, and the version.
- 11 had sample data or dead buttons removed. They now read as missing instead of looking finished.

Most missing rows are **UI only**: the Rust daemon already handles the frames. RUST-UI-8 found that some frames named in the protocol have no handler (`image_upload`, `command_catalog`, `command_result`, `session_project`, `pipeline_*`, `tool_approval_set`, `harness_install`, `cli_locations`, `timeline_list`); those rows now say "Daemon". Only six rows need new daemon frames:

- ticket comments;
- `notify_push` / `notify_sounds`;
- `run_branches`;
- calendar;
- an automation count;
- (with a TTS plugin) spoken alerts.

The biggest UI gaps, in the order the owner will feel them:

1. Agent-card menu and ticket assignment.
2. Composer attachments and slash commands.
3. Chat cards and images.
4. Toasts.
5. Board card actions, archive and comments.
