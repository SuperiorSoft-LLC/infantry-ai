# Plugin secrets

A plugin never keeps an API key in `module.json`, in its settings or in its storage. Secrets live in the daemon's vault
(`infantry-vault`), one vault entry per plugin, named `plugin.<module id in lower case>` (`plugin.acme_hello`).

Truth: `rust/crates/infantry-vault/src/handler.rs` (`plugin_secret_*`),
`rust/crates/infantry-daemon/src/compose/scope.rs` (`own_secret_request`),
`rust/crates/infantry-daemon/src/compose/plugins.rs` (the `frames` import),
`rust/crates/infantry-plugin-host/src/imports.rs` (`daemon.secrets`).

## Reading and writing from the daemon half

Put `frames` in `daemon.needs`. Then send the vault frames through the `frames` import:

| Frame | Data | Answer (`plugin_secret`) |
|---|---|---|
| `plugin_secret_get` | `{"field": "API_KEY"}` | `{"module", "field", "value"}` (`value` is null when unset) |
| `plugin_secret_set` | `{"field": "API_KEY", "value": "..."}` | `{"module", "field"}`, never the value |
| `plugin_secret_clear` | `{"field": "API_KEY"}` | `{"module", "field"}` |

```rust
use infantry::plugin::frames;
let reply = frames::request("plugin_secret_get", r#"{"field":"API_KEY"}"#, None, 5000)?;
let v: serde_json::Value = serde_json::from_str(&reply.data).unwrap_or_default();
let key = v.get("value").and_then(|x| x.as_str());
```

The daemon fills in `module` itself: a plugin reaches **only its own** entry. Naming another module is refused
(`a plugin reaches only its own secrets (<Module>), not those of <Other>`). These three frames are sent with the
vault's owner authority even when the plugin has no `ownerFrames`.

Refused as well:

- a field whose name starts with `INFANTRY_` (never read or written by a plugin);
- every call while a restricted policy locks the secrets.

## Setting a secret as the owner

The owner stores the value in the app's vault (Settings, Secrets) under the entry `plugin.<module>`, or the plugin
asks for it in its own UI and sends `plugin_secret_set`. Agents and scripts see secret names, never values.

## `daemon.secrets`

`module.json` may list `daemon.secrets`: the vault fields the plugin uses (`["API_KEY"]`). When the list is not empty,
`plugin_secret_get`, `_set` and `_clear` of a field it does not name are refused
(`plugin_secret_get refused: field 'X' is not in daemon.secrets of <Module> (API_KEY)`). An empty or missing list
leaves the field free. The rules above apply either way. Truth: `undeclared_secret` in
`rust/crates/infantry-plugin-host/src/imports.rs`.

## Sidecars

A native sidecar starts with a scrubbed environment: no daemon token, no daemon URL. It gets only the variables named
in `daemon.env` that are set in the daemon's own environment (an `INFANTRY_<X>` name falls back to the old
`AGENTHQ_<X>`). Use the vault frames above for secrets, not `daemon.env`.
