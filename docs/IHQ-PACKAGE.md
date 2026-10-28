# .ihq plugin package (S5, desktop JVM only)

A `.ihq` is a zip: `module.json` (its `jar` is `lib/<jar>`), `lib/*.jar`, and a jarsigner signature over the **whole zip**
(`META-INF/*.SF`, `*.RSA`), so `module.json` is covered too. Nothing else is accepted.

- Build: `./gradlew :plugins:_template:ihq` (script `gradle/ihq.gradle.kts`, apply it to any plugin module). Signing is
  optional: `-Pihq.keystore=<file> -Pihq.alias=<a>` and env `IHQ_STOREPASS`. No key is in the repo.
- Install: `IhqInstaller.install(file, pluginsRoot)` (agenthqPlugins, jvmMain only; Android/wasm do not see it).
  Unpacks into `<root>/_install-<uuid>` (the scan skips `_` folders), checks, then one atomic rename to
  `<root>/<tier folder>/<name>`. `IhqInstaller.sweep(root)` removes staging folders left by a killed process.
- Checks: entry names (no `..`, absolute, drive, backslash), only `module.json` and `lib/*.jar`, limits counted on real
  unpacked bytes, module.json parses, name is a plain folder name, `jar` is a lib/ jar in the package, signature valid over
  every entry, no signed entry removed, no unsigned entry added, one signer, certificate currently valid, not already installed.
- Settings, `<root>/install.json`: `maxPackageBytes`, `maxEntryBytes`, `maxTotalBytes`, `maxEntries`, `trustedKeys`
  (folder of `.cer` files, default `<root>/trusted-keys`), `allowUnsigned` (default false).

## Decision points (expensive to reverse; owner to confirm)
1. **Signer identity = pinned certificate.** A signer is "Infantry" only if its leaf certificate equals a cert in the
   trusted-keys folder (pin, no CA chain). Rotating the key = adding a second cert; revoking = deleting its file.
2. **Tier mapping (proposal).** Trusted signer => `core` folder; any other valid signature => `community`; unsigned =>
   refused (setting `allowUnsigned` makes it community). Alternative: a second list of "known community publishers".
   Core tier means in-process full trust and replacing the placeholders, so the Infantry private key must never be on CI by default.
3. **No upgrade path.** Installing a name that exists in any tier folder is refused; update/uninstall is S6+.
4. **Signed `.ihq` as a whole** (not just the jar), so manifest tampering is detected. Timestamping is not used: an
   expired signing certificate is refused.
5. The real signing key is not generated here.

## Multi-module package (S7)
One .ihq carries many modules through a bundle: its module.json names the bundle module and an `entry` that is a `ModuleSource` (see `CoreModuleSource`); the loader asks it for every module it holds. One signature covers all of them, so a bundle signed with a trusted key installs as core tier. Build it signed: `./gradlew :superiorSoftLibrary:agenthqAppUi:appUiIhq -Pihq.keystore=<file> -Pihq.alias=<alias>` (password in IHQ_STOREPASS).

## Composition package (NS3, docs/NORTH-STAR-DESIGN.md 1b)
An exported section is a `.ihq` with no code: `module.json` (no `entry`/`jar`; `fills: [{"node": "infantry.shell.center", "layout": "layout/<name>.json"}]`,
`dependencies`, `daemon: {needs, wants}`), `layout/*.json` (the 6t.5 page; per-item nodes carry `"template": true`) and `deps/*.ihq`
(each dependency's published package, byte-identical, its own signature). `fills` still accepts the plain node-id string form.
- Export: `LayoutExport.export` (pure: subtree -> page + dependencies + daemon union, or one refusal line per cause: not a type yet,
  module not a separate package, locked node, secret reference) and `CompositionExport.export/write` (desktop). Published packages
  are looked up as `<plugins root>/packages/<Module>.ihq`, then `-Dagenthq.packages`. Settings > Plugins > Registered > "Export section…"
  writes `<stateDir>/exports/<Section>.ihq`.
- Signing: `ExportKey` (per installation, `<stateDir>/export-key/export.p12` + `export.pass`, self-signed RSA cert built in-process
  because the jlink runtime has no keytool). It never goes into trusted-keys, so a composition installs as community.
- Install: `deps/*.ihq` go in first, one level only, each by the same checks with its own tier; same name+version already installed
  = skipped, another version = refused. Then the composition folder (layout/ kept, deps/ dropped).
- Load: a composition module is read by `DesktopPlugins.composition`; `TabFactory` builds each `fills[].layout` through the block-type
  registry (`PageLayout.instantiate`) into the slot. A community composition needs the slot in its modules.json `allowOverride`.
