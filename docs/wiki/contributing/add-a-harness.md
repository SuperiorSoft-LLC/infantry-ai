<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a harness (another agent CLI)

A harness teaches the daemon to run one agent CLI. It is a Layer 3 crate that implements the `Harness` trait in
`rust/crates/infantry-harness/src/lib.rs`. The four harnesses that ship (`infantry-harness-claude`, `-codex`,
`-opencode`, `-pi`) are the worked examples; `infantry-harness-pi` is the smallest.

## The contract

| Method | What it answers |
|---|---|
| `id()` | The provider id on the wire (`claude`, `codex`, ...). |
| `capabilities()` | What the CLI supports (`Capabilities`). |
| `models(probe)` | The models it offers on the machine the `Probe` looks at; empty means only the CLI's default. |
| `sign_in_problem(probe)` | Why the CLI cannot run there (not installed, not signed in), or `None`. |
| `launch(req)` | The `LaunchPlan` for a `LaunchRequest`: the command, arguments, environment and a `Translator`. |
| `Translator` | Turns the CLI's output into `HarnessEvent`s, and a prompt or an answer into `Outbound` traffic. |

Optional methods have defaults: `auth_kinds`, `key_env_vars`, `credential` (the API-key variable and the sign-in
command), `assets` (files staged before launch), `commands` (slash commands), `store_stats`, `context_window` and
`context_limit_support` (`supported`, `best_effort` or `unsupported`; the default never claims support).

A `Probe` is the only way a harness looks at the machine (environment, home folder, files). Tests pass a fake probe, so a
harness is tested without the real CLI installed.

## Steps

1. Create `rust/crates/infantry-harness-<name>` (license `Apache-2.0`), depending only on `infantry-harness`.
2. Implement `Harness` and a `Translator`. Spawn nothing yourself: the runner starts the plan through `infantry-proc`
   (guard `no_spawn_bypasses_the_windowless_helper`).
3. Add the crate to the workspace in `rust/Cargo.toml`, to the layer map in
   `rust/crates/infantry-guards/src/layers.rs` (`Layer::Harness`, tier 1) and to the crate lists in
   `rust/crates/infantry-guards/src/features.rs`.
4. Register it in `harness_registry()` in `rust/crates/infantry-distribution/src/lib.rs`.
5. Test: `cargo test -p infantry-harness-<name>` and `cargo test -p infantry-guards`.
6. Try it on a throwaway daemon ([Test daemon](test-daemon.md)) and start a session with that provider.

How users pick a provider and sign in: [Providers and models](../providers-and-models.md).
