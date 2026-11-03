# Third-party notices

Infantry AI ships the following third-party components. Each stays under its own license; this list is the
attribution those licenses ask for. The license of Infantry AI itself is not chosen yet (see `LICENSE`).

## JediTerm (terminal emulator of the Run console)

- `org.jetbrains.jediterm:jediterm-core` and `jediterm-ui` 3.72, https://github.com/JetBrains/jediterm
- Copyright JetBrains s.r.o. and contributors.
- Dual-licensed by its authors under the GNU LGPL v3 and the Apache License 2.0, at the user's option.
  **Infantry AI uses it under the Apache License 2.0** (https://www.apache.org/licenses/LICENSE-2.0).
- JediTerm contains classes derived from jVT220 by J.W. Janssen (https://github.com/jawi/jVT220), Apache License 2.0.
- Used unmodified, as a library.

## pty4j (pseudo-terminal for runs)

- `org.jetbrains.pty4j:pty4j` 0.13.13, https://github.com/JetBrains/pty4j
- Copyright JetBrains s.r.o. and contributors.
- Eclipse Public License 1.0 (https://www.eclipse.org/legal/epl-v10.html). Used unmodified; its source is
  available at the address above.
- Bundles native helpers: winpty (Copyright Ryan Prichard, MIT License, https://github.com/rprichard/winpty) and
  Microsoft's ConPTY / OpenConsole binaries (Copyright Microsoft Corporation, MIT License,
  https://github.com/microsoft/terminal).

## JNA (native calls; used by pty4j and the daemon's process priority)

- `net.java.dev.jna:jna` / `jna-platform`, https://github.com/java-native-access/jna
- Dual-licensed under the LGPL 2.1 or later and the Apache License 2.0; used under the Apache License 2.0.

---

# DRAFT additions (LICENSE-NOTICES-2, 2026-11-03). Not official until the public launch; lawyer review pending.

Generated from the SBOM: `./gradlew generateSbom --console=plain` writes `build/sbom/{daemon,desktop,android}.cdx.json`
(CycloneDX 1.5). Versions below are the ones in that run; re-run the task to refresh. Transitive libraries not named here
(Kotlin, kotlinx, Ktor, SQLDelight, AndroidX, Compose) are Apache License 2.0 and listed in the SBOM.

| Component | Version | License | Where |
|---|---|---|---|
| sqlite-jdbc (org.xerial) | 3.51.3.0 | Apache-2.0 (bundles SQLite, public domain) | daemon, desktop |
| slf4j-api, slf4j-simple | 2.0.17 | MIT | daemon, desktop |
| JetBrains Compose Multiplatform | see SBOM | Apache-2.0 | desktop, Android, web |
| Skiko (Skia bindings) | 0.9.37.4 | Apache-2.0 (bundles Skia, BSD-3-Clause) | desktop, web |
| JLayer (com.googlecode.soundlibs:jlayer) | 1.0.1.4 | **LGPL-2.1-or-later** | desktop only (see below) |
| Google ML Kit barcode scanning | play-services-mlkit-barcode-scanning 18.3.1 | ML Kit Terms of Service (proprietary, not open source) | Android only (see below) |
| ZXing core | 3.5.3 (catalog) | Apache-2.0 | declared in the version catalog; not on the three SBOM classpaths of this run |
| js-joda | see web bundle | BSD-3-Clause | web (wasm) client, an npm dependency, not in the Maven SBOM |
| Noto Sans Symbols 2 (`NotoSansSymbols2-Regular.ttf`, infantryWeb) | as shipped | SIL Open Font License 1.1 | web client |
| JDK runtime (Eclipse Temurin 21) | 21 | GPL-2.0 with the Classpath Exception | packaged desktop builds and Docker image |
| Docker base images `eclipse-temurin:21-jre` (Ubuntu userland) and `gcc:13-bookworm` (Debian userland, build stage only) | tag | GPL/LGPL/other per package | Docker image; each distro ships its own copyright files in `/usr/share/doc` |

## JLayer (LGPL-2.1-or-later)

- Used only by `JvmSoundPlayer` (desktop mp3 decode for the incoming-comms sound and the user's custom sound). It is a
  separate, unmodified jar linked dynamically; the user can replace the jar. The LGPL text must ship with packaged
  desktop builds and the source offer is its upstream (https://github.com/umjammer/jlayer). Replacement is tracked as
  an owner decision, see the ticket report.

## ML Kit (Android)

- `com.google.mlkit:barcode-scanning` / Google Play services: governed by the ML Kit Terms
  (https://developers.google.com/ml-kit/terms) and Google APIs Terms; not redistributable source. It is an optional
  Android dependency used only for QR pairing; the Apache parts of Infantry AI do not depend on it at compile time
  outside `infantryAndroid`.

## pty4j (EPL-1.0) next to MPL-2.0

- pty4j stays a separate, unmodified library (see its entry above). The EPL-1.0 applies to pty4j only; it is neither
  relicensed nor merged into Infantry AI files. Compatibility with the MPL core is on the lawyer-review list.

## Sound file of unknown origin

- `superiorSoftLibrary/infantryUiCore/src/commonMain/composeResources/files/incoming-comms.mp3` has no recorded author or
  license (first committed in the RENAME-2 rename commit eb8236d0; earlier history is not in this repository). Treat as
  unlicensed until the owner confirms its origin or approves a replacement.
