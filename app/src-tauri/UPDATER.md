<!-- SPDX-License-Identifier: Apache-2.0 -->
# Installer and updater (RUST-UPD-1)

The Rust desktop app installs with an NSIS installer and updates itself through the Tauri updater plugin. This page
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
