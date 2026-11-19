<!-- SPDX-License-Identifier: Apache-2.0 -->
# Installer and updater (RUST-UPD-1)

The Rust desktop app installs with a per-user MSI (WiX; NSIS also builds)  and updates itself through the Tauri updater plugin. This page
does what the Kotlin `Infantry_Updater` does, the Tauri way.

## Build the installer

From `app/`:

```
pnpm installer                                   # debug build (the default)
INFANTRY_BUNDLE_PROFILE=release bash src-tauri/scripts/stage-bundle.sh && pnpm tauri build --config src-tauri/bundle-stage/tauri.bundle.conf.json
```

`scripts/stage-bundle.sh` runs first:

- It builds `infantry-daemon`, `infantry` and `infantry-migrate` in `rust/`.
- It stages the official daemon plugins with `rust/plugins/stage.sh`.
- It writes everything into `bundle-stage/` (ignored by git).
- It writes `bundle-stage/tauri.bundle.conf.json`. That is `tauri.bundle.conf.json` plus one `resources` entry per
  staged plugin folder. A `**` glob would flatten every plugin into one folder.

That generated config is merged only for an installer build:

- It ships those files beside `InfantryAI-Rust.exe` (`bundle.resources`). The app finds the daemon there, and the daemon
  finds `plugins/official/<Module>/` beside itself.
- Install mode is per user, so no admin is needed. The uninstall hook (`windows/installer-hooks.nsh`) takes `infantry`
  off PATH.

The installer lands in `target/<profile>/bundle/nsis/`.

`infantry` on PATH is opt-in. In Settings > Daemon, "Add `infantry` to PATH" stores `paths.addCli` in `ui.json` and
runs `infantry install-cli --dir <install folder>`. While the setting is on, every app start runs it again to keep the
entry current.

## Updates

- **Settings** live in `ui.json` (`updater`) and are shown under Settings > About:
  - `channel`: `stable` or `nightly`.
  - `feedUrl`: `{channel}` in it is replaced with the channel. The default is
    `https://github.com/SuperiorSoft-LLC/infantry-ai/releases/download/rust-{channel}/latest.json`.
  - `autoCheck`: on by default.
  - `autoInstall`: off. Nothing installs without the user pressing Install.
- **Check:** at start, when `autoCheck` is on, the app reads the feed. A newer build posts one "update available" notice
  in the Attention panel. On the stable channel, any version different from the one running is offered, so leaving
  nightly brings the newest stable even if it is older.
- **Install** (the user presses Install and restart):
  1. Download the update and verify its signature.
  2. Stop the daemon sidecar by its PID. The PID comes from `health/status.json`, and only while that record names the
     live port.
  3. Run the installer, which replaces the files and starts the app again. The app's start brings the daemon back.
  4. If the installer fails to start, the daemon is restarted.

### The signing keys (the owner does this, once)

No key is in the repo. `tauri.conf.json` carries the placeholder `OWNER-SETS-THE-UPDATER-PUBLIC-KEY`. While the
placeholder is there, the app can find updates but refuses to install them, and its notice says so.

1. Generate the pair on the release machine:

   ```
   pnpm tauri signer generate -w ~/.tauri/infantry-updater.key
   ```

2. Put the PUBLIC key (the `.pub` file's text) in `tauri.conf.json` under `plugins.updater.pubkey`. It is public, so it
   is safe to commit.
3. Keep the PRIVATE key and its password out of the repo. A release build reads them from the environment:
   `TAURI_SIGNING_PRIVATE_KEY` (the key text or a path) and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.
4. For a release, add `"createUpdaterArtifacts": true` under `bundle` in `tauri.bundle.conf.json`. The build then writes
   the `.exe` installer and its `.sig` signature next to it.

### The feed

Publish one `latest.json` per channel at the feed URL (for example, as an asset on a GitHub release tagged
`rust-stable` or `rust-nightly`):

```json
{
  "version": "0.2.0",
  "notes": "What changed",
  "pub_date": "2026-11-20T12:00:00Z",
  "platforms": {
    "windows-x86_64": { "signature": "<contents of the .sig file>", "url": "https://.../Infantry AI Rust_0.2.0_x64-setup.exe" }
  }
}
```

A public feed needs no token. No update token is read or stored by this app.

## Phone pairing

Settings > Daemon > "Allow phone connections" (`ui.json` `remoteAccess`) does two things:

- It restarts the sidecar on `0.0.0.0` with a bearer token. The token is in `<daemon state>/remote-access.token`, made
  from the OS RNG.
- It shows the `infantry://connect` link and its QR code for one of this machine's addresses (Tailscale first).

The token reaches the page only after "Show pairing code" is pressed. The phone app (RUST-MOBILE-1) scans the code or
reads the link.

## Release pipeline and Android (RUST-UPD-2)

- **Releases:** `.github/workflows/rust-release.yml` runs on a push to `main` that changes `tauri.conf.json` (a version
  bump), or by hand (`channel` stable or nightly). It builds the Windows NSIS installer, the Android APK (arm64), and
  best-effort Linux (AppImage) and macOS (app bundle, unsigned) builds, signs the updater artifacts, and makes a DRAFT
  release `rust-v<version>` with `SHA256SUMS` and `latest.json`. It never publishes and never touches the feed.
- **Versions:** a feature bumps the minor, a bug fix bumps the patch. Bump `tauri.conf.json` and `Cargo.toml` together;
  `scripts/release-tools.mjs plan` refuses a version that did not move, went back, or differs between the two. A nightly
  is the next patch with `-nightly.<run>`.
- **Publishing:** after testing the draft, run `rust-promote.yml` with its tag. It waits on the `rust-release`
  environment (required reviewers), publishes the release and copies `latest.json` to the rolling release
  `rust-stable` or `rust-nightly`, which is the address installed apps read.
- **Android:** the phone reads the same `latest.json`. Its entry is `android-aarch64` (or `android-universal`) with the
  APK `url`, `sha256` and `size`, and an empty `signature` (the Tauri desktop reader needs the field). Settings > About
  shows "update available", "Download and install" downloads the APK with a progress bar, checks the sha256, and hands it
  to Android's package installer (`REQUEST_INSTALL_PACKAGES`; the first time Android asks to allow installs from the app).
  The APK must be signed with the same key as the installed app, so the Android release keystore must stay the same.
  Code: `src/feed.rs` (rules), `src/android_update.rs` (commands), `gen/android/.../UpdatePlugin.kt` (download, install).

### Release secrets (the owner creates them; never in the repo)

```
pnpm tauri signer generate -w ~/.tauri/infantry-updater.key         # then: TAURI_SIGNING_PRIVATE_KEY = the key file's text
keytool -genkeypair -v -keystore infantry-release.keystore -alias infantry -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 infantry-release.keystore                                # ANDROID_KEYSTORE_B64
```

GitHub repository secrets: `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, `ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Then commit the updater PUBLIC key to
`plugins.updater.pubkey` in `tauri.conf.json`. Without the secrets the pipeline signs with throwaway test keys and says so
in the release notes. Create the `rust-release` environment (Settings > Environments) with the owner as required reviewer.
Test the pipeline's scripts: `node --test src-tauri/scripts`.

## MSI (RUST-INSTALLER)

- `tauri.conf.json` bundles `msi` and `nsis`; the release workflow and `scripts/rust-owner-build.ps1` build the MSI
  (`--bundles msi`). The MSI is PER USER: `windows/main.wxs` is Tauri's WiX template with `InstallScope="perUser"` and
  `INSTALLDIR` = `%LOCALAPPDATA%\Programs\<product>` (set by `SetDirectory`, which keeps WiX validation ICE64 quiet).
  No admin prompt, so the updater installs silently. `wix.upgradeCode` in `tauri.bundle.conf.json` is fixed: never change it.
- The updater entry `windows-x86_64` in `latest.json` points at the MSI and its `.msi.sig`. After the MSI runs, the
  template's `LaunchApplication` restarts the app. State (`%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`), `ui.json` and the
  token live outside the install folder, so an upgrade keeps them; `plugins/` is replaced with the new version's.
- `daemonPort` in `ui.json` pins a second install's sidecar port (the MSI restart inherits no env).
- Proof: `scripts/rust-update-e2e.ps1 -V0 <dir> -V1 <dir>` installs MSI X as a throwaway product, serves a local feed,
  drives update_check + update_install, and asserts the app is back on X+1 with settings and state kept. Build the two
  MSIs with a test overlay (productName `Infantry AI MSITest`, identifier `...msitest`, own upgradeCode, test key). Debug
  builds accept an `http://127.0.0.1` feed; release builds need https.
