# Infantry updates: publish and install

The installed app (never a dev build) checks for a newer build at start and when you click the footer button.

## The Infantry_Updater plugin (UPDATER-1)

Updates are a plugin, `plugins/official/Infantry_Updater`, not core:

| part | what it does |
|---|---|
| daemon half (`daemon/`, `UpdaterDaemonPlugin`) | serves `GET /update/status`, `POST /update/check`, `POST /update/apply` (this server's own update) and `GET /update/client?platform=android&current=<x.y.z>` (the phone's APK offer); re-checks every `updater.checkIntervalHours` |
| the engine (same jar) | GitHub / local release sources, SHA-256 checks, the MSI handoff, the server archive staging. The desktop window and the headless `infantry update` command use the same jar |
| UI half (`src/`, `UpdaterPlugin`) | fills the `app.selfUpdate` registry: on Android, Configuration > Updates asks the daemon, downloads the APK, checks its size and SHA-256 and installs it (Android asks to confirm) |

Core keeps only generic seams: the plugin route table, `PlatformHost.selfUpdateKey` and the `app.selfUpdate` implementations
registry. The Updates config sections stay in the locked trust kernel, so an update stays checkable after a bad plugin drop.

### Where updates come from: the manifest (UPDATER-DEFAULT-1)

Where releases live is developer data, never a user setting: the Updater's `module.json` carries an `appUpdate` block,

```json
"appUpdate": { "type": "github", "repo": "SuperiorSoft-LLC/infantry-ai", "visibility": "public", "channel": "production", "checkIntervalHours": 24 }
```

and `daemon/build.gradle.kts` (task `updateManifest`) bakes it into the jar as `infantry-update-manifest.properties`
(`UpdateManifest`), so the window, the daemon half and the headless `infantry update` all check it with zero setup. A build
can override a value with `-PinfantryUpdateRepo` / `-PinfantryUpdateVisibility` / `-PinfantryUpdateChannel`; the release
workflow sets `ORG_GRADLE_PROJECT_infantryUpdateRepo` to the repository it publishes to, so a fork's release checks the fork.
`visibility`: `public` never sends a token, `private` sends a read token (below) to the GitHub API host only. `channel`:
`production` = GitHub releases, `unstable` = a local releases folder. It is NOT the module.json `update` key: that one
is the update engine's per-module `UpdateDeclaration` (`{"source": {...}}`, docs/wiki/Updates.md), where a module's OWN new versions
come from; reusing it broke every manifest parse of the Updater (QA-FIX-I).

### The user setting (Configuration > About > Updater, module config `infantry.updater`)

| key | values | meaning |
|---|---|---|
| `updater.autoCheck` | on (default) / off | on = check at start, every `checkIntervalHours`, and when the desktop window regains focus that long after its last check; off = only "Check for updates". A check never installs |

### Developer overrides (not in the UI)

`INFANTRY_UPDATE_REPO`, `INFANTRY_UPDATE_SOURCE` (public/private), `INFANTRY_UPDATE_DIR`, and `<stateDir>/update.properties`
(below; it also takes the `updater.*` keys: `updater.source`, `updater.repo`, `updater.channel`, `updater.dir`,
`updater.checkIntervalHours`). Precedence: env > `update.properties` > the module config > the manifest. A bad value
(a source that is not public/private, a repo that is not owner/repo, an unknown channel, a non-numeric interval) is ignored
with a warning in the log; it never turns updates off. Settings are re-read before each check: the window resolves them
again, and the daemon rebuilds its service when its config or `update.properties` changes, so no restart is needed. The daemon mirrors the effective values (never a token) to
`<state>/plugin-data/Infantry_Updater/settings.properties`, which the desktop window reads.

### The private-repo token

Only with `updater.source=private`, first found wins:

1. env `INFANTRY_UPDATE_TOKEN` (the owner's user environment variable);
2. the keystore secret `update-token` (Configuration > Keys, sealed in the state dir);
3. the OS credential store (Git Credential Manager's login for github.com).

Use a fine-grained token, read only (Contents: read), for this repo only. The token is never written to a file, a log, a
reply or a crash dump; `UpdateConfig.toString` shows `token=set` or `token=none`. `UpdateTokenGuardTest` fails the build
if a tracked file or a built jar, archive or installer holds a GitHub token, or the value of `INFANTRY_UPDATE_TOKEN`.
The phone never gets the token: the daemon asks GitHub and gives the phone a short-lived, token-free download URL.

### Launch-day switch (repo made public)

1. Set `updater.source=public` (the default: remove any `private` value from the config, env and `update.properties`).
2. Delete the token: `[Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', $null, 'User')`, and delete the
   keystore secret `update-token`.
3. Check: `pwsh scripts/updater-e2e.ps1 -Source public` shows the latest release with no token sent.

### Local production emulation

`scripts/updater-e2e.ps1` runs the whole flow on a throwaway state dir under `%TEMP%` and port 18797 (it refuses
8787, 8788, 8797 and 8798). Steps: `token` (set or none, never the value), `release`, `server` (an older headless build
updates itself), `msi` (download, SHA-256, then `msiexec /a` extraction only: `/i` would upgrade the owner's own app,
which has the same upgrade code), `apk` (a test daemon answers `/update/client`; the APK is downloaded from the
token-free URL and checked; `-Adb` installs it on the attached phone), and `public` (no token sent). Pick steps with
`-Steps token,release,msi`.

## Settings: `update.properties`
A file named `update.properties` in the installed app's state dir (`INFANTRY_STATE_DIR`).

| key | values | meaning |
|---|---|---|
| `channel` | `unstable` / `production` | unstable = local releases folder, production = GitHub releases. `nightly` is not built yet and is refused. |
| `source` | `local` / `github` | wins over `channel` when both are set |
| `dir` | folder path | the local releases folder (also `INFANTRY_UPDATE_DIR`) |
| `repo` | `owner/repo` | the GitHub repo (also `INFANTRY_UPDATE_REPO`); unset = the manifest's |

With neither `channel` nor `source`: a `dir` means local, else a `repo` means GitHub. `source=local|github` is where
releases come from; the `updater.source` key (`public` / `private`) is whether a token is sent. A private repo's token
comes from the places above, never from the file.

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
`INFANTRY_STATE_DIR`), and for a private repo `updater.source=private` with the token as above. The token is never written
to a file, never returned by the API, and only sent to the GitHub API host.

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

Daemon routes (contract 1.110.0, bearer token; served by Infantry_Updater's daemon half): `GET /update/status`,
`POST /update/check`, `POST /update/apply` (`{"confirm":false}`), and `GET /update/client?platform=android&current=<x.y.z>`
(`result`: `available` with `version`, `name`, `size`, `sha256` and a token-free `url`; or `up_to_date`, `disabled`,
`unsupported`, `failed` with a `message`). Configuration > Server shows the daemon version, whether an update exists, the Update button (a
confirm dialog, naming working agents) and a warning when app and server differ by more than a patch or the server's
contract is older than the app expects.

## Testing without a GitHub release

`INFANTRY_UPDATE_DIR=<folder>` with `<folder>/<v>/{InfantryAI-server-<v>-<os>-<arch>.{zip|tar.gz}, SHA256SUMS}` is a full
local release source (`LocalDirSource`). A private-repo download is tested by `scripts/updater-e2e.ps1`. Not testable
locally: a real `docker pull`, a Windows service restart (the Windows launcher is a plain wrapper, not a service).
