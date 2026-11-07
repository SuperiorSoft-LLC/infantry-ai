# infantry-plugins

The plugin catalog of Infantry AI. The app reads two files from GitHub Pages:

- https://superiorsoft-llc.github.io/infantry-plugins/official.json (plugins by SuperiorSoft LLC)
- https://superiorsoft-llc.github.io/infantry-plugins/community.json (everyone else)

They are the default sources of a fresh install (`infantry plugin sources`). Each plugin has one entry file
`<Id>.json` in `official/` or `community/`; the catalogs are generated with `infantry plugin catalog build`.
See CONTRIBUTING.md. Start a plugin from https://github.com/SuperiorSoft-LLC/infantry-plugin-template.

## Trust and signing

There is no plugin signing key yet. Every version is pinned by the sha256 of its `.ihq` in the catalog, and each
`.ihq` is a release asset of this repo (tag `<id>-v<version>`). The installer checks the sha256 and shows the plugin
as unsigned.

## Workflows

`validate.yml` and `publish.yml` install the `infantry` CLI from release assets. Until a release carries the CLI,
the catalogs are published by building them locally and pushing the `gh-pages` branch.
