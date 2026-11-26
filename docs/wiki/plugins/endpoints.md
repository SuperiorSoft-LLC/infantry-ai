# Plugin endpoints: prod, staging, dev

A plugin that talks to a server names that server's address per build environment in `daemon.endpoints`. The
daemon picks the address for the environment it runs in, and the `http` import refuses every other origin.

Truth: `rust/crates/infantry-plugin-host/src/manifest.rs` (`endpoint`), `rust/crates/infantry-plugin-host/src/host.rs`
(the load checks), `rust/crates/infantry-daemon/src/build_env.rs` (the environment), `rust/plugins/stage-external.sh`,
and in the infantry-plugins repository `official/Infantry_Sites/stage.sh`.

## Declaring them

```json
"daemon": {
  "needs": ["http"],
  "endpoints": {
    "api":  { "prod": "https://api.example.com", "staging": "https://staging.example.com", "dev": "http://127.0.0.1:18953" },
    "site": { "prod": "https://example.com" }
  }
}
```

Rules checked at load (a broken one refuses the module):

- every address starts with `http://` or `https://`;
- a `prod` address starts with `https://`.

## Reading them

```rust
let api = infantry::plugin::config::endpoint("api");      // Option<String>
let env = infantry::plugin::config::environment();        // "prod" | "staging" | "dev"
```

`endpoint(name)` answers the running environment's address, and the `prod` address when that environment has none.

## Outbound HTTP

With `http` in `needs` or `wants`, `http::send` sends a request. Only the addresses listed in `daemon.endpoints` (any
environment) are reachable; any other origin is refused. A request with no `timeout-ms` gets the host's default.

## Which environment a daemon runs in

| Build | Environment |
|---|---|
| Built with `INFANTRY_BUILD_ENV=prod` (every release build) | always `prod`; `INFANTRY_ENV` cannot change it |
| Built with `INFANTRY_BUILD_ENV=staging` or `dev` | `INFANTRY_ENV` at start if set, else the baked value |
| Built with neither | `INFANTRY_ENV` at start if set (`prod`, `staging`, `dev`), else `prod` |

A local test daemon runs with `INFANTRY_ENV=staging` (or `dev`). An unknown value is ignored.

## Rendered endpoints in a staged module

Some modules need an absolute address written into their `module.json` itself (Infantry_Sites puts `{api}` and
`{site}` in its account descriptor). Such a module ships its own `stage.sh` that renders them:

```
stage.sh <out-dir> --env prod|staging|dev [--release] [--api <url> --site <url>]
```

`--api` / `--site` point staging and dev (never prod) at a local stack.

`rust/plugins/stage-external.sh` calls that script for each module listed in `rust/plugins/bundled-external.json`,
with `--env` from `INFANTRY_BUILD_ENV` (default `staging`; anything but `prod`, `staging` or `dev` is refused).

## Release builds are prod only

The release workflows set `INFANTRY_BUILD_ENV=prod` for every step that stages plugins (`rust-release.yml` on each
stage step; `os-app-release.yml` at job level). After staging, `scripts/check-staged-endpoints.sh` reads every staged
text file (`module.json`, UI scripts and styles, markdown) and fails the build if one holds `127.0.0.1`, `localhost` or
`(staging)`. The guard `bundled_plugins.rs` in `infantry-guards` fails the tests when a release step stages plugins
without prod or without that check.
