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

Conventions in every recipe:

- `Acme_Something` is a made-up vendor and module name. Use your own `Vendor_Module` (letters, digits, one underscore).
- A path in a code block is a file you create. A path in running text exists in the repository today.
- Build one project at a time: `./gradlew <project> --console=plain`.
- A community module is off until `plugins/modules.json` enables it and grants what it uses (the file already has an
  example entry).
