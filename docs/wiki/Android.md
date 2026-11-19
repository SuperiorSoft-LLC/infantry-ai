<!-- SPDX-License-Identifier: Apache-2.0 -->
[← Wiki index](index.md)

# Android

The Android app is the same React front end as the desktop app, in a Tauri 2 mobile shell. **The phone runs no daemon
and no agents.** It connects to a daemon on another machine (your desktop, a server or a runner you control), paired once.

> **legacy-java:** the Kotlin Android shell (`infantryAndroid`, the Compose app and its APK) is on the `legacy-java` branch.

## What the phone does

- It keeps one remote address (in `remote.json` in the app's data folder) and a token. The token lives in the Android
  Keystore, never in a plain file (`app/src-tauri/src/mobile.rs`).
- It talks to that daemon over the same v1 WebSocket as the desktop app.
- It updates itself from the same `latest.json` feed as the desktop. The update is an APK download handed to the system
  installer (`app/src-tauri/src/android_update.rs`, see [Updates](Updates.md)).

## Pair the phone

1. On the desktop app, go to **Settings > Daemon** and turn on **Allow phone connections**. The daemon restarts on
   `0.0.0.0` with a bearer token. The token is kept in `remote-access.token` in the daemon state dir.
2. Press **Show pairing code**. The app shows an `infantry://connect?url=…&token=…` link and its QR code for one of this
   machine's addresses. Tailscale addresses come first. The token appears only after you press that button.
3. On the phone, scan the QR code, or paste the link. The phone stores the address and token and connects.

The daemon has to be reachable from the phone (same network, or a private network such as Tailscale). Opening the port to
the internet is your decision and outside this page.

## Build the APK

The release workflow (`.github/workflows/rust-release.yml`) builds the arm64 APK. You can build one yourself from `app/`:

```
cd app
pnpm install --frozen-lockfile
pnpm tauri android build --apk --target aarch64
```

The generated Android project is `app/src-tauri/gen/android` (application id `com.superiorsoft.infantry`). The build needs
the Android SDK with platform 36, build-tools 36.0.0 and NDK 27.2.12479018. The release APK is signed with the keystore
secrets. Without them the pipeline signs with throwaway test keys and says so in the release notes.

## Known limits

- The phone does not run agents, so a ticket or a session runs on the paired daemon, not on the phone.
- The release notes say whether a given APK is signed with the release key or with a test key.
