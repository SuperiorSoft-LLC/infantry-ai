# Vision: Infantry AI is an AI framework

**Your AI doesn't just run your workflow. It builds your workflow.**

Infantry AI is an AI framework. It is no longer a project-management app: it is the components to build something else. You can build a completely new application with it, or use the platform itself to build the application you want.

The AI is no longer just the builder. The agents are the cells of the body: they evolve the application into something else, and you can use the application itself to build something new. The project-management board is one app built on the framework.

## What it is made of today

- **Block-array shell**: the window is an array of blocks (JSON block trees); layout is itself a block type. Design: [BLOCKS-LAYOUT-DESIGN.md](BLOCKS-LAYOUT-DESIGN.md). The full block-layout migration is **planned**.
- **Plugin modules**: a folder with a `module.json` and a jar, found by scanning. `module.json` hooks (interceptors, container slots, events, panel overrides) act in the client. See [plugins/GUIDE.md](../plugins/GUIDE.md).
- **Daemon as the data layer**: tickets, agents, mail and project knowledge live in the daemon, reached by the UI, the CLI and plugins through its API.
- **Keystore**: the daemon seals secrets in a keystore.
- **Terminal runs**: agents and commands run in terminal sessions the daemon manages.
- **Marketplace** for plugins: **planned**.

## The extension rule

Extend by override, without editing core: `module.json` hooks, node ids (replace or wrap a node by its id) and open classes. If you need to change core to add something, that is a gap in the framework; file it.

## Licence

Licence terms to be announced.
