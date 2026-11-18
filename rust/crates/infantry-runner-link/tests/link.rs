// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! End to end over real sockets: a runner served on a loopback port, an orchestrator client pairing with a code, ping/pong,
//! then the [`RemoteRunner`] driving processes there. Plus the frames a real Kotlin runner answered (tests/fixtures/kotlin)
//! decode into the Rust types and encode back to the same bytes.

use infantry_runner_api::suite::collect;
use infantry_runner_api::{ProcId, ProcessSpec, Runner, RunnerError};
use infantry_runner_link::pairing::PairCodes;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::wire::*;
use infantry_runner_link::{serve, LinkClient, LinkServer, LinkServing, LinkStore, RemoteRunner};
use std::sync::Arc;
use std::time::Duration;

fn runner(backend: Box<dyn infantry_runner_link::Backend>) -> (LinkServing, String) {
    let server = LinkServer::new(LinkStore::in_memory(), Some(PairCodes::new(60_000, 10)), None, backend, None).with_log(|_| {});
    let code = server.issue_code("test-box").unwrap();
    let serving = serve(Arc::new(server), "127.0.0.1", 0).unwrap();
    (serving, code)
}

#[test]
fn pair_with_a_code_then_ping_pong() {
    let (serving, code) = runner(Box::new(infantry_runner_link::InfoOnly));
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let c = LinkClient::new(&url, "inf-orch", None);
    assert!(matches!(c.ping(), Err(infantry_runner_link::CallError::Refused(r)) if r.contains("needs a pair credential")));
    let paired = c.pair(&code, "test-orchestrator").unwrap();
    assert_eq!((paired.name.as_str(), paired.contract.as_str()), ("test-box", CONTRACT));
    let p = c.ping().unwrap();
    assert_eq!((p.hello.runner_name.as_str(), p.health.state.as_str()), ("test-box", "ok"));
    assert!(p.rtt > Duration::ZERO && p.rtt < Duration::from_secs(5));
    // The spent code pairs nobody else; a stranger's credential is refused.
    let other = LinkClient::new(&url, "inf-other", Some("forged".into()));
    assert!(matches!(other.health(), Err(infantry_runner_link::CallError::Refused(r)) if r.contains(NOT_PAIRED)));
    assert!(LinkClient::new(&url, "inf-other", None).pair(&code, "x").is_err());
}

#[test]
fn an_outdated_runner_is_refused_with_the_hint() {
    assert_eq!(
        infantry_runner_link::client::outdated_text("box", Some("1.9.0"), MIN_RUNNER_CONTRACT),
        "runner 'box' is older than the app (contract 1.9.0, the app needs 1.11.0 or newer): update it: Settings > Runners > Update runner"
    );
}

fn sh(line: &str) -> ProcessSpec {
    if cfg!(windows) {
        ProcessSpec::new("cmd", ["/c", line])
    } else {
        ProcessSpec::new("sh", ["-c", line])
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn remote_runner_spawns_echoes_and_exits() {
    let (serving, code) = runner(Box::new(ProcessBackend::local()));
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let client = LinkClient::new(&url, "inf-orch", None);
    let code2 = code.clone();
    let client = tokio::task::spawn_blocking(move || {
        client.pair(&code2, "t").unwrap();
        client
    })
    .await
    .unwrap();
    let remote = RemoteRunner::new("test-box", client);
    let pong = remote.ping().await.unwrap();
    assert_eq!(pong.os, std::env::consts::OS);
    assert!(pong.version.contains(CONTRACT));

    let h = remote.spawn(sh("echo link-piped-out")).await.unwrap();
    let mut rx = remote.events(&h.id).await.unwrap();
    let c = collect(&mut rx, Duration::from_secs(15)).await;
    assert!(c.stdout.contains("link-piped-out") && c.code == Some(0), "{c:?}");
    remote.release(&h.id).await.unwrap();

    let h = remote.spawn(sh("echo link-err 1>&2")).await.unwrap();
    let c = collect(&mut remote.events(&h.id).await.unwrap(), Duration::from_secs(15)).await;
    assert!(c.stderr.contains("link-err") && !c.stdout.contains("link-err"), "{c:?}");

    let h = remote.spawn(sh("exit 7")).await.unwrap();
    let c = collect(&mut remote.events(&h.id).await.unwrap(), Duration::from_secs(15)).await;
    assert_eq!(c.code, Some(7), "{c:?}");

    let cat = if cfg!(windows) { ProcessSpec::new("findstr", ["/r", "^"]) } else { ProcessSpec::new("cat", Vec::<String>::new()) };
    let h = remote.spawn(cat).await.unwrap();
    let mut rx = remote.events(&h.id).await.unwrap();
    remote.write(&h.id, b"link-stdin-line\n").await.unwrap();
    remote.close_input(&h.id).await.unwrap();
    let c = collect(&mut rx, Duration::from_secs(15)).await;
    assert!(c.stdout.contains("link-stdin-line") && c.code == Some(0), "{c:?}");
    assert!(remote.write(&h.id, b"x").await.is_err(), "write after exit must fail");
    remote.release(&h.id).await.unwrap();
    assert!(matches!(remote.events(&h.id).await, Err(RunnerError::NotFound(_))));
    assert!(matches!(remote.events(&ProcId("no-such".into())).await, Err(RunnerError::NotFound(_))));
    assert!(matches!(remote.screen(&h.id).await, Err(RunnerError::Unsupported(_))));
}

fn fixture(name: &str) -> (String, String) {
    let path = format!("{}/tests/fixtures/kotlin/{name}.json", env!("CARGO_MANIFEST_DIR"));
    let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let answer: LinkEnvelope = serde_json::from_str(v["answer"].as_str().unwrap()).unwrap();
    assert_eq!(answer.r#type, REPLY);
    (v["request"].as_str().unwrap().to_string(), answer.data)
}

/// Kotlin wrote every field in declaration order: the Rust types decode it and write the same bytes back.
fn same_bytes<T: serde::de::DeserializeOwned + serde::Serialize>(data: &str) {
    let t: T = serde_json::from_str(data).unwrap();
    assert_eq!(serde_json::to_string(&t).unwrap(), data);
}

#[test]
fn kotlin_runner_answers_round_trip() {
    let (req, pair) = fixture("runner_link_pair");
    same_bytes::<PairAnswer>(&pair);
    let sent: LinkEnvelope = serde_json::from_str(&req).unwrap();
    same_bytes::<PairRequest>(&sent.data);
    let (_, hello) = fixture("runner_link_hello");
    same_bytes::<HelloAnswer>(&hello);
    let h: HelloAnswer = serde_json::from_str(&hello).unwrap();
    assert_eq!((h.runner_name.as_str(), h.contract.as_str()), ("kotlin-runner", CONTRACT));
    assert!(h.offers.is_empty(), "a Kotlin runner offers no process starts");
    let (_, health) = fixture("runner_link_health");
    same_bytes::<HealthAnswer>(&health);
}
