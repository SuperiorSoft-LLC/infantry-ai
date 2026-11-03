# CORE-CLEAN-1: core is abstraction only

Owner (2026-10-29): if it is not abstract it goes out; once migrated to a plugin it is deleted from core, no stopgap. Standing approval: safe + feature preserved as a plugin = delete.

## Audit

| Item | Verdict | Where it lives / ends |
|---|---|---|
| `ConfigEntry`, `ShellConfigSettings` | dead, already gone (no source hit). `NodeConfigEntry` is the live abstract config contract | stays (core config) |
| 8 SEC-FIX-1 ratchet files (PluginDropIn, ShellDropIn, DesktopDropIn, DesktopPlugins, JvmEndpointAccess, JvmPlatformHost, ConfigDialog, ModulesSection) | abstract mechanism, demo-named | renamed: `demoLock` -> `lockdown`, texts "The lockdown is on". The mechanism (a deployment can lock plugin install and community modules) is generic and stays |
| `PluginFolders.demoLock()` / `DEMO_LOCK_ENV` | abstract mechanism | `PluginFolders.lockdown()`, env `INFANTRY_LOCKDOWN`; legacy `INFANTRY_DEMO_LOCK` still read so a deployed demo keeps working (remove the alias when the Demo_Lock deployment sets the new name) |
| refusal texts in ModuleResolver / EndpointGate / ServiceRegistry | abstract | say "lockdown" |
| `FreshShell` excluded `Infantry_Board` / `infantryAppUi` | concrete plugin id in core | now data: app build passes `-Dinfantry.freshShell.exclude=...`; core names none |
| `ModulesConfig.demoLock` / `DemoLockConfig` (modules.json key `demoLock`) | abstract allow-list, demo-named, persisted in users' modules.json | **stays for now**: renaming the JSON key breaks existing files. Needs a read-both migration; ticket to file |
| daemon DemoLock, visitor token, per-IP limit | already migrated by DAEMON-PLUGINS-1 / POLICY-1 (Demo_Lock plugin + `NoDemoInDaemonCoreGuardTest`) | done, not touched here |

## Guards

`NoDemoInCoreGuardTest`: baseline map removed, zero allowed demo names in core and official plugins. New: `core names no concrete plugin id` (no `Infantry_*`, `Demo_*`, `Acme_*` in non-comment core code).

## Replacing a core concern

A top-level plugin declares a shell override of a placeholder (`infantry.shell.frame`, `body`, `panes`) in its module.json and registers the block with `registrar.shell(name, override)`. The factory puts it in the placeholder's place; the frame replacement receives the host surface; the dialog layer stays core's. `TopLevelFramePluginTest` registers such a plugin through `registerModule` and checks the factory draws it with no problems. Policy is the same shape on the daemon side: a plugin ships `policies/<name>.json` (see Demo_Lock) selected by data, so a custom core supplies its own policy without touching core code.
