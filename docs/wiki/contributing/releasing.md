<!-- SPDX-License-Identifier: Apache-2.0 -->
# Releasing

Releases are built by GitHub Actions from `main`; nobody builds a release by hand.

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/rust-release.yml` | A push to `main` that changes `app/src-tauri/tauri.conf.json` (a version bump), or by hand with `channel=nightly` | Builds every target (Windows, Android, Linux, macOS), signs the updater artifacts and makes a **draft** release `rust-v<version>` with the installers, the APK, `SHA256SUMS` and `latest.json`. Nightly makes a draft prerelease `rust-v<next patch>-nightly.<run>`. |
| `.github/workflows/rust-promote.yml` | Publishing the draft on GitHub, or by hand with its tag | Publishes the release and marks a stable one as latest. Installed apps read GitHub's latest release, so they see the update only after this step. The feed never goes backwards. |
| `.github/workflows/android-release.yml` | see the file | The Android release path. |
| `.github/workflows/os-app-release.yml` | see the file | Packages Infantry AI as a BattalionOS app. |

## Versions

A feature bumps the minor, a bug fix the patch. The `plan` job refuses a version that did not move, went back, or
differs between `tauri.conf.json` and `Cargo.toml` (`app/src-tauri/scripts/release-tools.mjs`).

## Secrets

The owner creates them; none is in the repo (`app/src-tauri/UPDATER.md`, "Release secrets"):
`TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` and the Android keystore set (`ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). Without them the jobs use throwaway test keys
and say so in the release notes; nothing signed that way is trusted by a real install.

## The order

1. Test a local build first (`scripts/rust-owner-build.ps1`).
2. Bump the version and merge to `main`: the draft is built.
3. The owner downloads and tests the draft, then publishes it: `rust-promote` runs.
