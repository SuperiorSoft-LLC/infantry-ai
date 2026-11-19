[← Wiki index](../index.md)

# Recipes: how to add X

Short, complete recipes. Each one starts from code that is in the repository and builds, names the exact files to
touch, and says how to check the result. Read [Architecture](../Architecture.md) first for the picture.

| Recipe | You get |
|---|---|
| [Add a UI panel plugin](add-ui-panel-plugin.md) | A tab and a side-panel section from a copy of the starter template. |
| [Split a section into a child plugin](split-into-child-plugin.md) | A host plugin's section moved into a plugin that fills a slot. |
| [Add a daemon tool](add-daemon-tool.md) | A tool that agents and clients can call, behind the access policy. |
| [Add a setting with scopes](add-setting-with-scopes.md) | A plugin setting, and a core setting that works per project, role and session. |
| [Add a Lua hook](add-lua-hook.md) | A script that runs when a ticket changes lane. |
| [Add a CLI harness](add-cli-harness.md) | Support for another agent CLI, by descriptor or by plugin. |
| [Add a provider](add-provider.md) | A model group in a harness's picker. |
| [Add your service to the Services hub](add-a-service.md) | A third-party service in the Services tab: sign-in, account, resources, agent tools, billing links. |
| [Add an avatar style](add-an-avatar-style.md) | An agent avatar drawn by your own renderer, chosen by the `agentAvatarStyle` preference. |
| [Start a test daemon for runner repros](test-daemon-for-runner-repros.md) | Two scrubbed test daemons (PC and runner) from a built dist, paired and torn down by PID. |

**Rust status.** The framework on main is Rust. [Add a daemon tool](add-daemon-tool.md) is rewritten for it. The UI-panel,
child-plugin, setting-scope, avatar and Services recipes have a Rust note at the top and keep the Kotlin steps under a
legacy heading. Where a Rust path is not verified, the note says so.

Conventions in every recipe:

- `Acme_Something` is a made-up vendor and module name. Use your own `Vendor_Module` (letters, digits, one underscore).
- A path in a code block is a file you create. A path in running text exists in the repository today.
- Build one project at a time. Rust: `cargo build -p <crate>` from `rust/`, and `rust/plugins/stage.sh <out-dir>` for plugins. The Gradle commands inside the older recipes are Kotlin history.
- A community module is off until `plugins/modules.json` enables it and grants what it uses (the file already has an
  example entry).
