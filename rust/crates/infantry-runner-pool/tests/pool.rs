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
    let unit = AdminUnit { pool: pool.clone(), lend: None };

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

/// RR-11: a runner whose app is older than this one is `outdated`; a start on it is refused up front with the text, the
/// list row says so, and `refresh` re-reads it.
#[test]
fn an_outdated_hello_refuses_a_start_with_the_update_text() {
    use infantry_runner_source::RunnerSource;
    let (rdir, state) = (temp("old-runner"), temp("old-state"));
    let bearer = Bearer::of(&rdir);
    let v = bearer.ensure(Some("old-box"), false).unwrap();
    let token = BearerToken { name: v.name, fingerprint: None, secret: v.secret }.text();
    let server = LinkServer::new(LinkStore::in_memory(), None, Some(bearer), Box::new(InfoOnly), None).with_log(|_| {}).with_identity("runner", "0.0.1");
    let serving = serve(Arc::new(server), "127.0.0.1", 0, None).unwrap();
    let pool = RunnerPool::open(&state);
    pool.set_allow_plain(Arc::new(|| true));
    let unit = AdminUnit { pool: pool.clone(), lend: None };
    let url = format!("ws://127.0.0.1:{}", serving.port);
    assert_eq!(call(&unit, true, json!({"op": "add", "endpoint": url, "bearer": token})).unwrap()["ok"], true);

    let l = call(&unit, true, json!({"op": "refresh"})).unwrap();
    let row = &l["runners"][1];
    assert_eq!((row["state"].as_str(), row["outdated"].clone(), row["appVersion"].as_str()), (Some("outdated"), json!(true), Some("0.0.1")), "{l}");
    assert_eq!(row["needs"], infantry_runner_link::wire::MIN_RUNNER_CONTRACT);
    assert!(row["reason"].as_str().unwrap().contains("update it: Settings > Runners > Update runner"), "{row}");

    match RunnerSource::refresh(&*pool, "old-box") {
        infantry_runner_source::Availability::Outdated(why) => assert!(why.contains("update it: Settings > Runners > Update runner"), "{why}"),
        other => panic!("expected outdated, got {other:?}"),
    }
    // update of a runner that is not a local container answers with the command to run there; the embedded one cannot be updated.
    assert_eq!(call(&unit, true, json!({"op": "update", "name": "local"})).unwrap()["ok"], false);
    serving.close();
}

/// Ticket 5b9a882c: a Docker runner whose new image tag is missing. Update must not stay silent on "starting": the card
/// status gets the steps (container found + plan, pulling, failed) and the plain error, and the state leaves "running".
#[test]
fn an_update_to_a_missing_image_reports_its_steps_and_fails_plainly() {
    use infantry_runner_pool::update::Docker;
    struct MissingImage(u16);
    impl Docker for MissingImage {
        fn run(&self, args: &[String]) -> Result<String, String> {
            match args[0].as_str() {
                "ps" => Ok("abc".into()),
                "inspect" => Ok(json!([{"Name": "/testbox", "Config": {"Image": "infantry-runner-rust:e2e", "Cmd": [], "Env": []}, "Mounts": [],
                    "HostConfig": {"PortBindings": {"18950/tcp": [{"HostIp": "127.0.0.1", "HostPort": self.0.to_string()}]}}}]).to_string()),
                "pull" => Err("pull access denied for infantry-runner-rust".into()),
                "image" => Err("No such image".into()),
                other => panic!("the runner was touched: docker {other}"),
            }
        }
    }
    let (rdir, state) = (temp("runner-upd"), temp("state-upd"));
    let (serving, bearer) = runner(&rdir);
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let pool = RunnerPool::open(&state);
    pool.set_allow_plain(Arc::new(|| true));
    pool.set_docker(Arc::new(MissingImage(serving.port)));
    let unit = AdminUnit { pool: pool.clone(), lend: None };
    assert_eq!(call(&unit, true, json!({"op": "add", "endpoint": url, "bearer": bearer})).unwrap()["ok"], true);

    pool.update("test-box").unwrap();
    let end = Instant::now() + Duration::from_secs(10);
    let u = loop {
        let u = pool.update_status("test-box").expect("an update status");
        if u.state != "running" {
            break u;
        }
        assert!(Instant::now() < end, "stuck on running: {:?}", u.steps);
        std::thread::sleep(Duration::from_millis(50));
    };
    assert_eq!(u.state, "failed");
    assert!(u.steps[0].contains("plan: image infantry-runner-rust:"), "{:?}", u.steps);
    assert!(u.steps.iter().any(|s| s.starts_with("pulling ")), "{:?}", u.steps);
    assert!(u.detail.contains("could not be pulled and is not on this machine"), "{}", u.detail);
    assert!(pool.step_log("test-box").iter().any(|e| e["step"] == "update"), "Details > Setup log has the update steps");
}

// PARITY-AUDIT-1 #4: the five runner_admin ops the CLI sends (pair, pair_preview, pair_status, rotate, connect).

fn serve_opts() -> infantry_runner_pool::ServeOptions {
    infantry_runner_pool::ServeOptions {
        bind_host: "127.0.0.1".into(),
        port: 0,
        name: Some("box".into()),
        public_host: None,
        public_port: None,
        advertise_hosts: vec![],
        allow_projects: "*".into(),
        allow_harnesses: "*".into(),
        app_version: "0.0.0-test".into(),
        allow_plain: false,
        pair_code_ms: 60_000,
        pair_attempts_per_hour: 10,
    }
}

fn side(u: &infantry_runner_pool::RunnerSideAdmin, body: Value) -> Value {
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(8) };
    serde_json::from_str(&u.handle(&Envelope::new("runner_admin", body.to_string()), &ctx).unwrap()[0].data).unwrap()
}

#[test]
fn runner_side_pair_preview_pair_and_status() {
    let dir = temp("pair");
    let backend = infantry_runner_link::process::ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
    let rs = infantry_runner_pool::RunnerServe::start(&dir, Box::new(backend), serve_opts()).unwrap();
    let u = infantry_runner_pool::RunnerSideAdmin { serve: rs.clone() };
    assert!(side(&u, json!({"op": "pair_preview"}))["installedHarnesses"].is_array());
    assert_eq!(side(&u, json!({"op": "pair_status"}))["state"], "expired", "no code issued yet");
    assert!(side(&u, json!({"op": "pair", "name": "Bad Name"}))["error"].as_str().unwrap().contains("must be"));
    let p = side(&u, json!({"op": "pair", "name": "box"}));
    assert_eq!(p["ok"], true, "{p}");
    let code = p["code"].as_str().unwrap().to_string();
    assert_eq!((p["port"].as_u64(), p["plain"].as_bool(), p["expiryMin"].as_i64()), (Some(rs.port() as u64), Some(false), Some(1)));
    assert_eq!(side(&u, json!({"op": "pair_status"}))["state"], "waiting");
    let url = format!("wss://127.0.0.1:{}", rs.port());
    let c = infantry_runner_link::LinkClient::new(&url, "inf-orch".to_string(), None).with_pin(p["fingerprint"].as_str().map(str::to_string));
    assert!(c.pair("WRONG-CODE-XXXX-YYYY", "orch").is_err());
    let paired = c.pair(&code, "orch").unwrap();
    assert_eq!(paired.name, "box");
    let st = side(&u, json!({"op": "pair_status"}));
    assert_eq!((st["state"].as_str(), st["orchestratorName"].as_str()), (Some("paired"), Some("orch")), "{st}");
    assert!(c.pair(&code, "orch").is_err(), "the code is single use");
    assert!(side(&u, json!({"op": "rotate", "name": "x"}))["error"].as_str().unwrap().contains("unknown op"));
}

#[test]
fn rotate_refuses_the_old_key_and_unknown_runners() {
    let (rdir, state) = (temp("rot-runner"), temp("rot-state"));
    let (serving, bearer) = runner(&rdir);
    let pool = RunnerPool::open(&state);
    pool.set_allow_plain(Arc::new(|| true));
    let unit = AdminUnit { pool: pool.clone(), lend: None };
    assert!(call(&unit, true, json!({"op": "rotate", "name": "nope"})).unwrap()["error"].as_str().unwrap().contains("no runner named 'nope'"));
    assert!(call(&unit, true, json!({"op": "rotate"})).unwrap()["error"].as_str().unwrap().contains("needs a runner name"));
    let url = format!("ws://127.0.0.1:{}", serving.port);
    assert_eq!(call(&unit, true, json!({"op": "add", "endpoint": url, "bearer": bearer})).unwrap()["ok"], true);
    let old = pool.store().runner("test-box").unwrap();
    let r = call(&unit, true, json!({"op": "rotate", "name": "test-box"})).unwrap();
    assert_eq!((r["ok"].as_bool(), r["name"].as_str()), (Some(true), Some("test-box")), "{r}");
    assert_ne!(pool.store().runner("test-box").unwrap().credential, old.credential);
    let stale = infantry_runner_link::LinkClient::of(&old, &pool.orchestrator_id()).allow_plain(true);
    assert!(stale.hello().is_err(), "the old key is refused after rotate");
    assert_eq!(call(&unit, true, json!({"op": "check", "name": "test-box"})).unwrap()["state"], "online");
    // connect: an answering runner is `already`; an unknown one without a bearer is refused.
    let c = call(&unit, true, json!({"op": "connect", "name": "test-box"})).unwrap();
    assert_eq!((c["ok"].as_bool(), c["already"].as_bool()), (Some(true), Some(true)), "{c}");
    assert!(call(&unit, true, json!({"op": "connect", "name": "ghost"})).unwrap()["error"].as_str().unwrap().contains("needs the runner's bearer"));
    assert!(call(&unit, true, json!({"op": "pair"})).unwrap()["error"].as_str().unwrap().contains("runs on a runner"));
}

#[test]
fn connect_pairs_by_bearer_and_host() {
    let (rdir, state) = (temp("con-runner"), temp("con-state"));
    let (serving, bearer) = runner(&rdir);
    let pool = RunnerPool::open(&state);
    pool.set_allow_plain(Arc::new(|| true));
    let unit = AdminUnit { pool, lend: None };
    let c = call(&unit, true, json!({"op": "connect", "name": "test-box", "bearer": bearer, "host": "127.0.0.1", "port": serving.port.to_string()})).unwrap();
    assert_eq!((c["ok"].as_bool(), c["already"].as_bool(), c["name"].as_str()), (Some(true), Some(false), Some("test-box")), "{c}");
}
