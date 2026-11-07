# Infantry AI plugin template

A standalone Gradle build for an Infantry AI plugin: one UI tab (`src/`), one daemon read tool (`daemon/`), tests for both,
CI and a tag-release workflow. It knows only Maven coordinates of the published SDK.

## Rename it
1. Pick `Vendor_Module` and replace `Example_Starter` in `module.json` (`name`, `jar`, `daemon.jar`).
2. Rename the package `com.example.starter` and update `entry` and `daemon.entry` in `module.json`.
3. Set `author`, `description` and an SPDX `license` (the template says `NOASSERTION`).
4. Rename the tab id and the tool in `module.json` and in the code (the app refuses anything undeclared).

## Build
The SDK (`tech.superiorsoftllc.infantry:infantry-sdk-catalog`, `-bom`, `infantry-plugin-api`, `infantry-daemon-api`,
`infantry-harness-api`, ...) and the Gradle plugin `tech.superiorsoftllc.infantry.plugin` come from Maven Central: no account
and no token. `infantry.sdk.version` in `gradle.properties` picks the SDK release.

    ./gradlew build ihq

To build against a local SDK repository instead, add `-Pinfantry.sdkRepo=<path>`. For an SDK release that is not on Central
yet, `-Pinfantry.sdk.githubPackages=true` also reads GitHub Packages; that needs a token with `read:packages`
(`GITHUB_ACTOR` / `GITHUB_TOKEN`, or `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`).

**Until the SDK is on Maven Central, the SDK is published to GitHub Packages only, and GitHub Packages needs a token even for
public packages.** The CI workflows here already pass `-Pinfantry.sdk.githubPackages=true` with the workflow `GITHUB_TOKEN`
(the repository must be allowed to read the package: package settings > Manage Actions access). Locally, create a classic
token with `read:packages` and put `gpr.user=<you>` and `gpr.key=<token>` in `~/.gradle/gradle.properties`, then build with
`./gradlew build -Pinfantry.sdk.githubPackages=true`. The SDK artifacts are MPL-2.0.

## Install locally

    infantry plugin install build/ihq/<Name>.ihq --grant-all-requested

## Halves
* Daemon-only plugin: delete `src/` and the Compose lines in `build.gradle.kts`.
* UI-only plugin: delete `daemon/` and the `daemon` block in `module.json`.
* Add a harness half: create `harness/build.gradle.kts` (a Kotlin JVM project using `alias(infantry.plugins.kotlin.jvm)`),
  add a `harness` block to `module.json`; `settings.gradle.kts` includes it and the Infantry Gradle plugin wires
  `infantry-harness-api` and packs it into the `.ihq`. This template ships without one.

## Release
Set `version` in `module.json`, then push the tag `v<version>`. `.github/workflows/release.yml` checks the tag equals the
version, builds, tests, packs the `.ihq`, writes `catalog-entry.json` and runs `gh release create` with both files. Optional
signing: repository secrets `IHQ_KEYSTORE_B64` (base64 of the keystore), `IHQ_STOREPASS`, `IHQ_ALIAS`.

## Submit to the registry
Open a pull request to the `infantry-plugins` registry repository adding your `catalog-entry.json` for the release
(see REGISTRY-1b). The daemon recomputes the permission grants when it validates the entry.
