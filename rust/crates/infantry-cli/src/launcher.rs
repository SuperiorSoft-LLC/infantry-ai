// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Runs a command of the full (JVM) `infantry` CLI for the few things this bridge does not do itself. The launcher is
//! the one the session already names in `INFANTRY_BIN`, else the one beside this binary, else `infantry` on the PATH.

use crate::env::Getenv;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

fn launcher_in(dir: &Path) -> Option<PathBuf> {
    let names: &[&str] = if cfg!(windows) { &["infantry.cmd", "infantry.bat"] } else { &["infantry"] };
    names.iter().map(|n| dir.join(n)).find(|p| p.is_file())
}

/// The JVM launcher, or None (then `infantry` is looked up on the PATH).
fn launcher(getenv: Getenv) -> Option<PathBuf> {
    if let Some(bin) = getenv("INFANTRY_BIN").filter(|b| !b.trim().is_empty()) {
        if let Some(l) = launcher_in(Path::new(bin.trim())) {
            return Some(l);
        }
    }
    let here = std::env::current_exe().ok()?;
    launcher_in(here.parent()?)
}

/// Set on the child the launcher starts: a launcher that resolves back to this binary would otherwise start itself
/// without end (the 560-process pile-up of 2026-11-19).
const DEPTH_ENV: &str = "INFANTRY_LAUNCHER_DEPTH";

/// `limits.cli.delegateTimeoutMs`: how long a delegated JVM command may run before it is killed with its children.
pub const DELEGATE_TIMEOUT_MS: crate::env::Limit =
    crate::env::Limit { key: "limits.cli.delegateTimeoutMs", default: 120_000, min: 1, max: None, legacy_env: &[] };

fn guard(getenv: Getenv) -> Result<(), String> {
    if getenv(DEPTH_ENV).is_some_and(|v| !v.trim().is_empty()) {
        return Err("this command is not in the native infantry CLI and the launcher it was handed to is this binary again; set INFANTRY_BIN to the folder of the JVM infantry.cmd".into());
    }
    Ok(())
}

/// Waits for [child] at most the delegate timeout; kills it and its process tree on expiry.
fn wait_bounded(getenv: Getenv, mut child: std::process::Child) -> Result<std::process::ExitStatus, String> {
    let ms = DELEGATE_TIMEOUT_MS.from_env(getenv).unwrap_or(DELEGATE_TIMEOUT_MS.default).max(1) as u64;
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(ms);
    loop {
        if let Some(st) = child.try_wait().map_err(|e| e.to_string())? {
            return Ok(st);
        }
        if std::time::Instant::now() >= deadline {
            #[cfg(windows)]
            {
                let _ = infantry_proc::command("taskkill").args(["/PID", &child.id().to_string(), "/T", "/F"]).output();
            }
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("the JVM launcher did not finish within {ms}ms and was killed"));
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

fn command(getenv: Getenv, args: &[String]) -> Command {
    let program = launcher(getenv).map(|p| p.to_string_lossy().to_string()).unwrap_or_else(|| "infantry".into());
    #[cfg(windows)]
    {
        let mut c = infantry_proc::command("cmd");
        c.arg("/c").arg(program).args(args);
        c.env(DEPTH_ENV, "1");
        c
    }
    #[cfg(not(windows))]
    {
        let mut c = infantry_proc::command(program);
        c.args(args);
        c.env(DEPTH_ENV, "1");
        c
    }
}

/// Runs the launcher with [args]; returns its exit code and stdout followed by stderr. The output goes to temporary
/// files, not pipes: a detached process the command starts may inherit the handles, and a pipe would then never close.
pub fn run_captured(getenv: Getenv, args: &[String]) -> Result<(i32, String), String> {
    guard(getenv)?;
    let stamp = format!(
        "infantry-mcp-{}-{}",
        std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0)
    );
    let dir = std::env::temp_dir();
    let out_path = dir.join(format!("{stamp}.out"));
    let err_path = dir.join(format!("{stamp}.err"));
    let result = (|| {
        let out = std::fs::File::create(&out_path).map_err(|e| e.to_string())?;
        let err = std::fs::File::create(&err_path).map_err(|e| e.to_string())?;
        let child = command(getenv, args)
            .stdin(Stdio::null())
            .stdout(Stdio::from(out))
            .stderr(Stdio::from(err))
            .spawn()
            .map_err(|e| e.to_string())?;
        let status = wait_bounded(getenv, child)?;
        let mut text = String::from_utf8_lossy(&std::fs::read(&out_path).unwrap_or_default()).to_string();
        text.push_str(&String::from_utf8_lossy(&std::fs::read(&err_path).unwrap_or_default()));
        Ok((status.code().unwrap_or(-1), text))
    })();
    let _ = std::fs::remove_file(&out_path);
    let _ = std::fs::remove_file(&err_path);
    result
}

/// Runs the launcher with [args] on this process's own stdin / stdout / stderr; returns its exit code. Refuses to run
/// itself (a launcher that resolves to this binary).
pub fn run_inherited(getenv: Getenv, args: &[String]) -> Result<i32, String> {
    guard(getenv)?;
    let me = std::env::current_exe().ok().and_then(|p| std::fs::canonicalize(p).ok());
    if let (Some(me), Some(l)) = (&me, launcher(getenv).and_then(|l| std::fs::canonicalize(l).ok())) {
        if me == &l {
            return Err("the launcher found is this binary".into());
        }
    }
    let child = command(getenv, args).spawn().map_err(|e| e.to_string())?;
    let status = wait_bounded(getenv, child)?;
    Ok(status.code().unwrap_or(1))
}
