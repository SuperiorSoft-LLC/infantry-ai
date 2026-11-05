# Releasing Infantry (RELEASE-AUTO-1)

## Package a build locally (BUILD-LOC-1): the one procedure

Every local build goes through `scripts/package-dist.ps1`. Output locations are project data, never code:

| Setting | Meaning |
|---|---|
| `build.root` | The one build folder. Outputs: `<build.root>\builds\<sha>\` (one subfolder per sha, never a new top-level folder) and `<build.root>\current\` (the last build that passed the copy-integrity checks). |
| `build.backupRoot` | Where the owner state backup goes: `<build.backupRoot>\infantry-state-backup\<stamp>\` (read-only copy). |

Read order: env override (`INFANTRY_BUILD_ROOT`, `INFANTRY_BUILD_BACKUPROOT`), then the daemon's project config for the
project (`infantry project config get <projectId>`). If a setting is missing the script stops with exit 2 and writes nothing.

```powershell
powershell -File scripts/package-dist.ps1 -Sha <sha> -DryRun      # prints the resolved paths and the gradle command; builds nothing
powershell -File scripts/package-dist.ps1 -Sha <sha>              # devSign build (says so; not for release)
powershell -File scripts/package-dist.ps1 -Sha <sha> -Keystore <p12> -KeyAlias <alias>   # signed; needs env IHQ_STOREPASS
powershell -File scripts/package-dist.ps1 -BackupState -StateDir <owner state dir> -DryRun  # -DryRun first, then without it
```

What a build does: the sha must be on `feature-agenthq-mvp`; it is built in a clean detached worktree under `%TEMP%` with
`:superiorSoftLibrary:infantryUi:createDistributable --no-parallel --no-daemon` and `-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=2g -Xmx3g`.
The app image is copied to `builds\<sha>\`, which gets `BUILD-INFO.txt` and a one-line `MANIFEST.txt` (sha, UTC time,
`signed` or `devSign`). Integrity checks (exe present and same size, file count and bytes, BUILD-INFO sha) run before
`current\` is replaced. A build runs no tests. Run it as a background job (`infantry bg run --notify`), since it takes minutes.

Old folders from before BUILD-LOC-1 are never deleted by the script; remove them by hand after the owner agrees.

Pushing a tag `v<MAJOR.MINOR.BUILD>` runs `.github/workflows/release.yml`, which builds the Windows app and the
Android APK and publishes them to that tag's GitHub Release.

## Cut a release

1. Edit `infantryVersion=` in the root `gradle.properties` (the single version source: the MSI/app-image, the APK
   `versionName`, and the workflow all read it). MAJOR 1..255, MINOR 0..255, BUILD 0..65535; it must be higher than
   the last release or installed copies will not upgrade. The APK `versionCode` is derived (`MAJOR*1000000 + MINOR*10000 + BUILD`).
2. Commit and push the bump to the default branch.
3. `git tag v1.0.1 && git push origin v1.0.1` (tag = `v` + the exact `infantryVersion`; the workflow's first job fails otherwise).

## Build stamp (BUILD-STAMP-1)

Every build carries one build string, `<infantryVersion>+<UTC yyyyMMdd>.<UTC HHmm>.<short sha>[.dirty]`, e.g.
`1.0.1+20261023.1432.e85b223` (`gradle/build-stamp.gradle.kts`). Nothing is edited per build: the version is the property
above, the time is stamped when a packaging task runs (else HEAD's commit time), the sha and dirty flag come from git.

- The desktop app gets it as `-Dinfantry.build`, the APK as its `versionName`, `infantry` and the headless server as
  `-Dinfantry.build` / a `BUILD` file. The footer (bottom right) and Configuration > About show it.
- The daemon sends it in `ready` as `build=` (additive, CONTRACT.md); the app warns plainly when the daemon is another build.
- `infantry version` prints `infantry <contract> build <cli build> (daemon: <contract> build <daemon build>)`.
- The packaging guard writes `build=` into `BUILD-INFO.txt` and copies each msi/exe/apk to `<outdir>/<sha>/<name>-<build>.<ext>`.
- Checks: `./gradlew verifyBuildStamp` (format, UTC, dirty flag) and `verifyPackagingStamp` (part of `check` in infantryUi / infantryAndroid).

## What lands on the Release

| Asset | Source |
|---|---|
| `Infantry-<v>.msi` | Windows job, `releaseAssets` (unsigned MSI). This is what the in-app updater downloads. |
| `Infantry-<v>-windows.zip` | Windows job, `createDistributable` app-image (also the fallback if MSI packaging fails on the runner; then the updater has nothing to install). |
| `Infantry-<v>.apk` | Android job, signed `assembleRelease`, when the keystore secrets exist. |
| `Infantry-<v>-debug.apk` | Android job fallback (`assembleDebug`) when they do not. **The debug key differs per build machine, so Android will refuse to update an installed app signed with another key.** Use a stable release key for anything users keep. |
| `InfantryAI-server-<v>-<os>-<arch>.{zip\|tar.gz}` | Headless job (`headlessDist`): the daemon + CLI + a bundled Java runtime, one per OS (linux-x64, windows-x64 zip, macos-arm64). This is what `infantry update` downloads; the release job fails unless all three are present and listed in `SHA256SUMS`. |
| `SHA256SUMS` | `sha256sum` of every asset above (the format the updater parses). |
| `latest.json` | `{version, tag, published, assets:[{name, sha256, size, url}]}` for any other consumer (the Windows updater itself reads the GitHub Releases API plus `SHA256SUMS`). |

## Headless server updates (SERVER-UPDATE-1..5)

The headless server updates itself from the same Release, using the `InfantryAI-server-*` archive for its own OS and CPU
(the legacy `Infantry-server-*` prefix is still accepted) and the same `SHA256SUMS`; an archive with no checksum line is
refused. Installs under `<root>/versions/<v>/`, switches `<root>/current`, keeps the previous version for rollback, and
never touches the data dir. `infantry update` / `infantry rollback`, or the Update button in Configuration > Server.
Docker images cannot replace themselves: the daemon reports the newer release and the `docker pull` command. See
`superiorSoftLibrary/infantry/docs/UPDATES.md`. arm64 Linux/Windows archives are a later ticket.

## Owner setup (repository secrets, Settings > Secrets and variables > Actions)

Android signing (all four, or the build falls back to debug):
`INFANTRY_ANDROID_RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `INFANTRY_ANDROID_RELEASE_STORE_PASSWORD`,
`INFANTRY_ANDROID_RELEASE_KEY_ALIAS`, `INFANTRY_ANDROID_RELEASE_KEY_PASSWORD`. No key or certificate is committed.
Windows code signing is still HELD: the MSI is unsigned (SmartScreen will warn).

## Updater configuration

The updater is the official plugin `plugins/official/Infantry_Updater` (UPDATER-1); see
`superiorSoftLibrary/infantry/docs/UPDATES.md`. It resolves "latest" with `GET /repos/{repo}/releases/latest`: set
`updater.repo=owner/repo` (Configuration > About > Updater), or `INFANTRY_UPDATE_REPO`, or `repo=` in
`<stateDir>/update.properties`. With no newer release it reports up to date; offline, rate-limited or a missing release it
shows a message and keeps running. The desktop app installs the MSI, the headless server the `InfantryAI-server-*`
archive, and the phone the APK (`InfantryAI-<v>.apk`, else `-debug.apk`; the daemon serves the offer at `/update/client`).

## Private repository (before launch)

Release assets of a private repo cannot be downloaded anonymously. While the repo is private, set
`updater.source=private` and give a fine-grained read-only token (Contents: read, this repo only) in the user env var
`INFANTRY_UPDATE_TOKEN`, or the keystore secret `update-token` (never in a file in the repo). End users cannot update
from a private repo. Test the whole flow with `scripts/updater-e2e.ps1`.

## Launch-day switch

When the repo goes public: `updater.source=public` (the default), delete the env var
(`[Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', $null, 'User')`) and the keystore secret
`update-token`, then run `pwsh scripts/updater-e2e.ps1 -Source public`: the latest release is found with no token sent.

## Not verified without a real Actions run

Runner tooling (WiX on `windows-2022`, `7z`, Android SDK licences), secret decoding, `gh release` permissions, and the
action versions themselves (pinned to major tags, not commit SHAs).
