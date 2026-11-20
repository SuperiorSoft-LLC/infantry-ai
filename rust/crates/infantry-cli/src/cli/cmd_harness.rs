// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry harness-status` and `infantry harness-install [id...] [--update] [--runtime]` (Kotlin `HarnessInstallCli`):
//! the daemon's agent-CLI install report, and an install that prints the daemon's log lines until it ends. The install
//! itself runs in the daemon (`harness_install`); this only starts it and follows `harness_install_status`.
//! Exit 1 when a line says `<id>: failed - ...`.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};
use std::time::{Duration, Instant};

/// How long an install may run before this command gives up following it (the daemon keeps going).
const FOLLOW_LIMIT: Duration = Duration::from_secs(20 * 60);
const POLL: Duration = Duration::from_millis(1500);

fn no_extra(p: &Parsed, _: Getenv) -> Option<String> {
    let _ = p;
    None
}

fn status(ctx: &mut Ctx) -> Result<Value, String> {
    let ans = ctx.stream().request(&Envelope::new("harness_install_status", ""), "harness_install_status", "harness_install_status", true)?;
    serde_json::from_str(&ans.data).map_err(|e| format!("unreadable install report: {e}"))
}

fn print_report(ctx: &mut Ctx, r: &Value) {
    for h in r["harnesses"].as_array().into_iter().flatten() {
        let v = h["installedVersion"].as_str().map(|v| format!(" {v}")).unwrap_or_default();
        ctx.out(format!("{:<10} {}{}  {}", h["id"].as_str().unwrap_or(""), h["state"].as_str().unwrap_or(""), v, h["path"].as_str().unwrap_or("")));
    }
    ctx.out(format!("npm: {}", r["npmPath"].as_str().unwrap_or("not found")));
    ctx.out(format!("prefix: {}", r["prefixDir"].as_str().unwrap_or("")));
    if let Some(g) = r["guidance"].as_str() {
        ctx.out(g.to_string());
    }
}

fn run_status(ctx: &mut Ctx, _: &Parsed) -> CmdResult {
    let r = status(ctx)?;
    print_report(ctx, &r);
    Ok(0)
}

fn run_install(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let before = status(ctx)?["log"].as_array().map_or(0, Vec::len);
    let body = json!({ "ids": p.args, "update": p.flag("update"), "runtime": p.flag("runtime") });
    ctx.stream().request(&Envelope::new("harness_install", &body.to_string()), "harness_install_result", "harness_install", false)?;
    let start = Instant::now();
    let (mut printed, mut failed) = (before, false);
    loop {
        let r = status(ctx)?;
        let log: Vec<String> = r["log"].as_array().into_iter().flatten().filter_map(|l| l.as_str().map(String::from)).collect();
        // The daemon's log keeps its last 200 lines; a shorter log than `printed` means older lines rolled off.
        printed = printed.min(log.len());
        for l in &log[printed..] {
            failed |= l.contains(": failed - ");
            ctx.out(l.clone());
        }
        printed = log.len();
        if !r["busy"].as_bool().unwrap_or(false) {
            print_report(ctx, &r);
            return Ok(i32::from(failed));
        }
        if start.elapsed() > FOLLOW_LIMIT {
            return Err("the install is still running; it continues in the daemon (check with `infantry harness-status`)".into());
        }
        std::thread::sleep(POLL);
    }
}

pub static HARNESS_STATUS: Command = Command { name: "harness-status", needs: Needs::Stream, validate: no_extra, run: run_status };
pub static HARNESS_INSTALL: Command = Command { name: "harness-install", needs: Needs::Stream, validate: no_extra, run: run_install };
