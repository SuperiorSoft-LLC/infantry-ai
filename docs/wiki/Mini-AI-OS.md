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

Core is MPL-2.0. The SDK and the plugins are Apache-2.0. Nothing crosses that line (guard from #45). See
[licensing](licensing.md).

## 8. The rule for every design

**Can a user or an agent build and share a whole app without touching core?** If not, you are missing a seam: add the
generic seam, not the feature (AGENTS.md rule 1).

See also: [Architecture](Architecture.md), [Plugins](Plugins.md).
