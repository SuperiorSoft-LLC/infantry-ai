// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-1 end to end: a real `LinkServer` (the InfoOnly backend `infantry-runner-agent` serves) on port 0, driven through
//! the `runner_admin` frame: add by bearer, check (pong with ms), list online, kill the server -> offline with the link
//! error, detach forgets. No fixed port is bound.

use infantry_core::frames::{Envelope, FrameCtx, FrameUnit};
use infantry_core::bus::EventBus;
use infantry_runner_link::pairing::{Bearer, BearerToken};
use infantry_runner_link::{serve, InfoOnly, LinkServer, LinkServing, LinkStore};
use infantry_runner_pool::{AdminUnit, RunnerPool};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn temp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("runner-pool-{tag}-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// A runner on a free port; returns it with its bearer text.
fn runner(dir: &PathBuf) -> (LinkServing, String) {
    let bearer = Bearer::of(dir);
    let v = bearer.ensure(Some("test-box"), false).unwrap();
    let token = BearerToken { name: v.name, fingerprint: None, secret: v.secret }.text();
    let server = LinkServer::new(LinkStore::in_memory(), None, Some(bearer), Box::new(InfoOnly), None).with_log(|_| {});
    (serve(Arc::new(server), "127.0.0.1", 0, None).unwrap(), token)
}

fn call(u: &AdminUnit, owner: bool, body: Value) -> Result<Value, String> {
    let ctx = FrameCtx { conn_id: 1, owner, bus: EventBus::new(8) };
    let out = u.handle(&Envelope::new("runner_admin", body.to_string()), &ctx).map_err(|r| r.0)?;
    Ok(serde_json::from_str(&out[0].data).unwrap())
}

#[test]
fn add_check_list_offline_detach() {
    let (rdir, state) = (temp("runner"), temp("state"));
    let (serving, bearer) = runner(&rdir);
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let pool = RunnerPool::open(&state);
    pool.set_allow_plain(Arc::new(|| true)); // this runner serves plain ws
    let unit = AdminUnit { pool: pool.clone() };

    assert_eq!(call(&unit, false, json!({"op": "list"})).unwrap_err(), "runner_admin refused: owner only");
    assert!(call(&unit, true, json!({"op": "add", "endpoint": "wss://x:1", "bearer": bearer})).unwrap()["error"].as_str().unwrap().contains("cannot pair over wss"));
    let bad = call(&unit, true, json!({"op": "add", "endpoint": url, "bearer": "irb1.test-box.plain.wrongsecretwrongsecretwrongsecret"})).unwrap();
    assert_eq!(bad["ok"], false);

    let added = call(&unit, true, json!({"op": "add", "endpoint": url, "bearer": bearer, "orchestratorName": "pc"})).unwrap();
    assert_eq!((added["ok"].clone(), added["name"].as_str()), (json!(true), Some("test-box")));
    assert!(!added.to_string().contains(&bearer), "the bearer never comes back");

    let c = call(&unit, true, json!({"op": "check", "name": "test-box"})).unwrap();
    assert_eq!(c["ok"], true);
    assert!(c["rows"][0]["detail"].as_str().unwrap().starts_with("pong from 'test-box' in "), "{c}");
    let l = call(&unit, true, json!({"op": "list"})).unwrap();
    assert_eq!((l["runners"][0]["name"].as_str(), l["runners"][0]["kind"].as_str(), l["runners"][0]["state"].as_str()), (Some("local"), Some("embedded"), Some("online")), "local is the first row");
    assert_eq!((l["runners"][1]["state"].as_str(), l["runners"][1]["name"].as_str()), (Some("online"), Some("test-box")));
    assert!(l["runners"][1]["rttMs"].is_u64());

    // ONE store per state dir: a second pool on the same dir sees the same runners without a reload.
    assert!(Arc::ptr_eq(pool.store(), RunnerPool::open(&state).store()));

    // Kill the runner: the heartbeat flips it to offline with the link error.
    pool.start_heartbeat(Arc::new(|| 1));
    serving.close();
    drop(serving);
    let end = Instant::now() + Duration::from_secs(20);
    let reason = loop {
        let l = call(&unit, true, json!({"op": "list"})).unwrap();
        if l["runners"][1]["state"] == "offline" {
            break l["runners"][1]["reason"].as_str().unwrap().to_string();
        }
        assert!(Instant::now() < end, "never went offline: {l}");
        std::thread::sleep(Duration::from_millis(200));
    };
    assert!(!reason.is_empty());
    let c = call(&unit, true, json!({"op": "check", "name": "test-box"})).unwrap();
    assert_eq!((c["ok"].clone(), c["rows"][0]["ok"].clone()), (json!(true), json!(false)));

    let d = call(&unit, true, json!({"op": "detach", "name": "test-box"})).unwrap();
    assert_eq!(d["ok"], true);
    let after = call(&unit, true, json!({"op": "list"})).unwrap();
    assert_eq!(after["runners"].as_array().unwrap().len(), 1, "only the embedded runner is left");
    assert_eq!(after["runners"][0]["name"], "local");
    assert_eq!(call(&unit, true, json!({"op": "check", "name": "test-box"})).unwrap()["ok"], false);
}
