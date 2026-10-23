# AgentHQ updates: publish and install

The installed app (never a dev build) checks for a newer build at start and when you click the footer button.

## Settings: `update.properties`
A file named `update.properties` in the installed app's state dir (`AGENTHQ_STATE_DIR`).

| key | values | meaning |
|---|---|---|
| `channel` | `unstable` / `production` | unstable = local releases folder, production = GitHub releases. `nightly` is not built yet and is refused. |
| `source` | `local` / `github` | wins over `channel` when both are set |
| `dir` | folder path | the local releases folder (also `AGENTHQ_UPDATE_DIR`) |
| `repo` | `owner/repo` | the GitHub repo (also `AGENTHQ_UPDATE_REPO`) |

With neither `channel` nor `source`: a `dir` means local, else a `repo` means GitHub. A private repo's token comes from
`AGENTHQ_UPDATE_TOKEN` or the Git credential store, never from the file.

## Publish locally (unstable)
`scripts/publish-local-release.ps1 -Version 1.0.N` builds the MSI and writes `<dir>/1.0.N/{AgentHQ-1.0.N.msi, SHA256SUMS, RELEASE-NOTES.md}`
(default dir `%LOCALAPPDATA%\SuperiorSoft\AgentHQ-releases`). Set `channel=unstable` and `dir=<that folder>` in `update.properties`.

## Publish to GitHub (production)
`./gradlew :superiorSoftLibrary:agentHqUi:releaseAssets -PagenthqVersion=1.0.N` writes the same three files. Create a
GitHub release tagged `v1.0.N` (not a draft or pre-release) and attach `AgentHQ-1.0.N.msi` and `SHA256SUMS`. Set
`channel=production` and `repo=owner/repo`.

## What an installed exe does
1. Check: the highest version newer than the running one is offered (a missing checksum or wrong file name is refused).
2. A toast appears bottom-right; the footer button reads "Update available vX.Y.Z". Click either to see the notes.
3. **Update**: if agents are working (including sessions with background work) it asks "N agents running, update anyway?"; the default is Cancel.
4. The installer downloads to a temp folder; its SHA-256 must match `SHA256SUMS` or it is deleted and refused.
5. The verified MSI is handed to Windows, AgentHQ exits, the MSI upgrades in place, and the app restarts.

Footer states: up to date, checking, update available, downloading, ready to install, error (click to retry).
