// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The plugin host end to end: real components built for wasm32-wasip2 and a real sidecar exe.
mod common;

use common::ModuleDir;
use infantry_core::frames::{Envelope, EventBus, FrameCtx, FrameUnit};
use infantry_plugin_host::unit::PluginFrameUnit;
use infantry_plugin_host::{CallContext, CallError, Frame, HostLimits, MemoryServices, PluginHost};
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A daemon whose `health` frame answers with what it was asked (so the test sees the tool's request).
fn health_services() -> Arc<MemoryServices> {
    Arc::new(MemoryServices::new().with_frames(|_module, f| match f.r#type.as_str() {
        "health" => {
            let req: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
            let data = if req.get("format").and_then(|v| v.as_str()) == Some("text") {
                format!("daemon: up (session {})", req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("-"))
            } else {
                json!({"up": true, "asked": req}).to_string()
            };
            Ok(Frame { r#type: "health".into(), data, session_id: None, request_id: None })
        }
        "slow" => {
            std::thread::sleep(Duration::from_millis(400));
            Ok(Frame { r#type: "slow".into(), ..Default::default() })
        }
        other => Err(format!("no frame {other}")),
    }))
}

fn health_manifest(runtime: Value) -> Value {
    let mut m: Value = serde_json::from_str(&std::fs::read_to_string(common::workspace().join("plugins/official/Infantry_Health/module.json")).unwrap()).unwrap();
    let d = m["daemon"].as_object_mut().unwrap();
    d.remove("component");
    for (k, v) in runtime.as_object().unwrap() {
        d.insert(k.clone(), v.clone());
    }
    m
}

fn fixture_manifest(version: &str, extra: Value) -> Value {
    let tools: Vec<Value> = ["fixture_version", "fixture_spin", "fixture_panic", "fixture_slow", "fixture_storage", "fixture_error"]
        .iter()
        .map(|n| json!({"name": n, "action": format!("test.{n}")}))
        .collect();
    let mut daemon = json!({
        "component": "daemon/fixture.wasm",
        "needs": ["frames", "storage"],
        "limits": {"cpuMsPerCall": 50},
        "tools": tools,
        "frames": [{"type": "fixture_echo", "action": "test.echo"}],
    });
    for (k, v) in extra.as_object().unwrap() {
        daemon[k] = v.clone();
    }
    json!({"name": "Acme_Fixture", "version": version, "daemon": daemon})
}

fn ctx() -> CallContext {
    CallContext { client_session_id: Some("s-1".into()), ..Default::default() }
}

fn assert_health_answers(host: &PluginHost) {
    let text = host.call_tool("infantry_health", "{}", &ctx()).expect("the health tool answers");
    assert_eq!(text, "daemon: up (session s-1)");
    let j: Value = serde_json::from_str(&host.call_tool("infantry_health", r#"{"json":true}"#, &ctx()).unwrap()).unwrap();
    assert_eq!(j["asked"], json!({"sessionId": "s-1"}));
    assert_eq!(host.tools().iter().map(|t| t.decl.name.clone()).collect::<Vec<_>>(), vec!["infantry_health"]);
    let p = host.get("Infantry_Health").unwrap();
    let e = p.call_tool("infantry_other", "{}", &ctx()).unwrap_err();
    assert!(matches!(e, CallError::Refused(ref m) if m.contains("does not declare")), "{e:?}");
}

#[test]
fn health_guest_loads_from_wasm_and_answers_its_tool() {
    let Some(wasm) = common::health_wasm() else { return };
    let m = ModuleDir::new("health-wasm", "Infantry_Health", &health_manifest(json!({"component": "daemon/Infantry_Health.wasm"})), &[("daemon/Infantry_Health.wasm", &wasm)]);
    let host = PluginHost::new(health_services(), HostLimits::default()).unwrap();
    let before = Instant::now();
    let p = host.load(&m.dir).unwrap_or_else(|e| panic!("load: {e}"));
    let load_ms = before.elapsed().as_millis();
    assert_eq!((p.runtime_name(), p.tier), ("wasm", infantry_plugin_host::Tier::Official));
    assert_health_answers(&host);
    let mem = p.memory_bytes().unwrap();
    assert!(mem > 0);
    eprintln!("PERF Infantry_Health wasm: component {} KiB, linear memory {} KiB after 2 calls, load+start {load_ms} ms", std::fs::metadata(&wasm).unwrap().len() / 1024, mem / 1024);
    assert_eq!(host.statuses()["Infantry_Health"], "loaded");
}

#[test]
fn a_sidecar_exe_passes_the_same_tool_test_over_stdio() {
    let exe = common::health_sidecar();
    let exe_name = format!("daemon/infantry-health-sidecar{}", std::env::consts::EXE_SUFFIX);
    let m = ModuleDir::new("health-sidecar", "Infantry_Health", &health_manifest(json!({"sidecar": {"exe": "daemon/infantry-health-sidecar"}})), &[(&exe_name, &exe)]);
    let host = PluginHost::new(health_services(), HostLimits::default()).unwrap();
    let p = host.load(&m.dir).unwrap_or_else(|e| panic!("load: {e}"));
    assert_eq!(p.runtime_name(), "sidecar");
    assert_health_answers(&host);
    assert!(host.unload("Infantry_Health"));
}

#[test]
fn three_runaways_in_a_row_disable_the_plugin_and_the_host_stays_up() {
    let Some(wasm) = common::fixture_wasm(false) else { return };
    let Some(health) = common::health_wasm() else { return };
    let spin = ModuleDir::new("spin3", "Acme_Fixture", &fixture_manifest("1.0.0", json!({})), &[("daemon/fixture.wasm", &wasm)]);
    let h = ModuleDir::new("spin3-health", "Infantry_Health", &health_manifest(json!({"component": "daemon/Infantry_Health.wasm"})), &[("daemon/Infantry_Health.wasm", &health)]);
    let host = PluginHost::new(health_services(), HostLimits::default()).unwrap();
    host.load(&spin.dir).unwrap();
    host.load(&h.dir).unwrap();
    for strike in 1..=3 {
        let t = Instant::now();
        let e = host.call_tool("fixture_spin", "{}", &ctx()).unwrap_err();
        assert!(matches!(e, CallError::Failed(ref m) if m.contains("stopped")), "strike {strike}: {e:?}");
        assert!(t.elapsed() < Duration::from_secs(5), "the loop was not stopped by its budget");
        assert_eq!(host.get("Acme_Fixture").unwrap().guard.is_disabled(), strike == 3);
    }
    let e = host.call_tool("fixture_version", "{}", &ctx()).unwrap_err();
    assert!(matches!(e, CallError::Refused(ref m) if m.contains("disabled")), "{e:?}");
    assert!(host.tools().iter().all(|t| t.module_id != "Acme_Fixture"), "a disabled plugin's tools leave the table");
    // The host and the other plugin are untouched.
    assert_eq!(host.call_tool("infantry_health", "{}", &ctx()).unwrap(), "daemon: up (session s-1)");
}

#[test]
fn a_trap_disables_at_once_and_a_plugin_error_never_does() {
    let Some(wasm) = common::fixture_wasm(false) else { return };
    let m = ModuleDir::new("trap", "Acme_Fixture", &fixture_manifest("1.0.0", json!({})), &[("daemon/fixture.wasm", &wasm)]);
    let host = PluginHost::new(health_services(), HostLimits::default()).unwrap();
    host.load(&m.dir).unwrap();
    assert_eq!(host.call_tool("fixture_error", "{}", &ctx()), Err(CallError::Plugin("bad arguments".into())));
    assert_eq!(host.call_tool("fixture_storage", "stored", &ctx()).unwrap(), "stored");
    let e = host.call_tool("fixture_panic", "{}", &ctx()).unwrap_err();
    assert!(matches!(e, CallError::Failed(ref m) if m.contains("trapped")), "{e:?}");
    assert!(host.get("Acme_Fixture").unwrap().guard.is_disabled());
}

#[test]
fn an_import_the_manifest_does_not_request_is_not_linked() {
    let Some(wasm) = common::fixture_wasm(false) else { return };
    // The fixture imports storage; this manifest asks only for frames.
    let m = ModuleDir::new("grants", "Acme_Fixture", &fixture_manifest("1.0.0", json!({"needs": ["frames"]})), &[("daemon/fixture.wasm", &wasm)]);
    let host = PluginHost::new(health_services(), HostLimits::default()).unwrap();
    let e = host.load(&m.dir).err().expect("refused");
    assert!(e.contains("storage") && e.contains("does not request"), "{e}");
    assert!(host.statuses()["Acme_Fixture"].starts_with("refused:"));
}

#[test]
fn a_hot_swap_drains_the_in_flight_call_of_the_old_version() {
    let (Some(v1), Some(v2)) = (common::fixture_wasm(false), common::fixture_wasm(true)) else { return };
    let services = health_services();
    let host = Arc::new(PluginHost::new(services.clone(), HostLimits::default()).unwrap());
    let a = ModuleDir::new("swap-v1", "Acme_Fixture", &fixture_manifest("1.0.0", json!({})), &[("daemon/fixture.wasm", &v1)]);
    host.load(&a.dir).unwrap();
    assert_eq!(host.call_tool("fixture_version", "{}", &ctx()).unwrap(), "v1");
    let h = host.clone();
    let in_flight = std::thread::spawn(move || h.call_tool("fixture_slow", "{}", &ctx()));
    std::thread::sleep(Duration::from_millis(100));
    let b = ModuleDir::new("swap-v2", "Acme_Fixture", &fixture_manifest("2.0.0", json!({})), &[("daemon/fixture.wasm", &v2)]);
    host.load(&b.dir).unwrap();
    // New calls go to v2 at once, while v1 is still finishing its call.
    assert_eq!(host.call_tool("fixture_version", "{}", &ctx()).unwrap(), "v2");
    assert_eq!(in_flight.join().unwrap().unwrap(), "v1", "the in-flight call finished on the old version");
    host.settle();
    let logs = services.log_lines().join("\n");
    assert!(logs.contains("fixture v1 stopping") && logs.contains("version 1.0.0 stopped"), "{logs}");
    assert!(!logs.contains("fixture v2 stopping"));
    assert_eq!(host.get("Acme_Fixture").unwrap().version, "2.0.0");
}

#[test]
fn plugin_frames_are_a_frame_unit() {
    let Some(wasm) = common::fixture_wasm(false) else { return };
    let m = ModuleDir::new("unit", "Acme_Fixture", &fixture_manifest("1.0.0", json!({})), &[("daemon/fixture.wasm", &wasm)]);
    let host = Arc::new(PluginHost::new(health_services(), HostLimits::default()).unwrap());
    host.load(&m.dir).unwrap();
    let unit = PluginFrameUnit::new(host.clone(), "Acme_Fixture").unwrap();
    assert_eq!(unit.frames(), &["fixture_echo"]);
    let mut env = Envelope::new("fixture_echo", "hi");
    env.request_id = Some("r1".into());
    let owner = FrameCtx { conn_id: 1, owner: true, bus: EventBus::default() };
    let out = unit.handle(&env, &owner).unwrap();
    assert_eq!((out[0].r#type.as_str(), out[0].data.as_str(), out[0].request_id.as_deref()), ("fixture_echo_reply", "hi", Some("r1")));
    let guest = FrameCtx { conn_id: 2, owner: false, bus: EventBus::default() };
    assert_eq!(unit.handle(&env, &guest).unwrap_err().0, "fixture_echo refused: owner only");
}
