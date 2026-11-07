# SHELL-SHOW-1: the empty shell, as it is on 2026-11-05 (dev head 86f7daac)

Reference shots taken by the agent; the owner still looks for himself. Nothing in the shell or in a plugin was changed.

## Launch it

From the repository root (Windows):

```
scripts\show-empty-shell.cmd            (double-click: the shell with ZERO plugins)
scripts\show-empty-shell.cmd root       (the shell + Infantry_Settings only, the plugin that holds the plugin manager screen)
pwsh scripts/show-empty-shell.ps1 [-Mode empty|root] [-Plugins A,B] [-Keep] [-NoBuild]
```

What it does: builds `:superiorSoftLibrary:infantryUi:shellLaunchFiles` (the `runShell` java command written to
`build/shell/launch/`) and `:plugins:_template:ihq`, makes a new temp folder `%TEMP%\infantry-shell-show\<stamp>` with
an app dir (`app.json`, empty `plugins/core|official|community`) and a temp `user.home`, removes `INFANTRY_*`,
`AGENTHQ_*` and `CLAUDE*` from the child's environment, and starts the JVM itself (`--app <dir>`). The shell's bundled
daemon then binds 127.0.0.1 on an OS-assigned port (seen: 56532) with its state under the temp home: never the owner's
state dir, never 8787/8788/8797/8798. When the window closes (or Ctrl+C) it stops only that PID and deletes the temp
folder (`-Keep` keeps it). The starter `Example_Starter.ihq` is copied next to the app dir to try the drop box.

## What the empty shell draws (01-empty-shell.png)

- An empty left column and an empty right column (empty slots still take their width), with drag seams.
- The centre: the drop box "Drop a plugin here" + **Choose file**.
- Top right: **Configuration**.
- Status bar: daemon/contract/last-frame on the left, build stamp on the right, and a shortcut hint line.
- Log: `[plugins] ... 0 loaded`, daemon `provider=none`, `harnesses: <none>`, ws connected. No crash, no plugin error.

## Installing a first plugin from it (02, 03, 05)

The way that exists today: the drop box (drag a `.ihq`, or **Choose file**) -> consent dialog (name, version, author,
signer, tier, which window parts it asks for) -> **Install** unpacks it into `plugins/<tier>/<name>` -> soft reload.

Tried with `Example_Starter.ihq` (the only `.ihq` the repo can build; unsigned, so the temp `install.json` allows
unsigned into community):
- consent dialog: OK (02).
- Install: the files land in `plugins/community/Example_Starter`, then the loader refuses it:
  `[plugins] Example_Starter (community): refused - missing dependency Infantry_Git >=1.0 <2`.
  **The UI says nothing**: the drop box simply comes back (03). Only Configuration > Plugins > Modules shows the card
  with "Needs: Infantry_Git" (05). The consent dialog did not check dependencies before installing.

What is missing for "the root plugin installs a first plugin" (not built here):
1. **No official plugin is installable as a `.ihq`.** Only `plugins/_template` applies the `tech.superiorsoftllc.infantry.plugin`
   Gradle plugin (the `ihq` task). Official plugins build plain jars that the install bundles under `modules/official`.
   Owner: build-logic (`InfantryPluginPlugin`, Apache) applied to each official plugin, plus signing.
2. **No catalog browse in the shell.** The plugin manager screen with catalogs (`PluginsScreen`) lives in
   `plugins/official/Infantry_Settings` as a centre tab. With Settings alone loaded (06, mode `root`) it loads fine
   (UI + daemon half, 4 tools) but **draws nothing**: the centre tab strip that hosts tabs belongs to
   `plugins/official/Infantry_Chat` (`ShellCenter.kt`, `CenterTabBar.kt`). So a "root" plugin that can install others
   has to either own its own surface (a slot fill, not a centre tab) or the shell needs a generic tab host. Apache side
   either way (a plugin, or `infantryUiCore` shell blocks); nothing in MPL core is needed.
3. **Dependency refusal is silent** and checked only after unpacking (`IhqInstaller` / `DesktopDropIn` in
   infantryPlugins + infantryUiCore, Apache): the consent dialog should show unmet dependencies before Install.
4. `plugins/core` is empty by design, so today there is no plugin that is "the root plugin".

## Project-manager UI still compiled into the shell

Not drawn as panes, but in the shell's code/classpath (`infantryUiCore`, Apache), seen on screen:
- Status bar hint line: `shell/blocks/StatusBarBlocks.kt:136` hard-codes "^K search  Alt+Up/Down agents  ^J needs input
  ^N new  ^R resume  ^5 roles  ^6 attention ..." (01, bottom right).
- Configuration > General (04): "Agent scope" (`ConfigTabs.kt`, sidebar agent list / top-bar avatars), "Session
  lifecycle" (`model/LifecycleSetting.kt`), "Policy" presets; mostly agent/session concepts.
- Configuration > Plugins (05): "Natural voice (Piper, Kathleen)" (`ui/voice/`), a voice feature in the shell.
- `infantryUiCore/src/commonMain/.../ui/` root still holds agent/chat/session widgets (~3k lines): `AgentAvatar`,
  `AhqRoleIcons` (746), `AhqHarnessIcons`, `RoleIcon`, `AssigneeLabel`, `ChatAttachments`, `ChatBlockRenderers`,
  `ChatPanelState`, `ChatSpans`, `ExecutorPicker`, `SeededExecutorPicker`, `RunPills`, `SessionStopActions`,
  `ResumeSession`, `RestartSession`, `ReasoningPanelUi`, `ReportFlagChip`, `LayerSkillsPanel`, `ProfileExecutorField`,
  `AttentionKit`, `AssistantEntry/PanelState`, `ChangedFiles`, `DiffLine`, `EffortViews`, `ContextLimitViews`,
  `LimitsSection`, `TicketImages`, `TicketTag`, `TodoBox`, `SwitchAgentPicker`; and `model/Model.kt` (2,400 lines,
  sessions/agents/tickets state).
- The shell's daemon is the full daemon (`infantry`, `infantryCore`, `infantryBoard` jars on the shell classpath): by
  design (bundled daemon), it runs with no harness.
- The empty left/right columns are the frame's slots; they still take width with nothing in them.

The earlier list in docs/EMPTY-SHELL-DESIGN.md ("EpicsPane, PromptsPane, ... ProjectPicker, TitleBar") is out of date:
none of those is in infantryUiCore any more.

## Shots

| File | What |
|---|---|
| 01-empty-shell.png | zero plugins: drop box + Configuration |
| 02-after-choose-file.png | Choose file -> consent dialog for Example_Starter.ihq |
| 03-after-install.png | after Install: back to the drop box (refused for a missing dependency, silently) |
| 04-configuration.png | Configuration > General |
| 05-configuration-plugins.png | Configuration > Plugins: voice + the refused module's card |
| 06-shell-plus-settings.png | mode `root` (Infantry_Settings only): loaded, draws nothing |
