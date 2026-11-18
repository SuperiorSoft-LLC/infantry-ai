// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Runs a command of the full (JVM) `infantry` CLI for the few things this bridge does not do itself. The launcher is
//! the one the session already names in `INFANTRY_BIN`, else the one beside this binary, else `infantry` on the PATH.

use crate::env::Getenv;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

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

fn command(getenv: Getenv, args: &[String]) -> Command {
    let program = launcher(getenv).map(|p| p.to_string_lossy().to_string()).unwrap_or_else(|| "infantry".into());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let mut c = Command::new("cmd");
        c.arg("/c").arg(program).args(args);
        c.creation_flags(CREATE_NO_WINDOW);
        c
    }
    #[cfg(not(windows))]
    {
        let mut c = Command::new(program);
        c.args(args);
        c
    }
}

/// Runs the launcher with [args]; returns its exit code and stdout followed by stderr. The output goes to temporary
/// files, not pipes: a detached process the command starts may inherit the handles, and a pipe would then never close.
pub fn run_captured(getenv: Getenv, args: &[String]) -> Result<(i32, String), String> {
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
        let status = command(getenv, args)
            .stdin(Stdio::null())
            .stdout(Stdio::from(out))
            .stderr(Stdio::from(err))
            .status()
            .map_err(|e| e.to_string())?;
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
    let me = std::env::current_exe().ok().and_then(|p| std::fs::canonicalize(p).ok());
    if let (Some(me), Some(l)) = (&me, launcher(getenv).and_then(|l| std::fs::canonicalize(l).ok())) {
        if me == &l {
            return Err("the launcher found is this binary".into());
        }
    }
    let status = command(getenv, args).status().map_err(|e| e.to_string())?;
    Ok(status.code().unwrap_or(1))
}
