# LICENSE-MAP: directory-by-directory license split (APPLIED)

> The owner decided on 2026-11-03 (ticket 67e0d704, LICENSE-APPLY-1;
> memory `license-infantryplugins-apache`) and will review once everything is live. The rule: **MPL-2.0 is the abstraction layer**
> (the generic engine everything plugs into): `infantryProtocol`, `infantryCore`, `infantryRunner`, `infantry`, and the reserved
> empty `plugins/core/`. **Apache-2.0 is everything concrete on top**: `infantryPlugins` (the whole module, `plugin.api` AND
> `plugin.host`), `infantryBoard`, `infantryDaemonApi`, `infantryHarnessApi`, `infantryUiCore`, `infantryAppUi`, the apps, `samples/`,
> all of `plugins/`, `docs/`, `docker/`, `scripts/`, `gradle/`, `.github/` and the root build files. Neither license: logo, emblem
> and name (trademark). **Applied in the repo:** full texts in `LICENSES/`, a `LICENSE` in each MPL module and each Apache top
> directory, an SPDX header on every `.kt .kts .sh .ps1 .mjs` file (`scripts/license-headers.sh`, rule in
> `scripts/license-paths.txt`), and `LicenseMapGuardTest`. The tables below state this applied split.
> Owner reasons: `infantryPlugins` is one module and the
> plugin host is what plugin authors build against, so it is Apache whole; the protocol is part of the abstraction layer, so MPL
> (an Apache SDK may use unmodified MPL files, S13 is accepted); `infantryBoard` is a concrete feature, so Apache.

History: prepared by tickets 28145912 (LICENSE-PREP-1) and 835d76fe (LICENSE-PREP-2), applied by 67e0d704
(LICENSE-APPLY-1). Related: `docs/DAEMON-CORE.md`, `docs/wiki/licensing.md`. Not legal advice; section 6 lists the lawyer review.

Key words: **MPL** = Mozilla Public License 2.0 (file-level weak copyleft: changed MPL files stay MPL, a larger work may mix
other licenses). **Apache** = Apache License 2.0. **n/a** = not licensed by us (generated, third-party, or trademark).

## 1. The line, in one paragraph

MPL is file-level, so a module can be MPL while Apache plugins link against it, and Apache code may depend on MPL code. The
reverse is the rule that matters for taint: **MPL core must never contain Apache plugin code or demo-named code** (otherwise a
fork of core would have to keep plugin code under MPL, or core would depend on something outside the core promise). Apache
modules (the SDK/API) must import **nothing** MPL-only, else a plugin author who compiles against the SDK would be pulled onto
MPL terms. Today the physical line is blurry in exactly one place: `superiorSoftLibrary/infantry` is one Gradle module holding both
core and "stays/plugin-later" feature packages (section 3, S1); the whole module is MPL-2.0. Everything else splits cleanly by directory.

## 2. The map: every top-level and second-level directory

| path | proposed | reason | dependency direction check |
|---|---|---|---|
| `/` root files: `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*` | Apache-2.0 (repo default) | build glue and docs; `gradlew*` and `gradle/wrapper` are Apache-2.0 from Gradle already | n/a (no code deps) |
| `LICENSE`, `THIRD-PARTY-NOTICES.md` | replaced / extended (section 7) | `LICENSE` becomes a pointer to this map + the two texts; notices extended (section 5) | n/a |
| `gradle/` (`libs.versions.toml`, `*.gradle.kts` scripts, `wrapper/`) | Apache-2.0 | build logic, not product; `wrapper/` is Gradle's own Apache-2.0 | n/a |
| `.github/` (workflows, issue templates) | Apache-2.0 | CI config; `release.yml` signs releases with the Infantry key (secret, never in repo) | n/a |
| `assets/branding/` | **n/a: trademark + all-rights-reserved** | logo/emblem/icon of the name Infantry AI; Apache-2.0 section 6 grants no trademark rights, but copyright on the image should be kept out of the Apache grant | referenced by the app resources; the copies embedded in `infantryUi/appResources` and `icons/` need the same carve-out (S9) |
| `docs/` (incl. `docs/qa-e2e-2`, `qa-e2e-3`, `blocks-perf`) | Apache-2.0 | documentation and QA scripts. `docs/qa-e2e-*` and `blocks-perf` hold captured results; check no screenshot embeds a third-party asset | n/a |
| `docker/` | Apache-2.0 | Dockerfile, compose, entrypoint, smoke and demo scripts; **but** `landlock-exec.c` (6 KB, ours) is Apache. The *built image* bundles GPL/LGPL OS packages (base image); see S8 | scripts call the daemon only through its CLI/HTTP, no linking |
| `scripts/` (`infantry-gate.sh`) | Apache-2.0 | dev tooling | n/a |
| `plugins/` root: `README.md`, `GUIDE.md`, `modules.json` | Apache-2.0 | the plugin author's guide must be under the SDK license | n/a |
| `plugins/core/` | **MPL-2.0 (reserved)** | today only a `README.md`; the tier is reserved for modules that are part of the shell. Keep MPL so a core-tier plugin cannot drift to Apache by accident. If it stays empty the LICENSE can be skipped | must not contain `Infantry_*` or `Demo_*` |
| `plugins/official/` (27 `Infantry_*` UI modules, 3 with a `daemon/` half: Board, Processes, Usage) | Apache-2.0 | D3/D4: official = plugins by the app's own company, removable, not core. Same license as community so a third party can fork or replace any of them | depends on `infantryUiCore` + `infantryPlugins` (Apache) and, for `daemon/`, `infantryDaemonApi` (MPL). Apache -> MPL: allowed |
| `plugins/community/` (`Acme_Echo`, `Acme_Hello`) | Apache-2.0 for the two shipped samples | they are Acme sample modules; **real third-party community modules in this folder keep their own license** (D13: community/user dirs are not forced). Say so in `plugins/README.md` and in the site wording | uses `infantryUiCore` only |
| `plugins/examples/` (`Acme_DaemonHello`, `Acme_FieldKeys`, `Acme_PolicyHook`, `echo-backend`) | Apache-2.0 | teaching code; people copy it. Apache gives them patent grant and no copyleft | `daemon/` halves compile `compileOnly` against `infantryDaemonApi` only |
| `plugins/demo/` (`Demo_Banner`, `Demo_Lock`) | Apache-2.0 | D16: demo = ordinary plugins overriding live components; same terms as the rest. **Never in core** (guard exists) | `Demo_Lock/daemon` main compiles `compileOnly` against `infantryDaemonApi` only (S6 resolved); only its tests see `:infantry` |
| `plugins/_template/` | Apache-2.0 | copied by authors as a starting point; must be the most permissive, no header friction | `infantryUiCore`, `infantryDaemonApi` only |
| `superiorSoftLibrary/` (the Gradle `:superiorSoftLibrary` parent) | n/a | directory prefix only, no sources | n/a |
| `superiorSoftLibrary/infantryProtocol` | **MPL-2.0** | wire contract and DTOs; physically core. `infantryDaemonApi` (Apache) uses it unmodified, accepted (S13) | leaf: `infantryProtocol/build.gradle.kts` has no `project(...)` line |
| `superiorSoftLibrary/infantryRunner` | **MPL-2.0** | process runner, PTY, watch shims, tool profiles | leaf: no `project(...)` line; external deps kotlinx-serialization, JNA, pty4j (EPL-1.0, S10) |
| `superiorSoftLibrary/infantryCore` | **MPL-2.0** | the daemon core extracted by CORE-SPLIT-4b (150 main files: `agents db harness mailbox mvp persistence policy prompts provider session settings stats usage worktree`...) | `build.gradle.kts` 27-32: `api(project(...))` on `infantryPlugins`, `infantryDaemonApi`, `infantryHarnessApi`, `infantryProtocol`, `infantryRunner`, `infantryBoard`. MPL -> Apache SDK edges (allowed); no `:plugins:*`, no `daemon-plugin-*`, no `harness-plugin-*`. No `infantry.plugins.`/`com.acme.` identifier in its sources |
| `superiorSoftLibrary/infantry` | **MPL-2.0** (the rest of the daemon: 155 main files in `archive backlog cli client daemon epics experiments notify orchestration testrun ticketfiles tokens triggers update`; DaemonMain, plugin host, CLI, HTTP shell) | also holds feature packages; MPL as a whole module (S1) | lines 56-66: `implementation` `infantryPlugins`, `infantryDaemonApi`, `infantryHarnessApi`; `api` `infantryCore`, `infantryBoard`, `infantryProtocol`, `infantryRunner`. Lines 111-115: **test-only** `daemon-plugin-official-Infantry_Roles`, `harness-plugin-official-Infantry_Claude|OpenCode` (not shipped). Line 472 reads `infantryWeb`'s build dir (bundle bytes, no code edge). No `Demo_*`; the only `Infantry_*` text in main sources is strings and comments (`AgentDefCli`, `TicketCommentCli`, `UsageCli` messages, `CoreHarnessContributions` id as data, `DaemonPluginHost.kt:445` id compare) |
| `superiorSoftLibrary/infantryBoard` | **Apache-2.0** | the ticket board (`TicketBoard.kt`, frames, snapshots) split out of `infantry`; the Board plugin's daemon half fronts it. Concrete feature, so Apache (S15) | lines 15-16: `api` `infantryDaemonApi`, `infantryProtocol`; nothing else |
| `superiorSoftLibrary/infantryHarnessApi` | **Apache-2.0** | the ONE surface a harness plugin compiles against | leaf, header comment forbids any project dependency; no `project(...)` line. Clean |
| `superiorSoftLibrary/infantry/headless/` | Apache-2.0 | launcher scripts (`infantry`, `.cmd`, `infantry-server.*`); trivial, users edit them | n/a |
| `superiorSoftLibrary/infantry/preflight/` | Apache-2.0 | branch verification build script | n/a |
| `superiorSoftLibrary/infantryDaemonApi` | **Apache-2.0** | the plugin-facing daemon SDK. The older text in `DAEMON-CORE.md` §4 step 5 says "MPL-2.0" for it: **this map overrides that line** (S2) | `build.gradle.kts` 16-17: `api(infantryPlugins)` (Apache, ok) and **`api(infantryProtocol)` (MPL, used unmodified: accepted, S13)**. Sources import `infantry.plugin.*` (82 lines) and 2 `infantry.protocol.*` lines (`TriggerPorts.kt:4-5`: `TriggerEventView`, `TriggerView`) |
| `superiorSoftLibrary/infantryPlugins` | **Apache-2.0** | the UI plugin API + host loading rules (`plugin.api`, `plugin.host`), KMP | leaf: no `project(...)` line, imports only `...infantry.plugin.*`. Clean |
| `superiorSoftLibrary/infantryUiCore` | Apache-2.0 (S4) | UI contract + block model + shell, shared by desktop, wasm, Android | `build.gradle.kts` 29 `api(infantryPlugins)`, 51 `implementation(infantryAppUi)`, 27 `Infantry_*` compile-in projects (D9). No `infantry*` MPL edge. Apache -> Apache only |
| `superiorSoftLibrary/infantryAppUi` | Apache-2.0 | shared Compose app scaffolding | line 232 `api(infantryUiCore)`; 224/229 official + demo modules (compile-in, Apache). Clean |
| `superiorSoftLibrary/infantryUi` | Apache-2.0 (S5) | the desktop app: Compose host, bundles the official plugins, JediTerm console | line 91 `implementation(infantry)` (MPL; Apache -> MPL allowed), 22/27 `infantryUiCore`/`infantryAppUi`, 30 `Infantry_*`; tests also use `infantryDaemonApi`. Distributed artifact is a combined work (S5) |
| `superiorSoftLibrary/infantryUi/{appResources,icons}` | n/a for the logo, Apache for generic icons | the app icon is the trademarked emblem (S9) | n/a |
| `superiorSoftLibrary/infantryUi/{tab-shots-baseline, tab-shots-baseline-s0}` | Apache-2.0 | reference screenshots for tests | n/a |
| `superiorSoftLibrary/infantryWeb` | Apache-2.0 | wasmJs client, the bundle the daemon serves at `/`. Third-party `NotoSansSymbols2-Regular.ttf` (OFL 1.1) and `js-joda` (BSD-3) with their texts | lines 19/21: `infantryUiCore`, `infantryAppUi` only. `infantry` embeds the bundle bytes (larger work, allowed) |
| `superiorSoftLibrary/infantryAndroid` | Apache-2.0 | Android shell; ML Kit barcode is Google proprietary (S10) | lines 141/143: `infantryUiCore`, `infantryAppUi` only, no `infantry` |
| `superiorSoftLibrary/infantryHeadless` | Apache-2.0 | headless smoke/test harness around the daemon | **no compile edge**: line 15 forbids `project(":superiorSoftLibrary:infantry")`; line 51 only reads `infantry`'s install dir (a run-time path) |
| `superiorSoftLibrary/samples/markdownEnginePlugin` | Apache-2.0 | example plugin | `infantryUiCore` |
| `build/`, `.gradle/`, `.idea/`, `*/build/**`, `.worktrees/`, `local.properties` | n/a | generated or machine-local, not distributed from source | n/a |

Every Gradle module (from `settings.gradle.kts`) and its proposed license:

| Gradle module(s) | license |
|---|---|
| `:superiorSoftLibrary:infantryProtocol`, `:infantryRunner`, `:infantryCore`, `:infantry` | MPL-2.0 |
| `:superiorSoftLibrary:infantryDaemonApi`, `:infantryBoard`, `:infantryHarnessApi`, `:infantryPlugins`, `:infantryUiCore`, `:infantryAppUi`, `:infantryUi`, `:infantryWeb`, `:infantryAndroid`, `:infantryHeadless`, `:samples:markdownEnginePlugin` | Apache-2.0 |
| `:plugins:official:Infantry_*` (27) and `:daemon-plugin-official-*` (Board, Processes, Usage) | Apache-2.0 |
| `:plugins:community:Acme_*`, `:plugins:demo:Demo_Banner|Demo_Lock`, `:daemon-plugin-demo-Demo_Lock`, `:daemon-plugin-examples-*`, `:harness-plugin-*` | Apache-2.0 |
| `:plugins:_template`, `:daemon-plugin-_template` | Apache-2.0 |
| `:superiorSoftLibrary` (parent), build script plugins | n/a |

## 3. Straddlers and recommendations

| id | item | problem | recommendation |
|---|---|---|---|
| S1 | **`infantry` + `infantryCore`**: core and feature packages in one module | The physical split happened (LICENSE-PREP-2): `infantryCore` was extracted from `infantry` and `infantryBoard` from both; neither depends on a plugin or demo. Some feature packages of `DAEMON-CORE.md` §3 still sit in `infantry` / `infantryCore`. | Applied: both modules are **MPL-2.0** as whole modules. Feature code that moves into a plugin's `daemon/` folder becomes Apache with the move (owner holds copyright; outside contributions need the CLA, section 6). |
| S2 | `infantryDaemonApi` | `DAEMON-CORE.md` §4 step 5 says "`infantryCore` + `infantryDaemonApi` MPL-2.0". The ticket decision says SDK/API = Apache. | **Apache-2.0**, applied; this map overrides that older DAEMON-CORE.md line. Reason: daemon plugin authors compile against it; MPL there would make their jar a combined work reviewed by a lawyer for no benefit. API check: imports only `infantry.plugin.*` (from `infantryPlugins`) and kotlinx-serialization. |
| S3 | `infantryProtocol` | The wire contract that clients (CLI, web, Android, third-party UIs) implement. | Applied: **MPL-2.0** (part of the abstraction layer). MPL triggers only on modified MPL files, so an Apache client may use the protocol classes unmodified. |
| S4 | `infantryUiCore` | Apache, but heavy: depends on every official plugin project (compile-in) and bundles `incoming-comms.mp3` (provenance not documented), `jlayer` (LGPL) on JVM. | Apache-2.0. Action: document the mp3's origin or replace it; replace `jlayer` (LGPL-2.1+, dynamic library use, ok but must ship its license and allow relinking; see S10). Also confirm no file here imports `infantry` or `infantryRunner` (guard G3). |
| S5 | `infantryUi` (desktop app) | Apache module that depends on the MPL `infantry` and embeds it; the packaged installer is a *Larger Work* with both licenses plus all third-party jars. | Apache-2.0 for `infantryUi` sources. The installer must carry both license texts, the MPL "source form is available at <URL>" notice for the MPL files, and THIRD-PARTY-NOTICES. Lawyer to confirm the Larger Work wording. |
| S6 | `Demo_Lock/daemon` | **Resolved (LICENSE-PREP-2).** Its main compile path is `compileOnly(infantryDaemonApi)` only, the same as `plugins/examples/Acme_PolicyHook`; the whole `:infantry` appears only as `testImplementation` (tests run the lock against the real action catalog) and is never shipped or on the compile path. Verified by compiling the module and running its tests. Nothing is missing from the API. | Keep Apache. No demo-named code in core (identifier scan finds none; `NoDemoInDaemonCoreGuardTest` stays the guard). |
| S7 | `plugins/official/*/daemon` (Board, Processes, Usage) | Apache daemon halves of features whose packages still live in MPL `infantry` (`backlog`, `epics`...). The plugin half is Apache, the feature core it fronts is MPL. | Accept. This is the expected migration state: when DAEMON-PLUGINS-2/3 move code from `infantry` into the `daemon/` folder, the moved files become Apache (owner as sole copyright holder can relicense them; contributors' files need the CLA/DCO answer). |
| S8 | `docker/` | Our scripts are Apache, but the image built from `Dockerfile` redistributes a Linux base image and OS packages (GPL/LGPL). | Apache for the sources in `docker/`. For published images: add an image `NOTICE` listing base image + packages, and offer source (distro already hosts it). Lawyer item. |
| S9 | `assets/branding`, `infantryUi/appResources`, `infantryUi/icons` | Logo and emblem are trademark and copyright. Putting them under Apache-2.0 would give everyone a copyright licence to copy the logo (trademark is separate, but it muddies the policy). | Exclude by a per-directory `LICENSE`-note: "all rights reserved, trademark of SuperiorSoft LLC". Generic UI icons (`AhqIcons`, `AhqRoleIcons`, `AhqHarnessIcons` in `infantryUiCore`) are our drawn vectors: Apache. Check each harness icon (Claude, OpenCode, Pi...) is not a third-party logo; those are *their* trademarks. |
| S10 | Third-party bits listed in section 4 | pty4j EPL-1.0, jlayer LGPL, ML Kit proprietary, Noto font OFL. | See section 4. |
| S11 | `plugins/community/` | A folder name that invites third parties; the two shipped `Acme_*` samples are ours. | Apache for our samples; state that other contents keep their authors' license (D13: community/user dirs are not forced MPL). |
| S12 | `plugins/core/` | Empty tier, reserved. | If kept empty, ship no LICENSE file there. If a module ever lands, it is MPL. Guard against `Infantry_*` moving into it by accident (section 8). |

Direction summary: allowed edges are Apache -> Apache, Apache -> MPL, MPL -> MPL. Forbidden: MPL -> Apache plugin / SDK-only
convenience that puts plugin code in core, and anything -> `Demo_*` from core. Observed today: `infantry` has no `:plugins:*`
or `daemon-plugin-*` dependency and no `infantry.plugins.`/`com.acme.` source reference (guarded); `infantry` -> `infantryWeb`
(Apache bundle) and -> `infantryPlugins`/`infantryDaemonApi` (Apache SDKs) are MPL -> Apache **library** edges, which MPL allows
(an MPL work may use Apache libraries; the only question is whether MPL core may *contain* them, and it does not copy them).

Added by LICENSE-PREP-2:

| id | item | problem | recommendation |
|---|---|---|---|
| S13 | `infantryDaemonApi` (Apache) depends on `infantryProtocol` (MPL) | `infantryDaemonApi/build.gradle.kts` `api(project(":superiorSoftLibrary:infantryProtocol"))`; `triggers/TriggerPorts.kt` imports `TriggerEventView`, `TriggerView`. | Applied: accepted. An Apache SDK may use unmodified MPL files; the edge stays. |
| S14 | Duplicate event-stream reader | See 3a. | One copy in an Apache module. |
| S15 | `infantryBoard` | The ticket board, a concrete feature module. | Applied: **Apache-2.0**. |

### 3a. Removing the copied event-stream reader (proposal only, no code moved)

`EventStreamTranslator.kt` exists twice: `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/harness/EventStreamTranslator.kt`
(189 lines, MPL, core types) and `plugins/official/Infantry_Pi/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/pi/harness/EventStreamTranslator.kt`
(Apache, API types). `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/harness/EventStreamTranslatorCopyTest.kt`
keeps them identical outside package/import lines. One file under two licenses is a blur point, and a fix has to be written twice. Its only imports are
`kotlinx.serialization.json.*`.

Proposal: **one copy in `infantryHarnessApi` (Apache, leaf)**, which both sides already see (pi plugin `compileOnly`, daemon via `infantryCore` line 29).
Follow-up ticket, after owner sign-off: (1) move the file into `infantryHarnessApi`; (2) delete the core copy and the plugin copy; (3) `infantryCore`'s descriptor
harnesses and the pi plugin both use the API one (the daemon's `HarnessApiClassLoader` allow-list must expose the new package); (4) delete
`EventStreamTranslatorCopyTest`, add a translator test next to the API copy. Files touched: the two `EventStreamTranslator.kt`, `EventStreamTranslatorCopyTest.kt`,
`HarnessApiClassLoader` and its callers in `infantryCore/.../harness`, `infantryHarnessApi/build.gradle.kts`. Open point for the owner: the file needs
`kotlinx-serialization-json`, which **breaks the API's "no library beyond the Kotlin stdlib" promise**; options are (a) allow that one library in the API, (b) a small
hand-rolled JSON reader in the API, (c) keep the code in core and expose only a function type through the API. Recommended: (a). Until then the copy test stays.

## 4. Third-party dependencies and licenses

Source: `libs.versions.toml`, the module build files, and a real `:superiorSoftLibrary:infantry:dependencies --configuration
runtimeClasspath` run (exit 0). No Gradle lockfiles exist in the repo (none checked in), so versions below are the declared
ones; re-run the report per release. A full license report plugin (e.g. a CycloneDX/`license-report` task) is **not run**; the
transitive licenses below are from the well-known licenses of the listed artifacts and must be verified with a generated
SBOM before release.

| dependency (group:artifact, version) | where | license | verdict |
|---|---|---|---|
| Kotlin stdlib/reflect/test 2.3.10, kotlinx-coroutines 1.10.2, kotlinx-serialization 1.10.0, kotlinx-datetime 0.7.1, kotlinx-io, JetBrains annotations | all | Apache-2.0 | ok, attribution in NOTICE |
| Ktor server/client 3.4.1 (core, cio, websockets, content-negotiation, js) | `infantry`, `infantryUi`, `infantryUiCore`, `infantryWeb` | Apache-2.0 | ok |
| SQLDelight 2.2.1 (runtime, jdbc-driver, sqlite-driver, primitive-adapters) | `infantry` | Apache-2.0 | ok |
| `org.xerial:sqlite-jdbc` 3.51.3.0 | `infantry` | Apache-2.0 (bundles SQLite, public domain) | ok |
| `org.slf4j:slf4j-api`/`slf4j-simple` 2.0.17 | `infantry` runtime | MIT | ok, attribution |
| `com.typesafe:config`, `org.fusesource.jansi:jansi` | transitive (Ktor server) | Apache-2.0 | ok |
| Compose Multiplatform 1.10.3 (runtime, foundation, material3, resources, desktop) | UI modules | Apache-2.0 | ok. Desktop distribution bundles a JetBrains Runtime/JDK: check that JDK's license (GPLv2 + Classpath Exception for OpenJDK/JBR) is shipped with the installer |
| Skiko / Skia (via Compose desktop & wasm) | UI | Apache-2.0 (Skia BSD-3) | ok |
| `net.java.dev.jna:jna`, `jna-platform` 5.18.1 | `infantry`, `infantryRunner` | dual LGPL-2.1+ / Apache-2.0 | ok **only if** used under Apache-2.0; already declared in THIRD-PARTY-NOTICES |
| `org.jetbrains.pty4j:pty4j` 0.13.13 | `infantry`, `infantryRunner` | **EPL-1.0** (weak copyleft; bundles winpty MIT, ConPTY/OpenConsole MIT) | compatible as an unmodified library. MPL-2.0 is not on EPL-1.0's "secondary license" list, so Infantry code must not be *derived from* pty4j source; linking is fine. Keep notices, point to pty4j source. Lawyer to confirm |
| `org.jetbrains.jediterm:jediterm-core/ui` 3.72 | `infantryUi` | dual LGPL-3.0 / Apache-2.0 | ok under Apache-2.0 (declared); contains code derived from jVT220 (Apache-2.0) |
| `com.googlecode.soundlibs:jlayer` 1.0.1.4 | `infantryUiCore` (JVM, `JvmSoundPlayer` alone) | **LGPL-2.1+** | **incompatible with a "no LGPL" policy if the owner wants one.** Dynamic jar use is allowed but the user must be able to replace it and the LGPL text must ship. Easiest: replace the mp3 decoder (`javax.sound` with a WAV asset, or a permissive decoder). The toml comment already says "swap it there if the licence rules it out" |
| `com.google.zxing:core` 3.5.3 | `infantryUiCore` (QR) | Apache-2.0 | ok |
| `com.google.mlkit:barcode-scanning` 17.3.0 | Android only | **Google proprietary ML Kit terms** (not an OSS license; redistributes binary models) | not copyleft, but not OSS: Android build is not "pure Apache". Alternative: zxing-only scanner. Flag in the site wording |
| AndroidX activity/camera (CameraX 1.4.2), androidx.test | Android | Apache-2.0 | ok |
| `NotoSansSymbols2-Regular.ttf` (infantryWeb) | wasm bundle | SIL OFL 1.1 | ok to redistribute; keep `NotoSansSymbols2.LICENSE.txt` next to the font (present); may not sell the font alone |
| `js-joda` (infantryWeb) | wasm bundle | BSD-3-Clause | ok; license text present |
| `incoming-comms.mp3` (infantryUiCore) | resource | **unknown provenance** | owner must confirm the author/licence, else replace |
| Docker base image + OS packages | `docker/` | mixed incl. GPL/LGPL | see S8 |
| External harness CLIs run by the daemon (Claude Code, OpenCode, pi...) | not bundled | each vendor's terms | **not redistributed** by us; keep it that way (do not bundle). Names/icons are their trademarks |

Found: no GPL/AGPL and no Commons Clause/SSPL/BSL component in the runtime graph checked. Items needing action: **jlayer
(LGPL)**, **pty4j (EPL-1.0)**, **ML Kit (proprietary)**, **mp3 provenance**, **JDK/JBR and Docker base** in packaged artifacts.

## 5. Notices and attribution to produce at sign-off (not done here)

`THIRD-PARTY-NOTICES.md` currently lists JediTerm, pty4j, JNA. Add: sqlite-jdbc, slf4j (MIT), Noto font (OFL), js-joda, zxing,
jlayer (if kept), ML Kit (Android), Compose/Skiko, JDK. One `NOTICE` (Apache 2.0 §4(d)) is required only if a dependency ships a
NOTICE file we redistribute; generate it from the SBOM.

## 6. What the owner still has to do

**A. CLA vs DCO** (needed before outside contributions, and to later move files MPL -> Apache as in S1/S7):

| | CLA (e.g. Apache ICLA style) | DCO (`Signed-off-by`) |
|---|---|---|
| pros | grants SuperiorSoft the right to relicense and to offer a hosted/commercial edition; clean chain of title for the paid orchestrator | zero friction, no signing service, familiar to kernel/Linux devs, lower contributor drop-off |
| cons | friction, needs a signing bot and legal text, deters hobbyists; corporate contributors may need legal sign-off | does **not** grant relicensing rights: contributed files stay under the license they were contributed with, so S1/S7 relicensing (MPL -> Apache) and any dual-licensing is blocked for them |
| fit | **recommended** because the plan includes moving files between MPL and Apache and a paid hosted orchestrator | fine only if relicensing is never needed |

Recommended: CLA (Apache-style, with explicit relicensing right) via a bot; keep outside code out of `infantry` until it exists
(current `CONTRIBUTING.md` already refuses outside code).

**B. Trademark policy for "Infantry AI" (outline only):** (1) the name and logo are marks of SuperiorSoft LLC; the licences
grant no mark rights (Apache §6, MPL §2.3). (2) Allowed: factual reference ("compatible with Infantry AI", "plugin for Infantry
AI"), unmodified redistribution under the name. (3) Needs permission: a modified build, hosted service or fork using the
name/logo; official-looking `Infantry_*` plugin prefixes by others; implying endorsement. (4) Rename-on-fork rule: forks drop
the name and the emblem. (5) Registration (USPTO/EUIPO class 9/42) before launch is advisable; "Infantry AI" is generic-ish and
should be searched. (6) The `tier: official` / `Infantry_` naming and the signing key are trust marks; document that a module
is "official" only if signed with the Infantry key (release.yml).

**C. Lawyer review checklist:** the Apache SDK -> MPL protocol edge (S13); whole-module MPL for `infantry` and `infantryCore` (S1); MPL Larger Work wording for the desktop installer and Docker image (S5, S8); MPL "Exhibit A"
notice and file-header text; pty4j EPL-1.0 vs MPL combination
(§4); jlayer LGPL replace-or-ship; ML Kit terms in the Android store build; JDK/JBR and Docker base obligations; patent clause
effect of Apache on the hosted orchestrator business; provenance of `incoming-comms.mp3`, icons, emblem; CLA text; trademark
filing; export/crypto notice (TLS, keystore); whether the signed `.ihq` modules need a separate EULA for the marketplace;
privacy statement for the hosted orchestrator.

**D. What the site wording must say:** (1) "The core daemon is MPL-2.0; the plugin API/SDK, the official plugins, examples and
the demo are Apache-2.0" (features still in an MPL module are MPL until they move to a plugin). (2) Name and logo are trademarks,
not licensed; link the policy. (3) Plugins you write are yours: you may license them as you like, Apache is the template
default; community/user plugins carry their own license. (4) Official plugins are removable and replaceable (D3/D8). (5)
Third-party notices link; Android uses Google ML Kit under Google's terms. (6) The hosted orchestrator is a separate paid
service not covered by these licences (see memory `orchestrator-location-transparent-interface`). (7) No warranty; link to
SECURITY.md. (8) "Open source" is correct for the code; do not call the hosted service open source.

**E. Decisions still open:** 1) jlayer: replace or keep; 2) ML Kit vs zxing-only on Android; 3) CLA vs DCO;
4) keep `plugins/core/` reserved or delete; 5) logo carve-out (S9). Settled and applied: the map in §2, S1, S2, S3, S13, S15
and the SPDX header scheme.

## 7. Status

Applied by LICENSE-APPLY-1, official from the public launch (LAUNCH-PREP-1) (2026-11-03): `LICENSES/`, per-directory `LICENSE` files, SPDX headers and
`LicenseMapGuardTest`. The root `LICENSE` carries the full Apache-2.0 text (most of the repo) under a header that points to
this map and the MPL modules' own `LICENSE` files. The CLA bot is enabled (`.github/workflows/cla.yml`). Open follow-ups: notices from an SBOM,
replace jlayer / mp3.

## 8. Guard test (implemented as G2 only; G1, G3-G6 remain proposals)

`LicenseMapGuardTest` now exists and enforces G2 using `scripts/license-paths.txt`.

`LicenseMapGuardTest` in `superiorSoftLibrary/infantry/src/test`, in the style of `DaemonCoreGuardTest`:

* **G1 map complete and current:** a table in code (path -> `MPL-2.0` | `Apache-2.0` | `NONE`) equals the set of every
  top-level and second-level directory plus every Gradle project from `settings.gradle.kts`. A new directory or module fails
  the build until classified; a deleted one must leave the table (same mechanism as the package table).
* **G2 header agrees with the map:** once headers exist, every `.kt/.kts/.java` file under an `MPL` path has
  `SPDX-License-Identifier: MPL-2.0` on line 1-3, every file under `Apache` has `Apache-2.0`; none missing, none mixed.
* **G3 dependency direction:** parse each module's `build.gradle.kts` `project(":...")` edges; fail if an Apache-mapped
  module is depended on by a path-mapped MPL module *other than* the allowed SDK/bundle edges (`infantryDaemonApi`,
  `infantryPlugins`, `infantryWeb`), and fail if any Apache SDK module (`infantryDaemonApi`, `infantryPlugins`, `infantryUiCore`)
  has an edge to an MPL module. Kotlin import scan: Apache SDK sources import no package that only exists in an MPL module.
* **G4 no plugin/demo in core:** no file under an MPL path contains `infantry.plugins.`, `com.acme.`, `Infantry_`, `Demo_` as
  an identifier (comments excluded); `plugins/core` contains no `Infantry_*`/`Demo_*`. Reuse `NoDemoInDaemonCoreGuardTest`.
* **G5 third-party allowlist:** a checked-in `third-party-licenses.json` (from the SBOM) must list every `runtimeClasspath`
  artifact with a license in an allowlist (Apache-2.0, MIT, BSD, OFL, EPL-1.0, LGPL-with-Apache-choice); GPL/AGPL/SSPL/
  Commons Clause or an unknown artifact fails. Version bump of an artifact re-checks.
* **G6 trademark assets:** files under `assets/branding` are not covered by a repo-wide Apache LICENSE (path must be in the
  map as `NONE`).

