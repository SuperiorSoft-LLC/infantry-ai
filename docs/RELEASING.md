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

Pushing a tag `v<MAJOR.MINOR.BUILD>` runs `.github/workflows/release.yml`, which builds the Windows app, the
Android APK, the web bundle, the headless server archives and the macOS (beta) app, and publishes them to that tag's
GitHub Release. The user-facing list of downloads is the README's *Downloads* section.

## LAUNCH CHECKLIST (LAUNCH-PREP-1)

The launch-day switch, in this order. Do not skip ahead: each step assumes the one before it is done.

1. **Tokens rotated** (RELEASE-SECRETS-1): every repository secret and signing token is fresh, none was ever in a commit.
2. **Final QA clean on the launch commit**: the full gate passes on the exact commit that ships.
3. **Release builds** (Windows, Android, wasm web, macOS beta) via the tag workflow (`.github/workflows/release.yml`, see *Cut a release*).
4. **Updater switched to public** (UPDATER-1): installed copies read the public Release feed (see *Updater configuration*).
5. **Fast-forward `main`** to the launch commit.
6. **Merge the LAUNCH-PREP-1 branch** (drops the LICENSE DRAFT banners, enables the CLA bot, opens contributions).
7. **Make the repository public** (this also lifts the *Private repository limitation* below).
8. **Website download link live**, pointing at the launch Release.
9. **Post-launch smoke test**: download from the website, install, start, connect a client, open a pull request from
   an outside account and see the CLA bot comment.

## Cut a release

1. Edit `infantryVersion=` in the root `gradle.properties` (the single version source: the MSI/app-image, the APK
   `versionName`, and the workflow all read it). MAJOR 1..255, MINOR 0..255, BUILD 0..65535; it must be higher than
   the last release or installed copies will not upgrade. The APK `versionCode` is derived (`MAJOR*1000000 + MINOR*10000 + BUILD`).
   A stable BUILD stays below 40000: that range belongs to the nightly MSIs (see "Nightly builds"); the workflow refuses it.
2. Commit and push the bump to the default branch.
3. `git tag v1.0.1 && git push origin v1.0.1` (tag = `v` + the exact `infantryVersion`; the workflow's first job fails otherwise).
4. The tagged commit must pass the stable safety guard (below): it, or its parent with only this version bump, shipped as
   a nightly. The project's `release-cut` script does steps 1-3 and runs the guard before it pushes anything.

## Nightly builds (NIGHTLY-1)

`.github/workflows/nightly.yml` runs at 06:00 UTC and by hand (Actions > nightly > Run workflow; inputs `force` and
`branch`). Its `plan` job runs `scripts/nightly-version.sh plan`: when the head of the dev branch (`feature-agenthq-mvp`)
already carries a nightly tag there is no new commit since the last nightly and nothing is built (`force` overrides).
Otherwise it calls `release.yml` through `workflow_call` (the very jobs of a stable release, never a copy) with:

| Input | Value (example: last stable 1.0.7, run 12, 2026-11-07) |
|---|---|
| `version` | `<next>-nightly.<yyyymmdd>.<run>` = `1.0.8-nightly.20261107.12`. `next` is `infantryVersion` when it is above the newest stable tag, else that tag's BUILD + 1. |
| `package_version` | `<next MAJOR>.<next MINOR>.<40000 + run>` = `1.0.40012` |
| `tag` | `v1.0.8-nightly.20261107.12` |
| `ref`, `branch`, `channel` | the planned commit, the dev branch, `nightly` |

The release is a GitHub **prerelease** (never "latest"; `gh release create --prerelease --latest=false --target <sha>`,
which creates the tag on the built commit) with the same assets, `SHA256SUMS` (signed when the key exists) and
`nightly.json` (the `latest.json` shape) instead of `latest.json`. The Docker job is skipped. `release.yml` ignores pushed
`v*-nightly.*` tags. A schedule and the Run button work only from the copy of `nightly.yml` on the default branch
(`main`), which the next release cut brings there; until then nothing runs at night.

**Versions and ordering.** SemVer orders `1.0.7 < 1.0.8-nightly.20261107.12 < 1.0.8-nightly.20261108.13 < 1.0.8`: the
date comes first, so a later night always wins. The asset names carry the full version (`InfantryAI-1.0.8-nightly.20261107.12.msi`,
`.apk`, `-debug.apk`, `InfantryAI-server-1.0.8-nightly.20261107.12-linux-x64.tar.gz`). The app reports it as
`-Dinfantry.version` / the APK `versionName`, and the build stamp adds `+<date>.<time>.<sha>` after it.

**MSI (Windows).** An MSI version must be numeric MAJOR.MINOR.BUILD, so the nightly MSI gets `package_version`
(`-PinfantryPackageVersion`; the app's own version stays the SemVer). jpackage (JDK 21) derives the MSI ProductCode from
the version and allows both upgrades and downgrades (`JpAllowUpgrades`, `JpAllowDowngrades` in its `main.wxs`), but it
excludes the SAME version from its upgrade table: two different MSIs with one version would collide. So every nightly
gets a unique BUILD, `40000 + run number`, and stable BUILDs stay below 40000. Order does not matter to Windows: nightly
`1.0.40012` over stable `1.0.7` upgrades, and stable `1.0.8` over nightly `1.0.40012` is a downgrade that the MSI allows,
replacing the nightly. The range ends at BUILD 65535 (run 25535); then `nightly-version.sh` fails and says so (bump MINOR,
or lower `NIGHTLY_MSI_BUILD_BASE`). The macOS `.dmg` uses the same package version.

**APK (Android).** `versionCode` comes from the numeric core only (`1.0.8-nightly...` -> the code of `1.0.8`), so the
next nightly and the stable it leads up to install over it (Android allows an equal code). An OLDER stable (Nightly ->
Stable while the stable is behind) has a lower code, which Android refuses: the updater tells the phone user to
uninstall first (the app's data on the phone is lost; the daemon's is not).

**Channels in the updater.** The channels are manifest data, the `appUpdate.channels` map of
`plugins/official/Infantry_Updater/module.json`: `stable` reads `releases/latest`, `nightly` reads the release list
and takes the highest version that is a stable release or a `nightly` prerelease (so a nightly install also moves on to
a newer stable). The one user setting is **Update channel: Stable / Nightly** (`updater.releaseChannel`, Configuration >
About, default Stable). Switching Nightly -> Stable offers the newest stable even though it is older than the running
nightly, with a note above the release notes that says so (and, on the phone, that Android needs an uninstall). A
Stable install never downgrades otherwise.

**Known limit: a headless server does not downgrade.** The core update engine (`infantry update`, Configuration >
Server) installs only a NEWER version, so a server on a nightly that switches to Stable stays on the nightly until a
stable release newer than it ships. The desktop app and the phone do downgrade.

Retention: nightlies are never deleted by the workflow (the stable guard needs their tags). Delete old ones by hand if
the list grows, keeping the nightly of every commit that may still be cut as a stable.

## The stable safety guard (NIGHTLY-1)

A stable release ships only a commit that QA passed AND that was shipped as a nightly first: `scripts/stable-guard.sh
<commit>` passes when the commit carries a `v*-nightly.*` tag, or its first parent does and the commit changes nothing but
the `infantryVersion=` line of `gradle.properties` and the `version:` line of `docs/shared/product.md` (the release bump).
It runs twice:

- in the project's `release-cut` script, before it commits or pushes anything (override: arg `"force": "<reason>"`);
- in `release.yml`'s `version` job on every stable tag, with the full history and tags (override: an ANNOTATED tag whose
  message has a line `force-stable: <reason>`, e.g. `git tag -a v1.0.8 -m "force-stable: hotfix for ..."`). An override
  is a warning in the run log.

QA is the release-cut script's rule (run the `qa` script first); the nightly is the part the guard enforces. The first
stable cut after NIGHTLY-1 lands has no nightly yet (the schedule needs `nightly.yml` on `main`): it is cut with the
override, which also brings `nightly.yml` to `main`.

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
| `InfantryAI-<v>.msi` | Windows job, `releaseAssets` (unsigned MSI). This is what the in-app updater downloads. |
| `InfantryAI-<v>-windows.zip` | Windows job, `createDistributable` app-image (also the fallback if MSI packaging fails on the runner; then the updater has nothing to install). Do not update a zip-extracted copy with the MSI (see Windows notes). |
| `InfantryAI-<v>.apk` | Android job, signed `assembleRelease`, when the keystore secrets exist. |
| `InfantryAI-<v>-debug.apk` | Android job fallback (`assembleDebug`) when they do not. **The debug key differs per build, so Android refuses to update an installed app signed with another key.** Use a stable release key for anything users keep. |
| `InfantryAI-<v>-macos-arm64-beta.dmg` | macOS job (RELEASE-PLATFORMS-1, MACOS-DMG-2): `createDistributable`, then the workflow ad-hoc signs the `.app` itself (`codesign -s -`, its output shown and annotated) and builds the `.dmg` with `hdiutil`, on `macos-15`: **beta, ad-hoc signed only, not notarized**. Optional: the job is `continue-on-error` and the release goes out without it (a warning says so). No updater looks for it. |
| `InfantryAI-web-<v>.zip` | Web job, `:superiorSoftLibrary:infantryWeb:assembleWeb` (production), zipped under one `InfantryAI-web-<v>/` folder. The same files every daemon serves at `/`; a daemon serves an unpacked copy with `INFANTRY_WEB_DIR=<folder>`. Required. |
| `InfantryAI-server-<v>-<os>-<arch>.{zip\|tar.gz}` | Headless job (`headlessDist`): the daemon + CLI + a bundled Java runtime, one per OS (linux-x64, windows-x64 zip, macos-arm64). This is what `infantry update` downloads; the release job fails unless linux-x64 and windows-x64 are present, pass the smoke gate, and are listed in `SHA256SUMS`. macos-arm64 is beta: its matrix leg is `continue-on-error`, and when it fails the release ships without it (a macOS server then reports that the release has no archive for macos-arm64 and keeps running). |
| `SHA256SUMS` | `sha256sum` of every asset above (the format the updater parses). |
| `SHA256SUMS.sig` | ed25519 signature of `SHA256SUMS`, only when the signing secret exists (see below). |
| `latest.json` | `{version, tag, published, assets:[{name, sha256, size, url}]}` for any other consumer (the Windows updater itself reads the GitHub Releases API plus `SHA256SUMS`). |

## Platforms and what is required (RELEASE-PLATFORMS-1)

| Job | Runner | Output | Required? |
|---|---|---|---|
| `windows` | `windows-2022` | MSI + app-image zip | yes (the zip; the MSI warns when missing) |
| `android` | `ubuntu-24.04` | APK | yes |
| `web` | `ubuntu-24.04` | `InfantryAI-web-<v>.zip` | yes |
| `headless` | `ubuntu-24.04`, `windows-2022`, `macos-14` | server archives | linux-x64 and windows-x64 yes; macos-arm64 no (beta) |
| `macos` | `macos-14` (arm64) | `.dmg` (beta) | no |

The release job runs when `version`, `windows`, `android` and `web` succeeded, whatever the macOS jobs did
(`if: !cancelled() && ...`), and its first step fails unless the Windows zip, an APK, the web zip and the linux-x64 /
windows-x64 server archives are all present. A missing macOS asset is a warning. `macos-14` is GitHub's standard arm64
runner: free on a public repository; a private repository's plan refused it on 2026-11-06 (the v1.0.4 run).

The updater never depends on a macOS asset: it picks only `InfantryAI-<v>.msi` (desktop), `InfantryAI-<v>[-debug].apk`
(phone) and `InfantryAI-server-<v>-<own os>-<own arch>` (server), so the `.dmg`, the web zip, or their absence change
nothing for Windows, Android or a Linux/Windows server. The macOS desktop beta has no in-app update: users download the
next `.dmg`.

### macOS beta: what a user does (unsigned app)

There is no Apple Developer ID certificate yet, so the `.dmg` is neither signed nor notarized (the release workflow ad-hoc
signs the app, which is enough for arm64 to run). Gatekeeper blocks the first start. The user either right-clicks the app >
**Open** > **Open**, or clears the quarantine flag once: `xattr -dr com.apple.quarantine /Applications/InfantryAI.app`.
Signing and notarizing need the owner's Apple Developer account (secrets for the certificate and an app-specific
password); that is a later ticket, and nothing in this workflow creates or expects those secrets.

## Release gates (UPDATER-1)

Two checks stop a broken release before it is published:

- **Split-package scan**, `scripts/split-packages.sh <lib dir>`: fails when one module's jar is present twice (a stale
  jar left by a half-update) or when a package of ours is split over two jars and is not listed in
  `scripts/split-packages.baseline`. The baseline holds the 14 splits the module split left (infantryCore /
  infantryBoard / infantry / infantryProtocol, infantryUi / infantryUiCore-jvm). Remove a line when its split is cleaned
  up; never add one to make a release pass. Runs on the Windows and macOS app images and inside the smoke test.
- **Server smoke test**, `scripts/release-smoke.sh <server archive> [timeout s]`: unpacks the archive, runs the scan
  on its `lib/`, starts `bin/infantry-server` on a random free port (20000-59999, `INFANTRY_PORT_STRICT=1`) with a
  throwaway state dir and none of the caller's `INFANTRY_*` / `AGENTHQ_*` env, requires `GET /health` = 200 and no
  `[plugins] daemon half of ... refused` line, then stops it. The headless job runs it on every server archive.

Run either locally the same way (Git Bash on Windows).

## Headless server updates (SERVER-UPDATE-1..5)

The headless server updates itself from the same Release, using the `InfantryAI-server-*` archive for its own OS and CPU
(the legacy `Infantry-server-*` prefix is still accepted) and the same `SHA256SUMS`; an archive with no checksum line is
refused. Installs under `<root>/versions/<v>/`, switches `<root>/current`, keeps the previous version for rollback, and
never touches the data dir. `infantry update` / `infantry rollback`, or the Update button in Configuration > Server.
Docker images cannot replace themselves: the daemon reports the newer release and the `docker pull` command. See
`superiorSoftLibrary/infantry/docs/UPDATES.md`. arm64 Linux/Windows archives are a later ticket.

## Owner setup (repository secrets, Settings > Secrets and variables > Actions)

### Android signing key (owner action before launch)

Android signing (all four, or the build falls back to debug):
`INFANTRY_ANDROID_RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `INFANTRY_ANDROID_RELEASE_STORE_PASSWORD`,
`INFANTRY_ANDROID_RELEASE_KEY_ALIAS`, `INFANTRY_ANDROID_RELEASE_KEY_PASSWORD`. No key or certificate is committed.

**Current state (2026-11-05): the four secrets hold a throwaway TEST key.** UPDATER-1 found that every CI run built
the APK with a different debug key, so the phone refused rc2 over rc1 (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
"signatures do not match"). To prove the phone update, a test keystore was generated on the dev machine
(`%USERPROFILE%\.infantry-test-android-key\infantry-TEST-ONLY.jks`, outside the repo) and the four secrets were set from
it through the GitHub API. With it, rc1 -> rc2 updated in place on the phone.

Replace it with the permanent key **before any user installs the APK**. An app signed with one key can never update
to an APK signed with another: everyone who installed a test-key build must uninstall (losing the app's local data)
and install again.

1. Create the key once, on a machine you trust, and keep it (and its passwords) backed up offline. Losing it means no
   phone can ever update again:
   ```
   keytool -genkeypair -v -keystore infantry-release.jks -alias infantry -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Replace the four secrets: `INFANTRY_ANDROID_RELEASE_KEYSTORE_BASE64` = `base64 -w0 infantry-release.jks`, the store
   password, the alias (`infantry`) and the key password.
3. Delete the test keystore folder above, uninstall the test-key app from your own phones, and install the first
   real-key release.

What a user sees when sideloading the APK: the first install asks to allow "Install unknown apps" for the browser or
file manager; Play Protect may show "App scan recommended" (the APK is not from Play); the in-app update is in
Settings > About > Updates.

### Release signature (ed25519, optional)

The release job signs `SHA256SUMS` when the secret `INFANTRY_RELEASE_SIGNING_KEY` exists, and uploads
`SHA256SUMS.sig` (base64 of the 64 raw signature bytes). Without the secret the step prints a warning and the release
goes out unsigned. The key lives only in Actions secrets, never in the repo or a build.

1. Create the key (openssl 3): `openssl genpkey -algorithm ed25519 -out release-signing.pem`. Back it up offline.
2. Set the secret `INFANTRY_RELEASE_SIGNING_KEY` to the PEM (or its base64).
3. Optional pin: set the repository **variable** `INFANTRY_RELEASE_PUBLIC_KEY` to the base64 of the 32-byte public key
   (`openssl pkey -in release-signing.pem -pubout -outform DER | tail -c 32 | base64`). The job then fails if the
   secret's key does not match the pin. The job prints only the public key.

Verify a release by hand: `openssl pkeyutl -verify -pubin -inkey release-signing.pub.pem -rawin -in SHA256SUMS
-sigfile <(base64 -d SHA256SUMS.sig)`. The updater does not check the signature yet (a later ticket); it checks each
asset's SHA-256 against `SHA256SUMS`.

### Windows

Windows code signing is still HELD: the MSI is unsigned (SmartScreen will warn).

## Windows update notes (UPDATER-1 rc tests)

- The MSI installs per user (`INSTALLDIR`). An MSI-installed copy major-upgrades cleanly (`RemoveExistingProducts`).
- **Do not update a copy unpacked from `-windows.zip` with the MSI.** msiexec over files it did not install skips the
  unversioned files it sees as "modified" (595 in the test), a silent half-update. A zip copy must be replaced by
  hand, or reinstalled from the MSI (a later ticket makes the updater refuse or handle this).
- The window's update: download the MSI, verify its SHA-256, then `apply.cmd` waits for the app's PID to exit, runs
  `msiexec /i <msi> INSTALLDIR=<launcher's parent> /passive /norestart /l*v <log>`, writes `apply.result` and starts
  the app again.
- There is no download progress yet: the screen says "Downloading and verifying" for the whole download (about 3.5
  minutes for the MSI at 0.8 MB/s in the test). A later ticket adds progress.
- An MSI install has no `BUILD-INFO.txt`; the build string is in Configuration > About.

## Updater configuration

The updater is the official plugin `plugins/official/Infantry_Updater` (UPDATER-1); see
`superiorSoftLibrary/infantry/docs/UPDATES.md`. It resolves "latest" with `GET /repos/{repo}/releases/latest` on the repo
of its manifest: the `appUpdate` block of `plugins/official/Infantry_Updater/module.json`, baked into the jar at build time
(UPDATER-DEFAULT-1), so an install needs no setup. `.github/workflows/release.yml` sets `ORG_GRADLE_PROJECT_infantryUpdateRepo`
to the repository it publishes to (a fork's release checks the fork); `INFANTRY_UPDATE_REPO` or `repo=` in
`<stateDir>/update.properties` are developer overrides. With no newer release it reports up to date; offline, rate-limited or a missing release it
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

Runner tooling (WiX on `windows-2022`, `7z`, Android SDK licences, `hdiutil` `.dmg` on `macos-15`), secret decoding,
`gh release` permissions, and the action versions themselves (pinned to major tags, not commit SHAs). What is checked
locally: `actionlint` on the workflow, `bash -n` on every `run:` block, and a dry run of the release job's asset gate on
fake asset sets (all present; macOS missing; web, APK or linux server missing; MSI missing).
NIGHTLY-1 (no actionlint on that machine): both workflows parsed as YAML, every `needs.*.outputs.*`, `steps.*.outputs.*`
and `inputs.*` checked against what is declared, the `workflow_call` inputs checked against what `nightly.yml` passes,
`bash -n` on every `run:` block; `nightly-version.sh` and `stable-guard.sh` run against a scratch git repository
(built / not built / forced; guard on the nightly commit, on a pure bump, refused on a bump with another change and on a
commit without a nightly). The `gh release create --prerelease --latest=false --target` call and a real scheduled run
are not verified.

## Tests on GitHub Actions (CI-TESTS-1)

`.github/workflows/tests.yml` (push to `feature-agenthq-mvp`, pull requests, manual) runs the suites on free runners
with `INFANTRY_CI=1`; it uses no secrets and an older run of the same ref is cancelled.

| Job | Runner | What |
|---|---|---|
| `jvm` | ubuntu | `infantry:test`, `infantryUiCore:jvmTest`, `infantryUi:test`, `ownerSmoke` (the gate's tasks) under `xvfb-run` |
| `windows` | windows | the PowerShell `scripts/*.tests.ps1` |
| `compile` | ubuntu | `infantryWeb:assembleWeb` and `infantryAndroid:assembleDebug`, the tasks release.yml uses |

`scripts/ci-test-summary.sh` writes the job summary (counts, failed classes). A failing class on
[KNOWN-FAILURES.md](KNOWN-FAILURES.md) is reported as known and does not fail the job. Reports are kept 7 days as
artifacts. Tests that need the owner's machine (live daemon, logged-in CLI, real desktop) skip when `INFANTRY_CI=1`.
