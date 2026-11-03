# PLUGIN-MANAGER-0: audit and design of the plugin manager (ticket 269f0ec2)

Design only. The implementation is PLUGIN-MANAGER-1 (ticket de8dd3c2), split into the slices of section 7. Owner direction
(2026-10-30, in de8dd3c2): optional tooling (the process watcher, stack helpers, extra harnesses) are plugins the user
**enables**, installed and updated like the app itself; three plugin classes (official / community / custom); the optional
plugins are the testing ground for the community path, no bundled shortcut. Related: DAEMON-PLUGINS-1 (fb588e94),
PACKAGE-1 (514c3a9a), HARNESS-PLUGIN-3/4 (f0178091, 24a2706c), BUILD-PLUGINS-1 (9e3f0418), STACK-NEUTRAL-2 (c8e72d7f),
AGENT-REGISTRY-1 (a1d0867a).

Nothing here decides the owner questions of section 8.

## 1. Audit: what exists today

### 1.1 Discovery and loading (one resolver, three hosts)

| piece | where | what it does |
|---|---|---|
| plugins root | `PluginFolders.root` (infantryPlugins jvmMain) | `INFANTRY_PLUGINS_ROOT`, else `-Dinfantry.plugins.root`, else `<state dir>/plugins` |
| folders + enable list | `<root>/modules.json` (`ModulesConfig`) | `folders` (path + tier, default `core`/`official`/`community`), `modules.<Name>` = `ModuleSetting` (`enabled`, `allow`, `deny`, `allowOverride`, `allowInject`, `endpoints`) |
| scan | `PluginFolders.scan(root, config, bundled)` | every `<folder>/<Name>/module.json` (not `_*`), then the bundled root's `official/` (a user copy of the same name wins) |
| bundled root | `PluginFolders.bundledRoot` | `INFANTRY_BUNDLED_MODULES` / `-Dinfantry.plugins.bundled`; per layout in DAEMON-PLUGINS.md section 9 (installDist, docker, jpackage) |
| resolver | `ModuleResolver` (commonMain) | enable list, safe mode, dependencies (`DependencyGraph`, `SemVer`), `apiVersion` |
| UI host | `DesktopPlugins` (infantryUiCore jvmMain) | loads UI jars at runtime (desktop); wasm/Android compile from a plugin list (D6, D9) |
| daemon host | `DaemonPluginHost` (infantry `daemon/plugins`) | daemon halves (`daemon` block), own `URLClassLoader` under `DaemonApiClassLoader`, limits, hooks; **at daemon start only** |
| harness host | `HarnessModules` + `JarHarnessPluginSource` | harness halves (`harness` block) under `HarnessApiClassLoader`; at start; registry supports unload/discover per module |
| what is loaded | `GET /plugins` (DaemonMain) | modules + daemon + harness status (`loaded`, `refused: <reason>`, `failed`) |

### 1.2 Trust today

* **Tier comes from the folder** (`Tier`: core / official / community), never from module.json.
* **core**: compiled in, everything declared.
* **official**: the jar passes `OfficialTrust` (signed by a pinned key; signed module.json equal to the disk one), **or** it is in the
  install's bundled root (bundled = trusted, owner answer 97479106), **or** `-Dinfantry.plugins.devTrust=true` (property only, dev runs).
  `OfficialTrust.PINNED_FINGERPRINTS` holds a **placeholder** (`REPLACE_WITH_REAL_INFANTRY_SIGNING_CERT_SHA256_FINGERPRINT`): no
  real Infantry key exists, so outside the bundled root and devTrust nothing is official today.
* **community**: off until `enabled: true`, and only the services granted in `allow` (`daemon.code`, `daemon.tool`, `daemon.hook.<kind>`,
  `daemon.env:<N>`, `daemon.secret:<n>`, `daemon.processes` (never `kill`), `harness.code`, `harness.hook.<kind>`, `harness.setting:<k>`,
  `harness.cli`, plus the UI services) and node grants (`allowOverride`, `allowInject`). Default deny, fail closed.
* **safe mode** (`INFANTRY_SAFE_MODE`, `--safe-mode`): no community module loads.

### 1.3 Install paths today

| path | what it installs | checks | gaps |
|---|---|---|---|
| copy a folder into `<root>/<tier>/` | anything | the tier's load rules at next start | no record of where it came from, no version history |
| `.ihq` drop-in / picker (`DesktopDropIn` over `IhqInstaller`, desktop UI only) | `module.json` + `lib/*.jar` (+ `layout/*.json`, `deps/*.ihq` for compositions) | size/entry limits, safe names, jarsigner signature over the whole zip, one signer, cert currently valid, staged then atomic rename, `inspect` before `install`, re-inspect at commit | see 1.5 |
| `harness_install` frame / `infantry harness-install` / `infantry_harness_install` | the harness's **CLI** (npm etc., from `harness.cli.install`), not the plugin | none on the plugin; it is a tool install | not a plugin install at all; keep it, it is the harness's own setup step |
| `VoicePluginStore` (infantryUiCore voice) | voice engines from a bundled catalog | per-asset size + sha256, staged, enabled id file | a second, separate store with its own downloader and enable state |
| build presets (`plugins/build-presets.json`, BUILD-PLUGINS-1) | which plugins a packaged build carries; class `optional` = may be left out and installed later "through the plugin manager" | build-time only | the manager it points to does not exist yet |

### 1.4 Update, enable/disable, remove today

* **Update**: none for plugins. `IhqInstaller` refuses a name that exists in any tier folder (`ALREADY_INSTALLED`, "needs an
  upgrade path (none yet)"). A new version = delete the folder and drop again.
* **App update** (the mechanism to reuse): `update/` in infantry. `UpdateSource` (`check()`, `download()` -> `VerifiedInstaller`),
  `Updater` (GitHub Releases API + `SHA256SUMS`), `LocalDirSource` (a releases folder, offline), `UpdateSources.forConfig`
  (`INFANTRY_UPDATE_DIR` wins over `INFANTRY_UPDATE_REPO`), token only from the environment and only to the configured host,
  `ReleaseAssets.sha256` / `Updater.parseChecksum`. Headless: `ServerInstall` keeps `<root>/versions/<v>/` + `current`,
  previous version kept for rollback (`infantry rollback`). Desktop: verified MSI handed to Windows.
* **Enable/disable**: `modules.json` `enabled`; written by Settings (`ModulesFile.write`, `ModulesFile.grant` from the consent
  dialog). Takes effect at the next load (UI modules: `DesktopPlugins` reloads; daemon/harness halves: next daemon start).
  No daemon frame or CLI sets it.
* **Remove**: `IhqInstaller.uninstall` (community folder + its modules.json entry; Windows needs the jar closed:
  `DesktopDropIn.release()` unloads UI modules first). Official/bundled cannot be removed, only disabled.

### 1.5 Gaps (what the manager must close)

| # | gap | consequence |
|---|---|---|
| G1 | No catalog, no sources, no search | nothing to "install from"; optional plugins of BUILD-PLUGINS-1 have nowhere to come from |
| G2 | No update / rollback (`ALREADY_INSTALLED`), no version history | community plugins cannot be updated |
| G3 | `IhqInstaller` maps a trusted signer to **`Tier.CORE`** (stale since D3/D4: core = compiled in; signed by Infantry = official) | an Infantry-signed package would land in `core/` with full rights |
| G4 | The consent dialog lists only **node** asks (fill/override/inject); service grants (`allow`), `daemon.*`, `harness.*` are never shown or written | a community `.ihq` with a daemon or harness half installs but loads nothing (no `daemon.code`); the user must hand-edit modules.json |
| G5 | `.ihq` carries only the UI jar (`gradle/ihq.gradle.kts` packs `lib/<module jar>`); the `daemon.jar` / `harness.jar` paths are not rewritten, and `policies/` is not an allowed entry | a daemon or harness half (the watcher!) cannot be shipped as a package at all |
| G6 | Install/remove is desktop-UI only (`DesktopDropIn`); no daemon service, frame, CLI or MCP | headless servers and agents cannot manage plugins |
| G7 | Daemon and harness halves load at start only; Windows keeps an open jar locked | update/remove of a loaded module needs a staged apply at the next start |
| G8 | No pinned hash: nothing ties an installed package to a reviewed entry | a source can swap bytes under the same version |
| G9 | `OfficialTrust` key is a placeholder | official class cannot be verified outside the bundled root (owner decision 8.2) |
| G10 | Licence: `ModuleManifest.license` exists, no official module.json sets it, nothing shows it | the user cannot see a plugin's licence before installing |
| G11 | `VoicePluginStore` is a second store/downloader | a third would follow; fold it in later (slice PM-9), do not copy it |
| G12 | A community daemon half may never get `kill` (`daemon.processes` only) | the process watcher **cannot** run as community tier; it must be official-signed (see 6) |
| G13 | Remote client: UI halves load in the client, daemon halves in the daemon; both read the plugins root of their own state dir | the manager v1 manages the plugins root of the host it runs on; a remote client's UI halves are out of scope (listed, PM-9) |

## 2. The model: class, tier, source

Two words that must not be mixed:

* **Tier** (exists, unchanged): how far code is trusted at run time. Decided by the folder + signature, as today.
* **Class** (new, a label): where the plugin came from. `official` / `community` / `custom`, plus `bundled` vs `installed`.

| class | comes from | signature | lands in tier | grants |
|---|---|---|---|---|
| official | a source marked `official` **and** signed by a pinned Infantry key | required, `OfficialTrust` | official | declared ones, as today |
| community | a source marked `community` (a public index) | optional; the entry's **sha256 is required** | community | consent, default deny |
| custom | a source marked `custom` (local dir, private URL/git), **or anything the user points at that is in no configured catalog** (loud warning) | optional; may be signed by a key the user chose to trust | community | consent, default deny |

Rules:

* The class never raises the tier. A package claiming `official` that is not signed by a pinned key is **refused** from an
  official source, never downgraded silently. The tier is always computed from the bytes (as `IhqInstaller` does).
* A user-trusted custom key proves *who* published it (no "unknown publisher" warning), nothing more: same community tier,
  same consent (owner decision 8.5).
* `bundled` = the install's own read-only root; never removed, only disabled; updated by the app update.

## 3. Catalog format (schema 1)

A catalog is one static JSON file (`catalog.json`). It can be served from raw git files / Pages (a CDN, no API, no rate limits),
from a mirror, or from a local folder. It contains no code.

```json
{
  "schema": 1,
  "id": "infantry-official",
  "class": "official",
  "generated": "2026-10-30T12:00:00Z",
  "plugins": [
    {
      "id": "Infantry_Processes",
      "class": "official",
      "description": "Process watcher: rogue background processes, free RAM, reaping.",
      "tags": ["processes", "watch", "gradle"],
      "license": "Apache-2.0",
      "homepage": "https://...",
      "versions": [
        {
          "version": "1.1.0",
          "api": { "plugin": ">=2 <3", "daemon": ">=1.8.0 <2.0.0", "harness": null },
          "grants": ["daemon.code", "daemon.tool", "daemon.processes", "fill:infantry.shell.footer.processesBadge"],
          "platforms": ["desktop", "server"],
          "url": "https://.../Infantry_Processes-1.1.0.ihq",
          "size": 81234,
          "sha256": "9f2c...e1",
          "signer": "SHA256 fingerprint of the signing cert, or null",
          "published": "2026-10-30T12:00:00Z",
          "notes": "short release note"
        }
      ]
    }
  ]
}
```

* `id` = the module name (`ModuleManifest.name`), the folder name, the `modules.json` key.
* `api` ranges use the `SemVer` range syntax of `DependencyGraph`; `plugin` is `ModuleManifest.apiVersion` against the
  host's `PLUGIN_API_VERSION`, `daemon` against `DaemonApi.VERSION`, `harness` against the harness contract. Out of range = not
  offered, and refused if forced.
* `grants` = **exactly** `RequestedGrants.of(manifest)` (section 4.2). The installer recomputes it from the package; a mismatch
  is a refusal (the catalog cannot under-report).
* `license` = an SPDX id (or expression), shown before install. Missing = shown as "licence not stated" (owner decision 8.7).
  No licence files or headers are added by this work (LICENSE-MAP.md sign-off pending).
* `sha256` + `size` are required for every class; `url` may be `https:`, `file:` or relative to the catalog's own location (so a
  folder catalog is portable). `signer` is informational; the signature itself is checked in the package.
* Stack names are plain `tags` (`gradle`, `npm`); the manager never interprets them.
* Optional, later: `attestation` (Sigstore / GitHub build attestation URL). Not checked in v1.
* The official index is **generated** from the build (BUILD-PLUGINS-1's optional-plugins output writes catalog.json + the
  `.ihq` assets); a community index is a git repo where a pull request adds an entry and CI runs `infantry plugin catalog
  validate` (the same validator the manager uses), which recomputes sha256, the grants and the api range from the asset. Repo
  topic search is an unreviewed discovery aid only, its results are `custom`.

### 3.1 Sources are data

`<plugins root>/sources.json` (a missing file = the defaults the build configures; no URL is in code):

```json
{ "sources": [
  { "id": "infantry-official", "class": "official",  "url": "https://<owner-chosen>/catalog.json", "enabled": true },
  { "id": "community",         "class": "community", "url": "https://<owner-chosen>/catalog.json", "enabled": true },
  { "id": "acme-internal",     "class": "custom",    "dir": "\\\\fileserver\\infantry\\plugins", "trustedKeys": "acme-keys", "enabled": true }
] }
```

* Kinds: `url` (http(s)/file) and `dir` (a folder with catalog.json, or a bare folder of `.ihq` files, which is indexed on the fly
  as `custom`). A private git repo is a `url` to its raw catalog (token from the environment, as the app updater does) or a `dir`
  over a local clone; the manager does not run git (no tool assumption).
* Each fetched catalog is cached at `<state dir>/plugin-catalogs/<source id>.json` with its fetch time; offline the cache is used
  and marked stale; a `dir` source needs no network at all. Custom sources never upload anything and are never sent to another source.
* Companies add a mirror = another source entry; the official index is only a default (owner decision 8.1).

## 4. Interfaces

### 4.1 The service (Kotlin, daemon side; the desktop calls the same code on a local root)

```kotlin
interface PluginManager {
    fun list(): List<PluginRow>                       // installed + bundled, with class, tier, version, enabled, state, source
    fun sources(): List<CatalogSourceRow>; fun setSources(s: List<CatalogSource>)
    fun refresh(sourceId: String? = null): List<SourceRefresh>   // fetch or use the cache; never throws offline
    fun search(query: String, tags: Set<String> = emptySet()): List<CatalogHit>
    fun inspect(ref: PluginRef): Inspection           // downloads + verifies + reads manifest; installs nothing
    fun install(inspection: Inspection, consent: Consent): ManagerResult
    fun setEnabled(id: String, enabled: Boolean): ManagerResult
    fun setGrants(id: String, consent: Consent): ManagerResult
    fun checkUpdates(): List<UpdateOffer>             // per installed plugin: newer compatible version, grantsRaised?
    fun update(id: String, consent: Consent? = null): ManagerResult   // null consent = only if grants are not raised
    fun rollback(id: String): ManagerResult
    fun remove(id: String, purgeData: Boolean = false): ManagerResult
    fun status(id: String): PluginStatus              // incl. pending ops, last error, restart needed
}
sealed interface PluginRef { data class Catalog(val id: String, val version: String?, val source: String?) ;
    data class File(val path: String) ; data class Dir(val path: String) ; data class Url(val url: String) }
data class Inspection(val id: String, val version: String, val cls: PluginClass, val tier: Tier, val license: String?,
    val signer: String?, val sha256: String, val grants: List<GrantAsk>, val warnings: List<String>, val token: String)
data class Consent(val token: String, val granted: Set<String>)
```

* `Inspection.token` = sha256 of (package sha256 + sorted requested grants). `install`/`update` with a token that does not match a
  fresh re-inspection is refused ("changed since it was checked"), the rule `DesktopDropIn` already applies.
* `ManagerResult` = `Done(restartNeeded)` | `Pending(reason)` | `Refused(code, message)`; refusal codes extend `IhqRefusal`
  with `HASH_MISMATCH`, `GRANTS_MISMATCH`, `API_INCOMPATIBLE`, `NOT_SIGNED_OFFICIAL`, `CONSENT_REQUIRED`, `NOT_IN_CATALOG`.

### 4.2 Grants: one function for every caller

`RequestedGrants.of(manifest): List<GrantAsk>` (infantryPlugins commonMain, pure): every grant the module would need as
community tier: its declared services, `daemon.code` + one per declared daemon tool/frame/hook/env/secret/processes, `harness.code`
+ hooks/settings/cli, and the node asks (`fill:`, `override:`, `inject:`, locked nodes marked not grantable). The consent dialog,
the CLI, the catalog validator and the update diff all use it, so G4 closes in one place. Consent writes `modules.json`
(`enabled`, `allow`, `allowOverride`, `allowInject`) through `ModulesFile`; the manager keeps **no second enable list**.

### 4.3 Wire, CLI, MCP

* Frames (additive, documented in CONTRACT.md when built): `plugin_list`, `plugin_sources` (get/set), `plugin_refresh`, `plugin_search`,
  `plugin_inspect`, `plugin_install`, `plugin_set` (enabled / grants), `plugin_updates`, `plugin_update`, `plugin_rollback`,
  `plugin_remove`, `plugin_status`; each with a `_result`. Actions in `ActionCatalog`: `plugins.read` (read), `plugins.manage`
  (destructive, **owner-only**: install, grants, update, rollback, remove, sources). The demo lock refuses `plugins.manage`.
* CLI: `infantry plugin list | search <q> [--tag t] | show <id> | install <id|file.ihq|dir|url> [--version v] [--grant g ...] [--grant-all-requested] |
  enable <id> | disable <id> | grants <id> [--grant ...] | updates | update <id|--all> | rollback <id> | remove <id> [--purge] |
  status <id> | sources list|add|remove|refresh | catalog validate <catalog.json> [--assets dir]`. Without `--grant` an install
  prints the requested grants and stops (no interactive prompt).
* MCP (agents): `infantry_plugin_list`, `infantry_plugin_search`, `infantry_plugin_show`, `infantry_plugin_status` (read), and
  `infantry_plugin_request` which **asks the owner** (an `ask` with the inspection: grants, licence, class) instead of installing.
  Agents never install or grant (owner decision 8.6 recommends keeping it so).
* UI (later slice): Settings > Plugins: tabs Installed / Browse / Sources; class badges (official, community, **custom**), bundled
  vs installed, licence, signer, grants with ticks, update badge with "asks for new permissions", rollback, remove. The `.ihq`
  drop-in goes through `inspect`/`install` of the manager.

## 5. Flows

On-disk layout added under the plugins root (scan already skips `_` folders):

```
<root>/community/<Id>/            the active version (unchanged place, so every loader keeps working)
<root>/_versions/<Id>/<version>/  previous versions kept for rollback (default keep 2, setting)
<root>/_pending.json              operations to apply at the next start (update/rollback/remove of a loaded module)
<root>/installed.json             the ledger: id -> {version, class, source, sha256, signer, grantedAt, history[]}
<root>/sources.json, trusted-keys/, install.json   (sources new; the other two exist)
```

Official-class installs (non-bundled) land in `<root>/official/<Id>/` the same way; the ledger records `class`.

* **Install**: resolve ref -> fetch to a temp dir (shared fetcher, 5.1) -> size + **sha256 vs entry** (catalog refs; a file/dir/url
  ref outside any catalog is `custom` with a warning and its hash recorded as first seen) -> `IhqInstaller.inspect` (structure,
  signature, tier from signer) -> class check (official source needs official tier) -> manifest equals entry (id, version,
  grants, api) -> api range vs host -> show grants + licence -> consent -> `IhqInstaller.install` -> `ModulesFile.grant` -> ledger.
  New module: live at once for UI halves; daemon/harness halves at the next daemon start (`restartNeeded`).
* **Enable/disable**: `modules.json` only. A disabled module loads nothing (all three hosts already honour it).
* **Update**: `checkUpdates` picks the highest compatible version newer than the ledger's. Diff `RequestedGrants` new vs granted:
  not raised -> may apply without asking (if the source's auto-apply is on, owner decision 8.4); **raised -> never auto-applies**,
  offered with the new grants marked. Apply = install the new version into staging, move the current folder to
  `_versions/<Id>/<old>/`, rename the new one in; if the module is loaded (or a file is locked) the swap is written to
  `_pending.json` and done at the next start before the scan.
* **Rollback**: swap the active folder with the newest `_versions` entry (same pending rule). Grants of the older version are a
  subset or equal by construction of the history; if not, the extra ones are dropped.
* **Remove**: move the folder to `_versions/<Id>/<v>/` (pending if loaded), drop its `modules.json` entry and ledger row; `--purge`
  also deletes `<state>/plugin-data/<Id>/`. Bundled: refused, disable instead.
* **Pending apply**: `PluginManager.applyPending(root)` runs first thing in daemon start and desktop start (a lock file
  `_pending.lock` so only one process applies), before `PluginFolders.scan`. Each op is idempotent; a failed op stays listed with its
  reason and the old version keeps running.

### 5.1 Reuse of the app's own update mechanism (no second mechanism)

* Extract from `update/` a small `AssetFetch` (http(s)/file URL -> temp file, size cap, sha256 verify, token only from the
  environment and only to the source's own host, offline = a clean failure) that `Updater`, `LocalDirSource` and the plugin
  manager share. `ReleaseAssets.sha256` and `Updater.parseChecksum` are reused as is.
* The version store follows `ServerInstall` (keep previous, switch, rollback); the plugin check runs on the app's update check
  schedule and shows in the same footer/toast style ("2 plugin updates").
* Bundled plugins are updated by the app update, never by the manager.

## 6. The process watcher as the first optional entry (testing ground)

STACK-NEUTRAL-2 has landed: the watch is `plugins/official/Infantry_Processes` (UI badge + daemon half, DaemonApi >= 1.8.0).

* It becomes `optional` in `plugins/build-presets.json`; the presets that exclude `@optional` leave it out of the build, and the
  generated catalog lists it with its `.ihq` (needs G5: the package must carry the daemon jar).
* It goes through the exact path of section 5: catalog entry, fetch, hash, signature, grants shown, consent, enable, update,
  rollback, remove. No bundled shortcut in the optional build.
* **Gap G12, recorded, not hacked**: its daemon half reaps processes, and `kill` is never granted to community tier. So it must be
  **official-signed** to work fully. With the placeholder key (G9) the tests sign with a test key placed in `trusted-keys/`; a
  community-signed copy installs and runs read-only (listing, pressure), and reaping is refused: that is the correct behaviour.
* Fresh install: not installed, off. Existing installs keep it through the one-time migration of STACK-NEUTRAL-1
  (`limits.watch.enabled`) and the bundled copy of the full preset.

## 7. Slice plan (ranked; one Sonnet worker each; scoped tests only, never the full suite)

| # | slice | module(s) | done when | scoped tests |
|---|---|---|---|---|
| PM-1 | Model: `PluginClass`, `CatalogEntry`/`Catalog` (schema 1, serializable, validation), api ranges, `RequestedGrants.of` | infantryPlugins commonMain | pure functions, no IO | `CatalogTest`, `RequestedGrantsTest`, `SemVerTest` |
| PM-2 | Installer: trusted signer -> **OFFICIAL** (G3); expected sha256 + manifest-equals-entry checks; `_versions`, `installed.json`, `_pending.json` + `applyPending`; update/rollback/remove primitives | infantryPlugins jvmMain | an update and a rollback swap folders atomically, pending when locked | `IhqInstallerTest`, new `PluginVersionsTest`, `PendingOpsTest` |
| PM-3 | Package daemon + harness halves in `.ihq` (G5): `ihq.gradle.kts` adds `lib/<Module>-daemon.jar`, `lib/<Module>-harness.jar`, rewrites `daemon.jar`/`harness.jar`; installer allows `policies/*.json` | gradle, infantryPlugins | an installed Acme `.ihq` with a daemon half loads in the daemon | `IhqInstallerTest`, `DaemonPluginMechanismTest` |
| PM-4 | Sources and fetch: `sources.json`, dir + url catalog sources, cache, offline; `AssetFetch` extracted from `update/` and used by `Updater`/`LocalDirSource` | infantry `update/`, new `plugins/manager/` | a dir source works with no network; url source via a local test HTTP server | `CatalogSourcesTest`, `AssetFetchTest`, `UpdaterTest`, `LocalDirSourceTest` |
| PM-5 | Daemon `PluginManager` service + frames + `plugins.read`/`plugins.manage` actions + CLI `infantry plugin ...` + `catalog validate` | infantry daemon, cli, protocol | the de8dd3c2 test list passes | new `PluginManagerServiceTest` (install/enable/disable/update/rollback/remove with a fake catalog + the Acme jar; tampered hash and signature refused; consent required; grant-raising update not auto-applied; incompatible api refused; disabled loads nothing; custom dir offline), `PluginCliTest` |
| PM-6 | MCP read tools + `infantry_plugin_request` (owner ask) | infantry cli/mcp | agents can list and request, never install | `McpPluginManagerToolsTest` |
| PM-7 | Process watcher as the first optional catalog entry (section 6), generated catalog.json from BUILD-PLUGINS-1's optional output | plugins/official/Infantry_Processes, gradle | fresh optional build: not installed; install via catalog works; community copy cannot reap | `ProcessesCatalogEntryTest` + the Infantry_Processes daemon tests |
| PM-8 | UI: Settings > Plugins (Installed / Browse / Sources, consent with all grants, update/rollback), `DesktopDropIn` through the manager | infantryUiCore, Infantry_Settings | consent shows service + daemon + node grants | the Settings plugin UI tests touched, `ConfigDialogOfficialJarsUiTest` |
| PM-9 | Later: fold `VoicePluginStore` in (G11); remote client UI halves (G13); attestation check; licence display polish | various | | per slice |

PM-1 and PM-2 have no dependency on each other's code beyond PM-1's types, so PM-2 starts as soon as PM-1 merges (one worker
at a time, D7). PM-3 can run any time after PM-2. PM-5 needs PM-1, PM-2, PM-4.

## 8. Decisions that need the owner (listed, not decided)

1. **Official index repo and hosting**: which repository holds the official `catalog.json` and the `.ihq` release assets, and
   whether it is served from raw files or Pages. Expensive to reverse: the URL is baked into every install's default `sources.json`.
2. **The Infantry signing key**: generate it, where it lives (offline vs CI secret), its fingerprint replacing the
   `OfficialTrust` placeholder, rotation (add a second pinned cert). Expensive to reverse: every official package is signed by it.
3. **Catalog signing**: rely on HTTPS + per-entry sha256 (v1 design), or also sign `catalog.json` (detached signature) with the
   same or a separate index key. Recommended: separate index key, later slice.
4. **Update channel and default**: stable only vs stable + beta per source; whether updates that raise no grants auto-apply by
   default (recommended: check automatically, apply on click; raised grants never auto-apply in any case).
5. **User-trusted custom keys**: label + no publisher warning only (recommended), or also skip the grant consent (not recommended).
6. **Agents and installs**: agents may only request (owner ask) (recommended), or may install with a policy grant.
7. **Missing licence**: show "licence not stated" and allow (recommended), or refuse in the official/community indexes only.

## 9. Expensive to reverse in this design

* The catalog schema field names and the `id` = module name rule (indexes in the wild will carry them; `schema` allows a v2).
* The on-disk layout (`_versions`, `installed.json`, `_pending.json`): migrations later must read it.
* `plugins.manage` being owner-only: loosening later is easy, tightening after agents rely on it is not.

## 10. Implementation status (PLUGIN-MANAGER-1, ticket de8dd3c2)

Built: the daemon-side manager core and the CLI (slices PM-1, PM-2 core, PM-4 sources, PM-5 minus wire frames). Code:
`infantry/.../daemon/plugins/manager/` (`PluginCatalog.kt` = schema-1 model, `RequestedGrants`, ledger; `PluginManager.kt` = sources,
refresh/cache, search, inspect, install, enable/disable, grants, checkUpdates/update, rollback, remove, `validate`) and
`cli/PluginCli.kt` (`infantry plugin ...`, section 4.3 command set). Supporting edits in `infantryPlugins`: `IhqInstaller` takes
`trustedTier` (the manager passes OFFICIAL: **closes G3** for the manager path; the drop-in keeps CORE until PM-8) and `replace`
(update); `ModulesFile.update` writes `enabled`/`allow`/`allowOverride`/`allowInject`.

Decisions taken while building (all reversible, none owner-level):

* **No wire frames, no new contract.** `infantry plugin` works on this host's plugins root directly (like `infantry remote`), because the
  files are local to the daemon and the app. Frames (section 4.3) stay a follow-up for a remote client; `plugins.manage` owner-only is
  therefore "whoever can run the CLI on the host".
* **`RequestedGrants` lives in the manager package**, not infantryPlugins commonMain: the UI consent dialog (PM-8) moves it when it needs it.
* **Local paths never use the network fetcher.** Only `http(s)` goes through the injectable `Fetcher`; dir/file/`file:` sources work offline.
* **Unsigned is accepted for community/custom** (the catalog sha256 pin, or the user's own say for custom, is the proof); a broken
  signature is always refused; official needs a trusted-key signature (`NOT_SIGNED_OFFICIAL`). A user-trusted key lives in
  `trusted-keys/custom/` (labelled, never official; the installer only reads the top level of `trusted-keys/`).
* **Consent**: `--grant g` / `--grant-all-requested` / `--no-grants`; none given = CONSENT_REQUIRED and nothing installed. Install lands
  enabled with exactly the consented grants. An update asking for a grant not yet consented is GRANTS_RAISED and changes nothing.
* **Reuse of the app updater (5.1) is partial**: `ReleaseAssets.sha256` is reused; the fetch is a small `Fetcher` here, because
  `Updater`'s download is GitHub-release specific. Extracting a shared `AssetFetch` (PM-4) is still open.

Not built (gaps, listed not hacked):

* **MCP tools.** Five `infantry_plugin_*` tools were written and **backed out**: `BuiltinToolsListGuardTest` (a ratchet, "the list grew
  past 6") says new MCP tools must be daemon-plugin tools, and a daemon plugin cannot reach the manager (no `DaemonApi` surface for the
  plugins root). Seam needed: a `DaemonApi` plugin-manager read service (list/search/inspect) + an official `Infantry_Plugins` daemon half
  with the read tools and an `infantry_plugin_request` that calls the ask service. Until then agents use `infantry plugin list|search|show`
  through the CLI and `infantry ask` to request an install.
* **G7 staged apply.** A loaded module's locked files make update/rollback/remove answer IN_USE (nothing changed); `_pending.json` /
  `applyPending` is not built. Daemon/harness halves load at start, so every change says "takes effect at the next daemon start".
* **G5 packaging.** `.ihq` build (`gradle/ihq.gradle.kts`) still packs only the UI jar; a daemon half inside a package works if its
  `daemon.jar` points at `lib/<x>.jar` (the manager and installer accept it), but the build does not produce that yet (PM-3).
* **Catalog generation, UI screen (PM-8), VoicePluginStore fold-in (PM-9), update-check schedule, attestation.**

### The process watcher as the first optional catalog entry (item 4)

STACK-NEUTRAL-2 landed (`plugins/official/Infantry_Processes`, UI badge + daemon half). The entry the generated official index must carry,
and that `infantry plugin catalog validate` checks (sha256, size and grants are filled from the built `.ihq`):

```json
{ "id": "Infantry_Processes", "description": "Process watcher: rogue background processes, free RAM, reaping.",
  "tags": ["processes", "watch"], "license": null,
  "versions": [ { "version": "1.0.0", "api": { "plugin": ">=2 <3", "daemon": ">=1.8.0" },
    "grants": ["daemon.code", "daemon.tool", "override:infantry.shell.footer.processesBadge"],
    "url": "Infantry_Processes-1.0.0.ihq", "sha256": "<computed>", "size": 0 } ] }
```

Dependencies, recorded not worked around: (1) **G5** (the package must carry `Infantry_Processes-daemon.jar` under `lib/`);
(2) **G12**: the host process service gives `kill` only to official/core, so a community-signed copy runs read-only and reaping stays
refused; the real entry needs the Infantry key (**G9**, owner decision 8.2); (3) `daemon.processes` is a runtime grant the manifest does
not declare, so `RequestedGrants` cannot list it: the module.json needs `"services": ["daemon.processes"]` (or a daemon `needs` entry) to
be shown at consent; (4) `Infantry_Processes/module.json` has no `license`. Fresh install: not installed, off; existing installs keep it
through the STACK-NEUTRAL-1 one-time migration (untouched by this ticket).
