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
        .args(["build", "-p", "infantry-mcp-daemon", "--lib", "--target", "wasm32-wasip2", "--target-dir"])
        .arg(&target)
        .current_dir(root)
        .status()
        .expect("cargo build");
    assert!(status.success(), "building the wasm32-wasip2 component failed");
    target.join("wasm32-wasip2/debug/infantry_mcp_daemon.wasm")
}

fn module_dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("infantry-mcp-daemon-host-{tag}-{}", std::process::id()));
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
fn loads_and_lists_through_the_frames_grant() {
    let (_h, p, dir) = load(MemoryServices::new().with_frames(|_, f| {
        assert_eq!(f.r#type, "mcp_list");
        Ok(infantry_plugin_wit::jsonl::Frame { r#type: "mcp".into(), data: r#"{"servers":[{"name":"pw","target":"infantry","source":"app","state":"ok"}]}"#.into(), session_id: None, request_id: None })
    }));
    let out = p.call_tool("infantry_mcp", r#"{"op":"list"}"#, &CallContext::default()).expect("call");
    assert_eq!(out, "pw  infantry/app  ok");
    let _ = std::fs::remove_dir_all(dir);
}
