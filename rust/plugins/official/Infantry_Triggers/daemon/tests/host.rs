// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The built component, loaded by the real plugin host (light: save + list over frames, one signed webhook).
use infantry_plugin_host::{Caller, HostLimits, MemoryServices, PluginHost, RouteRequest, Tier};
use infantry_plugin_wit::jsonl::Frame;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;

fn build_component() -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let target = root.join("../../../../target-wasm-test");
    let status = Command::new(env!("CARGO"))
        .args(["build", "-p", "infantry-triggers-daemon", "--lib", "--target", "wasm32-wasip2", "--target-dir"])
        .arg(&target)
        .current_dir(root)
        .status()
        .expect("cargo build");
    assert!(status.success(), "building the wasm32-wasip2 component failed");
    target.join("wasm32-wasip2/debug/infantry_triggers_daemon.wasm")
}

fn frame(ty: &str, data: &str) -> Frame {
    Frame { r#type: ty.into(), data: data.into(), session_id: None, request_id: Some("r1".into()) }
}

#[test]
fn saves_lists_and_admits_a_signed_webhook() {
    let dir = std::env::temp_dir().join(format!("infantry-triggers-daemon-host-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::copy(Path::new(env!("CARGO_MANIFEST_DIR")).join("../module.json"), dir.join("module.json")).unwrap();
    std::fs::copy(build_component(), dir.join("daemon.component")).unwrap();
    // The daemon as the plugin's ports see it: no plugin catalog, nothing startable on the board.
    let services = MemoryServices::new().with_frames(|_, f| match f.r#type.as_str() {
        "plugins_op" => Ok(frame("plugins", "[]")),
        other => Err(format!("{other} refused: nothing startable")),
    });
    let host = PluginHost::new(Arc::new(services), HostLimits::default()).unwrap();
    let p = host.load_as(&dir, Tier::Official).expect("loads");
    let owner = Caller { owner: true, ..Default::default() };
    let secret = "k".repeat(40);
    let save = format!(r#"{{"name":"hook","kind":"webhook","enabled":true,"coalesceMs":0,"webhookSecret":"{secret}","action":{{"kind":"start_next"}}}}"#);
    assert!(p.handle_frame(&frame("trigger_save", &save), &owner).expect("save").is_none(), "a save is answered by the push");
    let list = p.handle_frame(&frame("trigger_list", ""), &owner).expect("list").expect("a reply");
    assert_eq!(list.r#type, "triggers");
    let v: serde_json::Value = serde_json::from_str(&list.data).unwrap();
    let id = v[0]["id"].as_str().unwrap().to_string();
    assert!(!list.data.contains(&secret), "the secret never leaves the store");

    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64;
    let body = br#"{"ping":1}"#.to_vec();
    let sig = infantry_triggers_daemon::webhook::sign(&secret, ts, "d-1", &body);
    let req = |sig: &str| RouteRequest {
        method: "POST".into(),
        path: format!("/hooks/{id}"),
        query: String::new(),
        headers: vec![("X-Infantry-Signature".into(), sig.into()), ("X-Infantry-Timestamp".into(), ts.to_string()), ("X-Infantry-Delivery".into(), "d-1".into())],
        body: body.clone(),
    };
    assert_eq!(p.handle_route(&req("v1=00")).expect("route").status, 401);
    let ok = p.handle_route(&req(&sig)).expect("route");
    assert_eq!(ok.status, 200);
    assert!(String::from_utf8_lossy(&ok.body).contains("nothing_to_start"));
    let _ = std::fs::remove_dir_all(dir);
}
