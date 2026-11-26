# Knowledge, scripting, settings and secrets

## Knowledge

**Notes** are what your agents remember. A note has a name and a scope:

| Scope | Who sees it |
|---|---|
| project | Every agent of the project. |
| role | Agents of one role. |
| session | One agent session. |

There are also **global** notes, shared by every project. A project reads them too.

```
infantry memory index
infantry memory show <name>
infantry memory search <words>
infantry memory save <name> --description "..."
```

**Skills** are instructions an agent can use. They come in three layers: global, project and role. An agent gets all
three.

## Scripting

Scripts automate your own process. They are written in **Lua** and run inside the daemon, with no model and no tokens.
A script cannot read or write files; it only uses the Infantry AI API.

Scripts are stored:

- per project, in `<project>/.infantry/scripts/`, or
- for every project, in the **Script Arsenal** (the app's `global-scripts` folder).

Plugins can ship scripts too. A project script wins over a global one with the same name.

```
infantry script list
infantry script save <name> --title "..." --description "..." --file my-script.lua
infantry script delete <name>
```

### Hooks

A hook runs a script when something happens. There are three kinds:

| Hook | Example |
|---|---|
| An event | `ticket.commented`, `ticket.*` |
| A lane change | `lane enter done` |
| A timer | `every 300s` |

```
infantry script hook list
infantry script hook add "lane enter done" my-script
infantry script hook remove <id>
```

When a hook fails, you hear about it: as a comment on the ticket, or as mail to you.

### Triggers

A **trigger** is a named rule that starts work. It either starts the next ticket or starts a prompt session. A trigger
fires by hand, on a schedule, from a webhook, or when a ticket is done. Triggers are rate-limited, so they cannot run
away.

## Settings

Open **Settings** in the app, or use the command line. A setting can be set at four levels, and the narrowest one wins:

`app` < `project` < `role` < `session`

```
infantry settings get <key>
infantry settings set <key> <value> --scope project
infantry settings explain <key>
infantry settings limits
```

## Secrets

Secrets are passwords, keys and tokens. Add them in **Settings > Secrets**. A value goes in once and is never shown
again, except when you **Reveal** or **Copy** it after Windows Hello.

Secrets are kept encrypted. On Windows the key is protected by your Windows account.

Agents never see a secret's value. They write a template with a placeholder, and the daemon fills it in:

```
API_KEY={{secret:my-api-key}}
```

```
infantry secret render config.template --out config.env
infantry secret find <words>
```

`render` writes the filled-in file, readable only by you. Project secrets are used before app secrets.

## Health

`infantry health` shows whether the daemon is up, which plugins are loaded, the hooks, and the free memory and disk.
