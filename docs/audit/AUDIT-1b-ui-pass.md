# AUDIT-1b: UI bug and polish pass, React app in a browser (2026-11-20)

Ticket #3 AUDIT-1b. Read-only: no product code was changed. The goal is to find the broken and clunky spots and keep
the UI the same overall: no redesign. Each P1 or P2 finding is filed as its own pending ticket, #54 to #67 (index at the
end). P3 findings are recorded here only. Branch `audit-1b-ui-pass` at `6ca71284f` (release 0.1.4).

## Method

- **App under test:** this worktree's React app, served by Vite through `scripts/ui-live-preview.ps1` (the AgentsRoom
  command "Infantry live preview"). It runs a throwaway dev daemon (`cargo run -p infantry-daemon`, build `rust-0.1.4`)
  on a free loopback port with a fresh state dir under `%TEMP%\infantry-ui-preview\`, and **no plugins staged**. In this
  run the daemon was on `127.0.0.1:64823` and the UI on `http://localhost:64824/`. The live daemon on :18990, its state
  dir, `~/.infantry` and the installed app were never touched.
- **Driver:** the Playwright MCP only (`mcp__playwright__*`), in a headed Chromium. Every finding below lists the steps
  that show it and a screenshot. WebSocket frames were read with `page.on('websocket')` inside `run_code_unsafe`.
- **Steps notation:** steps name the Playwright MCP tools without their prefix (`navigate`, `click`, `evaluate`,
  `resize`, `press_key`, `snapshot`, `console_messages`, `run_code_unsafe`). Each list ends with `take_screenshot`, saved
  under `docs/audit/ui/`. `/` is the preview URL the script prints.
- **Viewports:** 1600×900 (main), 1024×768 (tablet layout) and 390×844 (phone).
- **Extra modes:** `/?stress=1` (6 synthetic busy sessions, no daemon, perf HUD) and `/?daemon=ws://127.0.0.1:9/ws`
  (offline: nothing listens there, and Chrome refuses port 9, so the socket closes at once).
- **Contrast:** rendered colours read back from a 1×1 canvas and run through the WCAG 2.x luminance formula.
- **AgentsRoom reference:** AgentsRoom's product help pages, one comparison line per screen.
- **Cost:** the guided setup's last step spawned one real claude (opus) turn on its own (SW-1). No other agent was started.
- **Not driven:** the Tauri desktop host and the Android app. Their host-only code is covered by reading in section 20.
- **Paths:** a path with no prefix is under `app/shell/src/components/`. Other paths start at the repo root.

Priority: **P1** = a daily flow is broken or a control can't be reached. **P2** = a real defect a user meets (wrong
state, dead control, misleading text, an accessibility failure). **P3** = polish, recorded here only.

## Summary

Screens covered: the title bar, the sidebar, the session view (header, tab strip, transcript, composer), all 20 centre
tabs, all 7 side-panel tabs, the project picker, the board with New ticket and the ticket dialog, the Settings dialog
(4 sections), Stats (3 tabs), New agent, Resume, the setup wizard, the Assistant box and the Global workers modal. The
app was also checked at 1024 and 390 px, offline, under stress and in the light theme.

| Priority | Findings | Tickets |
|---|---|---|
| P1 | 3 (PP-1, SV-1, RP-1) | #54, #55 |
| P2 | 14 (TB-1, TB-3, SV-5, CT-1, CT-2, CT-3, RP-2, BD-1, ST-1, RS-1, SW-1, AB-1, NW-1, LT-1) | #56 to #67 |
| P3 | 28 | none (this report) |
| Checked, OK | 6 (session open, reconnect backoff, a virtualised transcript, New agent from the sidebar, New ticket and the ticket dialog, the workers modal) | none |

### Top 10

1. **PP-1** Add project fails silently when the daemon refuses the folder. #54 UI-PROJECT-ADD-1 (P1)
2. **SV-1, RP-1** 13 of 27 tabs are off-screen at 1600 px, and a mouse can't reach them. #55 UI-TABS-OVERFLOW-1 (P1)
3. **SW-1** Guided setup spawns a real agent (claude, opus) the moment its last step opens, and can wait forever.
   #57 UI-SETUP-HELLO-1
4. **SV-5** "End session" is a solid red trash button that kills the session on one click. #65 UI-END-SESSION-1
5. **AB-1** The Assistant box never settles, and a question typed into it would spawn a generic agent.
   #66 UI-ASSISTANT-BOX-1
6. **CT-1** A daemon with no plugins shows all 14 plugin tabs, each one a dead end. #56 UI-TABS-ZERO-PLUGINS-1
7. **CT-3** The Settings tab says "Loading settings…" forever. #59 UI-SETTINGS-LOADING-1
8. **ST-1** Cancel in the Settings dialog keeps your changes. #58 UI-SETTINGS-DIALOG-1
9. **RP-2** Offline, the Usage panel blames the daemon; unknown readings show raw reason codes.
   #63 UI-USAGE-STATES-1
10. **TB-1, NW-1** The window buttons are dead in a browser and make a phone page scroll sideways.
    #60 UI-WINCTRL-WEB-1

Next in line: LT-1 (#62), RS-1 (#61), BD-1 (#64), CT-2 and TB-3 (#67).

## 1. Title bar

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| TB-1 | Minimize, Maximize and Close show in a browser and do nothing: `run()` returns early outside Tauri. | `layout/window-controls.tsx:3, 11-15`; `layout/title-bar.tsx:81` | [01](ui/01-first-load-1600.png). navigate `/` → click "Close" → nothing happens; the page stays. | P2 | #60 |
| TB-2 | Routine controls use the danger red: the New ticket button and the attention count have a red outline, so normal counts look like errors (see GS-1). | `layout/title-bar.tsx:31, 60` | [10](ui/10-tab-skills.png). navigate `/` → screenshot the title bar. | P3 | none |
| TB-3 | Offline, the badge and the status bar show the raw WebSocket close code: "offline: closed (1006) ws://127.0.0.1:9/ws". | `app/packages/client/src/connection.ts:45` (also `app/shell/src/daemon/tauri-transport.ts:26`) | [57](ui/57-offline-2s.png). navigate `/?daemon=ws://127.0.0.1:9/ws` → wait 2 s → screenshot. | P2 | #67 |

**AgentsRoom:** errors read as sentences ("Could not read the repository", "Project folder not found"), never as codes.
Its title bar carries a needs-input pill.

## 2. Sidebar

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| SB-1 | "Needs input" is a muted grey label that does nothing on click, while "Working" is a button that opens the lanes modal. The count that most needs the user is the quietest one. | `agents/sidebar-counters.tsx:36, 38-44` | [52](ui/52-needs-input-counter-click.png), [51](ui/51-working-counter-click.png). navigate `/` → click "Needs input": nothing opens → click "Working": the lanes modal opens. | P3 | none (see #14) |
| SB-2 | The three counters show even with no agents at all (0, 0, 0). | `agents/sidebar-counters.tsx:34-46` | [10](ui/10-tab-skills.png). navigate `/` on a fresh daemon → screenshot. | P3 | none |
| SB-3 | The Board's open-ticket count and the active role chip use the danger red (GS-1). | `agents/sidebar-counters.tsx:53`; `agents/role-filter-chips.tsx:33` | [36](ui/36-board-two-tickets.png). Board → create two tickets → screenshot the sidebar. | P3 | none |

**AgentsRoom:** sidebar sections fold. The counters appear from 3 agents. The Needs-you counter has a red halo and opens
the needs-input inbox. Roles are filtered with sliders, not chips.

## 3. Session view: header, tab strip, transcript, composer

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| SV-1 | The 20 centre tabs sit in one row with no scrollbar. At 1600×900, 9 are off-screen, with no arrow, fade or "more" menu, and a mouse wheel doesn't scroll them. After a scroll the first tabs vanish the same way. The active tab turns bold, which widens it and nudges its neighbours. | `session/session-tab-bar.tsx:10, 23`; `app/shell/src/plugins/catalog.tsx:81-102` | [01](ui/01-first-load-1600.png), [16](ui/16-tab-health.png). resize 1600×900 → navigate `/` → evaluate: count the tabs in `[role=tablist][aria-label="Session views"]` whose right edge passes the strip's: 9 of 20. | P1 | #55 |
| SV-2 | Tab shortcut digits don't follow the strip: ^1 CLI, ^2 Pods, ^5 Roles, ^9 Profiles, ^0 Triggers; in the side panel ^6, ^7, ^3. ^8 is wired to Board in code. | `layout/use-shortcuts.ts:75-84`; each tab's `hint` in `app/shell/src/plugins/catalog.tsx` | [10](ui/10-tab-skills.png). navigate `/` → read the tab labels. | P3 | none |
| SV-3 | At 1024 px the session header wraps to about five lines, and "idle" shows twice (the status chip and the status line). | `session/session-header.tsx:32-34` (the GAPS chip at :49-57 is #26) | [53](ui/53-width-1024.png), [53b](ui/53b-width-1024-session-header.png). resize 1024×768 → navigate `/` → click a session → screenshot. | P3 | none |
| SV-4 | Offline, the composer looks enabled and takes typing. Only the hint says "Not connected: commands are disabled."; the textarea is disabled only while another client holds the session. | `session/composer.tsx:260, 384` | [58](ui/58-offline-11s.png). navigate `/?daemon=ws://127.0.0.1:9/ws` → wait 11 s → evaluate `document.querySelector('textarea').disabled`: false. | P3 | none |
| SV-5 | "End session" is a solid red trash button, the loudest control in the composer row, and one click sends `kill` with no confirm. "Remove agent" uses the same icon for a different action. | `session/composer.tsx:223-229` | [49](ui/49-sidebar-after-first-agent.png). click a session → evaluate the computed background of `[aria-label="End session"]`: `rgb(239, 68, 68)`. Don't click it. | P2 | #65 |
| SV-6 | Connected with no session picked, the empty pane shows the Unplug icon (the "disconnected" picture) above "No session selected". | `session/chat-panel.tsx:77-83` | [01](ui/01-first-load-1600.png). navigate `/` while connected → screenshot the CLI tab. | P3 | none |
| SV-7 | Prompts and Skills each appear twice: as centre tabs and as side-panel tabs. | `app/shell/src/plugins/catalog.tsx:81-113` | [10](ui/10-tab-skills.png), [11](ui/11-tab-prompts.png), [22](ui/22-right-prompts.png), [26](ui/26-right-skills.png). navigate `/` → click each. | P3 | none |

Checked, OK: opening a session takes under 60 ms ([59](ui/59-session-open-60ms.png)).

**AgentsRoom:** a ^K palette reaches every view and command, so a crowded tab strip is never the only way in.

## 4. Centre tabs

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| CT-1 | A daemon with zero plugins shows all 14 plugin tabs, and each opens a refusal or "Not in the Rust daemon yet". `useRegistry()` returns `null` both for "not loaded yet" and for "loaded, none", and `tabsFor()` reads `null` as "show every tab". | `app/shell/src/plugins/use-plugins.ts:45-50`; `app/shell/src/plugins/catalog.tsx:137-149` | [16](ui/16-tab-health.png) ("0 plugin(s)"), [17](ui/17-tab-plugins.png), [06](ui/06-tab-triggers.png), [07](ui/07-tab-calendar.png), [19](ui/19-tab-sites.png). navigate `/` → click Health → click Triggers, Calendar, Sites. | P2 | #56 |
| CT-2 | Protocol names, raw values and developer notes in user-facing text. Pods: "The daemon has no pod-list frame". Calendar: "Not in the Rust daemon yet … (calendar list, add, complete)". Roles: a `BUILT_IN` chip on all 17 cards, names cased from ids ("Pm", "Qa", "Seo", "Devops") and "Role text holds preferences; product code holds mechanisms". Health: an "owner ports" row. Review hint: "(there is no pod-list frame)". Secrets: "Protected by os." | `panels/board-panels.tsx:160-162`; `tabs/calendar-tab.tsx:12`; `roles/roles-tab.tsx:85, 126`; `health/health-tab.tsx:92`; `panels/service-panels.tsx:109`; `settings/secrets-panel.tsx:112` | [03](ui/03-tab-pods.png), [04](ui/04-tab-roles.png), [07](ui/07-tab-calendar.png), [16](ui/16-tab-health.png), [25](ui/25-right-review.png), [39 secrets](ui/39-settings-dialog-secrets.png). navigate `/` → click each tab → screenshot. | P2 | #67 |
| CT-3 | The Settings tab, and plugin settings cards, stay on "Loading settings…" forever once the daemon refuses `config_get`. No timeout, error or retry. | `settings/declared-settings.tsx:53`; `plugins/plugin-settings.tsx:24` | [20](ui/20-tab-settings.png). run_code_unsafe: log WebSocket frames → click the Settings tab → frame `config_get refused: this daemon does not serve that frame yet` → wait 30 s: still loading. | P2 | #59 |
| CT-4 | Empty states name the gap but don't hold the action. Skills says "No skills yet" while "New skill" sits in the header; the sidebar says "Start a new agent to see it here" with the button at the foot of the column. | `library/skills-tab.tsx:107`; `agents/agent-sidebar.tsx:66` | [10](ui/10-tab-skills.png). navigate `/` → click Skills. | P3 | none |
| CT-5 | Every Scripts card shows both a "never run" chip and "0 runs". | `scripts/scripts-tab.tsx:180, 198` | [12](ui/12-tab-scripts.png). navigate `/` → click Scripts. | P3 | none |
| CT-6 | A fresh daemon lists 18 bundled global scripts, and several are written for this project's own PM process ("Goal handoff … hands the machine to another project: mails its PM and tells our PM to start"). PM decision (2026-11-20): project-specific PM scripts must not ship to users; the PM is filing a bug. | `rust/crates/infantry-scripting/global-scripts/index.json` | [12](ui/12-tab-scripts.png). navigate `/` → click Scripts: "18 of 18". | P3 | bug filed by the PM |
| CT-7 | Mail puts bordered cards inside bordered panels, so borders double up. | `mail/mail-tab.tsx:89, 115, 138, 157` | [02](ui/02-tab-mail.png). navigate `/` → click Mail. | P3 | none |
| CT-8 | Services says "Your SuperiorSoft account". PM decision (2026-11-20): intended. The Services hub is SuperiorSoft's own service, so the wording stays. | `panels/service-panels.tsx:137` | [18](ui/18-tab-services.png). navigate `/` → click Services. | P3 | none (intended) |

Checked, nothing to report beyond CT-1: Profiles ([05](ui/05-tab-profiles.png)), Epics ([08](ui/08-tab-epics.png)),
Notes ([09](ui/09-tab-notes.png)), Run ([13](ui/13-tab-run.png)), Git ([14](ui/14-tab-git.png), see PP-1), Processes
([15](ui/15-tab-processes.png)).

**AgentsRoom:** "Create first skill" sits inside the empty state. Triggers open on preset cards. Processes shows a
"Nothing is stuck" chip. Git says "Could not read the repository". Prompts are validated before they save.

## 5. Right panel

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| RP-1 | The 7 side-panel tabs sit in one row with no scrollbar. At 1600×900, 4 are off-screen (the strip ends at "Changes ^3"), with no affordance. | `usage/right-panel.tsx:21` | [01](ui/01-first-load-1600.png), [25](ui/25-right-review.png). resize 1600×900 → navigate `/` → evaluate: side-panel tabs past the strip's right edge: 4 of 7. | P1 | #55 |
| RP-2 | Usage gets three states wrong. Offline it blames the daemon ("did not answer usage_get … older than this app") and keeps "Pacing off". A provider with no credentials shows "usage unknown (not signed in)" twice, then "usage unknown (no_credentials): pacing off for opencode-go (fail open)". The manual button reads "Type %" on one card and "Set %" on the next. | `usage/usage-panel.tsx:26-33`; `app/packages/client/src/stores/usage.ts:281-285`; `usage/pacing-card.tsx:97` | [10](ui/10-tab-skills.png) (both cards), [49](ui/49-sidebar-after-first-agent.png), [58](ui/58-offline-11s.png). navigate `/` → Usage → screenshot; navigate `/?daemon=ws://127.0.0.1:9/ws` → wait 11 s → screenshot. | P2 | #63 |
| RP-3 | The side-panel tablist has no accessible name; the centre one is "Session views". | `usage/right-panel.tsx:21` | [01](ui/01-first-load-1600.png). navigate `/` → snapshot: the second tablist has no name. | P3 | none |

Checked, nothing to report: Attention ([21](ui/21-right-attention.png)), Changes ([23](ui/23-right-changes.png)), MCP
([24](ui/24-right-mcp.png)).

**AgentsRoom:** usage is never shown as zero when it is unknown. MCP lists errors per server.

## 6. Project picker

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| PP-1 | Add project closes the form at once and shows nothing when the daemon refuses the folder. The picker still says "No projects", and the Git tab shows its generic "No project yet". The refusal does reach the store's `error`, but no screen shows it. | `layout/project-picker.tsx:26-31, 61-65`; `app/packages/client/src/stores/projects.ts:187-188, 392-394`; `git/git-tab.tsx:306, 311-316, 404` | [27](ui/27-project-picker-open.png), [28](ui/28-add-project-dialog.png), [29](ui/29-add-project-bad-path.png), [30](ui/30-git-tab-hides-project-refusal.png); a valid folder: [31](ui/31-git-tab-with-project.png). navigate `/` → click "No projects" → "Add project..." → Name `x`, Directory `C:\does\not\exist` → Add. | P1 | #54 |
| PP-2 | The folder is a free-text field with no Browse button, even in the desktop host, where a folder dialog exists (`@tauri-apps/plugin-dialog`, already used by `dialogs/resume-body.tsx:26`). | `layout/project-picker.tsx:74` | [28](ui/28-add-project-dialog.png). navigate `/` → picker → "Add project...". | P3 | none |

**AgentsRoom:** "Project folder not found", shown inline.

## 7. Board, New ticket, ticket dialog

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| BD-1 | The Board subtitle "ticket → dev → staging → main" is this repo's own branch flow, hard-coded for every user and project. | `tabs/board-tab.tsx:74` | [32](ui/32-board-empty.png), [48](ui/48-after-setup-sidebar.png). navigate `/` → click Board. | P2 | #64 |
| BD-2 | Ticket cards show a hex short id where the rest of the product (and AgentsRoom) use #N, carry a "ticket" chip on every card, and don't clamp long titles. | `board/ticket-card.tsx:16-17, 68` | [36](ui/36-board-two-tickets.png). Board → New ticket twice (one with a long title) → screenshot. | P3 | none |
| BD-3 | Ticket types use lowercase internal names that differ from the usual words: "enhancement", "defect", "restructure", "upkeep", "cosmetic", "hardening"… | `board/ticket-dialog.tsx:20` | [35](ui/35-new-ticket-type-list.png). Board → New ticket → open Type. | P3 | none |
| BD-4 | With both side panels open at 1600 px, the lanes squeeze and Done is off-screen. | `tabs/board-tab.tsx:94`; `board/lane.tsx:80` | [36](ui/36-board-two-tickets.png). resize 1600×900 → Board → screenshot. | P3 | none |

Checked, OK: New ticket, its advanced section and the ticket dialog save and reopen correctly
([33](ui/33-new-ticket-dialog.png), [34](ui/34-new-ticket-advanced.png), [37](ui/37-ticket-dialog.png)).

**AgentsRoom:** tickets read as #42 everywhere.

## 8. Settings dialog

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| ST-1 | Changes apply at once, yet the footer offers Cancel beside Done, and Cancel only closes: switch to Light, press Cancel, and the app stays light. The description promises "notifications", a section that doesn't exist. The "Guided setup" label is tied to the button, so the button's accessible name is "Guided setup", not its text "Open guided setup". | `dialogs/dialog-host.tsx:18, 58-66`; `dialogs/settings-body.tsx:16, 18-28` | [38](ui/38-settings-dialog-appearance.png), [39 daemon](ui/39-settings-dialog-daemon.png), [40](ui/40-light-theme-after-settings-cancel.png). click the gear → Appearance → Light → Cancel → screenshot; snapshot: the button is named "Guided setup". | P2 | #58 |
| ST-2 | Chrome logs "Password field is not contained in a form" for the secret value field. | `settings/secrets-panel.tsx:97` (also `connect/connect-screen.tsx:166`) | [39 secrets](ui/39-settings-dialog-secrets.png). gear → Secrets → console_messages (verbose). | P3 | none |

Also in this dialog: "Protected by os." (CT-2, #67). In a browser the runner choice is hidden (section 20). About:
[39 about](ui/39-settings-dialog-about.png).

**AgentsRoom:** one Settings page with search. Changes apply at once and there is no Save button.

## 9. Stats

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| SS-1 | Stats opens on its Session tab with "No session selected. Select an agent to see its session's statistics.", but the modal covers the sidebar, so picking an agent means closing it first. The empty state sits at the top of a tall, blank box. | `stats/stats-views.tsx:29` | [41](ui/41-stats-dialog.png); the Tokens and Pace tabs render: [42](ui/42-stats-tokens.png), [43](ui/43-stats-pace.png). click the chart icon in the title bar → screenshot. | P3 | none |

**AgentsRoom:** no counterpart checked.

## 10. New agent

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| NA-1 | The steps read 1 → 3 until a role is picked: step 2 renders only once `draft.roleId` is set. | `dialogs/new-agent-dialog.tsx:118-119` | [44](ui/44-new-agent-dialog.png), [46](ui/46-new-agent-role-picked.png). click "New agent" in the sidebar → screenshot → pick a role → screenshot. | P3 | none |
| NA-2 | The role grid cuts a row in half with no sign that it scrolls. | `dialogs/new-agent-dialog.tsx:74-116` | [44](ui/44-new-agent-dialog.png). As NA-1. | P3 | none |

Checked, OK: "New agent" in the sidebar opens this dialog.

**AgentsRoom:** no counterpart checked.

## 11. Resume

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| RS-1 | Resume can't run in a window connected to a daemon (every browser window): its button is disabled outside `localMode`. Ctrl+R still opens this dead dialog instead of reloading the page, and the status bar keeps advertising "^R resume". | `layout/use-shortcuts.ts:54-55`; `dialogs/dialog-host.tsx:49, 71-76` | [45](ui/45-resume-dialog.png). navigate `/` → press_key Control+R → "Resume session" opens, the page doesn't reload, Resume is disabled. | P2 | #61 |

**AgentsRoom:** no counterpart checked.

## 12. Setup wizard

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| SW-1 | On a machine that is already set up, Guided setup jumps to step 4, "Say hello", which spawns a real agent the moment it mounts: no button, and no word on the provider, model or cost. In this run it started claude on **opus** (the first model listed). Each open spawns again. On the first open no agent appeared and the step sat on "Waiting for the reply..." with no timeout. A "My first project" starter project was added silently although `demo-repo` existed. | `setup/setup-wizard.tsx:200-223, 210-214, 238`; `app/packages/client/src/setup-wizard.ts:51-53, 83-88` | [47](ui/47-setup-wizard.png), [47b](ui/47b-setup-wizard-step4-autostart.png), [47c](ui/47c-setup-wizard-still-waiting.png), [48](ui/48-after-setup-sidebar.png), [49](ui/49-sidebar-after-first-agent.png), [50](ui/50-assistant-box.png). run_code_unsafe: log WebSocket frames → gear → Daemon → click `#set-setup` → a `session` frame (claude, opus) arrives with no click. | P2 | #57 |

**AgentsRoom:** no counterpart checked.

## 13. Assistant box

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| AB-1 | The box asks for the Assistant once, then shows "Starting the Assistant… If this stays empty, this daemon does not provide the Assistant yet." for as long as it is open. Its composer has no session, so it falls back to "Task for a NEW agent. Enter to spawn": a question typed here would spawn a generic agent. | `session/popout-host.tsx:21-41`; `session/composer.tsx:254-257` | [50](ui/50-assistant-box.png). click "Assistant" in the title bar → wait 10 s → screenshot. Don't press Enter. | P2 | #66 |
| AB-2 | With the box open, the page has two buttons named "Minimize" and two named "Close" (the window controls and the box), plus a third "Minimize" chip on the chat panel. A screen reader can't tell them apart. | `layout/window-controls.tsx:37`; `session/chat-popout.tsx:127`; `session/chat-panel.tsx:52` | [50](ui/50-assistant-box.png). click "Assistant" → snapshot. | P3 | none |

**AgentsRoom:** no counterpart checked.

## 14. Global workers modal

Checked, OK: the Working counter opens the lanes ([51](ui/51-working-counter-click.png)). SB-1 covers the counters.

## 15. Narrow widths

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| NW-1 | At 390×844 the window controls push the title bar to 439 px, so the whole page scrolls sideways and Maximize and Close sit off-screen. | `layout/title-bar.tsx:81` | [55](ui/55-width-390-phone.png). resize 390×844 → navigate `/` → evaluate `document.documentElement.scrollWidth`: 439. | P2 | #60 |
| NW-2 | At 390 px key hints such as ^N still show. `KeyHint` has no pointer check, although the layout already reads `(pointer: coarse)`. | `app/packages/ui/src/blocks/key-hint.tsx:6`; `app/shell/src/state/ui-state.tsx:120` | [55](ui/55-width-390-phone.png). As NW-1. | P3 | none |

At 1024 px the session header wraps (SV-3).

## 16. Offline

TB-3 (raw close code, #67), RP-2 (Usage blames the daemon, #63) and SV-4 (the composer looks enabled).

Checked, OK: the reconnect backoff caps at 8 s ([57](ui/57-offline-2s.png), [58](ui/58-offline-11s.png)).

## 17. Stress and performance

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| PF-1 | With `?stress=1` (6 busy synthetic sessions, about 60k transcript items) the HUD reads 27 to 28 fps, with a worst frame of 107 ms. | `layout/perf-overlay.tsx` (the HUD); the stress source in `app/shell/src/daemon/daemon.ts` | [56](ui/56-stress-populated.png), [60](ui/60-stress-perf-hud.png). navigate `/?stress=1` → wait 10 s → read the HUD. | P3 | none (see #34, #19) |

Checked, OK: the transcript is virtualised (about 1.1k DOM nodes under that load, read with
`document.getElementsByTagName('*').length`), and opening a session takes under 60 ms.

## 18. Light theme

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| LT-1 | The red used for text (#dc2626 on #f4f5f7) measures 4.43:1, under WCAG AA's 4.5:1, and it is used on small text. Key hints measure 3.58 to 3.81:1, and many labels are 10 to 10.5 px. | `app/packages/ui/src/styles.css:64, 72, 87`; `app/packages/ui/src/blocks/key-hint.tsx:6` | [40](ui/40-light-theme-after-settings-cancel.png). gear → Light → evaluate: read the rendered colours of an active tab label and a key hint against the background. | P2 | #62 |

## 19. Cross-cutting: styling and accessibility

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| GS-1 | Red does two jobs: brand accent and danger. A switch that is on, the Board count, the active role chip, New ticket and the attention count are all red, so the danger red stops meaning "careful". | `app/packages/ui/src/components/switch.tsx:19` (`data-checked:bg-primary`); TB-2, SB-3 | [38](ui/38-settings-dialog-appearance.png), [36](ui/36-board-two-tickets.png). gear → Appearance → screenshot. | P3 | none |

Accessibility items elsewhere in this report: ST-1 (a label that renames its button), RP-3 (a tablist with no name),
AB-2 (duplicate button names) and LT-1 (contrast, 10 px text).

### Styling wins (cheap, same look)

1. Keep the danger red for destructive actions and errors only; move on, active and count states to a neutral or info
   token (GS-1, TB-2, SB-3, SV-5).
2. Give the active tab a fixed width, with no bold shift, and add an overflow menu (SV-1, RP-1).
3. Put each empty state's first action inside it (CT-4).
4. Darken the light-theme text tokens to pass 4.5:1 (LT-1).
5. Show tickets as #N, clamp titles to two lines and drop the "ticket" chip (BD-2).
6. One border per container in Mail (CT-7).

## 20. Host coupling: Tauri calls in a plain browser

The UI calls the desktop host directly: 16 `@tauri-apps/*` import sites in 10 files, each guarded by its own `isTauri`
check, or not at all. In a browser:

| Where | Host call | In a plain browser |
|---|---|---|
| `layout/window-controls.tsx:5-6` | `isTauri`, `getCurrentWindow` | renders and does nothing (TB-1, NW-1) |
| `layout/use-shortcuts.ts:54-55`, `dialogs/dialog-host.tsx:49` | Resume needs the embedded runner (`localMode`) | Ctrl+R opens a dead dialog (RS-1) |
| `dialogs/resume-body.tsx:26` | `plugin-dialog` `open` (folder picker) | unreachable: Resume is disabled |
| `app/shell/src/daemon/local-transport.ts:6, 19, 26, 49-50` | `local_connect`, `local_send`, `local_set_project`, `local_resumable` | no embedded runner; local mode stays off |
| `app/shell/src/daemon/daemon.ts:58-59` | `endpoint` | guarded; the browser takes `?daemon=` or the dev port |
| `dialogs/settings-body.tsx:35-44` | `ui_runner_get`, `ui_runner_set` | guarded; the runner choice is hidden |
| `settings/desktop-install.tsx:15-16, 40` | install and update commands, update events | guarded; the card is hidden |
| `connect/connect-screen.tsx:13-14, 20, 95, 190-191` | `remote_save`, `remote_forget`, the barcode scanner | QR pairing and saved remotes are host-only |
| `layout/app-version.ts:11, 20-23` | the app version | shows "web client" |
| `app/shell/src/daemon/perf-scenario.ts:8, 15` | `perf_report` | the call fails and is swallowed |
| `app/shell/src/daemon/tauri-transport.ts:5` | `plugin-websocket` | unused; the browser uses `WebSocket` (`app/packages/client/src/connection.ts`) |

| # | Finding | Where | Evidence and Playwright steps | Pri | Ticket |
|---|---|---|---|---|---|
| HC-1 | There is no host seam. One `host` interface in `app/packages/client` that says what the host can do (window, folder picker, endpoint, version, pairing, runner, local sessions), with a Tauri and a web implementation, would let components ask "can the host do X" and hide or adapt the control. TB-1 and RS-1 are where the missing seam shows today. A seam move needs the owner's go, so this is not filed; the PM is taking it to the owner (2026-11-20). | the 10 files above | [01](ui/01-first-load-1600.png), [45](ui/45-resume-dialog.png), [10](ui/10-tab-skills.png) ("web client" in the status bar). navigate `/` → evaluate `typeof window.__TAURI_INTERNALS__`: "undefined". | P3 | none (related #36) |

## Code-only notes (not counted: not shown in this run)

- Owner messages in the transcript (`CommsCard`) use the danger tint: `border-danger/50 bg-danger/15`, with a
  `bg-danger/25` header (`session/transcript-items.tsx:88-89`). No owner mail arrived in this run, so it was not seen.
  It belongs with GS-1.
- The stripe animations honour `prefers-reduced-motion` (`app/packages/ui/src/styles.css:321-327`): OK.

## Ticket index

All tickets are `pending`, role frontend, linked to #3 (relates_to). Each carries screenshots, Playwright repro steps,
acceptance criteria ending "verified end to end with the Playwright MCP on the live preview", and an evidence bar
(before and after screenshots, `pnpm test` and `pnpm typecheck` in `app/`, the components reused).

| Ticket | Findings | Pri | Also linked |
|---|---|---|---|
| #54 UI-PROJECT-ADD-1: Add project fails silently when the daemon refuses the folder | PP-1 | P1 | |
| #55 UI-TABS-OVERFLOW-1: hidden centre and side-panel tabs have no affordance | SV-1, RP-1 | P1 | |
| #56 UI-TABS-ZERO-PLUGINS-1: a daemon with no plugins shows every plugin tab | CT-1 | P2 | |
| #57 UI-SETUP-HELLO-1: guided setup spawns a real agent on open and can wait forever | SW-1 | P2 | #51 |
| #58 UI-SETTINGS-DIALOG-1: Settings Cancel keeps changes; description promises notifications | ST-1 | P2 | |
| #59 UI-SETTINGS-LOADING-1: Settings tab says "Loading settings…" forever when config_get is refused | CT-3 | P2 | #10 |
| #60 UI-WINCTRL-WEB-1: window controls show in a browser: dead, and overflow a phone screen | TB-1, NW-1 | P2 | |
| #61 UI-RESUME-WEB-1: Ctrl+R opens a dead Resume dialog in a browser instead of reloading | RS-1 | P2 | #36 |
| #62 UI-LIGHT-CONTRAST-1: light theme red text and key hints fail WCAG AA contrast | LT-1 | P2 | |
| #63 UI-USAGE-STATES-1: Usage panel offline and unknown states are wrong or raw | RP-2 | P2 | |
| #64 UI-BOARD-FLOW-1: Board subtitle hard-codes this repo's branch flow in the product | BD-1 | P2 | |
| #65 UI-END-SESSION-1: End session is a solid red trash button that kills at once | SV-5 | P2 | |
| #66 UI-ASSISTANT-BOX-1: Assistant box waits forever and its composer spawns a new agent | AB-1 | P2 | |
| #67 UI-COPY-1: protocol names and raw values in user-facing text | CT-2, TB-3 | P2 | |

Related tickets that already existed (no duplicates filed): #10 BUG-CONFIG-1, #14 BUG-NEEDS-INPUT-1, #19 PERF-STATS-1,
#26 BUG-TOKEN-CHIP-1, #29 GAP-HARNESS-INSTALL-1, #34 PERF-TRANSCRIPT-PAGE-1, #35 GAP-PROJECT-EDIT-1,
#36 ARCH-TAURI-LOCAL-1, #51 ONBOARD-1.
