# STORAGE-1: worktree storage audit (STORAGE-CLEAN-1, 2026-11-20)

## Result

| | |
|---|---|
| Worktrees before | 208 |
| Removed (clean, merged into `feature-rust-port`, unused) | 34 |
| Left | 175 (incl. the main checkout) |
| Free on C: right before removal | 35.5 GB |
| Free on C: right after removal + prune | 40.9 GB (≈5.4 GB reclaimed) |
| Cargo `target/` caches deleted in unused worktrees | 1 folder, 0.3 GB |
| Free on C: when this file was written | 29.7 GB |

Free space keeps falling while other jobs run (61 GB at 13:26 → 45 GB at 13:41 → 29.7 GB now). The worktrees did not cause that drop. Its source is not in this audit.

## Method

- Merged = the worktree's HEAD is an ancestor of `feature-rust-port` or `main` (squash merges show as unmerged).
- Clean = `git status --porcelain` is empty.
- In use = files changed in the last 4 h, the last commit is from today, or the worktree is named in the ticket (`dp-merge/wt1-merge`, `ui-live-1` for #37). `agents_list_live` gives no worktree paths.
- Only `target/` folders carrying a cargo marker (`CACHEDIR.TAG` / `.rustc_info.json`) were deleted. Gradle `build/` and `.gradle/` folders were left in place because the ticket did not list them.
- Claude Code stopped the full size scan twice because the PC was low on memory, so sizes below exist only for the worktrees that were measured (— = not measured).
- No branch was deleted, nothing was pushed, and the Rust daemon, `~/.infantry` and `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` were not touched.

## Removed

- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-d6d1187e-build-16-package-the-dev-head-with-all-t/.worktrees/build-30e1a218`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-fe38f07a-bug-qa-shell-1-agenthqbinshelltest-fails`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-f740f0f6-rust-bug-p0-update-2-prove-the-update-lo`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-dd29c18e-gate-batch-d-staging-agenthq-7-dfb0e91-b`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-d6d1187e-build-16-package-the-dev-head-with-all-t`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-837737a9-m1-e2e-milestone-1-live-proof-on-dev-eaa`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-78df52ee-usage-gap-1-the-stats-usage-fit-counts-o`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-73463ff2-release-secrets-1-before-the-repo-goes-p`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-71c0d6aa-gate-batch-a-re-gate-staging-agenthq-3-8`
- `C:/Users/antho/agenthq-standalone/.worktrees/ticket-d8ec60b0-vc-merge-start-session-gap-006b778b-tip`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-preview-dev-53e23ca1`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-preview-dev-3db7fbd7`
- `C:/Users/antho/agenthq-standalone/.worktrees/qa-gate-batchc-0b66e75`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-test-c8f2a0b6`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-preview-dev-3`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-preview-dev-2`
- `C:/Users/antho/agenthq-standalone/.worktrees/gate-d-base-a0e4067`
- `C:/Users/antho/agenthq-standalone/.worktrees/b44-20261103-110348`
- `C:/Users/antho/agenthq-standalone/.worktrees/dev-build-9807cde7`
- `C:/Users/antho/agenthq-standalone/.worktrees/dev-build-7f34c8bd`
- `C:/Users/antho/agenthq-standalone/.worktrees/dev-build-6754f741`
- `C:/Users/antho/AppData/Local/Temp/package-dist-f1ef6063-7256e0`
- `C:/Users/antho/agenthq-standalone/.worktrees/vc-drift-aac5d8de`
- `C:/Users/antho/agenthq-standalone/.worktrees/owner-preview-dev`
- `C:/Users/antho/agenthq-standalone/.worktrees/dev-build-67065ea`
- `C:/Users/antho/agenthq-standalone/.worktrees/dev-build-0336530`
- `C:/Users/antho/agenthq-standalone/.worktrees/qa-stg-9890f007`
- `C:/Users/antho/agenthq-standalone/.worktrees/rust-build-1`
- `C:/Users/antho/agenthq-standalone/.worktrees/pm-role-1`
- `C:/Users/antho/agenthq-standalone/.worktrees/h7b3-dev`
- `C:/Users/antho/agenthq-qa/c22ab046`
- `C:/Users/antho/agenthq-qa/8d1acdc4`
- `C:/Users/antho/scratch-b6be37cb`
- `C:/c/t/readme-main`

## Ask first (kept: needs the owner's OK)

| Worktree | Branch | Why kept | Last commit | Size MB | Build caches MB |
|---|---|---|---|---|---|
| `.worktrees/audit-1a-feature-gaps` | audit-1a-feature-gaps | unmerged | 2026-11-20 | 65 | 0 |
| `.worktrees/bug-park-hook-1` | bug-park-hook-1 | active today | 2026-11-20 | 7901 | 7836 |
| `.worktrees/demo-build` | demo-build | unmerged + 2 uncommitted | 2026-10-26 | 18 | 0 |
| `.worktrees/demo-loader` | (detached) | unmerged | 2026-10-26 | 18 | 0 |
| `.worktrees/dp-merge/merge-feature-agenthq-mvp` | feature-agenthq-mvp | unmerged | 2026-11-19 | 64 | 0 |
| `.worktrees/dp-merge/wt1-merge` | feature-rust-port | protected: mainline | 2026-11-20 | 17827 | 17493 |
| `.worktrees/qa-gate-046a725` | (detached) | unmerged | 2026-10-26 | 21 | 0 |
| `.worktrees/qa-stg-046a725` | (detached) | unmerged | 2026-10-26 | 21 | 0 |
| `.worktrees/qa1-base` | (detached) | 2 uncommitted files | 2026-11-02 | 33 | 0 |
| `.worktrees/rust-conf-2` | ticket/rust-conf-2 | unmerged | 2026-11-18 | 59 | 0 |
| `.worktrees/rust-plugins-a` | ticket/rust-plugins-a | unmerged | 2026-11-18 | 61 | 0 |
| `.worktrees/rust-wit-1` | ticket/rust-wit-1 | 1 uncommitted files | 2026-11-18 | 77 | 0 |
| `.worktrees/staging-agenthq-2` | staging-agenthq-2 | unmerged | 2026-10-23 | 15 | 0 |
| `.worktrees/staging-agenthq-4` | staging-agenthq-4 | unmerged | 2026-10-24 | 17 | 0 |
| `.worktrees/ticket-00250139-bug-daemon-crash-1-reproduce-the-daemon` | ticket/00250139-bug-daemon-crash-1-reproduce-the-daemon | 3 uncommitted files | 2026-11-01 | 32 | 0 |
| `.worktrees/ticket-03817a59-rust-bug-android-p1-scan-qr-crashes-the` | ticket/03817a59-rust-bug-android-p1-scan-qr-crashes-the | 1 uncommitted files | 2026-11-19 | 1087 | 2 |
| `.worktrees/ticket-0541d481-design-docs-only-primitives-inventory-wh` | ticket/0541d481-design-docs-only-primitives-inventory-wh | unmerged | 2026-10-27 | 21 | 0 |
| `.worktrees/ticket-05f93c31-bug-runner-add-hang-1-infantry-runner-ad` | ticket/05f93c31-bug-runner-add-hang-1-infantry-runner-ad | unmerged | 2026-11-08 | 46 | 0 |
| `.worktrees/ticket-08877cc7-license-0-evaluation-only-measure-core-v` | ticket/08877cc7-license-0-evaluation-only-measure-core-v | unmerged | 2026-10-26 | 21 | 0 |
| `.worktrees/ticket-092125b5-dp-7d-memory-package-memorystore-leadnot` | ticket/092125b5-dp-7d-memory-package-memorystore-leadnot | unmerged | 2026-10-31 | 31 | 0 |
| `.worktrees/ticket-0b021bc9-rust-arch-split-infantry-agents-into-its` | ticket/0b021bc9-rust-arch-split-infantry-agents-into-its | unmerged | 2026-11-19 | — | — |
| `.worktrees/ticket-0c48eaea-win-sign-1-authenticode-sign-the-windows` | ticket/0c48eaea-win-sign-1-authenticode-sign-the-windows | unmerged | 2026-11-15 | — | — |
| `.worktrees/ticket-191d8150-build-17-6120e04c-package-dist-from-dev` | ticket/191d8150-build-17-6120e04c-package-dist-from-dev | 1 uncommitted files | 2026-11-01 | — | — |
| `.worktrees/ticket-21d2f8cb-rust-bug-closing-the-app-must-shut-every` | ticket/21d2f8cb-rust-bug-closing-the-app-must-shut-every | 15 uncommitted files | 2026-11-19 | — | — |
| `.worktrees/ticket-27d5a0fd-bug-cli-arg-1-memory-save-description-fa` | ticket/27d5a0fd-bug-cli-arg-1-memory-save-description-fa | unmerged | 2026-11-07 | — | — |
| `.worktrees/ticket-27db3fea-bug-card-live-1-a-live-installed-plugin` | ticket/27db3fea-bug-card-live-1-a-live-installed-plugin | unmerged | 2026-11-13 | — | — |
| `.worktrees/ticket-288b734c-rust-bug-p0-remove-each-ticket-s-worktre` | ticket/288b734c-rust-bug-p0-remove-each-ticket-s-worktre | unmerged | 2026-11-19 | — | — |
| `.worktrees/ticket-293380f7-demo-ux-new-agent-dialog-unusable-model` | ticket/293380f7-demo-ux-new-agent-dialog-unusable-model | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-2a0ac887-design-post-launch-docker-image-for-the` | ticket/2a0ac887-design-post-launch-docker-image-for-the | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-2b74cb16-bug-android-attach-does-not-work-images` | ticket/2b74cb16-bug-android-attach-does-not-work-images | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-2e1d14d9-android-connect-over-the-same-wi-fi-lan` | ticket/2e1d14d9-android-connect-over-the-same-wi-fi-lan | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-2e6b444e-design-offline-demo-canned-data-web-demo` | ticket/2e6b444e-design-offline-demo-canned-data-web-demo | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-2ed33416-comms-card-parse-the-answer-this-message` | ticket/2ed33416-comms-card-parse-the-answer-this-message | unmerged + 3 uncommitted | 2026-10-24 | — | — |
| `.worktrees/ticket-2f154f5f-demo-lock-mode-preseeded-project-2-agent` | ticket/2f154f5f-demo-lock-mode-preseeded-project-2-agent | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-2f763595-cal-publish-1-publish-the-calendar-plugi` | tmp/cal-publish | unmerged | 2026-11-17 | — | — |
| `.worktrees/ticket-37d91d61-rust-ui-4-chat-panel-in-the-tauri-app-ch` | ticket/37d91d61-rust-ui-4-chat-panel-in-the-tauri-app-ch | 8 uncommitted files | 2026-11-18 | — | — |
| `.worktrees/ticket-3db21b6f-fix-headlessdistzip-fails-on-windows-ent` | ticket/3db21b6f-fix-headlessdistzip-fails-on-windows-ent | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-3e7461f9-bug-the-60-message-thread-ceiling-blocks` | ticket/3e7461f9-bug-the-60-message-thread-ceiling-blocks | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-3fd235bb-harness-gap-infantry-prompt-to-a-busy-se` | ticket/3fd235bb-harness-gap-infantry-prompt-to-a-busy-se | 1 uncommitted files | 2026-11-05 | — | — |
| `.worktrees/ticket-406cb237-launch-qa-1-regression-gate-full-test-su` | ticket/406cb237-launch-qa-1-regression-gate-full-test-su | 6 uncommitted files | 2026-11-02 | — | — |
| `.worktrees/ticket-4097d75b-rust-parity-priority-first-time-install` | ticket/4097d75b-rust-parity-priority-first-time-install | active today | 2026-11-20 | — | — |
| `.worktrees/ticket-422222bd-qa-cross-platform-look-windows-desktop-b` | ticket/422222bd-qa-cross-platform-look-windows-desktop-b | 17 uncommitted files | 2026-10-27 | — | — |
| `.worktrees/ticket-45d2ba63-stats-tab-per-session-chart-scale-contro` | ticket/45d2ba63-stats-tab-per-session-chart-scale-contro | 2 uncommitted files | 2026-10-23 | — | — |
| `.worktrees/ticket-4b24cdcc-stabilize-p0-the-daemon-died-at-06-13-11` | ticket/4b24cdcc-stabilize-p0-the-daemon-died-at-06-13-11 | 13 uncommitted files | 2026-11-20 | — | — |
| `.worktrees/ticket-4c22b8b3-bug-hook-lane-done-1-script-hooks-on-lan` | ticket/4c22b8b3-bug-hook-lane-done-1-script-hooks-on-lan | unmerged | 2026-11-15 | — | — |
| `.worktrees/ticket-4cafbd11-first-run-keys-1-api-keys-for-commandcod` | ticket/4cafbd11-first-run-keys-1-api-keys-for-commandcod | 1 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-508833b9-staging-gate-2-e2e-write-test-cases-then` | ticket/508833b9-staging-gate-2-e2e-write-test-cases-then | unmerged | 2026-10-27 | — | — |
| `.worktrees/ticket-52e6690f-first-run-1-test-the-first-time-experien` | ticket/52e6690f-first-run-1-test-the-first-time-experien | 1 uncommitted files | 2026-11-06 | — | — |
| `.worktrees/ticket-55d60a5e-daemon-hangs-in-the-docker-image-16-thre` | ticket/55d60a5e-daemon-hangs-in-the-docker-image-16-thre | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-591f7bd7-harness-gap-mid-turn-input-declared-on-t` | ticket/591f7bd7-harness-gap-mid-turn-input-declared-on-t | 1 uncommitted files | 2026-11-05 | — | — |
| `.worktrees/ticket-59b793bd-design-daemon-give-worker-session-proces` | ticket/59b793bd-design-daemon-give-worker-session-proces | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-5ccc0b8c-harness-gap-native-background-tool-end-c` | ticket/5ccc0b8c-harness-gap-native-background-tool-end-c | unmerged | 2026-11-05 | — | — |
| `.worktrees/ticket-5fd5ba18-bug-ui-freeze-nightly-1-settings-plugins` | ticket/5fd5ba18-bug-ui-freeze-nightly-1-settings-plugins | 10 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-63ec3e61-fix-web-build-text-glyphs-render-as-tofu` | ticket/63ec3e61-fix-web-build-text-glyphs-render-as-tofu | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-64fec801-fix-web-desktop-sidebar-agent-row-has-a` | ticket/64fec801-fix-web-desktop-sidebar-agent-row-has-a | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-6c071fb4-fix-gate-batch-a-projectdaemontest-proje` | ticket/6c071fb4-fix-gate-batch-a-projectdaemontest-proje | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-71b3452a-header-move-the-right-panel-toggle-into` | ticket/71b3452a-header-move-the-right-panel-toggle-into | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-7381ae90-bug-firstinstall-cli-dead-ends-projects` | ticket/7381ae90-bug-firstinstall-cli-dead-ends-projects | 1 uncommitted files | 2026-11-15 | — | — |
| `.worktrees/ticket-73b920ff-design-empty-shell-core-modules-out-of-t` | ticket/73b920ff-design-empty-shell-core-modules-out-of-t | unmerged | 2026-10-28 | — | — |
| `.worktrees/ticket-7531d13c-flaky-test-compactlayoutuitest-at-360dp` | ticket/7531d13c-flaky-test-compactlayoutuitest-at-360dp | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-75d12d1e-rust-bug-redo-chat-composer-still-nothin` | ticket/75d12d1e-rust-bug-redo-chat-composer-still-nothin | active today | 2026-11-20 | — | — |
| `.worktrees/ticket-79cc0238-rust-android-android-app-from-the-same-r` | ticket/79cc0238-rust-android-android-app-from-the-same-r | 2 uncommitted files | 2026-11-19 | — | — |
| `.worktrees/ticket-79f88ee7-bug-mascot-live-1-the-mascot-avatar-styl` | ticket/79f88ee7-bug-mascot-live-1-the-mascot-avatar-styl | 3 uncommitted files | 2026-11-08 | — | — |
| `.worktrees/ticket-7ab70391-feature-voice-notifications-spoken-atten` | ticket/7ab70391-feature-voice-notifications-spoken-atten | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-7bd04755-usage-poll-3-keep-polling-until-3-weekly` | ticket/7bd04755-usage-poll-3-keep-polling-until-3-weekly | 4 uncommitted files | 2026-10-31 | — | — |
| `.worktrees/ticket-7c5c47f6-card-1-a-card-type-fence-becomes-a-card` | ticket/7c5c47f6-card-1-a-card-type-fence-becomes-a-card | 1 uncommitted files | 2026-11-13 | — | — |
| `.worktrees/ticket-7d3e1dbb-qa-e2e-staging-write-test-cases-then-run` | ticket/7d3e1dbb-qa-e2e-staging-write-test-cases-then-run | unmerged | 2026-11-01 | — | — |
| `.worktrees/ticket-7feb060f-rust-feature-low-dynamic-observers-and-i` | ticket/7feb060f-rust-feature-low-dynamic-observers-and-i | 4 uncommitted files | 2026-11-19 | — | — |
| `.worktrees/ticket-82b4ccd2-rust-parity-harness-install-and-cli-loca` | ticket/82b4ccd2-rust-parity-harness-install-and-cli-loca | unmerged | 2026-11-19 | — | — |
| `.worktrees/ticket-83254915-token-3-critical-efficiency-audit-why-do` | ticket/83254915-token-3-critical-efficiency-audit-why-do | unmerged | 2026-10-31 | — | — |
| `.worktrees/ticket-83606068-launch-rename-the-product-to-infantry-ai` | ticket/83606068-launch-rename-the-product-to-infantry-ai | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-87b40e7b-ui-drag-and-drop-ticket-reordering-order` | ticket/87b40e7b-ui-drag-and-drop-ticket-reordering-order | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-8815b3ee-cal-ui-1-calendar-epic-month-grid-day-ce` | ticket/8815b3ee-cal-ui-1-calendar-epic-month-grid-day-ce | unmerged | 2026-11-17 | — | — |
| `.worktrees/ticket-8a909fb8-rust-bug-activity-log-shows-only-startin` | ticket/8a909fb8-rust-bug-activity-log-shows-only-startin | unmerged | 2026-11-19 | — | — |
| `.worktrees/ticket-8efbcb1d-chat-cards-show-a-formatted-timestamp-to` | ticket/8efbcb1d-chat-cards-show-a-formatted-timestamp-to | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-90588545-check-design-block-injectors-additive-ex` | ticket/90588545-check-design-block-injectors-additive-ex | unmerged | 2026-10-27 | — | — |
| `.worktrees/ticket-9a226e28-fix-test-statusprocessesbadgeuitest-flak` | ticket/9a226e28-fix-test-statusprocessesbadgeuitest-flak | unmerged | 2026-10-27 | — | — |
| `.worktrees/ticket-9bc0c955-security-pin-the-infantry-signing-key-in` | ticket/9bc0c955-security-pin-the-infantry-signing-key-in | unmerged | 2026-10-28 | — | — |
| `.worktrees/ticket-9bf81983-dev-locked-demo-must-make-it-impossible` | ticket/9bf81983-dev-locked-demo-must-make-it-impossible | 7 uncommitted files | 2026-10-25 | — | — |
| `.worktrees/ticket-9ed0e517-fix-web-start-on-a-no-role-ticket-expand` | ticket/9ed0e517-fix-web-start-on-a-no-role-ticket-expand | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-a0573aca-fix-shipped-knowledge-shared-knowledge-m` | ticket/a0573aca-fix-shipped-knowledge-shared-knowledge-m | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-a29b7d46-infantry-ai-project-configurations-repla` | ticket/a29b7d46-infantry-ai-project-configurations-repla | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-a5969342-task-routing-rules-which-agent-model-get` | ticket/a5969342-task-routing-rules-which-agent-model-get | 10 uncommitted files | 2026-10-24 | — | — |
| `.worktrees/ticket-a680e322-qa-follow-up-interactive-checks-on-dev-f` | ticket/a680e322-qa-follow-up-interactive-checks-on-dev-f | 3 uncommitted files | 2026-10-26 | — | — |
| `.worktrees/ticket-a8278557-harness-plugin-2-h2-zero-harness-core-th` | ticket/a8278557-harness-plugin-2-h2-zero-harness-core-th | 1 uncommitted files | 2026-10-30 | — | — |
| `.worktrees/ticket-a8e178d6-arch-break-down-daemon-core-2-and-sequen` | ticket/a8e178d6-arch-break-down-daemon-core-2-and-sequen | unmerged | 2026-10-31 | — | — |
| `.worktrees/ticket-a9dabdeb-fix-web-mobile-390px-session-title-ends` | ticket/a9dabdeb-fix-web-mobile-390px-session-title-ends | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-abc76a76-store-setup-1-walk-the-owner-through-mic` | ticket/abc76a76-store-setup-1-walk-the-owner-through-mic | unmerged | 2026-11-14 | — | — |
| `.worktrees/ticket-ad20a070-output-encoding-cli-mcp-read-output-show` | ticket/ad20a070-output-encoding-cli-mcp-read-output-show | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-ad824645-qa-cross-platform-look-windows-desktop-b` | ticket/ad824645-qa-cross-platform-look-windows-desktop-b | 5 uncommitted files | 2026-10-27 | — | — |
| `.worktrees/ticket-ae84c4bb-bug-cal-fix-1-calendar-1-0-1-community-i` | ticket/ae84c4bb-bug-cal-fix-1-calendar-1-0-1-community-i | unmerged | 2026-11-17 | — | — |
| `.worktrees/ticket-af4a997d-demo-switch-agent-button-must-always-be` | ticket/af4a997d-demo-switch-agent-button-must-always-be | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-b0a5da65-feature-headless-docker-image-of-the-dae` | ticket/b0a5da65-feature-headless-docker-image-of-the-dae | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-b0fb747d-usage-poll-script-poll-usage-pace-for-40` | ticket/b0fb747d-usage-poll-script-poll-usage-pace-for-40 | 5 uncommitted files | 2026-10-31 | — | — |
| `.worktrees/ticket-b4202754-fix-web-demo-missing-icon-glyphs-tofu-bo` | ticket/b4202754-fix-web-demo-missing-icon-glyphs-tofu-bo | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-b6d8aba2-claude-auth-choice-2-login-flow-in-a-rea` | ticket/b6d8aba2-claude-auth-choice-2-login-flow-in-a-rea | 1 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-b6ecc7a4-refresh-controls-icon-not-text-in-usage` | ticket/b6ecc7a4-refresh-controls-icon-not-text-in-usage | 2 uncommitted files | 2026-10-31 | — | — |
| `.worktrees/ticket-b80cd867-harness-descriptor-install-1-json-harnes` | ticket/b80cd867-harness-descriptor-install-1-json-harnes | 4 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-ba433f49-feature-ticket-weights-weight-based-agen` | ticket/ba433f49-feature-ticket-weights-weight-based-agen | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-ba7f1956-harness-gap-no-graceful-close-on-opencod` | ticket/ba7f1956-harness-gap-no-graceful-close-on-opencod | 2 uncommitted files | 2026-11-05 | — | — |
| `.worktrees/ticket-bc72cf1e-ci-fix-images-mcpticketimagese2etest-no` | ticket/bc72cf1e-ci-fix-images-mcpticketimagese2etest-no | 98 uncommitted files | 2026-11-07 | — | — |
| `.worktrees/ticket-be9e6978-build-7-dev-build-at-dev-tip-6e0c1f9c-bo` | ticket/be9e6978-build-7-dev-build-at-dev-tip-6e0c1f9c-bo | 1 uncommitted files | 2026-10-28 | — | — |
| `.worktrees/ticket-c00d1d1c-docker-self-contained-daemon-image-headl` | ticket/c00d1d1c-docker-self-contained-daemon-image-headl | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-c1f61440-design-epic-architecture-unify-the-two-t` | ticket/c1f61440-design-epic-architecture-unify-the-two-t | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-c4bd4e1c-critical-path-3-android-client-connects` | ticket/c4bd4e1c-critical-path-3-android-client-connects | unmerged | 2026-10-23 | — | — |
| `.worktrees/ticket-c76001ce-docs-rewrite-github-readme-features-scre` | ticket/c76001ce-docs-rewrite-github-readme-features-scre | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-c771fe57-mascot-shot-1-clean-mascot-screenshot-fo` | ticket/c771fe57-mascot-shot-1-clean-mascot-screenshot-fo | 2 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-c89907ae-usage-poll-2-4-hour-script-poll-to-catch` | ticket/c89907ae-usage-poll-2-4-hour-script-poll-to-catch | 2 uncommitted files | 2026-10-31 | — | — |
| `.worktrees/ticket-cca9056d-bug-turns-saved-1-the-turns-saved-counte` | ticket/cca9056d-bug-turns-saved-1-the-turns-saved-counte | 5 uncommitted files | 2026-11-12 | — | — |
| `.worktrees/ticket-d0918e02-qa-r2-c5c41cb-android-first-connect-show` | ticket/d0918e02-qa-r2-c5c41cb-android-first-connect-show | unmerged | 2026-10-30 | — | — |
| `.worktrees/ticket-d0b9bdcf-chat-ui-selected-highlighted-text-is-inv` | ticket/d0b9bdcf-chat-ui-selected-highlighted-text-is-inv | 10 uncommitted files | 2026-11-02 | — | — |
| `.worktrees/ticket-d219f8aa-rust-parity-triggers-tab-errors-trigger` | ticket/d219f8aa-rust-parity-triggers-tab-errors-trigger | unmerged + 1 uncommitted | 2026-11-18 | — | — |
| `.worktrees/ticket-d4c1dbd9-demo-image-preseeded-locked-down-single` | ticket/d4c1dbd9-demo-image-preseeded-locked-down-single | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-d5eb35a0-fix-web-demo-remaining-tofu-glyphs-mobil` | ticket/d5eb35a0-fix-web-demo-remaining-tofu-glyphs-mobil | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-d775e20c-waiting-flag-1-a-tool-command-for-an-age` | ticket/d775e20c-waiting-flag-1-a-tool-command-for-an-age | 11 uncommitted files | 2026-11-08 | — | — |
| `.worktrees/ticket-d8a1cf0f-bug-plugin-tree-ui-2-infantry-chat-card` | ticket/d8a1cf0f-bug-plugin-tree-ui-2-infantry-chat-card | 9 uncommitted files | 2026-11-02 | — | — |
| `.worktrees/ticket-db5b3b70-demo-lock-ui-fixes-no-red-disabled-in-th` | ticket/db5b3b70-demo-lock-ui-fixes-no-red-disabled-in-th | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-dcb1af9a-isolation-list-sessions-agents-output-la` | ticket/dcb1af9a-isolation-list-sessions-agents-output-la | 1 uncommitted files | 2026-10-23 | — | — |
| `.worktrees/ticket-dd30805c-assistant-opencode-harness-gives-no-repl` | ticket/dd30805c-assistant-opencode-harness-gives-no-repl | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-df416b6f-design-rev-empty-shell-design-md-misses` | ticket/df416b6f-design-rev-empty-shell-design-md-misses | unmerged | 2026-10-28 | — | — |
| `.worktrees/ticket-e0c04434-verify-mascot-1-0-3-live-prove-live-that` | ticket/e0c04434-verify-mascot-1-0-3-live-prove-live-that | 26 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-e13c0aa3-make-live-progress-automatic-harness-det` | ticket/e13c0aa3-make-live-progress-automatic-harness-det | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-e28f97f3-account-installable-1-infantry-account-i` | ticket/e28f97f3-account-installable-1-infantry-account-i | unmerged + 2 uncommitted | 2026-11-15 | — | — |
| `.worktrees/ticket-e566a1e0-qa-live-runner-mascot-1-real-gradle-wind` | ticket/e566a1e0-qa-live-runner-mascot-1-real-gradle-wind | 33 uncommitted files | 2026-11-09 | — | — |
| `.worktrees/ticket-e629aabe-runner-unify-5-medium-risk-approvals-fai` | ticket/e629aabe-runner-unify-5-medium-risk-approvals-fai | 11 uncommitted files | 2026-11-13 | — | — |
| `.worktrees/ticket-e65bfcf2-script-vis-1b-the-visible-part-injected` | ticket/e65bfcf2-script-vis-1b-the-visible-part-injected | 3 uncommitted files | 2026-11-01 | — | — |
| `.worktrees/ticket-e75d0b32-qa-attack-the-locked-demo-through-the-br` | ticket/e75d0b32-qa-attack-the-locked-demo-through-the-br | 62 uncommitted files | 2026-10-25 | — | — |
| `.worktrees/ticket-f0b7173f-harness-gap-folder-trust-auto-answer-on` | ticket/f0b7173f-harness-gap-folder-trust-auto-answer-on | 15 uncommitted files | 2026-11-05 | — | — |
| `.worktrees/ticket-f36ca86f-voice-input-speech-to-text-as-an-install` | ticket/f36ca86f-voice-input-speech-to-text-as-an-install | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-f5422254-qa-independently-verify-the-sandbox-conf` | ticket/f5422254-qa-independently-verify-the-sandbox-conf | 2 uncommitted files | 2026-10-25 | — | — |
| `.worktrees/ticket-f9e181af-demo-restrictions-model-allowlist-only-o` | ticket/f9e181af-demo-restrictions-model-allowlist-only-o | unmerged | 2026-10-25 | — | — |
| `.worktrees/ticket-fa41d42d-blocks-layout-design-the-core-layout-pri` | ticket/fa41d42d-blocks-layout-design-the-core-layout-pri | unmerged | 2026-10-26 | — | — |
| `.worktrees/ticket-fa99b990-now-branch-cleanup-1-remove-merged-ticke` | ticket/fa99b990-now-branch-cleanup-1-remove-merged-ticke | 1 uncommitted files | 2026-11-03 | — | — |
| `.worktrees/ticket-fb290df0-fix-web-empty-bordered-box-at-far-right` | ticket/fb290df0-fix-web-empty-bordered-box-at-far-right | unmerged | 2026-10-24 | — | — |
| `.worktrees/ticket-fe928319-rust-triggers-1-infantry-triggers-daemon` | ticket/fe928319-rust-triggers-1-infantry-triggers-daemon | 4 uncommitted files | 2026-11-18 | — | — |
| `.worktrees/ui-live-1` | ui-live-1 | protected: #37 in use | 2026-11-20 | — | — |
| `.worktrees/vc-feature-calendar` | feature-calendar | unmerged | 2026-11-17 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-0203832` | (detached) | 1351 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-1471b44` | (detached) | 1400 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-2737bfcb-android` | (detached) | 2995 uncommitted files | 2026-11-05 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-4895815` | (detached) | 1359 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-523752e` | (detached) | 1366 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-6e9c892` | (detached) | 1268 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-89072ae` | (detached) | 1162 uncommitted files | 2026-10-23 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-9d60950` | (detached) | 1645 uncommitted files | 2026-10-26 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-a0e4067` | (detached) | 1433 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-c43cfc2` | (detached) | 1421 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-d473fd2` | (detached) | 1187 uncommitted files | 2026-10-23 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-db250c9` | (detached) | 1466 uncommitted files | 2026-10-25 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-dffa3d9` | (detached) | 1329 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-auto-e85b223` | (detached) | 1206 uncommitted files | 2026-10-23 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-qa-gate-0c0f7cb/mvp` | (detached) | 1266 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/agenthq-qa-gate-0c0f7cb/wt` | (detached) | 1330 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/android-f2885e7a` | (detached) | 2905 uncommitted files | 2026-11-03 | — | — |
| `C:/Users/antho/AppData/Local/Temp/b36-scratch` | (detached) | 2659 uncommitted files | 2026-11-02 | — | — |
| `C:/Users/antho/AppData/Local/Temp/bisect-chgwt` | (detached) | 1205 uncommitted files | 2026-10-23 | — | — |
| `C:/Users/antho/AppData/Local/Temp/e7b6/wt` | (detached) | 1267 uncommitted files | 2026-10-24 | — | — |
| `C:/Users/antho/AppData/Local/Temp/package-dist-65cc7ac3-4280c1` | (detached) | 2650 uncommitted files | 2026-11-02 | — | — |
| `C:/Users/antho/AppData/Local/Temp/package-dist-83a993e2-ccd102` | (detached) | 2589 uncommitted files | 2026-11-01 | — | — |
| `C:/Users/antho/AppData/Local/Temp/package-dist-a47a6b2f-9d4af1` | (detached) | 2542 uncommitted files | 2026-11-01 | — | — |
| `C:/Users/antho/AppData/Local/Temp/package-dist-d0154bac-4ab3f6` | (detached) | 2729 uncommitted files | 2026-11-03 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-base` | (detached) | 1 uncommitted files | 2026-10-26 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-cfg` | (detached) | 1 uncommitted files | 2026-10-26 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-dev` | (detached) | 1 uncommitted files | 2026-10-28 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L1` | (detached) | 1 uncommitted files | 2026-10-26 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L11` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-l12` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L2` | (detached) | 1 uncommitted files | 2026-10-26 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L3` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L5` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-L7` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |
| `C:/Users/antho/perf-0202e34a/wt-pre` | (detached) | 1 uncommitted files | 2026-10-27 | — | — |

## Step 2: save then remove (owner OK, 2026-11-20, relayed by the Project Manager)

| | |
|---|---|
| Worktrees processed before Claude Code stopped the run for low memory | 120 removed |
| WIP commits made (on each worktree's own branch, not pushed) | 50 |
| New branches created | 0 |
| Gradle build caches in worktrees before the switch | 1.3 GB |
| User-level `~/.gradle` caches (caches, .tmp, jdks, daemon, wrapper/dists, native) | 12.3 GB |
| Worktrees left (`git worktree list`) | 53 |
| Free on C: now | 196.6 GB (low point today: 29.1 GB) |

Commit message: `WIP: saved before worktree cleanup (STORAGE-CLEAN-1, owner OK 2026-11-20)`. No branch was deleted and nothing was pushed.

### Kept

- `ticket-79f88ee7-bug-mascot-live-1-...`: an uncommitted file that looks like a secret (`.scratch-79f88ee7/state/owner.token`). Not committed.
- `ticket-03817a59-rust-bug-android-p1-scan-qr-...`: the WIP commit failed because something else committed there at the same moment (`4cf4394fe`), so it is in use. Left as it is.
- `ticket-4b24cdcc-stabilize-p0-...`: saved (`a62e5dc98`) and unregistered, but Windows refused to delete the empty folder (Permission denied: a process still has it open). Delete the empty folder once that process ends.
- Skipped by design: bug-park-hook-1, ticket-4097d75b, ticket-75d12d1e, dp-merge/wt1-merge, ui-live-1, storage-2 (#39), audit-1b-ui-pass (#3), the root checkout, and every worktree created after the 13:45 snapshot.
- Note: `audit-1a-feature-gaps` (clean, unmerged) was removed. Its branch and commits are kept.

### Not processed yet (44, because the run was stopped)

- `C:/Users/antho/agenthq-standalone/.worktrees/demo-loader`
- `C:/Users/antho/agenthq-standalone/.worktrees/qa-gate-046a725`
- `C:/Users/antho/agenthq-standalone/.worktrees/qa-stg-046a725`
- `C:/Users/antho/agenthq-standalone/.worktrees/qa1-base`
- `C:/Users/antho/agenthq-standalone/.worktrees/rust-conf-2`
- `C:/Users/antho/agenthq-standalone/.worktrees/rust-plugins-a`
- `C:/Users/antho/agenthq-standalone/.worktrees/rust-wit-1`
- `C:/Users/antho/agenthq-standalone/.worktrees/staging-agenthq-2`
- `C:/Users/antho/agenthq-standalone/.worktrees/staging-agenthq-4`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-0203832`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-1471b44`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-2737bfcb-android`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-4895815`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-523752e`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-6e9c892`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-89072ae`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-9d60950`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-a0e4067`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-c43cfc2`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-d473fd2`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-db250c9`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-dffa3d9`
- `C:/Users/antho/AppData/Local/Temp/agenthq-auto-e85b223`
- `C:/Users/antho/AppData/Local/Temp/agenthq-qa-gate-0c0f7cb/mvp`
- `C:/Users/antho/AppData/Local/Temp/agenthq-qa-gate-0c0f7cb/wt`
- `C:/Users/antho/AppData/Local/Temp/android-f2885e7a`
- `C:/Users/antho/AppData/Local/Temp/b36-scratch`
- `C:/Users/antho/AppData/Local/Temp/bisect-chgwt`
- `C:/Users/antho/AppData/Local/Temp/e7b6/wt`
- `C:/Users/antho/AppData/Local/Temp/package-dist-65cc7ac3-4280c1`
- `C:/Users/antho/AppData/Local/Temp/package-dist-83a993e2-ccd102`
- `C:/Users/antho/AppData/Local/Temp/package-dist-a47a6b2f-9d4af1`
- `C:/Users/antho/AppData/Local/Temp/package-dist-d0154bac-4ab3f6`
- `C:/Users/antho/perf-0202e34a/wt-base`
- `C:/Users/antho/perf-0202e34a/wt-cfg`
- `C:/Users/antho/perf-0202e34a/wt-dev`
- `C:/Users/antho/perf-0202e34a/wt-L1`
- `C:/Users/antho/perf-0202e34a/wt-L11`
- `C:/Users/antho/perf-0202e34a/wt-l12`
- `C:/Users/antho/perf-0202e34a/wt-L2`
- `C:/Users/antho/perf-0202e34a/wt-L3`
- `C:/Users/antho/perf-0202e34a/wt-L5`
- `C:/Users/antho/perf-0202e34a/wt-L7`
- `C:/Users/antho/perf-0202e34a/wt-pre`

### WIP commits

- `demo-build` 1fa7305a3
- `ticket/fe928319-rust-triggers-1-infantry-triggers-daemon` c40b2cf6c
- `ticket/fa99b990-now-branch-cleanup-1-remove-merged-ticke` e739fdc68
- `ticket/f5422254-qa-independently-verify-the-sandbox-conf` 8974f79ad
- `ticket/e75d0b32-qa-attack-the-locked-demo-through-the-br` 632d7b4bd
- `ticket/e629aabe-runner-unify-5-medium-risk-approvals-fai` ff82ac2e3
- `ticket/e566a1e0-qa-live-runner-mascot-1-real-gradle-wind` e4911bff1
- `ticket/e28f97f3-account-installable-1-infantry-account-i` f6ce7e9f6
- `ticket/e0c04434-verify-mascot-1-0-3-live-prove-live-that` 3cf5b3a36
- `ticket/dcb1af9a-isolation-list-sessions-agents-output-la` 1d125e3ae
- `ticket/d775e20c-waiting-flag-1-a-tool-command-for-an-age` 8ff0cdcf2
- `ticket/d0b9bdcf-chat-ui-selected-highlighted-text-is-inv` 0edd1818e
- `ticket/cca9056d-bug-turns-saved-1-the-turns-saved-counte` 4dafdea31
- `ticket/c89907ae-usage-poll-2-4-hour-script-poll-to-catch` b088b5268
- `ticket/c771fe57-mascot-shot-1-clean-mascot-screenshot-fo` 51ed05511
- `ticket/be9e6978-build-7-dev-build-at-dev-tip-6e0c1f9c-bo` be371fc8a
- `ticket/ba7f1956-harness-gap-no-graceful-close-on-opencod` f2ed22cb8
- `ticket/b80cd867-harness-descriptor-install-1-json-harnes` 6269967f2
- `ticket/b6d8aba2-claude-auth-choice-2-login-flow-in-a-rea` bd74243b6
- `ticket/b0fb747d-usage-poll-script-poll-usage-pace-for-40` 52ad62843
- `ticket/ad824645-qa-cross-platform-look-windows-desktop-b` bc69610ba
- `ticket/a8278557-harness-plugin-2-h2-zero-harness-core-th` f52c259c7
- `ticket/a680e322-qa-follow-up-interactive-checks-on-dev-f` 866f80755
- `ticket/a5969342-task-routing-rules-which-agent-model-get` 27a86550b
- `ticket/7feb060f-rust-feature-low-dynamic-observers-and-i` fc48e1e64
- `ticket/7bd04755-usage-poll-3-keep-polling-until-3-weekly` 128acf492
- `ticket/79cc0238-rust-android-android-app-from-the-same-r` b6d19cb32
- `ticket/5fd5ba18-bug-ui-freeze-nightly-1-settings-plugins` 05e76309b
- `ticket/591f7bd7-harness-gap-mid-turn-input-declared-on-t` 03f017279
- `ticket/52e6690f-first-run-1-test-the-first-time-experien` 50fd8a195
- `ticket/4cafbd11-first-run-keys-1-api-keys-for-commandcod` 3d5285207
- `ticket/4b24cdcc-stabilize-p0-the-daemon-died-at-06-13-11` a62e5dc98
- `ticket/45d2ba63-stats-tab-per-session-chart-scale-contro` 7821cab1e
- `ticket/422222bd-qa-cross-platform-look-windows-desktop-b` ea2e54724
- `ticket/406cb237-launch-qa-1-regression-gate-full-test-su` 6543698a6
- `ticket/3fd235bb-harness-gap-infantry-prompt-to-a-busy-se` 2d3ac3cc9
- `ticket/37d91d61-rust-ui-4-chat-panel-in-the-tauri-app-ch` 0be607e9a
- `ticket/2ed33416-comms-card-parse-the-answer-this-message` 09a4127f3
- `ticket/21d2f8cb-rust-bug-closing-the-app-must-shut-every` 1dec1d022
- `ticket/f0b7173f-harness-gap-folder-trust-auto-answer-on` 00a3d2abe
- `ticket/e65bfcf2-script-vis-1b-the-visible-part-injected` ddf8bba75
- `ticket/d8a1cf0f-bug-plugin-tree-ui-2-infantry-chat-card` 8451aff4a
- `ticket/d219f8aa-rust-parity-triggers-tab-errors-trigger` d01a496ad
- `ticket/bc72cf1e-ci-fix-images-mcpticketimagese2etest-no` 64b051de7
- `ticket/b6ecc7a4-refresh-controls-icon-not-text-in-usage` 1b8207eb1
- `ticket/9bf81983-dev-locked-demo-must-make-it-impossible` 8f22aeb65
- `ticket/7c5c47f6-card-1-a-card-type-fence-becomes-a-card` cadb5e6d0
- `ticket/7381ae90-bug-firstinstall-cli-dead-ends-projects` 193df94d0
- `ticket/191d8150-build-17-6120e04c-package-dist-from-dev` 930cccd0a
- `ticket/00250139-bug-daemon-crash-1-reproduce-the-daemon` f949545ef
