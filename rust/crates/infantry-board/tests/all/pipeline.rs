// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! PIPE-1: the `pipeline_config|show|record|check` frames, each answered by `pipeline_state`.
use crate::common;

use common::*;
use infantry_core::frames::{FrameCtx, FrameUnit};
use infantry_protocol::Envelope;
use serde_json::Value;

fn send(unit: &infantry_board::BoardUnit, ctx: &FrameCtx, frame: &str, data: &str) -> Result<Value, String> {
    let mut e = Envelope::new(frame, data);
    e.request_id = Some("r1".into());
    let out = unit.handle(&e, ctx).map_err(|r| r.0)?;
    assert_eq!(out[0].r#type, "pipeline_state");
    Ok(serde_json::from_str(&out[0].data).unwrap())
}

#[test]
fn no_config_means_no_pipeline_and_writes_refused() {
    let f = fixture("pipe-none", kotlin_daemon_env());
    let unit = infantry_board::BoardUnit::new(f.board.clone());
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    let s = send(&unit, &ctx, "pipeline_show", r#"{"projectId":"p1"}"#).unwrap();
    assert_eq!(s["configured"], false);
    let e = send(&unit, &ctx, "pipeline_record", r#"{"projectId":"p1","commit":"abc","stage":"unit"}"#).unwrap_err();
    assert!(e.starts_with("pipeline_record refused: no_pipeline"), "{e}");
    let e = send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","commit":"abc","what":"x"}"#).unwrap_err();
    assert!(e.starts_with("pipeline_check refused: no_pipeline"), "{e}");
}

#[test]
fn config_record_check_and_state() {
    let f = fixture("pipe-flow", kotlin_daemon_env());
    let unit = infantry_board::BoardUnit::new(f.board.clone());
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    let mut rx = f.bus.subscribe();

    // config: validation, then a pipeline.
    assert!(send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","stages":[]}"#).unwrap_err().contains("stages is required"));
    assert!(send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","stages":["a","a"]}"#).unwrap_err().contains("unique"));
    assert!(send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","stages":["a,b"]}"#).unwrap_err().contains("without commas"));
    let s = send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","stages":[" unit ","staging"],"exclusiveCheck":true}"#).unwrap();
    assert_eq!(s["configured"], true);
    assert_eq!(s["config"]["stages"], serde_json::json!(["unit", "staging"]));
    assert_eq!(rx.try_recv().unwrap().r#type, "pipeline_state", "a change is pushed");

    // record: an unknown stage is refused; the newest entry is the commit's stage.
    assert!(send(&unit, &ctx, "pipeline_record", r#"{"projectId":"p1","commit":"abc","stage":"nope"}"#).unwrap_err().contains("unknown_stage"));
    send(&unit, &ctx, "pipeline_record", r#"{"projectId":"p1","commit":"abc","stage":"unit","evidence":"42 passed","sessionId":"s1"}"#).unwrap();
    let s = send(&unit, &ctx, "pipeline_record", r#"{"projectId":"p1","commit":"abc","stage":"staging"}"#).unwrap();
    assert_eq!(s["history"].as_array().unwrap().len(), 2);
    assert_eq!(s["commits"].as_array().unwrap().len(), 1);
    assert_eq!(s["commits"][0]["stage"], "staging");
    assert_eq!(s["history"][1]["evidence"], "42 passed");
    assert_eq!(s["history"][1]["movedBy"], "s1");

    // check: start, a second start refused (exclusive), ping, finish, finish again refused.
    let s = send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","commit":"abc","what":"cargo test"}"#).unwrap();
    assert_eq!(s["checks"][0]["what"], "cargo test");
    let e = send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","commit":"def","what":"x"}"#).unwrap_err();
    assert!(e.contains("check_running") && e.contains("the default scope"), "{e}");
    send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","scope":"wt2","commit":"def","what":"x"}"#).unwrap();
    send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"ping"}"#).unwrap();
    let s = send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"finish"}"#).unwrap();
    assert_eq!(s["checks"].as_array().unwrap().len(), 1);
    assert!(send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"finish"}"#).unwrap_err().contains("no_check"));
    assert!(send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"bogus"}"#).unwrap_err().contains("unknown check action"));

    // show answers the same state; clear removes the pipeline; another project never sees p1's.
    assert_eq!(send(&unit, &ctx, "pipeline_show", r#"{"projectId":"p1"}"#).unwrap()["configured"], true);
    assert_eq!(send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","clear":true}"#).unwrap()["configured"], false);
    assert_eq!(send(&unit, &ctx, "pipeline_show", r#"{"projectId":"p2"}"#).unwrap()["configured"], false);
}

#[test]
fn non_exclusive_start_replaces_the_running_check() {
    let f = fixture("pipe-replace", kotlin_daemon_env());
    let unit = infantry_board::BoardUnit::new(f.board.clone());
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    send(&unit, &ctx, "pipeline_config", r#"{"projectId":"p1","stages":["unit"]}"#).unwrap();
    send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","commit":"a","what":"one"}"#).unwrap();
    let s = send(&unit, &ctx, "pipeline_check", r#"{"projectId":"p1","action":"start","commit":"b","what":"two"}"#).unwrap();
    assert_eq!(s["checks"].as_array().unwrap().len(), 1);
    assert_eq!(s["checks"][0]["what"], "two");
}
