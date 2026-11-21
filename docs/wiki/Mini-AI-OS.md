# Infantry AI: a mini AI operating system

Read this before any design. It is the identity every other page follows.

## 1. Identity

Infantry AI is a **mini AI operating system**. Agents are the cells; Infantry AI is the body. It is an everything app
on which users build their own apps.

**The plugins are basically apps.** In the mini AI OS, a plugin is an app: a daemon half, UI components, tools and
scripts. The installer is the app store's bootstrap and the marketplace is the app store. On this page, and in the
user's view, "plugin" and "app" mean the same thing; the SDK and API names keep "plugin".

## 2. Boot chain

1. **Core is the kernel.** It holds generic abstractions only: frames, events and hooks, slots, settings, vault,
   processes, files, progress and the plugin host. It names no feature.
2. When nothing is installed, core loads the **installer plugin** as the bootstrap.
3. The installer installs and loads the **UI plugin** (the shell plus a LayoutProvider).
4. The UI loads the **component plugins** (board, agents, mail, notes...).

With plugins installed, it opens like a normal app.

Status: step 2 is built (INSTALLER-1). `Infantry_Installer` is the bootstrap plugin, named by the generic `plugins.bootstrap`
setting or its manifest flag, never by core. With nothing else installed the daemon reports bootstrap mode and the app draws
the installer's Apps screen; an install is live. Step 3 is the UI plugin (Infantry_Ui, BOOT-CHAIN-1): without it the installer stays the window. Before it the installer installs
the component plugins and the shell stays built in. See [Plugins](Plugins.md#the-installer-and-the-bootstrap-plugin).

Apps (APPS-ROOT-1): the Apps screen lists **apps**, not plugins. An app is a module whose manifest has `app: { core, optional }` (see [Plugins](Plugins.md)); installing it installs its core plugins and removing it removes them, and the app's plugins are managed inside the app, in its Plugins tab. The installer is a system app (not removable); a plugin that belongs to no app sits under "Other plugins". Today the one app is Infantry AI (`Infantry_Ui`).

Proof: `pwsh scripts/shell-only-check.ps1` builds the **shell-only** flavour (the core daemon plus the installer, nothing
else), boots it on a throwaway state dir and port, installs the UI plugin and the rest of the plugin set from a local
plugin repo folder (`-PluginRepo`, default: staged from this checkout), checks that the board works (create a ticket, list
it), and compares the result with the bundled flavour (every plugin pre-installed): same plugins, same agent tools, and the
size and idle memory of both. `-Serve shell-only` leaves that flavour running with a Vite shell on it, to look at.
The board itself is still a core crate here (the LIC tickets move it into a plugin), so the installer offers the other plugins.

## 3. Splash and boot progress

Core owns the splash and the boot progress: core, then installer, then UI, then plugins N/M. Plugins report progress
through core's seam and never draw the splash.

## 4. Layouts

Plugins contribute components, never positions. A layout (a LayoutProvider, ticket LAYOUT-1 #82) decides placement,
and users build their own.

## 5. Same behaviour

A bundled build and a build installed from a repo are indistinguishable. A restructure never changes what the user
sees.

## 6. Host-agnostic

Core assumes no desktop window. The host seam (#70) lets the same chain run in a window, a browser, a phone, or one
day a bare machine or kiosk.

## 7. Licences

Core is MPL-2.0, and that includes the plugin host (the kernel's program loader, LIC-2). The SDK, the runner contract
crate and the plugins are Apache-2.0 (LIC-1). Nothing crosses that line (guard from #45). A plugin never runs as the
owner: it gets no owner-only frames, and it reads only its own secrets. See [licensing](licensing.md).

## 8. The bet

The stack is locked: React, Tauri 2 and Rust. Plugins are WebAssembly components (`infantry-plugin-wit`), so one build
can run in the daemon, the browser and the phone. Cheap resources mean cheap hosting.

## 9. The rule for every design

**Can a user or an agent build and share a whole app without touching core?** If not, you are missing a seam: add the
generic seam, not the feature (AGENTS.md rule 1).

See also: [Architecture](Architecture.md), [Plugins](Plugins.md).
