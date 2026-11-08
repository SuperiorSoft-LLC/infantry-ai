# Infantry AI wiki index

<!-- shared:pitch -->Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.<!-- /shared:pitch -->

> **Beta.** <!-- shared:beta_line -->Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet: you are an early tester. Please report anything that breaks on GitHub.<!-- /shared:beta_line -->  
> [Report a problem](https://github.com/SuperiorSoft-LLC/infantry-ai/issues)

Every page of this wiki is listed here, one line each. The pages are written from the code; where they disagree with it, the code wins.

## Start here

- [Home](Home.md): the wiki front page, how the pages were checked, how to support the project.
- [Architecture](Architecture.md): the three layers, the daemon, the protocol, a request from click to daemon and back, slots, scripting.

## Build plugins

- [Plugins](Plugins.md): `module.json`, the UI and daemon halves, config fields, building, installing, signing, a hello plugin.
- [Plugin lifecycle](Plugin-lifecycle.md): live swap of one plugin (install, update, enable, disable, remove), `PluginScope`, the leak check, and the do and don't for authors.
- [Make a plugin](make-a-plugin.md): step by step from the template to a built UI + daemon example.
- [Publish a plugin](publish-a-plugin.md): package a plugin from its own repo as an `.ihq` and list it in the community registry.
- [Run a remote environment](run-a-remote-environment.md): start a runner-only daemon (`--mode runner`, Docker) and pair it.
- [Runner quickstart](runner-quickstart.md): pair a server, pick it for an agent, run a ticket there (proven end to end).
- [Runner pairing design](RunnerPairing-design.md): RUNNER-PAIR-UX-1 (2026-11-08): today's 12 pairing steps and the one-link flow that replaces them (design record, tickets in backlog).
- [UI plugin API](plugin-api.md): every public type and function of the UI plugin API.
- [Daemon plugin API](daemon-plugin-api.md): tools, routes, hooks, events, services, storage, lifecycle.
- [Progress and downloads](Progress.md): the one progress/loader system and the one downloader; a plugin presenter, renderer or transport.
- [Bridges](Bridges.md): plugin capabilities and events, callable from scripts, agents (MCP) and other plugins.
- [Harnesses and providers](Harnesses.md): the harness API, the built-in harnesses, adding a harness or a provider.

## Automate

- [Scripting](Scripting.md): the Lua API, hooks, the process bridge, chat notes, progress, the session bridge.
- [Script Arsenal](script-arsenal.md): the app-wide global scripts every project can run and refine, with a bundled starter set.
- [Add a Lua hook](recipes/add-lua-hook.md): run a script on a lane change, mail, a timer or a named event.

## Configure

- [Board and epics](Board.md): epics, and moving tickets between epics.
- [Notifications](Notifications.md): the ONE notification bus (in-app toasts, OS notifications), presenters a plugin can override, routing settings, the inventory.
- [Health](Health.md): `infantry health`, the daemon health and environment report (up/down, restarts and why, MCP bridges, OS/shell/ports).
- [Memory](Memory.md): project knowledge notes, the project / global / role / session scopes, the `infantry_memory` MCP actions (read and write), who may write global notes.
- [Settings](Settings.md): scopes (app, project, role, session) and the cost-saving defaults.
- [Kotlin notes](kotlin-notes.md): toolchain versions, UI core source sets and expect/actual, Compose MP pitfalls, build flags.
- [Updates](Updates.md): the one update engine for the app and every plugin; per-plugin sources, private and paid ones.

## Recipes

- [Recipes overview](recipes/README.md): how the how-to-add-X guides are laid out.
- [Add a UI panel plugin](recipes/add-ui-panel-plugin.md): a new tab, panel or settings card.
- [Split into a child plugin](recipes/split-into-child-plugin.md): let another plugin extend yours through a slot.
- [Add a daemon tool](recipes/add-daemon-tool.md): an agent/MCP tool, route or hook on the daemon.
- [Add a setting with scopes](recipes/add-setting-with-scopes.md): a named setting with app, project, role and session scope.
- [Add a Lua hook](recipes/add-lua-hook.md): automate a process with a script and a hook line.
- [Add a CLI harness](recipes/add-cli-harness.md): support another agent CLI.
- [Add a provider](recipes/add-provider.md): add a model group to the picker.

## Project

- [Licensing (draft)](licensing.md): why the core is MPL-2.0 and everything else Apache-2.0.
- [Compatibility and deprecation policy (draft)](compatibility.md): what backward compatible means, support period, notice.
- [CONTRIBUTING](https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/CONTRIBUTING.md): how to contribute.
- [AGENTS.md](https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/AGENTS.md): the guide for AI coding agents.
- [llms.txt](https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/llms.txt): the same map in the llms.txt format.
