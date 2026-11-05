[← Wiki index](../index.md)

# Recipe: add a setting with scopes

A value can live at four scopes; the most specific one that holds a value wins:
**session > role (in a project) > project > app > built-in default** (`SettingScope` in
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/ScopedSettings.kt`). There are
two kinds of setting, and they live in different places.

## A. A plugin setting (the common case)

A plugin declares its fields as data in `module.json`; the Settings tab draws the card with no UI code from you.

### Files to touch

```
plugins/community/Acme_Notes/module.json            add or extend a "config" entry
plugins/community/Acme_Notes/src/main/kotlin/com/acme/notes/NotesPlugin.kt   read it in the UI (optional)
plugins/community/Acme_Notes/daemon/src/main/kotlin/com/acme/notes/daemon/NotesDaemon.kt   read it in the daemon (optional)
```

### Steps

1. Add a field to a `config` entry (this is the shipped example in [Make a plugin](../make-a-plugin.md) section 11):

```json
"config": [
  { "node": "acme.notes", "group": "Notes", "tab": "Plugins", "order": 910, "title": "Notes (Acme_Notes)",
    "fields": [
      { "key": "heading", "type": "text", "label": "Heading", "default": "My notes", "order": 1 },
      { "key": "maxLines", "type": "number", "label": "Max lines", "default": 20, "min": 1, "max": 200, "scope": "daemon", "order": 2 }
    ] }
]
```

   `type` is one of the built-ins in `ConfigFieldTypes` (`text`, `textarea`, `number`, `checkbox`, `select`, `radio`,
   `multiSelect`, `color`, `list`, `group`, `path`, `file`, ...). The field's `scope` says where the user's override lives: `device`
   (this host's UI preferences), `daemon` (stored by the daemon, so the daemon half can read it), or none (the default
   `node_config` rows). Every field key is documented in [Make a plugin](../make-a-plugin.md), section 3 (`config`).
2. Read it in the UI half with `context.config("acme.notes")` (`config.string("heading")`, `config.boolean("shout")`).
3. Read it in the daemon half with `api.config("maxLines")`. It returns a `String?`; only `daemon`-scoped fields are
   served, and the value is the user's stored one or your `default`.
4. If your plugin keeps a Kotlin copy of its config, add a test that fails when the copy differs from `module.json`
   (the pattern is `AcmeHelloConfigTest`, described in [Make a plugin](../make-a-plugin.md), section 3).

## B. A core setting that works per project, role and session

Only a generic mechanism belongs in core (rule 1 of the repository root file
`AGENTS.md`). A core key is **app-only** unless it is listed in `SettingScopes`; setting an unlisted key at another
scope is refused with the allowed list.

### Files to touch

```
superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/SettingsService.kt
superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/ScopedSettings.kt
```

### Steps (follow `INCLUDE_SKILLS_CONTEXT`, a boolean, through the same two files)

1. `SettingsService.kt`: add the key constant next to the others (`const val INCLUDE_SKILLS_CONTEXT = "context.includeSkillsContext"`
   is the model), a validating branch in the value check that rejects a bad value (`requireBoolean`, or
   `requireKeepAliveMinutes` for a bounded number), the typed getter that reads the stored value or returns the
   default, and the entry in the snapshot map of defaults.
2. `ScopedSettings.kt`: add the key to the `specs` map in `SettingScopes` with its allowed scopes, one line of `doc`
   and, if its non-default value costs something (tokens, RAM), a `cost` text. **The default must be the cheapest
   value**; the costlier one is an opt-in whose cost is stated.
3. A numeric limit also goes in `LimitRegistry.kt` (same folder): `LimitsGuardTest` fails on an unnamed numeric limit
   in `src/main`.
4. Test through the CLI shape: `infantry settings set <key> <value> --scope project --project <id>` then
   `infantry settings explain <key> --project <id>` prints the winner and every layer that holds a value.

## Check

```
./gradlew :superiorSoftLibrary:infantry:test --tests "com.superiorsoftllc.infantry.settings.LimitsGuardTest" --console=plain
```

## Related

[Settings](../Settings.md) (scopes, cost-saving defaults), [Daemon plugin API](../daemon-plugin-api.md) section 7.
