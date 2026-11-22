// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-6: credentials a remote start lends, end to end over a real socket: the lent env reaches the process, the git token is
//! only in the GIT_CONFIG_* env, `runner.acceptCredentials=false` drops them, the output is masked, and nothing of a lent
//! value is left in the runner's state dir or log.

use infantry_runner_api::suite::collect;
use infantry_runner_api::{LendingRunner, LentCredentials, LentVar, ProcessSpec, Runner};
use infantry_runner_link::pairing::PairCodes;
use infantry_runner_link::process::{LendPolicy, ProcessBackend};
use infantry_runner_link::wire::StartRequest;
use infantry_runner_link::{serve, LinkClient, LinkServer, LinkStore, RemoteRunner};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const KEY: &str = "sk-lent-key-1234567890";
const TOKEN: &str = "ghp_lenttoken0987654321";

fn lent() -> LentCredentials {
    LentCredentials { env: vec![LentVar { name: "LENT_KEY".into(), value: KEY.into(), harness: Some("pi".into()) }], git_token: Some(TOKEN.into()) }
}

fn sh(line: &str) -> ProcessSpec {
    if cfg!(windows) {
        ProcessSpec::new("cmd", ["/c", line])
    } else {
        ProcessSpec::new("sh", ["-c", line])
    }
}

/// What the process prints: the lent key's tail (not masked: it is not the whole value), the whole key (masked), the git env.
fn probe() -> ProcessSpec {
    if cfg!(windows) {
        sh("echo tail=%LENT_KEY:~3% & echo whole=%LENT_KEY% & echo count=%GIT_CONFIG_COUNT% & echo k=%GIT_CONFIG_KEY_0% & echo v=%GIT_CONFIG_VALUE_0%")
    } else {
        sh("echo tail=${LENT_KEY#sk-}; echo whole=$LENT_KEY; echo count=$GIT_CONFIG_COUNT; echo k=$GIT_CONFIG_KEY_0; echo v=$GIT_CONFIG_VALUE_0")
    }
}

struct Rig {
    remote: LendingRunner,
    log: Arc<Mutex<Vec<String>>>,
    state: std::path::PathBuf,
    _serving: infantry_runner_link::LinkServing,
}

async fn rig(policy: LendPolicy) -> Rig {
    let state = std::env::temp_dir().join(format!("infantry-lend-{}", uuid::Uuid::new_v4()));
    let log = Arc::new(Mutex::new(Vec::new()));
    let sink = log.clone();
    let backend = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default())).with_lend_policy(policy);
    let server = LinkServer::new(LinkStore::open(&state), Some(PairCodes::new(60_000, 10)), None, Box::new(backend), None).with_log(move |l| sink.lock().unwrap().push(l.to_string()));
    let code = server.issue_code("lend-box").unwrap();
    let serving = serve(Arc::new(server), "127.0.0.1", 0, None).unwrap();
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let client = LinkClient::new(&url, "inf-orch", None).allow_plain(true);
    let client = tokio::task::spawn_blocking(move || {
        client.pair(&code, "t").unwrap();
        client
    })
    .await
    .unwrap();
    Rig { remote: LendingRunner::new(Arc::new(RemoteRunner::new("lend-box", client)), lent()), log, state, _serving: serving }
}

async fn run(rig: &Rig, spec: ProcessSpec) -> infantry_runner_api::suite::Collected {
    let h = rig.remote.spawn(spec).await.unwrap();
    let mut rx = rig.remote.events(&h.id).await.unwrap();
    let c = collect(&mut rx, Duration::from_secs(20)).await;
    rig.remote.release(&h.id).await.unwrap();
    c
}

fn files_under(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let p = e.path();
            if p.is_dir() {
                files_under(&p, out);
            } else {
                out.push(p);
            }
        }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_lent_env_reaches_the_process_the_git_token_is_in_git_config_env_and_output_is_masked() {
    let rig = rig(LendPolicy::default()).await;
    let c = run(&rig, probe()).await;
    // The variable reached the process (its tail printed), the whole value is masked.
    assert!(c.stdout.contains("tail=lent-key-1234567890"), "{c:?}");
    assert!(c.stdout.contains("whole=***") && !c.stdout.contains(KEY), "{c:?}");
    // The git token: one config entry, `http.extraHeader`, the value a Basic header whose token part is masked in output.
    assert!(c.stdout.contains("count=1") && c.stdout.contains("k=http.extraHeader"), "{c:?}");
    assert!(c.stdout.contains("v=Authorization: Basic ***"), "{c:?}");
    assert!(!c.stdout.contains(TOKEN) && !c.stderr.contains(TOKEN));

    // After the run nothing of a lent value is in the runner's state dir or log.
    let mut files = Vec::new();
    files_under(&rig.state, &mut files);
    for f in files {
        let bytes = std::fs::read(&f).unwrap_or_default();
        let text = String::from_utf8_lossy(&bytes);
        assert!(!text.contains(KEY) && !text.contains(TOKEN), "a lent value is in {}", f.display());
    }
    let log = rig.log.lock().unwrap().join("\n");
    assert!(!log.contains(KEY) && !log.contains(TOKEN), "a lent value is in the runner log");
    let _ = std::fs::remove_dir_all(&rig.state);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn accept_credentials_false_drops_every_lent_value() {
    let rig = rig(LendPolicy { accept: Arc::new(|| false), ..Default::default() }).await;
    let c = run(&rig, probe()).await;
    assert!(!c.stdout.contains("lent-key") && !c.stdout.contains("***"), "nothing was lent: {c:?}");
    assert!(!c.stdout.contains("k=http.extraHeader"), "{c:?}");
    let _ = std::fs::remove_dir_all(&rig.state);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_runner_keeping_its_subscription_login_does_not_take_the_harness_key_but_takes_the_git_token() {
    let rig = rig(LendPolicy { keeps_own_login: Arc::new(|h| h == "pi"), ..Default::default() }).await;
    let c = run(&rig, probe()).await;
    assert!(!c.stdout.contains("lent-key"), "the runner's own login wins: {c:?}");
    assert!(c.stdout.contains("k=http.extraHeader"), "{c:?}");
    let _ = std::fs::remove_dir_all(&rig.state);
}

#[test]
fn the_start_request_debug_is_redacted() {
    let req = StartRequest { start_id: "s".into(), credentials: serde_json::to_value(lent()).ok(), process: Some(ProcessSpec { lent: Some(lent()), ..sh("x") }), ..Default::default() };
    let shown = format!("{req:?}");
    assert!(!shown.contains(KEY) && !shown.contains(TOKEN), "{shown}");
    assert!(shown.contains("redacted"));
}
