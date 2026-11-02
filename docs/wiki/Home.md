# Infantry AI wiki

Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail,
shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.

These pages are written from the code on the dev head. Where an older document disagrees with the code, the code
wins, and the page says so.

- [Plugins](Plugins.md): `module.json`, the UI and daemon halves, block types, panels and slots, config fields, building, installing, signing, and a hello plugin step by step.
- [Scripting](Scripting.md): the Lua API, hooks, `ih.again` phases, the process bridge (`exec`), chat notes, progress, the session bridge, settings.
- [Harnesses and providers](Harnesses.md): the harness API, its contributions, how the three built-in harnesses use it, adding a harness and a provider.
- [Settings](Settings.md): scopes (app, project, role, session), cost-saving defaults and what each opt-in costs.

Start with the [README](../../README.md) for install and first run.

Commands in these pages were checked against `infantry --help` (`agenthq` is an alias of `infantry`) and the CLI
source. Screenshots are listed in [docs/img/README.md](../img/README.md).
