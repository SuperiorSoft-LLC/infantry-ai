# Infantry AI wiki index

<!-- shared:pitch -->Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.<!-- /shared:pitch -->

> **Beta.** <!-- shared:beta_line -->Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet: you are an early tester. Please report anything that breaks on GitHub.<!-- /shared:beta_line -->  
> [Report a problem](https://github.com/SuperiorSoft-LLC/infantry-ai/issues)

Every page of this wiki is listed here, one line each. The pages are written from the Rust code on `main`. Where a page
and the code disagree, the code wins. Pages that describe the Kotlin app carry a `legacy-java` banner and keep their old
text under a legacy heading.

## Start here

- [Architecture](Architecture.md): the Rust framework: the daemon, the layers, the wire, plugins, scripting, clients.
- [Install](Install.md): the Windows installer, the headless daemon, the `infantry` command on PATH, building from source.
- [Android](Android.md): the Android app, pairing a phone to a daemon, building the APK.
- [Updates](Updates.md): the update channels, the feed, the signing keys, the release pipeline.
- [Command line](CommandLine.md): the native `infantry` command and what it hands to the Kotlin launcher.
- [Home](Home.md): the wiki front page, the support and reporting pages.

## Build plugins

- [Plugins](Plugins.md): `module.json`, the daemon half (WASM or sidecar), grants, the official plugins, building with `stage.sh`.
- [Make a plugin](make-a-plugin.md): step by step, from an official plugin to a built daemon half.
- [Daemon plugin API](daemon-plugin-api.md): the WIT world: exports, imports, tools, frames, routes, hooks, events.
- [Plugin knowledge](plugin-knowledge.md): module.json `knowledge` and the PLUGIN TOOLS briefing.
- [Plugin lifecycle](Plugin-lifecycle.md): install, update, enable, disable and remove a plugin.
- [Publish a plugin](publish-a-plugin.md): package a plugin from its own repo.
- [UI plugin API](plugin-api.md): the Kotlin UI plugin API (legacy-java).
- [Compatibility and deprecation](compatibility.md): the ABI promise for daemon plugins, and the Kotlin policy.
- [Recipes](recipes/README.md): worked how-to-add-X guides, one per page: daemon tool, UI panel, child plugin, setting, Lua hook, harness, provider, avatar style, service.

## Run and automate

- [Harnesses](Harnesses.md): the harness contract and the four harness crates (Claude Code, Codex, opencode, pi).
- [Runners](runner-quickstart.md): local and linked remote runners, placement, setup, troubleshooting.
- [Run a remote environment](run-a-remote-environment.md): a runner-only daemon (`--mode runner`) and pairing it.
- [Scripting](Scripting.md): the sandboxed Lua API, hooks, the process bridge, settings that govern scripts.
- [Script Arsenal](script-arsenal.md): the global scripts, resolution order and the bundled set.
- [Board](Board.md): tickets, lanes and epics.
- [Notifications](Notifications.md): the notification bus, attention items and the presenters.
- [Health](Health.md): the daemon health and environment report.
- [Settings](Settings.md): scopes (app, project, role, session) and the cost-saving defaults.
- [Secrets](Secrets.md): render text with saved secrets (`{{secret:NAME}}`).
- [Vault](Vault.md): the master key, Reveal and Copy after Windows Hello, the encrypted backup.
- [Usage](Usage.md): live plan meters, pacing and overrides.
- [Waiting](Waiting.md), [Memory](Memory.md), [Login](Login.md), [Progress](Progress.md), [Bridges](Bridges.md), [Browser](Browser.md): smaller features, each checked against its Rust crate.

## Design records and Kotlin-era pages

- [Licensing](licensing.md): MPL-2.0 for the core, Apache-2.0 for the SDKs, apps and plugins.
- [Kotlin notes](kotlin-notes.md): the Kotlin UI core, its toolchain and build flags (legacy-java).
- [UI driver](UI-driver.md), [UI verify design](UI-verify-design.md): driving and checking the UI (Kotlin era, legacy-java).
- [App preview of a branch](app-preview.md), [Variant builds](Variant-builds.md): Gradle-era branch builds (legacy-java).
- [Agent avatar mascot design](Agent-avatar-mascot-design.md), [Runner pairing design](RunnerPairing-design.md): design records (Kotlin era).

The design records under `docs/rust-port/` (plan, layers, migration, parity, move-over bar) are listed in
[docs/README.md](../README.md).
