// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI scripting (platform layer): run the owner's Lua scripts daemon-side, no model, no tokens.
//!
//! * [`lua::LuaEngine`]: a fresh sandboxed Lua 5.2 state per run (mlua, vendored) with an instruction budget,
//!   a wall-clock deadline and a heap cap. A script only QUEUES actions (`ih.call`, `ih.<alias>`).
//! * [`dispatcher::ScriptDispatcher`]: runs the queued actions in order through a [`host::ScriptHost`] (the daemon
//!   wires its tool registry; tests use [`host::FakeHost`]), with the two-phase `ih.again()` re-run.
//! * [`hooks::ScriptHooks`]: hooks on infantry_core's event bus, `mail.incoming` verdicts, `every <N>s` timers on tokio.
//! * [`exec::ExecTools`]: the process bridge. [`store::ScriptStore`]: project / global / plugin scripts and the
//!   bundled Script Arsenal. [`runlog::ScriptRunLog`]: runs and stats. [`tools::ScriptingTools`]: the tool surface.
//!
//! LuaJ compatibility (the scripts were written against LuaJ 3 and must run unchanged; only what they need):
//! * numbers enter Lua as doubles (an integral JSON number reads as an integer-valued number);
//! * a number leaving Lua that is integral and below 1e15 becomes a JSON integer; a numeric-looking string stays a string;
//! * a table is a JSON array when `#t > 0` and `#t` equals its key count, else an object (`{}` is an object);
//! * `ih.exec "cmd"` / `ih.cli "cmd"` (one string) mean `{command = "cmd"}`;
//! * `string.rep` refuses a result over a quarter of the heap cap (LuaJ died on the allocation instead);
//! * globals: base, string, table, math, plus `os.time` / `os.clock` only (no io, debug, package, load*, coroutine, bit32).
//!
//! Deliberate differences: the heap cap is on the live heap (LuaJ counted cumulative allocation); `print` writes to the
//! run log; `pairs` order is Lua 5.2's, so a script that builds text by iterating a table (usage-governor's `enc`)
//! may order keys differently.
pub mod dispatcher;
pub mod engine;
pub mod exec;
pub mod hooks;
pub mod host;
pub mod lua;
pub mod runlog;
pub mod store;
pub mod tools;
