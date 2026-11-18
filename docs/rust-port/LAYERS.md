# Rust port: crate layers

Dependencies point DOWN only. `cargo test -p infantry-guards` enforces this page: the table below is
`rust/crates/infantry-guards/src/layers.rs`, and the two must agree. When you add a crate, add it to both.

## Layer table

| Layer | Crates (tier) | What lives here |
|---|---|---|
| L0 wire | infantry-protocol (0) | `Envelope`, DTOs, frame names. Byte-compatible with Kotlin `infantryProtocol`. |
| L1 platform core | infantry-core (0), infantry-plugin-wit (0), infantry-plugin-host (1), infantry-plugin-sdk (1), infantry-usage (1), infantry-projects (1) | Generic mechanisms: the `FrameUnit` registry, event bus, db handle, settings; the plugin contract and host; usage, limits and pacing (infantry-usage); projects, workspaces, the project git view, worktrees and ticket branches (infantry-projects). |
| L2 runner | infantry-runner-api (0), infantry-runner (1), infantry-runner-local (2), infantry-runner-link (2) | Running processes, locally or on a linked runner. |
| L3 agents / harnesses | infantry-harness (0), infantry-harness-codex (1), infantry-harness-claude (1), infantry-harness-opencode (1), infantry-harness-pi (1) | One agent CLI each. Agents work with no orchestrator. |
| L4 orchestration | infantry-orchestrator-api (0), infantry-board (1) | Board, tickets, pipeline seams. |
| Plugin | anything under `plugins/official/*` (e.g. the Board daemon in `plugins/official/Infantry_Board`), infantry-board-daemon, infantry-news-daemon, infantry-health-daemon | A feature. Talks to the platform only through plugin-wit / plugin-sdk. |
| Top (composition roots) | infantry-daemon, infantry-cli, infantry-migrate, app/src-tauri (`infantry-tauri`) | Wires everything together. May depend on anything. |
| Tooling | infantry-conformance (wire only), infantry-guards (nothing) | Test and tooling crates with an explicit dependency list. |

## Rules

1. A crate depends only on LOWER layers. Inside one layer it may depend on a crate with a lower tier
   (runner-local on runner-api, harness-codex on harness); never on an equal or higher tier.
2. L3 never depends on L4: agents work with no orchestrator.
3. A plugin depends only on infantry-plugin-wit and infantry-plugin-sdk. Nothing but a top crate depends on a plugin.
4. Nothing but a top crate depends on a top or tooling crate.
5. infantry-conformance depends on infantry-protocol only (it is black-box); infantry-guards on no workspace crate.
6. Core names no plugin id: no `Infantry_<Name>` anywhere in `infantry-core` (a guard test greps it).
7. Harness code lives in L3: a crate below L3 holds no `src/<harness>` module (claude, codex, opencode, gemini, deepseek).
8. Every workspace crate is on the table. An unknown crate fails the guard; a crate under `plugins/official/*`
   maps to Plugin automatically.

Only normal and build dependencies are checked. Dev-dependencies are test-only (a crate may use the daemon in
its tests).

## Allowlist (known violations)

| Crate | Path | Why tolerated | Removed by |
|---|---|---|---|
| (none) | | The last entry, infantry-runner `src/claude`, was removed by RUST-HARN-CLAUDE-1: the daemon and the Tauri app both run infantry-harness-claude. | |

Adding to the allowlist needs a reason and the follow-up that removes it.

## The frozen FrameUnit contract (infantry-core::frames)

```rust
pub trait FrameUnit: Send + Sync {
    fn name(&self) -> &'static str;
    fn frames(&self) -> &'static [&'static str];            // frame types this unit owns
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal>;
}
pub struct FrameCtx { pub conn_id: u64, pub owner: bool, pub bus: EventBus }
pub struct Refusal(pub String); // the exact "<type> refused: …" text
```

`Registry::register` maps each frame type to one unit and refuses a second owner (all or nothing).
`Refusal::new(type, reason)` builds the `"<type> refused: <reason>"` text.
