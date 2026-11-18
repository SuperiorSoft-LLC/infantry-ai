// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The built component, loaded by the real plugin host (light: one load, one tool call).
use infantry_plugin_host::{CallContext, HostLimits, MemoryServices, PluginHost, Tier};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;

fn build_component() -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let target = root.join("../../../../target-wasm-test");
    let status = Command::new(env!("CARGO"))
        .args(["build", "-p", "infantry-git-daemon", "--lib", "--target", "wasm32-wasip2", "--target-dir"])
        .arg(&target)
        .current_dir(root)
        .status()
        .expect("cargo build");
    assert!(status.success(), "building the wasm32-wasip2 component failed");
    target.join("wasm32-wasip2/debug/infantry_git_daemon.wasm")
}

fn module_dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("infantry-git-daemon-host-{tag}-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::copy(Path::new(env!("CARGO_MANIFEST_DIR")).join("../module.json"), dir.join("module.json")).unwrap();
    std::fs::copy(build_component(), dir.join("daemon.component")).unwrap();
    dir
}

#[allow(dead_code)]
fn load(services: MemoryServices) -> (PluginHost, Arc<infantry_plugin_host::LoadedPlugin>, PathBuf) {
    let host = PluginHost::new(Arc::new(services), HostLimits::default()).unwrap();
    let dir = module_dir("t");
    let p = host.load_as(&dir, Tier::Official).expect("loads");
    (host, p, dir)
}

#[test]
fn loads_and_registers_no_tools() {
    let (h, p, dir) = load(MemoryServices::new());
    assert!(p.call_tool("anything", "{}", &CallContext::default()).is_err());
    assert!(h.tools().is_empty());
    let _ = std::fs::remove_dir_all(dir);
}
