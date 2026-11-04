# PLUGIN-REPO-1: plugins in their own repositories (design only, ticket 9710c637)

Owner question (2026-11-03): "With Kotlin, how exactly can you build a plugin without it becoming part of the project? How can
we create a separate repo for individual plugins and add it, so we can offer a repo for plugins to install?"

Short answer: a plugin is already a separate artifact at run time (its jar holds only its own classes; the host gives it the API
through a filtered class loader). What ties it to this monorepo is only the **build**: `compileOnly(project(":superiorSoftLibrary:infantryUiCore"))`
in `plugins/_template/build.gradle.kts` and `apply(from = rootProject.file("gradle/ihq.gradle.kts"))`. Publish the SDK as Maven
artifacts and the packaging script as a Gradle plugin, and a plugin builds in any repository. The install side (catalogs, sha256
pins, grants, update, rollback) already exists as PLUGIN-MANAGER-1 (`docs/PLUGIN-MANAGER.md`); the registry is a catalog of
that schema. Android and wasm cannot load code at run time (D6, D9); they get plugin UI as data.

Related tickets: PLUGIN-MANAGER-0 (269f0ec2, design), PLUGIN-MANAGER-1 (de8dd3c2, built), SIGNING-KEY (7ed8f8b7, backlog),
S5/S6/S7 (`docs/IHQ-PACKAGE.md`), HARNESS-PLUGIN-1 (c0ba8e4c), BLOCKS-LAYOUT (fa41d42d), NS3 compositions (`docs/NORTH-STAR-DESIGN.md`),
LICENSE-APPLY-1 (67e0d704, hold).

## 0. What exists (read from the code, 2026-11-03)

| piece | where | state |
|---|---|---|
| UI jar loading at run time (desktop) | `infantryUiCore/src/jvmMain/.../ui/plugin/DesktopPlugins.kt`, `PluginClassLoader.kt` | built; "a trust label, not a sandbox" |
| `.ihq` drop-in (desktop) | `DesktopDropIn.kt` -> `IhqInstaller` (`infantryPlugins/src/jvmMain/.../plugin/host/IhqInstaller.kt`) | built; signed-zip checks, atomic rename |
| `.ihq` build | `gradle/ihq.gradle.kts` (applied per module) | packs only the UI jar; daemon/harness halves not packed (gap G5) |
| daemon halves | `infantry/.../daemon/plugins/DaemonPluginHost.kt` under `DaemonApiClassLoader` | loaded at daemon start only |
| daemon class-path filter | `DaemonApiClassLoader.EXPOSED_PREFIXES` | `kotlin.`, `kotlinx.serialization.`, `kotlinx.coroutines.`, `...daemon.api.`, `...plugin.api.` |
| harness halves | `infantryCore/.../harness/JarHarnessPluginSource.kt` under `HarnessApiClassLoader` | loaded at start |
| catalog schema 1 | `infantry/.../daemon/plugins/manager/PluginCatalog.kt` | `Catalog`, `CatalogEntry`, `CatalogVersion` (url, sha256, size, grants, api ranges, signer) |
| manager + CLI | `PluginManager.kt`, `cli/PluginCli.kt` | sources, search, inspect, install, enable/disable, grants, update, rollback, remove, `catalog validate` |
| MCP tools for plugins | none | backed out: `BuiltinToolsListGuardTest`; needs a DaemonApi seam |
| Settings > Plugins screen | none (PM-8) | the drop-in is the only UI path |
| contract versions | `PLUGIN_API_VERSION = 2` (`ModuleManifest.kt`), `DaemonApi.VERSION = "1.35.0"`, `HARNESS_API_VERSION = "2.15.0"`, `BOARD_API_VERSION` | constants in code |
| publishing | no `maven-publish` on any SDK module | none |
| toolchain the host provides | Kotlin 2.3.10, Compose Multiplatform 1.10.3, JDK 21 (`gradle/libs.versions.toml`) | part of the UI contract in practice |

Measured fact that shapes section 1: the official plugins import from `infantryUiCore` packages `ui.model` (242 imports),
`ui.plugin` (216), `ui.shell` (133), `ui` (112), `ui.protocol` (90), `ui.block.layout` (65), `ui.net` (42) and more. The UI
plugin surface is therefore **the whole of infantryUiCore today** (430 files), not a small API module. The daemon and harness
surfaces are small and already fenced by class loaders.

## 1. Published SDK

### 1.1 What is published

| artifact (group `com.superiorsoftllc.infantry`) | from module | targets | version source | stability |
|---|---|---|---|---|
| `infantry-plugin-api` | `infantryPlugins` | jvm, android, wasmJs | `2.<minor>.<patch>`, major = `PLUGIN_API_VERSION` | stable contract |
| `infantry-protocol` | `infantryProtocol` | as the module | app version | stable (api dependency of the two below) |
| `infantry-daemon-api` | `infantryDaemonApi` | jvm | `DaemonApi.VERSION` (1.35.0) | stable contract |
| `infantry-harness-api` | `infantryHarnessApi` | jvm | `HARNESS_API_VERSION` (2.15.0) | stable contract |
| `infantry-ui-core` | `infantryUiCore` | jvm only (desktop plugins) | app version | **unstable**: published as is, marked so in its POM description |
| `infantry-sdk-bom` | new (java-platform) | - | app version | pins one compatible set |
| `infantry-sdk-catalog` | new (`version-catalog` plugin) | - | app version | Kotlin, Compose, serialization versions the host runs, plus the SDK artifacts |
| Gradle plugin `com.superiorsoftllc.infantry.plugin` | new (`build-logic`, section 2.1) | - | app version | build tool |

Rules:
* **The published version is read from the constant**, never typed in a build file, so the artifact cannot claim a contract
  version the code does not have. A plugin's `module.json` ranges (`apiVersion`, `daemon.apiAtLeast`, catalog `api.*`) use the
  same numbers the artifact carries.
* **One BOM per app release** ties the set; a plugin author imports only the BOM (or the version catalog) and gets matching
  versions. The catalog carries the Kotlin and Compose versions because a UI jar compiled against another Compose runtime can
  fail at load (the host provides Compose; the plugin compiles `compileOnly` against it).
* **Licence in the POM comes from a per-module property** (`infantry.license=MPL-2.0|Apache-2.0`), so the design works whichever
  way LICENSE-APPLY-1 lands (`license-infantryplugins-apache` note: the plugin API may become MPL). No licence string in a
  publish script.

### 1.2 What is NOT published

`infantry` (daemon, CLI, MCP), `infantryCore`, `infantryRunner`, `infantryBoard`, `infantryAppUi`, `infantryUi`, `infantryWeb`,
`infantryAndroid`, `infantryHeadless`, every `plugins/**` module, `samples/**`. A daemon plugin that needs one of them is
missing a seam in `infantryDaemonApi`; the answer is a seam, not a published internal. A guard (SDK-SURFACE-1) checks that the
daemon class-loader prefixes and the published daemon artifacts agree: a package exposed at run time but not published (or the
reverse) fails the build.

### 1.3 Keeping the surface honest

* Kotlin binary-compatibility-validator (`apiDump`/`apiCheck`) on the four stable artifacts: the `.api` files are in the repo,
  so a breaking change is a visible diff in review and a major bump.
* `infantry-ui-core` gets an `.api` dump too, but only as a record (no check) until it is carved. The carve (an
  `infantryUiApi` module holding `UiPlugin`, `UiRegistrar`, the tab/panel/slot types, the theme tokens and the block-type
  contract, with infantryUiCore depending on it) is a later, larger ticket; it is **expensive to reverse** once community jars
  compile against it, which is exactly why it is not done blind now. The measured import list above is its starting input.

### 1.4 Where and how

* **Now: GitHub Packages**, `https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai`. Trade-off recorded: GitHub Packages
  Maven needs a token to **read**, even for a public repository, so every community author must create a PAT with
  `read:packages`. Acceptable for the first official/friendly plugins; not for an open ecosystem.
* **Later: Maven Central** (namespace `com.superiorsoftllc`, Sonatype Central Portal, GPG-signed artifacts). Same coordinates,
  so a plugin build only changes its repository line. Do this before the registry is opened to the public.
* **Release workflow** `.github/workflows/sdk-publish.yml`: triggered by a tag `sdk-v*` or by hand; steps: JDK 21, `apiCheck`,
  compile, `publishToMavenLocal` into `build/m2` as a dry run, then `publish` in a job bound to a GitHub **environment
  `sdk-release` with required reviewers** (the owner). Nothing reaches the registry without the owner's click in GitHub. The
  first ticket prepares this and runs only the dry run.

## 2. Plugin template repository

### 2.1 The Gradle plugin (the piece that makes a repo independent)

`gradle/ihq.gradle.kts` becomes a real Gradle plugin `com.superiorsoftllc.infantry.plugin` in an included build
`build-logic/` (monorepo) that is also published (section 1.1). It:

1. Reads `module.json` (validates: name is a folder name, `entry`, `apiVersion` within the SDK's, halves consistent).
2. Adds the SDK as `compileOnly` per half: root/UI half -> `infantry-ui-core` + Compose from the catalog; `daemon/` ->
   `infantry-daemon-api`; `harness/` -> `infantry-harness-api`; tests get the same as `testImplementation`.
   In the monorepo it substitutes the local projects (Gradle dependency substitution), so official plugins and community repos
   build by the **same plugin and the same rules**.
3. Task `ihq`: packs `module.json` + `lib/<ui>.jar` + `lib/<daemon>.jar` + `lib/<harness>.jar` (each half optional), rewriting
   the `jar`, `daemon.jar`, `harness.jar` paths to `lib/`. This closes G5. Optional jarsigner signing as today (key never in
   the repo).
4. Task `catalogEntry`: writes `build/ihq/catalog-entry.json` (one `CatalogVersion`: version, api ranges from module.json,
   size, sha256, the release URL if `-Pihq.url` is given). Grants are left to `infantry plugin catalog validate`, which
   recomputes them with `RequestedGrants` (the daemon side owns that logic; the Gradle plugin must not copy it).

### 2.2 The template repo `SuperiorSoft-LLC/infantry-plugin-template` (a GitHub template)

```
settings.gradle.kts      pluginManagement + dependencyResolutionManagement: GitHub Packages (later Central), versionCatalogs { infantry from "com.superiorsoftllc.infantry:infantry-sdk-catalog:<v>" }
build.gradle.kts         plugins { id("com.superiorsoftllc.infantry.plugin") }  (UI half; delete src/ for a daemon-only plugin)
module.json              name, version, apiVersion, license (SPDX), entry, daemon {...}, harness {...}
src/main/kotlin/...      UI half (UiPlugin)
src/test/kotlin/...      UI tests
daemon/                  optional daemon half (DaemonPlugin) + tests; included only if the folder exists
harness/                 optional harness half (HarnessPlugin) + tests; included only if the folder exists
.github/workflows/ci.yml       PR/push: build, test, ihq
.github/workflows/release.yml  tag v*: build, test, ihq, catalogEntry -Pihq.url=<release asset url>; gh release create with <Name>.ihq and catalog-entry.json; optional signing from secrets IHQ_KEYSTORE_B64/IHQ_STOREPASS/IHQ_ALIAS
README.md                rename steps, how to install locally (`infantry plugin install build/ihq/<Name>.ihq --grant-all-requested`), how to submit to the registry
```

The template's source of truth lives in the monorepo at `templates/plugin-repo/` and is copied to the template repository by a
workflow (or by hand on the owner's OK), so it compiles in this repo's CI against `build/m2` and can never rot. A tag in the
template must match `module.json`'s `version` (the release job fails otherwise).

## 3. Registry: the `infantry-plugins` index repository

The registry **is a schema-1 catalog** (`PluginCatalog.kt`), so the manager, `infantry plugin search/install/update` and
`catalog validate` need no second format.

### 3.1 Layout

> **Built (REGISTRY-1b):** `registry/` holds the scaffold: `official/` (README only), `community/Example_Starter.json`, `schema/catalog-entry.schema.json`, `CODEOWNERS`, `CONTRIBUTING.md`, and `.github/workflows/validate.yml` + `publish.yml`. It is the content of the future `infantry-plugins` repo; nothing is pushed. The example has `tags:["example"]` and `versions: []`, so `catalog build` passes and `--assets` has nothing to download (CONTRIBUTING states the skip).

```
official/<Id>.json       one CatalogEntry per file (class official); CODEOWNERS: maintainers only
community/<Id>.json      one CatalogEntry per file (class community); open to PRs
schema/catalog-entry.schema.json
.github/workflows/validate.yml   on PR: schema, id = file name = module name, unique across both folders, every new version: download, sha256 + size match, grants recomputed equal the declared, api ranges parse, licence present (warning if not), url host is the entry's repo releases
.github/workflows/publish.yml    on main: `infantry plugin catalog build official/ -> official.json`, same for community, publish to GitHub Pages
CONTRIBUTING.md                   how to submit / update / yank
```

Field mapping to the owner's list: name = `id` (the module name), repo = **new additive field `repo`**, version + download URL +
sha256 = a `CatalogVersion`, apiVersion range = `api.plugin` / `api.daemon` / `api.harness`, license = `license` (SPDX), tier =
the catalog's `class` (`official` / `community`). Additive schema-1 fields: `repo`, and `yanked` + `yankedReason` on a version
(the manager never offers a yanked version for install or update; installed copies keep running and are flagged). Both are
optional with defaults, so older daemons that ignore unknown keys keep working; this must be checked in REGISTRY-1a
(`ignoreUnknownKeys` on the catalog `Json`).

### 3.2 Submissions and updates

* New plugin: a PR adding `community/<Id>.json` with the first version (the template's `catalog-entry.json` plus `id`,
  `description`, `tags`, `license`, `repo`). The validate workflow does every check a reviewer would otherwise do by hand;
  a human still merges (community is reviewed, not trusted: the class label never raises a tier, PLUGIN-MANAGER section 2).
* New version: a PR appending to `versions`. Later convenience: the template's release job opens that PR itself with a token
  the author supplies (not in v1).
* Official plugins: entries under `official/`, merged by maintainers only; their `.ihq` must be signed by the Infantry key
  (the installer already refuses `NOT_SIGNED_OFFICIAL`). Blocked on SIGNING-KEY (7ed8f8b7) for real releases.
* Catalog signing (PLUGIN-MANAGER decision 8.3): v1 relies on HTTPS + per-version sha256; a detached signature over the
  generated catalogs with an index key is a later slice.

### 3.3 Default sources

A fresh install's `sources.json` gets two entries: `infantry-official` and `infantry-community`, pointing at the published
`official.json` / `community.json`. **Expensive to reverse** (PLUGIN-MANAGER decision 8.1): the URL is copied into every
install. Recommendation: serve them under a URL we control independently of the hosting (a custom domain on GitHub Pages),
so the index can move without breaking installs; until the owner picks it, the default is data (a setting), not code.

## 4. In-app installer

Most of it is built: `infantry plugin search | show | install <id|file|url> --grant ... | update | rollback | remove | sources`.
What is missing, and the shape of each:

1. **Agent/script access (MCP).** A `DaemonApi` read seam over the manager (`plugins.list/search/show/status`) and a request call
   that raises an owner `ask` with the inspection (grants, licence, class, signer). An official `Infantry_Plugins` daemon half
   registers `infantry_plugin_list|search|show|status|request` as daemon-plugin tools (the `BuiltinToolsListGuardTest` route).
   Scripts reach them through `ih.*` (D21). Agents never install or grant on their own (decision 8.6 recommendation); the owner's
   CLI keeps `install`.
2. **Desktop "Plugins" screen** (PM-8): Settings > Plugins with Installed / Browse / Sources; Browse reads the sources; Install
   shows the consent dialog: class badge, signer (or "unsigned"), licence (or "licence not stated"), requested grants with ticks,
   and this warning in plain text: **"This plugin runs inside Infantry AI with the same access to your computer as the app
   itself. It is not sandboxed. Install only plugins you trust."** Update shows "asks for new permissions" when grants rise;
   rollback and remove are one click each. The drop-in goes through the same inspect/install.
3. **Loading the daemon half without a restart.** Today every change says "takes effect at the next daemon start". A
   `DaemonPluginHost` load-one / unload-one for a module (its own class loader already makes this possible) removes that.
   BOARD-DOWN-2 (6f94b170, backlog: re-enable a failed plugin without a restart) is the same mechanism; build it once.
   **Built (PLUGIN-HOTLOAD-1):** `DaemonPluginHost.load / unload / reload`, called by the manager; see PLUGIN-MANAGER "Live daemon halves".
   `load` of a failed plugin takes the failed copy out and starts it afresh, which is the BOARD-DOWN-2 re-enable.
4. **Staged apply (G7)** for a module whose jars are open: write `_pending.json`, apply at the next start. Needed for update on
   Windows; independent of the rest.
5. **Remote clients** (web, Android) manage plugins through frames (`plugin_*`, PLUGIN-MANAGER section 4.3), owner-only action
   `plugins.manage`. Later; the desktop and CLI work on the local root first.

Trust model: signature = identity (pinned certificate), class = where it was listed, tier = what it may do, grants = what the
owner allowed. Code is in-process and not sandboxed on desktop; the warning says so every time. Signatures for community authors
(their own keys, user-trusted under `trusted-keys/custom/`) already exist; publisher verification comes later.

## 5. Android and web

Constraint: Android and wasm compile UI modules in (D6, D9; generated module list in `infantryAppUi`). Google Play forbids an app
from downloading executable code outside the store (Device and Network Abuse policy), and Kotlin/wasm has no stable dynamic
linking. Daemon halves run on the daemon, so they already serve every client.

| option | what | cost | reach |
|---|---|---|---|
| (a) declarative UI | a plugin ships UI as data: a block tree (the BLOCKS-LAYOUT model; NS3 compositions are already code-free `.ihq` with `layout/*.json` built by `PageLayout.instantiate` + `BlockTypes` in commonMain) whose leaves are a fixed widget set compiled into every client; data and actions round-trip to the plugin's daemon half | medium-high: a widget vocabulary (text, list, table, form from config field types, button -> action frame, chart, badge), a binding from a node to a daemon route/frame, a `plugin_view` frame; 5-7 tickets | every client, store-safe, one plugin UI for desktop too |
| (b) curated compile-in | chosen plugins are compiled into the mobile/web builds (today's mechanism) | low per plugin, but every change needs an app release; only for official plugins | official set only |
| (c) daemon-only on mobile | a plugin's daemon half works; mobile shows no custom UI, only what generic surfaces render (tools, settings from `config` fields, notifications, board, chat) | zero (works today) | every client, no custom screens |

**Recommendation: (c) + (b) now, (a) as the next build.** Nothing to build to get (c) and (b): they are today's behaviour, and the
registry marks versions with `platforms` so Browse on mobile lists only daemon-capable plugins. (a) is the only route by which a
community plugin gets a screen on a phone or in the browser, and it reuses the block model rather than inventing one, so it earns
its cost; but its widget vocabulary and binding contract are a public contract (**expensive to reverse**: every declarative
plugin depends on the widget names and fields), so it gets its own design ticket first (DECL-UI-0) instead of build tickets here.
Desktop keeps Compose jars as the "full power" path; a plugin that wants to be everywhere writes a declarative view.

## 6. Migration

* **`plugins/official/*` stays in the monorepo** as the official set. They change in lockstep with the core APIs (one commit
  changes a seam and its users); split repos would turn every API change into N cross-repo version bumps. They are built by
  the same Gradle plugin as an external repo (section 2.1), so moving one out later is a `git subtree split` plus a repository
  line, nothing else.
* Each official plugin is also released as a signed `.ihq` and listed in `official/` of the registry, so an official plugin can
  ship a fix without an app release (desktop) while the bundled copy covers fresh installs and mobile.
* Move a plugin out only when it has a release cadence of its own and builds against the **published** SDK only (no
  substitution needed). The showcase plugins (News, RPG) are the natural first candidates: they exist to prove decoupling.
* **Scanning and installed plugins coexist** as they do now: `settings.gradle.kts` scans `plugins/**` for the build; at run time
  `PluginFolders.scan` reads the plugins root tier folders, then the bundled root's `official/`. Today "a user copy of the same
  name wins". Change to: **the higher version wins**, equal versions prefer the installed copy, and the loser gets a status
  line (`shadowed by <path> <version>`). Otherwise an old installed copy silently hides a newer bundled one after an app update.
* `plugins/community/` in the monorepo stays a local development folder; it is not the registry.

## 7. Decisions that are expensive to reverse (owner to confirm)

1. Maven coordinates (`com.superiorsoftllc.infantry:infantry-*`) and the Gradle plugin id: every external build names them.
2. Versioning artifacts by their contract constants (vs one release train): authors read compatibility directly from the
   version; switching later confuses every range already written.
3. Publishing `infantry-ui-core` whole as "unstable" before the carve: external jars will compile against its internals; the
   carve then breaks them (signalled by the unstable label and a major bump).
4. The registry's default URL (section 3.3).
5. The declarative widget vocabulary (section 5), decided in DECL-UI-0.

## 8. Build tickets (todo, in order)

Each is small, has grep-able acceptance and names its scoped tests; Sonnet for implementation unless stated. Created in todo
in this order (ids in brackets).

1. SDK-PUB-1 (d9704a04): publish config + BOM + version catalog + `sdk-publish.yml` (dry run only, no publish without the owner).
2. SDK-SURFACE-1 (fe21312a): API dumps and the class-loader/published-surface guard.
3. PLUGIN-GRADLE-1 (3e3271ef): `build-logic` Gradle plugin replacing `gradle/ihq.gradle.kts`; `ihq` packs all three halves (G5); `catalogEntry`.
4. TEMPLATE-REPO-1 (8d852bbf): `templates/plugin-repo/` building against `build/m2`, with CI and release workflows.
5. REGISTRY-1a (955298b5): catalog schema additions (`repo`, `yanked`) + `infantry plugin catalog build`.
6. REGISTRY-1b (190708ef): `registry/` scaffold for the `infantry-plugins` repo with validate/publish workflows.
7. PLUGIN-VERSION-WIN-1 (c2ba55f5): higher version wins between bundled and installed copies.
8. PLUGIN-MCP-1 (07a41012): DaemonApi plugin-manager read seam + `Infantry_Plugins` daemon tools + owner-ask request.
9. PLUGIN-HOTLOAD-1 (cae8a6fc): load/unload one daemon half without a restart (shared with BOARD-DOWN-2).
10. PLUGIN-UI-1 (7988ce17, PM-8): desktop Settings > Plugins screen with the consent dialog and the not-sandboxed warning.
11. DECL-UI-0 (b4681a4a, design, Opus): declarative plugin UI for Android/web.
