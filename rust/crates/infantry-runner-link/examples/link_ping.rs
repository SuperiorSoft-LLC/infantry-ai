// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `link_ping --to ws://host:port [--code CODE | --bearer irb1...] [--state DIR] [--count N] [--record DIR] [--name NAME]`
//!
//! Pairs with the runner when the orchestrator store in `--state` holds no credential for that URL (a one-time code, or
//! the bearer text; `-` reads it from stdin), then pings: hello (contract check) + health, `--count` times, printing the
//! RTT. `--record DIR` writes every exchange (credentials blanked) as JSON fixtures. Secrets are never printed.

use infantry_runner_link::pairing::BearerToken;
use infantry_runner_link::{LinkClient, LinkStore};
use std::io::Read;
use std::path::PathBuf;

fn usage() -> ! {
    eprintln!("usage: link_ping --to ws://host:port [--code CODE | --bearer irb1...|-] [--state DIR] [--count N] [--record DIR] [--name NAME]");
    std::process::exit(2)
}

/// Blanks every credential-shaped value in a recorded frame (the pair code, the issued credential).
fn scrub(text: &str) -> String {
    let Ok(mut v) = serde_json::from_str::<serde_json::Value>(text) else { return text.to_string() };
    if let Some(data) = v.get("data").and_then(|d| d.as_str()).map(str::to_string) {
        if let Ok(mut inner) = serde_json::from_str::<serde_json::Value>(&data) {
            for k in ["code", "credential"] {
                if inner.get(k).is_some() {
                    inner[k] = serde_json::Value::String("<redacted>".into());
                }
            }
            for k in ["runnerId", "issuedAt"] {
                if let Some(x) = inner.get(k) {
                    inner[k] = if x.is_number() { serde_json::json!(0) } else { serde_json::Value::String("<id>".into()) };
                }
            }
            v["data"] = serde_json::Value::String(inner.to_string());
        }
    }
    if v.get("requestId").is_some() {
        v["requestId"] = serde_json::Value::String("<request-id>".into());
    }
    v.to_string()
}

fn main() {
    let (mut to, mut code, mut state, mut count, mut record, mut name) = (None, None, None, 3usize, None::<PathBuf>, "rust-orchestrator".to_string());
    let mut pin: Option<String> = None;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| usage());
        match a.as_str() {
            "--to" => to = Some(val()),
            "--code" => code = Some(val()),
            "--bearer" => {
                let mut t = val();
                if t == "-" {
                    t.clear();
                    std::io::stdin().read_to_string(&mut t).expect("stdin");
                }
                let token = BearerToken::parse(&t).unwrap_or_else(|e| { eprintln!("link_ping: {e}"); std::process::exit(2) });
                pin = token.fingerprint.clone();
                code = Some(token.secret);
            }
            "--state" => state = Some(PathBuf::from(val())),
            "--count" => count = val().parse().unwrap_or_else(|_| usage()),
            "--record" => record = Some(PathBuf::from(val())),
            "--name" => name = val(),
            _ => usage(),
        }
    }
    let Some(to) = to else { usage() };
    let state = state.unwrap_or_else(|| std::env::temp_dir().join("link-ping-orchestrator"));
    let store = LinkStore::open(&state.join("runner-link"));
    let me = store.self_id();
    let known = store.runners().into_iter().find(|r| r.url == to);
    let mut client = LinkClient::new(&to, &me, known.as_ref().map(|r| r.credential.clone())).allow_plain(std::env::var("INFANTRY_ALLOW_PLAIN").is_ok());
    if record.is_some() {
        client = client.recording();
    }
    let fail = |e: &dyn std::fmt::Display| -> ! {
        eprintln!("link_ping: {e}");
        std::process::exit(1)
    };
    if code.is_some() || known.is_none() {
        let Some(c) = code.as_deref() else { fail(&"not paired with this runner yet: pass --code or --bearer") };
        let r = client.pair(c, &name).unwrap_or_else(|e| fail(&e));
        println!("paired: runner '{}' ({}) contract {}", r.name, r.runner_id, r.contract);
        store.save_runner(r);
    }
    for i in 1..=count {
        match client.ping() {
            Ok(p) => println!(
                "pong {i}: runner '{}' ({}) contract {} os '{}' state {} running {} | rtt {:.1} ms (health {:.1} ms)",
                p.hello.runner_name,
                p.hello.runner_id,
                p.hello.contract,
                p.hello.os,
                p.health.state,
                p.health.running,
                p.rtt.as_secs_f64() * 1e3,
                p.health_rtt.as_secs_f64() * 1e3
            ),
            Err(e) => fail(&e),
        }
    }
    if let (Some(dir), Some(trace)) = (record, client.trace.as_ref()) {
        std::fs::create_dir_all(&dir).expect("record dir");
        let mut seen = std::collections::BTreeSet::new();
        for x in trace.lock().unwrap().iter() {
            let t: serde_json::Value = serde_json::from_str(&x.sent).unwrap();
            let ty = t["type"].as_str().unwrap_or("frame").to_string();
            if !seen.insert(ty.clone()) {
                continue;
            }
            let fixture = serde_json::json!({"request": scrub(&x.sent), "answer": scrub(&x.received)});
            std::fs::write(dir.join(format!("{ty}.json")), serde_json::to_string_pretty(&fixture).unwrap() + "\n").expect("write fixture");
        }
        println!("recorded {} frame types into {}", seen.len(), dir.display());
    }
}
