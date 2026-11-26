# Publish, install and update a plugin

A built plugin is a folder: `module.json` plus what it names (`daemon.component` or the sidecar, `ui/dist`, `data`
folders). Publishing means putting that folder where a daemon's **source** can reach it. The daemon installs from its
sources into its plugins folder, with no restart.

Truth: `rust/crates/infantry-daemon/src/compose/sources.rs` (sources and catalogs),
`rust/crates/infantry-daemon/src/compose/installer.rs` (install, update, uninstall),
`rust/crates/infantry-daemon/src/compose/plugins.rs` (`plugin_admin`), `rust/plugins/official/Infantry_Plugins` (the
agent tools).

## The bundle layout

Every source, and the plugins folder itself, uses the same layout:

```
<root>/official/<Module>/module.json
<root>/official/<Module>/daemon.component     # or bin/<exe> for a sidecar
<root>/official/<Module>/ui/dist/...           # if the module has a web UI half
<root>/core/<Module>/...
```

`rust/plugins/stage.sh <out-dir> [release]` writes exactly this for the plugins under `rust/plugins/official` (see
[Build and load](build.md)); a plugin outside the repository copies its built files into the same shape.

## Sources

A source is one of:

| Kind | Location | How it is read |
|---|---|---|
| Folder | a local path | read in place |
| Git | a repository URL (`https://...`, `git@...`, `ssh://`, `git://`, `file://`, or ending in `.git`) | cloned under `<state>/plugin-sources/`, refreshed with a fast-forward pull |
| Catalog | an `http(s)` URL of a `.json` file | each listed file downloaded into a local copy |

A catalog document:

```json
{ "modules": [
  { "name": "Acme_Hello", "tier": "official", "base": "files/Acme_Hello",
    "files": ["module.json", "daemon.component"] }
] }
```

`base` is a URL or a path next to the catalog. `tier` must be `official` or `core`. A file path may not leave the module
folder. Each download is capped by the setting `catalog.maxFileBytes` (default 64 MiB). A failed refresh keeps the
previous copy.

The built-in sources (the bundled folder and the `plugins.sources` setting) come first, then the ones the user added.
The user's list is `plugin-sources.json` in the state dir, shared by every connected client.

## Commands

All are operations of the owner-only frame `plugin_admin` (`{"args": [op, id?]}`, answered `{code, out, err}`), which the app's plugin manager sends. The native `infantry` CLI has no `plugin` command yet.

| Op | Does |
|---|---|
| `status` / `list` | the installed modules and their load status |
| `available` | what the sources offer |
| `search <text>` | search the sources |
| `show <Id>` | one module from the sources |
| `sources` | the source list |
| `source-add <location>`, `source-remove <id>`, `source-refresh <id>` | change the source list |
| `install <Id>` | copy the module (and the modules in its `requires`, first) into the plugins folder and load it |
| `updates`, `update <Id>` | newer versions in the sources; install one (a hot swap) |
| `uninstall <Id>` | unload and remove it |
| `enable <Id>`, `disable <Id>` | keep it on or off across restarts |
| `signers`, `signer-add`, `signer-remove` | the signing keys the installer trusts |

An agent cannot install by itself: Infantry_Plugins gives agents `infantry_plugin_list`, `infantry_plugin_search`,
`infantry_plugin_show`, `infantry_plugin_status`, `infantry_plugin_list_import` and `infantry_plugin_request` (an
install request the owner approves).

## The infantry-plugins repository

SuperiorSoft's plugins that are not built inside the app repository live in the infantry-plugins repository
(`official/<Module>/`, each with its own `stage.sh`). The app's release bundles the ones listed in
`rust/plugins/bundled-external.json` through `rust/plugins/stage-external.sh` (the repository is the folder
`INFANTRY_PLUGINS_REPO` names, else `../infantry-plugins` next to the app repository).

## Before you publish

- `prod` endpoints are `https://` and the staged files hold no `127.0.0.1`, `localhost` or `(staging)`
  ([Endpoints](endpoints.md)).
- No secret in any file ([Secrets](secrets.md)).
- Bump `version` in `module.json`; the update check compares it.
