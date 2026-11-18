// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry-runner-agent --port N [--host H] [--advertise H] [--state DIR] [--name NAME] [--rotate]`: a Rust runner that
//! serves the runner link. It prints its endpoint, its long-lived bearer and a one-time pairing code, then serves until
//! killed. Plain ws:// for now: bind it to loopback or a private network (the default host is 127.0.0.1).

use infantry_runner_link::pairing::{Bearer, BearerToken, PairCodes};
use infantry_runner_link::{serve, Backend, LinkServer, LinkStore};
use std::path::PathBuf;
use std::sync::Arc;

const PAIR_CODE_MS: i64 = 10 * 60 * 1000;
const PAIR_ATTEMPTS_PER_HOUR: usize = 10;

fn usage() -> ! {
    eprintln!("usage: infantry-runner-agent --port N [--host 127.0.0.1] [--advertise HOST] [--state DIR] [--name NAME] [--rotate] [--processes]");
    std::process::exit(2)
}

fn main() {
    let mut port: Option<u16> = None;
    let mut host = "127.0.0.1".to_string();
    let mut advertise: Option<String> = None;
    let mut state: Option<PathBuf> = None;
    let mut name: Option<String> = None;
    let mut rotate = false;
    let mut processes = false;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| usage());
        match a.as_str() {
            "--port" => port = Some(val().parse().unwrap_or_else(|_| usage())),
            "--host" => host = val(),
            "--advertise" => advertise = Some(val()),
            "--state" => state = Some(PathBuf::from(val())),
            "--name" => name = Some(val()),
            "--rotate" => rotate = true,
            "--processes" => processes = true,
            "-h" | "--help" => usage(),
            _ => usage(),
        }
    }
    let Some(port) = port else { usage() };
    let state = state.unwrap_or_else(|| std::env::current_dir().unwrap().join("infantry-runner-agent-state"));
    let dir = state.join("runner-link");
    let bearer = Bearer::of(&dir);
    let value = bearer.ensure(name.as_deref(), rotate).unwrap_or_else(|e| {
        eprintln!("infantry-runner-agent: {e}");
        std::process::exit(2)
    });
    let backend: Box<dyn Backend> = if processes {
        Box::new(infantry_runner_link::process::ProcessBackend::local())
    } else {
        Box::new(infantry_runner_link::InfoOnly)
    };
    let server = LinkServer::new(LinkStore::open(&dir), Some(PairCodes::new(PAIR_CODE_MS, PAIR_ATTEMPTS_PER_HOUR)), Some(bearer), backend, Some(dir.join("audit.jsonl")));
    let code = server.issue_code(&value.name).expect("codes");
    let paired = server.store().orchestrator().map(|o| o.name);
    let serving = serve(Arc::new(server), &host, port).unwrap_or_else(|e| {
        eprintln!("infantry-runner-agent: cannot serve the runner link on {host}:{port}: {e}");
        std::process::exit(1)
    });
    let shown_host = advertise.unwrap_or_else(|| if host == "0.0.0.0" { "127.0.0.1".into() } else { host.clone() });
    let token = BearerToken { name: value.name.clone(), fingerprint: None, secret: value.secret.clone() };
    println!("==== INFANTRY RUNNER (rust, contract {}) ====", infantry_runner_link::wire::CONTRACT);
    println!("endpoint: ws://{shown_host}:{}", serving.port);
    println!("bearer:   {}", token.text());
    println!("code:     {code}  (one-time, {} min)", PAIR_CODE_MS / 60_000);
    if let Some(p) = paired {
        println!("last paired with: {p}");
    }
    println!("==== END INFANTRY RUNNER ====");
    eprintln!("[infantry] runner link: serving PLAIN ws on {host}:{} (pid {})", serving.port, std::process::id());
    loop {
        std::thread::park();
    }
}
