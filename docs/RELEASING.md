# Releasing AgentHQ (RELEASE-AUTO-1)

Pushing a tag `v<MAJOR.MINOR.BUILD>` runs `.github/workflows/release.yml`, which builds the Windows app and the
Android APK and publishes them to that tag's GitHub Release.

## Cut a release

1. Edit `agenthqVersion=` in the root `gradle.properties` (the single version source: the MSI/app-image, the APK
   `versionName`, and the workflow all read it). MAJOR 1..255, MINOR 0..255, BUILD 0..65535; it must be higher than
   the last release or installed copies will not upgrade. The APK `versionCode` is derived (`MAJOR*1000000 + MINOR*10000 + BUILD`).
2. Commit and push the bump to the default branch.
3. `git tag v1.0.1 && git push origin v1.0.1` (tag = `v` + the exact `agenthqVersion`; the workflow's first job fails otherwise).

## Build stamp (BUILD-STAMP-1)

Every build carries one build string, `<agenthqVersion>+<UTC yyyyMMdd>.<UTC HHmm>.<short sha>[.dirty]`, e.g.
`1.0.1+20261023.1432.e85b223` (`gradle/build-stamp.gradle.kts`). Nothing is edited per build: the version is the property
above, the time is stamped when a packaging task runs (else HEAD's commit time), the sha and dirty flag come from git.

- The desktop app gets it as `-Dagenthq.build`, the APK as its `versionName`, `agenthq` and the headless server as
  `-Dagenthq.build` / a `BUILD` file. The footer (bottom right) and Configuration > About show it.
- The daemon sends it in `ready` as `build=` (additive, CONTRACT.md); the app warns plainly when the daemon is another build.
- `agenthq version` prints `agenthq <contract> build <cli build> (daemon: <contract> build <daemon build>)`.
- The packaging guard writes `build=` into `BUILD-INFO.txt` and copies each msi/exe/apk to `<outdir>/<sha>/<name>-<build>.<ext>`.
- Checks: `./gradlew verifyBuildStamp` (format, UTC, dirty flag) and `verifyPackagingStamp` (part of `check` in agentHqUi / agentHqAndroid).

## What lands on the Release

| Asset | Source |
|---|---|
| `AgentHQ-<v>.msi` | Windows job, `releaseAssets` (unsigned MSI). This is what the in-app updater downloads. |
| `AgentHQ-<v>-windows.zip` | Windows job, `createDistributable` app-image (also the fallback if MSI packaging fails on the runner; then the updater has nothing to install). |
| `AgentHQ-<v>.apk` | Android job, signed `assembleRelease`, when the keystore secrets exist. |
| `AgentHQ-<v>-debug.apk` | Android job fallback (`assembleDebug`) when they do not. **The debug key differs per build machine, so Android will refuse to update an installed app signed with another key.** Use a stable release key for anything users keep. |
| `InfantryAI-server-<v>-<os>-<arch>.{zip\|tar.gz}` | Headless job (`headlessDist`): the daemon + CLI + a bundled Java runtime, one per OS (linux-x64, windows-x64 zip, macos-arm64). This is what `infantry update` downloads; the release job fails unless all three are present and listed in `SHA256SUMS`. |
| `SHA256SUMS` | `sha256sum` of every asset above (the format the updater parses). |
| `latest.json` | `{version, tag, published, assets:[{name, sha256, size, url}]}` for any other consumer (the Windows updater itself reads the GitHub Releases API plus `SHA256SUMS`). |

## Headless server updates (SERVER-UPDATE-1..5)

The headless server updates itself from the same Release, using the `InfantryAI-server-*` archive for its own OS and CPU
(the legacy `AgentHQ-server-*` prefix is still accepted) and the same `SHA256SUMS`; an archive with no checksum line is
refused. Installs under `<root>/versions/<v>/`, switches `<root>/current`, keeps the previous version for rollback, and
never touches the data dir. `infantry update` / `infantry rollback`, or the Update button in Configuration > Server.
Docker images cannot replace themselves: the daemon reports the newer release and the `docker pull` command. See
`superiorSoftLibrary/agentHq/docs/UPDATES.md`. arm64 Linux/Windows archives are a later ticket.

## Owner setup (repository secrets, Settings > Secrets and variables > Actions)

Android signing (all four, or the build falls back to debug):
`AGENTHQ_ANDROID_RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `AGENTHQ_ANDROID_RELEASE_STORE_PASSWORD`,
`AGENTHQ_ANDROID_RELEASE_KEY_ALIAS`, `AGENTHQ_ANDROID_RELEASE_KEY_PASSWORD`. No key or certificate is committed.
Windows code signing is still HELD: the MSI is unsigned (SmartScreen will warn).

## Updater configuration

The installed app resolves "latest" with `GET /repos/{repo}/releases/latest` (`UpdateSettings`): set
`AGENTHQ_UPDATE_REPO=owner/repo` (or `repo=` in `<stateDir>/update.properties`). With no newer release it reports up to
date; offline, rate-limited or a missing release it shows a message and keeps running.

## Private repository limitation

Release assets of a private repo cannot be downloaded anonymously. While the repo is private, the installed app needs a
read token in the environment (`AGENTHQ_UPDATE_TOKEN` or `INFANTRY_UPDATE_TOKEN`, never in a file; or Git Credential Manager's stored login).
End users cannot update from a private repo: it must be public (or assets hosted elsewhere) before shipping.

## Not verified without a real Actions run

Runner tooling (WiX on `windows-2022`, `7z`, Android SDK licences), secret decoding, `gh release` permissions, and the
action versions themselves (pinned to major tags, not commit SHAs).
