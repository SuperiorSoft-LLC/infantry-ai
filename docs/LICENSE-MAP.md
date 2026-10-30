# LICENSE-MAP: proposed directory-by-directory license split (DRAFT for owner sign-off)

Ticket 28145912 (LICENSE-PREP-1). **Docs only.** No LICENSE file, SPDX header or NOTICE is added by this ticket; the root
`LICENSE` is still the "[PLACEHOLDER] LICENSE NOT YET CHOSEN" text. Nothing below takes effect until the owner signs off the
list (section 7). Decision being applied (memory `license-decision-core-mpl-rest-apache`): **core = MPL-2.0, everything else
(SDK/plugin API, plugins, official, examples, demo) = Apache-2.0, split by directory.** Related: D13 (licensing parked; this is
preparation only), `docs/DAEMON-CORE.md` (license-readiness, classification, guards), `docs/CONFIG-FIELDS.md` (no license
content), tickets ad656fe1, aa17b1d2, ec738abc. Not legal advice; section 6 lists the lawyer review.

Key words: **MPL** = Mozilla Public License 2.0 (file-level weak copyleft: changed MPL files stay MPL, a larger work may mix
other licenses). **Apache** = Apache License 2.0. **n/a** = not licensed by us (generated, third-party, or trademark).

## 1. The line, in one paragraph

MPL is file-level, so a module can be MPL while Apache plugins link against it, and Apache code may depend on MPL code. The
reverse is the rule that matters for taint: **MPL core must never contain Apache plugin code or demo-named code** (otherwise a
fork of core would have to keep plugin code under MPL, or core would depend on something outside the core promise). Apache
modules (the SDK/API) must import **nothing** MPL-only, else a plugin author who compiles against the SDK would be pulled onto
MPL terms. Today the physical line is blurry in exactly one place: `superiorSoftLibrary/agentHq` is one Gradle module holding both
core and "stays/plugin-later" feature packages (section 3, S1). Everything else splits cleanly by directory.

## 2. Proposed map: every top-level and second-level directory

| path | proposed | reason | dependency direction check |
|---|---|---|---|
| `/` root files: `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*` | Apache-2.0 (repo default) | build glue and docs; `gradlew*` and `gradle/wrapper` are Apache-2.0 from Gradle already | n/a (no code deps) |
| `LICENSE`, `THIRD-PARTY-NOTICES.md` | replaced / extended (section 7) | `LICENSE` becomes a pointer to this map + the two texts; notices extended (section 5) | n/a |
| `gradle/` (`libs.versions.toml`, `*.gradle.kts` scripts, `wrapper/`) | Apache-2.0 | build logic, not product; `wrapper/` is Gradle's own Apache-2.0 | n/a |
| `.github/` (workflows, issue templates) | Apache-2.0 | CI config; `release.yml` signs releases with the Infantry key (secret, never in repo) | n/a |
| `assets/branding/` | **n/a: trademark + all-rights-reserved** | logo/emblem/icon of the name Infantry AI; Apache-2.0 section 6 grants no trademark rights, but copyright on the image should be kept out of the Apache grant | referenced by the app resources; the copies embedded in `agentHqUi/appResources` and `icons/` need the same carve-out (S9) |
| `docs/` (incl. `docs/qa-e2e-2`, `qa-e2e-3`, `blocks-perf`) | Apache-2.0 | documentation and QA scripts. `docs/qa-e2e-*` and `blocks-perf` hold captured results; check no screenshot embeds a third-party asset | n/a |
| `docker/` | Apache-2.0 | Dockerfile, compose, entrypoint, smoke and demo scripts; **but** `landlock-exec.c` (6 KB, ours) is Apache. The *built image* bundles GPL/LGPL OS packages (base image); see S8 | scripts call the daemon only through its CLI/HTTP, no linking |
| `scripts/` (`agenthq-gate.sh`) | Apache-2.0 | dev tooling | n/a |
| `plugins/` root: `README.md`, `GUIDE.md`, `modules.json` | Apache-2.0 | the plugin author's guide must be under the SDK license | n/a |
| `plugins/core/` | **MPL-2.0 (reserved)** | today only a `README.md`; the tier is reserved for modules that are part of the shell. Keep MPL so a core-tier plugin cannot drift to Apache by accident. If it stays empty the LICENSE can be skipped | must not contain `Infantry_*` or `Demo_*` |
| `plugins/official/` (27 `Infantry_*` UI modules, 3 with a `daemon/` half: Board, Processes, Usage) | Apache-2.0 | D3/D4: official = plugins by the app's own company, removable, not core. Same license as community so a third party can fork or replace any of them | depends on `agenthqUiCore` + `agenthqPlugins` (Apache) and, for `daemon/`, `agentHqDaemonApi` (MPL). Apache -> MPL: allowed |
| `plugins/community/` (`Acme_Echo`, `Acme_Hello`) | Apache-2.0 for the two shipped samples | they are Acme sample modules; **real third-party community modules in this folder keep their own license** (D13: community/user dirs are not forced). Say so in `plugins/README.md` and in the site wording | uses `agenthqUiCore` only |
| `plugins/examples/` (`Acme_DaemonHello`, `Acme_FieldKeys`, `Acme_PolicyHook`, `echo-backend`) | Apache-2.0 | teaching code; people copy it. Apache gives them patent grant and no copyleft | `daemon/` halves compile `compileOnly` against `agentHqDaemonApi` only |
| `plugins/demo/` (`Demo_Banner`, `Demo_Lock`) | Apache-2.0 | D16: demo = ordinary plugins overriding live components; same terms as the rest. **Never in core** (guard exists) | `Demo_Lock/daemon` depends on `project(":superiorSoftLibrary:agentHq")` (the whole daemon, not only the API); allowed (Apache -> MPL) but see S6 |
| `plugins/_template/` | Apache-2.0 | copied by authors as a starting point; must be the most permissive, no header friction | `agenthqUiCore`, `agentHqDaemonApi` only |
| `superiorSoftLibrary/` (the Gradle `:superiorSoftLibrary` parent) | n/a | directory prefix only, no sources | n/a |
| `superiorSoftLibrary/agentHqProtocol` | **MPL-2.0** | wire contract and DTOs; physically core (`DaemonCoreGuardTest`). See S3 on whether the contract itself should be Apache | leaf; imports only its own packages; no plugin, no demo |
| `superiorSoftLibrary/agentHqRunner` | **MPL-2.0** | process runner, PTY, watch shims, tool profiles; physically core | leaf; deps: kotlinx-serialization (Apache), JNA (Apache choice), pty4j (EPL-1.0, S10) |
| `superiorSoftLibrary/agentHq` | **MPL-2.0 for the whole module, interim** | cannot be split by directory until CORE-SPLIT-3/4; see S1 | depends on `agentHqProtocol`, `agentHqRunner`, `agentHqDaemonApi`, `agenthqPlugins`, `agentHqWeb`. No `:plugins:*`, no `daemon-plugin-*` (guarded). Sources name no `agenthq.plugins.*` and no `com.acme.` (guarded) |
| `superiorSoftLibrary/agentHq/headless/` | Apache-2.0 | launcher scripts (`infantry`, `.cmd`, `agenthq-server.*`); trivial, users edit them | n/a |
| `superiorSoftLibrary/agentHq/preflight/` | Apache-2.0 | branch verification build script | n/a |
| `superiorSoftLibrary/agentHqDaemonApi` | **Apache-2.0** | the plugin-facing daemon SDK. The older text in `DAEMON-CORE.md` §4 step 5 says "MPL-2.0" for it: **this map overrides that line** (S2) | imports only `agenthqPlugins` (`...agenthq.plugin.*`) and kotlinx-serialization. Clean |
| `superiorSoftLibrary/agenthqPlugins` | **Apache-2.0** | the UI plugin API + host loading rules (`plugin.api`, `plugin.host`), KMP | imports nothing outside `...agenthq.plugin.*`; no MPL module. Clean |
| `superiorSoftLibrary/agenthqUiCore` | Apache-2.0 (S4) | UI contract + block model + shell, shared by desktop, wasm, Android; the thing every UI plugin compiles against | imports `agenthqPlugins`, `agenthqAppUi`, `ui.protocol`; **its `build.gradle.kts` also depends on all 27 `Infantry_*` projects and the markdown sample** (compile-in list for web/Android, D9). Apache -> Apache, fine. Does not import `agentHq*` MPL modules (verify with the guard, section 8) |
| `superiorSoftLibrary/agenthqAppUi` | Apache-2.0 | shared Compose app scaffolding, depends only on `agenthqUiCore` | clean |
| `superiorSoftLibrary/agentHqUi` | Apache-2.0 (S5) | the desktop app: Compose host, bundles the official plugins, JediTerm console | depends on `agentHq` (MPL) for the in-process daemon and on every `Infantry_*`. Apache -> MPL: allowed. The *distributed desktop artifact* is a combined work, see S5 |
| `superiorSoftLibrary/agentHqUi/{appResources,icons}` | n/a for the logo, Apache for generic icons | the app icon is the trademarked emblem (S9) | n/a |
| `superiorSoftLibrary/agentHqUi/{tab-shots-baseline, tab-shots-baseline-s0}` | Apache-2.0 | reference screenshots for tests | n/a |
| `superiorSoftLibrary/agentHqWeb` | Apache-2.0 | wasmJs client, the bundle the daemon serves at `/`. Includes third-party `NotoSansSymbols2-Regular.ttf` (SIL OFL 1.1) and `js-joda` (BSD-3-Clause) with their license texts (keep them) | depends on `agenthqAppUi`, `agenthqUiCore`. `agentHq` embeds its bundle (MPL jar holding Apache bytes: "larger work", allowed) |
| `superiorSoftLibrary/agentHqAndroid` | Apache-2.0 | Android shell; ML Kit barcode is Google proprietary terms (S10) | depends on `agenthqAppUi`, `agenthqUiCore` only, not on `agentHq` |
| `superiorSoftLibrary/agentHqHeadless` | Apache-2.0 | headless smoke/test harness around the daemon | depends on `agentHq` (test/smoke only) |
| `superiorSoftLibrary/samples/markdownEnginePlugin` | Apache-2.0 | example plugin | `agenthqUiCore` |
| `build/`, `.gradle/`, `.idea/`, `*/build/**`, `.worktrees/`, `local.properties` | n/a | generated or machine-local, not distributed from source | n/a |

Every Gradle module (from `settings.gradle.kts`) and its proposed license:

| Gradle module(s) | license |
|---|---|
| `:superiorSoftLibrary:agentHqProtocol`, `:agentHqRunner`, `:agentHq` | MPL-2.0 |
| `:superiorSoftLibrary:agentHqDaemonApi`, `:agenthqPlugins`, `:agenthqUiCore`, `:agenthqAppUi`, `:agentHqUi`, `:agentHqWeb`, `:agentHqAndroid`, `:agentHqHeadless`, `:samples:markdownEnginePlugin` | Apache-2.0 |
| `:plugins:official:Infantry_*` (27) and `:daemon-plugin-official-*` (Board, Processes, Usage) | Apache-2.0 |
| `:plugins:community:Acme_*`, `:plugins:demo:Demo_Banner|Demo_Lock`, `:daemon-plugin-demo-Demo_Lock`, `:daemon-plugin-examples-*` | Apache-2.0 |
| `:plugins:_template`, `:daemon-plugin-_template` | Apache-2.0 |
| `:superiorSoftLibrary` (parent), build script plugins | n/a |

## 3. Straddlers and recommendations

| id | item | problem | recommendation |
|---|---|---|---|
| S1 | **`superiorSoftLibrary/agentHq`** (927 files): one module, core and features | Packages classified STAYS / PLUGIN-NOW / PLUGIN-LATER in `DAEMON-CORE.md` §3 (`backlog`, `epics`, `stories`, `mvp`, `ticketfiles`, `issues`, `decisions`, `memory`, `prompts`, `images`, `pipeline`, `triggers`, `attention`, `questions`, `commands`, `role`, `agents`, `claude`, `opencode`, `cli`, `client`, `experiments`...) are product features that the decision wants Apache, yet they sit in the MPL module. | **Recommended: license the module MPL-2.0 as a whole (interim, "MPL for the daemon").** MPL's file scope plus weak copyleft makes this the conservative choice: nobody loses an obligation they would have had, and the owner can later *relicense own files* from MPL to Apache after CORE-SPLIT-3/4 moves them (owner holds copyright; needs the CLA, section 6). Do **not** split by package with per-file headers before the module split: the guard `DaemonCoreGuardTest` classes are a package table, not a licence table, and `DaemonMain.kt` (4300 lines) names ~40 packages. The honest public wording: "the daemon is MPL-2.0; features move to Apache-2.0 as they leave core". |
| S2 | `agentHqDaemonApi` | `DAEMON-CORE.md` §4 step 5 says "`agentHqCore` + `agentHqDaemonApi` MPL-2.0". The ticket decision says SDK/API = Apache. | **Apache-2.0**, and edit that line of DAEMON-CORE.md when the owner signs off (not done here; docs-only scope is this file). Reason: daemon plugin authors compile against it; MPL there would make their jar a combined work reviewed by a lawyer for no benefit. API check: imports only `agenthq.plugin.*` (from `agenthqPlugins`) and kotlinx-serialization. |
| S3 | `agentHqProtocol` | It is the wire contract; clients (CLI, web, Android, third-party UIs) implement it. It imports `session.SessionState`, `harness.HarnessCommand` which live in the same module and are MPL. | **MPL-2.0 for now** (it is physically core and `CONTRACT.md` is the data contract). Owner option: make the *contract text* (`CONTRACT.md`, DTOs) Apache so third-party clients carry zero copyleft. MPL only triggers on *modified MPL files*, so using the protocol classes unmodified in an Apache client is already permitted; the option is low value. |
| S4 | `agenthqUiCore` | Apache, but heavy: depends on every official plugin project (compile-in) and bundles `incoming-comms.mp3` (provenance not documented), `jlayer` (LGPL) on JVM. | Apache-2.0. Action: document the mp3's origin or replace it; replace `jlayer` (LGPL-2.1+, dynamic library use, ok but must ship its license and allow relinking; see S10). Also confirm no file here imports `agentHq` or `agentHqRunner` (guard G3). |
| S5 | `agentHqUi` (desktop app) | Apache module that depends on the MPL `agentHq` and embeds it; the packaged installer is a *Larger Work* with both licenses plus all third-party jars. | Apache-2.0 for `agentHqUi` sources. The installer must carry both license texts, the MPL "source form is available at <URL>" notice for the MPL files, and THIRD-PARTY-NOTICES. Lawyer to confirm the Larger Work wording. |
| S6 | `Demo_Lock/daemon` | Apache plugin compiling against the **entire** `:agentHq` module (not only `agentHqDaemonApi`), so it can reach MPL internals. | Keep Apache. Follow-up (not this ticket): make it depend on `agentHqDaemonApi` only, like the examples do, or a lawyer may treat it as modifying/extending core internals. No demo-named code exists in core: grep found "demo" only in comments in `DaemonMain.kt` and `ActionCatalog.kt` ("DEMO-FIX-1"), not in identifiers; `NoDemoInDaemonCoreGuardTest` stays the guard. |
| S7 | `plugins/official/*/daemon` (Board, Processes, Usage) | Apache daemon halves of features whose packages still live in MPL `agentHq` (`backlog`, `epics`...). The plugin half is Apache, the feature core it fronts is MPL. | Accept. This is the expected migration state: when DAEMON-PLUGINS-2/3 move code from `agentHq` into the `daemon/` folder, the moved files become Apache (owner as sole copyright holder can relicense them; contributors' files need the CLA/DCO answer). |
| S8 | `docker/` | Our scripts are Apache, but the image built from `Dockerfile` redistributes a Linux base image and OS packages (GPL/LGPL). | Apache for the sources in `docker/`. For published images: add an image `NOTICE` listing base image + packages, and offer source (distro already hosts it). Lawyer item. |
| S9 | `assets/branding`, `agentHqUi/appResources`, `agentHqUi/icons` | Logo and emblem are trademark and copyright. Putting them under Apache-2.0 would give everyone a copyright licence to copy the logo (trademark is separate, but it muddies the policy). | Exclude by a per-directory `LICENSE`-note: "all rights reserved, trademark of SuperiorSoft LLC". Generic UI icons (`AhqIcons`, `AhqRoleIcons`, `AhqHarnessIcons` in `agenthqUiCore`) are our drawn vectors: Apache. Check each harness icon (Claude, OpenCode, Pi...) is not a third-party logo; those are *their* trademarks. |
| S10 | Third-party bits listed in section 4 | pty4j EPL-1.0, jlayer LGPL, ML Kit proprietary, Noto font OFL. | See section 4. |
| S11 | `plugins/community/` | A folder name that invites third parties; the two shipped `Acme_*` samples are ours. | Apache for our samples; state that other contents keep their authors' license (D13: community/user dirs are not forced MPL). |
| S12 | `plugins/core/` | Empty tier, reserved. | If kept empty, ship no LICENSE file there. If a module ever lands, it is MPL. Guard against `Infantry_*` moving into it by accident (section 8). |

Direction summary: allowed edges are Apache -> Apache, Apache -> MPL, MPL -> MPL. Forbidden: MPL -> Apache plugin / SDK-only
convenience that puts plugin code in core, and anything -> `Demo_*` from core. Observed today: `agentHq` has no `:plugins:*`
or `daemon-plugin-*` dependency and no `agenthq.plugins.`/`com.acme.` source reference (guarded); `agentHq` -> `agentHqWeb`
(Apache bundle) and -> `agenthqPlugins`/`agentHqDaemonApi` (Apache SDKs) are MPL -> Apache **library** edges, which MPL allows
(an MPL work may use Apache libraries; the only question is whether MPL core may *contain* them, and it does not copy them).

## 4. Third-party dependencies and licenses

Source: `libs.versions.toml`, the module build files, and a real `:superiorSoftLibrary:agentHq:dependencies --configuration
runtimeClasspath` run (exit 0). No Gradle lockfiles exist in the repo (none checked in), so versions below are the declared
ones; re-run the report per release. A full license report plugin (e.g. a CycloneDX/`license-report` task) is **not run**; the
transitive licenses below are from the well-known licenses of the listed artifacts and must be verified with a generated
SBOM before release.

| dependency (group:artifact, version) | where | license | verdict |
|---|---|---|---|
| Kotlin stdlib/reflect/test 2.3.10, kotlinx-coroutines 1.10.2, kotlinx-serialization 1.10.0, kotlinx-datetime 0.7.1, kotlinx-io, JetBrains annotations | all | Apache-2.0 | ok, attribution in NOTICE |
| Ktor server/client 3.4.1 (core, cio, websockets, content-negotiation, js) | `agentHq`, `agentHqUi`, `agenthqUiCore`, `agentHqWeb` | Apache-2.0 | ok |
| SQLDelight 2.2.1 (runtime, jdbc-driver, sqlite-driver, primitive-adapters) | `agentHq` | Apache-2.0 | ok |
| `org.xerial:sqlite-jdbc` 3.51.3.0 | `agentHq` | Apache-2.0 (bundles SQLite, public domain) | ok |
| `org.slf4j:slf4j-api`/`slf4j-simple` 2.0.17 | `agentHq` runtime | MIT | ok, attribution |
| `com.typesafe:config`, `org.fusesource.jansi:jansi` | transitive (Ktor server) | Apache-2.0 | ok |
| Compose Multiplatform 1.10.3 (runtime, foundation, material3, resources, desktop) | UI modules | Apache-2.0 | ok. Desktop distribution bundles a JetBrains Runtime/JDK: check that JDK's license (GPLv2 + Classpath Exception for OpenJDK/JBR) is shipped with the installer |
| Skiko / Skia (via Compose desktop & wasm) | UI | Apache-2.0 (Skia BSD-3) | ok |
| `net.java.dev.jna:jna`, `jna-platform` 5.18.1 | `agentHq`, `agentHqRunner` | dual LGPL-2.1+ / Apache-2.0 | ok **only if** used under Apache-2.0; already declared in THIRD-PARTY-NOTICES |
| `org.jetbrains.pty4j:pty4j` 0.13.13 | `agentHq`, `agentHqRunner` | **EPL-1.0** (weak copyleft; bundles winpty MIT, ConPTY/OpenConsole MIT) | compatible as an unmodified library. MPL-2.0 is not on EPL-1.0's "secondary license" list, so Infantry code must not be *derived from* pty4j source; linking is fine. Keep notices, point to pty4j source. Lawyer to confirm |
| `org.jetbrains.jediterm:jediterm-core/ui` 3.72 | `agentHqUi` | dual LGPL-3.0 / Apache-2.0 | ok under Apache-2.0 (declared); contains code derived from jVT220 (Apache-2.0) |
| `com.googlecode.soundlibs:jlayer` 1.0.1.4 | `agenthqUiCore` (JVM, `JvmSoundPlayer` alone) | **LGPL-2.1+** | **incompatible with a "no LGPL" policy if the owner wants one.** Dynamic jar use is allowed but the user must be able to replace it and the LGPL text must ship. Easiest: replace the mp3 decoder (`javax.sound` with a WAV asset, or a permissive decoder). The toml comment already says "swap it there if the licence rules it out" |
| `com.google.zxing:core` 3.5.3 | `agenthqUiCore` (QR) | Apache-2.0 | ok |
| `com.google.mlkit:barcode-scanning` 17.3.0 | Android only | **Google proprietary ML Kit terms** (not an OSS license; redistributes binary models) | not copyleft, but not OSS: Android build is not "pure Apache". Alternative: zxing-only scanner. Flag in the site wording |
| AndroidX activity/camera (CameraX 1.4.2), androidx.test | Android | Apache-2.0 | ok |
| `NotoSansSymbols2-Regular.ttf` (agentHqWeb) | wasm bundle | SIL OFL 1.1 | ok to redistribute; keep `NotoSansSymbols2.LICENSE.txt` next to the font (present); may not sell the font alone |
| `js-joda` (agentHqWeb) | wasm bundle | BSD-3-Clause | ok; license text present |
| `incoming-comms.mp3` (agenthqUiCore) | resource | **unknown provenance** | owner must confirm the author/licence, else replace |
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

Recommended: CLA (Apache-style, with explicit relicensing right) via a bot; keep outside code out of `agentHq` until it exists
(current `CONTRIBUTING.md` already refuses outside code).

**B. Trademark policy for "Infantry AI" (outline only):** (1) the name and logo are marks of SuperiorSoft LLC; the licences
grant no mark rights (Apache §6, MPL §2.3). (2) Allowed: factual reference ("compatible with Infantry AI", "plugin for Infantry
AI"), unmodified redistribution under the name. (3) Needs permission: a modified build, hosted service or fork using the
name/logo; official-looking `Infantry_*` plugin prefixes by others; implying endorsement. (4) Rename-on-fork rule: forks drop
the name and the emblem. (5) Registration (USPTO/EUIPO class 9/42) before launch is advisable; "Infantry AI" is generic-ish and
should be searched. (6) The `tier: official` / `Infantry_` naming and the signing key are trust marks; document that a module
is "official" only if signed with the Infantry key (release.yml).

**C. Lawyer review checklist:** MPL Larger Work wording for the desktop installer and Docker image (S5, S8); MPL "Exhibit A"
notice and file-header text; whether interim MPL for all of `agentHq` (S1) is accepted publicly; pty4j EPL-1.0 vs MPL combination
(§4); jlayer LGPL replace-or-ship; ML Kit terms in the Android store build; JDK/JBR and Docker base obligations; patent clause
effect of Apache on the hosted orchestrator business; provenance of `incoming-comms.mp3`, icons, emblem; CLA text; trademark
filing; export/crypto notice (TLS, keystore); whether the signed `.ihq` modules need a separate EULA for the marketplace;
privacy statement for the hosted orchestrator.

**D. What the site wording must say:** (1) "The core daemon is MPL-2.0; the plugin API/SDK, the official plugins, examples and
the demo are Apache-2.0" (state S1: features still in the daemon are MPL until they move). (2) Name and logo are trademarks,
not licensed; link the policy. (3) Plugins you write are yours: you may license them as you like, Apache is the template
default; community/user plugins carry their own license. (4) Official plugins are removable and replaceable (D3/D8). (5)
Third-party notices link; Android uses Google ML Kit under Google's terms. (6) The hosted orchestrator is a separate paid
service not covered by these licences (see memory `orchestrator-location-transparent-interface`). (7) No warranty; link to
SECURITY.md. (8) "Open source" is correct for the code; do not call the hosted service open source.

**E. Decisions to take (list for the owner):** 1) sign off the table in §2; 2) S1: whole-module MPL now (recommended) vs
waiting for the module split; 3) S2: confirm `agentHqDaemonApi` = Apache (overriding the older DAEMON-CORE line); 4) S3:
keep protocol MPL (recommended) or Apache; 5) jlayer: replace or keep; 6) ML Kit vs zxing-only on Android; 7) CLA vs DCO;
8) keep `plugins/core/` reserved or delete; 9) logo carve-out (S9); 10) SPDX header scheme (one line per file:
`SPDX-License-Identifier: MPL-2.0` or `Apache-2.0`, plus per-directory `LICENSE` files) once signed.

## 7. Sign-off gate

Until the owner approves section 2 in writing: no `LICENSE` change, no per-directory LICENSE files, no SPDX headers, no
NOTICE, no site wording. When approved, the follow-up tickets are: (a) license files + headers by directory, (b) fix
DAEMON-CORE.md §4 step 5 per S2, (c) notices from an SBOM, (d) guard test (section 8), (e) CLA bot, (f) replace jlayer / mp3.

## 8. Guard test proposal (text only, not implemented)

`LicenseMapGuardTest` in `superiorSoftLibrary/agentHq/src/test`, in the style of `DaemonCoreGuardTest`:

* **G1 map complete and current:** a table in code (path -> `MPL-2.0` | `Apache-2.0` | `NONE`) equals the set of every
  top-level and second-level directory plus every Gradle project from `settings.gradle.kts`. A new directory or module fails
  the build until classified; a deleted one must leave the table (same mechanism as the package table).
* **G2 header agrees with the map:** once headers exist, every `.kt/.kts/.java` file under an `MPL` path has
  `SPDX-License-Identifier: MPL-2.0` on line 1-3, every file under `Apache` has `Apache-2.0`; none missing, none mixed.
* **G3 dependency direction:** parse each module's `build.gradle.kts` `project(":...")` edges; fail if an Apache-mapped
  module is depended on by a path-mapped MPL module *other than* the allowed SDK/bundle edges (`agentHqDaemonApi`,
  `agenthqPlugins`, `agentHqWeb`), and fail if any Apache SDK module (`agentHqDaemonApi`, `agenthqPlugins`, `agenthqUiCore`)
  has an edge to an MPL module. Kotlin import scan: Apache SDK sources import no package that only exists in an MPL module.
* **G4 no plugin/demo in core:** no file under an MPL path contains `agenthq.plugins.`, `com.acme.`, `Infantry_`, `Demo_` as
  an identifier (comments excluded); `plugins/core` contains no `Infantry_*`/`Demo_*`. Reuse `NoDemoInDaemonCoreGuardTest`.
* **G5 third-party allowlist:** a checked-in `third-party-licenses.json` (from the SBOM) must list every `runtimeClasspath`
  artifact with a license in an allowlist (Apache-2.0, MIT, BSD, OFL, EPL-1.0, LGPL-with-Apache-choice); GPL/AGPL/SSPL/
  Commons Clause or an unknown artifact fails. Version bump of an artifact re-checks.
* **G6 trademark assets:** files under `assets/branding` are not covered by a repo-wide Apache LICENSE (path must be in the
  map as `NONE`).

Not run for this ticket: no tests, no builds beyond the one read-only dependency report. No license/SPDX/NOTICE files were
added or changed.
