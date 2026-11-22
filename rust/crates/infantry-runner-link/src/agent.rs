// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The runner agent's main: `--port N [--host H] [--advertise H] [--state DIR] [--name NAME] [--rotate]`. It prints its
//! endpoint, its long-lived bearer and a one-time pairing code (the Kotlin runner's `INFANTRY RUNNER` block), then serves
//! the link until killed. Serves wss with its pinned self-signed certificate (the bearer carries the pin); `--plain` serves ws instead, the runner-side
//! `runners.allowPlainTailnet` (plain traffic can be read on the path: private networks only; the default host is 127.0.0.1).
//!
//! The backend is the caller's: the `infantry-runner-agent` bin serves ping/pong ([`crate::InfoOnly`]); a composition root
//! with a local [`infantry_runner_api::Runner`] passes a [`crate::process::ProcessBackend`] (examples/process_agent.rs).

use crate::pairing::{Bearer, BearerToken, PairCodes};
use crate::{serve, Backend, LinkServer, LinkStore};
use std::path::PathBuf;
use std::sync::Arc;

/// The one-time code's life and the redeem attempts per hour (Kotlin's `limits.runners.pairCodeMin` / `pairAttemptsPerHour`
/// defaults).
pub const PAIR_CODE_MS: i64 = 10 * 60 * 1000;
pub const PAIR_ATTEMPTS_PER_HOUR: usize = 10;

fn usage(program: &str) -> ! {
    eprintln!("usage: {program} --port N [--host 127.0.0.1] [--advertise HOST] [--state DIR] [--name NAME] [--rotate] [--plain]");
    std::process::exit(2)
}

/// Parses `args` (without the program name), serves, and never returns.
pub fn run(program: &str, args: impl IntoIterator<Item = String>, backend: Box<dyn Backend>) -> ! {
    let (mut port, mut host, mut advertise, mut state, mut name, mut rotate, mut plain) = (None::<u16>, "127.0.0.1".to_string(), None, None, None::<String>, false, false);
    let mut args = args.into_iter();
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| usage(program));
        match a.as_str() {
            "--port" => port = Some(val().parse().unwrap_or_else(|_| usage(program))),
            "--host" => host = val(),
            "--advertise" => advertise = Some(val()),
            "--state" => state = Some(PathBuf::from(val())),
            "--name" => name = Some(val()),
            "--rotate" => rotate = true,
            "--plain" => plain = true,
            _ => usage(program),
        }
    }
    let Some(port) = port else { usage(program) };
    let state = state.unwrap_or_else(|| std::env::current_dir().unwrap().join("infantry-runner-agent-state"));
    let dir = state.join("runner-link");
    let bearer = Bearer::of(&dir);
    let value = bearer.ensure(name.as_deref(), rotate).unwrap_or_else(|e| {
        eprintln!("{program}: {e}");
        std::process::exit(2)
    });
    let server = LinkServer::new(LinkStore::open(&dir), Some(PairCodes::new(PAIR_CODE_MS, PAIR_ATTEMPTS_PER_HOUR)), Some(bearer), backend, Some(dir.join("audit.jsonl")));
    let code = server.issue_code(&value.name).expect("codes");
    let paired = server.store().orchestrator().map(|o| o.name);
    let cert = if plain {
        None
    } else {
        Some(crate::RunnerCert::load_or_create(&dir).unwrap_or_else(|e| {
            eprintln!("{program}: {e}");
            std::process::exit(2)
        }))
    };
    let config = cert.as_ref().map(|c| {
        c.server_config().unwrap_or_else(|e| {
            eprintln!("{program}: {e}");
            std::process::exit(2)
        })
    });
    let serving = serve(Arc::new(server), &host, port, config).unwrap_or_else(|e| {
        eprintln!("{program}: cannot serve the runner link on {host}:{port}: {e}");
        std::process::exit(1)
    });
    let shown_host = advertise.unwrap_or_else(|| if host == "0.0.0.0" { "127.0.0.1".into() } else { host.clone() });
    let token = BearerToken { name: value.name.clone(), fingerprint: cert.as_ref().map(|c| c.fingerprint.clone()), secret: value.secret.clone() };
    println!("==== INFANTRY RUNNER (rust, contract {}) ====", crate::wire::CONTRACT);
    println!("endpoint: {}://{shown_host}:{}", if plain { "ws" } else { "wss" }, serving.port);
    println!("bearer:   {}", token.text());
    println!("code:     {code}  (one-time, {} min)", PAIR_CODE_MS / 60_000);
    if let Some(p) = paired {
        println!("last paired with: {p}");
    }
    println!("==== END INFANTRY RUNNER ====");
    eprintln!("[infantry] runner link: serving {} on {host}:{} (pid {})", if plain { "PLAIN ws (--plain)" } else { "wss" }, serving.port, std::process::id());
    loop {
        std::thread::park();
    }
}
