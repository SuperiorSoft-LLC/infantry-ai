// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-3: the runner link over wss with a pinned self-signed certificate. Real sockets on port 0.

use infantry_runner_api::suite::collect;
use infantry_runner_api::{KillScope, ProcEvent, ProcessSpec, Runner};
use infantry_runner_link::pairing::PairCodes;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::transport::{LinkHandler, PLAIN_REFUSED};
use infantry_runner_link::wire::*;
use infantry_runner_link::{serve, LinkClient, LinkServer, LinkServing, LinkStore, RemoteRunner, RunnerCert};
use std::sync::{Arc, Mutex};

fn tmp() -> std::path::PathBuf {
    std::env::temp_dir().join(format!("rr3-{}", uuid::Uuid::new_v4()))
}

/// Wraps the link server and records every credential header and frame the runner saw.
struct Spy {
    inner: LinkServer,
    credentials: Arc<Mutex<Vec<Option<String>>>>,
    calls: Arc<Mutex<usize>>,
}

impl LinkHandler for Spy {
    fn handle(&self, t: &str, data: &str, o: Option<&str>, c: Option<&str>, from: Option<&str>) -> Answer {
        self.credentials.lock().unwrap().push(c.map(str::to_string));
        *self.calls.lock().unwrap() += 1;
        self.inner.handle(t, data, o, c, from)
    }
}

type Seen = (LinkServing, String, Arc<Mutex<Vec<Option<String>>>>, Arc<Mutex<usize>>);

fn runner(cert: Option<&RunnerCert>, backend: Box<dyn infantry_runner_link::Backend>) -> Seen {
    let server = LinkServer::new(LinkStore::in_memory(), Some(PairCodes::new(60_000, 10)), None, backend, None).with_log(|_| {});
    let code = server.issue_code("tls-box").unwrap();
    let (credentials, calls) = (Arc::new(Mutex::new(Vec::new())), Arc::new(Mutex::new(0)));
    let spy = Spy { inner: server, credentials: credentials.clone(), calls: calls.clone() };
    let tls = cert.map(|c| c.server_config().unwrap());
    (serve(Arc::new(spy), "127.0.0.1", 0, tls).unwrap(), code, credentials, calls)
}

#[test]
fn pair_and_check_over_wss_with_the_pin() {
    let cert = RunnerCert::load_or_create(&tmp()).unwrap();
    let (serving, code, _, _) = runner(Some(&cert), Box::new(infantry_runner_link::InfoOnly));
    let url = format!("wss://127.0.0.1:{}", serving.port);
    let c = LinkClient::new(&url, "inf-orch", None).with_pin(Some(cert.fingerprint.clone()));
    let paired = c.pair(&code, "orch").unwrap();
    assert_eq!(paired.fingerprint, cert.fingerprint, "the pin is kept in the paired record");
    let p = c.ping().unwrap();
    assert_eq!((p.hello.runner_name.as_str(), p.health.state.as_str()), ("tls-box", "ok"));
    // The stored record rebuilds a pinned client with no extra input.
    let again = LinkClient::of(&paired, "inf-orch");
    assert_eq!(again.ping().unwrap().hello.runner_name, "tls-box");
}

#[test]
fn a_wrong_pin_is_refused_before_any_credential_is_sent() {
    let cert = RunnerCert::load_or_create(&tmp()).unwrap();
    let other = RunnerCert::load_or_create(&tmp()).unwrap();
    let (serving, code, creds, calls) = runner(Some(&cert), Box::new(infantry_runner_link::InfoOnly));
    let url = format!("wss://127.0.0.1:{}", serving.port);
    // Pair right first, so a credential exists that could leak.
    let good = LinkClient::new(&url, "inf-orch", None).with_pin(Some(cert.fingerprint.clone()));
    let paired = good.pair(&code, "orch").unwrap();
    let before = *calls.lock().unwrap();
    creds.lock().unwrap().clear();

    let bad = LinkClient::new(&url, "inf-orch", Some(paired.credential.clone())).with_pin(Some(other.fingerprint.clone()));
    let err = bad.ping().unwrap_err().to_string();
    assert!(err.contains("recreated without its volume") && err.contains("wrong runner"), "{err}");
    assert!(!err.contains(&paired.credential));
    assert_eq!(*calls.lock().unwrap(), before, "the server handled no frame");
    assert!(creds.lock().unwrap().is_empty(), "the server saw no credential header");
}

#[test]
fn wss_needs_a_pin_and_plain_ws_needs_the_setting() {
    let cert = RunnerCert::load_or_create(&tmp()).unwrap();
    let (serving, _, _, calls) = runner(Some(&cert), Box::new(infantry_runner_link::InfoOnly));
    let no_pin = LinkClient::new(&format!("wss://127.0.0.1:{}", serving.port), "o", None);
    assert!(no_pin.health().unwrap_err().to_string().contains("needs the runner's certificate pin"));

    let (plain, code, _, plain_calls) = runner(None, Box::new(infantry_runner_link::InfoOnly));
    let ws_url = format!("ws://127.0.0.1:{}", plain.port);
    let refused = LinkClient::new(&ws_url, "o", None);
    assert_eq!(refused.health().unwrap_err().to_string(), PLAIN_REFUSED);
    assert!(PLAIN_REFUSED.contains("allowPlainTailnet") && PLAIN_REFUSED.contains("unencrypted"));
    assert_eq!((*calls.lock().unwrap(), *plain_calls.lock().unwrap()), (0, 0), "nothing reached either runner");

    // With the setting on, ws works.
    let allowed = LinkClient::new(&ws_url, "o", None).allow_plain(true);
    allowed.pair(&code, "orch").unwrap();
    assert_eq!(allowed.ping().unwrap().hello.runner_name, "tls-box");
}

#[test]
fn the_certificate_survives_a_restart_and_the_pin_still_matches() {
    let dir = tmp();
    let cert = RunnerCert::load_or_create(&dir).unwrap();
    let (serving, code, _, _) = runner(Some(&cert), Box::new(infantry_runner_link::InfoOnly));
    let c = LinkClient::new(&format!("wss://127.0.0.1:{}", serving.port), "o", None).with_pin(Some(cert.fingerprint.clone()));
    let paired = c.pair(&code, "orch").unwrap();
    drop(serving);
    // "Restart": the same dir gives the same certificate; a new listener serves it; the old pin still passes the handshake.
    let again = RunnerCert::load_or_create(&dir).unwrap();
    assert_eq!(again.fingerprint, cert.fingerprint);
    let server = LinkServer::new(LinkStore::in_memory(), None, None, Box::new(infantry_runner_link::InfoOnly), None).with_log(|_| {});
    let serving2 = serve(Arc::new(server), "127.0.0.1", 0, Some(again.server_config().unwrap())).unwrap();
    let c2 = LinkClient::new(&format!("wss://127.0.0.1:{}", serving2.port), "o", Some(paired.credential)).with_pin(Some(cert.fingerprint));
    // The handshake passes; this fresh in-memory server does not know the credential, which proves the frames got through TLS.
    assert!(c2.health().unwrap_err().to_string().contains(NOT_PAIRED));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn spawn_and_kill_over_wss() {
    let cert = RunnerCert::load_or_create(&tmp()).unwrap();
    let (serving, code, _, _) = runner(Some(&cert), Box::new(ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()))));
    let url = format!("wss://127.0.0.1:{}", serving.port);
    let client = LinkClient::new(&url, "inf-orch", None).with_pin(Some(cert.fingerprint.clone()));
    client.pair(&code, "orch").unwrap();
    let remote = RemoteRunner::new("tls-box", client);
    let echo = if cfg!(windows) { ProcessSpec::new("cmd", ["/c", "echo hi"]) } else { ProcessSpec::new("sh", ["-c", "echo hi"]) };
    let h = remote.spawn(echo).await.unwrap();
    let c = collect(&mut remote.events(&h.id).await.unwrap(), std::time::Duration::from_secs(15)).await;
    assert!(c.stdout.contains("hi") && c.code == Some(0), "{c:?}");
    let long = if cfg!(windows) { ProcessSpec::new("ping", ["-n", "60", "127.0.0.1"]) } else { ProcessSpec::new("sleep", ["60"]) };
    let h = remote.spawn(long).await.unwrap();
    let mut ev = remote.events(&h.id).await.unwrap();
    remote.kill(&h.id, KillScope::Tree).await.unwrap();
    let exited = loop {
        match tokio::time::timeout(std::time::Duration::from_secs(15), ev.recv()).await.unwrap() {
            Some(ProcEvent::Exited(c)) => break Some(c),
            Some(_) => {}
            None => break None,
        }
    };
    assert!(exited.is_some());
}
