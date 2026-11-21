<!-- SPDX-License-Identifier: Apache-2.0 -->
[← Wiki index](index.md)

# Updates

The Rust desktop app updates itself through the Tauri updater plugin. The phone updates from the same feed. This page
follows the code: `app/src-tauri/src/update.rs`, `feed.rs`, `android_update.rs`, and `app/src-tauri/UPDATER.md` (the full
signing and proof notes).

> **legacy-java:** the Kotlin updater (`Infantry_Updater`) is on the `legacy-java` branch. Its notes are in the legacy section at the end of this page.

## Settings

The update settings live in `ui.json` under `updater`, and you see them in **Settings > About**.

| Setting | Default | What it does |
|---|---|---|
| `channel` | `stable` | `stable` or `nightly`. |
| `feedUrl` | `https://github.com/SuperiorSoft-LLC/infantry-ai/releases/download/rust-{channel}/latest.json` | `{channel}` is replaced with the channel. |
| `autoCheck` | on | Read the feed at start, and show one notice when a newer build exists. |
| `autoInstall` | off | Nothing installs without the user pressing Install. |

On the stable channel any version different from the one running is offered. So leaving nightly brings the newest
stable build, even if it is older.

## Check

When `autoCheck` is on, the app reads the feed at start. A newer build posts one "update available" notice in the
Attention panel.

## Install

When the user presses **Install and restart**, the app:

1. downloads the update and verifies its signature against the public key;
2. stops the daemon sidecar by its PID. The PID comes from `health/status.json`, and only while that record names the live port;
3. runs the installer, which replaces the files and starts the app again. The app's start brings the daemon back;
4. if the installer fails to start, restarts the daemon.

The app refuses to install when the public key is missing, empty, or still the placeholder text
(`PUBKEY_PLACEHOLDER` and `signed()` in `update.rs`). This tree's `tauri.conf.json` carries a real public key under
`plugins.updater.pubkey`, so the check passes here. Its notice says so when it does not.

## The feed

Each channel has one `latest.json`, published as an asset of a GitHub release tagged `rust-stable` or `rust-nightly`:

```json
{
  "version": "0.2.0",
  "notes": "What changed",
  "pub_date": "2026-11-20T12:00:00Z",
  "platforms": {
    "windows-x86_64": { "signature": "<contents of the .sig file>", "url": "https://.../InfantryAI_0.2.0_x64.msi" },
    "android-aarch64": { "signature": "<contents of the .sig file>", "url": "https://.../InfantryAI_0.2.0_arm64.apk" }
  }
}
```

A public feed needs no token. The app reads and stores no update token.

## Releases

- `.github/workflows/rust-release.yml` runs on a push to `main` that changes `tauri.conf.json` (a version bump), or by
  hand (`channel` stable or nightly). It builds the Windows MSI and NSIS installer, the Android APK (arm64), and a best-effort
  Linux AppImage and macOS app bundle. It signs the updater artifacts and makes a **draft** release `rust-v<version>` with
  `SHA256SUMS` and `latest.json`. It never publishes and never touches the feed.
- After you test the draft, run `rust-promote.yml` with its tag. It waits for the `rust-release` environment (required
  reviewers), publishes the release and copies `latest.json` to the rolling `rust-stable` or `rust-nightly` release. That is
  the address installed apps read.
- Versions: a feature bumps the minor, a bug fix bumps the patch. Bump `app/src-tauri/tauri.conf.json` and `rust/Cargo.toml`
  together. `scripts/release-tools.mjs plan` refuses a version that did not move, went back, or differs between the two. A
  nightly is the next patch with `-nightly.<run>`.

## Signing keys

The private key is never in the repo. The release reads it from the environment (`TAURI_SIGNING_PRIVATE_KEY` and
`TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, set as GitHub secrets). To make a new pair on the release machine:

```
pnpm tauri signer generate -w ~/.tauri/infantry-updater.key
```

Put the PUBLIC key text in `plugins.updater.pubkey` in `tauri.conf.json`. It is safe to commit. Keep the private key and its
password out of the repo. Android needs its own keystore secrets; `app/src-tauri/UPDATER.md` lists them.

## Windows installer

- The MSI is per user, `InstallScope="perUser"`, installed to `%LOCALAPPDATA%\Programs\<product>`. No administrator prompt,
  so the updater installs silently. `wix.upgradeCode` in `tauri.bundle.conf.json` is fixed: never change it.
- The state (`%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`), `ui.json` and the token live outside the install folder, so an
  upgrade keeps them. `plugins/` is replaced with the new version's.
- `scripts/rust-update-e2e.ps1` is the proof: it installs MSI X as a throwaway product, serves a local feed, drives
  `update_check` and `update_install`, and asserts the app is back on X+1 with settings and state kept. Debug builds accept
  an `http://127.0.0.1` feed; release builds need https.

## The phone

Android has no updater plugin. `android_update.rs` reads the same `latest.json` (the `android-aarch64` entry), downloads the
APK and hands it to the system package installer. Settings > About shows one component for both.

## Legacy (Kotlin) reference

The Kotlin updater notes are kept below, unchanged except for the heading levels. They describe the Kotlin app on the
`legacy-java` branch and are not what main runs.


## Updates

Infantry AI has ONE update engine, in core. It manages the app itself and every installed plugin, official and
3rd-party alike. Each of them is a **component**. Every component names its own **source**, which says where its new
versions live. A plugin's vendor decides that, not us. A source can need a **credential**, such as a private GitHub
repo's read token or a paid plugin's license key. The engine looks the credential up by name, hands it only to that
component's source, and never prints it.

The engine knows no release host, no repo and no file format. Those come from plugins:

- A **source type** (`github`, `folder`, a vendor's own server) is a class a daemon plugin registers.
- An **applier** (how to install a staged file for one kind of component) is a class an official plugin registers.

Core does the generic part: it lists the components, picks the source type, resolves the credential, downloads into a
staging folder, and refuses the file unless its SHA-256 matches what the source announced.

| Piece | Where |
|---|---|
| Plugin interface (`Updates`, `UpdateSourceType`, `UpdateApplier`, `Credential`, `UpdateState`) | `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/updates/Updates.kt` |
| The module.json `update` block (`UpdateDeclaration`, `UpdateSourceRef`, `UpdateCredentialRef`) | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/UpdateDeclaration.kt` |
| The engine (check, fetch, verify, stage, apply) | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/update/UpdateEngine.kt` |
| The host rules (components, declarations, credential names) | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/HostUpdates.kt` |

### 1. Components

`api.updates.components()` lists, in order:

1. `core`: the app itself (kind `core`). Its version is the running build. Its source is not written in core; an
   official plugin sets it from its own settings with `setSource("core", ...)`.
2. Every resolved module (kind `plugin`), by its `name`, at its module.json `version`, with the source from its
   module.json `update` block. A module with no `update` block ships with the app and is not updated on its own.

### 2. Declare where your plugin's releases live

Add an `update` block to your module.json:

```json
{
  "name": "Acme_Notes",
  "version": "1.4.0",
  "update": {
    "source": {
      "type": "github",
      "location": "acme/notes-plugin",
      "channel": "production",
      "credential": { "env": "ACME_NOTES_TOKEN", "secret": "acme.notes-license" }
    }
  }
}
```

| Field | Meaning |
|---|---|
| `type` | the source type id; a daemon plugin must have registered it (`api.updates.sourceTypes()` lists them) |
| `location` | what that type needs to find your releases: `owner/repo`, a URL, a folder. The engine does not read it |
| `channel` | optional; a channel the type understands |
| `credential` | optional; the NAMES the secret is looked up under: env var `env` first, then the daemon keystore entry `secret` |

A secret value never goes in module.json, a settings file or a repo. The buyer of a paid plugin puts the vendor's key
in the keystore entry (or the env var) once, and the engine passes it to that plugin's source on every check.

A module may also change its own source at run time, for example from one of its own settings:
`api.updates.setSource(api.moduleId, UpdateSourceRef(...))`. Passing `null` clears it.

#### Which credential names a component may use

So that one plugin cannot read another's key or the app's own token:

| Component | Names it may use |
|---|---|
| `core` | any (only an official module may set its source) |
| an official module | names it declared in `daemon.env` / `daemon.secrets`, or names with its vendor prefix (`INFANTRY_...` env, `infantry...` secret for `Infantry_Updater`) |
| a community module | names with its vendor prefix (`ACME_...` env, `acme...` secret for `Acme_Notes`), or names it declared AND was granted (`daemon.env:<NAME>`, `daemon.secret:<name>`). The `INFANTRY` / `infantry` prefix never counts for a community module |

Any other name is skipped silently: the source is called with no credential.

### 3. Write a source type

A source type answers two questions: what is the newest version, and where are its bytes.

```kotlin
class AcmeStoreSource : UpdateSourceType {
    override val type = "acme-store"

    override fun latest(request: SourceRequest): SourceOffer? {
        // request.location, request.channel, request.platform ("windows", "linux", "macos", "android", "any")
        // request.credential?.reveal(): the buyer's license key, or null. Never log it: Credential.toString() hides it.
        val newest = askTheStore(request.location, request.credential?.reveal()) ?: return null
        return SourceOffer(version = newest.version, artifact = "Acme_Notes-${newest.version}.zip", sha256 = newest.sha256, handle = newest.id)
    }

    override fun download(request: SourceRequest, offer: SourceOffer, into: File): File =
        File(into, offer.artifact).also { downloadTo(it, offer.handle, request.credential?.reveal()) }
}

class AcmeDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.updates.registerSource(AcmeStoreSource())
    }
}
```

Declare the type in the daemon half, or the registration is refused:

```json
"daemon": { "entry": "...", "jar": "...", "updateSources": ["acme-store"] }
```

A community module also needs the grant `daemon.updateSource:acme-store` in `modules.json` `allow`.

Rules the engine holds every source to:

- `version` must be `MAJOR.MINOR.PATCH`. An offer that is not newer than the installed version counts as up to date.
- `sha256` is required (64 hex characters). An offer without one is refused at check time, and a download whose
  SHA-256 differs is deleted and refused. Every update is verified.
- `artifact` must be a plain file name (letters, digits, `._-`), and `download` must write that file inside `into`.
- Throwing from `latest` or `download` is fine; the engine turns it into `UpdateState.Failed` with the message. Do not put
  a secret in an exception message.

### 4. Check, fetch, apply

| Call | Does | Answers |
|---|---|---|
| `check(id)`, `checkAll()` | asks the component's source for its newest version; nothing is downloaded | `NoSource`, `UpToDate`, `Available`, `Failed` |
| `fetch(id)` | downloads the found version into `<state dir>/updates/<id>/<version>/` and verifies it | `Staged` or `Failed` |
| `apply(id)` | hands the staged file to the applier of the component's kind | `Applied` or `Failed` |

Installing is per kind (`core`: the app's installer for this platform; `plugin`: replace the module's folder). An
applier is registered with `registerApplier` by an official module; a community module may check and fetch but may not
register an applier or call `apply`. With no applier for a kind, `apply` answers `Failed` and the staged file stays.

### 5. Status

The interface, the engine and the rules above are in place and tested (`UpdateEngineTest`, `HostUpdatesTest`).

The official Infantry_Updater plugin runs the daemon's own update on this engine
(`plugins/official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/daemon/EngineBridge.kt`,
tested by `EngineBridgeTest`):

- It registers the source type `infantry-release` (`ReleaseSourceType`): GitHub Releases or a local releases folder,
  with the server archive for this OS / CPU and its SHA-256 from the release's `SHA256SUMS`.
- It registers the `core` applier (`CoreArchiveApplier`): an archive install unpacks the staged archive and makes it
  current; the launcher restarts into it.
- It sets the app's source from its `updater.*` settings. A private repo names the token as env `INFANTRY_UPDATE_TOKEN`,
  else the keystore entry `update-token`; the engine resolves it, and the plugin's own OS credential store lookup is
  the last fallback.
- Its `/update/status`, `/update/check` and `/update/apply` routes are the face of the engine (`CoreEnginePath`).

Not on the engine yet:

- The desktop window's own update (download the MSI, hand it to Windows). It runs in the window process, which has no
  engine.
- The phone's `/update/client` offer. The phone installs its own APK; the daemon only answers a token-free link.
- Plugin updates. The engine lists every module and can check and fetch one, but no `plugin` applier is registered
  yet, so a module's update stays staged.

The plugin's settings stay the one config point for the app's own source. See also [Daemon plugin API](daemon-plugin-api.md)
and [Plugins](Plugins.md).

## Armory update check (daemon)

The daemon, not a client, checks installed apps and the shell against the Armory apps catalog
(`rust/crates/infantry-daemon/src/compose/armory.rs`). The catalog is one `apps.json` in the `battalionOS-apps`
repository: `{ "apps": [ { id, name, kind: app|shell, version | feed, publicKey, restart: none|daemon|relaunch } ] }`.
A `feed` is a release feed like the one above (`{channel}` is replaced). The shell counts as installed at the daemon's
version; an app counts when `armory-installed.json` in the state dir lists it. Clients read one list with the
`armory_updates` frame (id, name, kind, installed, available, notes, restart); `armory_check` is "check now"; a change is
also pushed as `armory_updates`. A dead catalog or feed is a `warnings` line, never a failure.

| Setting | Default | What it does |
|---|---|---|
| `armory.catalogUrl` | the raw `apps.json` of `battalionOS-apps` | http(s) address, file, or folder holding `apps.json`. |
| `armory.channel` | `stable` | Fills `{channel}` in a feed. |
| `armory.checkSeconds` | `0` | Seconds between checks; 0 checks only at start and on "check now". |
| `catalog.maxFileBytes` | 64 MB | Largest file a catalog (plugin sources and Armory) may download. Read at daemon start. |
