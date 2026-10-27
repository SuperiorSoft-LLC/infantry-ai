# Vision: Infantry AI is an AI framework

**Your AI doesn't just run your workflow. It builds your workflow.**

Infantry AI is an AI framework. It is no longer a project-management app: it is the components to build something else. You can build a completely new application with it, or use the platform itself to build the application you want.

The AI is no longer just the builder. The agents are the cells of the body: they evolve the application into something else, and you can use the application itself to build something new. The project-management board is one app built on the framework.

## What it is made of today

- **Block-array shell**: the window is an array of blocks (today 19 Kotlin blocks; JSON block trees are planned, BLOCKS-1); layout is itself a block type. Design: [BLOCKS-LAYOUT-DESIGN.md](BLOCKS-LAYOUT-DESIGN.md). The full block-layout migration is **planned**.
- **Plugin modules**: a folder with a `module.json` and a jar, found by scanning. `module.json` hooks (interceptors, container slots, events, panel overrides) act in the client. See [plugins/GUIDE.md](../plugins/GUIDE.md).
- **Daemon as the data layer**: tickets, agents, mail and project knowledge live in the daemon, reached by the UI, the CLI and plugins through its API.
- **Keystore**: the daemon seals secrets in a keystore.
- **Terminal runs**: agents and commands run in terminal sessions the daemon manages.
- **Marketplace** for plugins: **planned**.

## The extension rule

Extend without editing core. Works today: `module.json` hooks (interceptors, container slots top/bottom, events `modules.loaded` and `ticket.changed`, panel preferences).

- Override by node id (replace or wrap a node): **planned** (BLOCKS-1 and layout step L5).
- Open classes for plugin-facing types: **planned** (the plugin API module, see `docs/LICENSE-0-REPORT.md` on branch `ticket/08877cc7-license-0-evaluation-only-measure-core-v`).

If you need to change core to add something, that is a gap in the framework; file it.

## Licence

Licence terms to be announced.
