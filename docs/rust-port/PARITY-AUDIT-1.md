# Kotlin vs Rust parity audit (PARITY-AUDIT-1, 2026-11-27)

What the Kotlin build still does that the Rust build does not, ranked by owner impact (daily use first), with one
backlog ticket per gap (project-73b27dff, epic epic-e574a380). Read-only audit of commit `67e213487`: nothing was fixed.

It supersedes the gap table in [PARITY-GAPS.md](../archive/rust-port/PARITY-GAPS.md) (RUST-AUDIT-1, 2026-11-19). Every
row of that table was re-checked against the current code; most are now done (section 4).

## Method and limits

- **Sources.** Kotlin: `plugins/official/*` (daemon and UI halves, `module.json`), `superiorSoftLibrary/infantry` (daemon,
  CLI, MCP), `infantryCore`, `infantryBoard`, `infantryProtocol`, `infantryUiCore`, `infantryAndroid`. Rust: `rust/crates/*`,
  `rust/plugins/official/*`, `app/shell/src` (tab gates in `app/shell/src/plugins/catalog.tsx`), `app/packages/*`,
  `app/src-tauri` (incl. `gen/android`).
- **Frames.** All 301 Kotlin frame names (`Protocol.kt`) are in `rust/crates/infantry-protocol/src/frames.rs`. Each name
  was then searched for a daemon-side handler or sender (the CLI, conformance, tests and the script alias table
  `dispatcher.rs` do not count).
- **Tools.** Quoted `infantry_*` names in Kotlin tool code and `module.json` against the Rust tool catalogs. A name that
  appears in Rust only in the script alias table is **not** implemented: the dispatcher resolves only names in the live
  catalog (`rust/crates/infantry-scripting/src/dispatcher.rs:212,263`).
- **CLI.** A word missing from `COMMANDS` (`rust/crates/infantry-cli/src/cli/mod.rs:187-193`) is "unknown command"; a
  word in `COMMANDS` but not in the registry falls back to the Kotlin launcher (`mod.rs:367-376`). Launcher-only counts as
  a gap: it fails on a machine with no JVM launcher.
- **Settings.** Key names diffed between `LimitRegistry.kt` (and Kotlin `module.json`/`ui.json` `config`) and everything
  under `rust/` and `app/`. A key that appears in Rust may appear only in a comment, so the counts are upper bounds.
- **Not run:** a live frame probe. A static scan can miss a frame name built at run time. Every ticket tells its worker
  to confirm the gap live on a test daemon first.
- **Paths.** `K:` = `plugins/official/`, `KD:` = `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/`,
  `UC:` = `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/`, `rd:` =
  `rust/crates/infantry-daemon/src/`, `S:` = `app/shell/src/`.

## 1. Ranked gaps (one ticket each)

Rank = owner impact: what breaks or is missing in daily use first, then automation and settings, then rarely used
surfaces. "Open" = an existing ticket already covers it (linked, not refiled).

| # | Gap | Kotlin evidence | Rust today | Ticket |
|---|---|---|---|---|
| 1 | `ticket_files` / `ticket_files_report` frames and tools `infantry_ticket_files`, `infantry_ticket_related_files`: every worker's first step (`ticket files --related`) answers "This feature isn't available" | `K:Infantry_Board/daemon/.../TicketFilesTools.kt:25-26`; `KD:daemon/DaemonMain.kt:3954` | CLI only, `rust/crates/infantry-cli/src/cli/cmd_ticket.rs:1405-1437`; no handler (`frames.rs:143` only) | open **4d762769** (its archive half is done) |
| 2 | Bus events never published: `daemon.started`, `session.started/idle/turn_started/turn_ended/mcp/woken/ended`, `mail.sent`, `progress.failed`. The bundled resume-after-restart hook can never run | `KD:daemon/plugins/DaemonEventPublisher.kt:40-73`; `KD:daemon/plugins/DaemonPluginHost.kt:1506` | `rd:compose/adapters.rs:722-723` (only `session.run_ended`/`exited`); hook waits on it at `rust/crates/infantry-scripting/builtin-scripts/builtins.json:5` | **e9f0c5ff** |
| 3 | Board tools `infantry_ticket_epic`, `_assign`, `_unassign`, `_delete`; `infantry ticket epic` is broken without the first | `K:Infantry_Board/daemon/.../TicketEpicTool.kt:136,168`; `TicketMoveTools.kt:150,203,249` | frames exist (`rust/crates/infantry-board/src/unit.rs:30,58-59,160-161,190`); not in `tools.json`; CLI calls it at `cmd_ticket.rs:50,269` | **ef379c3a** |
| 4 | `runner_admin` ops `pair`, `pair_preview`, `pair_status`, `rotate`, `connect` (and `test`): the CLI sends them, the daemon says "unknown op" | `KD:runners/RunnerLinkService.kt:224-286`; `KD:cli/RunnerCli.kt:72-209` | `rust/crates/infantry-runner-pool/src/admin.rs:298-307` (add, list, check, detail, detach, refresh, update, update_status) | **01ccbdc6** (+ open 5350befb for `test`, linked) |
| 5 | Desktop OS notifications (milestones reach the owner when the window is behind) | `K:Infantry_Notify/src/Notifier.kt:12` | `S:components/layout/toast-host.tsx:5,41` (in-app toast + sound only) | **72506574** |
| 6 | Android system notifications: permission, channels, tap opens ticket, `notify_pending` replay, opt-in background connection | `K:Infantry_Notify/androidSrc/AndroidNotifier.kt:76,106-121`; `NotifyBackgroundService.kt:56-70` | no `POST_NOTIFICATIONS` in `app/src-tauri/gen/android/app/src/main/AndroidManifest.xml`; daemon serves `notify_pending` (`rust/crates/infantry-mail/src/notify/push.rs:20`), app never asks | **ef684a17** |
| 7 | Ticket dialog executor section (role / provider / model / weight / effort pin) | `K:Infantry_Board/src/TicketExecutorField.kt:33` | `S:components/board/ticket-dialog.tsx:44` (title, description, type, epic) | **071652c5** |
| 8 | Chat: agent to-do box, report-back chip, Start/Resume in the empty pane, message-action slot | `UC:TodoBox.kt:44`; `UC:ReportFlagChip.kt:32`; `K:Infantry_ChatFeed/src/SessionStartPane.kt:32`; `MessageActions.kt:35` | todos parsed, never drawn (`app/packages/client/src/wire.ts:130`); `S:components/session/transcript.tsx:183` | **f6aede72** |
| 9 | Board: related-tickets block, runner badge, multi-select Move to epic, waiting chip, drop a session on the board | `K:Infantry_Board/src/RelatedView.kt:10`, `RunnerBadge.kt:20`, `EpicMoveUi.kt:38,66`, `WaitingCard.kt:16`, `BoardDropSession.kt:19` | none in `S:components/board/` (`board-dialog.tsx:109` ticket drag only) | **25a80505** |
| 10 | Tools `infantry_session_read`, `infantry_progress`, `infantry_capability_call`, `infantry_event_emit` (+ `emit` alias), `infantry_exec` `progress` arg | `K:Infantry_Scripting/daemon/.../SessionTools.kt:172`; `K:Infantry_Agents/daemon/.../ProgressTool.kt:31`; `KD:daemon/plugins/DaemonPluginHost.kt:1504,1534`; `JobProgress.kt:12` | alias rows only (`dispatcher.rs:22-40`); `rd:compose/tools.rs:234` (capability_list only); `rust/crates/infantry-scripting/src/exec.rs:255-283` | **f97a497b** |
| 11 | Broken native CLI: `project find/config/routing/branches/rename/base` refused; `prompt` unreachable | `KD:cli/CliMain.kt:2117-2127` | `rust/crates/infantry-cli/src/cli/cmd_project.rs:14-22`; `registry.rs:32` vs `mod.rs:187` | **3a05fa36** |
| 12 | Calendar (tab hidden, no backend) | feature-calendar branch (63f43de31 keeps it gated) | `S:plugins/catalog.tsx:89`; no calendar code in `rust/` | open **ba847393** |
| 13 | Chat settings cards: activity box, long chats (page size), chat cards, reasoning panel mode, markdown engine | `K:Infantry_ChatFeed/module.json:21,40,62`; `K:Infantry_Chat/module.json:65,80`; `UC:ReasoningPanelUi.kt:56` | Settings shows only `daemon.config` cards (`rd:compose/config.rs:26`); reasoning inline only (`S:components/session/transcript-items.tsx:14`) | **b3990bf1** |
| 14 | Appearance: accent colours, blur, avatar style (Mascot), owner portrait | `K:Infantry_Appearance/module.json:21,72,94,119`; `K:Infantry_Mascot/src/MascotPlugin.kt:27` | `S:components/dialogs/settings-body.tsx:93` (dark/light only) | **853d0027** |
| 15 | Notification tools `infantry_notification_post`, `infantry_notice_suppress`, `infantry_notify_milestone` | `K:Infantry_Scripting/daemon/.../NotificationTools.kt:83`; `SessionTools.kt:174`; `K:Infantry_Notify/.../NotifyDaemonPlugin.kt:149` | none (`infantry_notify` itself is done) | open **3218f914** |
| 16 | Limits registry and `limit_catalog` (Settings > Limits, `infantry settings limits`): 157 of 222 `limits.*` keys absent | `infantryCore/.../settings/LimitRegistry.kt`; `SettingsService.kt:300` | no handler (`frames.rs:206` only; CLI asks at `cmd_runner.rs:318`, `usage_settings.rs:371`); `rust/crates/infantry-vault/src/limits.rs:16` | open **8eeaf932** |
| 17 | Composer: permission mode in the status line, composer plugin slot | `UC:model/ToolApprovalSetting.kt:12`; `K:Infantry_Chat/module.json:40` | `S:components/session/composer.tsx:398` passes `null` | open **5be0e4c8** (attachments and force-send are now done) |
| 18 | "Compacting" status on a session | `K:Infantry_ChatFeed/src/ChatFeedSurface.kt:668` | none in `S:` / `app/packages/client` | open **580d3a15** |
| 19 | Attention: questions chip opens the asking chat; full question history | `K:Infantry_Approvals/src/ShellQuestionPill.kt:19`; `K:Infantry_Attention/src/QuestionHistory.kt:56` | `S:components/layout/title-bar.tsx:55`; `app/packages/client/src/stores/attention.ts:117` (7 days, in Mail) | **02fc5a4f** |
| 20 | Base-branch picker | `K:Infantry_Git/src/ProjectGitPane.kt:417` | `S:components/layout/project-picker.tsx:270` (read-only) | **4a932c76** |
| 21 | Launcher-only CLI, board/agent/run: submit, watch, pods, runs, logs, cancel, archive, agent, agent-def, epics, epic, processes, bg, image, harnesses, harness, ask | `KD:cli/CliMain.kt:342,504-534,554` | not in `rust/crates/infantry-cli/src/cli/registry.rs:17-51` | **2a8c2c5b** |
| 22 | Launcher-only CLI, knowledge/admin: knowledge, memory, decision, notify, event, card, docs, account, tool, mcp (non-serve), trait, skill, role, login, logout, whoami | `KD:cli/CliMain.kt:304,537-553` | same; `rust/crates/infantry-cli/src/main.rs:122` (`mcp serve` only) | **3486b833** |
| 23 | Watch hooks (`POST /watch/rewrite/{harness}`) and CLI `run-watched` / `unwatched` / `watch-file` ("unknown command", yet listed in Rust help `usage.rs:86-89`) | `KD:daemon/WatchHooks.kt:162`; `CliMain.kt:313-316` | `rust/crates/infantry-harness-claude/src/hooks.rs:27-29` (constants only, no route) | open **9fef3754** |
| 24 | Script capabilities `http.fetch`, `http.download`, `progress.start/update`; `scripting.maxSteps/timeoutMs/hooks.enabled/hooks.maxRuns` not honoured; engine seam | `K:Infantry_Scripting/daemon/.../ScriptingDaemonPlugin.kt:146-153,179-188`; `module.json:82-117` | none in `rust/crates/infantry-scripting`, `infantry-plugin-host` (only `http.send`, `imports.rs:428`) | **5b23e210** |
| 25 | Verification pipeline: `pipeline_config/show/record/check/state`, tools `infantry_pipeline`, `_record`, Git pane section | `K:Infantry_Board/daemon/.../PipelineHandler.kt:31`; `K:Infantry_Git/src/ProjectPipelineSection.kt:57` | frame names only (`frames.rs:292-294`) | open **200b9f6b** |
| 26 | Notification settings: per-milestone sound picker, `notify.background`, `notify.soundList`, incoming-comms sound | `K:Infantry_Notify/module.json:18-54`; `UC:sound/IncomingCommsSoundUi.kt:56` | `S:components/settings/notifications-card.tsx:45` (wake rules, held notices) | open **336e0091** |
| 27 | Diff modal from Changes and from the chat change box | `K:Infantry_Changes/src/DiffModal.kt:87` | `S:components/git/git-tab.tsx:429` (inline diff only) | open **aa48feb9** (Changes panel itself is done) |
| 28 | Model / role allowlist and sandbox mode | `infantryCore/.../settings/ModelAllowlist.kt:38,43`; `LimitRegistry.kt:742` | none in `rust/` | **ffb72352** |
| 29 | Policy presets, per-action gating (ActionCatalog / FrameGate; e.g. `board.archive.write` not enforced) | `infantryCore/.../policy/PolicyProvider.kt:14`; `ActionCatalog.kt:152` | `rd:wire.rs:57-61` (one fixed preset) | open **d248f12d** |
| 30 | `tool_approval_set` (prompt timer, expiry) and its settings card | `KD:daemon/DaemonMain.kt:4189`; `UC:model/PromptTimerSetting.kt:16` | no handler | open **905d089f** |
| 31 | Runner settings: `runners.cloneRoot`, `onLost`, `runner.gitName/gitEmail`, `runners.default`, `runners.update.*`, 16 `limits.runners.*` | `infantryCore/.../settings/RunnerLinkSettings.kt:21-67`; `LimitRegistry.kt:195` | `rd:compose/settings.rs:28-33` | **ea46d69b** (+ open fe4ee2eb for maxWorkers) |
| 32 | Claude harness settings `harness.claude.mode`, `authMode`, `userMcpServers` | `K:Infantry_Claude/module.json:15-21` | none in `rust/` | **089ba75f** |
| 33 | Desktop server switcher, daemon update section in About, `update.waitForWorkers/resumeWorkers` | `UC:ServerSwitcherUi.kt:67`; `UC:ServerUpdateSection.kt:23`; `K:Infantry_Updater/module.json:42-45` | `S:components/connect/connect-screen.tsx` (phone pairing only) | **9e1d4a07** |
| 34 | Scoped settings (`context.*`, `related.*`, `session.keepAliveMinutes`, `mail.wakeMode/deliveryMode/automationFooter`, `prompts.*`, `questions.*`, `tickets.exitBehavior`, `display.timeZone` ...): on Rust most are not built at all | `infantryCore/.../settings/SettingsService.kt:1025-1116` | doc comments only (`rust/crates/infantry-mail/src/mail/router.rs:71`) | open **b6096072** (written against Kotlin; retarget to Rust) |
| 35 | HTTP config routes `/config/schema`, `/config/values`, `/config-manifest`, `/node-config`, `/node-config/seed` | `KD:daemon/ConfigManifestHttp.kt:77-96`; `NodeConfigHttp.kt:85-93` | `rd:server.rs:552-558` | open **32497179** (`config_get/set/values/changed/schema` frames now done) |
| 36 | MCP tab: add, remove, test a server | `K:Infantry_Mcp/src/McpModel.kt:33-37` | `S:components/panels/service-panels.tsx:50,66` (enable, assign, revoke) | **ff63d5e0** |
| 37 | Android `infantry://connect` deep link; `allowBackup=false` | `superiorSoftLibrary/infantryUiCore/src/androidMain/AndroidManifest.xml:23`; `superiorSoftLibrary/infantryAndroid/src/main/AndroidManifest.xml:16` | Tauri manifest: LAUNCHER filter only, no allowBackup | **fbd2882c** |
| 38 | `process_pressure` push and `bg_job` request frame | `KD:daemon/DaemonMain.kt:1012,4404` | pressure flag in `rd:run/mod.rs:45`, no sender; `rust/crates/infantry-mail/src/mail/bgjob.rs:3` mails instead | open **c7d4f4ff** |
| 39 | News / Ticker chat-card renderers; Job feed box UI | `K:Infantry_News/src/NewsCardRenderer.kt:105`; `K:Infantry_Ticker/src/TickerCardRenderer.kt:89,135`; `K:Infantry_JobFeed/src/JobFeedBox.kt:67` | `S:components/session/chat-cards.tsx:20` (`registerChatCard` never called) | **97512f1b** |
| 40 | News daemon half (`news.headlines`); Browser daemon half (Playwright MCP) | `K:Infantry_News/daemon/.../NewsDaemonPlugin.kt:35`; `K:Infantry_Browser/daemon/.../BrowserDaemonPlugin.kt:19` | none | open **a9890b41** (its Approvals half is now done) |
| 41 | Settings sections: Limits, Logs folder, Test question, Modules; write policy later | `UC:ConfigTabs.kt:51-71`; `UC:ConfigPolicySection.kt:42` | none in `S:components/dialogs/settings-body.tsx` | **3575ca5a** |
| 42 | Slash `command_result` frame | `KD:daemon/SlashCommands.kt:106` | `rd:sessions.rs:995` (`command_catalog` only) | **f18d4d40** |
| 43 | HTTP routes `/plugins`, `/update/status`, `/update/check`, `/update/apply`, `/update/client` | `KD:daemon/DaemonMain.kt:2462`; `K:Infantry_Updater/daemon/.../UpdaterDaemonPlugin.kt:116-119` | none (`rd:os_app.rs:34` `/prepare-update` only) | open **c8fc1ac3** (`/mail/thread/{id}` is now done) |
| 44 | Voice: spoken alerts (`spoken` push), mic input, Voice settings (desktop and Android) | `UC:voice/VoiceUi.kt:51`; `KD:notify/Notify.kt:66`; Android `ui/voice/AndroidSpeaker.kt:14` | `SPOKEN` only in `frames.rs:298`, no sender or client | open **18e3c07c** |

New tickets filed by this audit: 26. Gaps covered by an open ticket (linked, not refiled): 18.

## 2. Feature-area table

Status for every Kotlin area. "Done" means the Rust build has it; the row number points at section 1 for a gap.

### Tabs, panels, header

| Area | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| CLI, Mail, Pods, Roles, Profiles, Epics, Notes, Skills, Scripts, Run, Git, Processes, Health, Plugins, Services tabs | `K:*/module.json` `tabs` | done | `S:plugins/catalog.tsx:83-100` |
| Attention, Usage, Prompts, Changes, MCP, Review, Skills (right panel) | same | done (MCP partial: #36) | `S:plugins/catalog.tsx:103-111` |
| Calendar | feature-calendar branch | missing (#12) | `S:plugins/catalog.tsx:89` |
| Triggers tab, Runners card | `K:Infantry_Triggers/src/Triggers.kt:110`; `K:Infantry_Runners/src/RunnersCard.kt` | done (web UI plugins) | `rust/plugins/official/Infantry_Triggers/ui/src/triggers-tab.tsx`; `Infantry_Runners/ui/src/runners-card.tsx` |
| Title bar (project picker, agent strip, attention chip) | `K:Infantry_Header/src/TitleBar.kt:89,207,239` | done | `S:components/layout/title-bar.tsx:16,40,55` |
| Questions pill, question history | `K:Infantry_Approvals/src/ShellQuestionPill.kt:19` | partial (#19) | `S:components/layout/title-bar.tsx:55` |
| Processes badge, update notice / app self-update | `K:Infantry_Processes/src/ProcessesBadge.kt:58`; `K:Infantry_Updater/src/UpdaterModule.kt:34` | done | `S:components/layout/status-bar.tsx:24,49` |
| Daemon update section, server switcher | `UC:ServerUpdateSection.kt:23`; `UC:ServerSwitcherUi.kt:67` | missing (#33) | none |
| Assistant popup | `K:Infantry_Assistant/src/AssistantPopup.kt:212` | done | `S:components/session/popout-host.tsx:39` |
| Stats / Pace (modal) | `K:Infantry_Stats/src/StatsModule.kt:122`; `K:Infantry_Pace/src/PaceSessionsPanel.kt:34` | done (check the "who spends now" ranking in the app) | `S:components/stats/stats-dialog.tsx:18,44` |
| Setup wizard, device pairing / QR, Secrets, plugin install consent, per-plugin settings cog | `UC:shell/blocks/ShellSetupWizard.kt:59`; `UC:ConfigTabs.kt:66-67`; `K:Infantry_Settings/src/PluginConsentDialog.kt:42` | done | `S:components/setup/setup-wizard.tsx`; `S:components/settings/desktop-install.tsx:271`; `S:components/plugins/plugin-manager.tsx:71-75` |
| Board archive view, lane collapse | `K:Infantry_Board/src/ArchiveView.kt:40`; `LaneCollapse.kt` | done | `S:components/board/archive-dialog.tsx:3`; `lane.tsx:76` |
| Board related / runner badge / move to epic / waiting chip / drop session | see #9 | missing (#9) | none |
| Ticket executor field | see #7 | missing (#7) | none |
| Base-branch picker; pipeline section | see #20, #25 | missing | none |
| Changes panel; diff modal | `K:Infantry_Changes/src/ProjectChangesPanel.kt:245`; `DiffModal.kt:87` | panel done; modal partial (#27) | `S:components/git/git-tab.tsx:396,429` |

### Chat

| Area | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| Chat window / pop-out, tool-approval prompts | `K:Infantry_ChatFeed/src/ChatWindow.kt:31`; `QuestionPanel.kt` | done | `S:components/session/chat-popout.tsx`; `prompt-cards.tsx:15` |
| Composer: any-file attachments, mid-turn force | `K:Infantry_Chat/src/ChatAttachmentViews.kt:55` | done | `S:components/session/composer.tsx:185,340` |
| Composer: permission mode, plugin slot | see #17 | missing (#17) | `composer.tsx:398` |
| To-do box, report-back chip, start pane, message actions | see #8 | missing (#8) | none |
| Reasoning panel mode; chat settings | see #13 | partial (#13) | `transcript-items.tsx:14` |
| Compacting status | see #18 | missing (#18) | none |
| News / Ticker cards; Job feed box | see #39 | partial / missing (#39) | `chat-cards.tsx:20` |
| Mascot avatars; appearance | see #14 | missing (#14) | `S:components/agents/pixel-bot.tsx` |
| OS notifications; sounds | see #5, #26 | partial | `toast-host.tsx:5,41` |
| Voice | see #44 | missing (#44) | none |

### Daemon units and frames

| Area | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| Agent registry edits `agent_save/delete/swap/roster/switch` | `KD:daemon/DaemonMain.kt:4018` | done | `rd:compose/agent_frames.rs:33`; `infantry-board/src/unit.rs:61` |
| Config frames `config_get/set/values/changed/schema` | `DaemonMain.kt:4263` | done | `rd:compose/config.rs:131,142,161` |
| `limit_catalog`, config HTTP routes | see #16, #35 | missing | none |
| Harness install, CLI locations | `DaemonMain.kt:4147` | done | `rd:compose/harness_install.rs:26`; `cli_locations.rs:116` |
| Project git detail frames (`project_git_*`) | `K:Infantry_Git/daemon` | done | `rust/crates/infantry-projects/src/units.rs:254-293` |
| Archive `archive_query/apply/report` + tools | `K:Infantry_Board/daemon/.../ArchiveTools.kt:26-28` | done | `infantry-board/src/unit.rs:37,221`; `archive.rs:24` |
| Ticket files | see #1 | missing (#1) | none |
| Pipeline | see #25 | missing (#25) | none |
| Stories `story_*` | `infantryBoard/.../backlog/BoardFrames.kt:111` | not a gap: deprecated (CONTRACT.md:1284), 7b820cea closed obsolete | n/a |
| Triggers, image upload / fetch | `K:Infantry_Triggers/daemon/.../TriggersDaemonPlugin.kt:118-152`; `K:Infantry_Chat/daemon/.../ChatDaemonPlugin.kt:63-64` | done | `rust/plugins/official/Infantry_Triggers/daemon/src/lib.rs:323,373`; `Infantry_Chat/daemon/src/lib.rs:8` |
| Approvals rules | `K:Infantry_Approvals/daemon/.../ApprovalsDaemonPlugin.kt:25-26` | done | `rust/crates/infantry-mail/src/notify/units.rs:141,171` |
| `tool_approval_set` | see #30 | missing | none |
| Sites daemon half | `K:Infantry_Sites/daemon/.../SitesDaemonPlugin.kt:78` | done as a closed-source sidecar contract (a5d5a192 done); plan / billing parity is open 3cd73c6f | `rust/plugins/official/Infantry_Sites/module.json:11` |
| News, Browser daemon halves | see #40 | missing | none |
| `process_pressure`, `bg_job` | see #38 | missing | none |
| `command_result` | see #42 | missing | none |
| `spoken` | see #44 | missing | none |
| Watch hooks route | see #23 | missing | none |
| Runner admin ops | see #4 | partial | `admin.rs:298-307` |
| Cards, Docs, Notes, Roles, Skills, Prompts, JobFeed (daemon), Plugins, Git, Health, Mcp, Settings daemon halves | the Kotlin daemon halves | done | `rust/plugins/official/*`; `rust/crates/infantry-knowledge/src/unit.rs:196,262,326` |
| Pods, Epics, Profiles, Review, Stats, Assistant (no Kotlin daemon half; core frames) | n/a | done | `rd:pods.rs:673`; `rd:sessions.rs:57,1009` |
| Bus events for hooks | see #2 | missing | none |
| Policy presets / action gating | see #29 | partial | `rd:wire.rs:57-61` |

### MCP tools

| Group | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| Ticket create/update/get/move/start/comment(s), board, agent_switch/queue, epic_base, ticket_archive/restore, archive | `K:Infantry_Board/daemon/.../TicketSaveTools.kt:43-44`, `TicketMoveTools.kt:101,291,358`, `ArchiveTools.kt:26-28` | done | `rust/crates/infantry-board/src/tools.json:3-477` |
| Sessions and agents: list_sessions, get_session, spawn, prompt, kill, session_remove/restart, pod_state, waiting, list_harnesses, agent_list/get/enable/disable | `K:Infantry_Agents/daemon/.../SpawnTools.kt:39-44`, `SessionReadTools.kt:36-38`, `AgentRegistryTools.kt:25-28` | done | `rd:compose/agent_tools.json:3-433` |
| ask, prompt_answer, attention_raise, capability_list, notify, session_act, usage, usage_override, pace, message, inbox, read, harness_status/install, announce, projects, chat_note/image | Attention / Notify / Usage / Pace / Chat / Agents daemon halves | done | `agent_tools.json:151-632`; `rd:compose/tools.rs:348-354`; `rust/plugins/official/Infantry_Chat/daemon/src/lib.rs:210` |
| Scripts, run commands, secrets, processes, roles, skills, plugins, settings, project config, health, mcp, memory | `K:Infantry_Scripting`, `Infantry_Run`, `Infantry_Roles`, `Infantry_Plugins`, `Infantry_Settings` daemon halves | done | `rust/crates/infantry-scripting/src/tools.rs:17-26`; `rd:run/tools.rs:11-23`; `rust/crates/infantry-knowledge/src/tools.rs:49-55`; `rust/plugins/official/{Infantry_Plugins,Infantry_Settings,Infantry_Health,Infantry_Mcp,Infantry_Notes}` |
| ticket_files, ticket_related_files | see #1 | missing | none |
| ticket_epic / assign / unassign / delete | see #3 | missing | none |
| session_read, progress, capability_call, event_emit; exec `progress` | see #10 | missing / partial | none |
| notification_post, notice_suppress, notify_milestone | see #15 | missing | none |
| pipeline, pipeline_record | see #25 | missing | none |
| sites_list / ssh_exec / ssh_upload | `K:Infantry_Sites/.../SitesDaemonPlugin.kt:139,227-228` | done in the closed-source sidecar (contract `rust/plugins/official/Infantry_Sites/module.json:16-18`) | n/a |
| watch_file | `KD:cli/mcp/McpWatchTools.kt:39` | partial: shells out to the Kotlin launcher (#23) | `rust/crates/infantry-cli/src/tools.rs:440-456` |
| tests | `KD:daemon/plugins/DaemonPluginHost.kt:1528` | not porting (Gradle test runner; Rust uses `cargo test`) | n/a |

### CLI

| Group | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| board, tickets, ticket create/update/move/assign/unassign/start/link/comment(s)/related/archive/restore | `KD:cli/CliMain.kt:510-511,1304-1315` | done (Rust adds close, restart) | `rust/crates/infantry-cli/src/cli/cmd_ticket.rs:254-272` |
| send, inbox, read, threads, thread, waiting; agents, session, kill; script, secret, run-cmd; usage, settings, health, issue; remote, install-cli, uninstall-cli, plugin; progress, run, version | `CliMain.kt:306-556` | done | `cmd_mail.rs:448-453`; `cmd_sessions.rs:455-463`; `tools_cmds.rs:105,167,249`; `usage_settings.rs:186,385`; `local_cmds.rs:430-431,534`; `cmd_plugin.rs:44` |
| ticket files, ticket epic | see #1, #3 | broken (no daemon side) | `cmd_ticket.rs:50,1405-1437` |
| project subcommands, prompt | see #11 | broken | `cmd_project.rs:14-22` |
| run-watched, unwatched, watch-file | see #23 | broken | `mod.rs:187` |
| runner pair / rotate / connect / test | see #4 | broken (daemon side) | `cmd_runner.rs:719-848` |
| launcher-only families | see #21, #22 | launcher-only | `mod.rs:367-376` |
| `runner remove`, per-command `--help`, `projects` | absent in both builds | not a parity gap (open c9b46d04, 44ddd514) | n/a |

### Settings

| Namespace | Kotlin keys | Rust keys | Status / ticket |
|---|---|---|---|
| `limits.*` (all) | 222 (`LimitRegistry.kt`) | 65 seen | partial: #16 (8eeaf932) |
| limits.concurrency / watch / watchdog / waiting / bg / notice / mcp / session / run / downloads / http / ticketFiles / progress / cpu / harness / jobs / knowledge / health / plugins / plugin.guard / mail | `LimitRegistry.kt:52-1322` | 0-7 per namespace | #16 (8eeaf932); the runner ones #31 |
| Plugin `config` cards | 114 keys across Kotlin `module.json` | 8 keys | #13, #14, #24, #26, #31, #32 |
| `usage.pacing`, `usage.manual`, `usage.overrides`, `usage.refreshMinutes` | `usage/Pacing.kt:109-111` | all | done (`rust/crates/infantry-usage`) |
| `usage.mock*` | `K:Infantry_Usage/module.json:28-31` | 0 | not filed: a developer test aid, not owner-facing |
| `pace.*` | `K:Infantry_Pace/module.json:33-40` | read, not declared | partial: card in #41 |
| `notify.*`, `notifications.*` | `K:Infantry_Notify/module.json:22-54` | 7 of 9 read | partial: #26 (336e0091) |
| `updater.*`, `update.*` | `K:Infantry_Updater/module.json:42-45` | `updater.autoCheck`; Tauri feed settings | partial: #33 |
| `runners.*`, `runner.*` | `RunnerLinkSettings.kt:21-67` | `rd:compose/settings.rs:28-33` | partial: #31 (+ fe4ee2eb) |
| Appearance | `K:Infantry_Appearance/module.json` | dark/light | partial: #14 |
| Scoped context / mail / prompts | `SettingsService.kt:1025-1116` | comments only | missing: #34 (b6096072) |
| Model / role allowlist, sandbox | `ModelAllowlist.kt:38,43`; `LimitRegistry.kt:742` | 0 | missing: #28 |
| `harness.claude.*` | `K:Infantry_Claude/module.json:15-21` | 0 | missing: #32 |
| `scripting.*` (30) | `K:Infantry_Scripting/module.json:82-117` | 6 read | partial: #24 |

### Scripts, triggers, hooks

| Area | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| `ih.call`, `ih.log`, alias metatable, sandboxed `os`/`string` | `K:Infantry_Lua/.../LuaScriptEngine.kt:87-133` | done | `rust/crates/infantry-scripting/src/lua.rs:192-268` |
| Hook kinds lane enter/exit, every Ns, mail.incoming, exec.tick/done | `HookStore.kt:21-55`; `ExecTools.kt:254,273` | done | `hooks.rs:26-87`; `exec.rs:437,502` |
| Bundled global scripts | `K:Infantry_Scripting/.../resources/global-scripts/` | done | `rust/crates/infantry-scripting/global-scripts/`, `builtin-scripts/` |
| Triggers (manual, schedule, ship, webhook, at; nudges; guard) | `K:Infantry_Triggers/module.json` | done | `rust/plugins/official/Infantry_Triggers/module.json` |
| Event sources for hooks | see #2 | missing | none |
| `session_read`, `notice_suppress`, `emit` aliases | see #10, #15 | missing | `dispatcher.rs:29-58` |
| `http.fetch`, `http.download`, `progress.*` capabilities; engine registry | see #24 | missing | none |
| Watch hooks | see #23 | missing | none |

`docs/wiki/reference/lua-api.md` names no hook event, so #2 also adds them there.

### Android

| Area | Kotlin | Rust status | Rust evidence |
|---|---|---|---|
| QR pairing | `superiorSoftLibrary/infantryUiCore/src/androidMain/.../pair/CameraQrScanner.kt:12,40` | done | `app/src-tauri/Cargo.toml` (`tauri-plugin-barcode-scanner`) |
| Token sealed in Keystore | `KeystoreTokenCipher.kt` | done | `app/src-tauri/gen/android/.../KeystorePlugin.kt:34` |
| Connect probe, lost-connection banner | `ConnectScreen.kt:62` | done | `app/src-tauri/src/mobile.rs:148`; `lost-connection-banner.tsx:15` |
| In-app APK update | `K:Infantry_Updater/androidSrc/AndroidSelfUpdate.kt` | done | `app/src-tauri/src/android_update.rs` |
| System notifications, tap opens ticket, background connection, pending replay, sound in background | see #6 | missing / partial | none |
| `infantry://connect` deep link; allowBackup=false | see #37 | missing | none |
| Voice read-out | see #44 | missing | none |

Not in either build (so not a gap): biometric unlock, widgets, share intents, offline cache, Firebase push. The Tauri app
adds Android TV launcher support, system-bar and keyboard insets and pinch-zoom lock (`MainActivity.kt:13-36`).

## 3. Not a gap (deliberate or renamed)

| Item | Why |
|---|---|
| Stories (`story_*`, `infantry stories`) | Deprecated, replaced by tickets and epics (CONTRACT.md:1284); 7b820cea closed obsolete. |
| `run_output` push | Rust streams `run_bytes` (`rd:run/mod.rs`); the Rust Run tab reads it. |
| `runner_update` / `runner_update_status` frames | Rust does the same work as `runner_admin` op `update` / `update_status` (`admin.rs:10,305`). |
| Plugin HTTP routes for Account `state/signin/signout`, Attention `GET /attention`, Notify `sounds`, Pace `usagefit` | Rust serves the same data as frames (`rd:account.rs:27`, `infantry-mail/src/notify/units.rs:50`, `infantry-usage/src/unit.rs:17,20`); every Rust client uses frames. |
| `infantry_tests`, `infantry tests` | Kotlin Gradle test runner; Rust uses `cargo test` and the gate scripts. |
| Kotlin harness plugins (Claude, OpenCode, Pi) and the core-feature plugins (Account, Attention, Board, Lua, Notify, Pace, Processes, RemoteRunner, Roles, Run, Scripting, Updater, Usage) as plugins | Rust ships them as crates or daemon units; feature gaps inside them are ranked above. |
| `usage.mock*` | Developer test aid for the usage view, not owner-facing. |

## 4. Earlier tickets: re-check

Done in code since PARITY-GAPS.md (the ticket is closed): 51d68a1c agent edits, 82b4ccd2 harness install / CLI
locations, c8e871dd project git detail, d302470e chat tools, c7f6a098 `session_act`, 01fa6414 `script_stats`, 41d625cc
agent tools, a5d5a192 Sites sidecar contract, d219f8aa triggers, 6146e11f image upload, 6bf06422 `epic_base`.

Open tickets whose text is now partly stale (left open; the worker should drop the done half):

| Ticket | Done already | Still missing |
|---|---|---|
| 0cb8b28c per-plugin settings cog | the cog and dialog (`S:components/plugins/plugin-manager.tsx:71-75`) | nothing found: confirm live and close |
| 32497179 config frames and routes | `config_get/set/values/changed/schema` (`rd:compose/config.rs:131-161`) | `limit_catalog`, the HTTP routes (#35) |
| 4d762769 ticket files and archive | archive frames and tools (1add5c97d, f6ee9d7e7) | `ticket_files*` and its tools (#1) |
| 3218f914 notification tools | `infantry_notify` | the other three (#15) |
| c8fc1ac3 HTTP routes | `/mail/thread/{id}` (`rust/crates/infantry-mail/src/mail/http.rs:28`) | `/plugins`, `/update/*` (#43) |
| a9890b41 Browser / Approvals / News | Approvals rules | News, Browser (#40) |
| 5be0e4c8 composer gaps | any-file attachments, mid-turn force | permission mode, plugin slot (#17) |
| b6096072, fe4ee2eb, d2701fa1, 274c4491 config fixes | n/a | written against the Kotlin files; on Rust most keys are not built at all. Retarget to Rust when started. |
