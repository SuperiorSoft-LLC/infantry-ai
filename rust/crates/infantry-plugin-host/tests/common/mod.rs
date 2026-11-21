// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Builds the test guests (once per test binary) and lays out module folders in a temp dir.
#![allow(dead_code)]
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Mutex, OnceLock};

/// `rust/` (the workspace root).
pub fn workspace() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").canonicalize().expect("workspace root")
}

/// The guests' own target dir (not the workspace's, so the build never waits on the outer cargo's lock).
fn guest_target() -> PathBuf {
    workspace().join("target").join("plugin-guests")
}

static BUILD: Mutex<()> = Mutex::new(());

fn cargo() -> Command {
    let mut c = Command::new(std::env::var("CARGO").unwrap_or_else(|_| "cargo".into()));
    c.env("CARGO_TARGET_DIR", guest_target()).env_remove("CARGO_ENCODED_RUSTFLAGS").env_remove("RUSTFLAGS");
    c
}

/// Whether the wasm32-wasip2 target is installed (tests that need it skip with a clear message otherwise).
pub fn wasip2_ready() -> bool {
    static READY: OnceLock<bool> = OnceLock::new();
    *READY.get_or_init(|| {
        let out = Command::new("rustup").args(["target", "list", "--installed"]).output();
        match out {
            Ok(o) if String::from_utf8_lossy(&o.stdout).lines().any(|l| l.trim() == "wasm32-wasip2") => true,
            _ => {
                eprintln!("SKIP: the wasm32-wasip2 target is not installed (rustup target add wasm32-wasip2)");
                false
            }
        }
    })
}

fn run(mut c: Command, what: &str) -> Result<(), String> {
    let out = c.output().map_err(|e| format!("{what}: {e}"))?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        let tail: Vec<&str> = err.lines().rev().take(20).collect();
        return Err(format!("{what} failed:\n{}", tail.into_iter().rev().collect::<Vec<_>>().join("\n")));
    }
    Ok(())
}

fn cached(cell: &'static OnceLock<Result<PathBuf, String>>, build: impl FnOnce() -> Result<PathBuf, String>) -> Option<PathBuf> {
    if !wasip2_ready() {
        return None;
    }
    let r = cell.get_or_init(|| {
        let _g = BUILD.lock().unwrap_or_else(|e| e.into_inner());
        build()
    });
    match r {
        Ok(p) => Some(p.clone()),
        Err(e) => panic!("{e}"),
    }
}

/// The Infantry_Health component.
pub fn health_wasm() -> Option<PathBuf> {
    static CELL: OnceLock<Result<PathBuf, String>> = OnceLock::new();
    cached(&CELL, || {
        let mut c = cargo();
        c.current_dir(workspace()).args(["build", "--target", "wasm32-wasip2", "--lib", "-p", "infantry-health-daemon"]);
        run(c, "building the Infantry_Health component")?;
        Ok(guest_target().join("wasm32-wasip2/debug/infantry_health_daemon.wasm"))
    })
}

/// The Infantry_Health native sidecar exe.
pub fn health_sidecar() -> PathBuf {
    static CELL: OnceLock<PathBuf> = OnceLock::new();
    CELL.get_or_init(|| {
        let _g = BUILD.lock().unwrap_or_else(|e| e.into_inner());
        let mut c = cargo();
        c.current_dir(workspace()).args(["build", "-p", "infantry-health-daemon", "--bin", "infantry-health-sidecar"]);
        run(c, "building the Infantry_Health sidecar").unwrap_or_else(|e| panic!("{e}"));
        guest_target().join("debug").join(format!("infantry-health-sidecar{}", std::env::consts::EXE_SUFFIX))
    })
    .clone()
}

/// The fixture guest, v1 or v2 (copied apart, both builds write the same file).
pub fn fixture_wasm(v2: bool) -> Option<PathBuf> {
    static V1: OnceLock<Result<PathBuf, String>> = OnceLock::new();
    static V2: OnceLock<Result<PathBuf, String>> = OnceLock::new();
    cached(if v2 { &V2 } else { &V1 }, || {
        let manifest = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixture-guest/Cargo.toml");
        let mut c = cargo();
        c.args(["build", "--target", "wasm32-wasip2", "--manifest-path"]).arg(&manifest);
        if v2 {
            c.args(["--features", "v2"]);
        }
        run(c, "building the fixture guest")?;
        let built = guest_target().join("wasm32-wasip2/debug/infantry_plugin_fixture.wasm");
        let copy = guest_target().join(format!("fixture-{}.wasm", if v2 { "v2" } else { "v1" }));
        std::fs::copy(&built, &copy).map_err(|e| e.to_string())?;
        Ok(copy)
    })
}

/// A temp folder `<root>/official/<name>` with `module.json` and the given files copied in.
pub struct ModuleDir {
    pub root: PathBuf,
    pub dir: PathBuf,
}

impl ModuleDir {
    pub fn new(tag: &str, name: &str, manifest: &serde_json::Value, files: &[(&str, &Path)]) -> ModuleDir {
        let root = std::env::temp_dir().join(format!("infantry-plugin-host-{tag}-{}", std::process::id()));
        let dir = root.join("official").join(name);
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("module.json"), serde_json::to_string_pretty(manifest).unwrap()).unwrap();
        for (rel, src) in files {
            let to = dir.join(rel);
            std::fs::create_dir_all(to.parent().unwrap()).unwrap();
            std::fs::copy(src, &to).unwrap_or_else(|e| panic!("copy {} -> {}: {e}", src.display(), to.display()));
        }
        ModuleDir { root, dir }
    }
}

impl Drop for ModuleDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}
