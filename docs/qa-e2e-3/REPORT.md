# QA-E2E-3 report (dist dev-6e0c1f9c, contract 1.141.0)

Desktop: InfantryAI.exe, state dir temp, port 18991 (owner's 8797 untouched). Log: `[plugins] ... 25 official module(s) load`, all 25 `Infantry_*: loaded`, no ERROR lines. Window stayed open, OS screenshot docs/qa-e2e-3/desktop-screen.png.
Web: offline demo served by the desktop daemon (UI is canvas/wasm). Static `python http.server` on web\ FAILS (see bug 1).
Legend: Y = seen, - = not checked. "Interaction" = what I clicked.

| plugin | loaded | drawn | interaction | errors |
|---|---|---|---|---|
| Cli | Y | Y (CLI tab, chat + comms) | tab switch | none |
| Agents | Y | Y (left list, filters, 3 agents in demo) | none beyond render | none |
| Settings | Y | Y as Configuration dialog (gear), NOT as a tab | 6 sections: Appearance, General, Agents shown; Notifications shown; Devices, About NOT seen | none |
| Git | Y | Y ("Select a project") | tab | none |
| Run | Y | Y (Add command, Start/Stop disabled) | tab | none |
| Notes | Y | Y ("Select a project") | tab | none |
| Changes | Y | Y (right) | tab | none |
| Prompts | Y | Y but "Loading prompts..." never ends (6s+) in demo | click | demo stuck, bug 2 |
| Skills | Y | Y but "Loading skills..." never ends in demo | click | bug 2 |
| Usage | Y | Y (Plan limits, Pacing, Observed, per-agent) | tab, Refresh not pressed | none |
| Stats | Y | Y, but only "daemon does not report session statistics, contract 1.92.0" (demo is 1.0.0); NO usage meter seen | tab | not verified on real daemon |
| Epics | Y | Y ("Select a project") | tab | none |
| Review | Y | Y (empty state) | tab | none |
| Pods | Y | Y (submit form, load by id) | none typed | none |
| Profiles | Y | Y (empty, + New profile) | tab | none |
| Triggers | Y | Y (empty, + New trigger) | tab | none |
| Attention | Y | Y (empty state) | tab | none |
| Roles | Y | Y ("The daemon sent no roles" in demo) | tab | none |
| Board | Y | Y overlay, 5 columns | New ticket form opened (assignee, epic, Create disabled when empty) | none |
| Alerts | Y | Y via Settings > Notifications (Spoken alerts, Incoming comms sound) | scrolled view only | none |
| Processes | Y | footer badge not identified | - | - |
| Header | Y | Y (title, project picker, avatars, connection) | project picker not opened | none |
| Chat | Y | Y (centre chat, composer, comms) | not sent | none |
| Approvals | Y | questions pill NOT seen (no open question) | - | not tested |
| Assistant | Y (desktop) | Y desktop header "Assistant" button; absent in web demo header | not opened | none |

Console (web): one 404 (static server only), WebGL warnings, 401/403 on /attention, /update/status, /node-config/seed in the demo (demo has no auth to the daemon: expected noise).

## Bugs
1. web\ in the dist is not self-contained: index.html loads agentHqWeb.mjs but the folder holds agentHqWeb.js (static server -> "Infantry AI could not start", 404). The daemon serves it (200).
2. Offline demo: Prompts and Skills panes stay on "Loading..." forever.
3. Settings plugin declares tab infantry.settings (center) but no Settings tab is drawn; only the gear dialog exists. Confirm intended.

## NOT covered
Real-daemon web login (needs AGENTHQ_TOKEN, owner.token is refused), question_ask panel, Stats usage meter, Settings Devices/About, Header/Board children with real data, desktop tab-by-tab clicks (desktop shows same tab set, only the first screenshot taken), Processes badge, Approvals pill, Alerts sound, Android.
