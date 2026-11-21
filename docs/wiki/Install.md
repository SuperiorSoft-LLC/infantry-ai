<!-- SPDX-License-Identifier: Apache-2.0 -->
[← Wiki index](index.md)

# Install

Infantry AI on `main` is the Rust framework: a desktop app (Tauri 2 with a React front end) that runs the daemon beside
it, a headless daemon, the `infantry` CLI, and an Android app that pairs to a daemon on another machine.

> **legacy-java:** the Kotlin app, its installers and its Gradle build live on the `legacy-java` branch. The install notes for that version are in [Command line](CommandLine.md) and [Architecture](Architecture.md), under their legacy sections.

## Windows desktop app

1. Download the installer from the GitHub releases of `SuperiorSoft-LLC/infantry-ai`. Rust releases are tagged `rust-v<version>`.
   The stable feed is the rolling `rust-stable` release, and `rust-nightly` is the nightly one.
2. Run the MSI (per user, `%LOCALAPPDATA%\Programs\<product>`, no administrator prompt) or the NSIS setup.
3. Start **Infantry AI**. The app starts the daemon for you as a sidecar process on this machine, on port **18990**, unless
   `daemonPort` in `ui.json` pins another port. The sidecar outlives the window, so the next start connects to it.

Your data lives outside the install folder: the daemon state dir is `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` and
`ui.json` sits beside it. An upgrade keeps them.

### The `infantry` command on PATH

PATH is opt-in. Go to **Settings > Daemon** and turn on **Add `infantry` to PATH**. The app stores `paths.addCli` in
`ui.json` and runs `infantry install-cli --dir <install folder>`. While the setting is on, each start runs it again. The
uninstaller runs `infantry uninstall-cli`, which removes only the entry it recorded (`app/src-tauri/windows/installer-hooks.nsh`).

Check it from any terminal:

```
infantry health
```

### The guided setup (first start)

On a start where no agent tool can answer, the app opens the guided setup by itself; **Settings > Daemon > Open guided
setup** opens it again at any time. One choice per screen:

1. **Get Node.js** (only while npm is missing and nothing is installed): the daemon downloads the nodejs.org LTS Windows
   zip, checks it against the release's `SHASUMS256.txt` and unpacks it into `<state dir>\harness-tools`. No admin prompt.
2. **Pick your agent tool**: claude, codex, opencode or pi. The daemon runs `npm install -g --prefix <state dir>\harness-tools <package>@latest`.
3. **Sign in**: skipped when the tool can already answer (a login, a token, or free models). Otherwise start the tool once in a terminal, sign in, press Check again.
4. **Say hello**: a first agent runs in a starter project (`My first project`) and its reply shows.

The same install works without the window: `infantry harness-status` and `infantry harness-install [id...] [--update]`.

## Headless daemon (no window)

The daemon is a separate binary, `infantry-daemon`. It runs on its own and needs no launcher script:

```
infantry-daemon --port 18990
```

- `INFANTRY_PORT` (default 18990) and `INFANTRY_HOST` (default `127.0.0.1`) set the bind when the flags are not given.
- A bind on a non-loopback address needs a token: set `INFANTRY_TOKEN_FILE` or `INFANTRY_TOKEN`.
- The daemon refuses port 8797, which belongs to the Kotlin app.
- Check it with `curl http://127.0.0.1:18990/health`.
- `--mode runner` starts a runner-only daemon for a remote machine. See [Runners](runner-quickstart.md).

The official plugins are loaded from a `plugins/` folder beside the daemon (or `INFANTRY_PLUGINS_DIR`). The installer ships
them there. See [Plugins](Plugins.md).

A build can instead ship them in a `plugins-bundled/` folder (or the folder `INFANTRY_BUNDLED_PLUGINS_DIR` names) and leave
`plugins/` empty. The daemon then copies the bootstrap plugin (`Infantry_Installer`) into `plugins/` and opens on its Apps
screen; the owner installs what they want from there, live, and the app opens as usual after the first install. See
[the installer](Plugins.md#the-installer-and-the-bootstrap-plugin).

## Other platforms

The release workflow (`.github/workflows/rust-release.yml`) also builds, on a best-effort basis, a Linux AppImage and a
macOS app bundle. The macOS bundle is unsigned. Both are test builds, so check the release notes before you rely on one.

## Build from source

You need Rust (the workspace is `rust/`), Node with pnpm 11.21.0 (the app is `app/`), and for the Windows installer the
Tauri prerequisites.

```
# daemon and CLI (from rust/)
cargo build --release -p infantry-distribution -p infantry-cli

# app (from app/)
pnpm install --frozen-lockfile
pnpm installer                              # a debug installer; it lands in the Cargo target's bundle/nsis/ folder
```

The owner's one-command build of the app and the daemon on Windows is `scripts/rust-owner-build.ps1`. It builds the UI,
the daemon, the shell, stages the plugins and packages a folder under `<DistRoot>\builds\`. It never launches anything.

For the signed release installer and the updater keys, read [Updates](Updates.md).

## Next steps

- [Android](Android.md): pair the phone.
- [Updates](Updates.md): the update channels and how the app installs a new build.
- [Command line](CommandLine.md): what the `infantry` command does.
- [Architecture](Architecture.md): what runs where.
