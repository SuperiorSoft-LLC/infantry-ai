# Rust parity gaps (RUST-AUDIT-1, 2026-11-18/19)

Static audit of the Kotlin app against the Rust daemon. Read-only: no fixes in this pass. Companion to
[PARITY.md](PARITY.md) (UI rows) and [MOVE-OVER.md](MOVE-OVER.md).

## Method and limits

- **Frames:** the 301 names in `rust/crates/infantry-protocol/src/frames.rs` were matched against every frame list a
  unit registers (`FrameUnit::frames`, `const FRAMES`, `sessions::owns`) and every plugin `module.json`
  `daemon.frames`. 298 names are claimed or named somewhere in Rust. The residue was then searched for any quoted
  occurrence in non-test Rust. Frames that only a CLI client or a test mentions count as unserved. Reply-only frames
  (for example `roles`, `skills`, `timeline_items`, `question_asked`) are pushed by their units and are not listed.
- **Plugins:** every `plugins/official/*/daemon` folder in Kotlin against `rust/plugins/official/*` and the Rust
  core crates (board, mail, knowledge, usage, scripting and run are Rust core crates, not plugins).
- **Tools:** quoted `infantry_*` names in Kotlin tool code and `module.json` against Rust. Names that appear in Rust
  only in the script alias table (`dispatcher.rs`) are NOT implemented.
- **Routes / settings:** `get/post/route("/...")` in Kotlin daemon code, and `limits.*` and other dotted keys.
- Not run: a live frame probe. A static scan can miss a handler that builds its frame name at run time, so every ticket
  tells the worker to confirm the gap live before coding.
- Already ticketed, not repeated: d219f8aa Triggers, 6146e11f images (`image_upload`), fdb3b34f plugin cog,
  6bf06422 `infantry_epic_base`, eeef969c worktree removal, 51da24af MCP kill/prompt/get_session.

## Gap table

Size: S under half a day, M about a day, L several days.

| Feature | Kotlin location | Rust status | Size | Ticket |
|---|---|---|---|---|
| Agent edits: `agent_save`, `agent_delete`, `agent_swap`, `agent_roster`, `agent_switch`; tools `infantry_agent_list/get/enable/disable/queue/switch` | `plugins/official/Infantry_Agents/daemon`, `cli/AgentDefCli.kt`, `Infantry_Board/.../TicketMoveTools.kt` | missing; CLI `agents --live` fails | M | 51d68a1c |
| Config frames `config_get/set/values/changed`, `limit_catalog`; routes `/config/schema`, `/config/values`, `/config-manifest`, `/node-config`, `/node-config/seed` | `daemon/http/ConfigManifestHttp.kt`, `NodeConfigHttp.kt` | missing | M | 32497179 |
| Named limits registry, about 160 `limits.*` keys | `infantryCore/.../settings/LimitRegistry.kt` | key strings in no Rust source | L | 8eeaf932 |
| Harness install and CLI locations: `harness_install_status`, `harness_install`, `cli_locations`, `cli_location_set`; tools `infantry_harness_install/status` | `infantryCore/.../harness/HarnessInstaller.kt`, `Infantry_Agents/.../AgentsDaemonPlugin.kt` | frame names only | M | 82b4ccd2 |
| Tool approval timer/expiry: `tool_approval_set` | `plugins/official/Infantry_Approvals/daemon` | no handler | S | 905d089f |
| Stories: `story_save/get/list/move` | `plugins/official/Infantry_Board/daemon` | no handler | M | 7b820cea |
| Verification pipeline: `pipeline_config/show/record/check/state`; tools `infantry_pipeline`, `infantry_pipeline_record` | `Infantry_Board/.../PipelineTools.kt` | no handler | M | 200b9f6b |
| Ticket files and archive: `ticket_files`, `ticket_files_report`, `archive_query/apply/report`; tools `infantry_ticket_files`, `infantry_ticket_related_files`, `infantry_archive`, `infantry_ticket_archive`, `infantry_ticket_restore` | `Infantry_Board/.../TicketFilesTools.kt`, `ArchiveTools.kt` | CLI client only | M | 4d762769 |
| Project git detail: `project_git`, `_patch`, `_history`, `_branch_list`, `_branch_diffstat`, `_checkout_result`, `_worktree_list`, `_worktree` | `plugins/official/Infantry_Git/daemon` | status/diff/log/branches served, rest missing | S | c8e871dd |
| Script tools `infantry_chat_note`, `infantry_chat_image` | `Infantry_Scripting/daemon` | alias row only | S | d302470e |
| Notification tools `infantry_notify`, `infantry_notification_post`, `infantry_notice_suppress`, `infantry_notify_milestone` | `Infantry_Scripting/.../NotificationTools.kt`, `cli/CliMain.kt` | alias row only | S | 3218f914 |
| Script tool `infantry_session_act` | `Infantry_Agents/.../SessionReadTools.kt` | alias row only | S | c7f6a098 |
| Script tool `infantry_script_stats` | `Infantry_Scripting/daemon` | run log only, no tool | S | 01fa6414 |
| Agent tools `infantry_waiting`, `infantry_pod_state`, `infantry_session_restart`, `infantry_capability_list`, `infantry_attention_raise` | `WaitingTool.kt`, `SessionReadTools.kt`, `SpawnTools.kt`, `DaemonPluginHost.kt`, `AttentionRaiseTool.kt` | tools missing, frames exist | S | 41d625cc |
| Infantry_Sites daemon half (SSH, servers, host pins; tool `infantry_sites_ssh_host_pins`) | `plugins/official/Infantry_Sites/daemon` | UI only | L | a5d5a192 |
| Routes `/update/check`, `/update/apply`, `/mail/thread/{id}`, `/plugins` | `ServerUpdateCli.kt`, `MailHttp.kt`, `DaemonMain.kt` | not found | S | c8fc1ac3 |
| Browser, Approvals, News daemon halves | `plugins/official/Infantry_{Browser,Approvals,News}/daemon` | no Rust daemon plugin | M | a9890b41 |
| Push frames `process_pressure`, `bg_job` | `Infantry_Processes/daemon`, watch code | no sender | S | c7d4f4ff |
| Triggers (`trigger_*`, `triggers`) | `Infantry_Triggers/daemon` | in progress | L | d219f8aa (existing) |
| Image upload (`image_upload`) | chat image path | missing | S | 6146e11f (existing) |
| Per-plugin settings cog | Plugins tab | missing | S | fdb3b34f (existing) |
| `infantry_epic_base` tool | `Infantry_Board/.../EpicBaseTool.kt` | missing | S | 6bf06422 (existing) |

## Not a daemon gap (UI only, see PARITY.md)

`spoken` (needs a TTS presenter, Kokoro plugin plan), archive view, question history, base-branch picker. The frames
exist or are pushed; the work is in the client.

## Not porting (deliberate)

| Item | Why |
|---|---|
| `infantry_tests` CLI (`cli/TestsCli.kt`) | A Kotlin Gradle test runner; the Rust workspace uses `cargo test` and `scripts/infantry-gate.sh`. |
| Kotlin harness plugins Claude, OpenCode, Pi as plugins | Rust has them as crates (`infantry-harness-claude`, `-opencode`, `-pi`, `-codex`). |
| Kotlin plugin halves Account, Attention, Board, Lua, Notify, Pace, Processes, RemoteRunner, Roles, Run, Scripting, Updater, Usage as plugins | Rust ships the same features as core crates or daemon units (account.rs, infantry-board, infantry-mail, infantry-usage, infantry-scripting, infantry-runner-*, run/, infantry-knowledge). Feature gaps inside them are in the tickets above. |
| Browser plugin (part of a9890b41) | The owner uses Playwright, not Claude in Chrome; the ticket asks the worker to drop it if nothing is left to do. |
