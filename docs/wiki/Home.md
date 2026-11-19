[← Wiki index](index.md)

# Infantry AI wiki

<!-- shared:pitch -->Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.<!-- /shared:pitch -->

> **Beta.** <!-- shared:beta_line -->Infantry AI is in Beta. It works and we use it every day, but not every part is fully tested yet: you are an early tester. Please report anything that breaks on GitHub.<!-- /shared:beta_line -->  
> [Report a problem](https://github.com/SuperiorSoft-LLC/infantry-ai/issues)

These pages are written from the Rust code on `main`. Where an older document disagrees with the code, the code
wins, and the page says so. The full list is in the [wiki index](index.md).

- [Install](Install.md): the Windows installer, the headless daemon, `infantry` on your PATH.
- [Android](Android.md): pair the phone to a daemon. The phone runs no agents.
- [Updates](Updates.md): the stable and nightly channels, how an update is installed.
- [Architecture](Architecture.md): the daemon, the layers, the wire, plugins, a request from the app to the daemon and back.
- [Command line](CommandLine.md): the `infantry` command.
- [Plugins](Plugins.md) and [Make a plugin](make-a-plugin.md): the module manifest, the daemon half, building it.
- [Scripting](Scripting.md): the Lua API, hooks, the process bridge.
- [Harnesses](Harnesses.md): the agent CLIs the daemon drives.
- [Settings](Settings.md): scopes and the cost-saving defaults.
- [Known failures](../KNOWN-FAILURES.md): the test classes that fail on dev too.
- [Licensing](licensing.md): why the core is MPL-2.0 and everything else Apache-2.0.

Start with the [README](../../README.md) for what Infantry AI is and how to get it running.

Commands in these pages were checked against the code (`rust/crates/infantry-cli`, the daemon's `main.rs`, the app's
`tauri.conf.json` and the release workflows).

## Support Infantry AI

<!-- shared:support_line -->The core (MPL-2.0) is the community foundation. Sponsorship and donations pay for keeping it stable and backward compatible.<!-- /shared:support_line -->

- [Sponsor on GitHub](https://github.com/sponsors/SPONSORS_ACCOUNT) (monthly)
- [Donate once](https://buy.stripe.com/STRIPE_PAYMENT_LINK) (you choose the amount)

## Reporting a problem

Infantry AI is a Beta, so your reports matter. Open an issue at <https://github.com/SuperiorSoft-LLC/infantry-ai/issues> and include:

- the build number (shown in the app footer, and under Configuration > About);
- the steps that led to the problem, and what you expected instead;
- the logs from the daemon's log folder (state directory) if the problem involves an agent or the daemon.
