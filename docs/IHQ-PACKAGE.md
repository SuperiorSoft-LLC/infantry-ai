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
