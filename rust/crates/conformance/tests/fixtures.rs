// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Offline checks: the recorded Kotlin frames decode and round-trip through `infantry-protocol`, and the recorder
//! and differ behave. No daemon needed.
use infantry_conformance::{fixture, normalize::Normalizer};
use infantry_protocol::session::SessionRecord;
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::PathBuf;

fn raw_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/kotlin/raw")
}

#[test]
fn recorded_kotlin_frames_round_trip_through_the_protocol_crate() {
    let mut sessions = 0;
    let mut files = 0;
    for entry in std::fs::read_dir(raw_dir()).expect("raw fixtures") {
        let path = entry.unwrap().path();
        files += 1;
        let frames: Vec<Value> = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        for wire in frames {
            let env: Envelope = serde_json::from_value(wire.clone()).unwrap();
            assert_eq!(serde_json::to_value(&env).unwrap(), wire, "Envelope drift in {}", path.display());
            let recs: Vec<Value> = match env.r#type.as_str() {
                "session" => vec![serde_json::from_str(&env.data).unwrap()],
                "sessions" => serde_json::from_str(&env.data).unwrap(),
                _ => continue,
            };
            for rec in recs {
                let r: SessionRecord = serde_json::from_value(rec.clone()).unwrap();
                assert_eq!(serde_json::to_value(&r).unwrap(), rec, "SessionRecord drift in {}", path.display());
                sessions += 1;
            }
        }
    }
    assert!(files >= 7 && sessions > 20, "fixtures missing: {files} files, {sessions} session records");
}

#[test]
fn normalizer_blanks_ids_times_and_paths_and_numbers_ids_by_first_use() {
    let id1 = "6890e4f4-3f7d-438a-af37-ee23e26be9b0";
    let id2 = "11111111-2222-3333-4444-555555555555";
    let rec = json!({"id": id1, "spawnedAt": 1794995230786_i64, "childPid": 123, "workingDir": "C:\\x", "runCount": 2});
    let mut e = Envelope::new("session", rec.to_string());
    e.session_id = Some(id1.into());
    let mut f = Envelope::new("turn", format!("resume {id2} then {id1}"));
    f.session_id = Some(id2.into());
    let mut n = Normalizer::new();
    let out = n.transcript(&[e, f]);
    assert_eq!(out[0]["data"], json!({"id": "<id:1>", "spawnedAt": "<ts>", "childPid": "<pid>", "workingDir": "<path>", "runCount": 2}));
    assert_eq!(out[1]["data"], json!("resume <id:2> then <id:1>"));
}

#[test]
fn ready_keeps_provider_and_contract_only() {
    let e = Envelope::new("ready", "provider=stub contract=1.155.0 instance=abc since=5 build=dev");
    let v = Normalizer::new().frame(&e);
    assert_eq!(v["data"], json!("provider=stub contract=1.155.0 instance=<id> since=<ts> build=<build>"));
}

#[test]
fn diff_orders_plain_frames_and_compares_timing_frames_as_sets() {
    let t = |ty: &str, d: &str| json!({"type": ty, "sessionId": null, "data": d});
    let a = vec![t("turn", "x"), t("status", "BUSY"), t("status", "IDLE"), t("finished", "ok")];
    let b = vec![t("turn", "x"), t("status", "IDLE"), t("status", "BUSY"), t("status", "IDLE"), t("finished", "ok")];
    assert_eq!(fixture::diff(&a, &b), None);
    let c = vec![t("finished", "ok"), t("turn", "x"), t("status", "BUSY"), t("status", "IDLE")];
    assert!(fixture::diff(&a, &c).is_some());
    let d = vec![t("turn", "x"), t("status", "BUSY"), t("finished", "ok")];
    assert!(fixture::diff(&a, &d).is_some(), "a missing status snapshot must be reported");
}
