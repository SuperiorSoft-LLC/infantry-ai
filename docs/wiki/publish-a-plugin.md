# Publish a plugin

Plugins have a public home: the catalog repo https://github.com/SuperiorSoft-LLC/infantry-plugins (the app's default
sources `infantry-official` and `infantry-community`, bundled data `plugins/default-sources.json`) and the starter
https://github.com/SuperiorSoft-LLC/infantry-plugin-template. Design: [PLUGIN-REPO-DESIGN](../PLUGIN-REPO-DESIGN.md).

1. Use the template repo (GitHub "Use this template"). It builds against the published SDK with the Gradle plugin.
2. Build the `.ihq` package; the output lands in the plugin repo's build folder. Its `module.json` must state an
   `author` and a `license`: an unsigned package installs into the community tier, where the loader refuses a module
   without either, so `ihq` refuses to build one and the installer refuses to install one (MASCOT-LICENSE-1):

   ```
   ./gradlew ihq   # writes build/ihq/
   ```
3. Release it: tag `<Id>-v<version>` on the plugin's repo and attach the `.ihq` as a release asset.
4. Open a PR on `infantry-plugins` adding `community/<Id>.json` (id, description, tags, license, `repo`, and per version:
   url, size, sha256, grants, api ranges). Check it with `infantry plugin catalog validate`.
5. After merge the catalogs `official.json` / `community.json` on GitHub Pages are rebuilt
   (`infantry plugin catalog build community --id infantry-community --class community`); users find the plugin with
   `infantry plugin search` and Settings > Plugins > Browse.

Community plugins are installed with explicit consent to their grants. Official-class entries must be signed; until a
plugin signing key exists the installer refuses an unsigned official entry (`NOT_SIGNED_OFFICIAL`).

## Licensing: the SDK is MPL-2.0; the application is licensed separately

Every published SDK artifact (plugin API, protocol, daemon API, harness API, UI core, BOM, version catalog and the Gradle
plugin) carries the Mozilla Public License 2.0 in its POM and as `META-INF/LICENSE` in its jar. That marks the SDK, not the
Infantry AI application, as the MPL part; the application's own licence is stated separately and is not changed by publishing
the SDK.

