// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-5: an interactive (pty) process on a runner in this process (port 0), driven by the orchestrator's [`RemoteRunner`]:
//! the screen it draws is read with `screen()` (the orchestrator's own vt100 over the pushed bytes), `resize` changes the
//! runner-side terminal, a hook POST and an MCP-style POST made on the runner's loopback arrive at the orchestrator, and the
//! output latency is measured and printed.

use infantry_runner_api::{ProcEvent, ProcessSpec, Runner, RunnerError, Size, TailFrom, TailSpec};
use infantry_runner_link::pairing::PairCodes;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::{serve, LinkClient, LinkServer, LinkStore, RemoteRunner};
use infantry_runner_local::LocalRunner;
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

async fn paired() -> (RemoteRunner, Arc<LocalRunner>, infantry_runner_link::LinkServing) {
    let local = Arc::new(LocalRunner::default());
    let backend = ProcessBackend::new(local.clone());
    let server = LinkServer::new(LinkStore::in_memory(), Some(PairCodes::new(60_000, 10)), None, Box::new(backend), None).with_log(|_| {});
    let code = server.issue_code("pty-box").unwrap();
    let serving = serve(Arc::new(server), "127.0.0.1", 0, None).unwrap();
    let url = format!("ws://127.0.0.1:{}", serving.port);
    let client = LinkClient::new(&url, "inf-orch", None).allow_plain(true);
    let client = tokio::task::spawn_blocking(move || {
        client.pair(&code, "t").unwrap();
        client
    })
    .await
    .unwrap();
    (RemoteRunner::new("pty-box", client), local, serving)
}

fn shell_pty() -> ProcessSpec {
    let spec = if cfg!(windows) { ProcessSpec::new("cmd", Vec::<String>::new()) } else { ProcessSpec::new("sh", Vec::<String>::new()) };
    spec.pty(Size { rows: 24, cols: 100 })
}

async fn screen_has(r: &RemoteRunner, id: &infantry_runner_api::ProcId, needle: &str, within: Duration) -> bool {
    let end = Instant::now() + within;
    while Instant::now() < end {
        if r.screen(id).await.map(|s| s.rows.iter().any(|l| l.contains(needle))).unwrap_or(false) {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    false
}

/// One POST to `url` made from "the runner's process": raw HTTP on the loopback, the answer body returned.
fn post(url: &str, body: &str) -> String {
    let rest = url.strip_prefix("http://").unwrap();
    let (host, path) = rest.split_once('/').unwrap();
    let mut s = std::net::TcpStream::connect(host).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(20))).unwrap();
    write!(s, "POST /{path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
    let mut out = String::new();
    s.read_to_string(&mut out).unwrap();
    out.split("\r\n\r\n").nth(1).unwrap_or_default().to_string()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_remote_pty_draws_a_screen_resizes_and_measures_latency() {
    let (remote, local, _serving) = paired().await;
    let t0 = Instant::now();
    let h = remote.spawn(shell_pty()).await.unwrap();
    let mut rx = remote.events(&h.id).await.unwrap();
    // First byte: the shell's banner.
    let first = loop {
        match tokio::time::timeout(Duration::from_secs(20), rx.recv()).await.expect("first byte") {
            Some(ProcEvent::Output(_)) => break t0.elapsed(),
            Some(_) => continue,
            None => panic!("closed"),
        }
    };

    // A screen the orchestrator reads with screen(): the shell echoes a marker it was typed.
    remote.write(&h.id, b"echo drawn-on-the-runner\r\n").await.unwrap();
    assert!(screen_has(&remote, &h.id, "drawn-on-the-runner", Duration::from_secs(20)).await, "{:?}", remote.screen(&h.id).await);
    let snap = remote.screen(&h.id).await.unwrap();
    assert_eq!(snap.size, Size { rows: 24, cols: 100 });
    assert!(remote.screen_text_since(&h.id, 0, 4096).await.unwrap().contains("drawn-on-the-runner"));

    // Resize: the runner's own terminal and the orchestrator's screen both change.
    remote.resize(&h.id, Size { rows: 30, cols: 120 }).await.unwrap();
    assert_eq!(remote.screen(&h.id).await.unwrap().size, Size { rows: 30, cols: 120 });
    assert_eq!(local.screen(&infantry_runner_api::ProcId(h.id.0.clone())).await.unwrap().size, Size { rows: 30, cols: 120 });

    // Steady latency: a typed line to its echo on the screen, through the link both ways.
    let mut ms = Vec::new();
    for i in 0..20 {
        let tag = format!("tick{i}x");
        let mark = remote.screen(&h.id).await.unwrap().total;
        let t = Instant::now();
        remote.write(&h.id, format!("{tag}\r\n").as_bytes()).await.unwrap();
        loop {
            if remote.screen_text_since(&h.id, mark, 4096).await.unwrap().contains(&tag) {
                break;
            }
            assert!(t.elapsed() < Duration::from_secs(10), "no echo of {tag}");
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
        ms.push(t.elapsed().as_millis());
    }
    ms.sort();
    println!("RR-5 latency: first byte {} ms, steady median {} ms, p90 {} ms (typed line to its echo on the orchestrator's screen)", first.as_millis(), ms[ms.len() / 2], ms[ms.len() * 9 / 10]);

    // Only a pty process started here has a screen or a size.
    assert!(matches!(remote.screen(&infantry_runner_api::ProcId("nope".into())).await, Err(RunnerError::Unsupported(_))));
    assert!(matches!(remote.resize(&infantry_runner_api::ProcId("nope".into()), Size { rows: 5, cols: 5 }).await, Err(RunnerError::Unsupported(_))));
    remote.kill(&h.id, infantry_runner_api::KillScope::Tree).await.unwrap();
    remote.release(&h.id).await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_hook_post_and_an_mcp_call_from_the_runner_arrive_at_the_orchestrator() {
    let (remote, _local, _serving) = paired().await;
    let mut hook = remote.hook_channel().await.unwrap();
    let mut mcp = remote.hook_channel().await.unwrap();
    assert_ne!(hook.url, mcp.url, "each channel is its own url (a session's own scope)");

    // The hook handler: the runner-side POST blocks until the orchestrator answers.
    let (hook_url, mcp_url) = (hook.url.clone(), mcp.url.clone());
    let a = tokio::task::spawn_blocking(move || post(&hook_url, r#"{"tool":"Bash","hook":"PreToolUse"}"#));
    let call = tokio::time::timeout(Duration::from_secs(20), hook.calls.recv()).await.expect("hook call").expect("open");
    assert!(call.body.contains("PreToolUse"));
    call.reply(r#"{"decision":"allow"}"#);
    assert_eq!(a.await.unwrap(), r#"{"decision":"allow"}"#);

    // The infantry MCP endpoint of the session: its own channel, its own answer.
    let b = tokio::task::spawn_blocking(move || post(&mcp_url, r#"{"jsonrpc":"2.0","method":"tools/list","id":1}"#));
    let call = tokio::time::timeout(Duration::from_secs(20), mcp.calls.recv()).await.expect("mcp call").expect("open");
    assert!(call.body.contains("tools/list"));
    call.reply(r#"{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"#);
    assert!(b.await.unwrap().contains(r#""tools":[]"#));

    // The command hook's events file is followed over the link, and only that file.
    let events = hook.events_file.clone();
    let mut lines = remote.tail(TailSpec { path: events.clone(), from: TailFrom::Start }).await.unwrap();
    std::fs::OpenOptions::new().create(true).append(true).open(&events).unwrap().write_all(b"stop\r\n").unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(20), lines.recv()).await.expect("event line"), Some("stop".into()));
    // Any file of the runner's host (a harness transcript): a line appended after the tail started is read.
    let transcript = std::env::temp_dir().join(format!("rr5-transcript-{}.jsonl", uuid::Uuid::new_v4()));
    std::fs::write(&transcript, b"").unwrap();
    let mut t = remote.tail(TailSpec { path: transcript.to_string_lossy().into_owned(), from: TailFrom::End }).await.unwrap();
    std::fs::OpenOptions::new().append(true).open(&transcript).unwrap().write_all(b"{\"type\":\"assistant\"}
").unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(20), t.recv()).await.expect("transcript line"), Some(r#"{"type":"assistant"}"#.into()));
    let _ = std::fs::remove_file(&transcript);

    drop(hook);
    drop(mcp);
}

