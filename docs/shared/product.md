---
name: Infantry AI
tagline: Run a whole team of coding agents from one ticket board.
sub: Every agent works on its own git branch, and you stay in charge of the merge. On your own machine, with no third-party cloud in between.
pitch: Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.
license_line: The core (the daemon) is the foundation: it is licensed MPL-2.0 to protect its integrity, so apps and plugins can rely on a stable base that does not break, with backward compatibility as the promise. Everything built on top (the SDKs, the UI core, the apps and every plugin) is free under Apache-2.0: build, fork, sell and extend it without copyleft.
license_footer: The core is a community effort; the rest is yours.
platforms: Windows desktop (MSI). Android app (APK, beta: debug-signed). Web (Kotlin/wasm, zip; the daemon also serves it at /). macOS desktop (beta, unsigned, arm64). Headless server (Linux/Windows; Docker). Built with Kotlin Multiplatform (Compose).
version: 1.0.8
downloads_url: https://github.com/SuperiorSoft-LLC/infantry-ai/releases/latest
downloads_assets: Windows MSI, Android APK (beta, debug-signed), web zip, server archives; macOS beta .dmg when its job builds.
status: Beta
beta_line: Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet: you are an early tester. Please report anything that breaks on GitHub.
issues_url: https://github.com/SuperiorSoft-LLC/infantry-ai/issues
website: https://infantryai.superiorsoftllc.tech/
repo: https://github.com/SuperiorSoft-LLC/infantry-ai
wiki: https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/docs/wiki/index.md
plugins_page: https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/docs/wiki/make-a-plugin.md
licensing_page: https://github.com/SuperiorSoft-LLC/infantry-ai/blob/main/docs/wiki/licensing.md
sponsor_url:
donate_url:
support_line: The core (MPL-2.0) is the community foundation. Sponsorship and donations pay for keeping it stable and backward compatible.
features:
  - account_tree | One branch per ticket | Lanes To Do, In Progress, Pending and Done. Role, type and weight per ticket.
  - extension | Plugins inside plugins | Tabs, panels, daemon tools and harnesses are plugins with a module.json.
  - data_object | Lua scripting and hooks | Hooks run a script on lane changes, mail, a timer or a named event.
---

# Shared product facts

The single source for the product facts that the app wiki (`README.md`, `docs/wiki/Home.md`, `docs/wiki/licensing.md`)
and the Infantry AI Site project (plain page and Vue app, via `npm run sync` in its `vue/` folder) both use.
Edit the front matter above; never copy these facts by hand.

Front matter format: `key: value` lines, and `key:` followed by `  - a | b | c` list items. Nothing else.

In a wiki page, a shared fact is written as `<!-- shared:KEY -->text<!-- /shared:KEY -->` and
`SharedDocsGuardTest` fails when the text differs from the value here.
