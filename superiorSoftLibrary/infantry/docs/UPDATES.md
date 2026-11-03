# Infantry updates: publish and install

The installed app (never a dev build) checks for a newer build at start and when you click the footer button.

## Settings: `update.properties`
A file named `update.properties` in the installed app's state dir (`INFANTRY_STATE_DIR`).

| key | values | meaning |
|---|---|---|
| `channel` | `unstable` / `production` | unstable = local releases folder, production = GitHub releases. `nightly` is not built yet and is refused. |
| `source` | `local` / `github` | wins over `channel` when both are set |
| `dir` | folder path | the local releases folder (also `INFANTRY_UPDATE_DIR`) |
| `repo` | `owner/repo` | the GitHub repo (also `INFANTRY_UPDATE_REPO`) |

With neither `channel` nor `source`: a `dir` means local, else a `repo` means GitHub. A private repo's token comes from
`INFANTRY_UPDATE_TOKEN` or the Git credential store, never from the file.

## Publish locally (unstable)
`scripts/publish-local-release.ps1 -Version 1.0.N` builds the MSI and writes `<dir>/1.0.N/{Infantry-1.0.N.msi, SHA256SUMS, RELEASE-NOTES.md}`
(default dir `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-releases`). Set `channel=unstable` and `dir=<that folder>` in `update.properties`.

## Publish to GitHub (production)
`./gradlew :superiorSoftLibrary:infantryUi:releaseAssets -PinfantryVersion=1.0.N` writes the same three files. Create a
GitHub release tagged `v1.0.N` (not a draft or pre-release) and attach `Infantry-1.0.N.msi` and `SHA256SUMS`. Set
`channel=production` and `repo=owner/repo`.

## What an installed exe does
1. Check: the highest version newer than the running one is offered (a missing checksum or wrong file name is refused).
2. A toast appears bottom-right; the footer button reads "Update available vX.Y.Z". Click either to see the notes.
3. **Update**: if agents are working (including sessions with background work) it asks "N agents running, update anyway?"; the default is Cancel.
4. The installer downloads to a temp folder; its SHA-256 must match `SHA256SUMS` or it is deleted and refused.
5. The verified MSI is handed to Windows, Infantry exits, the MSI upgrades in place, and the app restarts.

Footer states: up to date, checking, update available, downloading, ready to install, error (click to retry).

# Headless server updates (SERVER-UPDATE-1..5)

The headless daemon (the `InfantryAI-server-<v>-<os>-<arch>` archive, or the Docker image) updates through the same
settings as the desktop app: `INFANTRY_UPDATE_REPO=owner/repo` or `INFANTRY_UPDATE_DIR=<folder>` (or `update.properties` in
`INFANTRY_STATE_DIR`), and for a private repo `INFANTRY_UPDATE_TOKEN` / `INFANTRY_UPDATE_TOKEN` in the environment. The
token is never written to a file, never returned by the API, and only sent to the GitHub API host.

## Archive installs

Run the server with `bin/infantry` (Windows: `bin\infantry.cmd`). The first run copies the unpacked folder to
`<parent>/versions/<v>/` and writes `<parent>/infantry`, the stable entry point. Layout: `versions/<x.y.z>/`, `current`
(the version to start), `previous` (for rollback); the last two versions are kept. The data dir is elsewhere and untouched.

| command | does |
|---|---|
| `infantry` / `infantry serve` | runs the daemon under a supervisor; exit code 75 restarts it into `current`; a new version that dies within 30 s of the switch is rolled back once |
| `infantry update [--check-only] [--yes]` | check, show notes, ask, download, verify SHA-256 against `SHA256SUMS`, unpack, switch, restart. Exit 0 done/up to date, 10 an update exists (`--check-only`), 3 cannot self-update, 1 failed |
| `infantry rollback` | switch back to the previous version (restart to run it) |

Updates are never applied by themselves: only an explicit command or a confirmed button. With agents working, the command
and the button ask first.

## Docker

A container cannot replace its own image. The daemon (`INFANTRY_DISTRIBUTION=docker`, set in the image) checks the same
releases and reports the newer version plus the exact `docker pull infantryai/infantry-ai:<v>` / `docker compose pull &&
docker compose up -d` command (image name from `INFANTRY_DOCKER_IMAGE`), and a Watchtower note. It never installs.

## Clients

Daemon routes (contract 1.110.0, bearer token): `GET /update/status`, `POST /update/check`, `POST /update/apply`
(`{"confirm":false}`). Configuration > Server shows the daemon version, whether an update exists, the Update button (a
confirm dialog, naming working agents) and a warning when app and server differ by more than a patch or the server's
contract is older than the app expects.

## Testing without a GitHub release

`INFANTRY_UPDATE_DIR=<folder>` with `<folder>/<v>/{InfantryAI-server-<v>-<os>-<arch>.{zip|tar.gz}, SHA256SUMS}` is a full
local release source (`LocalDirSource`). Not testable locally: a private-repo token download, a real `docker pull`, a Windows
service restart (the Windows launcher is a plain wrapper, not a service).
