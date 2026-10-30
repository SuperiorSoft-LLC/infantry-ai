# CONFIG-FIELDS: PayloadCMS-style field schemas for plugin and block configuration

Ticket CONFIG-FIELDS-1 (aa17b1d2). Audit + design + gap list. It does NOT replace `docs/CONFIG-MANIFEST-DESIGN.md`
(CONFIG-1..10); it is the field-level reference on top of it and says what is still missing. Owner rule: extend, never break
(compiled unified manifest in the DB, injected into blocks). No wire change here; every new key below is optional.

## 1. Audit: what exists (verified in code, 2026-10-30)

| Concern | State | Where |
|---|---|---|
| Declaration model | built: `ConfigDeclaration(node, group, fields, tab, order, title, applies, keywords, block, locked, readOnly, platforms)`, `ConfigFieldDeclaration(key, type, label, default, options, optionLabels, min, max, step, registry, exposed, autoSelectNew, description, order, scope, platforms, slots, item)` | `agenthqPlugins/.../plugin/api/NodeConfig.kt` |
| Field types | built: `text textarea(new) number checkbox/toggle select radio multiSelect color classes list implementation`, plus module-registered types (`fieldTypes` in module.json, `UiRegistrar.fieldType`); an unknown type is kept and shown read-only | `ConfigFieldTypes`, `FieldTypes.kt` |
| Validation | one rule per type, shared: `ConfigFieldValidator` (daemon + client, throws) and `FieldTypes.validateBuiltIn` (dialog, one-line message). Bad value: refused on write | `NodeConfig.kt`, `FieldTypes.kt` |
| Settings UI | generated from the compiled manifest: tab/group/order/title/applies/keywords; `block` escape hatch for non-form sections; editors in `NodeFields.kt` (`TextEditor`, `ClassesEditor`, `ListEditor`, options, registered editors) | `ui/plugin/config/` |
| Compiled config | one unified JSON per host stored as reserved `node_config` row `@manifest` (`GET/PUT /config-manifest?host=`), hash-swapped on every load; user overrides stay separate (`node_config`, setBy user); device scope over ui prefs | `CompiledConfig.kt`, `ConfigManifests.kt`, `NodeConfigHttp.kt`, `DeviceScope.kt` |
| Resolution | default -> core config -> parent chain -> device/daemon override -> daemon policy; unset is null, never an error | `ResolvedConfig.kt` |
| Block params | `Render(scope)` gets no params; it calls `scope.config()` (`BlockScope.config()`, Contract.kt:90) -> `ConfigValues { string int double boolean strings classes color overridden }`, recomposes on change. Missing config = `EMPTY` | `ShellContext.kt:919` |
| Class overrides | built: `classes` field with per-slot token allow-list; `ConfigValues.classes(slot)`; validator refuses tokens outside the list; the block owns token -> Modifier map (no CSS strings from config) | `ConfigClassSlot` |
| Bean-style binding | built only for `implementation` fields: `registry` + `exposed`, resolved to a registered key (e.g. `tabs.sorter`, `orchestrator.provider`); `ConfigContext.implementation` | `ImplementationFields.kt` |
| Daemon halves | built (CONFIG-8): `daemon.config` in module.json, `config_schema/values/set/changed` frames, `rev` check, unsigned = read-only (contract 1.142.0) | `DaemonConfig`, `agentHq/CONTRACT.md` |
| Unknown tolerance | `PluginJson` has `ignoreUnknownKeys = true`; unknown field type kept read-only; conflicting node+key: first wins, second is a `problems` line | `ModuleResolver.kt` |

### Hard-wired in a blank install (no plugins)

Per `docs/RUNTIME-PLUGINS-AUDIT.md` and `docs/SHELL-LEAKS.md` (not re-measured here beyond reading them):

1. **UI modules are not runtime-loaded in the shipped desktop app.** The official modules compile into the app and are found by
   `ServiceLoader`; they toggle on/off through `plugins/modules.json` but their CODE is not loaded from data (D5 goal not met;
   0 of 20 at audit time, partly changed since by RUNTIME-6..26 which turned core modules into `plugins/official/*` jars; the
   shipped-jar count must be re-checked by the QA gate). wasm and Android are compile-time by design (D9).
2. **Daemon feature services** (tickets, mail, knowledge, roles, pods, triggers, epics, prompts, stats, usage, git, run, questions,
   update) are in the daemon jar. `DaemonPlugins` resolves modules but loads no code. Waiting on D11 / owner question d0e4e3fb.
3. **Harness plugins** `claude` and `pi` are on the daemon classpath (ServiceLoader). Descriptors (`harnesses/*.json`) are data.
4. **Core by design (D4):** shell frame and slots, connect/servers/switch error/server update, the locked trust config sections
   (policy, plugins, modules, registry, secrets, pair, about, updates, server update, logs), two shell-owned sections
   (agent scope, lifecycle), toast and status bar, panel frames, window/platform glue. These are declared in `CoreConfig.JSON`.
5. **Hand-written fallback:** `ConfigEntry`/`ConfigTab`/`ShellConfigSettings.kt` stay as the fallback when no snapshot exists
   (open owner question b of CONFIG-MANIFEST-DESIGN section 11).
6. **Per-host differences:** the daemon `settings` frames are still a key-by-key system with hand validators for built-in keys;
   only plugin-declared daemon keys use the declared validator.

Blank-shell check (what to script next, not scripted here): start with an empty `plugins/` tree and temp state dir, assert
(a) the shell draws with `PlaceholderBlock`s, (b) the Settings dialog shows only the core tabs (Plugins, Modules, Secrets, About...),
(c) `GET /config-manifest` contains only `CoreConfig.JSON` sources, (d) dropping one config-only plugin adds its tab with no code
change. The existing `empty` shot run (`renderTabShotsForDiff`, `empty`/`dropin`) covers (a); (b)-(d) are gap G10.

## 2. Field-type catalog

Every field is `{ key, type, label, ...properties }`. Stored values are plain JSON.

| type | Stored | Editor | Status |
|---|---|---|---|
| `text` | string | one-line box | built |
| `textarea` | string | multi-line box (3 lines) | **built in this ticket** |
| `number` | number | box, `min max step` | built |
| `checkbox` / `toggle` | boolean | switch | built |
| `select` | string | dropdown-style option list from `options` | built (rendered as option buttons) |
| `radio` | string | radio group | built |
| `multiSelect` | string[] | chips | built |
| `color` | `#RRGGBB` / `#AARRGGBB` | hex box | built (no swatch picker) |
| `classes` | `{slot: "tok tok"}` | one row per slot, allow-list | built |
| `list` | array of `item` | add/remove rows (scalar items) | built (object rows via `fields[]`: G3 done, CONFIG-FIELDS-4) |
| `implementation` | registry key | dropdown of registry keys | built |
| `secret` | write-only | masked box | **not built** (G4). Secrets today live in the Secrets section and never in a field |
| `group` | object of nested `fields` | indented sub-card, recursive | built (CONFIG-FIELDS-4) |
| custom | any | module `FieldType` | built |

## 3. Per-field properties

| Property | Meaning | Status |
|---|---|---|
| `label`, `description` (help), `default`, `order`, `options`, `optionLabels`, `min/max/step`, `scope`, `platforms` | as in the manifest design | built |
| `required` | value must be non-empty; empty refuses the write | **gap G1** |
| `visibleWhen` | `{ "key": "mode", "eq": "custom" }`, sibling key only, no expressions | **gap G2** |
| `pattern`, `minLength`, `maxLength` | text validation; message from `patternMessage` | **gap G1** |
| `placeholder`, `multiline` | text hints (`multiline` is made redundant by `textarea`) | gap G5 (placeholder only) |
| `since`, `renamedFrom` | versioning, section 6 | **gap G6** |
| `requires: {contract}` on the declaration | hide when the daemon is older | **gap G7** |

## 4. Block-params contract

1. A block never gets constructor params. It asks `scope.config()` for its own node id and receives a typed `ConfigValues`
   (`string int double boolean strings classes color`), resolved default -> parent -> override, recomposing on change.
2. Missing config is a value: `EMPTY` answers null for every key and the block uses its constants (blank shell and unit tests work).
3. A block reads its own node and its ancestors only; another module's node needs the `config.read` service allowed in
   `plugins/modules.json` (designed, see manifest design section 3; **audited in CONFIG-FIELDS-2: not built, `ConfigContext.values` answers any node id, G8 open**).
4. **Classes:** the block declares a `classes` field with `slots`, each with a token `allow-list`. The block maps tokens to
   Modifiers/TextStyles it owns; a token outside the list never reaches the block (validator). An empty allow-list means free text,
   stored but ignored unless the block understands it. No class names, no code, in config.
5. Typed bean (`scope.configAs<T>()` via kotlinx.serialization) is built (CONFIG-FIELDS-2): `ConfigValues.configAs<T>()` decodes the resolved map, ignores unknown keys, null on a wrong type. Example of the new field keys: `plugins/examples/Acme_FieldKeys`.

## 5. Bean-style binding rule

A block that needs a service declares an `implementation` field naming a registry id (`registry: "orchestrator.provider"`,
`default`, `exposed`). The host resolves the key to the provider registered under that id and hands it over through the
`ShellContext` domain; the block never names a class. Rules: (a) the key is data, the provider is code registered with the
host; (b) an unknown or missing key falls back to the declared `default`, then to a no-op (`NoXxx`) provider; (c) `autoSelectNew`
lets a newly installed provider take the slot while the user never picked; (d) a dependency is satisfied by a plugin or not at
all, never by a compile-time import between plugins. Today this works for tab sorters and the orchestrator provider; a generic
`dependsOn: ["registry.id"]` list on a block type is gap G11 (design only).

## 6. Versioning and unknown-field tolerance

- Unknown JSON keys in a declaration are ignored (`ignoreUnknownKeys`); unknown `type` renders read-only and keeps its value.
- A newer default reaches every user without an override (the compiled snapshot is rebuilt, overrides are a separate table).
- Removed field: override row inert, revived on reinstall. Type change: stored value re-validated, invalid -> default, never a crash.
- `renamedFrom` copies the override once (marker `<key>@migrated`) and `since` records the plugin version that added a field
  (informational). Both are designed in the manifest design section 2 and **not built** (G6).
- No wire change is needed for any gap below; new keys are optional and `apiVersion` stays 2. Contract bump only if G12 lands.

## 7. Gap list vs today

| # | Gap | Size | Notes |
|---|---|---|---|
| G1 **done** (CONFIG-FIELDS-2) | `required`, `pattern`, `minLength`, `maxLength` in both validators + editor error | S (0.5 d) | add optional keys to `ConfigFieldDeclaration`; extend `ConfigFieldValidator` and `validateBuiltIn`; tests. Not trivial enough for this ticket because the dialog Save-button logic needs a pass |
| G2 **done** (CONFIG-FIELDS-2) | `visibleWhen` (sibling key eq) | S (0.5 d) | hide the editor in `NodeFields` and skip validation of a hidden field |
| G3 **done** (CONFIG-FIELDS-4: recursive `ConfigFieldValidator`, `GroupEditor`, `ConfigValues.obj/list`, `configAs<T>()` nested; example in Acme_FieldKeys) | `group` type and `list` with object items (`fields[]`) | M (2 d) | recursive editor and validator; stored as object / array of objects |
| G4 | `secret` field | M (1.5 d) | must write to the Secrets store, never `node_config`; ties to the daemon secrets API; owner call on scope |
| G5 **done** (CONFIG-FIELDS-2; `select` is a dropdown only with `display:"dropdown"`, default stays option buttons) | `placeholder`, color swatch `palette`, real dropdown for `select` | S (0.5 d) | UI polish |
| G6 | `since`, `renamedFrom` migration marker | S (1 d) | loader step after compile |
| G7 | declaration-level `requires.contract` | S (0.5 d) | hide group when daemon contract is older |
| G8 **done** (CONFIG-FIELDS-3: `ConfigReadGate` + `GatedConfig`; community reads other nodes only with `config.read` declared in `services` and allowed in modules.json, core/official trusted, `configReadGate:false` dev switch; wired per module in CONFIG-FIELDS-4: `ModuleScope` swaps the reader-scoped `GatedConfig` into `LocalShellContext` wherever a community module draws; `ConfigReadScopeTest`) | gate + test | S (0.5 d) | `ConfigReadGateTest`, `GatedConfigTest` |
| G9 **done** (CONFIG-FIELDS-2, `ConfigValues.configAs<T>()`) | `scope.configAs<T>()` typed bean | S (0.5 d) | kotlinx.serialization over the resolved map |
| G10 **done** (CONFIG-FIELDS-3: `BlankShellE2ETest`; command in section 9) | scripted blank-shell E2E (empty plugins tree, temp state dir, port 8812) | M (2 d) | asserts section 1 items (a)-(d); the pre-launch QA gate wants it |
| G11 | generic block `dependsOn` registry ids for bean binding | M (1.5 d) | host injects the config-chosen provider; today only `implementation` fields |
| G12 | daemon `settings` built-in keys onto declared validators | M (2 d) | would let the daemon shell stop hand-validating; contract bump |
| G13 | desktop runtime loading of the official modules and daemon feature services | L (D5/D11) | tracked by RUNTIME tickets and owner question d0e4e3fb; out of scope here |

### Follow-up ticket texts

- **G1+G2 (CONFIG-FIELDS-2, S):** "Add optional `required`, `pattern`, `patternMessage`, `minLength`, `maxLength`, `visibleWhen{key,eq}` to
  `ConfigFieldDeclaration`. Enforce in `ConfigFieldValidator` and `FieldTypes.validateBuiltIn`; hide a field in `NodeFields` when
  `visibleWhen` fails and skip its validation. Old manifests unchanged (guard: `LegacyConfigStructureTest`). Tests in `CompiledConfigTest`."
- **G3 (CONFIG-FIELDS-3, M):** "`group` type and `list` of objects: recursive `ConfigFieldValidator`, `NodeFields` editor, `ConfigValues.obj/list`."
- **G10 (CONFIG-FIELDS-4, M):** "Blank-shell script: temp state dir, port 8812, empty plugins tree; assert core tabs only, manifest sources
  = core, add a config-only plugin and see its tab, remove it and see it vanish."
- **G4, G11, G12:** owner decisions first (secrets scope; dependsOn shape; daemon contract bump).

## 8. Implemented in this ticket

- `textarea` field type (commit 21a6ecac): `ConfigFieldTypes.TEXTAREA`, in `BUILT_IN`, validated as a string by both validators,
  drawn by `TextEditor` as a 3-line multi-line box. Test: `CompiledConfigTest` "textarea is a built-in string type"
  (`:superiorSoftLibrary:agenthqUiCore:jvmTest --tests *CompiledConfigTest`, passes).

## Files read

`docs/{CONFIG-MANIFEST-DESIGN,RUNTIME-PLUGINS-AUDIT,SHELL-LEAKS}.md` (sections), `plugins/modules.json`,
`superiorSoftLibrary/agenthqPlugins/.../plugin/api/NodeConfig.kt`, `agenthqUiCore/.../ui/plugin/config/{FieldTypes,NodeFields,CoreConfig}.kt`,
`agenthqUiCore/.../ui/shell/ShellContext.kt` (ConfigValues), `.../block/layout/Contract.kt` (grep), `CompiledConfigTest.kt`.

## 9. Blank-shell E2E (CONFIG-FIELDS-3, G10)

One command: `gradlew.bat :superiorSoftLibrary:agenthqUiCore:jvmTest --tests "*BlankShellE2ETest"` (use `--no-parallel` and the Metaspace 2g flags).
It runs the real `DesktopPlugins.load` on a temp state dir with an empty `plugins/` tree and no compiled modules, and asserts:
(1) no module, every config declaration is core, no plugin card, manifest sources empty; (2) a config-only `plugins/community/Acme_Look/module.json`
(no entry, no jar, no fills) adds its Appearance card and one manifest source; (3) deleting the dir removes both. It needs no daemon and
no port (the daemon only stores `/config-manifest`; the UI compiles it), so 8812 is not used. Change needed to make (2) possible:
a code-less module with `config` now loads as data-only (`DesktopPlugins.loadJar`); before, it was refused "module.json has no entry class".

Still hard-wired in a blank install (section 1 items): (a) the UI modules of the shipped app still compile in via `CompiledModules`
(`agenthqUiCore/.../plugin/CompiledModules.kt`; the test passes `compiled = emptyList()`); (b) daemon features live in the daemon jar
(`agentHq/.../daemon/DaemonPlugins.kt` resolves modules, loads no code); (c) harnesses `claude`/`pi` on the daemon classpath; (d) the
hand-written fallback `ConfigTab`/`ShellConfigSettings.kt` when no snapshot exists. Not scripted: a real daemon on 8812 and a rendered Settings dialog.

## 10. Group and list-of-objects fields (CONFIG-FIELDS-4, G3)

`{"type":"group","fields":[...]}` stores a JSON object; `{"type":"list","fields":[...]}` (or `item:{"type":"group","fields":[...]}`) stores an array of objects.
`ConfigFieldValidator` recurses (required/rules/`visibleWhen` per nested field; unknown keys in the object are kept). The editors are `GroupEditor` /
`ObjectListEditor` (`ui/plugin/config/GroupEditor.kt`), used by `NodeFields` and the generated Settings card; every change writes the whole object back.
Readers: `ConfigValues.obj(key)`, `ConfigValues.list(key)`, or `configAs<T>()` with nested `@Serializable` classes. `fields` is a new optional key, so old
manifests are unchanged (`LegacyConfigStructureTest`). Example: `plugins/examples/Acme_FieldKeys` (`primary` group, `servers` list). No wire change.
Section 9's hard-wired list is unaffected. Not covered by tests: the rendered Compose editor (compiles on jvm and wasm only).
