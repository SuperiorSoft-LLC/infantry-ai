# CONFIG-MANIFEST: manifest-driven configuration (PayloadCMS fields + bean-style injection into blocks)

Ticket CONFIG-1 (6718b3c5). Design only; no production code. Owner constraint (2026-10-28): **stick with what is built.**
This doc extends the fills / `Block` / `module.json` / `UiRegistrar` / `TabProvider` model and the P1 step 2 config layer
(`docs/BLOCKS-LAYOUT-DESIGN.md` 6s). It replaces nothing.

## 0. What already exists (the finding that shapes everything)

The owner asked for "a declarative `config` list in module.json that gives blocks parameters". Most of that is **already
built** (P1 step 2, 44aee6e5), and `Infantry_Settings/module.json` already ships one (`config: [{node, group, fields}]`):

| Piece | Where | State |
|---|---|---|
| `ConfigDeclaration(node, group, fields)` + `ConfigFieldDeclaration(key,type,label,default,options,min,max,step,registry,exposed,autoSelectNew)` | `agenthqPlugins/.../plugin/api/NodeConfig.kt` | built |
| Field types `text number checkbox select multiSelect implementation` (a module may register more) | `ConfigFieldTypes` | built |
| One merged tree: core `CoreConfig.JSON` + every module.json `config`, keyed by node id, parent chain from the block tree | `ui/plugin/config/ConfigTree.kt` | built |
| Overrides in the daemon table `node_config` (`nodeId,key,value,setBy seed|user,updatedAt`); `GET /node-config`, `POST /node-config/seed` (insert missing only), `PUT /node-config` (null value = delete) | `agentHq/daemon/NodeConfigHttp.kt` | built |
| Resolution child -> parent -> root -> declared default; unset is null | `ResolvedConfig` | built |
| Bean-style read: `ShellContext.config.values(nodeId)` returns typed `ConfigValues` (`string int double boolean strings`), recomposes on change | `ConfigContext` | built |
| Node editors in a "Plugins" view (`NodeFields.kt`) | `ui/plugin/config/` | built |

What is **missing** (and is the whole of this design):

1. Fields carry no **tab**, **order**, **description**, **requires**; the Settings dialog tab comes from the hand-written
   `ConfigEntry` enum (24 entries, `ConfigTabs.kt`) and its sections are hand-written Compose.
2. No field types `radio`, `toggle` (alias of `checkbox`), `color`, `list`, `textarea`, `classes`.
3. The seed rows are stored in the same table as user edits, so a plugin update **cannot change a default** for an unchanged
   key (section 2).
4. Blocks do not read `config` by default: `Render(scope)` reads `LocalShellContext`; only a few read `config.values(id)`.
5. Daemon settings (`SettingsService`, `settings`/`settings_set` frames) are a separate, key-by-key system with a hand-coded
   validator per key.
6. The 11 ownerless sections have no manifest at all.

## 1. Manifest schema

A module.json `config` entry is today `{node, group, fields[]}`. The new schema is the same shape plus optional keys. Every
new key is optional, so every module.json and `CoreConfig.JSON` already written stays valid (additive, `apiVersion` stays 2).

```jsonc
"config": [{
  "node":  "infantry.shell.theme",        // the block / tab-tree node the values are injected into (as today)
  "tab":   "Appearance",                  // NEW: Settings dialog tab. Absent = "Plugins" (today's view). Replaces ConfigEntry.tab
  "group": "Theme",                       // as today; NEW meaning: a titled card inside the tab (was: tab name)
  "order": 10,                            // NEW: sorts groups inside a tab (ties: module load order)
  "title": "Theme",                       // NEW: group heading (default: group)
  "applies": "The whole app, at once.",   // NEW: the "Applies to:" line the sections show today
  "keywords": ["color","dark"],           // NEW: feeds the dialog search (ConfigEntry.keywords today)
  "requires": { "contract": "1.139" },    // NEW: hide when the daemon is older (replaces contractAtLeast calls)
  "fields": [ /* ConfigFieldDeclaration, below */ ]
}]
```

Compatibility rule for `group`: today it names the tab in the Plugins view. If an entry has `tab`, `group` is the card title;
if it has no `tab`, `group` keeps its old meaning and the entry appears where it does today. No migration is forced.

**Field** (all keys but `key`/`type` optional; the added ones are marked NEW):

```jsonc
{ "key": "accent", "type": "select", "label": "Accent colour", "description": "Buttons, focus, highlights.", // NEW description
  "default": "RED", "order": 1,                                  // NEW order (within the group)
  "options": [ {"value":"RED","label":"Red"}, "TEAL" ],         // NEW: option may be an object; a bare string still works
  "visibleWhen": { "key": "mode", "eq": "custom" },              // NEW: a sibling-key condition, nothing else (no expressions)
  "scope": "device",                                             // NEW: see section 2; device | user | daemon
  "required": false, "min":0,"max":100,"step":1, "unit":"s",     // number
  "registry": "tabs.sorter", "exposed": true }                   // implementation, as today
```

| `type` | Stored JSON | Editor | Notes |
|---|---|---|---|
| `text` | string | text box | NEW `multiline`, `placeholder`, `secret:false` (secrets stay in the Secrets section; never a field) |
| `number` | number | stepper / slider | `min max step unit`, built |
| `toggle` (`checkbox` is its alias) | boolean | switch | |
| `select` | string | dropdown | `options` |
| `radio` NEW | string | radio group | same `options` as `select`; use for 2-4 choices |
| `multiSelect` | string[] | chips / checklist | built |
| `color` NEW | `"#RRGGBB"` or `"#AARRGGBB"` | swatch + hex box | `palette` optional (a fixed set of swatches) |
| `list` NEW | array of the item type | add / remove / reorder rows | `item`: a field (`{type:"text"}`) or `fields[]` for an object row |
| `classes` NEW | `{ "<slot>": "<style token string>" }` | one text row per slot | section 1c |
| `implementation` | registry key | dropdown of the registry's keys | built; a key, never a class name |
| any other | any | read-only JSON, unless a module registers an editor | built (unknown type kept read-only) |

Validation lives with the type, in one place (`FieldType.validate(field, json): String?`), shared by the dialog (inline
error) and, for `scope:"daemon"`, by the daemon (section 6). A value that fails validation is refused on write and falls
back to the default on read (never crashes a block).

### 1a. Example 1: Theme block, tab "Appearance"

Today `ThemeSettings(accent, dark)` is encoded `RED;dark` into the UI pref `THEME_PREF_KEY` and drawn by
`ConfigThemeSection`. Declared:

```jsonc
// plugins/official/Infantry_Appearance/module.json  (pilot, phase 1)
"config": [{
  "node": "infantry.shell.theme", "tab": "Appearance", "group": "Theme", "order": 10,
  "title": "Theme", "applies": "The whole app, at once. Remembered across launches.",
  "keywords": ["color","colour","accent","dark","light"],
  "fields": [
    { "key": "accent", "type": "select", "label": "Accent", "default": "RED", "scope": "device",
      "options": [ {"value":"RED","label":"Red"}, {"value":"BLUE","label":"Blue"}, {"value":"VIOLET","label":"Violet"}, {"value":"TEAL","label":"Teal"} ] },
    { "key": "dark",   "type": "toggle", "label": "Dark mode", "default": true, "scope": "device" },
    { "key": "custom", "type": "color",  "label": "Custom accent (overrides Accent)", "scope": "device" }
  ]
}]
```

The block (a new tiny `ThemeBlock` that fills a `infantry.shell.theme` slot and draws nothing, like `AlertsSound`):

```kotlin
object ThemeBlock : Block("infantry.shell.theme", "infantry.shell.theme") {
    @Composable override fun Render(scope: BlockScope) {
        val cfg = scope.config()                       // section 3: ConfigValues for THIS block's node id
        val accent = cfg.string("accent") ?: "RED"
        Palette.theme = ThemeSettings(
            ThemeAccent.entries.firstOrNull { it.name == accent } ?: ThemeAccent.RED,
            cfg.boolean("dark") ?: true, customAccent = cfg.string("custom"))
    }
}
```

`Palette.theme` stays the one holder every composable already reads, so nothing downstream changes. The Settings card is not
code: it is generated from the fields (section 4).

### 1b. Example 2: a multiselect

Voice alerts: which attention events are spoken (today a fixed set of switches in `ConfigVoiceSection`).

```jsonc
// Infantry_Alerts/module.json, config for node infantry.shell.voice
{ "node": "infantry.shell.voice", "tab": "Notifications", "group": "Voice", "order": 20, "fields": [
  { "key": "enabled", "type": "toggle", "label": "Speak alerts", "default": true, "scope": "device" },
  { "key": "events", "type": "multiSelect", "label": "Speak when", "default": ["question","approval"], "scope": "device",
    "visibleWhen": {"key":"enabled","eq":true},
    "options": [ {"value":"question","label":"An agent asks a question"}, {"value":"approval","label":"A tool needs approval"},
                 {"value":"done","label":"A ticket is done"}, {"value":"failed","label":"A job failed"} ] } ] }
```
```kotlin
val events = scope.config().strings("events").orEmpty()       // List<String>?; null only if the config layer is absent
if ("done" in events) speak(...)
```

### 1c. Example 3: a classes override

"Configs can define CLASSES to be passed". Compose has no CSS classes, so a "class" is a **named style token**: a string the
block resolves through a table the block's author owns. The developer exposes slots; the user types tokens; the block maps
token -> `Modifier`/`TextStyle`. Nothing in the JSON is code (same rule as 6s: no class names from config).

```jsonc
// Infantry_Board/module.json, config for node infantry.shell.board.card
{ "node": "infantry.shell.board.card", "tab": "Appearance", "group": "Board cards", "order": 30, "fields": [
  { "key": "classes", "type": "classes", "label": "Card style", "scope": "device",
    "slots": { "card": {"tokens": ["compact","roomy","outlined"], "default": "roomy"},
               "title": {"tokens": ["bold","normal","small"],     "default": "bold"} },
    "default": { "card": "roomy", "title": "bold" } } ] }
```
```kotlin
private val cardTokens = mapOf("compact" to Modifier.padding(4.dp), "roomy" to Modifier.padding(12.dp),
                               "outlined" to Modifier.border(1.dp, MaterialTheme.colorScheme.outline))
@Composable override fun Render(scope: BlockScope) {
    val cls = scope.config().classes("card").orEmpty()          // "compact outlined" -> list of tokens (space-separated)
    Box(Modifier.then(cls.fold(Modifier) { m, t -> m.then(cardTokens[t] ?: Modifier) })) { ... }
}
```
`slots[].tokens` is the allow-list: the editor offers exactly those (multi-pick), and a typed token outside the list is
refused. An empty `tokens` means free text (stored, ignored unless the block knows it). `ConfigValues` gains
`classes(slot): List<String>?`.

### 1d. Hello-world tab plugin: Acme_Hello with a `greeting`

The existing `plugins/community/Acme_Hello` already has the tab `acme.hello`. Add to its module.json:

```jsonc
"config": [{
  "node": "acme.hello", "tab": "Acme Hello", "group": "Greeting", "order": 1,
  "fields": [ { "key": "greeting", "type": "text", "label": "Greeting", "default": "Hello from Acme_Hello, a community module.",
                "description": "Shown at the top of the Hello tab." } ]
}]
```
Result: the Settings dialog gains an **Acme Hello** tab (no jar, no tab: "No-jar-no-tab" holds, section 4) with one text box.
The Kotlin that receives it (the tab's content lambda is the "block"; `TabContext` gets the same accessor):

```kotlin
registrar.tab(TabProvider(id = "acme.hello", ... ) { HelloTab(it) })

@Composable private fun HelloTab(context: TabContext) {
    val greeting = context.config.string("greeting") ?: "Hello"     // injected; recomposes when the user edits it
    ...
    Text(greeting)                                                    // was: Text("Hello from Acme_Hello, a community module.")
}
```
A community module declares config only on nodes it owns (its own tab ids): the existing `ConfigSource.owns` check.

## 2. Merge rules, overrides, versioning

**Layers, lowest to highest precedence** (the existing resolution, plus two layers made explicit):

```
1. declared default        the field's "default" in the declaring module.json   (never persisted)
2. core config.json        CoreConfig.JSON (same format, first in the merge)       (never persisted)
3. parent node's value     child -> parent -> root chain (existing; `children.*` and `@` keys never inherit)
4. device override         stored on this device (scope "device")
5. user override           stored in the daemon `node_config` (scope "user"): follows the user across their devices
6. daemon policy           scope "daemon": the daemon's value always wins (section 6); the dialog shows it locked
```
For one node: within a layer the nearest node in the chain wins; across layers a higher number wins. Unset is `null`, never an
error. `null` written to a key deletes the override (the key resolves again from below), as `PUT /node-config` does today.

**Store overrides separately from defaults (fix for gap 3).** Today `POST /node-config/seed` inserts every default as a
`setBy:"seed"` row. After a plugin update changes a default, the old seed row still wins. Rule: **seed rows are not
persisted for resolution.** `ConfigTree` already knows every default at load, so layer 1 is computed, not stored. The table
keeps only `setBy:"user"` rows. Migration (phase 1): on first load of the new build, delete `setBy='seed'` rows and
whose value equals the current default (they were never edited); keep the rest. `POST /node-config/seed` stays for old
clients and becomes a no-op in a newer daemon.

**Versioning on plugin update.** Each declaration is bound to its module `version` and each field may carry
`"since": "1.2.0"`. Rules, in order:
1. A changed **default** reaches every user who has no override (layers 1-3 are recomputed at load). A user override is kept.
2. A **removed field** leaves its override row inert (kept for 30 days, shown nowhere); a reinstall revives it. The Plugins
   view lists "orphaned settings" with a Clear button (same as the 6s D rule for a missing implementation).
3. A **renamed key**: the declaration gives `"renamedFrom": "oldKey"`; the loader copies the override once (marker row
   `<key>@migrated`, an `@` key, own-only) and never again.
4. A **type change** (e.g. text -> select): the stored value is validated against the new type; invalid -> ignored (falls
   back to the default) and listed as an orphan. Never a crash, never a silent rewrite.
5. `scope` change: an override at the old scope is ignored; the Plugins view offers "move to new scope".
6. Two modules declaring the same node+key: first in load order wins, the second is a `problems` line (built).

**Where overrides live.** `scope:"device"` -> the host's `saveUiPref` store (today's `THEME_PREF_KEY` etc., key `cfg.<node>.<key>`,
so existing saved theme/blur prefs are read once and migrated, phase 2). `scope:"user"` -> `node_config` in the daemon.
`scope:"daemon"` -> `SettingsService` (section 6). The user never edits module.json; plugin dirs hold only defaults, so a
plugin update never touches overrides (D13: user overrides never live in core/official dirs).

## 3. Bean-style injection

**Asking.** A block never receives constructor parameters (its `Render(scope)` signature is unchanged). It asks the scope:

```kotlin
// BlockScope gets one extension (kit, commonMain). The node id is the block's own id, so no argument is needed.
@Composable fun BlockScope.config(): ConfigValues =
    LocalShellContext.current?.config?.values(blockId) ?: ConfigValues.EMPTY
@Composable fun <T : Any> BlockScope.bean(type: KClass<T>, key: String): T? = ... // implementation fields (built: ConfigContext.implementation)
```
`ConfigValues` is the existing interface (+ `classes`, `color`, `list`, `obj`). "Injection" is therefore: the shell resolves
the node's values before it calls `Render`, via the composition local. For a **typed bean** a block may declare a data class
and decode once:

```kotlin
@Serializable data class ThemeCfg(val accent: String = "RED", val dark: Boolean = true)
val cfg = scope.configAs<ThemeCfg>()      // kotlinx.serialization over the resolved JSON; missing/invalid keys -> defaults
```
**Missing config is a value, not an error**: `EMPTY` answers null for every key and the block uses its constants, so a block
runs in a bare shell and in a unit test exactly as today.

**Live changes.** `ResolvedConfig.resolved()` reads `NodeConfigState.stored`, a Compose state, so any composable that called
`values()` recomposes when a row changes; the per-node resolution is cached per change of the stored map (built). The dialog
writes with `NodeConfigState.set(node,key,json)`: it updates the local copy first (instant), then `PUT`s the daemon. A
failed `PUT` rolls the local copy back and shows the error on the field. A change made on another device arrives as a
`node_config` frame (section 6) and goes through the same `replace(rows)`. **Non-composable consumers** (a tab's `start()`
service, a daemon endpoint) subscribe with `config.watch(node, key) { }` (a `Flow`), unsubscribed by the host on `stop()`,
like observers.

**Trust.** A block reads only its own node and its ancestors (the existing chain); reading another module's node needs the
`config.read` service allowed in `plugins/modules.json` (same gate shape as `endpoints`). A field of a locked core node is
never editable.

## 4. How the Settings dialog is generated

Input: `ConfigTree.fields` (all declarations) + `ResolvedConfig`. Algorithm, in `ConfigDialog` (replaces `ConfigEntry` + the
`ConfigTab` enum):

1. Collect declarations that have `tab`; drop those whose `requires` fails or whose module is not loaded (**no jar, no tab**:
   a declaration exists only because its module.json was read from a loaded jar, so an uninstalled plugin leaves no tab,
   and the core ships `CoreConfig.JSON` for the core-owned nodes only).
2. Tabs = distinct `tab` names. Order: a fixed list for the core tabs (`Appearance, General, Agents, Limits, Notifications,
   Plugins, Secrets, Devices, About` = today's `ConfigTab`, kept as the *default tab order* in `CoreConfig`), then any other
   tab by first declaration (module load order). A tab with no visible group is hidden (today's `visibleConfigTabs`).
3. Inside a tab: groups sorted by `order`, then load order; inside a group: fields by `order`. A group is a card with `title`,
   the `applies` line and the generated editors.
4. Each field renders through the type registry `FieldType` (`editor(field, value, onChange)`). The built-ins exist in
   `NodeFields.kt`; this adds `radio color list classes`. A module registers an editor with `registrar.fieldType(...)`
   (declare under `fieldTypes`).
5. Search matches `title`, `label`, `description`, `keywords` (replaces `ConfigEntry.keywords`).
6. **Escape hatch for sections that are not forms.** Secrets, Pair/QR, Logs, Updates, Roles and Limits have custom UI. A
   declaration may say `"block": "infantry.shell.config.roles"`: the card is the named fill (today's
   `ConfigSectionBlock`), still placed by `tab/group/order`. So **hand-written sections keep working unchanged** and migrate
   to forms only when a form is enough. The trust sections (plugins, modules, registry, secrets, pair, about, updates,
   serverUpdate, logs) stay core, locked, and declared in `CoreConfig.JSON` with `block` and `"locked": true`.

The dialog frame (title, lifetime note, search, Done) stays core. Existing test tags are kept by deriving them from
`config-<node>-<key>`; the current ones (`config-section-*`) stay on the card for the `block` escape hatch.

## 5. The 11 ownerless shell sections

From `ShellConfigSettings` (the 11 not owned by Usage/Roles/Alerts/Attention):

| Section (`ConfigEntry`) | Today's data | Owner (proposed) | Form or `block`? | Scope |
|---|---|---|---|---|
| THEME | UI pref `THEME_PREF_KEY` (`RED;dark`) | **new official `Infantry_Appearance`** (node `infantry.shell.theme`) | form: select + toggle (+ color) | device |
| BLUR | UI pref `BLUR_PREF_KEY` | Infantry_Appearance | form: toggle | device |
| USER_PORTRAIT | daemon setting `USER_PORTRAIT` (PNG) | Infantry_Appearance | `block` (image picker) | daemon |
| MARKDOWN_ENGINE | UI pref `CHAT_MARKDOWN_PREF_KEY` | **Infantry_Chat** (node of the chat renderer) | form: radio | device |
| REASONING | UI pref `REASONING_PLACEMENT_PREF_KEY` | Infantry_Chat | form: radio (`panel`/`inline`) | device |
| TAB_GROUPS | node_config `children.grouped` (Infantry_Settings already declares it) | **Infantry_Settings** (move the entry onto its existing declaration) | form: toggle | user |
| AGENT_SCOPE | UI state | **core manifest** (shell: which agents the sidebar lists) | form: radio | device |
| PROGRESS_STALE | UI pref `PROGRESS_STALE_PREF_KEY` | **Infantry_Agents** (the progress bar lives with agent cards) | form: number (min) | device |
| LIFECYCLE | daemon `keepAlive.minutes` | **core manifest** with `scope:"daemon"` (process lifetime is the daemon's) | form: number | daemon |
| CLI_LOCATIONS | daemon `cliLocations` + harness install | **Infantry_Agents** | `block` (install log, browse) | daemon |
| TOOL_APPROVAL | daemon `toolApproval`, `prompts.timeoutSec`, `prompts.onTimeout` | **Infantry_Approvals** | form: radio + number + radio | daemon |

Core manifest = `CoreConfig.JSON` extended: it holds only nodes the shell itself owns (agent scope, keep-alive, the nine
trust sections). Everything with a plugin that can own it goes to that plugin (D4/D8). Open question 2 covers Appearance.

## 6. Daemon / remote sections and wire frames

**Model.** The daemon is a host too. It reads a `config` list with the same schema from its own plugin manifests
(`daemon` section of a module.json: `"daemon": { "config": [ ... ] }`), so a module that ships a UI half and a daemon half
declares both in one file. The UI never trusts its own copy of a daemon field's schema: it takes the daemon's.

```jsonc
"daemon": { "config": [{ "node": "daemon.approvals", "tab": "Agents", "group": "Tool approval", "order": 30, "fields": [
  { "key": "prompts.timeoutSec", "type": "number", "label": "Prompt timer", "default": 0, "min": 0, "max": 86400, "unit": "s" },
  { "key": "prompts.onTimeout",  "type": "radio",  "label": "On timeout", "default": "deny", "options": ["deny","approve"] } ] }] }
```
Only `scope:"daemon"` fields come from the daemon. The daemon also owns validation for them: `SettingsService.set` keeps its
per-key `when` for the keys it has today and **falls back to the declared field's validator** for keys declared by a plugin
(replacing "unknown setting" with a lookup in the declared tree). Phase 5 deletes the `when` once each key is declared.

**Frames** (same channel as the existing `settings`/`settings_set`; contract bump additive, e.g. `1.1xx`):

```
-> client  config_schema   { rev, declarations:[ConfigDeclaration...] }   sent on connect and when a daemon plugin loads/unloads
-> client  config_values   { rev, rows:[NodeConfigEntry...] }              resolved layer 5+6 values (the node_config + settings)
<- server  config_set      { node, key, value|null, expectRev }           validated by the declared type; reply = config_values
-> client  config_changed  { node, key, value, by }                       push to every other device (live injection, section 3)
```
HTTP twin for clients without the socket: `GET /config/schema`, `GET /node-config` (built), `PUT /node-config` (built).
`expectRev` rejects a stale write (the dialog refetches). A daemon older than the contract has no `config_schema`: the dialog
falls back to the hand-written daemon sections (feature gate by contract, the same `contractAtLeast` used today). Remote
daemons are the common case: the client merges `config_schema` into the tree **after** local modules, marking each field
`origin: daemon`. Secrets never travel as fields.

## 7. wasm and Android (compile-time list, D9)

One format, three platforms (6s G already does this for `CoreConfig`): the module list generated at build time includes each
plugin's `module.json`, and the build step writes `GeneratedConfig.JSON` (the concatenation of each module's `config`) into
common source, read by the same `ConfigTree.merge`. Desktop reads the same text from the jar at run time. No platform parses
config differently. `scope:"device"` uses `saveUiPref` on all three (browser local storage on wasm, SharedPreferences on
Android). Platform filter: a field/group may say `"platforms": ["desktop"]` (as module.json does) and is dropped elsewhere.
Demo build (D16): demo plugins ship their own `config` defaults, which override live components' defaults by the usual
module load order.

## 8. Phased plan (each phase = one ticket; one worker at a time, D7)

| Phase | Ticket | Scope | Files |
|---|---|---|---|
| 1 | CONFIG-2 **pilot: schema + Appearance tab + Theme block** | Add `tab/order/title/applies/keywords/description` and `toggle/radio/color` types; stop persisting seed rows (+ migration); `BlockScope.config()` and `ConfigValues.color/classes/obj`; new official `Infantry_Appearance` with `ThemeBlock`; Settings dialog builds the **Appearance** tab from config (Theme + Blur), other tabs unchanged | `agenthqPlugins/.../api/NodeConfig.kt`, `ui/plugin/config/{ConfigTree,ResolvedConfig,NodeFields,CoreConfig}.kt`, `ui/ConfigDialog.kt`, `ui/ConfigTabs.kt`, `ui/shell/blocks/ShellConfig.kt`, `agentHq/daemon/NodeConfigHttp.kt`, new `plugins/official/Infantry_Appearance/{module.json,src}`, tests `NodeConfigTest`, `RegistrySectionUiTest`, a new dialog UI test |
| 2 | CONFIG-3 device scope + migrate prefs | `scope:device` store over `saveUiPref`; one-time read of `THEME/BLUR/MARKDOWN/REASONING/PROGRESS_STALE` pref keys | `ui/shell/PlatformHost.kt`, `ui/shell/blocks/ShellConfig.kt` (the `onFlags` plumbing shrinks), `ui/model/*Setting*.kt` |
| 3 | CONFIG-4 whole-dialog generation | all tabs from config; `ConfigEntry`/`ConfigTab` become the default tab order only; `block` escape hatch for custom sections; search over `description/keywords`; `fieldTypes` registrar API | `ConfigDialog.kt`, `ConfigTabs.kt`, `TabFactory.kt` (`UiRegistrar.fieldType`), `ShellConfigSettings.kt` (deleted) |
| 4 | CONFIG-5 move the 11 sections | per section 5: Infantry_Chat (markdown, reasoning), Infantry_Settings (tab groups), Infantry_Agents (progress stale, CLI locations), Infantry_Approvals (tool approval), core manifest (agent scope, lifecycle); Acme_Hello `greeting` as the community proof | the owning `plugins/official/*/module.json` + their plugin classes; `CoreConfig.kt` |
| 5 | CONFIG-6 daemon sections + frames | `daemon.config` in module.json, `config_schema/values/set/changed`, `SettingsService` falls back to declared validator, contract bump | `agentHq/settings/SettingsService.kt`, `agentHq/daemon/NodeConfigHttp.kt`, `DaemonMain.kt`, `ui/model/*Setting.kt`, `docs/` contract |
| 6 | CONFIG-7 wasm/Android generation + classes | `GeneratedConfig.JSON` from the module list; `classes` type; per-platform pass | the module-list Gradle task, `agentHqUi` wasm/android source sets |

Each phase ships with the default 32 shots unchanged (the generated card must match the old section pixel for pixel or the
baseline is updated deliberately in that ticket).

## Open questions for the owner

1. **Tab vs group meaning.** Today `group` is the Plugins-view tab. I propose `tab` (new) = dialog tab and `group` = card, with
   old behaviour when `tab` is absent. OK?
2. **Is Appearance a plugin?** D4 says everything but shell + config + orchestration is a plugin, so Theme goes to a new
   official `Infantry_Appearance`. But a blank shell with no theme plugin then has no Appearance tab and the default palette.
   Accept, or keep theme in core?
3. **Seed rows.** Drop them (defaults computed, only user edits stored) so plugin updates can change defaults; the cost is a
   one-time migration. OK? (Alternative: keep seeding and add a `seedVersion`, which is more state to get wrong.)
4. **Scopes.** `device | user | daemon` as above. Should "user" (follows the user across devices via the daemon) exist now, or
   everything device-or-daemon until the account model exists?
5. **Classes.** Named style tokens with an author-owned allow-list (section 1c), not free CSS-like strings. Enough for "override
   styles", or do you want numeric/color style overrides per slot (a `style` type) in v1?
6. **Community config reach.** A community module may declare config only on its own nodes. Should it be allowed to add a
   field to an official node (e.g. a Theme option), under `allowInject` as for blocks?
7. **Remote daemon schema trust.** The client renders whatever `config_schema` a paired daemon sends. OK for a paired daemon,
   or should daemon fields from an unsigned plugin render read-only?

## Files read

`plugins/official/Infantry_Alerts/{module.json,src/AlertsModule.kt}`, `plugins/community/Acme_Echo/module.json`,
`plugins/community/Acme_Hello/{module.json,src/.../HelloPlugin.kt}`, `plugins/official/Infantry_{Settings,Stats}/module.json`,
`agenthqUiCore/.../ui/{ConfigTabs.kt (ConfigEntry/ConfigTab), shell/blocks/ShellConfig.kt, shell/blocks/ShellConfigSettings.kt,
model/PromptTimerSetting.kt, model/ThemeSettings.kt, plugin/TabFactory.kt (UiRegistrar), plugin/config/{ConfigTree,ResolvedConfig,
CoreConfig}.kt}`, `agenthqPlugins/.../plugin/api/{NodeConfig.kt,ModuleManifest.kt}`, `agentHq/.../settings/SettingsService.kt`
(`set`), `agentHq/.../daemon/NodeConfigHttp.kt` (signatures), `docs/{BLOCKS-LAYOUT-DESIGN.md (6s),RUNTIME-PLUGINS-AUDIT.md,
EMPTY-SHELL-DESIGN.md (headings),PLUGIN-ENDPOINTS-DESIGN.md (D1)}`. Not read in full: `ConfigDialog.kt`, `NodeFields.kt`,
`NodeConfigState.kt` bodies (signatures only).
