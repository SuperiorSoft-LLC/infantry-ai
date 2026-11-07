[← Wiki index](index.md)

# Infantry AI wiki

<!-- shared:pitch -->Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.<!-- /shared:pitch -->

> **Beta.** <!-- shared:beta_line -->Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet: you are an early tester. Please report anything that breaks on GitHub.<!-- /shared:beta_line -->  
> [Report a problem](https://github.com/SuperiorSoft-LLC/infantry-ai/issues)

These pages are written from the code on the dev head. Where an older document disagrees with the code, the code
wins, and the page says so.

- [Architecture](Architecture.md): the three layers, the daemon, the protocol, a request from click to daemon and back, slots, scripting.
- [Recipes](recipes/README.md): worked how-to-add-X guides (UI plugin, child plugin, daemon tool, setting, Lua hook, harness, provider).
- [Plugins](Plugins.md): `module.json`, the UI and daemon halves, block types, panels and slots, config fields, building, installing, signing, and a hello plugin step by step.
- [Plugin lifecycle](Plugin-lifecycle.md): live swap of one plugin (install, update, enable, disable, remove), `PluginScope`, the leak check, and the do and don't for authors.
- [Make a plugin](make-a-plugin.md): step by step from the template to a built UI + daemon example, every `module.json` field, slots and child plugins.
- [UI plugin API](plugin-api.md): every public type and function of the UI plugin API, with examples.
- [Daemon plugin API](daemon-plugin-api.md): tools, routes, hooks, events, services, storage, lifecycle, the guard.
- [Scripting](Scripting.md): the Lua API, hooks, `ih.again` phases, the process bridge (`exec`), chat notes, progress, the session bridge, settings.
- [Harnesses and providers](Harnesses.md): the harness API, its contributions, how the three built-in harnesses use it, adding a harness and a provider.
- [Board and epics](Board.md): the ticket board, epics, and moving tickets between epics (board, MCP, CLI).
- [Settings](Settings.md): scopes (app, project, role, session), cost-saving defaults and what each opt-in costs.
- [Known failures](../KNOWN-FAILURES.md): the test classes that fail on dev too; a scoped run that hits one is not your bug.
- [Licensing (DRAFT)](licensing.md): why the core is MPL-2.0 and everything else Apache-2.0, per module, the compatibility promise as implemented.

Start with the `README.md` for install and first run.

Commands in these pages were checked against `infantry --help` (`agenthq` is a deprecated alias of `infantry`) and the CLI
source. Screenshots are listed in `docs/img/README.md`.

## Support Infantry AI

<!-- shared:support_line -->The core (MPL-2.0) is the community foundation. Sponsorship and donations pay for keeping it stable and backward compatible.<!-- /shared:support_line -->

- [Sponsor on GitHub](https://github.com/sponsors/SPONSORS_ACCOUNT) (monthly)
- [Donate once](https://buy.stripe.com/STRIPE_PAYMENT_LINK) (you choose the amount)

## Reporting a problem

Infantry AI is a Beta, so your reports matter. Open an issue at <https://github.com/SuperiorSoft-LLC/infantry-ai/issues> and include:

- the build number (shown in the app footer, and under Configuration > About);
- the steps that led to the problem, and what you expected instead;
- the logs from the daemon's log folder (state directory) if the problem involves an agent or the daemon.
