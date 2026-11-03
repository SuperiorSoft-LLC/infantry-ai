# Infantry AI wiki

Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail,
shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.

These pages are written from the code on the dev head. Where an older document disagrees with the code, the code
wins, and the page says so.

- [Architecture](Architecture.md): the three layers, the daemon, the protocol, a request from click to daemon and back, slots, scripting.
- [Recipes](recipes/README.md): worked how-to-add-X guides (UI plugin, child plugin, daemon tool, setting, Lua hook, harness, provider).
- [Plugins](Plugins.md): `module.json`, the UI and daemon halves, block types, panels and slots, config fields, building, installing, signing, and a hello plugin step by step.
- [Make a plugin](make-a-plugin.md): step by step from the template to a built UI + daemon example, every `module.json` field, slots and child plugins.
- [UI plugin API](plugin-api.md): every public type and function of the UI plugin API, with examples.
- [Daemon plugin API](daemon-plugin-api.md): tools, routes, hooks, events, services, storage, lifecycle, the guard.
- [Scripting](Scripting.md): the Lua API, hooks, `ih.again` phases, the process bridge (`exec`), chat notes, progress, the session bridge, settings.
- [Harnesses and providers](Harnesses.md): the harness API, its contributions, how the three built-in harnesses use it, adding a harness and a provider.
- [Settings](Settings.md): scopes (app, project, role, session), cost-saving defaults and what each opt-in costs.
- [Licensing (DRAFT)](licensing.md): why the core is MPL-2.0 and everything else Apache-2.0, per module, the compatibility promise as implemented.

Start with the `README.md` for install and first run.

Commands in these pages were checked against `infantry --help` (`agenthq` is an alias of `infantry`) and the CLI
source. Screenshots are listed in `docs/img/README.md`.
