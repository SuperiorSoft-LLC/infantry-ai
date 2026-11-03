# Licensing: the foundation and everything on top

> **DRAFT. Not legal advice. Not yet in effect.** No license is applied to this repository today: the root `LICENSE`
> is still a placeholder and all rights stay with SuperiorSoft LLC. This page explains the split the owner is
> deciding on. The source of truth is `docs/LICENSE-MAP.md`; where this page and the map differ,
> the map wins.

## The short version

<!-- shared:license_line -->The core (the daemon) is the foundation: it is licensed MPL-2.0 to protect its integrity, so apps and plugins can rely on a stable base that does not break, with backward compatibility as the promise. Everything built on top (the SDKs, the UI core, the apps and every plugin) is free under Apache-2.0: build, fork, sell and extend it without copyleft.<!-- /shared:license_line -->

**The core is the foundation. It is MPL-2.0 to protect its integrity.** Apps and plugins stand on the core, so the
core has to stay a stable base that does not break under them. MPL-2.0 keeps every change to a core file open, so a
fork of the core cannot quietly break the base everyone depends on, or close it. Backward compatibility is the promise
the core makes in return (see [The compatibility promise](#the-compatibility-promise-as-implemented-today)).

**Everything built on top is free under Apache-2.0**: the SDKs, the UI core, the apps, every official plugin, the
examples and the demo. Anyone may build on it, fork it, sell it and extend it, with no copyleft.

## 1. Why the split

| | Core (MPL-2.0) | Everything else (Apache-2.0) |
|---|---|---|
| What it is | The daemon: process runner, wire protocol, database, mail, worktrees, plugin host, CLI | The plugin APIs (SDKs), UI core, desktop/Android/web apps, all plugins, examples, demo |
| What the license is for | Integrity: changes to core files stay open, so the base stays one shared, trustworthy thing | Freedom: no strings, a patent grant, easy to copy, sell and extend |
| Who it protects | Everyone who builds on the foundation | Everyone who builds the apps and plugins |

The rule that makes this work (from the map, section 1): **the MPL core never contains plugin or demo code**, and the
**Apache SDKs import nothing that is MPL-only**, so a plugin author who compiles against an SDK is never pulled onto
MPL terms. MPL is per file, so Apache code may use MPL code and the other way round as separate files.

## 2. What the two licenses mean in practice

Plain words, not a legal reading.

| You are... | Under MPL-2.0 (core files) | Under Apache-2.0 (everything else) |
|---|---|---|
| **A user** running the app | Free to use. No obligations. | Free to use. No obligations. |
| **A plugin author** | Building against the SDK does not touch MPL. Your plugin is yours: license it as you like (the template is Apache-2.0). | Same. You may sell it, keep it closed, or open it. |
| **Someone forking the core** | If you change a core file and distribute it, you must publish your version of that file under MPL-2.0. Your own new files may be under any license. Keep the notices. | Fork freely. Keep the copyright and license notices and mark your changes. No duty to publish. |
| **A company hosting it** | You may host the unmodified or modified core as a service. Distribution (giving the code to others) triggers the file-level duty above; hosting alone does not. | You may host, resell and build a paid product on it. The Apache patent clause gives users a patent license from contributors. |

Both licenses grant **no trademark rights** (see section 6).

Not covered by either license: the hosted orchestrator, if it ships, is a separate paid service (map section 6D).

## 3. The line, module by module

From `docs/LICENSE-MAP.md` section 2. "Interim" means the map flags it for the owner to decide.

| Module / path | License | Why |
|---|---|---|
| `superiorSoftLibrary/infantryProtocol` | **MPL-2.0** | The wire contract between daemon and clients. Open question S13: the Apache daemon SDK depends on it, so the owner may prefer Apache for the contract. |
| `superiorSoftLibrary/infantryRunner` | **MPL-2.0** | Process runner, PTY, watch shims. Core. |
| `superiorSoftLibrary/infantryCore` | **MPL-2.0** | The daemon core (agents, db, harness registry, mailbox, policy, session, settings, worktree...). |
| `superiorSoftLibrary/infantry` | **MPL-2.0** (interim) | Daemon main, plugin host, CLI, HTTP shell. Still holds some feature packages (map S1) that may move into plugins and then become Apache. |
| `superiorSoftLibrary/infantryBoard` | **MPL-2.0** (interim) | The ticket board, a product feature fronted by the Board plugin (S15). |
| `superiorSoftLibrary/infantryDaemonApi` | Apache-2.0 | SDK for daemon plugins. Overrides the older "MPL" line in `DAEMON-CORE.md` (S2). |
| `superiorSoftLibrary/infantryHarnessApi` | Apache-2.0 | SDK for harness plugins. |
| `superiorSoftLibrary/infantryPlugins` | Apache-2.0 | UI plugin API and host loading rules. |
| `superiorSoftLibrary/infantryUiCore`, `infantryAppUi` | Apache-2.0 | UI contract, block model, shell. |
| `superiorSoftLibrary/infantryUi`, `infantryWeb`, `infantryAndroid`, `infantryHeadless` | Apache-2.0 | The desktop, web and Android apps and the headless test harness. The installer is a combined work with the MPL daemon (S5). |
| `superiorSoftLibrary/samples/*` | Apache-2.0 | Example plugin. |
| `plugins/official/` | Apache-2.0 | Official plugins: removable and replaceable, so a third party can fork or replace any of them. |
| `plugins/community/`, `plugins/examples/`, `plugins/demo/`, `plugins/_template/` | Apache-2.0 | Our samples and demo. Other people's modules in `community/` keep their own license. |
| `plugins/core/` | MPL-2.0 (reserved) | Empty tier. If a module ever lands there it is MPL. |
| `docs/`, `docker/`, `scripts/`, `gradle/`, `.github/`, root build files | Apache-2.0 | Docs and tooling. |
| `assets/branding/`, app icon and emblem copies | **Neither** | Logo and emblem: trademark, all rights reserved (S9). |
| `build/`, `.gradle/`, `.worktrees/`, generated files | n/a | Not distributed from source. <!-- docs-freshness: ignore --> |

Third-party dependencies keep their own licenses (map section 4). Items flagged there: `pty4j` (EPL-1.0), `jlayer`
(LGPL), Google ML Kit on Android (proprietary terms), a sound file of unknown origin, the JDK and Docker base image
in packaged artifacts.

## 4. What a plugin author needs to know

- **Compile against the SDK, stay out of MPL.** UI plugins use `infantryPlugins` / `infantryUiCore`; daemon plugins use
  `infantryDaemonApi`; harness plugins use `infantryHarnessApi`. All three are Apache-2.0. Your plugin is not a
  derivative of MPL code.
- **A daemon plugin never sees core classes.** Its class loader hides every daemon class and exposes only the declared
  `DaemonApi` surface (`superiorSoftLibrary/infantryDaemonApi/.../DaemonApi.kt`).
- **License your plugin as you like.** The templates and examples (`plugins/_template`, `plugins/examples`) are
  Apache-2.0 so you can copy them without friction.
- **One caveat today (S13).** `infantryDaemonApi` has an `api(...)` dependency on the MPL `infantryProtocol`
  (two trigger view types). It puts MPL classes on a daemon plugin's compile classpath. Whether that matters is on the
  lawyer list; the map proposes moving those two types or relicensing the protocol.
- **"Official" is a trust mark.** A module is official only if it is signed with the Infantry key. The name and the
  `Infantry_` prefix are not for others to use (section 6).

## The compatibility promise, as implemented today

Only mechanisms that exist in the code are listed. The promise is "what you built against keeps loading", and each
surface has a version that the code checks.

| Surface | Mechanism | Where |
|---|---|---|
| UI plugin API | `module.json` `apiVersion` (an integer, currently `2`). `PLUGIN_API_VERSION` is the host's; a module with a different `apiVersion` is refused with "needs plugin API N, this build has M". Additive changes go under `PLUGIN_API_REVISION` and do not break older modules. | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/ModuleManifest.kt`, `.../plugin/host/ModuleResolver.kt` |
| Daemon plugin API | `DaemonApi.VERSION` (semver, currently `1.35.0`), with the board contract `BOARD_API_VERSION` (`1.12.0`) inside it. A module declaring `daemon.apiAtLeast` newer than the daemon is refused ("needs daemon API X, this daemon has Y"). Adding a field with a default is a minor bump. | `superiorSoftLibrary/infantryDaemonApi/.../DaemonApi.kt`, `.../api/board/BoardApi.kt`; check in `superiorSoftLibrary/infantry/.../daemon/plugins/DaemonPluginHost.kt` |
| Harness API | `HARNESS_API_VERSION` (semver, currently `2.12.0`). A harness plugin states the version it was built against (`contractVersion`, no default on purpose) and the registry checks it. | `superiorSoftLibrary/infantryHarnessApi/.../harness/api/HarnessApi.kt`; check in `superiorSoftLibrary/infantryCore/.../harness/HarnessRegistry.kt` |
| Wire protocol | `EventVocabulary.VERSION` (semver, currently `1.149.0`). Additive frames and fields get a minor bump, breaking changes a major bump. Payloads decode with `ignoreUnknownKeys`, so an older client ignores fields it does not know. | `superiorSoftLibrary/infantryProtocol/.../protocol/EventVocabulary.kt` |
| Core stays generic | Guard tests fail the build when feature code leaks into core. `DaemonCoreGuardTest` (core names no plugin package and depends on no plugin module), `HarnessCoreGuardTest` (core carries no concrete harness), `NoDemoInDaemonCoreGuardTest` (no demo code in core), `CoreHasNoScriptingGuardTest` (no Lua engine in core), `BuiltinToolsListGuardTest` (a new built-in tool in core fails until it is a plugin), `HarnessApiImportGuardTest`, `BoardApiImportGuardTest`, `BoardImportGuardTest`, `OrchestratorBoundaryGuardTest`, `NoPresetInCoreGuardTest`, `StackNeutralGuardTest`. | `superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/` (the `daemon/`, `harness/`, `orchestration/`, `policy/`, `runner/` folders and `CoreHasNoScriptingGuardTest.kt`) |

## Open questions

Gaps in the promise, stated plainly:

- **No written deprecation policy.** Nothing says how long an old `apiVersion` or API version keeps loading, or how
  much notice a breaking change gets.
- **The UI plugin API is exact-match.** A module with a different `apiVersion` is refused; there is no range today.
- **Protocol contract text.** The code comments point to `CONTRACT.md`, which is not in the repository paths checked
  for this page; the version number in code is the contract's only recorded form here.
- **No guard test for the license map yet** (map section 8 proposes `LicenseMapGuardTest`; it is text only). <!-- docs-freshness: ignore -->
- **Where the protocol belongs** (MPL or Apache) is undecided (S3, S13).

## 5. Trademark: the name and the logo

"Infantry AI", the logo and the emblem are trademarks of SuperiorSoft LLC. **They are not under MPL-2.0 or Apache-2.0**,
and neither license grants any right to them (Apache section 6, MPL section 2.3). The map's outline: factual references
("a plugin for Infantry AI") are fine; a modified build, a hosted service or a fork using the name or logo needs
permission, and forks rename. No written trademark policy exists yet.

## 6. Contributions need a CLA

The map recommends a **CLA** (Apache-style, with an explicit right to relicense) over a DCO, because a file may later
move between MPL and Apache (for example when a feature leaves the daemon and becomes a plugin). Without a CLA, an
outside contributor's file stays under the license it came in with and cannot be moved. Until a CLA exists,
`CONTRIBUTING.md` refuses outside code.

## 7. Open items before this takes effect

From the map (sections 5, 6, 7):

1. **Owner sign-off** of the module table in section 3 above. Until then no license file, SPDX header, per-directory
   `LICENSE` or `NOTICE` is added.
2. **Lawyer review**: the Apache SDK to MPL protocol edge (S13); the interim MPL for `infantry`, `infantryCore`,
   `infantryBoard`; the wording for the desktop installer and Docker image as combined works; the MPL header text;
   `pty4j` (EPL-1.0) against MPL; `jlayer`, ML Kit, JDK and Docker base obligations; the effect of the Apache patent
   clause on a hosted orchestrator.
3. **CLA text** and a bot to enforce it.
4. **Notices**: extend `THIRD-PARTY-NOTICES.md` (sqlite-jdbc, slf4j, Noto font, js-joda, zxing, jlayer, ML Kit,
   Compose/Skiko, JDK), generated from an SBOM.
5. **Replace or document** `jlayer` and the sound file of unknown origin.
6. **Trademark policy** and a name search/registration.
7. **Site wording** (map 6D): the core is MPL-2.0; the SDKs, official plugins, examples and demo are Apache-2.0;
   features still in the daemon are MPL until they move; the name and logo are trademarks; the hosted service is not
   open source.
