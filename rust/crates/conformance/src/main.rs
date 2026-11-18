// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! ```text
//! infantry-conformance <run|record|compare> --url ws://127.0.0.1:18952 [--token T] [--fixtures DIR] [--scenario NAME]
//! ```
//! `run` checks the contract only; `record` also writes the normalised frames as golden fixtures; `compare` diffs the
//! frames against the fixtures. Exit 0 = everything passed.
use infantry_conformance::{fixture, normalize::Normalizer, scenarios};
use std::path::PathBuf;
use std::time::Duration;

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let mode = a.first().cloned().unwrap_or_default();
    let arg = |k: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned();
    if !matches!(mode.as_str(), "run" | "record" | "compare") {
        eprintln!("usage: infantry-conformance <run|record|compare> --url URL [--token T] [--fixtures DIR] [--scenario NAME]");
        std::process::exit(2);
    }
    let Some(url) = arg("--url").or_else(|| std::env::var("INFANTRY_TEST_DAEMON").ok()) else {
        eprintln!("--url (or INFANTRY_TEST_DAEMON) is required");
        std::process::exit(2);
    };
    let dir = PathBuf::from(arg("--fixtures").unwrap_or_else(|| format!("{}/fixtures/kotlin", env!("CARGO_MANIFEST_DIR"))));
    let cfg = scenarios::Config { url, token: arg("--token"), timeout: Duration::from_secs(30) };
    let only = arg("--scenario");
    let mut failed = 0;
    for name in scenarios::NAMES.iter().filter(|n| only.as_deref().is_none_or(|o| o == **n)) {
        let r = scenarios::run(name, &cfg);
        let mut problems: Vec<String> = r.result.err().into_iter().collect();
        let norm = Normalizer::new().transcript(&r.frames);
        match mode.as_str() {
            "record" if problems.is_empty() => {
                if let Err(e) = fixture::write(&dir, scenarios::SUITE, name, &norm, &r.frames) {
                    problems.push(e);
                }
            }
            "compare" => match fixture::read(&dir, scenarios::SUITE, name) {
                Ok(exp) => problems.extend(fixture::diff(&exp, &norm)),
                Err(e) => problems.push(e),
            },
            _ => {}
        }
        if problems.is_empty() {
            println!("PASS {}/{name} ({} frames)", scenarios::SUITE, norm.len());
        } else {
            failed += 1;
            println!("FAIL {}/{name}", scenarios::SUITE);
            for p in problems {
                println!("  {p}");
            }
        }
    }
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
