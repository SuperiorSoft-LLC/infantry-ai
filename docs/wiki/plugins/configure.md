# Configure a plugin

A plugin has two kinds of settings: the ones it **declares** (the owner changes them in Settings) and the ones it
**keeps** for itself (the `settings` import).

Truth: `rust/crates/infantry-plugin-host/src/imports.rs` (`config_get`, `setting_get`),
`rust/crates/infantry-daemon/src/compose/config.rs` (the Settings cards).

## Declared settings: `daemon.config`

Two shapes are accepted. Flat, one entry per key:

```json
"config": [ { "key": "hello.greeting", "scope": "daemon", "default": "Hello" } ]
```

Grouped, one Settings card with several fields:

```json
"config": [
  {
    "node": "acme.hello", "group": "Acme", "title": "Hello", "tab": "Project", "order": 900,
    "fields": [
      { "key": "hello.greeting", "scope": "daemon", "default": "Hello" },
      { "key": "hello.loud", "scope": "daemon", "default": false }
    ]
  }
]
```

The app's Settings dialog draws these cards (frames `config_schema` and `config_values`) and writes one key with
`config_set`. A flat entry becomes a one-field card of its module. The stored value is the app-scope row of the
daemon's settings, the same row `settings_get` / `settings_set` read and write.

The plugin reads a declared key with the always-linked `config` import:

```rust
let greeting = infantry::plugin::config::get("hello.greeting"); // Option<String>
```

`config::get` answers, in order:

1. the value the owner stored for the key, if the plugin declares the key (a plugin never reads a key it did not declare);
2. else the declared `default` of a `"scope": "daemon"` field (numbers and booleans come back as their JSON text, `6`, `false`);
3. else `None`.

The other `config` functions: `module-id`, `tier` (`core`, `official` or `community`), `api-version` (the ABI the host
speaks, `0.1.0`), `environment` (`prod`, `staging` or `dev`, see [Endpoints](endpoints.md)) and `endpoint(name)`.

## The plugin's own values: the `settings` import

List `settings` in `daemon.needs` or `daemon.wants`, then:

```rust
use infantry::plugin::settings;
settings::set("last-run", "2026-11-26")?;       // stored as "<Module>.last-run"
let v = settings::get("last-run");                // Option<String>
settings::remove("last-run")?;
```

Keys are bare; the host stores them as `<ModuleName>.<key>` in `plugin-settings.json` in the state dir. A key may not be
empty or contain whitespace. `settings::get` falls back to the owner's stored value for a key the plugin declares in
`daemon.config`.

## Limits are settings too

Timeouts and budgets are data, not code: `daemon.limits` (`callTimeoutMs`, `toolTimeoutMs`, `cpuMsPerCall`,
`routeRequestsPerMinute`, `memoryMbHint`) and a per-tool or per-frame `timeoutMs`. A declared timeout is capped by the
daemon setting `limits.plugins.maxDeclaredTimeoutMs`. With none declared, the host default applies
(`limits.plugins.toolTimeoutMs` for tools, 10 s for frames).

## Default plugin sources

`rust/plugins/default-sources.json` names the sources a host starts with (today the official repo,
https://github.com/SuperiorSoft-LLC/infantry-plugins, labelled `Official`). `stage.sh` copies it into the staged plugins
folder; at start the daemon adds each location once, off the boot path (`INFANTRY_DEFAULT_SOURCES` names another file).
The seeded locations are kept in `plugin-sources-seeded.json`, so a default the user removed is not added back; one that
could not be added (offline) is tried again at the next start. The Sources tab shows the label as a badge and asks before
removing a labelled source. Add a default source in that file, not in code.
